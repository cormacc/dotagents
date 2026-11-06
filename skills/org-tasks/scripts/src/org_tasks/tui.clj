(ns org-tasks.tui
  "Shared standalone and embedded terminal UI for `ot`.

  The interactive program is run through charm.clj so JLine owns raw-mode
  input, key decoding, window-size messages, rendering, and terminal cleanup.
  Standalone stdout holds one final org-tasks/v1 selected-task envelope;
  embedded mode emits no stdout payload. While Charm owns its system terminal,
  System/out is temporarily pointed at System/err so renderer control sequences
  go to the user's terminal stream rather than captured stdout."
  (:refer-clojure :exclude [run!])
  (:require [babashka.fs :as fs]
            [charm.message :as msg]
            [charm.program :as program]
            [charm.style.core :as style]
            [clojure.string :as str]
            [org-tasks.styling :as styling]
            [org-tasks.task :as task]
            [org-tasks.output :as out]
            [org-tasks.tui.dispatch :as dispatch]
            [org-tasks.tui.tasks :as tasks]
            [org-tasks.tui.embedding :as embedding]
            ;; Charm owns the terminal program loop; nexus owns event
            ;; dispatch. Each charm event is turned into nexus actions
            ;; (see `org-tasks.tui.dispatch`) that expand to effects against
            ;; a per-event store (see `update-fn`).
            [nexus.core :as nexus]))

(def ^:dynamic *interactive-terminal?*
  (fn [] (boolean (System/console))))

(defn interactive-terminal? [] (*interactive-terminal?*))

(defn- strip-ansi [s]
  (style/strip-ansi (str s)))

(defn- text-width [s]
  (style/string-width (strip-ansi s)))

(defn- crop [n s]
  (if (pos? n)
    (style/truncate (str s) n :tail (if (< n 4) "" "..."))
    ""))

(defn- pad-right [n s]
  (let [s (crop n s)
        w (text-width s)]
    (str s (apply str (repeat (max 0 (- n w)) " ")))))

(defn- blank-lines [n]
  (repeat (max 0 n) ""))

(defn- fit-lines [lines n]
  (take n (concat lines (blank-lines n))))

(defn- split-at-width [width s]
  (let [s (str s)
        width (max 1 width)]
    (loop [idx 0 last-good 0]
      (cond
        (>= idx (count s)) [s ""]
        (> (text-width (subs s 0 (inc idx))) width)
        (let [n (max 1 last-good)]
          [(subs s 0 n) (subs s n)])
        :else
        (recur (inc idx) (inc idx))))))

(defn- wrap-plain-line [width line]
  (let [line (strip-ansi line)
        width (max 1 width)]
    (loop [remaining line acc []]
      (if (<= (text-width remaining) width)
        (conj acc remaining)
        (let [[chunk rest-line] (split-at-width width remaining)]
          (recur rest-line (conj acc chunk)))))))

(defn- wrap-lines [width lines]
  (mapcat (fn [line]
            (let [parts (str/split-lines (str line))]
              (if (seq parts)
                (mapcat #(wrap-plain-line width %) parts)
                [""])))
          lines))

(defn- styled
  "Charm-backed styling for the TUI render path. The bling-backed
  `org-tasks.styling` namespace serves `--format text` CLI output; the two
  stacks are intentionally separate (different terminal backends) but share
  the colour definitions in `styling/palette-256`."
  [state text & opts]
  (if (:color? state true)
    (apply style/styled text opts)
    text))

(defn- palette-fg
  "Charm ANSI-256 colour for a `styling/palette-256` key."
  [k]
  (style/ansi256 (get styling/palette-256 k)))

(def ^:private accent-color (palette-fg :cyan))
(def ^:private selected-color (palette-fg :green))
(def ^:private local-color (palette-fg :magenta))
(def ^:private message-color (palette-fg :yellow))
(def dim-color (palette-fg :gray))

(def ^:private status-colors
  "Per-status charm colours derived from the shared CLI palette."
  (update-vals styling/status-palette palette-fg))

(def ^:private priority-colors
  "Per-priority charm colours derived from the shared CLI palette."
  (update-vals styling/priority-palette palette-fg))

(defn task-line [state task idx]
  (let [children? (some #(= (:id task) (:parentId %)) (:tasks state))
        cursor? (= idx (:cursor state))
        selected? (= (:id task) (:selected-id state))
        mark (if cursor? ">" " ")
        sel (if selected? "★" " ")
        local (if (:local task) "⊠" " ")
        coll (if children? (if (contains? (:expanded state) (:id task)) "▼" "▶") "•")
        ;; Indent by tree depth (parentId chain), not org :level: imported plan
        ;; children keep their plan-file level, which is shallower than their
        ;; TASKS.org parent, so :level would render them left of the parent.
        depth (or (get-in state [:depths (:id task)])
                  (max 0 (dec (:level task 1))))
        indent (apply str (repeat (* 2 depth) " "))
        issues (when (seq (:linkedIssues task)) (str " J" (count (:linkedIssues task))))
        prio (:priority task)
        prio-token (when prio (str "[#" prio "]"))
        prefix (format "%s%s%s %s%s " mark sel local indent coll)
        status-cell (format "%-9s" (:status task))
        id-cell (format " %-8s " (task/id-prefix (:id task)))
        tail (str (or (:summary task) "") (or issues ""))
        raw (str prefix status-cell id-cell
                 (when prio-token (str prio-token " ")) tail)]
    (cond
      cursor? (styled state raw :fg accent-color :bold true)
      selected? (styled state raw :fg selected-color :bold true)
      (:local task) (styled state raw :fg local-color)
      (#{"DONE" "CANCELLED"} (:status task)) (styled state raw :fg dim-color)
      ;; Plain rows colour the status and priority tokens exactly like
      ;; `ot list` does (shared palettes; see `styling/palette-256`).
      :else (str prefix
                 (if-let [color (status-colors (:status task))]
                   (styled state status-cell :fg color)
                   status-cell)
                 id-cell
                 (when prio-token
                   (str (if-let [color (priority-colors prio)]
                          (styled state prio-token :fg color)
                          prio-token)
                        " "))
                 tail))))

(defn detail-lines [task]
  (if task
    (remove nil?
            [(str (:status task) " " (or (:priority task) "") " " (:summary task))
             (str "id: " (:id task))
             (str "source: " (:sourcePath task) ":" (:line task))
             (when (:importPath task) (str "plan: " (or (:importRaw task) (:importPath task))))
             (when (seq (:linkedIssues task)) (str "issues: " (str/join ", " (:linkedIssues task))))
             (when (seq (:blockedBy task)) (str "blocked by: " (str/join ", " (:blockedBy task))))
             (when (:handoff task) (str "handoff: " (:handoff task)))
             ""
             (:description task)])
    ["No tasks found." "Press n to create a task or Esc to exit."]))

(def ^:private detail-meta-prefixes
  ["id:" "source:" "plan:" "issues:" "blocked by:" "handoff:"])

(defn- detail-meta-line? [line]
  (some #(str/starts-with? line %) detail-meta-prefixes))

(defn- detail-lines-styled [state lines]
  (let [[title & rest-lines] lines]
    (cons (styled state title :fg accent-color :bold true)
          (map (fn [line]
                 (if (detail-meta-line? line)
                   (styled state line :fg dim-color)
                   line))
               rest-lines))))

(defn- prompt-line [state]
  (when-let [{:keys [label input]} (:new-task state)]
    (styled state (str label ": " input "█") :fg message-color :bold true)))

(def ^:private footer
  "↑↓/jk cursor  ←→/hl status  ⇧←→ priority  Enter/Space/Tab collapse  s select  m move  D remove  e edit  p plan  n/N new  A archive  P publish  U unpublish  J issues  Esc/Alt-t quit")

(defn- view-dimensions [state]
  (let [width (max 1 (:width state 100))
        ;; Leave spare columns in the normal buffer/Display output so long
        ;; right-pane lines never hit the terminal's auto-wrap margin, even in
        ;; terminals whose reported width is slightly optimistic.
        content-w (max 1 (- width 4))
        pane-h (tasks/viewport-height state)
        ;; Stack the detail pane below the tree when the terminal is too
        ;; narrow for two useful columns, or in portrait orientation
        ;; (width < height), where vertical space is the abundant axis.
        ;; `tasks/stacked-layout?` owns the rule so scroll math agrees.
        stacked? (tasks/stacked-layout? state)
        tree-h (tasks/tree-pane-height state)
        gap-w 3
        tree-w (if stacked? content-w (max 40 (quot (- content-w gap-w) 2)))
        detail-w (if stacked? content-w (max 20 (- content-w tree-w gap-w)))]
    {:content-w content-w
     :pane-h pane-h
     :tree-h tree-h
     :stacked? stacked?
     :tree-w tree-w
     :detail-w detail-w}))

(defn- detail-view-lines [state detail-w]
  (->> (detail-lines (tasks/cursor-task state))
       (wrap-lines (max 1 detail-w))
       (detail-lines-styled state)
       (drop (:details-scroll state 0))))

(defn- tree-pane-lines [state tasks tree-h]
  (let [title (styled state "org-tasks" :fg accent-color :bold true)
        subtitle (styled state (str (count tasks) " visible tasks") :fg dim-color)
        visible-tree (->> tasks
                          (map-indexed #(task-line state %2 %1))
                          (drop (:tree-scroll state 0)))]
    (fit-lines (concat [title subtitle] visible-tree) tree-h)))

(defn- detail-pane-lines [state detail-lines pane-h]
  (fit-lines (concat [(styled state "Details" :fg accent-color :bold true)
                      (styled state "-------" :fg dim-color)]
                     detail-lines)
             pane-h))

(defn- render-wide [tree-w detail-w tree-lines detail-pane]
  (map (fn [l r]
         (str (pad-right tree-w l) " │ " (crop detail-w r)))
       tree-lines
       detail-pane))

(defn- render-stacked [state tree-w detail-w pane-h tree-lines detail-lines]
  (fit-lines (concat (map #(crop tree-w %) tree-lines)
                     ["" (styled state "Details" :fg accent-color :bold true)
                      (styled state "-------" :fg dim-color)]
                     (map #(crop detail-w %) detail-lines))
             pane-h))

(defn- context-notice [state]
  (or (:context-message state)
      (when (:pending-context state) "Project switch pending")))

(defn- status-line [state content-w]
  (let [msg-line (when-let [m (:message state)] (styled state m :fg message-color))]
    (crop content-w (str (when-let [notice (context-notice state)] (str notice " | "))
                         (or (prompt-line state) msg-line "")))))

(defn- move-picker-view [state]
  (let [{:keys [stage mode options cursor input]} (:move-task state)
        {:keys [content-w pane-h]} (view-dimensions state)
        section? (and (= stage :destination) (= mode :section))
        body-h (max 1 (dec pane-h))
        start (max 0 (- cursor (dec body-h)))
        lines (if section?
                [(str "Existing section name: " input "█")]
                (if (seq options)
                  (->> options
                       (map-indexed (fn [idx option]
                                      (str (if (= idx cursor) "> " "  ") (:label option))))
                       (drop start))
                  ["No same-file destinations. Esc cancels."]))]
    (str/join "\n"
              (map #(crop content-w %)
                   (concat [(styled state (str (when-let [notice (context-notice state)]
                                                   (str notice " | "))
                                             (if (= stage :relation) "Move: choose relation"
                                             (str "Move: choose " (name mode) " destination")))
                                    :fg accent-color :bold true)]
                           (fit-lines lines body-h)
                           ["" (if section?
                                 "Type section name  Enter confirm  Esc cancel"
                                 "↑↓/jk choose  Enter confirm  Esc cancel")])))))

(defn- home-shortened [path]
  (when path
    (let [home (System/getProperty "user.home")
          prefix (str (str/replace home #"/+$" "") "/")]
      (cond
        (= path home) "~"
        (str/starts-with? path prefix) (str "~/" (subs path (count prefix)))
        :else path))))

(defn- compact-progress [rows completed?]
  (str "(" (count (filter completed? rows)) "/" (count rows) ")"))

(defn- compact-selected-line [width label path]
  (if (and path (<= (+ (text-width label) 1 (text-width path)) width))
    (str (pad-right (- width (text-width path)) label) path)
    (crop width label)))

(defn compact-lines
  "Height-bounded selected-task context. Keep identity before ancestry, plan and
  descendants; pending descendants take space before completed history."
  [state]
  (let [rows (:tasks state)
        selected (first (filter #(= (:selected-id state) (:id %)) rows))
        ancestors (tasks/ancestor-ids rows (:selected-id state))
        parent (first (filter #(and (contains? ancestors (:id %))
                                   (not (contains? ancestors (:parentId %)))) rows))
        by-id (into {} (map (juxt :id identity)) rows)
        plan-owner (first (filter :importPath (take-while some?
                                                          (iterate #(get by-id (:parentId %)) selected))))
        plan (:importPath plan-owner)
        descendants (filter #(contains? (tasks/ancestor-ids rows (:id %))
                                         (:selected-id state)) rows)
        tree-nodes (tree-seq #(seq (concat (:children %) (:importChildren %)))
                             #(concat (:children %) (:importChildren %))
                             {:children (:task-tree state)})
        plan-node (first (filter #(= (:id plan-owner) (:id %)) tree-nodes))
        plan-tasks (task/flatten-tree (:importChildren plan-node))
        completed? #(#{"DONE" "CANCELLED"} (:status %))
        label (fn [t] (str (:summary t) " [" (task/id-prefix (:id t)) "] " (:status t)))
        lines (if selected
                (concat [(compact-selected-line (max 1 (:width state 100))
                                                (str "> " (label selected) " " (compact-progress descendants completed?))
                                                (home-shortened (:task-memory-path state)))]
                        (when parent [(str "Parent: " (:summary parent))])
                        (when plan [(str "Plan: " plan " " (compact-progress plan-tasks completed?))])
                        (map #(str "  " (label %))
                             (concat (remove completed? descendants)
                                     (filter completed? descendants))))
                [(if (:selected-id state)
                   (str "Stale selection: " (:selected-id state))
                   "No selected task")])
        lines (if-let [notice (context-notice state)]
                (cons notice lines) lines)]
    (take (max 1 (:height state 30)) lines)))

(defn- expanded-view [state]
  (let [{:keys [visible-tasks]} state
        {:keys [content-w pane-h tree-h stacked? tree-w detail-w]} (view-dimensions state)
        detail-lines (detail-view-lines state detail-w)
        tree-lines (tree-pane-lines state visible-tasks tree-h)
        detail-pane (detail-pane-lines state detail-lines pane-h)
        lines (if stacked?
                (render-stacked state tree-w detail-w pane-h tree-lines detail-lines)
                (render-wide tree-w detail-w tree-lines detail-pane))]
    (if (:move-task state)
      (move-picker-view state)
      (str (str/join "\n" lines)
           "\n"
           (status-line state content-w)
           "\n"
           (styled state (crop content-w footer) :fg dim-color)))))

(defn- small-view [state]
  (let [cursor (tasks/cursor-task state)
        picker (:move-task state)
        option (when picker (nth (:options picker) (:cursor picker) nil))]
    (str/join "\n"
              (remove nil?
                      [(context-notice state)
                       (or (prompt-line state)
                           (when picker
                             (if (= :section (:mode picker))
                               (str "Section: " (:input picker) "█")
                               (str "Move: " (:label option))))
                           (when cursor (str (:summary cursor) " [" (task/id-prefix (:id cursor)) "]"))
                           "No tasks")
                       (:message state)
                       "j/k move  Enter choose  Esc cancel/quit"]))))

(defn view [state]
  (let [width (max 1 (:width state 100))
        height (max 1 (:height state 30))
        rendered (cond
                   (and (:context-status state) (not= :ready (:context-status state))
                        (not (embedding/modal? state)))
                   (str (context-notice state) "\nTask mutations disabled")
                   (= "compact" (:mode state)) (str/join "\n" (compact-lines state))
                   (or (< height 7) (< width 40)) (small-view state)
                   :else (expanded-view state))]
    (->> (str/split-lines rendered)
         (take height)
         (map #(crop width %))
         (str/join "\n"))))

(defn- key-text [m]
  (let [k (:key m)]
    (cond
      (string? k) k
      (keyword? k) (name k)
      :else (str k))))

(defn- prompt-msg->actions [m]
  (cond
    (or (msg/key-match? m :escape) (msg/key-match? m "ctrl+c")) [[:new-task/cancel]]
    (msg/key-match? m :enter) [[:new-task/submit]]
    (or (msg/key-match? m :backspace) (msg/key-match? m :delete)) [[:new-task/backspace]]
    :else (let [s (key-text m)]
            (if (= 1 (count s)) [[:new-task/input s]] []))))

(defn- move-msg->actions [state m]
  (let [section? (= :section (get-in state [:move-task :mode]))]
    (cond
      (or (msg/key-match? m :escape) (msg/key-match? m "ctrl+c")
          (msg/key-match? m "alt+t")) [[:move-task/cancel]]
      (msg/key-match? m :enter) [[:move-task/submit]]
      (and section? (or (msg/key-match? m :backspace) (msg/key-match? m :delete)))
      [[:move-task/backspace]]
      section? (let [s (key-text m)]
                 (if (= 1 (count s)) [[:move-task/input s]] []))
      (or (msg/key-match? m :down) (msg/key-match? m "j")) [[:move-task/cursor 1]]
      (or (msg/key-match? m :up) (msg/key-match? m "k")) [[:move-task/cursor -1]]
      :else [])))

(defn- key-msg->actions [m]
  (cond
    (or (msg/key-match? m :escape) (msg/key-match? m "alt+t") (msg/key-match? m "ctrl+c")) [[:tui/quit]]
    (or (msg/key-match? m "j") (msg/key-match? m :down)) [[:tree/move-cursor 1]]
    (or (msg/key-match? m "k") (msg/key-match? m :up)) [[:tree/move-cursor -1]]
    (or (msg/key-match? m :tab) (msg/key-match? m :enter) (msg/key-match? m " ")) [[:tree/toggle-expansion]]
    (msg/key-match? m "ctrl+d") [[:task-details/scroll 5]]
    (msg/key-match? m "ctrl+u") [[:task-details/scroll -5]]
    ;; Shift+arrow checks must precede the bare :left/:right keyword
    ;; matches: keyword key-match? ignores modifiers, so :right would
    ;; also swallow shift+right.
    (msg/key-match? m "shift+right") [[:task/cycle-priority 1]]
    (msg/key-match? m "shift+left") [[:task/cycle-priority -1]]
    (or (msg/key-match? m "l") (msg/key-match? m :right)) [[:task/cycle-status 1]]
    (or (msg/key-match? m "h") (msg/key-match? m :left)) [[:task/cycle-status -1]]
    (msg/key-match? m "s") [[:task/select]]
    (msg/key-match? m "m") [[:move-task/start]]
    (msg/key-match? m "D") [[:task/remove]]
    (msg/key-match? m "A") [[:task/archive]]
    (msg/key-match? m "P") [[:task/publish]]
    (msg/key-match? m "U") [[:task/unpublish]]
    (msg/key-match? m "n") [[:new-task/start :sibling]]
    (msg/key-match? m "N") [[:new-task/start :child]]
    (msg/key-match? m "e") [[:task/edit]]
    (msg/key-match? m "p") [[:task/plan]]
    (msg/key-match? m "J") [[:task/open-issues]]
    :else []))

(defn msg->actions
  "Translate a charm message into a (possibly empty) vector of nexus actions."
  [state m]
  (cond
    (msg/window-size? m) [[:tui/resize (:width m) (:height m)]]
    (= "compact" (:mode state))
    (if (and (msg/key-press? m)
             (or (msg/key-match? m :escape) (msg/key-match? m "alt+t")
                 (msg/key-match? m "ctrl+c")))
      [[:tui/quit]] [])
    (and (:move-task state) (msg/key-press? m)) (move-msg->actions state m)
    (:new-task state)    (prompt-msg->actions m)
    (msg/key-press? m)   (key-msg->actions m)
    :else                []))

(defn- poll-cmd []
  (program/cmd (fn []
                 (Thread/sleep embedding/poll-ms)
                 {:type ::poll})))

(defn update-fn
  "charm `update` bridge: turn the message into nexus actions, dispatch them
  against a per-event store atom, and hand the resulting state (plus an
  optional quit command) back to charm."
  [opts state m]
  (let [poll? (= ::poll (:type m))
        state (embedding/sync-context opts state poll?)
        actions* (if (and (:context-file opts)
                          (not= :ready (:context-status state))
                          (msg/key-press? m)
                          (not (or (msg/key-match? m :escape)
                                   (msg/key-match? m "ctrl+c"))))
                   []
                   (if (and (:context-file opts) (:remove-confirmation state)
                            (msg/key-match? m :escape))
                     [[:state/assoc :remove-confirmation nil]]
                     (msg->actions state m)))]
    (if (seq actions*)
      (let [store (atom state)]
        (nexus/dispatch dispatch/nexus {:!store store} {:opts opts} actions*)
        (let [next-state @store]
          (if (get next-state dispatch/quit-marker)
            [(dissoc next-state dispatch/quit-marker) program/quit-cmd]
            [(embedding/sync-context opts next-state false) nil])))
      [state (when poll? (poll-cmd))])))

(defn- run-program! [opts state0]
  (let [original-out System/out]
    (try
      ;; charm.terminal/create-terminal uses JLine's system terminal. Route its
      ;; writer to stderr while the TUI owns the screen so stdout remains clean
      ;; for the final selected-task JSON emitted after program/run returns.
      (System/setOut System/err)
      (program/run {:init (fn [] [state0 (poll-cmd)])
                    :update (partial update-fn opts)
                    :view view
                    :alt-screen true
                    :hide-cursor true
                    :fps 60})
      (finally
        (System/setOut original-out)))))

(defn run! [opts]
  (let [state0 (-> (tasks/initial-state (if (:context-file opts) {:rows []}
                                         (tasks/load-tasks opts)))
                   (assoc :color? (not (:no-color opts)) :mode (:mode opts "expanded")))
        state0 (embedding/sync-context opts state0 true)]
    (run-program! opts state0)
    (when-not (:context-file opts)
      (tasks/selected-json! opts))))

(defn command! [{:keys [opts]}]
  (if (or (and (:context-file opts) (not (:instance opts)))
          (and (:instance opts) (not (:context-file opts)))
          (and (:context-file opts)
               (or (not (fs/absolute? (:context-file opts)))
                   (some #(contains? opts %) [:root :tasks :archive]))))
    (out/emit-error
     opts {:code "argument-error"
           :message "Embedding requires absolute --context-file and --instance, without root/file selectors"} 2)
    (run! (cond-> opts (:mode opts) (update :mode name)))))

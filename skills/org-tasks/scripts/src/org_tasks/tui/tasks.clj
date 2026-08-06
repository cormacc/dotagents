(ns org-tasks.tui.tasks
  "Task model and command bridge for the standalone TUI.

  Owns two concerns the view/dispatch layers build on:

  1. Command bridge — in-process invocation of command-family
     handlers (`call-command`, `load-tasks`, `selected-json!`,
     `error-message`, `call-result`). Envelopes travel as data: the
     success envelope is `output/emit-result`'s return value and the
     error envelope arrives through `output/*exit-fn*` — nothing is
     re-parsed from printed JSON.
  2. Tree/state model — pure functions over the wire-shaped task rows
     returned by `ot list --format json` (camelCase keys such as
     `:parentId`; contrast with `org-tasks.task`, which operates on the
     internal kebab-case tree). Includes visibility under the
     `:expanded` set, cursor addressing, and post-mutation reload.

  Charm-free and nexus-free: safe to require from tests and from the
  dispatch layer without dragging in the terminal stack."
  (:require [org-tasks.commands.list-show :as list-show]
            [org-tasks.output :as out]))

;; ── Command bridge ─────────────────────────────────────────────────

(defn call-command
  "Invoke command handler `f` in-process, suppressing its printed output.

  Returns `{:exit int :envelope map-or-nil}`. On success the envelope is
  the handler's return value (every command ends with `output/emit-result`,
  which returns the public envelope); on failure it is the error envelope
  `output/emit-error` hands to `output/*exit-fn*`."
  [f opts]
  (let [exit (atom 0)
        envelope (atom nil)]
    (binding [*out* (java.io.StringWriter.)
              *err* (java.io.StringWriter.)
              out/*exit-fn* (fn [code & [env]]
                              (reset! exit code)
                              (reset! envelope env)
                              (throw (ex-info "ot command exit" {:tag ::exit :code code})))]
      (try
        (let [ret (f {:opts (assoc opts :format :json)})]
          (when (map? ret) (reset! envelope ret)))
        (catch clojure.lang.ExceptionInfo e
          (when-not (= ::exit (:tag (ex-data e)))
            (throw e)))))
    {:exit @exit :envelope @envelope}))

(defn selected-json! [opts]
  (list-show/selected-cmd {:opts (assoc opts :format :json)}))

(defn load-tasks [opts]
  (let [{:keys [exit envelope]} (call-command list-show/list-cmd opts)]
    (if (zero? exit)
      (:result envelope)
      (throw (ex-info (get-in envelope [:error :message] "Unable to load tasks")
                      (or envelope {}))))))

(defn error-message
  "Human-readable message from an error envelope (nil-safe): the
  envelope's `[:error :message]`, falling back to a generic message."
  [envelope]
  (or (get-in envelope [:error :message]) "Command failed."))

(defn call-result
  "Run command handler `f` and reduce the outcome to
  `{:ok? true :result ...}` or `{:ok? false :message ...}`."
  [f opts]
  (let [{:keys [exit envelope]} (call-command f opts)]
    (if (zero? exit)
      {:ok? true :result (:result envelope)}
      {:ok? false :message (error-message envelope)})))

(defn removal-impact-message
  "Concise standalone-TUI confirmation text for an `ot remove` dry-run
  result. The actual preview and write remain in the command handler."
  [{:keys [subtree uncheckedCriteria inboundBlockers]}]
  (str "Remove armed: subtree " (count subtree)
       ", unchecked criteria " (count uncheckedCriteria)
       ", inbound blockers " (count inboundBlockers)
       "; inbound blockers will be pruned. Press D again to remove."))

;; ── Tree / state model ─────────────────────────────────────────────

(defn- tasks-by-id [tasks]
  (into {} (map (juxt :id identity) tasks)))

(defn task-index-by-id [tasks id]
  (first (keep-indexed #(when (= id (:id %2)) %1) tasks)))

(defn ancestor-ids [tasks id]
  (let [by-id (tasks-by-id tasks)]
    (loop [acc #{} pid (:parentId (get by-id id))]
      (if pid
        (recur (conj acc pid) (:parentId (get by-id pid)))
        acc))))

(defn task-depths
  "Map of task id -> nesting depth derived from the `:parentId` chain. Used for
  indentation: org heading `:level` is unreliable for imported plan children
  (they keep their plan-file level, which is shallower than their parent), but
  `:parentId` always reflects true nesting."
  [tasks]
  (let [by-id (tasks-by-id tasks)]
    (reduce (fn [m task]
              (assoc m (:id task)
                     (loop [d 0 pid (:parentId task)]
                       (if (and pid (contains? by-id pid))
                         (recur (inc d) (:parentId (get by-id pid)))
                         d))))
            {} tasks)))

(defn visible-tasks
  "Compute the tasks visible under the current `:expanded` set, in render
  order. The result is cached in state under `:visible-tasks` (see
  `with-visible-tasks` and the `:tree/refresh` action); consumers destructure
  the cache from state rather than recomputing."
  [{:keys [tasks expanded]}]
  (let [by-id (tasks-by-id tasks)
        parent-ids (fn [task]
                     (->> (:parentId task)
                          (iterate #(some-> (get by-id %) :parentId))
                          (take-while some?)))
        hidden-parent? (fn [task visible-ids]
                         (some #(and (not (contains? expanded %))
                                     (contains? visible-ids %))
                               (parent-ids task)))]
    (loop [remaining tasks visible [] visible-ids #{}]
      (if-let [task (first remaining)]
        (if (hidden-parent? task visible-ids)
          (recur (rest remaining) visible visible-ids)
          (recur (rest remaining) (conj visible task) (conj visible-ids (:id task))))
        visible))))

(defn with-visible-tasks [state]
  (assoc state :visible-tasks (visible-tasks state)))

(defn initial-state [list-result]
  (let [tasks (:rows list-result)
        selected-id (:selectedId list-result)
        state (with-visible-tasks
                {:tasks tasks
                 :depths (task-depths tasks)
                 :selected-id selected-id
                 :expanded (ancestor-ids tasks selected-id)
                 :details-scroll 0
                 :message nil
                 :width 100
                 :height 30
                 :tree-scroll 0
                 :color? true
                 :new-task nil
                 :remove-confirmation nil})]
    ;; The cursor indexes *visible* tasks; the selected task is always visible
    ;; because its ancestors start expanded.
    (assoc state :cursor
           (max 0 (or (task-index-by-id (:visible-tasks state) selected-id) 0)))))

(defn viewport-height
  "Rows available to the tree/detail panes for the state's `:height`.
  Shared by the view layer and the `:tree/scroll-to-cursor` action so
  scroll math and rendering agree on pane geometry."
  [state]
  ;; Reserve footer plus one status/prompt line; keep enough room for headers.
  (max 3 (- (max 5 (:height state 30)) 3)))

(defn stacked-layout?
  "True when the detail pane renders below the tree instead of beside it:
  narrow terminals (width < 80) or portrait orientation. Orientation is
  judged on physical proportions, not raw cell counts: terminal cells are
  roughly twice as tall as wide, so a portrait screen yields a near-square
  cell grid (e.g. 112x110) rather than width < height. Width is compared
  against height x 2 to correct for that cell aspect."
  [state]
  (let [width (max 20 (:width state 100))
        height (max 5 (:height state 30))]
    (or (< width 80) (< width (* 2 height)))))

(defn tree-pane-height
  "Rows available to the tree pane. Stacked layouts give the tree the
  upper half of the viewport (details take the rest); side-by-side
  layouts give it the full viewport. Shared by the view layer and
  `:tree/scroll-to-cursor` so scrolling matches the rendered pane."
  [state]
  (let [vh (viewport-height state)]
    (if (stacked-layout? state)
      (max 3 (quot vh 2))
      vh)))

(defn cursor-task [{:keys [cursor visible-tasks]}]
  (nth visible-tasks (or cursor 0) nil))

(defn cursor-id [state]
  (:id (cursor-task state)))

(defn reload-state
  "Reload tasks from disk after a mutation, preserving the operator's view.
  Collapse state, scroll, message, and dimensions carry over untouched;
  `:visible-tasks` is recomputed; and the cursor is re-anchored to the task it
  was on, falling back to the previous cursor index when that task no longer
  exists (e.g. after archive). The cursor is an index into *visible* tasks, so
  it must never snap to the freshly loaded selection's index in the full list.
  Clamping and scroll follow-up belong to the `:tree/reload` action, which
  chains `:tree/clamp-cursor` and `:tree/scroll-to-cursor` after this reload."
  [opts state]
  (let [prev-id (:id (cursor-task state))
        result (load-tasks opts)
        tasks (:rows result)
        state (-> state
                  (assoc :tasks tasks
                         :depths (task-depths tasks)
                         :selected-id (:selectedId result)
                         :details-scroll 0
                         :remove-confirmation nil)
                  with-visible-tasks)
        idx (task-index-by-id (:visible-tasks state) prev-id)]
    (assoc state :cursor (or idx (:cursor state 0)))))

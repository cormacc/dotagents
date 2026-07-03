(ns org-tasks.tui.dispatch
  "Nexus actions and effects for the standalone TUI.

  Actions are pure: they receive an immutable state snapshot and return a
  collection of actions/effects. Effects perform the side-effects against the
  store atom, and nexus refreshes the state snapshot after each effect, so a
  later action in the same batch (e.g. [:tree/refresh] after [:state/assoc])
  expands against the updated state. Durable mutations run through the
  :command/run effect, which dispatches the :state/reload effect on success
  to reload tasks from disk and re-anchor the view. This keeps key handling
  declarative and testable without a bespoke dispatcher. `command-fns` (derived
  from the shared command registry) maps action keywords to the durable `ot`
  command handlers so action vectors stay pure data.

  Charm-free by design: the terminal loop lives in `org-tasks.tui`, which
  translates charm messages into the action vectors dispatched here."
  (:require [babashka.process]
            [clojure.string :as str]
            [org-tasks.commands.list-show :as list-show]
            [org-tasks.commands.registry :as registry]
            [org-tasks.editor :as editor]
            [org-tasks.tui.tasks :as tasks]))

(def quit-marker
  "State key set by the `:tui/quit` effect and consumed (and removed) by the
  charm bridge in `org-tasks.tui/update-fn`."
  ::quit)

(defn open-url! [url]
  (let [cmd (if (= "Mac OS X" (System/getProperty "os.name")) "open" "xdg-open")]
    (babashka.process/shell {:continue true :out :inherit :err :inherit} cmd url)))

(defn- prompt-input [state]
  (get-in state [:new-task :input] ""))

(defn- backspace [s]
  (subs s 0 (max 0 (dec (count s)))))

(def command-fns
  "Keyword → durable `ot` command handler, derived from the shared
  command registry (entries carrying a `:tui-key`)."
  registry/tui-command-fns)

(defn create-opts
  "Build `ot create` options for a new task. Placement policy lives in `ot`:
   with a cursor task we hand it `--relative-to <id> --as sibling|child` and
   let it derive parent/after/local/source; with no cursor task we create a
   top-level task."
  [kind task summary]
  (merge {:summary summary}
         (when task
           {:relative-to (:id task)
            :as (if (= kind :child) :child :sibling)})))

(defn- cmd-run [cmd-key cmd-opts ok-message]
  [[:command/run cmd-key cmd-opts ok-message]])

(defn- cmd-run-for-cursor [state cmd-key cmd-opts ok-message]
  (when-let [id (tasks/cursor-id state)]
    (cmd-run cmd-key (assoc cmd-opts :id id) ok-message)))

(defn- prompt-create-opts [state]
  (let [{:keys [kind input]} (:new-task state)
        summary (str/trim input)]
    (when-not (str/blank? summary)
      (create-opts kind (tasks/cursor-task state) summary))))

(def nexus
  {:nexus/system->state (comp deref :!store)

   :nexus/effects
   {:state/assoc     (fn [_ {:keys [!store]} & args] (apply swap! (into [!store assoc] args)))
    :state/assoc-in  (fn [_ {:keys [!store]} path value] (swap! !store assoc-in path value))
    :tui/quit        (fn [_ {:keys [!store]}] (swap! !store assoc quit-marker true))

    :command/run
    (fn [{:keys [dispatch] {:keys [opts]} :dispatch-data} _system cmd-key cmd-opts ok-message]
      (let [{:keys [exit envelope]} (tasks/call-command (command-fns cmd-key) (merge opts cmd-opts))]
        (dispatch
         (if (zero? exit)
           [[:state/assoc :message ok-message]
            [:tree/reload]]
           [[:state/assoc :message (tasks/error-message envelope)]]))))

    :state/reload
    (fn [{{:keys [opts]} :dispatch-data} {:keys [!store]}]
      (reset! !store (tasks/reload-state opts @!store)))

    :editor/open
    (fn [{{:keys [opts]} :dispatch-data} {:keys [!store]} path line message]
      (swap! !store assoc :message (or (editor/open! opts path line) message)))

    :issues/open-linked
    (fn [{{:keys [opts]} :dispatch-data} {:keys [!store]} id]
      (let [{:keys [ok? result message]} (tasks/call-result (command-fns :issue-urls) (merge opts {:id id}))]
        (if ok?
          (let [urls (:urls result)]
            (doseq [u urls] (open-url! u))
            (swap! !store assoc :message (str "Opened " (count urls) " linked issue URL(s).")))
          (swap! !store assoc :message message))))

    :plan/open
    ;; Resolve the task's `:import-path` (which may be a typed link
    ;; such as `plan:foo.org`) to an absolute path the same way `ot
    ;; show` does, rather than handing the raw link token to the
    ;; editor as a literal path.
    (fn [{{:keys [opts]} :dispatch-data} {:keys [!store]} id]
      (let [{:keys [ok? result message]} (tasks/call-result list-show/show-cmd (merge opts {:id id}))]
        (if ok?
          (if-let [abs (get-in result [:record :path])]
            (swap! !store assoc :message (or (editor/open! opts abs 1) "Plan editor launched."))
            (swap! !store assoc :message "No change-record found for this task."))
          (swap! !store assoc :message message))))}

   :nexus/actions
   {:tui/resize
    (fn [_state w h]
      [[:state/assoc :width w :height h]
       [:tree/scroll-to-cursor]])

    :tree/reload
    (fn [_state]
      [[:state/reload]
       [:tree/clamp-cursor]
       [:tree/scroll-to-cursor]])

    :tree/move-cursor
    (fn [{:keys [cursor]} delta]
      [[:state/assoc :cursor (+ (or cursor 0) delta) :details-scroll 0]
       [:tree/clamp-cursor]
       [:tree/scroll-to-cursor]])

    :tree/toggle-expansion
    (fn [{:keys [expanded] :as state}]
      (when-let [id (tasks/cursor-id state)]
        [[:state/assoc :expanded (if (contains? expanded id)
                                   (disj expanded id)
                                   (conj expanded id))]
         [:tree/refresh]]))

    :tree/refresh
    (fn [state]
      [[:state/assoc :visible-tasks (tasks/visible-tasks state)]])

    :tree/clamp-cursor
    (fn [{:keys [cursor visible-tasks]}]
      (let [n (count visible-tasks)
            new-cursor (if (pos? n) (min (max 0 cursor) (dec n)) 0)]
        [[:state/assoc :cursor new-cursor]]))

    :tree/scroll-to-cursor
    (fn [{:keys [cursor tree-scroll] :as state}]
      (let [body-h (max 1 (- (tasks/tree-pane-height state) 2))
            cursor (or cursor 0)
            scroll (or tree-scroll 0)]
        [[:state/assoc :tree-scroll
          (cond
            (< cursor scroll) cursor
            (>= cursor (+ scroll body-h)) (max 0 (- cursor body-h -1))
            :else scroll)]]))

    :task-details/scroll
    (fn [{:keys [details-scroll]} delta]
      [[:state/assoc :details-scroll (max 0 (+ (or details-scroll 0) delta))]])

    :task/cycle-status
    (fn [state delta]
      (when-let [id (tasks/cursor-id state)]
        (cmd-run :status {:id id :cycle (if (neg? delta) :back :forward)} "Status updated.")))

    :task/cycle-priority
    (fn [state delta]
      (when-let [id (tasks/cursor-id state)]
        (cmd-run :priority {:id id :cycle (if (neg? delta) :back :forward)} "Priority updated.")))

    :task/select
    (fn [{:keys [selected-id] :as state}]
      (when-let [id (tasks/cursor-id state)]
        (cmd-run :select (if (= id selected-id) {:clear true} {:id id}) "Selection updated.")))

    :task/archive
    (fn [state]
      (cmd-run-for-cursor state :archive {:yes true} "Archived."))

    :task/publish
    (fn [state]
      (cmd-run-for-cursor state :publish {} "Published."))

    :task/unpublish
    (fn [state]
      (cmd-run-for-cursor state :unpublish {} "Unpublished."))

    :task/edit
    (fn [state]
      (when-let [{:keys [id sourcePath line]} (tasks/cursor-task state)]
        (when id
          [[:editor/open sourcePath line "Editor launched."]])))

    :task/plan
    (fn [state]
      (when-let [{:keys [id importPath]} (tasks/cursor-task state)]
        (when id
          (if importPath
            [[:plan/open id]]
            (cmd-run :record-create {:id id} "Plan created.")))))

    :task/open-issues
    (fn [state]
      (when-let [id (tasks/cursor-id state)]
        [[:issues/open-linked id]]))

    :new-task/start
    (fn [state kind]
     ;; Mirror the overlay: when there is no cursor task (empty list), n/N still
     ;; open a prompt and create a top-level task.
      (let [top?  (nil? (tasks/cursor-id state))
            label (cond
                    top?            "New task title"
                    (= kind :child) "New child task"
                    :else           "New sibling task")]
        [[:state/assoc :new-task {:kind kind :label label :input ""}
          :message nil]]))

    :new-task/cancel
    (fn [_] [[:state/assoc :new-task nil :message "Create cancelled."]])

    :new-task/backspace
    (fn [state]
      [[:state/assoc-in [:new-task :input] (backspace (prompt-input state))]])

    :new-task/input
    (fn [state s]
      [[:state/assoc-in [:new-task :input] (str (prompt-input state) s)]])

    :new-task/submit
    (fn [state]
      (if-let [opts (prompt-create-opts state)]
        (into [[:state/assoc :new-task nil]]
              (cmd-run :create opts "Task created."))
        [[:state/assoc :new-task nil]]))}})

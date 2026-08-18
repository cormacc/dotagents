(ns org-tasks.commands.status
  "`ot` status command handler (task lifecycle transitions)."
  (:require [clojure.string :as str]
            [org-tasks.lifecycle :as lifecycle]
            [org-tasks.loader :as loader]
            [org-tasks.output :as out]
            [org-tasks.parser :as parser]
            [org-tasks.styling :as style]
            [org-tasks.task :as task]
            [org-tasks.tree :as tree]
            [org-tasks.commands.util :refer [positional-arg load-context resolve-context
                                             resolve-required-id]]))

(def ^:private valid-statuses
  (set lifecycle/status-cycle))

(defn- find-path-to-id
  "Return the path of tasks from a top-level root to the task with
  :CUSTOM_ID: = `id` (inclusive). Walks both `:children` and
  `:import-children` so that auto-promotion crosses `#+IMPORT:`
  boundaries (e.g. a STARTED transition on a plan-file subtask
  promotes its TASKS.org parent)."
  [tasks id]
  (letfn [(walk [ts trail]
            (some
              (fn [t]
                (let [trail' (conj trail t)]
                  (if (= id (parser/get-task-id t))
                    trail'
                    (or (walk (:children t) trail')
                        (walk (:import-children t) trail')))))
              ts))]
    (walk tasks [])))

(defn- apply-status-cmd
  "Apply a resolved status transition to `target` and emit the envelope.
  Shared by explicit `ot status <id> <new-status>` and `--cycle`."
  [opts tasks target status]
  (let [full-id (parser/get-task-id target)
        {updated :task :keys [prev-status timestamp]}
        (lifecycle/apply-status-transition target status)
        tree-after (tree/update-by-id tasks full-id (constantly updated))
        ;; Parent auto-promotion: when a subtask goes STARTED, walk
        ;; ancestors and promote any TODO ancestor to STARTED.
        [tree-final promotions]
        (if (= status "STARTED")
          (let [path (find-path-to-id tree-after full-id)
                ancestors (vec (butlast path))]
            (reduce
              (fn [[tree-acc promoted] ancestor]
                (if (and (= "TODO" (:status ancestor))
                         (parser/get-task-id ancestor))
                  (let [{a-updated :task :keys [prev-status]}
                        (lifecycle/apply-status-transition ancestor "STARTED" timestamp)
                        promotion {:id (parser/get-task-id ancestor)
                                   :prevStatus prev-status
                                   :status "STARTED"}]
                    [(tree/update-by-id tree-acc (parser/get-task-id ancestor)
                                        (constantly a-updated))
                     (conj promoted promotion)])
                  [tree-acc promoted]))
              [tree-after []]
              ancestors))
          [tree-after []])]
    (when-not (:dry-run opts)
      (loader/save-source-roots-locality (:project-root (resolve-context opts))
                                         tree-final))
    (out/emit-result
      opts
      {:task (task/task->wire (task/find-by-id tree-final full-id) nil
                              {:include-content? (boolean (:include-content opts))})
       :prevStatus prev-status
       :status status
       :closed (:closed updated)
       :started (parser/get-task-started updated)
       :promoted promotions
       :text/lines
       [(str (:summary updated) ": "
             (style/status opts prev-status prev-status) " → "
             (style/status opts status status)
             (when (:closed updated) (str " (closed " (:closed updated) ")")))
        (when (seq promotions)
          (str "Promoted ancestors: "
               (str/join ", " (map :id promotions))))]}))
)

(def ^:private valid-priorities #{"A" "B" "C" "D"})

(defn priority-cmd
  "Set, cycle, or clear a task's priority cookie. Cycle order (including
  the unset slot) lives in `lifecycle/priority-cycle`: forward from unset
  is A (highest), back from unset is D (lowest)."
  [{:keys [opts] :as result}]
  (let [{:keys [tasks]} (load-context opts)
        id        (positional-arg result :id)
        cycle-dir (:cycle opts)
        clear?    (:clear opts)
        explicit  (some-> (positional-arg result :level 1) str/upper-case)]
    (cond
      (nil? id)
      (out/emit-error opts
                      {:code "argument-error"
                       :message "ot priority requires <id> and either <level>, --cycle, or --clear."})

      (and (nil? explicit) (nil? cycle-dir) (not clear?))
      (out/emit-error opts
                      {:code "argument-error"
                       :message "ot priority requires <id> and either <level>, --cycle, or --clear."})

      (and explicit (not (valid-priorities explicit)))
      (out/emit-error opts
                      {:code "invalid-priority"
                       :message "Priority must be one of A, B, C, D (or --clear)."
                       :details {:value explicit}})

      :else
      (let [target (resolve-required-id tasks id opts)
            prev   (:priority target)
            new-prio (cond
                       clear?    nil
                       cycle-dir (lifecycle/cycle-priority
                                   prev
                                   (if (contains? #{:back "back"} cycle-dir) -1 1))
                       :else     explicit)
            full-id (parser/get-task-id target)
            tree-final (tree/update-by-id tasks full-id
                                          #(assoc % :priority new-prio))]
        (when-not (:dry-run opts)
          (loader/save-source-roots-locality (:project-root (resolve-context opts))
                                             tree-final))
        (out/emit-result
          opts
          {:task (task/task->wire (task/find-by-id tree-final full-id) nil
                                  {:include-content? (boolean (:include-content opts))})
           :prevPriority prev
           :priority new-prio
           :text/lines
           [(str (:summary target) ": "
                 (if prev (style/priority opts prev) "(none)") " → "
                 (if new-prio (style/priority opts new-prio) "(none)"))]})))))

(defn status-cmd [{:keys [opts] :as result}]
  (let [{:keys [tasks]} (load-context opts)
        id        (positional-arg result :id)
        cycle-dir (:cycle opts)
        explicit  (some-> (positional-arg result :new-status 1)
                          str/upper-case)]
    (cond
      (nil? id)
      (out/emit-error opts
                      {:code "argument-error"
                       :message "ot status requires <id> and either <new-status> or --cycle."})

      (and (nil? explicit) (nil? cycle-dir))
      (out/emit-error opts
                      {:code "argument-error"
                       :message "ot status requires <id> and either <new-status> or --cycle."})

      :else
      ;; Resolve the target first so --cycle can read its current status. The
      ;; cycle order lives in lifecycle/status-cycle, the single source of
      ;; truth shared with the UIs.
      (let [target (resolve-required-id tasks id opts)
            status (if cycle-dir
                     (lifecycle/cycle-status (:status target)
                                             (if (contains? #{:back "back"} cycle-dir) -1 1))
                     explicit)]
        (if-not (valid-statuses status)
          (out/emit-error opts
                          {:code "invalid-status"
                           :message (str "Status must be one of "
                                         (str/join ", " (sort valid-statuses)))
                           :details {:value status}})
          (apply-status-cmd opts tasks target status))))))

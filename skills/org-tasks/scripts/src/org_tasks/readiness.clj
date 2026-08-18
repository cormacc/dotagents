(ns org-tasks.readiness
  "Task readiness evaluation over blocker properties and the task graph."
  (:require [org-tasks.lifecycle :as lifecycle]
            [org-tasks.parser.properties :as properties]))

(defn is-task-ready
  "Return a readiness report `{:ready bool, :gating [{:blocker, :reason}]}`.
  `resolve-task-by-id` is `(fn [id] task-or-nil)`."
  [task resolve-task-by-id]
  (let [gating
        (reduce
         (fn [acc blocker]
           (case (:kind blocker)
             :task
             (let [dependency (resolve-task-by-id (:ref blocker))]
               (cond
                 (nil? dependency) (conj acc {:blocker blocker :reason :missing-task})
                 (not (lifecycle/closed-statuses (:status dependency)))
                 (conj acc {:blocker blocker :reason (:status dependency)})
                 :else acc))
             (conj acc {:blocker blocker :reason :opaque})))
         []
         (properties/get-task-blockers task))]
    {:ready (empty? gating) :gating gating}))

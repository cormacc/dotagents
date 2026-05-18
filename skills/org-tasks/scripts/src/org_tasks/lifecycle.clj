(ns org-tasks.lifecycle
  "Status-transition semantics for the `ot` CLI.

  Port of `pi/extensions/tasks/lifecycle.ts`. Pure: applies a single
  status transition to a task map and returns the updated task plus
  metadata (`prev-status`, `was-closed`, `is-closed`, `timestamp`)
  needed by the CLI envelope and parent-promotion logic.

  Side-effecting concerns (file IO, parent walks, file watchers) live
  one level up in `org-tasks.commands.status`."
  (:require [org-tasks.parser :as parser]))

(def closed-statuses #{"DONE" "CANCELLED"})

(defn apply-status-transition
  "Apply the org-memory lifecycle semantics for a single status change.

  - Appends one LOGBOOK state entry per live transition.
  - Stamps `CLOSED:` on entry into a terminal state.
  - Clears `CLOSED:` on reopen from a terminal state.
  - Writes `:STARTED:` on the first transition into STARTED; preserves
    it across later DONE → STARTED re-opens.

  Returns `{:task <updated>, :prev-status, :status, :was-closed,
  :is-closed, :timestamp}`."
  ([task ^String status]
   (apply-status-transition task status (parser/format-org-timestamp)))
  ([task ^String status ^String timestamp]
   (let [prev-status (:status task)
         updated     (-> task
                         (assoc :status status)
                         (parser/append-state-log status prev-status timestamp))
         was-closed  (contains? closed-statuses prev-status)
         is-closed   (contains? closed-statuses status)
         updated     (cond
                       is-closed
                       (cond-> updated
                         (nil? (:closed updated)) (assoc :closed timestamp))

                       was-closed
                       (assoc updated :closed nil)

                       :else updated)
         updated     (if (and (= status "STARTED")
                              (not (parser/task-has-started-property? updated)))
                       (update updated :property-lines
                               (fnil conj []) (str ":STARTED: [" timestamp "]"))
                       updated)]
     {:task        updated
      :prev-status prev-status
      :status      status
      :was-closed  was-closed
      :is-closed   is-closed
      :timestamp   timestamp})))

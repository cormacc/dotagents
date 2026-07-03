(ns org-tasks.lifecycle
  "Status-transition semantics for the `ot` CLI.

  Pure: applies a single status transition to a task map and returns
  the updated task plus metadata (`prev-status`, `was-closed`,
  `is-closed`, `timestamp`) needed by the CLI envelope and
  parent-promotion logic.

  Side-effecting concerns (file IO, parent walks, file watchers) live
  one level up in `org-tasks.commands.status`."
  (:require [org-tasks.parser :as parser]))

(def closed-statuses #{"DONE" "CANCELLED"})

(def status-cycle
  "Canonical status order used when cycling forward/back. Single source of
  truth for the standalone TUI and the pi overlay (both dispatch through
  `ot status --cycle`)."
  ["TODO" "STARTED" "WAITING" "DONE" "CANCELLED"])

(defn cycle-status
  "Return the status `delta` steps from `current` in `status-cycle`, wrapping
  around. `delta` is typically +1 (forward) or -1 (back); an unknown `current`
  starts from the first entry."
  [current delta]
  (let [n (count status-cycle)
        i (.indexOf status-cycle current)
        base (if (neg? i) 0 i)]
    (nth status-cycle (mod (+ base delta) n))))

(def priority-cycle
  "Canonical priority order used when cycling forward/back, including the
  unset slot (nil). Forward from unset lands on the highest priority (A);
  back from unset lands on the lowest (D). Single source of truth for the
  standalone TUI and the pi overlay (both dispatch through
  `ot priority --cycle`)."
  [nil "A" "B" "C" "D"])

(defn cycle-priority
  "Return the priority `delta` steps from `current` in `priority-cycle`,
  wrapping around (so A cycles back to unset, D cycles forward to unset).
  `current` is a priority letter or nil; an unknown value starts from the
  unset slot."
  [current delta]
  (let [n (count priority-cycle)
        i (.indexOf priority-cycle current)
        base (if (neg? i) 0 i)]
    (nth priority-cycle (mod (+ base delta) n))))

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

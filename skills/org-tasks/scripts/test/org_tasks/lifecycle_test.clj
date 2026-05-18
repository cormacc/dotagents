(ns org-tasks.lifecycle-test
  "Tests for `org-tasks.lifecycle/apply-status-transition`.

  Mirrors `pi/extensions/tasks/lifecycle.test.ts`. Verifies LOGBOOK
  semantics, CLOSED write/clear/re-write, and :STARTED: once-only
  preservation across re-opens."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org-tasks.lifecycle :as lifecycle]
            [org-tasks.parser :as p]))

(defn- base-task [status]
  {:level 2
   :status status
   :priority nil
   :summary "Lifecycle task"
   :tags []
   :description ""
   :children []
   :property-lines [":CUSTOM_ID: 11111111-2222-4333-8444-555555555555"
                    ":CREATED: [2026-05-01 Fri 08:00]"]
   :logbook-lines ["- Created [2026-05-01 Fri 08:00]"]
   :import-path nil
   :import-raw nil
   :closed nil
   :line-number 0
   :end-line 0})

(deftest direct-todo-done-close
  (let [{:keys [task]} (lifecycle/apply-status-transition
                         (base-task "TODO") "DONE" "2026-05-01 Fri 09:00")]
    (is (= "DONE" (:status task)))
    (is (= "2026-05-01 Fri 09:00" (:closed task)))
    (is (= ["- Created [2026-05-01 Fri 08:00]"
            "- State \"DONE\" from \"TODO\" [2026-05-01 Fri 09:00]"]
           (:logbook-lines task)))))

(deftest reopen-and-reclose-preserves-started
  (let [t0 (-> (base-task "STARTED")
               (update :property-lines conj
                       ":STARTED: [2026-05-01 Fri 08:30]"))
        {t1 :task} (lifecycle/apply-status-transition t0 "DONE" "2026-05-01 Fri 09:00")
        {t2 :task} (lifecycle/apply-status-transition t1 "STARTED" "2026-05-01 Fri 09:10")
        {t3 :task} (lifecycle/apply-status-transition t2 "DONE" "2026-05-01 Fri 09:20")]
    (testing "reopen clears CLOSED"
      (is (nil? (:closed t2))))
    (testing "first :STARTED: timestamp is preserved across reopen"
      (is (= "2026-05-01 Fri 08:30" (p/get-task-started t2))))
    (testing "re-close writes fresh CLOSED"
      (is (= "2026-05-01 Fri 09:20" (:closed t3))))
    (testing "LOGBOOK is append-only and ordered"
      (is (= ["- Created [2026-05-01 Fri 08:00]"
              "- State \"DONE\" from \"STARTED\" [2026-05-01 Fri 09:00]"
              "- State \"STARTED\" from \"DONE\" [2026-05-01 Fri 09:10]"
              "- State \"DONE\" from \"STARTED\" [2026-05-01 Fri 09:20]"]
             (:logbook-lines t3))))
    (let [out (p/serialize-tasks [t3])]
      (is (str/includes? out "CLOSED: [2026-05-01 Fri 09:20]"))
      (is (str/includes? out "- State \"STARTED\" from \"DONE\" [2026-05-01 Fri 09:10]")))))

(deftest waiting-to-done-closes
  (let [{:keys [task]} (lifecycle/apply-status-transition
                         (base-task "WAITING") "DONE" "2026-05-01 Fri 10:00")]
    (is (= "2026-05-01 Fri 10:00" (:closed task)))
    (is (= "- State \"DONE\" from \"WAITING\" [2026-05-01 Fri 10:00]"
           (last (:logbook-lines task))))))

(deftest first-started-writes-property
  (let [t0 (base-task "TODO")
        {t1 :task} (lifecycle/apply-status-transition t0 "STARTED" "2026-05-01 Fri 09:00")]
    (is (= "2026-05-01 Fri 09:00" (p/get-task-started t1)))
    (is (= 3 (count (:property-lines t1)))
        "STARTED line appended exactly once")
    (let [{t2 :task} (lifecycle/apply-status-transition t1 "DONE" "2026-05-01 Fri 09:30")
          {t3 :task} (lifecycle/apply-status-transition t2 "STARTED" "2026-05-01 Fri 09:45")]
      (testing "no duplicate :STARTED: on re-entry"
        (is (= "2026-05-01 Fri 09:00" (p/get-task-started t3)))
        (is (= 3 (count (:property-lines t3))))))))

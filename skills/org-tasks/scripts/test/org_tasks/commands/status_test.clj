(ns org-tasks.commands.status-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org-tasks.commands.test-util :refer :all]
            [org-tasks.parser :as parser]
            [org-tasks.styling :as styling]))

(deftest status-cycles-with-logbook
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [id "11111111-2222-4333-8444-555555555551"
            {:keys [out exit]}
            (run-cli! "--root" root "--format" "json" "status" id "STARTED")
            r (parse-json-result out)]
        (is (zero? exit))
        (is (= "TODO"    (:prevStatus r)))
        (is (= "STARTED" (:status r)))
        (is (some? (:started r)))
        (is (nil? (:closed r)))
        (let [content (slurp (str (fs/path root "TASKS.org")))]
          (is (str/includes? content "* STARTED [#A] First"))
          (is (str/includes? content ":STARTED:"))
          (is (str/includes? content "- State \"STARTED\" from \"TODO\""))))

      (let [id "11111111-2222-4333-8444-555555555551"
            {:keys [out]}
            (run-cli! "--root" root "--format" "json" "status" id "DONE")
            r (parse-json-result out)]
        (is (= "STARTED" (:prevStatus r)))
        (is (= "DONE"    (:status r)))
        (is (some?       (:closed r)))
        (let [content (slurp (str (fs/path root "TASKS.org")))]
          (is (str/includes? content "CLOSED:")))))))

(deftest priority-set-cycle-and-clear
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [first-id  "11111111-2222-4333-8444-555555555551" ; has [#A]
            second-id "22229999-2222-4333-8444-555555555552" ; no priority
            run! (fn [& args]
                   (let [{:keys [out exit]}
                         (apply run-cli! "--root" root "--format" "json" "priority" args)]
                     (assoc (parse-json-result out) ::exit exit)))]
        (testing "explicit set (case-insensitive)"
          (let [r (run! first-id "b")]
            (is (zero? (::exit r)))
            (is (= "A" (:prevPriority r)))
            (is (= "B" (:priority r)))
            (is (str/includes? (slurp (str (fs/path root "TASKS.org")))
                               "* TODO [#B] First"))))
        (testing "cycle forward from unset lands on A (highest)"
          (let [r (run! second-id "--cycle" "forward")]
            (is (nil? (:prevPriority r)))
            (is (= "A" (:priority r)))))
        (testing "cycle back from A returns to unset"
          (let [r (run! second-id "--cycle" "back")]
            (is (= "A" (:prevPriority r)))
            (is (nil? (:priority r)))
            (is (str/includes? (slurp (str (fs/path root "TASKS.org")))
                               "** STARTED Second"))))
        (testing "cycle back from unset lands on D (lowest)"
          (let [r (run! second-id "--cycle" "back")]
            (is (nil? (:prevPriority r)))
            (is (= "D" (:priority r)))))
        (testing "cycle forward from D wraps to unset"
          (let [r (run! second-id "--cycle" "forward")]
            (is (= "D" (:prevPriority r)))
            (is (nil? (:priority r)))))
        (testing "--clear removes the cookie"
          (run! first-id "--cycle" "forward") ; B -> C
          (let [r (run! first-id "--clear")]
            (is (= "C" (:prevPriority r)))
            (is (nil? (:priority r)))))))))

(deftest priority-invalid-level-errors
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [{:keys [err exit]}
            (run-cli! "--root" root "--format" "json" "priority"
                      "11111111-2222-4333-8444-555555555551" "E")
            e (parse-json-error err)]
        (is (= 1 exit))
        (is (= "invalid-priority" (:code e)))))))

(deftest status-dry-run-does-not-write
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [before (slurp (str (fs/path root "TASKS.org")))
            id "11111111-2222-4333-8444-555555555551"]
        (run-cli! "--root" root "--format" "json"
                  "--dry-run" "status" id "DONE")
        (is (= before (slurp (str (fs/path root "TASKS.org")))))))))

(deftest status-invalid-status-errors
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [{:keys [err exit]}
            (run-cli! "--root" root "--format" "json"
                      "status" "11111111-2222-4333-8444-555555555551" "BOGUS")
            e (parse-json-error err)]
        (is (= 1 exit))
        (is (= "invalid-status" (:code e)))))))

;; ── linked plan mutation coverage ───────────────────────────────

(deftest linked-plan-fixture-round-trips-before-mutation
  (let [content (linked-plan-content)
        parsed (:tasks (parser/parse-tasks content))]
    (is (= content (parser/serialize-tasks-preserving-file content parsed)))))

(deftest status-mutates-linked-plan-task-and-promotes-parent
  (with-temp-dir
    (fn [root]
      (bootstrap-linked-plan-graph! root)
      (let [tasks-path (str (fs/path root "TASKS.org"))
            plan-path  (str (fs/path root "design" "log" "linked-plan.org"))
            {:keys [out exit]}
            (run-cli! "--root" root "--format" "json"
                      "status" linked-plan-child-id "STARTED")
            r (parse-json-result out)
            tasks-content (slurp tasks-path)
            plan-content  (slurp plan-path)]
        (is (zero? exit))
        (is (= "STARTED" (:status r)))
        (is (= plan-path (get-in r [:task :sourcePath])))
        (is (= [linked-plan-parent-id]
               (mapv :id (:promoted r))))
        (is (str/includes? plan-content "** STARTED Plan child"))
        (is (str/includes? plan-content ":STARTED:"))
        (is (str/includes? plan-content "- State \"STARTED\" from \"TODO\""))
        (is (str/includes? tasks-content "** STARTED Parent with linked plan"))
        (is (str/includes? tasks-content ":STARTED:")))

      (let [{:keys [out exit]}
            (run-cli! "--root" root "--format" "json"
                      "status" linked-plan-child-id "DONE")
            r (parse-json-result out)
            plan-content (slurp (str (fs/path root "design" "log" "linked-plan.org")))]
        (is (zero? exit))
        (is (= "DONE" (:status r)))
        (is (str/includes? plan-content "** DONE Plan child"))
        (is (str/includes? plan-content "CLOSED:")))

      (let [{:keys [out exit]}
            (run-cli! "--root" root "--format" "json"
                      "status" linked-plan-child-id "TODO")
            r (parse-json-result out)
            plan-content (slurp (str (fs/path root "design" "log" "linked-plan.org")))]
        (is (zero? exit))
        (is (= "TODO" (:status r)))
        (is (str/includes? plan-content "** TODO Plan child"))
        (is (not (str/includes? plan-content "CLOSED:")))))))

(deftest linked-plan-id-mutators-persist-to-plan-file
  (with-temp-dir
    (fn [root]
      (bootstrap-linked-plan-graph! root)
      (let [plan-path (str (fs/path root "design" "log" "linked-plan.org"))]
        (testing "handoff set/clear"
          (let [{:keys [out exit]}
                (run-cli! "--root" root "--format" "json"
                          "handoff" "set" linked-plan-child-id "resume here")
                r (parse-json-result out)]
            (is (zero? exit))
            (is (= "resume here" (:handoff r)))
            (is (str/includes? (slurp plan-path) ":HANDOFF: resume here")))
          (let [{:keys [exit]}
                (run-cli! "--root" root "--format" "json"
                          "handoff" "clear" linked-plan-child-id)]
            (is (zero? exit))
            (is (not (str/includes? (slurp plan-path) ":HANDOFF:")))))

        (testing "blocker add/remove and ready"
          (let [{:keys [exit]}
                (run-cli! "--root" root "--format" "json"
                          "blocker" "add" linked-plan-child-id "blocked by human")]
            (is (zero? exit))
            (is (str/includes? (slurp plan-path) ":BLOCKED-BY: human: blocked by human")))
          (let [{:keys [out exit]}
                (run-cli! "--root" root "--format" "json"
                          "ready" linked-plan-child-id)
                r (parse-json-result out)]
            (is (zero? exit))
            (is (false? (:ready r)))
            (is (= "human: blocked by human"
                   (get-in r [:gating 0 :blocker :raw]))))
          (let [{:keys [exit]}
                (run-cli! "--root" root "--format" "json"
                          "blocker" "remove" linked-plan-child-id "human: blocked by human")]
            (is (zero? exit))
            (is (not (str/includes? (slurp plan-path) ":BLOCKED-BY:")))))

        (testing "issue add/remove"
          (let [{:keys [exit]}
                (run-cli! "--root" root "--format" "json"
                          "issue" "add" linked-plan-child-id "[[jira:OT-1]]")]
            (is (zero? exit))
            (is (str/includes? (slurp plan-path) ":LINKED_ISSUES: [[jira:OT-1]]")))
          (let [{:keys [exit]}
                (run-cli! "--root" root "--format" "json"
                          "issue" "remove" linked-plan-child-id "[[jira:OT-1]]")]
            (is (zero? exit))
            (is (not (str/includes? (slurp plan-path) ":LINKED_ISSUES:")))))))))
(deftest status-cycle-moves-through-canonical-order
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [id "11111111-2222-4333-8444-555555555551"]
        (testing "forward TODO -> STARTED"
          (let [r (parse-json-result
                   (:out (run-cli! "--root" root "--format" "json"
                                   "status" id "--cycle" "forward")))]
            (is (= "TODO" (:prevStatus r)))
            (is (= "STARTED" (:status r)))))
        (testing "back STARTED -> TODO"
          (let [r (parse-json-result
                   (:out (run-cli! "--root" root "--format" "json"
                                   "status" id "--cycle" "back")))]
            (is (= "STARTED" (:prevStatus r)))
            (is (= "TODO" (:status r)))))
        (testing "back from TODO wraps to CANCELLED"
          (let [r (parse-json-result
                   (:out (run-cli! "--root" root "--format" "json"
                                   "status" id "--cycle" "back")))]
            (is (= "CANCELLED" (:status r)))))))))

(deftest status-requires-status-or-cycle
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [{:keys [err exit]}
            (run-cli! "--root" root "--format" "json"
                      "status" "11111111-2222-4333-8444-555555555551")
            e (parse-json-error err)]
        (is (= 1 exit))
        (is (= "argument-error" (:code e)))))))

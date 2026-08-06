(ns org-tasks.commands.removal-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org-tasks.commands.test-util :refer :all]
            [org-tasks.loader :as loader]))

(def parent-id "10101010-1111-4111-8111-101010101010")
(def child-id "20202020-2222-4222-8222-202020202020")
(def grandchild-id "30303030-3333-4333-8333-303030303030")
(def referrer-id "40404040-4444-4444-8444-404040404040")
(def local-parent-id "50505050-5555-4555-8555-505050505050")
(def local-child-id "60606060-6666-4666-8666-606060606060")
(def dangling-id "99999999-7777-4777-8777-999999999999")

(defn- task-block [level status summary id & [body]]
  (str (apply str (repeat level "*")) " " status " " summary "\n"
       ":PROPERTIES:\n:CUSTOM_ID: " id "\n:END:\n"
       (or body "")))

(defn- bootstrap-removal-graph! [root]
  (fs/create-dirs (str (fs/path root "design" "log")))
  (spit (str (fs/path root "TASKS.setup.org")) setup-org-preamble)
  (spit (str (fs/path root "TASKS.org"))
        (str tasks-org-preamble
             "* Improvements\n"
             (task-block 2 "TODO" "Parent" parent-id)
             (task-block 3 "TODO" "Child" child-id
                         (str "Acceptance criteria:\n- [ ] child criterion\n"
                              (task-block 4 "STARTED" "Grandchild" grandchild-id
                                          "- [ ] grandchild criterion\n")))
             (task-block 2 "TODO" "Referrer" referrer-id
                         (str ":PROPERTIES:\n:BLOCKED-BY: task:" child-id "\n:END:\n"))
             (task-block 2 "TODO" "Plan parent" linked-plan-parent-id)
             "#+IMPORT: [[plan:linked-plan.org]]\n"))
  (spit (str (fs/path root "TASKS.local.org"))
        (str "#+SELECTED: " child-id "\n\n* Drafts\n"
             (task-block 2 "TODO" "Local parent" local-parent-id)
             (task-block 3 "TODO" "Local child" local-child-id)))
  (spit (str (fs/path root "design" "log" "linked-plan.org"))
        (str "* Plan\n" (task-block 2 "TODO" "Imported child" linked-plan-child-id))))

(deftest remove-previews-refuses-roots-and-prunes-inbound-blockers
  (with-temp-dir
    (fn [root]
      (bootstrap-removal-graph! root)
      (let [tasks-path (str (fs/path root "TASKS.org"))
            before (slurp tasks-path)]
        (testing "protocol roots remain lifecycle/archive-owned"
          (let [{:keys [err exit]} (run-cli! "--root" root "--format" "json" "remove" parent-id "--yes")]
            (is (= 1 exit))
            (is (= "top-level-root" (:code (parse-json-error err))))))
        (testing "dry-run reports a compact complete impact without writing"
          (let [{:keys [out exit]} (run-cli! "--root" root "--format" "json" "--dry-run" "remove" child-id)
                r (parse-json-result out)]
            (is (zero? exit))
            (is (true? (:dryRun r)))
            (is (= [child-id grandchild-id] (mapv :id (:subtree r))))
            (is (= ["TODO" "STARTED"] (mapv :status (:subtree r))))
            (is (= ["child criterion" "grandchild criterion"]
                   (mapv :criterion (:uncheckedCriteria r))))
            (is (= [referrer-id] (mapv :taskId (:inboundBlockers r))))
            (is (true? (get-in r [:selection :cleared])))
            (is (= before (slurp tasks-path)))))
        (testing "bare removal needs confirmation and leaves every file unchanged"
          (let [{:keys [err exit]} (run-cli! "--root" root "--format" "json" "remove" child-id)]
            (is (= 1 exit))
            (is (= "confirmation-required" (:code (parse-json-error err))))
            (is (= before (slurp tasks-path)))))
        (testing "inbound blockers refuse deletion unless pruning is explicit"
          (let [{:keys [err exit]} (run-cli! "--root" root "--format" "json" "remove" child-id "--yes")]
            (is (= 1 exit))
            (is (= "inbound-blockers" (:code (parse-json-error err))))
            (is (= before (slurp tasks-path)))))
        (testing "--prune-blockers removes only target references and clears selected descendants"
          (let [{:keys [out exit]} (run-cli! "--root" root "--format" "json" "remove" child-id "--yes" "--prune-blockers")
                r (parse-json-result out)
                after (slurp tasks-path)
                local (slurp (str (fs/path root "TASKS.local.org")))]
            (is (zero? exit))
            (is (false? (:dryRun r)))
            (is (= [referrer-id] (mapv :taskId (:prunedBlockers r))))
            (is (not (str/includes? after child-id)))
            (is (not (str/includes? after grandchild-id)))
            (is (not (str/includes? after (str "task:" child-id))))
            (is (not (re-find #"(?im)^#\+SELECTED:" local)))))))))

(deftest remove-handles-imported-and-local-nested-owners-and-preflights-all-files
  (with-temp-dir
    (fn [root]
      (bootstrap-removal-graph! root)
      (let [plan-path (str (fs/path root "design" "log" "linked-plan.org"))
            local-path (str (fs/path root "TASKS.local.org"))]
        (testing "an imported task is removable through its import owner and persists to that file"
          (let [{:keys [out exit]} (run-cli! "--root" root "--format" "json" "remove" linked-plan-child-id "--yes")]
            (is (zero? exit))
            (is (= linked-plan-child-id (:targetId (parse-json-result out))))
            (is (not (str/includes? (slurp plan-path) linked-plan-child-id)))))
        (testing "a local nested task is eligible while its local root is refused"
          (let [{:keys [out exit]} (run-cli! "--root" root "--format" "json" "remove" local-child-id "--yes")]
            (is (zero? exit))
            (is (not (str/includes? (slurp local-path) local-child-id))))
          (let [{:keys [err exit]} (run-cli! "--root" root "--format" "json" "remove" local-parent-id "--yes")]
            (is (= 1 exit))
            (is (= "top-level-root" (:code (parse-json-error err))))))
        (testing "removing a selected local child rewrites its owner before clearing selection"
          (bootstrap-removal-graph! root)
          (spit local-path
                (str/replace (slurp local-path)
                             (str "#+SELECTED: " child-id)
                             (str "#+SELECTED: " local-child-id)))
          (let [{:keys [out exit]} (run-cli! "--root" root "--format" "json"
                                             "--dry-run" "remove" local-child-id)
                preview (parse-json-result out)]
            (is (zero? exit))
            (is (some #{local-path} (:affectedFiles preview))))
          (let [{:keys [exit]} (run-cli! "--root" root "--format" "json"
                                         "remove" local-child-id "--yes")
                local (slurp local-path)]
            (is (zero? exit))
            (is (not (str/includes? local local-child-id)))
            (is (not (re-find #"(?im)^#\+SELECTED:" local)))))
        (testing "a changed known baseline fails before any source write"
          (bootstrap-removal-graph! root)
          (let [tasks-path (str (fs/path root "TASKS.org"))
                local-before (slurp local-path)
                original-preflight loader/preflight-baselines!]
            (with-redefs [loader/preflight-baselines!
                          (fn [baselines]
                            (spit tasks-path "# external edit\n")
                            (original-preflight baselines))]
              (let [{:keys [err exit]} (run-cli! "--root" root "--format" "json"
                                                 "remove" child-id "--yes" "--prune-blockers")]
                (is (= 1 exit))
                (is (= "conflict" (:code (parse-json-error err))))
                (is (= local-before (slurp local-path)))))))))))

(deftest remove-refuses-duplicate-id-before-preview-or-write
  (with-temp-dir
    (fn [root]
      (bootstrap-removal-graph! root)
      (let [tasks-path (str (fs/path root "TASKS.org"))
            local-path (str (fs/path root "TASKS.local.org"))]
        (spit local-path
              (str (slurp local-path)
                   (task-block 2 "TODO" "Duplicate protected root" child-id)))
        (let [tasks-before (slurp tasks-path)
              local-before (slurp local-path)
              {:keys [err exit]} (run-cli! "--root" root "--format" "json"
                                            "remove" child-id "--yes" "--prune-blockers")]
          (is (= 1 exit))
          (is (= "ambiguous-id" (:code (parse-json-error err))))
          (is (= tasks-before (slurp tasks-path)))
          (is (= local-before (slurp local-path))))))))

(deftest blocker-prune-is-explicit-and-preserves-valid-and-human-blockers
  (with-temp-dir
    (fn [root]
      (bootstrap-removal-graph! root)
      (let [tasks-path (str (fs/path root "TASKS.org"))]
        (spit tasks-path
              (str (slurp tasks-path)
                   (task-block 2 "TODO" "Prunable" dangling-id
                               (str ":PROPERTIES:\n:BLOCKED-BY: task:deadbeef-0000-4000-8000-000000000000\n"
                                    ":BLOCKED-BY+: 12345678-1234-4234-8234-123456789012\n"
                                    ":BLOCKED-BY+: task:" parent-id "\n"
                                    ":BLOCKED-BY+: human: wait for review\n:END:\n"))))
        (let [before (slurp tasks-path)
              {:keys [out exit]} (run-cli! "--root" root "--format" "json" "--dry-run" "blocker" "prune")
              r (parse-json-result out)]
          (is (zero? exit))
          (is (true? (:dryRun r)))
          (is (= 2 (count (:pruned r))))
          (is (= before (slurp tasks-path))))
        (let [{:keys [err exit]} (run-cli! "--root" root "--format" "json" "blocker" "prune")]
          (is (= 1 exit))
          (is (= "confirmation-required" (:code (parse-json-error err)))))
        (let [{:keys [out exit]} (run-cli! "--root" root "--format" "json" "blocker" "prune" "--yes")
              r (parse-json-result out)
              after (slurp tasks-path)]
          (is (zero? exit))
          (is (= 2 (count (:pruned r))))
          (is (not (str/includes? after "deadbeef-0000-4000-8000-000000000000")))
          (is (not (str/includes? after "12345678-1234-4234-8234-123456789012")))
          (is (str/includes? after (str "task:" parent-id)))
          (is (str/includes? after "human: wait for review")))))))
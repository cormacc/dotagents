(ns org-tasks.commands.archive-publish-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org-tasks.commands.test-util :refer :all]
            [org-tasks.parser :as parser]
            [org-tasks.styling :as styling]))

(deftest publish-moves-local-to-shared
  (with-temp-dir
    (fn [root]
      (let [id "local-aaaa-bbbb-cccc-dddddddddddd"]
        (bootstrap-local! root id "Draft task")
        (let [{:keys [out exit]}
              (run-cli! "--root" root "--format" "json" "publish" id)
              r (parse-json-result out)
              shared (slurp (str (fs/path root "TASKS.org")))
              local  (slurp (str (fs/path root "TASKS.local.org")))]
          (is (zero? exit))
          (is (str/includes? shared (str ":CUSTOM_ID: " id)))
          (is (not (str/includes? local (str ":CUSTOM_ID: " id))))
          (is (false? (get-in r [:task :local]))))))))

(deftest unpublish-moves-shared-to-local
  (with-temp-dir
    (fn [root]
      (let [id "shared-aaa-bbbb-cccc-dddddddddddd"]
        (spit (str (fs/path root "TASKS.org"))
              (str "* Improvements\n"
                   "** TODO Shared task\n"
                   ":PROPERTIES:\n"
                   ":CUSTOM_ID: " id "\n"
                   ":END:\n"))
        (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n")
        (let [{:keys [out exit]}
              (run-cli! "--root" root "--format" "json" "unpublish" id)
              r (parse-json-result out)
              shared (slurp (str (fs/path root "TASKS.org")))
              local  (slurp (str (fs/path root "TASKS.local.org")))]
          (is (zero? exit))
          (is (not (str/includes? shared (str ":CUSTOM_ID: " id))))
          (is (str/includes? local (str ":CUSTOM_ID: " id)))
          (is (true? (get-in r [:task :local]))))))))

(deftest publish-from-a-local-file-with-no-other-tasks-does-not-report-conflict
  (testing "regression: destination file with zero prior roots must not false-positive as a conflict"
    (with-temp-dir
      (fn [root]
        (let [id "local-aaaa-bbbb-cccc-dddddddddddd"]
          (bootstrap-local! root id "Draft task")
          (let [{:keys [out exit]}
                (run-cli! "--root" root "--format" "json" "publish" id)
                shared (slurp (str (fs/path root "TASKS.org")))]
            (is (zero? exit) out)
            (is (str/includes? shared (str ":CUSTOM_ID: " id)))))))))

(deftest unpublish-the-only-shared-task-empties-tasks-org
  (testing "regression: moving a file's only task away must still rewrite that file"
    (with-temp-dir
      (fn [root]
        (let [id "shared-aaa-bbbb-cccc-dddddddddddd"]
          (spit (str (fs/path root "TASKS.org"))
                (str "* Improvements\n"
                     "** TODO Shared task\n"
                     ":PROPERTIES:\n:CUSTOM_ID: " id "\n:END:\n"))
          (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n")
          (let [{:keys [out exit]}
                (run-cli! "--root" root "--format" "json" "unpublish" id)
                shared (slurp (str (fs/path root "TASKS.org")))
                local (slurp (str (fs/path root "TASKS.local.org")))]
            (is (zero? exit) out)
            (is (not (str/includes? shared (str ":CUSTOM_ID: " id))))
            (is (str/includes? local (str ":CUSTOM_ID: " id)))))))))

;; ── archive ──────────────────────────────────────────

(deftest archive-moves-closed-top-level-task
  (with-temp-dir
    (fn [root]
      (let [id "closed-aaa-bbbb-cccc-dddddddddddd"]
        (spit (str (fs/path root "TASKS.org"))
              (str "* Improvements\n"
                   "** DONE Closed task\n"
                   "CLOSED: [2026-05-01 Fri 09:00]\n"
                   ":PROPERTIES:\n"
                   ":CUSTOM_ID: " id "\n"
                   ":END:\n"))
        (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n")
        (let [{:keys [out exit]}
              (run-cli! "--root" root "--format" "json" "archive" id)
              r (parse-json-result out)
              shared  (slurp (str (fs/path root "TASKS.org")))
              archive (slurp (str (fs/path root "TASKS.archive.org")))]
          (is (zero? exit))
          (is (not (str/includes? shared (str ":CUSTOM_ID: " id))))
          (is (str/includes? archive (str ":CUSTOM_ID: " id)))
          (is (str/includes? archive ":ARCHIVED:"))
          (is (= "2026-05-01 Fri 09:00" (:archivedAt r))))))))

(deftest archive-clears-selected-archived-task
  (with-temp-dir
    (fn [root]
      (let [id "closed-aaa-bbbb-cccc-dddddddddddd"
            local-path (str (fs/path root "TASKS.local.org"))]
        (spit (str (fs/path root "TASKS.org"))
              (str "* Improvements\n"
                   "** DONE Closed task\n"
                   "CLOSED: [2026-05-01 Fri 09:00]\n"
                   ":PROPERTIES:\n"
                   ":CUSTOM_ID: " id "\n"
                   ":END:\n"))
        (spit local-path (str "#+SELECTED: " id "\n"))
        (let [{:keys [out exit]}
              (run-cli! "--root" root "--format" "json" "archive" id)
              r (parse-json-result out)
              local (slurp local-path)]
          (is (zero? exit))
          (is (true? (:selectionCleared r)))
          (is (not (re-find #"(?im)^#\+SELECTED:" local))))))))

(deftest archive-clears-selected-descendant
  (with-temp-dir
    (fn [root]
      (let [parent-id "closed-aaa-bbbb-cccc-dddddddddddd"
            child-id "child-aaaa-bbbb-cccc-dddddddddddd"
            local-path (str (fs/path root "TASKS.local.org"))]
        (spit (str (fs/path root "TASKS.org"))
              (str "* Improvements\n"
                   "** DONE Closed parent\n"
                   "CLOSED: [2026-05-01 Fri 09:00]\n"
                   ":PROPERTIES:\n"
                   ":CUSTOM_ID: " parent-id "\n"
                   ":END:\n"
                   "*** TODO Child task\n"
                   ":PROPERTIES:\n"
                   ":CUSTOM_ID: " child-id "\n"
                   ":END:\n"))
        (spit local-path (str "#+SELECTED: " child-id "\n"))
        (let [{:keys [out exit]}
              (run-cli! "--root" root "--format" "json" "archive" parent-id)
              r (parse-json-result out)
              local (slurp local-path)]
          (is (zero? exit))
          (is (true? (:selectionCleared r)))
          (is (not (re-find #"(?im)^#\+SELECTED:" local))))))))

(deftest archive-leaves-different-selection-unchanged
  (with-temp-dir
    (fn [root]
      (let [archived-id "closed-aaa-bbbb-cccc-dddddddddddd"
            selected-id "other-aaaa-bbbb-cccc-dddddddddddd"
            local-path (str (fs/path root "TASKS.local.org"))]
        (spit (str (fs/path root "TASKS.org"))
              (str "* Improvements\n"
                   "** DONE Closed task\n"
                   "CLOSED: [2026-05-01 Fri 09:00]\n"
                   ":PROPERTIES:\n"
                   ":CUSTOM_ID: " archived-id "\n"
                   ":END:\n"
                   "** TODO Other task\n"
                   ":PROPERTIES:\n"
                   ":CUSTOM_ID: " selected-id "\n"
                   ":END:\n"))
        (spit local-path (str "#+SELECTED: " selected-id "\n"))
        (let [{:keys [out exit]}
              (run-cli! "--root" root "--format" "json" "archive" archived-id)
              r (parse-json-result out)
              local (slurp local-path)]
          (is (zero? exit))
          (is (false? (:selectionCleared r)))
          (is (str/includes? local (str "#+SELECTED: " selected-id))))))))

(deftest archive-dry-run-does-not-clear-selection
  (with-temp-dir
    (fn [root]
      (let [id "closed-aaa-bbbb-cccc-dddddddddddd"
            local-path (str (fs/path root "TASKS.local.org"))]
        (spit (str (fs/path root "TASKS.org"))
              (str "* Improvements\n"
                   "** DONE Closed task\n"
                   "CLOSED: [2026-05-01 Fri 09:00]\n"
                   ":PROPERTIES:\n"
                   ":CUSTOM_ID: " id "\n"
                   ":END:\n"))
        (spit local-path (str "#+SELECTED: " id "\n"))
        (let [{:keys [out exit]}
              (run-cli! "--root" root "--format" "json" "--dry-run" "archive" id)
              r (parse-json-result out)
              local (slurp local-path)]
          (is (zero? exit))
          (is (false? (:selectionCleared r)))
          (is (str/includes? local (str "#+SELECTED: " id))))))))

(deftest archive-refuses-open-task
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [{:keys [err exit]}
            (run-cli! "--root" root "--format" "json"
                      "archive" "11111111-2222-4333-8444-555555555551")
            e (parse-json-error err)]
        (is (= 1 exit))
        (is (= "validation" (:code e)))))))

(deftest archive-removes-file-level-imported-root-from-its-own-file
  (testing "a task rooted in a file-level #+IMPORT: is removed from that file, not TASKS.org"
    (with-temp-dir
      (fn [root]
        (let [id "11111111-2222-4333-8444-555555555551"]
          (fs/create-dirs (str (fs/path root "design" "log")))
          (spit (str (fs/path root "TASKS.org"))
                (str "#+TITLE: Tasks\n#+SETUPFILE: ./TASKS.local.org\n"
                     "#+IMPORT: design/log/root.org\n\n* Improvements\n"))
          (spit (str (fs/path root "design" "log" "root.org"))
                (str "#+TITLE: Root\n"
                     "** DONE Imported closed task\n"
                     "CLOSED: [2026-05-01 Fri 09:00]\n"
                     ":PROPERTIES:\n:CUSTOM_ID: " id "\n:END:\n"))
          (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n")
          (let [{:keys [out exit]}
                (run-cli! "--root" root "--format" "json" "archive" id)
                r (parse-json-result out)
                tasks-org (slurp (str (fs/path root "TASKS.org")))
                root-org (slurp (str (fs/path root "design" "log" "root.org")))
                archive (slurp (str (fs/path root "TASKS.archive.org")))
                list-out (:out (run-cli! "--root" root "--format" "json" "list"))
                rows (:rows (parse-json-result list-out))]
            (is (zero? exit))
            ;; The #+IMPORT: line stays; the task's own heading is gone.
            (is (str/includes? tasks-org "#+IMPORT: design/log/root.org"))
            (is (not (str/includes? root-org (str ":CUSTOM_ID: " id))))
            (is (str/includes? archive (str ":CUSTOM_ID: " id)))
            (is (= "2026-05-01 Fri 09:00" (:archivedAt r)))
            ;; Never appears in both the archive and `ot list`.
            (is (not (some #(= id (:id %)) rows)))))))))

(deftest archive-refuses-local-task
  (with-temp-dir
    (fn [root]
      (let [id "local-aaaa-bbbb-cccc-dddddddddddd"]
        (bootstrap-local! root id "Draft local")
        (let [{:keys [err exit]}
              (run-cli! "--root" root "--format" "json" "archive" id)
              e (parse-json-error err)]
          (is (= 1 exit))
          (is (= "validation" (:code e))))))))

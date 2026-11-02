(ns org-tasks.commands.archive-publish-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org-tasks.commands.test-util :refer :all]
            [org-tasks.loader :as loader]
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

(deftest locality-mutator-content-projection-is-compact-by-default-and-opt-in
  (with-temp-dir
    (fn [root]
      (let [id "local-aaaa-bbbb-cccc-dddddddddddd"]
        (bootstrap-local! root id "Draft task")
        (let [compact (parse-json-result
                       (:out (run-cli! "--root" root "--format" "json" "--dry-run"
                                       "publish" id)))
              expanded (parse-json-result
                        (:out (run-cli! "--root" root "--format" "json" "--dry-run"
                                        "publish" id "--include-content")))]
          (is (not (contains? (:task compact) :sourceContent)))
          (is (not (contains? (:task compact) :effectiveSourceContent)))
          (is (contains? (:task expanded) :sourceContent))
          (is (contains? (:task expanded) :effectiveSourceContent)))))))

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

(deftest archive-preserves-unrelated-imported-records
  (doseq [source [:shared :shared-only :imported-only]
          linked? [false true]
          selected? [false true]
          dry-run? [false true]]
    (testing (str {:source source :linked? linked? :selected? selected? :dry-run? dry-run?})
      (with-temp-dir
        (fn [root]
          (bootstrap-linked-plan-graph! root)
          (let [id "11111111-2222-4333-8444-555555555551"
                path (fn [relative] (str (fs/path root relative)))
                tasks-path (path "TASKS.org")
                local-path (path "TASKS.local.org")
                unrelated-path (path "design/log/linked-plan.org")
                own-path (path "design/log/own.org")
                archive-path (path "TASKS.archive.org")
                source-path (if (= source :imported-only) (path "target.org") tasks-path)
                original-index (slurp tasks-path)
                target (str "** DONE Closed task\nCLOSED: [2026-05-01 Fri 09:00]\n"
                            ":PROPERTIES:\n:CUSTOM_ID: " id "\n:END:\n"
                            (when linked? "#+IMPORT: [[plan:own.org]]\n"))
                unrelated (str "* Plan\n** TODO Plan child\n:PROPERTIES:\n:CUSTOM_ID: "
                               linked-plan-child-id "\n:END:\n"
                               "Acceptance criteria:\n- Preserve this spacing.\n\n"
                               "*** TODO Nested task\n:PROPERTIES:\n:CUSTOM_ID: "
                               linked-plan-second-child-id "\n:END:\n")
                own-record (str "#+PARENT: [[task:" id "][Closed task]]\n"
                                "* Plan\n** DONE Own child\n:PROPERTIES:\n:CUSTOM_ID: "
                                "dddddddd-4444-4444-8444-dddddddddddd\n:END:\n"
                                "- Preserve this spacing too.\n\n"
                                "*** DONE Own nested task\n:PROPERTIES:\n:CUSTOM_ID: "
                                "eeeeeeee-5555-4555-8555-eeeeeeeeeeee\n:END:\n")]
            (spit unrelated-path unrelated)
            (when linked? (spit own-path own-record))
            (spit local-path (str "#+SELECTED: " (when selected? id) "\n"))
            (case source
              :shared (spit tasks-path (str original-index "\n" target))
              :shared-only
              (do (spit (path "other-tasks.org") original-index)
                  (spit tasks-path (str tasks-org-preamble "#+IMPORT: other-tasks.org\n"
                                       "\n* Improvements\n" target)))
              :imported-only
              (do (spit tasks-path (str "#+IMPORT: target.org\n" original-index))
                  (spit source-path (str tasks-org-preamble "* Plan\n" target))))
            (is (not= unrelated
                      (parser/serialize-tasks-preserving-file
                        unrelated (:tasks (parser/parse-tasks unrelated))))
                "control: canonical serialization changes the unrelated fixture")
            (let [files (mapv path ["TASKS.org" "TASKS.local.org" "TASKS.setup.org"
                                   "TASKS.archive.org" "other-tasks.org" "target.org"
                                   "design/log/linked-plan.org" "design/log/own.org"])
                  snapshot (fn [] (into {} (map (fn [p] [p (loader/safe-slurp p)])) files))
                  before (snapshot)
                  writes (atom #{})
                  atomic-write loader/atomic-write
                  {:keys [out exit]}
                  (with-redefs [loader/atomic-write
                                (fn [p content]
                                  (swap! writes conj (str p))
                                  (atomic-write p content))]
                    (apply run-cli! "--root" root "--format" "json" "archive" id
                           (when dry-run? ["--dry-run"])))
                  envelope (json/parse-string out true)
                  result (:result envelope)
                  after (snapshot)
                  allowed (if dry-run? #{}
                              (cond-> #{source-path archive-path}
                                linked? (conj own-path)
                                selected? (conj local-path)))]
              (is (zero? exit) out)
              (is (true? (:ok envelope)))
              (is (= id (get-in result [:task :id])))
              (is (= (and selected? (not dry-run?)) (:selectionCleared result)))
              (is (= allowed @writes) "only the intended files are written")
              (is (= (apply dissoc before allowed) (apply dissoc after allowed))
                  "all files outside the write set remain byte-identical")
              (if dry-run?
                (is (= before after))
                (do
                  (is (not (str/includes? (get after source-path) (str ":CUSTOM_ID: " id))))
                  (is (str/includes? (get after archive-path) (str ":CUSTOM_ID: " id)))
                  (is (str/includes? (get after archive-path) ":ARCHIVED:"))
                  (when linked?
                    (is (= (str/replace own-record (str "[[task:" id) (str "[[archive:" id))
                           (get after own-path))
                        "the owned record changes only its parent link"))
                  (when selected?
                    (is (= "" (get after local-path)))))))))))))

(deftest archive-mutator-content-projection-is-compact-by-default-and-opt-in
  (with-temp-dir
    (fn [root]
      (let [id "closed-aaa-bbbb-cccc-dddddddddddd"]
        (spit (str (fs/path root "TASKS.org"))
              (str "* Improvements\n** DONE Closed task\nCLOSED: [2026-05-01 Fri 09:00]\n"
                   ":PROPERTIES:\n:CUSTOM_ID: " id "\n:END:\n"))
        (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n")
        (let [compact (parse-json-result
                       (:out (run-cli! "--root" root "--format" "json" "--dry-run"
                                       "archive" id)))
              expanded (parse-json-result
                        (:out (run-cli! "--root" root "--format" "json" "--dry-run"
                                        "archive" id "--include-content")))]
          (is (not (contains? (:task compact) :sourceContent)))
          (is (not (contains? (:task compact) :effectiveSourceContent)))
          (is (contains? (:task expanded) :sourceContent))
          (is (contains? (:task expanded) :effectiveSourceContent)))))))

(deftest unarchive-mutator-content-projection-is-compact-by-default-and-opt-in
  (with-temp-dir
    (fn [root]
      (let [id "closed-aaa-bbbb-cccc-dddddddddddd"
            archive-path (str (fs/path root "TASKS.archive.org"))]
        (spit (str (fs/path root "TASKS.org")) "* Improvements\n")
        (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n")
        (spit archive-path
              (str "* DONE Closed task\n:PROPERTIES:\n:CUSTOM_ID: " id
                   "\n:ARCHIVE_OLPATH: Improvements\n:END:\n"))
        (let [compact (parse-json-result
                       (:out (run-cli! "--root" root "--format" "json" "--dry-run"
                                       "unarchive" id)))
              expanded (parse-json-result
                        (:out (run-cli! "--root" root "--format" "json" "--dry-run"
                                        "unarchive" id "--include-content")))]
          (is (not (contains? (:task compact) :sourceContent)))
          (is (not (contains? (:task compact) :effectiveSourceContent)))
          (is (contains? (:task expanded) :sourceContent))
          (is (contains? (:task expanded) :effectiveSourceContent)))))))

(deftest archive-records-source-section
  (with-temp-dir
    (fn [root]
      (let [id "closed-aaa-bbbb-cccc-dddddddddddd"]
        (spit (str (fs/path root "TASKS.org"))
              (str "* Repairs\n** DONE Closed task\nCLOSED: [2026-05-01 Fri 09:00]\n:PROPERTIES:\n:CUSTOM_ID: " id "\n:END:\n"))
        (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n")
        (run-cli! "--root" root "archive" id)
        (is (str/includes? (slurp (str (fs/path root "TASKS.archive.org"))) ":ARCHIVE_OLPATH: Repairs"))))))

(deftest archive-omits-source-section-for-imported-record-roots
  (testing "a record-internal level-1 heading is never a valid restore destination"
    (with-temp-dir
      (fn [root]
        (let [id "11111111-2222-4333-8444-555555555551"
              archive-path (str (fs/path root "TASKS.archive.org"))]
          (fs/create-dirs (str (fs/path root "design" "log")))
          (spit (str (fs/path root "TASKS.org"))
                (str "#+TITLE: Tasks\n#+SETUPFILE: ./TASKS.local.org\n"
                     "#+IMPORT: design/log/root.org\n\n* Improvements\n"))
          (spit (str (fs/path root "design" "log" "root.org"))
                (str "#+TITLE: Root\n\n* Plan\n\n"
                     "** DONE Imported closed task\n"
                     "CLOSED: [2026-05-01 Fri 09:00]\n"
                     ":PROPERTIES:\n:CUSTOM_ID: " id "\n:END:\n"))
          (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n")
          (let [{:keys [out exit]} (run-cli! "--root" root "--format" "json" "archive" id)
                archive (slurp archive-path)]
            (is (zero? exit) out)
            (is (str/includes? archive (str ":CUSTOM_ID: " id)))
            (is (not (str/includes? archive ":ARCHIVE_OLPATH:"))))
          (testing "default unarchive refuses instead of inferring a section"
            (let [before (slurp archive-path)
                  {:keys [err exit]} (run-cli! "--root" root "--format" "json" "unarchive" id)
                  error (parse-json-error err)]
              (is (= 1 exit))
              (is (= "validation" (:code error)))
              (is (str/includes? (:message error) "ARCHIVE_OLPATH"))
              (is (= before (slurp archive-path)))))
          (testing "explicit --section restores into shared TASKS.org"
            (let [{:keys [out exit]} (run-cli! "--root" root "--format" "json"
                                              "unarchive" id "--section" "Improvements")
                  result (parse-json-result out)]
              (is (zero? exit) out)
              (is (= "Improvements" (:section result)))
              (is (= "--section" (:sectionSource result)))
              (is (str/includes? (slurp (str (fs/path root "TASKS.org"))) (str ":CUSTOM_ID: " id)))
              (is (not (str/includes? (slurp archive-path) (str ":CUSTOM_ID: " id)))))))))))

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

(deftest unarchive-restores-archived-subtree-without-reopening
  (with-temp-dir
    (fn [root]
      (let [id "closed-aaa-bbbb-cccc-dddddddddddd"
            child "child-aaa-bbbb-cccc-dddddddddddd"]
        (spit (str (fs/path root "TASKS.org"))
              (str "* Improvements\n** DONE Closed task\nCLOSED: [2026-05-01 Fri 09:00]\n:PROPERTIES:\n:CUSTOM_ID: " id "\n:END:\n:LOGBOOK:\n- State \\\"DONE\\\" from \\\"TODO\\\" [2026-05-01 Fri 09:00]\n:END:\nBody\n*** TODO Child\n:PROPERTIES:\n:CUSTOM_ID: " child "\n:END:\n"))
        (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n")
        (run-cli! "--root" root "archive" id)
        (let [{:keys [out exit]} (run-cli! "--root" root "--format" "json" "unarchive" "closed-aa")
              r (parse-json-result out)
              tasks (slurp (str (fs/path root "TASKS.org")))
              archive (slurp (str (fs/path root "TASKS.archive.org")))]
          (is (zero? exit))
          (is (= "Improvements" (:section r)))
          (is (str/includes? tasks (str ":CUSTOM_ID: " id)))
          (is (str/includes? tasks (str ":CUSTOM_ID: " child)))
          (is (str/includes? tasks "CLOSED: [2026-05-01 Fri 09:00]"))
          (is (not (str/includes? tasks ":ARCHIVED:")))
          (is (not (str/includes? archive (str ":CUSTOM_ID: " id)))))))))

(deftest unarchive-dry-run-and-missing-section-do-not-write
  (with-temp-dir
    (fn [root]
      (let [id "closed-aaa-bbbb-cccc-dddddddddddd"
            archive-path (str (fs/path root "TASKS.archive.org"))]
        (spit (str (fs/path root "TASKS.org")) "* Improvements\n")
        (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n")
        (spit archive-path (str "* DONE Legacy\n:PROPERTIES:\n:CUSTOM_ID: " id "\n:END:\n"))
        (let [before (slurp archive-path)
              {:keys [err exit]} (run-cli! "--root" root "--format" "json" "unarchive" id)]
          (is (= 1 exit))
          (is (str/includes? err "ARCHIVE_OLPATH"))
          (is (= before (slurp archive-path))))
        (spit archive-path (str "* DONE Legacy\n:PROPERTIES:\n:CUSTOM_ID: " id "\n:ARCHIVE_OLPATH: Improvements\n:END:\n"))
        (let [before (slurp archive-path)
              {:keys [exit]} (run-cli! "--root" root "--format" "json" "--dry-run" "unarchive" id)]
          (is (zero? exit))
          (is (= before (slurp archive-path))))))))

(deftest unarchive-uses-explicit-section-for-legacy-archive
  (with-temp-dir
    (fn [root]
      (let [id "legacy-aaa-bbbb-cccc-dddddddddddd"
            archive-path (str (fs/path root "TASKS.archive.org"))]
        (spit (str (fs/path root "TASKS.org")) "* Repairs\n")
        (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n")
        (spit archive-path (str "* DONE Legacy\n:PROPERTIES:\n:CUSTOM_ID: " id "\n:ARCHIVED: [2026-05-01 Fri 09:00]\n:END:\n"))
        (let [{:keys [out exit]} (run-cli! "--root" root "--format" "json" "unarchive" id "--section" "Repairs")
              result (parse-json-result out)]
          (is (zero? exit) out)
          (is (= "Repairs" (:section result)))
          (is (= "--section" (:sectionSource result)))
          (is (str/includes? (slurp (str (fs/path root "TASKS.org"))) (str ":CUSTOM_ID: " id)))
          (is (not (str/includes? (slurp archive-path) (str ":CUSTOM_ID: " id)))))))))

(deftest unarchive-refuses-an-active-duplicate
  (with-temp-dir
    (fn [root]
      (let [id "duplicate-aaa-bbbb-cccc-dddddddddddd"
            tasks-path (str (fs/path root "TASKS.org"))
            archive-path (str (fs/path root "TASKS.archive.org"))]
        (spit tasks-path (str "* Improvements\n** TODO Active duplicate\n:PROPERTIES:\n:CUSTOM_ID: " id "\n:END:\n"))
        (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n")
        (spit archive-path (str "* DONE Archived duplicate\n:PROPERTIES:\n:CUSTOM_ID: " id "\n:ARCHIVE_OLPATH: Improvements\n:END:\n"))
        (let [tasks-before (slurp tasks-path)
              archive-before (slurp archive-path)
              {:keys [err exit]} (run-cli! "--root" root "--format" "json" "unarchive" id)
              error (parse-json-error err)]
          (is (= 1 exit))
          (is (= "validation" (:code error)))
          (is (= tasks-before (slurp tasks-path)))
          (is (= archive-before (slurp archive-path))))))))

(deftest unarchive-rewrites-linked-record-parent
  (with-temp-dir
    (fn [root]
      (let [id "linked-aaa-bbbb-cccc-dddddddddddd"
            record-path (str (fs/path root "design" "log" "linked.org"))
            archive-path (str (fs/path root "TASKS.archive.org"))]
        (fs/create-dirs (str (fs/path root "design" "log")))
        (spit (str (fs/path root "TASKS.org")) "* Improvements\n")
        (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n")
        (spit record-path (str "#+TITLE: Linked\n#+PARENT: [[archive:" id "][Archived task]]\n"))
        (spit archive-path (str "* DONE Archived task\n:PROPERTIES:\n:CUSTOM_ID: " id "\n:ARCHIVE_OLPATH: Improvements\n:END:\n#+IMPORT: [[file:design/log/linked.org]]\n"))
        (let [{:keys [out exit]} (run-cli! "--root" root "--format" "json" "unarchive" id)
              result (parse-json-result out)]
          (is (zero? exit) out)
          (is (= (str "task:" id) (get-in result [:planRewrite :to])))
          (is (str/includes? (slurp record-path) (str "[[task:" id "]")))
          (is (not (str/includes? (slurp archive-path) (str ":CUSTOM_ID: " id)))))))))

(deftest unarchive-write-failures-retain-a-recoverable-copy
  (doseq [{:keys [stage linked?]} [{:stage :destination}
                                  {:stage :record :linked? true}
                                  {:stage :archive :linked? true}]]
    (testing (name stage)
      (with-temp-dir
        (fn [root]
          (let [id "failure-aaa-bbbb-cccc-dddddddddddd"
                tasks-path (str (fs/path root "TASKS.org"))
                archive-path (str (fs/path root "TASKS.archive.org"))
                record-path (str (fs/path root "design" "log" "linked.org"))]
            (spit tasks-path "* Improvements\n")
            (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n")
            (when linked?
              (fs/create-dirs (str (fs/path root "design" "log")))
              (spit record-path (str "#+PARENT: [[archive:" id "][Archived task]]\n")))
            (spit archive-path
                  (str "* DONE Archived task\n:PROPERTIES:\n:CUSTOM_ID: " id "\n:ARCHIVE_OLPATH: Improvements\n:END:\n"
                       (when linked? "#+IMPORT: [[file:design/log/linked.org]]\n")))
            (let [fail-path (case stage
                              :destination tasks-path
                              :record record-path
                              :archive archive-path)
                  real-atomic-write loader/atomic-write
                  {:keys [err exit]}
                  (with-redefs [loader/atomic-write
                                (fn [path content]
                                  (if (= path fail-path)
                                    (throw (ex-info "injected write failure" {:code :unreadable :file path}))
                                    (real-atomic-write path content)))]
                    (run-cli! "--root" root "--format" "json" "unarchive" id))
                  tasks (slurp tasks-path)
                  archive (slurp archive-path)]
              (is (= 1 exit))
              (is (= "unreadable" (:code (parse-json-error err))))
              (is (or (str/includes? tasks (str ":CUSTOM_ID: " id))
                      (str/includes? archive (str ":CUSTOM_ID: " id))))
              (case stage
                :destination (is (not (str/includes? tasks (str ":CUSTOM_ID: " id))))
                :record (is (str/includes? tasks (str ":CUSTOM_ID: " id)))
                :archive (do
                           (is (str/includes? tasks (str ":CUSTOM_ID: " id)))
                           (is (str/includes? (slurp record-path) (str "[[task:" id "]"))))))))))))

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

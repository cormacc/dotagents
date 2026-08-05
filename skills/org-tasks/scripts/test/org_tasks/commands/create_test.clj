(ns org-tasks.commands.create-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org-tasks.commands.test-util :refer :all]
            [org-tasks.parser :as parser]
            [org-tasks.styling :as styling]))

;; ── create ───────────────────────────────────────────

(deftest create-inserts-under-section
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [{:keys [out exit]}
            (run-cli! "--root" root "--format" "json"
                      "create" "New feature"
                      "--priority" "High"
                      "--tag" "backend")
            r (parse-json-result out)
            content (slurp (str (fs/path root "TASKS.org")))]
        (is (zero? exit))
        (is (some? (:id r)))
        (is (str/includes? content "** TODO [#B] New feature :backend:"))
        (is (str/includes? content (str ":CUSTOM_ID: " (:id r))))
        (is (str/includes? content ":CREATED:"))
        (is (str/includes? content ":LOGBOOK:"))
        (is (str/includes? content "- Created "))))))

(deftest create-empty-summary-errors
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [{:keys [err exit]}
            (run-cli! "--root" root "--format" "json" "create" "  ")
            e (parse-json-error err)]
        (is (= 1 exit))
        (is (= "empty-summary" (:code e)))))))

(deftest create-invalid-tag-errors
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [{:keys [err exit]}
            (run-cli! "--root" root "--format" "json"
                      "create" "Hyphen tag" "--tag" "skill_herdr-orch")
            e (parse-json-error err)
            content (slurp (str (fs/path root "TASKS.org")))]
        (is (= 1 exit))
        (is (= "invalid-tag" (:code e)))
        ;; The write must be refused, not persisted with an unqueryable tag.
        (is (not (str/includes? content "Hyphen tag")))))))

(deftest create-normalises-wrapped-tag
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [{:keys [out exit]}
            (run-cli! "--root" root "--format" "json"
                      "create" "Wrapped tag" "--tag" ":backend:")
            r (parse-json-result out)
            content (slurp (str (fs/path root "TASKS.org")))]
        (is (zero? exit))
        (is (some? (:id r)))
        (is (str/includes? content "** TODO Wrapped tag :backend:"))))))

(deftest create-duplicate-linked-issue-refused
  (with-temp-dir
    (fn [root]
      (spit (str (fs/path root "TASKS.org"))
            (str "* Improvements\n"
                 "** TODO Existing clone\n"
                 ":PROPERTIES:\n"
                 ":CUSTOM_ID: 11111111-2222-4333-8444-555555555551\n"
                 ":LINKED_ISSUES: [[jira:ABC-1]] [[jira:ABC-2]]\n"
                 ":END:\n"))
      (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n")
      (let [{:keys [err exit]}
            (run-cli! "--root" root "--format" "json"
                      "create" "Re-clone"
                      "--linked-issue" "[[jira:ABC-1]]")
            e (parse-json-error err)]
        (is (= 1 exit))
        (is (= "duplicate-linked-issue" (:code e)))
        (is (= "[[jira:ABC-1]]" (get-in e [:details :conflictingToken])))))))

(deftest create-missing-section-errors
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [{:keys [err exit]}
            (run-cli! "--root" root "--format" "json"
                      "create" "Whatever" "--section" "NoSuch")
            e (parse-json-error err)]
        (is (= 1 exit))
        (is (= "section-not-found" (:code e)))))))

(deftest create-allow-create-section-appends
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [{:keys [out exit]}
            (run-cli! "--root" root "--format" "json"
                      "create" "Fresh" "--section" "Brand New"
                      "--allow-create-section")
            r (parse-json-result out)
            content (slurp (str (fs/path root "TASKS.org")))]
        (is (zero? exit))
        (is (str/includes? content "* Brand New"))
        (is (str/includes? content "** TODO Fresh"))))))

(deftest create-dry-run-does-not-write
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [before (slurp (str (fs/path root "TASKS.org")))]
        (run-cli! "--root" root "--format" "json"
                  "--dry-run" "create" "Dry-run task")
        (is (= before (slurp (str (fs/path root "TASKS.org")))))))))

(deftest create-after-inserts-below-anchor
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [{:keys [exit]}
            (run-cli! "--root" root "--format" "json"
                      "create" "Between" "--after"
                      "11111111-2222-4333-8444-555555555551")
            content (slurp (str (fs/path root "TASKS.org")))]
        (is (zero? exit))
        (is (< (str/index-of content "** TODO [#A] First")
               (str/index-of content "** TODO Between")
               (str/index-of content "** STARTED Second")))))))

(deftest create-parent-inserts-child-task
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [{:keys [out exit]}
            (run-cli! "--root" root "--format" "json"
                      "create" "Child task" "--parent"
                      "11111111-2222-4333-8444-555555555551")
            r (parse-json-result out)
            content (slurp (str (fs/path root "TASKS.org")))]
        (is (zero? exit))
        (is (some? (:id r)))
        (is (str/includes? content "*** TODO Child task"))
        (is (< (str/index-of content "** TODO [#A] First")
               (str/index-of content "*** TODO Child task")
               (str/index-of content "** STARTED Second")))))))
(deftest create-relative-to-sibling-inserts-at-anchor-level
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [{:keys [exit]}
            (run-cli! "--root" root "--format" "json"
                      "create" "Rel sibling"
                      "--relative-to" "11111111-2222-4333-8444-555555555551"
                      "--as" "sibling")
            content (slurp (str (fs/path root "TASKS.org")))]
        (is (zero? exit))
        (is (str/includes? content "** TODO Rel sibling"))
        (is (< (str/index-of content "** TODO [#A] First")
               (str/index-of content "** TODO Rel sibling")
               (str/index-of content "** STARTED Second")))))))

(deftest create-relative-to-child-nests-under-anchor
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [{:keys [exit]}
            (run-cli! "--root" root "--format" "json"
                      "create" "Rel child"
                      "--relative-to" "11111111-2222-4333-8444-555555555551"
                      "--as" "child")
            content (slurp (str (fs/path root "TASKS.org")))]
        (is (zero? exit))
        (is (str/includes? content "*** TODO Rel child"))
        (is (< (str/index-of content "** TODO [#A] First")
               (str/index-of content "*** TODO Rel child")
               (str/index-of content "** STARTED Second")))))))

(deftest create-parent-honours-tasks-source-override
  (with-temp-dir
    (fn [root]
      (fs/create-dirs (str (fs/path root "design" "log")))
      (let [plan-path (str (fs/path root "design" "log" "feature.org"))
            parent-id "plan-parent-1111-4222-8333-444444444444"]
        (spit plan-path
              (str "* Plan\n"
                   "** TODO Plan parent\n"
                   ":PROPERTIES:\n"
                   ":CUSTOM_ID: " parent-id "\n"
                   ":END:\n"))
        (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n")
        (let [{:keys [out exit]}
              (run-cli! "--root" root "--tasks" plan-path "--format" "json"
                        "create" "Plan child" "--parent" parent-id)
              r (parse-json-result out)
              content (slurp plan-path)]
          (is (zero? exit))
          (is (some? (:id r)))
          (is (= plan-path (:file r)))
          (is (str/includes? content "*** TODO Plan child")))))))


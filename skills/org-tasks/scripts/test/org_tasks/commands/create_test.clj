(ns org-tasks.commands.create-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org-tasks.commands.test-util :refer :all]
            [org-tasks.commands.create :as create]
            [org-tasks.root :as root]
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

(deftest create-local-separates-selector-from-paths
  (doseq [placement [[] ["--parent" linked-plan-child-id]
                     ["--after" linked-plan-child-id]
                     ["--relative-to" linked-plan-child-id "--as" "child"]]]
    (with-temp-dir
      (fn [root]
        (bootstrap-linked-plan-graph! root)
        (spit (str (fs/path root "TASKS.local.org")) "* Improvements\n")
        (let [shared (str (fs/path root "TASKS.org"))
              local (str (fs/path root "TASKS.local.org"))
              before (slurp shared)
              {:keys [out exit]} (apply run-cli! "--root" root "--format" "json"
                                       "create" "Local task" "--local" placement)
              result (parse-json-result out)]
          (is (zero? exit))
          (is (= (if (seq placement) (str (fs/path root "design/log/linked-plan.org")) local)
                 (:file result)))
          (is (= before (slurp shared)))
          (is (str/includes? (slurp (:file result)) "TODO Local task")))))))

(deftest create-local-direct-handler-and-pi-shapes
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [local (str (fs/path root "TASKS.local.org"))]
        (spit local "* Improvements\n")
        (doseq [invoke [(fn [] (run-cli! "--root" root "--format" "json" "create"
                                         "--local" "--body=text" "--" "- pi summary"))
                        (fn [] (capture #(create/create-cmd
                                            {:opts {:root root :format :json :local true
                                                     :summary "Direct summary"}})))]]
          (let [{:keys [out exit]} (invoke)]
            (is (zero? exit))
            (is (= local (:file (parse-json-result out))))))
        (is (str/includes? (slurp local) "TODO - pi summary"))
        (is (str/includes? (slurp local) "TODO Direct summary"))))))

(deftest protocol-path-maps-retain-overrides
  (with-temp-dir
    (fn [dir]
      (let [opts (into {} (map (fn [k] [k (str (fs/path dir (str (name k) ".org")))])
                               [:tasks :local :archive]))]
        (is (= opts (root/resolve-protocol-files opts dir)))
        (let [{:keys [out exit]} (run-cli! "--root" dir "--format" "json" "list"
                                          "--tasks" (:tasks opts) "--archive" (:archive opts))
              files (:files (parse-json-result out))]
          (is (zero? exit))
          (is (= (:tasks opts) (:tasks files)))
          (is (= (:archive opts) (:archive files)))
          (is (= (str (fs/path dir "TASKS.local.org")) (:local files))))))))

(deftest create-preserves-numeric-looking-text
  (doseq [summary ["1e3" "- summary"]
          delimiter? [true false]
          :when (or delimiter? (= summary "1e3"))]
    (with-temp-dir
      (fn [root]
        (bootstrap-graph! root)
        (let [argv (if delimiter? ["create" "--body=1e3" "--" summary]
                       ["create" summary "--body=1e3"])
              {:keys [out exit]} (apply run-cli! "--root" root "--format" "json" argv)
              id (:id (parse-json-result out))
              shown (run-cli! "--root" root "--format" "json" "show" id)
              task (:task (parse-json-result (:out shown)))]
          (is (zero? exit))
          (is (zero? (:exit shown)))
          (is (= summary (:summary task)))
          (is (= "1e3" (:description task))))))))

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

(deftest create-body-heading-lines-are-indented
  ;; A body from another tracker (for example a Jira markdown description) can
  ;; contain `* item` bullets. At column 0 org parses them as headings, so the
  ;; new task's body would split into spurious top-level tasks.
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [body "Intro\n\n* a\n** b\n*bold* text\n * c"
            {:keys [out exit]}
            (run-cli! "--root" root "--format" "json"
                      "create" "Bullet body" (str "--body=" body))
            r (parse-json-result out)
            content (slurp (str (fs/path root "TASKS.org")))
            body-lines (set (str/split-lines content))]
        (is (zero? exit))
        (is (some? (:id r)))
        (is (contains? body-lines " * a") "a `* ` line becomes an indented list item")
        (is (contains? body-lines " ** b") "a `** ` line is indented too")
        (is (contains? body-lines "*bold* text") "a star without a following space is not a heading and is kept")
        (is (contains? body-lines " * c") "an already indented line is kept")
        (is (not-any? #{"* a" "** b"} body-lines) "no body line remains at column 0 as a heading")
        (is (= #{"* Improvements" "** TODO [#A] First :backend:" "** STARTED Second" "** TODO Bullet body"}
               (set (filter #(re-matches #"^\*+\s.*" %) (str/split-lines content))))
            "the file's heading lines are the fixture's plus the new task's"))))
  (testing "a CRLF body's star-only line is indented too"
    ;; The scanner splits on \n, so `*\r` matches its `^(\*+)\s+` heading rule.
    (with-temp-dir
      (fn [root]
        (bootstrap-graph! root)
        (let [{:keys [out exit]}
              (run-cli! "--root" root "--format" "json"
                        "create" "--body=intro\r\n*\r\nafter" "--" "CRLF body")
              id (:id (parse-json-result out))
              shown (run-cli! "--root" root "--format" "json" "show" id)]
          (is (zero? exit))
          (is (= "intro\r\n *\r\nafter" (get-in (parse-json-result (:out shown)) [:task :description]))
              "the whole body survives as the task description")))))
  (testing "`--body=<text>` accepts a body that starts with a dash"
    (with-temp-dir
      (fn [root]
        (bootstrap-graph! root)
        (let [{:keys [exit]}
              (run-cli! "--root" root "--format" "json"
                        "create" "Dash body" "--body=- one\n- two")
              content (slurp (str (fs/path root "TASKS.org")))]
          (is (zero? exit))
          (is (str/includes? content "- one\n- two")))))))

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

(deftest create-imported-anchor-preserves-duplicate-checks
  (doseq [owner-name ["TASKS.org" "TASKS.local.org"]
          placement [["--parent" linked-plan-child-id]
                     ["--after" linked-plan-child-id]
                     ["--relative-to" linked-plan-child-id "--as" "child"]]]
    (with-temp-dir
      (fn [root]
        (bootstrap-linked-plan-graph! root)
        (let [files (mapv #(str (fs/path root %))
                         ["TASKS.org" "TASKS.local.org" "design/log/linked-plan.org"])
              owner (str (fs/path root owner-name))]
          (spit owner (str (slurp owner)
                           "\n** TODO Existing clone\n:PROPERTIES:\n"
                           ":CUSTOM_ID: " (random-uuid) "\n"
                           ":LINKED_ISSUES: [[jira:ABC-1]]\n:END:\n"))
          (let [before (mapv slurp files)
                {:keys [err exit]}
                (apply run-cli! "--root" root "--format" "json"
                       "create" "Duplicate" "--linked-issue" "[[jira:ABC-1]]" placement)]
            (is (= 1 exit))
            (is (= "duplicate-linked-issue" (:code (parse-json-error err))))
            (is (= before (mapv slurp files)))))))))

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
                      "--as" "child" "--parent" "ignored" "--after" "ignored")
            content (slurp (str (fs/path root "TASKS.org")))]
        (is (zero? exit))
        (is (str/includes? content "*** TODO Rel child"))
        (is (< (str/index-of content "** TODO [#A] First")
               (str/index-of content "*** TODO Rel child")
               (str/index-of content "** STARTED Second")))))))

(deftest create-parent-resolves-a-task-inside-a-linked-record
  (with-temp-dir
    (fn [root]
      (bootstrap-linked-plan-graph! root)
      (let [plan-path (str (fs/path root "design" "log" "linked-plan.org"))
            {:keys [out exit]}
            (run-cli! "--root" root "--format" "json"
                      "create" "Plan grandchild" "--parent"
                      (subs linked-plan-child-id 0 8))
            r (parse-json-result out)]
        (is (zero? exit))
        (is (= plan-path (:file r)))
        (is (str/includes? (slurp plan-path) "*** TODO Plan grandchild"))
        (is (not (str/includes? (slurp (str (fs/path root "TASKS.org")))
                                "Plan grandchild")))))))

(deftest create-after-resolves-a-task-inside-a-linked-record
  (with-temp-dir
    (fn [root]
      (bootstrap-linked-plan-graph! root)
      (let [plan-path (str (fs/path root "design" "log" "linked-plan.org"))
            {:keys [exit]}
            (run-cli! "--root" root "--format" "json"
                      "create" "Between plan children" "--after" linked-plan-child-id)
            content (slurp plan-path)]
        (is (zero? exit))
        (is (< (str/index-of content "** TODO Plan child")
               (str/index-of content "** TODO Between plan children")
               (str/index-of content "** TODO Second plan child")))))))

(deftest create-parent-unknown-id-still-errors
  (with-temp-dir
    (fn [root]
      (bootstrap-linked-plan-graph! root)
      (let [before (slurp (str (fs/path root "TASKS.org")))
            {:keys [err exit]}
            (run-cli! "--root" root "--format" "json"
                      "create" "Orphan" "--parent" "9999abcd")
            e (parse-json-error err)]
        (is (= 1 exit))
        (is (= "unknown-task" (:code e)))
        (is (= before (slurp (str (fs/path root "TASKS.org")))))))))

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


(ns org-tasks.commands.links-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org-tasks.commands.test-util :refer :all]
            [org-tasks.loader :as loader]
            [org-tasks.parser :as parser]
            [org-tasks.styling :as styling]))

;; ── handoff ───────────────────────────────────────────

(deftest handoff-set-get-clear
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [id "11111111-2222-4333-8444-555555555551"]
        (testing "set"
          (let [{:keys [out exit]}
                (run-cli! "--root" root "--format" "json"
                          "handoff" "set" id "Start here next session")
                r (parse-json-result out)]
            (is (zero? exit))
            (is (= {:taskId id
                    :handoff "Start here next session"}
                   r))
            (is (str/includes? (slurp (str (fs/path root "TASKS.org")))
                               ":HANDOFF: Start here next session"))))
        (testing "get"
          (let [{:keys [out]} (run-cli! "--root" root "--format" "json"
                                        "handoff" "get" id)
                r (parse-json-result out)]
            (is (= "Start here next session" (:handoff r)))))
        (testing "clear"
          (let [{:keys [out]} (run-cli! "--root" root "--format" "json"
                                        "handoff" "clear" id)
                r (parse-json-result out)]
            (is (= {:taskId id
                    :handoff nil}
                   r))
            (is (not (str/includes? (slurp (str (fs/path root "TASKS.org")))
                                    ":HANDOFF:")))))))))

;; ── blocker + ready ────────────────────────────────────────

(deftest blocker-add-list-remove-ready
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [target "11111111-2222-4333-8444-555555555551"
            other  "22229999-2222-4333-8444-555555555552"]
        (testing "ready before blockers: ready=true"
          (let [{:keys [out]} (run-cli! "--root" root "--format" "json"
                                        "ready" target)
                r (parse-json-result out)]
            (is (true? (:ready r)))))
        (testing "add blocker referencing another task"
          (let [{:keys [out exit]}
                (run-cli! "--root" root "--format" "json"
                          "blocker" "add" target (str "task:" other))
                r (parse-json-result out)]
            (is (zero? exit))
            (is (= target (:taskId r)))
            (is (= [{:raw (str "task:" other) :kind "task" :ref other}]
                   (:blockers r)))
            (is (str/includes? (slurp (str (fs/path root "TASKS.org")))
                               (str ":BLOCKED-BY: task:" other)))))
        (testing "ready after blocker on STARTED dep: not ready"
          (let [{:keys [out]} (run-cli! "--root" root "--format" "json"
                                        "ready" target)
                r (parse-json-result out)]
            (is (false? (:ready r)))
            (is (= 1 (count (:gating r))))
            (is (= "STARTED" (get-in r [:gating 0 :reason])))))
        (testing "list shows the blocker"
          (let [{:keys [out]} (run-cli! "--root" root "--format" "json"
                                        "blocker" "list" target)
                r (parse-json-result out)]
            (is (= 1 (count (:blockers r))))
            (is (= "task" (get-in r [:blockers 0 :kind])))))
        (testing "remove restores ready"
          (let [{:keys [out exit]} (run-cli! "--root" root "--format" "json"
                                             "blocker" "remove" target (str "task:" other))
                r (parse-json-result out)]
            (is (zero? exit))
            (is (= target (:taskId r)))
            (is (= [] (:blockers r))))
          (let [{:keys [out]} (run-cli! "--root" root "--format" "json"
                                        "ready" target)
                r (parse-json-result out)]
            (is (true? (:ready r)))))
        (testing "remove keeps raw unprefixed token semantics"
          (run-cli! "--root" root "--format" "json" "blocker" "add" target "plain human")
          (let [{:keys [out exit]} (run-cli! "--root" root "--format" "json"
                                             "blocker" "remove" target "plain human")
                r (parse-json-result out)]
            (is (zero? exit))
            (is (= [{:raw "human: plain human" :kind "human" :ref "plain human"}]
                   (:blockers r)))))))))

(deftest blocker-add-preserves-bare-full-uuid
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [target "11111111-2222-4333-8444-555555555551"
            dep "22229999-2222-4333-8444-555555555552"
            {:keys [out exit]} (run-cli! "--root" root "--format" "json" "blocker" "add" target dep)
            r (parse-json-result out)]
        (is (zero? exit))
        (is (= [{:raw dep :kind "task" :ref dep}] (:blockers r)))
        (is (str/includes? (slurp (str (fs/path root "TASKS.org"))) (str ":BLOCKED-BY: " dep)))))))

;; ── issue ──────────────────────────────────────────────

(deftest issue-add-list-remove-urls
  (with-temp-dir
    (fn [root]
      (spit (str (fs/path root "TASKS.setup.org")) setup-org-preamble)
      (spit (str (fs/path root "TASKS.org"))
            (str tasks-org-preamble
                 "#+LINK: jira https://example.atlassian.net/browse/%s\n\n"
                 "* Improvements\n"
                 "** TODO Task\n"
                 ":PROPERTIES:\n"
                 ":CUSTOM_ID: 22222222-2222-4333-8444-555555555551\n"
                 ":END:\n"))
      (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n")
      (let [id "22222222-2222-4333-8444-555555555551"]
        (testing "add returns raw token strings"
          (let [{:keys [out exit]}
                (run-cli! "--root" root "--format" "json" "issue" "add" id "[[jira:ABC-1]]")
                r (parse-json-result out)]
            (is (zero? exit))
            (is (= {:taskId id
                    :tokens ["[[jira:ABC-1]]"]}
                   r))))
        (testing "list returns the added token with resolved URL"
          (let [{:keys [out]} (run-cli! "--root" root "--format" "json"
                                        "issue" "list" id)
                r (parse-json-result out)]
            (is (= 1 (count (:issues r))))
            (is (= "[[jira:ABC-1]]" (get-in r [:issues 0 :rawToken])))
            (is (= "https://example.atlassian.net/browse/ABC-1"
                   (get-in r [:issues 0 :url])))))
        (testing "urls returns only resolvable URLs"
          (let [{:keys [out]} (run-cli! "--root" root "--format" "json"
                                        "issue" "urls" id)
                r (parse-json-result out)]
            (is (= ["https://example.atlassian.net/browse/ABC-1"] (:urls r)))))
        (testing "remove returns raw token strings"
          (let [{:keys [out exit]}
                (run-cli! "--root" root "--format" "json"
                          "issue" "remove" id "[[jira:ABC-1]]")
                r (parse-json-result out)]
            (is (zero? exit))
            (is (= {:taskId id
                    :tokens []}
                   r)))
          (let [{:keys [out]} (run-cli! "--root" root "--format" "json"
                                        "issue" "list" id)
                r (parse-json-result out)]
            (is (= [] (:issues r)))))))))

;; ── heading tags ───────────────────────────────────────

(deftest tag-add-remove-root-and-local
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [root-id "11111111-2222-4333-8444-555555555551"
            local-id "33333333-2222-4333-8444-555555555553"
            root-file (str (fs/path root "TASKS.org"))
            local-file (str (fs/path root "TASKS.local.org"))]
        (testing "add normalises, preserves order, and does not duplicate"
          (let [{:keys [out exit]} (run-cli! "--root" root "--format" "json"
                                             "tag" "add" root-id " :ops: ")
                r (parse-json-result out)]
            (is (zero? exit))
            (is (= {:taskId root-id :tags ["backend" "ops"]} r))
            (is (str/includes? (slurp root-file)
                               "** TODO [#A] First :backend:ops:")))
          (let [{:keys [out]} (run-cli! "--root" root "--format" "json"
                                        "tag" "add" root-id "ops")]
            (is (= ["backend" "ops"] (:tags (parse-json-result out))))))
        (testing "remove is idempotent and leaves no trailing colon"
          (run-cli! "--root" root "--format" "json" "tag" "remove" root-id "backend")
          (let [{:keys [out]} (run-cli! "--root" root "--format" "json"
                                        "tag" "remove" root-id "ops")]
            (is (= [] (:tags (parse-json-result out)))))
          (let [content (slurp root-file)]
            (is (str/includes? content "** TODO [#A] First\n"))
            (is (not (str/includes? content "First :")))))
        (testing "local owner and dry-run use the same result shape"
          (spit local-file
                (str "#+SELECTED: " local-id "\n\n* Drafts\n** STARTED Second\n"
                     ":PROPERTIES:\n:CUSTOM_ID: " local-id "\n:END:\n"))
          (let [{:keys [out]} (run-cli! "--root" root "--format" "json"
                                        "--dry-run" "tag" "add" local-id "local")]
            (is (= {:taskId local-id :tags ["local"]} (parse-json-result out)))
            (is (not (str/includes? (slurp local-file) ":local:"))))
          (run-cli! "--root" root "--format" "json" "tag" "add" local-id "local")
          (is (str/includes? (slurp local-file) "** STARTED Second :local:")))
        (testing "invalid grammar returns a compact domain error"
          (let [{:keys [err exit]} (run-cli! "--root" root "--format" "json"
                                             "tag" "add" root-id "two words")]
            (is (= 1 exit))
            (is (= "invalid-tag" (:code (parse-json-error err))))))))))

(deftest tag-mutations-persist-to-imported-owner-and-detect-conflicts
  (with-temp-dir
    (fn [root]
      (bootstrap-linked-plan-graph! root)
      (let [id linked-plan-child-id
            plan-file (str (fs/path root "design" "log" "linked-plan.org"))
            before (slurp plan-file)]
        (testing "only the imported task heading changes"
          (let [{:keys [out exit]} (run-cli! "--root" root "--format" "json"
                                             "tag" "add" id "plan")]
            (is (zero? exit))
            (is (= {:taskId id :tags ["plan"]} (parse-json-result out)))
            (is (= (str/replace before "** TODO Plan child\n"
                                "** TODO Plan child :plan:\n")
                   (slurp plan-file)))))
        (testing "a changed imported owner fails with conflict and remains untouched"
          (let [original-save loader/save-source-roots
                changed (str (slurp plan-file) "# external edit\n")]
            (with-redefs [loader/save-source-roots
                          (fn [project-root tasks & args]
                            (spit plan-file changed)
                            (apply original-save project-root tasks args))]
              (let [{:keys [err exit]} (run-cli! "--root" root "--format" "json"
                                                  "tag" "add" id "conflict")]
                (is (= 1 exit))
                (is (= "conflict" (:code (parse-json-error err))))
                (is (= changed (slurp plan-file)))))))))))

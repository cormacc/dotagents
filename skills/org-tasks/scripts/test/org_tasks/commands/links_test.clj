(ns org-tasks.commands.links-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org-tasks.commands.test-util :refer :all]
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
            (is (= "unresolved-task" (get-in r [:gating 0 :reason])))))
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

(ns herdr-subagents.core-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [herdr-subagents.cli :as cli]
            [herdr-subagents.core :as core]
            [herdr-subagents.ledger :as ledger]
            [herdr-subagents.smoke :as smoke]))

(deftest resolution-and-label-contract
  (is (= "/project/.agents/subagents/scout.md" (core/roster-path #{"/project/.agents/subagents/scout.md" "/home/u/.agents/subagents/scout.md"} "/project" "/home/u" "scout")))
  (is (= "/home/u/.agents/subagents/scout.md" (core/roster-path #{"/home/u/.agents/subagents/scout.md"} "/project" "/home/u" "scout")))
  (is (nil? (core/roster-path #{} "/project" "/home/u" "unknown")))
  (is (= "right" (core/direction {:width 160 :height 80})))
  (is (= "down" (core/direction {:width 113 :height 110})))
  (is (= "pi" (core/resolve-kind {:requested nil :frontmatter {:kind "pi"} :parent-kind "claude"})))
  (is (= "codex" (core/resolve-kind {:requested "codex" :frontmatter {:kind "pi"} :parent-kind "claude"})))
  (is (= "m" (core/resolve-model {:requested "m" :resolved-kind "pi" :frontmatter {:kind "pi" :model "f"} :parent-kind "pi" :parent-model "p"})))
  (is (= "f" (core/resolve-model {:resolved-kind "pi" :frontmatter {:kind "pi" :model "f"} :parent-kind "pi" :parent-model "p"})))
  (is (= "p" (core/resolve-model {:resolved-kind "pi" :frontmatter {} :parent-kind "pi" :parent-model "p"})))
  (is (nil? (core/resolve-model {:resolved-kind "codex" :frontmatter {:kind "pi" :model "f"} :parent-kind "pi" :parent-model "p"})))
  (is (= ["--model" "x"] (core/model-args "pi" "x")))
  (is (= [] (core/model-args "codex" "x")))
  (is (= "/tmp/persona.md" (core/persona-system-prompt "pi" "/tmp/persona.md" "BODY")))
  (is (= "BODY" (core/persona-system-prompt "claude" "/tmp/persona.md" "BODY")))
  (is (= "planner-1/scout-2-claude-fable-5" (core/child-label {:parent-label "planner-1-claude-fable-5" :parent-persona "planner" :persona "scout" :index 2 :model "anthropic/claude-fable-5"})))
  (is (= "worker-1" (core/root-label "worker" 1 nil))))

(deftest frontmatter-and-envelope-contract
  (is (= {:name "scout" :description "x" :kind "pi" :model "vendor/model"}
         (core/parse-frontmatter "---\nname: scout\ndescription: x\nkind: pi\nmodel: vendor/model\n---\nbody")))
  (let [ledger {:child "child" :task "task" :result "/tmp/result"}
        text (core/envelope (assoc ledger :status "COMPLETE" :summary "done" :artifacts [] :findings [] :next nil))]
    (is (= "COMPLETE" (:status (core/validate-envelope ledger text))))
    (is (thrown? Exception (core/validate-envelope (assoc ledger :task "wrong") text)))
    (is (= "/tmp/artifact" (core/artifact-path "/tmp/artifact — report")))
    (is (= "/tmp/artifact" (core/artifact-path "/tmp/artifact")))
    (is (thrown? Exception (core/artifact-path "relative — report")))))

(deftest planner-exception-and-smoke-success-contract
  (is (.endsWith (cli/launcher-bin) "/skills/herdr-subagents/scripts/subagent"))
  (is (re-find #"at most one blocking ephemeral scout or researcher"
               (cli/delegation-guidance "planner")))
  (is (= "You are a leaf: do not spawn subagents."
         (cli/delegation-guidance "worker")))
  (is (= {:status "COMPLETE"} (smoke/complete! {:status "COMPLETE"})))
  (is (try
        (smoke/complete! {:status "FAILED"})
        false
        (catch clojure.lang.ExceptionInfo e
          (boolean (re-find #"did not publish COMPLETE" (.getMessage e)))))))

;; Zero is truthy in Clojure and Thread/sleep rejects negatives, so both must fall back.
(deftest poll-interval-parsing
  (is (= 1000 (cli/parse-poll-interval nil)))
  (is (= 1000 (cli/parse-poll-interval "")))
  (is (= 1000 (cli/parse-poll-interval "   ")))
  (is (= 1000 (cli/parse-poll-interval "0")))
  (is (= 1000 (cli/parse-poll-interval "-5")))
  (is (= 1000 (cli/parse-poll-interval "abc")))
  (is (= 250 (cli/parse-poll-interval "250"))))

(deftest findings-limit-boundary
  (let [ledger {:child "child" :task "task" :result "/tmp/result"}
        mk (fn [n] (core/envelope (assoc ledger :status "COMPLETE" :summary "s" :artifacts []
                                         :findings (mapv #(str "finding " %) (range n)) :next nil)))]
    (is (= 5 (count (:findings (core/validate-envelope ledger (mk 5))))))
    (is (thrown? Exception (mk 6)))
    ;; A hand-written six-item envelope is rejected at validation too, not only at publish.
    (is (thrown? Exception (core/validate-envelope ledger (str/replace (mk 5) "- finding 0" "- finding x\n- finding 0"))))))

(deftest assignment-root-override-is-absolute-and-checked
  (let [tmp (str (fs/create-temp-dir {:prefix "subagent-root-"}))]
    (is (nil? (ledger/resolve-override nil)))
    (is (nil? (ledger/resolve-override "")))
    (is (nil? (ledger/resolve-override "   ")))
    (is (= (str (fs/canonicalize tmp)) (ledger/resolve-override tmp)))
    ;; A relative value is absolutised so RESULT is never relative.
    (is (fs/absolute? (fs/path (ledger/resolve-override (str (fs/relativize (fs/cwd) tmp))))))
    (is (thrown? Exception (ledger/resolve-override (str (fs/path tmp "missing")))))))

(deftest index-allocation-is-monotonic
  (let [root (str (java.nio.file.Files/createTempDirectory "subagent-index" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (with-redefs [ledger/assignment-root (constantly root)]
      (is (= #{1 2 3 4 5}
             (set (doall (pmap (fn [_] (ledger/allocate-index! "parent/session" "worker")) (range 5))))))
      (is (= 1 (ledger/allocate-index! "other/session" "worker")))
      (is (= 1 (ledger/allocate-index! "/a/b" "scout")))
      (is (= 1 (ledger/allocate-index! "/a_b" "scout"))))))

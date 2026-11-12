(ns herdr-orch.persona-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [herdr-orch.cli :as cli]))

(def root (str (fs/canonicalize (fs/path (fs/parent *file*) "../../../../.."))))

(defn- packaged-persona
  ([name] (packaged-persona name []))
  ([name explicit-traits]
   (let [directory (str (fs/path root "skills/herdr-orch/subagents"))
         path (str (fs/path directory (str name ".md")))]
     (with-redefs [cli/persona-directories (constantly [directory])
                   cli/trait-directories
                   (constantly [{:source "packaged"
                                 :directory (str (fs/path root "skills/herdr-orch/traits"))}])]
       (#'cli/trait-interpolation name path (slurp path) explicit-traits)))))

(deftest reviewer-carries-the-review-evidence-contract-in-its-body
  ;; The body owns the evidence rule: traits/grounded/gate.md records no marginal effect
  ;; for a separate trait over this body and the code-review skill.
  (let [{:keys [persona-text traits]} (packaged-persona "reviewer")]
    (is (= ["read-only"] traits))
    (is (str/includes? persona-text "Do not create, modify, or delete repository files."))
    (is (str/includes? persona-text "report only concrete issues introduced or exposed by the reviewed change"))
    (is (str/includes? persona-text "If nothing meets the bar, state **No issues found.**"))
    (is (not (fs/exists? (fs/path root "skills/herdr-orch/traits/grounded"))))
    (is (not (fs/exists? (fs/path root "skills/herdr-orch/traits/no-bullshit.md")))))
  (doseq [removed ["grounded" "no-bullshit"]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo (re-pattern (str removed ".*not found"))
                          (packaged-persona "reviewer" [removed])))))

(deftest scout-has-one-result-format
  (let [{:keys [persona-text traits frontmatter]} (packaged-persona "scout")]
    (is (= ["focused" "read-only" "compact"] traits))
    (is (= "light" (:model frontmatter)))
    (is (str/includes? persona-text "Cite only line ranges that you actually read."))
    (is (str/includes? persona-text "No relevant locations found."))
    (is (str/includes? persona-text "Use the `compact` trait's result format."))
    (is (= 1 (count (re-seq #"End with one totals line" persona-text))))
    (is (not (str/includes? persona-text "**Content template:**")))
    (is (not (str/includes? persona-text "## Key Findings")))))

(deftest advisor-has-a-consult-verdict-and-evidence-bar
  (let [{:keys [persona-text traits frontmatter]} (packaged-persona "advisor")]
    (is (= ["simple" "read-only" "focused"] traits))
    (is (= "middle" (:model frontmatter)))
    (is (= "false" (:retro frontmatter)))
    (is (str/includes? persona-text "`PROCEED`, `REVISE`, or `STOP`"))
    (is (str/includes? persona-text "Cite a file location, command result, or authoritative source for each recommendation."))
    (is (str/includes? persona-text "Do not recommend `PROCEED` on an unverified, decision-critical premise."))
    (is (str/includes? persona-text "Do not broaden the design or replace the caller's implementation work."))
    (is (str/includes? persona-text "Do not create, modify, or delete repository files."))
    (is (not (str/includes? persona-text "APPROVED")))
    (is (not (str/includes? persona-text "No issues found.")))))

(deftest shared-boundaries-retain-one-owner
  (testing "the worker retains the shared engineering stance and its own scope escalation"
    (let [{:keys [persona-text traits]} (packaged-persona "worker")]
      (is (= ["simple"] traits))
      (is (= 1 (count (re-seq #"[Mm]ake the smallest coherent change" persona-text))))
      (is (str/includes? persona-text "return `BLOCKED` and state what the larger change needs."))))
  (testing "base and derived analysts inherit the write boundary without restating it"
    (doseq [name ["base-analyst" "simplifier"]]
      (let [{:keys [persona-text traits]} (packaged-persona name)]
        (is (= ["read-only" "simple"] traits))
        (is (= 1 (count (re-seq #"caller-provided artifact" persona-text)))))))
  (testing "leaf permission comes from generated launch guidance"
    (is (= "You are a leaf: do not spawn subagents." (cli/delegation-guidance [])))))

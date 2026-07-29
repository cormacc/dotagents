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
  ;; A requested model still wins over every frontmatter and parent-model value.
  (is (= "m" (core/resolve-model {:requested "m" :resolved-kind "pi" :frontmatter {:kind "pi" :model "f"} :parent-kind "pi" :parent-model "p"})))
  ;; A declared kind/model pair remains honoured only for its declared kind.
  (is (= "f" (core/resolve-model {:resolved-kind "pi" :frontmatter {:kind "pi" :model "f"} :parent-kind "pi" :parent-model "p"})))
  (is (nil? (core/resolve-model {:resolved-kind "codex" :frontmatter {:kind "pi" :model "f"} :parent-kind "pi" :parent-model "p"})))
  ;; Pi accepts the roster's kindless provider/model value; non-pi overrides drop it.
  (is (= "f" (core/resolve-model {:resolved-kind "pi" :frontmatter {:model "f"} :parent-kind "pi" :parent-model "p"})))
  (is (nil? (core/resolve-model {:resolved-kind "codex" :frontmatter {:model "f"} :parent-kind "pi" :parent-model "p"})))
  ;; Same-kind parent inheritance remains the fallback when frontmatter has no model.
  (is (= "p" (core/resolve-model {:resolved-kind "pi" :frontmatter {} :parent-kind "pi" :parent-model "p"})))
  (is (= ["--model" "x"] (core/model-args "pi" "x")))
  (is (= [] (core/model-args "codex" "x")))
  (is (= "/tmp/persona.md" (core/persona-system-prompt "pi" "/tmp/persona.md" "BODY")))
  (is (= "BODY" (core/persona-system-prompt "claude" "/tmp/persona.md" "BODY")))
  (is (= "planner-1/scout-2-claude-fable-5" (core/child-label {:parent-label "planner-1-claude-fable-5" :parent-persona "planner" :persona "scout" :index 2 :model "anthropic/claude-fable-5"})))
  ;; A kind-inheriting pi spawn resolves the roster's kindless model, so nested labels
  ;; carry its basename suffix.
  (is (= "worker-1/scout-2-claude-sonnet-5" (core/child-label {:parent-label "worker-1-claude-opus-5" :parent-persona "worker" :persona "scout" :index 2 :model "anthropic/claude-sonnet-5"})))
  (is (= "worker-3/scout-1-claude-fable-5" (core/child-label {:parent-label "worker-3-claude-fable-5" :parent-persona "worker" :persona "scout" :index 1 :model "anthropic/claude-fable-5"})))
  ;; A nested parent label (a grandchild spawn) still throws in nested-prefix. This is
  ;; an undesigned backstop only, not enforcement: depth is enforced mechanically by
  ;; the injected HERDR_SUBAGENT_SPAWNS, never by label parsing.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"anchored persona/index prefix"
                        (core/nested-prefix "planner-1/scout-2-claude-fable-5" "scout")))
  (is (= "worker-1" (core/root-label "worker" 1 nil))))

(deftest retro-skill-resolution-and-policy
  (let [probe #{"/project/.agents/skills/retro/SKILL.md" "/project/skills/retro/SKILL.md" "/home/u/.agents/skills/retro/SKILL.md"}]
    (is (= "/project/.agents/skills/retro/SKILL.md" (core/skill-path probe "/project" "/home/u" "retro")))
    (is (= "/project/skills/retro/SKILL.md" (core/skill-path (disj probe "/project/.agents/skills/retro/SKILL.md") "/project" "/home/u" "retro")))
    (is (= "/home/u/.agents/skills/retro/SKILL.md" (core/skill-path #{"/home/u/.agents/skills/retro/SKILL.md"} "/project" "/home/u" "retro")))
    (is (nil? (core/skill-path #{} "/project" "/home/u" "retro"))))
  ;; Frontmatter values arrive as strings; "false" is truthy and must be coerced.
  (is (= "false" (:retro (core/parse-frontmatter "---\nname: scout\nretro: false\n---\nbody"))))
  (is (false? (core/frontmatter-boolean "scout" :retro "false")))
  (is (true? (core/frontmatter-boolean "scout" :retro "true")))
  (is (try (core/frontmatter-boolean "scout" :retro "maybe") false
           (catch clojure.lang.ExceptionInfo e
             (and (re-find #"must be true or false" (.getMessage e))
                  (= {:persona "scout" :key "retro" :value "maybe"} (ex-data e))))))
  (let [skill "/skills/retro/SKILL.md"]
    (is (= {:retro true :retro-source "default"} (core/resolve-retro {:persona "worker" :frontmatter {} :retro-skill skill})))
    (is (= {:retro false :retro-source "frontmatter"} (core/resolve-retro {:persona "scout" :frontmatter {:retro "false"} :retro-skill skill})))
    (is (= {:retro true :retro-source "frontmatter"} (core/resolve-retro {:persona "scout" :frontmatter {:retro "true"} :retro-skill skill})))
    ;; A missing skill omits the instruction rather than referencing a path that is absent.
    (is (= {:retro false :retro-source "skill-missing"} (core/resolve-retro {:persona "worker" :frontmatter {} :retro-skill nil})))
    ;; An explicit --retro must not become a silent no-op: it fails fast when the skill is absent.
    (is (thrown-with-msg? Exception #"--retro requested but no retro skill is installed"
                          (core/resolve-retro {:persona "worker" :flag true :frontmatter {} :retro-skill nil})))
    ;; --no-retro with the skill absent is fine: nothing was requested that cannot be honoured.
    (is (= {:retro false :retro-source "flag"} (core/resolve-retro {:persona "worker" :flag false :frontmatter {} :retro-skill nil}))))
  (is (nil? (cli/retro-instruction nil))))

(deftest spawn-policy-parsing-and-resolution
  ;; Split on whitespace and/or commas; dedupe preserving declaration order.
  (is (= ["scout" "researcher"] (core/parse-spawns "scout researcher")))
  (is (= ["scout" "researcher"] (core/parse-spawns "scout, researcher,scout")))
  (is (= ["scout" "researcher"] (core/parse-spawns ", scout ,, researcher scout ")))
  (is (= ["researcher" "scout"] (core/parse-spawns "researcher scout researcher")))
  ;; Absent (nil) and present-but-blank values both mean leaf, not an error.
  (is (= [] (core/parse-spawns nil)))
  (is (= [] (core/parse-spawns "")))
  (is (= [] (core/parse-spawns "   ")))
  ;; Frontmatter carries the raw string through `parse-frontmatter`.
  (is (= "scout researcher" (:spawns (core/parse-frontmatter "---\nname: worker\nspawns: scout researcher\n---\nbody"))))
  (is (= "" (:spawns (core/parse-frontmatter "---\nname: worker\nspawns:\n---\nbody"))))
  (let [roster #{"scout" "researcher"}]
    ;; Precedence: flag > frontmatter > default deny.
    (is (= {:spawns [] :spawns-source "default"}
           (core/resolve-spawns {:persona "reviewer" :frontmatter {} :resolve-persona roster})))
    (is (= {:spawns ["scout" "researcher"] :spawns-source "frontmatter"}
           (core/resolve-spawns {:persona "worker" :frontmatter {:spawns "scout researcher"} :resolve-persona roster})))
    (is (= {:spawns [] :spawns-source "frontmatter"}
           (core/resolve-spawns {:persona "worker" :frontmatter {:spawns ""} :resolve-persona roster})))
    (is (= {:spawns ["scout"] :spawns-source "flag"}
           (core/resolve-spawns {:persona "worker" :flag "scout" :frontmatter {:spawns "researcher"} :resolve-persona roster})))
    (is (= {:spawns ["scout" "researcher"] :spawns-source "flag"}
           (core/resolve-spawns {:persona "reviewer" :flag "scout,researcher" :frontmatter {} :resolve-persona roster})))
    ;; The literal flag value `none` forces leaf without consulting the roster — the
    ;; throwing resolver proves no lookup happens — even over a frontmatter grant.
    (is (= {:spawns [] :spawns-source "flag"}
           (core/resolve-spawns {:persona "worker" :flag "none" :frontmatter {:spawns "scout"}
                                 :resolve-persona (fn [_] (throw (ex-info "roster consulted" {})))})))
    ;; An unresolvable name fails fast, naming the persona, the name, and the source.
    (is (try (core/resolve-spawns {:persona "worker" :flag "scoot" :frontmatter {} :resolve-persona roster}) false
             (catch clojure.lang.ExceptionInfo e
               (and (re-find #"unresolvable persona `scoot`" (.getMessage e))
                    (= {:persona "worker" :spawn "scoot" :source "flag"} (ex-data e))))))
    (is (try (core/resolve-spawns {:persona "worker" :frontmatter {:spawns "scout resercher"} :resolve-persona roster}) false
             (catch clojure.lang.ExceptionInfo e
               (and (re-find #"unresolvable persona `resercher`" (.getMessage e))
                    (= {:persona "worker" :spawn "resercher" :source "frontmatter"} (ex-data e))))))))

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

(deftest delegation-guidance-and-smoke-success-contract
  (is (.endsWith (cli/launcher-bin) "/skills/herdr-subagents/scripts/subagent"))
  ;; The prompt text is intentionally pinned: the trigger applies to factual research
  ;; and judgment consults alike, while preserving the blocking, one-child leaf bound.
  (is (= "You may spawn at most one blocking ephemeral scout or researcher only when a factual gap or material judgment blocks progress; that child must remain a leaf."
         (cli/delegation-guidance ["scout" "researcher"])))
  (is (= "You may spawn at most one blocking ephemeral scout or advisor only when a factual gap or material judgment blocks progress; that child must remain a leaf."
         (cli/delegation-guidance ["scout" "advisor"])))
  ;; Leaf guidance remains the default for an empty resolved policy; the live roster
  ;; grants planner and worker, while scout and researcher remain leaves.
  (is (= "You are a leaf: do not spawn subagents." (cli/delegation-guidance [])))
  (is (= "You are a leaf: do not spawn subagents." (cli/delegation-guidance nil)))
  (is (= {:status "COMPLETE"} (smoke/complete! {:status "COMPLETE"})))
  (is (try
        (smoke/complete! {:status "FAILED"})
        false
        (catch clojure.lang.ExceptionInfo e
          (boolean (re-find #"did not publish COMPLETE" (.getMessage e)))))))

;; The live smoke is never run by `bb test`, so its assertions are covered here instead.
(deftest live-smoke-assertions-contract
  (is (= ["wrong command → guardrail → verify the command list first"]
         (smoke/process! {:process ["wrong command → guardrail → verify the command list first"]})))
  ;; Silence on the signal-manufacturing leg is a failure, not a pass.
  (is (thrown? Exception (smoke/process! {:process []})))
  (is (thrown? Exception (smoke/process! {:process ["one arrow → only"]})))
  (is (= {:process []} (smoke/no-process! {:process []})))
  (is (thrown? Exception (smoke/no-process! {:process ["a → b → c"]})))
  (let [file (str (fs/create-temp-file {:prefix "smoke-session-"}))]
    (is (= {:kind "path" :value file} (smoke/session! {:child-session {:kind "path" :value file}})))
    (is (= {:kind "id" :value "opaque"} (smoke/session! {:child-session {:kind "id" :value "opaque"}})))
    ;; A `path` session that does not resolve is not a usable transcript reference.
    (is (thrown? Exception (smoke/session! {:child-session {:kind "path" :value (str file ".missing")}})))
    (is (thrown? Exception (smoke/session! {:child-session {:value "no kind"}})))))

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

(def ^:private process-ledger {:child "child" :task "task" :result "/tmp/result"})
(defn- process-envelope [n & {:keys [status findings] :or {status "COMPLETE" findings []}}]
  (core/envelope (assoc process-ledger :status status :summary "s" :artifacts [] :findings findings :next nil
                        :process (mapv #(str "signal " % " → guardrail → rule " %) (range n)))))

(deftest process-section-position-and-optionality
  ;; Omitted when empty: an envelope without candidates carries no PROCESS section at all.
  (is (= (str "--- HERDR RESULT v1 ---\nCHILD: child\nTASK: task\nRESULT: /tmp/result\nSTATUS: COMPLETE\n"
              "SUMMARY: s\nARTIFACTS:\n- none\nFINDINGS:\n- none\nNEXT: none\n--- END HERDR RESULT ---\n")
         (process-envelope 0)))
  (let [text (process-envelope 2) lines (str/split-lines text)]
    ;; Trailing: after NEXT:, before the END marker.
    (is (< (.indexOf lines "NEXT: none") (.indexOf lines "PROCESS:") (.indexOf lines "--- END HERDR RESULT ---")))
    (is (= ["signal 0 → guardrail → rule 0" "signal 1 → guardrail → rule 1"] (:process (core/parse-envelope text))))
    (is (= ["signal 0 → guardrail → rule 0" "signal 1 → guardrail → rule 1"] (:process (core/validate-envelope process-ledger text)))))
  ;; An envelope without the section still parses and validates, reporting no candidates.
  (is (= [] (:process (core/validate-envelope process-ledger (process-envelope 0)))))
  ;; The section coexists with a populated FINDINGS list.
  (is (= 5 (count (:findings (core/validate-envelope process-ledger (process-envelope 2 :findings (mapv str (range 5))))))))
  ;; A non-COMPLETE envelope may carry candidates; status validation is otherwise shared.
  (let [parsed (core/validate-envelope process-ledger (process-envelope 1 :status "BLOCKED"))]
    (is (= "BLOCKED" (:status parsed)))
    (is (= 1 (count (:process parsed))))))

(deftest process-limit-is-hard-at-publish-and-degraded-at-validate
  (is (= 5 (count (:process (core/validate-envelope process-ledger (process-envelope 5))))))
  (is (thrown? Exception (process-envelope 6)))
  ;; A hand-assembled six-item section bypasses `core/envelope`; validation must degrade
  ;; rather than throw, because a throw becomes the terminal ledger status `invalid`.
  (let [six (str/replace (process-envelope 5) "- signal 0" "- signal x → guardrail → rule x\n- signal 0")
        parsed (core/validate-envelope process-ledger six)]
    (is (= 5 (count (:process parsed))))
    (is (true? (:process-overflow parsed)))
    (is (= "COMPLETE" (:status parsed)))
    (is (= "signal x → guardrail → rule x" (first (:process parsed)))))
  ;; A malformed (non `- `-prefixed) line inside the section is ignored, not fatal.
  (let [text (str/replace (process-envelope 1) "PROCESS:\n" "PROCESS:\nnot a list item\n")]
    (is (= ["signal 0 → guardrail → rule 0"] (:process (core/validate-envelope process-ledger text))))))

;; Relocate the PROCESS block from its trailing position to immediately before `NEXT:`,
;; exercising placement independence.
(defn- process-before-next [text]
  (let [lines (vec (str/split-lines text))
        start (.indexOf lines "PROCESS:")
        end (.indexOf lines "--- END HERDR RESULT ---")
        block (subvec lines start end)
        without (into (subvec lines 0 start) (subvec lines end))
        next-at (first (keep-indexed #(when (str/starts-with? %2 "NEXT: ") %1) without))]
    (str (str/join "\n" (concat (subvec without 0 next-at) block (subvec without next-at))) "\n")))

(deftest section-boundaries-are-structural-not-content-derived
  ;; Placement independence: PROCESS before NEXT parses identically to PROCESS after NEXT.
  (let [trailing (process-envelope 2)
        leading (process-before-next trailing)
        strip #(dissoc % :text)]
    (is (< (.indexOf (vec (str/split-lines leading)) "PROCESS:")
           (.indexOf (vec (str/split-lines leading)) "NEXT: none")))
    (is (= (strip (core/parse-envelope trailing)) (strip (core/parse-envelope leading))))
    (is (= ["signal 0 → guardrail → rule 0" "signal 1 → guardrail → rule 1"] (:process (core/parse-envelope leading))))
    ;; FINDINGS is no longer absorbed: an early PROCESS neither leaks items nor breaches the cap.
    (is (= [] (:findings (core/parse-envelope leading))))
    (is (= "none" (:next (core/parse-envelope leading))))
    (is (= 2 (count (:process (core/validate-envelope process-ledger leading)))))
    ;; A populated FINDINGS list stays intact and capped with PROCESS placed before NEXT.
    (let [both (process-before-next (process-envelope 2 :findings (mapv #(str "finding " %) (range 5))))]
      (is (= (mapv #(str "finding " %) (range 5)) (:findings (core/validate-envelope process-ledger both))))
      (is (= 2 (count (:process (core/validate-envelope process-ledger both)))))))
  ;; Envelopes with no optional section are unaffected.
  (let [parsed (core/parse-envelope (process-envelope 0))]
    (is (= [] (:process parsed)))
    (is (= [] (:findings parsed)))
    (is (= [] (:artifacts parsed)))
    (is (= "none" (:next parsed))))
  ;; Field and item content that mimics delimiters is data, never a boundary.
  (let [text (core/envelope {:child "child" :task "task" :result "/tmp/result" :status "COMPLETE"
                             :summary "beware NEXT: none and --- END HERDR RESULT ---"
                             :artifacts ["/tmp/a — FINDINGS: not a header"]
                             :findings ["NEXT: none" "PROCESS:" "ARTIFACTS:" "--- END HERDR RESULT ---"]
                             :next "FINDINGS:"
                             :process ["NEXT: none → guardrail → still an item"]})
        parsed (core/validate-envelope process-ledger text)]
    (is (= "beware NEXT: none and --- END HERDR RESULT ---" (:summary parsed)))
    (is (= ["/tmp/a — FINDINGS: not a header"] (:artifacts parsed)))
    (is (= ["NEXT: none" "PROCESS:" "ARTIFACTS:" "--- END HERDR RESULT ---"] (:findings parsed)))
    (is (= "FINDINGS:" (:next parsed)))
    (is (= ["NEXT: none → guardrail → still an item"] (:process parsed)))
    ;; Same guarantee when the optional section leads.
    (is (= (dissoc parsed :text)
           (dissoc (core/validate-envelope process-ledger (process-before-next text)) :text))))
  ;; A missing required section is still malformed.
  (is (thrown? Exception (core/parse-envelope (str/replace (process-envelope 0) "FINDINGS:\n- none\n" ""))))
  ;; A foreign line inside a required section is still fatal.
  (is (thrown? Exception (core/parse-envelope (str/replace (process-envelope 0) "FINDINGS:\n" "FINDINGS:\nnot a list item\n"))))
  ;; A repeated required header is rejected rather than silently dropping the second block.
  (is (thrown? Exception (core/parse-envelope (str/replace (process-envelope 0) "FINDINGS:\n" "FINDINGS:\n- a\nFINDINGS:\n")))))

(deftest repeated-process-headers-merge-in-order
  ;; PROCESS stays tolerant where the required sections are strict: duplicate headers merge
  ;; their blocks in document order rather than silently dropping items.
  (let [adjacent (str/replace (process-envelope 1) "PROCESS:\n" "PROCESS:\nPROCESS:\n")
        separated (str/replace (process-before-next (process-envelope 1))
                               "NEXT: none\n" "NEXT: none\nPROCESS:\n- late → guardrail → rule\n")]
    ;; Adjacent duplicate before the items: the first block is empty, nothing is lost.
    (is (= ["signal 0 → guardrail → rule 0"] (:process (core/parse-envelope adjacent))))
    ;; Blocks separated by other content merge in document order.
    (is (= ["signal 0 → guardrail → rule 0" "late → guardrail → rule"] (:process (core/parse-envelope separated))))
    (is (= 2 (count (:process (core/validate-envelope process-ledger separated))))))
  ;; Merged blocks breaching the cap degrade to truncation + overflow, exactly like one block.
  (let [six (str/replace (process-envelope 5) "PROCESS:\n" "PROCESS:\n- extra → guardrail → rule\nPROCESS:\n")
        parsed (core/validate-envelope process-ledger six)]
    (is (= 5 (count (:process parsed))))
    (is (true? (:process-overflow parsed)))
    (is (= "COMPLETE" (:status parsed)))
    (is (= "extra → guardrail → rule" (first (:process parsed))))))

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

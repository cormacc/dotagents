(ns herdr-orch.core-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [herdr-orch.cli :as cli]
            [herdr-orch.core :as core]
            [herdr-orch.ledger :as ledger]
            [herdr-orch.smoke :as smoke]))

(deftest resolution-and-label-contract
  (let [directories (core/persona-directories "/project" "/home/u" "/installed/herdr-orch/subagents")]
    (is (= ["/project/.agents/subagents" "/home/u/.agents/subagents" "/installed/herdr-orch/subagents"] directories))
    (testing "project shadows home and packaged definitions"
      (is (= "/project/.agents/subagents/scout.md"
             (core/resolve-persona #{"/project/.agents/subagents/scout.md"
                                     "/home/u/.agents/subagents/scout.md"
                                     "/installed/herdr-orch/subagents/scout.md"}
                                   directories "scout"))))
    (testing "home shadows packaged definitions"
      (is (= "/home/u/.agents/subagents/scout.md"
             (core/resolve-persona #{"/home/u/.agents/subagents/scout.md"
                                     "/installed/herdr-orch/subagents/scout.md"}
                                   directories "scout"))))
    (testing "packaged definitions are the fallback"
      (is (= "/installed/herdr-orch/subagents/scout.md"
             (core/resolve-persona #{"/installed/herdr-orch/subagents/scout.md"}
                                   directories "scout"))))
    (is (nil? (core/resolve-persona #{} directories "unknown"))))
  (is (= "right" (core/direction {:width 160 :height 80})))
  (is (= "down" (core/direction {:width 113 :height 110})))
  ;; Two tiers, and nothing above them: a definition's `kind:`, else the parent's.
  (is (= "claude" (core/resolve-kind {:frontmatter {} :parent-kind "claude"})))
  (is (= "pi" (core/resolve-kind {:frontmatter {:kind "pi"} :parent-kind "claude"})))
  (is (thrown? Exception (core/resolve-kind {:frontmatter {} :parent-kind nil})))
  ;; A requested model still wins over every frontmatter and parent-model value.
  (is (= "m" (core/resolve-model {:requested "m" :resolved-kind "pi" :frontmatter {:kind "pi" :model "f"} :parent-kind "pi" :parent-model "p"})))
  ;; A definition model now survives a kind override instead of being dropped: it is
  ;; honoured for any resolved kind (translation, not `resolve-model`, decides the
  ;; per-harness spelling), so a paired `kind: pi` declaration is no longer special.
  (is (= "f" (core/resolve-model {:resolved-kind "pi" :frontmatter {:kind "pi" :model "f"} :parent-kind "pi" :parent-model "p"})))
  (is (= "f" (core/resolve-model {:resolved-kind "codex" :frontmatter {:kind "pi" :model "f"} :parent-kind "pi" :parent-model "p"})))
  ;; A kindless roster model is likewise honoured for every resolved kind now, not only pi.
  (is (= "f" (core/resolve-model {:resolved-kind "pi" :frontmatter {:model "f"} :parent-kind "pi" :parent-model "p"})))
  (is (= "f" (core/resolve-model {:resolved-kind "codex" :frontmatter {:model "f"} :parent-kind "pi" :parent-model "p"})))
  ;; Same-kind parent inheritance remains the fallback when frontmatter has no model.
  (is (= "p" (core/resolve-model {:resolved-kind "pi" :frontmatter {} :parent-kind "pi" :parent-model "p"})))
  (let [config {:harnesses {:pi {:model-flag "--model"} :claude {:model-flag "--model"} :codex {:model-flag "--model"}}
               :models {"claude-opus-5" {:pi "anthropic/claude-opus-5" :claude "opus" :codex "gpt-5.6-sol"}}}]
    ;; Known ID x every declared kind: translated to that harness's native spelling.
    (is (= "anthropic/claude-opus-5" (core/translate-model config "pi" "claude-opus-5")))
    (is (= "opus" (core/translate-model config "claude" "claude-opus-5")))
    (is (= "gpt-5.6-sol" (core/translate-model config "codex" "claude-opus-5")))
    ;; An ID absent from the table passes through unchanged — the ID set is open.
    (is (= "unlisted-model" (core/translate-model config "pi" "unlisted-model")))
    ;; A nil model passes through as nil.
    (is (nil? (core/translate-model config "pi" nil)))
    (is (= ["--model" "opus"] (core/model-args config "claude" "claude-opus-5")))
    (is (= ["--model" "gpt-5.6-sol"] (core/model-args config "codex" "claude-opus-5")))
    ;; Unlisted ID: still translated-as-passthrough into the resolved kind's flag.
    (is (= ["--model" "unlisted-model"] (core/model-args config "claude" "unlisted-model")))
    ;; Nil resolved model still yields empty model-args.
    (is (= [] (core/model-args config "pi" nil)))
    ;; A kind with no `:harnesses` entry yields empty model args — the kind set is open.
    (is (= [] (core/model-args config "gemini" "claude-opus-5")))
    ;; `:extra-args` is opt-in: a config without it grants no native args at all. This is
    ;; the assertion that fails if a permission bypass is ever shipped on by default.
    (is (= [] (core/harness-extra-args config "claude")))
    (is (= [] (core/harness-extra-args config "codex")))
    (is (= [] (core/harness-extra-args config "gemini"))))
  (testing "single-hop alias resolution: alias -> canonical -> per-kind row, canonical passthrough on miss"
    (let [config {:harnesses {:pi {:model-flag "--model"} :claude {:model-flag "--model"} :codex {:model-flag "--model"}}
                 :aliases {"heavy" "claude-opus-5" "ghost" "nowhere-canonical"}
                 :models {"claude-opus-5" {:pi "anthropic/claude-opus-5" :claude "opus" :codex "gpt-5.6-sol"}}}]
      ;; alias -> canonical -> row lookup, for every declared kind.
      (is (= "anthropic/claude-opus-5" (core/translate-model config "pi" "heavy")))
      (is (= "opus" (core/translate-model config "claude" "heavy")))
      (is (= "gpt-5.6-sol" (core/translate-model config "codex" "heavy")))
      (is (= ["--model" "opus"] (core/model-args config "claude" "heavy")))
      ;; `canonical-model` exposes the post-alias hop directly, independent of kind.
      (is (= "claude-opus-5" (core/canonical-model config "heavy")))
      ;; An alias whose canonical target has no `:models` row: the *canonical* ID passes
      ;; through, never the requested alias.
      (is (= "nowhere-canonical" (core/canonical-model config "ghost")))
      (is (= "nowhere-canonical" (core/translate-model config "pi" "ghost")))
      (is (= ["--model" "nowhere-canonical"] (core/model-args config "pi" "ghost")))
      ;; An ID absent from both `:aliases` and `:models` still passes through unchanged
      ;; with a non-empty `:aliases` map present.
      (is (= "unlisted-model" (core/translate-model config "pi" "unlisted-model")))
      ;; A kind with no `:harnesses` entry yields empty model args for an aliased model.
      (is (= [] (core/model-args config "gemini" "heavy")))
      ;; A nil model yields empty model args even with `:aliases` present, and
      ;; `canonical-model` passes nil through rather than throwing.
      (is (= [] (core/model-args config "pi" nil)))
      (is (nil? (core/canonical-model config nil)))))
  (testing "opt-in :extra-args reach the resolved kind only"
    (let [config {:harnesses {:pi {:model-flag "--model"}
                              :claude {:model-flag "--model" :extra-args ["--permission-mode" "bypassPermissions"]}
                              :codex {:model-flag "--model" :extra-args ["--dangerously-bypass-approvals-and-sandbox"]}}}]
      (is (= ["--permission-mode" "bypassPermissions"] (core/harness-extra-args config "claude")))
      (is (= ["--dangerously-bypass-approvals-and-sandbox"] (core/harness-extra-args config "codex")))
      ;; A kind the override did not name is untouched — granting claude never grants pi.
      (is (= [] (core/harness-extra-args config "pi")))))
  (testing "a harness override replaces the whole entry, so :model-flag must be restated"
    ;; `merge-config` is `merge-with merge`: level-two harness entries are replaced, not
    ;; key-merged. An override that omits `:model-flag` is therefore rejected at parse.
    (is (try (core/parse-config "/tmp/extra.edn" "{:harnesses {:claude {:extra-args [\"--permission-mode\" \"bypassPermissions\"]}}}") false
             (catch Exception e (str/includes? (ex-message e) ":model-flag"))))
    (is (= {:harnesses {:claude {:model-flag "--model" :extra-args ["--permission-mode" "bypassPermissions"]}}}
           (core/merge-config {:harnesses {:claude {:model-flag "--model"}}}
                              {:harnesses {:claude {:model-flag "--model" :extra-args ["--permission-mode" "bypassPermissions"]}}}))))
  (is (= ["--append-system-prompt" "/tmp/persona.md"] (core/persona-args "pi" "/tmp/persona.md")))
  (is (= ["--append-system-prompt-file" "/tmp/persona.md"] (core/persona-args "claude" "/tmp/persona.md")))
  (is (= [] (core/persona-args "codex" "/tmp/persona.md")))
  (is (= "planner-1/scout-2-claude-fable-5" (core/child-label {:parent-label "planner-1-claude-fable-5" :parent-persona "planner" :persona "scout" :index 2 :model "anthropic/claude-fable-5"})))
  ;; A kind-inheriting pi spawn resolves the roster's kindless model, so nested labels
  ;; carry its basename suffix.
  (is (= "worker-1/scout-2-claude-sonnet-5" (core/child-label {:parent-label "worker-1-claude-opus-5" :parent-persona "worker" :persona "scout" :index 2 :model "anthropic/claude-sonnet-5"})))
  (is (= "worker-3/scout-1-claude-fable-5" (core/child-label {:parent-label "worker-3-claude-fable-5" :parent-persona "worker" :persona "scout" :index 1 :model "anthropic/claude-fable-5"})))
  ;; A nested parent label (a grandchild spawn) still throws in nested-prefix. This is
  ;; an undesigned backstop only, not enforcement: depth is enforced mechanically by
  ;; the injected HERDR_ORCH_SPAWNS, never by label parsing.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"anchored persona/index prefix"
                        (core/nested-prefix "planner-1/scout-2-claude-fable-5" "scout")))
  (is (= "worker-1" (core/root-label "worker" 1 nil))))

(deftest config-parse-merge-and-shape-validation
  (testing "valid EDN parses"
    (is (= {:harnesses {:pi {:model-flag "--model"}} :models {"claude-opus-5" {:pi "anthropic/claude-opus-5"}}}
           (core/parse-config "/tmp/config.edn"
                              "{:harnesses {:pi {:model-flag \"--model\"}} :models {\"claude-opus-5\" {:pi \"anthropic/claude-opus-5\"}}}"))))
  (testing "sparse model rows and harness keywords unknown to :harnesses are allowed"
    (is (= {:models {"partial" {:gemini "g-model"}}} (core/parse-config "/tmp/config.edn" "{:models {\"partial\" {:gemini \"g-model\"}}}"))))
  (testing "malformed EDN throws naming the offending path"
    (is (try (core/parse-config "/tmp/bad.edn" "{:harnesses") false
             (catch clojure.lang.ExceptionInfo e
               (and (str/includes? (.getMessage e) "/tmp/bad.edn") (= "/tmp/bad.edn" (:path (ex-data e))))))))
  (testing "structurally invalid shapes throw naming the offending path"
    (doseq [[label text] {"non-map top level" "[1 2 3]"
                          ":harnesses not a map" "{:harnesses [1 2]}"
                          ":harnesses entry not a map" "{:harnesses {:pi \"nope\"}}"
                          "blank :model-flag" "{:harnesses {:pi {:model-flag \"\"}}}"
                          "non-string :model-flag" "{:harnesses {:pi {:model-flag 1}}}"
                          ":extra-args not sequential" "{:harnesses {:pi {:model-flag \"--model\" :extra-args \"--yolo\"}}}"
                          ":extra-args non-string member" "{:harnesses {:pi {:model-flag \"--model\" :extra-args [1]}}}"
                          ":extra-args blank member" "{:harnesses {:pi {:model-flag \"--model\" :extra-args [\"\"]}}}"
                          ;; Herdr rejects control characters in native argv, so they fail here first.
                          ":extra-args control character" "{:harnesses {:pi {:model-flag \"--model\" :extra-args [\"--a\\nb\"]}}}"
                          ":models not a map" "{:models [1 2]}"
                          ":models entry not a map" "{:models {\"x\" \"nope\"}}"
                          ":models row value not a string" "{:models {\"x\" {:pi 1}}}"
                          ":aliases not a map" "{:aliases [1 2]}"
                          ":aliases key not a string" "{:aliases {1 \"x\"}}"
                          ":aliases blank key" "{:aliases {\"\" \"x\"}}"
                          ":aliases value not a string" "{:aliases {\"a\" 1}}"
                          ":aliases blank value" "{:aliases {\"a\" \"\"}}"}]
      (is (try (core/parse-config "/tmp/shape.edn" text) false
               (catch clojure.lang.ExceptionInfo e (= "/tmp/shape.edn" (:path (ex-data e)))))
          label)))
  (testing "invalid defaults fail in parse-config with their path, before merge-with can run"
    (doseq [[label text message] [["non-map defaults" "{:defaults :split}" #"defaults must be a map"]
                                  ["unknown defaults key" "{:defaults {:unknown :split}}" #"defaults has unknown key"]
                                  ["unknown placement" "{:defaults {:placement :sideways}}" #"placement must be" ]
                                  ["removed focus default" "{:defaults {:focus true}}" #"defaults has unknown key"]]]
      (is (try
            (core/parse-config "/tmp/defaults.edn" text)
            false
            (catch clojure.lang.ExceptionInfo e
              (and (= "/tmp/defaults.edn" (:path (ex-data e)))
                   (re-find message (.getMessage e))))
            (catch Throwable _ false))
          label)))
  (testing "merge is row-level replacement, defaults merge per key, and later configs win"
    ;; `parse-config` admits only :placement in :defaults; synthetic keys isolate the
    ;; generic merge primitive's per-key behavior from the closed input schema.
    (let [default {:harnesses {:pi {:model-flag "--model"} :claude {:model-flag "--model"}}
                   :models {"a" {:pi "pa" :claude "ca"} "b" {:pi "pb"}}
                   :defaults {:placement :split :preserved :default}
                   :extension {:enabled true}}
          home {:models {"a" {:pi "pa-home"}} ;; replaces the whole "a" row, dropping :claude
                :defaults {:placement :tab}}
          project {:harnesses {:claude {:model-flag "--model2"}}
                   :models {"b" {:pi "pb-project"}}
                   :defaults {:placement :tab-split :project true}}
          merged (core/merge-config default home project)]
      (is (= {:pi "pa-home"} (get-in merged [:models "a"])))
      (is (= {:pi "pb-project"} (get-in merged [:models "b"])))
      (is (= {:model-flag "--model"} (get-in merged [:harnesses :pi])))
      (is (= {:model-flag "--model2"} (get-in merged [:harnesses :claude])))
      (is (= {:placement :tab-split :preserved :default :project true} (:defaults merged)))
      (is (= {:enabled true} (:extension merged)))))
  (testing "a solitary :aliases override retargets an alias across every kind without restating :models or :harnesses"
    (let [default {:harnesses {:pi {:model-flag "--model"} :claude {:model-flag "--model"} :codex {:model-flag "--model"}}
                   :aliases {"heavy" "canonical-a"}
                   :models {"canonical-a" {:pi "pa" :claude "ca" :codex "xa"}
                            "canonical-b" {:pi "pb" :claude "cb" :codex "xb"}}}
          override {:aliases {"heavy" "canonical-b"}}
          merged (core/merge-config default override)]
      (is (= "canonical-b" (get-in merged [:aliases "heavy"])))
      (doseq [kind ["pi" "claude" "codex"]]
        (is (= (get-in merged [:models "canonical-b" (keyword kind)]) (core/translate-model merged kind "heavy"))
            (str "heavy retargeted for " kind)))))
  (testing "project alias wins over home alias for the same key; sibling aliases from lower layers survive"
    (let [default {:aliases {"a" "canonical-default" "b" "canonical-shared"}}
          home {:aliases {"a" "canonical-home"}}
          project {:aliases {"a" "canonical-project"}}
          merged (core/merge-config default home project)]
      (is (= "canonical-project" (get-in merged [:aliases "a"])))
      (is (= "canonical-shared" (get-in merged [:aliases "b"]))))))

(deftest merged-config-alias-model-overlap-and-chain-validation
  (testing "a key present in both :aliases and :models fails, naming the key"
    (is (try (core/validate-merged-config! {:aliases {"heavy" "canonical-a"} :models {"heavy" {:pi "x"}}}) false
             (catch clojure.lang.ExceptionInfo e (= "heavy" (:key (ex-data e)))))))
  (testing "an :aliases value that is itself an :aliases key fails, naming the key (multi-hop chain)"
    (is (try (core/validate-merged-config! {:aliases {"heavy" "middle" "middle" "canonical-a"}}) false
             (catch clojure.lang.ExceptionInfo e (= "middle" (:key (ex-data e)))))))
  (testing "no overlap and no chain: the merged config passes through unchanged"
    (let [config {:aliases {"heavy" "canonical-a"} :models {"canonical-a" {:pi "x"}}}]
      (is (= config (core/validate-merged-config! config))))))

(deftest placement-resolution-contract
  (testing "every flag/configuration/depth combination"
    (doseq [[flag configured below-root? expected]
            [[nil nil false "split"] [nil nil true "split"]
             [nil :split false "split"] [nil :split true "split"]
             [nil :tab false "tab"] [nil :tab true "tab"]
             [nil :tab-split false "tab"] [nil :tab-split true "split"]
             ["tab" nil false "tab"] ["tab" nil true "tab"]
             ["tab" :split false "tab"] ["tab" :split true "tab"]
             ["tab" :tab false "tab"] ["tab" :tab true "tab"]
             ["tab" :tab-split false "tab"] ["tab" :tab-split true "tab"]
             ["split" nil false "split"] ["split" nil true "split"]
             ["split" :split false "split"] ["split" :split true "split"]
             ["split" :tab false "split"] ["split" :tab true "split"]
             ["split" :tab-split false "split"] ["split" :tab-split true "split"]]]
      (is (= expected
             (core/resolve-placement {:flag flag :configured configured :below-root? below-root?}))
          (str {:flag flag :configured configured :below-root? below-root?})))))

(deftest worktree-branch-naming-is-predictable
  (is (= "orch/89c7f5f6" (core/worktree-branch "89c7f5f6-cccc-dddd-eeee-000000000000")))
  ;; Same task, same branch: naming is a pure function of the task id alone.
  (is (= (core/worktree-branch "11112222-3333-4444-5555-666677778888")
         (core/worktree-branch "11112222-3333-4444-5555-666677778888"))))

(deftest worktree-trigger-resolution-contract
  (testing "no trigger at all: ordinary shared-tree spawn"
    (is (= {:create? false :trigger "none"}
           (core/resolve-worktree {:flag false :traits [] :in-flight? false :read-only? false}))))
  (testing "--worktree flag forces a checkout even with nothing in flight"
    (is (= {:create? true :trigger "flag"}
           (core/resolve-worktree {:flag true :traits [] :in-flight? false :read-only? false}))))
  (testing "a resolved %worktree trait forces a checkout identically to the flag"
    (is (= {:create? true :trigger "trait"}
           (core/resolve-worktree {:flag false :traits ["worktree"] :in-flight? false :read-only? false}))))
  (testing "in-flight default: another round of the session is open and the persona is not read-only"
    (is (= {:create? true :trigger "default"}
           (core/resolve-worktree {:flag false :traits [] :in-flight? true :read-only? false}))))
  (testing "read-only personas get no implicit worktree even while another round is in flight"
    (is (= {:create? false :trigger "none"}
           (core/resolve-worktree {:flag false :traits ["read-only"] :in-flight? true :read-only? true}))))
  (testing "%no-worktree suppresses the in-flight default"
    (is (= {:create? false :trigger "suppressed"}
           (core/resolve-worktree {:flag false :traits ["no-worktree"] :in-flight? true :read-only? false}))))
  (testing "%no-worktree is recorded even when nothing would have defaulted in anyway"
    (is (= {:create? false :trigger "suppressed"}
           (core/resolve-worktree {:flag false :traits ["no-worktree"] :in-flight? false :read-only? false}))))
  (testing "--worktree and a resolved %no-worktree conflict and fail before any mutation, naming both"
    (is (try (core/resolve-worktree {:flag true :traits ["no-worktree"] :in-flight? false :read-only? false}) false
             (catch clojure.lang.ExceptionInfo e
               (and (re-find #"worktree trigger conflict" (.getMessage e))
                    (= {:flag true :traits ["no-worktree"]} (ex-data e)))))))
  (testing "a resolved %worktree trait and %no-worktree conflict identically to the flag pairing"
    (is (try (core/resolve-worktree {:flag false :traits ["worktree" "no-worktree"] :in-flight? false :read-only? false}) false
             (catch clojure.lang.ExceptionInfo _ true)))))

;; Pane labels use `model-basename`, independent of roster translation: a canonical bare
;; ID and its pre-migration pi-syntax equivalent share the same basename, so labels are
;; unaffected by the roster migration.
(deftest label-stability-across-canonical-id-migration
  (is (= (core/model-basename "anthropic/claude-opus-5") (core/model-basename "claude-opus-5")))
  (is (= "worker-1-claude-opus-5" (core/root-label "worker" 1 "claude-opus-5")))
  (is (= "worker-1-claude-opus-5" (core/root-label "worker" 1 "anthropic/claude-opus-5"))))

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

(deftest ignores-retired-requires-frontmatter-key
  ;; The `requires:` mandate mechanism was retired (see
  ;; design/log/2026-07-31-subagents-retire-the-mandatory-advisor-c.org). A stale key in a
  ;; hand-written or third-party persona must be inert rather than an error, and must not
  ;; alter the resolved spawn policy.
  (is (= {:spawns ["scout" "advisor"] :spawns-source "frontmatter"}
         (core/resolve-spawns {:persona "worker"
                               :frontmatter {:spawns "scout advisor" :requires "advisor"}
                               :resolve-persona (fn [n] n)})))
  (is (nil? (resolve 'herdr-orch.core/resolve-required))))

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
  (is (.endsWith (cli/launcher-bin) "/skills/herdr-orch/scripts/oh"))
  ;; The prompt text is intentionally pinned. One gap-only trigger covers factual research
  ;; and discretionary judgment consults alike, while preserving the blocking, one-child
  ;; leaf bound. There is deliberately no mandated-consult variant.
  ;;
  ;; Closeout fix (P2): the close-before-publish sentence this used to append is *deleted*.
  ;; `assert-children-discharged!` now refuses `publish` mechanically while a caller still
  ;; owns an open child round, and that refusal names the exact remedy at the one moment it
  ;; is actionable -- so restating it as standing advice a child may ignore is precisely the
  ;; guard-plus-surviving-prose failure phase 2 existed to prevent. These equalities are the
  ;; positive pin; `close-before-publish-guidance-is-not-restated-in-any-prompt` below pins
  ;; its absence from the *rendered* prompt, which no test covered before this round.
  (is (= "You may spawn at most one blocking scout or researcher only when a factual gap or material judgment blocks progress; that child must remain a leaf."
         (cli/delegation-guidance ["scout" "researcher"])))
  (is (= "You may spawn at most one blocking scout or advisor only when a factual gap or material judgment blocks progress; that child must remain a leaf."
         (cli/delegation-guidance ["scout" "advisor"])))
  ;; Leaf guidance remains the default for an empty resolved policy; the live roster
  ;; grants planner and worker, while scout and researcher remain leaves.
  (is (= "You are a leaf: do not spawn subagents." (cli/delegation-guidance [])))
  (is (= "You are a leaf: do not spawn subagents." (cli/delegation-guidance nil)))
  ;; An advisor in the allow-list is covered by that same gap-only clause: the retired
  ;; mandate variant must not reappear, and the function takes exactly one argument.
  (is (= "You may spawn at most one blocking scout or researcher or advisor only when a factual gap or material judgment blocks progress; that child must remain a leaf."
         (cli/delegation-guidance ["scout" "researcher" "advisor"])))
  (is (not (str/includes? (cli/delegation-guidance ["scout" "researcher" "advisor"]) "mandates")))
  (doseq [banned ["Capturing its result closes nothing" "close it yourself"
                  "nobody else can close a child you own" "before you publish"]]
    (is (not (str/includes? (cli/delegation-guidance ["scout" "researcher" "advisor"]) banned)) banned))
  (is (thrown? clojure.lang.ArityException (cli/delegation-guidance ["scout"] ["advisor"])))
  (is (= {:status "COMPLETE"} (smoke/complete! {:status "COMPLETE"})))
  ;; The smoke carries no harness selector in its argv: a cross-harness run generates
  ;; personas that declare the kind (`with-personas`), which is the only tier there is.
  (is (= ["task" "run" "scout" "--model" "light" "--task" "smoke"]
         (smoke/smoke-task-args "scout" "light" "smoke")))
  ;; The worker retro leg is deliberately explicit, retaining the original flag
  ;; semantics rather than relying on the default retro policy.
  (is (= ["task" "run" "worker" "--retro" "--model" "light" "--task" "smoke"]
         (smoke/smoke-task-args "worker" "light" "smoke" :retro? true)))
  (testing "session! tolerates only the kinds herdr does not track"
    ;; claude: herdr reports no `agent_session`, so absence is reported rather than fatal.
    ;; This is the assertion that failed a claude smoke whose every leg published COMPLETE.
    (is (= "none" (:kind (smoke/session! "claude" {:child-session nil}))))
    ;; pi absence stays fatal -- there it would be a real regression.
    (is (try (smoke/session! "pi" {:child-session nil}) false
             (catch Exception e (str/includes? (ex-message e) "no usable :child-session"))))
    ;; A claude session that *is* present is still validated, not waved through.
    (is (try (smoke/session! "claude" {:child-session {:kind "path" :value "/definitely/absent"}}) false
             (catch Exception e (str/includes? (ex-message e) "does not exist")))))
  (is (try
        (smoke/complete! {:status "FAILED"})
        false
        (catch clojure.lang.ExceptionInfo e
          (boolean (re-find #"did not publish COMPLETE" (.getMessage e)))))))

;; The live smoke is never run by `bb test`, so its assertions are covered here instead.
(deftest live-smoke-assertions-contract
  ;; `retro-gated-in!` checks the mechanical gate only -- never a PROCESS candidate, which
  ;; is a content judgement `retro`'s own threshold owns (task 377ad650). A gated-in entry
  ;; whose source is the explicit flag passes unchanged; anything else throws, naming why.
  (let [gated {:task "t" :retro true :retro-source "flag"}]
    (is (= gated (smoke/retro-gated-in! gated))))
  (is (thrown-with-msg? Exception #"not gated in" (smoke/retro-gated-in! {:task "t" :retro false :retro-source "skill-missing"})))
  (is (thrown-with-msg? Exception #"did not resolve from --retro" (smoke/retro-gated-in! {:task "t" :retro true :retro-source "default"})))
  (is (= {:process []} (smoke/no-process! {:process []})))
  (is (thrown? Exception (smoke/no-process! {:process ["a → b → c"]})))
  (let [file (str (fs/create-temp-file {:prefix "smoke-session-"}))]
    (is (= {:kind "path" :value file} (smoke/session! "pi" {:child-session {:kind "path" :value file}})))
    (is (= {:kind "id" :value "opaque"} (smoke/session! "pi" {:child-session {:kind "id" :value "opaque"}})))
    ;; A `path` session that does not resolve is not a usable transcript reference.
    (is (thrown? Exception (smoke/session! "pi" {:child-session {:kind "path" :value (str file ".missing")}})))
    (is (thrown? Exception (smoke/session! "pi" {:child-session {:value "no kind"}})))
    ;; A nil kind is the unset-env default, i.e. pi, and stays strict.
    (is (= {:kind "path" :value file} (smoke/session! nil {:child-session {:kind "path" :value file}})))
    (is (thrown? Exception (smoke/session! nil {:child-session nil})))))

;; Zero is truthy in Clojure and Thread/sleep rejects negatives, so both must fall back.
(deftest poll-interval-parsing
  (is (= 1000 (cli/parse-poll-interval nil)))
  (is (= 1000 (cli/parse-poll-interval "")))
  (is (= 1000 (cli/parse-poll-interval "   ")))
  (is (= 1000 (cli/parse-poll-interval "0")))
  (is (= 1000 (cli/parse-poll-interval "-5")))
  (is (= 1000 (cli/parse-poll-interval "abc")))
  (is (= 250 (cli/parse-poll-interval "250"))))

(deftest waiting-interval-min-parsing
  (is (= 250 (cli/parse-waiting-interval-min "250")))
  (doseq [raw [nil "" "   " "soon" "0" "-5"]]
    (is (= cli/default-waiting-interval-min-ms
           (cli/parse-waiting-interval-min raw))
        (pr-str raw))))

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

;; --- portable Markdown artifact links ---------------------------------------------
;; The visible label is always the whole absolute path (never a basename) and the
;; destination is a `Path.toUri` encoded `file://` URI. No raw OSC 8 escape is ever
;; emitted: this is portable fallback syntax whose clickability depends on the harness.
(deftest artifact-link-renders-portable-markdown
  (testing "a bare path renders label + URI with no purpose suffix"
    (is (= "[/tmp/report.md](file:///tmp/report.md)" (core/artifact-link "/tmp/report.md"))))
  (testing "a purpose is preserved after the same ` — ` delimiter artifact-path splits on"
    (is (= "[/tmp/report.md](file:///tmp/report.md) — the report"
           (core/artifact-link "/tmp/report.md — the report")))
    ;; Only the first delimiter splits, so a purpose may itself contain one.
    (is (= "[/tmp/r.md](file:///tmp/r.md) — a — b"
           (core/artifact-link "/tmp/r.md — a — b"))))
  (testing "reserved path characters are percent-encoded by Path.toUri, not by hand"
    (is (= "[/tmp/a b/report.md](file:///tmp/a%20b/report.md)" (core/artifact-link "/tmp/a b/report.md")))
    (is (= "[/tmp/a#b.md](file:///tmp/a%23b.md)" (core/artifact-link "/tmp/a#b.md")))
    (is (= "[/tmp/100%/x.md](file:///tmp/100%25/x.md)" (core/artifact-link "/tmp/100%/x.md")))
    (is (= "[/tmp/q?r.md](file:///tmp/q%3Fr.md)" (core/artifact-link "/tmp/q?r.md")))
    ;; Non-ASCII too: `File.toURI` would leave this raw and omit the empty authority.
    (is (= "[/tmp/ü.md](file:///tmp/%C3%BC.md)" (core/artifact-link "/tmp/ü.md")))
    ;; Every destination carries the canonical empty authority, never `file:/tmp/…`.
    (is (str/includes? (core/artifact-link "/tmp/report.md") "(file:///")))
  (testing "parentheses are encoded because an unbalanced one ends a Markdown destination"
    (is (= "[/tmp/a(b)/c.md](file:///tmp/a%28b%29/c.md)" (core/artifact-link "/tmp/a(b)/c.md")))
    (is (= "[/tmp/open(.md](file:///tmp/open%28.md)" (core/artifact-link "/tmp/open(.md"))))
  (testing "Markdown-significant characters are escaped in both the label and the purpose"
    (is (= "[/tmp/w\\[x\\].md](file:///tmp/w%5Bx%5D.md) — see \\[docs\\]"
           (core/artifact-link "/tmp/w[x].md — see [docs]")))
    ;; `&` and `<` are inline constructs: unescaped, a CommonMark renderer would decode the
    ;; entity reference and display a *different* path than the artifact actually has.
    (is (= "[/tmp/amp\\&amp;.md](file:///tmp/amp&amp;.md)" (core/artifact-link "/tmp/amp&amp;.md")))
    (is (= "[/tmp/lt\\<b\\>.md](file:///tmp/lt%3Cb%3E.md) — \\<b\\>bold\\</b\\> \\& raw"
           (core/artifact-link "/tmp/lt<b>.md — <b>bold</b> & raw")))
    ;; GFM strikethrough.
    (is (= "[/tmp/a\\~\\~b.md](file:///tmp/a~~b.md)" (core/artifact-link "/tmp/a~~b.md")))
    (is (= "[/tmp/a\\*b\\_c\\`d.md](file:///tmp/a*b_c%60d.md) — \\*emphatic\\* \\_purpose\\_"
           (core/artifact-link "/tmp/a*b_c`d.md — *emphatic* _purpose_")))
    (is (= "[/tmp/back\\\\slash.md](file:///tmp/back%5Cslash.md)"
           (core/artifact-link "/tmp/back\\slash.md"))))
  (testing "no raw OSC 8 escape sequence and no basename-only label"
    (let [rendered (core/artifact-link "/tmp/deep/nested/report.md — r")]
      (is (not (str/includes? rendered "\u001b")))
      (is (not (str/includes? rendered "]8;;")))
      (is (str/includes? rendered "[/tmp/deep/nested/report.md]"))))
  (testing "the splitter is shared with artifact-path, so both see the same path"
    (doseq [line ["/tmp/a b/report.md — purpose" "/tmp/a#b.md" "/tmp/a(b)/c.md — p"]]
      (let [path (core/artifact-path line)]
        (is (= path (:path (core/artifact-parts line))))
        (is (str/starts-with? (core/artifact-link line) (str "[" (core/markdown-escape path) "]"))))))
  ;; `Paths/get` resolves a relative path against the process cwd, so a caller that skipped
  ;; `artifact-path` would otherwise get a confident link to the wrong file.
  (testing "absoluteness is enforced by the renderer, not trusted from the caller"
    (doseq [bad ["rel/x.md" "rel/x.md — purpose" " — only a purpose" "" nil]]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"artifact path must be absolute"
                            (core/artifact-link bad))
          (pr-str bad)))))

;; `Path.toUri` stats the filesystem, so the renderer is not pure: an existing directory
;; gains a trailing slash its label does not have. Pinned rather than left latent, since it
;; also means the advisory (rendered before the file need exist) can differ from the
;; collected link for the same declared artifact.
(deftest artifact-link-uri-reflects-directory-state-on-disk
  (let [dir (fs/create-temp-dir {:prefix "subagent-artifact-uri-"})
        file (str (fs/path dir "report.md"))
        absent (str (fs/path dir "not-created"))]
    (spit file "body")
    (is (str/ends-with? (core/file-uri (str dir)) "/"))
    (is (not (str/ends-with? (core/file-uri file) "/")))
    (is (not (str/ends-with? (core/file-uri absent) "/")))
    ;; The label is the declared path either way, so a directory artifact's label omits the
    ;; slash its destination carries.
    (is (= (str "[" dir "](file://" dir "/)") (core/artifact-link (str dir))))))

;; The ` — ` delimiter is envelope grammar, not an escapable value: a path containing it
;; mis-splits identically for `artifact-path` and the renderer, which is why a truncated
;; prefix is caught by the collect-time existence check rather than by the renderer.
(deftest artifact-link-mis-splits-a-path-containing-the-delimiter
  (let [line "/tmp/a — b.md"]
    (is (= "/tmp/a" (core/artifact-path line)))
    (is (= "[/tmp/a](file:///tmp/a) — b.md" (core/artifact-link line)))))

(defn- stream-entry [result]
  {:child "child" :task "task" :result result})

(deftest stream-state-contract
  (let [dir (fs/create-temp-dir {:prefix "herdr-orch-stream-state-"})
        result (str (fs/path dir "round.result"))
        entry (stream-entry result)
        item (fn [n status]
               {:item n :result (ledger/item-path result n) :captured-at "2026-08-07T00:00:00Z"
                :status status :envelope {:status status}})]
    (testing "empty streams have no published or captured items and are unsealed"
      (is (= {:published [] :captured [] :sealed? false} (cli/stream-state entry))))
    (testing "a pre-change terminal entry with only the bare RESULT reads as one item"
      (spit result "terminal")
      (let [legacy (assoc entry :captured-at "2026-08-07T00:00:00Z" :status "COMPLETE"
                          :envelope {:status "COMPLETE"})
            state (cli/stream-state legacy)]
        (is (= [1] (mapv :item (:published state))))
        (is (= [1] (mapv :item (:captured state))))
        (is (true? (:sealed? state)))))
    (testing "WAITING followed by a terminal item retains both captures and seals mechanically"
      (spit (ledger/item-path result 2) "terminal")
      (let [state (cli/stream-state (assoc entry :items [(item 1 "WAITING") (item 2 "COMPLETE")]))]
        (is (= [1 2] (mapv :item (:published state))))
        (is (= [1 2] (mapv :item (:captured state))))
        (is (true? (:sealed? state)))))
    (testing "the ledger head stays on the newest captured item while item records retain both"
      (let [captured (-> entry
                         (cli/record-item-capture {:item 1 :result result} (dissoc (item 1 "WAITING") :item :result))
                         (cli/record-item-capture {:item 2 :result (ledger/item-path result 2)} (dissoc (item 2 "COMPLETE") :item :result)))]
        (is (= [1 2] (mapv :item (:items captured))))
        (is (= "COMPLETE" (:status captured)))
        (is (= "COMPLETE" (get-in captured [:envelope :status])))))
    (testing "a malformed item is captured for audit but never seals"
      (spit (ledger/item-path result 3) "not an envelope")
      (is (thrown? Exception (core/validate-envelope entry (slurp (ledger/item-path result 3)))))
      (let [state (cli/stream-state (assoc entry :items [(assoc (item 1 "invalid") :envelope nil)]))]
        (is (= [1 2 3] (mapv :item (:published state))))
        (is (= [1] (mapv :item (:captured state))))
        (is (false? (:sealed? state)))))))

(deftest waiting-status-is-valid-and-non-terminal
  (let [ledger {:child "child" :task "task" :result "/tmp/result"}
        text (core/envelope (assoc ledger :status "WAITING" :summary "still working" :artifacts [] :findings [] :next nil))]
    (is (= "WAITING" (:status (core/validate-envelope ledger text))))
    (is (false? (core/terminal-status? "WAITING")))
    (doseq [status ["COMPLETE" "FAILED" "BLOCKED"]]
      (is (true? (core/terminal-status? status)) status))))

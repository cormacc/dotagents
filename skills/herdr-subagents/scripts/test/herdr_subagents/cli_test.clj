(ns herdr-subagents.cli-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [herdr-subagents.cli :as cli]
            [herdr-subagents.core :as core]
            [herdr-subagents.herdr :as herdr]
            [herdr-subagents.ledger :as ledger]))

(defn- git-toplevel []
  (let [proc @(process/process ["git" "rev-parse" "--show-toplevel"] {:out :string :err :string})]
    (when (zero? (:exit proc)) (str/trim (:out proc)))))
(def root
  (if (fs/absolute? *file*)
    (str (fs/canonicalize (fs/path (fs/parent *file*) "../../../../..")))
    (or (git-toplevel) (throw (ex-info "cannot resolve repo root" {})))))
(def bin (str root "/skills/herdr-subagents/scripts/subagent"))
(def fake (str root "/skills/herdr-subagents/scripts/test/fixtures/fake-herdr"))
(defn calls [log] (if (fs/exists? log) (mapv #(str/split % #"\037") (str/split-lines (slurp log))) []))
(defn mutating? [argv] (and (not (some #{"--help"} argv)) (contains? #{["pane" "split"] ["tab" "create"] ["pane" "rename"] ["pane" "close"] ["agent" "start"] ["agent" "prompt"]} (vec (take 2 argv)))))
;; `SUBAGENT_ASSIGNMENT_ROOT` keeps the ledger, index markers, result files, and roster
;; lookup inside the per-test temp dir: `bb test` must never touch the live tree.
;; `HOME` points at an empty directory so personas can only resolve through the project
;; roster. Tests may supply an isolated roster instead of the tracked default roster.
(defn fake-env
  ([overrides] (fake-env overrides nil))
  ([overrides personas]
   (let [dir (fs/create-temp-dir {:prefix "fake-herdr-"}) log (str (fs/path dir "calls")) env-file (str (fs/path dir "env")) prompt-file (str (fs/path dir "prompt"))
         home (fs/path dir "home") roster (fs/path dir ".agents" "subagents") skills (fs/path dir "skills")]
     (fs/create-sym-link (fs/path dir "herdr") fake)
     (fs/create-dirs home)
     (fs/create-dirs (fs/parent roster))
     (if personas
       (do
         (fs/create-dirs roster)
         (doseq [[name body] personas]
           (spit (str (fs/path roster (str name ".md"))) body)))
       (fs/create-sym-link roster (fs/path root "subagents")))
     ;; `<root>/skills/` is the second skill probe and the shape this repository uses, so
     ;; the retro skill resolves in-fixture without an installed `~/.agents/skills`.
     (fs/create-sym-link skills (fs/path root "skills"))
     {:dir dir :log log :env-file env-file :prompt-file prompt-file :roster (str roster) :skills (str skills)
      :env (merge {"PATH" (str dir ":" (System/getenv "PATH")) "HERDR_ENV" "1" "HERDR_PANE_ID" "w:p" "HERDR_SUBAGENT_BIN" bin "FAKE_HERDR_LOG" log "FAKE_HERDR_ENV_FILE" env-file "FAKE_HERDR_PROMPT_FILE" prompt-file
                   "HOME" (str home) "SUBAGENT_ASSIGNMENT_ROOT" (str dir)} overrides)})))

(def advisor-strategy-roster
  {"advised-worker" "---\nname: advised-worker\ndescription: fixture executor\nkind: pi\nmodel: anthropic/claude-sonnet-5\nspawns: scout researcher advisor\n---\nFixture executor.\n"
   "advisor" "---\nname: advisor\ndescription: fixture advisor\nkind: pi\nmodel: anthropic/claude-opus-5\nretro: false\n---\nFixture advisor.\n"
   "scout" "---\nname: scout\ndescription: fixture scout\nkind: pi\nmodel: anthropic/claude-sonnet-5\nretro: false\n---\nFixture scout.\n"
   "researcher" "---\nname: researcher\ndescription: fixture researcher\nkind: pi\nmodel: anthropic/claude-sonnet-5\nretro: false\n---\nFixture researcher.\n"})
(defn call! [env & argv] @(process/process (into [bin] argv) {:out :string :err :string :env env}))
(defn result [proc] (json/parse-string (:out proc) true))

(deftest preflight-and-vector-argv-contract
  (let [{:keys [env log prompt-file dir env-file]} (fake-env {}) proc (call! env "run" "worker" "--task" "quotes ' newline\n $(unsafe) `unsafe`" "--timeout" "20")
        argv (calls log)
        injected (into {} (map #(vec (str/split % #"=" 2)) (str/split-lines (slurp env-file))))]
    (is (zero? (:exit proc)))
    ;; SUBAGENT_ASSIGNMENT_ROOT relocates ledger + result state and is inherited by the child.
    (is (str/starts-with? (injected "HERDR_SUBAGENT_RESULT") (str (fs/path dir ".agents" "tmp" "herdr-subagents"))))
    (is (= (str dir) (injected "SUBAGENT_ASSIGNMENT_ROOT")))
    (is (= "COMPLETE" (get-in (result proc) [:result :status])))
    (is (= ["pane" "split" "--pane" "w:p" "--direction" "right"] (subvec (vec (first (filter #(and (= ["pane" "split"] (vec (take 2 %))) (not (some #{"--help"} %))) argv))) 0 6)))
    (is (some #(= ["pane" "close"] (vec (take 2 %))) argv))
    (is (re-find #"(?s)\$\(unsafe\).*`unsafe`" (slurp prompt-file)))
    (is (= #{["pane" "layout"] ["pane" "split"] ["tab" "create"] ["pane" "rename"] ["pane" "get"] ["pane" "close"] ["agent" "start"] ["agent" "prompt"] ["agent" "wait"] ["agent" "get"] ["agent" "list"] ["notification" "show"]}
           (set (map #(vec (take 2 %)) (filter #(= "--help" (nth % 2 nil)) argv)))))))

(defn- ledger-entry* [dir task]
  (json/parse-string (slurp (str (fs/path dir ".agents" "tmp" "herdr-subagents" "ledger" (str task ".json")))) true))
(defn- injected-env [env-file key]
  (get (into {} (map #(vec (str/split % #"=" 2)) (str/split-lines (slurp env-file)))) key))

;; The below-root policy check precedes preflight, ledger allocation, and all Herdr calls.
(deftest below-root-disallowed-spawn-is-side-effect-free
  (let [{:keys [env log dir]} (fake-env {"HERDR_SUBAGENT_PERSONA" "worker" "HERDR_SUBAGENT_SPAWNS" "scout researcher"})
        proc (call! env "start" "worker" "--task" "disallowed nested worker")]
    (is (= 1 (:exit proc)))
    (is (re-find #"spawn refused: target persona is not in this agent's HERDR_SUBAGENT_SPAWNS allow-list" (:out proc)))
    (is (not (fs/exists? (fs/path dir ".agents" "tmp" "herdr-subagents" "ledger"))))
    (is (empty? (calls log)))))

(deftest below-root-worker-scout-is-a-leaf
  (let [{:keys [env log env-file dir prompt-file]} (fake-env {"HERDR_SUBAGENT_PERSONA" "worker"
                                                               "HERDR_SUBAGENT_SPAWNS" "scout researcher"
                                                               "FAKE_PARENT_LABEL" "worker-1-claude-opus-5"})
        proc (call! env "start" "scout" "--task" "permitted nested scout")
        task (get-in (result proc) [:result :task])
        entry (ledger-entry* dir task)
        rename (first (filter #(and (= ["pane" "rename"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "" (injected-env env-file "HERDR_SUBAGENT_SPAWNS")))
    (is (str/includes? (slurp prompt-file) "You are a leaf: do not spawn subagents."))
    ;; The live roster's scout declares the unversioned canonical `claude-sonnet`.
    (is (= "worker-1/scout-1-claude-sonnet" (:label entry)))
    (is (= ["pane" "rename" "w:child" "worker-1/scout-1-claude-sonnet"] (vec rename)))
    (is (= {:spawns [] :spawns-source "depth"} (select-keys entry [:spawns :spawns-source])))))

(deftest root-worker-spawn-records-and-injects-frontmatter-policy
  (let [{:keys [env env-file dir]} (fake-env {})
        proc (call! env "start" "worker" "--task" "root worker policy")
        task (get-in (result proc) [:result :task])
        entry (ledger-entry* dir task)]
    (is (zero? (:exit proc)) (:err proc))
    ;; The default `worker` (formerly advised-worker) grants the advisor consult too.
    (is (= {:spawns ["scout" "researcher" "advisor"] :spawns-source "frontmatter"}
           (select-keys entry [:spawns :spawns-source])))
    (is (= "scout researcher advisor" (injected-env env-file "HERDR_SUBAGENT_SPAWNS")))))

(deftest advisor-strategy-spawn-contract
  (testing "the root advised-worker resolves its fixture allow-list"
    (let [{:keys [env env-file dir]} (fake-env {} advisor-strategy-roster)
          proc (call! env "start" "advised-worker" "--task" "root advised-worker policy")
          task (get-in (result proc) [:result :task])
          entry (ledger-entry* dir task)]
      (is (zero? (:exit proc)) (:err proc))
      (is (= {:spawns ["scout" "researcher" "advisor"] :spawns-source "frontmatter"}
             (select-keys entry [:spawns :spawns-source])))
      (is (= "scout researcher advisor" (injected-env env-file "HERDR_SUBAGENT_SPAWNS")))))
  (testing "a permitted nested advisor is forced to a leaf and carries the model override"
    (let [{:keys [env env-file dir prompt-file]} (fake-env {"HERDR_SUBAGENT_PERSONA" "advised-worker"
                                                              "HERDR_SUBAGENT_SPAWNS" "advisor"
                                                              "FAKE_PARENT_LABEL" "advised-worker-1-claude-sonnet-5"}
                                                             advisor-strategy-roster)
          proc (call! env "start" "advisor" "--model" "anthropic/claude-fable-5" "--task" "nested advisor consult")
          task (get-in (result proc) [:result :task])
          entry (ledger-entry* dir task)]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "" (injected-env env-file "HERDR_SUBAGENT_SPAWNS")))
      (is (str/includes? (slurp prompt-file) "You are a leaf: do not spawn subagents."))
      (is (= {:spawns [] :spawns-source "depth"} (select-keys entry [:spawns :spawns-source])))
      (is (= "advised-worker-1/advisor-1-claude-fable-5" (:label entry)))
      (is (false? (:retro entry)))
      (is (= "frontmatter" (:retro-source entry)))))
  (testing "the root may spawn an advisor directly without a parent grant"
    (let [{:keys [env dir prompt-file]} (fake-env {} advisor-strategy-roster)
          proc (call! env "start" "advisor" "--task" "direct root advisor consult")
          task (get-in (result proc) [:result :task])
          entry (ledger-entry* dir task)]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "advisor-1-claude-opus-5" (:label entry)))
      (is (= {:spawns [] :spawns-source "default"} (select-keys entry [:spawns :spawns-source])))
      (is (false? (:retro entry)))
      (is (= "frontmatter" (:retro-source entry)))
      (is (not (str/includes? (slurp prompt-file) "apply steps 1-2 of"))))))

(deftest root-spawns-none-forces-a-leaf
  (let [{:keys [env env-file dir prompt-file]} (fake-env {})
        proc (call! env "start" "worker" "--spawns" "none" "--task" "forced leaf")
        task (get-in (result proc) [:result :task])
        entry (ledger-entry* dir task)]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "" (injected-env env-file "HERDR_SUBAGENT_SPAWNS")))
    (is (str/includes? (slurp prompt-file) "You are a leaf: do not spawn subagents."))
    (is (= {:spawns [] :spawns-source "flag"} (select-keys entry [:spawns :spawns-source])))))

;; A permitted below-root spawn still fail-fasts on the target persona's own broken
;; frontmatter `spawns:` declaration: depth forces the injected policy empty, but the
;; roster defect must surface loudly instead of silently degrading to a leaf.
(deftest below-root-spawn-validates-target-frontmatter
  (let [{:keys [env log dir roster]} (fake-env {"HERDR_SUBAGENT_PERSONA" "worker"
                                                "HERDR_SUBAGENT_SPAWNS" "broken"})]
    (fs/delete (fs/path roster)) ;; replace the live-roster symlink with a broken fixture persona
    (fs/create-dirs roster)
    (spit (str (fs/path roster "broken.md"))
          "---\nname: broken\ndescription: fixture persona with a misspelled grant\nspawns: does-not-exist\n---\nFixture persona.\n")
    (let [proc (call! env "start" "broken" "--task" "broken nested frontmatter")]
      (is (= 1 (:exit proc)))
      (is (re-find #"unresolvable persona `does-not-exist`" (:out proc)))
      (is (not (fs/exists? (fs/path dir ".agents" "tmp" "herdr-subagents" "ledger"))))
      (is (not-any? mutating? (calls log))))))

(deftest spawns-env-is-identical-for-tab-and-split-placement
  (let [{split-env :env split-env-file :env-file} (fake-env {})
        {tab-env :env tab-env-file :env-file} (fake-env {})
        split (call! split-env "start" "worker" "--task" "split policy env")
        tab (call! tab-env "start" "worker" "--tab" "--task" "tab policy env")]
    (is (zero? (:exit split)) (:err split))
    (is (zero? (:exit tab)) (:err tab))
    (is (= "scout researcher advisor" (injected-env split-env-file "HERDR_SUBAGENT_SPAWNS")))
    (is (= (injected-env split-env-file "HERDR_SUBAGENT_SPAWNS")
           (injected-env tab-env-file "HERDR_SUBAGENT_SPAWNS")))))

;; `--tab` places the child in a new unfocused tab of the caller's workspace instead of
;; a split, but every other contract (env, label, ledger, collect, closure) is identical.
(deftest tab-placement-contract
  (let [{:keys [env log env-file dir]} (fake-env {}) proc (call! env "run" "worker" "--tab" "--task" "tab placement" "--timeout" "20")
        argv (calls log)
        tab-create (first (filter #(and (= ["tab" "create"] (vec (take 2 %))) (not (some #{"--help"} %))) argv))
        task (get-in (result proc) [:result :task])
        entry (ledger-entry* dir task)
        injected (into {} (map #(vec (str/split % #"=" 2)) (str/split-lines (slurp env-file))))]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "COMPLETE" (get-in (result proc) [:result :status])))
    (is (some? tab-create))
    (is (= ["tab" "create" "--workspace"] (subvec (vec tab-create) 0 3)))
    (is (some #{"--no-focus"} tab-create))
    (is (some #{"--label"} tab-create))
    ;; No split command at all for a tab-placed spawn.
    (is (not-any? #(and (= ["pane" "split"] (vec (take 2 %))) (not (some #{"--help"} %))) argv))
    ;; The rename→start→prompt flow and closure are unchanged, against the tab's root pane.
    (is (some #(= ["pane" "rename" "w:child"] (vec (take 3 %))) argv))
    (is (some #(and (= ["pane" "close"] (vec (take 2 %))) (not (some #{"--help"} %))) argv))
    ;; Env injection is identical to a split spawn.
    (is (str/starts-with? (injected "HERDR_SUBAGENT_CHILD") "worker-"))
    (is (= "blocking" (injected "HERDR_SUBAGENT_WAITING_POLICY")))
    (is (= "tab" (:placement entry)))
    (is (= "w:tab" (:tab-id entry)))
    (is (= "w:child" (:pane-id entry)))))

;; Regression guard: a default spawn (no `--tab`) never issues a mutating `tab create`.
(deftest default-placement-emits-no-tab-commands
  (let [{:keys [env log dir]} (fake-env {}) proc (call! env "run" "worker" "--task" "default placement" "--timeout" "20")
        task (get-in (result proc) [:result :task]) entry (ledger-entry* dir task)]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "COMPLETE" (get-in (result proc) [:result :status])))
    (is (not-any? #(and (= ["tab" "create"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))
    (is (= "split" (:placement entry)))
    (is (nil? (:tab-id entry)))))

;; `--tab` under non-blocking `start` follows the same contract: the ledger records the
;; tab placement immediately and a later `collect --wait` captures and closes as usual.
(deftest tab-placement-start-collect
  (let [{:keys [env log dir]} (fake-env {})
        start (call! env "start" "worker" "--tab" "--task" "tab start")
        task (get-in (result start) [:result :task])
        entry (ledger-entry* dir task)
        proc (call! env "collect" task "--wait" "--timeout" "5000")]
    (is (zero? (:exit start)) (:err start))
    (is (= "tab" (:placement entry)))
    (is (= "w:tab" (:tab-id entry)))
    (is (= "w:child" (:pane-id entry)))
    (is (zero? (:exit proc)) (:err proc))
    (is (= "COMPLETE" (get-in (result proc) [:result :status])))
    ;; Closure is identical to a split spawn: collect closes the tab's root pane (Herdr
    ;; closes a tab whose last pane closes).
    (is (some #(= ["pane" "close" "w:child"] (vec (take 3 %))) (calls log)))))

;; Partial failure after `tab create`: the pane is recorded before the failing step, so
;; cleanup closes the tab's root pane and the ledger lands in a failed state.
(deftest tab-placement-partial-start-failure-is-tracked-and-cleaned
  (let [{:keys [env log dir]} (fake-env {"FAKE_FAIL_START" "1"})
        proc (call! env "start" "worker" "--tab" "--task" "tab fail")
        argv (calls log)
        ;; The failed `start` exits non-zero without printing the task id, so recover the
        ;; single entry file from the ledger directory (skipping the `indices/` subdir).
        entry (first (for [f (fs/list-dir (fs/path dir ".agents" "tmp" "herdr-subagents" "ledger"))
                           :when (and (fs/regular-file? f) (str/ends-with? (fs/file-name f) ".json"))]
                       (ledger-entry* dir (str/replace (fs/file-name f) #"\.json$" ""))))]
    (is (= 1 (:exit proc)))
    (is (some #(and (= ["tab" "create"] (vec (take 2 %))) (not (some #{"--help"} %))) argv))
    (is (some #(= ["pane" "close" "w:child"] (vec (take 3 %))) argv))
    (is (= "failed" (:status entry)))
    (is (= "start" (:failure-phase entry)))
    (is (= "tab" (:placement entry)))
    (is (= "w:tab" (:tab-id entry)))))

(deftest persona-system-prompt-dialect
  ;; Also covers --task-file and --prompt-extra assignment input.
  (let [{:keys [env log roster dir prompt-file]} (fake-env {}) file (str (fs/path dir "assignment.md"))
        _ (spit file "assignment from a file")
        pi-proc (call! env "start" "worker" "--task-file" file "--prompt-extra" "stay read-only")
        pi-start (first (filter #(and (= ["agent" "start"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))]
    (is (zero? (:exit pi-proc)))
    (is (some #(str/ends-with? % "/worker.md") pi-start))
    ;; Project roster wins end-to-end: the persona resolves under the assignment root,
    ;; not under $HOME/.agents/subagents (which does not exist in this fixture).
    (is (some #(= (str roster "/worker.md") %) pi-start))
    (is (str/includes? (slurp prompt-file) "assignment from a file"))
    (is (str/includes? (slurp prompt-file) "Additional constraints: stay read-only")))
  ;; Also covers the nested planner label end-to-end: the injected persona gates it.
  ;; Below root the spawn gate requires the target in the injected allow-list.
  (let [{:keys [env log]} (fake-env {"HERDR_SUBAGENT_PERSONA" "planner" "HERDR_SUBAGENT_SPAWNS" "worker"})
        claude-proc (call! env "start" "worker" "--kind" "claude" "--model" "sonnet" "--task" "claude persona")
        claude-start (first (filter #(and (= ["agent" "start"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))
        rename (first (filter #(and (= ["pane" "rename"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))]
    (is (zero? (:exit claude-proc)))
    (is (= "---" (last claude-start)))
    (is (not-any? #(str/ends-with? % "/worker.md") claude-start))
    ;; Fake `pane get w:p` reports the parent label `planner-1-model`.
    (is (= ["pane" "rename" "w:child" "planner-1/worker-1-sonnet"] (vec rename)))
    (is (= "planner-1/worker-1-sonnet" (get-in (result claude-proc) [:result :label])))))

(deftest preflight-fails-before-ledger-or-mutation
  (doseq [overrides [{"FAKE_HERDR_VERSION" "0.7.4"} {"FAKE_MISSING_CAPABILITY" "pane-split"}]]
    (let [{:keys [env log]} (fake-env overrides) proc (call! env "start" "worker" "--task" "x")]
      (is (= 1 (:exit proc)))
      (is (not-any? mutating? (calls log))))))

(deftest preview-is-side-effect-free
  (let [{:keys [env log]} (fake-env {}) proc (call! env "run" "worker" "--task" "preview" "--print-prompt")]
    (is (zero? (:exit proc)))
    (is (re-find #"<assigned-task>" (:out proc)))
    (is (not-any? mutating? (calls log)))
    (is (not-any? #(and (= ["pane" "get"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log))))
  (let [{:keys [env log]} (fake-env {}) proc (call! env "start" "not-a-persona" "--task" "x")]
    (is (= 1 (:exit proc)))
    (is (re-find #"persona not found" (:out proc)))
    (is (re-find #"available" (:out proc)))
    (is (not-any? mutating? (calls log)))))

(deftest start-collect-status-and-blocked-contract
  ;; The hand-written envelope is BLOCKED, which also covers a BLOCKED envelope
  ;; end-to-end: captured, but the pane is never closed.
  (let [{:keys [env env-file log]} (fake-env {}) start (call! env "start" "worker" "--task" "later") task (get-in (result start) [:result :task])
        values (into {} (map #(str/split % #"=" 2) (str/split-lines (slurp env-file))))
        envelope (core/envelope {:child (values "HERDR_SUBAGENT_CHILD") :task task :result (values "HERDR_SUBAGENT_RESULT") :status "BLOCKED" :summary "later" :artifacts [] :findings [] :next nil})]
    (spit (values "HERDR_SUBAGENT_RESULT") envelope)
    (is (= "BLOCKED" (get-in (result (call! env "collect" task)) [:result :status])))
    (is (zero? (:exit (call! env "status" task))))
    (is (zero? (:exit (call! env "list"))))
    (is (some #(= ["agent" "get"] (vec (take 2 %))) (calls log)))
    (is (not-any? #(and (= ["pane" "close"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log))))
  (let [{:keys [env log]} (fake-env {"FAKE_WAIT" "blocked"}) proc (call! env "run" "worker" "--task" "blocked" "--timeout" "20")]
    (is (= "blocked" (get-in (result proc) [:result :status])))
    (is (not-any? #(and (= ["pane" "close"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))))

(deftest result-edge-and-publication-contract
  ;; Publication during a structured Herdr wait error, with a FAILED envelope end-to-end.
  (let [{:keys [env log]} (fake-env {"FAKE_WAIT" "timeout-publish" "FAKE_PUBLISH_STATUS" "FAILED"}) proc (call! env "run" "worker" "--task" "timeout publication" "--timeout" "20")]
    (is (zero? (:exit proc)))
    (is (= "FAILED" (get-in (result proc) [:result :status])))
    (is (some #(= ["pane" "close"] (vec (take 2 %))) (calls log))))
  ;; A published result is immutable, so a mismatched envelope is a non-final `invalid`
  ;; outcome (pane retained, needs manual intervention) rather than a thrown command.
  (let [{:keys [env log]} (fake-env {"FAKE_WAIT" "publish" "FAKE_BAD_ENVELOPE" "1"}) proc (call! env "run" "worker" "--task" "stale" "--timeout" "20")]
    (is (zero? (:exit proc)))
    (is (= "invalid" (get-in (result proc) [:result :status])))
    (is (re-find #"identity" (get-in (result proc) [:result :reason])))
    (is (not-any? #(and (= ["pane" "close"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log))))
  (let [{:keys [env log dir]} (fake-env {}) target (str (fs/path dir "published.result"))
        publication-env (merge env {"HERDR_SUBAGENT_CHILD" "child" "HERDR_SUBAGENT_TASK" "task" "HERDR_SUBAGENT_RESULT" target "HERDR_SUBAGENT_WAITING_POLICY" "non-blocking"})
        ok (call! publication-env "publish" "--status" "COMPLETE" "--summary" "done") second (call! publication-env "publish" "--status" "COMPLETE" "--summary" "again")
        from-file-target (str (fs/path dir "from-file.result")) body (str (fs/path dir "body.json"))]
    (is (zero? (:exit ok))) (is (= 1 (:exit second))) (is (fs/exists? target))
    (is (some #(and (= ["notification" "show"] (vec (take 2 %))) (some (fn [arg] (str/includes? arg "child=child task=task")) %)) (calls log)))
    (spit body (json/generate-string {:status "COMPLETE" :summary "published from file" :artifacts [] :findings [] :next nil}))
    (let [proc (call! (merge publication-env {"HERDR_SUBAGENT_RESULT" from-file-target "HERDR_SUBAGENT_WAITING_POLICY" "blocking"}) "publish" "--from-file" body)]
      (is (zero? (:exit proc)) (:err proc))
      (is (str/includes? (slurp from-file-target) "SUMMARY: published from file"))))
  (let [{:keys [env dir]} (fake-env {"FAKE_NOTIFY_FAIL" "1"}) target (str (fs/path dir "notify.result"))
        proc (call! (merge env {"HERDR_SUBAGENT_CHILD" "child" "HERDR_SUBAGENT_TASK" "task" "HERDR_SUBAGENT_RESULT" target "HERDR_SUBAGENT_WAITING_POLICY" "non-blocking"}) "publish" "--status" "COMPLETE" "--summary" "done")]
    (is (zero? (:exit proc))) (is (fs/exists? target))))

;; The failure-publication instruction is invariant across personas: without it a child
;; that cannot finish stops silently and the parent blocks to its full budget
;; (task 0365cc41). It must name both non-COMPLETE statuses, carry the unrecoverable/
;; blocking-dependency bar, and forbid silence and re-publication after recovery.
(deftest failure-publication-instruction-is-invariant
  (let [{:keys [env]} (fake-env {})
        prompt-of (fn [persona] (:out (call! env "run" persona "--task" "x" "--print-prompt")))]
    (doseq [persona ["worker" "scout" "researcher" "planner"]]
      (let [out (prompt-of persona)]
        (is (str/includes? out "`--status BLOCKED` (dependency)") persona)
        (is (str/includes? out "`--status FAILED` (unrecoverable)") persona)
        (is (str/includes? out "unrecoverable failure after reasonable retries") persona)
        (is (str/includes? out "summarising work completed vs remaining") persona)
        (is (str/includes? out "never stop silently or publish a second envelope after recovering") persona)))))

;; Behavioural gate, not a file-content assertion: frontmatter values are strings, so a
;; `retro: false` that was never coerced would still gate the persona *in* and only a
;; prompt-level check catches it.
(deftest retro-gating-is-resolved-and-recorded
  (let [{:keys [env dir]} (fake-env {})
        prompt-of (fn [& argv] (:out (apply call! env "run" argv)))]
    (testing "default-enabled personas carry the instruction, opted-out personas do not"
      (let [worker (prompt-of "worker" "--task" "x" "--print-prompt")]
        (is (str/includes? worker (str dir "/skills/retro/SKILL.md")))
        (is (str/includes? worker "apply steps 1-2 of"))
        (is (str/includes? worker "signal → category → proposed rule"))
        (is (str/includes? worker "an absent PROCESS section is a valid outcome"))
        ;; The child performs retro steps 1-2 only; routing and persistence stay parent-side.
        (is (str/includes? worker "Do not choose a destination, load `self-improvement`, run `ot`, or edit any instruction file")))
      (doseq [persona ["scout" "researcher"]]
        (let [out (prompt-of persona "--task" "x" "--print-prompt")]
          (is (not (str/includes? out "--process")) persona)
          (is (not (str/includes? out "retro/SKILL.md")) persona))))
    (testing "the flag outranks frontmatter in both directions"
      (is (str/includes? (prompt-of "scout" "--task" "x" "--retro" "--print-prompt") "retro/SKILL.md"))
      (is (not (str/includes? (prompt-of "worker" "--task" "x" "--no-retro" "--print-prompt") "retro/SKILL.md"))))
    (testing "boolean flags never consume the following argv element"
      ;; Registered in only one of option-map/help-request? this swallows --task and
      ;; fails with "provide exactly one of --task, --task-file, or stdin".
      (let [proc (call! env "run" "worker" "--retro" "--task" "resolved assignment" "--print-prompt")]
        (is (zero? (:exit proc)) (:err proc))
        (is (str/includes? (:out proc) "resolved assignment")))
      (let [proc (call! env "run" "worker" "--retro" "--help")]
        (is (zero? (:exit proc)) (:err proc))
        (is (str/starts-with? (:out proc) "subagent run|start"))))
    (testing "contradictory flags fail fast"
      (let [proc (call! env "run" "worker" "--task" "x" "--retro" "--no-retro" "--print-prompt")]
        (is (= 1 (:exit proc)))
        (is (re-find #"mutually exclusive" (:out proc)))))
    (testing "an uncoercible frontmatter value fails fast, naming persona and value"
      (let [solo (fs/create-temp-dir {:prefix "fake-herdr-roster-"})]
        (fs/create-dirs (fs/path solo ".agents" "subagents"))
        (spit (str (fs/path solo ".agents" "subagents" "broken.md")) "---\nname: broken\nkind: pi\nretro: sometimes\n---\nbody")
        (let [proc (call! (merge env {"SUBAGENT_ASSIGNMENT_ROOT" (str solo)}) "run" "broken" "--task" "x" "--print-prompt")]
          (is (= 1 (:exit proc)))
          (is (re-find #"must be true or false" (:out proc)))
          (is (re-find #"broken" (:out proc)))
          (is (re-find #"sometimes" (:out proc))))))
    ;; The absent-skill branch (`:retro-source "skill-missing"`) is covered in core-test:
    ;; `user.home` in Babashka comes from the OS user database rather than `$HOME`, so the
    ;; third probe cannot be neutralised from a subprocess fixture. The assertion above
    ;; that the prompt names `<assignment-root>/skills/retro/SKILL.md` already proves the
    ;; project probes outrank the home probe end to end.
    )
  ;; Recording is independent of the policy branch, which is covered above and in core-test.
  (let [{:keys [env]} (fake-env {})
        proc (call! env "start" "worker" "--task" "ledger policy")
        entry (result proc)]
    (is (zero? (:exit proc)) (:err proc))
    (is (true? (get-in entry [:result :retro])))
    (is (= "default" (get-in entry [:result :retro-source]))))
  ;; Gating shapes the prompt only: a gated-out child that publishes PROCESS anyway is
  ;; still captured normally.
  (let [{:keys [env dir]} (fake-env {"FAKE_PUBLISH_PROCESS" "unsolicited → behavioral → still accepted"})
        proc (call! env "run" "scout" "--task" "gated out but publishes" "--timeout" "200")
        task (get-in (result proc) [:result :task])
        entry (json/parse-string (slurp (str (fs/path dir ".agents" "tmp" "herdr-subagents" "ledger" (str task ".json")))) true)]
    (is (= "COMPLETE" (get-in (result proc) [:result :status])))
    (is (false? (:retro entry)))
    (is (= ["unsolicited → behavioral → still accepted"] (get-in entry [:envelope :process])))))

;; PROCESS candidates travel with the result: emitted by `publish`, persisted onto the
;; ledger entry's `:envelope` at capture, and never gating capture or pane closure.
(deftest process-candidates-publish-and-persist
  (let [{:keys [env dir]} (fake-env {}) target (str (fs/path dir "process.result"))
        base (merge env {"HERDR_SUBAGENT_CHILD" "child" "HERDR_SUBAGENT_TASK" "task" "HERDR_SUBAGENT_WAITING_POLICY" "blocking"})
        proc (call! (merge base {"HERDR_SUBAGENT_RESULT" target}) "publish" "--status" "COMPLETE" "--summary" "done"
                    "--process" "wrong flag → guardrail → verify flags first"
                    "--process" "repeated probe → behavioral → cache the probe")]
    (is (zero? (:exit proc)) (:err proc))
    (is (= ["NEXT: none" "PROCESS:" "- wrong flag → guardrail → verify flags first" "- repeated probe → behavioral → cache the probe" "--- END HERDR RESULT ---"]
           (vec (drop 10 (str/split-lines (slurp target))))))
    ;; Six candidates are rejected at publish; the result file is never created.
    (let [over (str (fs/path dir "over.result"))
          rejected (apply call! (merge base {"HERDR_SUBAGENT_RESULT" over}) "publish" "--status" "COMPLETE" "--summary" "done"
                          (mapcat (fn [n] ["--process" (str "s" n " → c → r" n)]) (range 6)))]
      (is (= 1 (:exit rejected)))
      (is (re-find #"PROCESS exceeds" (:out rejected)))
      (is (not (fs/exists? over))))
    ;; `--from-file` carries the same list.
    (let [from-file (str (fs/path dir "from-file-process.result")) body (str (fs/path dir "process-body.json"))]
      (spit body (json/generate-string {:status "BLOCKED" :summary "blocked but instructive" :artifacts [] :findings [] :next nil
                                        :process ["missing env → guardrail → preflight the env"]}))
      (let [proc (call! (merge base {"HERDR_SUBAGENT_RESULT" from-file}) "publish" "--from-file" body)]
        (is (zero? (:exit proc)) (:err proc))
        (is (str/includes? (slurp from-file) "PROCESS:\n- missing env → guardrail → preflight the env\n--- END HERDR RESULT ---")))))
  ;; End to end: a published section survives capture onto the ledger entry.
  (let [{:keys [env dir]} (fake-env {"FAKE_PUBLISH_PROCESS" "stale doc → guardrail → read the contract first"})
        proc (call! env "run" "worker" "--task" "process capture" "--timeout" "200")
        task (get-in (result proc) [:result :task])
        entry (json/parse-string (slurp (str (fs/path dir ".agents" "tmp" "herdr-subagents" "ledger" (str task ".json")))) true)]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "COMPLETE" (get-in (result proc) [:result :status])))
    (is (= ["stale doc → guardrail → read the contract first"] (get-in entry [:envelope :process])))
    (is (nil? (:process-overflow entry))))
  ;; A hand-assembled six-item section degrades to five at capture: the result stays
  ;; COMPLETE and the pane still closes, because PROCESS never gates capture.
  (let [{:keys [env log dir]} (fake-env {"FAKE_PUBLISH_PROCESS" (str/join "\n- " (map #(str "s" % " → c → r" %) (range 6)))})
        proc (call! env "run" "worker" "--task" "process overflow" "--timeout" "200")
        task (get-in (result proc) [:result :task])
        entry (json/parse-string (slurp (str (fs/path dir ".agents" "tmp" "herdr-subagents" "ledger" (str task ".json")))) true)]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "COMPLETE" (get-in (result proc) [:result :status])))
    (is (= "COMPLETE" (:status entry)))
    (is (true? (:process-overflow entry)))
    (is (= 5 (count (get-in entry [:envelope :process]))))
    (is (some #(and (= ["pane" "close"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))))

(defn- ledger-entry [dir task]
  (json/parse-string (slurp (str (fs/path dir ".agents" "tmp" "herdr-subagents" "ledger" (str task ".json")))) true))
(defn- child-get-count [log]
  (count (filter #(and (= ["agent" "get"] (vec (take 2 %))) (not= "w:p" (nth % 2 nil)) (not (some #{"--help"} %))) (calls log))))

;; The child's session reference must survive pane close, and no single hook is reliable:
;; Herdr reports `agent_session` asynchronously, so each fixture mode below exercises one
;; hook in isolation by making it the only one that offers the session.
(deftest child-session-is-recorded-by-every-hook
  (testing "the herdr/start! return is used when it already carries the session"
    (let [{:keys [env dir log]} (fake-env {"FAKE_SESSION_FROM" "start"})
          proc (call! env "run" "worker" "--task" "session at start" "--timeout" "200")
          entry (ledger-entry dir (get-in (result proc) [:result :task]))]
      (is (= "COMPLETE" (get-in (result proc) [:result :status])))
      ;; The whole map, not `value` alone: `value` is meaningless without `kind`.
      (is (= {:agent "pi" :kind "path" :source "pi" :value "/tmp/fake-child-session.jsonl"} (:child-session entry)))
      ;; The entry is read back after capture *and* pane close, so the reference outlives
      ;; the pane it came from.
      (is (some #(and (= ["pane" "close"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))))
  (testing "a session absent at start is backfilled by the post-prompt agent get"
    (let [{:keys [env dir]} (fake-env {"FAKE_SESSION_FROM" "get"})
          proc (call! env "start" "worker" "--task" "session after prompt")
          entry (ledger-entry dir (get-in (result proc) [:result :task]))]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "path" (get-in entry [:child-session :kind])))
      (is (= "/tmp/fake-child-session.jsonl" (get-in entry [:child-session :value])))))
  (testing "a wait outcome backfills without adding a Herdr call to the loop"
    (let [{:keys [env dir log]} (fake-env {"FAKE_SESSION_FROM" "wait" "FAKE_WAIT" "idle-then-publish" "FAKE_WAIT_PUBLISH_AFTER" "3" "SUBAGENT_POLL_INTERVAL_MS" "20"})
          proc (call! env "run" "worker" "--task" "session from wait" "--timeout" "5000")
          entry (ledger-entry dir (get-in (result proc) [:result :task]))]
      (is (= "COMPLETE" (get-in (result proc) [:result :status])))
      (is (= "/tmp/fake-child-session.jsonl" (get-in entry [:child-session :value])))
      ;; Four wait iterations, but only the post-prompt probe and the maybe-close! refresh
      ;; may issue `agent get`; the loop itself adds none.
      (is (<= (child-get-count log) 2))))
  (testing "live (status/list) backfills while the child is alive"
    (let [{:keys [env dir]} (fake-env {"FAKE_SESSION_FROM" "late-get"})
          start (call! env "start" "worker" "--task" "session via status")
          task (get-in (result start) [:result :task])]
      (is (nil? (:child-session (ledger-entry dir task))))
      (is (zero? (:exit (call! env "status" task))))
      (is (= "/tmp/fake-child-session.jsonl" (get-in (ledger-entry dir task) [:child-session :value])))))
  (testing "maybe-close! refreshes a BLOCKED owned entry, whose pane is still retained"
    (let [{:keys [env env-file dir log]} (fake-env {"FAKE_SESSION_FROM" "late-get"})
          start (call! env "start" "worker" "--task" "blocked session")
          task (get-in (result start) [:result :task])
          values (into {} (map #(vec (str/split % #"=" 2)) (str/split-lines (slurp env-file))))]
      (is (nil? (:child-session (ledger-entry dir task))))
      (spit (values "HERDR_SUBAGENT_RESULT")
            (core/envelope {:child (values "HERDR_SUBAGENT_CHILD") :task task :result (values "HERDR_SUBAGENT_RESULT")
                            :status "BLOCKED" :summary "blocked" :artifacts [] :findings [] :next nil}))
      (is (= "BLOCKED" (get-in (result (call! env "collect" task)) [:result :status])))
      (is (= "/tmp/fake-child-session.jsonl" (get-in (ledger-entry dir task) [:child-session :value])))
      (is (not-any? #(and (= ["pane" "close"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))))
  (testing "a child that never publishes still carries its session"
    (let [{:keys [env dir]} (fake-env {"FAKE_SESSION_FROM" "start" "FAKE_WAIT" "idle-forever" "SUBAGENT_POLL_INTERVAL_MS" "50"})
          proc (call! env "run" "worker" "--task" "never publishes" "--timeout" "200")
          entry (ledger-entry dir (get-in (result proc) [:result :task]))]
      (is (= "pending" (get-in (result proc) [:result :status])))
      (is (= "/tmp/fake-child-session.jsonl" (get-in entry [:child-session :value])))))
  (testing "a session-less AgentInfo raises nothing and the spawn still succeeds"
    (let [{:keys [env dir]} (fake-env {"FAKE_SESSION_FROM" "none"})
          proc (call! env "run" "worker" "--task" "no session anywhere" "--timeout" "200")
          entry (ledger-entry dir (get-in (result proc) [:result :task]))]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "COMPLETE" (get-in (result proc) [:result :status])))
      (is (nil? (:child-session entry))))))

(deftest partial-start-failure-is-tracked-and-cleaned
  (let [{:keys [env log]} (fake-env {"FAKE_FAIL_START" "1"}) proc (call! env "start" "worker" "--task" "fail")]
    (is (= 1 (:exit proc)))
    (is (some #(= ["pane" "close"] (vec (take 2 %))) (calls log)))))

(defn- wait-call-count [log]
  (count (filter #(and (= ["agent" "wait"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log))))

;; Capture correctness after several settled-without-result iterations. The fixture
;; publishes on a fixed call count, so this test proves capture/parity only — the
;; interval-derived bound lives in `bounded-poll-timeout-without-result`.
(deftest bounded-poll-eventual-publication
  (let [{:keys [env log]} (fake-env {"FAKE_WAIT" "idle-then-publish" "FAKE_WAIT_PUBLISH_AFTER" "3" "SUBAGENT_POLL_INTERVAL_MS" "50"})
        proc (call! env "run" "worker" "--task" "idle then publish" "--timeout" "5000")]
    (is (zero? (:exit proc)))
    (is (= "COMPLETE" (get-in (result proc) [:result :status])))
    (is (<= 4 (wait-call-count log)))))

;; Regression guard for the poll sleep itself: without it, a settled-but-unpublished
;; child drives `agent wait` as fast as the process can fork (measured 37 calls over
;; this budget). The bound is well under half that, and above the sleep-derived ceiling
;; of 400/50 + 1 = 9.
(deftest bounded-poll-timeout-without-result
  (let [{:keys [env log]} (fake-env {"FAKE_WAIT" "idle-forever" "SUBAGENT_POLL_INTERVAL_MS" "50"})
        proc (call! env "run" "worker" "--task" "never publishes" "--timeout" "400")]
    (is (zero? (:exit proc)))
    (is (= "pending" (get-in (result proc) [:result :status])))
    (is (= "timeout" (get-in (result proc) [:result :reason])))
    (is (<= (wait-call-count log) 14))))

(deftest bounded-poll-covers-collect-wait
  (let [{:keys [env log]} (fake-env {"FAKE_WAIT" "idle-then-publish" "FAKE_WAIT_PUBLISH_AFTER" "2" "SUBAGENT_POLL_INTERVAL_MS" "50"})
        start (call! env "start" "worker" "--task" "later") task (get-in (result start) [:result :task])
        proc (call! env "collect" task "--wait" "--timeout" "5000")]
    (is (zero? (:exit proc)))
    (is (= "COMPLETE" (get-in (result proc) [:result :status])))
    (is (<= 3 (wait-call-count log)))))

(deftest a-negative-poll-interval-never-escapes
  (let [{:keys [env]} (fake-env {"FAKE_WAIT" "idle-forever" "SUBAGENT_POLL_INTERVAL_MS" "-5"})
        proc (call! env "run" "worker" "--task" "negative interval" "--timeout" "200")]
    (is (zero? (:exit proc)))
    (is (= "pending" (get-in (result proc) [:result :status])))))

(deftest stdin-assignment-input
  (let [{:keys [env prompt-file]} (fake-env {})
        proc @(process/process [bin "start" "worker"] {:in "assignment from stdin" :out :string :err :string :env env})]
    (is (zero? (:exit proc)) (:err proc))
    (is (str/includes? (slurp prompt-file) "assignment from stdin"))))

;; One `run` covers two contracts: the implicit ten-minute budget, and a `pane close`
;; failure never demoting an already-captured COMPLETE to a nonzero exit.
(deftest default-budget-and-close-failure-tolerance
  (let [{:keys [env log]} (fake-env {"FAKE_FAIL_CLOSE" "1"}) proc (call! env "run" "worker" "--task" "default budget")
        wait (first (filter #(and (= ["agent" "wait"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))
        budget (parse-long (second (drop-while #(not= "--timeout" %) wait)))]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "COMPLETE" (get-in (result proc) [:result :status])))
    (is (<= 599000 budget 600000))
    (is (some #(and (= ["pane" "close"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))))

(deftest collect-pane-close-is-scoped-to-the-owning-session
  (let [{:keys [env env-file log]} (fake-env {}) start (call! env "start" "worker" "--task" "foreign") task (get-in (result start) [:result :task])
        values (into {} (map #(vec (str/split % #"=" 2)) (str/split-lines (slurp env-file))))
        envelope (core/envelope {:child (values "HERDR_SUBAGENT_CHILD") :task task :result (values "HERDR_SUBAGENT_RESULT") :status "COMPLETE" :summary "foreign" :artifacts [] :findings [] :next nil})]
    (spit (values "HERDR_SUBAGENT_RESULT") envelope)
    (testing "a different parent session captures but never closes"
      (let [proc (call! (merge env {"HERDR_PANE_ID" "w:other"}) "collect" task)]
        (is (zero? (:exit proc)) (:err proc))
        (is (= "COMPLETE" (get-in (result proc) [:result :status])))
        (is (true? (get-in (result proc) [:result :pane-retained])))
        (is (= "foreign-parent-session" (get-in (result proc) [:result :ownership])))
        (is (not-any? #(and (= ["pane" "close"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))))
    (testing "an unresolvable caller identity is non-owning but still captures"
      (let [proc (call! (merge env {"FAKE_FAIL_AGENT_GET" "w:p"}) "collect" task)]
        (is (zero? (:exit proc)) (:err proc))
        (is (= "COMPLETE" (get-in (result proc) [:result :status])))
        (is (true? (get-in (result proc) [:result :pane-retained])))
        (is (not-any? #(and (= ["pane" "close"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))))
    (testing "the owning session still closes"
      (let [proc (call! env "collect" task)]
        (is (zero? (:exit proc)) (:err proc))
        (is (nil? (get-in (result proc) [:result :pane-retained])))
        (is (some #(and (= ["pane" "close"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))))))

(deftest missing-artifact-is-non-final-and-repeatable
  (let [{:keys [env log]} (fake-env {"FAKE_PUBLISH_ARTIFACT" "/nonexistent/subagent-artifact — missing"})
        proc (call! env "run" "worker" "--task" "bad artifact" "--timeout" "200")
        task (get-in (result proc) [:result :task])]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "invalid" (get-in (result proc) [:result :status])))
    (is (re-find #"artifact" (get-in (result proc) [:result :reason])))
    (is (not-any? #(and (= ["pane" "close"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))
    ;; Publication is immutable, so re-collecting must repeat the outcome, not throw.
    (let [again (call! env "collect" task)]
      (is (zero? (:exit again)) (:err again))
      (is (= "invalid" (get-in (result again) [:result :status]))))))

(deftest help-is-human-readable-text
  (let [{:keys [env]} (fake-env {})]
    (doseq [argv [["--help"] ["help"] ["run" "--help"] ["publish" "--help"]]]
      (let [proc (apply call! env argv)]
        (is (zero? (:exit proc)) (str argv " -> " (:err proc)))
        (is (str/starts-with? (:out proc) "subagent run|start"))
        (is (not (str/includes? (:out proc) "\"ok\"")))))))

;; Ties the shipped default table to the record's verified rows, independent of the
;; loader/translation machinery under test elsewhere in this namespace.
(deftest default-roster-table-content-contract
  (let [config (core/parse-roster "roster.edn" (slurp (str (fs/path root "skills" "herdr-subagents" "subagents" "roster.edn"))))]
    (is (= "--model" (get-in config [:harnesses :pi :model-flag])))
    (is (= "--model" (get-in config [:harnesses :claude :model-flag])))
    (is (= "--model" (get-in config [:harnesses :codex :model-flag])))
    ;; `gpt-*` canonical IDs are deliberate tier-equivalence remaps onto the anthropic
    ;; pi/claude columns, not identity claims; a codex spawn runs the gpt-* ID itself.
    (is (= {:pi "anthropic/claude-sonnet-5" :claude "sonnet" :codex "gpt-5.6-terra"} (get-in config [:models "gpt-5.6-terra"])))
    (is (= {:pi "anthropic/claude-opus-5" :claude "opus" :codex "gpt-5.6-sol"} (get-in config [:models "gpt-5.6-sol"])))
    (is (= {:pi "anthropic/claude-haiku-4-5" :claude "claude-haiku-4-5" :codex "gpt-5.6-luna"} (get-in config [:models "gpt-5.6-luna"])))
    ;; The undocumented `haiku` alias is deliberately not used for the claude column.
    (is (= "claude-haiku-4-5" (get-in config [:models "claude-haiku-4-5" :claude])))
    ;; Unversioned canonical IDs are floating aliases for the latest version of the tier.
    (doseq [[unversioned latest] [["claude-fable" "claude-fable-5"] ["claude-opus" "claude-opus-5"]
                                  ["claude-sonnet" "claude-sonnet-5"] ["claude-haiku" "claude-haiku-4-5"]
                                  ["gpt-sol" "gpt-5.6-sol"] ["gpt-terra" "gpt-5.6-terra"] ["gpt-luna" "gpt-5.6-luna"]]]
      (is (= (get-in config [:models latest]) (get-in config [:models unversioned]))
          (str unversioned " resolves to the same row as " latest)))
    (is (= ["--model" "gpt-5.6-terra"] (core/model-args config "codex" "claude-sonnet-5")))))

;; Loader precedence, row-level replacement, missing/malformed/invalid-shape handling,
;; and bare-subtree/relocated-root path derivation, exercised directly against
;; `cli/roster-config` (no subprocess needed) so `user.home` never enters the picture
;; — `home` is an explicit injected argument, and `launcher-bin`/`assignment-root` are
;; stubbed via `with-redefs` rather than relying on `$HOME`, which Babashka's
;; `user.home` property does not observe (see cli_test.clj fake-env docstring).
(deftest roster-config-loader-precedence-and-deployment-modes
  (let [tmp (str (fs/create-temp-dir {:prefix "roster-loader-"}))
        ;; A bare-subtree install: only `scripts/` + a sibling `roster.edn`, nested under
        ;; arbitrary ancestor names with no `bb.edn` anywhere — proving derivation is from
        ;; the launcher path alone, never cwd/git.
        launcher (str (fs/path tmp "install" "a" "b" "skills" "herdr-subagents" "scripts" "subagent"))
        default-roster (fs/path tmp "install" "a" "b" "skills" "herdr-subagents" "subagents" "roster.edn")
        home-dir (str (fs/path tmp "home"))
        project-root (str (fs/path tmp "project"))
        project-roster (fs/path project-root ".agents" "subagents" "roster.edn")
        home-roster (fs/path home-dir ".agents" "subagents" "roster.edn")]
    (fs/create-dirs (fs/parent default-roster))
    (fs/create-dirs project-root) (fs/create-dirs home-dir)
    (spit (str default-roster) (slurp (str (fs/path root "skills" "herdr-subagents" "subagents" "roster.edn"))))
    (with-redefs [cli/launcher-bin (constantly launcher) ledger/assignment-root (constantly project-root)]
      (testing "default only"
        (let [config (cli/roster-config home-dir)]
          (is (= "opus" (get-in config [:models "claude-opus-5" :claude])))
          (is (= "--model" (get-in config [:harnesses :codex :model-flag])))))
      (testing "home override replaces a row"
        (fs/create-dirs (fs/parent home-roster))
        (spit (str home-roster) "{:models {\"claude-opus-5\" {:claude \"opus-home\"}}}")
        (is (= "opus-home" (get-in (cli/roster-config home-dir) [:models "claude-opus-5" :claude]))))
      (testing "project beats home for the same ID; row-level replacement drops untouched columns"
        (fs/create-dirs (fs/parent project-roster))
        (spit (str project-roster) "{:models {\"claude-opus-5\" {:claude \"opus-project\"}}}")
        (let [config (cli/roster-config home-dir)]
          (is (= "opus-project" (get-in config [:models "claude-opus-5" :claude])))
          ;; The overridden row replaces the whole default row: :pi/:codex are gone, not
          ;; deep-merged alongside the new :claude value.
          (is (nil? (get-in config [:models "claude-opus-5" :pi])))))
      (testing "missing override files are silently ignored"
        (fs/delete home-roster) (fs/delete project-roster)
        (is (= "opus" (get-in (cli/roster-config home-dir) [:models "claude-opus-5" :claude]))))
      (testing "malformed EDN in an override throws naming its path"
        (spit (str project-roster) "{:models")
        (is (try (cli/roster-config home-dir) false
                 (catch clojure.lang.ExceptionInfo e (= (str project-roster) (:path (ex-data e))))))
        (fs/delete project-roster))
      (testing "a structurally invalid override throws naming its path"
        (spit (str project-roster) "{:harnesses {:pi {:model-flag \"\"}}}")
        (is (try (cli/roster-config home-dir) false
                 (catch clojure.lang.ExceptionInfo e (= (str project-roster) (:path (ex-data e))))))
        (fs/delete project-roster))
      (testing "portability: an override adding a new harness + model column translates for unmodified code"
        (spit (str project-roster) "{:harnesses {:gemini {:model-flag \"--model\"}} :models {\"claude-opus-5\" {:gemini \"gemini-2.5-pro\"}}}")
        (let [config (cli/roster-config home-dir)]
          (is (= ["--model" "gemini-2.5-pro"] (core/model-args config "gemini" "claude-opus-5")))
          ;; A kind still absent from `:harnesses` remains empty args — the addition is
          ;; purely additive data, no code change and no other kind affected.
          (is (= [] (core/model-args config "vertex" "claude-opus-5"))))
        (fs/delete project-roster)))
    (testing "missing shipped default is fatal"
      (with-redefs [cli/launcher-bin (constantly (str (fs/path tmp "empty-install" "skills" "herdr-subagents" "scripts" "subagent")))
                    ledger/assignment-root (constantly project-root)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing shipped default roster table" (cli/roster-config home-dir)))))))

(def roster-model-personas
  {"canonical-worker" "---\nname: canonical-worker\ndescription: fixture canonical-id persona\nkind: pi\nmodel: claude-opus-5\n---\nFixture canonical worker.\n"
   "kindless-worker" "---\nname: kindless-worker\ndescription: fixture kindless canonical-id persona\nmodel: claude-opus-5\n---\nFixture kindless worker.\n"})
(defn- start-native-args [log]
  (first (filter #(and (= ["agent" "start"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log))))
(defn- flag-value [argv flag] (second (drop-while #(not= flag %) argv)))

;; Acceptance: a definition model survives a kind override instead of being dropped (the
;; retired paired kind+model rule), and is translated for the resolved kind.
(deftest kind-override-retains-and-translates-the-definition-model
  (let [{:keys [env log]} (fake-env {} roster-model-personas)
        proc (call! env "start" "canonical-worker" "--kind" "claude" "--task" "kind override retains model")]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "opus" (flag-value (start-native-args log) "--model")))))

;; Acceptance: a kindless roster model is now honoured for any resolved kind, not only
;; pi (the retired pi-only kindless guard).
(deftest kindless-model-is-honoured-for-any-resolved-kind
  (let [{:keys [env log]} (fake-env {} roster-model-personas)
        proc (call! env "start" "kindless-worker" "--kind" "claude" "--task" "kindless model non-pi kind")]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "opus" (flag-value (start-native-args log) "--model")))))

;; Preview case: `--print-prompt` (against the fake herdr CLI) reports both the
;; canonical resolved model and the effective translated native model args.
(deftest preview-shows-canonical-and-translated-model
  (let [{:keys [env]} (fake-env {} roster-model-personas)
        proc (call! env "run" "canonical-worker" "--kind" "claude" "--task" "preview translation" "--print-prompt")]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "claude-opus-5" (get-in (result proc) [:result :model])))
    (is (= ["--model" "opus"] (get-in (result proc) [:result :model-args])))))

;; A `SUBAGENT_ASSIGNMENT_ROOT` relocation (the fixture's `dir`, distinct from the real
;; repo root) resolves the project roster override under the relocated root, winning
;; over the shipped default.
(deftest relocated-assignment-root-resolves-project-roster-override
  (let [{:keys [env dir]} (fake-env {} roster-model-personas)]
    (spit (str (fs/path dir ".agents" "subagents" "roster.edn")) "{:models {\"claude-opus-5\" {:claude \"opus-relocated\"}}}")
    (let [proc (call! env "run" "canonical-worker" "--kind" "claude" "--task" "relocated override" "--print-prompt")]
      (is (zero? (:exit proc)) (:err proc))
      (is (= ["--model" "opus-relocated"] (get-in (result proc) [:result :model-args]))))))

(def minimal-persona
  {"probe" "---\nname: probe\ndescription: fixture persona for roster fail-fast\nkind: pi\n---\nFixture probe.\n"})

;; Config is loaded and schema-validated before any ledger allocation or pane mutation:
;; a malformed project override must abort the whole spawn before either exists.
(deftest invalid-roster-override-fails-before-ledger-or-mutation
  (let [{:keys [env log dir]} (fake-env {} minimal-persona)]
    (spit (str (fs/path dir ".agents" "subagents" "roster.edn")) "{:harnesses {:pi {:model-flag \"\"}}}")
    (let [proc (call! env "start" "probe" "--task" "invalid roster aborts")]
      (is (= 1 (:exit proc)))
      (is (re-find #"model-flag" (:out proc)))
      (is (not (fs/exists? (fs/path dir ".agents" "tmp" "herdr-subagents" "ledger"))))
      (is (not-any? mutating? (calls log))))))

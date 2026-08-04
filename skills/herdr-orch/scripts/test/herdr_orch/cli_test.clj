;; Parallel deftest execution (org-tasks.test-runner) is opt-in per namespace;
;; this is the one namespace it's enabled for (task 2fe1ce2a). Contract: any
;; test mutating in-process state must be ^:serial.
(ns ^{:parallel-tests true} herdr-orch.cli-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [herdr-orch.cli :as cli]
            [herdr-orch.core :as core]
            [herdr-orch.herdr :as herdr]
            [herdr-orch.ledger :as ledger]))

(defn- git-toplevel []
  (let [proc @(process/process ["git" "rev-parse" "--show-toplevel"] {:out :string :err :string})]
    (when (zero? (:exit proc)) (str/trim (:out proc)))))
(def root
  (if (fs/absolute? *file*)
    (str (fs/canonicalize (fs/path (fs/parent *file*) "../../../../..")))
    (or (git-toplevel) (throw (ex-info "cannot resolve repo root" {})))))
(def bin (str root "/skills/herdr-orch/scripts/oh"))
(def fake (str root "/skills/herdr-orch/scripts/test/fixtures/fake-herdr"))
(defn calls [log] (if (fs/exists? log) (mapv #(str/split % #"\037") (str/split-lines (slurp log))) []))
;; One shared per-run dir for babashka's deps.clj-honoured `CLJ_CACHE` (user classpath
;; cache override, normally `~/.clojure/.cpcache`) and `CLJ_CONFIG` (user config-dir
;; override, normally `~/.clojure` itself: a default `deps.edn`/`tools/tools.edn` is
;; bootstrapped there on first use regardless of `CLJ_CACHE`), shared by every `fake-env`
;; call for the whole test run. Each call gives its subprocess a fresh, empty `HOME` for
;; isolation, but both of these are keyed off `HOME` by default, so without them every one
;; of the ~180 `subagent` invocations paid a cold (~0.7s) classpath-resolution cost instead
;; of a warm (~70ms) one, and left a `.clojure` directory behind in the fake `HOME`. The
;; delay warms the shared cache exactly once (a plain `--help` call, which needs no
;; `HERDR_ENV`) under its own throwaway `HOME` with the same two env vars, so warming
;; never touches the real user's `~/.clojure` and the resulting cache key matches every
;; later call.
(def shared-clj-cache
  (delay
    (let [dir (str (fs/create-temp-dir {:prefix "cli-test-clj-cache-"}))
          warm-home (str (fs/create-temp-dir {:prefix "cli-test-clj-cache-warm-home-"}))
          proc @(process/process [bin "--help"] {:out :string :err :string :env {"PATH" (System/getenv "PATH") "HOME" warm-home "CLJ_CACHE" dir "CLJ_CONFIG" dir}})]
      ;; A failed warm-up would silently degrade every later call back to cold
      ;; classpath resolution rather than fail correctness, so fail loudly instead.
      (when-not (zero? (:exit proc)) (throw (ex-info "failed to warm shared CLJ_CACHE" {:exit (:exit proc) :err (:err proc)})))
      dir)))
(defn mutating? [argv] (and (not (some #{"--help"} argv)) (contains? #{["pane" "split"] ["tab" "create"] ["pane" "rename"] ["pane" "close"] ["agent" "start"] ["agent" "prompt"]} (vec (take 2 argv)))))
;; `ORCH_ASSIGNMENT_ROOT` keeps the ledger, index markers, result files, and project
;; override lookup inside the per-test temp dir: `bb test` must never touch the live tree.
;; `HOME` points at an empty directory, so default personas and roster data resolve from
;; the launcher's packaged skill subtree. Tests may supply isolated project definitions.
(defn fake-env
  ([overrides] (fake-env overrides nil))
  ([overrides personas]
   (let [dir (fs/create-temp-dir {:prefix "fake-herdr-"}) log (str (fs/path dir "calls")) env-file (str (fs/path dir "env")) prompt-file (str (fs/path dir "prompt"))
         home (fs/path dir "home") roster (fs/path dir ".agents" "subagents") skills (fs/path dir "skills")]
     (fs/create-sym-link (fs/path dir "herdr") fake)
     (fs/create-dirs home)
     (fs/create-dirs (fs/parent roster))
     (when personas
       (fs/create-dirs roster)
       (doseq [[name body] personas]
         (spit (str (fs/path roster (str name ".md"))) body)))
     ;; `<root>/skills/` is the second skill probe and the shape this repository uses, so
     ;; the retro skill resolves in-fixture without an installed `~/.agents/skills`.
     (fs/create-sym-link skills (fs/path root "skills"))
     {:dir dir :log log :env-file env-file :prompt-file prompt-file :roster (str roster) :skills (str skills) :state (str (fs/path dir "state"))
      :env (merge {"PATH" (str dir ":" (System/getenv "PATH")) "HERDR_ENV" "1" "HERDR_PANE_ID" "w:p" "HERDR_ORCH_BIN" bin "FAKE_HERDR_LOG" log "FAKE_HERDR_ENV_FILE" env-file "FAKE_HERDR_PROMPT_FILE" prompt-file "FAKE_HERDR_STATE_DIR" (str (fs/path dir "state"))
                   "HOME" (str home) "ORCH_ASSIGNMENT_ROOT" (str dir)
                   "CLJ_CACHE" @shared-clj-cache "CLJ_CONFIG" @shared-clj-cache
                   ;; Fast-by-default retry backoff: keeps the two `agent-start-retr*` tests
                   ;; under 500ms without changing the unconfigured production default (500).
                   "SUBAGENT_START_RETRY_BACKOFF_MS" "10"}
                  overrides)})))

(def advisor-strategy-roster
  {"advised-worker" "---\nname: advised-worker\ndescription: fixture executor\nkind: pi\nmodel: anthropic/claude-sonnet-5\nspawns: scout researcher advisor\n---\nFixture executor.\n"
   "advisor" "---\nname: advisor\ndescription: fixture advisor\nkind: pi\nmodel: anthropic/claude-opus-5\nretro: false\n---\nFixture advisor.\n"
   "scout" "---\nname: scout\ndescription: fixture scout\nkind: pi\nmodel: anthropic/claude-sonnet-5\nretro: false\n---\nFixture scout.\n"
   "researcher" "---\nname: researcher\ndescription: fixture researcher\nkind: pi\nmodel: anthropic/claude-sonnet-5\nretro: false\n---\nFixture researcher.\n"})
(defn call! [env & argv] @(process/process (into [bin] argv) {:out :string :err :string :env env}))
(defn fake-start! [env & native-args]
  @(process/process (into [fake "agent" "start" "child" "--kind" "pi" "--pane" "w:child" "--"] native-args)
                    {:out :string :err :string :env env}))
(defn result [proc] (json/parse-string (:out proc) true))

;; Mutates global with-redefs state (ledger/assignment-root, cli/home-directory,
;; cli/packaged-personas-directory, cli/launcher-bin) -- must run serially.
(deftest ^:serial three-source-persona-discovery-contract
  (let [dir (fs/create-temp-dir {:prefix "persona-discovery-"})
        project (str (fs/path dir "project"))
        home (str (fs/path dir "home"))
        packaged (str (fs/path dir "installed" "herdr-orch" "subagents"))
        write-persona! (fn [directory name]
                         (fs/create-dirs directory)
                         (spit (str (fs/path directory (str name ".md")))
                               (str "---\nname: " name "\n---\nFixture persona.\n")))
        project-dir (str (fs/path project ".agents" "subagents"))
        home-dir (str (fs/path home ".agents" "subagents"))]
    ;; Each overlap proves a distinct precedence boundary, while one package-only
    ;; definition drives the fallback and spawn-policy validation paths.
    (doseq [[directory name] [[project-dir "project-only"]
                              [project-dir "project-wins"]
                              [home-dir "home-only"]
                              [home-dir "home-wins"]
                              [home-dir "project-wins"]
                              [packaged "packaged-only"]
                              [packaged "home-wins"]
                              [packaged "project-wins"]]]
      (write-persona! directory name))
    (with-redefs [ledger/assignment-root (constantly project)
                  cli/home-directory (constantly home)
                  cli/packaged-personas-directory (constantly packaged)]
      (is (= (str (fs/path project-dir "project-wins.md"))
             (str (cli/roster "project-wins"))))
      (is (= (str (fs/path home-dir "home-wins.md"))
             (str (cli/roster "home-wins"))))
      (is (= (str (fs/path packaged "packaged-only.md"))
             (str (cli/roster "packaged-only"))))
      ;; Names overlapping across directories appear only once in the sorted union.
      (is (= ["home-only" "home-wins" "packaged-only" "project-only" "project-wins"]
             (cli/available-personas)))
      ;; The real spawn-policy path accepts a packaged-only target. Its returned policy
      ;; varies with nesting depth, so only acceptance is asserted here.
      (is (map? (cli/spawns-policy "worker" {} {:spawns "packaged-only"})))
      (is (try (cli/spawns-policy "worker" {} {:spawns "unknown"}) false
               (catch clojure.lang.ExceptionInfo e
                 (and (re-find #"unresolvable persona `unknown`" (.getMessage e))
                      (= {:persona "worker" :spawn "unknown" :source "frontmatter"}
                         (ex-data e))))))
      (is (try (cli/roster "unknown") false
               (catch clojure.lang.ExceptionInfo e
                 (and (re-find #"project, home, or packaged" (.getMessage e))
                      (= ["home-only" "home-wins" "packaged-only" "project-only" "project-wins"]
                         (:available (ex-data e))))))))
  ;; This must follow the resolved launcher location, not the assignment root or cwd.
  (with-redefs [cli/launcher-bin (constantly "/opt/installed/herdr-orch/scripts/oh")]
    (is (= "/opt/installed/herdr-orch/subagents" (cli/packaged-personas-directory))))))

;; Mutates global with-redefs state (ledger/assignment-root, cli/home-directory,
;; cli/packaged-personas-directory) -- must run serially.
(deftest ^:serial available-personas-follows-home-roster-symlink
  (let [dir (fs/create-temp-dir {:prefix "persona-list-symlink-"})
        project (str (fs/path dir "project"))
        home (str (fs/path dir "home"))
        stored (fs/path dir "home-manager-store" "subagents")
        home-roster (fs/path home ".agents" "subagents")]
    (fs/create-dirs project)
    (fs/create-dirs stored)
    (spit (str (fs/path stored "home-only.md")) "---\nname: home-only\n---\nFixture persona.\n")
    (fs/create-dirs (fs/parent home-roster))
    (fs/create-sym-link home-roster stored)
    (with-redefs [ledger/assignment-root (constantly project)
                  cli/home-directory (constantly home)
                  cli/packaged-personas-directory (constantly (str (fs/path dir "packaged")))]
      (is (= ["home-only"] (cli/available-personas))))))

(deftest preflight-and-vector-argv-contract
  (let [{:keys [env log prompt-file dir env-file]} (fake-env {}) proc (call! env "task" "run" "worker" "--task" "quotes ' newline\n $(unsafe) `unsafe`" "--timeout" "20")
        argv (calls log)
        injected (into {} (map #(vec (str/split % #"=" 2)) (str/split-lines (slurp env-file))))]
    (is (zero? (:exit proc)))
    ;; ORCH_ASSIGNMENT_ROOT relocates ledger + result state and is inherited by the child.
    (is (str/starts-with? (injected "HERDR_ORCH_RESULT") (str (fs/path dir ".agents" "tmp" "herdr-orch"))))
    (is (= (str dir) (injected "ORCH_ASSIGNMENT_ROOT")))
    (is (= "COMPLETE" (get-in (result proc) [:result :status])))
    (is (= ["pane" "split" "--pane" "w:p" "--direction" "right"] (subvec (vec (first (filter #(and (= ["pane" "split"] (vec (take 2 %))) (not (some #{"--help"} %))) argv))) 0 6)))
    (is (some #(= ["pane" "close"] (vec (take 2 %))) argv))
    (is (re-find #"(?s)\$\(unsafe\).*`unsafe`" (slurp prompt-file)))
    (is (= #{["pane" "layout"] ["pane" "split"] ["tab" "create"] ["pane" "rename"] ["pane" "get"] ["pane" "close"] ["agent" "start"] ["agent" "prompt"] ["agent" "wait"] ["agent" "get"] ["agent" "list"] ["notification" "show"]}
           (set (map #(vec (take 2 %)) (filter #(= "--help" (nth % 2 nil)) argv)))))
    ;; The advisory parent push needs `agent wait --until`, so the spawn-side contract is
    ;; widened even though `publish!` never runs preflight. Every spawn above proves the
    ;; fixture advertises it: a missing flag throws "lacks required flag" during preflight.
    (is (= ["--timeout" "--until"]
           (some (fn [[command flags]] (when (= ["agent" "wait"] command) flags)) herdr/required-capabilities)))))

(defn- ledger-entry* [dir task]
  (json/parse-string (slurp (str (fs/path dir ".agents" "tmp" "herdr-orch" "ledger" (str task ".json")))) true))
(defn- injected-env [env-file key]
  (get (into {} (map #(vec (str/split % #"=" 2)) (str/split-lines (slurp env-file)))) key))

;; The below-root policy check precedes preflight, ledger allocation, and all Herdr calls.
(deftest below-root-disallowed-spawn-is-side-effect-free
  (let [{:keys [env log dir]} (fake-env {"HERDR_ORCH_PERSONA" "worker" "HERDR_ORCH_SPAWNS" "scout researcher"})
        proc (call! env "task" "start" "worker" "--task" "disallowed nested worker")]
    (is (= 1 (:exit proc)))
    (is (re-find #"spawn refused: target persona is not in this agent's HERDR_ORCH_SPAWNS allow-list" (:out proc)))
    (is (not (fs/exists? (fs/path dir ".agents" "tmp" "herdr-orch" "ledger"))))
    (is (empty? (calls log)))))

(deftest below-root-worker-scout-is-a-leaf
  (let [{:keys [env log env-file dir prompt-file]} (fake-env {"HERDR_ORCH_PERSONA" "worker"
                                                               "HERDR_ORCH_SPAWNS" "scout researcher"
                                                               "FAKE_PARENT_LABEL" "worker-1-claude-opus-5"})
        proc (call! env "task" "start" "scout" "--task" "permitted nested scout")
        task (get-in (result proc) [:result :task])
        entry (ledger-entry* dir task)
        rename (first (filter #(and (= ["pane" "rename"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "" (injected-env env-file "HERDR_ORCH_SPAWNS")))
    (is (str/includes? (slurp prompt-file) "You are a leaf: do not spawn subagents."))
    ;; The live roster's scout declares the provider-neutral `light` alias.
    (is (= "worker-1/scout-1-light" (:label entry)))
    (is (= ["pane" "rename" "w:child" "worker-1/scout-1-light"] (vec rename)))
    (is (= {:spawns [] :spawns-source "depth"} (select-keys entry [:spawns :spawns-source])))))

(deftest root-worker-spawn-records-and-injects-frontmatter-policy
  (let [{:keys [env env-file dir prompt-file]} (fake-env {})
        proc (call! env "task" "start" "worker" "--task" "root worker policy")
        task (get-in (result proc) [:result :task])
        entry (ledger-entry* dir task)
        prompt (slurp prompt-file)]
    (is (zero? (:exit proc)) (:err proc))
    ;; The default `worker` (formerly advised-worker) grants the advisor consult too.
    (is (= {:spawns ["scout" "researcher" "advisor"] :spawns-source "frontmatter"}
           (select-keys entry [:spawns :spawns-source])))
    (is (= "scout researcher advisor" (injected-env env-file "HERDR_ORCH_SPAWNS")))
    ;; The advisor is available but discretionary: the mandatory pre-publish review was
    ;; retired, so the single gap-only clause covers it and no ledger mandate is recorded.
    (is (not (contains? entry :required)))
    (is (str/includes? prompt "You may spawn at most one blocking ephemeral scout or researcher or advisor only when a factual gap or material judgment blocks progress; that child must remain a leaf."))
    (is (not (str/includes? prompt "mandates")))))

(deftest worker-spawn-policy-narrows-to-a-leaf
  (testing "--spawns none forces a leaf"
    (let [{:keys [env dir prompt-file]} (fake-env {})
          proc (call! env "task" "start" "worker" "--spawns" "none" "--task" "forced leaf worker")
          task (get-in (result proc) [:result :task])
          entry (ledger-entry* dir task)]
      (is (zero? (:exit proc)) (:err proc))
      (is (= {:spawns [] :spawns-source "flag"}
             (select-keys entry [:spawns :spawns-source])))
      (is (str/includes? (slurp prompt-file) "You are a leaf: do not spawn subagents."))))
  (testing "a below-root worker is depth-forced to a leaf"
    (let [{:keys [env dir prompt-file]} (fake-env {"HERDR_ORCH_PERSONA" "planner"
                                                   "HERDR_ORCH_SPAWNS" "worker"
                                                   "FAKE_PARENT_LABEL" "planner-1-claude-opus-5"})
          proc (call! env "task" "start" "worker" "--task" "nested worker")
          task (get-in (result proc) [:result :task])
          entry (ledger-entry* dir task)]
      (is (zero? (:exit proc)) (:err proc))
      (is (= {:spawns [] :spawns-source "depth"}
             (select-keys entry [:spawns :spawns-source])))
      (is (str/includes? (slurp prompt-file) "You are a leaf: do not spawn subagents.")))))

(deftest advisor-strategy-spawn-contract
  (testing "the root advised-worker resolves its fixture allow-list"
    (let [{:keys [env env-file dir]} (fake-env {} advisor-strategy-roster)
          proc (call! env "task" "start" "advised-worker" "--task" "root advised-worker policy")
          task (get-in (result proc) [:result :task])
          entry (ledger-entry* dir task)]
      (is (zero? (:exit proc)) (:err proc))
      (is (= {:spawns ["scout" "researcher" "advisor"] :spawns-source "frontmatter"}
             (select-keys entry [:spawns :spawns-source])))
      (is (= "scout researcher advisor" (injected-env env-file "HERDR_ORCH_SPAWNS")))))
  (testing "a permitted nested advisor is forced to a leaf and carries the model override"
    (let [{:keys [env env-file dir prompt-file]} (fake-env {"HERDR_ORCH_PERSONA" "advised-worker"
                                                              "HERDR_ORCH_SPAWNS" "advisor"
                                                              "FAKE_PARENT_LABEL" "advised-worker-1-claude-sonnet-5"}
                                                             advisor-strategy-roster)
          proc (call! env "task" "start" "advisor" "--model" "anthropic/claude-fable-5" "--task" "nested advisor consult")
          task (get-in (result proc) [:result :task])
          entry (ledger-entry* dir task)]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "" (injected-env env-file "HERDR_ORCH_SPAWNS")))
      (is (str/includes? (slurp prompt-file) "You are a leaf: do not spawn subagents."))
      (is (= {:spawns [] :spawns-source "depth"} (select-keys entry [:spawns :spawns-source])))
      (is (= "advised-worker-1/advisor-1-claude-fable-5" (:label entry)))
      (is (false? (:retro entry)))
      (is (= "frontmatter" (:retro-source entry)))))
  (testing "the root may spawn an advisor directly without a parent grant"
    (let [{:keys [env dir prompt-file]} (fake-env {} advisor-strategy-roster)
          proc (call! env "task" "start" "advisor" "--task" "direct root advisor consult")
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
        proc (call! env "task" "start" "worker" "--spawns" "none" "--task" "forced leaf")
        task (get-in (result proc) [:result :task])
        entry (ledger-entry* dir task)]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "" (injected-env env-file "HERDR_ORCH_SPAWNS")))
    (is (str/includes? (slurp prompt-file) "You are a leaf: do not spawn subagents."))
    (is (= {:spawns [] :spawns-source "flag"} (select-keys entry [:spawns :spawns-source])))))

;; A permitted below-root spawn still fail-fasts on the target persona's own broken
;; frontmatter `spawns:` declaration: depth forces the injected policy empty, but the
;; roster defect must surface loudly instead of silently degrading to a leaf.
(deftest below-root-spawn-validates-target-frontmatter
  (let [{:keys [env log dir roster]} (fake-env {"HERDR_ORCH_PERSONA" "worker"
                                                "HERDR_ORCH_SPAWNS" "broken"})]
    (fs/create-dirs roster)
    (spit (str (fs/path roster "broken.md"))
          "---\nname: broken\ndescription: fixture persona with a misspelled grant\nspawns: does-not-exist\n---\nFixture persona.\n")
    (let [proc (call! env "task" "start" "broken" "--task" "broken nested frontmatter")]
      (is (= 1 (:exit proc)))
      (is (re-find #"unresolvable persona `does-not-exist`" (:out proc)))
      (is (not (fs/exists? (fs/path dir ".agents" "tmp" "herdr-orch" "ledger"))))
      (is (not-any? mutating? (calls log))))))

(deftest relocated-assignment-root-persona-shadows-packaged-default
  (let [{:keys [env log roster]} (fake-env {}
                                           {"worker" "---\nname: worker\ndescription: relocated project worker\nmodel: light\n---\nProject override.\n"})
        proc (call! env "task" "start" "worker" "--task" "project persona shadows package")
        start (first (filter #(and (= ["agent" "start"] (vec (take 2 %)))
                                  (not (some #{"--help"} %)))
                             (calls log)))]
    (is (zero? (:exit proc)) (:err proc))
    (is (some #(= (str roster "/worker.md") %) start))
    (is (not-any? #(= (str root "/skills/herdr-orch/subagents/worker.md") %) start))))

(deftest spawns-env-is-identical-for-tab-and-split-placement
  (let [{split-env :env split-env-file :env-file} (fake-env {})
        {tab-env :env tab-env-file :env-file} (fake-env {})
        split (call! split-env "task" "start" "worker" "--task" "split policy env")
        tab (call! tab-env "task" "start" "worker" "--tab" "--task" "tab policy env")]
    (is (zero? (:exit split)) (:err split))
    (is (zero? (:exit tab)) (:err tab))
    (is (= "scout researcher advisor" (injected-env split-env-file "HERDR_ORCH_SPAWNS")))
    (is (= (injected-env split-env-file "HERDR_ORCH_SPAWNS")
           (injected-env tab-env-file "HERDR_ORCH_SPAWNS")))))

;; `--tab` places the child in a new unfocused tab of the caller's workspace instead of
;; a split, but every other contract (env, label, ledger, collect, closure) is identical.
(deftest tab-placement-contract
  (let [{:keys [env log env-file dir]} (fake-env {}) proc (call! env "task" "run" "worker" "--tab" "--task" "tab placement" "--timeout" "20")
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
    (is (str/starts-with? (injected "HERDR_ORCH_CHILD") "worker-"))
    (is (= "blocking" (injected "HERDR_ORCH_WAITING_POLICY")))
    (is (= "tab" (:placement entry)))
    (is (= "w:tab" (:tab-id entry)))
    (is (= "w:child" (:pane-id entry)))))

;; Regression guard: a default spawn (no `--tab`) never issues a mutating `tab create`.
(deftest default-placement-emits-no-tab-commands
  (let [{:keys [env log dir]} (fake-env {}) proc (call! env "task" "run" "worker" "--task" "default placement" "--timeout" "20")
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
        start (call! env "task" "start" "worker" "--tab" "--task" "tab start")
        task (get-in (result start) [:result :task])
        entry (ledger-entry* dir task)
        proc (call! env "task" "collect" task "--wait" "--timeout" "5000")]
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
        proc (call! env "task" "start" "worker" "--tab" "--task" "tab fail")
        argv (calls log)
        ;; The failed `start` exits non-zero without printing the task id, so recover the
        ;; single entry file from the ledger directory (skipping the `indices/` subdir).
        entry (first (for [f (fs/list-dir (fs/path dir ".agents" "tmp" "herdr-orch" "ledger"))
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
        pi-proc (call! env "task" "start" "worker" "--task-file" file "--prompt-extra" "stay read-only")
        pi-start (first (filter #(and (= ["agent" "start"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))]
    (is (zero? (:exit pi-proc)))
    (is (some #(str/ends-with? % "/worker.md") pi-start))
    ;; With no project or home definition, the persona resolves from the launcher-local
    ;; package rather than requiring a repository-root `subagents/` directory.
    (is (some #(= (str root "/skills/herdr-orch/subagents/worker.md") %) pi-start))
    (is (str/includes? (slurp prompt-file) "assignment from a file"))
    (is (str/includes? (slurp prompt-file) "Additional constraints: stay read-only")))
  ;; Also covers the nested planner label end-to-end: the injected persona gates it.
  ;; Below root the spawn gate requires the target in the injected allow-list.
  (let [{:keys [env log]} (fake-env {"HERDR_ORCH_PERSONA" "planner" "HERDR_ORCH_SPAWNS" "worker"})
        persona-path (str root "/skills/herdr-orch/subagents/worker.md")
        persona-body (slurp persona-path)
        claude-proc (call! env "task" "start" "worker" "--kind" "claude" "--model" "sonnet" "--task" "claude persona")
        claude-start (first (filter #(and (= ["agent" "start"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))
        rename (first (filter #(and (= ["pane" "rename"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))]
    (is (zero? (:exit claude-proc)))
    (is (= persona-path (last claude-start)))
    (is (some #(= ["--append-system-prompt-file" persona-path] %) (partition 2 1 claude-start)))
    (is (not-any? #(= persona-body %) claude-start))
    ;; Fake `pane get w:p` reports the parent label `planner-1-model`.
    (is (= ["pane" "rename" "w:child" "planner-1/worker-1-sonnet"] (vec rename)))
    (is (= "planner-1/worker-1-sonnet" (get-in (result claude-proc) [:result :label])))))

(deftest fake-herdr-agent-start-native-argument-contract
  (let [{:keys [env prompt-file]} (fake-env {})
        expected "{\"error\":{\"code\":\"invalid_agent_argument\",\"message\":\"agent arguments cannot be encoded safely for the target shell\"},\"id\":\"cli:agent:start\"}\n"]
    (testing "multiline persona bodies are unrepresentable in agent start argv"
      (doseq [body ["line one\nline two" "line one\tline two" "line one\rline two"]]
        (let [proc (fake-start! env "--append-system-prompt" body)]
          (is (= 1 (:exit proc)))
          (is (= expected (:err proc))))))
    (testing "shell-quoting hazards and long single-line values pass unchanged"
      (doseq [body ["$VAR `command` 'single' \"double\"" (apply str (repeat 4000 "x"))]]
        (is (zero? (:exit (fake-start! env "--append-system-prompt" body))))))
    (testing "multiline prompt text remains valid"
      (let [prompt "line one\nline two"
            proc @(process/process [fake "agent" "prompt" "child" prompt]
                                   {:out :string :err :string :env env})]
        (is (zero? (:exit proc)))
        (is (= prompt (slurp prompt-file)))))))

(deftest preflight-fails-before-ledger-or-mutation
  (doseq [overrides [{"FAKE_HERDR_VERSION" "0.7.4"} {"FAKE_MISSING_CAPABILITY" "pane-split"}]]
    (let [{:keys [env log]} (fake-env overrides) proc (call! env "task" "start" "worker" "--task" "x")]
      (is (= 1 (:exit proc)))
      (is (not-any? mutating? (calls log))))))

(deftest preview-is-side-effect-free
  (let [{:keys [env log]} (fake-env {}) proc (call! env "task" "run" "worker" "--task" "preview" "--print-prompt")]
    (is (zero? (:exit proc)))
    (is (re-find #"<assigned-task>" (:out proc)))
    (is (not-any? mutating? (calls log)))
    (is (not-any? #(and (= ["pane" "get"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log))))
  (let [{:keys [env log]} (fake-env {}) proc (call! env "task" "start" "not-a-persona" "--task" "x")]
    (is (= 1 (:exit proc)))
    (is (re-find #"persona not found" (:out proc)))
    (is (re-find #"available" (:out proc)))
    (is (not-any? mutating? (calls log)))))

(deftest placement-flags-and-preview-contract
  (testing "--tab and --split are mutually exclusive before ledger allocation or mutation"
    (let [{:keys [env log dir]} (fake-env {})
          proc (call! env "task" "start" "worker" "--tab" "--split" "--task" "conflicting placement")]
      (is (= 1 (:exit proc)))
      (is (re-find #"--tab and --split are mutually exclusive" (:out proc)))
      (is (not (fs/exists? (fs/path dir ".agents" "tmp" "herdr-orch" "ledger"))))
      (is (not-any? mutating? (calls log)))))
  (testing "--print-prompt reports the resolved placement"
    (doseq [[flag expected] [[[] "split"] [["--tab"] "tab"] [["--split"] "split"]]]
      (let [{:keys [env]} (fake-env {})
            proc (apply call! env (concat ["task" "run" "worker"] flag ["--task" "placement preview" "--print-prompt"]))]
        (is (zero? (:exit proc)) (:err proc))
        (is (= expected (get-in (result proc) [:result :placement])) (str flag))))))

(deftest configured-placement-defaults-flow-through-cli
  (let [{:keys [env log dir]} (fake-env {})
        project-config (fs/path dir ".agents" "subagents" "config.edn")
        preview (fn [env & flags]
                  (let [proc (apply call! env (concat ["task" "run" "worker"] flags ["--task" "configured placement" "--print-prompt"]))]
                    (is (zero? (:exit proc)) (:err proc))
                    (get-in (result proc) [:result :placement])))]
    (fs/create-dirs (fs/parent project-config))
    (spit (str project-config) "{:defaults {:placement :tab}}")
    (is (= "tab" (preview env)))
    (is (= "tab" (preview (merge env {"HERDR_ORCH_PERSONA" "worker" "HERDR_ORCH_SPAWNS" "worker"}))))
    (let [proc (call! env "task" "start" "worker" "--task" "configured tab placement")
          task (get-in (result proc) [:result :task])
          entry (ledger-entry* dir task)]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "tab" (:placement entry)))
      (is (some #(and (= ["tab" "create"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log))))
    (spit (str project-config) "{:defaults {:placement :tab-split}}")
    (is (= "tab" (preview env)))
    (is (= "split" (preview (merge env {"HERDR_ORCH_PERSONA" "worker" "HERDR_ORCH_SPAWNS" "worker"}))))
    (is (= "split" (preview env "--split")))))

(deftest configured-harness-extra-args-reach-agent-start
  ;; A permission bypass is opt-in configuration, never a shipped default: the same spawn
  ;; carries no native permission args until an override asks for them, and then carries
  ;; them only for the kind that was named.
  (let [{:keys [env log dir]} (fake-env {})
        project-config (fs/path dir ".agents" "subagents" "config.edn")
        start-argv (fn [] (first (filter #(and (= ["agent" "start"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log))))]
    (fs/create-dirs (fs/parent project-config))
    ;; No override: nothing resembling a permission flag reaches `agent start`.
    (let [proc (call! env "task" "start" "worker" "--kind" "claude" "--model" "sonnet" "--task" "default permissions")]
      (is (zero? (:exit proc)) (:err proc))
      (is (not-any? #{"--permission-mode" "bypassPermissions" "--dangerously-bypass-approvals-and-sandbox"} (start-argv))))
    (let [{:keys [env log dir]} (fake-env {})
          config (fs/path dir ".agents" "subagents" "config.edn")
          argv-for (fn [kind]
                     (let [proc (call! env "task" "start" "worker" "--kind" kind "--model" "sonnet" "--task" "bypass permissions")]
                       (is (zero? (:exit proc)) (:err proc))
                       (vec (last (filter #(and (= ["agent" "start"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log))))))]
      (fs/create-dirs (fs/parent config))
      (spit (str config)
            (str "{:harnesses {:claude {:model-flag \"--model\" :extra-args [\"--permission-mode\" \"bypassPermissions\"]}"
                 "             :codex {:model-flag \"--model\" :extra-args [\"--dangerously-bypass-approvals-and-sandbox\"]}}}"))
      (let [claude-argv (argv-for "claude")]
        (is (some #(= ["--permission-mode" "bypassPermissions"] %) (partition 2 1 claude-argv)))
        ;; The persona transport still follows the bypass args, unbroken by the insertion.
        (is (some #(and (= "--append-system-prompt-file" (first %)) (str/ends-with? (second %) "/worker.md")) (partition 2 1 claude-argv))))
      (let [codex-argv (argv-for "codex")]
        (is (some #{"--dangerously-bypass-approvals-and-sandbox"} codex-argv))
        ;; Codex gets no persona flag: prompt-level adoption only, per `persona-args`.
        (is (not-any? #{"--append-system-prompt" "--append-system-prompt-file"} codex-argv)))
      ;; pi was never named by the override, so it is unaffected.
      (let [pi-argv (argv-for "pi")]
        (is (not-any? #{"--permission-mode" "bypassPermissions" "--dangerously-bypass-approvals-and-sandbox"} pi-argv))))))

(deftest start-collect-status-and-blocked-contract
  ;; The hand-written envelope is BLOCKED, which also covers a BLOCKED envelope
  ;; end-to-end: captured, but the pane is never closed.
  (let [{:keys [env env-file log]} (fake-env {}) start (call! env "task" "start" "worker" "--task" "later") task (get-in (result start) [:result :task])
        values (into {} (map #(str/split % #"=" 2) (str/split-lines (slurp env-file))))
        envelope (core/envelope {:child (values "HERDR_ORCH_CHILD") :task task :result (values "HERDR_ORCH_RESULT") :status "BLOCKED" :summary "later" :artifacts [] :findings [] :next nil})]
    (spit (values "HERDR_ORCH_RESULT") envelope)
    (is (= "BLOCKED" (get-in (result (call! env "task" "collect" task)) [:result :status])))
    (is (zero? (:exit (call! env "task" "status" task))))
    (is (zero? (:exit (call! env "task" "list"))))
    (is (some #(= ["agent" "get"] (vec (take 2 %))) (calls log)))
    (is (not-any? #(and (= ["pane" "close"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log))))
  (let [{:keys [env log]} (fake-env {"FAKE_WAIT" "blocked"}) proc (call! env "task" "run" "worker" "--task" "blocked" "--timeout" "20")]
    (is (= "blocked" (get-in (result proc) [:result :status])))
    (is (not-any? #(and (= ["pane" "close"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))))

(deftest result-edge-and-publication-contract
  ;; Publication during a structured Herdr wait error, with a FAILED envelope end-to-end.
  (let [{:keys [env log]} (fake-env {"FAKE_WAIT" "timeout-publish" "FAKE_PUBLISH_STATUS" "FAILED"}) proc (call! env "task" "run" "worker" "--task" "timeout publication" "--timeout" "20")]
    (is (zero? (:exit proc)))
    (is (= "FAILED" (get-in (result proc) [:result :status])))
    (is (some #(= ["pane" "close"] (vec (take 2 %))) (calls log))))
  ;; A published result is immutable, so a mismatched envelope is a non-final `invalid`
  ;; outcome (pane retained, needs manual intervention) rather than a thrown command.
  (let [{:keys [env log]} (fake-env {"FAKE_WAIT" "publish" "FAKE_BAD_ENVELOPE" "1"}) proc (call! env "task" "run" "worker" "--task" "stale" "--timeout" "20")]
    (is (zero? (:exit proc)))
    (is (= "invalid" (get-in (result proc) [:result :status])))
    (is (re-find #"identity" (get-in (result proc) [:result :reason])))
    (is (not-any? #(and (= ["pane" "close"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log))))
  (let [{:keys [env log dir]} (fake-env {}) target (str (fs/path dir "published.result"))
        publication-env (merge env {"HERDR_ORCH_CHILD" "child" "HERDR_ORCH_TASK" "task" "HERDR_ORCH_RESULT" target "HERDR_ORCH_WAITING_POLICY" "non-blocking"})
        ok (call! publication-env "task" "publish" "--status" "COMPLETE" "--summary" "done") second (call! publication-env "task" "publish" "--status" "COMPLETE" "--summary" "again")
        from-file-target (str (fs/path dir "from-file.result")) body (str (fs/path dir "body.json"))]
    (is (zero? (:exit ok))) (is (= 1 (:exit second))) (is (fs/exists? target))
    (is (some #(and (= ["notification" "show"] (vec (take 2 %))) (some (fn [arg] (str/includes? arg "child=child task=task")) %)) (calls log)))
    (spit body (json/generate-string {:status "COMPLETE" :summary "published from file" :artifacts [] :findings [] :next nil}))
    (let [proc (call! (merge publication-env {"HERDR_ORCH_RESULT" from-file-target "HERDR_ORCH_WAITING_POLICY" "blocking"}) "task" "publish" "--from-file" body)]
      (is (zero? (:exit proc)) (:err proc))
      (is (str/includes? (slurp from-file-target) "SUMMARY: published from file"))))
  (let [{:keys [env dir]} (fake-env {"FAKE_NOTIFY_FAIL" "1"}) target (str (fs/path dir "notify.result"))
        proc (call! (merge env {"HERDR_ORCH_CHILD" "child" "HERDR_ORCH_TASK" "task" "HERDR_ORCH_RESULT" target "HERDR_ORCH_WAITING_POLICY" "non-blocking"}) "task" "publish" "--status" "COMPLETE" "--summary" "done")]
    (is (zero? (:exit proc))) (is (fs/exists? target))))

;; The failure-publication instruction is invariant across personas: without it a child
;; that cannot finish stops silently and the parent blocks to its full budget
;; (task 0365cc41). It must name both non-COMPLETE statuses, carry the unrecoverable/
;; blocking-dependency bar, and forbid silence and re-publication after recovery.
(deftest failure-publication-instruction-is-invariant
  (let [{:keys [env]} (fake-env {})
        prompt-of (fn [persona] (:out (call! env "task" "run" persona "--task" "x" "--print-prompt")))]
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
        prompt-of (fn [& argv] (:out (apply call! env "task" "run" argv)))]
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
      (let [proc (call! env "task" "run" "worker" "--retro" "--task" "resolved assignment" "--print-prompt")]
        (is (zero? (:exit proc)) (:err proc))
        (is (str/includes? (:out proc) "resolved assignment")))
      (let [proc (call! env "task" "run" "worker" "--retro" "--help")]
        (is (zero? (:exit proc)) (:err proc))
        (is (str/starts-with? (:out proc) "oh pane"))))
    (testing "contradictory flags fail fast"
      (let [proc (call! env "task" "run" "worker" "--task" "x" "--retro" "--no-retro" "--print-prompt")]
        (is (= 1 (:exit proc)))
        (is (re-find #"mutually exclusive" (:out proc)))))
    (testing "an uncoercible frontmatter value fails fast, naming persona and value"
      (let [solo (fs/create-temp-dir {:prefix "fake-herdr-roster-"})]
        (fs/create-dirs (fs/path solo ".agents" "subagents"))
        (spit (str (fs/path solo ".agents" "subagents" "broken.md")) "---\nname: broken\nkind: pi\nretro: sometimes\n---\nbody")
        (let [proc (call! (merge env {"ORCH_ASSIGNMENT_ROOT" (str solo)}) "task" "run" "broken" "--task" "x" "--print-prompt")]
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
        proc (call! env "task" "start" "worker" "--task" "ledger policy")
        entry (result proc)]
    (is (zero? (:exit proc)) (:err proc))
    (is (true? (get-in entry [:result :retro])))
    (is (= "default" (get-in entry [:result :retro-source]))))
  ;; Gating shapes the prompt only: a gated-out child that publishes PROCESS anyway is
  ;; still captured normally.
  (let [{:keys [env dir]} (fake-env {"FAKE_PUBLISH_PROCESS" "unsolicited → behavioral → still accepted"})
        proc (call! env "task" "run" "scout" "--task" "gated out but publishes" "--timeout" "200")
        task (get-in (result proc) [:result :task])
        entry (json/parse-string (slurp (str (fs/path dir ".agents" "tmp" "herdr-orch" "ledger" (str task ".json")))) true)]
    (is (= "COMPLETE" (get-in (result proc) [:result :status])))
    (is (false? (:retro entry)))
    (is (= ["unsolicited → behavioral → still accepted"] (get-in entry [:envelope :process])))))

;; PROCESS candidates travel with the result: emitted by `publish`, persisted onto the
;; ledger entry's `:envelope` at capture, and never gating capture or pane closure.
(deftest process-candidates-publish-and-persist
  (let [{:keys [env dir]} (fake-env {}) target (str (fs/path dir "process.result"))
        base (merge env {"HERDR_ORCH_CHILD" "child" "HERDR_ORCH_TASK" "task" "HERDR_ORCH_WAITING_POLICY" "blocking"})
        proc (call! (merge base {"HERDR_ORCH_RESULT" target}) "task" "publish" "--status" "COMPLETE" "--summary" "done"
                    "--process" "wrong flag → guardrail → verify flags first"
                    "--process" "repeated probe → behavioral → cache the probe")]
    (is (zero? (:exit proc)) (:err proc))
    (is (= ["NEXT: none" "PROCESS:" "- wrong flag → guardrail → verify flags first" "- repeated probe → behavioral → cache the probe" "--- END HERDR RESULT ---"]
           (vec (drop 10 (str/split-lines (slurp target))))))
    ;; Six candidates are rejected at publish; the result file is never created.
    (let [over (str (fs/path dir "over.result"))
          rejected (apply call! (merge base {"HERDR_ORCH_RESULT" over}) "task" "publish" "--status" "COMPLETE" "--summary" "done"
                          (mapcat (fn [n] ["--process" (str "s" n " → c → r" n)]) (range 6)))]
      (is (= 1 (:exit rejected)))
      (is (re-find #"PROCESS exceeds" (:out rejected)))
      (is (not (fs/exists? over))))
    ;; `--from-file` carries the same list.
    (let [from-file (str (fs/path dir "from-file-process.result")) body (str (fs/path dir "process-body.json"))]
      (spit body (json/generate-string {:status "BLOCKED" :summary "blocked but instructive" :artifacts [] :findings [] :next nil
                                        :process ["missing env → guardrail → preflight the env"]}))
      (let [proc (call! (merge base {"HERDR_ORCH_RESULT" from-file}) "task" "publish" "--from-file" body)]
        (is (zero? (:exit proc)) (:err proc))
        (is (str/includes? (slurp from-file) "PROCESS:\n- missing env → guardrail → preflight the env\n--- END HERDR RESULT ---")))))
  ;; End to end: a published section survives capture onto the ledger entry.
  (let [{:keys [env dir]} (fake-env {"FAKE_PUBLISH_PROCESS" "stale doc → guardrail → read the contract first"})
        proc (call! env "task" "run" "worker" "--task" "process capture" "--timeout" "200")
        task (get-in (result proc) [:result :task])
        entry (json/parse-string (slurp (str (fs/path dir ".agents" "tmp" "herdr-orch" "ledger" (str task ".json")))) true)]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "COMPLETE" (get-in (result proc) [:result :status])))
    (is (= ["stale doc → guardrail → read the contract first"] (get-in entry [:envelope :process])))
    (is (nil? (:process-overflow entry))))
  ;; A hand-assembled six-item section degrades to five at capture: the result stays
  ;; COMPLETE and the pane still closes, because PROCESS never gates capture.
  (let [{:keys [env log dir]} (fake-env {"FAKE_PUBLISH_PROCESS" (str/join "\n- " (map #(str "s" % " → c → r" %) (range 6)))})
        proc (call! env "task" "run" "worker" "--task" "process overflow" "--timeout" "200")
        task (get-in (result proc) [:result :task])
        entry (json/parse-string (slurp (str (fs/path dir ".agents" "tmp" "herdr-orch" "ledger" (str task ".json")))) true)]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "COMPLETE" (get-in (result proc) [:result :status])))
    (is (= "COMPLETE" (:status entry)))
    (is (true? (:process-overflow entry)))
    (is (= 5 (count (get-in entry [:envelope :process]))))
    (is (some #(and (= ["pane" "close"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))))

(defn- ledger-entry [dir task]
  (json/parse-string (slurp (str (fs/path dir ".agents" "tmp" "herdr-orch" "ledger" (str task ".json")))) true))
(defn- child-get-count [log]
  (count (filter #(and (= ["agent" "get"] (vec (take 2 %))) (not= "w:p" (nth % 2 nil)) (not (some #{"--help"} %))) (calls log))))

;; The child's session reference must survive pane close, and no single hook is reliable:
;; Herdr reports `agent_session` asynchronously, so each fixture mode below exercises one
;; hook in isolation by making it the only one that offers the session.
(deftest child-session-is-recorded-by-every-hook
  (testing "the herdr/start! return is used when it already carries the session"
    (let [{:keys [env dir log]} (fake-env {"FAKE_SESSION_FROM" "start"})
          proc (call! env "task" "run" "worker" "--task" "session at start" "--timeout" "200")
          entry (ledger-entry dir (get-in (result proc) [:result :task]))]
      (is (= "COMPLETE" (get-in (result proc) [:result :status])))
      ;; The whole map, not `value` alone: `value` is meaningless without `kind`.
      (is (= {:agent "pi" :kind "path" :source "pi" :value "/tmp/fake-child-session.jsonl"} (:child-session entry)))
      ;; The entry is read back after capture *and* pane close, so the reference outlives
      ;; the pane it came from.
      (is (some #(and (= ["pane" "close"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))))
  (testing "a session absent at start is backfilled by the post-prompt agent get"
    (let [{:keys [env dir]} (fake-env {"FAKE_SESSION_FROM" "get"})
          proc (call! env "task" "start" "worker" "--task" "session after prompt")
          entry (ledger-entry dir (get-in (result proc) [:result :task]))]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "path" (get-in entry [:child-session :kind])))
      (is (= "/tmp/fake-child-session.jsonl" (get-in entry [:child-session :value])))))
  (testing "a wait outcome backfills without adding a Herdr call to the loop"
    (let [{:keys [env dir log]} (fake-env {"FAKE_SESSION_FROM" "wait" "FAKE_WAIT" "idle-then-publish" "FAKE_WAIT_PUBLISH_AFTER" "3" "SUBAGENT_POLL_INTERVAL_MS" "20"})
          proc (call! env "task" "run" "worker" "--task" "session from wait" "--timeout" "5000")
          entry (ledger-entry dir (get-in (result proc) [:result :task]))]
      (is (= "COMPLETE" (get-in (result proc) [:result :status])))
      (is (= "/tmp/fake-child-session.jsonl" (get-in entry [:child-session :value])))
      ;; Four wait iterations, but only the post-prompt probe and the maybe-close! refresh
      ;; may issue `agent get`; the loop itself adds none.
      (is (<= (child-get-count log) 2))))
  (testing "live (status/list) backfills while the child is alive"
    (let [{:keys [env dir]} (fake-env {"FAKE_SESSION_FROM" "late-get"})
          start (call! env "task" "start" "worker" "--task" "session via status")
          task (get-in (result start) [:result :task])]
      (is (nil? (:child-session (ledger-entry dir task))))
      (is (zero? (:exit (call! env "task" "status" task))))
      (is (= "/tmp/fake-child-session.jsonl" (get-in (ledger-entry dir task) [:child-session :value])))))
  (testing "maybe-close! refreshes a BLOCKED owned entry, whose pane is still retained"
    (let [{:keys [env env-file dir log]} (fake-env {"FAKE_SESSION_FROM" "late-get"})
          start (call! env "task" "start" "worker" "--task" "blocked session")
          task (get-in (result start) [:result :task])
          values (into {} (map #(vec (str/split % #"=" 2)) (str/split-lines (slurp env-file))))]
      (is (nil? (:child-session (ledger-entry dir task))))
      (spit (values "HERDR_ORCH_RESULT")
            (core/envelope {:child (values "HERDR_ORCH_CHILD") :task task :result (values "HERDR_ORCH_RESULT")
                            :status "BLOCKED" :summary "blocked" :artifacts [] :findings [] :next nil}))
      (is (= "BLOCKED" (get-in (result (call! env "task" "collect" task)) [:result :status])))
      (is (= "/tmp/fake-child-session.jsonl" (get-in (ledger-entry dir task) [:child-session :value])))
      (is (not-any? #(and (= ["pane" "close"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))))
  (testing "a child that never publishes still carries its session"
    (let [{:keys [env dir]} (fake-env {"FAKE_SESSION_FROM" "start" "FAKE_WAIT" "idle-forever" "SUBAGENT_POLL_INTERVAL_MS" "50"})
          proc (call! env "task" "run" "worker" "--task" "never publishes" "--timeout" "200")
          entry (ledger-entry dir (get-in (result proc) [:result :task]))]
      (is (= "pending" (get-in (result proc) [:result :status])))
      (is (= "/tmp/fake-child-session.jsonl" (get-in entry [:child-session :value])))))
  (testing "a session-less AgentInfo raises nothing and the spawn still succeeds"
    (let [{:keys [env dir]} (fake-env {"FAKE_SESSION_FROM" "none"})
          proc (call! env "task" "run" "worker" "--task" "no session anywhere" "--timeout" "200")
          entry (ledger-entry dir (get-in (result proc) [:result :task]))]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "COMPLETE" (get-in (result proc) [:result :status])))
      (is (nil? (:child-session entry))))))

(defn- start-call-count [log] (count (filter #(and (= ["agent" "start"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log))))
(defn- split-call-count [log] (count (filter #(and (= ["pane" "split"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log))))

(deftest partial-start-failure-is-tracked-and-cleaned
  (let [{:keys [env log]} (fake-env {"FAKE_FAIL_START" "1"}) proc (call! env "task" "start" "worker" "--task" "fail")]
    (is (= 1 (:exit proc)))
    (is (some #(= ["pane" "close"] (vec (take 2 %))) (calls log)))
    ;; A non-`agent_pane_busy` error code fails on the first attempt: no retry.
    (is (= 1 (start-call-count log)))))

;; `agent_pane_busy` is the one herdr mutation error `agent start` retries; a
;; busy-then-available pane spawns successfully with no duplicate pane or ledger entry.
(deftest agent-start-retries-transient-pane-busy-then-succeeds
  (let [{:keys [env log dir]} (fake-env {"FAKE_START_BUSY_COUNT" "2"})
        proc (call! env "task" "start" "worker" "--task" "busy then available")
        task (get-in (result proc) [:result :task])
        entry (ledger-entry* dir task)]
    (is (zero? (:exit proc)) (:err proc))
    (is (nil? (:failure-phase entry)))
    (is (= "prompted" (:status entry)))
    (is (= "w:child" (:pane-id entry)))
    ;; Two simulated `agent_pane_busy` failures plus the eventual success.
    (is (= 3 (start-call-count log)))
    (is (= 1 (split-call-count log)))
    ;; The retry never triggers cleanup: no pane is ever closed (excluding the harmless
    ;; `pane close --help` preflight probe, present on every spawn).
    (is (not (some #(and (= ["pane" "close"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log))))
    ;; exactly one ledger entry
    (is (= 1 (count (filter #(and (fs/regular-file? %) (str/ends-with? (fs/file-name %) ".json"))
                             (fs/list-dir (fs/path dir ".agents" "tmp" "herdr-orch" "ledger"))))))))

;; A mapped-but-different error code (real `{"error":{"code":...}}` shape, not the
;; bare-string FAKE_FAIL_START) proves code discrimination, not just nil-safety: only
;; `agent_pane_busy` is retried, so this fails on the first attempt.
(deftest agent-start-other-error-code-fails-on-first-attempt
  (let [{:keys [env log]} (fake-env {"FAKE_START_ERROR_CODE" "agent_pane_unavailable"})
        proc (call! env "task" "start" "worker" "--task" "non-busy code")]
    (is (= 1 (:exit proc)))
    (is (= 1 (start-call-count log)))
    (is (some #(= ["pane" "close"] (vec (take 2 %))) (calls log)))))

;; Budget exhaustion (every attempt busy) still yields the existing `:start`-phase
;; cleanup: one ledger entry, failed status, and a closed child pane.
(deftest agent-start-retry-budget-exhaustion-fails-cleanly
  (let [{:keys [env log dir]} (fake-env {"FAKE_START_BUSY_COUNT" "99"})
        proc (call! env "task" "start" "worker" "--task" "always busy")
        entry (first (for [f (fs/list-dir (fs/path dir ".agents" "tmp" "herdr-orch" "ledger"))
                           :when (and (fs/regular-file? f) (str/ends-with? (fs/file-name f) ".json"))]
                       (ledger-entry* dir (str/replace (fs/file-name f) #"\.json$" ""))))]
    (is (= 1 (:exit proc)))
    (is (= "failed" (:status entry)))
    (is (= "start" (:failure-phase entry)))
    (is (some #(= ["pane" "close" "w:child"] (vec (take 3 %))) (calls log)))
    (is (= herdr/start-retry-attempts (start-call-count log)))
    (is (= 1 (split-call-count log)))
    (is (= 1 (count (filter #(and (fs/regular-file? %) (str/ends-with? (fs/file-name %) ".json"))
                             (fs/list-dir (fs/path dir ".agents" "tmp" "herdr-orch" "ledger"))))))))

;; Zero is truthy in Clojure and Thread/sleep rejects negatives, so both must fall back;
;; same discipline as `poll-interval-parsing` for `cli/parse-poll-interval`. This is the
;; only place the unconfigured 500ms default is exercised: `fake-env` sets the env override
;; for every other test in this namespace.
(deftest start-retry-backoff-parsing
  (is (= 500 (herdr/parse-start-retry-backoff nil)))
  (is (= 500 (herdr/parse-start-retry-backoff "")))
  (is (= 500 (herdr/parse-start-retry-backoff "   ")))
  (is (= 500 (herdr/parse-start-retry-backoff "0")))
  (is (= 500 (herdr/parse-start-retry-backoff "-5")))
  (is (= 500 (herdr/parse-start-retry-backoff "abc")))
  (is (= 500 herdr/default-start-retry-backoff-ms))
  (is (= 10 (herdr/parse-start-retry-backoff "10"))))

(defn- wait-call-count [log]
  (count (filter #(and (= ["agent" "wait"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log))))

;; Capture correctness after several settled-without-result iterations. The fixture
;; publishes on a fixed call count, so this test proves capture/parity only — the
;; interval-derived bound lives in `bounded-poll-timeout-without-result`.
(deftest bounded-poll-eventual-publication
  (let [{:keys [env log]} (fake-env {"FAKE_WAIT" "idle-then-publish" "FAKE_WAIT_PUBLISH_AFTER" "3" "SUBAGENT_POLL_INTERVAL_MS" "50"})
        proc (call! env "task" "run" "worker" "--task" "idle then publish" "--timeout" "5000")]
    (is (zero? (:exit proc)))
    (is (= "COMPLETE" (get-in (result proc) [:result :status])))
    (is (<= 4 (wait-call-count log)))))

;; Regression guard for the poll sleep itself: without it, a settled-but-unpublished
;; child drives `agent wait` as fast as the process can fork (measured 37 calls over
;; this budget). The bound is well under half that, and above the sleep-derived ceiling
;; of 400/50 + 1 = 9.
(deftest bounded-poll-timeout-without-result
  (let [{:keys [env log]} (fake-env {"FAKE_WAIT" "idle-forever" "SUBAGENT_POLL_INTERVAL_MS" "50"})
        proc (call! env "task" "run" "worker" "--task" "never publishes" "--timeout" "400")]
    (is (zero? (:exit proc)))
    (is (= "pending" (get-in (result proc) [:result :status])))
    (is (= "timeout" (get-in (result proc) [:result :reason])))
    (is (<= (wait-call-count log) 14))))

(deftest bounded-poll-covers-collect-wait
  (let [{:keys [env log]} (fake-env {"FAKE_WAIT" "idle-then-publish" "FAKE_WAIT_PUBLISH_AFTER" "2" "SUBAGENT_POLL_INTERVAL_MS" "50"})
        start (call! env "task" "start" "worker" "--task" "later") task (get-in (result start) [:result :task])
        proc (call! env "task" "collect" task "--wait" "--timeout" "5000")]
    (is (zero? (:exit proc)))
    (is (= "COMPLETE" (get-in (result proc) [:result :status])))
    (is (<= 3 (wait-call-count log)))))

(deftest a-negative-poll-interval-never-escapes
  (let [{:keys [env]} (fake-env {"FAKE_WAIT" "idle-forever" "SUBAGENT_POLL_INTERVAL_MS" "-5"})
        proc (call! env "task" "run" "worker" "--task" "negative interval" "--timeout" "200")]
    (is (zero? (:exit proc)))
    (is (= "pending" (get-in (result proc) [:result :status])))))

(deftest stdin-assignment-input
  (let [{:keys [env prompt-file]} (fake-env {})
        proc @(process/process [bin "task" "start" "worker"] {:in "assignment from stdin" :out :string :err :string :env env})]
    (is (zero? (:exit proc)) (:err proc))
    (is (str/includes? (slurp prompt-file) "assignment from stdin"))))

;; One `run` covers two contracts: the implicit ten-minute budget, and a `pane close`
;; failure never demoting an already-captured COMPLETE to a nonzero exit.
(deftest default-budget-and-close-failure-tolerance
  (let [{:keys [env log]} (fake-env {"FAKE_FAIL_CLOSE" "1"}) proc (call! env "task" "run" "worker" "--task" "default budget")
        wait (first (filter #(and (= ["agent" "wait"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))
        budget (parse-long (second (drop-while #(not= "--timeout" %) wait)))]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "COMPLETE" (get-in (result proc) [:result :status])))
    (is (<= 599000 budget 600000))
    (is (some #(and (= ["pane" "close"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))))

(deftest collect-pane-close-is-scoped-to-the-owning-session
  (let [{:keys [env env-file log]} (fake-env {}) start (call! env "task" "start" "worker" "--task" "foreign") task (get-in (result start) [:result :task])
        values (into {} (map #(vec (str/split % #"=" 2)) (str/split-lines (slurp env-file))))
        envelope (core/envelope {:child (values "HERDR_ORCH_CHILD") :task task :result (values "HERDR_ORCH_RESULT") :status "COMPLETE" :summary "foreign" :artifacts [] :findings [] :next nil})]
    (spit (values "HERDR_ORCH_RESULT") envelope)
    (testing "a different parent session captures but never closes"
      (let [proc (call! (merge env {"HERDR_PANE_ID" "w:other"}) "task" "collect" task)]
        (is (zero? (:exit proc)) (:err proc))
        (is (= "COMPLETE" (get-in (result proc) [:result :status])))
        (is (true? (get-in (result proc) [:result :pane-retained])))
        (is (= "foreign-parent-session" (get-in (result proc) [:result :ownership])))
        (is (not-any? #(and (= ["pane" "close"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))))
    (testing "an unresolvable caller identity is non-owning but still captures"
      (let [proc (call! (merge env {"FAKE_FAIL_AGENT_GET" "w:p"}) "task" "collect" task)]
        (is (zero? (:exit proc)) (:err proc))
        (is (= "COMPLETE" (get-in (result proc) [:result :status])))
        (is (true? (get-in (result proc) [:result :pane-retained])))
        (is (not-any? #(and (= ["pane" "close"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))))
    (testing "the owning session still closes"
      (let [proc (call! env "task" "collect" task)]
        (is (zero? (:exit proc)) (:err proc))
        (is (nil? (get-in (result proc) [:result :pane-retained])))
        (is (some #(and (= ["pane" "close"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))))))


;; Publication is exactly-once and immutable, so a relative artifact path must be caught
;; before the write, not only at collect (core/artifact-path is the same predicate there).
(deftest relative-artifact-rejected-before-publication
  (let [{:keys [env dir]} (fake-env {}) base (merge env {"HERDR_ORCH_CHILD" "child" "HERDR_ORCH_TASK" "task" "HERDR_ORCH_WAITING_POLICY" "blocking"})
        target (str (fs/path dir "relative-artifact.result"))
        tmp-siblings (fn [] (->> (fs/list-dir dir) (map str) (filter #(str/includes? % ".tmp"))))]
    (testing "a relative --artifact is rejected before RESULT or a .tmp sibling exist"
      (let [proc (call! (merge base {"HERDR_ORCH_RESULT" target}) "task" "publish" "--status" "COMPLETE" "--summary" "done" "--artifact" "relative/report.md — report")]
        (is (= 1 (:exit proc)))
        (is (re-find #"absolute" (:out proc)))
        (is (not (fs/exists? target)))
        (is (empty? (tmp-siblings)))))
    (testing "the same violation via --from-file is rejected identically"
      (let [body (str (fs/path dir "relative-artifact-body.json"))]
        (spit body (json/generate-string {:status "COMPLETE" :summary "done" :artifacts ["relative/report.md — report"] :findings [] :next nil}))
        (let [proc (call! (merge base {"HERDR_ORCH_RESULT" target}) "task" "publish" "--from-file" body)]
          (is (= 1 (:exit proc)))
          (is (re-find #"absolute" (:out proc)))
          (is (not (fs/exists? target)))
          (is (empty? (tmp-siblings))))))
    (testing "a corrected retry in the same child session then succeeds"
      (let [artifact (str (fs/path dir "report.md"))
            proc (call! (merge base {"HERDR_ORCH_RESULT" target}) "task" "publish" "--status" "COMPLETE" "--summary" "done" "--artifact" (str artifact " — report"))]
        (is (zero? (:exit proc)) (:err proc))
        (is (fs/exists? target))
        (is (str/includes? (slurp target) (str "- " artifact " — report")))
        (is (empty? (tmp-siblings)))))))

(deftest missing-artifact-is-non-final-and-repeatable
  (let [{:keys [env log]} (fake-env {"FAKE_PUBLISH_ARTIFACT" "/nonexistent/subagent-artifact — missing"})
        proc (call! env "task" "run" "worker" "--task" "bad artifact" "--timeout" "200")
        task (get-in (result proc) [:result :task])]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "invalid" (get-in (result proc) [:result :status])))
    (is (re-find #"artifact" (get-in (result proc) [:result :reason])))
    (is (not-any? #(and (= ["pane" "close"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))
    ;; Publication is immutable, so re-collecting must repeat the outcome, not throw.
    (let [again (call! env "task" "collect" task)]
      (is (zero? (:exit again)) (:err again))
      (is (= "invalid" (get-in (result again) [:result :status]))))))

;; --- advisory progress reporting --------------------------------------------------
;; `progress` makes no herdr call at all, so every case below is pure ledger/env
;; plumbing; the fixture needs no new verb to cover it.
(defn- child-progress-env [env entry]
  (merge env {"HERDR_ORCH_CHILD" (:child entry) "HERDR_ORCH_TASK" (:task entry) "HERDR_ORCH_RESULT" (:result entry)}))

(deftest progress-records-first-snapshot-and-status-list-expose-it-unchanged
  (let [{:keys [env dir]} (fake-env {})
        start (call! env "task" "start" "worker" "--task" "long-running work")
        task (get-in (result start) [:result :task])
        entry (ledger-entry* dir task)
        proc (call! (child-progress-env env entry) "task" "progress" "--summary" "phase one done")]
    (is (zero? (:exit start)) (:err start))
    (is (zero? (:exit proc)) (:err proc))
    (is (= "recorded" (get-in (result proc) [:result :status])))
    (is (= "phase one done" (get-in (result proc) [:result :progress :summary])))
    (let [updated (ledger-entry* dir task)]
      (is (= "phase one done" (get-in updated [:progress :summary])))
      (is (some? (get-in updated [:progress :reported-at])))
      (is (= (:status entry) (:status updated)) "progress never changes assignment status")
      (is (nil? (:captured-at updated)) "progress never captures the assignment"))
    (testing "status and list expose the same snapshot through the existing ledger entry shape"
      (let [status-proc (call! env "task" "status" task) list-proc (call! env "task" "list")]
        (is (zero? (:exit status-proc)) (:err status-proc))
        (is (= "phase one done" (get-in (result status-proc) [:result :progress :summary])))
        (is (zero? (:exit list-proc)) (:err list-proc))
        (is (some #(= "phase one done" (get-in % [:progress :summary])) (get-in (result list-proc) [:result])))))))

(deftest progress-throttles-within-the-configured-interval-leaving-snapshot-untouched
  (let [{:keys [env dir]} (fake-env {})
        start (call! env "task" "start" "worker" "--task" "throttle guard")
        task (get-in (result start) [:result :task])
        entry (ledger-entry* dir task)
        child-env (child-progress-env env entry)
        first-proc (call! child-env "task" "progress" "--summary" "first snapshot")
        first-snapshot (:progress (ledger-entry* dir task))
        second-proc (call! child-env "task" "progress" "--summary" "second snapshot, must not land")
        second-snapshot (:progress (ledger-entry* dir task))]
    (is (zero? (:exit first-proc)) (:err first-proc))
    (is (= "recorded" (get-in (result first-proc) [:result :status])))
    (is (zero? (:exit second-proc)) (:err second-proc))
    (is (= "throttled" (get-in (result second-proc) [:result :status])) "a non-error outcome, not a failure")
    (is (= first-snapshot second-snapshot) "the stored snapshot stays byte-identical")
    (is (= "first snapshot" (:summary second-snapshot)))))

(deftest progress-rewrites-the-snapshot-once-the-interval-elapses
  (let [{:keys [env dir]} (fake-env {"ORCH_PROGRESS_INTERVAL_MS" "50"})
        start (call! env "task" "start" "worker" "--task" "elapsed interval")
        task (get-in (result start) [:result :task])
        entry (ledger-entry* dir task)
        child-env (child-progress-env env entry)]
    (is (zero? (:exit (call! child-env "task" "progress" "--summary" "first"))))
    (Thread/sleep 100)
    (let [second (call! child-env "task" "progress" "--summary" "second")]
      (is (zero? (:exit second)) (:err second))
      (is (= "recorded" (get-in (result second) [:result :status])))
      (is (= "second" (get-in (ledger-entry* dir task) [:progress :summary]))))))

(deftest progress-never-invokes-herdr-or-notifies-the-parent
  (let [{:keys [env dir log]} (fake-env {})
        start (call! env "task" "start" "worker" "--task" "no herdr calls from progress")
        task (get-in (result start) [:result :task])
        entry (ledger-entry* dir task)
        before (count (calls log))
        proc (call! (child-progress-env env entry) "task" "progress" "--summary" "phase boundary")]
    (is (zero? (:exit start)) (:err start))
    (is (zero? (:exit proc)) (:err proc))
    (is (= "recorded" (get-in (result proc) [:result :status])))
    (is (= before (count (calls log))) "progress makes no herdr call at all, so the call log is untouched")))

(deftest a-non-positive-progress-interval-never-escapes-the-default
  (let [{:keys [env dir]} (fake-env {"ORCH_PROGRESS_INTERVAL_MS" "-5"})
        start (call! env "task" "start" "worker" "--task" "negative progress interval")
        task (get-in (result start) [:result :task])
        entry (ledger-entry* dir task)
        child-env (child-progress-env env entry)]
    (is (zero? (:exit (call! child-env "task" "progress" "--summary" "first"))))
    (let [second (call! child-env "task" "progress" "--summary" "second, should still throttle")]
      (is (zero? (:exit second)) (:err second))
      (is (= "throttled" (get-in (result second) [:result :status]))
          "a non-positive override must fall back to the 60s default, not to 0"))))

(deftest progress-rejected-once-result-file-exists-even-when-uncaptured
  (let [{:keys [env dir]} (fake-env {})
        start (call! env "task" "start" "worker" "--task" "result exists uncaptured")
        task (get-in (result start) [:result :task])
        entry (ledger-entry* dir task)
        envelope (core/envelope {:child (:child entry) :task task :result (:result entry)
                                  :status "COMPLETE" :summary "done" :artifacts [] :findings [] :next nil})]
    (spit (:result entry) envelope)
    (let [proc (call! (child-progress-env env entry) "task" "progress" "--summary" "too late")]
      (is (zero? (:exit start)) (:err start))
      (is (= 1 (:exit proc)))
      (is (re-find #"RESULT" (:out proc)))
      (is (nil? (:progress (ledger-entry* dir task)))))))

(deftest progress-rejected-after-capture
  (let [{:keys [env dir]} (fake-env {})
        run (call! env "task" "run" "worker" "--task" "captured before progress")
        task (get-in (result run) [:result :task])
        entry (ledger-entry* dir task)]
    (is (zero? (:exit run)) (:err run))
    (is (= "COMPLETE" (get-in (result run) [:result :status])))
    (is (some? (:captured-at entry)))
    ;; Isolate the :captured-at guard from the (also-true) RESULT-exists guard: a
    ;; captured assignment stays rejected even were its RESULT file later removed.
    (fs/delete-if-exists (:result entry))
    (let [proc (call! (child-progress-env env entry) "task" "progress" "--summary" "too late")]
      (is (= 1 (:exit proc)))
      (is (re-find #"captured" (:out proc)))
      (is (nil? (:progress (ledger-entry* dir task)))))))

(deftest progress-rejected-on-a-terminal-failed-status
  (let [{:keys [env dir]} (fake-env {"FAKE_FAIL_START" "1"})
        proc (call! env "task" "start" "worker" "--task" "start failure")
        ;; The failed `start` exits non-zero without printing the task id, so recover
        ;; the single entry file from the ledger directory directly (see `tab-placement-
        ;; partial-start-failure-is-tracked-and-cleaned` above for the same recovery).
        entry (first (for [f (fs/list-dir (fs/path dir ".agents" "tmp" "herdr-orch" "ledger"))
                           :when (and (fs/regular-file? f) (str/ends-with? (fs/file-name f) ".json"))]
                       (ledger-entry* dir (str/replace (fs/file-name f) #"\.json$" ""))))
        progress-proc (call! (child-progress-env env entry) "task" "progress" "--summary" "too late")]
    (is (= 1 (:exit proc)))
    (is (= "failed" (:status entry)))
    (is (nil? (:captured-at entry)))
    (is (= 1 (:exit progress-proc)))
    (is (re-find #"terminal" (:out progress-proc)))
    (is (nil? (:progress (ledger-entry* dir (:task entry)))))))

(deftest progress-rejected-on-wrong-or-missing-child-identity
  (let [{:keys [env dir]} (fake-env {})
        start (call! env "task" "start" "worker" "--task" "identity guard")
        task (get-in (result start) [:result :task])
        entry (ledger-entry* dir task)]
    (is (zero? (:exit start)) (:err start))
    (testing "a mismatched child cannot update another assignment's entry"
      (let [proc (call! (merge env {"HERDR_ORCH_CHILD" "someone-else" "HERDR_ORCH_TASK" task "HERDR_ORCH_RESULT" (:result entry)}) "task" "progress" "--summary" "spoofed")]
        (is (= 1 (:exit proc)))
        (is (re-find #"mismatch" (:out proc)))))
    (testing "a missing child env fails fast rather than falling back to another identity"
      (let [proc (call! (merge env {"HERDR_ORCH_TASK" task "HERDR_ORCH_RESULT" (:result entry)}) "task" "progress" "--summary" "no identity")]
        (is (= 1 (:exit proc)))
        (is (re-find #"HERDR_ORCH_CHILD" (:out proc)))))
    (is (nil? (:progress (ledger-entry* dir task))))))

(deftest progress-instruction-appears-only-under-the-non-blocking-policy
  (let [{:keys [env]} (fake-env {})
        blocking (call! env "task" "run" "worker" "--task" "blocking preview" "--print-prompt")
        non-blocking (call! env "task" "start" "worker" "--task" "non-blocking preview" "--print-prompt")]
    (is (zero? (:exit blocking)) (:err blocking))
    (is (zero? (:exit non-blocking)) (:err non-blocking))
    (is (not (re-find #"progress --summary" (:out blocking))))
    (is (re-find #"progress --summary" (:out non-blocking)))
    (is (re-find #"ORCH_PROGRESS_INTERVAL_MS" (:out non-blocking)))
    (is (not (re-find #"draft findings" (:out blocking))))))

;; --- `collect --any` fan-in -------------------------------------------------------
;; Multi-child helpers. The fixture clobbers FAKE_HERDR_ENV_FILE on every placement, so
;; a fan-out test reads each child's identity from its own ledger entry instead.
(defn- start-child! [env dir assignment]
  (let [proc (call! env "task" "start" "worker" "--task" assignment)]
    (when-not (zero? (:exit proc)) (throw (ex-info "fixture spawn failed" {:out (:out proc) :err (:err proc)})))
    (ledger-entry* dir (get-in (result proc) [:result :task]))))
(defn- publish-child!
  ([entry] (publish-child! entry "COMPLETE"))
  ([entry status]
   (spit (:result entry)
         (core/envelope {:child (:child entry) :task (:task entry) :result (:result entry)
                         :status status :summary "fan-in child" :artifacts [] :findings [] :next nil}))))
(defn- child-state! [state entry file value]
  (let [path (fs/path state "children" (:child entry) file)]
    (fs/create-dirs (fs/parent path))
    (spit (str path) value)))
(defn- agent-list-count [log]
  (count (filter #(and (= ["agent" "list"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log))))
;; `--help` must be excluded explicitly: `pane close --help` is an unconditional preflight
;; probe sharing this call log, and a two-token prefix match would silently swallow it.
(defn- closed-panes [log]
  (set (keep #(when (and (= ["pane" "close"] (vec (take 2 %))) (not (some #{"--help"} %))) (nth % 2 nil))
             (calls log))))

;; --- `prune` --------------------------------------------------------------------
;; Remedies the one documented `collect --any` gap: a `run`/`start` killed between
;; `ledger/write!` and its cleanup leaves a same-session, uncaptured, non-`failed` entry
;; that no `RESULT` will ever complete and whose child can never reappear in `agent list`.
;; No fake-herdr change is needed: `child-state!` "gone" already models a vanished child.

(deftest prune-succeeds-on-a-stale-uncaptured-entry-with-no-live-child
  (let [{:keys [env log dir state]} (fake-env {})
        a (start-child! env dir "orphaned by a killed spawn")]
    (child-state! state a "gone" "")
    (let [proc (call! env "task" "prune" (:task a)) res (:result (result proc))]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "failed" (:status res)))
      (is (= "orphaned" (:failure-phase res)))
      (is (= "missing-agent" (:prune-reason res)))
      (is (some? (:pruned-at res)))
      (let [updated (ledger-entry* dir (:task a))]
        (is (= "failed" (:status updated)))
        (is (= "orphaned" (:failure-phase updated)))
        (is (= "missing-agent" (:prune-reason updated)))
        (is (some? (:pruned-at updated))))
      (is (empty? (closed-panes log)) "prune never closes the recorded pane"))))

(deftest prune-makes-the-entry-ineligible-for-collect-any
  (let [{:keys [env dir state]} (fake-env {})
        a (start-child! env dir "sole candidate, killed mid-flight")]
    (child-state! state a "gone" "")
    (is (zero? (:exit (call! env "task" "prune" (:task a)))))
    (let [res (:result (result (call! env "task" "collect" "--any")))]
      (is (= "pending" (:status res)))
      (is (= "no-candidates" (:reason res))))))

(deftest prune-refuses-when-the-named-child-is-still-live
  (let [{:keys [env log dir]} (fake-env {})
        a (start-child! env dir "still alive, not stale")
        before (ledger-entry* dir (:task a))
        proc (call! env "task" "prune" (:task a))]
    (is (= 1 (:exit proc)))
    (is (re-find #"present in agent list" (:out proc)))
    (is (= before (ledger-entry* dir (:task a))))
    (is (empty? (closed-panes log)))))

(deftest prune-refuses-an-already-captured-entry
  (let [{:keys [env dir]} (fake-env {})
        run (call! env "task" "run" "worker" "--task" "already captured before prune")
        task (get-in (result run) [:result :task])
        before (ledger-entry* dir task)
        proc (call! env "task" "prune" task)]
    (is (zero? (:exit run)) (:err run))
    (is (some? (:captured-at before)))
    (is (= 1 (:exit proc)))
    (is (re-find #"captured" (:out proc)))
    (is (= before (ledger-entry* dir task)))))

(deftest prune-refuses-an-already-terminal-entry
  (let [{:keys [env dir]} (fake-env {"FAKE_FAIL_START" "1"})
        proc (call! env "task" "start" "worker" "--task" "start failure before prune")
        ;; The failed `start` exits non-zero without printing the task id, so recover the
        ;; single entry file directly (see `progress-rejected-on-a-terminal-failed-status`).
        entry (first (for [f (fs/list-dir (fs/path dir ".agents" "tmp" "herdr-orch" "ledger"))
                           :when (and (fs/regular-file? f) (str/ends-with? (fs/file-name f) ".json"))]
                       (ledger-entry* dir (str/replace (fs/file-name f) #"\.json$" ""))))
        prune-proc (call! env "task" "prune" (:task entry))]
    (is (= 1 (:exit proc)))
    (is (= "failed" (:status entry)))
    (is (nil? (:captured-at entry)))
    (is (= 1 (:exit prune-proc)))
    (is (re-find #"terminal" (:out prune-proc)))
    (is (= entry (ledger-entry* dir (:task entry))))))

(deftest prune-refuses-when-result-file-already-exists
  (let [{:keys [env dir]} (fake-env {})
        a (start-child! env dir "result exists uncaptured before prune")]
    (publish-child! a)
    (let [proc (call! env "task" "prune" (:task a))]
      (is (= 1 (:exit proc)))
      (is (re-find #"RESULT" (:out proc)))
      (is (nil? (:captured-at (ledger-entry* dir (:task a)))))
      (is (= "prompted" (:status (ledger-entry* dir (:task a))))))))

;; Mirrors `collect-pane-close-is-scoped-to-the-owning-session`'s foreign-pane pattern: a
;; different `HERDR_PANE_ID` resolves to a caller identity that never matches the entry's
;; recorded `:parent-session`, so ownership is refused rather than merely retaining a pane.
(deftest prune-refuses-a-foreign-parent-session
  (let [{:keys [env dir]} (fake-env {})
        a (start-child! env dir "foreign session prune attempt")
        proc (call! (merge env {"HERDR_PANE_ID" "w:other"}) "task" "prune" (:task a))]
    (is (= 1 (:exit proc)))
    (is (re-find #"own" (:out proc)))
    (is (nil? (:captured-at (ledger-entry* dir (:task a)))))
    (is (= "prompted" (:status (ledger-entry* dir (:task a)))))))

(deftest prune-refuses-an-unresolvable-caller-identity
  (let [{:keys [env dir]} (fake-env {})
        a (start-child! env dir "unresolvable caller identity")
        proc (call! (merge env {"FAKE_FAIL_AGENT_GET" "w:p"}) "task" "prune" (:task a))]
    (is (= 1 (:exit proc)))
    (is (re-find #"own" (:out proc)))
    (is (= "prompted" (:status (ledger-entry* dir (:task a)))))))

;; Ambiguous liveness must never allow a prune: an unusable `agent list` (no `agents` key)
;; is indistinguishable from schema drift, so it must refuse even when the child really is
;; `gone` — a positive absence, not merely an unusable listing, is the only proof accepted.
(deftest prune-refuses-when-agent-list-is-unusable
  (let [{:keys [env dir state]} (fake-env {"FAKE_AGENT_LIST_NO_AGENTS_KEY" "1"})
        a (start-child! env dir "ambiguous liveness must never allow a prune")]
    (child-state! state a "gone" "")
    (let [proc (call! env "task" "prune" (:task a))]
      (is (= 1 (:exit proc)))
      (is (re-find #"unusable" (:out proc)))
      (is (= "prompted" (:status (ledger-entry* dir (:task a))))))))

(deftest prune-requires-a-task-positional
  (let [{:keys [env]} (fake-env {})
        proc (call! env "task" "prune")]
    (is (= 1 (:exit proc)))
    (is (re-find #"full task uuid" (:out proc)))))

;; This command never scans the ledger or resolves a prefix: an exact-filename miss is
;; the same "unknown assignment task" `ledger/read!` already raises for `collect`/`status`.
(deftest prune-does-not-resolve-a-task-id-prefix
  (let [{:keys [env dir]} (fake-env {})
        a (start-child! env dir "prefix must never resolve")
        prefix (subs (:task a) 0 8)
        proc (call! env "task" "prune" prefix)]
    (is (= 1 (:exit proc)))
    (is (re-find #"unknown assignment task" (:out proc)))))

;; Pins two facts together: (a) `usage` renders the identical `<full-task-uuid>`
;; placeholder for every assignment command, and (b) `collect`/`status` preserve the
;; same exact-ledger-key `unknown assignment task` rejection `prune` already pins above
;; -- never `ot`-style prefix resolution.
(deftest collect-and-status-do-not-resolve-a-task-id-prefix
  (let [{:keys [env dir]} (fake-env {}) usage (:out (call! env "--help"))
        a (start-child! env dir "prefix must never resolve")
        prefix (subs (:task a) 0 8)]
    (is (str/includes? usage "oh task collect <full-task-uuid> [--wait --timeout MS]"))
    (is (str/includes? usage "oh task status [full-task-uuid] | list"))
    (is (str/includes? usage "no prefix is ever resolved"))
    (is (not (str/includes? usage "collect <task>")))
    (is (not (str/includes? usage "status [task]")))
    (doseq [command ["collect" "status"]]
      (let [proc (call! env "task" command prefix)]
        (is (= 1 (:exit proc)) command)
        (is (re-find #"unknown assignment task" (:out proc)) command)))))

;; A child can publish and exit inside the `agent list` subprocess window — the exact
;; hazard `collect-any!` re-polls to avoid. `fake-herdr`'s `publish-queue` reproduces it:
;; the RESULT appears as a side effect of the very `agent list` call `prune!` uses for its
;; liveness proof, so the final mutation must re-check the freshest ledger state rather
;; than the pre-listing snapshot, and the race-won RESULT must remain collectible.
(deftest prune-refuses-a-result-published-during-the-liveness-scan
  (let [{:keys [env dir state]} (fake-env {})
        a (start-child! env dir "publishes inside the agent list window")]
    (child-state! state a "gone" "")
    (spit (str (fs/path state "publish-queue")) (str (:child a) "\n"))
    (let [proc (call! env "task" "prune" (:task a))]
      (is (= 1 (:exit proc)))
      (is (re-find #"captured or published" (:out proc)))
      (is (nil? (:captured-at (ledger-entry* dir (:task a)))))
      (is (= "prompted" (:status (ledger-entry* dir (:task a)))))
      (let [collected (call! env "task" "collect" (:task a))]
        (is (zero? (:exit collected)) (:err collected))
        (is (= "COMPLETE" (get-in (result collected) [:result :status])))))))

;; A bare `not=` would let an unresolvable caller (nil) own an entry whose own
;; `:parent-session` is also nil — a hand-edited or legacy-format ledger file — since
;; nil equals nil. The child is separately marked `gone` so only the ownership guard
;; (not the liveness guard) is exercised by this scenario.
(deftest prune-refuses-when-recorded-and-caller-identity-are-both-unresolvable
  (let [{:keys [env dir state]} (fake-env {})
        a (start-child! env dir "nil recorded parent-session")]
    (child-state! state a "gone" "")
    (let [path (fs/path dir ".agents" "tmp" "herdr-orch" "ledger" (str (:task a) ".json"))
          corrupted (dissoc a :parent-session)]
      (spit (str path) (json/generate-string corrupted))
      (let [proc (call! (merge env {"FAKE_FAIL_AGENT_GET" "w:p"}) "task" "prune" (:task a))]
        (is (= 1 (:exit proc)))
        (is (re-find #"own" (:out proc)))
        (is (= corrupted (ledger-entry* dir (:task a))))))))

;; The *second* child publishes first, so first-of-N cannot be an artefact of spawn order.
;; One capture closes exactly one pane and leaves the sibling's ledger entry untouched.
(deftest collect-any-captures-the-first-published-child
  (let [{:keys [env log dir]} (fake-env {})
        a (start-child! env dir "fan-in child a")
        b (start-child! env dir "fan-in child b")]
    (is (= "w:child" (:pane-id a)))
    (is (= "w:child-2" (:pane-id b)))
    (publish-child! b)
    (let [proc (call! env "task" "collect" "--any")
          res (:result (result proc))]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "COMPLETE" (:status res)))
      (is (= (:task b) (:task res)))
      ;; one candidate (a) is still in flight after this capture
      (is (= 1 (:remaining res)))
      ;; only the captured child's pane is closed
      (is (= #{(:pane-id b)} (closed-panes log)))
      ;; the sibling's ledger entry is untouched: still uncaptured and still `prompted`
      (let [sibling (ledger-entry* dir (:task a))]
        (is (nil? (:captured-at sibling)))
        (is (= "prompted" (:status sibling)))
        (is (= (dissoc a :child-session) (dissoc sibling :child-session))))
      ;; the captured entry itself carries the normal capture bookkeeping
      (is (= "COMPLETE" (:status (ledger-entry* dir (:task b)))))
      (is (some? (:captured-at (ledger-entry* dir (:task b))))))))

;; The fan-in result must be the single-task `collect` shape plus exactly one field.
(deftest collect-any-result-is-the-single-task-shape-plus-remaining
  (let [{single-env :env single-dir :dir} (fake-env {})
        {any-env :env any-dir :dir} (fake-env {})
        one (start-child! single-env single-dir "shape via single-task collect")
        other (start-child! any-env any-dir "shape via fan-in collect")]
    (publish-child! one)
    (publish-child! other)
    (let [single (:result (result (call! single-env "task" "collect" (:task one))))
          any (:result (result (call! any-env "task" "collect" "--any")))]
      (is (= "COMPLETE" (:status single)))
      (is (= "COMPLETE" (:status any)))
      (is (= (conj (set (keys single)) :remaining) (set (keys any))))
      (is (= 0 (:remaining any))))))

;; Candidacy is same `:parent-session` + no `:captured-at` + `:status` not `failed`. The
;; `failed` exclusion is load-bearing: `safe-cleanup!` marks a dead spawn `failed` without
;; a `:captured-at`, so without it `no-candidates` would be unreachable forever.
(deftest collect-any-candidacy-excludes-failed-captured-and-foreign-entries
  (let [{:keys [env dir]} (fake-env {})
        live (start-child! env dir "live fan-in child")
        dead (call! (merge env {"FAKE_FAIL_START" "1"}) "task" "start" "worker" "--task" "dead spawn")]
    (is (= 1 (:exit dead)))
    (publish-child! live)
    (testing "a foreign parent session sees no candidates, even with one publishable"
      ;; FAKE_SESSION_FROM=get gives `agent get w:other` a real, different session value.
      ;; Asserted before the owning capture, so the entry is genuinely still capturable.
      (let [proc (call! (merge env {"HERDR_PANE_ID" "w:other" "FAKE_SESSION_FROM" "get"}) "task" "collect" "--any")]
        (is (zero? (:exit proc)) (:err proc))
        (is (= "pending" (get-in (result proc) [:result :status])))
        (is (= "no-candidates" (get-in (result proc) [:result :reason])))
        ;; Never captured across the ownership boundary.
        (is (nil? (:captured-at (ledger-entry* dir (:task live)))))))
    (testing "a terminal spawn-failure entry is never a candidate"
      (let [res (:result (result (call! env "task" "collect" "--any")))]
        (is (= "COMPLETE" (:status res)))
        (is (= (:task live) (:task res)))
        ;; the failed entry is excluded from the candidate count, not merely from capture
        (is (= 0 (:remaining res)))))
    (testing "with every candidate captured, `no-candidates` is reachable"
      (let [res (:result (result (call! env "task" "collect" "--any")))]
        (is (= "pending" (:status res)))
        (is (= "no-candidates" (:reason res)))))))

;; A child can publish and exit inside the `agent list` subprocess window, so a terminal
;; short-circuit must never discard an envelope already on disk. The fixture reproduces the
;; race exactly: `agent list` prints its array *then* runs the publish queue, so a child
;; that is both invisible to the listing and queued publishes during the very call that
;; would otherwise classify it away.
(deftest collect-any-never-discards-a-result-published-inside-the-liveness-window
  (testing "a vanished-and-publishing child is captured, not reported no-live-children"
    (let [{:keys [env dir state]} (fake-env {"SUBAGENT_POLL_INTERVAL_MS" "50"})
          a (start-child! env dir "races the liveness scan")]
      (child-state! state a "gone" "")
      (spit (str (fs/path state "publish-queue")) (str (:child a) "\n"))
      (let [res (:result (result (call! env "task" "collect" "--any" "--wait" "--timeout" "20000")))]
        (is (= "COMPLETE" (:status res)))
        (is (= (:task a) (:task res)))
        (is (= 0 (:remaining res))))))
  (testing "a blocked sibling does not short-circuit past a racing publication either"
    (let [{:keys [env log dir state]} (fake-env {"SUBAGENT_POLL_INTERVAL_MS" "50"})
          a (start-child! env dir "blocked sibling")
          b (start-child! env dir "races the liveness scan")]
      (child-state! state a "status" "blocked")
      ;; `nameless` keeps `agent get` working, so closure of the captured pane is provable.
      (child-state! state b "nameless" "")
      (spit (str (fs/path state "publish-queue")) (str (:child b) "\n"))
      (let [res (:result (result (call! env "task" "collect" "--any" "--wait" "--timeout" "20000")))]
        (is (= "COMPLETE" (:status res)))
        (is (= (:task b) (:task res)))
        (is (= 1 (:remaining res)))
        (is (= #{(:pane-id b)} (closed-panes log)))))))

;; Liveness by `name` only: a real `agent list` contains nameless entries for manually
;; started agents, so an unidentifiable entry must count as vanished rather than keeping an
;; exited child "live" forever and making `no-live-children` unreachable.
(deftest collect-any-treats-a-nameless-listing-entry-as-vanished
  (let [{:keys [env dir state]} (fake-env {"SUBAGENT_POLL_INTERVAL_MS" "50"})
        a (start-child! env dir "nameless listing entry")]
    (child-state! state a "nameless" "")
    (let [began (System/currentTimeMillis)
          res (:result (result (call! env "task" "collect" "--any" "--wait" "--timeout" "20000")))]
      (is (= "pending" (:status res)))
      (is (= "no-live-children" (:reason res)))
      (is (< (- (System/currentTimeMillis) began) 8000)))))

;; "Liveness unknown" must degrade to polling, not to a spurious `no-live-children`: an
;; exit-0 listing with no `agents` key is indistinguishable from schema drift, and an
;; `(into {} … nil)` would hand the short-circuit a truthy empty index.
(deftest collect-any-degrades-to-polling-when-the-listing-payload-is-unusable
  (let [{:keys [env dir]} (fake-env {"SUBAGENT_POLL_INTERVAL_MS" "50" "FAKE_AGENT_LIST_NO_AGENTS_KEY" "1"})]
    (start-child! env dir "unusable listing")
    (let [res (:result (result (call! env "task" "collect" "--any" "--wait" "--timeout" "300")))]
      (is (= "pending" (:status res)))
      (is (= "timeout" (:reason res))))))

;; An unresolvable caller identity cannot be scoped, so it is reported distinctly rather
;; than as an empty fan-out. Non-throwing, matching `collect`, which never runs preflight.
(deftest collect-any-reports-an-unresolvable-caller-distinctly
  (let [{:keys [env dir]} (fake-env {})]
    (start-child! env dir "orphaned by a failing parent probe")
    (let [proc (call! (merge env {"FAKE_FAIL_AGENT_GET" "w:p"}) "task" "collect" "--any")]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "pending" (get-in (result proc) [:result :status])))
      (is (= "unknown-caller" (get-in (result proc) [:result :reason]))))))

(deftest collect-any-with-no-entries-reports-no-candidates
  (let [{:keys [env log]} (fake-env {})
        proc (call! env "task" "collect" "--any")]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "pending" (get-in (result proc) [:result :status])))
    (is (= "no-candidates" (get-in (result proc) [:result :reason])))
    ;; No candidate set means no liveness scan at all.
    (is (zero? (agent-list-count log)))))

;; Without `--wait` the budget is zero, so an in-flight candidate that has not published
;; is one poll and out — no sleep, and the same `timeout` reason a spent budget reports.
(deftest collect-any-without-wait-polls-once
  (let [{:keys [env log dir]} (fake-env {})]
    (start-child! env dir "in flight, never publishes")
    (let [before (agent-list-count log)
          began (System/currentTimeMillis)
          proc (call! env "task" "collect" "--any")
          res (:result (result proc))]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "pending" (:status res)))
      (is (= "timeout" (:reason res)))
      (is (= 1 (- (agent-list-count log) before)))
      (is (< (- (System/currentTimeMillis) began) 3000))
      (is (empty? (closed-panes log))))))

(deftest collect-any-rejects-a-task-positional
  (let [{:keys [env]} (fake-env {})
        proc (call! env "task" "collect" "--any" "some-task")]
    (is (= 1 (:exit proc)))
    (is (re-find #"takes no task argument" (:out proc)))))

;; Elapsed budget with nothing published, plus the poll-interval discipline: one
;; `agent list` per tick, and never a sleep that overshoots the total timeout.
(deftest collect-any-timeout-honours-the-poll-interval
  (testing "a spent budget reports pending/timeout after a bounded number of polls"
    (let [{:keys [env log dir]} (fake-env {"SUBAGENT_POLL_INTERVAL_MS" "50"})]
      (start-child! env dir "never publishes a")
      (start-child! env dir "never publishes b")
      (let [before (agent-list-count log)
            proc (call! env "task" "collect" "--any" "--wait" "--timeout" "400")
            polls (- (agent-list-count log) before)]
        (is (zero? (:exit proc)) (:err proc))
        (is (= "pending" (get-in (result proc) [:result :status])))
        (is (= "timeout" (get-in (result proc) [:result :reason])))
        ;; One listing per tick: above 1 (it really looped) and under the 400/50 + 1
        ;; sleep-derived ceiling with slack for fork cost.
        (is (<= 2 polls 14) (str "polls=" polls)))))
  (testing "a poll interval longer than the remaining budget never overshoots it"
    ;; A full 5 s sleep would dominate the wall time; `min(interval, remaining)` cannot.
    (let [{:keys [env dir]} (fake-env {"SUBAGENT_POLL_INTERVAL_MS" "5000"})]
      (start-child! env dir "long interval child")
      (let [began (System/currentTimeMillis)
            proc (call! env "task" "collect" "--any" "--wait" "--timeout" "200")
            elapsed (- (System/currentTimeMillis) began)]
        (is (zero? (:exit proc)) (:err proc))
        (is (= "timeout" (get-in (result proc) [:result :reason])))
        (is (< elapsed 4000) (str "elapsed=" elapsed))))))

;; All candidates blocked short-circuits instead of consuming the budget, and never
;; closes a pane (a blocked child is resumable in its retained pane).
(deftest collect-any-all-blocked-candidates-short-circuit
  (let [{:keys [env log dir state]} (fake-env {"SUBAGENT_POLL_INTERVAL_MS" "50"})
        a (start-child! env dir "blocked a")
        b (start-child! env dir "blocked b")]
    (doseq [entry [a b]] (child-state! state entry "status" "blocked"))
    (let [began (System/currentTimeMillis)
          proc (call! env "task" "collect" "--any" "--wait" "--timeout" "20000")
          elapsed (- (System/currentTimeMillis) began)
          res (:result (result proc))]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "blocked" (:status res)))
      (is (= #{(:task a) (:task b)} (set (:tasks res))))
      (is (< elapsed 8000) (str "elapsed=" elapsed))
      (is (empty? (closed-panes log)))
      ;; Neither entry is consumed: both stay capturable once unblocked.
      (doseq [entry [a b]] (is (nil? (:captured-at (ledger-entry* dir (:task entry)))))))))

;; A single unblocked sibling is enough to keep polling: the short-circuit is
;; "every live candidate", never "any".
(deftest collect-any-one-unblocked-sibling-defeats-the-blocked-short-circuit
  (let [{:keys [env dir state]} (fake-env {"SUBAGENT_POLL_INTERVAL_MS" "50"})
        a (start-child! env dir "blocked sibling")
        b (start-child! env dir "working sibling")]
    (child-state! state a "status" "blocked")
    (child-state! state b "status" "working")
    ;; The fixture publishes b's result on the second poll tick, mid-wait.
    (spit (str (fs/path state "publish-queue")) (str "\n" (:child b) "\n"))
    (let [proc (call! env "task" "collect" "--any" "--wait" "--timeout" "5000")
          res (:result (result proc))]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "COMPLETE" (:status res)))
      (is (= (:task b) (:task res)))
      (is (= 1 (:remaining res))))))

;; Every candidate's agent has vanished: `agent get`/`agent list` no longer know it.
(deftest collect-any-all-vanished-children-report-no-live-children
  (let [{:keys [env log dir state]} (fake-env {"SUBAGENT_POLL_INTERVAL_MS" "50"})
        a (start-child! env dir "vanished a")
        b (start-child! env dir "vanished b")]
    (doseq [entry [a b]] (child-state! state entry "gone" ""))
    (let [began (System/currentTimeMillis)
          proc (call! env "task" "collect" "--any" "--wait" "--timeout" "20000")
          elapsed (- (System/currentTimeMillis) began)
          res (:result (result proc))]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "pending" (:status res)))
      (is (= "no-live-children" (:reason res)))
      (is (< elapsed 8000) (str "elapsed=" elapsed))
      (is (empty? (closed-panes log))))))

;; --- `collect --any` settle-close --------------------------------------------------
;; `maybe-close!` probes `agent_status` once with no retry, so a child still `working` at
;; the instant of capture used to keep its COMPLETE pane forever. One bounded `agent wait`
;; on the captured child (only) precedes that unmodified close attempt. The fixture's
;; per-child `settle-to` marker is what avoids `agent wait`'s original publish side effect
;; on an already-published child.
(defn- child-waits [log child]
  (filterv #(and (= ["agent" "wait" child] (vec (take 3 %))) (not (some #{"--help"} %))) (calls log)))
;; A local reader: the shared `flag-value` helper is defined further down this file.
(defn- argv-flag [argv flag] (second (drop-while #(not= flag %) argv)))

(deftest collect-any-settles-a-working-child-before-closing-its-pane
  (let [{:keys [env log dir state]} (fake-env {})
        a (start-child! env dir "settle-close sibling")
        b (start-child! env dir "settle-close capture")]
    ;; b published but is still mid-turn — exactly the reproduced race.
    (child-state! state b "status" "working")
    (child-state! state b "settle-to" "idle")
    (publish-child! b)
    (let [proc (call! env "task" "collect" "--any")
          res (:result (result proc))
          waits (child-waits log (:child b))]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "COMPLETE" (:status res)))
      (is (= (:task b) (:task res)))
      (is (= 1 (:remaining res)))
      ;; the settled child's pane is closed; the sibling is untouched
      (is (= #{(:pane-id b)} (closed-panes log)))
      ;; exactly one settle wait, on the captured child only, at the default budget and
      ;; deliberately without `--until`: the bare form settles on `blocked` too.
      (is (= 1 (count waits)))
      (is (empty? (child-waits log (:child a))))
      (is (= (str cli/default-settle-close-ms) (argv-flag (first waits) "--timeout")))
      (is (not-any? #{"--until"} (first waits))))))

(deftest settle-close-budget-defaults-outlast-the-notify-wait-and-honour-the-override
  (is (= 45000 cli/default-settle-close-ms))
  ;; Load-bearing ordering: the 30 s notify wait is what keeps a publishing child `working`.
  (is (> cli/default-settle-close-ms cli/default-notify-timeout-ms))
  (is (= 1234 (cli/parse-settle-close "1234")))
  (doseq [raw [nil "" "   " "soon" "0" "-5"]]
    (is (= cli/default-settle-close-ms (cli/parse-settle-close raw)) (pr-str raw)))
  (let [{:keys [env log dir state]} (fake-env {"ORCH_SETTLE_CLOSE_MS" "1500"})
        b (start-child! env dir "settle budget override")]
    (child-state! state b "status" "working")
    (child-state! state b "settle-to" "idle")
    (publish-child! b)
    (let [proc (call! env "task" "collect" "--any")]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "1500" (argv-flag (first (child-waits log (:child b))) "--timeout"))))))

;; Budget expiry and a failed `agent wait` alike still make the one close attempt, which
;; simply finds the child unsettled: the pane is retained and the envelope's own status is
;; reported unchanged.
(deftest collect-any-settle-close-give-up-retains-the-pane-without-touching-the-result
  (doseq [outcome ["timeout" "error"]]
    (let [{:keys [env log dir state]} (fake-env {})
          b (start-child! env dir "settle-close give-up")]
      (child-state! state b "status" "working")
      (child-state! state b "settle-to" outcome)
      (publish-child! b)
      (let [proc (call! env "task" "collect" "--any")
            res (:result (result proc))]
        (is (zero? (:exit proc)) (str outcome " " (:err proc)))
        (is (= "COMPLETE" (:status res)) outcome)
        (is (= 0 (:remaining res)) outcome)
        (is (= 1 (count (child-waits log (:child b)))) outcome)
        (is (empty? (closed-panes log)) outcome)
        ;; capture bookkeeping is the normal one: the close outcome never demotes it
        (is (= "COMPLETE" (:status (ledger-entry* dir (:task b)))) outcome)))))

;; The whole result, `remaining` included, is a function of capture and candidacy — never
;; of the close outcome. Two single-child fan-outs differing *only* in settlement.
(deftest collect-any-result-is-identical-whether-the-close-succeeds-or-gives-up
  (let [collect-one! (fn [settle-to]
                       (let [{:keys [env log dir state]} (fake-env {})
                             b (start-child! env dir "identical shape either way")]
                         (child-state! state b "status" "working")
                         (child-state! state b "settle-to" settle-to)
                         (publish-child! b)
                         {:res (:result (result (call! env "task" "collect" "--any")))
                          :closed (closed-panes log) :pane (:pane-id b)}))
        closed (collect-one! "idle")
        gave-up (collect-one! "timeout")
        ;; identity fields (and the raw `:text` carrying them) are per-fixture; everything
        ;; else must match exactly
        strip #(dissoc % :child :task :result :text)]
    (is (= #{(:pane closed)} (:closed closed)))
    (is (empty? (:closed gave-up)))
    (is (= (set (keys (:res closed))) (set (keys (:res gave-up)))))
    (is (= (strip (:res closed)) (strip (:res gave-up))))
    (is (= 0 (:remaining (:res gave-up))))))

;; `herdr/wait!` is used deliberately instead of `wait-settled!`: without `--until` the
;; wait returns on *any* settled state, so a blocked child ends it immediately rather than
;; burning the budget — and its pane is still retained, because closure needs idle/done.
;; The fixture honours `--until` exactly as herdr does, so a `wait-settled!` regression
;; makes this child time out and the elapsed bound (not only the argv assertion above)
;; fails.
(deftest collect-any-settle-wait-ends-early-on-a-blocked-child
  (let [{:keys [env log dir state]} (fake-env {})
        b (start-child! env dir "settles to blocked")]
    (child-state! state b "status" "working")
    (child-state! state b "settle-to" "blocked")
    (publish-child! b)
    (let [began (System/currentTimeMillis)
          proc (call! env "task" "collect" "--any")
          elapsed (- (System/currentTimeMillis) began)
          res (:result (result proc))]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "COMPLETE" (:status res)))
      (is (= 1 (count (child-waits log (:child b)))))
      (is (empty? (closed-panes log)))
      ;; The fixture writes the settled status through only when the wait actually matched,
      ;; and it honours `--until` as herdr does — so this is what pins "blocked ends the
      ;; wait": a `wait-settled!` regression times out and leaves `working` behind.
      (is (= "blocked" (str/trim (slurp (str (fs/path state "children" (:child b) "status"))))))
      ;; nowhere near the 45 s budget: the bare wait matched `blocked`
      (is (< elapsed 20000) (str "elapsed=" elapsed)))))

;; A captured BLOCKED envelope never closes a pane, so there is nothing for a settle wait
;; to buy: it is skipped entirely rather than delaying the fan-in by the whole budget.
(deftest collect-any-blocked-envelope-skips-the-settle-wait
  (let [{:keys [env log dir state]} (fake-env {})
        b (start-child! env dir "blocked envelope")]
    (child-state! state b "status" "working")
    ;; Deliberately present: the call-log assertion below proves the wait was never issued,
    ;; and this marker would have made it succeed had it been.
    (child-state! state b "settle-to" "idle")
    (publish-child! b "BLOCKED")
    (let [proc (call! env "task" "collect" "--any")
          res (:result (result proc))]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "BLOCKED" (:status res)))
      (is (empty? (child-waits log (:child b))))
      (is (empty? (closed-panes log))))))

;; --- advisory parent push at publish time -----------------------------------------
;; `start-child!` spawns from the parent pane `w:p`; the child then publishes with its own
;; injected identity, which is exactly the runtime shape of the push path.
(defn- child-publish-env [env entry policy]
  (merge env {"HERDR_ORCH_CHILD" (:child entry) "HERDR_ORCH_TASK" (:task entry)
              "HERDR_ORCH_RESULT" (:result entry) "HERDR_ORCH_WAITING_POLICY" policy
              "HERDR_PANE_ID" (:pane-id entry)}))
;; `--help` must be excluded explicitly (as in `closed-panes`): preflight probes
;; `agent prompt/get/wait --help` on every spawn and shares this call log.
(defn- parent-calls [log command]
  (filterv #(and (= (conj command "w:p") (vec (take 3 %))) (not (some #{"--help"} %))) (calls log)))
(defn- parent-prompts [log] (parent-calls log ["agent" "prompt"]))
(defn- parent-waits [log] (parent-calls log ["agent" "wait"]))
(defn- parent-gets [log] (parent-calls log ["agent" "get"]))
(defn- flag-value [argv flag] (second (drop-while #(not= flag %) argv)))

(deftest ledger-records-the-parent-pane-at-allocation
  (let [{:keys [env dir]} (fake-env {})
        entry (start-child! env dir "parent pane recorded")]
    (is (= "w:p" (:parent-pane entry)))
    (is (= "session" (:parent-session entry)))))

;; One `agent get` probe, then one advisory `agent prompt` naming the child, the task, and
;; the `collect` command. Never `--wait`: that path can return `agent_prompt_stalled`.
(deftest non-blocking-publish-pushes-one-advisory-prompt-to-a-settled-parent
  (doseq [status ["idle" "done"]]
    (let [{:keys [env log dir]} (fake-env {})
          entry (start-child! env dir (str "push to " status " parent"))
          proc (call! (merge (child-publish-env env entry "non-blocking") {"FAKE_PARENT_STATUS" status})
                      "task" "publish" "--status" "COMPLETE" "--summary" "done")
          res (:result (result proc))
          pushes (parent-prompts log)]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "COMPLETE" (:status res)) status)
      (is (= "sent" (get-in res [:parent-push :push])) status)
      (is (= 1 (count pushes)) status)
      (let [text (nth (first pushes) 3)]
        (is (str/includes? text (:child entry)))
        (is (str/includes? text (:task entry)))
        (is (str/includes? text (str "collect " (:task entry)))))
      (is (not-any? #(some #{"--wait"} %) pushes) status)
      ;; Exactly one probe at publish time, on top of the single spawn-side identity read.
      (is (= 2 (count (parent-gets log))) status)
      (is (empty? (parent-waits log)) status)
      ;; The operator toast is retained alongside the push.
      (is (some #(and (= ["notification" "show"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)) status))))

;; Under `blocking` the parent is already in its own wait loop: no probe, no push at all.
(deftest blocking-publish-never-probes-or-pushes-to-the-parent
  (let [{:keys [env log dir]} (fake-env {})
        entry (start-child! env dir "blocking policy")
        proc (call! (child-publish-env env entry "blocking") "task" "publish" "--status" "COMPLETE" "--summary" "done")
        res (:result (result proc))]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "COMPLETE" (:status res)))
    (is (nil? (:parent-push res)))
    (is (nil? (:notification res)))
    (is (empty? (parent-prompts log)))
    (is (empty? (parent-waits log)))
    ;; Only the spawn-side identity read: publish added no probe.
    (is (= 1 (count (parent-gets log))))))

;; Prompt text submitted to a blocked agent lands in its approval UI, so a blocked parent
;; is never pushed to and never waited for.
(deftest a-blocked-parent-receives-no-push
  (let [{:keys [env log dir]} (fake-env {})
        entry (start-child! env dir "blocked parent")
        proc (call! (merge (child-publish-env env entry "non-blocking") {"FAKE_PARENT_STATUS" "blocked"})
                    "task" "publish" "--status" "COMPLETE" "--summary" "done")
        res (:result (result proc))]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "COMPLETE" (:status res)))
    (is (fs/exists? (:result entry)))
    (is (= "skipped" (get-in res [:parent-push :push])))
    (is (= "parent-blocked" (get-in res [:parent-push :reason])))
    (is (empty? (parent-prompts log)))
    (is (empty? (parent-waits log)))))

;; Panes outlive agent sessions: a replacement agent in the reused parent pane must never
;; be prompted, the same ownership boundary `maybe-close!` enforces for pane closure.
(deftest a-foreign-parent-session-receives-no-push
  (let [{:keys [env log dir]} (fake-env {})
        entry (start-child! env dir "replaced parent")
        proc (call! (merge (child-publish-env env entry "non-blocking") {"FAKE_PARENT_SESSION" "replacement"})
                    "task" "publish" "--status" "COMPLETE" "--summary" "done")
        res (:result (result proc))]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "COMPLETE" (:status res)))
    (is (= "skipped" (get-in res [:parent-push :push])))
    (is (= "session-mismatch" (get-in res [:parent-push :reason])))
    (is (empty? (parent-prompts log)))
    ;; A foreign session is not waited for either: there is nothing to settle.
    (is (empty? (parent-waits log)))))

;; `working` and `unknown` both get one bounded settle wait, then the single push.
(deftest an-unsettled-parent-is-waited-for-then-pushed-once
  (doseq [[status timeout argv] [["working" "30000" []] ["unknown" "1234" ["--notify-timeout" "1234"]]]]
    (let [{:keys [env log dir]} (fake-env {})
          entry (start-child! env dir (str status " parent"))
          proc (apply call! (merge (child-publish-env env entry "non-blocking") {"FAKE_PARENT_STATUS" status})
                      "task" "publish" "--status" "COMPLETE" "--summary" "done" argv)
          res (:result (result proc))
          waits (parent-waits log)]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "COMPLETE" (:status res)) status)
      (is (= "sent" (get-in res [:parent-push :push])) status)
      ;; `:waited` claims only that a settle wait preceded the outcome, never settlement.
      (is (true? (get-in res [:parent-push :waited])) status)
      (is (= 1 (count waits)) status)
      ;; Narrower than herdr's default match set, which also includes `blocked`.
      (is (= ["--until" "idle" "--until" "done"] (vec (take-last 4 (first waits)))) status)
      (is (= timeout (flag-value (first waits) "--timeout")) status)
      (is (= 1 (count (parent-prompts log))) status))))

;; The wait is bounded: an exhausted budget names the outcome and pushes nothing.
(deftest a-parent-that-never-settles-reports-timed-out-without-pushing
  (let [{:keys [env log dir]} (fake-env {})
        entry (start-child! env dir "never settles")
        proc (call! (merge (child-publish-env env entry "non-blocking") {"FAKE_PARENT_STATUS" "working" "FAKE_PARENT_WAIT" "timeout"})
                    "task" "publish" "--status" "COMPLETE" "--summary" "done" "--notify-timeout" "50")
        res (:result (result proc))]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "COMPLETE" (:status res)))
    (is (fs/exists? (:result entry)))
    (is (= "timed-out" (get-in res [:parent-push :push])))
    (is (= 50 (get-in res [:parent-push :timeout-ms])))
    (is (= "working" (get-in res [:parent-push :parent-status])))
    (is (= 1 (count (parent-waits log))))
    (is (empty? (parent-prompts log)))))

;; The settle wait can be satisfied by a *replacement* agent settling in the reused pane,
;; so both gates are re-checked on the AgentInfo observed after the wait, not before it.
(deftest both-gates-are-rechecked-on-the-agent-observed-after-the-wait
  (let [{:keys [env log dir]} (fake-env {})
        entry (start-child! env dir "replaced mid-wait")
        proc (call! (merge (child-publish-env env entry "non-blocking")
                           {"FAKE_PARENT_STATUS" "working" "FAKE_PARENT_WAIT_SESSION" "replacement"})
                    "task" "publish" "--status" "COMPLETE" "--summary" "done" "--notify-timeout" "500")
        res (:result (result proc))]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "COMPLETE" (:status res)))
    (is (= "skipped" (get-in res [:parent-push :push])))
    (is (= "session-mismatch" (get-in res [:parent-push :reason])))
    (is (true? (get-in res [:parent-push :waited])))
    (is (= 1 (count (parent-waits log))))
    (is (empty? (parent-prompts log)))))

;; Backward compatibility: an entry allocated before `:parent-pane` existed names no parent
;; to probe, so the push is skipped without a single herdr call.
(deftest an-entry-without-a-parent-pane-is-skipped-without-probing
  (let [{:keys [env log dir]} (fake-env {})
        entry (start-child! env dir "legacy entry")
        path (str (fs/path dir ".agents" "tmp" "herdr-orch" "ledger" (str (:task entry) ".json")))
        probes (count (parent-gets log))]
    (spit path (json/generate-string (dissoc entry :parent-pane)))
    (let [proc (call! (child-publish-env env entry "non-blocking") "task" "publish" "--status" "COMPLETE" "--summary" "done")
          res (:result (result proc))]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "COMPLETE" (:status res)))
      (is (fs/exists? (:result entry)))
      (is (= "skipped" (get-in res [:parent-push :push])))
      (is (= "no-parent-pane" (get-in res [:parent-push :reason])))
      (is (= probes (count (parent-gets log))))
      (is (empty? (parent-prompts log))))))

;; Publication is committed before the push, so any herdr failure on the push path is
;; reported and nothing more: status and exit code are untouched.
(deftest a-herdr-failure-on-the-push-path-never-affects-publication
  (testing "the probe itself fails"
    (let [{:keys [env log dir]} (fake-env {})
          entry (start-child! env dir "probe fails")
          proc (call! (merge (child-publish-env env entry "non-blocking") {"FAKE_FAIL_AGENT_GET" "w:p"})
                      "task" "publish" "--status" "COMPLETE" "--summary" "done")
          res (:result (result proc))]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "COMPLETE" (:status res)))
      (is (fs/exists? (:result entry)))
      (is (= "error" (get-in res [:parent-push :push])))
      (is (= "probe-failed" (get-in res [:parent-push :reason])))
      (is (empty? (parent-prompts log)))))
  ;; A failed submission may already have delivered text partially, so it is named
  ;; distinctly from a parent that was never contacted.
  (testing "the submission itself fails"
    (let [{:keys [env dir]} (fake-env {})
          entry (start-child! env dir "prompt fails")
          proc (call! (merge (child-publish-env env entry "non-blocking") {"FAKE_FAIL_PROMPT" "1"})
                      "task" "publish" "--status" "COMPLETE" "--summary" "done")
          res (:result (result proc))]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "COMPLETE" (:status res)))
      (is (fs/exists? (:result entry)))
      (is (= "error" (get-in res [:parent-push :push])))
      (is (= "prompt-failed" (get-in res [:parent-push :reason])))))
  (testing "the settle wait fails with a non-timeout code"
    (let [{:keys [env log dir]} (fake-env {})
          entry (start-child! env dir "wait fails")
          proc (call! (merge (child-publish-env env entry "non-blocking") {"FAKE_PARENT_STATUS" "working" "FAKE_PARENT_WAIT" "agent_not_found"})
                      "task" "publish" "--status" "COMPLETE" "--summary" "done" "--notify-timeout" "500")
          res (:result (result proc))]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "COMPLETE" (:status res)))
      (is (= "error" (get-in res [:parent-push :push])))
      (is (= "agent_not_found" (get-in res [:parent-push :reason])))
      (is (empty? (parent-prompts log)))))
  (testing "a publication with no ledger entry has no parent to probe"
    (let [{:keys [env log dir]} (fake-env {})
          target (str (fs/path dir "orphan.result"))
          proc (call! (merge env {"HERDR_ORCH_CHILD" "child" "HERDR_ORCH_TASK" "task"
                                  "HERDR_ORCH_RESULT" target "HERDR_ORCH_WAITING_POLICY" "non-blocking"})
                      "task" "publish" "--status" "COMPLETE" "--summary" "done")
          res (:result (result proc))]
      (is (zero? (:exit proc)) (:err proc))
      (is (fs/exists? target))
      (is (= "skipped" (get-in res [:parent-push :push])))
      (is (= "unknown-ledger-entry" (get-in res [:parent-push :reason])))
      (is (empty? (parent-prompts log))))))

(deftest ^:serial mechanical-cli-groups-dispatch-to-herdr
  (let [calls (atom [])
        record! (fn [op & args] (swap! calls conj (into [op] args)))]
    (with-redefs [herdr/caller-rect! (fn [] {:width 160 :height 80})
                  herdr/split! (fn [opts] (record! :pane/split opts) {:pane_id "split"})
                  herdr/pane-run! (fn [pane command] (record! :pane/run pane command))
                  herdr/pane-read! (fn [pane opts] (record! :pane/read pane opts) "output")
                  herdr/pane-wait-output! (fn [pane opts] (record! :pane/wait-output pane opts) {:output "matched"})
                  herdr/pane-send-text! (fn [pane text] (record! :pane/send-text pane text))
                  herdr/pane-send-keys! (fn [pane keys] (record! :pane/send-keys pane keys))
                  herdr/close! (fn [pane] (record! :pane/close pane))
                  herdr/pane-list! (fn [workspace] (record! :pane/list workspace) [])
                  herdr/current-pane! (fn [] (record! :pane/current) {:pane_id "current"})
                  herdr/pane! (fn [pane] (record! :pane/get pane) {:pane_id pane})
                  herdr/pane-layout! (fn [pane] (record! :pane/layout pane) {:panes []})
                  herdr/rename! (fn [pane label] (record! :pane/rename pane label) {:pane_id pane})
                  herdr/tab-create! (fn [opts] (record! :tab/create opts) {:pane_id "tab-pane" :tab-id "tab"})
                  herdr/tab-list! (fn [workspace] (record! :tab/list workspace) [])
                  herdr/tab-focus! (fn [tab] (record! :tab/focus tab) {:tab_id tab})
                  herdr/workspace-create! (fn [opts] (record! :ws/create opts) {:workspace_id "ws"})
                  herdr/workspace-list! (fn [] (record! :ws/list) [])
                  herdr/workspace-focus! (fn [workspace] (record! :ws/focus workspace) {:workspace_id workspace})
                  herdr/start! (fn [name kind pane native] (record! :agent/start name kind pane native) {:name name})
                  herdr/prompt! (fn [target text] (record! :agent/prompt target text))
                  herdr/agent-wait! (fn [target timeout until] (record! :agent/wait target timeout until) {:ok true})
                  herdr/agent-read! (fn [target opts] (record! :agent/read target opts) "output")
                  herdr/agent-send-keys! (fn [target keys] (record! :agent/send-keys target keys))
                  herdr/agent-focus! (fn [target] (record! :agent/focus target) {:name target})
                  herdr/agent-rename! (fn [target name clear?] (record! :agent/rename target name clear?) {:name name})
                  herdr/agents (fn [] (record! :agent/list) [])
                  herdr/agent! (fn [target] (record! :agent/get target) {:name target})]
      (doseq [argv [["pane" "split" "--direction" "right" "--env" "A=B"]
                    ["pane" "run" "p" "echo ok"] ["pane" "read" "p" "--source" "recent"]
                    ["pane" "wait-output" "p" "--match" "ready"] ["pane" "send-text" "p" "text"]
                    ["pane" "send-keys" "p" "enter"] ["pane" "close" "p"] ["pane" "list"]
                    ["pane" "current"] ["pane" "get" "p"] ["pane" "layout" "p"] ["pane" "rename" "p" "label"]
                    ["tab" "create" "--label" "tab"] ["tab" "list"] ["tab" "focus" "tab"]
                    ["ws" "create" "--label" "ws"] ["ws" "list"] ["ws" "focus" "ws"]
                    ["agent" "start" "worker" "--kind" "pi" "--pane" "p" "--" "--model" "light"]
                    ["agent" "prompt" "worker" "hello"] ["agent" "wait" "worker" "--until" "done"]
                    ["agent" "read" "worker"] ["agent" "send-keys" "worker" "enter"]
                    ["agent" "focus" "worker"] ["agent" "rename" "worker" "renamed"]
                    ["agent" "list"] ["agent" "get" "worker"]]]
        (cli/execute argv))
      (is (= #{:pane/split :pane/run :pane/read :pane/wait-output :pane/send-text :pane/send-keys :pane/close :pane/list :pane/current :pane/get :pane/layout :pane/rename
               :tab/create :tab/list :tab/focus :ws/create :ws/list :ws/focus
               :agent/start :agent/prompt :agent/wait :agent/read :agent/send-keys :agent/focus :agent/rename :agent/list :agent/get}
             (set (map first @calls)))))))

(deftest spawn-creates-a-tab-and-rejects-personas
  (let [{:keys [env log]} (fake-env {})
        proc (call! env "spawn" "echo ready")]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "w:child" (get-in (result proc) [:result :pane-id])))
    (is (some #(= ["tab" "create"] (vec (take 2 %))) (calls log)))
    (is (some #(= ["pane" "run" "w:child" "echo ready"] (vec %)) (calls log))))
  (let [{:keys [env log]} (fake-env {})
        proc (call! env "spawn" "worker")]
    (is (= 1 (:exit proc)))
    (is (re-find #"task run" (:out proc)))
    (is (empty? (calls log)))))

(deftest help-is-human-readable-text
  (let [{:keys [env]} (fake-env {})]
    (doseq [argv [["--help"] ["help"] ["task" "run" "--help"] ["task" "publish" "--help"]]]
      (let [proc (apply call! env argv)]
        (is (zero? (:exit proc)) (str argv " -> " (:err proc)))
        (is (str/starts-with? (:out proc) "oh pane"))
        (is (not (str/includes? (:out proc) "\"ok\"")))))))


;; Ties the shipped default table to the record's verified rows, independent of the
;; loader/translation machinery under test elsewhere in this namespace.
(deftest default-config-content-contract
  (let [config (core/parse-config "config.edn" (slurp (str (fs/path root "skills" "herdr-orch" "subagents" "config.edn"))))
        weight-rows {"heavy" {:pi "anthropic/claude-fable-5" :claude "fable" :codex "gpt-5.6-sol"}
                     "middle" {:pi "anthropic/claude-opus-5" :claude "opus" :codex "gpt-5.6-sol"}
                     "light" {:pi "anthropic/claude-sonnet-5" :claude "sonnet" :codex "gpt-5.6-terra"}
                     "feather" {:pi "anthropic/claude-haiku-4-5" :claude "haiku" :codex "gpt-5.6-luna"}}]
    (is (= "--model" (get-in config [:harnesses :pi :model-flag])))
    (is (= "--model" (get-in config [:harnesses :claude :model-flag])))
    (is (= "--model" (get-in config [:harnesses :codex :model-flag])))
    (is (= {:placement :split} (:defaults config)))
    (testing "all twelve weight-alias translations"
      (doseq [[alias row] weight-rows
              [kind native-model] row]
        (is (= native-model (get-in config [:models alias kind]))
            (str alias " " (name kind) " row"))
        (is (= ["--model" native-model] (core/model-args config (name kind) alias))
            (str alias " translates for " (name kind)))))
    ;; Pi receives the configured OpenAI model for `gpt-*`; only the claude/codex
    ;; columns use tier-equivalent cross-provider mappings.
    (is (= {:pi "openai-codex/gpt-5.6-terra" :claude "sonnet" :codex "gpt-5.6-terra"} (get-in config [:models "gpt-5.6-terra"])))
    (is (= {:pi "openai-codex/gpt-5.6-sol" :claude "opus" :codex "gpt-5.6-sol"} (get-in config [:models "gpt-5.6-sol"])))
    (is (= {:pi "openai-codex/gpt-5.6-luna" :claude "claude-haiku-4-5" :codex "gpt-5.6-luna"} (get-in config [:models "gpt-5.6-luna"])))
    (is (= ["--model" "openai-codex/gpt-5.6-sol"] (core/model-args config "pi" "gpt-5.6-sol")))
    (is (= ["--model" "opus"] (core/model-args config "claude" "gpt-5.6-sol")))
    ;; The canonical `claude-haiku*` rows retain the full name despite `feather` using
    ;; the requested undocumented `haiku` alias.
    (is (= "claude-haiku-4-5" (get-in config [:models "claude-haiku-4-5" :claude])))
    ;; Unversioned canonical IDs are floating aliases for the latest version of the tier.
    (doseq [[unversioned latest] [["claude-fable" "claude-fable-5"] ["claude-opus" "claude-opus-5"]
                                  ["claude-sonnet" "claude-sonnet-5"] ["claude-haiku" "claude-haiku-4-5"]
                                  ["gpt-sol" "gpt-5.6-sol"] ["gpt-terra" "gpt-5.6-terra"] ["gpt-luna" "gpt-5.6-luna"]]]
      (is (= (get-in config [:models latest]) (get-in config [:models unversioned]))
          (str unversioned " resolves to the same row as " latest)))
    (is (= ["--model" "gpt-5.6-terra"] (core/model-args config "codex" "claude-sonnet-5")))))

;; Named as a serial test by the shared-runner opt-in contract (task 2fe1ce2a),
;; kept alongside the other with-redefs-based contract tests for the same reason.
(deftest ^:serial packaged-persona-weight-selector-contract
  (let [expected {"planner" "heavy"
                  "advisor" "middle"
                  "reviewer" "middle"
                  "visual-tester" "middle"
                  "researcher" "light"
                  "scout" "light"
                  "worker" "light"}]
    (doseq [[persona weight] expected]
      (is (= weight (:model (core/parse-frontmatter (slurp (str (fs/path root "skills" "herdr-orch" "subagents" (str persona ".md")))))))
          (str persona " declares " weight)))
    ;; `skilled-worker` was retired: `worker --model <tier>` covers it (see
    ;; design/log/2026-07-31-subagents-retire-the-mandatory-advisor-c.org).
    (is (not (fs/exists? (fs/path root "skills" "herdr-orch" "subagents" "skilled-worker.md"))))
    (is (str/includes? (slurp (str (fs/path root "skills" "herdr-orch" "subagents" "worker.md"))) "--model heavy"))))

;; Loader precedence, row-level replacement, missing/malformed/invalid-shape handling,
;; and bare-subtree/relocated-root path derivation, exercised directly against
;; `cli/config` (no subprocess needed) so `user.home` never enters the picture
;; — `home` is an explicit injected argument, and `launcher-bin`/`assignment-root` are
;; stubbed via `with-redefs` rather than relying on `$HOME`, which Babashka's
;; `user.home` property does not observe (see cli_test.clj fake-env docstring).
;; Mutates global with-redefs state (cli/launcher-bin, ledger/assignment-root) --
;; must run serially.
(deftest ^:serial config-loader-precedence-and-deployment-modes
  (let [tmp (str (fs/create-temp-dir {:prefix "config-loader-"}))
        ;; A bare-subtree install: only `scripts/` + a sibling `config.edn`, nested under
        ;; arbitrary ancestor names with no `bb.edn` anywhere — proving derivation is from
        ;; the launcher path alone, never cwd/git.
        launcher (str (fs/path tmp "install" "a" "b" "skills" "herdr-orch" "scripts" "oh"))
        default-config (fs/path tmp "install" "a" "b" "skills" "herdr-orch" "subagents" "config.edn")
        home-dir (str (fs/path tmp "home"))
        project-root (str (fs/path tmp "project"))
        project-config (fs/path project-root ".agents" "subagents" "config.edn")
        home-roster (fs/path home-dir ".agents" "subagents" "config.edn")]
    (fs/create-dirs (fs/parent default-config))
    (fs/create-dirs project-root) (fs/create-dirs home-dir)
    (spit (str default-config) (slurp (str (fs/path root "skills" "herdr-orch" "subagents" "config.edn"))))
    (with-redefs [cli/launcher-bin (constantly launcher) ledger/assignment-root (constantly project-root)]
      (testing "default only"
        (let [config (cli/config home-dir)]
          (is (= "opus" (get-in config [:models "middle" :claude])))
          (is (= "--model" (get-in config [:harnesses :codex :model-flag])))))
      (testing "home override replaces a weight-alias row"
        (fs/create-dirs (fs/parent home-roster))
        (spit (str home-roster) "{:models {\"middle\" {:claude \"middle-home\"}}}")
        (is (= "middle-home" (get-in (cli/config home-dir) [:models "middle" :claude]))))
      (testing "project beats home for the same weight alias; row-level replacement drops untouched columns"
        (fs/create-dirs (fs/parent project-config))
        (spit (str project-config) "{:models {\"middle\" {:claude \"middle-project\"}}}")
        (let [config (cli/config home-dir)]
          (is (= "middle-project" (get-in config [:models "middle" :claude])))
          ;; The overridden row replaces the whole default row: :pi/:codex are gone, not
          ;; deep-merged alongside the new :claude value.
          (is (nil? (get-in config [:models "middle" :pi])))))
      (testing "canonical rows retain home/project replacement precedence"
        (spit (str home-roster) "{:models {\"claude-opus-5\" {:claude \"opus-home\"}}}")
        (is (= "opus-home" (get-in (cli/config home-dir) [:models "claude-opus-5" :claude])))
        (spit (str project-config) "{:models {\"claude-opus-5\" {:claude \"opus-project\"}}}")
        (let [config (cli/config home-dir)]
          (is (= "opus-project" (get-in config [:models "claude-opus-5" :claude])))
          (is (nil? (get-in config [:models "claude-opus-5" :pi])))))
      (testing "missing override files are silently ignored"
        (fs/delete home-roster) (fs/delete project-config)
        (is (= "opus" (get-in (cli/config home-dir) [:models "middle" :claude]))))
      (testing "malformed EDN in an override throws naming its path"
        (spit (str project-config) "{:models")
        (is (try (cli/config home-dir) false
                 (catch clojure.lang.ExceptionInfo e (= (str project-config) (:path (ex-data e))))))
        (fs/delete project-config))
      (testing "a structurally invalid override throws naming its path"
        (spit (str project-config) "{:harnesses {:pi {:model-flag \"\"}}}")
        (is (try (cli/config home-dir) false
                 (catch clojure.lang.ExceptionInfo e (= (str project-config) (:path (ex-data e))))))
        (fs/delete project-config))
      (testing "portability: an override adding a new harness + model column translates for unmodified code"
        (spit (str project-config) "{:harnesses {:gemini {:model-flag \"--model\"}} :models {\"claude-opus-5\" {:gemini \"gemini-2.5-pro\"}}}")
        (let [config (cli/config home-dir)]
          (is (= ["--model" "gemini-2.5-pro"] (core/model-args config "gemini" "claude-opus-5")))
          ;; A kind still absent from `:harnesses` remains empty args — the addition is
          ;; purely additive data, no code change and no other kind affected.
          (is (= [] (core/model-args config "vertex" "claude-opus-5"))))
        (fs/delete project-config)))
    (testing "missing shipped default is fatal"
      (with-redefs [cli/launcher-bin (constantly (str (fs/path tmp "empty-install" "skills" "herdr-orch" "scripts" "oh")))
                    ledger/assignment-root (constantly project-root)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing shipped default config" (cli/config home-dir)))))))

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
        proc (call! env "task" "start" "canonical-worker" "--kind" "claude" "--task" "kind override retains model")]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "opus" (flag-value (start-native-args log) "--model")))))

;; Acceptance: a kindless roster model is now honoured for any resolved kind, not only
;; pi (the retired pi-only kindless guard).
(deftest kindless-model-is-honoured-for-any-resolved-kind
  (let [{:keys [env log]} (fake-env {} roster-model-personas)
        proc (call! env "task" "start" "kindless-worker" "--kind" "claude" "--task" "kindless model non-pi kind")]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "opus" (flag-value (start-native-args log) "--model")))))

;; Preview case: `--print-prompt` (against the fake herdr CLI) reports both the
;; canonical resolved model and the effective translated native model args.
(deftest preview-shows-canonical-and-translated-model
  (let [{:keys [env]} (fake-env {} roster-model-personas)
        proc (call! env "task" "run" "canonical-worker" "--kind" "claude" "--task" "preview translation" "--print-prompt")]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "claude-opus-5" (get-in (result proc) [:result :model])))
    (is (= ["--model" "opus"] (get-in (result proc) [:result :model-args])))))

;; A `ORCH_ASSIGNMENT_ROOT` relocation (the fixture's `dir`, distinct from the real
;; repo root) resolves the project roster override under the relocated root, winning
;; over the shipped default.
(deftest relocated-assignment-root-resolves-project-config-override
  (let [{:keys [env dir]} (fake-env {} roster-model-personas)]
    (spit (str (fs/path dir ".agents" "subagents" "config.edn")) "{:models {\"claude-opus-5\" {:claude \"opus-relocated\"}}}")
    (let [proc (call! env "task" "run" "canonical-worker" "--kind" "claude" "--task" "relocated override" "--print-prompt")]
      (is (zero? (:exit proc)) (:err proc))
      (is (= ["--model" "opus-relocated"] (get-in (result proc) [:result :model-args]))))))

(def minimal-persona
  {"probe" "---\nname: probe\ndescription: fixture persona for roster fail-fast\nkind: pi\n---\nFixture probe.\n"})

;; Config is loaded and schema-validated before any ledger allocation or pane mutation:
;; a malformed project override must abort the whole spawn before either exists.
(deftest invalid-roster-override-fails-before-ledger-or-mutation
  (let [{:keys [env log dir]} (fake-env {} minimal-persona)]
    (spit (str (fs/path dir ".agents" "subagents" "config.edn")) "{:harnesses {:pi {:model-flag \"\"}}}")
    (let [proc (call! env "task" "start" "probe" "--task" "invalid roster aborts")]
      (is (= 1 (:exit proc)))
      (is (re-find #"model-flag" (:out proc)))
      (is (not (fs/exists? (fs/path dir ".agents" "tmp" "herdr-orch" "ledger"))))
      (is (not-any? mutating? (calls log))))))

;; --- portable Markdown artifact links ---------------------------------------------
;; Two link surfaces with deliberately different weight: the publish-time advisory is
;; built from the child's *declared* body (unvalidated), while `collect` renders only
;; artifacts whose existence it just checked. Neither may emit a raw OSC 8 escape.
(defn- publish-artifacts! [entry artifacts]
  (spit (:result entry)
        (core/envelope {:child (:child entry) :task (:task entry) :result (:result entry)
                        :status "COMPLETE" :summary "artifact links" :artifacts artifacts :findings [] :next nil})))
(defn- artifact-file! [dir name] (let [path (str (fs/path dir name))] (spit path "artifact body") path))

(deftest collect-returns-existence-validated-artifact-links
  (let [{:keys [env dir]} (fake-env {})
        entry (start-child! env dir "collect artifact links")
        ;; One hostile name covering spaces, `#`, `%`, and Markdown-significant label
        ;; characters at once, plus a second artifact to pin ordering and multiplicity.
        spaced (artifact-file! dir "a report #1 100% [draft].md")
        plain (artifact-file! dir "notes.md")
        declared [(str spaced " — the *main* report") plain]]
    (publish-artifacts! entry declared)
    (let [res (:result (result (call! env "task" "collect" (:task entry))))
          links (:artifact-links res)]
      (is (= "COMPLETE" (:status res)))
      (is (= 2 (count links)))
      (is (= (mapv core/artifact-link declared) links))
      (testing "the label is the whole absolute path, escaped, never a basename"
        (is (str/includes? (first links) (str "[" (str/replace spaced #"([\\`*_\[\]])" "\\\\$1") "]")))
        (is (str/starts-with? (first links) (str "[" dir))))
      (testing "the destination is a canonical, percent-encoded file URI"
        (is (str/includes? (first links) "](file:///"))
        (is (str/includes? (first links) "%20"))
        (is (str/includes? (first links) "%23"))
        (is (str/includes? (first links) "%25"))
        (is (str/includes? (first links) "%5B")))
      (testing "the purpose survives, escaped, outside the link"
        (is (str/ends-with? (first links) ") — the \\*main\\* report"))
        (is (not (str/includes? (second links) " — "))))
      (testing "no raw OSC 8 escape sequence reaches the parent"
        (is (not-any? #(str/includes? % "\u001b") links))
        (is (not-any? #(str/includes? % "]8;;") links)))
      (testing "the links are durable on the ledger entry, after the pane is closed"
        (is (= links (:artifact-links (ledger-entry* dir (:task entry)))))
        (is (= links (get-in (result (call! env "task" "status" (:task entry))) [:result :artifact-links])))))))

;; Absent is not the same claim as validated-empty, so an empty ARTIFACTS list must add no
;; key at all — on the entry or in the collect result — and neither may an invalid one.
(deftest empty-and-invalid-artifact-lists-produce-no-links
  (testing "no declared artifacts: no key anywhere"
    (let [{:keys [env dir]} (fake-env {})
          entry (start-child! env dir "no artifacts")]
      (publish-artifacts! entry [])
      (let [res (:result (result (call! env "task" "collect" (:task entry))))]
        (is (= "COMPLETE" (:status res)))
        (is (not (contains? res :artifact-links)))
        (is (not (contains? (ledger-entry* dir (:task entry)) :artifact-links))))))
  (testing "an artifact that fails the existence check never becomes a validated link"
    (let [{:keys [env dir]} (fake-env {})
          entry (start-child! env dir "missing artifact")]
      (publish-artifacts! entry ["/nonexistent/subagent-link-artifact — missing"])
      (let [res (:result (result (call! env "task" "collect" (:task entry))))]
        (is (= "invalid" (:status res)))
        (is (not (contains? res :artifact-links)))
        (is (not (contains? (ledger-entry* dir (:task entry)) :artifact-links)))))))

;; `--any` must remain the single-task shape plus exactly `remaining`: links flow through
;; the shared capture path rather than being added by the fan-in branch.
(deftest collect-any-carries-artifact-links-without-widening-its-shape
  (let [{single-env :env single-dir :dir} (fake-env {})
        {any-env :env any-dir :dir} (fake-env {})
        one (start-child! single-env single-dir "links via single-task collect")
        other (start-child! any-env any-dir "links via fan-in collect")
        a (artifact-file! single-dir "one report.md")
        b (artifact-file! any-dir "other report.md")]
    (publish-artifacts! one [(str a " — single")])
    (publish-artifacts! other [(str b " — fan-in")])
    (let [single (:result (result (call! single-env "task" "collect" (:task one))))
          any (:result (result (call! any-env "task" "collect" "--any")))]
      (is (= [(core/artifact-link (str b " — fan-in"))] (:artifact-links any)))
      (is (= (conj (set (keys single)) :remaining) (set (keys any))))
      (is (= 0 (:remaining any))))))

;; The advisory push carries declared links, labelled as pending validation. Publish still
;; performs no existence check, so this artifact deliberately does not exist.
(deftest publication-advisory-lists-declared-artifacts-as-pending-validation
  (let [{:keys [env dir prompt-file]} (fake-env {})
        entry (start-child! env dir "advisory artifact links")
        missing "/nonexistent/subagent advisory #1.md"
        second-artifact (str (fs/path dir "second.md"))
        proc (call! (merge (child-publish-env env entry "non-blocking") {"FAKE_PARENT_STATUS" "idle"})
                    "task" "publish" "--status" "COMPLETE" "--summary" "done"
                    "--artifact" (str missing " — pending report")
                    "--artifact" second-artifact)
        res (:result (result proc))
        pushed (slurp prompt-file)]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "sent" (get-in res [:parent-push :push])))
    (testing "the advisory is labelled as advisory/pending collection validation"
      (is (re-find #"(?i)advisory" pushed))
      (is (str/includes? pushed "pending validation by `collect`")))
    (testing "each declared artifact appears as a Markdown link with an encoded file URI"
      (is (str/includes? pushed (str "- " (core/artifact-link (str missing " — pending report")))))
      (is (str/includes? pushed (str "- " (core/artifact-link second-artifact))))
      (is (str/includes? pushed "(file:///"))
      (is (str/includes? pushed "%20"))
      (is (str/includes? pushed "%23")))
    (testing "advisory links are never collected evidence"
      (is (not (contains? res :artifact-links))))
    (testing "no raw OSC 8 escape sequence is submitted to the parent"
      (is (not (str/includes? pushed "\u001b")))
      (is (not (str/includes? pushed "]8;;"))))))

;; A publication with no artifacts adds no advisory section at all — not an empty one.
(deftest publication-advisory-omits-an-empty-artifact-section
  (let [{:keys [env dir prompt-file]} (fake-env {})
        entry (start-child! env dir "no advisory artifacts")
        proc (call! (merge (child-publish-env env entry "non-blocking") {"FAKE_PARENT_STATUS" "idle"})
                    "task" "publish" "--status" "COMPLETE" "--summary" "done")
        pushed (slurp prompt-file)]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "sent" (get-in (result proc) [:result :parent-push :push])))
    ;; The push itself still happens and still names the collect command.
    (is (str/includes? pushed (str "collect " (:task entry))))
    (is (not (re-find #"(?i)declared artifacts" pushed)))
    (is (not (str/includes? pushed "(file://")))
    ;; The artifact list is the only multi-line part of the advisory.
    (is (= 1 (count (str/split-lines pushed))))))

;; `collect` has no `:captured-at` short-circuit and `ledger/update!` rewrites the whole
;; entry, so a re-collect whose artifact has since been deleted must actively drop the links
;; the first capture wrote: an `invalid` entry may never advertise validated links.
(deftest a-recollect-that-loses-its-artifact-drops-the-stored-links
  (let [{:keys [env dir]} (fake-env {})
        entry (start-child! env dir "recollect drops stale links")
        artifact (artifact-file! dir "vanishing report.md")]
    (publish-artifacts! entry [(str artifact " — the report")])
    (let [first-pass (:result (result (call! env "task" "collect" (:task entry))))]
      (is (= "COMPLETE" (:status first-pass)))
      (is (= 1 (count (:artifact-links first-pass))))
      (is (some? (:artifact-links (ledger-entry* dir (:task entry))))))
    (fs/delete artifact)
    (let [second-pass (:result (result (call! env "task" "collect" (:task entry))))]
      (is (= "invalid" (:status second-pass)))
      (is (not (contains? second-pass :artifact-links)))
      (is (not (contains? (ledger-entry* dir (:task entry)) :artifact-links))))))

;; Publication is committed before the advisory renders, so one unrenderable artifact must
;; not cost the parent its notification. A NUL byte is the reachable case: `artifact-path`
;; accepts it (it only checks the leading `/`) while `Paths/get` rejects it. It cannot travel
;; through argv at all — which is also why it cannot be degraded to raw text, only dropped —
;; so it reaches `publish` via `--from-file`.
(deftest an-unrenderable-declared-artifact-degrades-the-advisory-but-still-pushes
  (let [{:keys [env dir prompt-file]} (fake-env {})
        entry (start-child! env dir "unrenderable advisory artifact")
        good (str (fs/path dir "good.md"))
        body (str (fs/path dir "nul-body.json"))]
    ;; Written as a JSON escape so the file itself carries no NUL byte.
    (spit body (str "{\"status\":\"COMPLETE\",\"summary\":\"done\",\"artifacts\":"
                    "[\"/tmp/nul\\u0000x.md — broken\",\"" good " — fine\"],"
                    "\"findings\":[],\"next\":null}"))
    (let [proc (call! (merge (child-publish-env env entry "non-blocking") {"FAKE_PARENT_STATUS" "idle"})
                      "task" "publish" "--from-file" body)
          res (:result (result proc))
          pushed (slurp prompt-file)]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "COMPLETE" (:status res)))
      ;; The parent is contacted, not reported as a prompt failure.
      (is (= "sent" (get-in res [:parent-push :push])))
      ;; The unrenderable item is dropped and counted; its sibling still links normally.
      (is (str/includes? pushed "1 declared artifact path(s) not renderable"))
      (is (not (str/includes? pushed "/tmp/nul")))
      (is (not (str/includes? pushed "\u0000")))
      (is (str/includes? pushed (str "- " (core/artifact-link (str good " — fine")))))
      (is (re-find #"(?i)advisory" pushed))
      ;; The envelope keeps the child's declared list verbatim.
      (is (str/includes? (slurp (:result entry)) "broken")))))
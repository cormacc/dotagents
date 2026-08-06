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
(defn mutating? [argv] (contains? #{["pane" "split"] ["tab" "create"] ["pane" "rename"] ["pane" "close"] ["agent" "start"] ["agent" "prompt"]} (vec (take 2 argv))))
;; `ORCH_ASSIGNMENT_ROOT` keeps the ledger, index markers, result files, and project
;; override lookup inside the per-test temp dir: `bb test` must never touch the live tree.
;; `HOME` points at an empty directory, so default personas and roster data resolve from
;; the launcher's packaged skill subtree. Tests may supply isolated project definitions.
(defn fake-env
  ([overrides] (fake-env overrides nil))
  ([overrides personas]
   (let [dir (fs/canonicalize (fs/create-temp-dir {:prefix "fake-herdr-"})) log (str (fs/path dir "calls")) env-file (str (fs/path dir "env")) prompt-file (str (fs/path dir "prompt"))
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
                   "ORCH_START_RETRY_BACKOFF_MS" "10"}
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

;; `--split` is explicit because this test pins the split argv itself, not the shipped
;; placement default (which is `:tab-split`, so a root spawn otherwise creates a tab).
(deftest preflight-and-vector-argv-contract
  (let [{:keys [env log prompt-file dir env-file]} (fake-env {}) proc (call! env "task" "run" "worker" "--split" "--task" "quotes ' newline\n $(unsafe) `unsafe`" "--timeout" "20")
        argv (calls log)
        injected (into {} (map #(vec (str/split % #"=" 2)) (str/split-lines (slurp env-file))))]
    (is (zero? (:exit proc)))
    (is (= (str dir) (str (fs/canonicalize dir))))
    ;; ORCH_ASSIGNMENT_ROOT relocates ledger + result state and is inherited by the child.
    (is (str/starts-with? (injected "HERDR_ORCH_RESULT") (str (fs/path dir ".tmp" "herdr-orch"))))
    (is (= (str dir) (injected "ORCH_ASSIGNMENT_ROOT")))
    (is (= "COMPLETE" (get-in (result proc) [:result :status])))
    (is (= ["pane" "split" "--pane" "w:p" "--direction" "right"] (subvec (vec (first (filter #(= ["pane" "split"] (vec (take 2 %))) argv))) 0 6)))
    ;; Capture closes nothing: the pane persists until `close` or `continue` acts on it.
    (is (not-any? #(= ["pane" "close"] (vec (take 2 %))) argv))
    (is (re-find #"(?s)\$\(unsafe\).*`unsafe`" (slurp prompt-file)))
    ;; Preflight is the version gate alone: no per-command `--help` probing survives, so a
    ;; spawn issues exactly one non-mutating capability call.
    (is (empty? (filter #(some #{"--help"} %) argv)))
    (is (= 1 (count (filter #(= ["--version"] (vec %)) argv))))))

(defn- ledger-entry* [dir task]
  (json/parse-string (slurp (str (fs/path dir ".tmp" "herdr-orch" "ledger" (str task ".json")))) true))
(defn- injected-env [env-file key]
  (get (into {} (map #(vec (str/split % #"=" 2)) (str/split-lines (slurp env-file)))) key))
;; Rewrites a ledger entry in place. Several lifecycle states -- a captured round, a
;; different waiting policy, a foreign owner -- are ordinary ledger facts with no CLI verb
;; that produces them in isolation, and writing them directly keeps a test's herdr call log
;; about the verb under test alone.
(defn- patch-entry! [dir entry & kvs]
  (let [updated (apply assoc entry kvs)]
    (spit (str (fs/path dir ".tmp" "herdr-orch" "ledger" (str (:task entry) ".json")))
          (json/generate-string updated))
    updated))
(defn- capture-entry! [dir entry] (patch-entry! dir entry :captured-at "2026-01-01T00:00:00Z" :status "COMPLETE"))

;; The below-root policy check precedes preflight, ledger allocation, and all Herdr calls.
(deftest below-root-disallowed-spawn-is-side-effect-free
  (let [{:keys [env log dir]} (fake-env {"HERDR_ORCH_PERSONA" "worker" "HERDR_ORCH_SPAWNS" "scout researcher"})
        proc (call! env "task" "start" "worker" "--task" "disallowed nested worker")]
    (is (= 1 (:exit proc)))
    (is (re-find #"spawn refused: target persona is not in this agent's HERDR_ORCH_SPAWNS allow-list" (:out proc)))
    (is (not (fs/exists? (fs/path dir ".tmp" "herdr-orch" "ledger"))))
    (is (empty? (calls log)))))

(deftest below-root-worker-scout-is-a-leaf
  (let [{:keys [env log env-file dir prompt-file]} (fake-env {"HERDR_ORCH_PERSONA" "worker"
                                                               "HERDR_ORCH_SPAWNS" "scout researcher"
                                                               "FAKE_PARENT_LABEL" "worker-1-claude-opus-5"})
        proc (call! env "task" "start" "scout" "--task" "permitted nested scout")
        task (get-in (result proc) [:result :task])
        entry (ledger-entry* dir task)
        rename (first (filter #(= ["pane" "rename"] (vec (take 2 %))) (calls log)))]
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
    (is (str/includes? prompt "You may spawn at most one blocking scout or researcher or advisor only when a factual gap or material judgment blocks progress; that child must remain a leaf."))
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
      (is (not (fs/exists? (fs/path dir ".tmp" "herdr-orch" "ledger"))))
      (is (not-any? mutating? (calls log))))))

(deftest relocated-assignment-root-persona-shadows-packaged-default
  (let [{:keys [env log roster]} (fake-env {}
                                           {"worker" "---\nname: worker\ndescription: relocated project worker\nmodel: light\n---\nProject override.\n"})
        proc (call! env "task" "start" "worker" "--task" "project persona shadows package")
        start (first (filter #(= ["agent" "start"] (vec (take 2 %)))
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
        tab-create (first (filter #(= ["tab" "create"] (vec (take 2 %))) argv))
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
    (is (not-any? #(= ["pane" "split"] (vec (take 2 %))) argv))
    ;; The rename→start→prompt flow is unchanged, against the tab's root pane, and capture
    ;; leaves it standing exactly as it leaves a split.
    (is (some #(= ["pane" "rename" "w:child"] (vec (take 3 %))) argv))
    (is (not-any? #(= ["pane" "close"] (vec (take 2 %))) argv))
    ;; Env injection is identical to a split spawn.
    (is (str/starts-with? (injected "HERDR_ORCH_CHILD") "worker-"))
    ;; Nothing lifecycle-related reaches the child: the waiting policy lives only on the
    ;; ledger entry, so a continued round cannot inherit a stale spawn-time value.
    (is (nil? (injected "HERDR_ORCH_WAITING_POLICY")))
    (is (= "blocking" (:waiting-policy entry)))
    (is (= "tab" (:placement entry)))
    (is (= "w:tab" (:tab-id entry)))
    (is (= "w:child" (:pane-id entry)))))

;; The shipped `:defaults :placement` is `:tab-split`, so an unflagged *root* spawn takes a
;; tab -- one tab per first-level subagent, rather than an ever-narrowing column of splits --
;; and an unflagged *below-root* spawn still splits, keeping a nested unit together.
;; `--split` remains the escape hatch and must issue no `tab create` at all.
(deftest shipped-default-placement-is-tab-at-root-and-split-below
  (testing "root: tab"
    (let [{:keys [env log dir]} (fake-env {}) proc (call! env "task" "run" "worker" "--task" "default placement" "--timeout" "20")
          task (get-in (result proc) [:result :task]) entry (ledger-entry* dir task)]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "COMPLETE" (get-in (result proc) [:result :status])))
      (is (not-any? #(= ["pane" "split"] (vec (take 2 %))) (calls log)))
      (is (= "tab" (:placement entry)))
      (is (= "w:tab" (:tab-id entry)))))
  (testing "below root: split"
    (let [{:keys [env log dir]} (fake-env {"HERDR_ORCH_PERSONA" "worker" "HERDR_ORCH_SPAWNS" "scout"
                                           "FAKE_PARENT_LABEL" "worker-1-light"})
          proc (call! env "task" "run" "scout" "--task" "nested placement" "--timeout" "20")
          _ (is (zero? (:exit proc)) (:out proc))
          task (get-in (result proc) [:result :task]) entry (ledger-entry* dir task)]
      (is (not-any? #(= ["tab" "create"] (vec (take 2 %))) (calls log)))
      (is (= "split" (:placement entry)))
      (is (nil? (:tab-id entry)))))
  (testing "--split overrides the configured default and issues no tab command"
    (let [{:keys [env log dir]} (fake-env {}) proc (call! env "task" "run" "worker" "--split" "--task" "forced split" "--timeout" "20")
          task (get-in (result proc) [:result :task]) entry (ledger-entry* dir task)]
      (is (zero? (:exit proc)) (:err proc))
      (is (not-any? #(= ["tab" "create"] (vec (take 2 %))) (calls log)))
      (is (= "split" (:placement entry)))
      (is (nil? (:tab-id entry))))))

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
    ;; Retention is identical to a split spawn: the tab's root pane survives the capture,
    ;; and `close` is what later takes it (Herdr closes a tab whose last pane closes).
    (is (not-any? #(= ["pane" "close"] (vec (take 2 %))) (calls log)))
    (let [closed (call! env "task" "close" task)]
      (is (zero? (:exit closed)) (:err closed))
      (is (some #(= ["pane" "close" "w:child"] (vec (take 3 %))) (calls log))))))

;; Partial failure after `tab create`: the pane is recorded before the failing step, so
;; cleanup closes the tab's root pane and the ledger lands in a failed state.
(deftest tab-placement-partial-start-failure-is-tracked-and-cleaned
  (let [{:keys [env log dir]} (fake-env {"FAKE_FAIL_START" "1"})
        proc (call! env "task" "start" "worker" "--tab" "--task" "tab fail")
        argv (calls log)
        ;; The failed `start` exits non-zero without printing the task id, so recover the
        ;; single entry file from the ledger directory (skipping the `indices/` subdir).
        entry (first (for [f (fs/list-dir (fs/path dir ".tmp" "herdr-orch" "ledger"))
                           :when (and (fs/regular-file? f) (str/ends-with? (fs/file-name f) ".json"))]
                       (ledger-entry* dir (str/replace (fs/file-name f) #"\.json$" ""))))]
    (is (= 1 (:exit proc)))
    (is (some #(= ["tab" "create"] (vec (take 2 %))) argv))
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
        pi-start (first (filter #(= ["agent" "start"] (vec (take 2 %))) (calls log)))]
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
        claude-start (first (filter #(= ["agent" "start"] (vec (take 2 %))) (calls log)))
        rename (first (filter #(= ["pane" "rename"] (vec (take 2 %))) (calls log)))]
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
  (let [{:keys [env log]} (fake-env {"FAKE_HERDR_VERSION" "0.7.4"}) proc (call! env "task" "start" "worker" "--task" "x")]
    (is (= 1 (:exit proc)))
    (is (str/includes? (str (:out proc) (:err proc)) "0.7.5"))
    (is (not-any? mutating? (calls log)))))

(deftest preview-is-side-effect-free
  (let [{:keys [env log]} (fake-env {}) proc (call! env "task" "run" "worker" "--task" "preview" "--print-prompt")]
    (is (zero? (:exit proc)))
    (is (re-find #"<assigned-task>" (:out proc)))
    (is (not-any? mutating? (calls log)))
    (is (not-any? #(= ["pane" "get"] (vec (take 2 %))) (calls log))))
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
      (is (not (fs/exists? (fs/path dir ".tmp" "herdr-orch" "ledger"))))
      (is (not-any? mutating? (calls log)))))
  ;; The unflagged root row is the shipped `:tab-split` default resolving at root.
  (testing "--print-prompt reports the resolved placement"
    (doseq [[flag expected] [[[] "tab"] [["--tab"] "tab"] [["--split"] "split"]]]
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
      (is (some #(= ["tab" "create"] (vec (take 2 %))) (calls log))))
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
        start-argv (fn [] (first (filter #(= ["agent" "start"] (vec (take 2 %))) (calls log))))]
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
                       (vec (last (filter #(= ["agent" "start"] (vec (take 2 %))) (calls log))))))]
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
    (is (not-any? #(= ["pane" "close"] (vec (take 2 %))) (calls log))))
  (let [{:keys [env log]} (fake-env {"FAKE_WAIT" "blocked"}) proc (call! env "task" "run" "worker" "--task" "blocked" "--timeout" "20")]
    (is (= "blocked" (get-in (result proc) [:result :status])))
    (is (not-any? #(= ["pane" "close"] (vec (take 2 %))) (calls log)))))

(deftest result-edge-and-publication-contract
  ;; Publication during a structured Herdr wait error, with a FAILED envelope end-to-end.
  (let [{:keys [env log]} (fake-env {"FAKE_WAIT" "timeout-publish" "FAKE_PUBLISH_STATUS" "FAILED"}) proc (call! env "task" "run" "worker" "--task" "timeout publication" "--timeout" "20")]
    (is (zero? (:exit proc)))
    (is (= "FAILED" (get-in (result proc) [:result :status])))
    (is (not-any? #(= ["pane" "close"] (vec (take 2 %))) (calls log))))
  ;; A published result is immutable, so a mismatched envelope is a non-final `invalid`
  ;; outcome (pane retained, needs manual intervention) rather than a thrown command.
  (let [{:keys [env log]} (fake-env {"FAKE_WAIT" "publish" "FAKE_BAD_ENVELOPE" "1"}) proc (call! env "task" "run" "worker" "--task" "stale" "--timeout" "20")]
    (is (zero? (:exit proc)))
    (is (= "invalid" (get-in (result proc) [:result :status])))
    (is (re-find #"identity" (get-in (result proc) [:result :reason])))
    (is (not-any? #(= ["pane" "close"] (vec (take 2 %))) (calls log))))
  ;; A hand-driven publish names a task with no ledger entry, so it has no waiting policy
  ;; and is silent: the RESULT is still written exactly once, but no toast and no push.
  (let [{:keys [env log dir]} (fake-env {}) target (str (fs/path dir "published.result"))
        publication-env (merge env {"HERDR_ORCH_CHILD" "child" "HERDR_ORCH_TASK" "task" "HERDR_ORCH_RESULT" target})
        ok (call! publication-env "task" "publish" "--status" "COMPLETE" "--summary" "done") second (call! publication-env "task" "publish" "--status" "COMPLETE" "--summary" "again")
        from-file-target (str (fs/path dir "from-file.result")) body (str (fs/path dir "body.json"))]
    (is (zero? (:exit ok))) (is (= 1 (:exit second))) (is (fs/exists? target))
    (is (not-any? #(= ["notification" "show" "Subagent child published"] (vec (take 3 %))) (calls log)))
    (is (nil? (get-in (result ok) [:result :notification])))
    (is (nil? (get-in (result ok) [:result :parent-push])))
    (spit body (json/generate-string {:status "COMPLETE" :summary "published from file" :artifacts [] :findings [] :next nil}))
    (let [proc (call! (merge publication-env {"HERDR_ORCH_RESULT" from-file-target}) "task" "publish" "--from-file" body)]
      (is (zero? (:exit proc)) (:err proc))
      (is (str/includes? (slurp from-file-target) "SUMMARY: published from file")))))

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
        ;; Help text, not a missing-assignment failure: `--retro` must not have eaten
        ;; `--help`. Per-command help means this is `task run`'s signature, not the
        ;; global usage.
        (is (str/starts-with? (:out proc) "oh task run <persona>"))))
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
        entry (json/parse-string (slurp (str (fs/path dir ".tmp" "herdr-orch" "ledger" (str task ".json")))) true)]
    (is (= "COMPLETE" (get-in (result proc) [:result :status])))
    (is (false? (:retro entry)))
    (is (= ["unsolicited → behavioral → still accepted"] (get-in entry [:envelope :process])))))

;; PROCESS candidates travel with the result: emitted by `publish`, persisted onto the
;; ledger entry's `:envelope` at capture, and never gating capture or pane closure.
(deftest process-candidates-publish-and-persist
  (let [{:keys [env dir]} (fake-env {}) target (str (fs/path dir "process.result"))
        base (merge env {"HERDR_ORCH_CHILD" "child" "HERDR_ORCH_TASK" "task"})
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
        entry (json/parse-string (slurp (str (fs/path dir ".tmp" "herdr-orch" "ledger" (str task ".json")))) true)]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "COMPLETE" (get-in (result proc) [:result :status])))
    (is (= ["stale doc → guardrail → read the contract first"] (get-in entry [:envelope :process])))
    (is (nil? (:process-overflow entry))))
  ;; A hand-assembled six-item section degrades to five at capture: the result stays
  ;; COMPLETE and capture is otherwise unaffected, because PROCESS never gates it.
  (let [{:keys [env log dir]} (fake-env {"FAKE_PUBLISH_PROCESS" (str/join "\n- " (map #(str "s" % " → c → r" %) (range 6)))})
        proc (call! env "task" "run" "worker" "--task" "process overflow" "--timeout" "200")
        task (get-in (result proc) [:result :task])
        entry (json/parse-string (slurp (str (fs/path dir ".tmp" "herdr-orch" "ledger" (str task ".json")))) true)]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "COMPLETE" (get-in (result proc) [:result :status])))
    (is (= "COMPLETE" (:status entry)))
    (is (true? (:process-overflow entry)))
    (is (= 5 (count (get-in entry [:envelope :process]))))
    (is (not-any? #(= ["pane" "close"] (vec (take 2 %))) (calls log)))))

(defn- ledger-entry [dir task]
  (json/parse-string (slurp (str (fs/path dir ".tmp" "herdr-orch" "ledger" (str task ".json")))) true))
(defn- child-get-count [log]
  (count (filter #(and (= ["agent" "get"] (vec (take 2 %))) (not= "w:p" (nth % 2 nil))) (calls log))))

;; The child's session reference must outlive the pane it came from, and no single hook is
;; reliable:
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
      ;; Recorded from the `agent start` return, so it is on the entry before any later
      ;; probe could supply it -- and before the pane is closed, whenever that happens.
      (is (not-any? #(= ["pane" "close"] (vec (take 2 %))) (calls log)))))
  (testing "a session absent at start is backfilled by the post-prompt agent get"
    (let [{:keys [env dir]} (fake-env {"FAKE_SESSION_FROM" "get"})
          proc (call! env "task" "start" "worker" "--task" "session after prompt")
          entry (ledger-entry dir (get-in (result proc) [:result :task]))]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "path" (get-in entry [:child-session :kind])))
      (is (= "/tmp/fake-child-session.jsonl" (get-in entry [:child-session :value])))))
  (testing "a wait outcome backfills without adding a Herdr call to the loop"
    (let [{:keys [env dir log]} (fake-env {"FAKE_SESSION_FROM" "wait" "FAKE_WAIT" "idle-then-publish" "FAKE_WAIT_PUBLISH_AFTER" "3" "ORCH_POLL_INTERVAL_MS" "20"})
          proc (call! env "task" "run" "worker" "--task" "session from wait" "--timeout" "5000")
          entry (ledger-entry dir (get-in (result proc) [:result :task]))]
      (is (= "COMPLETE" (get-in (result proc) [:result :status])))
      (is (= "/tmp/fake-child-session.jsonl" (get-in entry [:child-session :value])))
      ;; Four wait iterations, but only the post-prompt probe and the capture-time session
      ;; backfill may issue `agent get`; the loop itself adds none.
      (is (<= (child-get-count log) 2))))
  (testing "live (status/list) backfills while the child is alive"
    (let [{:keys [env dir]} (fake-env {"FAKE_SESSION_FROM" "late-get"})
          start (call! env "task" "start" "worker" "--task" "session via status")
          task (get-in (result start) [:result :task])]
      (is (nil? (:child-session (ledger-entry dir task))))
      (is (zero? (:exit (call! env "task" "status" task))))
      (is (= "/tmp/fake-child-session.jsonl" (get-in (ledger-entry dir task) [:child-session :value])))))
  (testing "capture backfills the session of a BLOCKED owned entry, whose pane is retained"
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
      (is (not-any? #(= ["pane" "close"] (vec (take 2 %))) (calls log)))))
  (testing "a child that never publishes still carries its session"
    (let [{:keys [env dir]} (fake-env {"FAKE_SESSION_FROM" "start" "FAKE_WAIT" "idle-forever" "ORCH_POLL_INTERVAL_MS" "50"})
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

(defn- start-call-count [log] (count (filter #(= ["agent" "start"] (vec (take 2 %))) (calls log))))
(defn- split-call-count [log] (count (filter #(= ["pane" "split"] (vec (take 2 %))) (calls log))))

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
        ;; `--split` pins the placement this test counts (`split-call-count`); the retry
        ;; behaviour under test is identical for either placement.
        proc (call! env "task" "start" "worker" "--split" "--task" "busy then available")
        task (get-in (result proc) [:result :task])
        entry (ledger-entry* dir task)]
    (is (zero? (:exit proc)) (:err proc))
    (is (nil? (:failure-phase entry)))
    (is (= "prompted" (:status entry)))
    (is (= "w:child" (:pane-id entry)))
    ;; Two simulated `agent_pane_busy` failures plus the eventual success.
    (is (= 3 (start-call-count log)))
    (is (= 1 (split-call-count log)))
    ;; The retry never triggers cleanup: no pane is ever closed.
    (is (not (some #(= ["pane" "close"] (vec (take 2 %))) (calls log))))
    ;; exactly one ledger entry
    (is (= 1 (count (filter #(and (fs/regular-file? %) (str/ends-with? (fs/file-name %) ".json"))
                             (fs/list-dir (fs/path dir ".tmp" "herdr-orch" "ledger"))))))))

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
        ;; `--split` pins the placement this test counts (`split-call-count`); cleanup is
        ;; identical for either placement.
        proc (call! env "task" "start" "worker" "--split" "--task" "always busy")
        entry (first (for [f (fs/list-dir (fs/path dir ".tmp" "herdr-orch" "ledger"))
                           :when (and (fs/regular-file? f) (str/ends-with? (fs/file-name f) ".json"))]
                       (ledger-entry* dir (str/replace (fs/file-name f) #"\.json$" ""))))]
    (is (= 1 (:exit proc)))
    (is (= "failed" (:status entry)))
    (is (= "start" (:failure-phase entry)))
    (is (some #(= ["pane" "close" "w:child"] (vec (take 3 %))) (calls log)))
    (is (= herdr/start-retry-attempts (start-call-count log)))
    (is (= 1 (split-call-count log)))
    (is (= 1 (count (filter #(and (fs/regular-file? %) (str/ends-with? (fs/file-name %) ".json"))
                             (fs/list-dir (fs/path dir ".tmp" "herdr-orch" "ledger"))))))))

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

;; --- post-prompt dispatch verification --------------------------------------------
;; `agent prompt` submits atomically, but a swallowed Enter leaves the composed prompt in
;; the child's composer, which burned a pane and the parent's whole timeout budget while the
;; ledger read `prompted`. The fixture models both outcomes: a dispatched prompt drives the
;; child to `working`, while FAKE_HELD_PROMPT leaves it `idle` until an explicit `enter`.
(defn- enter-nudges [log child]
  (filterv #(= ["agent" "send-keys" child "enter"] (vec (take 4 %))) (calls log)))

(deftest dispatched-prompt-is-confirmed-without-touching-the-keyboard
  (let [{:keys [env log dir]} (fake-env {"ORCH_POLL_INTERVAL_MS" "20"})
        proc (call! env "task" "start" "worker" "--task" "prompt lands unaided")
        entry (ledger-entry* dir (get-in (result proc) [:result :task]))]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "dispatched" (get-in entry [:dispatch :status])))
    (is (= "working" (get-in entry [:dispatch :state])))
    (is (= 0 (get-in entry [:dispatch :nudges])))
    (is (string? (:dispatched-at entry)))
    ;; The confirming probe is the same `agent get` that backfills `:child-session`: the
    ;; check adds no Herdr call of its own to a healthy spawn.
    (is (= 1 (child-get-count log)))
    (is (empty? (enter-nudges log (:child entry))))))

(deftest held-prompt-is-nudged-once-and-then-confirmed
  (let [{:keys [env log dir]} (fake-env {"FAKE_HELD_PROMPT" "1" "ORCH_POLL_INTERVAL_MS" "20"})
        proc (call! env "task" "start" "worker" "--task" "prompt held unsubmitted")
        entry (ledger-entry* dir (get-in (result proc) [:result :task]))]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "dispatched" (get-in entry [:dispatch :status])))
    (is (= 1 (get-in entry [:dispatch :nudges])))
    (is (string? (:dispatched-at entry)))
    ;; Exactly one Enter: the nudge is not repeated once the child is observably working.
    (is (= 1 (count (enter-nudges log (:child entry)))))))

;; An unobservable child must never be guessed at: Enter on a state we could not read risks
;; submitting stray empty input into whatever the pane actually holds.
(deftest unknown-state-is-never-nudged
  (doseq [[label overrides] {"unknown status" {"FAKE_AGENT_STATUS" "unknown"}
                             "failing agent get" {"FAKE_FAIL_CHILD_GET" "1"}}]
    (let [{:keys [env log dir]} (fake-env (merge {"FAKE_HELD_PROMPT" "stuck" "ORCH_POLL_INTERVAL_MS" "20"
                                                  "ORCH_DISPATCH_TIMEOUT_MS" "120"}
                                                 overrides))
          proc (call! env "task" "start" "worker" "--task" (str "unobservable child: " label))
          task (get-in (result proc) [:result :task])
          entry (ledger-entry* dir task)]
      (is (zero? (:exit proc)) (str label " " (:err proc)))
      (is (= "unconfirmed" (get-in entry [:dispatch :status])) label)
      (is (= 0 (get-in entry [:dispatch :nudges])) label)
      (is (nil? (:dispatched-at entry)) label)
      (is (empty? (enter-nudges log (:child entry))) label))))

;; A child that never dispatches is a diagnosis, not a spawn failure: the pane is kept and
;; the ordinary wait/collect path still runs, so `run` reports its usual `pending` timeout.
(deftest unconfirmed-dispatch-neither-fails-the-spawn-nor-closes-the-pane
  (let [{:keys [env log dir]} (fake-env {"FAKE_HELD_PROMPT" "stuck" "ORCH_POLL_INTERVAL_MS" "20"
                                         "ORCH_DISPATCH_TIMEOUT_MS" "150" "FAKE_WAIT" "idle-forever"})
        proc (call! env "task" "run" "worker" "--task" "prompt never dispatches" "--timeout" "150")
        task (get-in (result proc) [:result :task])
        entry (ledger-entry* dir task)]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "pending" (get-in (result proc) [:result :status])))
    (is (= "unconfirmed" (get-in entry [:dispatch :status])))
    (is (= "idle" (get-in entry [:dispatch :state])))
    ;; Nudged up to the cap and no further, so a misread state cannot burst keys.
    (is (= cli/max-dispatch-nudges (get-in entry [:dispatch :nudges])))
    (is (= cli/max-dispatch-nudges (count (enter-nudges log (:child entry)))))
    (is (nil? (:dispatched-at entry)))
    (is (= "w:child" (:pane-id entry)))
    (is (not-any? #(= ["pane" "close"] (vec (take 2 %))) (calls log)))))

;; A child that settled before the first probe has plainly dispatched; only `idle` and
;; `unknown` are ambiguous.
(deftest already-settled-child-counts-as-dispatched
  (doseq [state ["done" "blocked"]]
    (let [{:keys [env log dir]} (fake-env {"FAKE_AGENT_STATUS" state "FAKE_HELD_PROMPT" "stuck"
                                           "ORCH_POLL_INTERVAL_MS" "20" "ORCH_DISPATCH_TIMEOUT_MS" "120"})
          proc (call! env "task" "start" "worker" "--task" (str "settled at " state))
          entry (ledger-entry* dir (get-in (result proc) [:result :task]))]
      (is (zero? (:exit proc)) (str state " " (:err proc)))
      (is (= "dispatched" (get-in entry [:dispatch :status])) state)
      (is (= state (get-in entry [:dispatch :state])) state)
      (is (empty? (enter-nudges log (:child entry))) state))))

;; Same non-positive/unparseable/blank discipline as every other ORCH_* budget.
(deftest dispatch-timeout-parsing
  (is (= 15000 cli/default-dispatch-timeout-ms))
  (is (= 2 cli/max-dispatch-nudges))
  (is (= 1234 (cli/parse-dispatch-timeout "1234")))
  (doseq [raw [nil "" "   " "soon" "0" "-5"]]
    (is (= cli/default-dispatch-timeout-ms (cli/parse-dispatch-timeout raw)) (pr-str raw))))

(defn- wait-call-count [log]
  (count (filter #(= ["agent" "wait"] (vec (take 2 %))) (calls log))))

;; Capture correctness after several settled-without-result iterations. The fixture
;; publishes on a fixed call count, so this test proves capture/parity only — the
;; interval-derived bound lives in `bounded-poll-timeout-without-result`.
(deftest bounded-poll-eventual-publication
  (let [{:keys [env log]} (fake-env {"FAKE_WAIT" "idle-then-publish" "FAKE_WAIT_PUBLISH_AFTER" "3" "ORCH_POLL_INTERVAL_MS" "50"})
        proc (call! env "task" "run" "worker" "--task" "idle then publish" "--timeout" "5000")]
    (is (zero? (:exit proc)))
    (is (= "COMPLETE" (get-in (result proc) [:result :status])))
    (is (<= 4 (wait-call-count log)))))

;; Regression guard for the poll sleep itself: without it, a settled-but-unpublished
;; child drives `agent wait` as fast as the process can fork (measured 37 calls over
;; this budget). The bound is well under half that, and above the sleep-derived ceiling
;; of 400/50 + 1 = 9.
(deftest bounded-poll-timeout-without-result
  (let [{:keys [env log]} (fake-env {"FAKE_WAIT" "idle-forever" "ORCH_POLL_INTERVAL_MS" "50"})
        proc (call! env "task" "run" "worker" "--task" "never publishes" "--timeout" "400")]
    (is (zero? (:exit proc)))
    (is (= "pending" (get-in (result proc) [:result :status])))
    (is (= "timeout" (get-in (result proc) [:result :reason])))
    (is (<= (wait-call-count log) 14))))

(deftest bounded-poll-covers-collect-wait
  (let [{:keys [env log]} (fake-env {"FAKE_WAIT" "idle-then-publish" "FAKE_WAIT_PUBLISH_AFTER" "2" "ORCH_POLL_INTERVAL_MS" "50"})
        start (call! env "task" "start" "worker" "--task" "later") task (get-in (result start) [:result :task])
        proc (call! env "task" "collect" task "--wait" "--timeout" "5000")]
    (is (zero? (:exit proc)))
    (is (= "COMPLETE" (get-in (result proc) [:result :status])))
    (is (<= 3 (wait-call-count log)))))

(deftest a-negative-poll-interval-never-escapes
  (let [{:keys [env]} (fake-env {"FAKE_WAIT" "idle-forever" "ORCH_POLL_INTERVAL_MS" "-5"})
        proc (call! env "task" "run" "worker" "--task" "negative interval" "--timeout" "200")]
    (is (zero? (:exit proc)))
    (is (= "pending" (get-in (result proc) [:result :status])))))

(deftest stdin-assignment-input
  (let [{:keys [env prompt-file]} (fake-env {})
        proc @(process/process [bin "task" "start" "worker"] {:in "assignment from stdin" :out :string :err :string :env env})]
    (is (zero? (:exit proc)) (:err proc))
    (is (str/includes? (slurp prompt-file) "assignment from stdin"))))

;; The implicit ten-minute capture budget.
(deftest default-collect-budget
  (let [{:keys [env log]} (fake-env {}) proc (call! env "task" "run" "worker" "--task" "default budget")
        wait (first (filter #(= ["agent" "wait"] (vec (take 2 %))) (calls log)))
        budget (parse-long (second (drop-while #(not= "--timeout" %) wait)))]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "COMPLETE" (get-in (result proc) [:result :status])))
    (is (<= 599000 budget 600000))))

;; Capture no longer closes anything for anyone, but it still reports *whose* assignment it
;; captured: a foreign or unresolvable caller is named as such, so a parent reading a result
;; it does not own knows the close-or-continue decision is not its to make.
(deftest capture-reports-ownership-and-closes-nothing-for-any-caller
  (let [{:keys [env env-file log]} (fake-env {}) start (call! env "task" "start" "worker" "--task" "foreign") task (get-in (result start) [:result :task])
        values (into {} (map #(vec (str/split % #"=" 2)) (str/split-lines (slurp env-file))))
        envelope (core/envelope {:child (values "HERDR_ORCH_CHILD") :task task :result (values "HERDR_ORCH_RESULT") :status "COMPLETE" :summary "foreign" :artifacts [] :findings [] :next nil})]
    (spit (values "HERDR_ORCH_RESULT") envelope)
    (testing "a different parent session captures and reports the foreign ownership"
      (let [proc (call! (merge env {"HERDR_PANE_ID" "w:other"}) "task" "collect" task)]
        (is (zero? (:exit proc)) (:err proc))
        (is (= "COMPLETE" (get-in (result proc) [:result :status])))
        (is (true? (get-in (result proc) [:result :pane-retained])))
        (is (= "foreign-parent-session" (get-in (result proc) [:result :ownership])))))
    (testing "an unresolvable caller identity is non-owning but still captures"
      (let [proc (call! (merge env {"FAKE_FAIL_AGENT_GET" "w:p"}) "task" "collect" task)]
        (is (zero? (:exit proc)) (:err proc))
        (is (= "COMPLETE" (get-in (result proc) [:result :status])))
        (is (true? (get-in (result proc) [:result :pane-retained])))))
    (testing "the owning session captures without the foreign marker, and closes nothing"
      (let [proc (call! env "task" "collect" task)]
        (is (zero? (:exit proc)) (:err proc))
        (is (nil? (get-in (result proc) [:result :pane-retained])))))
    (is (not-any? #(= ["pane" "close"] (vec (take 2 %))) (calls log)))))


;; Publication is exactly-once and immutable, so a relative artifact path must be caught
;; before the write, not only at collect (core/artifact-path is the same predicate there).
(deftest relative-artifact-rejected-before-publication
  (let [{:keys [env dir]} (fake-env {}) base (merge env {"HERDR_ORCH_CHILD" "child" "HERDR_ORCH_TASK" "task"})
        target (str (fs/path dir "relative-artifact.result"))
        ;; The orphan this guards against is the `<result>.<uuid>.tmp` *file* publish writes
        ;; before hard-linking. Directories are excluded deliberately: resolving the ledger
        ;; entry creates the assignment root's own `.tmp/` tree, which is not an orphan.
        tmp-siblings (fn [] (->> (fs/list-dir dir) (filter fs/regular-file?) (map str) (filter #(str/ends-with? % ".tmp"))))]
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
    (is (not-any? #(= ["pane" "close"] (vec (take 2 %))) (calls log)))
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
        entry (first (for [f (fs/list-dir (fs/path dir ".tmp" "herdr-orch" "ledger"))
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
  (count (filter #(= ["agent" "list"] (vec (take 2 %))) (calls log))))
(defn- closed-panes [log]
  (set (keep #(when (= ["pane" "close"] (vec (take 2 %))) (nth % 2 nil))
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
        entry (first (for [f (fs/list-dir (fs/path dir ".tmp" "herdr-orch" "ledger"))
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
    (let [path (fs/path dir ".tmp" "herdr-orch" "ledger" (str (:task a) ".json"))
          corrupted (dissoc a :parent-session)]
      (spit (str path) (json/generate-string corrupted))
      (let [proc (call! (merge env {"FAKE_FAIL_AGENT_GET" "w:p"}) "task" "prune" (:task a))]
        (is (= 1 (:exit proc)))
        (is (re-find #"own" (:out proc)))
        (is (= corrupted (ledger-entry* dir (:task a))))))))

;; The *second* child publishes first, so first-of-N cannot be an artefact of spawn order.
;; One capture touches exactly one entry and leaves the sibling's untouched.
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
      ;; no pane is closed, captured or not
      (is (empty? (closed-panes log)))
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
    (let [{:keys [env dir state]} (fake-env {"ORCH_POLL_INTERVAL_MS" "50"})
          a (start-child! env dir "races the liveness scan")]
      (child-state! state a "gone" "")
      (spit (str (fs/path state "publish-queue")) (str (:child a) "\n"))
      (let [res (:result (result (call! env "task" "collect" "--any" "--wait" "--timeout" "20000")))]
        (is (= "COMPLETE" (:status res)))
        (is (= (:task a) (:task res)))
        (is (= 0 (:remaining res))))))
  (testing "a blocked sibling does not short-circuit past a racing publication either"
    (let [{:keys [env log dir state]} (fake-env {"ORCH_POLL_INTERVAL_MS" "50"})
          a (start-child! env dir "blocked sibling")
          b (start-child! env dir "races the liveness scan")]
      (child-state! state a "status" "blocked")
      ;; `nameless` hides b from the listing without breaking `agent get`, so the capture
      ;; has to come from the publication racing the very listing that classifies it away.
      (child-state! state b "nameless" "")
      (spit (str (fs/path state "publish-queue")) (str (:child b) "\n"))
      (let [res (:result (result (call! env "task" "collect" "--any" "--wait" "--timeout" "20000")))]
        (is (= "COMPLETE" (:status res)))
        (is (= (:task b) (:task res)))
        (is (= 1 (:remaining res)))
        (is (empty? (closed-panes log)))))))

;; Liveness by `name` only: a real `agent list` contains nameless entries for manually
;; started agents, so an unidentifiable entry must count as vanished rather than keeping an
;; exited child "live" forever and making `no-live-children` unreachable.
(deftest collect-any-treats-a-nameless-listing-entry-as-vanished
  (let [{:keys [env dir state]} (fake-env {"ORCH_POLL_INTERVAL_MS" "50"})
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
  (let [{:keys [env dir]} (fake-env {"ORCH_POLL_INTERVAL_MS" "50" "FAKE_AGENT_LIST_NO_AGENTS_KEY" "1"})]
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
    (let [{:keys [env log dir]} (fake-env {"ORCH_POLL_INTERVAL_MS" "50"})]
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
    (let [{:keys [env dir]} (fake-env {"ORCH_POLL_INTERVAL_MS" "5000"})]
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
  (let [{:keys [env log dir state]} (fake-env {"ORCH_POLL_INTERVAL_MS" "50"})
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
  (let [{:keys [env dir state]} (fake-env {"ORCH_POLL_INTERVAL_MS" "50"})
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
  (let [{:keys [env log dir state]} (fake-env {"ORCH_POLL_INTERVAL_MS" "50"})
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

;; --- capture never closes a pane ----------------------------------------------------
;; Capture used to make a bounded settle wait and one close attempt on every COMPLETE or
;; FAILED envelope. Both are gone: a pane persists until `close` or `continue` acts on it,
;; because the retain-or-close decision is only makeable after reading the result, and the
;; auto-close was unreliable by construction anyway -- its wait, probe, and close errors
;; were all swallowed and could never alter a result field.
(defn- child-waits [log child]
  (filterv #(= ["agent" "wait" child] (vec (take 3 %))) (calls log)))
;; A local reader: the shared `flag-value` helper is defined further down this file.
(defn- argv-flag [argv flag] (second (drop-while #(not= flag %) argv)))

;; The `settle-to` marker would have satisfied a settle wait had one been issued, so the
;; empty wait log is positive evidence that no capture path waits for settlement any more.
(deftest capture-neither-waits-nor-closes-on-any-path
  (doseq [[label collect-argv] [["fan-in" ["collect" "--any"]] ["single" ["collect"]]]]
    (let [{:keys [env log dir state]} (fake-env {})
          a (start-child! env dir (str label " sibling"))
          b (start-child! env dir (str label " capture"))]
      ;; b published but is still mid-turn -- the race the settle wait once existed for.
      (child-state! state b "status" "working")
      (child-state! state b "settle-to" "idle")
      (publish-child! b)
      (let [proc (apply call! env "task" (if (= "single" label) (conj collect-argv (:task b)) collect-argv))
            res (:result (result proc))]
        (is (zero? (:exit proc)) (str label " " (:err proc)))
        (is (= "COMPLETE" (:status res)) label)
        (is (= (:task b) (:task res)) label)
        (is (empty? (closed-panes log)) label)
        (is (empty? (child-waits log (:child b))) label)
        (is (empty? (child-waits log (:child a))) label)
        ;; The child stayed `working`: nothing waited for it, and it is captured anyway.
        (is (= "working" (str/trim (slurp (str (fs/path state "children" (:child b) "status"))))) label)
        ;; Capture bookkeeping is unchanged.
        (is (= "COMPLETE" (:status (ledger-entry* dir (:task b)))) label)))))

;; A working child is captured and then closed by the explicit verb, which is where the
;; settle wait now lives: the same budget, spent once, on an operator's own command.
(deftest a-working-child-is-captured-then-settled-and-closed-by-the-close-verb
  (let [{:keys [env log dir state]} (fake-env {})
        b (start-child! env dir "captured working, closed later")]
    (child-state! state b "status" "working")
    (child-state! state b "settle-to" "idle")
    (publish-child! b)
    (let [collected (call! env "task" "collect" (:task b))]
      (is (zero? (:exit collected)) (:err collected))
      (is (= "COMPLETE" (get-in (result collected) [:result :status])))
      (is (empty? (closed-panes log))))
    (let [closed (call! env "task" "close" (:task b))
          waits (child-waits log (:child b))]
      (is (zero? (:exit closed)) (:err closed))
      (is (= "closed" (get-in (result closed) [:result :status])))
      (is (= #{(:pane-id b)} (closed-panes log)))
      (is (= 1 (count waits)))
      (is (= (str cli/default-settle-close-ms) (argv-flag (first waits) "--timeout"))))))

(deftest settle-close-budget-defaults-outlast-the-notify-wait-and-honour-the-override
  (is (= 45000 cli/default-settle-close-ms))
  ;; Load-bearing ordering: the 30 s notify wait is what keeps a publishing child `working`,
  ;; so a parent acting the moment its collect returns still meets an unsettled child.
  (is (> cli/default-settle-close-ms cli/default-notify-timeout-ms))
  (is (= 1234 (cli/parse-settle-close "1234")))
  (doseq [raw [nil "" "   " "soon" "0" "-5"]]
    (is (= cli/default-settle-close-ms (cli/parse-settle-close raw)) (pr-str raw)))
  (let [{:keys [env log dir state]} (fake-env {"ORCH_SETTLE_CLOSE_MS" "1500"})
        b (capture-entry! dir (start-child! env dir "settle budget override"))]
    (child-state! state b "status" "working")
    (child-state! state b "settle-to" "idle")
    (let [proc (call! env "task" "close" (:task b))]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "1500" (argv-flag (first (child-waits log (:child b))) "--timeout"))))))

;; `herdr/wait!` is used deliberately instead of `wait-settled!`: without `--until` the wait
;; returns on *any* settled state, so a blocked child ends it immediately rather than
;; burning the budget -- and the pane is still retained, because closure needs idle/done.
;; The fixture honours `--until` exactly as herdr does, so a `wait-settled!` regression
;; makes this child time out and the elapsed bound (not only the argv assertion) fails.
(deftest close-settle-wait-ends-early-on-a-blocked-child
  (let [{:keys [env log dir state]} (fake-env {})
        b (capture-entry! dir (start-child! env dir "settles to blocked"))]
    (child-state! state b "status" "working")
    (child-state! state b "settle-to" "blocked")
    (let [began (System/currentTimeMillis)
          proc (call! env "task" "close" (:task b))
          elapsed (- (System/currentTimeMillis) began)
          res (:result (result proc))]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "retained" (:status res)))
      (is (= "unsettled" (:reason res)))
      (is (= 1 (count (child-waits log (:child b)))))
      (is (empty? (closed-panes log)))
      (is (= "blocked" (str/trim (slurp (str (fs/path state "children" (:child b) "status"))))))
      ;; nowhere near the 45 s budget: the bare wait matched `blocked`
      (is (< elapsed 20000) (str "elapsed=" elapsed)))))

;; Never before the capture: a collect with nothing published is `pending` and issues no
;; settle wait at all, so no budget can be spent on a child that has not answered.
(deftest collect-without-a-published-result-never-waits
  (let [{:keys [env log dir state]} (fake-env {})
        b (start-child! env dir "nothing published yet")]
    (child-state! state b "status" "working")
    (child-state! state b "settle-to" "idle")
    (let [proc (call! env "task" "collect" (:task b))]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "pending" (get-in (result proc) [:result :status])))
      (is (empty? (child-waits log (:child b))))
      (is (empty? (closed-panes log))))))
;; --- advisory parent push at publish time -----------------------------------------
;; `start-child!` spawns from the parent pane `w:p`; the child then publishes with its own
;; injected identity, which is exactly the runtime shape of the push path.
;; No policy is injected: `publish!` reads it from the entry alone. `start-child!` spawns
;; with `task start`, so every entry below is `non-blocking` unless `with-policy!` rewrites
;; it -- which is the only way to represent the value at all.
(defn- child-publish-env [env entry]
  (merge env {"HERDR_ORCH_CHILD" (:child entry) "HERDR_ORCH_TASK" (:task entry)
              "HERDR_ORCH_RESULT" (:result entry)
              "HERDR_PANE_ID" (:pane-id entry)}))
(defn- parent-calls [log command]
  (filterv #(= (conj command "w:p") (vec (take 3 %))) (calls log)))
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
          proc (call! (merge (child-publish-env env entry) {"FAKE_PARENT_STATUS" status})
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
        (is (str/includes? text (str "collect " (:task entry))))
        ;; Capture closes nothing, so the advisory names the whole remaining sequence:
        ;; a parent told only to collect leaves a pane standing every time.
        (is (str/includes? text (str "close " (:task entry))))
        (is (str/includes? text (str "continue " (:task entry)))))
      (is (not-any? #(some #{"--wait"} %) pushes) status)
      ;; Exactly one probe at publish time, on top of the single spawn-side identity read.
      (is (= 2 (count (parent-gets log))) status)
      (is (empty? (parent-waits log)) status)
      ;; The operator toast is retained alongside the push.
      (is (some #(= ["notification" "show"] (vec (take 2 %))) (calls log)) status))))

;; Under `blocking` the parent is already in its own wait loop: no probe, no push at all.
(deftest blocking-publish-never-probes-or-pushes-to-the-parent
  (let [{:keys [env log dir]} (fake-env {})
        entry (patch-entry! dir (start-child! env dir "blocking policy") :waiting-policy "blocking")
        proc (call! (child-publish-env env entry) "task" "publish" "--status" "COMPLETE" "--summary" "done")
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
        proc (call! (merge (child-publish-env env entry) {"FAKE_PARENT_STATUS" "blocked"})
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
;; be prompted, the same ownership boundary `close` enforces for pane closure.
(deftest a-foreign-parent-session-receives-no-push
  (let [{:keys [env log dir]} (fake-env {})
        entry (start-child! env dir "replaced parent")
        proc (call! (merge (child-publish-env env entry) {"FAKE_PARENT_SESSION" "replacement"})
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
          proc (apply call! (merge (child-publish-env env entry) {"FAKE_PARENT_STATUS" status})
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
        proc (call! (merge (child-publish-env env entry) {"FAKE_PARENT_STATUS" "working" "FAKE_PARENT_WAIT" "timeout"})
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
        proc (call! (merge (child-publish-env env entry)
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

;; Publication is committed before the push, so any herdr failure on the push path is
;; reported and nothing more: status and exit code are untouched.
(deftest a-herdr-failure-on-the-push-path-never-affects-publication
  (testing "the probe itself fails"
    (let [{:keys [env log dir]} (fake-env {})
          entry (start-child! env dir "probe fails")
          proc (call! (merge (child-publish-env env entry) {"FAKE_FAIL_AGENT_GET" "w:p"})
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
          proc (call! (merge (child-publish-env env entry) {"FAKE_FAIL_PROMPT" "1"})
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
          proc (call! (merge (child-publish-env env entry) {"FAKE_PARENT_STATUS" "working" "FAKE_PARENT_WAIT" "agent_not_found"})
                      "task" "publish" "--status" "COMPLETE" "--summary" "done" "--notify-timeout" "500")
          res (:result (result proc))]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "COMPLETE" (:status res)))
      (is (= "error" (get-in res [:parent-push :push])))
      (is (= "agent_not_found" (get-in res [:parent-push :reason])))
      (is (empty? (parent-prompts log)))))
  ;; The toast is emitted before the push and its failure is already tolerated; it must not
  ;; become fatal now that the entry, rather than the environment, decides it happens.
  (testing "the operator toast itself fails"
    (let [{:keys [env dir]} (fake-env {"FAKE_NOTIFY_FAIL" "1"})
          entry (start-child! env dir "toast fails")
          proc (call! (child-publish-env env entry) "task" "publish" "--status" "COMPLETE" "--summary" "done")
          res (:result (result proc))]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "COMPLETE" (:status res)))
      (is (fs/exists? (:result entry)))
      (is (some? (get-in res [:notification :notification-error])))))
  ;; A publication whose task has no ledger entry has no policy, so it is uniformly silent:
  ;; no toast, no probe, no push -- not a `skipped` push with a reason.
  (testing "a publication with no ledger entry is silent"
    (let [{:keys [env log dir]} (fake-env {})
          target (str (fs/path dir "orphan.result"))
          proc (call! (merge env {"HERDR_ORCH_CHILD" "child" "HERDR_ORCH_TASK" "task"
                                  "HERDR_ORCH_RESULT" target})
                      "task" "publish" "--status" "COMPLETE" "--summary" "done")
          res (:result (result proc))]
      (is (zero? (:exit proc)) (:err proc))
      (is (fs/exists? target))
      (is (nil? (:parent-push res)))
      (is (nil? (:notification res)))
      (is (empty? (parent-prompts log)))
      (is (not-any? #(= ["notification" "show"] (vec (take 2 %))) (calls log))))))

;; --- `--task` identity override ----------------------------------------------------
;; A further round of the same child. `continue` writes exactly this shape, but publish's
;; contract does not depend on who wrote the entry, so these fixtures write it directly and
;; stay independent of the continue verb.
(defn- extra-round! [dir entry policy]
  (let [task (str (java.util.UUID/randomUUID))
        next-entry (-> (dissoc entry :captured-at :envelope :progress)
                       (assoc :task task
                              :result (str (fs/path dir ".tmp" "herdr-orch" (str task "-round.result")))
                              :waiting-policy policy :continues (:task entry)
                              :status "prompted" :created-at "2999-01-01T00:00:00Z"))]
    (spit (str (fs/path dir ".tmp" "herdr-orch" "ledger" (str task ".json"))) (json/generate-string next-entry))
    next-entry))

;; The named entry -- not the injected environment -- supplies both the result path and the
;; waiting policy, so a child whose env still names round one publishes round two correctly
;; and under round two's own policy.
(deftest publish-task-override-resolves-result-and-policy-from-the-named-entry
  (let [{:keys [env log dir]} (fake-env {})
        first-round (start-child! env dir "round one")
        second-round (extra-round! dir first-round "blocking")
        proc (call! (child-publish-env env first-round) "task" "publish" "--task" (:task second-round)
                    "--status" "COMPLETE" "--summary" "round two")
        res (:result (result proc))]
    (is (zero? (:exit proc)) (:err proc))
    (is (= (:task second-round) (:task res)))
    ;; `HERDR_ORCH_RESULT` still names round one's file and is ignored.
    (is (= (:result second-round) (:result res)))
    (is (fs/exists? (:result second-round)))
    (is (not (fs/exists? (:result first-round))))
    (is (str/includes? (slurp (:result second-round)) (str "TASK: " (:task second-round))))
    ;; This round is `blocking` though the spawn was not: the entry decides, so there is
    ;; no toast and no push.
    (is (nil? (:parent-push res)))
    (is (nil? (:notification res)))
    (is (empty? (parent-prompts log)))))

;; `HERDR_ORCH_CHILD` is the boundary a `--task` can never cross: the name is stable across
;; a child's rounds, so a mistyped uuid can only ever reach the caller's own rounds.
(deftest publish-and-progress-task-override-refuse-another-childs-assignment
  (let [{:keys [env dir]} (fake-env {})
        mine (start-child! env dir "my round")
        theirs (start-child! env dir "sibling round")
        published (call! (child-publish-env env mine) "task" "publish" "--task" (:task theirs)
                         "--status" "COMPLETE" "--summary" "spoofed")
        reported (call! (child-progress-env env mine) "task" "progress" "--task" (:task theirs) "--summary" "spoofed")]
    (is (= 1 (:exit published)))
    (is (re-find #"mismatch" (:out published)))
    (is (= 1 (:exit reported)))
    (is (re-find #"mismatch" (:out reported)))
    (is (not (fs/exists? (:result theirs))))
    (is (not (fs/exists? (:result mine))))
    (is (nil? (:progress (ledger-entry* dir (:task theirs)))))))

;; An explicit `--task` naming no entry is a caller error, and the *silent* fallback is the
;; damage: `result` would resolve to the injected `HERDR_ORCH_RESULT`, so this round's
;; envelope would land in the child's original round file under a foreign `TASK:` -- and
;; publication being one-shot, that round could then never publish at all. Refuse before
;; the write. The env-derived hand-driven publish (SKILL.md § Manual fallback) keeps working.
(deftest publish-refuses-an-explicit-task-that-names-no-ledger-entry
  (let [{:keys [env dir]} (fake-env {})
        entry (start-child! env dir "round one")
        unknown (str (java.util.UUID/randomUUID))
        proc (call! (child-publish-env env entry) "task" "publish" "--task" unknown
                    "--status" "COMPLETE" "--summary" "typo in the uuid")]
    (is (= 1 (:exit proc)))
    (is (str/includes? (:out proc) "--task names no ledger entry"))
    ;; The real round is untouched and can still publish: the mistyped flag burned nothing.
    (is (not (fs/exists? (:result entry))))
    (let [ok (call! (child-publish-env env entry) "task" "publish" "--status" "COMPLETE" "--summary" "the real result")]
      (is (zero? (:exit ok)) (:out ok))
      (is (str/includes? (slurp (:result entry)) (str "TASK: " (:task entry)))))))

;; The asymmetry that guard must preserve: an *env-derived* task with no entry is the
;; legitimate hand-driven publish, and stays silent-but-working -- no entry means no policy,
;; so no toast and no push, but the RESULT is still written.
(deftest a-hand-driven-publish-with-no-ledger-entry-still-writes-its-result
  (let [{:keys [env log dir]} (fake-env {})
        result-path (str (fs/path dir "hand-driven.result"))
        proc (call! (merge env {"HERDR_ORCH_CHILD" "hand-driven-child"
                                "HERDR_ORCH_TASK" (str (java.util.UUID/randomUUID))
                                "HERDR_ORCH_RESULT" result-path})
                    "task" "publish" "--status" "COMPLETE" "--summary" "by hand")
        res (:result (result proc))]
    (is (zero? (:exit proc)) (:out proc))
    (is (= result-path (:result res)))
    (is (fs/exists? result-path))
    (is (nil? (:parent-push res)))
    (is (nil? (:notification res)))
    (is (empty? (parent-prompts log)))))

(deftest progress-task-override-targets-the-named-round
  (let [{:keys [env dir]} (fake-env {})
        first-round (start-child! env dir "progress round one")
        second-round (extra-round! dir first-round "non-blocking")
        proc (call! (child-progress-env env first-round) "task" "progress" "--task" (:task second-round)
                    "--summary" "round two phase one")]
    (is (zero? (:exit proc)) (:err proc))
    (is (= (:task second-round) (get-in (result proc) [:result :task])))
    (is (= "round two phase one" (get-in (ledger-entry* dir (:task second-round)) [:progress :summary])))
    (is (nil? (:progress (ledger-entry* dir (:task first-round)))))))

;; A stale identity fails loudly and names the one open round it could have meant. It is
;; never resolved automatically: the open round's RESULT stays unwritten.
(deftest a-stale-publish-identity-names-the-open-round-without-resolving-it
  (let [{:keys [env dir]} (fake-env {})
        first-round (start-child! env dir "already published round")
        ok (call! (child-publish-env env first-round) "task" "publish" "--status" "COMPLETE" "--summary" "one")
        second-round (extra-round! dir first-round "non-blocking")
        retry (call! (child-publish-env env first-round) "task" "publish" "--status" "COMPLETE" "--summary" "two")]
    (is (zero? (:exit ok)) (:err ok))
    (is (= 1 (:exit retry)))
    (is (str/includes? (:out retry) "RESULT already exists"))
    (is (str/includes? (:out retry) (str "--task " (:task second-round))))
    (is (not (fs/exists? (:result second-round))))))

;; Ambiguity earns no hint: with two open rounds there is no single fix to name.
(deftest a-stale-publish-identity-hints-only-when-one-open-round-exists
  (let [{:keys [env dir]} (fake-env {})
        first-round (start-child! env dir "ambiguous rounds")
        ok (call! (child-publish-env env first-round) "task" "publish" "--status" "COMPLETE" "--summary" "one")
        _ (extra-round! dir first-round "non-blocking")
        _ (extra-round! dir first-round "non-blocking")
        retry (call! (child-publish-env env first-round) "task" "publish" "--status" "COMPLETE" "--summary" "two")]
    (is (zero? (:exit ok)) (:err ok))
    (is (= 1 (:exit retry)))
    (is (str/includes? (:out retry) "RESULT already exists"))
    (is (not (str/includes? (:out retry) "--task ")))))

;; --- `oh task continue` and `oh task close` ------------------------------------------
;; Both verbs act on a captured round, and every fixture below marks its entry captured
;; directly rather than going through `collect`: the guards read `:captured-at` and
;; `:status` and nothing else, and a herdr-call-free setup keeps each test's call-log
;; assertions about the verb under test alone.
(defn- closed-entry [dir entry] (ledger-entry* dir (:task entry)))
(defn- continue! [env dir prior & argv]
  (let [proc (apply call! env "task" "continue" (:task prior) argv)]
    {:proc proc :entry (when (zero? (:exit proc))
                         (ledger-entry* dir (get-in (result proc) [:result :task])))}))

(deftest continue-allocates-a-fresh-round-inheriting-the-childs-identity
  (let [{:keys [env log dir prompt-file]} (fake-env {})
        prior (capture-entry! dir (start-child! env dir "round one"))
        {:keys [proc entry]} (continue! env dir prior "--task" "round two assignment")
        prompt (slurp prompt-file)]
    (is (zero? (:exit proc)) (:err proc))
    (is (not= (:task prior) (:task entry)))
    (is (= (:task prior) (:continues entry)))
    ;; Inherited, never re-derived: the child name belongs to the spawn that created it and
    ;; bears no relation to this round's own uuid.
    (is (= (:child prior) (:child entry)))
    (is (= (:pane-id prior) (:pane-id entry)))
    (is (= (:label prior) (:label entry)))
    (is (= (:persona-path prior) (:persona-path entry)))
    (is (not= (:result prior) (:result entry)))
    (is (not (fs/exists? (:result entry))))
    (is (nil? (:captured-at entry)))
    ;; A fresh round is an ordinary uncaptured entry: `prune` and `collect --any` need no
    ;; special case for it.
    (is (= "prompted" (:status entry)))
    (is (some? (:prompted-at entry)))
    (is (= "dispatched" (get-in entry [:dispatch :status])))
    ;; Prompted in place: the existing pane, no new placement of any kind, no new agent.
    (is (some #(= ["agent" "prompt" (:child prior)] (vec (take 3 %))) (calls log)))
    (is (= 1 (count (filter #(#{["pane" "split"] ["tab" "create"]} (vec (take 2 %))) (calls log)))))
    (is (= 1 (count (filter #(= ["agent" "start"] (vec (take 2 %))) (calls log)))))
    (is (str/includes? prompt "round two assignment"))))

;; The prompt carries assignment content and one ready-to-run command whose identity is
;; interpolated, never described. Protocol the CLI can guarantee is not restated.
(deftest continue-prompt-names-the-round-and-its-exact-publish-command
  (let [{:keys [env dir prompt-file]} (fake-env {})
        prior (capture-entry! dir (start-child! env dir "round one"))
        {:keys [proc entry]} (continue! env dir prior "--task" "re-review the changed files")
        prompt (slurp prompt-file)]
    (is (zero? (:exit proc)) (:err proc))
    (is (str/includes? prompt (str "TASK=" (:task entry))))
    (is (str/includes? prompt (str "RESULT=" (:result entry))))
    (is (str/includes? prompt (str "task publish --task " (:task entry) " --status COMPLETE")))
    (is (str/includes? prompt "Follow-on round"))
    (is (str/includes? prompt "This is a new assignment, not a revision of your last one"))
    (is (str/includes? prompt "Revalidate every prior finding and every mutable baseline"))
    ;; Every publication instruction the round emits carries `--task`, not only the
    ;; COMPLETE one: the child's injected HERDR_ORCH_TASK still names its original,
    ;; already-published round, so a bare `--status BLOCKED` could not publish at all and
    ;; the failure path would break exactly when it is needed.
    (is (str/includes? prompt (str "`--task " (:task entry) " --status BLOCKED` (dependency)")))
    (is (str/includes? prompt (str "`--task " (:task entry) " --status FAILED` (unrecoverable)")))
    (is (not (re-find #"`--status (BLOCKED|FAILED)`" prompt)))
    ;; Nothing lifecycle-related: the child never learns it was continued or retained.
    (is (not (str/includes? prompt "continue")))
    (is (not (str/includes? prompt "resident")))))

;; `--wait` blocks like `run`, the default is non-blocking like `start`, and the round's own
;; policy -- not the spawn's -- drives both the prompt's progress clause and `publish!`.
(deftest continue-round-policy-governs-the-prompt-and-the-publish-path
  (testing "a blocking spawn continued without --wait runs the new round non-blocking"
    (let [{:keys [env log dir prompt-file]} (fake-env {})
          run-proc (call! env "task" "run" "worker" "--task" "round one" "--timeout" "200")
          prior (ledger-entry* dir (get-in (result run-proc) [:result :task]))
          {:keys [proc entry]} (continue! env dir prior "--task" "round two")
          prompt (slurp prompt-file)]
      (is (= "blocking" (:waiting-policy prior)))
      (is (zero? (:exit proc)) (:err proc))
      (is (= "non-blocking" (:waiting-policy entry)))
      (is (str/includes? prompt (str "task progress --task " (:task entry) " --summary")))
      (let [published (call! (merge (child-publish-env env prior) {"FAKE_PARENT_STATUS" "idle"})
                             "task" "publish" "--task" (:task entry) "--status" "COMPLETE" "--summary" "round two done")
            res (:result (result published))]
        (is (zero? (:exit published)) (:err published))
        (is (= "sent" (get-in res [:parent-push :push])))
        (is (some? (:notification res)))
        (is (some #(and (= ["agent" "prompt" "w:p"] (vec (take 3 %)))
                        (str/includes? (nth % 3 "") (str "collect " (:task entry))))
                  (calls log))))))
  (testing "a non-blocking spawn continued with --wait runs the new round blocking"
    (let [{:keys [env log dir prompt-file]} (fake-env {"FAKE_WAIT" "idle-forever" "ORCH_POLL_INTERVAL_MS" "20"})
          prior (capture-entry! dir (start-child! env dir "round one"))
          proc (call! env "task" "continue" (:task prior) "--task" "round two" "--wait" "--timeout" "200")
          res (:result (result proc))
          entry (ledger-entry* dir (:task res))
          prompt (slurp prompt-file)]
      (is (= "non-blocking" (:waiting-policy prior)))
      (is (zero? (:exit proc)) (:err proc))
      ;; `--wait` blocks in the same capture loop `run` uses, so an unpublished round is
      ;; the ordinary timeout outcome rather than an immediate entry.
      (is (= "pending" (:status res)))
      (is (= "blocking" (:waiting-policy entry)))
      (is (not (str/includes? prompt "task progress")))
      (let [published (call! (merge (child-publish-env env prior) {"FAKE_PARENT_STATUS" "idle"})
                             "task" "publish" "--task" (:task entry) "--status" "COMPLETE" "--summary" "round two done")
            pub-res (:result (result published))]
        (is (zero? (:exit published)) (:err published))
        (is (nil? (:parent-push pub-res)))
        (is (nil? (:notification pub-res)))
        (is (not-any? #(= ["notification" "show"] (vec (take 2 %))) (calls log)))))))

;; The new entry is persisted before the pane is prompted -- a failed `agent prompt` may
;; still have delivered its text, so the entry must exist -- but a prompt that throws then
;; retires it, the way `safe-cleanup!` retires a dead spawn. Left as `continuing` it would
;; be uncaptured, non-terminal, and newest, which wedges the child: `close` and `continue`
;; refuse on both rounds, `prune` refuses while the child is live, and `collect --any`
;; counts it forever. No pane is touched: it predates this verb and hosts a healthy child.
(deftest continue-retires-its-entry-when-the-prompt-fails
  (let [{:keys [env log dir]} (fake-env {})
        prior (capture-entry! dir (start-child! env dir "round one"))
        closes-before (count (filter #(= ["pane" "close"] (vec (take 2 %))) (calls log)))
        ;; The failure is injected on the continuation's own prompt only; the spawn above
        ;; must succeed for there to be a round to continue.
        proc (call! (merge env {"FAKE_FAIL_PROMPT" "1"}) "task" "continue" (:task prior) "--task" "round two")
        rounds-of (fn [] (filterv #(= (:task prior) (:continues %))
                                  (for [f (fs/list-dir (fs/path dir ".tmp" "herdr-orch" "ledger"))
                                        :when (and (fs/regular-file? f) (str/ends-with? (fs/file-name f) ".json"))]
                                    (ledger-entry* dir (str/replace (fs/file-name f) #"\.json$" "")))))
        rounds (rounds-of)]
    (is (= 1 (:exit proc)))
    (is (= 1 (count rounds)) "the entry survives the failure rather than being deleted")
    (is (= "failed" (:status (first rounds))))
    (is (= "continue-prompt" (:failure-phase (first rounds))))
    (is (some? (:failed-at (first rounds))))
    (is (nil? (:prompted-at (first rounds))))
    (is (= closes-before (count (filter #(= ["pane" "close"] (vec (take 2 %))) (calls log))))
        "the child's own pane is never closed by a failed continuation")
    ;; Recoverable: the retry succeeds, so a failed continuation costs a round, not a child.
    (let [retry (call! env "task" "continue" (:task prior) "--task" "round two, retried")]
      (is (zero? (:exit retry)) (:out retry))
      (is (= 2 (count (rounds-of))))
      (is (= "prompted" (:status (ledger-entry* dir (get-in (result retry) [:result :task]))))))
    ;; ...and so is closing the prior round instead: a retired round is not "newest".
    (is (= 1 (count (filter #(and (= "failed" (:status %)) (= "continue-prompt" (:failure-phase %)))
                            (rounds-of)))))))

;; The same retirement seen from `close`: with only the failed continuation in between, the
;; prior round is still the child's newest *live* round and stays closable.
(deftest a-failed-continuation-round-does-not-block-closing-the-prior-round
  (let [{:keys [env log dir]} (fake-env {})
        prior (capture-entry! dir (start-child! env dir "round one"))
        failed (call! (merge env {"FAKE_FAIL_PROMPT" "1"}) "task" "continue" (:task prior) "--task" "round two")
        proc (call! env "task" "close" (:task prior))
        res (:result (result proc))]
    (is (= 1 (:exit failed)))
    (is (zero? (:exit proc)) (:out proc))
    (is (= "closed" (:status res)))
    (is (= #{(:pane-id prior)} (closed-panes log)))
    (is (some? (:closed-at (closed-entry dir prior))))))

;; ...and from the sweep, which groups by child and must not let a retired round stand in
;; for the child's real, closable one.
(deftest close-settled-ignores-a-retired-continuation-round
  (let [{:keys [env log dir]} (fake-env {})
        prior (capture-entry! dir (start-child! env dir "round one"))
        failed (call! (merge env {"FAKE_FAIL_PROMPT" "1"}) "task" "continue" (:task prior) "--task" "round two")
        proc (call! env "task" "close" "--settled")
        outcomes (:result (result proc))]
    (is (= 1 (:exit failed)))
    (is (zero? (:exit proc)) (:err proc))
    (is (= [(:task prior)] (mapv :task outcomes)))
    (is (= ["closed"] (mapv :status outcomes)))
    (is (= #{(:pane-id prior)} (closed-panes log)))))

(deftest continue-is-root-only-and-refuses-before-any-mutation
  (let [{:keys [env log dir]} (fake-env {})
        prior (capture-entry! dir (start-child! env dir "round one"))
        before (count (calls log))
        proc (call! (merge env {"HERDR_ORCH_PERSONA" "worker" "HERDR_ORCH_SPAWNS" "scout"})
                    "task" "continue" (:task prior) "--task" "round two")]
    (is (= 1 (:exit proc)))
    (is (re-find #"root-only" (:out proc)))
    (is (= before (count (calls log))) "refused before preflight and every herdr call")
    (is (= 1 (count (filter #(and (fs/regular-file? %) (str/ends-with? (fs/file-name %) ".json"))
                            (fs/list-dir (fs/path dir ".tmp" "herdr-orch" "ledger"))))))))

(deftest continue-refuses-an-unusable-prior-round
  (let [{:keys [env log dir]} (fake-env {})]
    (testing "an uncaptured prior round"
      (let [prior (start-child! env dir "never captured")
            proc (call! env "task" "continue" (:task prior) "--task" "round two")]
        (is (= 1 (:exit proc)))
        (is (re-find #"not captured with a validated envelope" (:out proc)))))
    (testing "an invalid capture"
      (let [prior (patch-entry! dir (capture-entry! dir (start-child! env dir "invalid capture")) :status "invalid")
            proc (call! env "task" "continue" (:task prior) "--task" "round two")]
        (is (= 1 (:exit proc)))
        (is (re-find #"not captured with a validated envelope" (:out proc)))))
    (testing "a pruned (terminal failed) entry"
      (let [prior (patch-entry! dir (start-child! env dir "pruned") :status "failed" :pruned-at "2026-01-01T00:00:00Z")
            proc (call! env "task" "continue" (:task prior) "--task" "round two")]
        (is (= 1 (:exit proc)))
        (is (re-find #"not captured with a validated envelope" (:out proc)))))
    (testing "a foreign owner"
      (let [prior (patch-entry! dir (capture-entry! dir (start-child! env dir "foreign")) :parent-session "someone-else")
            proc (call! env "task" "continue" (:task prior) "--task" "round two")]
        (is (= 1 (:exit proc)))
        (is (re-find #"does not own" (:out proc)))))
    ;; Nothing was continued: every prompt in the log belongs to one of the four spawns.
    (is (= 4 (count (filter #(= ["agent" "prompt"] (vec (take 2 %))) (calls log)))))))

;; Resuming a captured BLOCKED round is exactly what this verb is for: the resumption needs
;; a fresh task anyway, and envelope-status BLOCKED is not agent-status `blocked`.
(deftest continue-accepts-a-captured-blocked-round
  (let [{:keys [env dir]} (fake-env {})
        prior (patch-entry! dir (capture-entry! dir (start-child! env dir "blocked round")) :status "BLOCKED")
        {:keys [proc entry]} (continue! env dir prior "--task" "here is the missing dependency")]
    (is (zero? (:exit proc)) (:err proc))
    (is (= (:task prior) (:continues entry)))))

(deftest continue-refuses-unless-the-live-child-matches-and-is-settled
  (let [{:keys [env dir state]} (fake-env {})]
    (testing "the child has vanished"
      (let [prior (capture-entry! dir (start-child! env dir "vanished"))]
        (child-state! state prior "gone" "")
        (let [proc (call! env "task" "continue" (:task prior) "--task" "round two")]
          (is (= 1 (:exit proc)))
          (is (re-find #"absent from the agent list" (:out proc))))))
    (testing "the recorded pane is not the child's pane"
      (let [prior (capture-entry! dir (start-child! env dir "pane moved"))]
        (child-state! state prior "pane" "w:somewhere-else")
        (let [proc (call! env "task" "continue" (:task prior) "--task" "round two")]
          (is (= 1 (:exit proc)))
          (is (re-find #"not this child's pane" (:out proc))))))
    ;; Prompt text delivered to a blocked agent lands in its approval UI, not a new round.
    (testing "the child is not settled"
      (let [prior (capture-entry! dir (start-child! env dir "blocked agent"))]
        (child-state! state prior "status" "blocked")
        ;; The bounded settle wait cannot rescue it, so the guard is what refuses.
        (child-state! state prior "settle-to" "timeout")
        (let [proc (call! env "task" "continue" (:task prior) "--task" "round two")]
          (is (= 1 (:exit proc)))
          (is (re-find #"not settled" (:out proc))))))
    (testing "the agent listing is unusable"
      (let [prior (capture-entry! dir (start-child! env dir "liveness unknown"))
            proc (call! (merge env {"FAKE_AGENT_LIST_NO_AGENTS_KEY" "1"}) "task" "continue" (:task prior) "--task" "round two")]
        (is (= 1 (:exit proc)))
        (is (re-find #"liveness is unknown" (:out proc)))))))

(deftest continue-refuses-a-second-open-round-or-a-stale-one
  (let [{:keys [env dir]} (fake-env {})
        prior (capture-entry! dir (start-child! env dir "round one"))]
    (testing "an uncaptured round already names this child"
      (let [_ (extra-round! dir prior "non-blocking")
            proc (call! env "task" "continue" (:task prior) "--task" "round three")]
        (is (= 1 (:exit proc)))
        (is (re-find #"uncaptured round already names this child" (:out proc)))))
    (testing "a newer captured round exists, so the prior one is stale"
      (let [newer (capture-entry! dir (last (sort-by :created-at
                                                     (filterv #(= (:task prior) (:continues %))
                                                              (for [f (fs/list-dir (fs/path dir ".tmp" "herdr-orch" "ledger"))
                                                                    :when (and (fs/regular-file? f) (str/ends-with? (fs/file-name f) ".json"))]
                                                                (ledger-entry* dir (str/replace (fs/file-name f) #"\.json$" "")))))))
            proc (call! env "task" "continue" (:task prior) "--task" "round three")]
        (is (some? newer))
        (is (= 1 (:exit proc)))
        (is (re-find #"newer round exists" (:out proc)))))))

(deftest continue-refuses-a-closed-child-and-an-arity-error
  (let [{:keys [env dir]} (fake-env {})
        prior (capture-entry! dir (start-child! env dir "closed already"))
        closed (call! env "task" "close" (:task prior))
        proc (call! env "task" "continue" (:task prior) "--task" "round two")
        missing (call! env "task" "continue")]
    (is (zero? (:exit closed)) (:err closed))
    (is (= 1 (:exit proc)))
    (is (re-find #"already closed" (:out proc)))
    (is (= 1 (:exit missing)))
    (is (re-find #"requires a full task uuid" (:out missing)))))

;; A continuation entry is an ordinary entry everywhere else: fan-in candidacy and prune
;; treat it exactly like a spawned round.
(deftest a-continued-round-is-an-ordinary-collect-any-candidate
  (let [{:keys [env dir]} (fake-env {})
        prior (capture-entry! dir (start-child! env dir "round one"))
        {:keys [entry]} (continue! env dir prior "--task" "round two")]
    (publish-child! entry)
    (let [proc (call! env "task" "collect" "--any")
          res (:result (result proc))]
      (is (zero? (:exit proc)) (:err proc))
      (is (= (:task entry) (:task res)))
      (is (= "COMPLETE" (:status res)))
      (is (= 0 (:remaining res))))))

;; --- `oh task close` ----------------------------------------------------------------
;; Capture closes nothing, so this verb is the whole closure path.
;; A raw child directory in the fake herdr state: models an agent this ledger never
;; recorded, e.g. a replacement occupying a pane a vanished child once held.
(defn- fake-child! [state name pane]
  (let [dir (fs/path state "children" name)]
    (fs/create-dirs dir)
    (spit (str (fs/path dir "pane")) pane)))

(deftest close-closes-a-settled-captured-child-and-records-closed-at
  (let [{:keys [env log dir]} (fake-env {})
        entry (capture-entry! dir (start-child! env dir "settled and captured"))
        proc (call! env "task" "close" (:task entry))
        res (:result (result proc))
        waits (child-waits log (:child entry))]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "closed" (:status res)))
    (is (= (:task entry) (:task res)))
    (is (= #{(:pane-id entry)} (closed-panes log)))
    (is (some? (:closed-at (closed-entry dir entry))))
    ;; One bounded settle wait at the relocated budget, and deliberately no `--until`:
    ;; the bare form returns on any settled state, and the listing decides the rest.
    (is (= 1 (count waits)))
    (is (= (str cli/default-settle-close-ms) (argv-flag (first waits) "--timeout")))
    (is (not-any? #{"--until"} (first waits)))
    ;; The captured status is untouched: `:closed-at` is a marker, not a lifecycle state.
    (is (= "COMPLETE" (:status (closed-entry dir entry))))))

(deftest close-refuses-an-uncaptured-entry
  (let [{:keys [env log dir]} (fake-env {})
        entry (start-child! env dir "never captured")
        proc (call! env "task" "close" (:task entry))]
    (is (= 1 (:exit proc)))
    (is (re-find #"not captured" (:out proc)))
    (is (empty? (closed-panes log)))
    (is (nil? (:closed-at (closed-entry dir entry))))))

;; Two captured rounds of one child: only the newest may be closed, because only the
;; newest round's `:pane-id` is a current claim on that pane.
(deftest close-refuses-a-stale-round
  (let [{:keys [env log dir]} (fake-env {})
        first-round (capture-entry! dir (start-child! env dir "round one"))
        _ (capture-entry! dir (extra-round! dir first-round "non-blocking"))
        proc (call! env "task" "close" (:task first-round))]
    (is (= 1 (:exit proc)))
    (is (re-find #"newer round exists" (:out proc)))
    (is (empty? (closed-panes log)))
    (is (nil? (:closed-at (closed-entry dir first-round))))))

(deftest close-refuses-when-an-uncaptured-newer-round-names-the-child
  (let [{:keys [env log dir]} (fake-env {})
        first-round (capture-entry! dir (start-child! env dir "round one, still busy"))
        _ (extra-round! dir first-round "non-blocking")
        proc (call! env "task" "close" (:task first-round))]
    (is (= 1 (:exit proc)))
    (is (re-find #"uncaptured newer round" (:out proc)))
    (is (empty? (closed-panes log)))))

;; Ownership is `prune!`'s rule exactly, with no dead-owner exception. The third case is the
;; regression pin for that removal: a recorded transcript path that no longer exists is *not*
;; evidence the owner is gone -- pi keeps session transcripts on disk indefinitely, so the
;; file outlives the agent and the old test never fired for a real pi parent.
(deftest close-refuses-any-caller-that-is-not-the-recorded-owner
  (let [{:keys [env log dir]} (fake-env {})
        live-session (str (fs/path dir "live-owner-session.jsonl"))
        _ (spit live-session "{}")
        entry (patch-entry! dir (capture-entry! dir (start-child! env dir "owned elsewhere"))
                            :parent-session live-session)]
    (testing "a caller that is not the recorded owner"
      (let [proc (call! env "task" "close" (:task entry))]
        (is (= 1 (:exit proc)))
        (is (re-find #"does not own" (:out proc)))))
    (testing "a caller whose own identity cannot be resolved owns nothing"
      (let [proc (call! (merge env {"FAKE_FAIL_AGENT_GET" "w:p"}) "task" "close" (:task entry))]
        (is (= 1 (:exit proc)))
        (is (re-find #"does not own" (:out proc)))))
    (testing "an owner whose recorded transcript path is gone is still not this caller's"
      (let [orphan (patch-entry! dir entry :parent-session (str (fs/path dir "vanished-owner-session.jsonl")))
            proc (call! env "task" "close" (:task orphan))]
        (is (not (fs/exists? (:parent-session orphan))))
        (is (= 1 (:exit proc)))
        (is (re-find #"does not own" (:out proc)))))
    (testing "nor is one recorded as an opaque session id"
      (let [opaque (patch-entry! dir entry :parent-session "opaque-session-id")
            proc (call! env "task" "close" (:task opaque))]
        (is (= 1 (:exit proc)))
        (is (re-find #"does not own" (:out proc)))))
    (is (empty? (closed-panes log)))
    (is (nil? (:closed-at (closed-entry dir entry))))))

;; Absence of the name proves only that the name was released. The recorded pane may host
;; an unrelated replacement, so a vanished child is reported and nothing is touched.
(deftest close-reports-gone-and-never-closes-a-replaced-occupant
  (let [{:keys [env log dir state]} (fake-env {})
        entry (capture-entry! dir (start-child! env dir "vanished child"))]
    (child-state! state entry "gone" "")
    (fake-child! state "unrelated-replacement" (:pane-id entry))
    (let [proc (call! env "task" "close" (:task entry))
          res (:result (result proc))]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "gone" (:status res)))
      (is (= "child-absent" (:reason res)))
      (is (empty? (closed-panes log)))
      (is (nil? (:closed-at (closed-entry dir entry)))))))

(deftest close-refuses-when-the-recorded-pane-is-not-the-childs-pane
  (let [{:keys [env log dir state]} (fake-env {})
        entry (capture-entry! dir (start-child! env dir "pane moved"))]
    (child-state! state entry "pane" "w:somewhere-else")
    (let [proc (call! env "task" "close" (:task entry))]
      (is (= 1 (:exit proc)))
      (is (re-find #"not this child's pane" (:out proc)))
      (is (empty? (closed-panes log)))
      (is (nil? (:closed-at (closed-entry dir entry)))))))

(deftest close-retains-an-unsettled-child-and-stays-retryable
  (let [{:keys [env log dir state]} (fake-env {})
        entry (capture-entry! dir (start-child! env dir "still working"))]
    (child-state! state entry "status" "working")
    (child-state! state entry "settle-to" "timeout")
    (let [proc (call! env "task" "close" (:task entry))
          res (:result (result proc))]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "retained" (:status res)))
      (is (= "unsettled" (:reason res)))
      (is (= "working" (:agent-status res)))
      (is (empty? (closed-panes log)))
      (is (nil? (:closed-at (closed-entry dir entry)))))
    ;; Retryable: nothing about the refusal is recorded, so a settled retry closes.
    (child-state! state entry "settle-to" "idle")
    (let [proc (call! env "task" "close" (:task entry))]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "closed" (get-in (result proc) [:result :status])))
      (is (= #{(:pane-id entry)} (closed-panes log))))))

(deftest close-refuses-an-already-closed-round
  (let [{:keys [env log dir]} (fake-env {})
        entry (capture-entry! dir (start-child! env dir "closed twice"))
        first-pass (call! env "task" "close" (:task entry))
        second-pass (call! env "task" "close" (:task entry))]
    (is (zero? (:exit first-pass)) (:err first-pass))
    (is (= 1 (:exit second-pass)))
    (is (re-find #"already closed" (:out second-pass)))
    (is (= 1 (count (filter #(= ["pane" "close"] (vec (take 2 %))) (calls log)))))))

(deftest close-refuses-when-the-agent-list-is-unusable
  (let [{:keys [env log dir]} (fake-env {})
        entry (capture-entry! dir (start-child! env dir "liveness unknown"))
        proc (call! (merge env {"FAKE_AGENT_LIST_NO_AGENTS_KEY" "1"}) "task" "close" (:task entry))]
    (is (= 1 (:exit proc)))
    (is (re-find #"liveness is unknown" (:out proc)))
    (is (empty? (closed-panes log)))
    (is (nil? (:closed-at (closed-entry dir entry))))))

(deftest close-argument-arity
  (let [{:keys [env dir]} (fake-env {})
        entry (capture-entry! dir (start-child! env dir "arity"))
        missing (call! env "task" "close")
        both (call! env "task" "close" "--settled" (:task entry))]
    (is (= 1 (:exit missing)))
    (is (re-find #"requires a full task uuid" (:out missing)))
    (is (= 1 (:exit both)))
    (is (re-find #"takes no task argument" (:out both)))))

;; The sweep groups by child across continuation lineage and considers only each child's
;; newest round, so two captured rounds of one child yield exactly one close attempt.
(deftest close-settled-sweeps-each-childs-newest-round-once
  (let [{:keys [env log dir]} (fake-env {})
        a-first (capture-entry! dir (start-child! env dir "child a, round one"))
        a-second (capture-entry! dir (extra-round! dir a-first "non-blocking"))
        b (capture-entry! dir (start-child! env dir "child b"))
        uncaptured (start-child! env dir "child c, still working")
        proc (call! env "task" "close" "--settled")
        outcomes (:result (result proc))]
    (is (zero? (:exit proc)) (:err proc))
    (is (= 2 (count outcomes)))
    (is (= #{(:task a-second) (:task b)} (set (map :task outcomes))))
    (is (= #{"closed"} (set (map :status outcomes))))
    ;; One attempt per child: the stale round is neither closed nor retried.
    (is (= #{(:pane-id a-second) (:pane-id b)} (closed-panes log)))
    (is (= 1 (count (child-waits log (:child a-first)))))
    (is (some? (:closed-at (closed-entry dir a-second))))
    (is (nil? (:closed-at (closed-entry dir a-first))))
    (is (nil? (:closed-at (closed-entry dir uncaptured))))))

;; A per-child refusal is reported in the array rather than abandoning the rest of the
;; sweep, and it mutates nothing.
(deftest close-settled-reports-per-child-outcomes-without-aborting
  (let [{:keys [env log dir state]} (fake-env {})
        gone (capture-entry! dir (start-child! env dir "child that vanished"))
        moved (capture-entry! dir (start-child! env dir "child whose pane moved"))
        ok (capture-entry! dir (start-child! env dir "child that closes"))]
    (child-state! state gone "gone" "")
    (child-state! state moved "pane" "w:somewhere-else")
    (let [proc (call! env "task" "close" "--settled")
          by-task (into {} (map (juxt :task identity)) (:result (result proc)))]
      (is (zero? (:exit proc)) (:err proc))
      (is (= 3 (count by-task)))
      (is (= "gone" (get-in by-task [(:task gone) :status])))
      (is (= "refused" (get-in by-task [(:task moved) :status])))
      (is (re-find #"not this child's pane" (get-in by-task [(:task moved) :reason])))
      (is (= "closed" (get-in by-task [(:task ok) :status])))
      (is (= #{(:pane-id ok)} (closed-panes log))))))

;; A child whose *current* round is still open is skipped whole. Falling back to its older
;; captured round would close the pane its live round is still working in.
(deftest close-settled-skips-a-child-whose-newest-round-is-uncaptured
  (let [{:keys [env log dir]} (fake-env {})
        first-round (capture-entry! dir (start-child! env dir "captured round one"))
        second-round (extra-round! dir first-round "non-blocking")
        proc (call! env "task" "close" "--settled")]
    (is (zero? (:exit proc)) (:err proc))
    (is (empty? (:result (result proc))))
    (is (empty? (closed-panes log)))
    (is (nil? (:closed-at (closed-entry dir first-round))))
    (is (nil? (:closed-at (closed-entry dir second-round))))))

;; An `invalid` capture means the envelope needs manual intervention, so the sweep must
;; leave the operator the pane they would use to deal with it -- while still closing every
;; other child in the same pass, so the exclusion is visibly a filter and not an abort.
(deftest close-settled-skips-an-invalid-capture
  (let [{:keys [env log dir]} (fake-env {})
        invalid (patch-entry! dir (capture-entry! dir (start-child! env dir "invalid envelope"))
                              :status "invalid")
        ok (capture-entry! dir (start-child! env dir "valid envelope"))
        proc (call! env "task" "close" "--settled")
        outcomes (:result (result proc))]
    (is (zero? (:exit proc)) (:err proc))
    (is (= [(:task ok)] (mapv :task outcomes)))
    (is (= #{(:pane-id ok)} (closed-panes log)))
    (is (nil? (:closed-at (closed-entry dir invalid))))
    ;; Not even a settle wait is spent on it: it is excluded by the candidate filter, so no
    ;; close is ever attempted against its pane.
    (is (empty? (child-waits log (:child invalid))))))

;; Foreign and already-closed entries are not candidates at all, so a sweep is idempotent.
(deftest close-settled-skips-foreign-and-already-closed-entries
  (let [{:keys [env log dir]} (fake-env {})
        mine (capture-entry! dir (start-child! env dir "mine"))
        theirs (patch-entry! dir (capture-entry! dir (start-child! env dir "theirs"))
                             :parent-session (str (fs/path dir "other-live-session.jsonl")))]
    (spit (:parent-session theirs) "{}")
    (let [first-pass (call! env "task" "close" "--settled")
          second-pass (call! env "task" "close" "--settled")]
      (is (= [(:task mine)] (mapv :task (:result (result first-pass)))))
      (is (empty? (:result (result second-pass))))
      (is (= #{(:pane-id mine)} (closed-panes log)))
      (is (nil? (:closed-at (closed-entry dir theirs)))))))

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
    (testing "bare and unknown-group help is the global usage"
      (doseq [argv [["--help"] ["help"] ["bogus" "--help"]]]
        (let [proc (apply call! env argv)]
          (is (zero? (:exit proc)) (str argv " -> " (:err proc)))
          (is (str/starts-with? (:out proc) "oh pane"))
          (is (not (str/includes? (:out proc) "\"ok\""))))))
    ;; A group listing cannot show positional arity, so `<group> <op> --help` must narrow
    ;; to the one signature; guessing an arity costs a failed invocation.
    (testing "group help lists its own commands and command help narrows to one"
      (doseq [[argv expected] {["agent" "--help"] "oh agent start <name> --kind KIND"
                               ["agent" "prompt" "--help"] "oh agent prompt <target> <text>"
                               ["pane" "rename" "--help"] "oh pane rename <pane> <label>"
                               ["task" "publish" "--help"] "oh task publish --status COMPLETE|BLOCKED|FAILED"
                               ["spawn" "--help"] "oh spawn \"<shell command>\""}]
        (let [proc (apply call! env argv)]
          (is (zero? (:exit proc)) (str argv " -> " (:err proc)))
          (is (str/starts-with? (:out proc) expected) (str argv " -> " (:out proc)))
          (is (not (str/includes? (:out proc) "\"ok\""))))))
    (testing "a single-command help does not dump every other command"
      (let [proc (call! env "agent" "prompt" "--help")]
        (is (= 1 (count (str/split-lines (str/trim (:out proc))))))))
    ;; `task run --help` must still short-circuit before the assignment-input check, so a
    ;; help request never becomes "exactly one of --task/--task-file/stdin".
    (testing "help short-circuits commands with required options"
      (doseq [argv [["task" "run" "--help"] ["agent" "start" "--help"]]]
        (let [proc (apply call! env argv)]
          (is (zero? (:exit proc)) (str argv " -> " (:err proc)))
          (is (not (str/includes? (:out proc) "\"ok\""))))))))


;; Ties the shipped default table to the record's verified rows, independent of the
;; loader/translation machinery under test elsewhere in this namespace.
(deftest default-config-content-contract
  (let [config (core/parse-config "config.edn" (slurp (str (fs/path root "skills" "herdr-orch" "subagents" "config.edn"))))
        ;; The pre-migration flat table (design/log/2026-08-04-herdr-orch-simplify-model-aliasing.org
        ;; task 1's starting point): every ID used to be its own `:models` row. Hardcoded
        ;; here, never derived from the new two-level table below, so the argv-preservation
        ;; check cannot be circular.
        old-flat-table {"heavy"            {:pi "anthropic/claude-fable-5"   :claude "fable"            :codex "gpt-5.6-sol"}
                         "middle"           {:pi "anthropic/claude-opus-5"    :claude "opus"             :codex "gpt-5.6-sol"}
                         "light"            {:pi "anthropic/claude-sonnet-5"  :claude "sonnet"           :codex "gpt-5.6-terra"}
                         "feather"          {:pi "anthropic/claude-haiku-4-5" :claude "haiku"            :codex "gpt-5.6-luna"}
                         "claude-fable"     {:pi "anthropic/claude-fable-5"   :claude "fable"            :codex "gpt-5.6-sol"}
                         "claude-opus"      {:pi "anthropic/claude-opus-5"    :claude "opus"             :codex "gpt-5.6-sol"}
                         "claude-sonnet"    {:pi "anthropic/claude-sonnet-5"  :claude "sonnet"           :codex "gpt-5.6-terra"}
                         "claude-haiku"     {:pi "anthropic/claude-haiku-4-5" :claude "claude-haiku-4-5" :codex "gpt-5.6-luna"}
                         "gpt-sol"          {:pi "openai-codex/gpt-5.6-sol"   :claude "opus"             :codex "gpt-5.6-sol"}
                         "gpt-terra"        {:pi "openai-codex/gpt-5.6-terra" :claude "sonnet"           :codex "gpt-5.6-terra"}
                         "gpt-luna"         {:pi "openai-codex/gpt-5.6-luna"  :claude "claude-haiku-4-5" :codex "gpt-5.6-luna"}
                         "claude-fable-5"   {:pi "anthropic/claude-fable-5"   :claude "fable"            :codex "gpt-5.6-sol"}
                         "claude-opus-5"    {:pi "anthropic/claude-opus-5"    :claude "opus"             :codex "gpt-5.6-sol"}
                         "claude-sonnet-5"  {:pi "anthropic/claude-sonnet-5"  :claude "sonnet"           :codex "gpt-5.6-terra"}
                         "claude-haiku-4-5" {:pi "anthropic/claude-haiku-4-5" :claude "claude-haiku-4-5" :codex "gpt-5.6-luna"}
                         "gpt-5.6-sol"      {:pi "openai-codex/gpt-5.6-sol"   :claude "opus"             :codex "gpt-5.6-sol"}
                         "gpt-5.6-terra"    {:pi "openai-codex/gpt-5.6-terra" :claude "sonnet"           :codex "gpt-5.6-terra"}
                         "gpt-5.6-luna"     {:pi "openai-codex/gpt-5.6-luna"  :claude "claude-haiku-4-5" :codex "gpt-5.6-luna"}}
        weight-rows {"heavy" {:pi "anthropic/claude-fable-5" :claude "fable" :codex "gpt-5.6-sol"}
                     "middle" {:pi "anthropic/claude-opus-5" :claude "opus" :codex "gpt-5.6-sol"}
                     "light" {:pi "anthropic/claude-sonnet-5" :claude "sonnet" :codex "gpt-5.6-terra"}
                     "feather" {:pi "anthropic/claude-haiku-4-5" :claude "claude-haiku-4-5" :codex "gpt-5.6-luna"}}]
    (is (= "--model" (get-in config [:harnesses :pi :model-flag])))
    (is (= "--model" (get-in config [:harnesses :claude :model-flag])))
    (is (= "--model" (get-in config [:harnesses :codex :model-flag])))
    (is (= {:placement :tab-split} (:defaults config)))
    (is (= 7 (count (:models config))) "shipped :models has exactly 7 canonical rows")
    (is (= 18 (count (:aliases config))) "shipped :aliases has exactly 18 entries")
    (testing "argv preservation: every pre-migration ID translates identically for every kind, except feather+claude"
      (doseq [[id row] old-flat-table
              [kind native-model] row
              :let [expected (if (and (= id "feather") (= kind :claude)) "claude-haiku-4-5" native-model)]]
        (is (= ["--model" expected] (core/model-args config (name kind) id))
            (str id " translates for " (name kind)))))
    ;; contract.md is the single enumerated documentation home for these rows; every
    ;; other document states the rule and links to it. Pin the surviving copy here so a
    ;; model bump that misses the table fails the suite instead of drifting silently.
    (testing "contract.md's weight table matches the shipped config's effective translations"
      (let [doc (slurp (str (fs/path root "skills" "herdr-orch" "scripts" "docs" "contract.md")))
            documented (into {} (for [[_ weight pi claude codex]
                                      (re-seq #"(?m)^\|\s*`(heavy|middle|light|feather)`\s*\|\s*`([^`]+)`\s*\|\s*`([^`]+)`\s*\|\s*`([^`]+)`\s*\|\s*$" doc)]
                                  [weight {:pi pi :claude claude :codex codex}]))]
        (is (= weight-rows documented)
            "contract.md § Model resolution must enumerate exactly the shipped weight rows")
        (doseq [[weight row] weight-rows
                [kind native-model] row]
          (is (= ["--model" native-model] (core/model-args config (name kind) weight))
              (str weight " " (name kind) " must resolve to the documented cell")))))
    ;; Pi receives the configured OpenAI model for `gpt-*`; only the claude/codex
    ;; columns use tier-equivalent cross-provider mappings.
    (is (= ["--model" "openai-codex/gpt-5.6-terra"] (core/model-args config "pi" "gpt-5.6-terra")))
    (is (= ["--model" "sonnet"] (core/model-args config "claude" "gpt-5.6-terra")))
    (is (= ["--model" "gpt-5.6-terra"] (core/model-args config "codex" "gpt-5.6-terra")))
    (is (= ["--model" "openai-codex/gpt-5.6-sol"] (core/model-args config "pi" "gpt-5.6-sol")))
    (is (= ["--model" "opus"] (core/model-args config "claude" "gpt-5.6-sol")))
    (is (= ["--model" "openai-codex/gpt-5.6-luna"] (core/model-args config "pi" "gpt-5.6-luna")))
    (is (= ["--model" "claude-haiku-4-5"] (core/model-args config "claude" "gpt-5.6-luna")))
    ;; The canonical `claude-haiku*` rows retain the full name despite `feather` using
    ;; the requested undocumented `haiku` alias in the pre-migration table.
    (is (= "claude-haiku-4-5" (get-in config [:models "anthropic/claude-haiku-4-5" :claude])))
    ;; Unversioned canonical IDs are floating aliases for the latest version of the tier.
    (doseq [[unversioned latest] [["claude-fable" "claude-fable-5"] ["claude-opus" "claude-opus-5"]
                                  ["claude-sonnet" "claude-sonnet-5"] ["claude-haiku" "claude-haiku-4-5"]
                                  ["gpt-sol" "gpt-5.6-sol"] ["gpt-terra" "gpt-5.6-terra"] ["gpt-luna" "gpt-5.6-luna"]]
            kind ["pi" "claude" "codex"]]
      (is (= (core/model-args config kind latest) (core/model-args config kind unversioned))
          (str unversioned " resolves identically to " latest " for " kind)))
    (is (= ["--model" "gpt-5.6-terra"] (core/model-args config "codex" "claude-sonnet-5")))))

;; --- lifecycle documentation contract -----------------------------------------------
;; The skill's own documented lifecycle is a deliverable of this change, not commentary,
;; and prose regresses silently in a way code does not. This pins the load-bearing
;; sentences and, more importantly, the language that had to *go*: the auto-close era's
;; phrasing reads plausibly, so a future edit could reintroduce it without any test noticing.
;; Every document that describes the lifecycle must be listed here: a document omitted from
;; this vector is one the suite cannot police, which is how README.org kept asserting
;; capture-time closure through a green run of this very test (closure-validation P1).
(def ^:private lifecycle-docs
  ["skills/herdr-orch/SKILL.md"
   "skills/herdr-orch/README.org"
   "skills/herdr-orch/scripts/README.md"
   "skills/herdr-orch/scripts/docs/contract.md"
   "skills/herdr-orch/subagents/planner.md"
   "skills/herdr-orch/subagents/worker.md"])

(deftest lifecycle-documentation-contract
  (let [read-doc (fn [rel] (slurp (str (fs/path root rel))))
        skill (read-doc "skills/herdr-orch/SKILL.md")
        contract (read-doc "skills/herdr-orch/scripts/docs/contract.md")]
    (testing "Class C requires a validator spawned fresh for the closeout"
      ;; The old sentence said only \"the root or an ephemeral reviewer\", inheriting its
      ;; freshness from the \"ephemeral by default\" lifecycle default this change removed.
      ;; Retaining it verbatim would have silently dropped the guarantee, so the rewrite --
      ;; not merely the prohibition on self-reaffirmation -- is what is pinned here.
      (is (str/includes? skill "spawned fresh for that closeout"))
      (is (str/includes? skill "never a child continued from an earlier round of the same work"))
      (is (str/includes? skill "Never the implementing worker reaffirming its own result")
          "the pre-existing prohibition must survive the rewrite")
      (is (not (str/includes? skill "independent validation by the root or an ephemeral reviewer"))
          "the superseded Class C sentence must not survive verbatim"))
    (testing "the lifecycle is stated as parent-driven close-or-continue"
      (is (str/includes? skill "No capture closes a pane"))
      (is (str/includes? skill "a child's pane persists until you act on it"))
      (is (str/includes? skill "Whoever spawns a child closes it"))
      (is (str/includes? contract "**Capture closes nothing.**"))
      (is (str/includes? contract "\n## Close\n"))
      (is (str/includes? contract "\n## Continue\n")))
    (testing "the ledger fields this lifecycle introduced are documented"
      (doseq [field [":continues" ":closed-at" ":waiting-policy"]]
        (is (str/includes? contract field) field)))
    (testing "no document asserts the behaviour the code no longer has"
      (doseq [rel lifecycle-docs
              ;; The last pins the specific claim that survived in README.org: a banned
              ;; phrase only guards the wording it names, so add the wording each stale
              ;; document actually used rather than assuming the list is exhaustive. Ban the
              ;; false subject ("Every capture path settle-waits"), not the shared predicate
              ;; ("before its single close attempt"), which is true of `task close`.
              phrase ["HERDR_ORCH_WAITING_POLICY"
                      "ephemeral by default"
                      "closed its pane automatically"
                      "pane-close-on-completion"
                      "unknown-ledger-entry"
                      "Every capture path settle-waits"]]
        (is (not (str/includes? (read-doc rel) phrase))
            (str rel " still says \"" phrase "\""))))))

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
          (is (= "opus" (get-in config [:models "anthropic/claude-opus-5" :claude])))
          (is (= "--model" (get-in config [:harnesses :codex :model-flag])))))
      (testing "home override replaces a canonical model row"
        (fs/create-dirs (fs/parent home-roster))
        (spit (str home-roster) "{:models {\"anthropic/claude-opus-5\" {:claude \"opus-home\"}}}")
        (is (= "opus-home" (get-in (cli/config home-dir) [:models "anthropic/claude-opus-5" :claude]))))
      (testing "project beats home for the same canonical row; row-level replacement drops untouched columns"
        (fs/create-dirs (fs/parent project-config))
        (spit (str project-config) "{:models {\"anthropic/claude-opus-5\" {:claude \"opus-project\"}}}")
        (let [config (cli/config home-dir)]
          (is (= "opus-project" (get-in config [:models "anthropic/claude-opus-5" :claude])))
          ;; The overridden row replaces the whole default row: :codex is gone, not
          ;; deep-merged alongside the new :claude value.
          (is (nil? (get-in config [:models "anthropic/claude-opus-5" :codex])))))
      (testing "missing override files are silently ignored"
        (fs/delete home-roster) (fs/delete project-config)
        (is (= "opus" (get-in (cli/config home-dir) [:models "anthropic/claude-opus-5" :claude]))))
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
        (spit (str project-config) "{:harnesses {:gemini {:model-flag \"--model\"}} :models {\"anthropic/claude-opus-5\" {:gemini \"gemini-2.5-pro\"}}}")
        (let [config (cli/config home-dir)]
          (is (= ["--model" "gemini-2.5-pro"] (core/model-args config "gemini" "anthropic/claude-opus-5")))
          ;; A kind still absent from `:harnesses` remains empty args — the addition is
          ;; purely additive data, no code change and no other kind affected.
          (is (= [] (core/model-args config "vertex" "anthropic/claude-opus-5"))))
        (fs/delete project-config)))
    (testing "missing shipped default is fatal"
      (with-redefs [cli/launcher-bin (constantly (str (fs/path tmp "empty-install" "skills" "herdr-orch" "scripts" "oh")))
                    ledger/assignment-root (constantly project-root)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"missing shipped default config" (cli/config home-dir)))))))

(def roster-model-personas
  {"canonical-worker" "---\nname: canonical-worker\ndescription: fixture canonical-id persona\nkind: pi\nmodel: claude-opus-5\n---\nFixture canonical worker.\n"
   "kindless-worker" "---\nname: kindless-worker\ndescription: fixture kindless canonical-id persona\nmodel: claude-opus-5\n---\nFixture kindless worker.\n"})
(defn- start-native-args [log]
  (first (filter #(= ["agent" "start"] (vec (take 2 %))) (calls log))))
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
    (spit (str (fs/path dir ".agents" "subagents" "config.edn")) "{:models {\"anthropic/claude-opus-5\" {:claude \"opus-relocated\"}}}")
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
      (is (not (fs/exists? (fs/path dir ".tmp" "herdr-orch" "ledger"))))
      (is (not-any? mutating? (calls log))))))

;; A project override introducing a fresh `:aliases` key (never a shipped `:models` row)
;; retargets `--model` end-to-end through the real loader, and `--print-prompt` reports
;; the post-alias canonical ID alongside the resolved (pre-alias) model and the native
;; translated model-args -- acceptance for two-level model resolution in code. The target
;; is the genuine canonical ID rather than the shipped `"claude-opus-5"` alias, because
;; pointing a fresh alias at an existing alias key would be a rejected multi-hop chain.
(deftest preview-reports-post-alias-canonical-model
  (let [{:keys [env dir]} (fake-env {} roster-model-personas)]
    (spit (str (fs/path dir ".agents" "subagents" "config.edn")) "{:aliases {\"fixture-heavy\" \"anthropic/claude-opus-5\"}}")
    (let [proc (call! env "task" "run" "canonical-worker" "--kind" "claude" "--model" "fixture-heavy" "--task" "alias preview" "--print-prompt")]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "fixture-heavy" (get-in (result proc) [:result :model])))
      (is (= "anthropic/claude-opus-5" (get-in (result proc) [:result :model-canonical])))
      (is (= ["--model" "opus"] (get-in (result proc) [:result :model-args]))))))

;; Post-merge validation fires before any ledger allocation or pane mutation, exactly
;; like the per-file shape checks above. `"anthropic/claude-opus-5"` is already a shipped
;; `:models` key (the two-level table's canonical row), so a project override adding it
;; as an `:aliases` key collides on merge without this test having to shadow anything
;; itself.
(deftest project-override-alias-model-key-overlap-fails-before-ledger-or-mutation
  (let [{:keys [env log dir]} (fake-env {} minimal-persona)]
    (spit (str (fs/path dir ".agents" "subagents" "config.edn")) "{:aliases {\"anthropic/claude-opus-5\" \"anthropic/claude-fable-5\"}}")
    (let [proc (call! env "task" "start" "probe" "--task" "alias/model overlap aborts")]
      (is (= 1 (:exit proc)))
      (is (re-find #":aliases" (:out proc)) (:out proc))
      (is (re-find #":models" (:out proc)) (:out proc))
      (is (re-find #"anthropic/claude-opus-5" (:out proc)) (:out proc))
      (is (not (fs/exists? (fs/path dir ".tmp" "herdr-orch" "ledger"))))
      (is (not-any? mutating? (calls log))))))

;; A chained alias (a value that is itself an `:aliases` key) is rejected the same way,
;; using fresh keys absent from the shipped table so only the chain check can fire. The
;; second hop targets the genuine canonical ID (a `:models` key) rather than the shipped
;; `"claude-opus-5"` alias, which would itself trip the chain check and make the failing
;; key non-deterministic between the two violations.
(deftest project-override-alias-chain-fails-before-ledger-or-mutation
  (let [{:keys [env log dir]} (fake-env {} minimal-persona)]
    (spit (str (fs/path dir ".agents" "subagents" "config.edn")) "{:aliases {\"fixture-x\" \"fixture-y\" \"fixture-y\" \"anthropic/claude-opus-5\"}}")
    (let [proc (call! env "task" "start" "probe" "--task" "alias chain aborts")]
      (is (= 1 (:exit proc)))
      (is (re-find #"fixture-y" (:out proc)) (:out proc))
      (is (not (fs/exists? (fs/path dir ".tmp" "herdr-orch" "ledger"))))
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
        proc (call! (merge (child-publish-env env entry) {"FAKE_PARENT_STATUS" "idle"})
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
        proc (call! (merge (child-publish-env env entry) {"FAKE_PARENT_STATUS" "idle"})
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
    (let [proc (call! (merge (child-publish-env env entry) {"FAKE_PARENT_STATUS" "idle"})
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
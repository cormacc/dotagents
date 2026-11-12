#!/usr/bin/env bb

(require '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.test :refer [deftest is run-tests]])

(def root
  (str/trim (:out @(process/process ["git" "rev-parse" "--show-toplevel"]
                                    {:out :string :err :string}))))
(def runner
  (or (System/getenv "TRAIT_GATE_RUNNER")
      (str (fs/path root "scripts" "run-trait-gate.bb"))))

(defn call! [argv options]
  @(process/process argv (merge {:out :string :err :string} options)))

(defn write-fake-pi!
  "Installs `pi` in `directory` as a wrapper around scripts/fixtures/fake-trait-gate-pi.bb that
  logs to `log-path`."
  [directory log-path]
  (let [path (fs/path directory "pi")]
    (spit (str path)
          (str "#!/usr/bin/env bash\n"
               "export FAKE_PI_LOG=" (pr-str log-path) "\n"
               "exec bb " (pr-str (str (fs/path root "scripts" "fixtures" "fake-trait-gate-pi.bb"))) " \"$@\"\n"))
    (fs/set-posix-file-permissions path "rwxr-xr-x")
    (str path)))

(defn file-tree [path]
  (if (fs/exists? path)
    (->> (file-seq (fs/file path))
         (filter #(.isFile %))
         (map (fn [file] [(str file) (slurp file)]))
         (into (sorted-map)))
    {}))

(defn prep-fake-path! [directory]
  (str directory java.io.File/pathSeparator (System/getenv "PATH")))

(defn gate-env
  "Environment for a fake run: the fake `pi` first on PATH, and a private agent directory whose
  AGENTS.md is the home instruction baseline."
  [temp extra]
  (let [agent-dir (fs/path temp "agent")]
    (fs/create-dirs agent-dir)
    (spit (str (fs/path agent-dir "AGENTS.md")) "# Fake home baseline\n\nA synthetic home instruction.\n")
    (merge (into {} (System/getenv))
           {"PATH" (prep-fake-path! temp) "PI_CODING_AGENT_DIR" (str agent-dir)}
           extra)))

(defn new-temp [prefix]
  (str (fs/create-temp-dir {:dir (str (fs/path root ".tmp")) :prefix prefix})))

(defn outputs-dir [output] (second (re-find #"(?m)^Outputs: (.+)$" output)))

(deftest prune-gate-derives-and-runs-two-direct-prompt-arms
  (let [temp (new-temp "trait-gate-test-")
        log-path (str (fs/path temp "pi.log"))
        _ (write-fake-pi! temp log-path)
        agents-path (fs/path root ".agents")
        agents-before (file-tree agents-path)
        proc (call! [runner "prune" "--model" "anthropic/fake-model"]
                    {:dir root :env (gate-env temp {"HERDR_ENV" "1"
                                                    "PI_SESSION_ID" "caller-session"
                                                    "TRAIT_GATE_KEEP" "kept"})})
        output (:out proc)
        required-markers ["Pass condition, fixed before the run:"
                          "=== TREATED ==="
                          "=== CONTROL ==="
                          "Mode: scaffold"
                          "Treated: fake/fake-model; 1 context files"
                          "1 audited requests"
                          "Outputs: "]]
    (is (zero? (:exit proc)) (:err proc))
    (doseq [marker required-markers]
      (is (and (string? output) (str/includes? output marker))
          (str "missing required output marker " (pr-str marker) "; stderr: " (:err proc))))
    (when (and (zero? (:exit proc))
               (string? output)
               (every? #(str/includes? output %) required-markers))
      (let [run-dir (outputs-dir output)
            treated-source (slurp (str (fs/path run-dir "treated.source.md")))
            control-prompt (slurp (str (fs/path run-dir "control.prompt.md")))
            treated-prompt (slurp (str (fs/path run-dir "treated.prompt.md")))
            interpolation (json/parse-string
                           (slurp (str (fs/path run-dir "treated.interpolation.json"))) true)
            provenance (json/parse-string
                        (slurp (str (fs/path run-dir "treated.provenance.json"))) true)
            expect (json/parse-string (slurp (str (fs/path run-dir "treated.audit-expect.json"))))
            invocations (mapv read-string (str/split-lines (slurp log-path)))
            audit-extension (str (fs/path root "scripts" "trait-gate-audit.ts"))
            token-line "%prune"]
        (is (some? run-dir) output)
        (is (= control-prompt
               (->> (str/split treated-source #"\n" -1)
                    (remove #(= token-line %))
                    (str/join "\n")))
            "the source arms differ only by the one exact token line")
        (is (= 1 (count (filter #(= token-line %)
                                (str/split-lines treated-source)))))
        (is (not (str/includes? control-prompt token-line)))
        (is (= treated-prompt (get-in interpolation [:result :text])))
        (is (= ["prune"] (mapv :trait (get-in interpolation [:result :resolved]))))
        (is (not (str/includes? treated-prompt token-line)))
        (is (not (str/includes? output "Verdict: **PASS")))
        (is (= 2 (count invocations)))
        (is (= [treated-prompt control-prompt] (mapv (comp last :args) invocations))
            "each arm sends its exact prompt as the final argument")
        (is (= #{"baseline" "fragment"} (set (keys expect))))
        (is (str/includes? treated-prompt (get expect "fragment")) "the audited fragment is the substituted text")
        (is (not (str/includes? control-prompt (get expect "fragment"))))
        (doseq [{:keys [args cwd herdr session keep system]} invocations]
          (is (= ["-p" "--no-session" "--mode" "json" "--model" "anthropic/fake-model"
                  "-e" audit-extension "--"]
                 (butlast args)))
          (is (nil? system) "scaffold mode appends no system prompt")
          (is (not (str/starts-with? cwd root)) "each arm runs outside the checkout")
          (is (nil? herdr) "the caller's Herdr variables are removed")
          (is (nil? session) "the caller's Pi session variables are removed")
          (is (= "kept" keep) "other environment passes through"))
        (is (not= (:cwd (first invocations)) (:cwd (second invocations)))
            "each arm gets its own directory")
        (is (= {:provider "fake" :model "fake-model" :api "fake-api"
                :context-files ["/home/AGENTS.md"] :skills 2 :tools ["read" "bash"]
                :mcp-servers ["mcp__one" "mcp__two"] :prompt-sent true :cwd-files-after []
                :audit {:controls {:positive true :missing false :knownBad false} :requests 1}}
               (select-keys provenance [:provider :model :api :context-files :skills :tools
                                        :mcp-servers :prompt-sent :cwd-files-after :audit])))
        (is (str/starts-with? (slurp (str (fs/path run-dir "treated.output.md"))) "FAKE RESPONSE"))
        (is (= agents-before (file-tree agents-path))
            "the runner does not write durable .agents configuration")))))

(deftest an-arm-without-a-final-response-fails
  (let [temp (new-temp "trait-gate-test-")
        log-path (str (fs/path temp "pi.log"))
        _ (write-fake-pi! temp log-path)
        proc (call! [runner "prune" "--model" "anthropic/fake-model"]
                    {:dir root :env (gate-env temp {"FAKE_PI_MODE" "silent"})})]
    (is (= 1 (:exit proc)))
    (is (str/includes? (:err proc) "treated agent run produced no final response"))
    (is (= 1 (count (str/split-lines (slurp log-path))))
        "the control arm does not run after the treated arm fails")))

(deftest a-request-without-the-baseline-is-an-invalid-observation
  (let [temp (new-temp "trait-gate-test-")
        log-path (str (fs/path temp "pi.log"))
        _ (write-fake-pi! temp log-path)
        proc (call! [runner "prune" "--model" "anthropic/fake-model"]
                    {:dir root :env (gate-env temp {"FAKE_PI_MODE" "no-baseline"})})]
    (is (= 1 (:exit proc)))
    (is (str/includes? (:err proc) "treated observation is invalid"))
    (is (str/includes? (:err proc) "\"baseline\" true"))
    (is (= 1 (count (str/split-lines (slurp log-path)))))))

(deftest persona-mode-appends-the-composed-persona-and-removes-only-the-fragment
  (let [temp (new-temp "trait-gate-test-")
        log-path (str (fs/path temp "pi.log"))
        _ (write-fake-pi! temp log-path)
        proc (call! [runner "focused" "--model" "anthropic/fake-model" "--persona" "scout"]
                    {:dir root :env (gate-env temp {"TRAIT_GATE_OH" (str (fs/path root "scripts" "fixtures" "fake-trait-gate-oh.bb"))})})
        output (:out proc)]
    (is (zero? (:exit proc)) (:err proc))
    (is (str/includes? (str output) "Mode: packaged persona scout"))
    (when (zero? (:exit proc))
      (let [run-dir (outputs-dir output)
            [treated control] (mapv read-string (str/split-lines (slurp log-path)))
            assignment (slurp (str (fs/path run-dir "assignment.md")))
            fragment (get (json/parse-string (slurp (str (fs/path run-dir "treated.audit-expect.json")))) "fragment")
            occurrences (fn [text] (count (re-seq (re-pattern (java.util.regex.Pattern/quote fragment)) text)))]
        (is (= assignment (last (:args treated)) (last (:args control)))
            "both arms send only the assignment")
        (is (some #{"--append-system-prompt"} (:args treated)))
        (is (str/includes? (:system treated) "# Fake scout persona") "the composed persona is appended")
        (is (= 1 (occurrences (:system treated))))
        (is (zero? (occurrences (:system control))))
        (is (= (:system control) (str/replace (:system treated) fragment ""))
            "the control persona differs only by the fragment")
        (is (str/includes? (:system control) "Consume the worktree")
            "the persona's other traits stay in the control arm")))))

(deftest a-fixture-is-committed-in-each-arm-and-a-write-is-reported
  (doseq [[write? marker] [[false "fixture unchanged"] [true "fixture CHANGED: M notes.md"]]]
    (let [temp (new-temp "trait-gate-test-")
          log-path (str (fs/path temp "pi.log"))
          _ (write-fake-pi! temp log-path)
          proc (call! [runner "read-only" "--model" "anthropic/fake-model"]
                      {:dir root :env (gate-env temp (if write? {"FAKE_PI_WRITE" "notes.md"} {}))})
          output (str (:out proc))]
      (is (zero? (:exit proc)) (:err proc))
      (is (= 2 (count (re-seq (re-pattern (java.util.regex.Pattern/quote marker)) output)))
          (str "both arms report " marker))
      (when (zero? (:exit proc))
        (let [provenance (json/parse-string (slurp (str (fs/path (outputs-dir output) "control.provenance.json"))) true)]
          (is (= (if write? " M notes.md\n" "") (:fixture-status provenance))))))))

(deftest malformed-gate-fails-with-its-full-path-before-running-an-arm
  (let [temp (str (fs/create-temp-dir {:dir (str (fs/path root ".tmp"))
                                       :prefix "trait-gate-malformed-"}))
        gate (fs/path temp "traits" "broken" "gate.md")
        temp-runner (fs/path temp "scripts" "run-trait-gate.bb")
        traits-bin (fs/path temp "skills" "herdr-orch" "scripts" "traits")
        pi-log (str (fs/path temp "pi.log"))]
    (fs/create-dirs (fs/parent gate))
    (fs/create-dirs (fs/parent temp-runner))
    (fs/create-dirs (fs/parent traits-bin))
    (spit (str gate)
          "# Gate: %broken\n\nPass condition, fixed before the run: never reached.\n\n## Scaffold\n\n%broken\n")
    (fs/copy runner temp-runner)
    (fs/set-posix-file-permissions temp-runner "rwxr-xr-x")
    (spit (str traits-bin) "#!/usr/bin/env bb\n")
    (fs/set-posix-file-permissions traits-bin "rwxr-xr-x")
    (write-fake-pi! temp pi-log)
    (is (zero? (:exit (call! ["git" "init" "--quiet"] {:dir temp}))))
    (let [proc (call! [(str temp-runner) "broken" "--model" "fake-model"]
                      {:dir temp :env (assoc (into {} (System/getenv))
                                             "PATH" (prep-fake-path! temp))})]
      (is (= 1 (:exit proc)))
      (is (str/includes? (:err proc) (str gate)))
      (is (str/includes? (:err proc) "expected exactly one ## Assignment section"))
      (is (not (fs/exists? pi-log))
          "malformed input fails before either direct agent arm runs"))))

(deftest missing-pass-marker-fails-with-its-full-path-before-running-an-arm
  (let [temp (str (fs/create-temp-dir {:dir (str (fs/path root ".tmp"))
                                       :prefix "trait-gate-missing-marker-"}))
        gate (fs/path temp "traits" "broken" "gate.md")
        temp-runner (fs/path temp "scripts" "run-trait-gate.bb")
        traits-bin (fs/path temp "skills" "herdr-orch" "scripts" "traits")
        pi-log (str (fs/path temp "pi.log"))]
    (fs/create-dirs (fs/parent gate))
    (fs/create-dirs (fs/parent temp-runner))
    (fs/create-dirs (fs/parent traits-bin))
    (spit (str gate)
          "# Gate: %broken\n\nCondition: deliberately malformed.\n\n## Scaffold\n\n%broken\n\n## Assignment\n\nDo the work.\n")
    (fs/copy runner temp-runner)
    (fs/set-posix-file-permissions temp-runner "rwxr-xr-x")
    (spit (str traits-bin) "#!/usr/bin/env bb\n")
    (fs/set-posix-file-permissions traits-bin "rwxr-xr-x")
    (write-fake-pi! temp pi-log)
    (is (zero? (:exit (call! ["git" "init" "--quiet"] {:dir temp}))))
    (let [proc (call! [(str temp-runner) "broken" "--model" "fake-model"]
                      {:dir temp :env (assoc (into {} (System/getenv))
                                             "PATH" (prep-fake-path! temp))})]
      (is (= 1 (:exit proc)))
      (is (str/includes? (:err proc) (str gate)))
      (is (str/includes? (:err proc)
                         "expected exactly one 'Pass condition, fixed before the run:' paragraph"))
      (is (not (fs/exists? pi-log))
          "malformed input fails before either direct agent arm runs"))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))

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
  "A fake `pi` that logs its argv, cwd and selected environment, then emits the JSON-mode
  messages the runner reads. FAKE_PI_MODE=silent emits no assistant message."
  [directory log-path]
  (let [path (fs/path directory "pi")]
    (spit (str path)
          (str "#!/usr/bin/env bb\n"
               "(require '[cheshire.core :as json])\n"
               "(let [args *command-line-args*\n"
               "      prompt (last args)\n"
               "      cwd (System/getProperty \"user.dir\")\n"
               "      emit (fn [m] (println (json/generate-string {:type \"message_end\" :message m})))]\n"
               "  (spit \"" log-path "\" (str (pr-str {:args (vec args) :cwd cwd\n"
               "                                         :herdr (System/getenv \"HERDR_ENV\")\n"
               "                                         :session (System/getenv \"PI_SESSION_ID\")\n"
               "                                         :keep (System/getenv \"TRAIT_GATE_KEEP\")}) \"\\n\") :append true)\n"
               "  (emit {:role \"system\" :toolsAdded [{:name \"read\"} {:name \"bash\"}]\n"
               "         :sections {:project_context \"<project_instructions path=\\\"/home/AGENTS.md\\\">x\"\n"
               "                    :skills \"<skill>a</skill><skill>b</skill>\"\n"
               "                    :mcp_servers \"- mcp__one (codemode)\\n- mcp__two\"}})\n"
               "  (emit {:role \"user\" :content [{:type \"text\" :text prompt}]})\n"
               "  (when-not (= \"silent\" (System/getenv \"FAKE_PI_MODE\"))\n"
               "    (emit {:role \"assistant\" :provider \"fake\" :model \"fake-model\" :api \"fake-api\"\n"
               "           :stopReason \"stop\" :content [{:type \"text\" :text (str \"FAKE RESPONSE\\n\" prompt)}]})))\n"))
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

(deftest prune-gate-derives-and-runs-two-direct-prompt-arms
  (let [temp (str (fs/create-temp-dir {:dir (str (fs/path root ".tmp"))
                                       :prefix "trait-gate-test-"}))
        log-path (str (fs/path temp "pi.log"))
        _ (write-fake-pi! temp log-path)
        agents-path (fs/path root ".agents")
        agents-before (file-tree agents-path)
        proc (call! [runner "prune" "--model" "anthropic/fake-model"]
                    {:dir root :env (assoc (into {} (System/getenv))
                                           "PATH" (prep-fake-path! temp)
                                           "HERDR_ENV" "1"
                                           "PI_SESSION_ID" "caller-session"
                                           "TRAIT_GATE_KEEP" "kept")})
        output (:out proc)
        required-markers ["Pass condition, fixed before the run:"
                          "=== TREATED ==="
                          "=== CONTROL ==="
                          "Treated: fake/fake-model; 1 context files"
                          "Outputs: "]]
    (is (zero? (:exit proc)) (:err proc))
    (doseq [marker required-markers]
      (is (and (string? output) (str/includes? output marker))
          (str "missing required output marker " (pr-str marker) "; stderr: " (:err proc))))
    (when (and (zero? (:exit proc))
               (string? output)
               (every? #(str/includes? output %) required-markers))
      (let [run-dir (second (re-find #"(?m)^Outputs: (.+)$" output))
            treated-source (slurp (str (fs/path run-dir "treated.source.md")))
            control-prompt (slurp (str (fs/path run-dir "control.prompt.md")))
            treated-prompt (slurp (str (fs/path run-dir "treated.prompt.md")))
            interpolation (json/parse-string
                           (slurp (str (fs/path run-dir "treated.interpolation.json"))) true)
            provenance (json/parse-string
                        (slurp (str (fs/path run-dir "treated.provenance.json"))) true)
            invocations (mapv read-string (str/split-lines (slurp log-path)))
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
        (doseq [{:keys [args cwd herdr session keep]} invocations]
          (is (= ["-p" "--no-session" "--mode" "json" "--model" "anthropic/fake-model" "--"]
                 (butlast args)))
          (is (not (str/starts-with? cwd root)) "each arm runs outside the checkout")
          (is (nil? herdr) "the caller's Herdr variables are removed")
          (is (nil? session) "the caller's Pi session variables are removed")
          (is (= "kept" keep) "other environment passes through"))
        (is (not= (:cwd (first invocations)) (:cwd (second invocations)))
            "each arm gets its own directory")
        (is (= {:provider "fake" :model "fake-model" :api "fake-api"
                :context-files ["/home/AGENTS.md"] :skills 2 :tools ["read" "bash"]
                :mcp-servers ["mcp__one" "mcp__two"] :prompt-sent true :cwd-files-after []}
               (select-keys provenance [:provider :model :api :context-files :skills :tools
                                        :mcp-servers :prompt-sent :cwd-files-after])))
        (is (str/starts-with? (slurp (str (fs/path run-dir "treated.output.md"))) "FAKE RESPONSE"))
        (is (= agents-before (file-tree agents-path))
            "the runner does not write durable .agents configuration")))))

(deftest an-arm-without-a-final-response-fails
  (let [temp (str (fs/create-temp-dir {:dir (str (fs/path root ".tmp"))
                                       :prefix "trait-gate-test-"}))
        log-path (str (fs/path temp "pi.log"))
        _ (write-fake-pi! temp log-path)
        proc (call! [runner "prune" "--model" "anthropic/fake-model"]
                    {:dir root :env (assoc (into {} (System/getenv))
                                           "PATH" (prep-fake-path! temp)
                                           "FAKE_PI_MODE" "silent")})]
    (is (= 1 (:exit proc)))
    (is (str/includes? (:err proc) "treated agent run produced no final response"))
    (is (= 1 (count (str/split-lines (slurp log-path))))
        "the control arm does not run after the treated arm fails")))

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

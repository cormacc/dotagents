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

(defn write-fake-codex! [directory log-path]
  (let [path (fs/path directory "codex")]
    (spit (str path)
          (str "#!/usr/bin/env bb\n"
               "(require '[clojure.string :as str])\n"
               "(let [args *command-line-args*\n"
               "      output (second (drop-while #(not= \"--output-last-message\" %) args))\n"
               "      prompt (slurp *in*)]\n"
               "  (spit \"" log-path "\" (str (pr-str {:args args :codex-home (System/getenv \"CODEX_HOME\")}) \"\\n\") :append true)\n"
               "  (spit output (str \"FAKE RESPONSE\\n\" prompt)))\n"))
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
        log-path (str (fs/path temp "codex.log"))
        _ (write-fake-codex! temp log-path)
        agents-path (fs/path root ".agents")
        agents-before (file-tree agents-path)
        proc (call! [runner "prune" "--model" "gpt-5.6-terra"]
                    {:dir root :env (assoc (into {} (System/getenv))
                                           "PATH" (prep-fake-path! temp))})
        output (:out proc)
        required-markers ["Pass condition, fixed before the run:"
                          "=== TREATED ==="
                          "=== CONTROL ==="
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
        (is (not (str/includes? treated-prompt "## Observed")))
        (is (not (str/includes? output "Verdict: **PASS")))
        (is (= 2 (count invocations)))
        (doseq [{:keys [args codex-home]} invocations]
          (is (some #{"gpt-5.6-terra"} args))
          (is (some #{"--ephemeral"} args))
          (is (some #{"--ignore-user-config"} args))
          (is (= "read-only" (second (drop-while #(not= "--sandbox" %) args))))
          (is (str/starts-with? codex-home (str (fs/path root ".tmp" "trait-gates"))))
          (is (not (fs/exists? codex-home))
              "the isolated Codex configuration is removed after the run"))
        (is (= agents-before (file-tree agents-path))
            "the runner does not write durable .agents configuration")))))

(deftest malformed-gate-fails-with-its-full-path-before-running-an-arm
  (let [temp (str (fs/create-temp-dir {:dir (str (fs/path root ".tmp"))
                                       :prefix "trait-gate-malformed-"}))
        gate (fs/path temp "traits" "broken" "gate.md")
        temp-runner (fs/path temp "scripts" "run-trait-gate.bb")
        traits-bin (fs/path temp "skills" "herdr-orch" "scripts" "traits")
        codex-log (str (fs/path temp "codex.log"))]
    (fs/create-dirs (fs/parent gate))
    (fs/create-dirs (fs/parent temp-runner))
    (fs/create-dirs (fs/parent traits-bin))
    (spit (str gate)
          "# Gate: %broken\n\nPass condition, fixed before the run: never reached.\n\n## Scaffold\n\n%broken\n")
    (fs/copy runner temp-runner)
    (fs/set-posix-file-permissions temp-runner "rwxr-xr-x")
    (spit (str traits-bin) "#!/usr/bin/env bb\n")
    (fs/set-posix-file-permissions traits-bin "rwxr-xr-x")
    (write-fake-codex! temp codex-log)
    (is (zero? (:exit (call! ["git" "init" "--quiet"] {:dir temp}))))
    (let [proc (call! [(str temp-runner) "broken" "--model" "fake-model"]
                      {:dir temp :env (assoc (into {} (System/getenv))
                                             "PATH" (prep-fake-path! temp))})]
      (is (= 1 (:exit proc)))
      (is (str/includes? (:err proc) (str gate)))
      (is (str/includes? (:err proc) "expected exactly one ## Assignment section"))
      (is (not (fs/exists? codex-log))
          "malformed input fails before either direct agent arm runs"))))

(deftest missing-pass-marker-fails-with-its-full-path-before-running-an-arm
  (let [temp (str (fs/create-temp-dir {:dir (str (fs/path root ".tmp"))
                                       :prefix "trait-gate-missing-marker-"}))
        gate (fs/path temp "traits" "broken" "gate.md")
        temp-runner (fs/path temp "scripts" "run-trait-gate.bb")
        traits-bin (fs/path temp "skills" "herdr-orch" "scripts" "traits")
        codex-log (str (fs/path temp "codex.log"))]
    (fs/create-dirs (fs/parent gate))
    (fs/create-dirs (fs/parent temp-runner))
    (fs/create-dirs (fs/parent traits-bin))
    (spit (str gate)
          "# Gate: %broken\n\nCondition: deliberately malformed.\n\n## Scaffold\n\n%broken\n\n## Assignment\n\nDo the work.\n")
    (fs/copy runner temp-runner)
    (fs/set-posix-file-permissions temp-runner "rwxr-xr-x")
    (spit (str traits-bin) "#!/usr/bin/env bb\n")
    (fs/set-posix-file-permissions traits-bin "rwxr-xr-x")
    (write-fake-codex! temp codex-log)
    (is (zero? (:exit (call! ["git" "init" "--quiet"] {:dir temp}))))
    (let [proc (call! [(str temp-runner) "broken" "--model" "fake-model"]
                      {:dir temp :env (assoc (into {} (System/getenv))
                                             "PATH" (prep-fake-path! temp))})]
      (is (= 1 (:exit proc)))
      (is (str/includes? (:err proc) (str gate)))
      (is (str/includes? (:err proc)
                         "expected exactly one 'Pass condition, fixed before the run:' paragraph"))
      (is (not (fs/exists? codex-log))
          "malformed input fails before either direct agent arm runs"))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))

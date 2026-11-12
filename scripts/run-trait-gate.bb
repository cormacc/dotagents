#!/usr/bin/env bb

(require '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(def usage
  (str "Usage: scripts/run-trait-gate.bb <trait> --model <provider/id[:thinking]> [--persona NAME]\n"
       "\n"
       "Runs the treated and control arms from traits/<trait>/gate.md as two\n"
       "independent `pi -p --no-session` calls in the host harness, each from a\n"
       "new directory outside the checkout, with every installed skill and\n"
       "extension loaded. Without --persona, each arm sends the gate's scaffold\n"
       "and assignment as its prompt. With --persona, each arm sends only the\n"
       "assignment, and Pi receives the packaged persona composed by `oh` as an\n"
       "appended system prompt, as a delegated child does; the control arm's\n"
       "persona omits the trait fragment. An audit extension checks every actual\n"
       "provider request for the home AGENTS.md, the persona and the fragment.\n"
       "Outputs and per-arm provenance are written under .tmp/trait-gates/ and\n"
       "printed beside the pre-registered pass condition. The runner reports no\n"
       "verdict; scoring remains human.\n"))

(defn fail! [gate message]
  (throw (ex-info message {:gate gate})))

(defn parse-args [args]
  (loop [remaining args
         options {:trait nil :model nil}]
    (if (empty? remaining)
      options
      (let [[arg value & more] remaining]
        (cond
          (contains? #{"--help" "-h"} arg)
          (recur (rest remaining) (assoc options :help? true))

          (#{"--model" "--persona"} arg)
          (do
            (when-not value
              (throw (ex-info (str arg " requires a value") {})))
            (recur more (assoc options (keyword (subs arg 2)) value)))

          (str/starts-with? arg "--")
          (throw (ex-info (str "unknown option " arg) {}))

          (:trait options)
          (throw (ex-info "expected one trait name" {}))

          :else
          (recur (rest remaining) (assoc options :trait arg)))))))

(defn trim-blank-lines [lines]
  (->> lines
       (drop-while str/blank?)
       reverse
       (drop-while str/blank?)
       reverse
       vec))

(defn indexes-matching [pred lines]
  (keep-indexed (fn [index line] (when (pred line) index)) lines))

(defn exactly-one-index! [gate label pred lines]
  (let [indexes (vec (indexes-matching pred lines))]
    (when-not (= 1 (count indexes))
      (fail! gate (str "expected exactly one " label " section")))
    (first indexes)))

(defn parse-fixture
  "Reads an optional `## Fixture` section after the assignment. Each fenced block whose info
  string is a relative path becomes a file that each arm commits to a new Git repository in
  its directory, so that a write to the fixture shows in `git status`."
  [gate lines assignment-index]
  (let [indexes (vec (indexes-matching #(= "## Fixture" %) lines))]
    (when (> (count indexes) 1)
      (fail! gate "expected at most one ## Fixture section"))
    (when-let [start (first indexes)]
      (when-not (> start assignment-index)
        (fail! gate "## Fixture must follow ## Assignment"))
      (let [end (or (first (filter #(str/starts-with? (nth lines %) "## ")
                                   (range (inc start) (count lines))))
                    (count lines))]
        (loop [remaining (subvec lines (inc start) end) files []]
          (if-let [[open & more] (seq (drop-while #(not (str/starts-with? % "```")) remaining))]
            (let [path (str/trim (subs open 3))
                  [body tail] (split-with #(not= "```" %) more)]
              (when (or (str/blank? path) (fs/absolute? path) (str/includes? path ".."))
                (fail! gate (str "## Fixture block needs a relative path, got '" path "'")))
              (when (empty? tail)
                (fail! gate (str "## Fixture block '" path "' is not closed")))
              (recur (vec (rest tail)) (conj files {:path path :content (str (str/join "\n" body) "\n")})))
            (do (when (empty? files) (fail! gate "## Fixture section has no file block"))
                files)))))))

(defn parse-gate [gate text trait]
  (let [lines (str/split text #"\r?\n" -1)
        scaffold-index (exactly-one-index! gate "## Scaffold" #(= "## Scaffold" %) lines)
        assignment-index (exactly-one-index! gate "## Assignment"
                                             #(boolean (re-matches #"## Assignment(?: -- .*)?" %))
                                             lines)
        pass-indexes (vec (indexes-matching
                           #(str/starts-with? % "Pass condition, fixed before the run:")
                           lines))]
    (when-not (= 1 (count pass-indexes))
      (fail! gate "expected exactly one 'Pass condition, fixed before the run:' paragraph"))
    (when-not (< (first pass-indexes) scaffold-index assignment-index)
      (fail! gate "pass condition, ## Scaffold, and ## Assignment must appear in that order"))
    (let [next-heading-index (or (first (filter #(and (> % assignment-index)
                                                      (str/starts-with? (nth lines %) "## "))
                                                (range (count lines))))
                                 (count lines))
          scaffold (trim-blank-lines (subvec lines (inc scaffold-index) assignment-index))
          assignment (trim-blank-lines (subvec lines (inc assignment-index) next-heading-index))
          token (str "%" trait)
          token-count (count (filter #(= token %) scaffold))
          fixture (parse-fixture gate lines assignment-index)]
      (when (empty? scaffold)
        (fail! gate "## Scaffold section is empty"))
      (when (empty? assignment)
        (fail! gate "## Assignment section is empty"))
      (when-not (= 1 token-count)
        (fail! gate (str "## Scaffold must contain exactly one '" token "' token line")))
      {:pass-condition (nth lines (first pass-indexes))
       :scaffold scaffold
       :assignment assignment
       :token token
       :fixture fixture})))

(defn arm-sources [{:keys [scaffold assignment token]} gate]
  (let [treated-lines (vec (concat scaffold ["" "## Assignment" ""] assignment))
        source-token-count (count (filter #(= token %) treated-lines))
        control-lines (vec (remove #(= token %) treated-lines))]
    (when-not (= 1 source-token-count)
      (fail! gate (str "treated/control sources must differ by exactly one '" token "' line")))
    {:treated (str (str/join "\n" treated-lines) "\n")
     :control (str (str/join "\n" control-lines) "\n")}))

(defn run-process [argv options]
  @(process/process argv (merge {:out :string :err :string} options)))

(defn interpolate-treated! [gate root trait traits-bin treated-source interpolation-path]
  (let [proc (run-process [traits-bin
                           "--file" treated-source
                           "--layer" (str "repository=" root "/traits")
                           "--layer" (str "packaged=" root "/skills/herdr-orch/traits")]
                          {:dir root})]
    (when-not (zero? (:exit proc))
      (fail! gate (str "trait interpolation failed: " (str/trim (:err proc)))))
    (spit interpolation-path (:out proc))
    (let [envelope (json/parse-string (:out proc) true)
          resolved (get-in envelope [:result :resolved])]
      (when-not (and (= true (:ok envelope))
                     (= 1 (count resolved))
                     (= trait (:trait (first resolved)))
                     (empty? (get-in envelope [:result :unknowns]))
                     (empty? (get-in envelope [:result :repeats])))
        (fail! gate (str "interpolator did not resolve exactly '" trait
                         "' with no unknowns or repeats")))
      (get-in envelope [:result :text]))))

(defn fragment-text!
  "The exact text that the interpolator substitutes for the trait, without surrounding blank
  lines. It is the text that the treated arm must carry and the control arm must not."
  [gate root trait traits-bin run-dir]
  (let [source (str (fs/path run-dir "fragment.source.md"))]
    (spit source (str "%" trait "\n"))
    (str/trim (interpolate-treated! gate root trait traits-bin source
                                    (str (fs/path run-dir "fragment.interpolation.json"))))))

(defn persona-text!
  "The effective persona text that `oh` would give a delegated child of `persona` with the
  trait selected, from `--print-prompt`. Like any `oh` launch, the preview needs the caller
  to run inside Herdr or herdr-app, so it keeps the caller environment. It runs outside the
  checkout, so the project persona layer of this repository does not apply. TRAIT_GATE_OH
  replaces the `oh` executable for tests."
  [gate root persona trait]
  (let [oh (or (System/getenv "TRAIT_GATE_OH") (str (fs/path root "skills" "herdr-orch" "scripts" "oh")))
        dir (str (fs/create-temp-dir {:prefix "trait-gate-persona-"}))
        proc (run-process [oh "task" "run" persona "--trait" trait "--task" "trait gate preview" "--print-prompt"]
                          {:dir dir})
        envelope (try (json/parse-string (:out proc) true) (catch Exception _ nil))]
    (when-not (and (zero? (:exit proc)) (true? (:ok envelope)))
      (fail! gate (str "oh could not compose persona '" persona "': " (str/trim (str (:out proc) (:err proc))))))
    (when-not (some #{trait} (get-in envelope [:result :traits]))
      (fail! gate (str "composed persona '" persona "' does not select '" trait "'")))
    (get-in envelope [:result :persona-text])))

(defn without-fragment!
  "The control persona: the treated persona with the one occurrence of the fragment removed."
  [gate treated fragment]
  (let [n (count (re-seq (re-pattern (java.util.regex.Pattern/quote fragment)) treated))]
    (when-not (= 1 n)
      (fail! gate (str "composed persona contains the fragment " n " times; expected once")))
    (str/replace treated fragment "")))

(defn baseline-text! [gate]
  (let [dir (or (System/getenv "PI_CODING_AGENT_DIR") (str (fs/path (fs/home) ".pi" "agent")))
        path (fs/path dir "AGENTS.md")]
    (when-not (fs/regular-file? path)
      (fail! gate (str "the home instruction baseline does not exist: " path)))
    (str/trim (slurp (str path)))))

(def caller-session-env
  "Variables that identify the session that started the runner. A child Pi inheriting them
  could report itself to the caller's Herdr pane or session, so each arm runs without them."
  {:prefixes ["HC_" "HERDR_" "ORCH_"]
   :names #{"PI_CODING_AGENT" "PI_SESSION_FILE" "PI_SESSION_ID" "PI_MODEL" "PI_PROVIDER"
            "PI_REASONING_LEVEL"}})

(defn arm-env []
  (let [{:keys [prefixes names]} caller-session-env]
    (into {} (remove (fn [[k _]] (or (contains? names k)
                                     (some #(str/starts-with? k %) prefixes))))
          (System/getenv))))

(defn parse-events [text]
  (into [] (keep #(try (json/parse-string % true) (catch Exception _ nil)))
        (str/split-lines text)))

(defn message-text [message]
  (->> (:content message)
       (filter #(= "text" (:type %)))
       (map :text)
       (str/join "\n")))

(defn provenance
  "Records what the arm's session actually loaded, from the system and user messages that Pi
  emits in JSON mode, so a scorer can confirm the deployed baseline rather than assume it."
  [events prompt arm-dir fixture?]
  (let [messages (keep #(when (= "message_end" (:type %)) (:message %)) events)
        system (first (filter #(= "system" (:role %)) messages))
        user (first (filter #(= "user" (:role %)) messages))
        assistant (last (filter #(= "assistant" (:role %)) messages))
        sections (:sections system)]
    {:provider (:provider assistant)
     :model (:model assistant)
     :api (:api assistant)
     :stop-reason (:stopReason assistant)
     :assistant-messages (count (filter #(= "assistant" (:role %)) messages))
     :context-files (vec (map second (re-seq #"<project_instructions path=\"([^\"]+)\">"
                                             (str (:project_context sections)))))
     :skills (count (re-seq #"<skill>" (str (:skills sections))))
     :tools (vec (keep :name (:toolsAdded system)))
     :mcp-servers (vec (map second (re-seq #"(?m)^- (mcp__[^ ]+)" (str (:mcp_servers sections)))))
     :prompt-sent (= (str/trim prompt) (str/trim (message-text user)))
     :cwd arm-dir
     :cwd-files-after (if fixture?
                        []
                        (vec (sort (map #(str (fs/relativize arm-dir %))
                                        (fs/glob arm-dir "**" {:hidden true})))))}))

(defn git! [gate dir & args]
  (let [proc (run-process (into ["git" "-c" "user.name=trait-gate" "-c" "user.email=trait-gate@invalid"
                                 "-c" "commit.gpgsign=false"] args)
                          {:dir dir})]
    (when-not (zero? (:exit proc))
      (fail! gate (str "git " (str/join " " args) " failed in " dir ": " (str/trim (:err proc)))))
    (:out proc)))

(defn commit-fixture! [gate arm-dir fixture]
  (doseq [{:keys [path content]} fixture]
    (let [file (fs/path arm-dir path)]
      (fs/create-dirs (fs/parent file))
      (spit (str file) content)))
  (git! gate arm-dir "init" "-q")
  (git! gate arm-dir "add" "-A")
  (git! gate arm-dir "commit" "-q" "-m" "fixture"))

(defn audit-result
  "Reads the audit extension's lines and compares every request with the expected presence."
  [audit-path want]
  (let [lines (when (fs/exists? audit-path)
                (mapv #(json/parse-string % false) (str/split-lines (slurp audit-path))))
        controls (get (first lines) "controls")
        requests (mapv #(get % "present") (rest lines))
        failures (cond-> []
                   (not= {"positive" true "missing" false "knownBad" false} controls)
                   (conj (str "matcher controls " (pr-str controls)))
                   (empty? requests)
                   (conj "no provider request was observed")
                   (some #(not= want %) requests)
                   (conj (str "expected " (pr-str want) " in every request, observed " (pr-str requests))))]
    {:controls controls :requests (count requests) :failures failures}))

(defn run-arm! [gate model run-dir {:keys [arm prompt system expect want]} fixture audit-extension]
  (let [arm-dir (str (fs/canonicalize (fs/create-temp-dir {:prefix (str "trait-gate-" arm "-")})))
        _ (when fixture (commit-fixture! gate arm-dir fixture))
        system-path (str (fs/path run-dir (str arm ".system.md")))
        expect-path (str (fs/path run-dir (str arm ".audit-expect.json")))
        audit-path (str (fs/path run-dir (str arm ".audit.jsonl")))
        _ (when system (spit system-path system))
        _ (spit expect-path (json/generate-string expect))
        proc (run-process (vec (concat ["pi" "-p" "--no-session" "--mode" "json" "--model" model]
                                       (when system ["--append-system-prompt" system-path])
                                       ["-e" audit-extension "--" prompt]))
                          {:dir arm-dir :in ""
                           :env (assoc (arm-env) "TRAIT_GATE_AUDIT_EXPECT" expect-path
                                       "TRAIT_GATE_AUDIT_OUT" audit-path)})
        events (parse-events (:out proc))
        audit (audit-result audit-path want)
        record (cond-> (assoc (provenance events prompt arm-dir (boolean fixture))
                              :audit (dissoc audit :failures))
                 fixture (assoc :fixture-status (git! gate arm-dir "status" "--porcelain")
                                :fixture-diff (git! gate arm-dir "diff")))
        output (str (fs/path run-dir (str arm ".output.md")))]
    (spit (str (fs/path run-dir (str arm ".events.jsonl"))) (:out proc))
    (spit (str (fs/path run-dir (str arm ".stderr.log"))) (:err proc))
    (spit (str (fs/path run-dir (str arm ".provenance.json")))
          (json/generate-string record {:pretty true}))
    (when-not (zero? (:exit proc))
      (fail! gate (str arm " agent run failed; see " run-dir "/" arm ".stderr.log")))
    (when-not (:prompt-sent record)
      (fail! gate (str arm " session did not record the exact arm prompt as its user message")))
    (when (contains? #{"error" "aborted"} (:stop-reason record))
      (fail! gate (str arm " agent run stopped with " (:stop-reason record))))
    (when (seq (:failures audit))
      (fail! gate (str arm " observation is invalid: " (str/join "; " (:failures audit)))))
    (let [text (some->> events
                        (keep #(when (= "message_end" (:type %)) (:message %)))
                        (filter #(= "assistant" (:role %)))
                        last
                        message-text)]
      (when (str/blank? text)
        (fail! gate (str arm " agent run produced no final response")))
      (spit output text)
      {:text text :provenance record})))

(defn provenance-line [arm {:keys [provider model context-files skills tools mcp-servers cwd-files-after audit]
                            :as record}]
  (str arm ": " provider "/" model "; " (count context-files) " context files " (pr-str context-files)
       "; " skills " skills; " (count tools) " tools; " (count mcp-servers) " MCP servers; "
       (:requests audit) " audited requests; "
       (cond
         (contains? record :fixture-status)
         (if (str/blank? (:fixture-status record))
           "fixture unchanged"
           (str "fixture CHANGED: " (str/trim (:fixture-status record))))
         (empty? cwd-files-after) "arm directory unchanged"
         :else (str "WARNING: arm directory now holds " (pr-str cwd-files-after)))))

(defn run! [{:keys [trait model persona]}]
  (when-not (re-matches #"[a-z][a-z0-9-]*" (or trait ""))
    (throw (ex-info (str "invalid trait name " trait) {})))
  (when (str/blank? model)
    (throw (ex-info "--model is required" {})))
  (let [root (str/trim (:out (run-process ["git" "rev-parse" "--show-toplevel"] {})))
        gate (str (fs/path root "traits" trait "gate.md"))
        traits-bin (str (fs/path root "skills" "herdr-orch" "scripts" "traits"))]
    (when-not (fs/regular-file? gate)
      (fail! gate "gate file does not exist"))
    (let [parsed (parse-gate gate (slurp gate) trait)
          {:keys [treated control]} (arm-sources parsed gate)
          _traits-check (when-not (fs/executable? traits-bin)
                          (fail! gate (str "trait interpolator is not executable: " traits-bin)))
          _pi-check (when-not (fs/which "pi")
                      (fail! gate "pi is not available on PATH"))
          run-dir (str (fs/path root ".tmp" "trait-gates"
                                (str trait "-" (System/currentTimeMillis) "-"
                                     (.pid (java.lang.ProcessHandle/current)))))
          treated-source (str (fs/path run-dir "treated.source.md"))
          control-prompt (str (fs/path run-dir "control.prompt.md"))
          treated-prompt (str (fs/path run-dir "treated.prompt.md"))
          interpolation (str (fs/path run-dir "treated.interpolation.json"))]
      (fs/create-dirs run-dir)
      (spit (str (fs/path run-dir "scaffold.md")) (str (str/join "\n" (:scaffold parsed)) "\n"))
      (spit (str (fs/path run-dir "assignment.md")) (str (str/join "\n" (:assignment parsed)) "\n"))
      (spit treated-source treated)
      (spit control-prompt control)
      (let [treated-text (interpolate-treated! gate root trait traits-bin treated-source interpolation)
            fragment (fragment-text! gate root trait traits-bin run-dir)
            baseline (baseline-text! gate)
            audit-extension (str (fs/path root "scripts" "trait-gate-audit.ts"))
            arms (if persona
                   (let [assignment (str (str/join "\n" (:assignment parsed)) "\n")
                         treated-persona (persona-text! gate root persona trait)
                         control-persona (without-fragment! gate treated-persona fragment)
                         arm (fn [name text has-fragment?]
                               {:arm name :prompt assignment :system text
                                :expect {"baseline" baseline "persona" (str/trim text) "fragment" fragment}
                                :want {"baseline" true "persona" true "fragment" has-fragment?}})]
                     [(arm "treated" treated-persona true) (arm "control" control-persona false)])
                   (let [arm (fn [name text has-fragment?]
                               {:arm name :prompt text
                                :expect {"baseline" baseline "fragment" fragment}
                                :want {"baseline" true "fragment" has-fragment?}})]
                     [(arm "treated" treated-text true) (arm "control" control false)]))
            status-before (:out (run-process ["git" "status" "--porcelain"] {:dir root}))]
        (spit treated-prompt treated-text)
        (let [treated (run-arm! gate model run-dir (first arms) (:fixture parsed) audit-extension)
              control (run-arm! gate model run-dir (second arms) (:fixture parsed) audit-extension)
              status-after (:out (run-process ["git" "status" "--porcelain"] {:dir root}))]
          (println (str "Gate: %" trait))
          (println (str "Model: " model))
          (println (str "Mode: " (if persona (str "packaged persona " persona) "scaffold")))
          (println (provenance-line "Treated" (:provenance treated)))
          (println (provenance-line "Control" (:provenance control)))
          (when-not (= status-before status-after)
            (println "WARNING: the checkout's git status changed during the run; inspect it before scoring."))
          (println "Pass condition:")
          (println (:pass-condition parsed))
          (println "\n=== TREATED ===")
          (println (:text treated))
          (println "\n=== CONTROL ===")
          (println (:text control))
          (println (str "\nOutputs: " run-dir))
          (println "No verdict emitted; compare both outputs against the pass condition."))))))

(try
  (let [options (parse-args *command-line-args*)]
    (if (:help? options)
      (print usage)
      (run! options)))
  (catch Exception error
    (let [gate (:gate (ex-data error))]
      (binding [*out* *err*]
        (println (str "ERROR: " (when gate (str gate ": ")) (ex-message error)))))
    (System/exit 1)))

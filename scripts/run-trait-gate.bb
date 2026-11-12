#!/usr/bin/env bb

(require '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(def usage
  (str "Usage: scripts/run-trait-gate.bb <trait> --model <provider/id[:thinking]>\n"
       "\n"
       "Runs the treated and control arms from traits/<trait>/gate.md as two\n"
       "independent `pi -p --no-session` calls in the host harness, each from an\n"
       "empty directory outside the checkout, with every installed skill and\n"
       "extension loaded. Outputs and per-arm provenance are written under\n"
       ".tmp/trait-gates/ and printed beside the pre-registered pass condition.\n"
       "The runner reports no verdict; scoring remains human.\n"))

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

          (= "--model" arg)
          (do
            (when-not value
              (throw (ex-info "--model requires a value" {})))
            (recur more (assoc options :model value)))

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
          token-count (count (filter #(= token %) scaffold))]
      (when (empty? scaffold)
        (fail! gate "## Scaffold section is empty"))
      (when (empty? assignment)
        (fail! gate "## Assignment section is empty"))
      (when-not (= 1 token-count)
        (fail! gate (str "## Scaffold must contain exactly one '" token "' token line")))
      {:pass-condition (nth lines (first pass-indexes))
       :scaffold scaffold
       :assignment assignment
       :token token})))

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
  [events prompt arm-dir]
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
     :cwd-files-after (vec (sort (map #(str (fs/relativize arm-dir %))
                                      (fs/glob arm-dir "**" {:hidden true}))))}))

(defn run-arm! [gate model run-dir arm prompt]
  (let [arm-dir (str (fs/canonicalize (fs/create-temp-dir {:prefix (str "trait-gate-" arm "-")})))
        proc (run-process ["pi" "-p" "--no-session" "--mode" "json" "--model" model "--" prompt]
                          {:dir arm-dir :env (arm-env) :in ""})
        events (parse-events (:out proc))
        record (provenance events prompt arm-dir)
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
    (let [text (some->> events
                        (keep #(when (= "message_end" (:type %)) (:message %)))
                        (filter #(= "assistant" (:role %)))
                        last
                        message-text)]
      (when (str/blank? text)
        (fail! gate (str arm " agent run produced no final response")))
      (spit output text)
      {:text text :provenance record})))

(defn provenance-line [arm {:keys [provider model context-files skills tools mcp-servers cwd-files-after]}]
  (str arm ": " provider "/" model "; " (count context-files) " context files " (pr-str context-files)
       "; " skills " skills; " (count tools) " tools; " (count mcp-servers) " MCP servers; "
       (if (empty? cwd-files-after)
         "arm directory unchanged"
         (str "WARNING: arm directory now holds " (pr-str cwd-files-after)))))

(defn run! [{:keys [trait model]}]
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
            status-before (:out (run-process ["git" "status" "--porcelain"] {:dir root}))]
        (spit treated-prompt treated-text)
        (let [treated (run-arm! gate model run-dir "treated" treated-text)
              control (run-arm! gate model run-dir "control" control)
              status-after (:out (run-process ["git" "status" "--porcelain"] {:dir root}))]
          (println (str "Gate: %" trait))
          (println (str "Model: " model))
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

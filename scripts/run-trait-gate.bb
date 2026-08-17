#!/usr/bin/env bb

(require '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(def usage
  (str "Usage: scripts/run-trait-gate.bb <trait> --model <codex-model>\n"
       "\n"
       "Runs the treated and control arms from traits/<trait>/gate.md with two\n"
       "independent, ephemeral, read-only `codex exec` calls. Outputs are written\n"
       "under .tmp/trait-gates/ and printed beside the pre-registered pass\n"
       "condition. The runner reports no verdict; scoring remains human.\n"))

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

(defn caller-codex-home []
  (or (System/getenv "CODEX_HOME")
      (str (fs/path (fs/home) ".codex"))))

(defn run-arm! [gate root model run-dir codex-home arm prompt]
  (let [output (str (fs/path run-dir (str arm ".output.md")))
        proc (run-process ["codex" "exec"
                           "--model" model
                           "--sandbox" "read-only"
                           "--cd" root
                           "--ephemeral"
                           "--ignore-user-config"
                           "--output-last-message" output
                           "-"]
                          {:dir root
                           :env (assoc (into {} (System/getenv)) "CODEX_HOME" codex-home)
                           :in prompt})]
    (spit (str (fs/path run-dir (str arm ".stdout.log"))) (:out proc))
    (spit (str (fs/path run-dir (str arm ".stderr.log"))) (:err proc))
    (when-not (zero? (:exit proc))
      (fail! gate (str arm " agent run failed; see " run-dir "/" arm ".stderr.log")))
    (when-not (and (fs/regular-file? output) (pos? (fs/size output)))
      (fail! gate (str arm " agent run produced no final response")))
    (slurp output)))

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
          _codex-check (when-not (fs/which "codex")
                         (fail! gate "codex is not available on PATH"))
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
            codex-home (caller-codex-home)]
        (spit treated-prompt treated-text)
        (let [treated-output (run-arm! gate root model run-dir codex-home "treated" treated-text)
              control-output (run-arm! gate root model run-dir codex-home "control" control)]
          (println (str "Gate: %" trait))
          (println (str "Model: " model))
          (println "Pass condition:")
          (println (:pass-condition parsed))
          (println "\n=== TREATED ===")
          (println treated-output)
          (println "\n=== CONTROL ===")
          (println control-output)
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

(ns herdr-orch.measurement
  "Subject launch and transport helper for isolated Pi measurement runs.

  Pure of orchestration state: it validates the closed measurement configuration, freezes and
  compiles the treatment, builds the subject argv/env, reads back terminal evidence, and reads
  process identity for the headless execution. The headless supervisor that owns a run
  (herdr-orch.supervisor) reuses these functions rather than rebuilding argv, stdin, resource
  selection or request auditing. The Pi-side adapter (scripts/pi/measurement-adapter.ts)
  enforces the request-context audit inside the subject."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [herdr-orch.core :as core]))

;; Neutral CLI message the adapter replaces with the literal assignment, so argv never carries
;; assignment text.
(def input-sentinel "herdr-orch-measurement-input")
(def config-fields #{"systemPromptFile" "skillFiles" "tools" "extensions" "hardTimeoutMs" "expectedOutputs"})
(def subject-marker "HERDR_ORCH_SUBJECT")

(defn- fail [message data] (throw (ex-info message data)))

(defn- string-list [config field]
  (let [value (get config field [])]
    (when-not (and (sequential? value) (every? #(and (string? %) (not (str/blank? %))) value))
      (fail (str "measurement config `" field "` must be a list of non-blank strings") {:field field :value value}))
    (vec value)))

(defn- resolve-file [base field path]
  (let [resolved (fs/path (fs/parent base) path)
        absolute (str (fs/absolutize resolved))]
    (when-not (fs/regular-file? absolute)
      (fail (str "measurement config `" field "` names an unreadable file") {:field field :path path :resolved absolute}))
    absolute))

(defn- expected-output! [path]
  (let [normal (fs/normalize (fs/path path))]
    (when (or (fs/absolute? normal) (str/starts-with? (str normal) ".."))
      (fail "measurement config `expectedOutputs` must stay inside the work root" {:path path}))
    (str normal)))

(defn read-config
  "Validates the closed JSON configuration at `config-path` and freezes every resolved input.
  Input paths resolve against the configuration file; `expectedOutputs` stay relative to the
  work root. Unknown fields, invalid types, unreadable inputs and wildcard tool names fail before
  any launch. Returns file texts rather than paths alone so later edits cannot change a run."
  [config-path]
  (let [config (try (json/parse-string (slurp config-path))
                    (catch java.io.FileNotFoundException _ (fail "measurement config is unreadable" {:path config-path}))
                    (catch Exception e (fail "measurement config is not valid JSON" {:path config-path :cause (.getMessage e)})))
        _ (when-not (map? config) (fail "measurement config must be a JSON object" {:path config-path}))
        unknown (remove config-fields (keys config))
        _ (when (seq unknown) (fail (str "measurement config has unknown fields: " (str/join ", " (sort unknown))) {:unknown (vec (sort unknown))}))
        timeout (get config "hardTimeoutMs")
        _ (when-not (and (integer? timeout) (pos? timeout))
            (fail "measurement config `hardTimeoutMs` is required and must be a positive integer" {:value timeout}))
        system-file (get config "systemPromptFile")
        _ (when (and (some? system-file) (not (and (string? system-file) (not (str/blank? system-file)))))
            (fail "measurement config `systemPromptFile` must be a non-blank string" {:value system-file}))
        tools (string-list config "tools")
        _ (when-let [bad (seq (filter #(re-find #"[*?,\s]" %) tools))]
            (fail "measurement config `tools` are exact names, not patterns or lists" {:tools (vec bad)}))
        _ (when (not= (count tools) (count (distinct tools)))
            (fail "measurement config `tools` names a tool twice" {:tools tools}))
        freeze (fn [field path] (let [p (resolve-file config-path field path)] {:path p :text (slurp p)}))]
    {:config-path (str (fs/absolutize config-path))
     :system-prompt (when system-file (freeze "systemPromptFile" system-file))
     :skills (mapv #(freeze "skillFiles" %) (string-list config "skillFiles"))
     :tools tools
     :extensions (mapv #(resolve-file config-path "extensions" %) (string-list config "extensions"))
     :hard-timeout-ms timeout
     :expected-outputs (mapv expected-output! (string-list config "expectedOutputs"))}))

(defn compile-treatment
  "The system file text followed by each selected skill file's literal text, joined by two
  newlines. An absent or empty part contributes nothing, so the default treatment is empty."
  [{:keys [system-prompt skills]}]
  (->> (cons (:text system-prompt) (map :text skills))
       (remove str/blank?)
       (str/join "\n\n")))

(defn check-expected-outputs!
  "Refuses a target that already exists: existence alone would not prove this run produced it."
  [{:keys [expected-outputs]} work-root]
  (doseq [path expected-outputs]
    (let [target (fs/path work-root path)]
      (when (fs/exists? target {:nofollow-links true})
        (fail "measurement `expectedOutputs` target already exists" {:path path :work-root (str work-root)})))))

(defn subject-env
  "The subject's environment: the caller's with every Herdr routing/authority value removed
  (HERDR_* and ORCH_*), plus the task-bound subject marker. Provider credentials stay."
  [parent-env task]
  (-> (into {} (remove (fn [[k _]] (or (str/starts-with? k "HERDR_") (str/starts-with? k "ORCH_")))) parent-env)
      (assoc subject-marker task)))

(defn launch-spec
  "Freezes one run under `evidence-dir` and returns what a supervisor needs to start the subject.
  `assignment` is the literal text; it is written to a file and replaced into the model's input
  by the adapter, never placed on argv. `model-args` and `effort-args` are the resolved native
  arguments. The subject's stdin is closed by the caller (`:in nil` / `< /dev/null`)."
  [{:keys [resolved task assignment evidence-dir adapter-dir model-args effort-args parent-env pi-bin]}]
  (let [dir (str (fs/absolutize evidence-dir))
        assignment-file (str (fs/path dir "assignment.txt"))
        manifest-path (str (fs/path dir "manifest.json"))
        treatment (compile-treatment resolved)
        adapter (str (fs/path adapter-dir "measurement-adapter.ts"))]
    (fs/create-dirs dir)
    (spit assignment-file assignment)
    (spit manifest-path (json/generate-string {:treatment treatment :assignmentFile assignment-file
                                               :tools (:tools resolved) :evidenceDir (str (fs/path dir "adapter"))}))
    (spit (str (fs/path dir "treatment.txt")) treatment)
    (spit (str (fs/path dir "inputs.json"))
          (json/generate-string {:treatment-sha256 (core/sha256 treatment) :assignment-sha256 (core/sha256 assignment)
                                 :system-prompt (when-let [s (:system-prompt resolved)] {:path (:path s) :sha256 (core/sha256 (:text s))})
                                 :skills (mapv (fn [s] {:path (:path s) :sha256 (core/sha256 (:text s))}) (:skills resolved))
                                 :tools (:tools resolved) :extensions (:extensions resolved)
                                 :hard-timeout-ms (:hard-timeout-ms resolved) :expected-outputs (:expected-outputs resolved)}))
    {:argv (vec (concat [(or pi-bin "pi") "--offline" "--no-session" "-ne"]
                        (mapcat (fn [e] ["-e" e]) (:extensions resolved))
                        ;; Last, so the adapter audits after every other extension's changes.
                        ["-e" adapter "--no-skills" "--no-prompt-templates" "--no-context-files" "--no-mcp"]
                        model-args effort-args
                        (if (seq (:tools resolved)) ["--tools" (str/join "," (:tools resolved))] ["--no-tools"])
                        ["--mode" "json" "-p" input-sentinel]))
     :env (assoc (subject-env parent-env task) "HERDR_ORCH_MEASUREMENT_MANIFEST" manifest-path)
     :manifest-path manifest-path
     :evidence-dir dir
     :adapter-evidence-dir (str (fs/path dir "adapter"))}))

(defn- read-json [path]
  (try (json/parse-string (slurp (str path)) true) (catch Exception _ ::malformed)))

(defn- audit-index [path]
  (some-> (re-find #"^audit-(\d+)\.json$" (str (fs/file-name path))) second parse-long))

(defn interpret-evidence
  "Reads the adapter's terminal evidence. Success needs a well-formed `terminal.json` with
  `success: true`, no violations, and, for every request the terminal evidence records, a
  retained `request-N.json` and an `audit-N.json` that is `ok` with no violations. The recorded
  request count is the authority: a missing audit, an audit file beyond the count, a missing
  request payload or a missing count are failures, so no request can have been sent unaudited.
  Anything missing or malformed is a failure with reasons; this is execution success, not
  answer quality."
  [adapter-evidence-dir]
  (let [terminal-file (fs/path adapter-evidence-dir "terminal.json")
        terminal (when (fs/exists? terminal-file) (read-json terminal-file))
        requests (when (map? terminal) (:requests terminal))
        counted? (and (integer? requests) (not (neg? requests)))
        indexes (into (sorted-set) (keep audit-index) (fs/glob adapter-evidence-dir "audit-*.json"))
        audits (into {} (map (fn [i] [i (read-json (fs/path adapter-evidence-dir (str "audit-" i ".json")))])) indexes)
        audit-ok? (fn [a] (and (map? a) (true? (:ok a)) (empty? (:violations a))))
        per-request (when counted?
                      (for [i (range 1 (inc requests))
                            reason [(when-not (contains? audits i) (str "audit evidence for request " i " is missing"))
                                    (when (and (contains? audits i) (not (audit-ok? (get audits i)))) (str "a request-context audit failed (request " i ")"))
                                    (when (let [f (fs/path adapter-evidence-dir (str "request-" i ".json"))]
                                            (or (not (fs/exists? f)) (= ::malformed (read-json f))))
                                      (str "request payload evidence for request " i " is missing or malformed"))]
                            :when reason]
                        reason))
        reasons (cond-> []
                  (nil? terminal) (conj "terminal evidence is missing")
                  (= ::malformed terminal) (conj "terminal evidence is malformed")
                  (and (map? terminal) (not (true? (:success terminal)))) (conj (str "harness reported failure" (when-let [r (:reason terminal)] (str ": " r))))
                  (and (map? terminal) (seq (:violations terminal))) (conj "the run recorded violations")
                  (and (map? terminal) (not counted?)) (conj "terminal evidence records no valid request count")
                  (empty? indexes) (conj "no request-context audit was recorded")
                  (and counted? (some #(> % requests) indexes)) (conj "audit evidence is inconsistent with the recorded request count")
                  (seq per-request) (into per-request))]
    {:success? (empty? reasons) :reasons reasons :terminal (when (map? terminal) terminal) :audits (count indexes)}))

;; --- headless execution identity --------------------------------------------------------
;; A PID alone proves nothing after reuse, so an identity is the PID plus its kernel start time,
;; process group and session, read from /proc/<pid>/stat. The supervisor is a session leader
;; (setsid), and its session is the ownership unit of the run: the subject and every descendant
;; that does not leave the session belong to it. Linux does not reissue a session id while any
;; member of that session lives, so while the run's session has a member, every live process
;; with the recorded session id is the run's own. Once every member has exited, the number can
;; be reissued to an unrelated session leader after PID wrap. Its processes then read as
;; members, and the run reads as running: close, prune and worktree removal refuse until that
;; session ends. That direction is fail-safe, because only the supervisor signals members of
;; its own session, and it does so while it still leads it.
(defn- read-stat [pid]
  (try
    (let [text (String. (java.nio.file.Files/readAllBytes (java.nio.file.Paths/get (str "/proc/" pid "/stat") (make-array String 0))))
          ;; The command name may contain spaces or parentheses; the fields after it do not.
          fields (str/split (str/trim (subs text (+ 2 (str/last-index-of text ")")))) #" ")]
      {:pid (long pid) :state (nth fields 0) :ppid (parse-long (nth fields 1)) :pgid (parse-long (nth fields 2))
       :sid (parse-long (nth fields 3)) :start (parse-long (nth fields 19))})
    (catch Exception _ nil)))
(defn process-identity
  "The identity of a live process, or nil when it does not exist."
  [pid]
  (some-> (read-stat pid) (select-keys [:pid :start :pgid :sid])))
(defn identity-alive?
  "True only when the PID exists with the recorded start time and is not a zombie."
  [{:keys [pid start]}]
  (let [stat (when pid (read-stat pid))]
    (boolean (and stat (= start (:start stat)) (not= "Z" (:state stat))))))
(defn session-members
  "Identities of every live, non-zombie process in session `sid`."
  [sid]
  (if-not sid
    []
    (->> (fs/list-dir "/proc")
         (keep #(some-> (re-matches #"\d+" (str (fs/file-name %))) parse-long read-stat))
         (filterv #(and (= sid (:sid %)) (not= "Z" (:state %))))
         (mapv #(select-keys % [:pid :start :pgid :sid])))))
(defn stop-session!
  "Ends every member of session `sid` except `self-pid`: TERM, then KILL after a grace period,
  until none remain or the bound expires. Returns the members still alive (empty on success)."
  [sid self-pid]
  (let [others #(vec (remove (fn [m] (= self-pid (:pid m))) (session-members sid)))
        ;; The identity is re-read just before each signal, so a PID that exited and was
        ;; reused since the listing is not signalled.
        signal! (fn [members forcibly?]
                  (doseq [member members :when (identity-alive? member)]
                    (when-let [handle (.orElse (java.lang.ProcessHandle/of (:pid member)) nil)]
                      (if forcibly? (.destroyForcibly handle) (.destroy handle)))))
        started (System/currentTimeMillis)
        grace-ms 2000
        bound-ms 10000]
    (signal! (others) false)
    (loop []
      (let [remaining (others) elapsed (- (System/currentTimeMillis) started)]
        (cond (empty? remaining) []
              (< bound-ms elapsed) remaining
              :else (do (when (< grace-ms elapsed) (signal! remaining true))
                        (Thread/sleep 100)
                        (recur)))))))
;; Detachment needs a new session (`setsid`) and session membership needs /proc. Both are
;; verified on Linux only; any other platform is refused before a subject launches rather than
;; assumed to behave the same.
(defn platform []
  {:os-name (System/getProperty "os.name") :setsid (some-> (fs/which "setsid") str)
   :proc? (fs/readable? "/proc/self/stat")})
(defn detach-refusal
  "Nil when headless detachment is supported on `platform`, else the refusal message."
  [{:keys [os-name setsid proc?]}]
  (cond (not= "Linux" os-name) (str "--context measurement supports detached supervision on Linux only; this platform is " os-name)
        (not setsid) "--context measurement needs `setsid` on PATH to detach its supervisor"
        (not proc?) "--context measurement needs a readable /proc to record process identity"
        :else nil))

(defn -main
  "Debug and test entry: `launch-spec CONFIG TASK ASSIGNMENT-FILE EVIDENCE-DIR ADAPTER-DIR [MODEL-ARG]...`
  prints the launch spec as JSON."
  [& [op config task assignment-file evidence-dir adapter-dir & model-args]]
  (case op
    "launch-spec" (println (json/generate-string
                            (launch-spec {:resolved (read-config config) :task task :assignment (slurp assignment-file)
                                          :evidence-dir evidence-dir :adapter-dir adapter-dir
                                          :model-args (vec model-args) :effort-args [] :parent-env (into {} (System/getenv))})))
    (fail "unknown measurement command" {:command op})))

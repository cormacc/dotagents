(ns herdr-subagents.cli
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [herdr-subagents.core :as core]
            [herdr-subagents.herdr :as herdr]
            [herdr-subagents.ledger :as ledger])
  (:import [java.nio.file Files FileAlreadyExistsException Paths]
           [java.util UUID]))

(def usage "subagent run|start <persona> --task TEXT [options]\nsubagent collect <task> [--wait --timeout MS]\nsubagent status [task] | list
subagent publish --status STATUS --summary TEXT [--artifact PATH]* [--finding TEXT]* [--next TEXT] [--process TEXT]*\n\nOpaque assignment input is --task, --task-file, or stdin. Run `subagent --help` for contract details.")
(defn fail [message data] (throw (ex-info message data)))
(defn now [] (str (java.time.Instant/now)))
;; Zero is truthy in Clojure and `Thread/sleep` rejects negatives, so only a
;; strictly positive parse overrides the default.
(defn parse-poll-interval [raw]
  (let [n (some-> raw str/trim not-empty parse-long)]
    (if (and n (pos? n)) n 1000)))
(defn poll-interval-ms [] (parse-poll-interval (System/getenv "SUBAGENT_POLL_INTERVAL_MS")))
;; Single source of truth for value-less flags. `option-map` and `help-request?` both
;; consume argv and must agree: a flag known to only one of them silently swallows the
;; following element (e.g. `run worker --retro --task 'X'` losing its assignment).
(def boolean-flags #{"--wait" "--print-prompt" "--retro" "--no-retro" "--tab"})
(defn option-map [args]
  (loop [xs args out {}]
    (if-let [x (first xs)]
      (cond (boolean-flags x) (recur (next xs) (assoc out (keyword (subs x 2)) true))
            (str/starts-with? x "--") (let [key (keyword (subs x 2)) value (second xs)]
                                          (when-not value (fail "option requires a value" {:option x}))
                                          (recur (nnext xs) (update out key (fnil conj []) value)))
            :else (recur (next xs) (update out :_ (fnil conj []) x))) out)))
(defn one [opts k] (let [value (get opts k)] (if (sequential? value) (last value) value)))
(defn all [opts k] (get opts k []))
(defn task-text [opts]
  (let [choices (remove nil? [(one opts :task) (one opts :task-file) (when (and (nil? (one opts :task)) (nil? (one opts :task-file)) (nil? (System/console))) "-")])]
    (when-not (= 1 (count choices)) (fail "provide exactly one of --task, --task-file, or stdin" {}))
    (let [v (first choices)] (cond (= v "-") (slurp *in*) (and (one opts :task-file) (= v (one opts :task-file))) (slurp v) :else v))))
(defn persona-names [dir]
  (if (fs/directory? dir)
    (mapv #(str (fs/strip-ext (fs/file-name %))) (fs/glob dir "*.md"))
    []))
(defn available-personas []
  (let [root (ledger/assignment-root) home (System/getProperty "user.home")]
    (->> (concat (persona-names (fs/path root ".agents" "subagents"))
                 (persona-names (fs/path home ".agents" "subagents")))
         distinct sort vec)))
(defn roster [persona]
  (let [path (core/roster-path #(fs/exists? %) (ledger/assignment-root) (System/getProperty "user.home") persona)]
    (or (some-> path fs/path)
        (fail "persona not found in project or global roster" {:persona persona :available (available-personas)}))))
(defn child-name [persona task] (str persona "-" (subs task 0 8)))
(defn launcher-bin []
  (or (System/getenv "HERDR_SUBAGENT_BIN")
      (let [candidate (fs/path (ledger/assignment-root) "skills" "herdr-subagents" "scripts" "subagent")]
        (when (fs/exists? candidate) (str (fs/absolutize candidate))))
      (fail "could not resolve subagent launcher" {})))
(defn parent-identity []
  (let [agent (herdr/agent! (System/getenv "HERDR_PANE_ID"))]
    {:parent-session (or (get-in agent [:agent_session :value]) (:pane_id agent)) :parent-kind (:agent agent) :parent-pane (:pane_id agent)}))
(defn delegation-guidance [persona]
  (if (= persona "planner")
    "You may spawn at most one blocking ephemeral scout or researcher only when a factual gap blocks planning; that child must remain a leaf."
    "You are a leaf: do not spawn subagents."))
(defn retro-instruction [retro-skill]
  (when retro-skill
    (str "\nBefore publishing, apply steps 1-2 of " retro-skill " to your own session, using that skill's own threshold and signal categories."
         "\nEmit each surviving candidate as one `--process` item shaped `signal → category → proposed rule` (at most five)."
         "\nEmit nothing when the session does not meet that threshold; an absent PROCESS section is a valid outcome, not a failure."
         "\nDo not choose a destination, load `self-improvement`, run `ot`, or edit any instruction file: the parent owns approval and persistence.")))
(defn prompt-text [{:keys [persona persona-path task result waiting-policy assignment prompt-extra retro-skill]}]
  (str "Read " persona-path ", adopt that role. Task: " assignment "\n\n"
       (delegation-guidance persona) " Herdr assigned TASK=" task " and RESULT=" result ". "
       "When finished, publish exactly once with `$HERDR_SUBAGENT_BIN publish --status COMPLETE --summary \"...\"`; do not send result text to the parent PTY. "
       "If you cannot finish — an unrecoverable failure after reasonable retries, or a genuine blocking dependency — publish once with `--status BLOCKED` (dependency) or `--status FAILED` (unrecoverable), summarising work completed vs remaining; never stop silently or publish a second envelope after recovering. "
       "The waiting policy is " waiting-policy "."
       (retro-instruction retro-skill)
       (when prompt-extra (str "\nAdditional constraints: " prompt-extra))))
(defn retro-flag [opts]
  (let [on (boolean (one opts :retro)) off (boolean (one opts :no-retro))]
    (when (and on off) (fail "--retro and --no-retro are mutually exclusive" {}))
    (cond on true off false :else nil)))
(defn retro-skill-path []
  (core/skill-path #(fs/exists? %) (ledger/assignment-root) (System/getProperty "user.home") "retro"))
(defn retro-policy [persona opts frontmatter]
  (let [skill (retro-skill-path)
        resolved (core/resolve-retro {:persona persona :flag (retro-flag opts) :frontmatter frontmatter :retro-skill skill})]
    (assoc resolved :retro-skill (when (:retro resolved) skill))))
(defn preview! [persona opts waiting-policy]
  (herdr/preflight!)
  (let [path (roster persona) frontmatter (core/parse-frontmatter (slurp (str path))) ident (parent-identity)
        kind (core/resolve-kind {:requested (one opts :kind) :frontmatter frontmatter :parent-kind (:parent-kind ident)})
        model (core/resolve-model {:requested (one opts :model) :resolved-kind kind :frontmatter frontmatter :parent-kind (:parent-kind ident) :parent-model (one opts :parent-model)})
        retro (retro-policy persona opts frontmatter)]
    {:preview (prompt-text {:persona persona :persona-path path :task "<assigned-task>" :result "<assigned-result>" :waiting-policy waiting-policy :assignment (task-text opts) :prompt-extra (one opts :prompt-extra) :retro-skill (:retro-skill retro)})
     :persona-path (str path) :kind kind :model model :retro (:retro retro) :retro-source (:retro-source retro)}))
;; A published result is immutable, so a result that fails validation can never become
;; valid. Record it as the non-final `invalid` status (pane retained, needs manual
;; intervention) instead of throwing out of the wait loop and making the assignment
;; permanently uncollectable.
(defn capture! [entry]
  (let [result (:result entry)]
    (when (fs/exists? result)
      (try
        (let [parsed (core/validate-envelope entry (slurp result)) artifacts (:artifacts parsed)]
          (doseq [artifact artifacts]
            (let [path (core/artifact-path artifact)]
              (when-not (fs/exists? path) (fail "result artifact does not exist" {:artifact artifact :path path}))))
          ;; An over-length PROCESS section is degraded, not fatal: record the fact on the
          ;; entry and keep the envelope's own status.
          (ledger/update! (:task entry) #(cond-> (assoc % :status (:status parsed) :captured-at (now) :envelope parsed :artifacts artifacts)
                                           (:process-overflow parsed) (assoc :process-overflow true)))
          parsed)
        (catch Exception e
          (ledger/update! (:task entry) assoc :status "invalid" :captured-at (now) :invalid-reason (.getMessage e) :invalid-data (ex-data e))
          {:status "invalid" :task (:task entry) :pane-id (:pane-id entry) :result result
           :reason (.getMessage e) :detail (ex-data e) :pane-retained true})))))
;; The child's transcript reference is only reachable while Herdr still knows the agent,
;; so it is recorded opportunistically at every point the CLI already holds an
;; `AgentInfo`. The whole `agent_session` map is stored because its `value` is
;; discriminated by `kind` (a path or an opaque id). Every hook is best-effort: it never
;; fails a spawn, demotes a captured result, or overwrites an earlier observation.
(defn record-session! [task session]
  (when (and task (map? session) (seq session))
    (try (ledger/update! task #(if (:child-session %) % (assoc % :child-session session)))
         (catch Exception _ nil))))
(defn caller-owns? [entry]
  (boolean (when-let [recorded (:parent-session entry)]
             (= recorded (try (:parent-session (parent-identity)) (catch Exception _ nil))))))
;; The ledger is repo-wide, so only the parent session that created an entry may close
;; its pane. An unresolvable caller identity is non-owning: capture still succeeds.
(defn maybe-close! [entry parsed owned?]
  (if-not owned?
    (assoc parsed :pane-retained true :ownership "foreign-parent-session")
    ;; The refresh is hoisted out of the COMPLETE/FAILED branch: a BLOCKED entry keeps its
    ;; pane but still needs its session reference recorded.
    (let [agent (try (herdr/agent! (:child entry)) (catch Exception _ nil))]
      (when-not (:child-session entry) (record-session! (:task entry) (:agent_session agent)))
      (when (#{"COMPLETE" "FAILED"} (:status parsed))
        (when (and (:pane-id entry) agent (#{"idle" "done"} (:agent_status agent)))
          ;; A close failure must never demote a captured result to a failed command.
          (try (herdr/close! (:pane-id entry)) (catch Exception _ nil))))
      parsed)))
(defn wait-and-capture! [entry timeout owned?]
  (let [deadline (+ (System/currentTimeMillis) timeout)]
    (loop []
      (if-let [parsed (capture! entry)] (maybe-close! entry parsed owned?)
          (let [remaining (- deadline (System/currentTimeMillis))]
            (if (<= remaining 0) {:status "pending" :reason "timeout" :task (:task entry) :pane-id (:pane-id entry)}
                (let [outcome (herdr/wait! (:child entry) remaining)
                      ;; The wait outcome already carries the AgentInfo: no extra Herdr call.
                      _ (record-session! (:task entry) (get-in outcome [:value :result :agent :agent_session]))
                      current (capture! entry)]
                  (if current (maybe-close! entry current owned?)
                      (if (and (:ok outcome) (= "blocked" (get-in outcome [:value :result :agent :agent_status])))
                        {:status "blocked" :task (:task entry) :pane-id (:pane-id entry)}
                        (let [remaining* (- deadline (System/currentTimeMillis))]
                          (when (pos? remaining*) (Thread/sleep (min (poll-interval-ms) remaining*)))
                          (recur)))))))))))
(defn safe-cleanup! [entry phase]
  (ledger/update! (:task entry) assoc :status "failed" :failed-at (now) :failure-phase (name phase))
  (when (and (:pane-id entry) (#{"split" "rename" "start"} (name phase))) (try (herdr/close! (:pane-id entry)) (catch Exception _))) )
(defn spawn! [persona opts waiting-policy]
  (if (one opts :print-prompt)
    (preview! persona opts waiting-policy)
    (do
      (herdr/preflight!)
      (let [path (roster persona)
            frontmatter (core/parse-frontmatter (slurp (str path)))
            ident (parent-identity)
            kind (core/resolve-kind {:requested (one opts :kind) :frontmatter frontmatter :parent-kind (:parent-kind ident)})
            model (core/resolve-model {:requested (one opts :model) :resolved-kind kind :frontmatter frontmatter :parent-kind (:parent-kind ident) :parent-model (one opts :parent-model)})
            retro (retro-policy persona opts frontmatter)
            tab? (boolean (one opts :tab))
            task (ledger/fresh-task)
            result (ledger/fresh-result task)
            index (ledger/allocate-index! (:parent-session ident) persona)
            parent-label (:label (herdr/pane! (:parent-pane ident)))
            label (core/child-label {:parent-label (when (= "planner" (System/getenv "HERDR_SUBAGENT_PERSONA")) parent-label) :parent-persona "planner" :persona persona :index index :model model})
            assignment (task-text opts)
            bin (launcher-bin)
            name (child-name persona task)
            entry {:task task :result result :child name :pane-id nil :label label :index index :persona-path (str path) :parent-session (:parent-session ident) :waiting-policy waiting-policy :retro (:retro retro) :retro-source (:retro-source retro) :placement (if tab? "tab" "split") :status "allocating" :created-at (now)}]
        ;; Persist before the first pane mutation, so every partial failure is recoverable.
        (ledger/write! entry)
        (try
          (let [env (cond-> {"HERDR_SUBAGENT_CHILD" name "HERDR_SUBAGENT_TASK" task "HERDR_SUBAGENT_RESULT" result "HERDR_SUBAGENT_BIN" bin "HERDR_SUBAGENT_WAITING_POLICY" waiting-policy "HERDR_SUBAGENT_PERSONA" persona}
                      ;; Keep a relocated assignment root in force for any nested delegation.
                      (System/getenv "SUBAGENT_ASSIGNMENT_ROOT") (assoc "SUBAGENT_ASSIGNMENT_ROOT" (ledger/assignment-root)))
                ;; `--tab` skips caller-rect!/direction entirely: a tab needs neither. No
                ;; inheritance: placement is spawn argv only, never carried in `env`, so a
                ;; tab-placed child's own spawns still split by default.
                placement (if tab?
                            (herdr/tab-create! {:cwd (System/getProperty "user.dir") :label label :env env})
                            (herdr/split! {:direction (core/direction (herdr/caller-rect!)) :cwd (System/getProperty "user.dir") :env env}))
                persisted (ledger/update! task assoc :pane-id (:pane_id placement) :tab-id (:tab-id placement) :status "split")]
            (try
              (let [renamed (herdr/rename! (:pane-id persisted) label)]
                (when-not (= label (:label renamed)) (fail "Herdr did not apply child pane label" {:expected label :actual (:label renamed)}))
                (ledger/update! task assoc :status "renamed")
                (let [native (concat (core/model-args kind model)
                                     (when (#{"pi" "claude"} kind)
                                       ["--append-system-prompt"
                                        (core/persona-system-prompt kind (str path) (slurp (str path)))]))]
                  (record-session! task (:agent_session (herdr/start! name kind (:pane-id persisted) native)))
                  (let [prompt (prompt-text {:persona persona :persona-path path :task task :result result :waiting-policy waiting-policy :assignment assignment :prompt-extra (one opts :prompt-extra) :retro-skill (:retro-skill retro)})]
                    (ledger/update! task assoc :status "started" :started-at (now))
                    (herdr/prompt! name prompt)
                    (let [prompted (ledger/update! task assoc :status "prompted" :prompted-at (now))]
                      ;; A child reports its session asynchronously, so a read at `start` often
                      ;; observes nothing; re-read once the prompt has landed.
                      (or (when-not (:child-session prompted)
                            (record-session! task (:agent_session (try (herdr/agent! name) (catch Exception _ nil)))))
                          prompted)))))
              (catch Exception e
                (safe-cleanup! (ledger/read! task) :start)
                (throw e))))
          (catch Exception e
            (let [current (ledger/read! task)]
              (when (not= "failed" (:status current)) (safe-cleanup! current :split)))
            (throw e)))))))
(defn publication-body [opts]
  (if-let [path (one opts :from-file)]
    (let [body (json/parse-string (slurp path) true)]
      {:status (:status body) :summary (:summary body) :artifacts (vec (:artifacts body)) :findings (vec (:findings body)) :next (:next body) :process (vec (:process body))})
    {:status (one opts :status) :summary (one opts :summary) :artifacts (all opts :artifact) :findings (all opts :finding) :next (one opts :next) :process (all opts :process)}))
(defn publish! [opts]
  (let [env #(System/getenv %) child (or (env "HERDR_SUBAGENT_CHILD") (fail "missing HERDR_SUBAGENT_CHILD" {})) task (or (env "HERDR_SUBAGENT_TASK") (fail "missing HERDR_SUBAGENT_TASK" {})) result (or (env "HERDR_SUBAGENT_RESULT") (fail "missing HERDR_SUBAGENT_RESULT" {})) policy (or (env "HERDR_SUBAGENT_WAITING_POLICY") (fail "missing HERDR_SUBAGENT_WAITING_POLICY" {}))
        body (publication-body opts) text (core/envelope (merge {:child child :task task :result result} body)) target (fs/path result) temp (fs/path (str result "." (UUID/randomUUID) ".tmp"))]
    (when-not (core/policies policy) (fail "invalid HERDR_SUBAGENT_WAITING_POLICY" {:policy policy}))
    (fs/create-dirs (fs/parent target))
    (try
      (spit (str temp) text) (Files/createLink (Paths/get (str target) (make-array String 0)) (Paths/get (str temp) (make-array String 0))) (fs/delete-if-exists temp)
      ;; Result publication is committed before notification. Notification failure is observable but never turns it into a retryable failure.
      (let [notification (when (= policy "non-blocking") (try (herdr/notify! (str "Subagent " child " published") (str "child=" child " task=" task " result=" result)) (catch Exception e {:notification-error (.getMessage e)})))]
        (cond-> {:task task :result result :status (:status body)} notification (assoc :notification notification)))
      (catch FileAlreadyExistsException e
        (fs/delete-if-exists temp) (try (herdr/notify! "Subagent publish failed" (str "child=" child " pane=" (or (env "HERDR_PANE_ID") "unknown") " task=" task " error=RESULT already exists")) (catch Exception _))
        (throw (ex-info "RESULT already exists; publication is exactly once" {:result result} e)))
      (catch Exception e
        (fs/delete-if-exists temp) (try (herdr/notify! "Subagent publish failed" (str "child=" child " pane=" (or (env "HERDR_PANE_ID") "unknown") " task=" task " error=" (.getMessage e))) (catch Exception _)) (throw e)))))
(defn collect! [task opts]
  (let [entry (ledger/read! task) owned? (caller-owns? entry)]
    (if (:wait opts) (wait-and-capture! entry (Long/parseLong (or (one opts :timeout) "600000")) owned?)
        (if-let [parsed (capture! entry)] (maybe-close! entry parsed owned?) {:status "pending" :task task :pane-id (:pane-id entry)}))))
(defn live [entry]
  (let [agent (try (herdr/agent! (:child entry)) (catch Exception _ nil))
        entry (or (when-not (:child-session entry) (record-session! (:task entry) (:agent_session agent))) entry)]
    (assoc entry :live-agent agent)))
;; `--help` is the documented non-JSON exception: it prints usage and exits 0 for any
;; command, so `subagent run --help` never returns "option requires a value".
(defn help-request? [command args]
  (boolean (or (#{"--help" "-h" "help"} command)
               ;; Mirror option-map's consumption so opaque assignment text that happens
               ;; to be `--help` is never mistaken for a help request.
               (loop [xs args]
                 (when-let [x (first xs)]
                   (cond (#{"--help" "-h"} x) true
                         (boolean-flags x) (recur (next xs))
                         (str/starts-with? x "--") (recur (nnext xs))
                         :else (recur (next xs))))))))
(defn execute [argv]
  (let [[command & args] argv]
    (if (help-request? command args)
      usage
      (let [opts (option-map args) positional (:_ opts)]
        (case command
          "run" (let [entry (spawn! (first positional) opts "blocking")] (if (:preview entry) entry (wait-and-capture! entry (Long/parseLong (or (one opts :timeout) "600000")) true)))
          "start" (spawn! (first positional) opts "non-blocking") "collect" (collect! (first positional) opts)
          "status" (if-let [task (first positional)] (live (ledger/read! task)) (mapv live (ledger/entries)))
          "list" (mapv live (ledger/entries)) "publish" (publish! opts)
          (fail "unknown subagent command" {:command command}))))))
(defn -main [& argv]
  (try (let [result (execute argv)] (println (if (string? result) result (core/json-envelope true result))))
       (catch Exception e (println (core/json-envelope false {:message (.getMessage e) :data (ex-data e)})) (System/exit 1))))

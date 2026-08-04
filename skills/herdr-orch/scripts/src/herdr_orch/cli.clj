(ns herdr-orch.cli
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [herdr-orch.core :as core]
            [herdr-orch.herdr :as herdr]
            [herdr-orch.ledger :as ledger])
  (:import [java.nio.file Files FileAlreadyExistsException Paths]
           [java.util UUID]))

(def usage "oh pane split|run|read|wait-output|send-text|send-keys|close|list|current|get|layout|rename\noh tab create|list|focus\noh ws create|list|focus\n\nRAW AGENT CONTROL\n  oh agent start|prompt|wait|read|send-keys|focus|rename|list|get\n\nDELEGATION TASK PROTOCOL\n  oh task run|start <persona> --task TEXT [--tab|--split] [--spawns NAMES|none] [options]\n  oh task collect <full-task-uuid> [--wait --timeout MS]\n  oh task collect --any [--wait --timeout MS]\n  oh task status [full-task-uuid] | list\n  oh task publish --status STATUS --summary TEXT [--artifact PATH]* [--finding TEXT]* [--next TEXT] [--process TEXT]* [--notify-timeout MS]\n  oh task progress --summary TEXT\n  oh task prune <full-task-uuid>\n\noh spawn \"<shell command>\"\n\nspawn creates an unfocused tab, runs an ordinary shell command in its root pane, and reports that pane id. It never delegates; use `oh task run <persona>` for a persona.\n--notify-timeout bounds the settle wait before the advisory parent push under the non-blocking policy (default 30000 ms).\n--tab places the delegated child in a new unfocused tab of the caller's workspace instead of a split; --split explicitly selects a split.\n--spawns overrides the persona's `spawns:` allow-list (whitespace/comma separated); the literal `none` forces a leaf.\nprogress stores one latest advisory snapshot for the injected child/task identity, throttled to ORCH_PROGRESS_INTERVAL_MS (default 60000 ms); it never signals completion.\nprune requires the caller's own :parent-session to own <full-task-uuid> and proves it stale (uncaptured, no RESULT, absent from one `agent list`) before marking it failed.\ncollect, status, and prune all resolve their assignment argument as the exact ledger key emitted by task run/start; no prefix is ever resolved.\nOpaque assignment input is --task, --task-file, or stdin. Run `oh --help` for contract details.")
(defn fail [message data] (throw (ex-info message data)))
(defn now [] (str (java.time.Instant/now)))
;; Zero is truthy in Clojure and `Thread/sleep` rejects negatives, so only a
;; strictly positive parse overrides the default.
(defn parse-poll-interval [raw]
  (let [n (some-> raw str/trim not-empty parse-long)]
    (if (and n (pos? n)) n 1000)))
(defn poll-interval-ms [] (parse-poll-interval (System/getenv "ORCH_POLL_INTERVAL_MS")))
;; Bounds the settle wait before the advisory parent push only; same non-positive/
;; unparseable discipline as parse-poll-interval.
(def default-notify-timeout-ms 30000)
(defn parse-notify-timeout [raw]
  (let [n (some-> raw str/trim not-empty parse-long)]
    (if (and n (pos? n)) n default-notify-timeout-ms)))
;; Throttle floor for `progress --summary`; same non-positive/unparseable/blank ->
;; default discipline as parse-poll-interval/parse-notify-timeout.
(def default-progress-interval-ms 60000)
(defn parse-progress-interval [raw]
  (let [n (some-> raw str/trim not-empty parse-long)]
    (if (and n (pos? n)) n default-progress-interval-ms)))
(defn progress-interval-ms [] (parse-progress-interval (System/getenv "ORCH_PROGRESS_INTERVAL_MS")))
;; Bounds the settle wait a `collect --any` capture makes before its one-shot pane close
;; (see `collect-any!`); same non-positive/unparseable/blank -> default discipline as
;; parse-poll-interval/parse-notify-timeout/parse-progress-interval. The default is
;; deliberately larger than `default-notify-timeout-ms`: a non-blocking child publishing
;; while its parent sits inside `collect --any` reads `working` for that entire notify
;; wait (the parent, mid-collect, never settles idle/done), which is exactly the
;; reproduced pane retention, so a budget at or below 30 000 ms would miss it.
(def default-settle-close-ms 45000)
(defn parse-settle-close [raw]
  (let [n (some-> raw str/trim not-empty parse-long)]
    (if (and n (pos? n)) n default-settle-close-ms)))
(defn settle-close-ms [] (parse-settle-close (System/getenv "ORCH_SETTLE_CLOSE_MS")))
;; Single source of truth for value-less flags. `option-map` and `help-request?` both
;; consume argv and must agree: a flag known to only one of them silently swallows the
;; following element (e.g. `run worker --retro --task 'X'` losing its assignment).
(def boolean-flags #{"--wait" "--print-prompt" "--retro" "--no-retro" "--tab" "--split" "--any" "--focus" "--no-focus" "--clear" "--raw"})
(defn option-map [args]
  (loop [xs args out {}]
    (if-let [x (first xs)]
      (cond (= "--" x) (assoc out :native (vec (next xs)))
            (boolean-flags x) (recur (next xs) (assoc out (keyword (subs x 2)) true))
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
    (mapv #(str (fs/strip-ext (fs/file-name %))) (fs/glob dir "*.md" {:follow-links true}))
    []))
;; `$HOME` wins over `user.home`, which Babashka resolves from the passwd entry and never
;; from the environment. Honouring `$HOME` is the POSIX expectation, and it is what keeps a
;; subprocess test hermetic: without it a real `~/.agents/subagents/config.edn` on the
;; developer's machine leaks into every fake-env spawn, which is how the harness
;; `:extra-args` override was first observed contaminating the suite.
(defn home-directory [] (or (not-empty (System/getenv "HOME")) (System/getProperty "user.home")))
(declare launcher-bin)
(defn skill-directory []
  (fs/parent (fs/parent (fs/path (launcher-bin)))))
;; Shipped personas and config are siblings of `scripts/`, so both derive from
;; the resolved launcher path rather than the assignment root or current working directory.
(defn packaged-personas-directory []
  (str (fs/path (skill-directory) "subagents")))
(defn persona-directories []
  (core/persona-directories (ledger/assignment-root) (home-directory) (packaged-personas-directory)))
(defn available-personas []
  (let [directories (persona-directories)]
    (->> directories
         (mapcat persona-names)
         distinct
         ;; Resolve each name through the same ordered lookup used by direct lookup and
         ;; spawn-policy validation; the predicate also ignores a vanished file safely.
         (filter #(core/resolve-persona (fn [path] (fs/exists? path)) directories %))
         sort vec)))
(defn roster [persona]
  (let [path (core/resolve-persona (fn [path] (fs/exists? path)) (persona-directories) persona)]
    (or (some-> path fs/path)
        (fail "persona not found in project, home, or packaged roster" {:persona persona :available (available-personas)}))))
(defn child-name [persona task] (str persona "-" (subs task 0 8)))
(defn launcher-bin []
  (or (System/getenv "HERDR_ORCH_BIN")
      (let [candidate (fs/path (ledger/assignment-root) "skills" "herdr-orch" "scripts" "oh")]
        (when (fs/exists? candidate) (str (fs/absolutize candidate))))
      (fail "could not resolve oh launcher" {})))
(defn default-config-path []
  (str (packaged-personas-directory) "/config.edn"))
(defn config-file [path]
  (when (fs/exists? path) (core/parse-config (str path) (slurp (str path)))))
;; Loader precedence: shipped default ← home override ← project override (project
;; wins), merged with `core/merge-config`. A missing override file is silently ignored;
;; a missing shipped default is fatal. `home` is an explicit argument (defaulting to
;; `home-directory`) so in-process callers/tests can inject it directly rather than going
;; through the environment.
(defn config
  ([] (config (home-directory)))
  ([home]
   (let [default-path (default-config-path)
         home-path (fs/path home ".agents" "subagents" "config.edn")
         project-path (fs/path (ledger/assignment-root) ".agents" "subagents" "config.edn")]
     (core/merge-config
      (or (config-file default-path) (fail "missing shipped default config" {:path default-path}))
      (or (config-file home-path) {})
      (or (config-file project-path) {})))))
(defn parent-identity []
  (let [agent (herdr/agent! (System/getenv "HERDR_PANE_ID"))]
    {:parent-session (or (get-in agent [:agent_session :value]) (:pane_id agent)) :parent-kind (:agent agent) :parent-pane (:pane_id agent)}))
;; Composed from the resolved spawn policy, not the persona name: any persona whose
;; policy is non-empty gets the delegation sentence, everyone else the leaf sentence.
(defn delegation-guidance [spawns]
  (if (seq spawns)
    (str "You may spawn at most one blocking ephemeral " (str/join " or " spawns)
         " only when a factual gap or material judgment blocks progress; that child must remain a leaf.")
    "You are a leaf: do not spawn subagents."))
(defn retro-instruction [retro-skill]
  (when retro-skill
    (str "\nBefore publishing, apply steps 1-2 of " retro-skill " to your own session, using that skill's own threshold and signal categories."
         "\nEmit each surviving candidate as one `--process` item shaped `signal → category → proposed rule` (at most five)."
         "\nEmit nothing when the session does not meet that threshold; an absent PROCESS section is a valid outcome, not a failure."
         "\nDo not choose a destination, load `self-improvement`, run `ot`, or edit any instruction file: the parent owns approval and persistence.")))
;; Advisory only, and only worth asking for when there is nobody blocking on this child
;; already: a `blocking` run's parent is already waiting and gets nothing extra to poll.
(defn progress-instruction [waiting-policy]
  (when (= waiting-policy "non-blocking")
    (str "\nReport concise phase-boundary progress with `$HERDR_ORCH_BIN task progress --summary \"...\"` at most once per ORCH_PROGRESS_INTERVAL_MS (default 60000 ms); never include draft findings or result content, and never treat it as completion.")))
(defn prompt-text [{:keys [spawns persona-path task result waiting-policy assignment prompt-extra retro-skill]}]
  (str "Read " persona-path ", adopt that role. Task: " assignment "\n\n"
       (delegation-guidance spawns) " Herdr assigned TASK=" task " and RESULT=" result ". "
       "When finished, publish exactly once with `$HERDR_ORCH_BIN task publish --status COMPLETE --summary \"...\"`; do not send result text to the parent PTY. "
       "If you cannot finish — an unrecoverable failure after reasonable retries, or a genuine blocking dependency — publish once with `--status BLOCKED` (dependency) or `--status FAILED` (unrecoverable), summarising work completed vs remaining; never stop silently or publish a second envelope after recovering. "
       "The waiting policy is " waiting-policy "."
       (retro-instruction retro-skill)
       (progress-instruction waiting-policy)
       (when prompt-extra (str "\nAdditional constraints: " prompt-extra))))
(defn retro-flag [opts]
  (let [on (boolean (one opts :retro)) off (boolean (one opts :no-retro))]
    (when (and on off) (fail "--retro and --no-retro are mutually exclusive" {}))
    (cond on true off false :else nil)))
(defn retro-skill-path []
  (core/skill-path #(fs/exists? %) (ledger/assignment-root) (home-directory) "retro"))
(defn retro-policy [persona opts frontmatter]
  (let [skill (retro-skill-path)
        resolved (core/resolve-retro {:persona persona :flag (retro-flag opts) :frontmatter frontmatter :retro-skill skill})]
    (assoc resolved :retro-skill (when (:retro resolved) skill))))
(defn placement-flag [opts]
  (let [tab? (boolean (one opts :tab)) split? (boolean (one opts :split))]
    (when (and tab? split?) (fail "--tab and --split are mutually exclusive" {}))
    (cond tab? "tab" split? "split" :else nil)))
(defn placement-policy [opts config]
  (core/resolve-placement {:flag (placement-flag opts)
                           :configured (get-in config [:defaults :placement])
                           :below-root? (some? (System/getenv "HERDR_ORCH_PERSONA"))}))
;; Depth and capability gate: below root (own HERDR_ORCH_PERSONA set) a run/start is
;; refused before herdr/preflight!, ledger allocation, and any pane mutation, so a denied
;; spawn creates nothing billable. Blank and unset HERDR_ORCH_SPAWNS both parse to
;; the empty allow-list — the empty-string/unset distinction is never load-bearing.
(defn enforce-spawns! [persona opts]
  (when-let [own (System/getenv "HERDR_ORCH_PERSONA")]
    (let [flag (one opts :spawns)]
      (when (and flag (not= "none" flag))
        (fail "below-root spawns cannot grant capability: only `--spawns none` is permitted"
              {:own-persona own :target persona :spawns flag})))
    (let [allowed (core/parse-spawns (System/getenv "HERDR_ORCH_SPAWNS"))]
      (when-not (some #{persona} allowed)
        (fail "spawn refused: target persona is not in this agent's HERDR_ORCH_SPAWNS allow-list"
              {:own-persona own :target persona :allowed allowed})))))
;; Mirrors retro-policy: resolved once at spawn (flag > frontmatter > default deny),
;; recorded on the entry, and injected into the child. Resolution always runs so an
;; unresolvable frontmatter `spawns:` name fails fast at any depth (roster typos stay
;; loud); below root the validated result is then discarded and the policy forced empty
;; with source "depth" regardless of the child persona's frontmatter: one nesting level
;; absolutely, no depth counter needed.
(defn spawns-policy [persona opts frontmatter]
  (let [directories (persona-directories)
        resolved (core/resolve-spawns {:persona persona :flag (one opts :spawns) :frontmatter frontmatter
                                       :resolve-persona #(core/resolve-persona (fn [path] (fs/exists? path)) directories %)})]
    (if (System/getenv "HERDR_ORCH_PERSONA")
      {:spawns [] :spawns-source "depth"}
      resolved)))
(defn preview! [persona opts waiting-policy]
  (herdr/preflight!)
  (let [path (roster persona) frontmatter (core/parse-frontmatter (slurp (str path))) ident (parent-identity)
        kind (core/resolve-kind {:requested (one opts :kind) :frontmatter frontmatter :parent-kind (:parent-kind ident)})
        model (core/resolve-model {:requested (one opts :model) :resolved-kind kind :frontmatter frontmatter :parent-kind (:parent-kind ident) :parent-model (one opts :parent-model)})
        config (config)
        placement (placement-policy opts config)
        retro (retro-policy persona opts frontmatter)
        spawns (spawns-policy persona opts frontmatter)]
    {:preview (prompt-text {:spawns (:spawns spawns) :persona-path path :task "<assigned-task>" :result "<assigned-result>" :waiting-policy waiting-policy :assignment (task-text opts) :prompt-extra (one opts :prompt-extra) :retro-skill (:retro-skill retro)})
     ;; :model is the canonical resolved ID; :model-args is the effective translated
     ;; native spelling (e.g. `["--model" "opus"]`) from the merged roster config.
     :persona-path (str path) :kind kind :model model :model-args (core/model-args config kind model) :placement placement :retro (:retro retro) :retro-source (:retro-source retro)
     :spawns (:spawns spawns) :spawns-source (:spawns-source spawns)}))
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
          ;; Rendered only after every artifact passed the existence check above, so a
          ;; collected link is evidence in a way a publish-time advisory link is not. The
          ;; parent surfaces these to the user once the child pane is gone. `cond->`, not
          ;; an empty vector: absent is not the same claim as validated-empty.
          (let [links (when (seq artifacts) (mapv core/artifact-link artifacts))]
            ;; An over-length PROCESS section is degraded, not fatal: record the fact on the
            ;; entry and keep the envelope's own status.
            (ledger/update! (:task entry) #(cond-> (assoc % :status (:status parsed) :captured-at (now) :envelope parsed :artifacts artifacts)
                                             links (assoc :artifact-links links)
                                             (:process-overflow parsed) (assoc :process-overflow true)))
            (cond-> parsed links (assoc :artifact-links links))))
        (catch Exception e
          ;; `ledger/update!` rewrites the whole entry, so an earlier successful capture's
          ;; `:artifact-links` must be actively dropped: a re-collect whose artifact has
          ;; since been deleted is `invalid`, and an `invalid` entry may never advertise
          ;; existence-validated links.
          (ledger/update! (:task entry) #(-> (dissoc % :artifact-links)
                                             (assoc :status "invalid" :captured-at (now) :invalid-reason (.getMessage e) :invalid-data (ex-data e))))
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
  (enforce-spawns! persona opts)
  (if (one opts :print-prompt)
    (preview! persona opts waiting-policy)
    (do
      (herdr/preflight!)
      (let [path (roster persona)
            frontmatter (core/parse-frontmatter (slurp (str path)))
            ident (parent-identity)
            kind (core/resolve-kind {:requested (one opts :kind) :frontmatter frontmatter :parent-kind (:parent-kind ident)})
            model (core/resolve-model {:requested (one opts :model) :resolved-kind kind :frontmatter frontmatter :parent-kind (:parent-kind ident) :parent-model (one opts :parent-model)})
            ;; Loaded and schema-validated here, before `ledger/fresh-result`'s
            ;; `fs/create-dirs` and every later ledger/pane mutation: malformed config
            ;; must fail fast, never after allocation has begun.
            config (config)
            placement (placement-policy opts config)
            retro (retro-policy persona opts frontmatter)
            spawns (spawns-policy persona opts frontmatter)
            task (ledger/fresh-task)
            result (ledger/fresh-result task)
            index (ledger/allocate-index! (:parent-session ident) persona)
            ;; Nested labels compose for any spawning persona, gated on the spawner's
            ;; own injected HERDR_ORCH_PERSONA (display metadata only — depth is
            ;; enforced by enforce-spawns!, never by label parsing).
            own-persona (System/getenv "HERDR_ORCH_PERSONA")
            parent-label (:label (herdr/pane! (:parent-pane ident)))
            label (core/child-label {:parent-label (when own-persona parent-label) :parent-persona own-persona :persona persona :index index :model model})
            assignment (task-text opts)
            bin (launcher-bin)
            name (child-name persona task)
            entry {:task task :result result :child name :pane-id nil :label label :index index :persona-path (str path) :parent-session (:parent-session ident) :parent-pane (:parent-pane ident) :waiting-policy waiting-policy :retro (:retro retro) :retro-source (:retro-source retro) :spawns (:spawns spawns) :spawns-source (:spawns-source spawns) :placement placement :status "allocating" :created-at (now)}]
        ;; Persist before the first pane mutation, so every partial failure is recoverable.
        (ledger/write! entry)
        (try
          (let [env (cond-> {"HERDR_ORCH_CHILD" name "HERDR_ORCH_TASK" task "HERDR_ORCH_RESULT" result "HERDR_ORCH_BIN" bin "HERDR_ORCH_WAITING_POLICY" waiting-policy "HERDR_ORCH_PERSONA" persona "HERDR_ORCH_SPAWNS" (str/join " " (:spawns spawns))}
                      ;; Keep a relocated assignment root in force for any nested delegation.
                      (System/getenv "ORCH_ASSIGNMENT_ROOT") (assoc "ORCH_ASSIGNMENT_ROOT" (ledger/assignment-root)))
                ;; Tab placement skips caller-rect!/direction entirely: a tab needs neither.
                ;; Placement is never carried in `env`, so children resolve their own config.
                pane-placement (if (= placement "tab")
                                 (herdr/tab-create! {:cwd (System/getProperty "user.dir") :label label :env env})
                                 (herdr/split! {:direction (core/direction (herdr/caller-rect!)) :cwd (System/getProperty "user.dir") :env env}))
                persisted (ledger/update! task assoc :pane-id (:pane_id pane-placement) :tab-id (:tab-id pane-placement) :status "split")]
            (try
              (let [renamed (herdr/rename! (:pane-id persisted) label)]
                (when-not (= label (:label renamed)) (fail "Herdr did not apply child pane label" {:expected label :actual (:label renamed)}))
                (ledger/update! task assoc :status "renamed")
                (let [native (concat (core/model-args config kind model)
                                     (core/harness-extra-args config kind)
                                     (core/persona-args kind (str path)))]
                  (record-session! task (:agent_session (herdr/start! name kind (:pane-id persisted) native)))
                  (let [prompt (prompt-text {:spawns (:spawns spawns) :persona-path path :task task :result result :waiting-policy waiting-policy :assignment assignment :prompt-extra (one opts :prompt-extra) :retro-skill (:retro-skill retro)})]
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
;; --- advisory parent push ----------------------------------------------------------
;; Under `non-blocking` the parent has nothing to block on, so a committed publish pushes
;; one advisory `agent prompt` naming the child, the task, and the `collect` command to
;; run. Two gates must both hold, on whichever `AgentInfo` was observed *last*:
;;   1. `agent_status` is `idle` or `done` — never hijack an active turn, and never a
;;      `blocked` parent, whose prompt text would land in an approval UI;
;;   2. the probe's `agent_session` value equals the ledger's `:parent-session` — panes
;;      outlive agent sessions, so status alone would let a delayed child prompt an
;;      unrelated replacement agent occupying the reused pane. This is the same ownership
;;      boundary `maybe-close!` enforces for pane closure. A parent whose session cannot be
;;      observed fails the gate by construction (nil never equals a recorded identity),
;;      which is the safe direction: the ledger's `:parent-session` is never nil.
;; Never `--wait` (that path can return `agent_prompt_stalled`), and never under the
;; `blocking` policy, where the parent is already in its own wait loop.
;; Declared artifacts are rendered as portable Markdown file links so a parent whose child
;; pane has already closed can still open them. These come from the child's *declared*
;; envelope body: publish only ever checked path shape, never existence, so the list is
;; explicitly advisory and only `collect`'s existence-validated `artifact-links` are
;; evidence. An empty list adds no section at all rather than an empty one.
;; Publication is already committed when this renders, so one unrenderable artifact must
;; never cost the parent its whole notification. `artifact-path` accepts a NUL byte that
;; `Paths/get` rejects, and such a path cannot be degraded to *raw* text either: the byte
;; would then reach the `agent prompt` argv and fail the submission itself. So the item is
;; dropped and counted — the RESULT envelope remains the authoritative declared list.
(defn artifact-advisory [artifacts]
  (when (seq artifacts)
    (let [items (keep (fn [artifact]
                        (try (str "- " (core/artifact-link (str artifact)))
                             (catch Exception _ nil)))
                      artifacts)
          dropped (- (count artifacts) (count items))]
      (str "\nDeclared artifacts (advisory — pending validation by `collect`):\n"
           (str/join "\n" (cond-> (vec items)
                            (pos? dropped)
                            (conj (str "- (" dropped " declared artifact path(s) not renderable as a link; see the RESULT envelope)"))))))))
(defn push-text [bin child task status artifacts]
  (str "Subagent " child " published a " status " result for task " task
       ". Capture it with `" bin " task collect " task "`."
       " Advisory only: the validated RESULT file remains the sole completion signal."
       (artifact-advisory artifacts)))
(defn settled-parent? [status] (contains? #{"idle" "done"} status))
(defn push-decision [entry agent]
  (cond
    (not= (:parent-session entry) (get-in agent [:agent_session :value])) {:push "skipped" :reason "session-mismatch"}
    (settled-parent? (:agent_status agent)) {:push "send"}
    (= "blocked" (:agent_status agent)) {:push "skipped" :reason "parent-blocked"}
    :else {:push "wait" :reason "parent-unsettled" :parent-status (:agent_status agent)}))
;; Publication is already committed when this runs, so every outcome — sent, skipped with
;; a reason, timed out, or a herdr error — is only *reported*: nothing here may change the
;; publish status or exit code. Each herdr call therefore carries its own reason (a failed
;; `agent prompt` may have delivered text partially, which is not the same operator fact as
;; a parent that was never contacted), with a last-resort catch behind them.
(defn notify-parent! [entry {:keys [child task status timeout artifacts]}]
  (try
    (if-let [pane (:parent-pane entry)]
      (let [send! (fn [extra]
                    (try (herdr/prompt! pane (push-text (launcher-bin) child task status artifacts))
                         (merge {:push "sent" :parent-pane pane} extra)
                         (catch Exception e {:push "error" :parent-pane pane :reason "prompt-failed" :message (.getMessage e)})))
            probe (try {:agent (herdr/agent! pane)} (catch Exception e {:error (.getMessage e)}))]
        (if (:error probe)
          {:push "error" :parent-pane pane :reason "probe-failed" :message (:error probe)}
          (let [decision (push-decision entry (:agent probe))]
            (case (:push decision)
              "send" (send! nil)
              ;; `:waited` records only that a settle wait preceded the outcome — never that
              ;; the parent settled, which the post-wait re-check may still reject.
              "wait" (let [outcome (herdr/wait-settled! pane timeout)
                           observed (get-in outcome [:value :result :agent])
                           code (get-in outcome [:error :response :error :code])
                           ;; Re-checked on the freshly observed AgentInfo: the wait can be
                           ;; satisfied by a *replacement* agent settling in the same pane.
                           after (when observed (push-decision entry observed))]
                       (cond
                         (and (not (:ok outcome)) (= "timeout" code)) {:push "timed-out" :parent-pane pane :timeout-ms timeout :parent-status (:parent-status decision)}
                         (not (:ok outcome)) {:push "error" :parent-pane pane :reason (or code "wait-failed") :waited true}
                         (nil? after) {:push "skipped" :reason "parent-unobserved" :parent-pane pane :waited true}
                         (= "send" (:push after)) (send! {:waited true})
                         :else (assoc after :push "skipped" :parent-pane pane :waited true)))
              (assoc decision :parent-pane pane)))))
      {:push "skipped" :reason "no-parent-pane"})
    (catch Exception e {:push "error" :reason "push-failed" :message (.getMessage e)})))
(defn publication-body [opts]
  (if-let [path (one opts :from-file)]
    (let [body (json/parse-string (slurp path) true)]
      {:status (:status body) :summary (:summary body) :artifacts (vec (:artifacts body)) :findings (vec (:findings body)) :next (:next body) :process (vec (:process body))})
    {:status (one opts :status) :summary (one opts :summary) :artifacts (all opts :artifact) :findings (all opts :finding) :next (one opts :next) :process (all opts :process)}))
(defn publish! [opts]
  (let [env #(System/getenv %) child (or (env "HERDR_ORCH_CHILD") (fail "missing HERDR_ORCH_CHILD" {})) task (or (env "HERDR_ORCH_TASK") (fail "missing HERDR_ORCH_TASK" {})) result (or (env "HERDR_ORCH_RESULT") (fail "missing HERDR_ORCH_RESULT" {})) policy (or (env "HERDR_ORCH_WAITING_POLICY") (fail "missing HERDR_ORCH_WAITING_POLICY" {}))
        body (publication-body opts)
        ;; Publication is exactly-once and immutable, so a relative artifact path must fail
        ;; before the write, not only at collect (core/artifact-path is the same check
        ;; there): a child could otherwise never repair a COMPLETE-but-invalid envelope.
        ;; Checked on the raw --artifact/--from-file values, before any path is rewritten.
        ;; `str` guards a non-string --from-file artifact entry: without it a nil/numeric
        ;; JSON value would NPE before reaching artifact-path's own clean error.
        _ (doseq [artifact (:artifacts body)] (core/artifact-path (str artifact)))
        text (core/envelope (merge {:child child :task task :result result} body)) target (fs/path result) temp (fs/path (str result "." (UUID/randomUUID) ".tmp"))]
    (when-not (core/policies policy) (fail "invalid HERDR_ORCH_WAITING_POLICY" {:policy policy}))
    (fs/create-dirs (fs/parent target))
    (try
      (spit (str temp) text) (Files/createLink (Paths/get (str target) (make-array String 0)) (Paths/get (str temp) (make-array String 0))) (fs/delete-if-exists temp)
      ;; Result publication is committed before notification. Notification failure is observable but never turns it into a retryable failure.
      (let [notification (when (= policy "non-blocking") (try (herdr/notify! (str "Subagent " child " published") (str "child=" child " task=" task " result=" result)) (catch Exception e {:notification-error (.getMessage e)})))
            ;; The operator toast is retained; the push is additional. A publication whose
            ;; task has no ledger entry (a hand-driven `publish`) has no parent to probe.
            push (when (= policy "non-blocking")
                   (if-let [entry (try (ledger/read! task) (catch Exception _ nil))]
                     (notify-parent! entry {:child child :task task :status (:status body)
                                            :artifacts (:artifacts body)
                                            :timeout (parse-notify-timeout (one opts :notify-timeout))})
                     {:push "skipped" :reason "unknown-ledger-entry"}))]
        (cond-> {:task task :result result :status (:status body)}
          notification (assoc :notification notification)
          push (assoc :parent-push push)))
      (catch FileAlreadyExistsException e
        (fs/delete-if-exists temp) (try (herdr/notify! "Subagent publish failed" (str "child=" child " pane=" (or (env "HERDR_PANE_ID") "unknown") " task=" task " error=RESULT already exists")) (catch Exception _))
        (throw (ex-info "RESULT already exists; publication is exactly once" {:result result} e)))
      (catch Exception e
        (fs/delete-if-exists temp) (try (herdr/notify! "Subagent publish failed" (str "child=" child " pane=" (or (env "HERDR_PANE_ID") "unknown") " task=" task " error=" (.getMessage e))) (catch Exception _)) (throw e)))))
(defn collect! [task opts]
  (let [entry (ledger/read! task) owned? (caller-owns? entry)]
    (if (:wait opts) (wait-and-capture! entry (Long/parseLong (or (one opts :timeout) "600000")) owned?)
        (if-let [parsed (capture! entry)] (maybe-close! entry parsed owned?) {:status "pending" :task task :pane-id (:pane-id entry)}))))
;; --- advisory progress reporting ---------------------------------------------------
;; A latest-only snapshot for the injected child identity, never a second transcript:
;; `status`/`list` already return the raw ledger entry (see `live`), so a `:progress`
;; key needs no further plumbing there. Identity and lifecycle are all checked before
;; the throttle, so a foreign, post-publish, captured, or terminal assignment can never
;; be perturbed by a stray or malicious `progress` call.
(defn- progress-elapsed-ms [reported-at]
  ;; Defensive: a hand-edited or foreign-format :reported-at must degrade to "treat as
  ;; stale" (rewrite), never hard-fail an advisory command. A negative value (backward
  ;; clock step) is treated the same way rather than throttling until the clock catches up.
  (try (let [elapsed (- (System/currentTimeMillis) (.toEpochMilli (java.time.Instant/parse reported-at)))]
         (when (<= 0 elapsed) elapsed))
       (catch Exception _ nil)))
(defn progress! [opts]
  (let [env #(System/getenv %)
        child (or (env "HERDR_ORCH_CHILD") (fail "missing HERDR_ORCH_CHILD" {}))
        task (or (env "HERDR_ORCH_TASK") (fail "missing HERDR_ORCH_TASK" {}))
        _ (or (env "HERDR_ORCH_RESULT") (fail "missing HERDR_ORCH_RESULT" {}))
        summary (or (one opts :summary) (fail "provide --summary" {}))
        entry (ledger/read! task)]
    ;; Wrong or missing child identity must never update another assignment's entry,
    ;; silently or otherwise — checked first, ahead of every lifecycle rejection below.
    (when-not (= child (:child entry))
      (fail "child identity mismatch: cannot update another assignment's progress" {:task task}))
    ;; The entry's own :result is the completion signal capture! checks (cli.clj's
    ;; `capture!`), not the injected env value: a mismatched env can never bypass this.
    (when (fs/exists? (:result entry))
      (fail "cannot report progress: RESULT already published" {:task task}))
    (when (:captured-at entry)
      (fail "cannot report progress: assignment already captured" {:task task}))
    ;; "invalid" is defence-in-depth, not load-bearing: capture!'s invalid branch always
    ;; sets :captured-at too, so the guard above already excludes it. "failed" (spawn-
    ;; failure cleanup) sets no :captured-at and is the one this check actually protects.
    (when (contains? #{"failed" "invalid"} (:status entry))
      (fail "cannot report progress: assignment is terminal" {:task task :status (:status entry)}))
    (let [previous (:progress entry)
          elapsed-ms (some-> (:reported-at previous) progress-elapsed-ms)]
      ;; Throttled is a non-error, non-final outcome: the stored snapshot is left
      ;; byte-identical (no `ledger/update!` call at all on this branch).
      (if (and elapsed-ms (< elapsed-ms (progress-interval-ms)))
        {:status "throttled" :task task :progress previous}
        (let [snapshot {:summary summary :reported-at (now)}]
          (ledger/update! task assoc :progress snapshot)
          {:status "recorded" :task task :progress snapshot})))))
;; --- explicit stale-entry pruning ---------------------------------------------------
;; Remedies the one known `collect --any` gap (contract.md § Fan-in "Known limitation"):
;; a `run`/`start` killed between `ledger/write!` and its cleanup leaves a same-session,
;; uncaptured, non-`failed` entry that no `RESULT` will ever complete and whose named
;; child can never reappear in `agent list` — yet it satisfies the `--any` candidate
;; predicate forever. This command is the only way to clear one, by full ledger task id,
;; scoped to the caller's own session and proven stale rather than merely old.
;; Ownership reuses `parent-identity` (never `caller-owns?`'s permissive fallback, which
;; exists only so a foreign `collect` can still capture-with-retained-pane): pruning is
;; destructive, so an unresolvable caller identity must refuse exactly like a genuine
;; foreign session, never merely retain something. `:parent-session` is documented as
;; never nil on an entry this CLI wrote, but a hand-edited or legacy-format ledger file
;; could still omit it, so both sides are required non-nil below rather than trusting
;; that invariant to make a bare `not=` safe against a nil-vs-nil coincidence.
(defn- caller-parent-session []
  (try (:parent-session (parent-identity)) (catch Exception _ nil)))
;; `live-agents` is defined below (it belongs next to `any-candidates`/`collect-any!`,
;; its only other caller); this forward declaration lets `prune!` reuse it unchanged.
(declare live-agents)
(defn prune! [task]
  (when-not task (fail "prune requires a full task uuid" {}))
  (let [entry (ledger/read! task)
        caller (caller-parent-session)
        recorded (:parent-session entry)]
    ;; Both sides must be non-nil, not merely equal: a bare `not=` would let a caller
    ;; whose own identity is unresolvable (nil) own an entry whose `:parent-session` is
    ;; also nil (a hand-edited or legacy-format ledger file), since nil = nil. Pruning is
    ;; destructive, so that nil-vs-nil coincidence must never grant ownership.
    (when-not (and caller recorded (= caller recorded))
      (fail "prune refused: caller session does not own this ledger entry" {:task task}))
    (when (:captured-at entry)
      (fail "prune refused: assignment already captured" {:task task}))
    (when (contains? #{"failed" "invalid"} (:status entry))
      (fail "prune refused: assignment is already terminal" {:task task :status (:status entry)}))
    (when (fs/exists? (:result entry))
      (fail "prune refused: RESULT already exists" {:task task}))
    ;; Reuses `live-agents` (same name-keyed classification `--any` uses) rather than a
    ;; second liveness scan: `nil` means the listing itself is unusable, so liveness is
    ;; unknown and a prune must never proceed — only a listing that positively omits this
    ;; child's name is proof of absence.
    (let [index (live-agents)]
      (when-not index
        (fail "prune refused: agent list is unusable; liveness is unknown" {:task task}))
      (when (contains? index (:child entry))
        (fail "prune refused: named child is present in agent list" {:task task :child (:child entry)})))
    ;; The `agent list` call above can itself race a child publishing and exiting inside
    ;; its subprocess window — the same hazard `collect-any!` re-polls to avoid (see its
    ;; comment above `capture-first`). So the final mutation re-validates the *freshest*
    ;; ledger state inside `ledger/update!`'s own function, not the `entry` snapshot taken
    ;; before the listing, and refuses rather than overwriting a capture or a RESULT that
    ;; appeared during the check. Never closes `:pane-id` — it may be stale, reused, or
    ;; already gone; this command only ever touches the ledger JSON.
    (ledger/update! task (fn [current]
                           (if (or (:captured-at current) (fs/exists? (:result current)))
                             (fail "prune refused: assignment was captured or published during the liveness check" {:task task})
                             (assoc current :status "failed" :failure-phase "orphaned" :pruned-at (now) :prune-reason "missing-agent"))))))
;; Fan-in candidacy: same `:parent-session` as the caller, not yet captured, and not a
;; terminal spawn failure. The ledger is repo-wide, so the session scope is what makes
;; `--any` safe to run alongside another parent. The `failed` exclusion is load-bearing
;; rather than tidiness: `safe-cleanup!` marks a dead spawn `failed` *without* a
;; `:captured-at`, so an uncaptured-only predicate would keep that entry a candidate
;; forever and make both `no-candidates` and the all-blocked short-circuit unreachable.
;; `invalid` entries need no exclusion — `capture!` sets `:captured-at` on that branch too.
;; `ledger/entries` is sorted by `:created-at`, so poll order is spawn order.
;; `parent-session` is a loop invariant, so it is checked by the caller rather than per
;; entry: an unresolvable caller identity must not slurp and parse every ledger file for a
;; guaranteed-empty result.
(defn any-candidates [parent-session]
  (vec (filter #(and (= parent-session (:parent-session %))
                     (nil? (:captured-at %))
                     (not= "failed" (:status %)))
               (ledger/entries))))
(defn- capture-first [candidates]
  (some (fn [entry] (when-let [parsed (capture! entry)] [entry parsed])) candidates))
;; One `agent list` per tick classifies every candidate's liveness, instead of one
;; `agent get` per child. `nil` means the listing itself failed: liveness is unknown, so
;; neither short-circuit may fire and the loop keeps polling to its budget. Agents are
;; indexed by `name` (the ledger's `:child`), which Herdr clears when an agent exits —
;; exactly the vanished case. Deliberately no `pane_id` fallback: a real listing contains
;; nameless entries for manually started agents, so falling back to the pane would keep an
;; exited child "live" forever and make `no-live-children` unreachable.
;; `when-let` on the raw listing matters: `(into {} … nil)` would yield a truthy `{}` and
;; silently classify every candidate as vanished on an exit-0 payload with no `agents` key.
;; A genuine empty listing is `[]`, which is truthy, so zero live agents still short-circuits.
(defn live-agents []
  (try (when-let [agents (herdr/agents)]
         (into {} (keep #(when-let [name (:name %)] [name %])) agents))
       (catch Exception _ nil)))
;; `agent wait` takes a single target, so N children cannot be awaited in one call:
;; `--any` polls result files each tick instead of blocking in `herdr/wait!`, sleeping
;; `min(poll-interval, remaining-budget)` so the total timeout is never overshot by a
;; full interval. Candidates are same-session by construction, so a capture is always
;; owned and `capture!`/`maybe-close!` are reused unchanged — only the captured child's
;; pane is closed, under the existing COMPLETE/FAILED + settled rules. That poll budget
;; bounds waiting for a *publication* only; the post-capture settle wait below is a
;; separate, additional bound, so a late capture can return up to `settle-close-ms` after
;; `--timeout` would have elapsed.
(defn collect-any! [opts]
  (let [session (try (:parent-session (parent-identity)) (catch Exception _ nil))
        ;; Without `--wait` the budget is zero: one poll, then the same `timeout` outcome.
        deadline (+ (System/currentTimeMillis)
                    (if (one opts :wait) (Long/parseLong (or (one opts :timeout) "600000")) 0))]
    ;; Distinct from `no-candidates`: nothing can be scoped without a caller identity, and
    ;; reporting an empty fan-out would hide the misconfiguration. Non-final, like every
    ;; other `pending`, and non-throwing to match `collect`, which never runs `preflight!`.
    (if-not session
      {:status "pending" :reason "unknown-caller"}
      (loop []
        (let [candidates (any-candidates session)
              ;; Settle-close, after the capture and never before it: `maybe-close!` probes
              ;; `agent_status` exactly once with no retry, so a child still mid-turn at the
              ;; instant of capture would keep its COMPLETE/FAILED pane forever. One bounded
              ;; `herdr/wait!` — deliberately *not* `wait-settled!`, whose `--until idle
              ;; --until done` would burn the whole budget on a blocked child; the bare form
              ;; returns on any settled state, including blocked, whose pane must be kept
              ;; anyway. A captured BLOCKED envelope never closes a pane, so it skips the
              ;; wait entirely. Whatever the outcome — settled, timed out, or a herdr error —
              ;; the unmodified `maybe-close!` makes the same single close attempt, and
              ;; giving up only retains the pane: no result field, `remaining` included,
              ;; depends on the close outcome.
              captured (fn [[entry parsed]]
                         (when (#{"COMPLETE" "FAILED"} (:status parsed))
                           (try (herdr/wait! (:child entry) (settle-close-ms)) (catch Exception _ nil)))
                         (assoc (maybe-close! entry parsed true) :remaining (dec (count candidates))))]
          (if (empty? candidates)
            {:status "pending" :reason "no-candidates"}
            (if-let [hit (capture-first candidates)]
              (captured hit)
              (let [index (live-agents)
                    ;; Re-scan before any terminal short-circuit: a child can publish and
                    ;; exit inside the `agent list` subprocess window, and `blocked` /
                    ;; `no-live-children` must never discard a valid envelope already on
                    ;; disk. The first scan stays as the fast path.
                    hit (capture-first candidates)]
                (if hit
                  (captured hit)
                  (let [live (when index (filterv #(contains? index (:child %)) candidates))
                        blocked (when index (filterv #(= "blocked" (:agent_status (get index (:child %)))) live))]
                    (cond
                      (and index (empty? live)) {:status "pending" :reason "no-live-children"}
                      (and index (seq live) (= (count live) (count blocked))) {:status "blocked" :tasks (mapv :task blocked)}
                      :else (let [remaining (- deadline (System/currentTimeMillis))]
                              (if (<= remaining 0) {:status "pending" :reason "timeout"}
                                  (do (Thread/sleep (min (poll-interval-ms) remaining)) (recur)))))))))))))))
(defn live [entry]
  (let [agent (try (herdr/agent! (:child entry)) (catch Exception _ nil))
        entry (or (when-not (:child-session entry) (record-session! (:task entry) (:agent_session agent))) entry)]
    (assoc entry :live-agent agent)))
;; `--help` is the documented non-JSON exception: it prints usage and exits 0 for any
;; command, so `oh task run --help` never returns "option requires a value".
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
;; Per-command signatures. The global `usage` lists which commands exist; this lists how
;; to call one, because a group listing cannot show positional arity and a caller who
;; guesses wrong pays a failed invocation. Keep each line true to the `require-positionals`
;; arity and flag reads in the handler directly below it.
(def signatures
  {"pane" ["split [--direction right|down] [--cwd DIR] [--env KEY=VALUE]*"
           "run <pane> <command>"
           "read <pane> [--source visible|recent|recent-unwrapped|detection] [--lines N] [--format text|ansi]"
           "wait-output <pane> (--match TEXT | --regex PATTERN) [--source S] [--lines N] [--timeout MS] [--raw]"
           "send-text <pane> <text>"
           "send-keys <pane> <key> [<key>...]"
           "close <pane>"
           "list [--workspace ID]"
           "current"
           "get <pane>"
           "layout <pane>"
           "rename <pane> <label>"]
   "tab" ["create [--workspace ID] [--cwd DIR] [--label TEXT] [--env KEY=VALUE]* [--focus]"
          "list [--workspace ID]"
          "focus <tab>"]
   "ws" ["create [--cwd DIR] [--label TEXT] [--env KEY=VALUE]* [--focus]"
         "list"
         "focus <workspace>"]
   "agent" ["start <name> --kind KIND --pane PANE [--native ARG]*"
            "prompt <target> <text>"
            "wait <target> [--timeout MS] [--until STATE]*"
            "read <target> [--source S] [--lines N] [--format text|ansi]"
            "send-keys <target> <key> [<key>...]"
            "focus <target>"
            "rename <target> (<name> | --clear)"
            "list"
            "get <target>"]
   "task" ["run <persona> (--task TEXT | --task-file PATH | stdin) [--kind KIND] [--model MODEL] [--timeout MS] [--tab|--split] [--spawns NAMES|none] [--retro|--no-retro] [--prompt-extra TEXT] [--print-prompt]"
           "start <persona> (--task TEXT | --task-file PATH | stdin) [same options as run]"
           "collect <full-task-uuid> [--wait] [--timeout MS]"
           "collect --any [--wait] [--timeout MS]"
           "status [full-task-uuid]"
           "list"
           "publish --status COMPLETE|BLOCKED|FAILED --summary TEXT [--artifact PATH]* [--finding TEXT]* [--next TEXT] [--process TEXT]* [--from-file PATH] [--notify-timeout MS]"
           "progress --summary TEXT"
           "prune <full-task-uuid>"]
   "spawn" ["spawn \"<shell command>\""]})
(defn help-text
  "Global usage, one group's signatures, or a single command's signature."
  [group op]
  (if-let [lines (and group (signatures group))]
    (let [prefix (if (= group "spawn") "oh " (str "oh " group " "))
          matching (when (and op (not (str/starts-with? op "-")))
                     (seq (filter #(or (= op (first (str/split % #" ")))
                                       ;; `spawn`'s only signature repeats the group name.
                                       (= group "spawn"))
                                  lines)))]
      (str/join "\n" (map #(str prefix %) (or matching lines))))
    usage))
(defn require-positionals [items n description]
  (when-not (= n (count items))
    (fail (str description " requires " n " argument" (when (not= n 1) "s"))
          {:arguments items}))
  items)
(defn parse-env [values]
  (into {}
        (map (fn [value]
               (let [[key val] (str/split value #"=" 2)]
                 (when (or (str/blank? key) (nil? val))
                   (fail "--env must be KEY=VALUE" {:value value}))
                 [key val])))
        values))
(defn current-cwd [] (System/getProperty "user.dir"))
(defn raw-pane! [op opts positional]
  (case op
    "split" (herdr/split! {:direction (or (one opts :direction) (core/direction (herdr/caller-rect!)))
                            :cwd (or (one opts :cwd) (current-cwd)) :env (parse-env (all opts :env))})
    "run" (let [[pane command] (require-positionals positional 2 "pane run")] (herdr/pane-run! pane command))
    "read" (let [[pane] (require-positionals positional 1 "pane read")] (herdr/pane-read! pane {:source (one opts :source) :lines (one opts :lines) :format (one opts :format)}))
    "wait-output" (let [[pane] (require-positionals positional 1 "pane wait-output")
                         match (one opts :match) regex (one opts :regex)]
                     (when (= (boolean match) (boolean regex))
                       (fail "pane wait-output requires exactly one of --match or --regex" {}))
                     (herdr/pane-wait-output! pane (assoc {:source (one opts :source) :lines (one opts :lines) :timeout (one opts :timeout) :raw (one opts :raw)} (if regex :regex :match) (or regex match))))
    "send-text" (let [[pane text] (require-positionals positional 2 "pane send-text")] (herdr/pane-send-text! pane text))
    "send-keys" (let [[pane & keys] positional]
                  (when (or (nil? pane) (empty? keys)) (fail "pane send-keys requires a pane and at least one key" {}))
                  (herdr/pane-send-keys! pane keys))
    "close" (let [[pane] (require-positionals positional 1 "pane close")] (herdr/close! pane))
    "list" (do (require-positionals positional 0 "pane list")
                 (let [items (herdr/pane-list! (one opts :workspace))]
                   (herdr/render-panes items (:pane_id (herdr/current-pane!)))))
    "current" (do (require-positionals positional 0 "pane current") (herdr/current-pane!))
    "get" (let [[pane] (require-positionals positional 1 "pane get")] (herdr/pane! pane))
    "layout" (let [[pane] (require-positionals positional 1 "pane layout")] (herdr/pane-layout! pane))
    "rename" (let [[pane label] (require-positionals positional 2 "pane rename")] (herdr/rename! pane label))
    (fail "unknown pane command" {:command op})))
(defn raw-tab! [op opts positional]
  (case op
    "create" (do (require-positionals positional 0 "tab create")
                  (herdr/tab-create! {:workspace (one opts :workspace) :cwd (or (one opts :cwd) (current-cwd))
                                      :label (one opts :label) :env (parse-env (all opts :env)) :focus (boolean (one opts :focus))}))
    "list" (do (require-positionals positional 0 "tab list") (herdr/tab-list! (one opts :workspace)))
    "focus" (let [[tab] (require-positionals positional 1 "tab focus")] (herdr/tab-focus! tab))
    (fail "unknown tab command" {:command op})))
(defn raw-workspace! [op opts positional]
  (case op
    "create" (do (require-positionals positional 0 "ws create")
                  (herdr/workspace-create! {:cwd (or (one opts :cwd) (current-cwd)) :label (one opts :label)
                                            :env (parse-env (all opts :env)) :focus (boolean (one opts :focus))}))
    "list" (do (require-positionals positional 0 "ws list") (herdr/workspace-list!))
    "focus" (let [[workspace] (require-positionals positional 1 "ws focus")] (herdr/workspace-focus! workspace))
    (fail "unknown ws command" {:command op})))
(defn raw-agent! [op opts positional]
  (case op
    "start" (let [[name] (require-positionals positional 1 "agent start") kind (or (one opts :kind) (fail "agent start requires --kind" {})) pane (or (one opts :pane) (fail "agent start requires --pane" {}))]
              (herdr/start! name kind pane (:native opts)))
    "prompt" (let [[target text] (require-positionals positional 2 "agent prompt")] (herdr/prompt! target text))
    "wait" (let [[target] (require-positionals positional 1 "agent wait")] (herdr/agent-wait! target (Long/parseLong (or (one opts :timeout) "600000")) (all opts :until)))
    "read" (let [[target] (require-positionals positional 1 "agent read")] (herdr/agent-read! target {:source (one opts :source) :lines (one opts :lines) :format (one opts :format)}))
    "send-keys" (let [[target & keys] positional]
                  (when (or (nil? target) (empty? keys)) (fail "agent send-keys requires a target and at least one key" {}))
                  (herdr/agent-send-keys! target keys))
    "focus" (let [[target] (require-positionals positional 1 "agent focus")] (herdr/agent-focus! target))
    "rename" (let [[target name] positional clear? (boolean (one opts :clear))]
               (when (or (nil? target) (and (not clear?) (nil? name)) (and clear? name))
                 (fail "agent rename requires <target> <name> or <target> --clear" {}))
               (herdr/agent-rename! target name clear?))
    "list" (do (require-positionals positional 0 "agent list") (herdr/render-agents (herdr/agents)))
    "get" (let [[target] (require-positionals positional 1 "agent get")] (herdr/agent! target))
    (fail "unknown agent command" {:command op})))
(defn task! [op opts positional]
  (case op
    "run" (let [entry (spawn! (first positional) opts "blocking")] (if (:preview entry) entry (wait-and-capture! entry (Long/parseLong (or (one opts :timeout) "600000")) true)))
    "start" (spawn! (first positional) opts "non-blocking")
    "collect" (let [any? (boolean (one opts :any))]
                (when (and any? (first positional)) (fail "collect --any takes no task argument" {:task (first positional)}))
                (if any? (collect-any! opts) (collect! (first positional) opts)))
    "status" (if-let [task (first positional)] (live (ledger/read! task)) (mapv live (ledger/entries)))
    "list" (mapv live (ledger/entries))
    "publish" (publish! opts)
    "progress" (progress! opts)
    "prune" (prune! (first positional))
    (fail "unknown task command" {:command op})))
(defn spawn-command! [opts positional]
  (let [[command] (require-positionals positional 1 "spawn")]
    (when (some #{command} (available-personas))
      (fail "spawn only runs shell commands; use `oh task run <persona>` for delegation" {:persona command}))
    (let [pane (herdr/tab-create! {:cwd (or (one opts :cwd) (current-cwd)) :label (or (one opts :label) "spawn") :env {} :focus false})]
      (herdr/pane-run! (:pane_id pane) command)
      {:pane-id (:pane_id pane) :tab-id (:tab-id pane)})))
(defn execute [argv]
  (let [[group op & args] argv]
    (if (help-request? group (cons op args))
      (help-text group op)
      (let [opts (option-map args) positional (:_ opts)]
        (case group
          "pane" (raw-pane! op opts positional)
          "tab" (raw-tab! op opts positional)
          "ws" (raw-workspace! op opts positional)
          "agent" (raw-agent! op opts positional)
          "task" (task! op opts positional)
          "spawn" (spawn-command! (option-map (cons op args)) (:_ (option-map (cons op args))))
          (fail "unknown oh command" {:command group}))))))
(defn -main [& argv]
  (try (let [result (execute argv)] (println (if (string? result) result (core/json-envelope true result))))
       (catch Exception e (println (core/json-envelope false {:message (.getMessage e) :data (ex-data e)})) (System/exit 1))))

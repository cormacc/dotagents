(ns herdr-orch.cli
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [herdr-orch.core :as core]
            [herdr-orch.herdr :as herdr]
            [herdr-orch.ledger :as ledger])
  (:import [java.nio.file Files FileAlreadyExistsException Paths]
           [java.util UUID]))

(def usage "oh pane split|run|read|wait-output|send-text|send-keys|close|list|current|get|layout|rename\noh tab create|list|focus\noh ws create|list|focus\n\nRAW AGENT CONTROL\n  oh agent start|prompt|wait|read|send-keys|focus|rename|list|get\n\nDELEGATION TASK PROTOCOL\n  oh task run|start <persona> --task TEXT [--tab|--split] [--spawns NAMES|none] [options]\n  oh task collect <full-task-uuid> [--wait --timeout MS]\n  oh task collect --any [--wait --timeout MS]\n  oh task status [full-task-uuid] | list\n  oh task publish --status STATUS --summary TEXT [--artifact PATH]* [--finding TEXT]* [--next TEXT] [--process TEXT]* [--task UUID] [--notify-timeout MS]\n  oh task progress --summary TEXT [--task UUID]\n  oh task prune <full-task-uuid>\n  oh task continue <full-task-uuid> --task TEXT [--wait]\n  oh task close <full-task-uuid> | oh task close --settled\n\noh spawn \"<shell command>\"\n\nspawn creates an unfocused tab, runs an ordinary shell command in its root pane, and reports that pane id. It never delegates; use `oh task run <persona>` for a persona.\n--notify-timeout bounds the settle wait before the advisory parent push under the non-blocking policy (default 30000 ms).\n--tab places the delegated child in a new unfocused tab of the caller's workspace instead of a split; --split explicitly selects a split.\n--spawns overrides the persona's `spawns:` allow-list (whitespace/comma separated); the literal `none` forces a leaf.\nprogress stores one latest advisory snapshot for the injected child/task identity, throttled to ORCH_PROGRESS_INTERVAL_MS (default 60000 ms); it never signals completion.\nprune requires the caller's own :parent-session to own <full-task-uuid> and proves it stale (uncaptured, no RESULT, absent from one `agent list`) before marking it failed.\ncontinue assigns a settled, captured child another round in its existing context: root-only, guarded by the same live child+pane match close uses, allocating a fresh task and result and writing a new ledger entry with :continues. --wait blocks like run; the default is non-blocking like start.\nclose is the only path that closes a child pane: capture never does. It acts on a captured entry that is the newest round for its child, and only on a live observation matching both that entry's child name and its pane id in idle/done; --settled sweeps the caller's own captured children, newest round only, at most one attempt each.\ncollect, status, close, and prune all resolve their assignment argument as the exact ledger key emitted by task run/start; no prefix is ever resolved.\nOpaque assignment input is --task, --task-file, or stdin. Run `oh --help` for contract details.")
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
;; Bounds the settle wait `close` and `continue` make before reading liveness (see
;; `settle-and-list!`); same non-positive/unparseable/blank -> default discipline as
;; parse-poll-interval/parse-notify-timeout/parse-progress-interval. Capture makes no such
;; wait at all any more, so this budget is spent only by a verb an operator invoked.
;; The default is deliberately larger than `default-notify-timeout-ms`: a non-blocking
;; child publishing while its parent sits inside a collect reads `working` for that entire
;; notify wait (the parent, mid-collect, never settles idle/done), so a parent acting the
;; instant its collect returns still meets a `working` child, and a budget at or below
;; 30 000 ms would miss it.
(def default-settle-close-ms 45000)
(defn parse-settle-close [raw]
  (let [n (some-> raw str/trim not-empty parse-long)]
    (if (and n (pos? n)) n default-settle-close-ms)))
(defn settle-close-ms [] (parse-settle-close (System/getenv "ORCH_SETTLE_CLOSE_MS")))
;; Bounds the post-prompt dispatch check (`verify-dispatch!`); same non-positive/
;; unparseable/blank -> default discipline as the knobs above. The cadence inside that
;; budget is the shared `ORCH_POLL_INTERVAL_MS`, so verifying a spawn adds no second
;; interval knob. The default is sized against the 25.6-46.6 s submit delays observed
;; when the Enter was swallowed: it does not have to outlast them, only to notice the
;; held prompt and clear it, and the healthy path exits on the first observation.
(def default-dispatch-timeout-ms 15000)
(defn parse-dispatch-timeout [raw]
  (let [n (some-> raw str/trim not-empty parse-long)]
    (if (and n (pos? n)) n default-dispatch-timeout-ms)))
(defn dispatch-timeout-ms [] (parse-dispatch-timeout (System/getenv "ORCH_DISPATCH_TIMEOUT_MS")))
;; Capped independently of the budget: a misread state must cost at most a couple of
;; stray keys, never a burst for the whole budget.
(def max-dispatch-nudges 2)
;; Single source of truth for value-less flags. `option-map` and `help-request?` both
;; consume argv and must agree: a flag known to only one of them silently swallows the
;; following element (e.g. `run worker --retro --task 'X'` losing its assignment).
(def boolean-flags #{"--wait" "--print-prompt" "--retro" "--no-retro" "--tab" "--split" "--any" "--settled" "--focus" "--no-focus" "--clear" "--raw"})
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
     (core/validate-merged-config!
      (core/merge-config
       (or (config-file default-path) (fail "missing shipped default config" {:path default-path}))
       (or (config-file home-path) {})
       (or (config-file project-path) {}))))))
(defn parent-identity []
  (let [agent (herdr/agent! (System/getenv "HERDR_PANE_ID"))]
    {:parent-session (or (get-in agent [:agent_session :value]) (:pane_id agent)) :parent-kind (:agent agent) :parent-pane (:pane_id agent)}))
;; Composed from the resolved spawn policy, not the persona name: any persona whose
;; policy is non-empty gets the delegation sentence, everyone else the leaf sentence.
;; The closing sentence is the one lifecycle rule a child must be *told*, because it is the
;; one the CLI cannot enforce: only an entry's owner may close it, and for a grandchild that
;; owner is this child. It lives here, composed once for every spawner, rather than repeated
;; in each persona that happens to delegate.
(defn delegation-guidance [spawns]
  (if (seq spawns)
    (str "You may spawn at most one blocking " (str/join " or " spawns)
         " only when a factual gap or material judgment blocks progress; that child must remain a leaf."
         " Capturing its result closes nothing, so close it yourself with `$HERDR_ORCH_BIN task close <its full task uuid>` before you publish: nobody else can close a child you own.")
    "You are a leaf: do not spawn subagents."))
(defn retro-instruction [retro-skill]
  (when retro-skill
    (str "\nBefore publishing, apply steps 1-2 of " retro-skill " to your own session, using that skill's own threshold and signal categories."
         "\nEmit each surviving candidate as one `--process` item shaped `signal → category → proposed rule` (at most five)."
         "\nEmit nothing when the session does not meet that threshold; an absent PROCESS section is a valid outcome, not a failure."
         "\nDo not choose a destination, load `self-improvement`, run `ot`, or edit any instruction file: the parent owns approval and persistence.")))
;; Advisory only, and only worth asking for when there is nobody blocking on this child
;; already: a `blocking` run's parent is already waiting and gets nothing extra to poll.
;; `task` is supplied only for a continued round, whose injected `HERDR_ORCH_TASK` names an
;; earlier round: the identity is written into the command rather than left to the child to
;; work out. A spawn's environment is already correct, so it passes none.
(defn progress-instruction
  ([waiting-policy] (progress-instruction waiting-policy nil))
  ([waiting-policy task]
   (when (= waiting-policy "non-blocking")
     (str "\nReport concise phase-boundary progress with `$HERDR_ORCH_BIN task progress"
          (when task (str " --task " task))
          " --summary \"...\"` at most once per ORCH_PROGRESS_INTERVAL_MS (default 60000 ms); never include draft findings or result content, and never treat it as completion."))))
(defn prompt-text [{:keys [spawns persona-path task result waiting-policy assignment prompt-extra retro-skill]}]
  (str "Read " persona-path ", adopt that role. Task: " assignment "\n\n"
       (delegation-guidance spawns) " Herdr assigned TASK=" task " and RESULT=" result ". "
       "When finished, publish exactly once with `$HERDR_ORCH_BIN task publish --status COMPLETE --summary \"...\"`; do not send result text to the parent PTY. "
       "If you cannot finish — an unrecoverable failure after reasonable retries, or a genuine blocking dependency — publish once with `--status BLOCKED` (dependency) or `--status FAILED` (unrecoverable), summarising work completed vs remaining; never stop silently or publish a second envelope after recovering. "
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
     ;; :model is the resolved (pre-alias) ID; :model-canonical is that ID after the
     ;; single-hop `:aliases` translation; :model-args is the effective translated
     ;; native spelling (e.g. `["--model" "opus"]`) from the merged roster config.
     :persona-path (str path) :kind kind :model model :model-canonical (core/canonical-model config model) :model-args (core/model-args config kind model) :placement placement :retro (:retro retro) :retro-source (:retro-source retro)
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
;; --- post-prompt dispatch verification ---------------------------------------------
;; `agent prompt` submits atomically, but a harness TUI still finishing startup can swallow
;; the Enter and leave the composed prompt sitting unsubmitted in the child's composer: of
;; nine spawns in the session that motivated this, seven needed a manual Enter and two
;; dispatched unaided, so this must tolerate a prompt that already landed. A dispatched
;; prompt drives the child out of `idle`, so a definite `idle` is the held-prompt signal and
;; one `enter` clears it. The nudge is gated on that *definite* reading: `unknown` means only
;; that the agent could not be observed, and a guessed Enter there submits stray empty input.
;; Failing to confirm is not a spawn failure -- the child may simply be slow, the pane is
;; kept, and the ordinary wait/collect path is unchanged -- so every Herdr call here is
;; best-effort and the outcome is recorded rather than thrown. The probe doubles as the
;; post-prompt `:child-session` backfill, so it adds no `agent get` of its own.
;;
;; Only a *persisting* idle is nudged: `agent prompt` returns once the keystrokes are
;; delivered, so a child that simply has not begun its turn yet also reads `idle` for a
;; moment. Requiring a second consecutive idle reading costs one poll interval in the held
;; case and nothing at all in the healthy one (a dispatched child is already out of `idle`
;; on the first probe, so a normal spawn sleeps zero times and makes exactly one call).
(defn verify-dispatch! [task child]
  (let [deadline (+ (System/currentTimeMillis) (dispatch-timeout-ms))]
    (loop [nudges 0 idle-readings 0]
      (let [agent (try (herdr/agent! child) (catch Exception _ nil))
            ;; Nested at `[:result :agent :agent_status]`, already unwrapped by
            ;; `herdr/agent!`. Reading it one level too shallow yields `unknown` for every
            ;; child, which the `unknown` gate turns into a silently inert check.
            status (or (:agent_status agent) "unknown")]
        (record-session! task (:agent_session agent))
        (if-not (contains? #{"idle" "unknown"} status)
          (ledger/update! task assoc :dispatched-at (now)
                          :dispatch {:status "dispatched" :state status :nudges nudges})
          (let [nudge? (and (= "idle" status) (pos? idle-readings) (< nudges max-dispatch-nudges))
                _ (when nudge? (try (herdr/agent-send-keys! child ["enter"]) (catch Exception _ nil)))
                nudges (cond-> nudges nudge? inc)
                remaining (- deadline (System/currentTimeMillis))]
            (if (pos? remaining)
              (do (Thread/sleep (min (poll-interval-ms) remaining))
                  (recur nudges (if (= "idle" status) (inc idle-readings) 0)))
              (ledger/update! task assoc :dispatch {:status "unconfirmed" :state status :nudges nudges}))))))))
(defn caller-owns? [entry]
  (boolean (when-let [recorded (:parent-session entry)]
             (= recorded (try (:parent-session (parent-identity)) (catch Exception _ nil))))))
;; Capture closes nothing. A pane persists until the parent explicitly closes or continues
;; it (`close-task!`, `continue!`), because the retain-or-close decision is only makeable
;; *after* reading the published result -- and because an auto-close was unreliable by
;; construction: its wait, probe, and close errors were all swallowed and could never alter
;; a result field, so a parent had to enforce closure anyway. The settle wait moved with it,
;; so no capture pays for one; the budget knob (`ORCH_SETTLE_CLOSE_MS`) is now spent only by
;; the verbs an operator invokes on purpose.
;;
;; What remains is bookkeeping: the ledger is repo-wide, so a capture from a session that
;; does not own the entry still reports that fact (an unresolvable caller identity is
;; non-owning), and an owned capture backfills the child's transcript reference while Herdr
;; still knows the agent. The probe is made only when that reference is still missing --
;; with no close to decide, an already-recorded session needs no `agent get` at all.
(defn finish-capture! [entry parsed owned?]
  (if-not owned?
    (assoc parsed :pane-retained true :ownership "foreign-parent-session")
    (do (when-not (:child-session entry)
          (record-session! (:task entry) (:agent_session (try (herdr/agent! (:child entry)) (catch Exception _ nil)))))
        parsed)))
(defn wait-and-capture! [entry timeout owned?]
  (let [deadline (+ (System/currentTimeMillis) timeout)]
    (loop []
      (if-let [parsed (capture! entry)] (finish-capture! entry parsed owned?)
          (let [remaining (- deadline (System/currentTimeMillis))]
            (if (<= remaining 0) {:status "pending" :reason "timeout" :task (:task entry) :pane-id (:pane-id entry)}
                (let [outcome (herdr/wait! (:child entry) remaining)
                      ;; The wait outcome already carries the AgentInfo: no extra Herdr call.
                      _ (record-session! (:task entry) (get-in outcome [:value :result :agent :agent_session]))
                      current (capture! entry)]
                  (if current (finish-capture! entry current owned?)
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
          ;; No waiting policy is injected: it lives on the ledger entry alone (see
          ;; `publish!`), so a child continued into another round can never publish under
          ;; its spawn-time policy. Nothing lifecycle-related reaches the child's env.
          (let [env (cond-> {"HERDR_ORCH_CHILD" name "HERDR_ORCH_TASK" task "HERDR_ORCH_RESULT" result "HERDR_ORCH_BIN" bin "HERDR_ORCH_PERSONA" persona "HERDR_ORCH_SPAWNS" (str/join " " (:spawns spawns))}
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
                    ;; `:prompted-at` timestamps the submission *attempt*, never the moment
                    ;; the child began the turn: the two differ by however long a swallowed
                    ;; Enter held the prompt. `verify-dispatch!` records `:dispatched-at` for
                    ;; that, and also backfills the session a read at `start` usually misses.
                    (ledger/update! task assoc :status "prompted" :prompted-at (now))
                    (verify-dispatch! task name))))
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
;;      boundary `close-task!` enforces for pane closure. A parent whose session cannot be
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
;; Names the whole remaining sequence, not just the capture: since capture closes nothing,
;; a parent told only to `collect` leaves a pane standing every time it acts on this push.
(defn push-text [bin child task status artifacts]
  (str "Subagent " child " published a " status " result for task " task
       ". Capture it with `" bin " task collect " task "`, then close or continue it:"
       " `" bin " task close " task "` or `" bin " task continue " task " --task '<next assignment>'`."
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
                           ;; Re-checked on the freshly observed AgentInfo. `agent wait` pins
                           ;; the resolved occupant, so a *replacement* cannot satisfy the wait
                           ;; itself; the re-check guards the unguarded gap between this wait
                           ;; returning and the `agent prompt` submission below.
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
;; --- child-side identity resolution -------------------------------------------------
;; `--task` overrides the injected `HERDR_ORCH_TASK`, so a child continued into a further
;; round publishes against that round's own entry without a respawned environment.
;; `HERDR_ORCH_CHILD` is what a `--task` can never reach past: the name is stable across a
;; child's rounds and every entry records it, so a mistyped uuid can only ever name another
;; round of the caller's own child, never a sibling's assignment.
(defn task-identity [opts]
  (let [env #(System/getenv %)]
    {:child (or (env "HERDR_ORCH_CHILD") (fail "missing HERDR_ORCH_CHILD" {}))
     :task (or (one opts :task) (env "HERDR_ORCH_TASK") (fail "missing HERDR_ORCH_TASK" {}))}))
;; A child continued into a new round whose environment still names an earlier round's task
;; publishes into an existing RESULT. The remedy is mechanical and unambiguous exactly when
;; one uncaptured entry names this child, so the failure names it -- but never resolves it,
;; which would reintroduce the implicit re-targeting this design forbids.
(defn- stale-identity-hint [child task]
  (try
    (let [open (filterv #(and (= child (:child %)) (nil? (:captured-at %)) (not= task (:task %))) (ledger/entries))]
      (when (= 1 (count open)) (str " Retry with `--task " (:task (first open)) "`.")))
    (catch Exception _ nil)))
(defn publish! [opts]
  (let [env #(System/getenv %)
        {:keys [child task]} (task-identity opts)
        ;; A hand-driven publish (SKILL.md § Manual fallback) has no entry at all: it still
        ;; writes its RESULT, but with no entry there is no policy, no parent, and hence no
        ;; toast and no push. One uniform rule, not a special case.
        entry (try (ledger/read! task) (catch Exception _ nil))
        _ (when (and entry (not= child (:child entry)))
            (fail "child identity mismatch: the named assignment belongs to another child" {:task task :child child :entry-child (:child entry)}))
        ;; The entry owns the result path and the waiting policy; env supplies the result
        ;; only where there is no entry to derive it from, and the policy has no env home
        ;; at all, so a stale one cannot be represented.
        result (or (:result entry) (env "HERDR_ORCH_RESULT") (fail "missing HERDR_ORCH_RESULT" {}))
        policy (:waiting-policy entry)
        body (publication-body opts)
        ;; Publication is exactly-once and immutable, so a relative artifact path must fail
        ;; before the write, not only at collect (core/artifact-path is the same check
        ;; there): a child could otherwise never repair a COMPLETE-but-invalid envelope.
        ;; Checked on the raw --artifact/--from-file values, before any path is rewritten.
        ;; `str` guards a non-string --from-file artifact entry: without it a nil/numeric
        ;; JSON value would NPE before reaching artifact-path's own clean error.
        _ (doseq [artifact (:artifacts body)] (core/artifact-path (str artifact)))
        text (core/envelope (merge {:child child :task task :result result} body)) target (fs/path result) temp (fs/path (str result "." (UUID/randomUUID) ".tmp"))]
    (fs/create-dirs (fs/parent target))
    (try
      (spit (str temp) text) (Files/createLink (Paths/get (str target) (make-array String 0)) (Paths/get (str temp) (make-array String 0))) (fs/delete-if-exists temp)
      ;; Result publication is committed before notification. Notification failure is observable but never turns it into a retryable failure.
      (let [notification (when (= policy "non-blocking") (try (herdr/notify! (str "Subagent " child " published") (str "child=" child " task=" task " result=" result)) (catch Exception e {:notification-error (.getMessage e)})))
            ;; The operator toast is retained; the push is additional. Both are gated on the
            ;; entry's own policy, so a ledger-less publish is silent by construction.
            push (when (= policy "non-blocking")
                   (notify-parent! entry {:child child :task task :status (:status body)
                                          :artifacts (:artifacts body)
                                          :timeout (parse-notify-timeout (one opts :notify-timeout))}))]
        (cond-> {:task task :result result :status (:status body)}
          notification (assoc :notification notification)
          push (assoc :parent-push push)))
      (catch FileAlreadyExistsException e
        (fs/delete-if-exists temp) (try (herdr/notify! "Subagent publish failed" (str "child=" child " pane=" (or (env "HERDR_PANE_ID") "unknown") " task=" task " error=RESULT already exists")) (catch Exception _))
        (throw (ex-info (str "RESULT already exists; publication is exactly once." (stale-identity-hint child task)) {:result result :task task} e)))
      (catch Exception e
        (fs/delete-if-exists temp) (try (herdr/notify! "Subagent publish failed" (str "child=" child " pane=" (or (env "HERDR_PANE_ID") "unknown") " task=" task " error=" (.getMessage e))) (catch Exception _)) (throw e)))))
(defn collect! [task opts]
  (let [entry (ledger/read! task) owned? (caller-owns? entry)]
    (if (:wait opts) (wait-and-capture! entry (Long/parseLong (or (one opts :timeout) "600000")) owned?)
        (if-let [parsed (capture! entry)] (finish-capture! entry parsed owned?) {:status "pending" :task task :pane-id (:pane-id entry)}))))
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
  (let [{:keys [child task]} (task-identity opts)
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
;; --- explicit pane closure -----------------------------------------------------------
;; Capture closes nothing, so this verb is the whole closure path: explicit, guarded, and
;; auditable. It owns the settle wait (`ORCH_SETTLE_CLOSE_MS`) that capture used to spend on
;; every collect, and spends it only when an operator has actually asked for a close.
;;
;; The evidence bar is a live observation matching *both* the entry's `:child` name and its
;; `:pane-id`. A released name proves only that the name is free: the recorded pane may host
;; an unrelated replacement agent or a user's own shell, which is precisely why `prune!`
;; refuses to close a pruned entry's pane. Name absence is therefore `gone`, not a close.
;;
;; Ownership is `prune!`'s rule exactly -- both `:parent-session`s non-nil and equal -- with
;; no exception for a dead owner, deliberately. An entry whose owning session has gone is
;; therefore unclosable through this verb, and its pane is an operator cleanup (§ Close in
;; contract.md), the same remedy `prune` already prescribes for another session's stale entry.
;;
;; Two mechanisms were considered and both rejected on evidence. Testing whether the recorded
;; `:parent-session` transcript path still exists does not work at all: pi keeps session
;; transcripts on disk indefinitely, so the file outlives the agent and the test never fires
;; (measured on an orphaned child whose parent had been dead for minutes). Inferring death
;; from `agent list` is buildable -- every listed agent does carry its `agent_session` -- but
;; not sound: absence from one listing proves only that the session is not attached right now,
;; exactly as a child's absence proves only that its name was released, and the recorded value
;; falls back to a bare `pane_id` when Herdr tracks no session for the caller, which no session
;; set can ever contain. Either mistake closes a live foreign parent's child. A guard that
;; never fires and a guard that fires wrongly are both worse than a documented gap.
;; `ledger/entries` is sorted by `:created-at`, so the last entry naming a child is that
;; child's current round -- the only round whose `:pane-id` may be acted on. Continuation
;; lineage needs no traversal of `:continues`: the child name is stable across rounds.
(defn- newest-round [entries child]
  (last (filter #(= child (:child %)) entries)))
(defn- assert-closable! [entry entries]
  (let [task (:task entry) child (:child entry)
        caller (caller-parent-session) recorded (:parent-session entry)]
    (when-not (and caller recorded (= caller recorded))
      (fail "close refused: caller session does not own this ledger entry" {:task task}))
    (when-not (:captured-at entry)
      (fail "close refused: assignment is not captured" {:task task}))
    (when (:closed-at entry)
      (fail "close refused: this round's pane was already closed" {:task task :closed-at (:closed-at entry)}))
    (when-not (:pane-id entry)
      (fail "close refused: entry records no pane" {:task task}))
    (let [newest (newest-round entries child)]
      (when-not (= task (:task newest))
        (fail (if (:captured-at newest)
                "close refused: a newer round exists for this child"
                "close refused: an uncaptured newer round names this child")
              {:task task :child child :newest (:task newest)})))))
(defn- close-observed! [entry index]
  (let [task (:task entry) child (:child entry) agent (get index child)]
    (cond
      (nil? agent) {:status "gone" :reason "child-absent" :task task :child child :pane-id (:pane-id entry)}
      (not= (:pane-id entry) (:pane_id agent))
      (fail "close refused: the recorded pane is not this child's pane"
            {:task task :child child :recorded (:pane-id entry) :observed (:pane_id agent)})
      (not (contains? #{"idle" "done"} (:agent_status agent)))
      {:status "retained" :reason "unsettled" :task task :child child :pane-id (:pane-id entry) :agent-status (:agent_status agent)}
      :else
      ;; The listing can race a further round being written, so the freshest ledger state is
      ;; re-validated inside the mutation, mirroring `prune!`'s race re-check. `herdr/close!`
      ;; runs inside it too: `ledger/update!` writes nothing if its function throws, so a
      ;; failed close can never leave a `:closed-at` recording a closure that never happened.
      (let [updated (ledger/update! task
                                    (fn [current]
                                      (when (:closed-at current) (fail "close refused: already closed" {:task task}))
                                      (when-not (= task (:task (newest-round (ledger/entries) child)))
                                        (fail "close refused: a newer round appeared during the settle wait" {:task task :child child}))
                                      (herdr/close! (:pane-id current))
                                      (assoc current :closed-at (now))))]
        {:status "closed" :task task :child child :pane-id (:pane-id entry) :closed-at (:closed-at updated)}))))
;; Bounded settle wait, then exactly one positively-usable listing. Shared by `close` and
;; `continue` because both act immediately after a capture, where the child is routinely
;; still `working` -- the same race that once forced a settle wait into capture itself, and
;; the reason a bare probe would find almost every freshly captured child unsettled.
;; The wait's outcome is never consulted: settled, timed out, or errored, the listing
;; decides. Deliberately the bare `herdr/wait!` and not `wait-settled!`, whose `--until idle
;; --until done` would burn the whole budget on a blocked child the listing refuses anyway.
(defn- settle-and-list! [verb entry]
  (try (herdr/wait! (:child entry) (settle-close-ms)) (catch Exception _ nil))
  (or (live-agents) (fail (str verb " refused: agent list is unusable; liveness is unknown") {:task (:task entry)})))
(defn- settle-then-close! [entry]
  (close-observed! entry (settle-and-list! "close" entry)))
(defn close-task! [task]
  (when-not task (fail "close requires a full task uuid" {}))
  (let [entry (ledger/read! task)]
    (assert-closable! entry (ledger/entries))
    (settle-then-close! entry)))
;; The sweep's candidate filter *is* its guard, and it is the same rule `assert-closable!`
;; applies one entry at a time: owned (strictly -- a bulk sweep grants no dead-owner
;; recovery), captured, not already closed, with a pane, and the newest round for its child.
;; Considering only the newest round per child is what makes "at most one close attempt per
;; child" structural rather than a counter. `invalid` captures are excluded: that status
;; means the envelope needs manual intervention, and a sweep must not quietly take away the
;; pane an operator would use to deal with it.
(defn close-settled! []
  (let [caller (or (caller-parent-session) (fail "close --settled refused: caller session is unresolvable" {}))]
    (->> (ledger/entries)
         (group-by :child)
         vals
         ;; Newest round per child is chosen *before* any filtering, so a child whose
         ;; current round is uncaptured, foreign, or already closed drops out entirely
         ;; rather than falling back to an older round that names the same pane.
         (map last)
         (filter #(and (= caller (:parent-session %)) (:captured-at %) (:pane-id %)
                       (nil? (:closed-at %)) (not= "invalid" (:status %))))
         (sort-by :created-at)
         ;; One refusal must not abandon the rest of the sweep, so each child's outcome --
         ;; including a refusal, named in full -- is reported in the array instead.
         (mapv (fn [entry]
                 (try (settle-then-close! entry)
                      (catch Exception e {:status "refused" :task (:task entry) :child (:child entry)
                                          :reason (.getMessage e) :detail (ex-data e)})))))))
;; --- follow-on rounds ---------------------------------------------------------------
;; A settled child keeps its context, so `continue` assigns it another round in place rather
;; than paying for a fresh spawn to rebuild what it already knows. The child stays entirely
;; lifecycle-agnostic: it is never told it was continued or retained, only that it has a new
;; assignment under a new TASK, with the one command it must run written out in full.
;;
;; The prompt carries assignment content and that command -- nothing the CLI could have
;; guaranteed itself. The round's identity is interpolated (never "publish under your new
;; task"), the round's policy decides the progress clause mechanically, and `publish!` reads
;; the same policy off the same entry, so prompt wording cannot diverge from behaviour.
;;
;; The revalidation clause is the one instruction that cannot be mechanised: a long
;; residency's real risk is anchoring on its own earlier conclusions, and only the child can
;; re-check them.
(defn continuation-prompt [{:keys [assignment task result waiting-policy]}]
  (str "Follow-on round in the role you already hold. Task: " assignment "\n\n"
       "This is a new assignment, not a revision of your last one: Herdr assigned TASK=" task " and RESULT=" result ". "
       "Revalidate every prior finding and every mutable baseline against current source before restating it; a claim you have not re-checked this round does not carry forward. "
       "When finished, publish exactly once with `$HERDR_ORCH_BIN task publish --task " task " --status COMPLETE --summary \"...\"`; do not send result text to the parent PTY. "
       "If you cannot finish — an unrecoverable failure after reasonable retries, or a genuine blocking dependency — publish once with `--status BLOCKED` (dependency) or `--status FAILED` (unrecoverable), summarising work completed vs remaining; never stop silently or publish a second envelope after recovering."
       (progress-instruction waiting-policy task)))
(defn continue! [prior-task opts]
  (when-not prior-task (fail "continue requires a full task uuid" {}))
  ;; Root-only, and refused before preflight, ledger allocation, and any pane mutation: a
  ;; delegated child deciding to keep its own children resident is exactly the hidden
  ;; lifecycle this design removes, so a denied continue creates nothing at all.
  (when-let [persona (System/getenv "HERDR_ORCH_PERSONA")]
    (fail "continue is root-only: a delegated child cannot assign follow-on rounds" {:own-persona persona}))
  (let [assignment (task-text opts)
        waiting-policy (if (one opts :wait) "blocking" "non-blocking")
        entry (ledger/read! prior-task)
        child (:child entry)
        entries (vec (ledger/entries))]
    (herdr/preflight!)
    (let [ident (try (parent-identity) (catch Exception _ nil))
          caller (:parent-session ident)
          recorded (:parent-session entry)]
      (when-not (and caller recorded (= caller recorded))
        (fail "continue refused: caller session does not own this ledger entry" {:task prior-task}))
      ;; A validated envelope, so the parent has actually read a result before deciding to
      ;; continue. `invalid`, `failed`, pruned, and uncaptured entries all fall out of this
      ;; one check; `BLOCKED` is admitted deliberately -- resuming a blocked child needs a
      ;; fresh task anyway, and this is that verb.
      (when-not (and (:captured-at entry) (contains? #{"COMPLETE" "FAILED" "BLOCKED"} (:status entry)))
        (fail "continue refused: the prior round is not captured with a validated envelope"
              {:task prior-task :status (:status entry)}))
      (when (:closed-at entry)
        (fail "continue refused: this child's pane was already closed" {:task prior-task :closed-at (:closed-at entry)}))
      (when-let [open (seq (filter #(and (= child (:child %)) (nil? (:captured-at %))) entries))]
        (fail "continue refused: an uncaptured round already names this child"
              {:task prior-task :child child :open (mapv :task open)}))
      (when-not (= prior-task (:task (newest-round entries child)))
        (fail "continue refused: a newer round exists for this child"
              {:task prior-task :child child :newest (:task (newest-round entries child))}))
      ;; Same live evidence bar as `close`: the name and the pane must both match, and the
      ;; child must be settled -- prompt text delivered to a `blocked` agent lands in its
      ;; approval UI rather than starting a round.
      (let [index (settle-and-list! "continue" entry)
            agent (or (get index child) (fail "continue refused: the child is absent from the agent list" {:task prior-task :child child}))]
        (when-not (= (:pane-id entry) (:pane_id agent))
          (fail "continue refused: the recorded pane is not this child's pane"
                {:task prior-task :child child :recorded (:pane-id entry) :observed (:pane_id agent)}))
        (when-not (contains? #{"idle" "done"} (:agent_status agent))
          (fail "continue refused: the child is not settled" {:task prior-task :child child :agent-status (:agent_status agent)}))
        (let [task (ledger/fresh-task)
              result (ledger/fresh-result task)
              ;; `:child` is inherited, never re-derived: a continued round's child name
              ;; belongs to the spawn that created it and has no relation to this round's
              ;; own uuid. Display and policy metadata carry over unchanged; the parent
              ;; fields come from the caller, which owns this round.
              next-entry (merge (select-keys entry [:child :pane-id :tab-id :label :index :persona-path :retro :retro-source :spawns :spawns-source :placement])
                                {:task task :result result :continues prior-task
                                 :parent-session caller :parent-pane (:parent-pane ident)
                                 :waiting-policy waiting-policy :status "continuing" :created-at (now)})]
          ;; Persisted before the pane is prompted, exactly as `spawn!` does: a failure
          ;; between the two leaves a recoverable uncaptured entry rather than a prompted
          ;; child that no entry names.
          (ledger/write! next-entry)
          (herdr/prompt! child (continuation-prompt {:assignment assignment :task task :result result :waiting-policy waiting-policy}))
          (ledger/update! task assoc :status "prompted" :prompted-at (now))
          (verify-dispatch! task child)
          (let [written (ledger/read! task)]
            (if (one opts :wait)
              (wait-and-capture! written (Long/parseLong (or (one opts :timeout) "600000")) true)
              written)))))))
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
;; owned and `capture!`/`finish-capture!` are reused unchanged. No pane is closed here, or
;; on any other capture path: the fan-in's whole cost is bounded by its own poll budget.
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
              ;; `remaining` is a function of capture and candidacy alone: the candidate set
              ;; observed at capture, never anything the closure path might later do.
              captured (fn [[entry parsed]]
                         (assoc (finish-capture! entry parsed true) :remaining (dec (count candidates))))]
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
           "publish --status COMPLETE|BLOCKED|FAILED --summary TEXT [--artifact PATH]* [--finding TEXT]* [--next TEXT] [--process TEXT]* [--from-file PATH] [--task UUID] [--notify-timeout MS]"
           "progress --summary TEXT [--task UUID]"
           "prune <full-task-uuid>"
           "continue <full-task-uuid> (--task TEXT | --task-file PATH | stdin) [--wait] [--timeout MS]"
           "close <full-task-uuid>"
           "close --settled"]
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
    "continue" (continue! (first positional) opts)
    "close" (let [settled? (boolean (one opts :settled))]
              (when (and settled? (first positional)) (fail "close --settled takes no task argument" {:task (first positional)}))
              (if settled? (close-settled!) (close-task! (first positional))))
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

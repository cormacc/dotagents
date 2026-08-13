(ns herdr-orch.cli
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [herdr-orch.core :as core]
            [herdr-orch.herdr :as herdr]
            [herdr-orch.ledger :as ledger]
            [herdr-orch.traits :as traits])
  (:import [java.io ByteArrayOutputStream]
           [java.nio.charset StandardCharsets]
           [java.nio.file Files FileAlreadyExistsException Paths]
           [java.util UUID]))

(def usage "oh pane split|run|read|wait-output|send-text|send-keys|close|list|current|get|layout|rename\noh tab create|list|focus\noh ws create|list|focus\n\nRAW AGENT CONTROL\n  oh agent start|prompt|wait|read|send-keys|focus|rename|list|get\n\nDELEGATION TASK PROTOCOL\n  oh task run|start <persona> --task TEXT [--tab|--split] [--spawns NAMES|none] [options]\n  oh task collect <full-task-uuid> [--wait --timeout MS] [--close] [--format text] [--raw]\n  oh task collect --any [--wait --timeout MS] [--close] [--format text] [--raw]\n  oh task status [full-task-uuid] | list [--format text] [--raw]\n  oh task publish --status STATUS --summary TEXT [--artifact PATH]* [--finding TEXT]* [--next TEXT] [--process TEXT]* [--task UUID] [--notify-timeout MS]\n  oh task prune <full-task-uuid>\n  oh task continue <full-task-uuid> --task TEXT [--wait]\n  oh task close <full-task-uuid> | oh task close --settled\n  oh task orphans [--close]\n  oh task compact <full-task-uuid> | oh task compact --closed\n  oh task harvest [--format text]\n\nWORKTREE TEARDOWN\n  oh worktree list\n  oh worktree remove <full-task-uuid>\n\noh spawn \"<shell command>\"\n\nspawn creates an unfocused tab, runs an ordinary shell command in its root pane, and reports that pane id. It never delegates; use `oh task run <persona>` for a persona.\n--notify-timeout bounds the settle wait before the advisory parent push under the non-blocking policy (default 30000 ms).\n--tab places the delegated child in a new tab of the caller's workspace; --split places it in a split of the caller's pane. Either flag overrides the configured :defaults :placement, which ships as :tab-split (tab at root, split below root).\n--spawns overrides the persona's `spawns:` allow-list (whitespace/comma separated); the literal `none` forces a leaf.\n\nprune requires the caller's own :parent-session to own <full-task-uuid> and proves it stale (uncaptured, no RESULT, absent from one `agent list`) before marking it failed.\ncontinue assigns a settled, captured child another round in its existing context: root-only, guarded by the same live child+pane match close uses, allocating a fresh task and result and writing a new ledger entry with :continues. --wait blocks like run; the default is non-blocking like start.\nharvest returns this session's PROCESS candidates from the ledger, deduplicated, with every child and task that raised each one; it is read-only and routes, persists, and acts on nothing.\ncompact retires bulk rather than entries: it drops the raw envelope text (a duplicate of the parsed fields and of the retained RESULT file) from a closed or terminal round the caller owns, keeping the task, child, :child-session, and artifact links a cited uuid resolves through. --closed sweeps the caller's own such rounds.\norphans lists, and under --close closes, captured rounds owned by a session other than this one, applying close's own live child+pane match; a foreign session is not a dead one, so the authority is the operator's and the list is the default. Root-only, like continue: a delegated child is refused before any listing or mutation, because every sibling and ancestor session looks equally foreign to it.\nclose is the only path that closes a *spawned* child's pane: no capture does, and the only other closure is spawn-failure cleanup taking a pane the child never worked in. It acts on a captured entry that is the newest round for its child, and only on a live observation matching that entry's child name and pane id in idle/done -- or, when the name has been released (a resumed process), a live pane whose recorded shell_pid still matches and whose foreground is not busy; --settled sweeps the caller's own captured children, newest round only, at most one attempt each.\ncollect, status, close, and prune all resolve their assignment argument as the exact ledger key emitted by task run/start; no prefix is ever resolved.\ncollect, status, and list accept --format text for a compact line rendering, and emit the raw result envelope beside the parsed fields only under --raw.\ncollect --close captures and then closes in one call, under every close guard, reporting that outcome under :close without ever degrading the capture.\nworktree list enumerates this session's recorded checkouts (one per child lineage, current round only) with the same reconciliation object collect/status report; worktree remove takes the checkout and never the branch -- `git worktree remove` leaves the branch and its commits intact, so removing a checkout can never destroy committed work, and deleting the branch remains the parent's own act. It applies prune/close's own ownership rule (both :parent-session non-nil and equal), refuses a dirty checkout by naming the dirty paths, refuses while any live round of the session still references the checkout, and reports tip ancestry against the parent's live HEAD as information only, never a gate. close, prune, orphans, and collect --close never touch a checkout; teardown is only ever these two verbs.\nOpaque assignment input is --task, --task-file, or stdin. Run `oh --help` for contract details.")
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
;; Throttle floor for WAITING result items; same non-positive/unparseable/blank ->
;; default discipline as parse-poll-interval/parse-notify-timeout.
(def default-waiting-interval-min-ms 60000)
(defn parse-waiting-interval-min [raw]
  (let [n (some-> raw str/trim not-empty parse-long)]
    (if (and n (pos? n)) n default-waiting-interval-min-ms)))
(defn waiting-interval-min-ms []
  (parse-waiting-interval-min (System/getenv "ORCH_WAITING_INTERVAL_MIN_MS")))
;; Each stream snapshot reads at most this many immutable item files and this many bytes per
;; file. Over-limit streams refuse rather than treating an unseen terminal as unsealed.
(def default-max-stream-items 1000)
(defn parse-max-stream-items [raw]
  (let [n (some-> raw str/trim not-empty parse-long)]
    (if (and n (pos? n)) n default-max-stream-items)))
(defn max-stream-items []
  (parse-max-stream-items (System/getenv "ORCH_MAX_STREAM_ITEMS")))
(def default-max-envelope-bytes 65536)
(defn parse-max-envelope-bytes [raw]
  (let [n (some-> raw str/trim not-empty parse-long)]
    (if (and n (pos? n)) n default-max-envelope-bytes)))
(defn max-envelope-bytes []
  (parse-max-envelope-bytes (System/getenv "ORCH_MAX_ENVELOPE_BYTES")))
;; Bounds the settle wait `close` and `continue` make before reading liveness (see
;; `settle-and-list!`); same non-positive/unparseable/blank -> default discipline as
;; parse-poll-interval/parse-notify-timeout. Capture makes no such
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
;; Single source of truth for delegation value-less flags. The raw tab/workspace create
;; `--focus` operator flag is scoped to those commands by `boolean-flags-for`; both argv
;; consumers use the same resolved set so no value-less flag swallows the next element.
(def boolean-flags #{"--wait" "--print-prompt" "--retro" "--no-retro" "--tab" "--split" "--any" "--settled" "--clear" "--raw" "--close" "--closed" "--abandon" "--worktree"})
(defn boolean-flags-for [group op]
  (cond-> boolean-flags
    (and (#{"tab" "ws"} group) (= "create" op)) (conj "--focus")))
(defn option-map [args flags known]
  (loop [xs args out {}]
    (if-let [x (first xs)]
      (cond (= "--" x) (assoc out :native (vec (next xs)))
            (str/starts-with? x "--")
            (let [key (keyword (subs x 2))]
              (when-not (known key)
                (fail "unknown option" {:option x}))
              (if (flags x)
                (recur (next xs) (assoc out key true))
                (let [value (second xs)]
                  (when-not value (fail "option requires a value" {:option x}))
                  (recur (nnext xs) (update out key (fnil conj []) value)))))
            :else (recur (next xs) (update out :_ (fnil conj []) x)))
      out)))
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
(defn trait-directories []
  (traits/trait-directories (ledger/assignment-root) (home-directory) (skill-directory)))
(defn- interpolation-failure! [message trait directories]
  (fail (str "trait `" trait "` " message "; searched layers: "
             (str/join ", " (map #(str (:source %) "=" (:directory %)) directories))
             "; write it as code or fix the trait name")
        {:trait trait
         :searched-layers (mapv :source directories)
         :searched-paths (vec (mapcat #(traits/trait-candidate-paths % trait) directories))}))
(defn- incompatible-trait-failure! [message remediation declaring-trait incompatible-trait directories]
  (fail (str "trait `" declaring-trait "` declares incompatible trait `" incompatible-trait "` " message
             "; searched layers: "
             (str/join ", " (map #(str (:source %) "=" (:directory %)) directories))
             "; " remediation)
        {:trait incompatible-trait
         :declaring-trait declaring-trait
         :incompatible-trait incompatible-trait
         :searched-layers (mapv :source directories)
         :searched-paths (vec (mapcat #(traits/trait-candidate-paths % incompatible-trait) directories))}))
(defn- incompatibility-declarations [result]
  (for [{:keys [trait]} (:resolved result)
        incompatible-trait (get (:incompatibilities result) trait)]
    {:declaring-trait trait :incompatible-trait incompatible-trait}))
(defn- trait-interpolation [path persona-text]
  (let [directories (trait-directories)
        result (traits/interpolate {:text persona-text
                                    :directories directories
                                    :exists? #(fs/exists? %)
                                    :read-text slurp})
        declarations (incompatibility-declarations result)]
    (when-let [trait (first (filter #(< 2 (count %)) (:unknowns result)))]
      (interpolation-failure! "was not found in the searched layers" trait directories))
    (when-let [trait (first (:repeats result))]
      (interpolation-failure! "appears more than once in the persona body" trait directories))
    (when-let [{:keys [declaring-trait incompatible-trait]}
               (first (remove #(traits/resolve-trait (fn [candidate] (fs/exists? candidate))
                                                    directories
                                                    (:incompatible-trait %))
                              declarations))]
      (incompatible-trait-failure! "was not found in the searched layers"
                                  (str "fix the name in `incompatible-with:` on trait `" declaring-trait "`")
                                  declaring-trait incompatible-trait directories))
    (let [resolved-traits (set (map :trait (:resolved result)))]
      (when-let [{:keys [declaring-trait incompatible-trait]}
                 (first (filter #(contains? resolved-traits (:incompatible-trait %)) declarations))]
        (incompatible-trait-failure! "also resolves in the persona body"
                                    (str "remove `%" declaring-trait "` or `%" incompatible-trait
                                         "` from the persona body: these directives state opposing rules and must not compose")
                                    declaring-trait incompatible-trait directories)))
    (let [sources (mapv #(select-keys % [:trait :source :path]) (:resolved result))]
      (cond-> {:persona-path (str path)
               :traits (mapv :trait sources)
               :trait-sources sources}
        (seq sources) (assoc :composed-content (:text result))))))
(defn composed-persona-path [task persona]
  (str (fs/path (ledger/assignment-root) ".tmp" "herdr-orch" "composed" (str task "-" persona ".md"))))
(defn materialize-persona! [composition task persona]
  (if-let [content (:composed-content composition)]
    (let [path (composed-persona-path task persona)]
      (fs/create-dirs (fs/parent path))
      (spit path content)
      (-> composition (assoc :persona-path path) (dissoc :composed-content)))
    composition))
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
;; The spawning agent's own model, feeding `resolve-model`'s same-kind inheritance tier.
;; `oh` injects HERDR_ORCH_MODEL into every child it spawns, so a child spawning a
;; grandchild reads back the model it was itself started with; at the root, where nothing
;; injected it, fall back to the harness's own environment. Inheritance is one level at
;; every depth because each spawn only ever reads its immediate parent's value.
;; Only pi's variables are read, and only when the parent actually is pi: claude and codex
;; expose no verified equivalent, and an unrelated PI_MODEL left in the shell of a
;; claude/codex root would otherwise be inherited as if it were that root's model.
(defn parent-model [parent-kind]
  (or (System/getenv "HERDR_ORCH_MODEL")
      (when (= "pi" parent-kind)
        (when-let [model (System/getenv "PI_MODEL")]
          (if-let [provider (System/getenv "PI_PROVIDER")] (str provider "/" model) model)))))
(defn parent-identity []
  (let [agent (herdr/agent! (System/getenv "HERDR_PANE_ID"))]
    {:parent-session (or (get-in agent [:agent_session :value]) (:pane_id agent)) :parent-kind (:agent agent) :parent-pane (:pane_id agent)
     :parent-model (parent-model (:agent agent))}))
;; Composed from the resolved spawn policy, not the persona name: any persona whose
;; policy is non-empty gets the delegation sentence, everyone else the leaf sentence.
;; The closing sentence is the one lifecycle rule a child must be *told*, because it is the
;; one the CLI cannot enforce: only an entry's owner may close it, and for a grandchild that
;; owner is this child. It lives here, composed once for every spawner, rather than repeated
;; in each persona that happens to delegate.
;; Closeout fix (P2): the close-before-publish sentence this used to carry is deleted, not
;; reworded -- `assert-children-discharged!` now refuses `publish` mechanically while a
;; child still owns an open round, and that refusal already names the exact remedy
;; (`collect`, then `close` or `prune`) at the one moment it is actionable, which is a
;; guard added while its prose survived doing the identical job worse: standing advice a
;; child can ignore, versus a refusal it cannot. Restating it here would be exactly the
;; failure phase 2 existed to prevent.
(defn delegation-guidance [spawns]
  (if (seq spawns)
    (str "You may spawn at most one blocking " (str/join " or " spawns)
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
;; Every round's task is written into the command, including a spawn: a child cannot publish
;; against a task it does not know, and a continued child still has its original task in env.
(defn waiting-instruction [waiting-policy task]
  (when (= waiting-policy "non-blocking")
    (str "\nReport concise phase-boundary status with `$HERDR_ORCH_BIN task publish --task " task
         " --status WAITING --summary \"...\"` at most once per ORCH_WAITING_INTERVAL_MIN_MS "
         "(default 60000 ms); never include draft findings or terminal result content.")))
;; Universal result-inbox routing belongs to the wrapper, which knows every child has one;
;; persona-local output sections keep only the role-specific definition of a key finding.
(def ^:private publication-guidance
  "Published `SUMMARY` must be a single line. Write multi-line detail to the assignment-provided report path (fall back to `.tmp/`), pass the report with `--artifact`, and emit each key finding with `--finding`; do not hide findings only in `SUMMARY`, and never treat pane text as the result.")
(defn prompt-text [{:keys [spawns persona-path task result waiting-policy assignment prompt-extra retro-skill]}]
  (str "Read " persona-path ", adopt that role. Task: " assignment "\n\n"
       (delegation-guidance spawns) " Herdr assigned TASK=" task " and RESULT=" result ". "
       "When finished, publish exactly once with `$HERDR_ORCH_BIN task publish --status COMPLETE --summary \"...\"`; do not send result text to the parent PTY. "
       "If you cannot finish — an unrecoverable failure after reasonable retries, or a genuine blocking dependency — publish once with `--status BLOCKED` (dependency) or `--status FAILED` (unrecoverable), summarising work completed vs remaining; never stop silently or publish a second envelope after recovering. "
       publication-guidance
       (retro-instruction retro-skill)
       (waiting-instruction waiting-policy task)
       (when prompt-extra (str "\nAdditional constraints: " prompt-extra))))
;; Kind is a deployment property: a persona declares `kind:` once, or the child inherits the
;; harness Herdr measured for the parent. `--kind` is refused rather than ignored, because
;; `option-map` accepts any `--x value` pair and a dropped flag would spawn the wrong harness
;; silently. Raw `oh agent start --kind` is untouched.
(defn kind-policy [opts frontmatter parent-kind]
  (when (one opts :kind)
    (fail "--kind is not a spawn-time flag: declare `kind:` in the persona definition"
          {:requested (one opts :kind)}))
  (core/resolve-kind {:frontmatter frontmatter :parent-kind parent-kind}))
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
;; Wait budget for this round: an explicit `--timeout` on *this* command first, then the
;; budget the spawn resolved and recorded (`--timeout` at spawn, else the persona's
;; `timeout:`), then the shipped default. Recording it is what lets a later `collect --wait`
;; and a `continue --wait` inherit the persona's own budget instead of asking every caller to
;; restate it. `collect --any` has no single entry to read and keeps flag-or-default.
(defn timeout-policy [persona opts frontmatter]
  (core/resolve-timeout {:persona persona :flag (one opts :timeout) :frontmatter frontmatter}))
(defn- round-timeout [entry opts]
  (or (some->> (one opts :timeout) (core/timeout-value! "--timeout" nil))
      (:timeout entry)
      core/default-timeout-ms))
(defn worktree-flag [opts] (boolean (one opts :worktree)))
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
  (let [path (roster persona)
        persona-text (slurp (str path))
        frontmatter (core/parse-frontmatter persona-text)
        composition (trait-interpolation path persona-text)
        prompt-persona-path (if (:composed-content composition) "<composed-persona-path>" path)
        ident (parent-identity)
        kind (kind-policy opts frontmatter (:parent-kind ident))
        model (core/resolve-model {:requested (one opts :model) :resolved-kind kind :frontmatter frontmatter :parent-kind (:parent-kind ident) :parent-model (:parent-model ident)})
        config (config)
        placement (placement-policy opts config)
        retro (retro-policy persona opts frontmatter)
        spawns (spawns-policy persona opts frontmatter)
        timeout (timeout-policy persona opts frontmatter)]
    {:preview (prompt-text {:spawns (:spawns spawns) :persona-path prompt-persona-path :task "<assigned-task>" :result "<assigned-result>" :waiting-policy waiting-policy :assignment (task-text opts) :prompt-extra (one opts :prompt-extra) :retro-skill (:retro-skill retro)})
     ;; :model is the resolved (pre-alias) ID; :model-canonical is that ID after the
     ;; single-hop `:aliases` translation; :model-args is the effective translated
     ;; native spelling (e.g. `["--model" "opus"]`) from the merged roster config.
     :persona-path (str path) :traits (:traits composition) :trait-sources (:trait-sources composition)
     :kind kind :model model :model-canonical (core/canonical-model config model) :model-args (core/model-args config kind model) :placement placement :retro (:retro retro) :retro-source (:retro-source retro)
     :spawns (:spawns spawns) :spawns-source (:spawns-source spawns)
     :timeout (:timeout timeout) :timeout-source (:timeout-source timeout)}))
;; A stream's item records are append-only by item identity. Pre-stream ledger entries retain
;; their historical stable head only, so expose that head as item 1 while readers migrate; no
;; ledger rewrite is needed just to read an old round.
(defn captured-items [entry]
  (if (contains? entry :items)
    (vec (:items entry))
    (cond-> []
      (:captured-at entry)
      (conj (select-keys (assoc entry :item 1) [:item :result :captured-at :status :envelope :artifacts :artifact-links :process-overflow :invalid-reason :invalid-data])))))

;; This is the single stream predicate used by later capture and lifecycle operations: published
;; items come from immutable files, captured items from the ledger, and only a validated terminal
;; item seals the round. An invalid record carries no envelope and therefore can never seal it.
;;
;; Sealing is a property of the *published* stream, never of capture (closeout finding: a seal
;; computed from captured items alone let a child publish WAITING past its own terminal item
;; before the parent collected, so the ledger head read WAITING on a completed round). The seal
;; depends only on the immutable envelope and its ledger identity; artifact existence is mutable
;; and belongs solely to capture-time validation. An unreadable item is indeterminate rather than
;; unsealed, so publication refuses instead of reopening a round on an IO failure.
(defn- read-envelope! [{:keys [item result]}]
  (let [limit (max-envelope-bytes)
        bytes (try
                (with-open [input (io/input-stream result)]
                  (let [output (ByteArrayOutputStream.)
                        buffer (byte-array 8192)]
                    (loop [read-total 0]
                      (let [remaining (inc (- limit read-total))
                            read (.read input buffer 0 (int (min (alength buffer) remaining)))]
                        (cond
                          (= -1 read) (.toByteArray output)
                          (< limit (+ read-total read))
                          (fail "result envelope exceeds byte limit" {:item item :result result :limit limit})
                          :else (do (.write output buffer 0 read)
                                    (recur (+ read-total read))))))))
                (catch Exception e
                  (fail "result item cannot be read" {:item item :result result}))) ]
    (String. ^bytes bytes StandardCharsets/UTF_8)))
(defn- validated-item [entry item]
  ;; An unreadable item is distinct from an immutable malformed envelope: capture records the
  ;; former as invalid, while publish refuses it rather than treating it as an unsealed stream.
  (try
    (let [text (read-envelope! item)]
      (try {:parsed (core/validate-envelope entry text)}
           (catch Exception e {:error e})))
    (catch Exception e {:read-error e})))
(defn- bounded-result-items [result]
  (let [items (ledger/result-items result)
        limit (max-stream-items)]
    (when (< limit (count items))
      (fail "result stream exceeds item limit" {:result result :limit limit :count (count items)}))
    items))
(defn- stream-state* [entry]
  (let [published (bounded-result-items (:result entry))
        captured (captured-items entry)
        consumed (into #{} (map :item) captured)
        validations (into {} (for [item published :when (not (contains? consumed (:item item)))]
                               [(:item item) (validated-item entry item)]))]
    {:published published
     :captured captured
     :validations validations
     :read-errors (->> validations
                       (keep (fn [[item validation]]
                               (when-let [error (:read-error validation)]
                                 {:item item :error error})))
                       vec)
     :sealed? (boolean (or (some #(and (map? (:envelope %))
                                       (core/terminal-status? (get-in % [:envelope :status])))
                                 captured)
                           (some #(some-> (get-in validations [(:item %) :parsed :status])
                                          core/terminal-status?)
                                 published)))}))
(defn stream-state [entry]
  (select-keys (stream-state* entry) [:published :captured :sealed?]))

;; Capture means the parent has consumed at least one immutable item and every item currently
;; on disk has a capture record. The non-empty capture requirement preserves the old refusal
;; for a round that has published nothing, while legacy captured entries remain readable even
;; if their scratch RESULT has since disappeared.
(defn stream-captured? [{:keys [published captured]}]
  (let [consumed (into #{} (map :item) captured)]
    (and (seq captured)
         (every? #(contains? consumed (:item %)) published))))

(defn- unconsumed-stream-items [{:keys [published captured]}]
  (let [consumed (into #{} (map :item) captured)]
    (filterv #(not (contains? consumed (:item %))) published)))
(defn unconsumed-items [entry]
  (unconsumed-stream-items (stream-state entry)))

;; Keep status/envelope as the stable latest-capture head while preserving an item-level audit
;; record beside it. Re-capturing an existing item replaces only that item's record; a newer
;; capture remains the head, mirroring `record-session!`'s stable-key/history approach without
;; making existing readers discover a new key.
(defn record-item-capture [entry item capture]
  (let [record (cond-> (merge capture item)
                 (:compacted-at entry) (update :envelope #(some-> % (dissoc :text))))
        records (conj (vec (remove #(= (:item item) (:item %)) (captured-items entry))) record)
        head (last records)
        stable-keys [:status :captured-at :envelope :artifacts :artifact-links :process-overflow :invalid-reason :invalid-data]]
    (-> (apply dissoc entry stable-keys)
        (assoc :items records)
        (merge (select-keys head stable-keys)))))

(defn record-item-capture! [task item capture]
  (ledger/update! task record-item-capture item capture))

;; A published item is immutable, so a result that fails validation cannot become valid.
;; Recording it as non-final `invalid` consumes that item without sealing the stream: the
;; child can publish a corrected successor and the next collect will capture that item.
(defn capture!
  ([entry] (capture! entry (stream-state* entry)))
  ([entry state]
   (when-let [item (first (unconsumed-stream-items state))]
     (let [result (:result item)
           captured (fn [parsed] (assoc parsed :item (:item item) :item-result result
                                        :terminal? (core/terminal-status? (:status parsed))))]
       (try
         (let [parsed (or (get-in state [:validations (:item item) :parsed])
                          (or (get-in state [:validations (:item item) :error])
                              (get-in state [:validations (:item item) :read-error])))
               artifacts (:artifacts parsed)]
          (doseq [artifact artifacts]
            (let [path (core/artifact-path artifact)]
              (when-not (fs/exists? path)
                (fail "result artifact does not exist" {:artifact artifact :path path}))))
          ;; Rendered only after every artifact passed the existence check above, so a
          ;; collected link is evidence in a way a publish-time advisory link is not. The
          ;; parent surfaces these to the user once the child pane is gone. `cond->`, not
          ;; an empty vector: absent is not the same claim as validated-empty.
          (let [links (when (seq artifacts) (mapv core/artifact-link artifacts))]
            ;; An over-length PROCESS section is degraded, not fatal: record the fact on the
            ;; entry and keep the envelope's own status. A compacted entry keeps its raw text
            ;; retired, while this capture still returns the freshly parsed item text.
            (record-item-capture! (:task entry) item
                                  (cond-> {:status (:status parsed) :captured-at (now)
                                           :envelope parsed :artifacts artifacts}
                                    links (assoc :artifact-links links)
                                    (:process-overflow parsed) (assoc :process-overflow true)))
            (captured (cond-> parsed links (assoc :artifact-links links)))))
        (catch Exception e
          ;; The stable head follows this invalid item, so stale artifact links or an
          ;; envelope from an earlier item never masquerade as this item's validation.
          (record-item-capture! (:task entry) item
                                {:status "invalid" :captured-at (now)
                                 :invalid-reason (.getMessage e) :invalid-data (ex-data e)})
           {:status "invalid" :task (:task entry) :pane-id (:pane-id entry) :item (:item item)
            :item-result result :terminal? false :reason (.getMessage e) :detail (ex-data e)
            :pane-retained true}))))))
;; The child's transcript reference is only reachable while Herdr still knows the agent,
;; so it is recorded opportunistically at every point the CLI already holds an
;; `AgentInfo`. The whole `agent_session` map is stored because its `value` is
;; discriminated by `kind` (a path or an opaque id). Every hook is best-effort: it never
;; fails a spawn or demotes a captured result.
;;
;; First-write-wins used to mean every later hook merely deferred to whichever observation
;; got here first, so a session recorded before a crash-and-resume stayed on the entry
;; forever even once every subsequent hook -- dispatch verification, each wait tick, a
;; capture-time backfill -- was observing the *new* session the resumed process actually
;; ran under (measured on this record's own phase-2 round: entry `21f8fbb1` still named the
;; session that crashed). Last-write-wins would trade one wrong reference for another in
;; the other direction, discarding whichever session held real work. So this compares
;; instead of merely backfilling: an unset `:child-session` is still simply set (unchanged
;; from before), an observation matching the current one is a no-op, and a genuinely
;; different observation replaces `:child-session` while the superseded value moves onto
;; `:child-session-history` -- appended only if not already present, so a session that is
;; observed again after another has intervened is not duplicated. Nothing is ever discarded,
;; and every existing reader (`compact`, the live smoke, contract.md) keeps resolving the
;; *newest* observation through the same stable `:child-session` key it always has; only a
;; reader that has actually seen a mismatch need consult the history.
;;
;; The no-op case is checked *before* `ledger/update!`, not merely inside the function it
;; runs: `ledger/update!` (`ledger.clj:43`) writes its `f`'s return value unconditionally,
;; even when that value is the entry unchanged, so an equal-observation branch living only
;; inside `f` still performed an atomic rewrite on every call -- on every wait tick, for a
;; settled child polled repeatedly. The pre-check here is what makes "no ledger write at
;; all" (contract.md § Ledger and completion) true, and it also removes the no-op case from
;; the documented read-modify-write lost-update race entirely rather than merely returning
;; the same value from inside it; the genuinely different-observation branch still runs the
;; race, unchanged. The `cond` retained inside `f` is the safety net for the race window
;; between this read and `update!`'s own: a session that arrived there in between changes
;; nothing about correctness, only which write count wins.
(defn record-session! [task session]
  (when (and task (map? session) (seq session))
    (try
      (when-not (= session (:child-session (ledger/read! task)))
        (ledger/update! task
                       (fn [entry]
                         (let [current (:child-session entry)]
                           (cond
                             (nil? current) (assoc entry :child-session session)
                             (= current session) entry
                             :else (-> entry
                                      (update :child-session-history
                                              (fn [history] (vec (distinct (conj (vec history) current)))))
                                      (assoc :child-session session)))))))
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
;; still knows the agent. The probe is made unconditionally, one `agent get` per owned
;; capture -- a bare `collect` of an already-published result is otherwise the one path that
;; never re-observes the child at all, so a crash-and-resume between spawn and this capture
;; left `:child-session` naming the dead session forever (task ecb85350's actual defect: the
;; `collect --wait` coverage that shipped for it only ever exercised the wait-loop hook,
;; whose `agent wait` outcome happens to carry the newer session first). `record-session!`
;; is itself the no-op guard for the common case -- an observation matching what is already
;; recorded costs a call but no ledger write.
(defn finish-capture! [entry parsed owned?]
  (if-not owned?
    (assoc parsed :pane-retained true :ownership "foreign-parent-session")
    (do (record-session! (:task entry) (:agent_session (try (herdr/agent! (:child entry)) (catch Exception _ nil))))
        parsed)))
(defn wait-and-capture! [entry timeout owned?]
  (let [deadline (+ (System/currentTimeMillis) timeout)]
    (loop []
      (if-let [parsed (capture! entry)] (finish-capture! entry parsed owned?)
          (let [remaining (- deadline (System/currentTimeMillis))]
            (if (<= remaining 0) {:status "pending" :terminal? false :reason "timeout"
                                  :task (:task entry) :pane-id (:pane-id entry)}
                (let [outcome (herdr/wait! (:child entry) remaining)
                      ;; The wait outcome already carries the AgentInfo: no extra Herdr call.
                      _ (record-session! (:task entry) (get-in outcome [:value :result :agent :agent_session]))
                      current (capture! entry)]
                  (if current (finish-capture! entry current owned?)
                      (if (and (:ok outcome) (= "blocked" (get-in outcome [:value :result :agent :agent_status])))
                        {:status "blocked" :terminal? false :task (:task entry)
                         :pane-id (:pane-id entry)}
                        (let [remaining* (- deadline (System/currentTimeMillis))]
                          (when (pos? remaining*) (Thread/sleep (min (poll-interval-ms) remaining*)))
                          (recur)))))))))))
(defn safe-cleanup! [entry phase]
  (ledger/update! (:task entry) assoc :status "failed" :failed-at (now) :failure-phase (name phase))
  (when (and (:pane-id entry) (#{"split" "rename" "start"} (name phase))) (try (herdr/close! (:pane-id entry)) (catch Exception _))) )
;; --- worktree checkout creation ------------------------------------------------------
;; "In flight" means genuinely unfinished, not merely unclosed: a round whose newest item
;; is a validated terminal result (COMPLETE/BLOCKED/FAILED) is done even if its pane was
;; never explicitly closed ("capture closes nothing" -- see `stream-state`), and the
;; publish-side discharge guard's own `outstanding-children` deliberately still counts that
;; case as outstanding *for closing purposes*. Reusing it here would match almost every
;; completed prior spawn of the same session as "in flight", which is not what a worker
;; concurrent with active work means. `newest-rounds` (defined below) keeps this to each
;; child lineage's current round, exactly as the discharge guard does. Must be called
;; before the new entry is written, or the new entry itself (unsealed, no `:closed-at`)
;; would immediately satisfy its own check.
(declare newest-rounds)
(defn- worktree-in-flight? [parent-session]
  (boolean (and parent-session
                (some #(and (= parent-session (:parent-session %))
                            (nil? (:closed-at %))
                            (not (:sealed? (stream-state %))))
                      (newest-rounds (ledger/entries))))))
(defn source-cwd [] (System/getProperty "user.dir"))
;; Every worktree git call targets the *source* cwd, never `ledger/assignment-root`: the
;; actual repository is wherever `oh` was launched from, and an `ORCH_ASSIGNMENT_ROOT`
;; override relocates only the ledger/RESULT/checkout *destination*, never which repository
;; HEAD, `status --porcelain`, and `worktree add` read from (task f49a63f5's constraint).
(defn- git! [dir & argv]
  (let [{:keys [exit out err]} @(process/process (into ["git"] argv) {:dir dir :out :string :err :string})]
    (if (zero? exit) (str/trim out)
        (fail (str "git " (str/join " " argv) " failed") {:dir dir :argv (vec argv) :exit exit :stderr (str/trim err)}))))
(defn head-sha [dir] (git! dir "rev-parse" "HEAD"))
;; One line per changed path, exactly as `git status --porcelain` emits it (status code
;; plus path); recorded verbatim on the ledger entry and surfaced in spawn output.
(defn dirty-paths [dir] (vec (remove str/blank? (str/split-lines (git! dir "status" "--porcelain")))))
;; Destination resolves under the assignment root, alongside every other per-task path
;; (`ledger/composed-persona-path`, `ledger/fresh-result`), never under the source cwd.
(defn worktree-checkout-path [task] (str (fs/path (ledger/assignment-root) ".tmp" "herdr-orch" "worktrees" task)))
(defn create-worktree! [dir path branch base]
  (fs/create-dirs (fs/parent (fs/path path)))
  (git! dir "worktree" "add" "-b" branch path base))
;; --- reconciliation object (task 47005e8f) -----------------------------------------
;; One identical shape rides on `collect`, `collect --any`, and `status`, because all
;; three already read the ledger entry a worktree round carries: no new tool call is ever
;; needed on the parent's side. Reported against the *recorded* `:base`, never the
;; parent's live HEAD -- the parent's own HEAD moving after spawn must change nothing here
;; (task f49a63f5/47005e8f's decision). Every git call below targets the *checkout*, not
;; the parent's source cwd, and is skipped entirely when the checkout directory is gone: a
;; missing directory (an operator removed it, or cleared `.tmp/`) is reported as `missing`
;; data, never an error the verb throws.
(defn- committed-paths [dir base tip]
  (vec (remove str/blank? (str/split-lines (git! dir "diff" "--name-status" base tip)))))
(defn reconciliation [entry]
  (when-let [{:keys [path branch base]} (:worktree entry)]
    (if-not (fs/exists? path)
      {:base base :branch branch :tip nil :checkout-state "missing" :committed nil :dirty nil}
      (let [tip (head-sha path)]
        ;; Committed and dirty sets stay distinct -- their union is "changed files", but
        ;; the parent's action differs per set (merge the branch vs rescue/re-prompt a
        ;; child whose work is uncommitted) -- so they are never collapsed into one list.
        {:base base :branch branch :tip tip :checkout-state "present"
         :committed (committed-paths path base tip)
         :dirty (dirty-paths path)}))))
;; Attached only when the entry actually carries worktree identity: a shared-tree round
;; has nothing to reconcile, so its response carries no `:reconciliation` key at all,
;; exactly as it carries no `:worktree` field on the ledger entry itself.
(defn- with-reconciliation [entry value]
  (cond-> value (:worktree entry) (assoc :reconciliation (reconciliation entry))))
(defn spawn! [persona opts waiting-policy]
  (enforce-spawns! persona opts)
  (if (one opts :print-prompt)
    (preview! persona opts waiting-policy)
    (do
      (herdr/preflight!)
      (let [path (roster persona)
            persona-text (slurp (str path))
            frontmatter (core/parse-frontmatter persona-text)
            ;; Trait interpolation and its failure policy run before parent identity,
            ;; config loading, task allocation, and every pane/ledger mutation.
            composition (trait-interpolation path persona-text)
            ident (parent-identity)
            kind (kind-policy opts frontmatter (:parent-kind ident))
            model (core/resolve-model {:requested (one opts :model) :resolved-kind kind :frontmatter frontmatter :parent-kind (:parent-kind ident) :parent-model (:parent-model ident)})
            ;; Loaded and schema-validated here, before `ledger/fresh-result`'s
            ;; `fs/create-dirs` and every later ledger/pane mutation: malformed config
            ;; must fail fast, never after allocation has begun.
            config (config)
            placement (placement-policy opts config)
            retro (retro-policy persona opts frontmatter)
            spawns (spawns-policy persona opts frontmatter)
            ;; Resolved before allocation, so an unparseable `timeout:` or `--timeout` fails
            ;; fast exactly like an invalid `retro:` or an unresolvable `spawns:` name.
            timeout (timeout-policy persona opts frontmatter)
            ;; Reuses whatever the trait scan above already resolved rather than a second
            ;; scan (task f49a63f5's constraint): `%worktree`/`%no-worktree`/`%read-only`
            ;; are read off `(:traits composition)`, and the in-flight check is the same
            ;; open-round predicate `publish` already applies to this parent session. Placed
            ;; after every other spawn-time validation above (kind/config/placement/retro/
            ;; spawns/timeout) so its own conflict check still fails before allocation or
            ;; mutation. `in-flight?` is short-circuited to `false` -- never evaluated, so
            ;; `ledger/entries`'s directory-creating side effect never runs -- whenever the
            ;; flag/trait already forces or suppresses the decision on their own.
            forced-or-suppressed? (or (core/worktree-forced? (worktree-flag opts) (:traits composition))
                                      (core/worktree-suppressed? (:traits composition)))
            worktree-decision (core/resolve-worktree {:flag (worktree-flag opts) :traits (:traits composition)
                                                       :in-flight? (and (not forced-or-suppressed?) (worktree-in-flight? (:parent-session ident)))
                                                       :read-only? (contains? (set (:traits composition)) "read-only")})
            task (ledger/fresh-task)
            composition (materialize-persona! composition task persona)
            persona-path (:persona-path composition)
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
            ;; Computed and included on `entry` before it is ever written, so the ledger
            ;; worktree fields exist before `git worktree add` runs: a crash between the
            ;; write below and the checkout call leaves a recoverable record, never an
            ;; orphaned checkout with no trace.
            worktree (when (:create? worktree-decision)
                       (let [source (source-cwd)]
                         {:path (worktree-checkout-path task) :branch (core/worktree-branch task)
                          :base (head-sha source) :parent-dirty (dirty-paths source)}))
            entry (cond-> {:task task :result result :child name :pane-id nil :label label :index index
                           :persona-path persona-path :kind kind :model model :parent-session (:parent-session ident) :parent-pane (:parent-pane ident) :waiting-policy waiting-policy :retro (:retro retro) :retro-source (:retro-source retro) :spawns (:spawns spawns) :spawns-source (:spawns-source spawns) :timeout (:timeout timeout) :timeout-source (:timeout-source timeout) :placement placement :worktree-trigger (:trigger worktree-decision) :status "allocating" :created-at (now)}
                    worktree (assoc :worktree worktree))]
        ;; Persist before the first pane mutation, so every partial failure is recoverable.
        (ledger/write! entry)
        (try
          ;; The checkout is created (and, on failure, labelled and left recoverable)
          ;; before any pane mutation, mirroring the entry-before-mutation ordering above.
          (when worktree
            (try (create-worktree! (source-cwd) (:path worktree) (:branch worktree) (:base worktree))
                 (catch Exception e (safe-cleanup! (ledger/read! task) :worktree) (throw e))))
          ;; No waiting policy is injected: it lives on the ledger entry alone (see
          ;; `publish!`), so a child continued into another round can never publish under
          ;; its spawn-time policy. Nothing lifecycle-related reaches the child's env.
          (let [child-cwd (or (:path worktree) (System/getProperty "user.dir"))
                env (cond-> {"HERDR_ORCH_CHILD" name "HERDR_ORCH_TASK" task "HERDR_ORCH_RESULT" result "HERDR_ORCH_BIN" bin "HERDR_ORCH_PERSONA" persona "HERDR_ORCH_SPAWNS" (str/join " " (:spawns spawns))}
                      ;; Keep a relocated assignment root in force for any nested delegation,
                      ;; and always pin it for a worktree child: its own cwd is a *different*
                      ;; git worktree whose own `git rev-parse --show-toplevel` would resolve
                      ;; to the checkout itself, never the parent's assignment root.
                      (or worktree (System/getenv "ORCH_ASSIGNMENT_ROOT")) (assoc "ORCH_ASSIGNMENT_ROOT" (ledger/assignment-root))
                      ;; This child's own model, so its grandchildren inherit from it exactly
                      ;; as it inherited from here (`parent-model`). Absent when unresolved,
                      ;; leaving a grandchild on the harness default rather than a stale value.
                      model (assoc "HERDR_ORCH_MODEL" model))
                ;; Tab placement skips caller-rect!/direction entirely: a tab needs neither.
                pane-placement (if (= placement "tab")
                                 (herdr/tab-create! {:cwd child-cwd :label label :env env :focus false})
                                 (herdr/split! {:direction (core/direction (herdr/caller-rect!)) :cwd child-cwd :env env}))
                persisted (ledger/update! task assoc :pane-id (:pane_id pane-placement) :tab-id (:tab-id pane-placement) :status "split")
                ;; Best-effort, exactly like `record-session!`: never fails a spawn. This is
                ;; the identity witness `close` falls back to when a resume has released the
                ;; child's agent name (task ca6fecef) -- the pane's shell is the *parent* of
                ;; whatever agent occupies it, so it survives that agent dying and a fresh
                ;; one starting in the same pane, unlike the name Herdr releases on exit.
                ;; Recorded now, on the bare freshly-split shell, rather than after `start!`:
                ;; the value is identical either way (it is the shell, never the foreground
                ;; process), so there is no reason to wait.
                _ (try (when-let [pid (:shell_pid (herdr/process-info! (:pane-id persisted)))]
                         (ledger/update! task assoc :shell-pid pid))
                       (catch Exception _ nil))]
            (try
              (let [renamed (herdr/rename! (:pane-id persisted) label)]
                (when-not (= label (:label renamed)) (fail "Herdr did not apply child pane label" {:expected label :actual (:label renamed)}))
                (ledger/update! task assoc :status "renamed")
                (let [native (concat (core/model-args config kind model)
                                     (core/harness-extra-args config kind)
                                     (core/persona-args kind persona-path))]
                  (record-session! task (:agent_session (herdr/start! name kind (:pane-id persisted) native)))
                  (let [prompt (prompt-text {:spawns (:spawns spawns) :persona-path persona-path :task task :result result :waiting-policy waiting-policy :assignment assignment :prompt-extra (one opts :prompt-extra) :retro-skill (:retro-skill retro)})]
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
;; `:parent-pane` is never nil on an entry this CLI wrote: both writers take it from
;; `parent-identity`, whose `agent get` must have succeeded for the caller to get this far
;; (`spawn!` additionally dereferences it in `pane get` before allocating anything). A
;; hand-edited ledger file that omits it is not a supported input -- this surface keeps no
;; legacy path anywhere -- and degrades to the `push-failed` catch below rather than
;; needing a branch of its own.
(defn notify-parent! [entry {:keys [child task status timeout artifacts]}]
  (try
    (let [pane (:parent-pane entry)
          send! (fn [extra]
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
;; Forward declarations for the ledger rules `publish!` shares with `close --settled`
;; (`newest-rounds`), for the same reason `prune!` forward-declares `live-agents`. The
;; `stale-identity-hint` that lived here died with the one-shot publish: a stale identity now
;; surfaces as the superseded-round refusal, which names the successor itself (`:newer-task`
;; in `assert-publishable!`), so the hint's remedy is expressed by the refusal it precedes.
(declare assert-children-discharged! newest-round)
(defn- link-publication! [result item text]
  (let [target (ledger/item-path result item)
        temp (str target "." (UUID/randomUUID) ".tmp")]
    (fs/create-dirs (fs/parent target))
    (try
      (spit temp text)
      (Files/createLink (Paths/get target (make-array String 0))
                        (Paths/get temp (make-array String 0)))
      {:item item :result target}
      (finally (fs/delete-if-exists temp)))))
(defn- append-publication! [result text]
  (let [items (bounded-result-items result)
        limit (max-stream-items)]
    (when (<= limit (count items))
      (fail "result stream exceeds item limit" {:result result :limit limit :count (count items)}))
    (loop [item (inc (or (some->> items (map :item) seq (apply max)) 0))]
      (let [outcome (try {:published (link-publication! result item text)}
                         (catch FileAlreadyExistsException _ {:exists true}))]
        (if (:exists outcome) (recur (inc item)) (:published outcome))))))
(defn- waiting-elapsed-ms [item]
  (try
    (let [elapsed (- (System/currentTimeMillis)
                     (.toMillis (fs/last-modified-time (:result item))))]
      (when (<= 0 elapsed) elapsed))
    (catch Exception _ nil)))
(defn- assert-publishable! [entry]
  (when entry
    (let [state (stream-state* entry)
          newest (newest-round (ledger/entries) (:child entry))]
      ;; Superseded wins over sealed: a stale identity's own round is usually sealed too
      ;; (its terminal item is what allowed the continuation), and only the superseded
      ;; refusal carries the actionable remedy -- retry with `--task <:newer-task>`.
      (when (and newest (not= (:task entry) (:task newest)))
        (fail "publish refused: round is superseded" {:task (:task entry) :newer-task (:task newest)}))
      (when-let [{:keys [item error]} (first (:read-errors state))]
        (fail "publish refused: result item cannot be read"
              {:task (:task entry) :item item :cause (.getMessage error)}))
      (when (:sealed? state)
        (fail "publish refused: round is sealed" {:task (:task entry)}))
      (when (:closed-at entry)
        (fail "publish refused: round is closed" {:task (:task entry) :closed-at (:closed-at entry)}))
      state)))
(defn- throttled-waiting [entry status state]
  (when (and entry (= "WAITING" status))
    (let [previous (last (:published state))
          elapsed (some-> previous waiting-elapsed-ms)
          interval (waiting-interval-min-ms)]
      (when (and elapsed (< elapsed interval))
        {:status "throttled" :task (:task entry) :result (:result entry)
         :previous-item (:item previous) :retry-after-ms (- interval elapsed)}))))
;; Closeout restoration: the pre-stream publish raised a best-effort "Subagent publish failed"
;; operator toast on every failed publication, and the stream rewrite dropped it without a
;; recorded decision -- leaving a refusal (sealed, superseded, closed, undischarged children,
;; validation) visible only inside the child's own pane. It is restored here, at the single
;; wrapper every ledger-backed and manual publish shares; the toast is best-effort and never
;; masks or changes the underlying failure.
(defn- publish-failure-toast! [child task e]
  (try (herdr/notify! "Subagent publish failed"
                      (str "child=" child " pane=" (or (System/getenv "HERDR_PANE_ID") "unknown")
                           " task=" task " error=" (.getMessage e)))
       (catch Exception _ nil)))
(defn- publish-round! [opts child task]
  (let [env #(System/getenv %)
        entry (try (ledger/read! task) (catch Exception _ nil))
        _ (when (and (one opts :task) (nil? entry))
            (fail "--task names no ledger entry" {:task task :child child}))
        _ (when (and entry (not= child (:child entry)))
            (fail "child identity mismatch: the named assignment belongs to another child"
                  {:task task :child child :entry-child (:child entry)}))
        result (or (:result entry) (env "HERDR_ORCH_RESULT")
                   (fail "missing HERDR_ORCH_RESULT" {}))
        policy (:waiting-policy entry)
        body (publication-body opts)
        _ (doseq [artifact (:artifacts body)] (core/artifact-path (str artifact)))
        text (core/envelope (merge {:child child :task task :result result} body))
        state (assert-publishable! entry)]
    (if-let [throttled (throttled-waiting entry (:status body) state)]
      throttled
      (let [released-children (assert-children-discharged! child)
            item (if entry
                   (append-publication! result text)
                   (link-publication! result 1 text))]
        ;; Publication is committed before notification. Notification failure is observable
        ;; but never turns it into a retryable failure.
        (let [notification (when (= policy "non-blocking")
                             (try (herdr/notify! (str "Subagent " child " published")
                                                (str "child=" child " task=" task " result=" (:result item)))
                                  (catch Exception e {:notification-error (.getMessage e)})))
              ;; WAITING reports are intentionally toast-only. Terminal reports retain the
              ;; existing non-blocking advisory push; ledger-less fallback stays silent.
              push (when (and (= policy "non-blocking")
                              (core/terminal-status? (:status body)))
                     (notify-parent! entry {:child child :task task :status (:status body)
                                            :artifacts (:artifacts body)
                                            :timeout (parse-notify-timeout (one opts :notify-timeout))}))]
          (cond-> {:task task :result (:result item) :status (:status body) :item (:item item)}
            notification (assoc :notification notification)
            push (assoc :parent-push push)
            (seq released-children) (assoc :released-children released-children)))))))
(defn publish! [opts]
  (let [{:keys [child task]} (task-identity opts)]
    (try (publish-round! opts child task)
         (catch Exception e (publish-failure-toast! child task e) (throw e)))))
(defn collect! [task opts]
  (let [entry (ledger/read! task) owned? (caller-owns? entry)]
    (with-reconciliation entry
      (if (:wait opts) (wait-and-capture! entry (round-timeout entry opts) owned?)
          (if-let [parsed (capture! entry)]
            (finish-capture! entry parsed owned?)
            {:status "pending" :terminal? false :task task :pane-id (:pane-id entry)})))))
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
;; `pane-alive?`/`pane-shell-pid!` are defined below, next to `released-name-close`, their
;; other caller; forward-declared so `assert-children-discharged!`'s release classification
;; (below) can share `close`'s own evidence for a name-absent round instead of a weaker one.
(declare pane-alive? pane-shell-pid!)
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
    (when (seq (:published (stream-state entry)))
      (fail "prune refused: a published RESULT item already exists" {:task task}))
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
                           (if (or (:captured-at current) (seq (:published (stream-state current))))
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
;; A terminal `failed` round is skipped, exactly as `any-candidates` already skips it: an
;; allocation that never reached its pane (a spawn cleaned up by `safe-cleanup!`, a
;; continuation whose prompt threw) is not a claim on anything, and leaving it "newest"
;; would wedge the child -- `close` would refuse it as uncaptured and refuse the real round
;; as stale, with no verb able to retire either.
(defn- live-round? [entry] (not= "failed" (:status entry)))
(defn- newest-round [entries child]
  (last (filter #(and (= child (:child %)) (live-round? %)) entries)))
;; Every child's current round and nothing else, which is the only round whose `:pane-id` is
;; a claim on a pane. Shared by `close --settled` and the publish-side discharge guard,
;; because a rule this subtle must not be spelled twice: an older captured round of a child
;; whose newest round was closed carries no `:closed-at` of its own -- `close` writes that on
;; the round it took -- so treating one as outstanding would be a permanent false positive.
(defn- newest-rounds [entries]
  (->> entries (group-by :child) vals (map #(filterv live-round? %)) (keep last)))
;; --- publish-side ownership discharge -------------------------------------------------
;; "Whoever spawns a child closes it" was prose only, and the ledger already holds the
;; answer: the measured consequence was a leaked grandchild pane whose remedy was to *ask* a
;; delegating child to close its scout and then assert it had. `publish` refuses instead, so
;; the rule is mechanical -- a delegating child cannot publish while it still owns an open
;; round -- and its own COMPLETE result is the proof its children were dealt with.
;;
;; "Own" is the same both-non-nil rule `prune` and `close` use, so an unresolvable caller
;; identity owns nothing and this guard cannot fire on it. That is deliberately the open
;; direction: a validated terminal item is the sole completion signal, so a guard that
;; cannot attribute a child must not be able to strand a finished result. The caller's own
;; child name is excluded too -- an entry naming this very child is its own round, never a
;; child of it.
;;
;; Outstanding means the round is still actionable *by this caller*: uncaptured (collect it,
;; or `prune` it if the spawn died), or captured with a pane and no `:closed-at`. A captured
;; round whose child has vanished from a positively-usable listing is *released* only when
;; `close`'s own fallback could not reclaim it either: the pane itself is gone, or its
;; `:shell-pid` no longer matches -- `close` then reports `gone` and mutates nothing, and
;; `prune` refuses a captured entry, so no verb can close it and blocking on it would be a
;; dead end rather than a lever. A round whose pane is still live and whose `:shell-pid`
;; still matches is instead classified *blocking*, exactly as `close`'s shell_pid fallback
;; (task ca6fecef) would still reclaim it: publishing past it would recreate the leak this
;; guard exists to prevent. That discharge is reported, not silent, because a released round
;; means a pane was left behind. An unusable listing proves nothing, so nothing is
;; discharged from one.
(defn- bin-hint [] (try (launcher-bin) (catch Exception _ "oh")))
(defn- outstanding-children [caller own-child entries]
  (filterv (fn [entry]
             (let [state (stream-state entry)]
               (and caller (:parent-session entry) (= caller (:parent-session entry))
                    (not= own-child (:child entry))
                    (or (not (stream-captured? state))
                        ;; A closed round is no longer live, but an unsealed resident child
                        ;; remains open even after each published WAITING item was captured.
                        (and (nil? (:closed-at entry))
                             (or (not (:sealed? state)) (:pane-id entry)))))))
           (newest-rounds entries)))
;; The same evidence `close`'s shell_pid fallback (task ca6fecef) uses for a name-absent
;; round: a live pane whose recorded `:shell-pid` still matches is a round `close` could
;; still reclaim, so it is never released here either -- see `released-name-close`.
(defn- pane-reclaimable? [entry]
  (boolean (and (:pane-id entry) (pane-alive? (:pane-id entry))
               (let [recorded (:shell-pid entry) live (pane-shell-pid! (:pane-id entry))]
                 (and recorded live (= recorded live))))))
(defn assert-children-discharged! [own-child]
  (when-let [outstanding (seq (outstanding-children (caller-parent-session) own-child (ledger/entries)))]
    (let [index (live-agents)
          ;; `stream-captured?`, never the `:captured-at` head (closeout finding: the head is
          ;; set by *any* capture, so a vanished child with a captured WAITING item and an
          ;; unconsumed terminal item read as discharged while that terminal sat unconsumed).
          ;; A partially consumed stream stays blocking: `collect` needs no live child.
          released (when index (filterv #(and (stream-captured? (stream-state %))
                                              (not (contains? index (:child %)))
                                              (not (pane-reclaimable? %)))
                                        outstanding))
          blocking (vec (remove (set released) outstanding))]
      (when (seq blocking)
        (fail (str "publish refused: you still own " (count blocking) " open child assignment(s). Capture each with `"
                   (bin-hint) " task collect <full-task-uuid>` and then close it with `" (bin-hint)
                   " task close <full-task-uuid>` (or `" (bin-hint) " task prune <full-task-uuid>` for a spawn that never started) before publishing.")
              {:children (mapv (fn [entry] {:task (:task entry) :child (:child entry) :pane-id (:pane-id entry)
                                            :state (if (stream-captured? (stream-state entry)) "captured-unclosed" "uncaptured")})
                               blocking)}))
      (when (seq released) (mapv :task released)))))
(defn- assert-closable! [entry entries]
  (let [task (:task entry) child (:child entry)
        caller (caller-parent-session) recorded (:parent-session entry)]
    (when-not (and caller recorded (= caller recorded))
      (fail "close refused: caller session does not own this ledger entry" {:task task}))
    (when-not (stream-captured? (stream-state entry))
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
(defn- close-mutation! [task child pane-id & [{:keys [abandon?]}]]
  ;; The listing can race a further round being written, so the freshest ledger state is
  ;; re-validated inside the mutation, mirroring `prune!`'s race re-check. `herdr/close!`
  ;; runs inside it too: `ledger/update!` writes nothing if its function throws, so a
  ;; failed close can never leave a `:closed-at` recording a closure that never happened.
  ;; The unconsumed-items re-check closes the time-of-check/time-of-use window the settle
  ;; wait opens (closeout finding): `assert-closable!` proved every item captured *before*
  ;; the wait, and an item published during it would otherwise be closed past and stranded.
  (let [updated (ledger/update! task
                                (fn [current]
                                  (when (:closed-at current) (fail "close refused: already closed" {:task task}))
                                  (when (seq (unconsumed-items current))
                                    (fail "close refused: an item was published during the settle wait" {:task task}))
                                  (when-not (= task (:task (newest-round (ledger/entries) child)))
                                    (fail "close refused: a newer round appeared during the settle wait" {:task task :child child}))
                                  (when-not abandon? (herdr/close! (:pane-id current)))
                                  (cond-> (assoc current :closed-at (now))
                                    abandon? (assoc :pane-abandoned true))))]
    (cond-> {:status (if abandon? "abandoned" "closed") :task task :child child :pane-id pane-id :closed-at (:closed-at updated)}
      abandon? (assoc :reason "pane-left-to-operator"))))
(defn- pane-alive? [pane] (try (boolean (herdr/pane! pane)) (catch Exception _ false)))
(defn- pane-process-info! [pane] (try (herdr/process-info! pane) (catch Exception _ nil)))
(defn- pane-shell-pid! [pane] (:shell_pid (pane-process-info! pane)))
;; A foreground process group that differs from the pane's own shell means something is
;; actively running there *right now* -- an operator's build, tail, or REPL, invisible to
;; `agent list`, which only ever knows agents. Measured directly (herdr 0.8.0): an idle
;; pane's `process_info` reports `foreground_process_group_id` equal to `shell_pid` (the
;; shell itself is the foreground process); a busy one reports the running command's own
;; group. Without this check, a crashed child's pane with no listed occupant at all -- the
;; non-agent foreground process is invisible to `agent list`, so `occupant` below is `nil`
;; -- and a `:shell-pid` that still matches (the shell never changed) would fall straight
;; through to `close-mutation!` and take a pane the operator is actively using: the
;; destructive regression this guards against. It costs no extra Herdr call: `info` below
;; is the same `process_info` read the shell_pid witness already made.
(defn- pane-busy-foreground? [info]
  (let [shell (:shell_pid info) fg (:foreground_process_group_id info)]
    (boolean (and shell fg (not= shell fg)))))
;; A resumed process releases its herdr agent name, so the ordinary name-and-pane match
;; can never see it again -- `agent list` shows, at best, a bare re-started agent at the
;; same pane, under no name at all or a different one. `gone` used to conflate two facts
;; that need telling apart: the pane is genuinely absent (leave it alone), and the pane is
;; still live but the name was released (a remedy exists). This is the split: a vanished
;; pane is reported `pane-absent`; a live one falls back to the shell_pid witness recorded
;; at spawn (task ca6fecef) -- the pane's shell outlives the agent that occupied it, so a
;; match means this is still the shell we provisioned for this round, which is exactly the
;; granularity a decision to take the pane needs. The fallback never relaxes the ownership
;; or newest-round guards `assert-closable!` already applied before this ever runs, and it
;; never fires when the name *is* present -- see `close-observed!` below.
(defn- released-name-close [entry agents]
  (let [task (:task entry) child (:child entry) pane (:pane-id entry)]
    (if-not (pane-alive? pane)
      {:status "gone" :reason "pane-absent" :task task :child child :pane-id pane}
      (let [occupant (some #(when (= pane (:pane_id %)) %) agents)]
        (if (and occupant (not (contains? #{"idle" "done"} (:agent_status occupant))))
          {:status "retained" :reason "unsettled" :task task :child child :pane-id pane :agent-status (:agent_status occupant)}
          (let [info (pane-process-info! pane)]
            (cond
              (pane-busy-foreground? info)
              {:status "retained" :reason "unsettled" :task task :child child :pane-id pane}
              (let [recorded (:shell-pid entry) live (:shell_pid info)]
                (and recorded live (= recorded live)))
              (close-mutation! task child pane)
              :else
              {:status "gone" :reason "name-released" :task task :child child :pane-id pane
               :remedy (str "the agent name was released and the pane's shell no longer matches what this round provisioned, "
                            "so neither `close` nor `orphans --close` can confirm it; inspect the pane manually "
                            "(`herdr pane get " pane "`) before deciding whether to close it directly")})))))))
(defn- close-observed! [entry {:keys [index agents]}]
  (let [task (:task entry) child (:child entry) agent (get index child)]
    (cond
      (nil? agent) (released-name-close entry agents)
      (not= (:pane-id entry) (:pane_id agent))
      (fail "close refused: the recorded pane is not this child's pane"
            {:task task :child child :recorded (:pane-id entry) :observed (:pane_id agent)})
      (not (contains? #{"idle" "done"} (:agent_status agent)))
      {:status "retained" :reason "unsettled" :task task :child child :pane-id (:pane-id entry) :agent-status (:agent_status agent)}
      :else (close-mutation! task child (:pane-id entry)))))
;; Bounded settle wait, then exactly one positively-usable listing. Shared by `close` and
;; `continue` because both act immediately after a capture, where the child is routinely
;; still `working` -- the same race that once forced a settle wait into capture itself, and
;; the reason a bare probe would find almost every freshly captured child unsettled.
;; The wait's outcome is never consulted: settled, timed out, or errored, the listing
;; decides. Deliberately the bare `herdr/wait!` and not `wait-settled!`, whose `--until idle
;; --until done` would burn the whole budget on a blocked child the listing refuses anyway.
;;
;; `close`/`continue` need the raw listing too, not merely the name index `live-agents`
;; builds -- a resumed child can occupy its pane with no name at all (task ca6fecef),
;; which that name-only index drops entirely -- so both are captured from the one call
;; this already makes, rather than a second `agent list` just to see nameless entries.
(defn- agents-snapshot []
  (try (when-let [agents (herdr/agents)]
         {:agents agents :index (into {} (keep #(when-let [name (:name %)] [name %])) agents)})
       (catch Exception _ nil)))
(defn- settle-and-list! [verb entry]
  (try (herdr/wait! (:child entry) (settle-close-ms)) (catch Exception _ nil))
  (or (agents-snapshot) (fail (str verb " refused: agent list is unusable; liveness is unknown") {:task (:task entry)})))
(defn- settle-then-close! [entry]
  (close-observed! entry (settle-and-list! "close" entry)))
;; `--abandon` retires the ledger round for a captured entry whose pane cannot be confirmed
;; free, and never touches the pane. It runs the whole of `assert-closable!` first, so it
;; grants no authority the ordinary path lacks -- same ownership, capture, newest-round and
;; not-already-closed bars -- and skips only the liveness observation, which is precisely
;; the step that cannot conclude for a stuck round. The pane is the operator's to dispose
;; of; what this recovers is the entry, which otherwise outlives it in `close --settled`
;; and `orphans` forever (task c04a4e67).
(defn close-task! [task opts]
  (when-not task (fail "close requires a full task uuid" {}))
  (let [entry (ledger/read! task)]
    (assert-closable! entry (ledger/entries))
    (if (one opts :abandon)
      (close-mutation! (:task entry) (:child entry) (:pane-id entry) {:abandon? true})
      (settle-then-close! entry))))
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
         ;; Terminal `failed` rounds are dropped first, for the same reason `newest-round`
         ;; skips them: an allocation that never reached its pane (a cleaned-up spawn, a
         ;; continuation whose prompt threw) is not a claim on anything, and letting one
         ;; count as "newest" would silently exclude a child whose real round is closable.
         ;; The newest surviving round per child is chosen *before* any further filtering, so
         ;; a child whose current round is uncaptured, foreign, or already closed drops out
         ;; entirely rather than falling back to an older round that names the same pane.
         newest-rounds
         (filter #(and (= caller (:parent-session %))
                       (stream-captured? (stream-state %)) (:pane-id %)
                       (nil? (:closed-at %)) (not= "invalid" (:status %))))
         (sort-by :created-at)
         ;; One refusal must not abandon the rest of the sweep, so each child's outcome --
         ;; including a refusal, named in full -- is reported in the array instead.
         (mapv (fn [entry]
                 (try (settle-then-close! entry)
                      (catch Exception e {:status "refused" :task (:task entry) :child (:child entry)
                                          :reason (.getMessage e) :detail (ex-data e)})))))))
;; --- retro candidate harvest ----------------------------------------------------------
;; Sixteen `PROCESS` candidates accumulated across six children in one session, tracked only
;; by re-reading six captures -- and they die with the session unless a retro routes them.
;; The ledger already holds every one on its entry's `:envelope`, so this is a read: scope by
;; the caller's own `:parent-session` (the ledger is repo-wide, and another session's
;; testimony is not this retro's input), deduplicate, and keep every source.
;;
;; A candidate two children raised independently is stronger evidence than one that appeared
;; once, so dedup collapses the *text* while keeping the list of children and tasks that
;; raised it rather than the first only. `:truncated` surfaces an entry whose `PROCESS`
;; section overflowed the five-item cap at validation, because that entry's testimony is
;; knowably incomplete.
;;
;; Strictly read-only, by design: it routes nothing, persists nothing, and writes no ledger
;; field. The parent still owns verification, deduplication of *meaning*, approval, and
;; persistence -- this only removes the re-reading.
(defn harvest! []
  (let [caller (or (caller-parent-session) (fail "harvest refused: caller session is unresolvable" {}))
        items (->> (ledger/entries)
                   (filter #(= caller (:parent-session %)))
                   (mapcat (fn [entry]
                             (keep (fn [item]
                                     (when-not (str/blank? (str item))
                                       {:candidate (str/trim (str item)) :child (:child entry) :task (:task entry)
                                        :truncated (boolean (:process-overflow entry))}))
                                   (get-in entry [:envelope :process]))))
                   vec)]
    ;; First-seen order is spawn order, because `ledger/entries` is sorted by `:created-at`.
    (mapv (fn [text]
            (let [sources (filterv #(= text (:candidate %)) items)]
              (cond-> {:candidate text :sources (mapv #(select-keys % [:child :task]) sources)}
                (some :truncated sources) (assoc :truncated true))))
          (distinct (map :candidate items)))))
(defn harvest-text [candidates]
  (if-not (seq candidates)
    "none"
    (str/join "\n"
              (map (fn [{:keys [candidate sources truncated]}]
                     (str "- " candidate
                          " [" (str/join "; " (map #(str (:child %) " " (:task %)) sources)) "]"
                          (when truncated " (source PROCESS section was truncated at five items)")))
                   candidates))))
;; --- orphan cleanup -------------------------------------------------------------------
;; Ownership is absolute and gains no dead-owner exception, so a captured child whose owning
;; session has ended cannot be closed by `close` at all. That left a documented two-step
;; manual recipe -- `agent list`, then `pane close <pane-id>` -- whose second step closes on a
;; recorded pane id alone, which is weaker evidence than any verb here accepts and exactly
;; what the `gone` outcome exists to refuse. This verb is that recipe with `close`'s own live
;; child-and-pane match applied to it, and with the operator supplying the authority the
;; protocol cannot infer: name absence still closes nothing (`gone`), a pane the child no
;; longer occupies is still a refusal, and an unsettled child is still retained.
;;
;; The authority really is the operator's, because "not this session" is not "dead": a
;; concurrently delegating session's captured, unclosed child is indistinguishable from a
;; true orphan here, and no available signal separates them (see § Close in contract.md).
;; So listing is the default, `--close` must be asked for, and the two are one verb
;; precisely so the list is read before the sweep.
;; Closeout fix (P1): root-only, mirroring `continue`'s guard and gated on the identical
;; `:below-root?` predicate. Below root, every sibling and every ancestor session's captured
;; children look equally "foreign" -- a delegated child cannot tell root's own children from
;; a genuine orphan -- so it could otherwise list and, under `--close`, sweep and close any
;; captured, settled sibling whose child name and pane id still match, which is authority
;; only the operator has. Refused before the first ledger read, not merely before `--close`'s
;; mutation: the listing itself leaks that same visibility, and the contract already has the
;; operator take closing authority straight from the list.
(defn orphans! [opts]
  (when-let [persona (System/getenv "HERDR_ORCH_PERSONA")]
    (fail "orphans is root-only: a delegated child cannot manage another session's captured children" {:own-persona persona}))
  (let [close? (boolean (one opts :close))
        ;; Closeout fix (P3): refused before the first ledger read for *either* form, not
        ;; only `--close`. The plain listing's candidate filter is `(not= caller
        ;; (:parent-session %))`; with `caller` nil (identity unresolvable even at root --
        ;; e.g. the caller's own `agent get` failed) every entry with a non-nil
        ;; `:parent-session` satisfies that, including root's *own* captured children, so a
        ;; degraded listing would present them as orphans. The contract already has the
        ;; operator take closing authority straight from this list -- annotating the
        ;; degradation was the other admissible fix, but a list an operator might act on by
        ;; reading it (with or without `--close`) should not exist misleadingly.
        caller (or (caller-parent-session) (fail "orphans refused: caller session is unresolvable" {}))
        candidates (->> (ledger/entries)
                        newest-rounds
                        (filter #(and (stream-captured? (stream-state %)) (:pane-id %)
                                      (nil? (:closed-at %)) (:parent-session %)
                                      (not= caller (:parent-session %))))
                        (sort-by :created-at)
                        vec)]
    (if-not close?
      (mapv #(select-keys % [:task :child :pane-id :tab-id :label :status :captured-at :parent-session]) candidates)
      ;; One refusal must not abandon the rest of the sweep, exactly as in `close --settled`.
      (mapv (fn [entry]
              (try (settle-then-close! entry)
                   (catch Exception e {:status "refused" :task (:task entry) :child (:child entry)
                                       :reason (.getMessage e) :detail (ex-data e)})))
            candidates))))
;; --- explicit teardown: `oh worktree list|remove` (task 4962846f) --------------------
;; Removal takes the checkout and never the branch. `git worktree remove` leaves the
;; branch and its commits intact (reviewer-probed, task f49a63f5/47005e8f's decision
;; record), so removing a checkout can never destroy committed work; deleting the branch
;; remains the parent's own act. Tip ancestry against the parent's *live* HEAD is reported
;; as information only, never a gate: an "unmerged" gate would need an ancestry predicate
;; against a moving parent ref, and squash or cherry-pick integration never satisfies it,
;; so it would refuse forever after ordinary integration (see the change-record's
;; "Teardown" decision).
;;
;; The only hard guards are dirt and liveness. Ownership follows `prune!`/`close`'s rule
;; exactly -- both `:parent-session`s non-nil and equal -- so an unresolvable caller owns
;; nothing, and a hand-edited or legacy-format entry whose own `:parent-session` is nil is
;; never granted ownership by a nil-vs-nil coincidence.
(defn- assert-worktree-owned! [verb entry]
  (let [task (:task entry) caller (caller-parent-session) recorded (:parent-session entry)]
    (when-not (and caller recorded (= caller recorded))
      (fail (str verb " refused: caller session does not own this ledger entry") {:task task}))))
(defn- worktree-of [entry]
  (or (:worktree entry) (fail "worktree remove refused: this task recorded no worktree" {:task (:task entry)})))
;; "References the checkout" reuses `worktree-in-flight?`'s own "genuinely unfinished, not
;; merely unclosed" predicate (see its comment above), scoped by checkout path instead of
;; parent-session: unclosed *and* unsealed. A round whose stream already carries a
;; validated terminal item (COMPLETE/BLOCKED/FAILED) is done with the checkout even before
;; an operator runs `close` on its pane -- closing and tearing down a checkout are
;; independent actions, and coupling removal to `close` would force an ordering neither
;; the design record nor `close`'s own "never touches a checkout" guarantee requires. Once
;; explicitly closed (or `--abandon`ed), a round is never live either, regardless of seal
;; state. `newest-rounds` scopes this to each child's *current* round only, exactly as
;; `close --settled`/`assert-children-discharged!` do, so a superseded continuation round
;; can never wedge removal forever, and a round that failed before it ever claimed a live
;; occupant is already excluded from `newest-rounds` by its own `live-round?` filter --
;; exactly the recovery path task f49a63f5 built for: a checkout survives a spawn failure
;; that struck after `git worktree add` but before the entry could claim a pane. Shared
;; with `--worktree-from` (task e76180b9), whose own guard is the identical predicate
;; against the same checkout path.
(defn- worktree-referenced-by-live-round? [path entries]
  (boolean (some #(and (= path (get-in % [:worktree :path]))
                       (nil? (:closed-at %))
                       (not (:sealed? (stream-state %))))
                 (newest-rounds entries))))
;; Every entry naming a given `:child` still carries its lineage's one checkout unchanged
;; (`continue!`'s whitelist), so the *last* entry for a child -- unfiltered, unlike
;; `newest-rounds` -- is the current view of it, including a terminal `failed` round whose
;; checkout was never removed. `newest-rounds`'s own `live-round?` filter would drop that
;; round from the listing entirely, hiding exactly the checkout the recovery path exists
;; to surface.
(defn- newest-entry-per-child [entries]
  (->> entries (group-by :child) vals (map last)))
(defn worktree-list! []
  (let [caller (or (caller-parent-session) (fail "worktree list refused: caller session is unresolvable" {}))]
    (->> (ledger/entries)
         newest-entry-per-child
         (filter #(and (= caller (:parent-session %)) (:worktree %)))
         (sort-by :created-at)
         (mapv (fn [entry]
                 (-> (select-keys entry [:task :child :status])
                     (assoc :worktree (:worktree entry) :reconciliation (reconciliation entry))))))))
;; `git merge-base --is-ancestor` exits 0 (ancestor) or 1 (not); anything else -- a bad
;; object name, a corrupt repository -- is a real error, never silently read as "not an
;; ancestor".
(defn- ancestor-of-parent-head? [dir tip]
  (let [{:keys [exit err]} @(process/process ["git" "merge-base" "--is-ancestor" tip (head-sha dir)] {:dir dir :out :string :err :string})]
    (case (int exit)
      0 true
      1 false
      (fail "git merge-base --is-ancestor failed" {:dir dir :tip tip :exit exit :stderr (str/trim err)}))))
(defn worktree-remove! [task]
  (when-not task (fail "worktree remove requires a full task uuid" {}))
  (let [entry (ledger/read! task)]
    (assert-worktree-owned! "worktree remove" entry)
    (let [{:keys [path branch base]} (worktree-of entry)]
      (when (worktree-referenced-by-live-round? path (ledger/entries))
        (fail "worktree remove refused: a live round of this session still references the checkout" {:task task :path path}))
      (if-not (fs/exists? path)
        {:status "missing" :task task :child (:child entry) :path path :branch branch :base base}
        (let [dirty (dirty-paths path)]
          (when (seq dirty)
            (fail "worktree remove refused: checkout is dirty" {:task task :path path :dirty dirty}))
          (let [tip (head-sha path) ancestor? (ancestor-of-parent-head? (source-cwd) tip)]
            (git! (source-cwd) "worktree" "remove" path)
            {:status "removed" :task task :child (:child entry) :path path :branch branch :base base
             :tip tip :tip-is-ancestor-of-parent-head ancestor?}))))))
(defn worktree! [op opts positional]
  ;; A manual arity check, not `require-positionals`: that helper is defined later in
  ;; this file (next to `help-text`), and this dispatcher sits beside `orphans!` above it
  ;; -- referencing it here would fail to compile as an unresolved forward reference.
  (case op
    "list" (do (when (seq positional) (fail "worktree list takes no arguments" {:arguments positional})) (worktree-list!))
    "remove" (worktree-remove! (first positional))
    (fail "unknown worktree command" {:command op})))
;; --- retention ------------------------------------------------------------------------
;; Nothing retired a captured ledger entry: `prune` only retires an entry proved *stale*, so
;; a captured one lived forever (measured 2026-08-07 in this repository's own assignment root:
;; 32 entries, 5 parent sessions, 127 677 bytes, none removable by any verb).
;;
;; Deletion and the audit trail pull in opposite directions, and the argument settles it
;; rather than a TTL. An old entry is the *only* route to a finished child's transcript --
;; `:child-session` outlives the pane -- and the only thing that makes a task UUID cited in a
;; commit, record, or task body resolve at all; age says nothing about either. So this
;; retires *bulk*, not entries: compaction, and specifically the one field whose size is
;; unbounded. `:envelope :text` is the whole raw result file repeated inside the entry
;; (40 384 of those 127 677 bytes, 32%, and 50% of the `:envelope` maps themselves), while
;; every field that survives is a single line or a capped list -- so a compacted entry is
;; bounded in size where an uncompacted one is not. Entry *count* is deliberately not bounded:
;; that is the audit trail itself.
;;
;; Nothing durable is lost. The dropped text is a duplicate of two things that both survive:
;; the parsed envelope fields beside it (status, summary, findings, process, artifacts, next)
;; and the `RESULT` file it was parsed from, which no verb deletes -- all 32 captured entries'
;; result files were still on disk when this was measured. `collect` never re-returns a
;; consumed item (a fully-consumed round reports `pending`), so post-compaction the raw text
;; is read from those retained item files directly. The one honest caveat is
;; that `RESULT` lives in gitignored scratch (`.tmp/`): an operator who clears that tree loses
;; the raw text for good, while every parsed field on the entry remains. Recorded in
;; contract.md § Retention rather than traded away silently.
;;
;; Age-plus-closed *deletion* was the admissible alternative and is rejected: it retires
;; exactly the entries worth keeping, since the ones old enough to qualify are the ones whose
;; panes and transcripts are already gone and whose UUIDs have had the longest time to be
;; cited somewhere durable.
;;
;; Only a round that is out of play may be compacted -- one carrying `:closed-at`, or a
;; terminal `failed` round -- and only by its owner, under the same both-non-nil
;; `:parent-session` rule `prune` and `close` apply, because a shared assignment root means
;; one session's cleanup must never touch another's state. There is deliberately no automatic
;; sweep on spawn, capture, or session end: retirement is an explicit action, like every other
;; destructive verb here.
(defn- compactable? [entry]
  (and (nil? (:compacted-at entry))
       (or (:closed-at entry) (= "failed" (:status entry)))))
(defn- compacted-entry [entry]
  (cond-> (assoc entry :compacted-at (now))
    (map? (:envelope entry)) (update :envelope dissoc :text)
    (seq (:items entry)) (update :items
                                 #(mapv (fn [item]
                                          (cond-> item
                                            (map? (:envelope item))
                                            (update :envelope dissoc :text)))
                                        %))))
(defn- entry-bytes [entry] (count (json/generate-string entry)))
(defn- compact-entry! [entry]
  (let [task (:task entry) before (entry-bytes entry)]
    ;; The final mutation re-validates the freshest ledger state inside `ledger/update!`,
    ;; mirroring `prune!`'s and `close`'s race re-check: a round reopened by a `continue`, or
    ;; compacted by a concurrent sweep, must refuse rather than be written over.
    (let [updated (ledger/update! task (fn [current]
                                         (when-not (compactable? current)
                                           (fail "compact refused: this round is no longer compactable"
                                                 {:task task :compacted-at (:compacted-at current) :status (:status current)}))
                                         (compacted-entry current)))]
      {:status "compacted" :task task :child (:child updated)
       :reclaimed-bytes (- before (entry-bytes updated)) :compacted-at (:compacted-at updated)})))
(defn- assert-compactable! [entry]
  (let [task (:task entry) caller (caller-parent-session) recorded (:parent-session entry)]
    (when-not (and caller recorded (= caller recorded))
      (fail "compact refused: caller session does not own this ledger entry" {:task task}))
    (when (:compacted-at entry)
      (fail "compact refused: this round is already compacted" {:task task :compacted-at (:compacted-at entry)}))
    (when-not (or (:closed-at entry) (= "failed" (:status entry)))
      (fail "compact refused: this round is still in play; compaction retires a closed or terminal round only"
            {:task task :status (:status entry)}))))
(defn compact! [task opts]
  (let [closed? (boolean (one opts :closed))]
    (when (and closed? task) (fail "compact --closed takes no task argument" {:task task}))
    (if-not closed?
      (do (when-not task (fail "compact requires a full task uuid, or --closed" {}))
          (let [entry (ledger/read! task)]
            (assert-compactable! entry)
            (compact-entry! entry)))
      (let [caller (or (caller-parent-session) (fail "compact --closed refused: caller session is unresolvable" {}))]
        (->> (ledger/entries)
             (filter #(and (= caller (:parent-session %)) (compactable? %)))
             (sort-by :created-at)
             ;; One failure must not abandon the rest of the sweep, exactly as elsewhere.
             (mapv (fn [entry]
                     (try (compact-entry! entry)
                          (catch Exception e {:status "refused" :task (:task entry) :child (:child entry)
                                              :reason (.getMessage e) :detail (ex-data e)})))))))))
;; --- capture-then-close --------------------------------------------------------------
;; The retain decision needs the published result first, which is why no capture closes --
;; but for an ephemeral scout or reviewer the parent already knows at spawn that it will
;; close, and paying two calls for it was measured six times in one session. `collect
;; --close` is that one call, and it weakens nothing: closure runs through `close-task!`
;; unchanged, so every guard, the settle wait, and all three outcomes apply, and the
;; outcome is reported under its own `:close` key *beside* the capture rather than replacing
;; or degrading it. A refusal is reported in the same shape `close --settled` uses for a
;; per-child refusal, because a loud close failure must never cost the parent the validated
;; result it just captured.
;;
;; Closure is attempted only for a capture carrying a validated envelope status: `pending`
;; and `blocked` captured nothing to close, and an `invalid` capture is excluded for exactly
;; the reason `close --settled` excludes it -- that status means the envelope needs manual
;; intervention, and the pane is what an operator would use to deal with it. Both cases
;; report `skipped` with a reason rather than nothing at all: silence would leave a caller
;; unable to tell a declined closure from one that never ran.
(def ^:private closable-capture-statuses #{"COMPLETE" "BLOCKED" "FAILED"})
(defn- capture-close! [captured]
  (let [task (:task captured) status (:status captured)]
    (if-not (and task (closable-capture-statuses status))
      {:status "skipped" :reason (if (= "invalid" status) "invalid-capture" "nothing-captured") :task task}
      ;; Never `--abandon`: retiring a round without taking its pane is an explicit operator
      ;; decision, not something a capture-time flag may take on the operator's behalf.
      (try (close-task! task {})
           (catch Exception e {:status "refused" :task task :reason (.getMessage e) :detail (ex-data e)})))))
(defn- collect-with-closure! [opts captured]
  (cond-> captured
    (and (one opts :close) (map? captured)) (assoc :close (capture-close! captured))))
;; --- follow-on rounds ---------------------------------------------------------------
;; A settled child keeps its context, so `continue` assigns it another round in place rather
;; than paying for a fresh spawn to rebuild what it already knows. The child stays entirely
;; lifecycle-agnostic: it is never told it was continued or retained, only that it has a new
;; assignment under a new TASK, with the one command it must run written out in full.
;;
;; The prompt carries assignment content and that command -- nothing the CLI could have
;; guaranteed itself. The round's identity is interpolated (never "publish under your new
;; task"), the round's policy decides the WAITING clause mechanically, and `publish!` reads
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
       ;; Every publication instruction the round emits must carry `--task`, not only the
       ;; COMPLETE one: a continued child's injected `HERDR_ORCH_TASK` still names its
       ;; original, already-published round, so a bare `--status BLOCKED` cannot publish at
       ;; all -- the failure path would break exactly when it is needed.
       "If you cannot finish — an unrecoverable failure after reasonable retries, or a genuine blocking dependency — publish once with `--task " task " --status BLOCKED` (dependency) or `--task " task " --status FAILED` (unrecoverable), summarising work completed vs remaining; never stop silently or publish a second envelope after recovering. "
       publication-guidance
       (waiting-instruction waiting-policy task)))
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
      (when-not (and (stream-captured? (stream-state entry))
                     (not (contains? #{"failed" "invalid"} (:status entry))))
        (fail "continue refused: the prior round is not captured with a validated envelope"
              {:task prior-task :status (:status entry)}))
      (when (:closed-at entry)
        (fail "continue refused: this child's pane was already closed" {:task prior-task :closed-at (:closed-at entry)}))
      ;; `live-round?` excludes a terminal `failed` round here for the same reason
      ;; `newest-round` does: a continuation whose prompt threw is retired, not open, and
      ;; must not block the retry that recovers from it.
      (when-let [open (seq (filter #(and (= child (:child %))
                                          (not (stream-captured? (stream-state %)))
                                          (live-round? %))
                                   entries))]
        (fail "continue refused: an uncaptured round already names this child"
              {:task prior-task :child child :open (mapv :task open)}))
      (when-not (= prior-task (:task (newest-round entries child)))
        (fail "continue refused: a newer round exists for this child"
              {:task prior-task :child child :newest (:task (newest-round entries child))}))
      ;; Same live evidence bar as `close`: the name and the pane must both match, and the
      ;; child must be settled -- prompt text delivered to a `blocked` agent lands in its
      ;; approval UI rather than starting a round.
      (let [{:keys [index]} (settle-and-list! "continue" entry)
            agent (or (get index child) (fail "continue refused: the child is absent from the agent list" {:task prior-task :child child}))]
        (when-not (= (:pane-id entry) (:pane_id agent))
          (fail "continue refused: the recorded pane is not this child's pane"
                {:task prior-task :child child :recorded (:pane-id entry) :observed (:pane_id agent)}))
        (when-not (contains? #{"idle" "done"} (:agent_status agent))
          (fail "continue refused: the child is not settled" {:task prior-task :child child :agent-status (:agent_status agent)}))
        ;; The all-items-captured guard above ran before the settle wait, so an item published
        ;; *during* that wait -- the same time-of-check/time-of-use window `close-mutation!`
        ;; re-checks (closeout finding) -- would be continued past and left unconsumed on a
        ;; superseded round. Re-read the freshest entry after the wait and refuse instead,
        ;; before any allocation: the raced item stays ordinarily collectable.
        (when (seq (unconsumed-items (ledger/read! prior-task)))
          (fail "continue refused: an item was published during the settle wait" {:task prior-task}))
        (let [task (ledger/fresh-task)
              result (ledger/fresh-result task)
              ;; `:child` is inherited, never re-derived: a continued round's child name
              ;; belongs to the spawn that created it and has no relation to this round's
              ;; own uuid. Display and policy metadata carry over unchanged; the parent
              ;; fields come from the caller, which owns this round.
              ;; `:worktree` is one field, deliberately -- task 47005e8f's decision: worktree
              ;; identity belongs to the child lineage, not the round, so it rides through
              ;; this whitelist as a single map entry rather than several flat keys that
              ;; would each need remembering here. Any field this `select-keys` does not
              ;; name is silently dropped on the next round; that is exactly the mechanism
              ;; by which worktree identity would otherwise vanish from a continued child.
              next-entry (merge (select-keys entry [:child :pane-id :tab-id :label :index :persona-path :kind :model :retro :retro-source :spawns :spawns-source :timeout :timeout-source :placement :shell-pid :worktree])
                                {:task task :result result :continues prior-task
                                 :parent-session caller :parent-pane (:parent-pane ident)
                                 :waiting-policy waiting-policy :status "continuing" :created-at (now)})]
          ;; Persisted before the pane is prompted, exactly as `spawn!` does: a failure
          ;; between the two leaves a recoverable uncaptured entry rather than a prompted
          ;; child that no entry names.
          (ledger/write! next-entry)
          ;; The entry must survive a failed prompt (a failed `agent prompt` may still have
          ;; delivered text, so deleting it could orphan a working child), but it must not
          ;; survive as `continuing`: that status is uncaptured, non-terminal, and newest,
          ;; which refuses `close` and `continue` on both rounds, refuses `prune` while the
          ;; child is live, and stays a `collect --any` candidate forever. Retiring it the
          ;; way `safe-cleanup!` retires a dead spawn makes the round recoverable -- retry
          ;; the `continue`, or `close` the prior round. No pane is touched: unlike a spawn
          ;; failure, the pane here predates this verb and belongs to a healthy child.
          (try
            (herdr/prompt! child (continuation-prompt {:assignment assignment :task task :result result :waiting-policy waiting-policy}))
            (catch Exception e
              (ledger/update! task assoc :status "failed" :failed-at (now) :failure-phase "continue-prompt")
              (throw e)))
          (ledger/update! task assoc :status "prompted" :prompted-at (now))
          (verify-dispatch! task child)
          (let [written (ledger/read! task)]
            (if (one opts :wait)
              (wait-and-capture! written (round-timeout written opts) true)
              written)))))))
;; Capture candidacy is deliberately narrower than wait candidacy: a round is capturable
;; only with an unconsumed item, while a live unsealed round with no item is still worth
;; polling. Both sets stay session-scoped and exclude terminal spawn failures.
;; `ledger/entries` is sorted by `:created-at`, so poll order is spawn order.
;; `parent-session` is a loop invariant, so it is checked by the caller rather than per
;; entry: an unresolvable caller identity must not slurp and parse every ledger file for a
;; guaranteed-empty result.
(defn- stream-snapshot [entries parent-session]
  (->> entries
       (filter #(= parent-session (:parent-session %)))
       (mapv #(assoc % :stream-state (stream-state* %)))))
(defn- any-candidates [snapshot]
  (vec (filter #(and (seq (unconsumed-stream-items (:stream-state %)))
                     (not= "failed" (:status %)))
               snapshot)))
(defn- wait-candidates [snapshot]
  (vec (filter #(and (not= "failed" (:status %))
                     (nil? (:closed-at %))
                     (not (:sealed? (:stream-state %))))
               snapshot)))
(defn- capture-first [candidates]
  (some (fn [entry]
          (when-let [parsed (capture! entry (:stream-state entry))]
            [entry parsed]))
        candidates))
(defn- remaining-candidates [candidates captured-entry parsed]
  (count (filter (fn [entry]
                   (let [items (unconsumed-stream-items (:stream-state entry))]
                     (if (= (:task entry) (:task captured-entry))
                       (some #(not= (:item parsed) (:item %)) items)
                       (seq items))))
                 candidates)))
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
                    (if (one opts :wait) (round-timeout nil opts) 0))]
    ;; Distinct from `no-candidates`: nothing can be scoped without a caller identity, and
    ;; reporting an empty fan-out would hide the misconfiguration. Non-final, like every
    ;; other `pending`, and non-throwing to match `collect`, which never runs `preflight!`.
    (if-not session
      {:status "pending" :reason "unknown-caller"}
      (loop []
        (let [entries (ledger/entries)
              ;; Preserve the no-entry fast path: nothing can be a candidate until this
              ;; session owns an entry, so no liveness subprocess is useful yet.
              scoped? (some #(= session (:parent-session %)) entries)]
          (if-not scoped?
            {:status "pending" :reason "no-candidates"}
            ;; `agent list` is the liveness boundary. The one snapshot taken immediately
            ;; afterward carries both candidate sets and capture decisions for this tick.
            (let [index (live-agents)
                  snapshot (stream-snapshot entries session)
                  candidates (any-candidates snapshot)
                  waiters (wait-candidates snapshot)
                  captured (fn [[entry parsed]]
                             (with-reconciliation entry
                               (assoc (finish-capture! entry parsed true)
                                      :remaining (remaining-candidates candidates entry parsed))))]
              (if (and (empty? candidates) (empty? waiters))
                {:status "pending" :reason "no-candidates"}
                (if-let [hit (capture-first candidates)]
                  (captured hit)
                  (let [live (when index (filterv #(contains? index (:child %)) waiters))
                        blocked (when index
                                  (filterv #(= "blocked" (:agent_status (get index (:child %)))) live))]
                    (cond
                      (and index (empty? live)) {:status "pending" :reason "no-live-children"}
                      (and index (seq live) (= (count live) (count blocked)))
                      {:status "blocked" :tasks (mapv :task blocked)}
                      :else (let [remaining (- deadline (System/currentTimeMillis))]
                              (if (<= remaining 0)
                                {:status "pending" :reason "timeout"}
                                (do (Thread/sleep (min (poll-interval-ms) remaining))
                                    (recur))))))))))))
)))
;; `agent!` is made unconditionally regardless of whether `:child-session` is already
;; recorded, so gating `record-session!` on its absence discarded an observation this call
;; already paid for, for free -- see `finish-capture!`'s identical fix.
(defn live [entry]
  (let [agent (try (herdr/agent! (:child entry)) (catch Exception _ nil))
        entry (or (record-session! (:task entry) (:agent_session agent)) entry)]
    (with-reconciliation entry (assoc entry :live-agent agent))))
;; --- agent-facing output shaping ----------------------------------------------------
;; Two independent knobs on the read verbs (`collect`, `status`, `list`, `harvest`), because
;; the parent pays for every byte of them and both defaults were wrong for an agent reader.
;; `--format text` renders the acted-on fields as lines instead of JSON, and the raw
;; envelope `text` -- a byte-for-byte repeat of the parsed fields sitting beside them, and
;; the single largest field in a capture -- becomes opt-in behind `--raw` instead of always
;; present. Nothing durable is discarded by opting out: the blob stays on the ledger entry,
;; the `RESULT` file it was parsed from is never deleted, and `--raw` reproduces it verbatim.
(def output-formats #{"json" "text"})
(defn output-format [opts]
  (let [value (or (one opts :format) "json")]
    (when-not (output-formats value) (fail "--format must be json or text" {:format value}))
    value))
;; Drops the duplicate blob wherever a read verb can carry it: at the top level of a
;; capture (`core/parse-envelope`'s `:text`) and nested under a ledger entry's `:envelope`.
(defn- without-raw-text [value]
  (cond
    (sequential? value) (mapv without-raw-text value)
    (map? value) (cond-> (dissoc value :text)
                   (map? (:envelope value)) (update :envelope dissoc :text))
    :else value))
(defn- text-field [label value]
  (when-not (str/blank? (str value)) (str label ": " value)))
(defn- text-list [label items]
  (when (seq items) (str label ":\n" (str/join "\n" (map #(str "- " %) items)))))
(defn- text-lines [& parts] (str/join "\n" (remove nil? parts)))
;; A capture and its non-final outcomes (`pending`, `blocked`, `invalid`) alike: the fields a
;; parent acts on, in envelope order. Existence-validated `:artifact-links` are preferred
;; over the declared paths, exactly as the JSON form's weighting does. TERMINAL renders the
;; JSON `:terminal?` (closeout finding: the acceptance criterion "the capture result states
;; terminality explicitly" was met for JSON only), so a WAITING capture is unambiguously
;; non-final in this rendering too.
(defn capture-text [result]
  (text-lines (text-field "STATUS" (:status result))
              (text-field "TERMINAL" (:terminal? result))
              (text-field "TASK" (:task result))
              (text-field "CHILD" (:child result))
              (text-field "REASON" (:reason result))
              (text-field "OWNERSHIP" (:ownership result))
              (text-field "SUMMARY" (:summary result))
              (text-list "ARTIFACTS" (or (:artifact-links result) (:artifacts result)))
              (text-list "FINDINGS" (:findings result))
              (text-field "NEXT" (:next result))
              (text-list "PROCESS" (:process result))
              (text-field "REMAINING" (:remaining result))
              (text-field "CLOSE" (some-> (:close result) :status))))
;; One tab-separated line per entry: the task id first because it is the only field a
;; caller can act on, then the five the assignment names. Deliberately not the whole entry
;; -- the JSON form remains the complete view, and `list` emitting full envelopes for every
;; historical round is the cost this replaces.
(defn entry-text [entry]
  (str/join "\t" [(:task entry) (:child entry) (or (:pane-id entry) "-") (:status entry)
                  (if (:captured-at entry) "captured" "uncaptured")
                  (if (:closed-at entry) "closed" "open")]))
;; "none", never an empty string: an empty rendering is indistinguishable from a failed one.
(defn entries-text [entries] (if (seq entries) (str/join "\n" (map entry-text entries)) "none"))
(defn ledger-text [value] (if (sequential? value) (entries-text value) (entry-text value)))
(defn present [opts value render]
  (let [format (output-format opts)
        value (cond-> value (not (one opts :raw)) without-raw-text)]
    (if (= "text" format) (render value) value)))
;; `--help` is the documented non-JSON exception: it prints usage and exits 0 for any
;; command, so `oh task run --help` never returns "option requires a value".
(defn help-request? [group op args]
  (let [flags (boolean-flags-for group op)]
    (boolean (or (#{"--help" "-h" "help"} group)
                 ;; Mirror option-map's consumption so opaque assignment text that happens
                 ;; to be `--help` is never mistaken for a help request.
                 (loop [xs (cons op args)]
                   (when-let [x (first xs)]
                     (cond (#{"--help" "-h"} x) true
                           (flags x) (recur (next xs))
                           (str/starts-with? x "--") (recur (nnext xs))
                           :else (recur (next xs)))))))))
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
   "task" ["run <persona> (--task TEXT | --task-file PATH | stdin) [--model MODEL] [--timeout MS] [--tab|--split] [--spawns NAMES|none] [--retro|--no-retro] [--prompt-extra TEXT] [--print-prompt] [--worktree]"
           "start <persona> (--task TEXT | --task-file PATH | stdin) [same options as run]"
           "collect <full-task-uuid> [--wait] [--timeout MS] [--close] [--format json|text] [--raw]"
           "collect --any [--wait] [--timeout MS] [--close] [--format json|text] [--raw]"
           "status [full-task-uuid] [--format json|text] [--raw]"
           "list [--format json|text] [--raw]"
           "publish --status COMPLETE|BLOCKED|FAILED|WAITING --summary TEXT [--artifact PATH]* [--finding TEXT]* [--next TEXT] [--process TEXT]* [--from-file PATH] [--task UUID] [--notify-timeout MS]"
           "prune <full-task-uuid>"
           "continue <full-task-uuid> (--task TEXT | --task-file PATH | stdin) [--wait] [--timeout MS]"
           "close <full-task-uuid> [--abandon]"
           "close --settled"
           "orphans [--close]"
           "compact <full-task-uuid>"
           "compact --closed"
           "harvest [--format json|text]"]
   "worktree" ["list"
               "remove <full-task-uuid>"]
   "spawn" ["spawn \"<shell command>\""]})
;; Derived from the signatures above rather than restated, so help and validation cannot
;; disagree. Flat on purpose: it exists to catch typos, and per-group scoping would
;; reintroduce a second list to keep in step. Boolean-ness is *not* derivable here
;; (`[--format text|ansi]` reads the same as a value-less flag), so `boolean-flags` stays.
(def known-options
  (set (map #(keyword (subs % 2)) (re-seq #"--[a-z][a-z0-9-]*" (str/join " " (mapcat val signatures))))))
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
    "run" (let [entry (spawn! (first positional) opts "blocking")] (if (:preview entry) entry (wait-and-capture! entry (round-timeout entry opts) true)))
    "start" (spawn! (first positional) opts "non-blocking")
    "collect" (let [any? (boolean (one opts :any))]
                (when (and any? (first positional)) (fail "collect --any takes no task argument" {:task (first positional)}))
                (present opts (collect-with-closure! opts (if any? (collect-any! opts) (collect! (first positional) opts))) capture-text))
    "status" (present opts (if-let [task (first positional)] (live (ledger/read! task)) (mapv live (ledger/entries))) ledger-text)
    "list" (present opts (mapv live (ledger/entries)) entries-text)
    "publish" (publish! opts)
    "prune" (prune! (first positional))
    "continue" (continue! (first positional) opts)
    "orphans" (do (require-positionals positional 0 "task orphans") (orphans! opts))
    "compact" (compact! (first positional) opts)
    "harvest" (do (require-positionals positional 0 "task harvest") (present opts (harvest!) harvest-text))
    "close" (let [settled? (boolean (one opts :settled))]
              (when (and settled? (first positional)) (fail "close --settled takes no task argument" {:task (first positional)}))
              ;; Never silently ignored: a sweep that abandoned panes in bulk is authority
              ;; nobody asked for, and an accepted-but-dropped flag reads as though it acted.
              (when (and settled? (one opts :abandon))
                (fail "close --settled does not take --abandon; abandon one round at a time" {}))
              (if settled? (close-settled!) (close-task! (first positional) opts)))
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
    (if (help-request? group op args)
      (help-text group op)
      (let [parse-args (if (= "spawn" group) (cons op args) args)
            opts (option-map parse-args (boolean-flags-for group op) known-options)
            positional (:_ opts)]
        (case group
          "pane" (raw-pane! op opts positional)
          "tab" (raw-tab! op opts positional)
          "ws" (raw-workspace! op opts positional)
          "agent" (raw-agent! op opts positional)
          "task" (task! op opts positional)
          "worktree" (worktree! op opts positional)
          "spawn" (spawn-command! opts positional)
          (fail "unknown oh command" {:command group}))))))
(defn -main [& argv]
  (try (let [result (execute argv)] (println (if (string? result) result (core/json-envelope true result))))
       (catch Exception e (println (core/json-envelope false {:message (.getMessage e) :data (ex-data e)})) (System/exit 1))))

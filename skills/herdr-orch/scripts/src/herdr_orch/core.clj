(ns herdr-orch.core
  "Pure protocol helpers for the oh CLI."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [cheshire.core :as json]))

(def schema "herdr-orch/v1")
(def statuses #{"COMPLETE" "BLOCKED" "FAILED" "WAITING"})
(def terminal-statuses #{"COMPLETE" "BLOCKED" "FAILED"})
(defn terminal-status? [status] (contains? terminal-statuses status))
(def policies #{"blocking" "non-blocking"})
(def max-findings 5)
(defn findings-limit! [findings]
  (when (< max-findings (count findings))
    (throw (ex-info "result envelope FINDINGS exceeds the five-item limit" {:count (count findings) :limit max-findings})))
  findings)
;; PROCESS is an optional, discardable retro annotation. The cap is enforced hard here
;; (called only from `envelope`, i.e. at publish) and degraded silently at validation,
;; because a validation throw is recorded as the terminal ledger status `invalid` and a
;; discardable annotation must never destroy an otherwise valid result.
(def max-process 5)
(defn process-limit! [process]
  (when (< max-process (count process))
    (throw (ex-info "result envelope PROCESS exceeds the five-item limit" {:count (count process) :limit max-process})))
  process)

(defn single-line! [label value]
  (when (or (str/blank? (str value)) (re-find #"[\r\n]" (str value)))
    (throw (ex-info (str label " must be a non-empty single line") {:label label :value value})))
  (str value))

(defn parse-frontmatter [text]
  (let [[_ yaml] (re-find #"(?s)\A---\s*\n(.*?)\n---" text)]
    (when-not yaml (throw (ex-info "persona has no YAML frontmatter" {})))
    (into {} (keep (fn [line]
                     (when-let [[_ k v] (re-matches #"\s*([A-Za-z][\w-]*)\s*:\s*(.*?)\s*" line)]
                       [(keyword k) (str/replace v #"^['\"]|['\"]$" "")]))
                   (str/split-lines yaml)))))

;; Persona definitions resolve project > home > packaged. `packaged-dir` is supplied by
;; the launcher-relative CLI boundary, never inferred from the assignment root or cwd.
(defn persona-directories [project-root home packaged-dir]
  [(str project-root "/.agents/subagents")
   (str home "/.agents/subagents")
   (str packaged-dir)])
(defn resolve-persona [exists? directories persona]
  (some (fn [directory]
          (let [path (str directory "/" persona ".md")]
            (when (exists? path) path)))
        directories))

;; Skills resolve like the roster, plus a bare `<root>/skills/` probe because a skill
;; repository holds its own skills there rather than under `.agents/skills/`.
(defn skill-path [exists? project-root home skill]
  (some #(when (exists? %) %)
        [(str project-root "/.agents/skills/" skill "/SKILL.md")
         (str project-root "/skills/" skill "/SKILL.md")
         (str home "/.agents/skills/" skill "/SKILL.md")]))

;; `parse-frontmatter` yields strings, and every non-empty string is truthy in Clojure,
;; so `retro: false` must be coerced explicitly or it silently gates the persona *in*.
(defn frontmatter-boolean [persona key value]
  (when (some? value)
    (let [coerced (get {"true" true "false" false} value ::invalid)]
      (when (= ::invalid coerced)
        (throw (ex-info (str "persona frontmatter `" (name key) "` must be true or false")
                        {:persona persona :key (name key) :value value})))
      coerced)))
;; Two-dimensional gating: this resolves only whether the child runs the retro step at
;; all. Whether that step emits anything is the `retro` skill's own threshold, applied by
;; the child at runtime — "enabled" never forces a retro.
;; An installation without the `retro` skill degrades silently for the frontmatter and
;; default sources — the retro step is optional equipment — but an explicit `--retro`
;; is an operator request that must not become a silent no-op, so it fails fast.
(defn resolve-retro [{:keys [persona flag frontmatter retro-skill]}]
  (let [declared (frontmatter-boolean persona :retro (:retro frontmatter))
        [enabled source] (cond (some? flag) [flag "flag"]
                               (some? declared) [declared "frontmatter"]
                               :else [true "default"])]
    (cond
      (and enabled (nil? retro-skill) (= source "flag"))
      (throw (ex-info "--retro requested but no retro skill is installed" {:persona persona :skill "retro"}))
      (and enabled (nil? retro-skill))
      {:retro false :retro-source "skill-missing"}
      :else {:retro enabled :retro-source source})))

;; Wait-budget resolution, mirroring `retro:` and `spawns:`: explicit `--timeout` flag >
;; persona frontmatter `timeout:` > the shipped default. It exists because "give review and
;; implementation work an explicit `--timeout`" was a per-persona constant expressed as a
;; standing instruction to every caller -- a value the persona itself can declare once.
;; Frontmatter values are always strings, so the value is coerced explicitly, and an
;; unparseable or non-positive one fails fast at spawn rather than degrading to the default:
;; the same discipline `retro:` applies to a value that is neither true nor false, for the
;; same reason -- a silent degradation hides a typo in a persona file forever. The flag is
;; validated by the same function, so `--timeout abc` is a clean refusal rather than a raw
;; `NumberFormatException`.
(def default-timeout-ms 600000)
(defn timeout-value! [label persona value]
  (let [n (parse-long (str/trim (str value)))]
    (when-not (and n (pos? n))
      (throw (ex-info (str label " must be a positive integer of milliseconds")
                      (cond-> {:value value} persona (assoc :persona persona)))))
    n))
(defn resolve-timeout [{:keys [persona flag frontmatter]}]
  (let [declared (when (some? (:timeout frontmatter))
                   (timeout-value! "persona frontmatter `timeout`" persona (:timeout frontmatter)))
        [ms source] (cond (some? flag) [(timeout-value! "--timeout" nil flag) "flag"]
                          (some? declared) [declared "frontmatter"]
                          :else [default-timeout-ms "default"])]
    {:timeout ms :timeout-source source}))

;; A `spawns:` frontmatter value is a whitespace- and/or comma-separated allow-list.
;; Blank (or absent, arriving as nil) means leaf; `distinct` dedupes while preserving
;; declaration order.
(defn parse-spawns [value]
  (->> (str/split (str value) #"[,\s]+")
       (remove str/blank?)
       distinct
       vec))
;; Spawn-policy precedence: `--spawns` flag > frontmatter `spawns:` > default deny.
;; The literal flag value `none` forces the empty (leaf) policy without consulting the
;; roster. `resolve-persona` is the injected roster lookup (name → path or nil): an
;; unresolvable name fails fast — mirroring the `retro:`
;; invalid-value precedent — rather than silently degrading to leaf.
(defn resolve-spawns [{:keys [persona flag frontmatter resolve-persona]}]
  (let [[names source] (cond (some? flag) [(when-not (= "none" flag) (parse-spawns flag)) "flag"]
                             (some? (:spawns frontmatter)) [(parse-spawns (:spawns frontmatter)) "frontmatter"]
                             :else [nil "default"])]
    (doseq [n names]
      (when (nil? (resolve-persona n))
        (throw (ex-info (str "spawns policy for persona `" persona "` names unresolvable persona `" n "` (source: " source ")")
                        {:persona persona :spawn n :source source}))))
    {:spawns (vec names) :spawns-source source}))

(defn direction [{:keys [width height]}]
  (if (and (>= width 80) (>= width (* 2 height))) "right" "down"))
(defn model-basename [model] (some-> model (str/split #"/") last))
;; Two tiers, and no third. Kind is a deployment property -- which CLI, which approval
;; model, which sandbox -- declared once in a persona's `kind:` or inherited from the
;; harness Herdr measured for the parent. Nothing per-assignment selects it: not a flag,
;; not an env var. A caller wanting a different harness writes a definition that declares
;; one, which is the same mechanism a benchmark uses.
(defn resolve-kind [{:keys [frontmatter parent-kind]}]
  (or (:kind frontmatter) parent-kind
      (throw (ex-info "could not resolve agent kind" {}))))
(defn resolve-model [{:keys [requested resolved-kind frontmatter parent-kind parent-model]}]
  (cond requested requested
        (:model frontmatter) (:model frontmatter)
        (= resolved-kind parent-kind) parent-model
        :else nil))
;; Canonical model IDs are spelled differently per harness kind; the merged config
;; (see `parse-config`/`merge-config`) carries a single-hop `:aliases` map (short name
;; -> canonical ID) alongside the per-kind flag spelling (`:harnesses`) and the per-ID
;; translation table (`:models`). Both the ID set and the kind set are open by contract:
;; an unlisted ID/alias or a kind with no `:harnesses` entry is never a failure, only a
;; pass-through/empty result. `canonical-model` is the single alias hop: its result is
;; looked up in `:models` directly, never re-resolved through `:aliases` again — a chain
;; is a validation error (`validate-merged-config!`), not runtime behaviour.
(defn canonical-model [config model]
  (get-in config [:aliases model] model))
(defn translate-model [config kind model]
  (let [canonical (canonical-model config model)]
    (or (get-in config [:models canonical (keyword kind)]) canonical)))
(defn model-args [config kind model]
  (let [translated (translate-model config kind model) flag (get-in config [:harnesses (keyword kind) :model-flag])]
    (if (and translated flag) [flag translated] [])))
;; Opt-in per-harness native args, appended to every `agent start` for that kind. The
;; shipped config ships none, so relaxed command approval -- claude
;; `--permission-mode auto`, codex `--ask-for-approval never --sandbox workspace-write`
;; -- is only ever granted by an override file that asks for it explicitly. Because
;; `merge-config` replaces a harness entry wholesale rather than merging its keys, such an
;; override must restate `:model-flag`; validation enforces that.
(defn harness-extra-args [config kind]
  (vec (get-in config [:harnesses (keyword kind) :extra-args])))
;; config.edn shape validation: sparse model rows and harness keywords absent from
;; `:harnesses` are allowed (the kind set is open by contract). `:defaults` is closed
;; so placement typos fail loudly. Every failure carries the offending file path.
(defn- validate-config-shape! [path config]
  (when-not (map? config) (throw (ex-info "config must be an EDN map" {:path path :value config})))
  (let [harnesses (get config :harnesses {}) models (get config :models {})]
    (when-not (map? harnesses) (throw (ex-info "config :harnesses must be a map" {:path path :value harnesses})))
    (doseq [[kind entry] harnesses]
      (when-not (keyword? kind) (throw (ex-info "config :harnesses key must be a keyword" {:path path :key kind})))
      (when-not (map? entry) (throw (ex-info "config :harnesses entry must be a map" {:path path :harness kind :value entry})))
      (let [flag (:model-flag entry)]
        (when-not (and (string? flag) (not (str/blank? flag)))
          (throw (ex-info "config :harnesses entry :model-flag must be a non-blank string" {:path path :harness kind :model-flag flag}))))
      ;; `:extra-args` reaches Herdr as native `agent start` argv, which rejects control
      ;; characters outright, so a bad value fails here with the offending file rather
      ;; than as an opaque `invalid_agent_argument` at spawn time.
      (when (contains? entry :extra-args)
        (let [extra (:extra-args entry)]
          (when-not (sequential? extra)
            (throw (ex-info "config :harnesses entry :extra-args must be a vector of strings" {:path path :harness kind :extra-args extra})))
          (doseq [arg extra]
            (when-not (and (string? arg) (not (str/blank? arg)))
              (throw (ex-info "config :harnesses entry :extra-args must be a vector of non-blank strings" {:path path :harness kind :arg arg})))
            (when (re-find #"[\n\t\r]" arg)
              (throw (ex-info "config :harnesses entry :extra-args must not contain control characters; Herdr rejects them at agent start"
                              {:path path :harness kind :arg arg})))))))
    (when-not (map? models) (throw (ex-info "config :models must be a map" {:path path :value models})))
    (doseq [[id row] models]
      (when-not (string? id) (throw (ex-info "config :models key must be a string" {:path path :key id})))
      (when-not (map? row) (throw (ex-info "config :models entry must be a map" {:path path :model id :value row})))
      (doseq [[kind value] row]
        (when-not (keyword? kind) (throw (ex-info "config :models row key must be a keyword" {:path path :model id :key kind})))
        (when-not (string? value) (throw (ex-info "config :models row value must be a string" {:path path :model id :harness kind :value value})))))
    ;; `:aliases` is a flat short-name -> canonical-ID map; both sides are keys/values
    ;; that must round-trip through argv and error messages, so both are constrained to
    ;; non-blank strings exactly like a `:models` row value.
    (let [aliases (get config :aliases {})]
      (when-not (map? aliases) (throw (ex-info "config :aliases must be a map" {:path path :value aliases})))
      (doseq [[k v] aliases]
        (when-not (and (string? k) (not (str/blank? k)))
          (throw (ex-info "config :aliases key must be a non-blank string" {:path path :key k})))
        (when-not (and (string? v) (not (str/blank? v)))
          (throw (ex-info "config :aliases value must be a non-blank string" {:path path :key k :value v})))))
    (when (contains? config :defaults)
      (let [defaults (:defaults config)]
        (when-not (map? defaults) (throw (ex-info "config :defaults must be a map" {:path path :value defaults})))
        (when-not (every? #{:placement} (keys defaults))
          (throw (ex-info "config :defaults has unknown key" {:path path :defaults defaults})))
        (when (contains? defaults :placement)
          (when-not (#{:split :tab :tab-split} (:placement defaults))
            (throw (ex-info "config :defaults :placement must be :split, :tab, or :tab-split"
                            {:path path :placement (:placement defaults)})))))))
  config)
;; Parse is pure given text: `parse-config` never touches disk (file IO — `fs/exists?`,
;; `slurp` — stays at the cli.clj boundary), mirroring `parse-frontmatter`.
(defn parse-config [path text]
  (validate-config-shape! path
    (try (edn/read-string text)
         (catch Exception e (throw (ex-info (str "config is invalid EDN: " path) {:path path} e))))))
;; Per-file validation guarantees the map-valued known top-level keys are safe to merge.
;; `merge-with merge` therefore preserves row replacement at level two while allowing
;; `:defaults` to merge its keys across the override chain.
(defn merge-config [& configs]
  (apply merge-with merge configs))
;; Per-file validation only constrains shape within one file; a key can legally be a
;; sole `:models` row in one layer and a sole `:aliases` entry in another and still
;; collide only after `merge-config` composes them. Both checks here fire on the merged
;; result, before any ledger allocation or pane mutation (see `cli/config`), and both
;; are validation errors rather than a silent precedence rule: an alias/model overlap
;; would otherwise let one direction silently shadow the other (see the change-record's
;; "Decisions" section), and a chained alias (a value that is itself an `:aliases` key) would
;; make the effective translation depend on lower-layer files the reader may never see.
(defn validate-merged-config! [config]
  (let [aliases (get config :aliases {}) models (get config :models {})]
    (doseq [k (keys aliases)]
      (when (contains? models k)
        (throw (ex-info "config key is present in both :aliases and :models" {:key k}))))
    (doseq [v (vals aliases)]
      (when (contains? aliases v)
        (throw (ex-info "config :aliases value is itself an :aliases key; multi-hop alias chains are not supported" {:key v})))))
  config)
(defn worktree-branch [task]
  (str "orch/" (subs task 0 8)))
;; Trigger resolution is trait-token based, reusing whatever `%worktree`/`%no-worktree`
;; already resolved in the persona body (see `cli/trait-interpolation`) rather than a
;; second scan. `flag` is the raw `--worktree` boolean; `traits` the resolved trait names.
;; Forcing (`--worktree` or `%worktree`) and suppressing (`%no-worktree`) on one spawn is a
;; spawn-time validation error, fired here before task allocation or any pane/ledger
;; mutation, exactly like an unknown or incompatible trait. The default -- an extra
;; checkout when another round of the same parent session is already in flight -- never
;; applies to a read-only persona (`%read-only` resolved), which gets no implicit
;; worktree. `:trigger` is always recorded, auditable rather than inferred: `flag`/`trait`
;; force one, `suppressed` records an opt-out that fired (whether or not the default would
;; otherwise have applied), `default` is the in-flight checkout, and `none` is an ordinary
;; shared-tree spawn.
(defn worktree-forced? [flag traits] (boolean (or flag ((set traits) "worktree"))))
(defn worktree-suppressed? [traits] (boolean ((set traits) "no-worktree")))
;; `in-flight?` is expected to already be short-circuited by the caller (`false`, never a
;; ledger read) whenever `flag`/`traits` alone are forced or suppressed: the ledger read
;; behind an actual in-flight check is a side effect (it creates the ledger directory --
;; see `cli/worktree-in-flight?`), and a spawn that is about to fail this function's own
;; conflict check, or one whose decision the flag/trait already settled, must not pay for
;; or trigger that side effect first.
(defn resolve-worktree [{:keys [flag traits in-flight? read-only?]}]
  (let [forced? (worktree-forced? flag traits)
        suppressed? (worktree-suppressed? traits)]
    (when (and forced? suppressed?)
      (throw (ex-info "worktree trigger conflict: --worktree/%worktree and %no-worktree cannot both apply to one spawn"
                      {:flag (boolean flag) :traits (vec (set traits))})))
    (cond
      forced? {:create? true :trigger (if flag "flag" "trait")}
      suppressed? {:create? false :trigger "suppressed"}
      (and in-flight? (not read-only?)) {:create? true :trigger "default"}
      :else {:create? false :trigger "none"})))
(defn resolve-placement [{:keys [flag configured below-root?]}]
  (case flag
    "tab" "tab"
    "split" "split"
    (case configured
      :tab "tab"
      :tab-split (if below-root? "split" "tab")
      "split")))
;; Claude Code 2.1.220 accepts this file transport. Intentionally no runtime version
;; probe or body-text fallback: unsupported versions fail loudly at startup.
(defn persona-args [kind path]
  (case kind
    "pi" ["--append-system-prompt" path]
    "claude" ["--append-system-prompt-file" path]
    []))
(defn root-label [persona index model]
  (str persona "-" index (when-let [m (model-basename model)] (str "-" m))))
(defn nested-prefix [parent-label parent-persona]
  (or (second (re-find (re-pattern (str "\\A(" (java.util.regex.Pattern/quote parent-persona) "-[0-9]+)(?:-|\\z)")) parent-label))
      (throw (ex-info "parent pane label lacks anchored persona/index prefix" {:label parent-label :persona parent-persona}))))
(defn child-label [{:keys [parent-label parent-persona persona index model]}]
  (str (when parent-label (str (nested-prefix parent-label parent-persona) "/"))
       (root-label persona index model)))

(defn envelope [{:keys [child task result status summary artifacts findings next process]}]
  (when-not (statuses status) (throw (ex-info "invalid result status" {:status status})))
  (doseq [[k v] [[:child child] [:task task] [:result result] [:summary summary]]]
    (single-line! (name k) v))
  (findings-limit! findings)
  (process-limit! process)
  (doseq [v (concat artifacts findings process (when next [next]))] (single-line! "envelope item" v))
  (str "--- HERDR RESULT v1 ---\nCHILD: " child "\nTASK: " task "\nRESULT: " result "\nSTATUS: " status "\nSUMMARY: " summary "\n"
       "ARTIFACTS:\n" (if (seq artifacts) (str/join "\n" (map #(str "- " %) artifacts)) "- none") "\n"
       "FINDINGS:\n" (if (seq findings) (str/join "\n" (map #(str "- " %) findings)) "- none") "\n"
       "NEXT: " (or next "none") "\n"
       ;; Omitted when empty; trailing is simply the canonical serialization order. The
       ;; reader delimits sections structurally and accepts PROCESS in any position.
       (when (seq process) (str "PROCESS:\n" (str/join "\n" (map #(str "- " %) process)) "\n"))
       "--- END HERDR RESULT ---\n"))

(defn- field! [lines label]
  (let [matches (filter #(str/starts-with? % (str label ": ")) lines)]
    (when-not (= 1 (count matches)) (throw (ex-info "result envelope field is missing or repeated" {:field label})))
    (single-line! label (subs (first matches) (+ 2 (count label))))))
(def ^:private end-marker "--- END HERDR RESULT ---")
(def ^:private field-labels ["CHILD" "TASK" "RESULT" "STATUS" "SUMMARY" "NEXT"])
(def ^:private section-headers ["ARTIFACTS:" "FINDINGS:" "PROCESS:"])
;; Sections are delimited structurally — by the next section header, scalar field line, or
;; the END marker — never by a value the child chose. Every list item carries a `- `
;; prefix, so no item line can be mistaken for a boundary, and optional sections may be
;; placed anywhere between the markers without corrupting a neighbour.
(defn- boundary? [line]
  (boolean (or (= end-marker line)
               (some #(= % line) section-headers)
               (some #(str/starts-with? line (str % ": ")) field-labels))))
(defn- section-body [lines header]
  (let [v (vec lines) a (.indexOf v header)]
    (when (neg? a) (throw (ex-info "result envelope section is malformed" {:section header})))
    (let [tail (subvec v (inc a))]
      (subvec tail 0 (count (take-while (complement boundary?) tail))))))
(defn- section-lines [lines header]
  ;; Required sections keep `field!`'s strictness: a repeated header would silently drop
  ;; the duplicate block now that a header is itself a boundary, so reject it outright.
  (when-not (= 1 (count (filter #(= header %) lines)))
    (throw (ex-info "result envelope section is missing or repeated" {:section header})))
  (let [items (section-body lines header)]
    (when-not (every? #(str/starts-with? % "- ") items) (throw (ex-info "result envelope list item is malformed" {:section header})))
    (let [values (mapv #(subs % 2) items)] (if (= ["none"] values) [] values))))
;; PROCESS is a discardable annotation, so unlike the required sections it is absent-safe
;; and parsed tolerantly: non `- ` lines are ignored rather than invalidating the result,
;; and repeated headers merge their blocks in document order instead of dropping items.
(defn- process-items [lines]
  (let [v (vec lines)
        values (->> (keep-indexed (fn [i line] (when (= "PROCESS:" line) (inc i))) v)
                    (mapcat #(take-while (complement boundary?) (subvec v %)))
                    (filter (fn [line] (str/starts-with? line "- ")))
                    (mapv #(subs % 2)))]
    (if (= ["none"] values) [] values)))
(defn parse-envelope [text]
  (let [lines (str/split-lines text)]
    (when-not (and (= "--- HERDR RESULT v1 ---" (first lines)) (= end-marker (last lines)))
      (throw (ex-info "invalid result envelope markers" {})))
    {:child (field! lines "CHILD") :task (field! lines "TASK") :result (field! lines "RESULT")
     :status (field! lines "STATUS") :summary (field! lines "SUMMARY")
     :artifacts (section-lines lines "ARTIFACTS:")
     :findings (section-lines lines "FINDINGS:")
     :next (field! lines "NEXT") :process (process-items lines) :text text}))

;; An ARTIFACTS item is `<absolute path>[ — <purpose>]`. One splitter owns that delimiter
;; so the absoluteness check and the link renderer can never disagree about it.
;; A path *containing* ` — ` mis-splits, exactly as it always has for `artifact-path`: the
;; delimiter is envelope grammar, not an escapable value. The truncated prefix then fails
;; the collect-time existence check in all but pathological cases.
(defn artifact-parts [line]
  (let [[path purpose] (str/split (str line) #" — " 2)]
    {:path path :purpose purpose}))
(defn artifact-path [line]
  (let [{:keys [path]} (artifact-parts line)]
    (when-not (str/starts-with? path "/") (throw (ex-info "artifact path must be absolute" {:artifact line :path path})))
    path))
;; Escapes only the characters that change *inline* rendering: the two that could break out
;; of a link label (`[`, `]`), the escape character itself, the code/emphasis markers a path
;; may legitimately contain, and `&`/`<`/`>`/`~`. Those last four matter because a rendered
;; label must stay byte-identical to the real path: a CommonMark renderer decodes the entity
;; reference in `/tmp/amp&amp;.md` to `/tmp/amp&.md` (a *different* file), and raw inline
;; HTML (`<b>`) can vanish outright. All are ASCII punctuation, so `\&`/`\<` are valid
;; escapes. Line-level constructs (`#`, `-`) cannot fire mid-line and are left alone.
(defn markdown-escape [text]
  (str/replace (str text) #"([\\`*_\[\]<>&~])" "\\\\$1"))
;; Not pure: `Path.toUri` stats the path and appends a trailing `/` when it is an existing
;; directory, so a directory artifact's label omits the slash its URI carries, and the
;; advisory (rendered before the file need exist) may differ from the collected link.
;; `Path.toUri` (not `File.toURI`, which omits the empty authority and yields `file:/…`)
;; does the percent-encoding: spaces, `#`, `%`, `?`, `[`, `]`, and non-ASCII all come back
;; encoded, and hand-rolling that is exactly the bug this avoids. It leaves `(` and `)`
;; alone — legal URI sub-delims — but an unbalanced parenthesis terminates a Markdown link
;; destination early, so those two are percent-encoded afterwards. That is a Markdown
;; concern, not a second encoder: the result is the same file URI either way.
(defn file-uri [path]
  (-> (str (.toUri (java.nio.file.Paths/get (str path) (make-array String 0))))
      (str/replace "(" "%28")
      (str/replace ")" "%29")))
;; Portable fallback syntax, never a raw OSC 8 escape: a renderer that understands
;; Markdown links can turn this into a terminal hyperlink, and every other reader still
;; sees the full absolute path plus a usable URL. The visible label is always the whole
;; absolute path — a basename would discard the context the parent needs after the child
;; pane is gone. Absoluteness is enforced here rather than trusted from the caller: both
;; current callers pre-validate, but `Paths/get` resolves a relative path against the
;; process cwd, so a caller that forgot would emit a confident link to the wrong file.
(defn artifact-link [artifact]
  (let [line (str artifact)
        path (artifact-path line)
        purpose (:purpose (artifact-parts line))
        link (str "[" (markdown-escape path) "](" (file-uri path) ")")]
    (if (str/blank? purpose) link (str link " — " (markdown-escape purpose)))))
(defn validate-envelope [ledger text]
  (let [parsed (parse-envelope text)]
    (doseq [key [:child :task :result]]
      (when-not (= (str (get ledger key)) (get parsed key))
        (throw (ex-info "result envelope identity does not match ledger" {:field key :expected (get ledger key) :actual (get parsed key)}))))
    (when-not (statuses (:status parsed)) (throw (ex-info "invalid envelope status" {:status (:status parsed)})))
    (findings-limit! (:findings parsed))
    (doseq [artifact (:artifacts parsed)] (artifact-path artifact))
    (if (< max-process (count (:process parsed)))
      (assoc parsed :process (vec (take max-process (:process parsed))) :process-overflow true)
      parsed)))
(defn json-envelope [ok payload]
  (json/generate-string (if ok {:ok true :schema schema :result payload} {:ok false :schema schema :error payload})))

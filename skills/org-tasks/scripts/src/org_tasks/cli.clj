(ns org-tasks.cli
  "Org-tasks (`ot`) command-line entry point.

  Dispatches subcommands via `babashka.cli`. Per-command specs merge
  over `global-spec`; the dispatch table at the bottom of this file
  wires each `[cmd args]` shape onto a handler in `org-tasks.commands`.
  Machine-output contract: `skills/org-tasks/scripts/docs/contract.md`."
  (:require [babashka.cli :as cli]
            [clojure.string :as str]
            [org-tasks.commands :as commands]
            [org-tasks.output :as out]))

;; ── Option specs ───────────────────────────────────────────────────

(def ^:private global-spec
  "Options accepted by every subcommand. Per-command specs merge over
  this map so command-specific options can shadow defaults."
  {:root      {:desc "Project root (default: nearest TASKS.org ancestor, else cwd)"
               :ref  "<dir>"}
   :format    {:desc "Output format: text | json | edn"
               :ref  "<fmt>"
               :default :text
               :coerce :keyword
               ;; `dispatch` validates before coercing, so the predicate must
               ;; accept the raw string form. The handler still sees a keyword
               ;; because `:coerce :keyword` runs afterwards.
               :validate
               {:pred   #(contains? #{"text" "json" "edn" :text :json :edn} %)
                :ex-msg (fn [{:keys [value]}]
                          (str "--format must be one of text|json|edn, got "
                               (pr-str value)))}}
   :tasks     {:desc "Override path to TASKS.org"
               :ref  "<path>"}
   :local     {:desc "Override path to TASKS.local.org"
               :ref  "<path>"}
   :archive   {:desc "Override path to TASKS.archive.org"
               :ref  "<path>"}
   :dry-run   {:desc "Print proposed changes without writing"
               :coerce :boolean}
   :yes       {:desc "Skip confirmation prompts on destructive commands"
               :alias :y
               :coerce :boolean}
   :no-color  {:desc "Disable ANSI styling in --format text"
               :coerce :boolean}
   :help      {:desc "Show command help"
               :alias :h
               :coerce :boolean}})

;; Per-command spec extensions. Each map is merged over `global-spec`
;; when registering the dispatch entry.
(def ^:private create-spec
  {:section  {:desc "Section heading to insert under (default: Improvements)"
              :ref  "<name>"
              :default "Improvements"}
   :parent   {:desc "Parent task :CUSTOM_ID:" :ref "<uuid>"}
   :after    {:desc "Insert immediately after this task :CUSTOM_ID:"
              :ref  "<uuid>"}
   :local    {:desc "Insert into TASKS.local.org instead of TASKS.org"
              :coerce :boolean}
   :priority {:desc "Priority cookie (A|B|C|D or Highest|High|Medium|Low|Lowest)"
              :ref  "<level>"}
   :tag      {:desc "Tag to append (repeatable)"
              :coerce []}
   :body     {:desc "Task description text"
              :ref  "<text>"}
   :linked-issue {:desc "Linked issue org token (repeatable, e.g. '[[jira:ABC-1]]')"
                  :coerce []}
   :also-scan {:desc "Additional org file to scan for duplicate linked issues (repeatable)"
               :ref "<path>"
               :coerce []}
   :id {:desc "Override generated :CUSTOM_ID: (mostly for tests / shims)"
        :ref "<uuid>"}
   :created-at {:desc "Override :CREATED: timestamp body, without brackets"
                :ref "<timestamp>"}
   :allow-create-section {:desc "Create the target section if missing"
                          :coerce :boolean}})

(def ^:private list-spec
  {:status-filter {:desc "Filter by status (repeatable)" :coerce []
                   :alias :S}
   :selected      {:desc "Show only the selected task subtree" :coerce :boolean}
   :scope         {:desc "active | archived | all" :ref "<scope>" :default :active
                   :coerce :keyword
                   :validate {:pred #(contains? #{"active" "archived" "all"
                                                   :active :archived :all} %)}}
   :levels        {:desc (str "Limit hierarchy depth (0 = top-level only, "
                              "1 = top-level + direct children, …). "
                              "Omit for unlimited.")
                   :ref  "<n>"
                   :alias :l
                   :coerce :long
                   :validate
                   {:pred (fn [v]
                            (let [n (cond (integer? v) v
                                          (string? v) (try (Long/parseLong v)
                                                           (catch Throwable _ nil)))]
                              (and (integer? n) (>= n 0))))
                    :ex-msg (fn [{:keys [value]}]
                              (str "--levels must be a non-negative integer, got "
                                   (pr-str value)))}}})

(def ^:private scan-spec
  {:scope         {:desc "active | archived | all" :ref "<scope>" :default :all
                   :coerce :keyword
                   :validate {:pred #(contains? #{"active" "archived" "all"
                                                   :active :archived :all} %)}}
   :tag           {:desc "Tag whitelist (repeatable, OR-semantics)"
                   :coerce []}
   :max-body-chars {:desc "Cap on inlined * Summary body length per row"
                    :ref  "<n>" :coerce :long :default 500}})

(def ^:private section-spec
  {})

(def ^:private status-spec
  {})

(def ^:private select-spec
  {:clear {:desc "Clear the current selection" :coerce :boolean}})

(def ^:private record-spec
  {:path {:desc "Override the change-record path"
          :ref  "<path>"}
   :mode {:desc "proactive | retrospective"
          :ref  "<mode>"
          :coerce :keyword
          :default :proactive
          :validate {:pred #(contains? #{"proactive" "retrospective"
                                          :proactive :retrospective} %)}}})

(def ^:private uuid-spec
  {:count {:desc "Number of UUIDv4 values to generate"
           :ref  "<n>"
           :coerce :long
           :default 1
           :validate {:pred #(and (integer? %) (pos? %))
                      :ex-msg (fn [{:keys [value]}]
                                (str "--count must be a positive integer, got "
                                     (pr-str value)))}}})

(def ^:private backfill-spec
  {:created-at {:desc "Override generated :CREATED: timestamp body, without brackets"
                :ref "<timestamp>"}})

(defn- merge-spec [extra]
  (merge global-spec extra))

;; ── Help ─────────────────────────────────────────────────────────

(def ^:private command-summary
  "User-visible command index. Used to render `ot --help` and
  `ot help`. Entries are [name args description]; nested subcommands
  flatten with the dotted form used by the dispatch table."
  [["init"             ""                  "Bootstrap TASKS.{setup,local}.org + TASKS.org"]
   ["list"             ""                  "List the task graph (--levels N caps depth, --format json|edn for machine output)"]
   ["show"             "<id|selected>"     "Show one task plus its plan summary"]
   ["create"           "<summary>"         "Create a new task under --section"]
   ["status"           "<id> <new-status>" "Cycle a task to STARTED / WAITING / DONE / CANCELLED / TODO"]
   ["select"           "<id>"              "Mark a task selected (or pass --clear to deselect)"]
   ["selected"         ""                  "Show the currently-selected task"]
   ["archive"          "<id>"              "Archive a closed top-level task"]
   ["publish"          "<id>"              "Move a local task to TASKS.org"]
   ["unpublish"        "<id>"              "Move a top-level shared task to TASKS.local.org"]
   ["doctor"           ""                  "Run protocol health checks"]
   ["backfill"         ""                  "Fill missing :CUSTOM_ID: metadata for hand-authored tasks"]
   ["section"          "<file> [<section>]" "Read one * section of an org file"]
   ["scan"             ""                  "Walk the graph for prior-art change-record summaries"]
   ["record create"    "<id>"              "Scaffold a change-record and attach #+IMPORT:"]
   ["record path"      "<id>"              "Suggest a change-record path"]
   ["issue list"       "<id>"              "List :LINKED_ISSUES: tokens"]
   ["issue add"        "<id> <token>"      "Append a token to :LINKED_ISSUES:"]
   ["issue remove"     "<id> <token>"      "Remove a token from :LINKED_ISSUES:"]
   ["issue urls"       "<id>"              "Resolve linked-issue URLs"]
   ["blocker list"     "<id>"              "List :BLOCKED-BY: entries"]
   ["blocker add"      "<id> <token>"      "Append a :BLOCKED-BY: entry"]
   ["blocker remove"   "<id> <token>"      "Remove a :BLOCKED-BY: entry"]
   ["ready"            "<id>"              "Report whether a task is ready to start"]
   ["handoff get"      "<id>"              "Print the :HANDOFF: note"]
   ["handoff set"      "<id> <text>"       "Set the :HANDOFF: note"]
   ["handoff clear"    "<id>"              "Clear the :HANDOFF: note"]
   ["uuid"             "[--count N]"       "Generate one or more UUIDv4 values for new task IDs"]])

(defn- pad [s n] (format (str "%-" n "s") s))

(defn- format-help []
  (let [cmd-w  (apply max (map (comp count first)  command-summary))
        args-w (apply max (map (comp count second) command-summary))
        cmd-lines
        (for [[c a d] command-summary]
          (str "  " (pad c cmd-w) "  " (pad a args-w) "  " d))]
    (str/join "\n"
              (concat
               ["ot — org-tasks command-line interface"
                ""
                "Usage: ot [global-options] <command> [command-options]"
                ""
                "Commands:"]
               cmd-lines
               [""
                "Global options:"
                (cli/format-opts {:spec global-spec
                                  :order [:root :format :tasks :local :archive
                                          :dry-run :yes :no-color :help]})
                ""
                "Run `ot <command> --help` for command-specific options."
                "Machine output contract:"
                "  skills/org-tasks/scripts/docs/contract.md"]))))

(defn- help-cmd [_dispatch-result]
  (println (format-help))
  (out/*exit-fn* 0))

;; ── Dispatch table ────────────────────────────────────────────────

(def ^:private dispatch-table
  [{:cmds ["init"]               :fn commands/init-cmd
    :spec (merge-spec {})}
   {:cmds ["list"]               :fn commands/list-cmd
    :spec (merge-spec list-spec)}
   {:cmds ["show"]               :fn commands/show-cmd
    :spec (merge-spec {}) :args->opts [:id]}
   {:cmds ["create"]             :fn commands/create-cmd
    :spec (merge-spec create-spec) :args->opts [:summary]}
   {:cmds ["status"]             :fn commands/status-cmd
    :spec (merge-spec status-spec) :args->opts [:id :new-status]}
   {:cmds ["select"]             :fn commands/select-cmd
    :spec (merge-spec select-spec) :args->opts [:id]}
   {:cmds ["selected"]           :fn commands/selected-cmd
    :spec (merge-spec {})}
   {:cmds ["archive"]            :fn commands/archive-cmd
    :spec (merge-spec {}) :args->opts [:id]}
   {:cmds ["publish"]            :fn commands/publish-cmd
    :spec (merge-spec {}) :args->opts [:id]}
   {:cmds ["unpublish"]          :fn commands/unpublish-cmd
    :spec (merge-spec {}) :args->opts [:id]}
   {:cmds ["doctor"]             :fn commands/doctor-cmd
    :spec (merge-spec {})}
   {:cmds ["backfill"]           :fn commands/backfill-cmd
    :spec (merge-spec backfill-spec)}
   {:cmds ["section"]            :fn commands/section-cmd
    :spec (merge-spec section-spec) :args->opts [:file :section]}
   {:cmds ["scan"]               :fn commands/scan-cmd
    :spec (merge-spec scan-spec)}
   {:cmds ["record" "create"]    :fn commands/record-create-cmd
    :spec (merge-spec record-spec) :args->opts [:id]}
   {:cmds ["record" "path"]      :fn commands/record-path-cmd
    :spec (merge-spec {}) :args->opts [:id]}
   {:cmds ["issue" "list"]       :fn commands/issue-list-cmd
    :spec (merge-spec {}) :args->opts [:id]}
   {:cmds ["issue" "add"]        :fn commands/issue-add-cmd
    :spec (merge-spec {}) :args->opts [:id :token]}
   {:cmds ["issue" "remove"]     :fn commands/issue-remove-cmd
    :spec (merge-spec {}) :args->opts [:id :token]}
   {:cmds ["issue" "urls"]       :fn commands/issue-urls-cmd
    :spec (merge-spec {}) :args->opts [:id]}
   {:cmds ["blocker" "list"]     :fn commands/blocker-list-cmd
    :spec (merge-spec {}) :args->opts [:id]}
   {:cmds ["blocker" "add"]      :fn commands/blocker-add-cmd
    :spec (merge-spec {}) :args->opts [:id :token]}
   {:cmds ["blocker" "remove"]   :fn commands/blocker-remove-cmd
    :spec (merge-spec {}) :args->opts [:id :token]}
   {:cmds ["ready"]              :fn commands/ready-cmd
    :spec (merge-spec {}) :args->opts [:id]}
   {:cmds ["handoff" "get"]      :fn commands/handoff-get-cmd
    :spec (merge-spec {}) :args->opts [:id]}
   {:cmds ["handoff" "set"]      :fn commands/handoff-set-cmd
    :spec (merge-spec {}) :args->opts [:id :text]}
   {:cmds ["handoff" "clear"]    :fn commands/handoff-clear-cmd
    :spec (merge-spec {}) :args->opts [:id]}
   {:cmds ["uuid"]               :fn commands/uuid-cmd
    :spec (merge-spec uuid-spec)}
   ;; Catch-all → top-level help. Must be last.
   {:cmds [] :fn help-cmd}])

;; ── Error handling ────────────────────────────────────────────────

(defn- cli-error-fn
  "Translate babashka.cli option-parse errors into the contract
  envelope and exit with code 2."
  [{:keys [type cause msg option value] :as _err}]
  (when (= :org.babashka/cli type)
    (out/emit-error {:format :text}
                    {:code    "argument-error"
                     :message msg
                     :details (cond-> {:cause cause}
                                option (assoc :option option)
                                value  (assoc :value value))}
                    2)))

;; ── Entry point ───────────────────────────────────────────────────

(def ^:private dispatch-coerce
  "Top-level :coerce passed to `babashka.cli/dispatch`. Per-spec
  `:coerce` is honoured by `parse-opts` but ignored by `dispatch`, so
  every option whose coerced shape matters at the handler boundary is
  also listed here."
  {:format         :keyword
   :scope          :keyword
   :mode           :keyword
   :max-body-chars :long
   :count          :long
   :levels         :long
   :dry-run        :boolean
   :yes            :boolean
   :no-color       :boolean
   :selected       :boolean
   :local          :boolean
   :allow-create-section :boolean
   :clear          :boolean
   :help           :boolean
   :tag            []
   :status-filter  []
   :linked-issue   []})

(defn -main [& args]
  (let [args (vec args)]
    ;; Honour `--help` as a top-level flag regardless of position so
    ;; `ot --help`, `ot -h`, and `ot help` all do the same thing.
    (cond
      (or (empty? args)
          (some #{"-h" "--help" "help"} args))
      (help-cmd nil)

      :else
      (cli/dispatch dispatch-table args
                    {:error-fn cli-error-fn
                     :coerce   dispatch-coerce}))))

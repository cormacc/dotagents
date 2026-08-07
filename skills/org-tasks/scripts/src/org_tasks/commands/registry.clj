(ns org-tasks.commands.registry
  "Single source of truth for `ot` commands.

  Each entry in `commands` is pure data describing one command:

    :cmds       babashka.cli command path, e.g. [\"record\" \"create\"]
    :fn         handler fn from a `org-tasks.commands.*` family namespace
    :spec       per-command option spec, merged over `global-spec`
    :args->opts positional-argument mapping (babashka.cli)
    :summary    [args-string description] for `ot --help`
    :tui-key    keyword under which the TUI exposes the handler (optional)

  `org-tasks.cli` derives its dispatch table and help index from this;
  `org-tasks.tui.dispatch` derives its `command-fns` map. Adding a
  command means writing the handler in its family namespace and adding
  one entry here."
  (:require [clojure.string :as str]
            [org-tasks.commands.archive-publish :as archive-publish]
            [org-tasks.commands.create :as create]
            [org-tasks.commands.links :as links]
            [org-tasks.commands.list-show :as list-show]
            [org-tasks.commands.maintenance :as maintenance]
            [org-tasks.commands.move :as move]
            [org-tasks.commands.record :as record]
            [org-tasks.commands.removal :as removal]
            [org-tasks.commands.spec :as spec]
            [org-tasks.commands.status :as status]))

;; ── Option specs ───────────────────────────────────────────────────

(defn- enum-opt [flag values]
  (let [allowed (set (concat (map name values) values))
        label (str/join "|" (map name values))]
    {:coerce :keyword
     :validate {:pred #(contains? allowed %)
                :ex-msg (fn [{:keys [value]}]
                          (str "--" flag " must be one of " label ", got "
                               (pr-str value)))}}))

(def global-spec
  "Options accepted by every subcommand. Per-command specs merge over
  this map so command-specific options can shadow defaults."
  {:root      {:desc "Project root (default: nearest TASKS.org ancestor, else cwd)"
               :ref  "<dir>"}
   :format    (merge {:desc "Output format: text | json | edn"
                      :ref  "<fmt>"
                      :default :text}
                     (enum-opt "format" [:text :json :edn]))
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

(def ^:private scope-spec
  (merge {:desc "active | archived | all" :ref "<scope>"}
         (enum-opt "scope" [:active :archived :all])))

;; Per-command spec extensions. Each map is merged over `global-spec`
;; when the dispatch entry is built.
(def ^:private create-spec
  {:section  {:desc "Section heading to insert under (default: Improvements)"
              :ref  "<name>"
              :default "Improvements"}
   :parent   {:desc "Parent task :CUSTOM_ID:" :ref "<uuid>" :coerce :string}
   :after    {:desc "Insert immediately after this task :CUSTOM_ID:"
              :ref  "<uuid>" :coerce :string}
   :relative-to {:desc (str "Anchor task :CUSTOM_ID: to place the new task relative "
                            "to; derives parent/after/local/source (overrides those)")
                 :ref  "<uuid>" :coerce :string}
   :as       (merge {:desc "Placement relative to --relative-to: sibling (default) | child"
                     :ref  "<rel>"}
                    (enum-opt "as" [:sibling :child]))
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
        :ref "<uuid>" :coerce :string}
   :created-at {:desc "Override :CREATED: timestamp body, without brackets"
                :ref "<timestamp>"}
   :allow-create-section {:desc "Create the target section if missing"
                          :coerce :boolean}})

(def ^:private move-spec
  {:parent  {:desc "Destination parent task :CUSTOM_ID: (append as last child)"
             :ref  "<uuid>" :coerce :string}
   :section {:desc (str "Destination level-1 section in the task's own file "
                       "(move back to top level)")
             :ref  "<name>"}})

(def ^:private list-spec
  {:status-filter {:desc "Filter by status (repeatable)" :coerce []
                   :alias :S}
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
  {:scope         (assoc scope-spec :default :all)
   :tag           {:desc "Tag whitelist (repeatable, OR-semantics)"
                   :coerce []}
   :max-body-chars {:desc "Cap on inlined * Summary body length per row"
                    :ref  "<n>" :coerce :long :default 500}})

(def ^:private status-spec
  {:cycle (merge {:desc "Cycle relative to the current status instead of setting it: forward | back"
                  :ref "<dir>"}
                 (enum-opt "cycle" [:forward :back]))})

(def ^:private priority-spec
  {:cycle (merge {:desc "Cycle relative to the current priority instead of setting it: forward | back"
                  :ref "<dir>"}
                 (enum-opt "cycle" [:forward :back]))
   :clear {:desc "Clear the priority cookie" :coerce :boolean}})

(def ^:private include-content-spec
  {:include-content {:desc "Include raw sourceContent/effectiveSourceContent in JSON/EDN output"
                     :coerce :boolean}})

(def ^:private unarchive-spec
  {:section {:desc "Restore under this existing level-1 section (overrides :ARCHIVE_OLPATH:)"
             :ref "<name>"}})

(def ^:private select-spec
  {:clear {:desc "Clear the current selection" :coerce :boolean}
   :clear-stale {:desc "Clear only an unresolved #+SELECTED: pointer" :coerce :boolean}})

(def ^:private remove-spec
  {:prune-blockers {:desc "Remove inbound task blockers that resolve into the deleted subtree"
                    :coerce :boolean}})

(def ^:private record-spec
  {:path {:desc "Override the change-record path"
          :ref  "<path>"}
   :mode (merge {:desc "proactive | retrospective"
                 :ref  "<mode>"
                 :default :proactive}
                (enum-opt "mode" [:proactive :retrospective]))})

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

;; ── Registry ──────────────────────────────────────────────────────

(def commands
  "Ordered command registry. Order drives both dispatch precedence and
  the `ot --help` command index."
  [{:cmds ["init"]               :fn maintenance/init-cmd
    :spec {}
    :summary ["" "Bootstrap TASKS.{setup,local}.org + TASKS.org"]}
   {:cmds ["root"]               :fn maintenance/root-cmd
    :spec {}
    :summary ["" "Print the resolved project root"]}
   {:cmds ["list"]               :fn list-show/list-cmd
    :spec list-spec
    :summary ["" "List the task graph (--levels N caps depth, --format json|edn for machine output)"]}
   {:cmds ["show"]               :fn list-show/show-cmd
    :spec include-content-spec :args->opts [:id]
    :summary ["<id|selected>" "Show one task plus its plan summary"]}
   {:cmds ["create"]             :fn create/create-cmd
    :spec create-spec :args->opts [:summary]
    :tui-key :create
    :summary ["<summary>" "Create a new task under --section"]}
   {:cmds ["move"]               :fn move/move-cmd
    :spec (merge move-spec include-content-spec) :args->opts [:id]
    :summary ["<id> (--parent <id> | --section <name>)"
              "Move an existing task subtree under another task or back to a section"]}
   {:cmds ["remove"]             :fn removal/remove-cmd
    :spec remove-spec :args->opts [:id]
    :tui-key :remove
    :summary ["<id> [--prune-blockers] --yes"
              "Preview or remove an eligible non-top-level task subtree"]}
   {:cmds ["status"]             :fn status/status-cmd
    :spec (merge status-spec include-content-spec) :args->opts [:id :new-status]
    :tui-key :status
    :summary ["<id> <new-status>" "Cycle a task to STARTED / WAITING / DONE / CANCELLED / TODO"]}
   {:cmds ["priority"]           :fn status/priority-cmd
    :spec (merge priority-spec include-content-spec) :args->opts [:id :level]
    :summary ["<id> [<level>]" "Set, cycle (--cycle forward|back), or --clear the priority cookie"]
    :tui-key :priority}
   {:cmds ["select"]             :fn list-show/select-cmd
    :spec select-spec :args->opts [:id]
    :tui-key :select
    :summary ["<id>" "Mark a task selected (or pass --clear to deselect)"]}
   {:cmds ["selected"]           :fn list-show/selected-cmd
    :spec include-content-spec
    :summary ["" "Show the currently-selected task"]}
   {:cmds ["archive"]            :fn archive-publish/archive-cmd
    :spec include-content-spec :args->opts [:id]
    :tui-key :archive
    :summary ["<id>" "Archive a closed top-level task"]}
   {:cmds ["unarchive"]          :fn archive-publish/unarchive-cmd
    :spec (merge unarchive-spec include-content-spec) :args->opts [:id]
    :summary ["<id>" "Restore an archived task under --section or :ARCHIVE_OLPATH:"]}
   {:cmds ["publish"]            :fn archive-publish/publish-cmd
    :spec include-content-spec :args->opts [:id]
    :tui-key :publish
    :summary ["<id>" "Move a local task to TASKS.org"]}
   {:cmds ["unpublish"]          :fn archive-publish/unpublish-cmd
    :spec include-content-spec :args->opts [:id]
    :tui-key :unpublish
    :summary ["<id>" "Move a top-level shared task to TASKS.local.org"]}
   {:cmds ["doctor"]             :fn maintenance/doctor-cmd
    :spec {}
    :summary ["" "Run protocol health checks"]}
   {:cmds ["backfill"]           :fn maintenance/backfill-cmd
    :spec backfill-spec
    :summary ["" "Fill missing :CUSTOM_ID: metadata for hand-authored tasks"]}
   {:cmds ["section"]            :fn maintenance/section-cmd
    :spec {} :args->opts [:file :section]
    :summary ["<file> [<section>]" "Read one * section of an org file"]}
   {:cmds ["scan"]               :fn maintenance/scan-cmd
    :spec scan-spec
    :summary ["" "Walk the graph for prior-art change-record summaries"]}
   {:cmds ["record" "create"]    :fn record/record-create-cmd
    :spec record-spec :args->opts [:id]
    :tui-key :record-create
    :summary ["<id>" "Scaffold a change-record and attach #+IMPORT:"]}
   {:cmds ["record" "path"]      :fn record/record-path-cmd
    :spec {} :args->opts [:id]
    :summary ["<id>" "Suggest a change-record path"]}
   {:cmds ["issue" "list"]       :fn links/issue-list-cmd
    :spec {} :args->opts [:id]
    :summary ["<id>" "List :LINKED_ISSUES: tokens"]}
   {:cmds ["issue" "add"]        :fn links/issue-add-cmd
    :spec {} :args->opts [:id :token]
    :summary ["<id> <token>" "Append a token to :LINKED_ISSUES:"]}
   {:cmds ["issue" "remove"]     :fn links/issue-remove-cmd
    :spec {} :args->opts [:id :token]
    :summary ["<id> <token>" "Remove a token from :LINKED_ISSUES:"]}
   {:cmds ["tag" "add"]          :fn links/tag-add-cmd
    :spec {} :args->opts [:id :tag-token]
    :summary ["<id> <tag>" "Append a trailing heading tag"]}
   {:cmds ["tag" "remove"]       :fn links/tag-remove-cmd
    :spec {} :args->opts [:id :tag-token]
    :summary ["<id> <tag>" "Remove a trailing heading tag"]}
   {:cmds ["issue" "urls"]       :fn links/issue-urls-cmd
    :spec {} :args->opts [:id]
    :tui-key :issue-urls
    :summary ["<id>" "Resolve linked-issue URLs"]}
   {:cmds ["blocker" "list"]     :fn links/blocker-list-cmd
    :spec {} :args->opts [:id]
    :summary ["<id>" "List :BLOCKED-BY: entries"]}
   {:cmds ["blocker" "add"]      :fn links/blocker-add-cmd
    :spec {} :args->opts [:id :token]
    :summary ["<id> <token>" "Append a :BLOCKED-BY: entry"]}
   {:cmds ["blocker" "remove"]   :fn links/blocker-remove-cmd
    :spec {} :args->opts [:id :token]
    :summary ["<id> <token>" "Remove a :BLOCKED-BY: entry"]}
   {:cmds ["blocker" "prune"]    :fn removal/blocker-prune-cmd
    :spec {} :summary ["[--yes]" "Preview or prune unresolved task blockers"]}
   {:cmds ["ready"]              :fn links/ready-cmd
    :spec {} :args->opts [:id]
    :summary ["<id>" "Report whether a task is ready to start"]}
   {:cmds ["handoff" "get"]      :fn links/handoff-get-cmd
    :spec {} :args->opts [:id]
    :summary ["<id>" "Print the :HANDOFF: note"]}
   {:cmds ["handoff" "set"]      :fn links/handoff-set-cmd
    :spec {} :args->opts [:id :text]
    :summary ["<id> <text>" "Set the :HANDOFF: note"]}
   {:cmds ["handoff" "clear"]    :fn links/handoff-clear-cmd
    :spec {} :args->opts [:id]
    :summary ["<id>" "Clear the :HANDOFF: note"]}
   {:cmds ["uuid"]               :fn maintenance/uuid-cmd
    :spec uuid-spec
    :summary ["[--count N]" "Generate one or more UUIDv4 values for new task IDs"]}
   {:cmds ["spec" "list"]        :fn spec/spec-list-cmd
    :spec {}
    :summary ["" "Report the discovered spec set with root provenance (read-only)"]}
   {:cmds ["spec" "discover"]    :fn spec/spec-list-cmd
    :spec {}
    :summary ["" "Alias of 'spec list'"]}])

(def dispatch-coerce
  "Top-level :coerce passed to `babashka.cli/dispatch`, derived from
  `global-spec` plus every command spec.

  Required because `dispatch` parses options that *precede* the subcommand
  before it has resolved which entry to run, so no entry `:spec` is in scope
  for them and only this top-level map applies. `ot --format json show <id>`
  therefore yields the string \"json\" without the aggregate entry, while
  `ot show <id> --format json` is coerced to `:json` by the entry spec alone.
  Duplicating every command's coercions here keeps both orders equivalent.

  Positional id mappings use this aggregate entry without adding a synthetic
  `--id` option to their per-command help; `:id :string` also stops an
  all-digit or scientific-notation-shaped `:CUSTOM_ID:` prefix being
  number-coerced.

  Measured identical across org.babashka/cli 0.7.53, 0.8.61, 0.11.73, 0.12.75
  and 0.12.85 (bb v1.13.219, 2026-08-08): this is stable library behaviour,
  not a version-specific workaround. Dropping the aggregation fails ~111
  tests with `No matching clause: json`."
  (let [spec-coerce (into {}
                          (keep (fn [[k spec]]
                                  (when (contains? spec :coerce)
                                    [k (:coerce spec)])))
                          (apply merge global-spec (map :spec commands)))
        positional-id? (some #(some #{:id} (:args->opts %)) commands)]
    (cond-> spec-coerce
      positional-id? (assoc :id :string))))

(def tui-command-fns
  "Keyword → handler map for TUI-exposed commands (entries with a
  `:tui-key`). Consumed by `org-tasks.tui.dispatch/command-fns`."
  (into {}
        (keep (fn [entry]
                (when-let [k (:tui-key entry)]
                  [k (:fn entry)])))
        commands))

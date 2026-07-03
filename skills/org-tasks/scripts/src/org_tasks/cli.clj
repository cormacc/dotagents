(ns org-tasks.cli
  "Org-tasks (`ot`) command-line entry point.

  Dispatches subcommands via `babashka.cli`. The dispatch table and the
  `ot --help` command index are both derived from the shared command
  registry (`org-tasks.commands.registry`); per-command specs merge
  over the registry's `global-spec`.
  Machine-output contract: `skills/org-tasks/scripts/docs/contract.md`."
  (:require [babashka.cli :as cli]
            [clojure.string :as str]
            [org-tasks.commands.registry :as registry]
            [org-tasks.output :as out]
            [org-tasks.tui :as tui]
            [org-tasks.tui.tasks :as tui-tasks]))

;; ── Help ─────────────────────────────────────────────────────────

(def ^:private command-summary
  "User-visible command index. Used to render `ot --help` and
  `ot help`. Entries are [name args description]; nested subcommands
  flatten with the dotted form used by the dispatch table."
  (mapv (fn [{:keys [cmds summary]}]
          (into [(str/join " " cmds)] summary))
        registry/commands))

(defn- pad [s n] (format (str "%-" n "s") s))

(def ^:private global-opt-order
  [:root :format :tasks :local :archive :dry-run :yes :no-color :help])

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
                (cli/format-opts {:spec registry/global-spec
                                  :order global-opt-order})
                ""
                "Run `ot <command> --help` for command-specific options."
                "Machine output contract:"
                "  skills/org-tasks/scripts/docs/contract.md"]))))

(defn- format-command-help
  "Render help for one registry entry: usage line, command-specific
  options (when the entry declares any), then the global options."
  [{:keys [cmds spec summary]}]
  (let [[args desc] summary
        usage (str "ot " (str/join " " cmds)
                   (when (seq args) (str " " args)))]
    (str/join "\n"
              (concat
               [(str usage " — " desc)]
               (when (seq spec)
                 [""
                  "Options:"
                  (cli/format-opts {:spec spec})])
               [""
                "Global options:"
                (cli/format-opts {:spec registry/global-spec
                                  :order global-opt-order})]))))

(defn- command-entry-for
  "Longest registry entry whose `:cmds` path prefixes the leading
  non-flag tokens of `args` (help tokens ignored), or nil."
  [args]
  (let [tokens (->> args
                    (remove #{"-h" "--help" "help"})
                    (take-while #(not (str/starts-with? % "-")))
                    vec)]
    (when (seq tokens)
      (->> registry/commands
           (filter (fn [{:keys [cmds]}]
                     (and (seq cmds)
                          (= cmds (vec (take (count cmds) tokens))))))
           (sort-by (comp count :cmds) >)
           first))))

(defn- help-cmd [_dispatch-result]
  (println (format-help))
  (out/*exit-fn* 0))

;; ── Dispatch table ────────────────────────────────────────────────

(def ^:private dispatch-table
  (conj
   (mapv (fn [{:keys [cmds spec args->opts] :as entry}]
           (cond-> {:cmds cmds
                    :fn   (:fn entry)
                    :spec (merge registry/global-spec spec)}
             args->opts (assoc :args->opts args->opts)))
         registry/commands)
   ;; Catch-all → top-level help. Must be last.
   {:cmds [] :fn help-cmd}))

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

(defn -main [& args]
  (let [args (vec args)]
    ;; Honour `--help` as a top-level flag regardless of position so
    ;; `ot --help`, `ot -h`, and `ot help` all do the same thing.
    (cond
      (some #{"-h" "--help" "help"} args)
      (if-let [entry (command-entry-for args)]
        (do (println (format-command-help entry))
            (out/*exit-fn* 0))
        (help-cmd nil))

      (or (empty? args)
          (= ["--format" "json"] args)
          (= ["--format=json"] args))
      (if (and (empty? args) (tui/interactive-terminal?))
        (tui/run! {:format :json})
        (tui-tasks/selected-json! {:format :json}))

      :else
      (cli/dispatch dispatch-table args
                    {:error-fn cli-error-fn
                     :coerce   registry/dispatch-coerce}))))

(ns org-tasks.commands.util
  "Shared context and id resolution helpers for `ot` command families."
  (:require [org-tasks.loader :as loader]
            [org-tasks.output :as out]
            [org-tasks.parser :as parser]
            [org-tasks.root :as root]
            [org-tasks.task :as task]))

;; ── Argument helpers ───────────────────────────────────────────────

(defn positional-arg
  "Return positional argument `n` after registry :args->opts mapping.

  Command handlers are also invoked directly from tests/TUI helpers, so keep
  the legacy raw :args fallback in one place instead of at every call site."
  ([result k] (positional-arg result k 0))
  ([result k n]
   (or (get-in result [:opts k]) (nth (:args result) n nil))))

;; ── Context helpers ────────────────────────────────────────────────

(defn resolve-context [opts]
  (let [project-root (root/resolve-root opts)
        files        (root/resolve-protocol-files opts project-root)]
    {:project-root project-root :files files}))

(defn load-context [opts]
  (let [{:keys [project-root files]} (resolve-context opts)]
    (try
      (let [graph (loader/load-graph project-root files)]
        (merge {:project-root project-root :files files} graph))
      (catch clojure.lang.ExceptionInfo e
        (let [{:keys [code file line]} (ex-data e)]
          (out/emit-error opts
                          {:code (name (or code :parse-error))
                           :message (ex-message e)
                           :file file
                           :line line}))))))

(def ^:private guarded-codes
  "Structured error codes raised by `loader`/`parser` that commands
  translate into the standard error envelope instead of letting
  propagate as a raw exception: a write-time conflict, an unreadable
  file, or an unterminated `:PROPERTIES:`/`:LOGBOOK:` drawer."
  #{:conflict :unreadable :unterminated-drawer})

(defn guard!
  "Run thunk `f`, translating a known structured ex-info (see
  `guarded-codes`) into the standard error envelope. Other exceptions
  propagate."
  [opts f]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo e
      (let [{:keys [code file line]} (ex-data e)]
        (if (contains? guarded-codes code)
          (out/emit-error opts (cond-> {:code (name code) :message (ex-message e)}
                                 file (assoc :file file)
                                 line (assoc :line line)))
          (throw e))))))

(def guard-write!
  "Alias of [[guard!]] kept for write call sites that predate the
  generalisation to also cover read errors."
  guard!)

(defn- id-match->wire [task]
  {:id (parser/get-task-id task)
   :summary (:summary task)
   :file (:source-path task)})

(defn resolve-required-id
  "Resolve an id argument, accepting full UUIDs or unique prefixes.

  `resolver` is usually `task/find-by-id-or-prefix`; top-level-only
  commands pass `task/find-top-level-by-id-or-prefix` to preserve their
  validation semantics. On failure this emits the standard error
  envelope and exits through `out/emit-error`, matching the rest of the
  command namespace's short-circuit style."
  ([tasks id opts]
   (resolve-required-id tasks id opts task/find-by-id-or-prefix))
  ([tasks id opts resolver]
   (let [resolved (resolver tasks id)]
     (case (-> resolved keys first)
       :match
       (:match resolved)

       :ambiguous
       (let [matches (:ambiguous resolved)]
         (out/emit-error opts
                         {:code "ambiguous-id"
                          :message (str "Task id prefix '" id "' is ambiguous ("
                                        (count matches) " matches)")
                          :details {:id id
                                    :matches (mapv id-match->wire matches)}}))

       (out/emit-error opts
                       {:code "unknown-task"
                        :message (str "No task with :CUSTOM_ID: " id)
                        :details {:id id}})))))

(defn resolve-required-top-level-id [tasks id opts]
  (resolve-required-id tasks id opts task/find-top-level-by-id-or-prefix))

(defn coerce-seq
  "Normalise an option that may be missing, scalar, or already a vector
  to a vector. The top-level dispatch :coerce coerces repeated flags
  into vectors but a single occurrence may slip through as a scalar."
  [v]
  (cond
    (nil? v) []
    (sequential? v) (vec v)
    :else [v]))

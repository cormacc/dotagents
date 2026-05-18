(ns org-tasks.output
  "Envelope formatting for `ot` command output.

  Every command emits a result via [[emit-result]] or [[emit-error]],
  which renders one of three formats:

    * `:text` (default, human-readable; ANSI styling under `--no-color`)
    * `:json` (the contract documented in
      `skills/org-tasks/scripts/docs/contract.md`)
    * `:edn`  (the same envelope rendered as EDN)

  Result maps may carry namespaced keys (e.g. `:text/lines`) that are
  consumed by the text renderer but stripped before JSON/EDN encoding
  so they never leak into the machine contract.

  Exit codes are listed in the contract:
    0 success, 1 domain error, 2 argument/option parse failure,
    64 dry-run would-modify."
  (:require [cheshire.core :as json]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.walk :as walk]))

(def schema "org-tasks/v1")

(def ^:dynamic *exit-fn*
  "Indirection so tests can inspect intended exit codes without
  terminating the JVM."
  (fn [code] (System/exit code)))

(defn- fmt [opts]
  (or (:format opts) :text))

(defn- strip-internal-keys
  "Recursively drop namespaced keys (e.g. `:text/lines`) from maps so
  internal text-rendering hints never end up in JSON/EDN output."
  [x]
  (walk/postwalk
    (fn [v]
      (if (map? v)
        (into {} (remove (fn [[k _]] (and (keyword? k) (namespace k))) v))
        v))
    x))

(defn- success-envelope [result warnings]
  {:ok       true
   :schema   schema
   :result   result
   :warnings (vec warnings)})

(defn- error-envelope [error]
  {:ok     false
   :schema schema
   :error  error})

(defn- pp-edn [m]
  (with-out-str (pprint/pprint m)))

(defn- pp-json [m]
  (json/generate-string m {:pretty true}))

(defn- render-text-result [result]
  ;; Per-command renderers populate :text/lines for richer output.
  ;; The default falls back to a generic pprint dump.
  (or (some-> result :text/lines (->> (remove nil?) (str/join "\n")))
      (str/trim-newline (pp-edn (dissoc result :text/lines)))))

(defn- render-text-error [{:keys [code message file line]}]
  (let [loc (cond
              (and file line) (str " (" file ":" line ")")
              file            (str " (" file ")")
              :else           "")]
    (str "ot: " (or code "error") ": " message loc)))

(defn emit-result
  "Emit a success envelope. `result` is the per-command payload.
  Optional `:warnings` is a sequence of warning maps."
  ([opts result]
   (emit-result opts result nil))
  ([opts result warnings]
   (let [envelope (success-envelope result warnings)]
     (case (fmt opts)
       :json (println (pp-json (strip-internal-keys envelope)))
       :edn  (println (str/trim-newline (pp-edn (strip-internal-keys envelope))))
       :text (do (println (render-text-result result))
                 (doseq [w warnings]
                   (binding [*out* *err*]
                     (println "warning:" (:message w)))))))))

(defn emit-error
  "Emit a failure envelope and exit with `:exit` (default 1).

  `error` is a map with at minimum `:code` and `:message`; `:file`,
  `:line`, and `:details` are surfaced when present."
  ([opts error]
   (emit-error opts error 1))
  ([opts error exit-code]
   (let [envelope (error-envelope error)]
     (case (fmt opts)
       :json (binding [*out* *err*] (println (pp-json (strip-internal-keys envelope))))
       :edn  (binding [*out* *err*] (println (str/trim-newline (pp-edn (strip-internal-keys envelope)))))
       :text (binding [*out* *err*] (println (render-text-error error))))
     (*exit-fn* exit-code))))

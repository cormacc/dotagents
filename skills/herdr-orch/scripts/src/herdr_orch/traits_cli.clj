(ns herdr-orch.traits-cli
  "Standalone stdin/file adapter for the shared trait interpolator."
  (:require [babashka.fs :as fs]
            [herdr-orch.core :as core]
            [herdr-orch.traits :as traits]))

(def usage
  (str "Usage: traits [--file PATH] [--layer SOURCE=DIR ...] [--plain]\n"
       "\n"
       "Reads text from stdin unless --file is supplied. JSON mode emits a\n"
       "herdr-orch/v1 envelope; --plain emits only transformed text.\n"))

(defn- missing-value! [flag]
  (throw (ex-info (str "missing value for " flag) {:flag flag})))

(defn- parse-layer [value]
  (let [at (.indexOf ^String value "=")]
    (when (or (not (pos? at)) (= at (dec (count value))))
      (throw (ex-info (str "invalid --layer `" value "`; expected <source>=<dir>")
                      {:value value})))
    {:source (subs value 0 at)
     :directory (subs value (inc at))}))

(defn- parse-args [args]
  (loop [remaining (seq args)
         options {:file nil :layers [] :plain? false :help? false}]
    (if-not remaining
      options
      (let [[arg value & more] remaining]
        (case arg
          "--" (recur (next remaining) options)
          "--help" (recur (next remaining) (assoc options :help? true))
          "-h" (recur (next remaining) (assoc options :help? true))
          "--plain" (recur (next remaining) (assoc options :plain? true))
          "--file" (do
                     (when-not value (missing-value! arg))
                     (when (:file options)
                       (throw (ex-info "--file may be supplied only once" {:flag arg})))
                     (recur more (assoc options :file value)))
          "--layer" (do
                      (when-not value (missing-value! arg))
                      (recur more (update options :layers conj (parse-layer value))))
          (throw (ex-info (str "unknown argument `" arg "`") {:argument arg})))))))

(defn run
  "Interpolates one CLI request and returns the shared result map."
  [{:keys [file layers]}]
  (traits/interpolate {:text (if file (slurp file) (slurp *in*))
                       :directories layers
                       :exists? #(fs/exists? %)
                       :read-text slurp}))

(defn -main [& args]
  (try
    (let [{:keys [plain? help?] :as options} (parse-args args)]
      (if help?
        (print usage)
        (let [result (run options)]
          (if plain?
            (print (:text result))
            (println (core/json-envelope true result))))))
    (catch Exception e
      (binding [*out* *err*]
        (println (core/json-envelope false {:message (ex-message e)
                                            :data (or (ex-data e) {})})))
      (System/exit 1))))

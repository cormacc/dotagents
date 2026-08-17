#!/usr/bin/env bb
(require '[cheshire.core :as json]
         '[clojure.java.io :as io]
         '[clojure.string :as str])
(import '[java.time Instant])

(def usage
  (str "Usage: bb prompt-cache-ttl [--root DIR] [--sessions N] [--scan-limit N]\n"
       "\n"
       "Compare logged five-minute Anthropic cache costs with one-hour no-rescue\n"
       "and optimistic-resume bounds. Output is JSON; prompt content is omitted.\n"))

(defn require-value [flag value]
  (when (or (nil? value) (str/starts-with? value "--"))
    (throw (ex-info (str flag " requires a value") {:flag flag})))
  value)

(defn parse-args [args]
  (loop [opts {:root (str (or (System/getenv "PI_CODING_AGENT_DIR")
                              (str (System/getProperty "user.home") "/.pi/agent"))
                          "/sessions")
               :sessions 20
               :scan-limit 200}
         xs args]
    (if (empty? xs)
      opts
      (let [[flag value & more] xs]
        (cond
          (contains? #{"--help" "-h"} flag)
          (recur (assoc opts :help? true) (rest xs))

          (= "--root" flag)
          (recur (assoc opts :root (require-value flag value)) more)

          (= "--sessions" flag)
          (recur (assoc opts :sessions (Long/parseLong (require-value flag value))) more)

          (= "--scan-limit" flag)
          (recur (assoc opts :scan-limit (Long/parseLong (require-value flag value))) more)

          :else
          (throw (ex-info (str "Unknown argument: " flag) {:flag flag})))))))

(def opts (parse-args *command-line-args*))
(when (:help? opts)
  (print usage)
  (flush)
  (System/exit 0))
(doseq [key [:sessions :scan-limit]]
  (when-not (pos? (get opts key))
    (throw (ex-info (str "--" (name key) " must be positive") {key (get opts key)}))))
(when-not (.isDirectory (io/file (:root opts)))
  (throw (ex-info (str "Session root is not a directory: " (:root opts)) {:root (:root opts)})))
(defn instant-ms [value]
  (try (.toEpochMilli (Instant/parse value)) (catch Exception _ nil)))
(defn json-lines [file]
  (with-open [reader (io/reader file)]
    (doall
     (keep (fn [line]
             (try (json/parse-string line true)
                  (catch Exception _ nil)))
           (line-seq reader)))))
(defn assistant-row [file entry]
  (let [message (:message entry)
        usage (:usage message)]
    (when (and (= "message" (:type entry))
               (= "assistant" (:role message))
               (= "anthropic" (:provider message))
               usage
               (instant-ms (:timestamp entry)))
      {:file (.getAbsolutePath file)
       :file-name (.getName file)
       :timestamp (:timestamp entry)
       :time-ms (instant-ms (:timestamp entry))
       :model (:model message)
       :input (long (or (:input usage) 0))
       :output (long (or (:output usage) 0))
       :cache-read (long (or (:cacheRead usage) 0))
       :cache-write (long (or (:cacheWrite usage) 0))
       :cache-write-1h (long (or (:cacheWrite1h usage) 0))
       :cost (or (:cost usage) {})})))
(defn session-data [file]
  (let [entries (json-lines file)
        rows (->> entries (keep #(assistant-row file %)) (sort-by :time-ms) vec)
        compactions (->> entries
                         (filter #(str/includes? (str/lower-case (str (:type %))) "compaction"))
                         (keep #(instant-ms (:timestamp %)))
                         sort vec)]
    (when (seq rows)
      {:file (.getAbsolutePath file)
       :file-name (.getName file)
       :rows rows
       :compactions compactions
       :last-ms (:time-ms (last rows))})))
(def files (->> (file-seq (io/file (:root opts)))
                (filter #(.isFile %))
                (filter #(str/ends-with? (.getName %) ".jsonl"))
                (sort-by #(.lastModified %) >)
                (take (:scan-limit opts))))
(def sessions (->> files
                   (keep session-data)
                   (sort-by :last-ms >)
                   (take (:sessions opts))
                   vec))

(defn between? [x lo hi] (< lo x hi))
(defn compaction-between? [compactions from-ms to-ms]
  (boolean (some #(between? % from-ms to-ms) compactions)))
(defn base-input-rate [{:keys [input cache-read cache-write cost]}]
  (cond
    (and (pos? input) (pos? (double (or (:input cost) 0))))
    (/ (double (:input cost)) input)

    (and (pos? cache-read) (pos? (double (or (:cacheRead cost) 0))))
    (/ (double (:cacheRead cost)) cache-read 0.1)

    (and (pos? cache-write) (pos? (double (or (:cacheWrite cost) 0))))
    (/ (double (:cacheWrite cost)) cache-write 1.25)

    :else nil))
(defn sum [xs] (reduce + 0.0 xs))
(defn analyse-model [rows compactions]
  (loop [remaining (sort-by :time-ms rows)
         previous nil
         result []]
    (if-let [row (first remaining)]
      (let [rate (base-input-rate row)
            gap-min (when previous (/ (- (:time-ms row) (:time-ms previous)) 60000.0))
            previous-prefix (when previous (+ (:cache-read previous) (:cache-write previous)))
            candidate? (and rate previous gap-min
                            (> gap-min 5.0) (<= gap-min 60.0)
                            (zero? (:cache-read row))
                            (pos? (:cache-write row))
                            (pos? previous-prefix)
                            (>= (:cache-write row) previous-prefix)
                            (not (compaction-between? compactions (:time-ms previous) (:time-ms row))))
            rescued (if candidate? (min previous-prefix (:cache-write row)) 0)
            actual-cache (double (+ (or (get-in row [:cost :cacheRead]) 0)
                                    (or (get-in row [:cost :cacheWrite]) 0)))
            no-rescue-cache (when rate (* rate (+ (* 0.1 (:cache-read row))
                                                  (* 2.0 (:cache-write row)))))
            optimistic-cache (when rate (* rate (+ (* 0.1 (+ (:cache-read row) rescued))
                                                   (* 2.0 (- (:cache-write row) rescued)))))
            actual-total (double (or (get-in row [:cost :total]) 0))]
        (recur (rest remaining) row
               (conj result (assoc row
                                   :gap-min gap-min
                                   :candidate-resume candidate?
                                   :rescued-tokens rescued
                                   :actual-cache-cost actual-cache
                                   :actual-total-cost actual-total
                                   :long-no-rescue-cache-cost no-rescue-cache
                                   :long-optimistic-cache-cost optimistic-cache))))
      result)))
(defn analyse-session [{:keys [file file-name rows compactions]}]
  (let [analysed (mapcat #(analyse-model (val %) compactions) (group-by :model rows))
        short-only? (every? #(zero? (:cache-write-1h %)) rows)
        known (filter :long-no-rescue-cache-cost analysed)
        actual-total (sum (map :actual-total-cost known))
        actual-cache (sum (map :actual-cache-cost known))
        long-no-rescue-cache (sum (map :long-no-rescue-cache-cost known))
        long-optimistic-cache (sum (map :long-optimistic-cache-cost known))
        unchanged (- actual-total actual-cache)
        long-no-rescue-total (+ unchanged long-no-rescue-cache)
        long-optimistic-total (+ unchanged long-optimistic-cache)]
    {:file file
     :fileName file-name
     :start (:timestamp (first rows))
     :end (:timestamp (last rows))
     :models (sort (set (map :model rows)))
     :turns (count rows)
     :shortOnly short-only?
     :pricedTurns (count known)
     :candidateResumes (count (filter :candidate-resume analysed))
     :candidateRescuedTokens (reduce + 0 (map :rescued-tokens analysed))
     :actualFiveMinuteTotalUsd actual-total
     :hypotheticalOneHourNoRescueTotalUsd long-no-rescue-total
     :hypotheticalOneHourOptimisticTotalUsd long-optimistic-total
     :optimisticDeltaUsd (- long-optimistic-total actual-total)
     :noRescueDeltaUsd (- long-no-rescue-total actual-total)}))
(def reports (mapv analyse-session sessions))
(def short-reports (filterv :shortOnly reports))
(def excluded-long (remove :shortOnly reports))
(def totals
  {:sessions (count short-reports)
   :turns (reduce + 0 (map :turns short-reports))
   :pricedTurns (reduce + 0 (map :pricedTurns short-reports))
   :candidateResumes (reduce + 0 (map :candidateResumes short-reports))
   :candidateRescuedTokens (reduce + 0 (map :candidateRescuedTokens short-reports))
   :actualFiveMinuteTotalUsd (sum (map :actualFiveMinuteTotalUsd short-reports))
   :hypotheticalOneHourNoRescueTotalUsd (sum (map :hypotheticalOneHourNoRescueTotalUsd short-reports))
   :hypotheticalOneHourOptimisticTotalUsd (sum (map :hypotheticalOneHourOptimisticTotalUsd short-reports))})
(def totals (assoc totals
                   :optimisticDeltaUsd (- (:hypotheticalOneHourOptimisticTotalUsd totals)
                                          (:actualFiveMinuteTotalUsd totals))
                   :noRescueDeltaUsd (- (:hypotheticalOneHourNoRescueTotalUsd totals)
                                       (:actualFiveMinuteTotalUsd totals))))
(println (json/generate-string
          {:schema "prompt-cache-ttl-analysis/v1"
           :method {:selection (str "Most recent " (:sessions opts) " session files containing Anthropic responses, from the most recent " (:scan-limit opts) " JSONL files")
                    :actual "Only sessions with cacheWrite1h=0 are compared; their logged five-minute costs are the baseline."
                    :noRescue "Prices every logged cache write at the one-hour 2x rate and assumes no additional hits."
                    :optimistic "For a 5-60 minute same-model gap with zero cache reads, no intervening compaction, and a rewrite at least as large as the prior cacheable prefix, converts that prior prefix from a write to a read. Exact prefix identity is not present in session logs, so this is an optimistic bound."
                    :unchanged "Input, output, and reasoning costs are held constant."}
           :root (:root opts)
           :filesScanned (count files)
           :anthropicSessionsFound (count sessions)
           :shortSessionsCompared (count short-reports)
           :longSessionsExcluded (count excluded-long)
           :totals totals
           :sessions short-reports
           :excludedLongSessions (mapv #(select-keys % [:fileName :start :end :models :turns]) excluded-long)}
          {:pretty true}))

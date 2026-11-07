(require '[cheshire.core :as j] '[babashka.fs :as fs] '[clojure.string :as str])
(defn median [xs] (let [s (vec (sort xs)) c (count s)] (when (pos? c) (if (odd? c) (s (quot c 2)) (/ (+ (s (dec (quot c 2))) (s (quot c 2))) 2.0)))))
(doseq [dir (sort (map str (fs/glob ".tmp/comms-bench" "run-*")))]
  (let [r (j/parse-string (slurp (str dir "/results.json")) true)
        n (:children r)]
    (println (format "N=%d  spawn-ms=%s  recovery exactly-once=%s final=%s" n (pr-str (map :ms (:spawns r))) (get-in r [:recovery :exactly-once]) (get-in r [:recovery :final :reason])))
    (doseq [round (range (* 2 (:rounds r))) :let [s (filter #(= round (:round %)) (:samples r)) arm (:arm (first s))]]
      (println (format "  round %2d arm %4d  median %5.0f ms  min %4d max %4d  herdr-calls/sample median %s  mismatches %d"
                       round arm (double (median (map :latency s))) (apply min (map :latency s)) (apply max (map :latency s))
                       (median (map :herdr-calls s)) (count (remove #(= (:task %) (:captured-task %)) s)))))
    (doseq [arm (:arms r) :let [s (filter #(= arm (:arm %)) (:samples r))]]
      (println (format "  arm %4d overall: %d samples, median %.0f ms, p90 %d ms, median herdr calls %s" arm (count s) (double (median (map :latency s)))
                       (nth (vec (sort (map :latency s))) (int (* 0.9 (dec (count s))))) (median (map :herdr-calls s)))))))

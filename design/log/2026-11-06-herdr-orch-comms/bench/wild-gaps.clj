;; Real ledger: for each captured item, gap = ledger captured-at minus result item mtime.
;; This is the parent-side publish-to-capture delay in real sessions (pipe + parent decision).
(require '[babashka.fs :as fs] '[cheshire.core :as j] '[clojure.string :as str])
(defn inst-ms [s] (.toEpochMilli (java.time.Instant/parse s)))
(def rows
  (for [f (fs/glob ".tmp/herdr-orch/ledger" "*.json")
        :let [e (j/parse-string (slurp (str f)) true)]
        [i item] (map-indexed vector (:items e))
        :let [path (if (zero? i) (:result e) (str (:result e) "." (inc i)))]
        :when (and (:captured-at item) (fs/exists? path))
        :let [gap (- (inst-ms (:captured-at item)) (.toMillis (fs/last-modified-time path)))]
        :when (>= gap 0)]
    {:task (subs (:task e) 0 8) :status (get-in e [:envelope :status] (:status e)) :persona (:persona e) :policy (:waiting-policy e) :gap-s (/ gap 1000.0) :captured (:captured-at item)}))
(def sorted (sort-by :captured rows))
(println "n" (count sorted))
(doseq [r (take-last 30 sorted)] (println (format "%s %-8s %-10s %-12s %8.1f s  %s" (:task r) (:status r) (str (:persona r)) (str (:policy r)) (:gap-s r) (:captured r))))
(defn median [xs] (let [s (vec (sort xs)) c (count s)] (if (odd? c) (s (quot c 2)) (/ (+ (s (dec (quot c 2))) (s (quot c 2))) 2.0))))
(println "median gap s" (median (map :gap-s sorted)) "p25" (nth (vec (sort (map :gap-s sorted))) (int (* 0.25 (count sorted)))) "p75" (nth (vec (sort (map :gap-s sorted))) (int (* 0.75 (count sorted)))))
(println "under 2 s:" (count (filter #(< (:gap-s %) 2) sorted)) " 2-60 s:" (count (filter #(<= 2 (:gap-s %) 60) sorted)) " over 60 s:" (count (filter #(> (:gap-s %) 60) sorted)))

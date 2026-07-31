(ns segwin-acceptance
  "Hidden acceptance suite for the segwin benchmark (round 3, performance axis).

  Never seeded into an executor cell and never named in ASSIGNMENT.md. Run via
  the sibling `score3` wrapper, which puts a cell's src/ on the classpath:

    ./score3 /path/to/r3-cell-i cell-i [--edn out.edn]

  Two kinds of case:

  * correctness — small deterministic cases plus randomised cross-checks against
    an independent brute-force implementation in this file.
  * performance — the 30,000 / 30,000 workload from ASSIGNMENT.md §5 under a
    different seed, checked for BOTH result correctness at scale and completion
    inside the 60s budget, reported as two separate cases so a fast-but-wrong
    solution is distinguishable from a correct-but-slow one.

  A thrown exception is one errored case and never aborts the run. The timed case
  is hard-aborted at 100s so scoring a quadratic solution stays bounded."
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [segwin :as sw]))

;; ---------------------------------------------------------------------------
;; Independent brute force (obviously correct; only used at small sizes)

(defn- brute [ops]
  (let [[[_ raw] & rest-ops] ops
        pts (vec (sort (distinct raw)))]
    (loop [os rest-ops vals (into {} (map (fn [t] [t 0]) pts)) out []]
      (if-let [[op a b v] (first os)]
        (case op
          :add (recur (next os)
                      (reduce (fn [m t] (if (<= a t b) (update m t + v) m)) vals (keys vals))
                      out)
          :sum (recur (next os) vals
                      (conj out (reduce (fn [acc t] (if (<= a t b) (+ acc (vals t)) acc)) 0 (keys vals))))
          :max (recur (next os) vals
                      (conj out (let [in (keep (fn [t] (when (<= a t b) (vals t))) (keys vals))]
                                  (when (seq in) (apply max in))))))
        out))))

;; Same generator shape as the visible test/workload.clj, different seed.
(defn- gen [n-points n-ops seed]
  (let [r (java.util.Random. seed)
        pts (vec (distinct (repeatedly n-points #(.nextInt r 1000000))))]
    (into [[:points pts]]
          (repeatedly n-ops
                      (fn []
                        (let [a (.nextInt r 1000000) b (.nextInt r 1000000)
                              lo (min a b) hi (max a b)]
                          (case (.nextInt r 3)
                            0 [:add lo hi (- (.nextInt r 21) 10)]
                            1 [:sum lo hi]
                            2 [:max lo hi])))))))

(def ^:private perf-seed 20260731)
(def ^:private perf-points 30000)
(def ^:private perf-ops 30000)
(def ^:private budget-ms 60000)
(def ^:private abort-ms 100000)
;; Precomputed from the validated reference implementation for perf-seed.
(def ^:private perf-expected {:count 20003 :checksum -21366938338})

;; ---------------------------------------------------------------------------
;; Deterministic correctness cases

(def cases
  [{:id :b-01-assignment-example :cat :basics
    :ops [[:points [10 20 30]] [:add 10 20 5] [:sum 10 30] [:max 25 40] [:max 31 40]]
    :expect [10 0 nil]}
   {:id :b-02-all-zero-initially :cat :basics
    :ops [[:points [4 8]] [:sum 1 9] [:max 1 9]] :expect [0 0]}
   {:id :b-03-single-point :cat :basics
    :ops [[:points [7]] [:add 7 7 3] [:sum 7 7] [:max 7 7]] :expect [3 3]}
   {:id :b-04-duplicate-points-are-one :cat :basics
    :ops [[:points [5 5 5]] [:add 1 9 2] [:sum 1 9] [:max 1 9]] :expect [2 2]}
   {:id :b-05-unsorted-points :cat :basics
    :ops [[:points [30 10 20]] [:add 10 20 1] [:sum 1 100]] :expect [2]}

   {:id :r-01-partial-overlap-low :cat :ranges
    :ops [[:points [1 5 9]] [:add 1 5 10] [:sum 1 9]] :expect [20]}
   {:id :r-02-partial-overlap-high :cat :ranges
    :ops [[:points [1 5 9]] [:add 5 9 10] [:sum 1 9]] :expect [20]}
   {:id :r-03-add-touches-nothing :cat :ranges
    :ops [[:points [1 5 9]] [:add 6 8 100] [:sum 1 9] [:max 1 9]] :expect [0 0]}
   {:id :r-04-query-narrower-than-points :cat :ranges
    :ops [[:points [1 5 9]] [:add 1 9 4] [:sum 4 6]] :expect [4]}
   {:id :r-05-query-outside-all-points :cat :ranges
    :ops [[:points [1 5 9]] [:add 1 9 4] [:sum 10 20] [:max 10 20]] :expect [0 nil]}
   {:id :r-06-endpoints-inclusive :cat :ranges
    :ops [[:points [2 4]] [:add 2 4 1] [:sum 2 2] [:sum 4 4]] :expect [1 1]}
   {:id :r-07-degenerate-range :cat :ranges
    :ops [[:points [3 6]] [:add 6 6 9] [:sum 6 6] [:max 3 3]] :expect [9 0]}
   {:id :r-08-query-between-points :cat :ranges
    :ops [[:points [10 30]] [:add 10 30 2] [:sum 15 25] [:max 15 25]] :expect [0 nil]}

   {:id :a-01-adds-accumulate :cat :accumulation
    :ops [[:points [5]] [:add 1 10 3] [:add 4 6 4] [:sum 1 10]] :expect [7]}
   {:id :a-02-interleaved-queries :cat :accumulation
    :ops [[:points [1 2]] [:add 1 1 5] [:sum 1 2] [:add 2 2 7] [:sum 1 2] [:max 1 2]]
    :expect [5 12 7]}
   {:id :a-03-negative-add :cat :accumulation
    :ops [[:points [1 2]] [:add 1 2 5] [:add 1 1 -8] [:sum 1 2] [:max 1 2]]
    :expect [2 5]}
   {:id :a-04-max-is-not-running-peak :cat :accumulation
    :ops [[:points [1]] [:add 1 1 10] [:add 1 1 -10] [:max 1 1]] :expect [0]}
   {:id :a-05-all-negative-max :cat :accumulation
    :ops [[:points [1 2]] [:add 1 2 -3] [:max 1 2] [:sum 1 2]] :expect [-3 -6]}
   {:id :a-06-sum-negative :cat :accumulation
    :ops [[:points [1 2 3]] [:add 1 3 -2] [:sum 1 3]] :expect [-6]}
   {:id :a-07-many-sequential-adds :cat :accumulation
    :ops (into [[:points [1 2 3 4]]]
               (concat (repeat 25 [:add 1 4 2]) [[:sum 1 4] [:max 1 4]]))
    :expect [200 50]}

   {:id :m-01-max-picks-largest :cat :max
    :ops [[:points [1 2 3]] [:add 1 1 5] [:add 2 2 9] [:add 3 3 7] [:max 1 3]] :expect [9]}
   {:id :m-02-max-within-subrange :cat :max
    :ops [[:points [1 2 3]] [:add 1 1 5] [:add 2 2 9] [:add 3 3 7] [:max 3 3]] :expect [7]}
   {:id :m-03-max-empty-is-nil :cat :max
    :ops [[:points [1]] [:max 2 3]] :expect [nil]}
   {:id :m-04-max-zero-not-nil :cat :max
    :ops [[:points [1]] [:max 1 1]] :expect [0]}
   {:id :m-05-max-after-partial-add :cat :max
    :ops [[:points [1 2 3 4]] [:add 1 2 6] [:max 1 4] [:max 3 4]] :expect [6 0]}

   {:id :o-01-no-queries :cat :output
    :ops [[:points [1]] [:add 1 1 1]] :expect []}
   {:id :o-02-only-queries :cat :output
    :ops [[:points [1]] [:sum 1 1] [:max 1 1] [:sum 1 1]] :expect [0 0 0]}
   {:id :o-03-query-order-preserved :cat :output
    :ops [[:points [1 2]] [:add 2 2 4] [:max 1 2] [:sum 1 2] [:max 1 1]] :expect [4 4 0]}
   {:id :o-04-empty-points :cat :output
    :ops [[:points []] [:add 1 9 5] [:sum 1 9] [:max 1 9]] :expect [0 nil]}])

;; ---------------------------------------------------------------------------
;; Runner

(defn- truncate [x]
  (let [s (pr-str x)]
    (if (> (count s) 220) (str (subs s 0 220) "…") s)))

(defn- run-deterministic [{:keys [id cat ops expect]}]
  (try
    (let [got (sw/run-ops ops)]
      (if (= expect (vec got))
        {:id id :cat cat :status :pass}
        {:id id :cat cat :status :fail
         :expected (truncate expect) :actual (truncate got)}))
    (catch Throwable t
      {:id id :cat cat :status :error
       :error (str (.getSimpleName (class t)) ": " (ex-message t))})))

(defn- run-random [seed n-points n-ops]
  (let [id (keyword (str "x-brute-agreement-seed-" seed))]
    (try
      (let [ops (gen n-points n-ops seed)
            want (brute ops)
            got (vec (sw/run-ops ops))]
        (if (= want got)
          {:id id :cat :randomised :status :pass}
          (let [i (first (keep-indexed (fn [i [a b]] (when (not= a b) i))
                                       (map vector want got)))]
            {:id id :cat :randomised :status :fail
             :expected (truncate {:first-divergence-index i
                                  :want (nth want i nil) :n-results (count want)})
             :actual (truncate {:got (nth got i nil) :n-results (count got)})})))
      (catch Throwable t
        {:id id :cat :randomised :status :error
         :error (str (.getSimpleName (class t)) ": " (ex-message t))}))))

(defn- run-performance []
  (let [ops (gen perf-points perf-ops perf-seed)
        fut (future
              (let [t0 (System/nanoTime)
                    res (sw/run-ops ops)
                    ms (/ (- (System/nanoTime) t0) 1e6)]
                {:ms ms
                 :count (count res)
                 :checksum (reduce (fn [a x] (unchecked-add a (long (or x 0)))) 0 res)}))
        ;; deref re-throws whatever the future threw, so this must be guarded:
        ;; an unimplemented or throwing run-ops must yield errored cases, never
        ;; abort the whole run.
        outcome (try (deref fut abort-ms ::timeout)
                     (catch Throwable t {::threw t}))]
    (cond
      (and (map? outcome) (contains? outcome ::threw))
      (let [t (::threw outcome)
            msg (str (.getSimpleName (class t)) ": " (ex-message t))]
        [{:id :perf-01-correct-at-scale :cat :performance :status :error :error msg}
         {:id :perf-02-within-budget :cat :performance :status :error :error msg}])

      (= ::timeout outcome)
      (do (future-cancel fut)
          [{:id :perf-01-correct-at-scale :cat :performance :status :error
            :error (str "aborted: exceeded hard limit " abort-ms "ms")}
           {:id :perf-02-within-budget :cat :performance :status :fail
            :expected (str "< " budget-ms " ms")
            :actual (str "did not finish within hard limit " abort-ms " ms")}])

      :else
      (let [{:keys [ms count checksum]} outcome
            correct? (and (= count (:count perf-expected))
                          (= checksum (:checksum perf-expected)))]
        [(if correct?
           {:id :perf-01-correct-at-scale :cat :performance :status :pass}
           {:id :perf-01-correct-at-scale :cat :performance :status :fail
            :expected (truncate perf-expected)
            :actual (truncate {:count count :checksum checksum})})
         (if (< ms budget-ms)
           {:id :perf-02-within-budget :cat :performance :status :pass
            :note (format "%.0f ms" ms)}
           {:id :perf-02-within-budget :cat :performance :status :fail
            :expected (str "< " budget-ms " ms")
            :actual (format "%.0f ms" ms)})]))))

(defn- summarize [label results]
  (let [by-status (frequencies (map :status results))
        total (count results)
        passed (get by-status :pass 0)]
    {:label label
     :total total
     :passed passed
     :failed (get by-status :fail 0)
     :errored (get by-status :error 0)
     :pass-rate (/ (Math/round (* 1000.0 (/ passed (double total)))) 1000.0)
     :by-category (into (sorted-map)
                        (for [[cat rs] (group-by :cat results)]
                          [cat {:passed (count (filter #(= :pass (:status %)) rs))
                                :total (count rs)}]))
     :perf (into {} (for [r results
                          :when (= :performance (:cat r))]
                      [(:id r) (or (:note r) (:actual r) (:error r) "pass")]))
     :not-passed (mapv :id (remove #(= :pass (:status %)) results))}))

(defn -main [& args]
  (let [positional (remove #(str/starts-with? % "--") args)
        label (or (first positional) "unlabelled")
        edn-path (second (drop-while #(not= "--edn" %) args))
        results (vec (concat (map run-deterministic cases)
                             [(run-random 101 60 60)
                              (run-random 202 200 200)
                              (run-random 303 12 400)]
                             (run-performance)))
        summary (summarize label results)]
    (println (str "=== " label " ==="))
    (doseq [{:keys [id status expected actual error note]} results]
      (println (format "%-7s %s%s" (str/upper-case (name status)) (name id)
                       (if note (str "  (" note ")") "")))
      (when expected (println "        expected:" expected))
      (when actual (println "        actual:  " actual))
      (when error (println "        error:   " error)))
    (println)
    (println (format "%d/%d passed (%.1f%%)  failed=%d errored=%d"
                     (:passed summary) (:total summary)
                     (* 100.0 (:pass-rate summary))
                     (:failed summary) (:errored summary)))
    (doseq [[cat {:keys [passed total]}] (:by-category summary)]
      (println (format "  %-12s %d/%d" (name cat) passed total)))
    (when edn-path
      (spit edn-path (with-out-str (pp/pprint summary)))
      (println "\nwrote" edn-path))
    (System/exit (if (= (:passed summary) (:total summary)) 0 1))))

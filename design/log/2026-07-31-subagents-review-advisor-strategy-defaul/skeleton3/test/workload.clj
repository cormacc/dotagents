(ns workload
  "Workload generator — the same one used for scoring, with a different seed.
  Provided so you can measure your own performance; see ASSIGNMENT.md §5.")

(defn gen
  "Deterministically generate [[:points [...]] & ops] with `n-points` points and
  `n-ops` operations, using `seed`."
  [n-points n-ops seed]
  (let [r (java.util.Random. seed)
        pts (vec (distinct (repeatedly n-points #(.nextInt r 1000000))))]
    (into [[:points pts]]
          (repeatedly n-ops
                      (fn []
                        (let [a (.nextInt r 1000000)
                              b (.nextInt r 1000000)
                              lo (min a b)
                              hi (max a b)]
                          (case (.nextInt r 3)
                            0 [:add lo hi (- (.nextInt r 21) 10)]
                            1 [:sum lo hi]
                            2 [:max lo hi])))))))

(def scoring-size
  "The size used for scoring: 30,000 points and 30,000 operations."
  {:n-points 30000 :n-ops 30000})

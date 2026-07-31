(ns segwin
  "A range-update / range-query engine over a fixed set of timestamps.

  Implement `run-ops` per the contract in ASSIGNMENT.md. The namespace, the
  function name, its arity, and the data shapes are fixed: do not rename them, do
  not change the namespace, and do not move this file. Helper functions may be
  added freely.

  Note the performance requirement in ASSIGNMENT.md §5: per-operation cost must be
  logarithmic in the number of points.")

(defn run-ops
  "Apply `ops` in order and return a vector of results, one per query op.

  `ops` begins with `[:points [t ...]]` declaring the existing timestamps, each
  starting at value 0. Subsequent ops are:

    [:add t0 t1 v]  add v to every existing point in the inclusive range
    [:sum t0 t1]    sum of values of existing points in the inclusive range
    [:max t0 t1]    maximum value among existing points in the inclusive range

  Only `:sum` and `:max` contribute to the output. An empty range yields 0 for
  `:sum` and nil for `:max`. See ASSIGNMENT.md for full semantics."
  [ops]
  (throw (ex-info "run-ops not implemented" {:op-count (count ops)})))

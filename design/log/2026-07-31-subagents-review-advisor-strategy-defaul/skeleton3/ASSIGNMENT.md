# Assignment: segwin — a range-update / range-query engine (Babashka)

Implement `run-ops` in `src/segwin.clj`. The namespace, the function name, its
arity, and the data shapes below are fixed by the skeleton — do not rename or
relocate them. Add helpers freely.

**This task has a hard performance requirement. Read §5 before you start coding —
it constrains your choice of data structure, and a correct but naive solution will
not pass.**

```clojure
(segwin/run-ops ops) ; => vector of results, one per query op, in order
```

## 1. Input

`ops` is a vector. The **first** element is always `[:points [t ...]]`, declaring
the timestamps that exist. Timestamps are integers, in arbitrary order, possibly
with duplicates — duplicates denote the same single point. Every point starts with
value `0`.

The remaining elements are operations applied in order:

| Op | Meaning |
|---|---|
| `[:add t0 t1 v]` | add integer `v` to the value of **every existing point** whose timestamp is in `[t0, t1]` |
| `[:sum t0 t1]` | query: sum of the values of existing points in `[t0, t1]` |
| `[:max t0 t1]` | query: maximum value among existing points in `[t0, t1]` |

Ranges are **inclusive** at both ends. `t0 <= t1` always holds.

## 2. Output

A vector containing one result per **query** op (`:sum` and `:max`), in the order
those queries appear. `:add` contributes nothing to the output.

- `:sum` over a range containing no existing points is `0`.
- `:max` over a range containing no existing points is `nil`.
- `v` may be negative, so values and sums may be negative and `:max` is a genuine
  maximum, not a running peak.

```clojure
(run-ops [[:points [10 20 30]]
          [:add 10 20 5]      ; points 10 and 20 now 5; point 30 still 0
          [:sum 10 30]        ; => 10
          [:max 25 40]        ; => 0   (point 30 exists, value 0)
          [:max 31 40]])      ; => nil (no points in range)
;; => [10 0 nil]
```

## 3. Semantics notes

- Only timestamps declared in `[:points ...]` ever exist. `:add` silently affects
  nothing outside them, and queries ignore non-existent timestamps entirely.
- Query range endpoints need not coincide with any declared timestamp.
- Adds accumulate: two overlapping `:add` ops both apply.

## 4. Constraints

- Babashka only, no dependencies beyond the Clojure/bb standard library.
- `run-ops` must be a pure function of its argument: no I/O, no global mutable
  state, no `println`. Internal mutation (arrays, transients, `loop`) is expected
  and encouraged.
- Do not add a `deps.edn`, a build step, or extra source paths.

## 5. Performance requirement

Your implementation is scored on a workload of **30,000 points and 30,000
operations** (a mix of `:add`, `:sum` and `:max`) and must complete that workload
in **under 60 seconds** on this machine.

This budget is deliberately generous in absolute terms — babashka is an
interpreter and its constant factor is large. It is not a micro-optimisation
exercise, and you are not expected to shave constants. What it does rule out is
any approach whose cost **per operation** grows with the number of points: 30,000
operations each rescanning 30,000 points is roughly 10^9 element visits and takes
several minutes. Per-operation cost must be logarithmic in the number of points.

Any structure achieving that is acceptable. Both a lazy-propagating segment tree
and the two-Fenwick-tree range-update/range-sum formulation fit comfortably inside
the budget; the obvious map-scan does not.

### Measuring it yourself

`test/workload.clj` contains the same generator used for scoring, so you can check
your own timing:

```
bb bench          # generates 30,000 / 30,000 and prints elapsed ms
```

The scoring workload uses the identical generator and size with a **different
random seed**, so tune your data structure, not your constants. `bb bench` also
prints a checksum, which is useful for spotting a regression while you refactor.

## Verifying

- `bb test` runs the visible smoke tests in `test/segwin_smoke_test.clj`. They are
  a thin happy-path sample, **not** the standard you are judged against.
- `bb bench` checks the performance requirement.

Correctness is judged by a broader hidden suite using inputs other than the
examples shown here, including randomised cross-checks against an independent
brute-force implementation, plus the timed workload above.

## Definition of done

- `src/segwin.clj` implements `run-ops` per the contract.
- `bb test` passes.
- `bb bench` completes in under 60 seconds.
- No stray files beyond your implementation and any tests you add.

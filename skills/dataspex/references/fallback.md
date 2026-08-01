# Dataspex -- fallback cljs forms

Reference data for agents that don't have the pi `dataspex` extension loaded. Each form here is the canonical, token-disciplined equivalent of one `dataspex` op in `SKILL.md`. Mirror the same default projections and length/depth bounds -- paste verbatim and adjust the label / path / `n` as needed.

## Eval idiom

These forms are CLJS. From a JVM nREPL, evaluate them in the connected ClojureScript runtime via shadow-cljs:

```clojure
(require '[shadow.cljs.devtools.api :as shadow])
(shadow/cljs-eval :app "<CLJS-FORM>" {})
;; => {:results [<pr-str>], :out "", :err "", :ns cljs.user}
```

Read forms below wrap their value in `(with-out-str (binding [*print-length* ...] (pr <expr>)))` so the cljs *return value* is an already-bounded string. Reading that string out of `:results[0]` (rather than `:out`) sidesteps two cross-cutting bugs:

1. *Shadow's 1 MB remote writer limit* fires when the cljs return value is too large, regardless of `*print-length*` / `*print-level*` (those don't constrain shadow's own serialisation of the return value).
2. *`:out` is not per-call under concurrent `cljs-eval` against the same build* -- sibling requests share the runtime's `*out*` and each request's `:out` snapshot accumulates bytes printed by other requests. `:results` is per-call.

To unwrap `:results[0]` (which is shadow's pr-str of the cljs string), use `clojure.edn/read-string` on the JVM side. If your agent has no Clojure evaluator at all, this skill can't help -- surface that to the user.

`:app` is the shadow build id. Discover the active build with:

```clojure
(shadow.cljs.devtools.api/active-builds)
;; => #{:app :app-portfolio :test}
```

If more than one build is active, ask the user which to target.

## Forms

### Equivalent of `dataspex op=labels`

```clojure
(with-out-str
  (binding [*print-length* 200 *print-level* 5]
    (pr
      (into []
        (for [label (filter string? (keys @dataspex.core/store))
              :let [e (get @dataspex.core/store label)]]
          {:label label
           :rev (:rev e)
           :idx (:idx e)
           :history-len (count (:history e))
           :val-type (let [v (:val e)
                           t (str (type v))]
                       (cond
                         (map? v) "map"
                         (vector? v) "vector"
                         (set? v) "set"
                         (seq? v) "seq"
                         :else (subs t 0 (min 80 (count t)))))
           :has-ref? (some? (:ref e))})))))
```

### Equivalent of `dataspex op=value`

Bounded snapshot read, optionally navigated by `path`:

```clojure
(with-out-str
  (binding [*print-length* 50 *print-level* 5]
    (pr
      (let [e (get @dataspex.core/store "state")
            v (:val e)
            path [:patient]]           ; optional navigation; [] for whole map
        (if (seq path) (get-in v path) v)))))
```

`(with-out-str (pr ...))` inside the binding is what keeps the cljs return value bounded; without it, a deep app-state atom can blow shadow's 1 MB writer limit before `*print-length*` / `*print-level*` have any effect. Bounds match the `dataspex op=value` defaults (50 / 5). For especially large app-state, reduce them or path-navigate before reading.

Fresh deref of an atom-backed inspectee (skip the snapshot), still bounded:

```clojure
(with-out-str
  (binding [*print-length* 50 *print-level* 5]
    (pr
      (some-> (:ref (get @dataspex.core/store "state")) deref))))
```

### Equivalent of `dataspex op=history` (diffs only)

```clojure
(with-out-str
  (binding [*print-length* 80 *print-level* 6]
    (pr
      (let [label "state"
            audit-label (str label "-audit")
            actual-label (if (contains? @dataspex.core/store audit-label) audit-label label)
            hist (:history (get @dataspex.core/store actual-label))]
        {:label actual-label
         :history (->> hist
                       (take 10)
                       (mapv (fn [h] (select-keys h [:rev :created-at :diff]))))}))))
```

Opt in to full `:val` snapshots only when needed:

```clojure
(with-out-str
  (binding [*print-length* 80 *print-level* 6]
    (pr
      (let [label "state"
            audit-label (str label "-audit")
            actual-label (if (contains? @dataspex.core/store audit-label) audit-label label)]
        {:label actual-label
         :history (->> (:history (get @dataspex.core/store actual-label))
                       (take 10)
                       (mapv (fn [h] (select-keys h [:rev :created-at :diff :val]))))}))))
```

### Equivalent of `dataspex op=track`

```clojure
(let [label "state"
      audit-label (str label "-audit")
      entry (get @dataspex.core/store label)
      ref (:ref entry)]
  (cond
    (nil? entry)
    (throw (ex-info (str "dataspex track: no such label \"" label "\"")
                    {:reason :missing-label :label label}))

    (contains? @dataspex.core/store audit-label)
    (throw (ex-info (str "dataspex track: audit label \"" audit-label
                         "\" already exists; untrack first")
                    {:reason :audit-label-exists :label audit-label}))

    (nil? ref)
    (throw (ex-info (str "dataspex track: label \"" label "\" has no :ref to watch")
                    {:reason :not-watchable :label label}))

    :else
    (do (dataspex.core/inspect audit-label ref
                               {:track-changes? true :history-limit 50})
        {:tracked audit-label :history-limit 50})))
```

### Equivalent of `dataspex op=untrack`

```clojure
(let [label "state"
      audit-label (if (.endsWith label "-audit") label (str label "-audit"))
      was-present? (contains? @dataspex.core/store audit-label)]
  (dataspex.core/uninspect audit-label)
  {:untracked audit-label :was-present? was-present?})
```

### Equivalent of `dataspex op=db_query`

Server-side projection -- only the result set crosses the wire, never the DB:

```clojure
(do
  (require 'cljs.reader 'datascript.core)
  (with-out-str
    (binding [*print-length* 100 *print-level* 5]
      (pr
        (let [db (:val (get @dataspex.core/store "db"))
              q (cljs.reader/read-string "[:find ?e ?id :where [?e :patient/id ?id]]")
              args (cljs.reader/read-string "[]")]
          (apply datascript.core/q q db args))))))
```

### Equivalent of `dataspex op=actions_tail`

`LogInspector` is JS-interop-flavoured -- the underlying log is only reachable via `aget`:

```clojure
(with-out-str
  (binding [*print-length* 80 *print-level* 5]
    (pr
      (let [li (:val (get @dataspex.core/store "Actions"))
            log (aget li "log")]
        (->> log
             (take-last 20)
             (mapv (fn [entry]
                     {:dispatched-at (:dispatched-at entry)
                      :actions (some-> (:actions entry) (.-data))
                      :dispatch-data (:dispatch-data entry)})))))))
```

## Pitfalls

- **Read `:results[0]`, not `:out`.** The forms above use `(with-out-str (pr ...))`, so the bounded string is the cljs *return value* and lives in `:results[0]` (already shadow-pr-str'd; `clojure.edn/read-string` it to unwrap). Reading `:out` is unsafe because concurrent `cljs-eval` calls against the same build share the runtime's `*out*` -- a request can see bytes printed by sibling requests in its `:out` snapshot.
- **Shadow's remote writer has its own 1 MB limit.** Even with tight `*print-length*` / `*print-level*` bindings, shadow tries to serialise the cljs *return value* of the form across the runtime boundary, *outside* the binding scope. A deep atom can fail with `The limit of 1048576 bytes was reached while printing` unless the form's *return value* is itself a bounded string (which is what `(with-out-str (binding [...] (pr ...)))` achieves).
- **`*print-length*` / `*print-level*` are CLJS dynamic vars.** They must appear inside the form passed to `cljs-eval`, not on the JVM side -- the printing happens in the browser runtime.
- **`pr-str` truncation markers.** Long collections truncate with `#` at print- level bounds and `...` at print-length bounds. If you need the structured value, drop the bounds (or path-navigate first) and re-read.
- **No retroactive `:history-limit` widening.** If the parallel `*-audit` label was registered with a tight limit, untrack and re-register with a larger one rather than trying to grow it.

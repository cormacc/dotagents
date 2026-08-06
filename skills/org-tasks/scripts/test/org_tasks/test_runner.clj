(ns org-tasks.test-runner
  "Discover and run every clojure.test suite under scripts/test/.

  Invoked from bb.edn's `test` task and from CI. Exit code is 0 when
  all tests pass, 1 otherwise.

  A namespace opts in to parallel deftest execution with
  `^{:parallel-tests true}` ns metadata; every other namespace still runs
  exactly as before via `clojure.test/test-ns`. Within an opted-in
  namespace, vars marked `^:serial` run first, sequentially, in the calling
  thread (the same thread/bindings a normal run would use); every other var
  in that namespace runs on a bounded thread pool, each with its own bound
  `*report-counters*` ref and a private `*test-out*` buffer that is printed
  whole once that var finishes, so concurrent output never interleaves.
  Pool size defaults to `(.availableProcessors (Runtime/getRuntime))` and is
  overridable via `OT_TEST_PARALLELISM` (unset/blank/unparseable/non-positive
  all fall back to the default, same discipline as herdr-orch' env-seam
  parsers, e.g. `ORCH_POLL_INTERVAL_MS`).

  Any test mutating in-process state (with-redefs, shared atoms/refs outside
  its own isolated counters, global env/fs fixtures not itself
  concurrency-safe) must be marked `^:serial` in an opted-in namespace."
  (:require [babashka.classpath :as cp]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]
            [edamame.core :as edamame])
  (:import [java.io StringWriter]
           [java.util.concurrent Callable Executors ExecutorService]))

(defn- discover-test-namespaces
  "Walk every directory on the classpath, find Clojure files whose
  namespace symbol ends in `-test`, and return them in a stable order."
  []
  (let [roots (->> (cp/split-classpath (cp/get-classpath))
                   (map fs/path)
                   (filter fs/directory?))
        nses  (->> roots
                   (mapcat (fn [root]
                             (->> (fs/glob root "**/*_test.clj")
                                  (map (fn [p]
                                         (let [rel (str (fs/relativize root p))
                                               base (str/replace rel #"\.clj$" "")]
                                           (-> base
                                               (str/replace "/" ".")
                                               (str/replace "_" "-")
                                               symbol)))))))
                   distinct
                   sort)]
    nses))

(defn- pool-size
  "Bounded pool size for the parallel batch: strictly positive env override
  via OT_TEST_PARALLELISM, else `Runtime/availableProcessors`. Same
  unset/blank/unparseable/non-positive -> default discipline used by the
  herdr-orch env-seam parsers (e.g. `parse-poll-interval`)."
  []
  (let [n (some-> (System/getenv "OT_TEST_PARALLELISM") str/trim not-empty parse-long)]
    (if (and n (pos? n)) n (.availableProcessors (Runtime/getRuntime)))))

(defn- test-var? [v] (boolean (:test (meta v))))
(defn- serial-var? [v] (boolean (:serial (meta v))))

(def ^:private unsafe-global-var-mutators
  "The deliberately narrow set of definite global-var mutation forms."
  '#{with-redefs with-redefs-fn alter-var-root})

(def ^:private edamame-reader-options
  ;; `:var`, `:deref`, `:fn`, and `:regex` accept ordinary test syntax; :bb
  ;; selects the Babashka branch of reader conditionals and preserves metadata.
  {:read-cond :allow :features #{:bb}
   :var true :deref true :fn true :regex true})

(defn- namespace-source
  "Return an opted-in namespace's source text and stable display path."
  [ns-obj]
  (let [source-file (:file (meta ns-obj))
        resource-name (str (-> (str (ns-name ns-obj))
                               (str/replace "-" "_")
                               (str/replace "." "/"))
                           ".clj")
        resource (or (some-> source-file io/resource)
                     (io/resource resource-name))]
    (cond
      (and source-file (fs/exists? source-file))
      {:path (str (fs/canonicalize (fs/path source-file)))
       :text (slurp source-file)}

      resource
      {:path (str resource)
       :text (slurp resource)}

      :else
      (throw (ex-info "cannot locate source for parallel-tests namespace"
                      {:ns (ns-name ns-obj) :file source-file
                       :resource resource-name})))))

(defn- unqualified-symbol [x]
  (when (symbol? x) (symbol (name x))))

(defn- deftest-form? [form]
  (and (seq? form) (= 'deftest (unqualified-symbol (first form)))))

(defn- unsafe-call [test-form]
  (some (fn [form]
          (when (and (seq? form)
                     (contains? unsafe-global-var-mutators
                                (unqualified-symbol (first form))))
            form))
        (tree-seq coll? seq (drop 2 test-form))))

(defn- assert-serial-global-var-mutations!
  "Reject untagged tests that definitely mutate global vars before pooling."
  [ns-obj]
  (let [{:keys [path text]} (namespace-source ns-obj)
        forms (edamame/parse-string-all text edamame-reader-options)]
    (doseq [form forms
            :when (deftest-form? form)
            :let [test-name (second form)
                  call (unsafe-call form)]
            :when (and call (not (:serial (meta test-name))))]
      (let [mutator (first call)
            {:keys [row col]} (meta mutator)
            location (str path ":" row ":" col)]
        (throw (ex-info (str "parallel test " (ns-name ns-obj) "/" test-name
                             " at " location " uses " mutator
                             " and must be tagged ^:serial")
                        {:ns (ns-name ns-obj)
                         :test test-name
                         :source path
                         :line row
                         :column col
                         :mutator mutator}))))))

(defn- run-var-isolated
  "Runs one deftest var with its own bound *report-counters* ref and a
  private *test-out* buffer, returning {:output :counts}. Used for the
  parallel batch: each task's report output and counters are entirely its
  own, so no thread ever interleaves with another or races a shared ref."
  [v]
  (let [buf (StringWriter.)
        counters (ref t/*initial-report-counters*)]
    (binding [t/*test-out* buf t/*report-counters* counters]
      (t/test-var v))
    {:output (str buf) :counts @counters}))

(defn- assert-supported!
  "`run-parallel-ns` calls `t/test-var` directly and does not replicate
  `clojure.test/test-ns`'s fixture/test-ns-hook handling. Neither is used
  anywhere in this repo today (verified: no `use-fixtures`/`test-ns-hook`
  hits), but a future addition to an opted-in namespace would otherwise be
  silently skipped rather than failing loudly, so fail fast instead."
  [ns-obj]
  (when (or (::t/once-fixtures (meta ns-obj))
            (::t/each-fixtures (meta ns-obj))
            (find-var (symbol (str (ns-name ns-obj)) "test-ns-hook")))
    (throw (ex-info "parallel-tests namespace uses fixtures or test-ns-hook, which run-parallel-ns does not support"
                    {:ns (ns-name ns-obj)}))))

(defn- run-parallel-ns
  "Runs every deftest var in ns-sym, which carries `^{:parallel-tests
  true}` ns metadata. `^:serial` vars run first, sequentially, in the
  calling thread/bindings (identical to a normal, unparallelised run); the
  remaining vars run on `pool`, each isolated per `run-var-isolated`, then
  have their buffered output printed and their counts merged in, one at a
  time, in submission order. Mirrors `clojure.test/test-ns`'s reporting
  envelope (begin/end-test-ns) and return shape so its result folds into
  the same summary/exit-code logic as every other namespace."
  [ns-sym ^ExecutorService pool]
  (let [ns-obj (the-ns ns-sym)
        _ (assert-supported! ns-obj)
        _ (assert-serial-global-var-mutations! ns-obj)
        vars (->> (ns-interns ns-obj) vals (filter test-var?))
        serial-vars (filter serial-var? vars)
        parallel-vars (remove serial-var? vars)]
    (binding [t/*report-counters* (ref t/*initial-report-counters*)]
      (t/do-report {:type :begin-test-ns :ns ns-obj})
      (doseq [v serial-vars] (t/test-var v))
      (when (seq parallel-vars)
        (let [tasks (mapv (fn [v] (reify Callable (call [_] (run-var-isolated v)))) parallel-vars)
              futures (.invokeAll pool tasks)]
          (doseq [fut futures]
            (let [{:keys [output counts]} (.get fut)]
              (print output)
              (flush)
              (dosync (commute t/*report-counters* (partial merge-with +) counts))))))
      (t/do-report {:type :end-test-ns :ns ns-obj})
      @t/*report-counters*)))

(defn run
  "Run every discovered test namespace. A namespace bearing
  `^{:parallel-tests true}` ns metadata runs via `run-parallel-ns`; every
  other namespace runs exactly as before via `clojure.test/test-ns`. Results
  are merged and reported once via a single final :summary, identical in
  shape to the previous `(apply clojure.test/run-tests nses)` behaviour;
  the aggregate :fail/:error counts feed the same exit-code logic.

  Args (parsed positionally):
    [ns-symbol ...] - explicit namespace whitelist (optional)."
  [& args]
  (let [explicit (seq (map symbol args))
        nses    (or explicit (discover-test-namespaces))]
    (if (empty? nses)
      (do (binding [*out* *err*]
            (println "test-runner: no test namespaces found"))
          (System/exit 1))
      (do (doseq [n nses] (require n))
          (let [parallel? (fn [n] (boolean (:parallel-tests (meta (find-ns n)))))
                pool (when (some parallel? nses) (Executors/newFixedThreadPool (pool-size)))]
            (try
              ;; Dispatched in the same order `nses` was discovered/given (unchanged from
              ;; before), rather than grouping opted-in namespaces first.
              (let [summaries (mapv (fn [n] (if (parallel? n) (run-parallel-ns n pool) (t/test-ns n))) nses)
                    combined (apply merge-with + summaries)
                    summary (assoc combined :type :summary)]
                (t/do-report summary)
                (let [exit (+ (:fail combined 0) (:error combined 0))]
                  (System/exit (if (pos? exit) 1 0))))
              (finally (when pool (.shutdown pool)))))))))

;; Runs inside the parent agent pane (under the stub `pi`). Spawns N stub children with
;; `oh task start`, publishes one item per child with the child's injected identity, then
;; captures each with `oh task collect --any --wait`. Writes results.json and parent.done.
(require '[babashka.fs :as fs] '[babashka.process :as p] '[cheshire.core :as j] '[clojure.string :as str])

(def base (System/getenv "BENCH_BASE"))
(def repo (System/getenv "BENCH_REPO"))
(def n (parse-long (System/getenv "BENCH_CHILDREN")))
(def oh (str repo "/skills/herdr-orch/scripts/oh"))
(defn now [] (System/currentTimeMillis))
(defn log [& xs] (println (now) (str/join " " (map str xs))) (flush))

(defn oh! [args & [{:keys [env timeout] :or {timeout 60000}}]]
  (let [proc (p/process (into [oh] args) {:env (merge (into {} (System/getenv)) env) :out :string :err :string})
        r (deref proc timeout nil)]
    (when-not r (p/destroy-tree proc) (throw (ex-info "oh timeout" {:args args})))
    (log "oh" (str/join " " args) "->" (:exit r))
    (when-not (str/blank? (:err r)) (log "  err:" (str/trim (:err r))))
    (let [body (try (j/parse-string (:out r) true) (catch Exception _ nil))]
      (when-not body (log "  out:" (:out r)))
      (assoc (or body {}) :exit (:exit r) :raw-out (:out r)))))

(def results (atom {:children [] :publishes [] :captures []}))
(defn finish! [status]
  (spit (str base "/results.json") (j/generate-string @results {:pretty true}))
  (spit (str base "/parent.done") status))

(try
  (log "env HERDR_ENV" (System/getenv "HERDR_ENV") "HERDR_PANE_ID" (System/getenv "HERDR_PANE_ID") "root" (System/getenv "ORCH_ASSIGNMENT_ROOT"))
  ;; Wait until Herdr classifies this pane as an agent; `oh` needs a recognised caller.
  (loop [n 120]
    (let [r (oh! ["agent" "get" (System/getenv "HERDR_PANE_ID")] {:timeout 15000})]
      (cond (zero? (:exit r)) (log "recognised" (:raw-out r))
            (pos? n) (do (Thread/sleep 500) (recur (dec n)))
            :else (throw (ex-info "caller pane never recognised as agent" r)))))
  ;; 1. spawn
  (doseq [i (range n)]
    (let [r (oh! ["task" "start" "--task" (str "inert child " i "; do nothing") "--format" "json"] {:timeout 90000})]
      (when-not (:ok r) (throw (ex-info "spawn failed" r)))
      (let [res (:result r)]
        (swap! results update :children conj (select-keys res [:task :child :result :work-root :pane-id :status])))))
  (log "spawned" (count (:children @results)))
  ;; 2. publish one item per child with the child's injected identity
  (doseq [{:keys [task child result work-root pane-id]} (:children @results)]
    (let [t0 (now)
          r (oh! ["task" "publish" "--task" task "--status" "COMPLETE" "--summary" (str "stub child " child " done")]
                 {:env {"HERDR_ORCH_CHILD" child "HERDR_ORCH_TASK" task "HERDR_ORCH_RESULT" result
                        "HERDR_ORCH_WORK_ROOT" work-root "HERDR_ORCH_BIN" oh "HERDR_PANE_ID" pane-id}
                  :timeout 60000})
          t1 (now)
          mtime (when (fs/exists? result) (.toMillis (fs/last-modified-time result)))]
      (swap! results update :publishes conj {:task task :child child :t-before t0 :t-after t1 :mtime mtime :ok (:ok r) :result (:result r) :error (:error r)})))
  ;; 3. capture
  (loop [remaining (set (map :task (:children @results))) tries 10]
    (when (and (seq remaining) (pos? tries))
      (let [t0 (now)
            r (oh! ["task" "collect" "--any" "--wait" "--timeout" "20000" "--format" "json"] {:timeout 40000})
            t1 (now)
            task (get-in r [:result :task])]
        (swap! results update :captures conj {:t-before t0 :t-after t1 :task task :status (get-in r [:result :status]) :item (get-in r [:result :item]) :terminal (get-in r [:result :terminal?]) :ok (:ok r) :error (:error r)})
        (recur (disj remaining task) (dec tries)))))
  (let [captured (set (keep :task (:captures @results)))
        expected (set (map :task (:children @results)))]
    (log "captured" (count captured) "of" (count expected))
    (finish! (if (= captured expected) "ok" "incomplete")))
  (catch Exception e
    (log "FAIL" (ex-message e) (pr-str (ex-data e)))
    (finish! "fail")))

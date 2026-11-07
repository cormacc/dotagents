;; Detection bench, run inside the parent agent pane (under the stub `pi`).
;; Measures publication-to-capture latency of `oh task collect --any --wait` for two poll
;; intervals (ABAB), counts `herdr` subprocesses per capture, then tests restart recovery.
;; Env: BENCH_CHILDREN, BENCH_ROUNDS (rounds per arm, default 5), BENCH_ARMS (default "1000,250").
(require '[babashka.fs :as fs] '[babashka.process :as p] '[cheshire.core :as j] '[clojure.string :as str])

(def base (System/getenv "BENCH_BASE"))
(def repo (System/getenv "BENCH_REPO"))
(def n (parse-long (System/getenv "BENCH_CHILDREN")))
(def rounds (or (some-> (System/getenv "BENCH_ROUNDS") parse-long) 5))
(def arms (mapv parse-long (str/split (or (System/getenv "BENCH_ARMS") "1000,250") #",")))
(def oh (str repo "/skills/herdr-orch/scripts/oh"))
(def calls-log (System/getenv "BENCH_HERDR_LOG"))
(defn now [] (System/currentTimeMillis))
(defn log [& xs] (println (now) (str/join " " (map str xs))) (flush))
(defn call-count [] (if (fs/exists? calls-log) (count (filter #(str/includes? % " parent ") (str/split-lines (slurp calls-log)))) 0))

(defn oh-proc [args env] (p/process (into [oh] args) {:env (merge (into {} (System/getenv)) env) :out :string :err :string}))
(defn finish [proc timeout label]
  (let [r (deref proc timeout nil)]
    (when-not r (p/destroy-tree proc) (throw (ex-info "oh timeout" {:label label})))
    (let [body (try (j/parse-string (:out r) true) (catch Exception _ nil))]
      (when-not (zero? (:exit r)) (log label "exit" (:exit r) (str/trim (:err r)) (:out r)))
      (assoc (or body {}) :exit (:exit r)))))
(defn oh! [args & [{:keys [env timeout label] :or {timeout 60000 label "oh"}}]]
  (finish (oh-proc args env) timeout label))

(def results (atom {:children n :rounds rounds :arms arms :spawns [] :samples [] :recovery nil}))
(defn finish! [status]
  (spit (str base "/results.json") (j/generate-string @results {:pretty true}))
  (spit (str base "/parent.done") status))
(defn median [xs] (let [s (vec (sort xs)) c (count s)] (when (pos? c) (if (odd? c) (s (quot c 2)) (/ (+ (s (dec (quot c 2))) (s (quot c 2))) 2.0)))))

(defn child-env [{:keys [task child result work-root pane-id]}]
  {"HERDR_ORCH_CHILD" child "HERDR_ORCH_TASK" task "HERDR_ORCH_RESULT" result
   "HERDR_ORCH_WORK_ROOT" work-root "HERDR_ORCH_BIN" oh "HERDR_PANE_ID" pane-id
   "ORCH_WAITING_INTERVAL_MIN_MS" "1"})
(defn publish! [c status summary]
  (let [r (oh! ["task" "publish" "--task" (:task c) "--status" status "--summary" summary] {:env (child-env c) :label "publish"})]
    (when-not (:ok r) (throw (ex-info "publish failed" r)))
    (let [item (get-in r [:result :item]) path (if (= 1 item) (:result c) (str (:result c) "." item))]
      {:item item :path path :mtime (.toMillis (fs/last-modified-time path))})))

(try
  (loop [k 120]
    (let [r (oh! ["agent" "get" (System/getenv "HERDR_PANE_ID")] {:timeout 15000 :label "agent get"})]
      (cond (zero? (:exit r)) (log "recognised")
            (pos? k) (do (Thread/sleep 500) (recur (dec k)))
            :else (throw (ex-info "caller pane never recognised" r)))))
  ;; spawn
  (def children
    (vec (for [i (range n)]
           (let [t0 (now) r (oh! ["task" "start" "--task" (str "inert child " i "; do nothing") "--format" "json"] {:timeout 90000 :label "start"})]
             (when-not (:ok r) (throw (ex-info "spawn failed" r)))
             (swap! results update :spawns conj {:i i :ms (- (now) t0)})
             (select-keys (:result r) [:task :child :result :work-root :pane-id])))))
  (log "spawned" n)
  ;; ABAB rounds: one sample per child per round; the collector is already polling when the item lands.
  (doseq [round (range (* 2 rounds)) :let [arm (arms (mod round 2))]]
    (doseq [c children]
      (let [calls0 (call-count)
            collector (oh-proc ["task" "collect" "--any" "--wait" "--timeout" "20000" "--format" "json"] {"ORCH_POLL_INTERVAL_MS" (str arm)})]
        (Thread/sleep (+ 1200 (rand-int 1300)))
        (let [pub (publish! c "WAITING" (str "round " round " arm " arm))
              r (finish collector 40000 "collect")
              t-capture (now)
              calls1 (call-count)]
          (swap! results update :samples conj
                 {:round round :arm arm :child (:child c) :task (:task c) :item (:item pub)
                  :t-publish (:mtime pub) :t-capture t-capture :latency (- t-capture (:mtime pub))
                  :captured-task (get-in r [:result :task]) :captured-item (get-in r [:result :item])
                  :status (get-in r [:result :status]) :herdr-calls (- calls1 calls0) :ok (:ok r)})
          (when-not (= (:task c) (get-in r [:result :task])) (log "WARN captured a different task" (get-in r [:result :task]))))))
    (let [s (filter #(= round (:round %)) (:samples @results))]
      (log "round" round "arm" arm "median-ms" (median (map :latency s)) "calls" (median (map :herdr-calls s)))))
  ;; restart recovery: publish with no collector, then a fresh process captures each once.
  (let [pubs (mapv #(publish! % "WAITING" "recovery") children)
        fresh (p/process ["bb" (str repo "/.tmp/comms-bench/detect-recover.clj")] {:out :string :err :string})
        r (deref fresh 120000 nil)]
    (when-not r (p/destroy-tree fresh) (throw (ex-info "recover timeout" {})))
    (let [captures (j/parse-string (:out r) true)
          tasks (map :task (filter :ok captures))]
      (swap! results assoc :recovery {:published (count pubs) :captures captures
                                      :exactly-once (and (= (count tasks) n) (= (count (set tasks)) n))
                                      :final (last captures)})))
  (log "done")
  (finish! "ok")
  (catch Exception e
    (log "FAIL" (ex-message e) (pr-str (ex-data e)))
    (finish! "fail")))

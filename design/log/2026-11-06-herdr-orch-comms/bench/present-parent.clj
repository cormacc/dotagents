;; Presentation bench helper. Runs in the background of the parent pane while a real Pi
;; (with present-ext.ts) is the pane's foreground agent. Arms, ABAB per round:
;;   push-idle   fresh stub child publishes COMPLETE; existing advisory `agent prompt` push.
;;   push-busy   same, while the parent runs `bash sleep`.
;;   watch-idle  long-lived stub child publishes WAITING; extension watcher steers it.
;;   watch-busy  same, parent busy.
;; Env: BENCH_ROUNDS (default 3), BENCH_SLEEP (busy tool seconds, default 20).
(require '[babashka.fs :as fs] '[babashka.process :as p] '[cheshire.core :as j] '[clojure.string :as str])

(def base (System/getenv "BENCH_BASE"))
(def repo (System/getenv "BENCH_REPO"))
(def rounds (or (some-> (System/getenv "BENCH_ROUNDS") parse-long) 3))
(def sleep-s (or (some-> (System/getenv "BENCH_SLEEP") parse-long) 20))
(def oh (str repo "/skills/herdr-orch/scripts/oh"))
(def pane (System/getenv "HERDR_PANE_ID"))
(def events (System/getenv "BENCH_EVENTS"))
(def watch-enable (System/getenv "BENCH_WATCH_ENABLE"))
(def proto? (= "1" (System/getenv "BENCH_PROTO")))
(def publish-bin (or (System/getenv "BENCH_PUBLISH_BIN") oh))
(defn now [] (System/currentTimeMillis))
(defn log [& xs] (println (now) (str/join " " (map str xs))) (flush))

(defn oh! [args & [{:keys [env timeout label] :or {timeout 90000 label "oh"}}]]
  (let [proc (p/process (into [oh] args) {:env (merge (into {} (System/getenv)) env) :out :string :err :string})
        r (deref proc timeout nil)]
    (when-not r (p/destroy-tree proc) (throw (ex-info "oh timeout" {:label label})))
    (let [body (try (j/parse-string (:out r) true) (catch Exception _ nil))]
      (when-not (zero? (:exit r)) (log label "exit" (:exit r) (str/trim (:err r)) (subs (:out r) 0 (min 400 (count (:out r))))))
      (assoc (or body {}) :exit (:exit r)))))
(defn herdr! [& args]
  (let [r @(p/process (into ["herdr"] args) {:out :string :err :string})]
    (when-not (zero? (:exit r)) (log "herdr" (str/join " " args) "exit" (:exit r) (:err r)))
    (try (j/parse-string (:out r) true) (catch Exception _ nil))))
(defn agent [] (let [r (herdr! "agent" "get" pane)] (get-in r [:result :agent])))
(defn wait-for! [label pred & {:keys [tries sleep] :or {tries 240 sleep 500}}]
  (loop [k tries]
    (let [v (try (pred) (catch Exception _ nil))]
      (cond v v
            (pos? k) (do (Thread/sleep sleep) (recur (dec k)))
            :else (throw (ex-info (str "wait failed: " label) {}))))))
(defn read-events [] (if (fs/exists? events) (mapv #(j/parse-string % true) (str/split-lines (slurp events))) []))
(defn wait-idle! [] (wait-for! "parent idle" #(let [a (agent)] (when (contains? #{"idle" "done"} (:agent_status a)) a)) :tries 240 :sleep 500))

(def results (atom {:rounds rounds :sleep-s sleep-s :samples []}))
(defn finish! [status]
  (spit (str base "/results.json") (j/generate-string @results {:pretty true}))
  (spit (str base "/parent.done") status))

(defn child-env [{:keys [task child result work-root pane-id]}]
  {"HERDR_ORCH_CHILD" child "HERDR_ORCH_TASK" task "HERDR_ORCH_RESULT" result
   "HERDR_ORCH_WORK_ROOT" work-root "HERDR_ORCH_BIN" oh "HERDR_PANE_ID" pane-id
   "ORCH_WAITING_INTERVAL_MIN_MS" "1"})
(defn spawn! [label]
  (let [t0 (now) r (oh! ["task" "start" "--task" (str "inert child " label "; do nothing") "--format" "json"] {:label "start"})]
    (when-not (:ok r) (throw (ex-info "spawn failed" r)))
    (log "spawned" label "ms" (- (now) t0))
    (select-keys (:result r) [:task :child :result :work-root :pane-id])))
(defn publish! [c status summary]
  (let [bin (if (and proto? (= status "WAITING")) publish-bin oh)
        proc (p/process [bin "task" "publish" "--task" (:task c) "--status" status "--summary" summary "--notify-timeout" "60000"] {:env (merge (into {} (System/getenv)) (child-env c)) :out :string :err :string})
        rr (deref proc 120000 nil)
        _ (when-not rr (p/destroy-tree proc) (throw (ex-info "publish timeout" {})))
        r (assoc (or (try (j/parse-string (:out rr) true) (catch Exception _ nil)) {}) :exit (:exit rr))]
    (when-not (:ok r) (throw (ex-info "publish failed" r)))
    (let [item (get-in r [:result :item]) path (if (= 1 item) (:result c) (str (:result c) "." item))]
      {:item item :path path :mtime (.toMillis (fs/last-modified-time path)) :push (get-in r [:result :parent-push])})))
(defn make-busy! []
  ;; A typed prompt: the parent runs one long bash tool call.
  (herdr! "agent" "prompt" pane (str "sleep: sleep " sleep-s))
  (wait-for! "tool running" #(some (fn [e] (and (= "tool_start" (:kind e)) (> (:t e) (- (now) 60000)))) (read-events)) :tries 120 :sleep 250))
(defn presented-at [pred since]
  ;; First event after `since` that satisfies pred, or nil within the wait.
  (wait-for! "presentation" #(some (fn [e] (when (and (> (:t e) (- since 1)) (pred e)) e)) (read-events)) :tries (* 2 (+ sleep-s 90)) :sleep 250))

(try
  (log "waiting for real pi parent with a session")
  (wait-for! "parent pi with session" #(let [a (agent)] (when (and (= "pi" (:agent a)) (get-in a [:agent_session :value])) a)))
  (wait-idle!)
  (log "parent" (pr-str (select-keys (agent) [:agent :agent_status :agent_session])))
  (let [long-lived (spawn! "watch-target")]
    (wait-idle!)
    (doseq [round (range rounds) arm [:push-idle :watch-idle :push-busy :watch-busy]]
      (wait-idle!)
      (let [watch? (str/starts-with? (name arm) "watch") busy? (str/ends-with? (name arm) "busy")
            c (if watch? long-lived (spawn! (str (name arm) "-" round)))
            _ (wait-idle!)
            _ (if watch? (spit watch-enable "on") (when (fs/exists? watch-enable) (fs/delete watch-enable)))
            _ (when busy? (make-busy!))
            t-start (now)
            pub (if watch? (publish! c "WAITING" (str (name arm) " round " round)) (publish! c "COMPLETE" (str (name arm) " round " round)))
            t-published (now)
            pred (if watch?
                   (fn [e] (and (= "message_end" (:kind e)) (contains? #{"bench-mail" "herdr-orch-item"} (:customType e)) (str/includes? (or (:text e) "") (:task c))))
                   (fn [e] (and (= "message_end" (:kind e)) (= "user" (:role e)) (str/includes? (or (:text e) "") (:task c)))))
            shown (try (presented-at pred t-start) (catch Exception _ nil))
            input-ev (when-not watch? (some (fn [e] (when (and (> (:t e) (dec t-start)) (= "input" (:kind e)) (str/includes? (or (:text e) "") (:task c))) e)) (read-events)))
            fire-ev (when watch? (some (fn [e] (when (and (> (:t e) (dec t-start)) (contains? #{"watch-fire" "proto-fire"} (:kind e)) (= (:path e) (:path pub))) e)) (read-events)))
            proto-ev (when-not watch? (some (fn [e] (when (and (> (:t e) (dec t-start)) (= "message_end" (:kind e)) (= "herdr-orch-item" (:customType e)) (str/includes? (or (:text e) "") (:task c))) e)) (read-events)))
            sample {:round round :arm (name arm) :task (:task c) :item (:item pub) :t-publish (:mtime pub) :t-publish-returned t-published
                    :push (:push pub) :t-input (:t input-ev) :t-fire (:t fire-ev) :t-presented (:t shown) :t-proto (:t proto-ev) :proto-latency (when proto-ev (- (:t proto-ev) (:mtime pub)))
                    :latency (when shown (- (:t shown) (:mtime pub)))
                    :publish-call-ms (- t-published t-start)}]
        (swap! results update :samples conj sample)
        (log "sample" (pr-str (select-keys sample [:round :arm :latency :push :publish-call-ms])))
        (when (fs/exists? watch-enable) (fs/delete watch-enable))
        (wait-idle!)
        (when-not watch? (oh! ["task" "collect" (:task c) "--format" "json"] {:label "collect"}))))
    (log "done")
    (finish! "ok"))
  (catch Exception e
    (log "FAIL" (ex-message e) (pr-str (ex-data e)))
    (finish! "fail")))

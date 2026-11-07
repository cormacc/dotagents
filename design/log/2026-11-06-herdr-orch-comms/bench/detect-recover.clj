;; A fresh parent process (new PID, same pane and session) that captures every unconsumed item
;; with `collect --any` until no candidates remain. Prints a JSON array of captures to stdout;
;; the last element is the terminating `no-candidates` (or timeout) envelope.
(require '[babashka.process :as p] '[cheshire.core :as j])
(def oh (str (System/getenv "BENCH_REPO") "/skills/herdr-orch/scripts/oh"))
(def out (atom []))
(loop [k 20]
  (let [r @(p/process [oh "task" "collect" "--any" "--format" "json"] {:out :string :err :string})
        body (try (j/parse-string (:out r) true) (catch Exception _ {:parse-error (:out r)}))
        entry {:ok (boolean (and (:ok body) (get-in body [:result :task]))) :task (get-in body [:result :task])
               :item (get-in body [:result :item]) :status (get-in body [:result :status]) :reason (get-in body [:result :reason])
               :exit (:exit r)}]
    (swap! out conj entry)
    (when (and (:ok entry) (pos? k)) (recur (dec k)))))
(println (j/generate-string @out))

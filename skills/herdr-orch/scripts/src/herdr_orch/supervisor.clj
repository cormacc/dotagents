(ns herdr-orch.supervisor
  "Detached headless supervisor and read-only monitor for one measurement run.

  `supervise` owns one run: `oh task run|start --context measurement` starts it in its own
  session with stdin closed, records its identity, and only then does it launch the subject
  through the frozen argv from `herdr-orch.measurement/launch-spec`. It bounds the subject's
  process tree by the configured hard limit, writes execution evidence, and publishes the
  validated result through `herdr-orch.cli/publish-execution!`. It never retries a sample.

  `monitor` prints a run's recorded events, tool activity, errors and execution state. It
  reads evidence files only, so closing it or typing into it changes nothing in the run."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [herdr-orch.cli :as cli]
            [herdr-orch.ledger :as ledger]
            [herdr-orch.measurement :as measurement])
  (:import [java.nio.file Files Paths StandardCopyOption]))

(defn- now [] (str (java.time.Instant/now)))
(defn- write-json! [path value]
  (let [temp (str path ".tmp")]
    (spit temp (json/generate-string value))
    (Files/move (Paths/get temp (make-array String 0)) (Paths/get (str path) (make-array String 0))
                (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING StandardCopyOption/ATOMIC_MOVE]))))
(defn- log! [dir & parts]
  (spit (str (fs/path dir "supervisor.log")) (str (now) " " (str/join " " parts) "\n") :append true))
(defn- one-line [text]
  (let [flat (str/trim (str/replace (str text) #"\s+" " "))]
    (subs flat 0 (min 300 (count flat)))))

;; The subject starts only after the launcher has recorded this process as the run's supervisor,
;; so a launcher that dies before acknowledging leaves no subject behind.
(defn- acknowledged? [task self window-ms]
  (let [deadline (+ (System/currentTimeMillis) window-ms)]
    (loop []
      (let [recorded (try (:supervisor (ledger/read! task)) (catch Exception _ nil))]
        (cond (= (select-keys self [:pid :start]) (select-keys recorded [:pid :start])) true
              (< deadline (System/currentTimeMillis)) false
              :else (do (Thread/sleep 50) (recur)))))))

;; `expectedOutputs` must be new regular files; a symlink does not count as produced output.
(defn- outputs [work-root expected]
  (let [present? #(fs/regular-file? (fs/path work-root %) {:nofollow-links true})]
    {:present (filterv present? expected) :missing (vec (remove present? expected))}))

(defn- publication-body [{:keys [success reasons evidence-dir outputs]}]
  {:status (if success "COMPLETE" "FAILED")
   :summary (if success
              "measurement execution succeeded; this is execution evidence only, not an answer-quality score"
              (str "measurement execution failed: " (one-line (first reasons))))
   :artifacts (:present outputs)
   :findings (vec (take 5 (cons (str evidence-dir " -- execution evidence: execution.json, adapter/, subject.stdout, subject.stderr")
                                (map one-line reasons))))
   :process []})

(defn- run-subject! [{:keys [task argv work-root manifest-path adapter-evidence-dir hard-timeout-ms expected-outputs evidence-dir]} self]
  (let [file #(io/file (str (fs/path evidence-dir %)))
        env (assoc (measurement/subject-env (into {} (System/getenv)) task) "HERDR_ORCH_MEASUREMENT_MANIFEST" manifest-path)
        proc (process/process argv {:dir work-root :env env :in (io/file "/dev/null") :out (file "subject.stdout") :err (file "subject.stderr")})
        subject (measurement/process-identity (.pid ^Process (:proc proc)))
        _ (write-json! (fs/path evidence-dir "subject.json") subject)
        _ (log! evidence-dir "subject started pid" (:pid subject))
        finished (deref proc hard-timeout-ms ::timeout)
        timed-out? (= ::timeout finished)
        _ (when timed-out? (log! evidence-dir "hard limit of" hard-timeout-ms "ms expired; stopping the owned session"))
        ;; Every other member of this session is the subject's tree; nothing it left running
        ;; may outlive the run, after a normal exit as well as at the hard limit.
        remaining (measurement/stop-session! (:sid self) (:pid self))
        exit (:exit @proc)
        _ (log! evidence-dir "subject ended exit" exit)
        evidence (measurement/interpret-evidence adapter-evidence-dir)
        produced (outputs work-root expected-outputs)
        reasons (cond-> []
                  timed-out? (conj (str "the hard limit of " hard-timeout-ms " ms expired; the owned processes were stopped"))
                  (and (not timed-out?) (not (zero? exit))) (conj (str "the subject exited with status " exit))
                  true (into (:reasons evidence))
                  (seq (:missing produced)) (conj (str "expected outputs are missing: " (str/join ", " (:missing produced))))
                  (seq remaining) (conj (str (count remaining) " owned process(es) survived cleanup")))]
    {:task task :success (empty? reasons) :reasons reasons :exit exit :timed-out timed-out?
     :hard-timeout-ms hard-timeout-ms :supervisor self :subject subject :cleanup {:remaining remaining}
     :outputs produced :audits (:audits evidence) :finished-at (now) :evidence-dir evidence-dir}))

(defn supervise!
  "Runs the measurement described by `run-file` and returns the process exit code."
  [run-file]
  (let [run (json/parse-string (slurp run-file) true)
        {:keys [task evidence-dir acknowledgement-window-ms]} run
        self (measurement/process-identity (.pid (java.lang.ProcessHandle/current)))]
    (write-json! (fs/path evidence-dir "supervisor.json") self)
    (log! evidence-dir "supervisor started pid" (:pid self) "session" (:sid self))
    (if-not (acknowledged? task self acknowledgement-window-ms)
      (do (log! evidence-dir "not acknowledged by the launcher; no subject was launched") 3)
      (let [execution (try (run-subject! run self)
                           (catch Exception e
                             (log! evidence-dir "supervisor error:" (one-line (.getMessage e)))
                             {:task task :success false :reasons [(str "supervisor error: " (.getMessage e))]
                              :supervisor self :cleanup {:remaining (measurement/stop-session! (:sid self) (:pid self))}
                              :outputs {:present [] :missing []} :finished-at (now) :evidence-dir evidence-dir}))]
        (write-json! (fs/path evidence-dir "execution.json") execution)
        (try (cli/publish-execution! task (publication-body execution) self)
             (log! evidence-dir "published" (if (:success execution) "COMPLETE" "FAILED"))
             0
             ;; Evidence stays in place; a run whose publication failed remains diagnosable.
             (catch Exception e
               (write-json! (fs/path evidence-dir "publication-error.json") {:message (.getMessage e) :data (str (ex-data e))})
               (log! evidence-dir "publication failed:" (one-line (.getMessage e)))
               2))))))

;; --- read-only monitor ---------------------------------------------------------------
(defn- event-line [line]
  (let [event (try (json/parse-string line true) (catch Exception _ nil))]
    (cond
      (not (map? event)) (str "[output] " line)
      (#{"tool_execution_start" "tool_execution_end"} (:type event))
      (str "[tool] " (if (= "tool_execution_start" (:type event)) "start " "end ") (:toolName event)
           (when (:isError event) " (error)"))
      (get-in event [:message :errorMessage]) (str "[error] " (get-in event [:message :errorMessage]))
      :else (str "[event] " (:type event)))))
(def ^:private monitored
  [["supervisor.log" #(str "[supervisor] " %)]
   ["subject.stdout" event-line]
   ["subject.stderr" #(str "[stderr] " %)]
   ["adapter/events.jsonl" #(str "[adapter] " %)]])
(defn- new-lines [dir offsets]
  (reduce (fn [[offsets out] [file render]]
            (let [path (fs/path dir file)
                  text (if (fs/exists? path) (slurp (str path)) "")
                  from (get offsets file 0)
                  complete (let [end (str/last-index-of text "\n")] (if (and end (<= from end)) (subs text from (inc end)) ""))]
              [(assoc offsets file (+ from (count complete)))
               (into out (map render) (remove str/blank? (str/split-lines complete)))]))
          [offsets []] monitored))
(defn- state-line [dir]
  (let [read (fn [f] (let [p (fs/path dir f)] (when (fs/exists? p) (try (json/parse-string (slurp (str p)) true) (catch Exception _ nil)))))
        execution (read "execution.json")
        supervisor (read "supervisor.json")]
    (cond execution (str "finished: " (if (:success execution) "success" (str "failed -- " (first (:reasons execution)))))
          (and supervisor (measurement/identity-alive? supervisor)) "running"
          supervisor "stopped without terminal evidence"
          :else "launching")))
(defn monitor!
  "Prints `evidence-dir`'s recorded activity until the run stops, then returns."
  [evidence-dir]
  (println "herdr-orch measurement monitor (read-only):" evidence-dir)
  (println "Input typed here reaches nothing: the measured subject's stdin is closed.")
  (loop [offsets {} shown nil]
    (let [[offsets lines] (new-lines evidence-dir offsets)
          state (state-line evidence-dir)]
      (doseq [line lines] (println line))
      (when (not= state shown) (println (str "[state] " state)))
      (flush)
      (if (or (str/starts-with? state "finished") (str/starts-with? state "stopped"))
        (let [[_ tail] (new-lines evidence-dir offsets)] (doseq [line tail] (println line)) (flush))
        (do (Thread/sleep 500) (recur offsets state))))))

(defn -main [& [op path]]
  (case op
    "supervise" (System/exit (supervise! path))
    "monitor" (monitor! path)
    (do (binding [*out* *err*] (println "usage: herdr-orch.supervisor supervise RUN-FILE | monitor EVIDENCE-DIR"))
        (System/exit 64))))

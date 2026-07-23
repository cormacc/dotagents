(ns herdr-subagents.cli-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [herdr-subagents.core :as core]
            [herdr-subagents.herdr :as herdr]))

(defn- git-toplevel []
  (let [proc @(process/process ["git" "rev-parse" "--show-toplevel"] {:out :string :err :string})]
    (when (zero? (:exit proc)) (str/trim (:out proc)))))
(def root
  (if (fs/absolute? *file*)
    (str (fs/canonicalize (fs/path (fs/parent *file*) "../../../../..")))
    (or (git-toplevel) (throw (ex-info "cannot resolve repo root" {})))))
(def bin (str root "/skills/herdr-subagents/scripts/subagent"))
(def fake (str root "/skills/herdr-subagents/scripts/test/fixtures/fake-herdr"))
(defn calls [log] (if (fs/exists? log) (mapv #(str/split % #"\037") (str/split-lines (slurp log))) []))
(defn mutating? [argv] (and (not (some #{"--help"} argv)) (contains? #{["pane" "split"] ["pane" "rename"] ["pane" "close"] ["agent" "start"] ["agent" "prompt"]} (vec (take 2 argv)))))
;; `SUBAGENT_ASSIGNMENT_ROOT` keeps the ledger, index markers, result files, and roster
;; lookup inside the per-test temp dir: `bb test` must never touch the live tree.
;; `HOME` points at an empty directory so personas can only resolve through the project
;; roster symlinked to the repo's own tracked `subagents/`.
(defn fake-env [overrides]
  (let [dir (fs/create-temp-dir {:prefix "fake-herdr-"}) log (str (fs/path dir "calls")) env-file (str (fs/path dir "env")) prompt-file (str (fs/path dir "prompt"))
        home (fs/path dir "home") roster (fs/path dir ".agents" "subagents")]
    (fs/create-sym-link (fs/path dir "herdr") fake)
    (fs/create-dirs home)
    (fs/create-dirs (fs/parent roster))
    (fs/create-sym-link roster (fs/path root "subagents"))
    {:dir dir :log log :env-file env-file :prompt-file prompt-file :roster (str roster)
     :env (merge {"PATH" (str dir ":" (System/getenv "PATH")) "HERDR_ENV" "1" "HERDR_PANE_ID" "w:p" "HERDR_SUBAGENT_BIN" bin "FAKE_HERDR_LOG" log "FAKE_HERDR_ENV_FILE" env-file "FAKE_HERDR_PROMPT_FILE" prompt-file
                  "HOME" (str home) "SUBAGENT_ASSIGNMENT_ROOT" (str dir)} overrides)}))
(defn call! [env & argv] @(process/process (into [bin] argv) {:out :string :err :string :env env}))
(defn result [proc] (json/parse-string (:out proc) true))

(deftest preflight-and-vector-argv-contract
  (let [{:keys [env log prompt-file dir env-file]} (fake-env {}) proc (call! env "run" "worker" "--task" "quotes ' newline\n $(unsafe) `unsafe`" "--timeout" "20")
        argv (calls log)
        injected (into {} (map #(vec (str/split % #"=" 2)) (str/split-lines (slurp env-file))))]
    (is (zero? (:exit proc)))
    ;; SUBAGENT_ASSIGNMENT_ROOT relocates ledger + result state and is inherited by the child.
    (is (str/starts-with? (injected "HERDR_SUBAGENT_RESULT") (str (fs/path dir ".agents" "tmp" "herdr-subagents"))))
    (is (= (str dir) (injected "SUBAGENT_ASSIGNMENT_ROOT")))
    (is (= "COMPLETE" (get-in (result proc) [:result :status])))
    (is (= ["pane" "split" "--pane" "w:p" "--direction" "right"] (subvec (vec (first (filter #(and (= ["pane" "split"] (vec (take 2 %))) (not (some #{"--help"} %))) argv))) 0 6)))
    (is (some #(= ["pane" "close"] (vec (take 2 %))) argv))
    (is (re-find #"(?s)\$\(unsafe\).*`unsafe`" (slurp prompt-file)))
    (is (= #{["pane" "layout"] ["pane" "split"] ["pane" "rename"] ["pane" "get"] ["pane" "close"] ["agent" "start"] ["agent" "prompt"] ["agent" "wait"] ["agent" "get"] ["agent" "list"] ["notification" "show"]}
           (set (map #(vec (take 2 %)) (filter #(= "--help" (nth % 2 nil)) argv)))))))

(deftest persona-system-prompt-dialect
  ;; Also covers --task-file and --prompt-extra assignment input.
  (let [{:keys [env log roster dir prompt-file]} (fake-env {}) file (str (fs/path dir "assignment.md"))
        _ (spit file "assignment from a file")
        pi-proc (call! env "start" "worker" "--task-file" file "--prompt-extra" "stay read-only")
        pi-start (first (filter #(and (= ["agent" "start"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))]
    (is (zero? (:exit pi-proc)))
    (is (some #(str/ends-with? % "/worker.md") pi-start))
    ;; Project roster wins end-to-end: the persona resolves under the assignment root,
    ;; not under $HOME/.agents/subagents (which does not exist in this fixture).
    (is (some #(= (str roster "/worker.md") %) pi-start))
    (is (str/includes? (slurp prompt-file) "assignment from a file"))
    (is (str/includes? (slurp prompt-file) "Additional constraints: stay read-only")))
  ;; Also covers the nested planner label end-to-end: the injected persona gates it.
  (let [{:keys [env log]} (fake-env {"HERDR_SUBAGENT_PERSONA" "planner"})
        claude-proc (call! env "start" "worker" "--kind" "claude" "--model" "sonnet" "--task" "claude persona")
        claude-start (first (filter #(and (= ["agent" "start"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))
        rename (first (filter #(and (= ["pane" "rename"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))]
    (is (zero? (:exit claude-proc)))
    (is (= "---" (last claude-start)))
    (is (not-any? #(str/ends-with? % "/worker.md") claude-start))
    ;; Fake `pane get w:p` reports the parent label `planner-1-model`.
    (is (= ["pane" "rename" "w:child" "planner-1/worker-1-sonnet"] (vec rename)))
    (is (= "planner-1/worker-1-sonnet" (get-in (result claude-proc) [:result :label])))))

(deftest preflight-fails-before-ledger-or-mutation
  (doseq [overrides [{"FAKE_HERDR_VERSION" "0.7.4"} {"FAKE_MISSING_CAPABILITY" "pane-split"}]]
    (let [{:keys [env log]} (fake-env overrides) proc (call! env "start" "worker" "--task" "x")]
      (is (= 1 (:exit proc)))
      (is (not-any? mutating? (calls log))))))

(deftest preview-is-side-effect-free
  (let [{:keys [env log]} (fake-env {}) proc (call! env "run" "worker" "--task" "preview" "--print-prompt")]
    (is (zero? (:exit proc)))
    (is (re-find #"<assigned-task>" (:out proc)))
    (is (not-any? mutating? (calls log)))
    (is (not-any? #(and (= ["pane" "get"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log))))
  (let [{:keys [env log]} (fake-env {}) proc (call! env "start" "not-a-persona" "--task" "x")]
    (is (= 1 (:exit proc)))
    (is (re-find #"persona not found" (:out proc)))
    (is (re-find #"available" (:out proc)))
    (is (not-any? mutating? (calls log)))))

(deftest start-collect-status-and-blocked-contract
  ;; The hand-written envelope is BLOCKED, which also covers a BLOCKED envelope
  ;; end-to-end: captured, but the pane is never closed.
  (let [{:keys [env env-file log]} (fake-env {}) start (call! env "start" "worker" "--task" "later") task (get-in (result start) [:result :task])
        values (into {} (map #(str/split % #"=" 2) (str/split-lines (slurp env-file))))
        envelope (core/envelope {:child (values "HERDR_SUBAGENT_CHILD") :task task :result (values "HERDR_SUBAGENT_RESULT") :status "BLOCKED" :summary "later" :artifacts [] :findings [] :next nil})]
    (spit (values "HERDR_SUBAGENT_RESULT") envelope)
    (is (= "BLOCKED" (get-in (result (call! env "collect" task)) [:result :status])))
    (is (zero? (:exit (call! env "status" task))))
    (is (zero? (:exit (call! env "list"))))
    (is (some #(= ["agent" "get"] (vec (take 2 %))) (calls log)))
    (is (not-any? #(and (= ["pane" "close"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log))))
  (let [{:keys [env log]} (fake-env {"FAKE_WAIT" "blocked"}) proc (call! env "run" "worker" "--task" "blocked" "--timeout" "20")]
    (is (= "blocked" (get-in (result proc) [:result :status])))
    (is (not-any? #(and (= ["pane" "close"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))))

(deftest result-edge-and-publication-contract
  ;; Publication during a structured Herdr wait error, with a FAILED envelope end-to-end.
  (let [{:keys [env log]} (fake-env {"FAKE_WAIT" "timeout-publish" "FAKE_PUBLISH_STATUS" "FAILED"}) proc (call! env "run" "worker" "--task" "timeout publication" "--timeout" "20")]
    (is (zero? (:exit proc)))
    (is (= "FAILED" (get-in (result proc) [:result :status])))
    (is (some #(= ["pane" "close"] (vec (take 2 %))) (calls log))))
  ;; A published result is immutable, so a mismatched envelope is a non-final `invalid`
  ;; outcome (pane retained, needs manual intervention) rather than a thrown command.
  (let [{:keys [env log]} (fake-env {"FAKE_WAIT" "publish" "FAKE_BAD_ENVELOPE" "1"}) proc (call! env "run" "worker" "--task" "stale" "--timeout" "20")]
    (is (zero? (:exit proc)))
    (is (= "invalid" (get-in (result proc) [:result :status])))
    (is (re-find #"identity" (get-in (result proc) [:result :reason])))
    (is (not-any? #(and (= ["pane" "close"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log))))
  (let [{:keys [env log dir]} (fake-env {}) target (str (fs/path dir "published.result"))
        publication-env (merge env {"HERDR_SUBAGENT_CHILD" "child" "HERDR_SUBAGENT_TASK" "task" "HERDR_SUBAGENT_RESULT" target "HERDR_SUBAGENT_WAITING_POLICY" "non-blocking"})
        ok (call! publication-env "publish" "--status" "COMPLETE" "--summary" "done") second (call! publication-env "publish" "--status" "COMPLETE" "--summary" "again")
        from-file-target (str (fs/path dir "from-file.result")) body (str (fs/path dir "body.json"))]
    (is (zero? (:exit ok))) (is (= 1 (:exit second))) (is (fs/exists? target))
    (is (some #(and (= ["notification" "show"] (vec (take 2 %))) (some (fn [arg] (str/includes? arg "child=child task=task")) %)) (calls log)))
    (spit body (json/generate-string {:status "COMPLETE" :summary "published from file" :artifacts [] :findings [] :next nil}))
    (let [proc (call! (merge publication-env {"HERDR_SUBAGENT_RESULT" from-file-target "HERDR_SUBAGENT_WAITING_POLICY" "blocking"}) "publish" "--from-file" body)]
      (is (zero? (:exit proc)) (:err proc))
      (is (str/includes? (slurp from-file-target) "SUMMARY: published from file"))))
  (let [{:keys [env dir]} (fake-env {"FAKE_NOTIFY_FAIL" "1"}) target (str (fs/path dir "notify.result"))
        proc (call! (merge env {"HERDR_SUBAGENT_CHILD" "child" "HERDR_SUBAGENT_TASK" "task" "HERDR_SUBAGENT_RESULT" target "HERDR_SUBAGENT_WAITING_POLICY" "non-blocking"}) "publish" "--status" "COMPLETE" "--summary" "done")]
    (is (zero? (:exit proc))) (is (fs/exists? target))))

(deftest partial-start-failure-is-tracked-and-cleaned
  (let [{:keys [env log]} (fake-env {"FAKE_FAIL_START" "1"}) proc (call! env "start" "worker" "--task" "fail")]
    (is (= 1 (:exit proc)))
    (is (some #(= ["pane" "close"] (vec (take 2 %))) (calls log)))))

(defn- wait-call-count [log]
  (count (filter #(and (= ["agent" "wait"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log))))

;; Capture correctness after several settled-without-result iterations. The fixture
;; publishes on a fixed call count, so this test proves capture/parity only — the
;; interval-derived bound lives in `bounded-poll-timeout-without-result`.
(deftest bounded-poll-eventual-publication
  (let [{:keys [env log]} (fake-env {"FAKE_WAIT" "idle-then-publish" "FAKE_WAIT_PUBLISH_AFTER" "3" "SUBAGENT_POLL_INTERVAL_MS" "50"})
        proc (call! env "run" "worker" "--task" "idle then publish" "--timeout" "5000")]
    (is (zero? (:exit proc)))
    (is (= "COMPLETE" (get-in (result proc) [:result :status])))
    (is (<= 4 (wait-call-count log)))))

;; Regression guard for the poll sleep itself: without it, a settled-but-unpublished
;; child drives `agent wait` as fast as the process can fork (measured 37 calls over
;; this budget). The bound is well under half that, and above the sleep-derived ceiling
;; of 400/50 + 1 = 9.
(deftest bounded-poll-timeout-without-result
  (let [{:keys [env log]} (fake-env {"FAKE_WAIT" "idle-forever" "SUBAGENT_POLL_INTERVAL_MS" "50"})
        proc (call! env "run" "worker" "--task" "never publishes" "--timeout" "400")]
    (is (zero? (:exit proc)))
    (is (= "pending" (get-in (result proc) [:result :status])))
    (is (= "timeout" (get-in (result proc) [:result :reason])))
    (is (<= (wait-call-count log) 14))))

(deftest bounded-poll-covers-collect-wait
  (let [{:keys [env log]} (fake-env {"FAKE_WAIT" "idle-then-publish" "FAKE_WAIT_PUBLISH_AFTER" "2" "SUBAGENT_POLL_INTERVAL_MS" "50"})
        start (call! env "start" "worker" "--task" "later") task (get-in (result start) [:result :task])
        proc (call! env "collect" task "--wait" "--timeout" "5000")]
    (is (zero? (:exit proc)))
    (is (= "COMPLETE" (get-in (result proc) [:result :status])))
    (is (<= 3 (wait-call-count log)))))

(deftest a-negative-poll-interval-never-escapes
  (let [{:keys [env]} (fake-env {"FAKE_WAIT" "idle-forever" "SUBAGENT_POLL_INTERVAL_MS" "-5"})
        proc (call! env "run" "worker" "--task" "negative interval" "--timeout" "200")]
    (is (zero? (:exit proc)))
    (is (= "pending" (get-in (result proc) [:result :status])))))

(deftest stdin-assignment-input
  (let [{:keys [env prompt-file]} (fake-env {})
        proc @(process/process [bin "start" "worker"] {:in "assignment from stdin" :out :string :err :string :env env})]
    (is (zero? (:exit proc)) (:err proc))
    (is (str/includes? (slurp prompt-file) "assignment from stdin"))))

;; One `run` covers two contracts: the implicit ten-minute budget, and a `pane close`
;; failure never demoting an already-captured COMPLETE to a nonzero exit.
(deftest default-budget-and-close-failure-tolerance
  (let [{:keys [env log]} (fake-env {"FAKE_FAIL_CLOSE" "1"}) proc (call! env "run" "worker" "--task" "default budget")
        wait (first (filter #(and (= ["agent" "wait"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))
        budget (parse-long (second (drop-while #(not= "--timeout" %) wait)))]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "COMPLETE" (get-in (result proc) [:result :status])))
    (is (<= 599000 budget 600000))
    (is (some #(and (= ["pane" "close"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))))

(deftest collect-pane-close-is-scoped-to-the-owning-session
  (let [{:keys [env env-file log]} (fake-env {}) start (call! env "start" "worker" "--task" "foreign") task (get-in (result start) [:result :task])
        values (into {} (map #(vec (str/split % #"=" 2)) (str/split-lines (slurp env-file))))
        envelope (core/envelope {:child (values "HERDR_SUBAGENT_CHILD") :task task :result (values "HERDR_SUBAGENT_RESULT") :status "COMPLETE" :summary "foreign" :artifacts [] :findings [] :next nil})]
    (spit (values "HERDR_SUBAGENT_RESULT") envelope)
    (testing "a different parent session captures but never closes"
      (let [proc (call! (merge env {"HERDR_PANE_ID" "w:other"}) "collect" task)]
        (is (zero? (:exit proc)) (:err proc))
        (is (= "COMPLETE" (get-in (result proc) [:result :status])))
        (is (true? (get-in (result proc) [:result :pane-retained])))
        (is (= "foreign-parent-session" (get-in (result proc) [:result :ownership])))
        (is (not-any? #(and (= ["pane" "close"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))))
    (testing "an unresolvable caller identity is non-owning but still captures"
      (let [proc (call! (merge env {"FAKE_FAIL_AGENT_GET" "w:p"}) "collect" task)]
        (is (zero? (:exit proc)) (:err proc))
        (is (= "COMPLETE" (get-in (result proc) [:result :status])))
        (is (true? (get-in (result proc) [:result :pane-retained])))
        (is (not-any? #(and (= ["pane" "close"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))))
    (testing "the owning session still closes"
      (let [proc (call! env "collect" task)]
        (is (zero? (:exit proc)) (:err proc))
        (is (nil? (get-in (result proc) [:result :pane-retained])))
        (is (some #(and (= ["pane" "close"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))))))

(deftest missing-artifact-is-non-final-and-repeatable
  (let [{:keys [env log]} (fake-env {"FAKE_PUBLISH_ARTIFACT" "/nonexistent/subagent-artifact — missing"})
        proc (call! env "run" "worker" "--task" "bad artifact" "--timeout" "200")
        task (get-in (result proc) [:result :task])]
    (is (zero? (:exit proc)) (:err proc))
    (is (= "invalid" (get-in (result proc) [:result :status])))
    (is (re-find #"artifact" (get-in (result proc) [:result :reason])))
    (is (not-any? #(and (= ["pane" "close"] (vec (take 2 %))) (not (some #{"--help"} %))) (calls log)))
    ;; Publication is immutable, so re-collecting must repeat the outcome, not throw.
    (let [again (call! env "collect" task)]
      (is (zero? (:exit again)) (:err again))
      (is (= "invalid" (get-in (result again) [:result :status]))))))

(deftest help-is-human-readable-text
  (let [{:keys [env]} (fake-env {})]
    (doseq [argv [["--help"] ["help"] ["run" "--help"] ["publish" "--help"]]]
      (let [proc (apply call! env argv)]
        (is (zero? (:exit proc)) (str argv " -> " (:err proc)))
        (is (str/starts-with? (:out proc) "subagent run|start"))
        (is (not (str/includes? (:out proc) "\"ok\"")))))))

(ns herdr-orch.hc-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [herdr-orch.cli-test :as cli]
            [herdr-orch.hc :as hc]))

(defn hc-env
  "Reuse cli/fake-env's isolated auth/cache environment before model routing."
  ([] (hc-env {}))
  ([overrides]
   (let [{:keys [dir] :as fixture} (cli/fake-env {})
         log (str (fs/path dir "hc-calls"))
         state (str (fs/path dir "hc-state"))
         session (str (fs/path dir "parent.jsonl"))]
     (assert (fs/exists? (get-in fixture [:env "FAKE_QUOTA_REPORT"])))
     (spit session "{}\n")
     (assoc fixture :hc-log log :hc-state state :session session
            :env (merge (:env fixture)
                        {"HC_TERMINAL_ID" "t1" "PI_SESSION_FILE" session "FAKE_HC_LOG" log
                         "FAKE_HC_STATE_DIR" state "FAKE_HC_PID" "222" "FAKE_SHELL_PID" "111"
                         "FAKE_ANCESTOR_PIDS" "222,111"}
                        overrides)))))
(defn probe [env & args]
  @(process/process (into ["bb" "-cp" (str cli/root "/skills/herdr-orch/scripts/src")
                          (str cli/root "/skills/herdr-orch/scripts/test/fixtures/hc-runtime.clj")] args)
                    {:dir (get env "ORCH_ASSIGNMENT_ROOT") :env env :out :string :err :string}))
(defn state [fixture]
  (edn/read-string (slurp (str (fs/path (:hc-state fixture) "terminals.edn")))))
(defn change! [fixture f & args]
  (spit (str (fs/path (:hc-state fixture) "terminals.edn")) (pr-str (apply f (state fixture) args))))
(defn entry [fixture task]
  (json/parse-string (slurp (str (fs/path (:dir fixture) ".tmp/herdr-orch/ledger" (str task ".json")))) true))
(defn start! [fixture & args]
  (let [proc (apply cli/call! (:env fixture) "task" "start" "scout" "--task" "hc assignment sentinel" args)]
    (is (zero? (:exit proc)) (:out proc))
    (get-in (cli/result proc) [:result])))
(defn publish! [entry]
  (spit (:result entry)
        (str "--- HERDR RESULT v1 ---\nCHILD: " (:child entry) "\nTASK: " (:task entry)
             "\nRESULT: " (:result entry) "\nWORK-ROOT: " (:work-root entry)
             "\nSTATUS: COMPLETE\nSUMMARY: fixture publication\nARTIFACTS:\n- none\nFINDINGS:\n- none\nNEXT: none\n--- END HERDR RESULT ---\n")))

(deftest hc-runtime-protocol-observes-local-identity-and-errors
  (let [{:keys [env session] :as fixture} (hc-env {})
        identity (probe env "identity")]
    (is (zero? (:exit identity)) (:out identity))
    (is (= {:parent-pane "t1" :parent-kind "pi" :parent-session session :parent-session-kind "path"}
           (:result (cli/result identity))))
    (is (zero? (:exit (probe env "preflight"))))
    (is (= "fake hc transcript" (:result (cli/result (probe env "read" "t1")))))
    (is (= "hc-notification-unavailable" (get-in (cli/result (probe env "notify")) [:result :reason])))
    (is (= "pane_not_found" (get-in (cli/result (probe env "status" "terminal" "t-missing")) [:data :response :error :code])))
    (is (= 1 (:exit (probe env "close" "t1"))))
    (is (= 1 (:exit (probe env "status" "terminal" "remote/t1"))))
    (is (empty? (filter #(= "kill" (first %)) (cli/calls (:hc-log fixture)))))
    (let [identity (probe (assoc env "FAKE_HC_KIND" "codex") "identity")]
      (is (= "t1:1000" (get-in (cli/result identity) [:result :parent-session]))))
    (is (= 1 (:exit (probe (assoc env "PI_SESSION_FILE" "") "identity"))))
    (is (= 1 (:exit (probe (assoc env "FAKE_HC_NO_LABELS" "1") "identity"))))
    (is (= 1 (:exit (probe (assoc env "FAKE_HC_NO_STATUS" "1") "identity"))))))

(deftest hc-launch-delivers-one-native-prompt-with-flags-env-and-placement
  (doseq [placement ["--tab" "--split"]]
    (let [{:keys [env log hc-log] :as fixture} (hc-env {})
          launched (start! fixture placement)
          task (:task launched)
          saved (entry fixture task)
          terminal (get (state fixture) (:pane-id saved))
          calls (cli/calls hc-log)
          runs (filter #(= "run" (first %)) calls)
          run (first runs)]
      (is (= 1 (count runs)))
      (is (every? (set run) ["--bg" "--no-notify" "--outlive-parent" "env"]))
      (is (= "space-parent" (get-in terminal [:labels :herdr.space])))
      (is (= (= "--split" placement) (= "tab-parent" (get-in terminal [:labels :herdr.tab]))))
      (is (= (:tab-id saved) (get-in terminal [:labels :herdr.tab])))
      (is (= "t1" (:parent terminal)))
      (is (false? (:die_with_parent terminal)))
      (is (false? (:notify terminal)))
      (is (= (:work-root saved) (:cwd terminal)))
      (is (= task (get-in terminal [:round-env "HERDR_ORCH_TASK"])))
      (is (= (:result saved) (get-in terminal [:round-env "HERDR_ORCH_RESULT"])))
      (is (= 1 (count (:prompts terminal))))
      (is (= 1 (count (re-seq #"hc assignment sentinel" (first (:prompts terminal))))))
      (is (not-any? #(= "send" (first %)) calls))
      ;; The only TUI call is the ancestry candidate, once for this immutable CLI call.
      (is (= [["pane" "process-info" "--pane" "w:p"]] (cli/calls log)))
      (is (= 1 (count (filter #(= ["get" "t1"] %) (take 1 calls)))))
      (is (zero? (:exit (probe env "status" "process" (:pane-id saved)))))
      (is (= "working" (get-in (cli/result (probe env "status" "agent" (:child saved))) [:result :agent_status]))))))

(deftest hc-cli-prompt-until-working-does-not-wait-for-turn-completion
  (let [{:keys [env hc-log] :as fixture} (hc-env {"FAKE_HC_SEND_WAIT_TIMEOUT" "1"})
        launched (start! fixture) saved (entry fixture (:task launched))]
    (change! fixture assoc-in [(:pane-id saved) :status :state] "idle")
    (let [prompted (cli/call! env "agent" "prompt" (:child saved) "ongoing work"
                             "--wait" "--until" "working" "--timeout" "1000")]
      (is (zero? (:exit prompted)) (:out prompted))
      (is (true? (:ok (cli/result prompted))))
      (is (= "working" (get-in (state fixture) [(:pane-id saved) :status :state])))
      (is (= 1 (count (filter #(= "send" (first %)) (cli/calls hc-log)))))
      (is (not-any? #(and (= "send" (first %)) (some #{"--wait"} %)) (cli/calls hc-log))))))

(deftest hc-run-collect-fanin-and-close-use-the-existing-ledger
  (let [{:keys [env hc-log] :as fixture} (hc-env {})
        run (cli/call! env "task" "run" "scout" "--task" "run fixture" "--timeout" "3000")
        result (get-in (cli/result run) [:result])]
    (is (zero? (:exit run)) (:out run))
    (is (= "COMPLETE" (:status result)))
    (is (= "closed" (get-in (cli/result (cli/call! env "task" "close" (:task result))) [:result :status])))
    (is (some #(= "kill" (first %)) (cli/calls hc-log)))
    (let [first-entry (start! fixture "--worktree" (str (:dir fixture)))
          second-entry (start! fixture "--worktree" (str (:dir fixture)))
          a (entry fixture (:task first-entry)) b (entry fixture (:task second-entry))]
      (change! fixture assoc :publish-queue [(:child b)])
      (let [collected (cli/call! env "task" "collect" "--any" "--wait" "--timeout" "3000")]
        (is (zero? (:exit collected)) (:out collected))
        (is (= (:task b) (get-in (cli/result collected) [:result :task])))
        (is (= "COMPLETE" (get-in (cli/result collected) [:result :status]))))
      (publish! a)
      (is (= "COMPLETE" (get-in (cli/result (cli/call! env "task" "collect" (:task a))) [:result :status])))
      (doseq [entry [a b]]
        (change! fixture assoc-in [(:pane-id entry) :status :state] "idle")
        (is (= "closed" (get-in (cli/result (cli/call! env "task" "close" (:task entry))) [:result :status])))))))

(deftest hc-blocked-send-surfaces-exit-nine-and-never-forces
  (let [{:keys [env hc-log] :as fixture} (hc-env {})
        launched (start! fixture) saved (entry fixture (:task launched))]
    (change! fixture assoc-in [(:pane-id saved) :status :state] "blocked")
    (let [sent (probe env "prompt" (:child saved))]
      (is (= 1 (:exit sent)))
      (is (= 9 (get-in (cli/result sent) [:data :exit])))
      (is (= "blocked" (get-in (cli/result sent) [:data :response :error :code]))))
    (is (= 1 (count (:prompts (get (state fixture) (:pane-id saved))))))
    (is (not-any? #(some #{"--force"} %) (cli/calls hc-log)))
    (change! fixture assoc-in [(:pane-id saved) :status :state] "idle")
    (is (zero? (:exit (probe env "prompt" (:child saved)))))
    (is (= 2 (count (:prompts (get (state fixture) (:pane-id saved))))))))

(deftest hc-pi-parent-publication-skips-unobservable-session-and-still-collects
  ;; A Pi parent is left to its extension watcher before the session gate is even reached;
  ;; a non-Pi parent on hc still hits the unobservable session and is skipped for that.
  (doseq [[app expected-reason] [["pi" "watcher"] ["claude" "session-mismatch"]]
          status ["idle" "working" "blocked" "unknown"]]
    (let [{:keys [env hc-log] :as fixture} (hc-env {})
          launched (start! fixture) saved (entry fixture (:task launched))
          child-env (merge env (get-in (state fixture) [(:pane-id saved) :round-env])
                           {"HC_TERMINAL_ID" (:pane-id saved) "PI_SESSION_FILE" "/child-session.jsonl"
                            "FAKE_HC_PID" (str (get-in (state fixture) [(:pane-id saved) :pid]))
                            "FAKE_ANCESTOR_PIDS" (str (get-in (state fixture) [(:pane-id saved) :pid]) ",111")})]
      ;; Save the root terminal as observed by a different publishing child.
      (change! fixture assoc "t1" {:address "t1" :pid 222 :created_ms 1000 :state "live"
                                   :labels {:herdr.space "space-parent" :herdr.tab "tab-parent"}
                                   :status {:app app :state status}})
      (let [published (cli/call! child-env "task" "publish" "--status" "COMPLETE" "--summary" "published with no push")]
        (is (zero? (:exit published)) (:out published))
        (is (= "COMPLETE" (get-in (cli/result published) [:result :status])))
        (is (= expected-reason (get-in (cli/result published) [:result :parent-push :reason])) (str app " " status))
        (is (= expected-reason (:reason (json/parse-string (slurp (str (:result saved) ".push.json")) true))) (str app " " status)))
      (is (not-any? #(= "send" (first %)) (cli/calls hc-log)))
      ;; Even a stale inherited path equal to the ledger cannot attest another terminal's Pi.
      (when (= app "pi")
        (let [observed (probe (assoc child-env "PI_SESSION_FILE" (:parent-session saved)) "status" "agent" "t1")]
          (is (nil? (get-in (cli/result observed) [:result :agent_session])))))
      (is (= "COMPLETE" (get-in (cli/result (cli/call! env "task" "collect" (:task saved))) [:result :status]))))))

(deftest hc-failed-start-cleans-only-the-new-terminal
  (doseq [status ["blocked" "exited"]]
    (let [{:keys [env hc-log] :as fixture} (hc-env {"FAKE_HC_START_STATUS" status})
          proc (cli/call! env "task" "start" "scout" "--task" "startup failure")]
      (is (= 1 (:exit proc)) (:out proc))
      (is (some #(= "read" (first %)) (cli/calls hc-log)))
      (is (some #(= ["kill" "t2"] %) (cli/calls hc-log)))
      (is (every? #(= "finished" (:state %)) (vals (state fixture)))))))

(deftest hc-continuation-poke-and-ownership-preserve-round-boundaries
  (let [{:keys [env hc-log] :as fixture} (hc-env {"FAKE_HC_PUBLISH" "0"})
        first-round (start! fixture) a (entry fixture (:task first-round))
        foreign (assoc env "PI_SESSION_FILE" "/replacement-parent.jsonl")]
    (change! fixture assoc-in [(:pane-id a) :status :state] "idle")
    (doseq [verb ["poke" "close" "continue"]]
      (let [proc (apply cli/call! foreign "task" verb (:task a)
                        (when (= verb "continue") ["--task" "foreign follow-up"]))]
        (is (= 1 (:exit proc)) [verb (:out proc)])))
    (is (not-any? #(#{"send" "kill"} (first %)) (cli/calls hc-log)))
    (let [poked (cli/call! env "task" "poke" (:task a))]
      (is (zero? (:exit poked)) (:out poked))
      (is (= "poked" (get-in (cli/result poked) [:result :status]))))
    (publish! a)
    (is (= "COMPLETE" (get-in (cli/result (cli/call! env "task" "collect" (:task a))) [:result :status])))
    (let [continued (cli/call! env "task" "continue" (:task a) "--task" "second round")
          b (get-in (cli/result continued) [:result])]
      (is (zero? (:exit continued)) (:out continued))
      (is (= (:task a) (:continues b)))
      (is (= (:pane-id a) (:pane-id b)))
      (is (= (:child a) (:child b)))
      (is (not= (:task a) (:task b)))
      (is (not= (:result a) (:result b)))
      (is (= 1 (count (filter #(= "run" (first %)) (cli/calls hc-log)))))
      (is (= 2 (count (filter #(= "send" (first %)) (cli/calls hc-log)))))
      (is (= 1 (:exit (cli/call! env "task" "close" (:task a)))))
      (publish! b)
      (is (= "COMPLETE" (get-in (cli/result (cli/call! env "task" "collect" (:task b))) [:result :status])))
      (is (= "closed" (get-in (cli/result (cli/call! env "task" "close" (:task b))) [:result :status]))))))

(deftest hc-fork-resolves-caller-pi-session-without-native-session-field
  (let [{:keys [env dir session] :as fixture} (hc-env {})
        forked (str (fs/path dir "forked.jsonl"))
        env (assoc env "HERDR_ORCH_PI_BIN" (str cli/root "/skills/herdr-orch/scripts/test/fixtures/fake-pi")
                   "FAKE_PI_LOG" (str (fs/path dir "pi.log")) "FAKE_PI_FORK_FILE" forked)
        proc (cli/call! env "task" "start" "scout" "--context" "fork" "--task" "fork hc fixture")
        launched (get-in (cli/result proc) [:result])
        saved (when (zero? (:exit proc)) (entry fixture (:task launched)))]
    (is (zero? (:exit proc)) (:out proc))
    (is (= session (get-in saved [:fork :source-session])))
    (is (= forked (get-in saved [:fork :session-file])))
    (when saved
      (let [argv (get-in (state fixture) [(:pane-id saved) :foreground :argv])]
        (is (some #{"--session"} argv))
        (is (some #{forked} argv))
        (is (= 1 (count (get-in (state fixture) [(:pane-id saved) :prompts]))))))))

(deftest hc-collect-reports-blocked-exited-and-missing-children-without-forcing
  (doseq [state ["blocked" "exited" "missing"]]
    (let [{:keys [env hc-log] :as fixture} (hc-env {"FAKE_HC_PUBLISH" "0"})
          launched (start! fixture) saved (entry fixture (:task launched))]
      (case state
        "blocked" (change! fixture assoc-in [(:pane-id saved) :status :state] "blocked")
        "exited" (change! fixture update (:pane-id saved) assoc :state "finished" :status nil :pid nil)
        "missing" (change! fixture dissoc (:pane-id saved)))
      (let [proc (cli/call! env "task" "collect" (:task saved) "--wait" "--timeout" "100")
            result (get-in (cli/result proc) [:result])]
        (is (zero? (:exit proc)) (:out proc))
        (is (= (if (= state "blocked") "blocked" "pending") (:status result)))
        (when-not (= state "blocked")
          (is (= (if (= state "exited") "done" "unknown") (:child-status result)))))
      (is (not-any? #(#{"send" "kill"} (first %)) (cli/calls hc-log))))))

(deftest hc-process-replacement-does-not-inherit-the-owned-agent-name
  (let [{:keys [env hc-log] :as fixture} (hc-env {"FAKE_HC_PUBLISH" "0" "ORCH_SETTLE_CLOSE_MS" "100"})
        launched (start! fixture) saved (entry fixture (:task launched))]
    (publish! saved)
    (is (= "COMPLETE" (get-in (cli/result (cli/call! env "task" "collect" (:task saved))) [:result :status])))
    (change! fixture update (:pane-id saved) #(-> % (update :pid inc) (update-in [:foreground :pid] inc) (assoc-in [:status :state] "idle")))
    (let [closed (cli/call! env "task" "close" (:task saved))]
      (is (zero? (:exit closed)) (:out closed))
      (is (= "gone" (get-in (cli/result closed) [:result :status]))))
    (is (not-any? #(= "kill" (first %)) (cli/calls hc-log)))))

(deftest hc-missing-executable-and-start-readiness-fail-clearly
  (with-redefs [fs/which (constantly nil)]
    (let [error (try (hc/invoke! ["get" "t1"]) nil (catch Exception e e))]
      (is (= "missing_executable" (get-in (ex-data error) [:response :error :code])))
      (is (str/includes? (.getMessage error) "not on PATH"))))
  (doseq [[state app code] [["unknown" nil "timeout"] ["blocked" "pi" "agent_not_ready"]
                           ["idle" "codex" "agent_kind_mismatch"] ["idle" "pi" nil]]]
    (with-redefs [hc/terminal! (constantly {:address "t2" :created_ms 2 :pid 2 :state "live"
                                          :status {:state state :app app}})
                  hc/read! (fn [& _] "startup screen")]
      (let [outcome (try (#'hc/ready! "t2" "pi" (if (= state "unknown") 0 100)) :ready
                         (catch Exception e (get-in (ex-data e) [:response :error :code])))]
        (is (= (or code :ready) outcome))))))

(deftest hc-native-prompt-transport-retains-pi-claude-and-codex-arguments
  (doseq [app ["pi" "claude-code" "codex"]]
    (let [{:keys [env] :as fixture} (hc-env {"FAKE_HC_KIND" app})
          launched (start! fixture)
          saved (entry fixture (:task launched))
          native (get-in (state fixture) [(:pane-id saved) :foreground :argv])]
      (is (= (if (= app "claude-code") "claude" app) (first native)))
      (is (= "--" (nth native (- (count native) 2))))
      (is (str/includes? (peek native) "hc assignment sentinel"))
      (is (= 1 (count (get-in (state fixture) [(:pane-id saved) :prompts])))))))

(deftest hc-children-outlive-parent-program-exit
  (let [{:keys [env] :as fixture} (hc-env {})
        launched (start! fixture) saved (entry fixture (:task launched))
        parent-exit @(process/process [(str (fs/path (:dir fixture) "hc")) "kill" "t1"] {:env env :out :string :err :string})]
    (is (zero? (:exit parent-exit)))
    (is (= "finished" (get-in (state fixture) ["t1" :state])))
    (is (= "live" (get-in (state fixture) [(:pane-id saved) :state])))
    (is (zero? (:exit (probe env "close" (:pane-id saved)))))
    (is (= "finished" (get-in (state fixture) [(:pane-id saved) :state])))))

(deftest hc-managed-worktree-launch-and-publication-retain-checkout-semantics
  (let [{:keys [env dir] :as fixture} (hc-env {})
        launched (start! fixture "--worktree" "new") saved (entry fixture (:task launched))
        checkout (:work-root saved)
        terminal (get (state fixture) (:pane-id saved))
        child-env (merge env (:round-env terminal)
                         {"HC_TERMINAL_ID" (:pane-id saved) "PI_SESSION_FILE" "/worktree-child.jsonl"
                          "FAKE_ANCESTOR_PIDS" (str (:pid terminal) ",111")})]
    (is (not= (str dir) checkout))
    (is (fs/directory? checkout))
    (is (= checkout (:cwd terminal)))
    (is (= checkout (get-in terminal [:round-env "HERDR_ORCH_WORK_ROOT"])))
    (let [published (cli/call-in! checkout child-env "task" "publish" "--status" "COMPLETE" "--summary" "checkout completed")]
      (is (zero? (:exit published)) (:out published))
      (is (seq (get-in (cli/result published) [:result :checkpoint]))))
    (is (= "COMPLETE" (get-in (cli/result (cli/call! env "task" "collect" (:task saved))) [:result :status])))))

(defn- app-names [fixture]
  (let [path (str (fs/path (:hc-state fixture) "app-tab-names.edn"))]
    (if (fs/exists? path) (edn/read-string (slurp path)) {})))

(deftest hc-tab-child-and-root-tabs-get-names
  (let [{:keys [env] :as fixture} (hc-env {"FAKE_HERDR_APP_LOG" "/dev/null"})
        tabbed (entry fixture (:task (start! fixture "--tab")))
        split (entry fixture (:task (start! fixture "--split")))
        names (app-names fixture)]
    (testing "a tab child's tab carries its label; a split leaves its parent's tab alone"
      (is (= (:label tabbed) (:tab-name tabbed) (get names (:tab-id tabbed))))
      (is (nil? (:tab-name split)))
      (is (nil? (:tab-name-error split))))
    (testing "the root's tab is named once, and the root terminal gets the same oh.label"
      (is (= "orch-0" (get names "tab-parent")))
      (is (= {:tab "tab-parent" :name "orch-0" :named-by "oh"} (:root-tab tabbed)))
      (is (= {:tab "tab-parent" :name "orch-0" :named-by "user"} (:root-tab split)))
      (is (= "orch-0" (get-in (state fixture) ["t1" :labels :oh.label]))))
    (is (= 2 (count (filter #(= "run" (first %)) (cli/calls (:hc-log fixture))))))
    env))

(deftest hc-root-tab-takes-the-lowest-free-number-and-keeps-a-users-name
  (let [fixture (hc-env {"FAKE_HERDR_APP_TABS" (pr-str {"tab-other" "orch-0"})})
        saved (entry fixture (:task (start! fixture "--split")))]
    (is (= "orch-1" (get-in saved [:root-tab :name])))
    (is (= "orch-1" (get (app-names fixture) "tab-parent"))))
  (let [fixture (hc-env {"FAKE_HERDR_APP_TABS" (pr-str {"tab-parent" "my work"})})
        saved (entry fixture (:task (start! fixture "--split")))]
    (is (= {:tab "tab-parent" :name "my work" :named-by "user"} (:root-tab saved)))
    (is (empty? (app-names fixture)))
    (is (nil? (get-in (state fixture) ["t1" :labels :oh.label])) "no label without a tab name from oh")))

(deftest hc-tab-naming-failures-are-recorded-and-never-fail-the-spawn
  (let [fixture (hc-env {"FAKE_HERDR_APP_FAIL" "1"})
        saved (entry fixture (:task (start! fixture "--tab")))]
    (is (= "prompted" (:status saved)))
    (is (str/includes? (:tab-name-error saved) "fake herdr-app failure"))
    (is (str/includes? (:root-tab-error saved) "fake herdr-app failure"))
    (is (nil? (get-in (state fixture) ["t1" :labels :oh.label]))))
  (let [fixture (hc-env {"FAKE_HERDR_APP_HIDE" "1"})
        saved (entry fixture (:task (start! fixture "--tab")))]
    (is (= "prompted" (:status saved)))
    (is (str/includes? (:tab-name-error saved) "no tab for the new terminal"))
    (is (= "orch-0" (get-in saved [:root-tab :name])))))

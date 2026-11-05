(ns herdr-orch.hc-prompt-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.test :refer [deftest is testing]]
            [herdr-orch.hc :as hc]
            [herdr-orch.hc-test :as fixtures]
            [herdr-orch.runtime :as runtime]))

(def terminal {:address "t2" :created_ms 2 :pid 2 :state "live"
               :status {:app "pi" :state "idle"}})
(defn observe [value]
  (if (string? value) (assoc-in terminal [:status :state] value) (merge terminal value)))
(defn prompt-scenario
  [{:keys [before after native send-delay opts]
    :or {before "idle" after ["working"] native {:turn 1}
         send-delay 0 opts {:wait true :until ["working"] :timeout 200}}}]
  (let [sent? (atom false) reads (atom 0) calls (atom [])
        snapshots (mapv observe after)
        outcome
        (with-redefs [hc/terminal! (fn [_]
                                    (if @sent?
                                      (let [index (swap! reads inc)]
                                        (get snapshots (min (dec index) (dec (count snapshots)))))
                                      (observe before)))
                      hc/invoke! (fn [argv]
                                   (swap! calls conj argv)
                                   (when (pos? send-delay) (Thread/sleep send-delay))
                                   (reset! sent? true)
                                   (if (= before "blocked")
                                     (throw (ex-info "blocked" {:exit 9 :response {:error {:code "blocked"}}}))
                                     ;; Native --wait cannot finish an ongoing working turn.
                                     (if (some #{"--wait"} argv)
                                       (assoc native :wait (or (:wait native) {:reason "timeout" :status "working" :ended false}))
                                       (dissoc native :wait))))]
          (try {:result (runtime/prompt-runtime! (runtime/for-backend! :hc) "child-name" "task" opts)}
               (catch Exception e {:error (get-in (ex-data e) [:response :error :code]) :data (ex-data e)})))]
    (assoc outcome :calls @calls :reads @reads)))

(deftest requested-working-matches-without-waiting-for-the-turn-to-end
  (doseq [[before native] [["idle" {:turn 1}] ["working" {:turn 1}]
                           ["working" {:turn 1 :started false}]]]
    (let [result (prompt-scenario {:before before :native native :after ["working"]})]
      (is (nil? (:error result)) result)
      (is (pos? (:reads result)))
      (is (= [["send" "t2" "task" "--json"]] (:calls result)))))
  (let [working (observe "working")]
    (with-redefs [hc/terminal! (constantly working)]
      (is (true? (:ok (hc/wait! "t2" 100 ["working"])))))))

(deftest a-matching-idle-state-does-not-prove-a-new-turn-started
  (let [result (prompt-scenario {:after ["idle"] :native {:turn 1 :wait {:reason "status" :status "idle" :ended true}}
                                 :opts {:wait true :until ["idle"] :timeout 40}})]
    (is (= "timeout" (:error result)) result))
  (let [result (prompt-scenario {:after ["idle" "working" "idle"]
                                 :opts {:wait true :until ["idle"] :timeout 400}})]
    (is (nil? (:error result)) result)
    (is (<= 3 (:reads result))))
  (testing "native status at submission takes precedence over an earlier working snapshot"
    (is (= "timeout" (:error (prompt-scenario {:before "working" :after ["idle"]
                                              :native {:turn 1 :status "idle"}
                                              :opts {:wait true :until ["idle"] :timeout 40}})))))
  (testing "an already-working native submission needs no new start"
    (is (nil? (:error (prompt-scenario {:native {:turn 1 :status "working" :started false}})))))
  (testing "an unreported native status does not inherit stale activity"
    (is (= "timeout" (:error (prompt-scenario {:before "working" :after ["unknown"]
                                              :native {:turn 1 :status nil}
                                              :opts {:wait true :until ["unknown"] :timeout 40}}))))))

(deftest prompt-start-evidence-and-blocking-stay-distinct-from-state-matching
  (testing "a reported short turn can finish before the next local observation"
    (is (nil? (:error (prompt-scenario {:after ["idle"] :native {:turn 1 :started true}
                                       :opts {:wait true :until ["idle"] :timeout 200}})))))
  (testing "native start-stall remains a refusal"
    (is (= "agent_prompt_stalled" (:error (prompt-scenario {:native {:turn 1 :started false}})))))
  (testing "a caller deadline exhausted during submission wins over start-stall"
    (is (= "timeout" (:error (prompt-scenario {:send-delay 50 :native {:turn 1 :started false}
                                              :opts {:wait true :until ["working"] :timeout 20}})))))
  (testing "a pre-blocked target surfaces native exit 9 and is never forced"
    (let [result (prompt-scenario {:before "blocked"})]
      (is (= "blocked" (:error result)))
      (is (= 9 (get-in result [:data :exit])))
      (is (not-any? #(some #{"--force"} %) (:calls result)))))
  (testing "a newly blocked target establishes activity and matches only the requested state"
    (is (nil? (:error (prompt-scenario {:after ["blocked"] :opts {:wait true :until ["blocked"] :timeout 200}})))))
  (testing "an excluded blocked state does not satisfy working"
    (is (= "timeout" (:error (prompt-scenario {:after ["blocked"] :opts {:wait true :until ["working"] :timeout 40}}))))))

(deftest waiting-prompt-pins-the-pre-submit-process-through-every-phase
  (doseq [replacement [{:pid 3} {:created_ms 3} {:address "t3"}]]
    (let [result (prompt-scenario {:before "working" :after [(assoc replacement :status {:app "pi" :state "working"})]})]
      (is (= "agent_replaced" (:error result)) result)))
  (let [result (prompt-scenario {:after ["working" {:pid 3 :status {:app "pi" :state "idle"}}]
                                 :opts {:wait true :until ["idle"] :timeout 300}})]
    (is (= "agent_replaced" (:error result)) result))
  (is (= "finished" (:error (prompt-scenario {:after [{:state "finished"}]})))))

(deftest waiting-prompt-uses-one-caller-deadline-including-submission
  (let [result (prompt-scenario {:send-delay 50 :after ["working"]
                                 :native {:turn 1 :started true :wait {:reason "status" :status "idle" :ended true}}
                                 :opts {:wait true :until ["working"] :timeout 20}})]
    (is (= "timeout" (:error result)) result))
  (let [result (prompt-scenario {:before "working" :after ["working"]
                                 :opts {:wait true :until ["idle"] :timeout 40}})]
    (is (= "timeout" (:error result)) result)))

(deftest a-non-working-target-without-start-evidence-stalls-without-a-caller-timeout
  (let [result (prompt-scenario {:after ["idle"] :opts {:wait true :until ["idle"]}})]
    (is (= "agent_prompt_stalled" (:error result)) result)))

(deftest a-slow-native-client-cannot-reset-or-outlive-the-caller-budget
  (let [fixture (fixtures/hc-env {"FAKE_HC_SEND_DELAY_MS" "2000"})
        executable (str (fs/path (:dir fixture) "hc"))
        spawn process/process
        started (System/nanoTime)
        outcome (with-redefs [fs/which (constantly executable)
                              process/process (fn [argv opts]
                                                (spawn (assoc (vec argv) 0 executable)
                                                       (assoc opts :env (:env fixture))))]
                  (try (hc/prompt! "t1" "task" {:wait true :until ["working"] :timeout 200})
                       (catch Exception e (get-in (ex-data e) [:response :error :code]))))
        elapsed-ms (/ (- (System/nanoTime) started) 1000000)]
    (is (= "timeout" outcome))
    (is (re-find #"(?m)^send" (slurp (:hc-log fixture))))
    (is (< elapsed-ms 1000) (str "caller budget escaped: " elapsed-ms " ms"))))

(deftest non-waiting-prompt-keeps-the-native-submission-path
  (let [native {:turn 1} result (prompt-scenario {:native native :opts {}})]
    (is (= native (:result result)))
    (is (= 0 (:reads result)))
    (is (= [["send" "child-name" "task" "--json"]] (:calls result)))))

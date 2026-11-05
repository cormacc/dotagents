;; A deferred-allocation runtime: no TUI adapter or raw operation can satisfy this test.
(require '[cheshire.core :as json]
         '[herdr-orch.cli :as cli]
         '[herdr-orch.herdr :as herdr]
         '[herdr-orch.runtime :as runtime])

(def events (atom []))
(def child (atom nil))
(def launch-env (atom nil))
(defn event! [operation] (swap! events conj operation))
(def parent {:name "parent" :pane_id "t:parent" :agent "pi" :agent_status "idle"
             :agent_session {:value "session" :kind "path"}})
(defn publish! []
  (let [env @launch-env]
    (spit (env "HERDR_ORCH_RESULT")
          (str "--- HERDR RESULT v1 ---\nCHILD: " (env "HERDR_ORCH_CHILD")
               "\nTASK: " (env "HERDR_ORCH_TASK") "\nRESULT: " (env "HERDR_ORCH_RESULT")
               "\nWORK-ROOT: " (env "HERDR_ORCH_WORK_ROOT")
               "\nSTATUS: COMPLETE\nSUMMARY: semantic completed\nARTIFACTS:\n- none\nFINDINGS:\n- none\nNEXT: none\n--- END HERDR RESULT ---\n"))))
(def fake-runtime
  (reify runtime/Runtime
    (preflight-runtime! [_] (event! :preflight) true)
    (identity! [this] (runtime/identity! this {}))
    (identity! [_ _]
      (event! :identity)
      {:parent-session "session" :parent-kind "pi" :parent-pane "t:parent" :parent-session-kind "path"})
    (place! [_ request]
      (event! :place)
      (when (:target request) (throw (ex-info "deferred placement must not prepare an allocated TUI shell" {})))
      request)
    (launch! [_ {:keys [name prompt placement]}]
      (event! :launch)
      (when-not (and (seq prompt) (get-in placement [:env "HERDR_ORCH_TASK"]))
        (throw (ex-info "launch needs the initial prompt and placement environment" {})))
      (reset! launch-env (:env placement))
      (reset! child {:name name :pane_id "t:child" :agent "pi" :agent_status "working"})
      {:pane_id "t:child" :shell_pid 222 :prompt-delivered? true})
    (prompt-runtime! [_ target _ _]
      (event! :prompt)
      (when (= target (:name @child))
        (throw (ex-info "initial prompt was delivered twice" {}))))
    (wait-runtime! [_ _ _ _]
      (event! :wait)
      (publish!)
      (swap! child assoc :agent_status "idle")
      {:ok true :value {:result {:agent @child}}})
    (read-runtime! [_ _ _] (event! :read) "semantic transcript")
    (status! [_ {:keys [scope target]}]
      (event! :status)
      (case scope
        :agents [parent @child]
        :agent (if (= target "t:parent") parent @child)
        :terminal {:pane_id target :label "parent"}
        :process {:shell_pid 222 :foreground_process_group_id 222}))
    (close-runtime! [_ target]
      (event! :close)
      (when-not (= target "t:child") (throw (ex-info "wrong close target" {:target target})))
      {})
    (notify-runtime! [_ _ _] (event! :notify) {})))

(with-redefs [runtime/for-backend! (fn [backend]
                                   (when-not (= :hc backend)
                                     (throw (ex-info "semantic fixture requires detected hc" {:backend backend})))
                                   fake-runtime)
              herdr/invoke (fn [& _] (throw (ex-info "semantic path called the TUI adapter" {})))]
  ;; The shared gate must allow a supported hc runtime, even with leaked TUI variables.
  (cli/execute ["roster" "--format" "json"])
  (when-not (= "t:parent" (:pane-id (cli/execute [])))
    (throw (ex-info "home view used a leaked TUI identity" {})))
  (let [raw-error (try (cli/execute ["pane" "current"]) nil (catch Exception e (.getMessage e)))
        result (cli/execute ["task" "run" "scout" "--task" "semantic fixture" "--timeout" "2000"])
        task (:task result)
        transcript (cli/execute ["agent" "read" (:name @child)])
        _ (cli/execute ["agent" "prompt" "t:parent" "advisory"])
        _ (#'cli/publish-failure-toast! "fixture" task (ex-info "fixture" {}))
        status (cli/execute ["task" "status" task])
        closed (cli/execute ["task" "close" task])]
    (println (json/generate-string {:result result :transcript transcript :status status :closed closed
                                   :raw-error raw-error :events @events}))))

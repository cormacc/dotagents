(ns herdr-orch.hc
  "Local herdr-core adapter for the delegation runtime."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [herdr-orch.core :as core]
            [herdr-orch.herdr :as output]))

(defn- decode [text]
  (try (json/parse-string text true) (catch Exception _ nil)))
(defn- failure [code message data]
  (ex-info message (merge {:kind :hc :response {:error {:code code}}} data)))
(defn- local! [target]
  (when (or (str/blank? target) (str/includes? target "/"))
    (throw (failure "unsupported_target" "hc delegation requires a local terminal address" {:target target})))
  target)
(def ^:dynamic ^:private *prompt-deadline* nil)
(defn invoke! [argv]
  (when-not (fs/which "hc")
    (throw (failure "missing_executable" "hc is not on PATH; install herdr-core before using this runtime" {})))
  (let [_ (when (and *prompt-deadline* (>= (System/currentTimeMillis) *prompt-deadline*))
            (throw (failure "timeout" "hc prompt wait timed out" {:argv argv})))
        proc (process/process (into ["hc"] argv) {:out :string :err :string})
        finished (if *prompt-deadline*
                   (deref proc (max 0 (- *prompt-deadline* (System/currentTimeMillis))) ::timeout)
                   @proc)
        _ (when (= ::timeout finished)
            (process/destroy-tree proc)
            (throw (failure "timeout" "hc prompt wait timed out" {:argv argv})))
        {:keys [exit out err]} finished
        value (decode out)]
    (if (and (zero? exit) (map? value) (not= false (:ok value)) (not (:error value)))
      value
      (let [response (or (decode err) value)
            kind (or (:kind response) (get-in response [:error :data :kind])
                     (get-in response [:data :kind]) (get-in response [:error :kind]))
            code (if (= "not_found" kind) "pane_not_found" (or kind "hc_error"))]
        (throw (failure code (str "hc " (first argv) " failed: " (or (:message response) (:error response) (not-empty err) out))
                        {:argv argv :exit exit :stderr err :native-response response}))))))
(defn terminal! [target]
  (let [terminal (invoke! ["get" (local! target)])]
    (when-not (and (seq (:address terminal)) (number? (:created_ms terminal))
                   (#{"live" "finished"} (:state terminal)))
      (throw (failure "invalid_response" "hc get returned an unusable terminal" {:response terminal})))
    terminal))
(defn- kind-of [terminal]
  (let [app (get-in terminal [:status :app])]
    (when-not (str/blank? app) (if (= "claude-code" app) "claude" app))))
(defn- session [terminal]
  (when-let [kind (kind-of terminal)]
    (if (= "pi" kind)
      ;; A tool subprocess can attest only its own Pi session, never a remote parent.
      ;; No cached terminal label is a fresh observation of that parent's session.
      (when (= (:address terminal) (System/getenv "HC_TERMINAL_ID"))
        (when-let [path (not-empty (System/getenv "PI_SESSION_FILE"))]
          {:agent kind :kind "path" :source "PI_SESSION_FILE" :value path}))
      {:agent kind :kind "id" :source "hc" :value (str (:address terminal) ":" (:created_ms terminal))})))
(defn- process-witness [terminal] [(:created_ms terminal) (:pid terminal)])
(defn agent-info [terminal]
  (let [kind (kind-of terminal)
        recorded (get-in terminal [:labels :oh.process])
        same-process? (or (nil? recorded) (= recorded (pr-str (process-witness terminal))))]
    {:pane_id (:address terminal) :name (when same-process? (:name terminal)) :agent kind
     :agent_status (if (= "finished" (:state terminal)) "done" (or (get-in terminal [:status :state]) "unknown"))
     :cwd (or (get-in terminal [:foreground :cwd]) (:cwd terminal))
     :label (get-in terminal [:labels :oh.label]) :agent_session (session terminal)
     :created_ms (:created_ms terminal)}))
(defn identity!
  ([] (identity! {}))
  ([{:keys [local?]}]
   (let [address (not-empty (System/getenv "HC_TERMINAL_ID"))]
     (if local?
       {:parent-pane address}
       (let [terminal (terminal! address) agent (agent-info terminal)
             kind (:agent agent) parent-session (:agent_session agent)]
         (when-not (and (= "live" (:state terminal)) kind parent-session)
           (throw (failure "agent_not_found" "hc caller needs a recognised agent and its current session identity" {:target address})))
         (when-not (and (seq (get-in terminal [:labels :herdr.space])) (seq (get-in terminal [:labels :herdr.tab])))
           (throw (failure "missing_placement" "hc caller has no herdr.space/herdr.tab labels" {:target address})))
         {:parent-pane (:address terminal) :parent-kind kind
          :parent-session (:value parent-session) :parent-session-kind (:kind parent-session)})))))
(defn preflight! []
  (local! (System/getenv "HC_TERMINAL_ID"))
  (identity!)
  true)
(defn place! [{:keys [target label mode] :as request}]
  (if target
    (do (invoke! ["label" (local! target) (str "oh.label=" label)])
        (assoc (agent-info (terminal! target)) :label label))
    (let [parent (terminal! (System/getenv "HC_TERMINAL_ID"))
          space (get-in parent [:labels :herdr.space])
          tab (get-in parent [:labels :herdr.tab])]
      (when-not (and (seq space) (seq tab) (= :tab mode))
        (throw (failure "missing_placement" "hc placement needs the parent's herdr.space/herdr.tab labels" {:mode mode})))
      ;; Label adoption creates the pane in this space even if another tab has focus.
      (assoc request :parent (:address parent) :space space
             :tab (str (java.util.UUID/randomUUID))))))
(defn read! [target {:keys [source lines]}]
  (output/truncate-output
   (:text (invoke! (cond-> ["read" (local! target) "--json"]
                    (not= "visible" source) (into ["--lines" (str (or lines 2000))]))))))
(defn close! [target]
  (when (= target (System/getenv "HC_TERMINAL_ID"))
    (throw (failure "caller_terminal" "Refusing to close the terminal the caller is running in." {:target target})))
  (invoke! ["kill" (local! target)]))
(defn- ready! [address kind timeout]
  (let [deadline (+ (System/currentTimeMillis) timeout)]
    (loop []
      (let [terminal (terminal! address) agent (agent-info terminal) state (:agent_status agent)
            code (cond
                   (= "finished" (:state terminal)) "agent_start_failed"
                   (= "blocked" state) "agent_not_ready"
                   (and (:agent agent) (not= kind (:agent agent))) "agent_kind_mismatch"
                   (>= (System/currentTimeMillis) deadline) "timeout")]
        (cond
          code (throw (failure code (str "hc agent did not become ready: " code)
                               {:target address :agent agent :output (read! address {:source "visible"})}))
          (and (= kind (:agent agent)) (#{"idle" "working" "done"} state)) terminal
          :else (do (Thread/sleep 100) (recur)))))))
;; Tabs belong to the herdr-app window, not to the core, so `hc` cannot name one; the
;; app's own CLI can. Each call is bounded: a window that does not answer must not hold
;; a delegation. Tab naming is display only, so callers record a failure and continue.
(def ^:private app-timeout-ms 5000)
(defn- app-invoke! [argv]
  (let [bin (fs/which "herdr-app")
        _ (when-not bin (throw (failure "missing_executable" "herdr-app is not on PATH" {})))
        proc (process/process (into [(str bin)] argv) {:out :string :err :string})
        finished (deref proc app-timeout-ms ::timeout)]
    (when (= ::timeout finished)
      (process/destroy-tree proc)
      (throw (failure "timeout" "herdr-app did not answer" {:argv argv})))
    (let [{:keys [exit out err]} finished value (decode out)]
      (if (and (zero? exit) (map? value) (not (:error value)))
        value
        (throw (failure "herdr_app_error" (str "herdr-app " (first argv) " failed: " (or (:error value) (not-empty err) out))
                        {:argv argv :exit exit}))))))
;; `herdr-app ls` lists only the open window's spaces, with local terminal addresses
;; unprefixed, so a local address identifies its tab directly.
(defn- app-tab-of [address]
  (first (for [space (:spaces (app-invoke! ["ls"]))
               tab (:tabs space)
               :when (some #(= address (:terminal %)) (:terminals tab))]
           {:tab tab :space space})))
(defn- app-rename-tab! [tab tab-name]
  (app-invoke! ["do" "tab.rename" (json/generate-string {:id tab :name tab-name})])
  tab-name)
(defn- name-child-tab! [address label]
  ;; The window adopts the new terminal into a tab after `hc run` returns.
  (let [deadline (+ (System/currentTimeMillis) 3000)]
    (loop []
      (if-let [{:keys [tab]} (app-tab-of address)]
        (app-rename-tab! (:id tab) label)
        (if (< (System/currentTimeMillis) deadline)
          (do (Thread/sleep 100) (recur))
          (throw (failure "tab_not_found" "herdr-app shows no tab for the new terminal" {:target address})))))))
(defn name-root-tab!
  "Name the caller's tab `orch-<n>` (lowest free in its space) when nobody named it, and
  give the caller's terminal the same `oh.label` when it has none, so space lists that
  name agents by that label show it."
  []
  (let [address (local! (System/getenv "HC_TERMINAL_ID"))
        {:keys [tab space]} (or (app-tab-of address)
                                (throw (failure "tab_not_found" "herdr-app shows no tab for the caller" {:target address})))]
    (if-not (core/unnamed-tab? (:name tab))
      {:tab (:id tab) :name (:name tab) :named-by "user"}
      (let [tab-name (app-rename-tab! (:id tab) (core/orch-tab-name (map :name (:tabs space))))]
        (when (str/blank? (get-in (terminal! address) [:labels :oh.label]))
          (invoke! ["label" address (str "oh.label=" tab-name)]))
        {:tab (:id tab) :name tab-name :named-by "oh"}))))
(defn launch! [{:keys [name kind native-args prompt placement]}]
  (when-not placement
    (throw (failure "missing_placement" "hc launch requires a deferred placement" {})))
  (let [{:keys [cwd env label parent space tab]} placement
        argv (into ["run" "--json" "--bg" "--no-notify" "--outlive-parent"
                    "--name" name "--cwd" cwd "--parent" parent
                    "--label" (str "herdr.space=" space) "--label" (str "herdr.tab=" tab)
                    "--label" (str "oh.label=" label) "--" "env"]
                   (concat (map (fn [[k v]] (str k "=" v)) (sort-by key env))
                           [kind] native-args ["--" prompt]))
        terminal (invoke! argv) address (:address terminal)]
    (when-not (seq address)
      (throw (failure "invalid_response" "hc run did not return a terminal address" {:response terminal})))
    (try
      (let [ready (ready! address kind 30000)
            witness (process-witness ready)]
        (invoke! ["label" address (str "oh.process=" (pr-str witness))])
        (cond-> (assoc (agent-info ready) :tab-id tab :shell_pid witness :prompt-delivered? true)
          ;; A split shares its parent's tab, which keeps the parent's name.
          (= :tab (:mode placement))
          (merge (try {:tab-name (name-child-tab! address label)}
                      (catch Exception e {:tab-name-error (ex-message e)})))))
      ;; Deferred launch owns the terminal until it returns its address to the ledger.
      (catch Exception e (try (close! address) (catch Exception _)) (throw e)))))
(defn wait! [target timeout until]
  (try
   (let [deadline (when timeout (+ (System/currentTimeMillis) timeout))
        settled (if (seq until) (set until) #{"idle" "done" "blocked"})
        initial (terminal! target)
        witness (select-keys initial [:address :created_ms :pid])]
      (loop []
        (let [terminal (terminal! target) agent (agent-info terminal)
              remaining (if deadline (- deadline (System/currentTimeMillis)) 1000)]
          (cond
            (not= witness (select-keys terminal [:address :created_ms :pid]))
            {:ok false :error {:response {:error {:code "agent_replaced"}}}}
            (or (= "finished" (:state terminal)) (settled (:agent_status agent)))
            {:ok true :value {:result {:agent agent}}}
            (<= remaining 0) {:ok false :error {:response {:error {:code "timeout"}}}}
            :else (do (invoke! ["wait" (local! target) "--json" "--timeout" (str (/ (min remaining 1000) 1000.0))])
                      ;; A blocked status excluded by `until` must not busy-loop.
                      (Thread/sleep (min 50 remaining))
                      (recur))))))
    (catch Exception e {:ok false :error (ex-data e)})))
(defn- prompt-deadline! [deadline target]
  (when (and deadline (>= (System/currentTimeMillis) deadline))
    (throw (failure "timeout" "hc prompt wait timed out" {:target target}))))
(defn- await-prompt! [initial outcome deadline until]
  (let [target (:address initial)
        witness (select-keys initial [:address :created_ms :pid])
        matching (if (seq until) (set until) #{"idle" "done" "blocked"})
        already-working? (= "working" (if (contains? outcome :status)
                                        (:status outcome)
                                        (get-in initial [:status :state])))
        start-deadline (+ (System/currentTimeMillis) 5000)]
    (loop [active? (or already-working? (true? (:started outcome)))]
      (prompt-deadline! deadline target)
      (let [terminal (terminal! target)
            state (:agent_status (agent-info terminal))
            now (System/currentTimeMillis)
            active? (or active? (#{"working" "blocked"} state))]
        (prompt-deadline! deadline target)
        (cond
          (not= witness (select-keys terminal [:address :created_ms :pid]))
          (throw (failure "agent_replaced" "hc prompt target's process changed" {:target target}))
          (= "finished" (:state terminal))
          (throw (failure "finished" "hc prompt target's program ended" {:target target}))
          (or (and (not already-working?) (false? (:started outcome)))
              (and (not active?) (>= now start-deadline)))
          (throw (failure "agent_prompt_stalled" "hc prompt did not start a turn" {:target target}))
          (and active? (matching state)) outcome
          :else (do (Thread/sleep (max 1 (min 50
                                             (if deadline (- deadline now) 50)
                                             (if active? 50 (- start-deadline now)))))
                    (recur active?)))))))
(defn prompt! [target text {:keys [wait timeout until]}]
  (if-not wait
    (invoke! ["send" (local! target) text "--json"])
    (let [deadline (when timeout (+ (System/currentTimeMillis) timeout))]
      (binding [*prompt-deadline* deadline]
        (let [initial (terminal! target)
              _ (prompt-deadline! deadline target)
              ;; Native --wait waits for turn completion, so it cannot observe an ongoing
              ;; requested working state. Poll after submission, pinned to this process.
              outcome (invoke! ["send" (local! (:address initial)) text "--json"])]
          (await-prompt! initial outcome deadline until))))))
(defn status! [{:keys [scope target]}]
  (case scope
    :agent (agent-info (terminal! target))
    :terminal (let [terminal (terminal! target)]
                (when (= "finished" (:state terminal))
                  (throw (failure "pane_not_found" "hc terminal's program has ended" {:target target})))
                (agent-info terminal))
    ;; `hc ls` rows are summaries: a bare status string and no created_ms, pid or labels.
    ;; Re-read each local program row with `hc get`, so that liveness, kind and the
    ;; process witness come from the same shape as a single-terminal read.
    :agents (let [terminals (:terminals (invoke! ["ls" "--all"]))]
              (when-not (and (vector? terminals) (every? #(string? (:address %)) terminals))
                (throw (failure "invalid_response" "hc ls returned an unusable terminal listing" {})))
              (into [] (comp (filter #(and (not (str/includes? (:address %) "/")) (not (str/blank? (:agent %)))))
                             (keep #(try (terminal! (:address %))
                                         (catch clojure.lang.ExceptionInfo e
                                           ;; A terminal removed between ls and get is absent, not an error.
                                           (when-not (= "pane_not_found" (get-in (ex-data e) [:response :error :code]))
                                             (throw e)))))
                             (filter kind-of)
                             (map agent-info))
                    terminals))
    :process (let [terminal (terminal! target)]
               {:shell_pid (process-witness terminal)
                :foreground_process_group_id (when-let [pid (get-in terminal [:foreground :pid])]
                                               [(:created_ms terminal) pid])
                :created_ms (:created_ms terminal)})))
(defn notify! [_title _body]
  ;; known-limit: hc has no operator-toast primitive; revisit when hc exposes one.
  ;; Results and verified parent pushes remain independent of operator notifications.
  {:shown false :reason "hc-notification-unavailable"})

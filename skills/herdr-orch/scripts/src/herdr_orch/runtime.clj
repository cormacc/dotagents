(ns herdr-orch.runtime
  "Terminal needs for delegation; raw TUI control is a separate, guarded escape hatch."
  (:require [herdr-orch.backend :as backend]
            [herdr-orch.core :as core]
            [herdr-orch.herdr :as herdr]
            [herdr-orch.hc :as hc]))

(defprotocol Runtime
  (preflight-runtime! [runtime])
  (identity! [runtime] [runtime opts]
    "Return parent identity; :local? requests only :parent-pane without a host lookup.")
  (place! [runtime request]
    "Plan or allocate by :mode (tab/split), cwd, label and env, or prepare :target's label.
    Return an opaque placement descriptor, with :pane_id and optional :tab-id if allocated.")
  (launch! [runtime request]
    "Start name/kind using :placement (or allocated :pane) and :native-args.
    Return :pane_id when allocating here, optional :tab-id, :shell_pid and :agent_session.
    Deliver :prompt at launch and return :prompt-delivered? true, or leave it for prompt-runtime!.")
  (prompt-runtime! [runtime target text opts])
  (wait-runtime! [runtime target timeout until])
  (read-runtime! [runtime target opts])
  (status! [runtime request]
    "Inspect :scope agent, agents, terminal or process, with optional :target.
    Preserve ledger-compatible agent/session, pane_id, label and process witness fields;
    absence errors use :response :error :code pane_not_found.")
  (close-runtime! [runtime target])
  (notify-runtime! [runtime title body]))

(defrecord TuiRuntime []
  Runtime
  (preflight-runtime! [_] (herdr/preflight!))
  (identity! [this] (identity! this {}))
  (identity! [_ {:keys [local?]}]
    (if local?
      {:parent-pane (not-empty (System/getenv "HERDR_PANE_ID"))}
      (let [agent (herdr/agent! (System/getenv "HERDR_PANE_ID"))]
        {:parent-session (or (get-in agent [:agent_session :value]) (:pane_id agent))
         :parent-kind (:agent agent) :parent-pane (:pane_id agent)
         :parent-session-kind (get-in agent [:agent_session :kind])})))
  (place! [_ {:keys [mode target label] :as request}]
    (if target
      (herdr/rename! target label)
      (case mode
        :tab (herdr/tab-create! request)
        :split (herdr/split! (assoc request :direction (core/direction (herdr/caller-rect!)))))))
  (launch! [_ {:keys [name kind pane native-args]}] (herdr/start! name kind pane native-args))
  (prompt-runtime! [_ target text opts]
    (if (seq opts) (herdr/prompt! target text opts) (herdr/prompt! target text)))
  (wait-runtime! [_ target timeout until]
    (cond
      (empty? until) (herdr/wait! target timeout)
      (= ["idle" "done"] until) (herdr/wait-settled! target timeout)
      :else (herdr/agent-wait! target timeout until)))
  (read-runtime! [_ target opts] (herdr/agent-read! target opts))
  (status! [_ {:keys [scope target]}]
    (case scope
      :agent (herdr/agent! target)
      :agents (herdr/agents)
      :terminal (herdr/pane! target)
      :process (herdr/process-info! target)))
  (close-runtime! [_ target] (herdr/close! target))
  (notify-runtime! [_ title body] (herdr/notify! title body)))

(defrecord HcRuntime []
  Runtime
  (preflight-runtime! [_] (hc/preflight!))
  (identity! [_] (hc/identity!))
  (identity! [_ opts] (hc/identity! opts))
  (place! [_ request] (hc/place! request))
  (launch! [_ request] (hc/launch! request))
  (prompt-runtime! [_ target text opts] (hc/prompt! target text opts))
  (wait-runtime! [_ target timeout until] (hc/wait! target timeout until))
  (read-runtime! [_ target opts] (hc/read! target opts))
  (status! [_ request] (hc/status! request))
  (close-runtime! [_ target] (hc/close! target))
  (notify-runtime! [_ title body] (hc/notify! title body)))

(def ^:private tui (->TuiRuntime))
(def ^:private core-runtime (->HcRuntime))
(defn call-context [f] (backend/call-context f))
(defn for-backend! [backend]
  (case backend
    (nil :herdr) tui
    :hc core-runtime))
(defn selected! [] (for-backend! (:backend (backend/detect!))))
(defn gate! []
  (backend/preflight!)
  ;; Offline no-host commands remain valid. A detected host must have an implementation,
  ;; even when the verb never calls a terminal operation (including Pi listing routes).
  (when-let [host (backend/detect!)] (for-backend! (:backend host)))
  true)
(defn preflight! []
  (gate!)
  (when-not (backend/detect!)
    (throw (ex-info "oh requires HERDR_ENV=1 or HC_TERMINAL_ID; run inside a supported terminal" {:kind :environment})))
  (preflight-runtime! (selected!)))

(defn require-tui! []
  (gate!)
  (when (= :hc (:backend (backend/detect!)))
    (throw (ex-info "backend-mismatch: raw TUI control requires a Herdr caller" {:backend :hc}))))
(defn raw [operation & args]
  (require-tui!)
  (apply (ns-resolve 'herdr-orch.herdr (symbol (name operation))) args))

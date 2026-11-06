(ns org-tasks.tui.embedding
  "Private context-file consumer for the shared terminal UI.

  Host placement and focus are outside this namespace. All task reads and
  writes remain in the existing command bridge and root resolver."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [org-tasks.root :as root]
            [org-tasks.tui.tasks :as tasks]))

(def poll-ms 500)
(def lease-ms 5000)
(def future-skew-ms 1000)

(defn modal? [state]
  (boolean (or (:new-task state) (:move-task state) (:remove-confirmation state))))

(defn- nonblank? [s] (and (string? s) (not (str/blank? s))))

(defn read-context
  "Read and validate one atomic snapshot for the expected instance.
  Returns an explicit status; remote directories are never resolved locally."
  ([opts] (read-context opts (System/currentTimeMillis)))
  ([{:keys [context-file instance]} now]
   (try
     (if-not (fs/exists? context-file)
       {:status :unavailable :reason "Adapter context missing"}
       (let [c (json/parse-string (slurp context-file) true)
             age (when (integer? (:updatedAt c)) (- now (:updatedAt c)))]
         (cond
           (not (and (map? c) (= "org-tasks-embed/v1" (:schema c))
                     (nonblank? instance) (= instance (:instanceId c))
                     (nonblank? (:contextId c))
                     (#{"compact" "expanded"} (:mode c))
                     (#{"local" "unavailable" "remote"} (:availability c))
                     (some? age)))
           {:status :invalid :reason "Invalid adapter context"}

           (or (> age lease-ms) (< age (- future-skew-ms)))
           {:status :stale :reason "Adapter context lease expired"}

           (= "remote" (:availability c))
           {:status :remote :reason "Remote context unsupported" :context c}

           (= "unavailable" (:availability c))
           {:status :unavailable :reason "Project context unavailable" :context c}

           (not (and (nonblank? (:directory c))
                     (fs/absolute? (:directory c))
                     (fs/directory? (:directory c))))
           {:status :invalid :reason "Local context needs an absolute existing directory"}

           :else {:status :ready :context c})))
     (catch Exception _
       {:status :invalid :reason "Unreadable or malformed adapter context"}))))

(defn context-key [c]
  (select-keys c [:instanceId :contextId :directory :availability :mode]))

(defn action-opts [opts state]
  (if (:context-file opts)
    ;; Never allow CLI selectors to leak from the adapter checkout into a project.
    (merge (select-keys opts [:no-color :editor]) (:action-opts state))
    opts))

(defn command-allowed?
  "Revalidate liveness immediately before an embedded command or editor launch.
  A valid pending switch may finish the interaction in the original root."
  [opts state]
  (or (not (:context-file opts))
      (let [latest (read-context opts)
            key (context-key (:context latest))]
        (and (= :ready (:context-status state))
             (= "expanded" (:mode state))
             (= :ready (:status latest))
             (or (= key (context-key (:active-context state)))
                 (= key (context-key (:pending-context state))))
             (when-let [root (get-in state [:action-opts :root])]
               (fs/exists? (fs/path root "TASKS.org")))))))

(defn- blocked [state {:keys [status reason]}]
  (assoc state :context-status status :context-message reason))

(defn- load-context-state [opts state c]
  (try
    (let [resolved (root/resolve-root {} (:directory c))
          task-opts {:root resolved}
          memory? (fs/exists? (fs/path resolved "TASKS.org"))
          same? (= (context-key c) (context-key (:active-context state)))
          loaded (cond
                   (not memory?) (tasks/initial-state {:rows []})
                   same? (tasks/reload-state task-opts state)
                   :else (tasks/initial-state (tasks/load-tasks task-opts)))
          loaded (merge loaded (select-keys state [:width :height :color?]))
          ;; A producer can replace its file during a slow graph read. Never
          ;; install that result under the replacement's identity.
          latest (read-context opts)]
      (if (and (= :ready (:status latest))
               (= (context-key c) (context-key (:context latest))))
        (assoc loaded :active-context c :action-opts task-opts :mode (:mode c)
               :pending-context nil
               :context-status (if memory? :ready :missing-memory)
               :context-message (when-not memory? "No task memory in project"))
        (blocked state (if (= :ready (:status latest))
                         {:status :switching :reason "Project context changed during refresh"}
                         latest))))
    (catch Exception e
      (blocked state {:status :load-error :reason (str "Task refresh failed: " (ex-message e))}))))

(defn sync-context
  "Validate context on every event; load on ticks or switches, never during a modal.
  Modal state and action options stay bound to their original project."
  [opts state refresh?]
  (if-not (:context-file opts)
    (if (and refresh? (not (modal? state)))
      (try (tasks/reload-state opts state)
           (catch Exception e (assoc state :message (str "Refresh failed: " (ex-message e)))))
      state)
    (let [{:keys [status context] :as snapshot} (read-context opts)]
      (if-not (= :ready status)
        (blocked state snapshot)
        (let [changed? (not= (context-key context) (context-key (:active-context state)))]
          (cond
            (modal? state)
            (assoc state :pending-context (when changed? context)
                   :context-status (if (:active-context state) :ready :unavailable)
                   :context-message nil)

            (or refresh? changed? (not= :ready (:context-status state)))
            ;; known-limit: reload the graph each poll, including imports and
            ;; setupfiles; revisit with measured large-project refresh latency.
            (load-context-state opts state context)

            :else (assoc state :pending-context nil)))))))

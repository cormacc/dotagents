(ns org-tasks-host
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.nio.channels FileChannel]
           [java.nio.file StandardOpenOption]))

(def plugin-id "cormacc.org-tasks")
(defn uuid [] (str (random-uuid)))
(defn now [] (System/currentTimeMillis))
(defn read-json [path] (when (fs/exists? path) (json/parse-string (slurp (str path)) true)))

(defn atomic! [path value]
  (let [tmp (str path "." (uuid))]
    (try
      (fs/create-file tmp {:posix-file-permissions "rw-------"})
      (spit tmp (json/generate-string value))
      (fs/move tmp path {:replace-existing true :atomic-move true})
      (finally (fs/delete-if-exists tmp)))))

(defn private-dir! [path]
  (fs/create-dirs path {:posix-file-permissions "rwx------"})
  (when (or (fs/sym-link? path)
            (not= "rwx------" (fs/posix->str (fs/posix-file-permissions path))))
    (throw (ex-info "State directory must be private and not a symlink" {:path (str path)})))
  (str path))

(def ^:dynamic *request-timeout-ms* 2000)

(defn call! [& args]
  (let [proc (p/process (into [(or (System/getenv "HERDR_BIN_PATH") "herdr")] args)
                        {:out :string :err :string})
        result (deref proc *request-timeout-ms* nil)]
    (when-not result (p/destroy-tree proc) (throw (ex-info "Herdr request timed out" {:args args})))
    (let [data (try (json/parse-string (:out result) true) (catch Exception _ nil))]
      (when (or (not= 0 (:exit result)) (:error data) (nil? (:result data)))
        (throw (ex-info (str "Herdr request failed: " (pr-str args) " " (:err result) " " (:out result))
                        {:args args :response result})))
      (:result data))))

(defn snapshot! [] (:snapshot (call! "api" "snapshot")))
(defn pane [snapshot id] (some #(when (= id (:pane_id %)) %) (:panes snapshot)))
(defn process! [id] (:process_info (call! "pane" "process-info" "--pane" id)))

(def defaults {:compact-rows 6 :expanded-fraction 0.65})
(defn config! [root]
  (let [path (fs/path root "config.edn")
        config (merge defaults (when (fs/exists? path) (edn/read-string (slurp (str path)))))]
    (when-not (and (= (set (keys defaults)) (set (keys config)))
                   (integer? (:compact-rows config)) (<= 3 (:compact-rows config) 20)
                   (number? (:expanded-fraction config)) (<= 0.3 (:expanded-fraction config) 0.9))
      (throw (ex-info "Invalid org-tasks config.edn" {:config config})))
    config))

(defn tab-focused-pane [snapshot tab]
  (let [focused-id (some #(when (= tab (:tab_id %)) (:focused_pane_id %)) (:layouts snapshot))]
    (some #(when (and (= tab (:tab_id %))
                     (if focused-id (= focused-id (:pane_id %)) (:focused %))) %)
          (:panes snapshot))))

(defn source
  "Inspect this tab's focused pane; retain it while the task pane is focused."
  [snapshot tab task-id previous]
  (let [panes (filter #(= tab (:tab_id %)) (:panes snapshot))
        focused (tab-focused-pane snapshot tab)
        candidate (if (= task-id (:pane_id focused))
                    (some #(when (= (:pane_id previous) (:pane_id %)) %) panes)
                    focused)]
    (when (and candidate (not= task-id (:pane_id candidate)))
      (merge candidate (some #(when (= (:pane_id candidate) (:pane_id %)) %) (:agents snapshot))))))

(defn source-context [candidate info]
  (let [processes (:foreground_processes info)
        remote? (some #(#{"ssh" "mosh" "mosh-client"} (:name %)) processes)
        ;; Managed launch readiness excludes agents started by the user. Verify
        ;; the foreground executable instead of trusting a retained agent label.
        ;; known-limit: wrappers with no matching name/argv0 are unavailable;
        ;; revisit with host-authoritative process identity if one is rejected.
        agent-process? (some (fn [process]
                               (some #(and (string? %) (= (:agent candidate) (str (fs/file-name %))))
                                     [(:name process) (:argv0 process)])) processes)
        directory (:foreground_cwd candidate)]
    (cond
      remote? {:availability "remote" :directory nil}
      (and candidate agent-process? (string? directory)
           (fs/absolute? directory) (fs/directory? directory))
      {:availability "local" :directory directory}
      :else {:availability "unavailable" :directory nil})))

(defn context [old instance candidate info mode time]
  (let [value (assoc (source-context candidate info) :sourcePane (:pane_id candidate)
                     :sourceTerminal (:terminal_id candidate))
        changed? (not= value (select-keys old (keys value)))]
    (merge value {:schema "org-tasks-embed/v1" :instanceId instance
                  :contextId (if changed? (uuid) (or (:contextId old) (uuid)))
                  :mode mode :updatedAt time})))

(defn handoff-source
  "Resolve a fresh instance-bound source witness without trusting its directory."
  [snapshot record witness time]
  (let [candidate (pane snapshot (:sourcePane witness))
        age (when (integer? (:updatedAt witness)) (- time (:updatedAt witness)))]
    (when (and (string? (:requestId witness)) (not (str/blank? (:requestId witness)))
               (= (:instance record) (:instanceId witness))
               (= (:tab record) (:tabId witness))
               (= (:terminal record) (:targetTerminal witness))
               (#{"compact" "expanded"} (:mode witness))
               (#{"local" "remote" "unavailable"} (:availability witness))
               age (<= -1000 age 5000)
               candidate (not= (:pane record) (:pane_id candidate))
               (= (:tab record) (:tab_id candidate))
               (= (:sourceTerminal witness) (:terminal_id candidate)))
      candidate)))

(defn sample!
  "Sample live source eligibility, consuming each changed request before retention."
  [snapshot record previous last-context request last-request]
  (let [candidate (if (and (= (:pane record) (:pane_id (tab-focused-pane snapshot (:tab record))))
                           (not= request last-request))
                    (handoff-source snapshot record request (now))
                    (source snapshot (:tab record) (:pane record) previous))
        info (when candidate (process! (:pane_id candidate)))
        c (context last-context (:instance record) candidate info (or (:mode request) "compact") (now))]
    {:candidate candidate
     :context (assoc c :requestId (:requestId request) :tabId (:tab record)
                     :targetTerminal (:terminal record))}))

(defn action-request! [snapshot record dir mode]
  (let [candidate (if (= (:pane record) (:pane_id (tab-focused-pane snapshot (:tab record))))
                    (let [request (read-json (fs/path dir "request.json"))
                          c (read-json (fs/path dir "context.json"))
                          witness (if (and request (= (:requestId request) (:requestId c))) c (or request c))]
                      (handoff-source snapshot record witness (now)))
                    (source snapshot (:tab record) (:pane record) nil))
        availability (:availability (source-context candidate (when candidate (process! (:pane_id candidate)))))]
    {:requestId (uuid) :instanceId (:instance record) :tabId (:tab record)
     :targetTerminal (:terminal record) :updatedAt (now) :mode mode
     :sourcePane (:pane_id candidate) :sourceTerminal (:terminal_id candidate)
     :availability availability}))

(defn identity! [pid]
  (let [r @(p/process ["ps" "-p" (str pid) "-o" "lstart=" "-o" "command="]
                       {:out :string :err :string})]
    (when (zero? (:exit r)) (str/trim (:out r)))))

(defn owned?
  "Require the same terminal, live supervisor process and fresh lease."
  [record snapshot live-process identity time]
  (let [current (pane snapshot (:pane record))]
    (and current (= (:tab record) (:tab_id current))
         (= (:terminal record) (:terminal_id current))
         (string? (:identity record)) (= (:identity record) identity)
         (<= 0 (- time (or (:updatedAt record) 0)) 5000)
         (some #(= (:pid record) (:pid %))
               (concat (:foreground_processes live-process)
                       [{:pid (:shell_pid live-process)}])))))

(defn with-lock [path f]
  ;; File locks are released by the OS on process death, including during open.
  (with-open [channel (FileChannel/open (fs/path path)
                                       (into-array StandardOpenOption
                                                   [StandardOpenOption/CREATE StandardOpenOption/WRITE]))
              _lock (.lock channel)]
    (f)))

(defn state-root! []
  (let [base (or (System/getenv "HERDR_PLUGIN_STATE_DIR")
                 (throw (ex-info "HERDR_PLUGIN_STATE_DIR is required" {})))
        socket (or (System/getenv "HERDR_SOCKET_PATH")
                   (throw (ex-info "HERDR_SOCKET_PATH is required" {})))]
    ;; A state directory is shared across sessions; their pane IDs are not.
    (private-dir! (fs/path base (str "session-" (java.math.BigInteger. 1 (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes socket "UTF-8"))))))))

(defn resize! [record mode config]
  (let [layout (:layout (call! "pane" "layout" "--pane" (:pane record)))
        rect (:rect (some #(when (= (:pane record) (:pane_id %)) %) (:panes layout)))
        ;; Use only a horizontal boundary that contains the owned bottom pane.
        split (last (sort-by #(get-in % [:rect :height]) >
                             (filter #(and (= "down" (:direction %))
                                           (= (get-in % [:rect :x]) (:x rect))
                                           (= (get-in % [:rect :width]) (:width rect))
                                           (= (+ (get-in % [:rect :y]) (get-in % [:rect :height]))
                                              (+ (:y rect) (:height rect)))) (:splits layout))))]
    (when split
      (let [height (get-in split [:rect :height])
            target (if (= mode "expanded") (* height (:expanded-fraction config)) (:compact-rows config))
            delta (/ (- target (:height rect)) height)]
        (when (> (abs delta) 0.01)
          (call! "pane" "resize" "--pane" (:pane record) "--direction" (if (pos? delta) "up" "down")
                 "--amount" (str (double (abs delta)))))))))

(defn instance-record [root index]
  (when-let [instance (:instance index)]
    (when (= instance (str (java.util.UUID/fromString instance)))
      (let [record (read-json (fs/path root instance "owner.json"))]
        (when (and (= instance (:instance record)) (= (:tab index) (:tab record))
                   (or (:pending index)
                       (= (dissoc index :updatedAt) (dissoc record :updatedAt))))
          record)))))

(defn record-owned? [record snapshot]
  (and record
       (try (owned? record snapshot (process! (:pane record)) (identity! (:pid record)) (now))
            (catch Exception _ false))))

(defn action! [operation config-dir]
  (let [config (config! config-dir)
        root (state-root!)
        snapshot (snapshot!)
        tab (or (System/getenv "HERDR_TAB_ID") (:focused_tab_id snapshot))]
    (when-not (some #(= tab (:tab_id %)) (:tabs snapshot))
      (throw (ex-info "Invocation tab is unavailable" {})))
    (with-lock (fs/path root (str tab ".lock"))
      (fn []
        (let [index (fs/path root (str tab ".json"))
              pointer (read-json index)
              record (instance-record root pointer)
              snapshot (snapshot!)
              owned (record-owned? record snapshot)
              mode (if (= operation "expand") "expanded" "compact")]
          (cond
            (= operation "close")
            (when owned (call! "plugin" "pane" "close" (:pane record)))

            (and (not owned) (not= operation "open"))
            (throw (ex-info "No live owned interface; use open" {}))

            (and (not owned) (:pending pointer) (< (- (now) (:updatedAt pointer)) 5000))
            (throw (ex-info "Task open is pending; retry after its lease expires" {}))

            :else
            (let [record (if owned record
                          (let [target (or (tab-focused-pane snapshot tab)
                                           (throw (ex-info "Invocation tab has no focused pane" {})))
                                instance (uuid)
                                dir (private-dir! (fs/path root instance))
                                _ (atomic! index {:instance instance :tab tab :pending true :updatedAt (now)})
                                result (call! "plugin" "pane" "open" "--plugin" plugin-id "--entrypoint" "tasks"
                                              "--placement" "split" "--direction" "down" "--target-pane" (:pane_id target)
                                              "--no-focus" "--env" (str "OT_HOST_INSTANCE=" instance)
                                              "--env" (str "OT_HOST_DIR=" dir) "--env" (str "OT_HOST_TAB=" tab))
                                pane (get-in result [:plugin_pane :pane])]
                            (loop [remaining 40]
                              (let [record (read-json (fs/path dir "owner.json"))]
                                (if (and record (= (:pane_id pane) (:pane record))) record
                                  (if (pos? remaining) (do (Thread/sleep 50) (recur (dec remaining)))
                                    (throw (ex-info "Task supervisor did not become ready" {:instance instance}))))))))]
              (when-not (record-owned? record (snapshot!))
                (throw (ex-info "Owned task process is no longer live" {})))
              (let [request (action-request! (snapshot!) record (fs/path root (:instance record)) mode)]
                ;; Capture the pre-focus source before the browser can retain a
                ;; supervisor sample from an earlier agent.
                (atomic! (fs/path root (:instance record) "request.json") request)
                (atomic! index record)
                (resize! record mode config)
                (cond
                  (= mode "expanded") (call! "plugin" "pane" "focus" (:pane record))
                  (= operation "collapse")
                  (let [agent (handoff-source (snapshot!) record request (now))]
                    (when (and agent (= "local" (:availability (source-context agent (process! (:pane_id agent))))))
                      (call! "agent" "focus" (:pane_id agent)))))))))))))

(defn supervise! [ot]
  (let [dir (System/getenv "OT_HOST_DIR") instance (System/getenv "OT_HOST_INSTANCE")
        tab (System/getenv "OT_HOST_TAB") id (System/getenv "HERDR_PANE_ID")
        pid (.pid (java.lang.ProcessHandle/current))
        current (pane (snapshot!) id)
        owner {:pane id :terminal (:terminal_id current) :tab tab :pid pid
               :identity (identity! pid) :instance instance}
        file (fs/path dir "context.json")
        owner-file (fs/path dir "owner.json")
        index (fs/path (fs/parent dir) (str tab ".json"))]
    (when-not (and dir instance tab id (= tab (:tab_id current)) (fs/directory? dir)
                   (= instance (str (fs/file-name dir))))
      (throw (ex-info "Invalid task supervisor instance" {})))
    (private-dir! dir)
    (when-not (= instance (:instance (read-json index)))
      (throw (ex-info "Task open reservation expired" {})))
    (atomic! file (context nil instance nil nil "compact" (now)))
    (atomic! owner-file (assoc owner :updatedAt (now)))
    (let [child (p/process [ot "tui" "--context-file" (str file) "--instance" instance]
                           {:in :inherit :out :inherit :err :inherit :shutdown p/destroy-tree})]
      (try
        (binding [*request-timeout-ms* 200]
         (loop [previous nil last-context nil last-request nil]
          (when (nil? (deref child 0 nil))
            (let [started (now)
                  snapshot (snapshot!)
                  current (pane snapshot id)]
              (when-not (and (= (:terminal owner) (:terminal_id current)) (= tab (:tab_id current))
                             (= instance (:instance (read-json index))))
                (throw (ex-info "Task pane moved or disappeared" {})))
              (let [request (read-json (fs/path dir "request.json"))
                    {:keys [candidate context]} (sample! snapshot owner previous last-context request last-request)
                    record (assoc owner :updatedAt (now))]
                (atomic! file context)
                (atomic! owner-file record)
                ;; The immutable tab pointer is action-owned. Heartbeats must
                ;; not wait on an action's lock or slow host requests.
                (Thread/sleep (max 1 (- 500 (- (now) started))))
                (recur candidate context request))))))
        (finally
          ;; known-limit: retain private instance metadata for ownership diagnosis;
          ;; add bounded pruning if repeated reopening causes material state growth.
          (fs/delete-if-exists file)
          (p/destroy-tree child))))))

(defn -main [& [operation]]
  (try
    (let [root (or (System/getenv "HERDR_PLUGIN_ROOT") (throw (ex-info "HERDR_PLUGIN_ROOT is required" {})))
          ot (str (fs/path root ".." ".." "skills" "org-tasks" "scripts" "ot"))]
      (if (= operation "serve") (supervise! ot)
        (if (#{"open" "expand" "collapse" "close"} operation)
          (action! operation (System/getenv "HERDR_PLUGIN_CONFIG_DIR"))
          (throw (ex-info "Expected open, expand, collapse, close or serve" {})))))
    (catch Exception e (binding [*out* *err*] (println (ex-message e))) (System/exit 1))))

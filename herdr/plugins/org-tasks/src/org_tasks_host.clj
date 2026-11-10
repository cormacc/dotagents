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
    (when-not result (p/destroy-tree proc) (throw (ex-info "Herdr request timed out" {:args args :timeout true})))
    (let [data (try (json/parse-string (:out result) true) (catch Exception _ nil))]
      (when (or (not= 0 (:exit result)) (:error data) (nil? (:result data)))
        (throw (ex-info (str "Herdr request failed: " (pr-str args) " " (:err result) " " (:out result))
                        {:args args :response result})))
      (:result data))))

(defn timeout?
  "A timed-out request is transient: the server can stall briefly under load."
  [e]
  (true? (:timeout (ex-data e))))

(defn snapshot! [] (:snapshot (call! "api" "snapshot")))
(defn pane [snapshot id] (some #(when (= id (:pane_id %)) %) (:panes snapshot)))
(defn process! [id] (:process_info (call! "pane" "process-info" "--pane" id)))

(def defaults {:compact-rows 5})
(defn config! [root]
  (let [path (fs/path root "config.edn")
        config (merge defaults (when (fs/exists? path) (edn/read-string (slurp (str path)))))]
    (when-not (and (= (set (keys defaults)) (set (keys config)))
                   (integer? (:compact-rows config)) (<= 3 (:compact-rows config) 20))
      (throw (ex-info "Invalid org-tasks config.edn" {:config config})))
    config))

;; ── Key hints ─────────────────────────────────────────────────────

(defn- toml-string [line]
  (second (re-find #"^\s*[A-Za-z_]+\s*=\s*\[?\s*\"((?:[^\"\\]|\\.)*)\"" line)))

(defn key-bindings
  "Read the prefix and this plugin's action bindings from Herdr config text.
  known-limit: a line scanner for the flat `[keys]` / `[[keys.command]]`
  shapes Herdr documents, not a TOML parser; an unreadable shape yields no
  binding, so the hint is omitted rather than wrong."
  [text]
  (loop [[line & more] (str/split-lines (or text "")) table nil block {}
         result {:prefix "ctrl+b" :actions {}}]
    (let [flush (fn [r] (if (and (= table "[[keys.command]]") (:key block) (:command block))
                          (assoc-in r [:actions (:command block)] (:key block))
                          r))]
      (cond
        (nil? line) (flush result)
        (re-find #"^\s*\[" line)
        (recur more (str/trim (str/replace line #"#.*$" "")) {} (flush result))
        (and (= table "[keys]") (re-find #"^\s*prefix\s*=" line))
        (recur more table block (if-let [v (toml-string line)] (assoc result :prefix v) result))
        (and (= table "[[keys.command]]") (re-find #"^\s*(key|command)\s*=" line))
        (recur more table (assoc block (keyword (second (re-find #"^\s*(\w+)" line))) (toml-string line)) result)
        :else (recur more table block result)))))

(defn display-key
  "`prefix+ctrl+o` with prefix `ctrl+b` renders as `ctrl+b ctrl+o`."
  [prefix key]
  (let [k (str/replace key #"(^|\+)shift\+([a-z])$" (fn [[_ sep c]] (str sep (str/upper-case c))))]
    (if (str/starts-with? k "prefix+")
      (str prefix " " (subs k (count "prefix+")))
      k)))

(defn hints
  "One hint line for the bound select and toggle actions, so the hints cost a
  single row of the compact pane; nil when neither is bound."
  [config-text]
  (let [{:keys [prefix actions]} (key-bindings config-text)
        parts (keep (fn [[action text]]
                      (when-let [k (get actions (str plugin-id "." action))]
                        (str (display-key prefix k) ": " text)))
                    [["select" "select"] ["toggle" "show/hide"]])]
    (when (seq parts) [(str/join " / " parts)])))

(defn herdr-config-text []
  (let [path (or (System/getenv "HERDR_CONFIG_PATH")
                 (str (fs/path (System/getProperty "user.home") ".config" "herdr" "config.toml")))]
    (try (slurp path) (catch Exception _ nil))))

;; ── Source context ────────────────────────────────────────────────

(defn tab-focused-pane [snapshot tab]
  (let [focused-id (some #(when (= tab (:tab_id %)) (:focused_pane_id %)) (:layouts snapshot))]
    (some #(when (and (= tab (:tab_id %))
                     (if focused-id (= focused-id (:pane_id %)) (:focused %))) %)
          (:panes snapshot))))

(defn plugin-roots!
  "Registered Herdr plugin roots. A plugin pane starts in its root, so a
  non-agent pane there is plugin UI, not a project."
  []
  (into (set (keep #(some-> (System/getenv %) fs/canonicalize str) ["HERDR_PLUGIN_ROOT"]))
        (try (keep :plugin_root (:plugins (call! "plugin" "list" "--json")))
             (catch Exception _ nil))))

(defn plugin-pane?
  ;; known-limit: a shell whose folder is exactly a plugin root also matches;
  ;; revisit if Herdr reports plugin pane ownership in its snapshot.
  [plugin-roots pane]
  (and pane (not (:agent pane)) (contains? plugin-roots (:foreground_cwd pane))))

(defn source
  "Inspect this tab's focused pane. While the task pane or another plugin's
  pane is focused, retain the previous source."
  ([snapshot tab task-id previous] (source snapshot tab task-id previous #{}))
  ([snapshot tab task-id previous plugin-roots]
   (let [panes (filter #(= tab (:tab_id %)) (:panes snapshot))
         focused (tab-focused-pane snapshot tab)
         candidate (if (or (= task-id (:pane_id focused)) (plugin-pane? plugin-roots focused))
                     (some #(when (and (= (:pane_id previous) (:pane_id %))
                                       (not (plugin-pane? plugin-roots %))) %) panes)
                     focused)]
     (when (and candidate (not= task-id (:pane_id candidate)))
       (merge candidate (some #(when (= (:pane_id candidate) (:pane_id %)) %) (:agents snapshot)))))))

(defn source-context
  "Any local pane is a source: an agent pane or a shell gives its foreground
  folder, so a tab without an agent still shows its project."
  [candidate info]
  (let [processes (:foreground_processes info)
        remote? (some #(#{"ssh" "mosh" "mosh-client"} (:name %)) processes)
        directory (:foreground_cwd candidate)]
    (cond
      remote? {:availability "remote" :directory nil}
      (and candidate (string? directory) (fs/absolute? directory) (fs/directory? directory))
      {:availability "local" :directory directory}
      :else {:availability "unavailable" :directory nil})))

(defn context-value [candidate info]
  (assoc (source-context candidate info) :sourcePane (:pane_id candidate)
         :sourceTerminal (:terminal_id candidate)))

(defn publish [old instance value hint-lines time]
  (let [changed? (not= value (select-keys old (keys value)))]
    (cond-> (merge value {:schema "org-tasks-embed/v1" :instanceId instance
                          :contextId (if changed? (uuid) (or (:contextId old) (uuid)))
                          :mode "compact" :updatedAt time})
      (seq hint-lines) (assoc :hints (vec hint-lines)))))

(defn context [old instance candidate info hint-lines time]
  (publish old instance (context-value candidate info) hint-lines time))

;; About one second at the supervisor's 500 ms poll.
(def hold-samples 3)

(defn settle
  "The context value to publish, and the next pending state. A source pane's
  folder moves while its foreground command runs elsewhere, such as an agent's
  tool call or a shell `cd`, so a new folder in the same pane publishes only
  after `hold-samples` samples. A source change publishes at once."
  [old value pending]
  (if-not (and (= (:sourcePane old) (:sourcePane value))
               (= "local" (:availability old) (:availability value))
               (not= (:directory old) (:directory value)))
    [value nil]
    (let [seen (if (= (:directory pending) (:directory value)) (inc (:seen pending)) 1)]
      (if (>= seen hold-samples)
        [value nil]
        [(select-keys old (keys value)) {:directory (:directory value) :seen seen}]))))

(defn sample!
  "Sample the tab's live source and publish it with a heartbeat."
  [snapshot record previous last-context pending plugin-roots hint-lines]
  (let [candidate (source snapshot (:tab record) (:pane record) previous plugin-roots)
        info (when candidate (process! (:pane_id candidate)))
        [value pending] (settle last-context (context-value candidate info) pending)]
    {:candidate candidate :pending pending
     :context (publish last-context (:instance record) value hint-lines (now))}))

;; ── Ownership ─────────────────────────────────────────────────────

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

(defn plugin-state-dir []
  (or (System/getenv "HERDR_PLUGIN_STATE_DIR")
      (throw (ex-info "HERDR_PLUGIN_STATE_DIR is required" {}))))

(defn state-root! []
  (let [socket (or (System/getenv "HERDR_SOCKET_PATH")
                   (throw (ex-info "HERDR_SOCKET_PATH is required" {})))]
    ;; A state directory is shared across sessions; their pane IDs are not.
    (private-dir! (fs/path (plugin-state-dir) (str "session-" (java.math.BigInteger. 1 (.digest (java.security.MessageDigest/getInstance "SHA-256") (.getBytes socket "UTF-8"))))))))

(defn display-file []
  ;; Global across sessions: the toggle is a user preference, not tab state,
  ;; and holds no private data, so Herdr's own directory permissions suffice.
  (fs/path (fs/create-dirs (plugin-state-dir)) "display.json"))

(defn display-enabled? []
  (not (false? (:enabled (read-json (display-file))))))

(defn resize! [record config]
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
            delta (/ (- (:compact-rows config) (:height rect)) height)]
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

;; ── Actions ───────────────────────────────────────────────────────

(defn- tab-lock [root tab f]
  (with-lock (fs/path root (str tab ".lock")) f))

(defn tab-record
  "The verified live instance for `tab`, or nil."
  [root tab snapshot]
  (let [record (instance-record root (read-json (fs/path root (str tab ".json"))))]
    (when (record-owned? record snapshot) record)))

(defn open-tab!
  "Open this tab's compact pane unless a live instance or a fresh reservation
  exists. The pane never takes focus."
  [root tab config]
  (tab-lock root tab
    (fn []
      (let [index (fs/path root (str tab ".json"))
            pointer (read-json index)
            snapshot (snapshot!)]
        (cond
          (tab-record root tab snapshot) nil
          (and (:pending pointer) (< (- (now) (:updatedAt pointer)) 5000)) nil
          :else
          (when-let [target (tab-focused-pane snapshot tab)]
            (let [instance (uuid)
                  dir (private-dir! (fs/path root instance))
                  _ (atomic! index {:instance instance :tab tab :pending true :updatedAt (now)})
                  result (call! "plugin" "pane" "open" "--plugin" plugin-id "--entrypoint" "tasks"
                                "--placement" "split" "--direction" "down" "--target-pane" (:pane_id target)
                                "--no-focus" "--env" (str "OT_HOST_INSTANCE=" instance)
                                "--env" (str "OT_HOST_DIR=" dir) "--env" (str "OT_HOST_TAB=" tab))
                  pane (get-in result [:plugin_pane :pane])
                  record (loop [remaining 40]
                           (let [record (read-json (fs/path dir "owner.json"))]
                             (if (and record (= (:pane_id pane) (:pane record))) record
                               (if (pos? remaining) (do (Thread/sleep 50) (recur (dec remaining)))
                                 (throw (ex-info "Task supervisor did not become ready" {:instance instance}))))))]
              (atomic! index record)
              (resize! record config))))))))

(defn close-tab! [root tab]
  (tab-lock root tab
    (fn [] (when-let [record (tab-record root tab (snapshot!))]
             (call! "plugin" "pane" "close" (:pane record))))))

(defn- each-tab! [f]
  ;; One tab's failure must not stop the others; report them together.
  (let [failures (keep (fn [tab] (try (f (:tab_id tab)) nil
                                      (catch Exception e (str (:tab_id tab) ": " (ex-message e)))))
                       (:tabs (snapshot!)))]
    (when (seq failures)
      (throw (ex-info (str/join "\n" failures) {})))))

(defn ensure-all!
  "Open the compact pane in every tab while display is enabled."
  [config]
  (when (display-enabled?)
    (let [root (state-root!)] (each-tab! #(open-tab! root % config)))))

(defn toggle!
  "Flip the global display preference and apply it to every tab."
  [config]
  (let [enabled (not (display-enabled?))]
    (atomic! (display-file) {:enabled enabled})
    (if enabled
      (ensure-all! config)
      (let [root (state-root!)] (each-tab! #(close-tab! root %))))))

(defn selection-directory
  "The project the tab's compact pane shows, from the live source pane."
  [root tab snapshot]
  (let [record (tab-record root tab snapshot)
        last-context (when record (read-json (fs/path root (:instance record) "context.json")))
        candidate (source snapshot tab (:pane record) (pane snapshot (:sourcePane last-context))
                          (plugin-roots!))
        result (source-context candidate (when candidate (process! (:pane_id candidate))))]
    (when (= "local" (:availability result)) (:directory result))))

(defn select!
  "Open the task browser as a popup; it closes when `ot` exits on Esc, and
  Herdr then restores focus to the pane that had it."
  []
  (let [root (state-root!)
        snapshot (snapshot!)
        tab (or (System/getenv "HERDR_TAB_ID") (:focused_tab_id snapshot))]
    (call! "plugin" "pane" "open" "--plugin" plugin-id "--entrypoint" "select"
           "--env" (str "OT_SELECT_DIR=" (or (selection-directory root tab snapshot) "")))))

(defn select-pane! [ot]
  (let [dir (System/getenv "OT_SELECT_DIR")]
    (if (str/blank? dir)
      (do (println "No local project in this tab.")
          (println "Press Enter to close.")
          (read-line))
      @(p/process [ot "tui"] {:dir dir :in :inherit :out :inherit :err :inherit}))))

;; ── Supervisor ────────────────────────────────────────────────────

(defn sync-label!
  "Mirror the TUI's OSC 2 title into the manual pane label, which Herdr draws
  on the pane border. The label is display-only, so a failed rename must not
  end the supervisor; the next poll retries it."
  [current]
  (let [title (:terminal_title current)]
    (when (and (string? title) (not (str/blank? title)) (not= title (:label current)))
      (try (call! "pane" "rename" (:pane_id current) title)
           (catch Exception _ nil)))))

(defn supervise! [ot]
  (let [dir (System/getenv "OT_HOST_DIR") instance (System/getenv "OT_HOST_INSTANCE")
        tab (System/getenv "OT_HOST_TAB") id (System/getenv "HERDR_PANE_ID")
        pid (.pid (java.lang.ProcessHandle/current))
        current (pane (snapshot!) id)
        owner {:pane id :terminal (:terminal_id current) :tab tab :pid pid
               :identity (identity! pid) :instance instance}
        file (fs/path dir "context.json")
        owner-file (fs/path dir "owner.json")
        index (fs/path (fs/parent dir) (str tab ".json"))
        hint-lines (hints (herdr-config-text))
        roots (plugin-roots!)]
    (when-not (and dir instance tab id (= tab (:tab_id current)) (fs/directory? dir)
                   (= instance (str (fs/file-name dir))))
      (throw (ex-info "Invalid task supervisor instance" {})))
    (private-dir! dir)
    (when-not (= instance (:instance (read-json index)))
      (throw (ex-info "Task open reservation expired" {})))
    (atomic! file (context nil instance nil nil hint-lines (now)))
    (atomic! owner-file (assoc owner :updatedAt (now)))
    (let [child (p/process [ot "tui" "--context-file" (str file) "--instance" instance]
                           {:in :inherit :out :inherit :err :inherit :shutdown p/destroy-tree})]
      (try
        (binding [*request-timeout-ms* 200]
          (loop [previous nil last-context nil pending nil]
            (when (nil? (deref child 0 nil))
              (let [started (now)
                    ;; nil after a timed-out request: keep the previous state
                    ;; and retry at the next poll. Every other failure ends
                    ;; the pane.
                    sampled (try
                              (let [snapshot (snapshot!)
                                    current (pane snapshot id)]
                                (when-not (and (= (:terminal owner) (:terminal_id current)) (= tab (:tab_id current))
                                               (= instance (:instance (read-json index))))
                                  (throw (ex-info "Task pane moved or disappeared" {})))
                                (sync-label! current)
                                (sample! snapshot owner previous last-context pending roots hint-lines))
                              (catch clojure.lang.ExceptionInfo e
                                (when-not (timeout? e) (throw e))))]
                (when sampled (atomic! file (:context sampled)))
                ;; The supervisor is alive during a retry, so its lease stays
                ;; fresh and `ensure` does not open a second pane.
                (atomic! owner-file (assoc owner :updatedAt (now)))
                (Thread/sleep (max 1 (- 500 (- (now) started))))
                (if sampled
                  (recur (:candidate sampled) (:context sampled) (:pending sampled))
                  (recur previous last-context pending))))))
        (finally
          ;; known-limit: retain private instance metadata for ownership diagnosis;
          ;; add bounded pruning if repeated reopening causes material state growth.
          (fs/delete-if-exists file)
          (p/destroy-tree child))))))

(defn resolve-ot
  "The first executable `ot`: the home skill, then the skill in the checkout
  that holds `plugin-root`. The home skill comes first so that this plugin and
  the herdr-app widget, which runs the home skill, use the same `ot`."
  [home plugin-root]
  (let [candidates [(str (fs/path home ".agents" "skills" "org-tasks" "scripts" "ot"))
                    (str (fs/path plugin-root ".." ".." ".." "skills" "org-tasks" "scripts" "ot"))]]
    (or (some #(when (fs/executable? %) %) candidates)
        (throw (ex-info (str "ot not found at " (str/join " or " candidates)) {:candidates candidates})))))

(defn -main [& [operation]]
  (try
    (let [root (or (System/getenv "HERDR_PLUGIN_ROOT") (throw (ex-info "HERDR_PLUGIN_ROOT is required" {})))
          ;; Only the pane operations run `ot`; toggle and ensure must work without it.
          ot #(resolve-ot (or (System/getenv "HOME") (System/getProperty "user.home")) root)
          config #(config! (System/getenv "HERDR_PLUGIN_CONFIG_DIR"))]
      (case operation
        "serve" (supervise! (ot))
        "select-pane" (select-pane! (ot))
        "select" (select!)
        "toggle" (toggle! (config))
        ;; Startup and tab.created hooks: idempotent, so neither needs the
        ;; event payload.
        "ensure" (ensure-all! (config))
        (throw (ex-info "Expected toggle, select, ensure, serve or select-pane" {}))))
    (catch Exception e (binding [*out* *err*] (println (ex-message e))) (System/exit 1))))

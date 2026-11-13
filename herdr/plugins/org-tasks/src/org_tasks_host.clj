(ns org-tasks-host
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.io BufferedReader InputStreamReader]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels Channels FileChannel SocketChannel]
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

;; ── Event subscription ────────────────────────────────────────────
;; One long-lived connection replaces a `herdr` process per request. The wire
;; format is one JSON object per line, as `herdr api schema` describes:
;; a request `{id, method, params}`, then `{id, result: {type:
;; "subscription_started"}}`, then one `{event, data}` envelope per event.

(def ^:dynamic *subscribe-timeout-ms* 2000)

(defn- read-json-line [^BufferedReader reader]
  (when-let [line (.readLine reader)]
    (try {:value (json/parse-string line true)}
         (catch Exception _ {:unparsable line}))))

(defn subscribe!
  "Open `socket` and subscribe to the event `types`, such as \"tab.focused\".
  Returns a connection for `events!` once Herdr confirms the subscription.
  Throws ex-info with `:subscription` `:closed`, `:error`, `:unparsable`,
  `:unexpected` or `:timeout` otherwise; the connection is then closed."
  [socket types]
  (let [channel (SocketChannel/open StandardProtocolFamily/UNIX)]
    (try
      (.connect channel (UnixDomainSocketAddress/of (str socket)))
      (let [reader (BufferedReader. (InputStreamReader. (Channels/newInputStream channel) "UTF-8"))
            writer (Channels/newOutputStream channel)
            request {:id (str "org-tasks-" (uuid)) :method "events.subscribe"
                     :params {:subscriptions (mapv (fn [t] {:type t}) types)}}]
        (.write writer (.getBytes (str (json/generate-string request) "\n") "UTF-8"))
        (.flush writer)
        ;; The read blocks; closing the channel on timeout releases it.
        (let [reply (deref (future (read-json-line reader)) *subscribe-timeout-ms* ::timeout)
              fail (fn [kind data] (throw (ex-info (str "Herdr event subscription failed: " (name kind))
                                                   (assoc data :subscription kind))))]
          (cond
            (= ::timeout reply) (fail :timeout {})
            (nil? reply) (fail :closed {})
            (:unparsable reply) (fail :unparsable {:line (:unparsable reply)})
            (:error (:value reply)) (fail :error {:error (:error (:value reply))})
            (not= "subscription_started" (get-in (:value reply) [:result :type]))
            (fail :unexpected {:reply (:value reply)})
            :else {:channel channel :reader reader})))
      (catch Throwable e (.close channel) (throw e)))))

(defn events!
  "Call `(on-event envelope)` for each `{:event :data}` envelope on
  `connection` until it ends, then close it and return why: `{:end :closed}`,
  `{:end :error :error ...}` for an error reply, or `{:end :unparsable :line
  ...}`. An exception from `on-event` closes the connection and propagates."
  [{:keys [^SocketChannel channel reader]} on-event]
  (try
    (loop []
      (let [line (try (read-json-line reader)
                      ;; A channel closed from another thread ends the stream.
                      (catch java.io.IOException _ nil))
            value (:value line)]
        (cond
          (nil? line) {:end :closed}
          (:unparsable line) {:end :unparsable :line (:unparsable line)}
          (:error value) {:end :error :error (:error value)}
          (and (string? (:event value)) (map? (:data value))) (do (on-event value) (recur))
          :else {:end :unparsable :line (json/generate-string value)})))
    (finally (.close channel))))

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

(def hold-ms 1000)

(defn settle
  "The context value to publish at `time`, and the next pending state. A
  source pane's folder moves while its foreground command runs elsewhere, such
  as an agent's tool call or a shell `cd`, so a new folder in the same pane
  publishes only once a sample sees it `hold-ms` after the first did. A source
  change publishes at once."
  [old value pending time]
  (if-not (and (= (:sourcePane old) (:sourcePane value))
               (= "local" (:availability old) (:availability value))
               (not= (:directory old) (:directory value)))
    [value nil]
    (let [since (if (= (:directory pending) (:directory value)) (:since pending) time)]
      (if (>= (- time since) hold-ms)
        [value nil]
        [(select-keys old (keys value)) {:directory (:directory value) :since since}]))))

(defn sample!
  "Sample the tab's live source and publish it with a heartbeat, or nil to
  skip this sample. The source pane can close between the snapshot and its
  process-info request, which then fails; the next snapshot no longer lists
  it, so the next sample picks the new source."
  [snapshot record previous last-context pending plugin-roots hint-lines]
  (let [candidate (source snapshot (:tab record) (:pane record) previous plugin-roots)
        info (if candidate
               (try (process! (:pane_id candidate))
                    (catch clojure.lang.ExceptionInfo _ ::failed))
               nil)]
    (when-not (= ::failed info)
      (let [[value pending] (settle last-context (context-value candidate info) pending (now))]
        {:candidate candidate :pending pending
         :context (publish last-context (:instance record) value hint-lines (now))}))))

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

(defn- instance-id? [s]
  (try (= s (str (java.util.UUID/fromString s))) (catch Exception _ false)))

(defn instance-record
  "The owner record that `index` points to, or nil. A pending index is a
  reservation; otherwise the index must equal the record. The supervisor
  renews `:label` in the record only, so it is not compared."
  [root index]
  (when-let [instance (:instance index)]
    (when (instance-id? instance)
      (let [record (read-json (fs/path root instance "owner.json"))]
        (when (and (= instance (:instance record))
                   (or (:pending index)
                       (= (dissoc index :updatedAt :label) (dissoc record :updatedAt :label))))
          record)))))

(defn record-owned? [record snapshot]
  (and record
       (try (owned? record snapshot (process! (:pane record)) (identity! (:pid record)) (now))
            (catch Exception _ false))))

;; ── Actions ───────────────────────────────────────────────────────
;; One compact pane per session. It moves to the focused tab, so its index and
;; lock are per session, and its record names the pane and tab it is in now.

(def index-name "instance.json")
(defn index-path [root] (fs/path root index-name))
(defn- session-lock [root f] (with-lock (fs/path root "instance.lock") f))

(defn session-record
  "The verified live instance, or nil."
  [root snapshot]
  (let [record (instance-record root (read-json (index-path root)))]
    (when (record-owned? record snapshot) record)))

(defn focused-tab [snapshot]
  (or (:focused_tab_id snapshot)
      (some #(when (:focused %) (:tab_id %)) (:panes snapshot))))

(defn open-session!
  "Open the compact pane below the focused pane of the focused tab, unless a
  live instance or a fresh reservation exists. The pane never takes focus."
  [root config]
  (session-lock root
    (fn []
      (let [index (index-path root)
            pointer (read-json index)
            snapshot (snapshot!)]
        (cond
          (session-record root snapshot) nil
          (and (:pending pointer) (< (- (now) (:updatedAt pointer)) 5000)) nil
          :else
          (when-let [target (some->> (focused-tab snapshot) (tab-focused-pane snapshot))]
            (let [instance (uuid)
                  dir (private-dir! (fs/path root instance))
                  _ (atomic! index {:instance instance :pending true :updatedAt (now)})
                  result (call! "plugin" "pane" "open" "--plugin" plugin-id "--entrypoint" "tasks"
                                "--placement" "split" "--direction" "down" "--target-pane" (:pane_id target)
                                "--no-focus" "--env" (str "OT_HOST_INSTANCE=" instance)
                                "--env" (str "OT_HOST_DIR=" dir))
                  pane (get-in result [:plugin_pane :pane])
                  record (loop [remaining 40]
                           (let [record (read-json (fs/path dir "owner.json"))]
                             (if (and record (= (:pane_id pane) (:pane record))) record
                               (if (pos? remaining) (do (Thread/sleep 50) (recur (dec remaining)))
                                 (throw (ex-info "Task supervisor did not become ready" {:instance instance}))))))]
              (atomic! index record)
              (resize! record config))))))))

(defn close-session! [root]
  (session-lock root
    (fn [] (when-let [record (session-record root (snapshot!))]
             (call! "plugin" "pane" "close" (:pane record))))))

;; ── Restored panes ────────────────────────────────────────────────
;; Herdr saves a plugin pane with its session and, after a restart, restores
;; it with the same pane ID as a plain shell in the plugin root, with the
;; saved label. The snapshot does not mark it as a plugin pane, and `plugin
;; pane close` rejects it, so only the plugin's own records can identify it.

(defn- canonical [path]
  (when (string? path) (try (str (fs/canonicalize path)) (catch Exception _ nil))))

(defn restored?
  "Is `current` the pane that `record` names, restored by Herdr as an idle
  shell? Pane IDs are not reused within a session, but a deleted session's
  name can start again from `w1:p1`, so the pane must also have a new
  terminal, the label that the supervisor last recorded, the plugin root
  `plugin-root` as its folder, and only its shell in the foreground, so that
  no user command can be lost."
  [record current info plugin-root]
  (let [processes (:foreground_processes info)]
    (boolean
     (and current plugin-root
          (= (:pane record) (:pane_id current))
          (= (:tab record) (:tab_id current))
          (string? (:terminal record)) (not= (:terminal record) (:terminal_id current))
          (string? (:label record)) (= (:label record) (:label current))
          (not (:agent current))
          (= plugin-root (canonical (:foreground_cwd current)))
          (= 1 (count processes))
          (some? (:shell_pid info)) (= (:shell_pid info) (:pid (first processes)))))))

(defn owner-records
  "Every instance record in the session state `root`, including those of
  ended supervisors, which are kept for this purpose."
  [root]
  (keep (fn [dir]
          (let [instance (str (fs/file-name dir))
                record (when (instance-id? instance) (read-json (fs/path dir "owner.json")))]
            (when (= instance (:instance record)) record)))
        (fs/list-dir root fs/directory?)))

(defn close-restored!
  "Close each pane in `snapshot` that one of this session's records shows
  Herdr restored from an earlier compact pane. Returns the closed pane IDs."
  [root snapshot plugin-root]
  (vec (for [record (owner-records root)
             :let [current (pane snapshot (:pane record))]
             ;; Only a pane with a new terminal needs its processes read.
             :when (and current (not= (:terminal record) (:terminal_id current))
                        (try (and (restored? record current (process! (:pane_id current)) plugin-root)
                                  (call! "pane" "close" (:pane_id current)))
                             (catch Exception _ false)))]
         (:pane_id current))))

(defn close-legacy!
  "Close compact panes of the earlier one-pane-per-tab design, whose
  supervisors can still run after an upgrade, and remove their index files."
  [root]
  (let [indexes (remove #(= index-name (str (fs/file-name %))) (fs/glob root "*.json"))]
    (when (seq indexes)
      (let [snapshot (snapshot!)]
        (doseq [index indexes]
          (when-let [record (instance-record root (read-json index))]
            (when (record-owned? record snapshot)
              (try (call! "plugin" "pane" "close" (:pane record)) (catch Exception _ nil))))
          (fs/delete-if-exists index))))))

(defn plugin-root []
  (canonical (System/getenv "HERDR_PLUGIN_ROOT")))

(defn ensure!
  "Close compact panes that Herdr restored as shells, and open the compact
  pane while display is enabled. The startup and tab.focused hooks run this,
  so a lost supervisor comes back at the next tab switch."
  [config]
  (let [root (state-root!)]
    (session-lock root #(close-restored! root (snapshot!) (plugin-root)))
    (when (display-enabled?)
      (close-legacy! root)
      (open-session! root config))))

(defn toggle!
  "Flip the global display preference and apply it."
  [config]
  (let [enabled (not (display-enabled?))]
    (atomic! (display-file) {:enabled enabled})
    (if enabled
      (ensure! config)
      (let [root (state-root!)] (close-legacy! root) (close-session! root)))))

(defn selection-directory
  "The project of `tab`'s focused pane. While the compact pane is in `tab`, its
  last source stands in for a focused task pane."
  [root tab snapshot]
  (let [record (session-record root snapshot)
        last-context (when (and record (= tab (:tab record)))
                       (read-json (fs/path root (:instance record) "context.json")))
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
  end the supervisor; the next poll retries it. Returns the label that the
  pane has afterwards, which `restored?` later compares."
  [current]
  (let [title (:terminal_title current)]
    (if (and (string? title) (not (str/blank? title)) (not= title (:label current)))
      (try (call! "pane" "rename" (:pane_id current) title)
           title
           (catch Exception _ (:label current)))
      (:label current))))

(defn sole-pane?
  "Is pane `id` the only pane left in `tab`? Herdr closes a tab with its last
  pane, so a compact pane that outlives the tab's other panes would keep an
  otherwise finished tab, such as a collected subagent's, open."
  [snapshot tab id]
  (= [id] (map :pane_id (filter #(= tab (:tab_id %)) (:panes snapshot)))))

(def subscriptions ["tab.focused" "pane.focused" "pane.updated" "pane.closed" "pane.moved"])
;; A folder change without a title change emits no event, so the supervisor
;; also samples at this interval.
(def ^:dynamic *fallback-ms* 5000)
;; Groups bursts, such as an agent's title updates, into one sample.
(def ^:dynamic *min-sample-ms* 250)
;; The context lease is 5 s and the contract asks for a renewal each second.
(def heartbeat-ms 500)

(defn sample-reason
  "Whether an event in the session needs a new sample of `tab`: a focus change
  anywhere, a closed or moved pane, or a title change in `tab`."
  [tab {:keys [event data]}]
  (case event
    ("tab_focused" "pane_focused" "pane_closed" "pane_moved") true
    "pane_updated" (= tab (get-in data [:pane :tab_id]))
    false))

(defn move-ratio
  "The share of a `height`-row target pane that leaves `rows` rows below it."
  [height rows]
  (max 0.5 (min 0.95 (/ (- height rows) (double height)))))

(defn follow!
  "Move pane `id` below the focused pane of `tab`, without focus. Returns the
  moved pane, whose ID changes when the tab is in another workspace."
  [id tab snapshot config]
  (when-let [target (tab-focused-pane snapshot tab)]
    (let [height (or (some #(when (= (:pane_id target) (:pane_id %)) (get-in % [:rect :height]))
                           (:panes (:layout (call! "pane" "layout" "--pane" (:pane_id target)))))
                     40)
          result (call! "pane" "move" id "--tab" tab "--split" "down" "--target-pane" (:pane_id target)
                        "--ratio" (format "%.3f" (move-ratio height (:compact-rows config))) "--no-focus")]
      (get-in result [:move_result :pane]))))

(defn- take-events!
  "Every queued event, waiting up to `ms` for the first."
  [^java.util.concurrent.LinkedBlockingQueue queue ms]
  (let [first-event (.poll queue ms java.util.concurrent.TimeUnit/MILLISECONDS)
        more (java.util.ArrayList.)]
    (.drainTo queue more)
    (cond-> (vec more) first-event (->> (cons first-event) vec))))

(def exit-name "exit.json")

(defn record-exit!
  "Write why the supervisor ended, and when, to `exit.json` in its instance
  directory `dir`: Herdr keeps no pane stderr, and `herdr plugin log list`
  records only hooks and actions. `recorded` keeps the first reason, so the
  shutdown hook cannot replace a reason that the loop or an exception wrote.
  A failed write must not hide the exit itself."
  [dir recorded reason]
  (when (compare-and-set! recorded false true)
    (try (atomic! (fs/path dir exit-name) (assoc reason :exitedAt (str (java.time.Instant/now))))
         (catch Exception _ nil))))

(defn exit-reason [^Throwable e]
  {:reason "error" :class (.getName (class e)) :message (ex-message e)})

(defn supervise! [ot config]
  (let [dir (System/getenv "OT_HOST_DIR") instance (System/getenv "OT_HOST_INSTANCE")
        initial (System/getenv "HERDR_PANE_ID") socket (System/getenv "HERDR_SOCKET_PATH")
        pid (.pid (java.lang.ProcessHandle/current))
        start (pane (snapshot!) initial)
        owner {:terminal (:terminal_id start) :pid pid :identity (identity! pid) :instance instance}
        root (when dir (fs/parent dir))
        file (fs/path dir "context.json")
        owner-file (fs/path dir "owner.json")
        index (index-path root)
        hint-lines (hints (herdr-config-text))
        roots (plugin-roots!)
        record-of (fn [{:keys [id tab label]}] (cond-> (assoc owner :pane id :tab tab)
                                                 label (assoc :label label)))
        heartbeat! (fn [st]
                     (when (:context st) (atomic! file (assoc (:context st) :updatedAt (now))))
                     (atomic! owner-file (assoc (record-of st) :updatedAt (now))))]
    (when-not (and dir instance initial socket start (fs/directory? dir)
                   (= instance (str (fs/file-name dir))))
      (throw (ex-info "Invalid task supervisor instance" {})))
    (private-dir! dir)
    ;; The directory is verified, so every later exit is recorded there. A
    ;; signal, such as when Herdr closes the pane, ends the process without an
    ;; exception, so a shutdown hook records it.
    (let [recorded (atom false)
          exit! #(record-exit! dir recorded %)
          hook (Thread. #(exit! {:reason "terminated"}))]
      (.addShutdownHook (Runtime/getRuntime) hook)
      (try
        (when-not (= instance (:instance (read-json index)))
          (throw (ex-info "Task open reservation expired" {})))
        (atomic! file (context nil instance nil nil hint-lines (now)))
        (atomic! owner-file (assoc (record-of {:id initial :tab (:tab_id start) :label (:label start)})
                                   :updatedAt (now)))
        (let [queue (java.util.concurrent.LinkedBlockingQueue.)
              connection (subscribe! socket subscriptions)
              stream (future (try (events! connection #(.put queue %))
                                  (finally (.put queue ::ended))))
              child (p/process [ot "tui" "--context-file" (str file) "--instance" instance]
                               {:in :inherit :out :inherit :err :inherit :shutdown p/destroy-tree})
              ;; One sample: move to the focused tab, close when alone, or publish.
              ;; nil skips it after a timed-out request or a source pane that closed
              ;; mid-sample; every other failure ends the pane.
              step (fn [st]
                     (try
                       (let [snapshot (snapshot!)
                             current (pane snapshot (:id st))
                             focus (focused-tab snapshot)]
                         (when-not (and (= (:terminal owner) (:terminal_id current))
                                        (= instance (:instance (read-json index))))
                           (throw (ex-info "Task pane moved or disappeared" {})))
                         (cond
                           (and focus (not= focus (:tab_id current)))
                           (when-let [moved (binding [*request-timeout-ms* 2000]
                                              (follow! (:id st) focus snapshot config))]
                             (let [st (assoc st :id (:pane_id moved) :tab (:tab_id moved) :previous nil :due true)]
                               (session-lock root #(do (atomic! owner-file (assoc (record-of st) :updatedAt (now)))
                                                       (atomic! index (record-of st))))
                               (binding [*request-timeout-ms* 2000] (resize! (record-of st) config))
                               st))

                           (sole-pane? snapshot (:tab_id current) (:id st))
                           (do (call! "plugin" "pane" "close" (:id st)) ::closed)

                           :else
                           (let [st (assoc st :label (sync-label! current))]
                             (when-let [sampled (sample! snapshot (record-of (assoc st :tab (:tab_id current)))
                                                           (:previous st) (:context st) (:pending st) roots hint-lines)]
                                 (atomic! file (:context sampled))
                                 (assoc st :tab (:tab_id current) :previous (:candidate sampled)
                                        :context (:context sampled) :pending (:pending sampled) :due false
                                        ;; A pending folder publishes at the first sample after its hold.
                                        :resample-at (some-> (:pending sampled) :since (+ hold-ms)))))))
                       (catch clojure.lang.ExceptionInfo e
                         (when-not (timeout? e) (throw e)))))]
          (try
            (exit!
             (binding [*request-timeout-ms* 200]
               (loop [st {:id initial :tab (:tab_id start) :label (:label start) :due true :sampled-at 0}]
                 (if-let [ended (deref child 0 nil)]
                   {:reason "ot-exited" :exit (:exit ended)}
                   (let [events (take-events! queue (if (:due st) 0 heartbeat-ms))
                         _ (when (some #{::ended} events)
                             (throw (ex-info "Herdr event stream ended" {})))
                         due (or (:due st)
                                 (some #(sample-reason (:tab st) %) events)
                                 (some-> (:resample-at st) (<= (now)))
                                 (>= (- (now) (:sampled-at st)) *fallback-ms*))
                         wait (- *min-sample-ms* (- (now) (:sampled-at st)))
                         _ (when (and due (pos? wait)) (Thread/sleep wait))
                         next-st (if due
                                   (or (step st) (assoc st :due true :sampled-at (now)))
                                   st)]
                     (if (= ::closed next-st)
                       {:reason "closed-alone"}
                       (let [next-st (cond-> next-st due (assoc :sampled-at (now)))]
                         (heartbeat! next-st)
                         (recur next-st))))))))
            (finally
              ;; known-limit: retain private instance metadata for ownership diagnosis;
              ;; add bounded pruning if repeated reopening causes material state growth.
              (fs/delete-if-exists file)
              (.close ^java.nio.channels.SocketChannel (:channel connection))
              (future-cancel stream)
              (p/destroy-tree child))))
        (catch Throwable e (exit! (exit-reason e)) (throw e))
        (finally
          (try (.removeShutdownHook (Runtime/getRuntime) hook)
               ;; The JVM is already shutting down, so the hook has run.
               (catch IllegalStateException _ nil)))))))

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
        "serve" (supervise! (ot) (config))
        "select-pane" (select-pane! (ot))
        "select" (select!)
        "toggle" (toggle! (config))
        ;; Startup and tab.focused hooks: idempotent, so neither needs the
        ;; event payload.
        "ensure" (ensure! (config))
        (throw (ex-info "Expected toggle, select, ensure, serve or select-pane" {}))))
    (catch Exception e (binding [*out* *err*] (println (ex-message e))) (System/exit 1))))

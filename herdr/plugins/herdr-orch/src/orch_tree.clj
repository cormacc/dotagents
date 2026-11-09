(ns orch-tree
  "One-shot reconcile of the herdr-orch ledgers and `herdr agent list` into Herdr sidebar metadata
  (`pane report-metadata`) and the plugin agent view (`agent.view.set`). No daemon: each run
  finishes before it exits.

  Command line: `tree.bb reconcile` | `tree.bb toggle`. Exit 0 on success (a skipped malformed
  ledger file is not a failure); 1 on a failed `herdr` or socket call, an unreadable state file,
  missing environment, or a bad command.

  Environment read: HERDR_BIN_PATH (default `herdr`), HERDR_SOCKET_PATH, HERDR_PLUGIN_STATE_DIR
  and HERDR_SESSION (absent means `default`). The display flag `$HERDR_PLUGIN_STATE_DIR/display.json`
  (`{\"enabled\": bool}`, absent means enabled; unreadable is an error) is global across sessions.
  While it is off, every reconcile clears what its session recorded and does nothing else; `toggle`
  flips it and applies the new value to the invoking session. Other state lives in
  `$HERDR_PLUGIN_STATE_DIR/session-<key>/`: `tree-state.json`, `tree.lock` and `tree.dirty`. Nothing is written under an assignment root."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [herdr-orch.tree :as tree])
  (:import (java.net StandardProtocolFamily UnixDomainSocketAddress)
           (java.nio.channels Channels FileChannel SocketChannel)
           (java.nio.file StandardOpenOption)))

(def source "plugin:cormacc.herdr-orch")

(def view-params
  {:source source
   :sort [{:field "workspace_order" :order "asc"}
          {:field {:token "order"} :order "asc"}
          {:field "pane_order" :order "asc"}]})

(defn- warn
  "One stderr line: only the first line of each part (parser messages span several)."
  [& parts]
  (binding [*out* *err*] (println (str "tree.bb: " (str/join " " (map (comp first str/split-lines str) parts))))))

(defn- env [k] (not-empty (System/getenv k)))

(defn- require-env [k] (or (env k) (throw (ex-info (str k " is required") {}))))

;; --- pure helpers -----------------------------------------------------------------------

(def machine
  "Machine part of a root's name. The plugin reconciles only the server it runs on."
  "local")

(defn root-name
  "`<machine>:<workspace label>:<agent>` for a root. The agent part is the ledger name, else the
  Herdr agent name, else the agent kind; a workspace without a label falls back to its id."
  [{:keys [workspace_id agent] :as a} labels row-name]
  (when-let [who (or row-name (not-empty (:name a)) agent)]
    (str machine ":" (get labels workspace_id workspace_id) ":" who)))

(defn desired
  "Tree rows, agent rows and `{workspace-id label}` -> `{pane {:name str|nil :marks str :order
  \"0001\"|nil}}`, the comparable form kept in the state file. Every listed agent gets an entry: a
  child's name is its mark, a space and its ledger name; a root, with or without a tree row, gets
  `root-name`. The mark is part of the name, not a token of its own, because Herdr draws a `·`
  separator between row tokens. An agent outside the tree has no order, so the plugin view does
  not move it."
  [rows agents labels]
  (into {} (map (fn [{pane :pane_id :as a}]
                  (let [{:keys [order marks name] :as row} (get rows pane)
                        order (some->> order (format "%04d"))]
                    [pane (if (str/blank? marks)
                            {:name (root-name a labels (when row name)) :marks "" :order order}
                            {:name (some->> name (str marks " ")) :marks marks :order order})])))
        agents))

(defn set-args
  "Argv tail after `herdr` for reporting `want` on `pane`, given the `prev` state entry or nil.
  A nil name reports no display name and a nil order clears the order token. The `tree` token is
  always cleared, so one that an earlier plugin version reported cannot linger."
  [pane {:keys [name order]} prev]
  (-> ["pane" "report-metadata" pane "--source" source]
      (into (if name ["--display-agent" name] (when (:name prev) ["--clear-display-agent"])))
      (into ["--clear-token" "tree"])
      (into (cond order        ["--token" (str "order=" order)]
                  (:order prev) ["--clear-token" "order"]))))

(defn clear-args [pane]
  ["pane" "report-metadata" pane "--source" source "--clear-display-agent" "--clear-token" "tree" "--clear-token" "order"])

(defn parse-agents [text]
  (let [parsed (json/parse-string text true)
        agents (if (map? parsed) (get-in parsed [:result :agents]) parsed)]
    (when-not (sequential? agents) (throw (ex-info "agent list: no result.agents array" {})))
    (vec agents)))

(defn parse-workspace-labels
  "`herdr workspace list` output -> `{workspace-id label}`; a workspace without a label is omitted."
  [text]
  (let [ws (get-in (json/parse-string text true) [:result :workspaces])]
    (when-not (sequential? ws) (throw (ex-info "workspace list: no result.workspaces array" {})))
    (into {} (keep (fn [{:keys [workspace_id label]}] (when (not-empty label) [workspace_id label]))) ws)))

;; --- herdr, git, ledgers ------------------------------------------------------------------

(defn- run
  "Run `bin args`; {:ok bool :out str :err str}. A launch failure is {:ok false :launch-failed true}."
  [opts bin & args]
  (try (let [{:keys [exit out err]} @(apply process/process (merge {:out :string :err :string} opts) bin args)]
         {:ok (zero? exit) :out out :err err})
       (catch Exception e {:ok false :launch-failed true :out "" :err (ex-message e)})))

(defn- pane-gone?
  "True when a failed `herdr` result carries the error code `pane_not_found`."
  [{:keys [err]}]
  (try (= "pane_not_found" (get-in (json/parse-string (str/trim err) true) [:error :code]))
       (catch Exception _ false)))

(defn- herdr [& args]
  (let [r (apply run {} (or (env "HERDR_BIN_PATH") "herdr") args)]
    (when-not (:ok r) (warn "herdr" (str/join " " args) "failed:" (str/trim (:err r))))
    r))

(defn- clear-pane!
  "Clear the tokens on `pane`. A pane Herdr no longer knows has nothing to clear: success, no warning."
  [pane]
  (let [args (clear-args pane)
        r    (apply run {} (or (env "HERDR_BIN_PATH") "herdr") args)]
    (cond (:ok r)        r
          (pane-gone? r) (assoc r :ok true)
          :else          (do (warn "herdr" (str/join " " args) "failed:" (str/trim (:err r))) r))))

(def ^:private git-launch-warned (atom false))

(defn- git-root [cwd]
  (when (and cwd (fs/directory? cwd))
    (let [r (run {:dir cwd} "git" "rev-parse" "--show-toplevel")]
      ;; a non-zero exit is a non-git cwd; a launch failure hides every ledger, so say so once
      (when (and (:launch-failed r) (compare-and-set! git-launch-warned false true))
        (warn "cannot launch git; ledgers are not found:" (:err r)))
      (when (:ok r) (not-empty (str/trim (:out r)))))))

(defn- read-ledger-file [f]
  (try (let [m (json/parse-string (slurp (str f)) true)]
         (if (map? m) m (throw (ex-info "not a JSON object" {}))))
       (catch Exception e (warn "skipping malformed ledger file" (str f) "-" (ex-message e)) nil)))

(defn ledger-entries
  "Entries of `<root>/.tmp/herdr-orch/ledger/*.json` for every distinct git root of `agents`' cwds."
  [agents]
  (->> (keep (comp git-root :cwd) agents)
       distinct
       (mapcat (fn [root]
                 (let [dir (fs/path root ".tmp" "herdr-orch" "ledger")]
                   (when (fs/directory? dir) (sort (map str (fs/glob dir "*.json")))))))
       (keep read-ledger-file)))

;; --- socket -----------------------------------------------------------------------------

(defn socket-request
  "Send one newline-delimited JSON request over the Unix socket; the parsed response line."
  [path method params]
  (with-open [ch (SocketChannel/open StandardProtocolFamily/UNIX)]
    (.connect ch (UnixDomainSocketAddress/of (str path)))
    (let [out  (Channels/newOutputStream ch)
          in   (java.io.BufferedReader. (java.io.InputStreamReader. (Channels/newInputStream ch) "UTF-8"))
          read (future (.readLine in))]
      (.write out (.getBytes (str (json/generate-string {:id (str "tree-" method) :method method :params params}) "\n") "UTF-8"))
      (.flush out)
      (let [line (deref read 10000 ::timeout)]
        (when (or (= ::timeout line) (nil? line)) (throw (ex-info (str method ": no response from socket") {})))
        (let [resp (json/parse-string line true)]
          (when (:error resp) (throw (ex-info (str method " failed: " (json/generate-string (:error resp))) {})))
          resp)))))

(defn- socket-ok? [method params]
  (try (socket-request (require-env "HERDR_SOCKET_PATH") method params) true
       (catch Exception e (warn (ex-message e)) false)))

;; --- state, lock ------------------------------------------------------------------------

(defn session-dir []
  (let [key (str/replace (or (env "HERDR_SESSION") "default") #"[^A-Za-z0-9_.-]" "_")
        dir (fs/path (require-env "HERDR_PLUGIN_STATE_DIR") (str "session-" key))]
    (fs/create-dirs dir)
    dir))

(defn- server-id
  "Identifies the running server by its socket file: a restart creates a new one, and pane ids
  from the old server are meaningless."
  []
  (try (let [attrs (java.nio.file.Files/readAttributes (fs/path (require-env "HERDR_SOCKET_PATH"))
                                                       java.nio.file.attribute.BasicFileAttributes
                                                       (make-array java.nio.file.LinkOption 0))]
         (str (.fileKey attrs) "@" (.toMillis (.creationTime attrs))))
       (catch Exception _ nil)))

(defn- read-state [dir]
  (let [f (fs/path dir "tree-state.json")]
    (if-not (fs/exists? f)
      {}
      (try (let [m (json/parse-string (slurp (str f)) true)]
             (if (map? m) m (throw (ex-info "not a JSON object" {}))))
           (catch Exception e (throw (ex-info (str "unreadable state file " f ": " (ex-message e)) {})))))))

(defn- write-json! [f v]
  (let [tmp (fs/path (fs/parent f) (str (fs/file-name f) "." (random-uuid)))]
    (try (spit (str tmp) (json/generate-string v))
         (fs/move tmp f {:replace-existing true :atomic-move true})
         (finally (fs/delete-if-exists tmp)))))

(defn- write-state! [dir state] (write-json! (fs/path dir "tree-state.json") state))

(defn- display-file [] (fs/path (require-env "HERDR_PLUGIN_STATE_DIR") "display.json"))

(defn- display-enabled?
  "Absent means enabled. An unreadable file is an error: a damaged flag must not silently re-enable the tree."
  []
  (let [f (display-file)]
    (if-not (fs/exists? f)
      true
      (try (let [m (json/parse-string (slurp (str f)) true)]
             (if (map? m) (not (false? (:enabled m))) (throw (ex-info "not a JSON object" {}))))
           (catch Exception e (throw (ex-info (str "unreadable display file " f ": " (ex-message e)) {})))))))

(defn- try-lock [dir]
  (let [ch (FileChannel/open (fs/path dir "tree.lock") (into-array StandardOpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE]))]
    (if-let [lock (.tryLock ch)] {:ch ch :lock lock} (do (.close ch) nil))))

(defn- unlock! [{:keys [ch]}] (.close ch)) ; closing the channel releases its lock

(defn- block-lock
  "Blocking lock on `name` in `dir`."
  [dir name]
  (let [ch (FileChannel/open (fs/path dir name) (into-array StandardOpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE]))]
    {:ch ch :lock (.lock ch)}))

;; --- reconcile --------------------------------------------------------------------------

(defn- clear-failures
  "Clear tokens on `panes`; the set that failed and must stay in state. A failure on a pane that
  is not in `live` (closed) is not a failure."
  [panes live]
  (->> panes (remove #(:ok (clear-pane! %))) (filter live) set))

(defn- reset-once!
  "Clear every recorded pane, drop the plugin view, empty the state. The caller holds the lock."
  [dir]
  (let [old    (read-state dir)
        panes  (map name (keys (:panes old)))
        lr     (herdr "agent" "list")
        live   (if (:ok lr) (set (map :pane_id (parse-agents (:out lr)))) (set panes))
        failed (clear-failures panes live)
        view-ok (socket-ok? "agent.view.clear" {:source source})]
    (write-state! dir {:server (:server old) :view (not view-ok)
                       :panes (select-keys (:panes old) (map keyword failed))})
    (and (empty? failed) view-ok)))

(defn- reconcile-enabled!
  "One pass over the live agents and ledgers; see `reconcile-once!`."
  [dir]
  (let [list-r (herdr "agent" "list")
        ws-r   (when (:ok list-r) (herdr "workspace" "list"))]
    (if-not (:ok ws-r)
      false
      (let [agents  (parse-agents (:out list-r))
            live    (set (map :pane_id agents))
            want    (desired (tree/rows (ledger-entries agents) agents) agents (parse-workspace-labels (:out ws-r)))
            sid     (server-id)
            old     (read-state dir)
            fresh?  (not= sid (:server old))
            prev    (if fresh? {} (into {} (map (fn [[k v]] [(name k) v])) (:panes old)))
            changed (filter (fn [[p w]] (not= w (get prev p))) want)
            stale   (remove want (keys prev))
            set-res (into {} (map (fn [[p w]] [p (:ok (apply herdr (set-args p w (get prev p))))])) changed)
            failed-clear (clear-failures stale live)
            panes   (merge (select-keys prev failed-clear)
                           (into {} (keep (fn [[p w]] (cond (not (contains? set-res p)) [p w]
                                                            (set-res p)                 [p w]
                                                            (prev p)                    [p (prev p)])))
                                 want))
            ;; known-limit: the view is set only once rows exist, so a session with no oh agents keeps its own sort
            view?   (and (not fresh?) (:view old))
            ;; re-sent with any report: Herdr may have dropped the view while the state file still says set
            send-view? (and (some :order (vals want)) (or (not view?) (seq set-res) (seq stale)))
            view-ok (if send-view? (socket-ok? "agent.view.set" view-params) true)
            view    (boolean (if send-view? view-ok view?))]
        (write-state! dir {:server sid :view view :panes panes})
        (and (every? true? (vals set-res)) (empty? failed-clear) view-ok)))))

(defn- reconcile-once!
  "One pass. Returns true when every `herdr` and socket call succeeded. While the display flag is
  off it resets a session that still records panes or a view, and otherwise sends nothing."
  [dir]
  (if-not (display-enabled?)
    (let [old (read-state dir)]
      (if (or (seq (:panes old)) (:view old)) (reset-once! dir) true))
    (reconcile-enabled! dir)))

(defn reconcile!
  "Serialised reconcile. A run that finds the lock held leaves a dirty marker for the holder."
  []
  (let [dir   (session-dir)
        dirty (fs/path dir "tree.dirty")]
    (loop [ok true, marked? false]
      (if-let [lock (try-lock dir)]
        (let [_  (fs/delete-if-exists dirty)
              ok (and (try (reconcile-once! dir) (finally (unlock! lock))) ok)]
          ;; a marker written after the delete, whether by a contender or after the release
          (if (fs/exists? dirty) (recur ok false) ok))
        (if marked?
          ok
          (do (spit (str dirty) "1") ; the holder may release before the marker lands: retry once
              (recur ok true)))))))

(defn- flip-display!
  "Read, flip and write the global flag under `display.lock`; the new value. The lock is never
  held while waiting for a session lock, so two toggles cannot lose a flip."
  []
  (let [lock (block-lock (fs/create-dirs (require-env "HERDR_PLUGIN_STATE_DIR")) "display.lock")]
    (try (let [enabled (not (display-enabled?))]
           (write-json! (display-file) {:enabled enabled})
           enabled)
         (finally (unlock! lock)))))

(defn toggle!
  "Flip the global display flag, then reconcile this session. The reconcile reads the flag inside
  the session lock (off: reset what the session recorded; on: rebuild), and a run that finds the
  lock held leaves the dirty marker, so the holder applies the latest flag and no toggle is lost."
  []
  (flip-display!)
  (reconcile!))

(defn -main [& [cmd]]
  (try (case cmd
         "reconcile" (if (reconcile!) 0 1)
         "toggle"    (if (toggle!) 0 1)
         (do (warn "usage: tree.bb reconcile|toggle") 1))
       (catch Exception e (warn (ex-message e)) 1)))

#!/usr/bin/env bb
;; Reflow check for plan task fe753777: a disposable Herdr session in which a stand-in
;; compact pane follows tab focus, as the follow-focus design moves it.
;; Run from the repository root:
;;   bb design/log/2026-11-10-org-tasks-follow-focus-pane/reflow/reflow-check.bb
;; Then attach from another terminal with the printed command, switch tabs and workspaces
;; with your usual keys or the mouse, and watch the panes. Press Enter here to stop.
;; The session has isolated XDG directories, so your session, config and plugins are
;; untouched; it stops by itself after 15 minutes.
(require '[babashka.fs :as fs] '[babashka.process :as p] '[cheshire.core :as j] '[clojure.string :as str])
(import '[java.net UnixDomainSocketAddress StandardProtocolFamily]
        '[java.nio.channels SocketChannel Channels]
        '[java.io BufferedReader InputStreamReader])

(def rows 6) ; the compact pane's target height, as :compact-rows 5 plus its border
(def token (subs (str (random-uuid)) 0 8))
(def repo (str (fs/cwd)))
(def base (str "/tmp/rf-" token))
(def session (str "rf" token))
(def out-log (str repo "/.tmp/reflow-check-" token ".log"))
(def binary (str (fs/which "herdr")))
(doseq [d ["/config/herdr" "/state" "/runtime" "/work" "/plugin"]] (fs/create-dirs (str base d)))
(fs/create-dirs (str repo "/.tmp"))

(def xdg {"XDG_CONFIG_HOME" (str base "/config") "XDG_STATE_HOME" (str base "/state")
          "XDG_RUNTIME_DIR" (str base "/runtime")})
(def env (merge (into {} (remove (fn [[k _]] (re-find #"^(HERDR_|HC_|ORCH_|OT_)" k)) (System/getenv))) xdg))
(defn note [& xs]
  (let [line (str (System/currentTimeMillis) " " (str/join " " xs))]
    (spit out-log (str line "\n") :append true)))

(defn run! [& args]
  (let [proc (p/process (into [binary "--session" session] args) {:env env :out :string :err :string})
        result (deref proc 15000 nil)]
    (when-not result (p/destroy-tree proc) (throw (ex-info "timeout" {:args args})))
    (when-not (zero? (:exit result)) (throw (ex-info (str "CLI failed: " (:err result)) {:args args})))
    (let [value (j/parse-string (:out result) true)]
      (when (:error value) (throw (ex-info "API error" value)))
      (:result value))))
(defn snap [] (:snapshot (run! "api" "snapshot")))
(defn wait! [f]
  (loop [n 60] (or (try (f) (catch Exception _ nil))
                   (if (pos? n) (do (Thread/sleep 250) (recur (dec n))) (throw (ex-info "wait failed" {}))))))

;; A stand-in compact pane, and workload panes that show their size and rewrap text.
;; Each draws once and redraws only on SIGWINCH, so any flicker comes from the move
;; and the resize, not from the panes themselves.
(def redraw-loop "trap draw WINCH; draw; while :; do sleep 60 & wait $!; done\n")
(spit (str base "/plugin/compact.sh")
      (str "#!/usr/bin/env bash\ndraw() { clear; printf 'STAND-IN COMPACT PANE  %sx%s\\n' \"$(tput cols)\" \"$(tput lines)\"; printf 'TODO Example task\\n  ... STARTED Example subtask\\n'; }\n" redraw-loop))
(spit (str base "/work/load.sh")
      (str "#!/usr/bin/env bash\nlabel=$1\ndraw() { clear; printf '%s  %sx%s\\n\\n' \"$label\" \"$(tput cols)\" \"$(tput lines)\"; seq 1 400 | tr '\\n' ' '; }\n" redraw-loop))
(doseq [f [(str base "/plugin/compact.sh") (str base "/work/load.sh")]] (fs/set-posix-file-permissions f "rwx------"))
(spit (str base "/plugin/herdr-plugin.toml")
      (str "id = \"reflow.check\"\nname = \"reflow check\"\nversion = \"0.0.1\"\nmin_herdr_version = \"0.9.5\"\nplatforms = [\"linux\", \"macos\"]\n"
           "\n[[panes]]\nid = \"compact\"\ntitle = \"Tasks (stand-in)\"\nplacement = \"split\"\ncommand = [\"bash\", \"compact.sh\"]\n"))

(defn subscribe! [socket on-event]
  (let [ch (SocketChannel/open StandardProtocolFamily/UNIX)]
    (.connect ch (UnixDomainSocketAddress/of socket))
    (let [w (Channels/newOutputStream ch)
          r (BufferedReader. (InputStreamReader. (Channels/newInputStream ch) "UTF-8"))]
      (.write w (.getBytes (str (j/generate-string {:id "sub" :method "events.subscribe"
                                                    :params {:subscriptions [{:type "tab.focused"}]}}) "\n") "UTF-8"))
      (.flush w)
      (future (try (loop [] (when-let [line (.readLine r)]
                              (try (on-event (j/parse-string line true))
                                   (catch Exception e (note "EVENT-ERROR" (ex-message e))))
                              (recur)))
                   (catch Exception e (note "SUBSCRIPTION-ENDED" (ex-message e)))))
      ch)))

(def server (atom nil))
(def pane-id (atom nil))
(def lock (Object.))

(defn follow!
  "Move the stand-in pane below the focused pane of `tab`, sized to `rows`."
  [tab]
  (locking lock
    (let [started (System/currentTimeMillis)
          s (snap)
          current (some #(when (= @pane-id (:pane_id %)) %) (:panes s))
          target-id (some #(when (= tab (:tab_id %)) (:focused_pane_id %)) (:layouts s))
          target (or (some #(when (= target-id (:pane_id %)) %) (:panes s))
                     (some #(when (and (= tab (:tab_id %)) (not= @pane-id (:pane_id %))) %) (:panes s)))]
      (when (and current target (not= tab (:tab_id current)))
        (let [height (or (some #(when (= (:pane_id target) (:pane_id %)) (get-in % [:rect :height]))
                               (get-in (run! "pane" "layout" "--pane" (:pane_id target)) [:layout :panes]))
                         40)
              ratio (max 0.5 (min 0.95 (/ (- height rows) (double height))))
              moved (run! "pane" "move" @pane-id "--tab" tab "--split" "down" "--target-pane" (:pane_id target)
                          "--ratio" (format "%.3f" ratio) "--no-focus")]
          (reset! pane-id (get-in moved [:move_result :pane :pane_id]))
          (note "MOVED" tab "target" (:pane_id target) "height" height "ratio" (format "%.3f" ratio)
                "ms" (- (System/currentTimeMillis) started)))))))

(try
  (spit (str base "/shell") (str "#!/bin/sh\nexec " (fs/which "bash") " --noprofile --norc\n"))
  (fs/set-posix-file-permissions (str base "/shell") "rwx------")
  (spit (str base "/config/herdr/config.toml")
        (str "onboarding = false\n[terminal]\ndefault_shell = " (pr-str (str base "/shell")) "\n"))
  (reset! server (p/process [binary "--session" session "server"]
                            {:env env :out :write :out-file (str base "/server.log") :err :out}))
  (wait! snap)
  (run! "plugin" "link" (str base "/plugin"))
  (let [load (fn [pane label] (run! "pane" "run" pane (str "exec " base "/work/load.sh '" label "'")))
        w1 (run! "workspace" "create" "--cwd" (str base "/work") "--label" "one" "--focus")
        t1 (get-in w1 [:tab :tab_id]) a (get-in w1 [:root_pane :pane_id])
        b (get-in (run! "pane" "split" a "--direction" "right" "--no-focus") [:pane :pane_id])
        t2r (run! "tab" "create" "--workspace" (get-in w1 [:workspace :workspace_id]) "--cwd" (str base "/work") "--label" "two" "--no-focus")
        c (get-in t2r [:root_pane :pane_id])
        w2 (run! "workspace" "create" "--cwd" (str base "/work") "--label" "two-ws" "--no-focus")
        d (get-in w2 [:root_pane :pane_id])]
    (doseq [[pane label] [[a "tab one, left"] [b "tab one, right"] [c "tab two"] [d "workspace two"]]] (load pane label))
    (reset! pane-id (get-in (run! "plugin" "pane" "open" "--plugin" "reflow.check" "--entrypoint" "compact"
                                  "--placement" "split" "--target-pane" a "--direction" "down" "--no-focus")
                            [:plugin_pane :pane :pane_id]))
    (note "OPENED" @pane-id "in" t1)
    (subscribe! (str (fs/path base "config" "herdr" "sessions" session "herdr.sock"))
                (fn [e] (when (= "tab_focused" (:event e)) (follow! (get-in e [:data :tab_id])))))
    (println "Disposable Herdr session ready. In another terminal, attach with:\n")
    (println (str "  " (str/join " " (map (fn [[k v]] (str k "=" v)) xdg)) " " binary " --session " session "\n"))
    (println "It has tabs \"one\" and \"two\" in workspace \"one\", and workspace \"two-ws\".")
    (println "Switch tabs and workspaces; the stand-in Tasks pane follows focus.")
    (println (str "Moves and their latency are logged to " out-log))
    (println "Press Enter here to stop and delete the session.")
    (flush)
    (deref (future (read-line)) (* 15 60 1000) nil))
  (finally
    (try (run! "server" "stop") (catch Exception e (println "stop failed:" (ex-message e))))
    (when (and @server (nil? (deref @server 5000 nil))) (p/destroy-tree @server))
    (deref (p/process [binary "--session" session "session" "delete" session "--json"]
                      {:env env :out :string :err :string}) 8000 nil)
    (fs/delete-tree base)
    (println "Stopped and deleted. Log:" out-log)
    (System/exit 0)))

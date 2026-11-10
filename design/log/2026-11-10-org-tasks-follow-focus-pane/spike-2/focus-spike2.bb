#!/usr/bin/env bb
;; Spike for a follow-focus org-tasks pane. Disposable Herdr server with isolated XDG
;; dirs (pattern: design/log/2026-11-06-herdr-orch-comms/bench/spike-driver.bb).
;; Questions:
;;  1. events.subscribe wire protocol; one connection keeps receiving events.
;;  2. tab_focused names the tab; pane_updated reports a folder change.
;;  3. tab.focused / workspace.focused work as plugin [[events]] hooks.
;;  4. pane move of a plugin pane across tabs and workspaces: same process, new id,
;;     old id still resolves from inside, --ratio sets the height.
(require '[babashka.fs :as fs] '[babashka.process :as p] '[cheshire.core :as j] '[clojure.string :as str])
(import '[java.net UnixDomainSocketAddress StandardProtocolFamily]
        '[java.nio.channels SocketChannel Channels]
        '[java.io BufferedReader InputStreamReader])

(def token (subs (str (random-uuid)) 0 8))
(def repo (str (fs/cwd)))
(def base (str "/tmp/fs-" token))
(def session (str "fs" token))
(def out-dir (str repo "/.tmp/focus-spike-" token))
(def binary (str (fs/which "herdr")))
(def real-registry (str (fs/home) "/.config/herdr/plugins.json"))
(defn digest [f] (when (fs/exists? f) (str/trim (:out (p/shell {:out :string} "sha256sum" f)))))
(def registry-before (digest real-registry))
(doseq [d ["/config/herdr" "/state" "/runtime" "/work/a" "/work/b" "/plugin"]] (fs/create-dirs (str base d)))
(fs/create-dirs out-dir)

(def env (-> (into {} (remove (fn [[k _]] (re-find #"^(HERDR_|HC_|ORCH_|OT_)" k)) (System/getenv)))
             (merge {"XDG_CONFIG_HOME" (str base "/config") "XDG_STATE_HOME" (str base "/state")
                     "XDG_RUNTIME_DIR" (str base "/runtime")})))
(def log (atom []))
(defn note [& xs] (let [line (str/join " " xs)] (println line) (flush) (swap! log conj line)))

(defn run! [& args]
  (let [proc (p/process (into [binary "--session" session] args) {:env env :out :string :err :string})
        result (deref proc 15000 nil)]
    (when-not result (p/destroy-tree proc) (throw (ex-info "timeout" {:args args})))
    (when-not (zero? (:exit result)) (throw (ex-info "CLI failed" {:args args :result result})))
    (let [value (try (j/parse-string (:out result) true) (catch Exception _ {:raw (:out result)}))]
      (when (:error value) (throw (ex-info "API error" value)))
      (or (:result value) value))))
(defn try-run! [& args] (try {:ok (apply run! args)} (catch Exception e {:error (ex-message e) :data (ex-data e)})))
(defn snap [] (:snapshot (run! "api" "snapshot")))
(defn wait! [label f & {:keys [tries sleep] :or {tries 60 sleep 250}}]
  (loop [n tries]
    (let [v (try (f) (catch Exception _ nil))]
      (cond v v (pos? n) (do (Thread/sleep sleep) (recur (dec n)))
            :else (throw (ex-info (str "wait failed: " label) {}))))))
(defn pane-of [s id] (some #(when (= id (:pane_id %)) %) (:panes s)))

;; ── test plugin ──
(def hook-log (str base "/hooks.log"))
(def probe-log (str base "/probe.log"))
(spit (str base "/plugin/hook.sh")
      (str "#!/bin/sh\n{ printf '%s %s\\n' \"$1\" \"$(date +%s%N)\"; env | grep '^HERDR_' | sort | sed 's/^/  /';"
           " printf '  stdin: %s\\n' \"$(timeout 1 cat 2>/dev/null | head -c 400)\"; } >> " hook-log "\n"))
(spit (str base "/plugin/probe.sh")
      (str "#!/bin/sh\nwhile :; do printf '%s pid=%s env=%s current=%s\\n' \"$(date +%s%N)\" \"$$\" \"$HERDR_PANE_ID\""
           " \"$(herdr pane current --current 2>&1 | tr -d '\\n' | head -c 300)\" >> " probe-log "; sleep 1; done\n"))
(doseq [f ["hook.sh" "probe.sh"]] (fs/set-posix-file-permissions (str base "/plugin/" f) "rwx------"))
(spit (str base "/plugin/herdr-plugin.toml")
      (str "id = \"spike.focus\"\nname = \"spike\"\nversion = \"0.0.1\"\nmin_herdr_version = \"0.9.5\"\nplatforms = [\"linux\", \"macos\"]\n"
           (str/join (for [e ["tab.focused" "workspace.focused" "pane.focused"]]
                       (str "\n[[events]]\non = \"" e "\"\ncommand = [\"sh\", \"hook.sh\", \"" e "\"]\n")))
           "\n[[panes]]\nid = \"probe\"\ntitle = \"probe\"\nplacement = \"split\"\ncommand = [\"sh\", \"probe.sh\"]\n"))

;; ── event subscriber ──
(defn subscribe! [socket types]
  (let [ch (SocketChannel/open StandardProtocolFamily/UNIX)]
    (.connect ch (UnixDomainSocketAddress/of socket))
    (let [w (Channels/newOutputStream ch)
          r (BufferedReader. (InputStreamReader. (Channels/newInputStream ch) "UTF-8"))
          events (atom [])]
      (.write w (.getBytes (str (j/generate-string {:id "sub-1" :method "events.subscribe"
                                                    :params {:subscriptions (mapv (fn [t] {:type t}) types)}}) "\n") "UTF-8"))
      (.flush w)
      (future (try (loop [] (when-let [line (.readLine r)]
                              (swap! events conj {:t (System/currentTimeMillis) :line line}) (recur)))
                   (catch Exception e (swap! events conj {:t (System/currentTimeMillis) :error (ex-message e)}))))
      {:channel ch :events events})))

(def server (atom nil))
(def sub (atom nil))
(try
  (spit (str base "/shell") (str "#!/bin/sh\nexec " (fs/which "bash") " --noprofile --norc\n"))
  (fs/set-posix-file-permissions (str base "/shell") "rwx------")
  (spit (str base "/config/herdr/config.toml")
        (str "onboarding = false\n[terminal]\ndefault_shell = " (pr-str (str base "/shell")) "\n"))
  (reset! server (p/process [binary "--session" session "server"]
                            {:env env :out :write :out-file (str base "/server.log") :err :out}))
  (wait! "isolated server" #(snap))
  (def socket (str (first (filter #(not (str/ends-with? (str %) ".agent"))
                                  (fs/glob base "**.sock")))))
  (note "SOCKETS" (pr-str (mapv str (fs/glob base "**.sock"))) "using" socket)
  (reset! sub (subscribe! socket ["pane.updated"
                                  ]))
  (Thread/sleep 500)
  (note "SUBSCRIBE first lines" (pr-str (mapv :line (take 2 @(:events @sub)))))

  (let [w1 (run! "workspace" "create" "--cwd" (str base "/work/a") "--focus")
        p1 (get-in w1 [:root_pane :pane_id]) t1 (get-in w1 [:tab :tab_id])]
    (Thread/sleep 800)
    (note "MARK cd") (run! "pane" "run" p1 (str "cd " base "/work/b")) (Thread/sleep 3000)
    (note "CWD after cd" (:foreground_cwd (pane-of (snap) p1)))
    (note "MARK foreground command") (run! "pane" "run" p1 "sleep 2") (Thread/sleep 3500)
    (note "MARK title") (run! "pane" "run" p1 "printf '\\033]2;spike-title\\007'") (Thread/sleep 2000)
    (note "TITLE" (:terminal_title (pane-of (snap) p1)))
    (note "MARK rename (control)") (run! "pane" "rename" p1 "renamed") (Thread/sleep 1500)
    (let [s1 (run! "pane" "split" p1 "--direction" "down" "--ratio" "0.85" "--no-focus")
          p2 (get-in s1 [:pane :pane_id])]
      (Thread/sleep 800)
      (note "SPLIT ratio 0.85" (pr-str (map #(select-keys % [:pane_id :rect]) (get-in (run! "pane" "layout" "--pane" p1) [:layout :panes]))))))
  (Thread/sleep 500)
  (finally
    (try (run! "server" "stop") (catch Exception e (note "stop failed" (ex-message e))))
    (when (and @server (nil? (deref @server 5000 nil))) (p/destroy-tree @server))
    (let [r (deref (p/process [binary "--session" session "session" "delete" session "--json"]
                              {:env env :out :string :err :string}) 8000 nil)]
      (note "CLEANUP session delete exit" (:exit r)))
    (when @sub (spit (str out-dir "/events.jsonl") (str/join "\n" (map #(j/generate-string %) @(:events @sub)))))
    (doseq [[f to] [[hook-log "hooks.log"] [probe-log "probe.log"] [(str base "/server.log") "server.log"]]]
      (when (fs/exists? f) (fs/copy f (str out-dir "/" to) {:replace-existing true})))
    (spit (str out-dir "/driver.log") (str/join "\n" @log))
    (fs/delete-tree base)
    (note "OUT" out-dir)
    (note "REAL plugins.json unchanged:" (= registry-before (digest real-registry)))))

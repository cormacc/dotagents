#!/usr/bin/env bb
;; Does an org-tasks compact pane keep a tab open after its only other pane closes?
;; Disposable Herdr server with isolated XDG dirs (pattern from
;; design/log/2026-11-06-herdr-orch-comms/bench/spike-driver.bb). Run from the repo root.
;; Control: the same steps before the plugin is linked.
(require '[babashka.fs :as fs] '[babashka.process :as p] '[cheshire.core :as j] '[clojure.string :as str])

(def token (subs (str (random-uuid)) 0 8))
(def repo (str (fs/cwd)))
(def base (str "/tmp/tr-" token))
(def session (str "tr" token))
(def binary (str (fs/which "herdr")))
(def real-registry (str (fs/home) "/.config/herdr/plugins.json"))
(defn digest [f] (when (fs/exists? f) (str/trim (:out (p/shell {:out :string} "sha256sum" f)))))
(def registry-before (digest real-registry))
(doseq [d ["/config/herdr" "/state" "/runtime" "/work"]] (fs/create-dirs (str base d)))

(def env (-> (into {} (remove (fn [[k _]] (re-find #"^(HERDR_|HC_|ORCH_|OT_)" k)) (System/getenv)))
             (merge {"XDG_CONFIG_HOME" (str base "/config") "XDG_STATE_HOME" (str base "/state")
                     "XDG_RUNTIME_DIR" (str base "/runtime")})))

(defn run! [& args]
  (let [proc (p/process (into [binary "--session" session] args) {:env env :out :string :err :string})
        result (deref proc 15000 nil)]
    (when-not result (p/destroy-tree proc) (throw (ex-info "timeout" {:args args})))
    (when-not (zero? (:exit result)) (throw (ex-info "CLI failed" {:args args :result result})))
    (let [value (try (j/parse-string (:out result) true) (catch Exception _ {:raw (:out result)}))]
      (when (:error value) (throw (ex-info "API error" value)))
      (or (:result value) value))))
(defn snap [] (:snapshot (run! "api" "snapshot")))
(defn wait! [label f & {:keys [tries sleep] :or {tries 60 sleep 250}}]
  (loop [n tries]
    (let [v (try (f) (catch Exception _ nil))]
      (cond v v
            (pos? n) (do (Thread/sleep sleep) (recur (dec n)))
            :else (throw (ex-info (str "wait failed: " label) {}))))))
(defn tab-panes [s tab] (filter #(= tab (:tab_id %)) (:panes s)))
(defn tab-live? [s tab] (boolean (some #(= tab (:tab_id %)) (:tabs s))))

;; Mirror `oh`: a new unfocused tab, then close only the agent's (root) pane.
(defn trial [label wait-for-compact?]
  (let [created (run! "tab" "create" "--cwd" (str base "/work") "--label" label "--no-focus")
        tab (get-in created [:tab :tab_id]) root (get-in created [:root_pane :pane_id])]
    (when wait-for-compact?
      (wait! "compact pane opened" #(when (= 2 (count (tab-panes (snap) tab))) true) :tries 80))
    (Thread/sleep 1500) ; let any hook settle in both trials
    (let [before (mapv #(select-keys % [:pane_id :title :label]) (tab-panes (snap) tab))]
      (run! "pane" "close" root)
      (loop [n 24] (when (and (pos? n) (tab-live? (snap) tab)) (Thread/sleep 250) (recur (dec n))))
      (let [s (snap)]
        {:label label :panes-before before :tab-live-after (tab-live? s tab)
         :panes-after (mapv #(select-keys % [:pane_id :title :label]) (tab-panes s tab))}))))

(def server (atom nil))
(try
  (spit (str base "/shell") (str "#!/bin/sh\nexec " (fs/which "bash") " --noprofile --norc\n"))
  (fs/set-posix-file-permissions (str base "/shell") "rwx------")
  (spit (str base "/config/herdr/config.toml")
        (str "onboarding = false\n[terminal]\ndefault_shell = " (pr-str (str base "/shell")) "\n"))
  (reset! server (p/process [binary "--session" session "server"]
                            {:env env :out :write :out-file (str base "/server.log") :err :out}))
  (wait! "isolated server" #(snap))
  (run! "workspace" "create" "--cwd" (str base "/work") "--no-focus")
  (println "CONTROL" (pr-str (trial "control" false)))
  (run! "plugin" "link" (str repo "/herdr/plugins/org-tasks"))
  (println "PLUGINS" (pr-str (run! "plugin" "list" "--json")))
  (println "WITH-PLUGIN" (pr-str (trial "with-plugin" true)))
  (finally
    (try (run! "server" "stop") (catch Exception e (println "stop failed" (ex-message e))))
    (when (and @server (nil? (deref @server 5000 nil))) (p/destroy-tree @server))
    (let [r (deref (p/process [binary "--session" session "session" "delete" session "--json"]
                              {:env env :out :string :err :string}) 8000 nil)]
      (println "CLEANUP session delete exit" (:exit r)))
    (fs/copy (str base "/server.log") (str repo "/.tmp/tab-retain-server.log") {:replace-existing true})
    (fs/delete-tree base)
    (println "REAL plugins.json unchanged:" (= registry-before (digest real-registry)))))

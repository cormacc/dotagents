#!/usr/bin/env bb
;; Spike driver: disposable Herdr server, stub `pi` agents, one parent pane that runs
;; spike-parent.clj through the stub. Run from the repository root.
;; Usage: bb .tmp/comms-bench/spike-driver.bb [children]
(require '[babashka.fs :as fs] '[babashka.process :as p] '[cheshire.core :as j] '[clojure.string :as str])

(def children (or (some-> (first *command-line-args*) parse-long) 2))
(def token (subs (str (random-uuid)) 0 8))
(def repo (str (fs/cwd)))
(def base (str "/tmp/cb-" token)) ; short: macOS sun_path limit
(def session (str "cb" token))
(def keep (str (fs/absolutize (str ".tmp/comms-bench/run-" token))))
(fs/create-dirs keep)
(def binary (str (fs/which "herdr")))
(def real-pi (str (fs/which "pi")))
(def stub-dir (str base "/bin"))
(fs/create-dirs stub-dir)
(fs/create-dirs (str base "/config/herdr"))
(fs/create-dirs (str base "/runtime"))
(fs/create-dirs (str base "/assign"))
(fs/create-dirs (str base "/work"))
;; Concurrent write-enabled children need a repository to branch managed checkouts from.
(let [g (fn [& a] (let [r @(p/process (into ["git" "-C" (str base "/work")] a) {:out :string :err :string})] (assert (zero? (:exit r)) (:err r))))]
  (g "init" "-q") (spit (str base "/work/README") "bench\n") (g "add" "README") (g "-c" "user.name=bench" "-c" "user.email=bench@example.invalid" "commit" "-q" "-m" "init"))

;; Server env: no ambient host variables; stub dir first on PATH so `agent start --kind pi`
;; launches the stub inside every pane shell.
(def env (-> (into {} (remove (fn [[k _]] (re-find #"^(HERDR_|HC_|ORCH_|PI_)" k)) (System/getenv)))
             (merge {"XDG_CONFIG_HOME" (str base "/config") "XDG_STATE_HOME" (str base "/state")
                     "XDG_RUNTIME_DIR" (str base "/runtime")
                     "PATH" (str stub-dir ":" (System/getenv "PATH"))
                     "HERDR_ORCH_PI_BIN" real-pi
                     "ORCH_ASSIGNMENT_ROOT" (str base "/assign")
                     "BENCH_BASE" base "BENCH_REPO" repo "BENCH_CHILDREN" (str children)})))

(defn run! [& args]
  (let [proc (p/process (into [binary "--session" session] args) {:env env :out :string :err :string})
        result (deref proc 15000 nil)]
    (when-not result (p/destroy-tree proc) (throw (ex-info "timeout" {:args args})))
    (spit (str base "/commands.edn") (str (pr-str {:args args :result (select-keys result [:exit :out :err])}) "\n") :append true)
    (println "herdr" (str/join " " args) "->" (:exit result)) (flush)
    (when-not (zero? (:exit result)) (throw (ex-info "CLI failed" {:args args :result result})))
    (let [value (j/parse-string (:out result) true)]
      (when (:error value) (throw (ex-info "API error" value))) (:result value))))
(defn wait! [label f & {:keys [tries sleep] :or {tries 60 sleep 250}}]
  (loop [n tries]
    (let [value (try (f) (catch Exception _ nil))]
      (if value (do (println "PASS" label) (flush) value)
          (if (pos? n) (do (Thread/sleep sleep) (recur (dec n)))
              (throw (ex-info "wait failed" {:label label})))))))
(defn snap [] (:snapshot (run! "api" "snapshot")))

(def server (atom nil))
(println "BASE" base "SESSION" session) (flush)
(try
  (let [r @(p/process ["cc" (str repo "/.tmp/comms-bench/stub-pi.c") "-o" (str stub-dir "/pi")] {:out :string :err :string})]
    (assert (zero? (:exit r)) (:err r)))
  (spit (str base "/shell") (str "#!/bin/sh\nexec " (fs/which "bash") " --noprofile --norc\n"))
  (fs/set-posix-file-permissions (str base "/shell") "rwx------")
  (spit (str base "/config/herdr/config.toml") (str "onboarding = false\n[terminal]\ndefault_shell = " (pr-str (str base "/shell")) "\n"))
  (reset! server (p/process [binary "--session" session "server"] {:env env :out :write :out-file (str base "/server.log") :err :out}))
  (wait! "isolated server" #(try (snap) (catch Exception _ nil)))
  (let [w (run! "workspace" "create" "--cwd" (str base "/work") "--no-focus")
        parent (get-in w [:root_pane :pane_id])]
    (println "PARENT PANE" parent)
    ;; The parent pane becomes a recognised `pi` agent whose child process is the bench parent.
    (run! "pane" "run" parent (str "BENCH_PARENT_CMD='bb " repo "/.tmp/comms-bench/spike-parent.clj > " base "/parent.log 2>&1' exec pi"))
    (wait! "parent recognised as pi" #(some (fn [a] (and (= parent (:pane_id a)) (= "pi" (:agent a)))) (:agents (snap))))
    (let [done (wait! "parent script finished" #(when (fs/exists? (str base "/parent.done")) (slurp (str base "/parent.done")))
                      :tries 240 :sleep 1000)]
      (println "PARENT DONE" done)
      (println "---- parent.log ----")
      (println (slurp (str base "/parent.log")))
      (when (fs/exists? (str base "/results.json")) (println "RESULTS" (slurp (str base "/results.json"))))
      (assert (= "ok" (str/trim done)) "parent script failed")
      (println "SPIKE PASS")))
  (finally
    (doseq [f ["parent.log" "results.json" "server.log" "commands.edn"]] (when (fs/exists? (str base "/" f)) (fs/copy (str base "/" f) (str keep "/" f) {:replace-existing true})))
    (println "KEPT" keep)
    (try (run! "server" "stop") (catch Exception e (println "stop failed" (ex-message e))))
    (when (nil? (deref @server 5000 nil)) (p/destroy-tree @server))
    (let [r (deref (p/process [binary "--session" session "session" "delete" session "--json"] {:env env :out :string :err :string}) 8000 nil)]
      (println "CLEANUP session delete" (select-keys r [:exit])))))

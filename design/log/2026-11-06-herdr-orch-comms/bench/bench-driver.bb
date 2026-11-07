#!/usr/bin/env bb
;; Bench driver: disposable Herdr server, stub `pi` agents, a counting `herdr` shim, and one
;; parent pane that runs the given parent script through the stub. Run from the repo root.
;; Usage: bb .tmp/comms-bench/bench-driver.bb <parent-script.clj> <children> [K=V ...]
(require '[babashka.fs :as fs] '[babashka.process :as p] '[cheshire.core :as j] '[clojure.string :as str])

(def parent-script (str (fs/absolutize (first *command-line-args*))))
(def children (or (some-> (second *command-line-args*) parse-long) 2))
(def extra-env (into {} (map (fn [kv] (let [[k v] (str/split kv #"=" 2)] [k v])) (drop 2 *command-line-args*))))
(def token (subs (str (random-uuid)) 0 8))
(def repo (str (fs/cwd)))
(def base (str "/tmp/cb-" token)) ; short: macOS sun_path limit
(def keep (str (fs/absolutize (str ".tmp/comms-bench/run-" token))))
(def session (str "cb" token))
(def binary (str (fs/which "herdr")))
(def real-pi (str (fs/which "pi")))
(def stub-dir (str base "/bin"))
(def shim-dir (str base "/shim"))
(doseq [d [stub-dir shim-dir (str base "/config/herdr") (str base "/runtime") (str base "/assign") (str base "/assign/.tmp/herdr-orch") (str base "/work") keep]]
  (fs/create-dirs d))
;; Concurrent write-enabled children need a repository to branch managed checkouts from.
(let [g (fn [& a] (let [r @(p/process (into ["git" "-C" (str base "/work")] a) {:out :string :err :string})] (assert (zero? (:exit r)) (:err r))))]
  (g "init" "-q") (spit (str base "/work/README") "bench\n") (g "add" "README")
  (g "-c" "user.name=bench" "-c" "user.email=bench@example.invalid" "commit" "-q" "-m" "init"))
;; Counting shim: every `herdr` subprocess appends "<epoch-ms> <verb> <noun>" and execs the real binary.
(spit (str shim-dir "/herdr")
      (str "#!/bin/sh\n"
           "if [ -n \"$BENCH_HERDR_LOG\" ]; then printf '%s %s %s %s\\n' \"$(date +%s)\" \"${HERDR_ORCH_CHILD:-parent}\" \"$1\" \"$2\" >> \"$BENCH_HERDR_LOG\"; fi\n"
           "exec '" binary "' \"$@\"\n"))
(fs/set-posix-file-permissions (str shim-dir "/herdr") "rwxr-xr-x")

(def env (-> (into {} (remove (fn [[k _]] (re-find #"^(HERDR_|HC_|ORCH_|PI_)" k)) (System/getenv)))
             (merge {"XDG_CONFIG_HOME" (str base "/config") "XDG_STATE_HOME" (str base "/state")
                     "XDG_RUNTIME_DIR" (str base "/runtime")
                     "PATH" (str stub-dir ":" shim-dir ":" (System/getenv "PATH"))
                     "HERDR_ORCH_PI_BIN" real-pi
                     "ORCH_ASSIGNMENT_ROOT" (str base "/assign")
                     "BENCH_BASE" base "BENCH_REPO" repo "BENCH_CHILDREN" (str children)
                     "BENCH_HERDR_LOG" (str base "/herdr-calls.log")
                     "BENCH_EVENTS" (str base "/events.jsonl") "BENCH_WATCH_DIR" (str base "/assign/.tmp/herdr-orch")
                     "BENCH_WATCH_ENABLE" (str base "/watch-on")}
                    extra-env
                    ;; Prototype mode: the proto extension watches the result dir; the bench watcher is off.
                    (when (= "1" (get extra-env "BENCH_PROTO"))
                      {"BENCH_WATCH_DIR" "" "HERDR_ORCH_PROTO_DIR" (str base "/assign/.tmp/herdr-orch")}))))

(defn run! [& args]
  (let [proc (p/process (into [binary "--session" session] args) {:env env :out :string :err :string})
        result (deref proc 15000 nil)]
    (when-not result (p/destroy-tree proc) (throw (ex-info "timeout" {:args args})))
    (spit (str base "/commands.edn") (str (pr-str {:args args :result (select-keys result [:exit :out :err])}) "\n") :append true)
    (when-not (= ["api" "snapshot"] (vec (take 2 args))) (println "herdr" (str/join " " args) "->" (:exit result)) (flush))
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
(println "BASE" base "SESSION" session "KEEP" keep) (flush)
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
    (if (= "1" (get extra-env "BENCH_REAL_PI"))
      ;; Real Pi parent: the helper runs in the background of the pane shell; Pi is the foreground agent.
      (run! "pane" "run" parent (str "bb " parent-script " > " base "/parent.log 2>&1 & exec '" real-pi "' -e " repo "/.tmp/comms-bench/present-ext.ts --model anthropic-1/claude-haiku-4-5 -ns -np -nc --no-mcp" (when (= "1" (get extra-env "BENCH_PROTO")) (str " -e " repo "/.tmp/comms-bench/proto-ext.ts"))))
      (run! "pane" "run" parent (str "BENCH_PARENT_CMD='bb " parent-script " > " base "/parent.log 2>&1' exec pi")))
    (wait! "parent recognised as pi" #(some (fn [a] (and (= parent (:pane_id a)) (= "pi" (:agent a)))) (:agents (snap))))
    (let [done (wait! "parent script finished" #(when (fs/exists? (str base "/parent.done")) (slurp (str base "/parent.done")))
                      :tries 1800 :sleep 1000)]
      (println "PARENT DONE" done)
      (assert (= "ok" (str/trim done)) "parent script failed")
      (println "BENCH PASS")))
  (finally
    (doseq [f ["parent.log" "results.json" "server.log" "commands.edn" "herdr-calls.log" "events.jsonl"]]
      (when (fs/exists? (str base "/" f)) (fs/copy (str base "/" f) (str keep "/" f) {:replace-existing true})))
    (println "KEPT" keep)
    (try (run! "server" "stop") (catch Exception e (println "stop failed" (ex-message e))))
    (when (nil? (deref @server 5000 nil)) (p/destroy-tree @server))
    (let [r (deref (p/process [binary "--session" session "session" "delete" session "--json"] {:env env :out :string :err :string}) 8000 nil)]
      (println "CLEANUP session delete" (select-keys r [:exit])))
    (fs/delete-tree base)))

#!/usr/bin/env bb
;; Native validation for plan task 50bb9678: the follow-focus org-tasks pane in
;; disposable Herdr servers with isolated XDG directories. Run from the
;; repository root:
;;   bb design/log/2026-11-10-org-tasks-follow-focus-pane/validation/validate.bb functional
;;   bb design/log/2026-11-10-org-tasks-follow-focus-pane/validation/validate.bb requests
;;   bb design/log/2026-11-10-org-tasks-follow-focus-pane/validation/validate.bb cpu <baseline-plugin-dir>
;; `functional` asserts the move and project timings, the silent-cd timing and
;; the compact height. `requests` counts Herdr CLI starts while idle, under
;; `strace -f`. `cpu` compares the process-tree CPU of 20 idle tabs for this
;; plugin and a baseline checkout of the per-tab plugin, in ABAB rounds.
;; Each mode prints EDN results on its last line and exits 1 if an assertion fails.
(require '[babashka.fs :as fs] '[babashka.process :as p] '[cheshire.core :as j] '[clojure.string :as str])

(def repo (str (fs/cwd)))
;; VALIDATE_PLUGIN runs functional or requests against another plugin checkout,
;; such as the per-tab baseline, to show that the checks fail there.
(def plugin (str (fs/absolutize (or (System/getenv "VALIDATE_PLUGIN") (str repo "/herdr/plugins/org-tasks")))))
(def binary (str (fs/real-path (fs/which "herdr"))))
(def real-registry (str (fs/home) "/.config/herdr/plugins.json"))
(defn digest [f] (when (fs/exists? f) (str/trim (:out (p/shell {:out :string} "sha256sum" f)))))
(def failures (atom []))
(defn check! [label ok detail]
  (println (if ok "PASS" "FAIL") label (pr-str detail)) (flush)
  (when-not ok (swap! failures conj label)))

(defn server-ctx []
  (let [token (subs (str (random-uuid)) 0 8) base (str "/tmp/vv-" token)]
    (doseq [d ["/config/herdr" "/state" "/runtime" "/work"]] (fs/create-dirs (str base d)))
    {:base base :session (str "vv" token)
     :env (merge (into {} (remove (fn [[k _]] (re-find #"^(HERDR_|HC_|ORCH_|OT_)" k)) (System/getenv)))
                 {"XDG_CONFIG_HOME" (str base "/config") "XDG_STATE_HOME" (str base "/state")
                  "XDG_RUNTIME_DIR" (str base "/runtime")})}))

(defn run! [{:keys [env session]} & args]
  (let [proc (p/process (into [binary "--session" session] args) {:env env :out :string :err :string})
        result (deref proc 15000 nil)]
    (when-not result (p/destroy-tree proc) (throw (ex-info "timeout" {:args args})))
    (when-not (zero? (:exit result)) (throw (ex-info (str "CLI failed: " (:err result)) {:args args})))
    (:result (j/parse-string (:out result) true))))
(defn snap [ctx] (:snapshot (run! ctx "api" "snapshot")))
(defn wait-for [ms f]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop [] (or (try (f) (catch Exception _ nil))
                 (when (< (System/currentTimeMillis) deadline) (Thread/sleep 50) (recur))))))

(defn start! [ctx plugin-dir & {:keys [strace]}]
  (let [{:keys [base session env]} ctx]
    (spit (str base "/shell") (str "#!/bin/sh\nexec " (fs/which "bash") " --noprofile --norc\n"))
    (fs/set-posix-file-permissions (str base "/shell") "rwx------")
    (spit (str base "/config/herdr/config.toml")
          (str "onboarding = false\n[terminal]\ndefault_shell = " (pr-str (str base "/shell")) "\n"))
    (let [argv (cond->> [binary "--session" session "server"]
                 strace (into ["strace" "-f" "-qq" "-e" "trace=execve" "-s" "200" "-o" strace]))
          server (p/process argv {:env env :out :write :out-file (str base "/server.log") :err :out})]
      (wait-for 15000 #(snap ctx))
      ;; Link before the first workspace, so the startup hook has nothing to do.
      (run! ctx "plugin" "link" plugin-dir)
      (assoc ctx :server server))))

(defn stop! [{:keys [server base session env] :as ctx}]
  (try (run! ctx "server" "stop") (catch Exception e (println "stop failed" (ex-message e))))
  (when (and server (nil? (deref server 8000 nil))) (p/destroy-tree server))
  (deref (p/process [binary "--session" session "session" "delete" session "--json"]
                    {:env env :out :string :err :string}) 8000 nil)
  (fs/delete-tree base))

(defn state-file [{:keys [base]} & names]
  (first (fs/glob base (str "state/herdr/plugins/cormacc.org-tasks/session-*/" (str/join "/" names)))))
(defn instance [ctx] (some-> (state-file ctx "instance.json") str slurp (j/parse-string true)))
(defn published-dir [ctx]
  (when-let [i (:instance (instance ctx))]
    (some-> (state-file ctx i "context.json") str slurp (j/parse-string true) :directory)))
;; A plugin pane starts in its plugin root; the supervisor renames its label.
(defn compact-panes [s plugin-dir] (filter #(= plugin-dir (:cwd %)) (:panes s)))
(defn compact-pane [ctx s]
  (let [id (:pane (instance ctx))] (some #(when (= id (:pane_id %)) %) (:panes s))))

(defn project! [{:keys [base]} name]
  (let [dir (str base "/work/" name)] (fs/create-dirs dir) (spit (str dir "/TASKS.org") "* Tasks\n") dir))

(defn timed [ms f] (let [t0 (System/currentTimeMillis) v (wait-for ms f)] [v (- (System/currentTimeMillis) t0)]))

;; ── functional ──

(defn functional []
  (let [ctx (start! (server-ctx) plugin)]
    (try
      (let [p1 (project! ctx "p1") p2 (project! ctx "p2") p3 (project! ctx "p3") p4 (project! ctx "p4")
            w1 (run! ctx "workspace" "create" "--cwd" p1 "--label" "one" "--focus")
            t1 (get-in w1 [:tab :tab_id])
            t2 (get-in (run! ctx "tab" "create" "--cwd" p2 "--label" "two" "--no-focus") [:tab :tab_id])
            w2 (run! ctx "workspace" "create" "--cwd" p3 "--label" "other" "--no-focus")
            t3 (get-in w2 [:tab :tab_id])
            _ (run! ctx "tab" "focus" t1)
            [opened ms] (timed 20000 #(when (= p1 (published-dir ctx)) (compact-pane ctx (snap ctx))))]
        (check! "pane opens in the focused tab and publishes its project" (= t1 (:tab_id opened)) {:ms ms})
        (check! "one compact pane" (= 1 (count (compact-panes (snap ctx) plugin)))
                (mapv (juxt :pane_id :cwd :label) (:panes (snap ctx))))
        (doseq [[label tab dir] [["tab in the same workspace" t2 p2] ["tab in another workspace" t3 p3]
                                 ["back to the first tab" t1 p1]]]
          (let [before (:terminal_id (compact-pane ctx (snap ctx)))
                _ (run! ctx "tab" "focus" tab)
                [moved move-ms] (timed 5000 #(let [c (compact-pane ctx (snap ctx))] (when (= tab (:tab_id c)) c)))
                [_ dir-ms] (timed 5000 #(= dir (published-dir ctx)))
                rows (some #(when (= (:pane_id moved) (:pane_id %)) (get-in % [:rect :height]))
                           (:panes (:layout (run! ctx "pane" "layout" "--pane" (:pane_id moved)))))]
            (check! (str "move to " label " within 1 s") (and moved (<= move-ms 1000)) {:ms move-ms})
            (check! (str "same terminal after the move to " label) (= before (:terminal_id moved)) {})
            (check! (str "project of " label " within 1 s of focus") (<= dir-ms 1000) {:ms (+ move-ms dir-ms)})
            (check! (str "compact height after the move to " label) (and rows (<= 5 rows 7)) {:rows rows})))
        (let [focused (some #(when (and (= t1 (:tab_id %)) (not= plugin (:cwd %))) (:pane_id %)) (:panes (snap ctx)))
              _ (Thread/sleep 1500)
              _ (run! ctx "pane" "run" focused (str "cd " p4))
              [published cd-ms] (timed 10000 #(= p4 (published-dir ctx)))]
          (check! "silent cd published within 7 s" (and published (<= cd-ms 7000)) {:ms cd-ms :pane focused}))
        (check! "still one compact pane, after visiting three tabs" (= 1 (count (compact-panes (snap ctx) plugin))) {}))
      (finally (stop! ctx)))))

;; ── requests ──

(defn requests []
  (let [trace (str "/tmp/vv-trace-" (subs (str (random-uuid)) 0 8))
        ctx (start! (server-ctx) plugin :strace trace)]
    (try
      (let [p1 (project! ctx "p1")
            _ (run! ctx "workspace" "create" "--cwd" p1 "--focus")
            other-tab (get-in (run! ctx "tab" "create" "--cwd" p1 "--no-focus") [:tab :tab_id])]
        (wait-for 20000 #(= p1 (published-dir ctx)))
        (Thread/sleep 3000)
        (let [count-herdr (fn [] (count (filter #(and (str/includes? % "execve(") (str/includes? % (str "\"" binary "\""))
                                                      (not (str/includes? % "\"server\"")))
                                                (str/split-lines (slurp trace)))))
              before (count-herdr)
              window 20000]
          (Thread/sleep window)
          (let [n (- (count-herdr) before) bound (* 2 (inc (quot window 5000)))]
            (check! "idle Herdr requests at most two per 5 s" (<= n bound) {:requests n :window-ms window :bound bound})
            ;; Positive control: a tab switch makes the supervisor and the
            ;; tab.focused hook start Herdr CLI processes under the traced server.
            (let [c0 (count-herdr)] (run! ctx "tab" "focus" other-tab) (Thread/sleep 2000)
              (check! "control: the trace counts the CLI starts of a tab switch" (pos? (- (count-herdr) c0))
                      {:requests (- (count-herdr) c0)})))))
      (finally (stop! ctx) (fs/delete-if-exists trace)))))

;; ── cpu ──

(defn tree-cpu-ticks
  "utime + stime + cutime + cstime over `root-pid` and its live descendants."
  [root-pid]
  ;; `slurp` and readers fail on /proc/<pid>/stat with "Invalid argument";
  ;; Files/readAllBytes reads it.
  (let [stats (keep (fn [d] (let [f (str d "/stat")]
                              (try (let [s (String. (java.nio.file.Files/readAllBytes (fs/path f))) fields (str/split (subs s (+ 2 (str/last-index-of s ")"))) #" ")]
                                     {:pid (parse-long (fs/file-name d)) :ppid (parse-long (nth fields 1))
                                      :ticks (reduce + (map #(parse-long (nth fields %)) [11 12 13 14]))})
                                   (catch Exception _ nil))))
                    (fs/glob "/proc" "[0-9]*"))
        children (group-by :ppid stats)
        walk (fn walk [pid] (cons pid (mapcat #(walk (:pid %)) (children pid))))
        pids (set (walk root-pid))]
    (reduce + (map :ticks (filter #(pids (:pid %)) stats)))))

(defn cpu-round [plugin-dir label]
  (let [ctx (start! (server-ctx) plugin-dir)]
    (try
      (let [p1 (project! ctx "p1")]
        (run! ctx "workspace" "create" "--cwd" p1 "--focus")
        (dotimes [_ 19] (run! ctx "tab" "create" "--cwd" p1 "--no-focus"))
        (wait-for 60000 #(let [n (count (compact-panes (snap ctx) plugin-dir))]
                           (when (>= n (if (= label :baseline) 20 1)) n)))
        (Thread/sleep 5000)
        (let [pid (.pid ^Process (:proc (:server ctx)))
              panes (count (compact-panes (snap ctx) plugin-dir))
              samples (loop [i 0 prev (tree-cpu-ticks pid) acc []]
                        (if (= i 12) acc
                          (do (Thread/sleep 5000)
                              (let [now (tree-cpu-ticks pid)]
                                ;; Ticks are 1/100 s; percent of one core over 5 s.
                                (recur (inc i) now (conj acc (/ (- now prev) 5.0)))))))
              median (nth (sort samples) (quot (count samples) 2))]
          (println "ROUND" label "compact panes" panes "median %CPU" median "samples" (pr-str samples)) (flush)
          {:arm label :panes panes :median median :samples samples}))
      (finally (stop! ctx)))))

(defn cpu [baseline-dir]
  ;; Positive control: this process has used CPU, so its ticks are above zero.
  (let [own (tree-cpu-ticks (.pid (java.lang.ProcessHandle/current)))]
    (check! "control: the CPU probe reads this process" (pos? own) {:ticks own})
    (when-not (pos? own) (throw (ex-info "CPU probe reads nothing" {}))))
  (let [rounds (vec (for [arm [:baseline :follow-focus :baseline :follow-focus]]
                      (cpu-round (if (= arm :baseline) baseline-dir plugin) arm)))]
    (println "RESULTS" (pr-str (mapv #(select-keys % [:arm :panes :median]) rounds)))
    rounds))

(let [[mode arg] *command-line-args*
      registry (digest real-registry)]
  (try
    (case mode
      "functional" (functional)
      "requests" (requests)
      "cpu" (cpu (str (fs/absolutize arg)))
      (do (println "Usage: validate.bb functional | requests | cpu <baseline-plugin-dir>") (System/exit 2)))
    (finally
      (check! "real plugins.json unchanged" (= registry (digest real-registry)) {})))
  (println (pr-str {:mode mode :failures @failures}))
  (System/exit (if (empty? @failures) 0 1)))

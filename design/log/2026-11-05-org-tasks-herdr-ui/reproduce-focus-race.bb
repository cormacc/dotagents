(require '[babashka.classpath :as cp])
(cp/add-classpath "herdr-plugin/org-tasks/src")
(require '[org-tasks-host :as h] '[org-tasks.tui.embedding :as embed]
         '[babashka.fs :as fs] '[babashka.process :as p] '[clojure.string :as str])
(def script (str (fs/absolutize *file*)))
(if-let [root (System/getenv "REREVIEW_PROBE_ROOT")]
  (let [instance (System/getenv "OT_HOST_INSTANCE")
        dir (str (fs/path root instance)) tab (System/getenv "OT_HOST_TAB")
        a {:pane_id "agent-a" :terminal_id "term-a" :tab_id tab :agent "pi" :foreground_cwd (str root "/a")}
        b (assoc a :pane_id "agent-b" :terminal_id "term-b" :foreground_cwd (str root "/b"))
        tasks {:pane_id "tasks" :terminal_id "term-tasks" :tab_id tab}
        state (atom {:panes [a b tasks] :agents [a b] :tabs [{:tab_id tab}]
                     :layouts [{:tab_id tab :focused_pane_id "agent-a"}]})
        race? (= "race" (System/getenv "REREVIEW_CASE"))
        snapshots (atom 0) contexts (atom []) real-atomic h/atomic!
        info {:foreground_processes [{:name "pi" :pid 42}]}
        call (fn [& args]
               (case (vec (take 2 args))
                 ["api" "snapshot"]
                 (let [old @state n (swap! snapshots inc)]
                   ;; The supervisor's poll has obtained A, then yields before reading request.json.
                   (when (= n 2)
                     (swap! state assoc :layouts [{:tab_id tab :focused_pane_id "agent-b"}])
                     (h/action! "expand" root)
                     (println "ACTION captured" (:sourcePane (h/read-json (fs/path dir "request.json"))))
                     (flush))
                   {:snapshot (if (and (= n 2) (not race?)) @state old)})
                 ["pane" "process-info"] {:process_info (if (= "tasks" (last args))
                                                           {:shell_pid (.pid (java.lang.ProcessHandle/current))} info)}
                 ["pane" "layout"] {:layout {:panes [] :splits []}}
                 ["plugin" "pane"] (do (swap! state assoc :layouts [{:tab_id tab :focused_pane_id "tasks"}]) {})
                 ["agent" "focus"] {}))]
    (with-redefs [h/call! call h/state-root! (constantly root) h/identity! (constantly "fixture")
                  h/atomic! (fn [file value]
                              (when (and (= "context.json" (str (fs/file-name file))) (:requestId value))
                                (swap! contexts conj value))
                              (real-atomic file value))]
      (h/supervise! (str root "/sleeper")))
    (let [c (last @contexts) file (str root "/observed.json")]
      (assert (>= (count @contexts) 2))
      (h/atomic! file c)
      (let [result (embed/read-context {:context-file file :instance instance} (h/now))]
        (println "CASE" (System/getenv "REREVIEW_CASE") "polls" (count @contexts)
                 "SOURCES" (mapv :sourcePane @contexts) "CONSUMER" (:status result) "DIR" (get-in result [:context :directory]))
        (assert (= :ready (:status result)))
        (assert (= (if race? "agent-a" "agent-b") (:sourcePane c)))
        (assert (= (str root (if race? "/a" "/b")) (:directory c))))))
  (doseq [mode ["control" "race"]]
    (let [root (str (fs/canonicalize (fs/create-temp-dir {:dir ".tmp" :prefix "focus-rereview-race-"})))
          instance (h/uuid) dir (h/private-dir! (fs/path root instance)) tab (or (System/getenv "HERDR_TAB_ID") "w1:t1")]
      (try
        (doseq [name ["a" "b"]] (fs/create-dir (str root "/" name)))
        (spit (str root "/sleeper") "#!/bin/sh\nexec /bin/sleep 2\n")
        (fs/set-posix-file-permissions (str root "/sleeper") "rwx------")
        (h/atomic! (fs/path root (str tab ".json")) {:instance instance :tab tab :pending true :updatedAt (h/now)})
        (let [proc (p/process ["bb" script] {:extra-env {"REREVIEW_PROBE_ROOT" root "REREVIEW_CASE" mode
                                                       "OT_HOST_DIR" dir "OT_HOST_INSTANCE" instance
                                                       "OT_HOST_TAB" tab "HERDR_PANE_ID" "tasks"}
                                            :out :string :err :string})
              result (deref proc 12000 nil)]
          (when-not result (p/destroy-tree proc))
          (println (:out result)) (print (:err result))
          (assert (= 0 (:exit result)) (str "probe failed: " mode)))
        (finally (fs/delete-tree root))))))

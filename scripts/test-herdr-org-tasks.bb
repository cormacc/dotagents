#!/usr/bin/env bb
(require '[babashka.classpath :as cp])
(cp/add-classpath "herdr-plugin/org-tasks/src")
(require '[org-tasks-host :as host]
         '[org-tasks.tui.embedding :as embed]
         '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.string :as str]
         '[clojure.test :refer [deftest is run-tests]])

(defn temporary [f]
  (fs/create-dirs ".tmp")
  (let [dir (str (fs/canonicalize (fs/create-temp-dir {:dir ".tmp" :prefix "terminal-host-test-"})))]
    (try (f dir) (finally (fs/delete-tree dir)))))

(def host-source-root (str (fs/parent (:file (meta #'host/with-lock)))))
(def tab (or (System/getenv "HERDR_TAB_ID") "w1:t1"))
(def a {:pane_id "agent-a" :terminal_id "term-a" :tab_id tab :agent "pi"
        :interactive_ready true :focused true :foreground_cwd (str (fs/cwd))})
(def b (assoc a :pane_id "agent-b" :terminal_id "term-b"))
(def task-pane {:pane_id "tasks" :terminal_id "term-tasks" :tab_id tab :focused false})
(def local-info {:foreground_processes [{:pid 42 :name "pi"}]})
(defn snapshot [panes] {:panes panes :agents (filter :agent panes) :tabs [{:tab_id tab}]})

(deftest source-is-tab-local-and-preserved-only-on-task-focus
  (is (= "agent-a" (:pane_id (host/source (snapshot [a task-pane]) tab "tasks" nil))))
  (is (= "agent-a" (:pane_id (host/source (snapshot [(assoc a :focused false) (assoc task-pane :focused true)]) tab "tasks" a))))
  (is (nil? (host/source (snapshot [(assoc a :tab_id "another-tab") task-pane]) tab "tasks" nil)))
  (is (= "unavailable" (:availability (host/source-context
                                     (host/source (snapshot [(assoc a :focused false) (assoc task-pane :pane_id "shell" :focused true)]) tab "tasks" a)
                                     {:foreground_processes [{:name "bash"}]}))))
  (is (= "ssh-pane" (:pane_id (host/source (snapshot [(assoc task-pane :pane_id "ssh-pane" :focused true)]) tab "tasks" nil))))
  (is (= "remote" (:availability (host/source-context
                                (host/source (snapshot [(assoc task-pane :pane_id "ssh-pane" :focused true)]) tab "tasks" nil)
                                {:foreground_processes [{:name "ssh"}]}))))
  (is (nil? (host/source (snapshot [(assoc task-pane :focused true)]) tab "tasks" a)))
  (is (= "agent-a" (:pane_id (host/source
                             (assoc (snapshot [(assoc a :focused false) task-pane])
                                    :focused_tab_id "other-tab"
                                    :layouts [{:tab_id tab :focused_pane_id "tasks"}])
                             tab "tasks" a)))))

(deftest snapshots-pin-transition-identity-not-heartbeats
  (temporary
   (fn [dir]
    (let [a (assoc a :foreground_cwd (str (fs/create-dir (fs/path dir "a"))))
          b (assoc b :foreground_cwd (str (fs/create-dir (fs/path dir "b"))))
          first (host/context nil "instance" a local-info "compact" 100)
        beat (host/context first "instance" a local-info "compact" 200)
        switched (host/context beat "instance" b local-info "expanded" 300)
        back (host/context switched "instance" a local-info "compact" 400)]
    (is (= "org-tasks-embed/v1" (:schema first)))
    (is (= "local" (:availability first)))
    (is (= "local" (:availability (host/context nil "unmanaged" (dissoc a :interactive_ready) local-info "compact" 100))))
    (is (= 200 (:updatedAt beat)))
    (is (= (:contextId first) (:contextId beat)))
    (is (not= (:contextId first) (:contextId switched)))
    (is (not= (:contextId first) (:contextId back)))
    (is (= "expanded" (:mode switched)))
    (is (= (:foreground_cwd b) (:directory switched)))
    (is (= (:foreground_cwd a) (:directory back)))
    (is (= "remote" (:availability (host/context first "instance" a {:foreground_processes [{:name "ssh"}]} "compact" 500))))
    (doseq [[candidate info] [[nil nil] [a {:foreground_processes [{:name "bash"}]}] [a {}] [(assoc a :foreground_cwd "relative") local-info]]]
      (is (= "unavailable" (:availability (host/context first "instance" candidate info "compact" 500)))))))))

(deftest real-producer-is-accepted-by-consumer-and-expires
  (temporary
   (fn [dir]
     (let [private (host/private-dir! (fs/path dir "instance"))
           file (str (fs/path private "context.json"))
           c (host/context nil "instance" a local-info "expanded" 10000)
           opts {:context-file file :instance "instance"}]
       (host/atomic! file c)
       (is (= "rwx------" (fs/posix->str (fs/posix-file-permissions private))))
       (is (= "rw-------" (fs/posix->str (fs/posix-file-permissions file))))
       (is (= :ready (:status (embed/read-context opts 10000))))
       (is (= :ready (:status (embed/read-context opts 15000))))
       (is (= :stale (:status (embed/read-context opts 15001))))
       (is (= :invalid (:status (embed/read-context (assoc opts :instance "foreign") 10000))))
       (host/atomic! file (host/context c "instance" nil nil "compact" 10001))
       (is (= :unavailable (:status (embed/read-context opts 10001))))
       (host/atomic! file (host/context c "instance" a {:foreground_processes [{:name "ssh"}]} "compact" 10002))
       (is (= :remote (:status (embed/read-context opts 10002))))
       (is (= ["context.json"] (mapv (comp str fs/file-name) (fs/list-dir private))))))))

(deftest atomic-publish-commits-a-complete-private-sibling
  (temporary
   (fn [dir]
     (let [file (str (fs/path dir "context.json"))
           real-move fs/move commits (atom 0)]
       (host/atomic! file {:value "old"})
       (with-redefs [fs/move (fn [from to options]
                              (swap! commits inc)
                              (is (= file to))
                              (is (= (fs/parent from) (fs/parent file)))
                              (is (= {:value "old"} (host/read-json file)))
                              (is (= {:value "new"} (host/read-json from)))
                              (is (= "rw-------" (fs/posix->str (fs/posix-file-permissions from))))
                              (is (true? (:atomic-move options)))
                              (real-move from to options))]
         (host/atomic! file {:value "new"}))
       (is (= 1 @commits))
       (is (= {:value "new"} (host/read-json file)))))))

(deftest ownership-needs-terminal-process-identity-and-live-lease
  (let [record {:pane "tasks" :terminal "term-tasks" :tab tab :pid 22 :identity "start command" :updatedAt 10000}
        s (snapshot [task-pane]) info {:shell_pid 22}]
    (is (host/owned? record s info "start command" 11000))
    (doseq [[r snap process identity time]
            [[(assoc record :terminal "foreign") s info "start command" 11000]
             [record (snapshot []) info "start command" 11000]
             [record s {:shell_pid 23} "start command" 11000]
             [record s info "another start" 11000]
             [record s info "start command" 15001]
             [record s info "start command" 9999]
             [(dissoc record :identity) s info nil 11000]]]
      (is (not (host/owned? r snap process identity time))))))

(deftest config-refuses-unsupported-root-modes-and-invalid-sizing
  (temporary
   (fn [dir]
     (is (= host/defaults (host/config! dir)))
     (doseq [bad [{:mode :workspace} {:compact-rows 0} {:expanded-fraction 1} {:compact-rows "six"}]]
       (spit (str (fs/path dir "config.edn")) (pr-str bad))
       (is (thrown? Exception (host/config! dir)))))))

(defn fake-host [dir f]
  (let [s (atom (snapshot [a])) calls (atom [])
        live (atom true) timestamp (atom 10000)
        dispatch
        (fn [& args]
          (swap! calls conj (vec args))
          (case (vec (take 2 args))
            ["api" "snapshot"] {:snapshot @s}
            ["pane" "process-info"] {:process_info (if (= "tasks" (last args))
                                                    (if @live {:shell_pid 22} {:shell_pid 23})
                                                    local-info)}
            ["pane" "resize"] (do (Double/parseDouble (last args)) {})
            ["pane" "layout"] {:layout {:panes [{:pane_id "tasks" :rect {:x 0 :y 21 :width 100 :height 19}}]
                                         :splits [{:direction "down" :rect {:x 0 :y 0 :width 100 :height 40}}]}}
            ["plugin" "pane"]
            (case (nth args 2)
              "open" (let [env (filter #(and (string? %) (.startsWith % "OT_HOST_")) args)
                           values (into {} (map #(str/split % #"=" 2) env))
                           instance (get values "OT_HOST_INSTANCE")
                           owner {:pane "tasks" :terminal "term-tasks" :tab tab :pid 22 :identity "start command"
                                  :instance instance :updatedAt @timestamp}]
                       (swap! s update :panes conj task-pane)
                       (host/atomic! (fs/path dir instance "owner.json") owner)
                       (host/atomic! (fs/path dir instance "context.json")
                                     {:sourcePane "agent-a" :sourceTerminal "term-a"})
                       {:plugin_pane {:pane task-pane}})
              "close" (do (swap! s update :panes #(remove (fn [x] (= "tasks" (:pane_id x))) %)) {})
              "focus" {})
            {}))]
    (with-redefs [host/call! dispatch host/state-root! (constantly dir)
                  host/identity! (constantly "start command") host/now #(deref timestamp)]
      (f {:state s :calls calls :live live :time timestamp}))))

(deftest explicit-open-reuses-and-focus-controls-never-type-input
  (temporary
   (fn [dir]
     (fake-host dir
      (fn [{:keys [calls]}]
        (host/action! "open" dir)
        (host/action! "open" dir)
        (is (= 1 (count (filter #(= ["plugin" "pane" "open"] (take 3 %)) @calls))))
        (is (some #(and (= ["plugin" "pane" "open"] (take 3 %))
                        (some #{"down"} %) (some #{"--no-focus"} %)) @calls))
        (host/action! "expand" dir)
        (is (some #{["plugin" "pane" "focus" "tasks"]} @calls))
        (is (some #(and (= ["pane" "resize"] (take 2 %)) (some #{"up"} %)) @calls))
        (host/action! "collapse" dir)
        (is (some #{["agent" "focus" "agent-a"]} @calls))
        (is (some #(and (= ["pane" "resize"] (take 2 %)) (some #{"down"} %)) @calls))
        (is (not-any? #(some #{"send-text" "send-keys" "run"} %) @calls))
        (host/action! "close" dir)
        (is (some #{["plugin" "pane" "close" "tasks"]} @calls)))))))

(deftest stale-process-and-foreign-pane-are-not-controlled
  (temporary
   (fn [dir]
     (fake-host dir
      (fn [{:keys [calls live state]}]
        (host/action! "open" dir)
        (reset! calls [])
        (reset! live false)
        (host/action! "close" dir)
        (is (not-any? #(= ["plugin" "pane" "close"] (take 3 %)) @calls))
        (is (thrown? Exception (host/action! "expand" dir)))
        (reset! live true)
        (swap! state update :panes #(mapv (fn [pane] (if (= "tasks" (:pane_id pane)) (assoc pane :terminal_id "foreign") pane)) %))
        (reset! calls [])
        (host/action! "close" dir)
        (is (not-any? #(= ["plugin" "pane" "close"] (take 3 %)) @calls)))))))

(deftest process-exit-reopen-uses-new-instance
  (temporary
   (fn [dir]
     (fake-host dir
      (fn [{:keys [state]}]
        (host/action! "open" dir)
        (let [old (:instance (host/read-json (fs/path dir (str tab ".json"))))]
          (reset! state (snapshot [a]))
          (host/action! "open" dir)
          (is (not= old (:instance (host/read-json (fs/path dir (str tab ".json"))))))))))))

(deftest pending-open-reservation-is-not-replaced
  (temporary
   (fn [dir]
     (fake-host dir
      (fn [{:keys [calls]}]
        (host/atomic! (fs/path dir (str tab ".json"))
                      {:instance (host/uuid) :tab tab :pending true :updatedAt 10000})
        (is (thrown? Exception (host/action! "open" dir)))
        (is (not-any? #(= ["plugin" "pane" "open"] (take 3 %)) @calls)))))))

(deftest concurrent-open-actions-reuse-one-host-pane
  (temporary
   (fn [dir]
     (host/atomic! (fs/path dir "host.json") (assoc (snapshot [a]) :focused_tab_id tab))
     (let [argv ["bb" "-cp" (str host-source-root ":herdr-plugin/org-tasks/test")
                 "-m" "org-tasks-host-fixture" dir]
           processes [(p/process argv {:out :string :err :string})
                      (p/process argv {:out :string :err :string})]]
       (try
         (doseq [proc processes]
           (let [r (deref proc 10000 nil)]
             (is (= 0 (:exit r)) (:err r))))
         (is (= "open\n" (slurp (str (fs/path dir "opens")))))
         (is (= 2 (count (:panes (host/read-json (fs/path dir "host.json"))))))
         (finally (doseq [proc processes] (p/destroy-tree proc))))))))

(deftest concurrent-processes-serialize
  (temporary
   (fn [dir]
     (let [lock (str (fs/path dir "lock"))
           log (str (fs/path dir "log"))
           code (str "(require '[org-tasks-host :as h]) (h/with-lock " (pr-str lock)
                     " #(do (spit " (pr-str log) " \"start\\n\" :append true) (Thread/sleep 150) (spit "
                     (pr-str log) " \"end\\n\" :append true)))")
           argv ["bb" "-cp" host-source-root "-e" code]
           one (p/process argv {:out :string :err :string})
           two (p/process argv {:out :string :err :string})]
       (doseq [proc [one two]]
         (let [result (deref proc 10000 nil)]
           (when-not result (p/destroy-tree proc))
           (is (= 0 (:exit result)) (:err result))))
       (is (= "start\nend\nstart\nend\n" (slurp log)))))))

(deftest host-errors-and-timeouts-never-become-context
  (doseq [response [{:exit 1 :out "" :err "offline"}
                   {:exit 0 :out "{\"error\":{\"code\":\"unavailable\"}}" :err ""}
                   {:exit 0 :out "not-json" :err ""}]]
    (with-redefs [p/process (fn [& _] (delay response))]
      (is (thrown? Exception (host/call! "api" "snapshot")))))
  (let [killed (atom false)]
    (binding [host/*request-timeout-ms* 1]
      (with-redefs [p/process (fn [& _] (promise)) p/destroy-tree (fn [_] (reset! killed true))]
        (is (thrown? Exception (host/call! "api" "snapshot")))
        (is @killed)))))

(deftest process-death-releases-open-lock
  (temporary
   (fn [dir]
     (let [lock (str (fs/path dir "lock")) ready (str (fs/path dir "ready"))
           code (str "(require '[org-tasks-host :as h]) (h/with-lock " (pr-str lock)
                     " #(do (spit " (pr-str ready) " \"ready\") (Thread/sleep 10000)))")
           holder (p/process ["bb" "-cp" host-source-root "-e" code] {:out :string :err :string})]
       (try
         (loop [remaining 100]
           (when (and (not (fs/exists? ready)) (pos? remaining))
             (Thread/sleep 20) (recur (dec remaining))))
         (is (fs/exists? ready))
         (p/destroy-tree holder)
         (let [waiter (p/process ["bb" "-cp" host-source-root "-e"
                                  (str "(require '[org-tasks-host :as h]) (h/with-lock " (pr-str lock) " #(println :released))")]
                                 {:out :string :err :string})]
           (try
             (let [r (deref waiter 5000 nil)]
               (is (= 0 (:exit r)) (:err r))
               (is (= ":released\n" (:out r))))
             (finally (p/destroy-tree waiter))))
         (finally (p/destroy-tree holder)))))))

(deftest expansion-hands-off-the-agent-focused-between-polls
  (temporary
   (fn [dir]
     (let [a (assoc a :foreground_cwd (str (fs/create-dir (fs/path dir "a"))))
           b (assoc b :foreground_cwd (str (fs/create-dir (fs/path dir "b"))))
           instance (host/uuid)
           private (host/private-dir! (fs/path dir instance))
           record {:pane "tasks" :terminal "term-tasks" :tab tab :pid 22
                   :identity "fixture" :instance instance :updatedAt 10000}
           state (atom (assoc (snapshot [(assoc a :focused false) b task-pane])
                              :layouts [{:tab_id tab :focused_pane_id "agent-b"}]))
           calls (atom [])
           file (fs/path private "context.json")
           previous (host/context nil instance a local-info "expanded" 10000)]
       (host/atomic! (fs/path dir (str tab ".json")) record)
       (host/atomic! (fs/path private "owner.json") record)
       (host/atomic! file previous)
       (with-redefs [host/state-root! (constantly dir) host/identity! (constantly "fixture")
                     host/now (constantly 10000)
                     host/call! (fn [& args]
                                  (swap! calls conj (vec args))
                                  (case (vec (take 2 args))
                                    ["api" "snapshot"] {:snapshot @state}
                                    ["pane" "process-info"] {:process_info (if (= "tasks" (last args)) {:shell_pid 22} local-info)}
                                    ["pane" "layout"] {:layout {:panes [] :splits []}}
                                    ["plugin" "pane"] (do
                                                        (is (= "agent-b" (:sourcePane (host/read-json (fs/path private "request.json")))) "handoff exists before focus moves")
                                                        (swap! state assoc :layouts [{:tab_id tab :focused_pane_id "tasks"}]) {})
                                    ["agent" "focus"] {}))]
         (is (= "agent-b" (:pane_id (host/source @state tab "tasks" a))) "positive pre-focus control")
         (host/action! "expand" dir)
         (let [request (host/read-json (fs/path private "request.json"))
               sampled (host/sample! @state record a previous request nil)
               c (:context sampled)]
           (is (= "agent-b" (:sourcePane request)))
           (is (= "term-b" (:sourceTerminal request)))
           (is (= (:foreground_cwd b) (:directory c)))
           (is (not= (:contextId previous) (:contextId c)))
           (is (= (:contextId c) (get-in (host/sample! @state record (:candidate sampled) c request request) [:context :contextId])))
           (host/atomic! file c)
           (is (= :ready (:status (embed/read-context {:context-file (str file) :instance instance} 10000))))
           (is (= (:foreground_cwd b) (get-in (embed/read-context {:context-file (str file) :instance instance} 10000) [:context :directory])))
           (host/action! "collapse" dir)
           (is (some #{["agent" "focus" "agent-b"]} @calls))))))))

(deftest handoff-validation-and-live-eligibility-fail-closed
  (temporary
   (fn [dir]
     (let [record {:pane "tasks" :terminal "term-tasks" :tab tab :instance "instance"}
           b (assoc b :foreground_cwd dir)
           s (assoc (snapshot [(assoc a :focused false) b task-pane])
                    :layouts [{:tab_id tab :focused_pane_id "tasks"}])
           request {:requestId "handoff-b" :instanceId "instance" :tabId tab
                    :targetTerminal "term-tasks" :updatedAt 10000 :mode "expanded"
                    :availability "local" :sourcePane "agent-b" :sourceTerminal "term-b"
                    :directory (:foreground_cwd a)}
           previous (host/context nil "instance" a local-info "expanded" 10000)]
       (with-redefs [host/now (constantly 10000) host/process! (constantly local-info)]
         (doseq [bad [(assoc request :instanceId "foreign") (assoc request :tabId "other-tab")
                      (assoc request :targetTerminal "foreign") (assoc request :sourceTerminal "foreign")
                      (assoc request :sourcePane "missing") (assoc request :updatedAt 4999)
                      (assoc request :updatedAt 11001) (dissoc request :requestId)
                      (assoc request :availability "unknown") (assoc request :mode "unknown") nil]]
           (let [one (host/sample! s record a previous bad {:requestId "older"})
                 two (host/sample! s record (:candidate one) (:context one) bad bad)]
             (is (= "unavailable" (get-in one [:context :availability])) (pr-str bad))
             (is (nil? (get-in two [:context :directory])) "invalid handoff must not resurrect A")))
         (doseq [time [5000 11000]]
           (is (= dir (get-in (host/sample! s record a previous (assoc request :updatedAt time) nil) [:context :directory]))))
         (let [one (host/sample! s record a previous request nil)
               two (with-redefs [host/now (constantly 16000)]
                     (host/sample! s record (:candidate one) (:context one) request request))]
           (is (= dir (get-in one [:context :directory])) "handoff directory is ignored in favour of live host cwd")
           (is (= (:contextId (:context one)) (:contextId (:context two)))))
         (doseq [[info expected] [[{:foreground_processes [{:name "ssh"}]} :remote]
                                 [{} :unavailable]
                                 [{:foreground_processes [{:name "bash"}]} :unavailable]]]
           (let [c (with-redefs [host/process! (constantly info)]
                     (:context (host/sample! s record a previous request nil)))
                 file (str (fs/path dir "context.json"))]
             (host/atomic! file c)
             (is (nil? (:directory c)))
             (is (= expected (:status (embed/read-context {:context-file file :instance "instance"} 10000))))))
         (doseq [changed [(assoc b :terminal_id "replacement") (assoc b :tab_id "other-tab") (dissoc b :agent)]]
           (let [changed-s (assoc s :panes [(assoc a :focused false) changed task-pane] :agents [a])]
             (is (= "unavailable" (get-in (host/sample! changed-s record a previous request nil) [:context :availability]))))))))))

(deftest collapse-validates-the-unconsumed-handoff-instead-of-old-context
  (temporary
   (fn [dir]
     (fake-host dir
      (fn [{:keys [calls state]}]
        (host/action! "open" dir)
        (swap! state update :panes conj b)
        (swap! state assoc :layouts [{:tab_id tab :focused_pane_id "agent-b"}])
        (host/action! "expand" dir)
        (swap! state assoc :layouts [{:tab_id tab :focused_pane_id "tasks"}])
        (let [record (host/read-json (fs/path dir (str tab ".json")))
              file (fs/path dir (:instance record) "request.json")
              request (host/read-json file)]
          (doseq [bad [(assoc request :instanceId "foreign") (assoc request :sourceTerminal "replacement")
                       (assoc request :updatedAt 4999) (assoc request :updatedAt 11001)
                       (assoc request :tabId "other-tab") (assoc request :targetTerminal "foreign") nil]]
            (host/atomic! file bad)
            (reset! calls [])
            (host/action! "collapse" dir)
            (is (not-any? #(= ["agent" "focus"] (take 2 %)) @calls) "no old A fallback"))
          (host/atomic! file request)
          (reset! calls [])
          (let [process-info host/process!]
            (with-redefs [host/process! (fn [id] (if (= id "agent-b") {:foreground_processes [{:name "ssh"}]} (process-info id)))]
              (host/action! "collapse" dir)))
          (is (not-any? #(= ["agent" "focus"] (take 2 %)) @calls) "remote handoff cannot restore a local agent")
          (host/atomic! file request)
          (reset! calls [])
          (host/action! "collapse" dir)
          (is (some #{["agent" "focus" "agent-b"]} @calls) "positive control: fresh pending B restores B without a poll")))))))

(def race-script (str (fs/absolutize *file*)))

(defn race-probe
  "Child process: the user focuses B and expands after a supervisor poll has taken its
  snapshot (A focused) but before that poll could be reconciled with request.json."
  [root]
  (let [instance (System/getenv "OT_HOST_INSTANCE")
        a (assoc a :foreground_cwd (str root "/a")) b (assoc b :foreground_cwd (str root "/b"))
        state (atom (assoc (snapshot [a b task-pane])
                           :layouts [{:tab_id tab :focused_pane_id "agent-a"}]))
        action-at (parse-long (or (System/getenv "RACE_ACTION_AT") "2"))
        snapshots (atom 0) acted (atom false) contexts (atom []) post (atom []) real-atomic host/atomic!]
    (with-redefs [host/call! (fn [& args]
                               (case (vec (take 2 args))
                                 ["api" "snapshot"]
                                 (let [old @state n (swap! snapshots inc)]
                                   (when (= n action-at)
                                     (swap! state assoc :layouts [{:tab_id tab :focused_pane_id "agent-b"}])
                                     (host/action! "expand" root)
                                     (reset! acted true))
                                   {:snapshot (if (= n action-at) old @state)})
                                 ["pane" "process-info"] {:process_info (if (= "tasks" (last args))
                                                                           {:shell_pid (.pid (java.lang.ProcessHandle/current))}
                                                                           local-info)}
                                 ["pane" "layout"] {:layout {:panes [] :splits []}}
                                 ["plugin" "pane"] (do (swap! state assoc :layouts [{:tab_id tab :focused_pane_id "tasks"}]) {})
                                 ["agent" "focus"] {}))
                  host/state-root! (constantly root) host/identity! (constantly "fixture")
                  host/atomic! (fn [file value]
                                 (when (= "context.json" (str (fs/file-name file)))
                                   (when (:requestId value) (swap! contexts conj value))
                                   (when @acted (swap! post conj (:sourcePane value))))
                                 (real-atomic file value))]
      (host/supervise! (str root "/sleeper")))
    (let [c (last @contexts) file (str root "/observed.json")
          _ (host/atomic! file c)
          result (embed/read-context {:context-file file :instance instance} (host/now))]
      (println (pr-str {:post @post :polls (count @contexts) :sources (mapv :sourcePane @contexts)
                        :status (:status result) :directory (get-in result [:context :directory])})))))

(when-let [root (System/getenv "RACE_PROBE_ROOT")]
  (race-probe root)
  (System/exit 0))

(deftest supervisor-reconciles-a-request-with-a-snapshot-no-older-than-it
  (temporary
   (fn [dir]
     (let [instance (host/uuid) private (host/private-dir! (fs/path dir instance))]
       (fs/create-dir (str dir "/a")) (fs/create-dir (str dir "/b"))
       (spit (str dir "/sleeper") "#!/bin/sh\nexec /bin/sleep 2\n")
       (fs/set-posix-file-permissions (str dir "/sleeper") "rwx------")
       (host/atomic! (fs/path dir (str tab ".json")) {:instance instance :tab tab :pending true :updatedAt (host/now)})
       (let [proc (p/process ["bb" race-script]
                             {:extra-env {"RACE_PROBE_ROOT" dir "OT_HOST_DIR" private
                                          "OT_HOST_INSTANCE" instance "OT_HOST_TAB" tab
                                          "HERDR_PANE_ID" "tasks"}
                              :out :string :err :string})
             result (deref proc 15000 nil)]
         (when-not result (p/destroy-tree proc))
         (is (and result (zero? (:exit result))) (str "probe exit/err: " (:err result)))
         (let [out (read-string (last (str/split-lines (:out result))))]
           (is (= :ready (:status out)))
           (is (= "agent-b" (last (:sources out))) "the expanded-from agent B is retained, not stale A")
           (is (= (str dir "/b") (:directory out)))))))))

;; The supervisor has already published a poll that sampled A when the user focuses B
;; and expands inside the next poll's snapshot call.
(deftest supervisor-never-publishes-a-sample-incoherent-with-its-request
  (temporary
   (fn [dir]
     (let [instance (host/uuid) private (host/private-dir! (fs/path dir instance))]
       (fs/create-dir (str dir "/a")) (fs/create-dir (str dir "/b"))
       (spit (str dir "/sleeper") "#!/bin/sh\nexec /bin/sleep 3\n")
       (fs/set-posix-file-permissions (str dir "/sleeper") "rwx------")
       (host/atomic! (fs/path dir (str tab ".json")) {:instance instance :tab tab :pending true :updatedAt (host/now)})
       (let [proc (p/process ["bb" race-script]
                             {:extra-env {"RACE_PROBE_ROOT" dir "OT_HOST_DIR" private "RACE_ACTION_AT" "3"
                                          "OT_HOST_INSTANCE" instance "OT_HOST_TAB" tab
                                          "HERDR_PANE_ID" "tasks"}
                              :out :string :err :string})
             result (deref proc 15000 nil)]
         (when-not result (p/destroy-tree proc))
         (is (and result (zero? (:exit result))) (str "probe exit/err: " (:err result)))
         (let [out (read-string (last (str/split-lines (:out result))))]
           (is (seq (:post out)) "positive control: contexts were published after the expansion")
           (is (not-any? #{"agent-a"} (:post out)) (str "no post-expansion context names A: " (:post out)))
           (is (= :ready (:status out)))
           (is (= "agent-b" (last (:sources out))))))))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))

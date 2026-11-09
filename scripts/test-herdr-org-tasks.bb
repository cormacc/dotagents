#!/usr/bin/env bb
(require '[babashka.classpath :as cp])
(cp/add-classpath "herdr/plugins/org-tasks/src")
(require '[org-tasks-host :as host]
         '[org-tasks.tui.embedding :as embed]
         '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[clojure.string :as str]
         '[clojure.test :refer [deftest is run-tests testing]])

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
;; :focused_tab_id lets action! resolve the invocation tab without an ambient
;; HERDR_TAB_ID, so the suite runs outside a Herdr pane.
(defn snapshot [panes] {:panes panes :agents (filter :agent panes) :tabs [{:tab_id tab}]
                        :focused_tab_id tab})
;; NixOS has no /bin/sleep.
(def sleep-bin (str (fs/which "sleep")))

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
          hints ["ctrl+b t: Select a task from the backlog"]
          first (host/context nil "instance" a local-info hints 100)
          beat (host/context first "instance" a local-info hints 200)
          switched (host/context beat "instance" b local-info hints 300)
          back (host/context switched "instance" a local-info nil 400)]
    (is (= "org-tasks-embed/v1" (:schema first)))
    (is (= "local" (:availability first)))
    (is (= "compact" (:mode first)) "the embedded pane is always compact")
    (is (= hints (:hints first)))
    (is (not (contains? back :hints)) "no bindings, no hints field")
    (is (= 200 (:updatedAt beat)))
    (is (= (:contextId first) (:contextId beat)))
    (is (not= (:contextId first) (:contextId switched)))
    (is (not= (:contextId first) (:contextId back)))
    (is (= (:foreground_cwd b) (:directory switched)))
    (is (= (:foreground_cwd a) (:directory back)))
    (is (= "remote" (:availability (host/context first "instance" a {:foreground_processes [{:name "ssh"}]} nil 500))))
    (doseq [[candidate info] [[nil nil] [a {:foreground_processes [{:name "bash"}]}] [a {}] [(assoc a :foreground_cwd "relative") local-info]]]
      (is (= "unavailable" (:availability (host/context first "instance" candidate info nil 500)))))))))

(deftest real-producer-is-accepted-by-consumer-and-expires
  (temporary
   (fn [dir]
     (let [private (host/private-dir! (fs/path dir "instance"))
           file (str (fs/path private "context.json"))
           c (host/context nil "instance" a local-info ["hint"] 10000)
           opts {:context-file file :instance "instance"}]
       (host/atomic! file c)
       (is (= "rwx------" (fs/posix->str (fs/posix-file-permissions private))))
       (is (= "rw-------" (fs/posix->str (fs/posix-file-permissions file))))
       (is (= :ready (:status (embed/read-context opts 10000))))
       (is (= :ready (:status (embed/read-context opts 15000))))
       (is (= :stale (:status (embed/read-context opts 15001))))
       (is (= :invalid (:status (embed/read-context (assoc opts :instance "foreign") 10000))))
       (host/atomic! file (host/context c "instance" nil nil nil 10001))
       (is (= :unavailable (:status (embed/read-context opts 10001))))
       (host/atomic! file (host/context c "instance" a {:foreground_processes [{:name "ssh"}]} nil 10002))
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

(deftest config-refuses-unknown-keys-and-invalid-sizing
  (temporary
   (fn [dir]
     (is (= host/defaults (host/config! dir)))
     (doseq [bad [{:mode :workspace} {:compact-rows 0} {:expanded-fraction 0.5} {:compact-rows "six"}]]
       (spit (str (fs/path dir "config.edn")) (pr-str bad))
       (is (thrown? Exception (host/config! dir)))))))

(deftest hints-render-the-configured-prefix-and-bindings
  (let [config (str "[keys]\nprefix = \"ctrl+a\" # comment\n\n"
                    "[[keys.command]]\nkey = \"prefix+t\"\ntype = \"plugin_action\"\n"
                    "command = \"cormacc.org-tasks.select\"\n\n"
                    "[[keys.command]]\nkey = \"prefix+ctrl+o\"\ntype = \"plugin_action\"\n"
                    "command = \"cormacc.org-tasks.toggle\"\n")]
    (is (= ["ctrl+a t: Select a task from the backlog"
            "ctrl+a ctrl+o: Disable/enable org-tasks overlay"]
           (host/hints config)))
    (is (= ["ctrl+b t: Select a task from the backlog"
            "ctrl+b ctrl+o: Disable/enable org-tasks overlay"]
           (host/hints (str/replace config "[keys]\nprefix = \"ctrl+a\" # comment\n" "")))
        "Herdr's default prefix applies when keys.prefix is absent")
    (is (= ["ctrl+a ctrl+o: Disable/enable org-tasks overlay"]
           (host/hints (str/replace config "cormacc.org-tasks.select" "other.plugin.select")))
        "an unbound action has no hint")
    (is (= ["ctrl+a f12: Select a task from the backlog"]
           (host/hints (str "[keys]\nprefix = [\"ctrl+a\", \"f12\"]\n"
                            "[[keys.command]]\ncommand = \"cormacc.org-tasks.select\"\nkey = \"prefix+f12\"\n")))
        "the first of several prefixes; key and command in either order")
    (is (nil? (host/hints nil)))
    (is (= "ctrl+b ctrl+o" (host/display-key "ctrl+b" "prefix+ctrl+o")))
    (is (= "ctrl+b O" (host/display-key "ctrl+b" "prefix+shift+o")) "shift+letter renders as the capital")
    (is (= "alt+x" (host/display-key "ctrl+b" "alt+x")) "a binding without the prefix is shown as is")))

(def tab2 "w1:t9")
(def c (assoc a :pane_id "agent-c" :terminal_id "term-c" :tab_id tab2))

(defn fake-host
  "Two tabs with one agent each. Plugin pane opens create `tasks-<tab>` panes
  whose supervisor is live while `live` holds."
  [dir f]
  (let [s (atom (assoc (snapshot [a c]) :tabs [{:tab_id tab} {:tab_id tab2}]))
        calls (atom []) live (atom true) timestamp (atom 10000)
        display (str (fs/path dir "plugin-state"))
        dispatch
        (fn [& args]
          (swap! calls conj (vec args))
          (case (vec (take 2 args))
            ["api" "snapshot"] {:snapshot @s}
            ["pane" "process-info"] {:process_info (if (str/starts-with? (last args) "tasks")
                                                    (if @live {:shell_pid 22} {:shell_pid 23})
                                                    local-info)}
            ["pane" "resize"] (do (Double/parseDouble (last args)) {})
            ["pane" "layout"] {:layout {:panes [] :splits []}}
            ["plugin" "pane"]
            (case (nth args 2)
              "open" (let [values (into {} (map #(str/split % #"=" 2))
                                        (filter #(and (string? %) (re-find #"^[A-Z_]+=" %)) args))]
                       (if-let [instance (get values "OT_HOST_INSTANCE")]
                         (let [owner-tab (get values "OT_HOST_TAB")
                               task {:pane_id (str "tasks-" owner-tab) :terminal_id (str "term-tasks-" owner-tab)
                                     :tab_id owner-tab :focused false}
                               owner {:pane (:pane_id task) :terminal (:terminal_id task) :tab owner-tab :pid 22
                                      :identity "start command" :instance instance :updatedAt @timestamp}]
                           (swap! s update :panes conj task)
                           (host/atomic! (fs/path dir instance "owner.json") owner)
                           {:plugin_pane {:pane task}})
                         {:ok true}))
              "close" (do (swap! s update :panes #(remove (fn [x] (= (nth args 3) (:pane_id x))) %)) {}))
            {}))]
    (fs/create-dirs display)
    (with-redefs [host/call! dispatch host/state-root! (constantly dir)
                  host/plugin-state-dir (constantly display)
                  host/identity! (constantly "start command") host/now #(deref timestamp)]
      (f {:state s :calls calls :live live :time timestamp}))))

(defn- opens [calls] (filter #(= ["plugin" "pane" "open"] (take 3 %)) @calls))
(defn- task-panes [state] (sort (keep #(when (str/starts-with? (:pane_id %) "tasks") (:pane_id %)) (:panes @state))))

(deftest ensure-opens-every-tab-once-without-focus-or-input
  (temporary
   (fn [dir]
     (fake-host dir
      (fn [{:keys [calls state]}]
        (is (host/display-enabled?) "display defaults to enabled")
        (host/ensure-all! host/defaults)
        (host/ensure-all! host/defaults)
        (is (= [(str "tasks-" tab2) (str "tasks-" tab)] (task-panes state)))
        (is (= 2 (count (opens calls))) "a live instance is reused")
        (is (every? #(and (some #{"--no-focus"} %) (some #{"down"} %)) (opens calls)))
        (is (not-any? #(some #{"send-text" "send-keys" "run" "focus"} %) @calls)))))))

(deftest toggle-is-global-and-persists
  (temporary
   (fn [dir]
     (fake-host dir
      (fn [{:keys [calls state]}]
        (host/ensure-all! host/defaults)
        (host/toggle! host/defaults)
        (is (false? (host/display-enabled?)))
        (is (empty? (task-panes state)) "disabling closes the pane in every tab")
        (reset! calls [])
        (host/ensure-all! host/defaults)
        (is (empty? (opens calls)) "startup and tab.created stay closed while disabled")
        (host/toggle! host/defaults)
        (is (host/display-enabled?))
        (is (= 2 (count (task-panes state))) "enabling reopens every tab"))))))

(deftest stale-process-and-foreign-pane-are-not-controlled
  (temporary
   (fn [dir]
     (fake-host dir
      (fn [{:keys [calls live state]}]
        (host/ensure-all! host/defaults)
        (reset! calls [])
        (reset! live false)
        (host/close-tab! dir tab)
        (is (not-any? #(= ["plugin" "pane" "close"] (take 3 %)) @calls))
        (reset! live true)
        (swap! state update :panes #(mapv (fn [p] (if (= (str "tasks-" tab) (:pane_id p)) (assoc p :terminal_id "foreign") p)) %))
        (reset! calls [])
        (host/close-tab! dir tab)
        (is (not-any? #(= ["plugin" "pane" "close"] (take 3 %)) @calls))
        (host/close-tab! dir tab2)
        (is (some #{["plugin" "pane" "close" (str "tasks-" tab2)]} @calls) "positive control"))))))

(deftest process-exit-reopen-uses-new-instance
  (temporary
   (fn [dir]
     (fake-host dir
      (fn [{:keys [state]}]
        (host/open-tab! dir tab host/defaults)
        (let [old (:instance (host/read-json (fs/path dir (str tab ".json"))))]
          (swap! state update :panes #(remove (fn [p] (str/starts-with? (:pane_id p) "tasks")) %))
          (host/open-tab! dir tab host/defaults)
          (is (not= old (:instance (host/read-json (fs/path dir (str tab ".json"))))))))))))

(deftest pending-open-reservation-is-not-replaced
  (temporary
   (fn [dir]
     (fake-host dir
      (fn [{:keys [calls]}]
        (host/atomic! (fs/path dir (str tab ".json"))
                      {:instance (host/uuid) :tab tab :pending true :updatedAt 10000})
        (host/open-tab! dir tab host/defaults)
        (is (empty? (opens calls)))
        (host/open-tab! dir tab2 host/defaults)
        (is (= 1 (count (opens calls))) "positive control: another tab opens"))))))

(deftest select-opens-a-popup-for-the-compact-pane-project
  (temporary
   (fn [dir]
     (fake-host dir
      (fn [{:keys [calls state]}]
        (let [project (str (fs/create-dir (fs/path dir "project")))
              select-env #(some (fn [x] (when (str/starts-with? (str x) "OT_SELECT_DIR=") x))
                                (last (filter (fn [call] (some #{"select"} call)) @calls)))]
          (swap! state update :panes #(mapv (fn [p] (if (= "agent-a" (:pane_id p)) (assoc p :foreground_cwd project) p)) %))
          (swap! state assoc :agents (filter :agent (:panes @state)))
          (host/open-tab! dir tab host/defaults)
          (host/select!)
          (is (= (str "OT_SELECT_DIR=" project) (select-env)))
          (is (not-any? #(some #{"--placement"} %) (filter #(some #{"select"} %) @calls))
              "the manifest supplies the popup placement")
          (testing "with the task pane focused, the retained source still names the project"
            (let [record (host/read-json (fs/path dir (str tab ".json")))]
              (host/atomic! (fs/path dir (:instance record) "context.json") {:sourcePane "agent-a"})
              (swap! state assoc :layouts [{:tab_id tab :focused_pane_id (str "tasks-" tab)}])
              (host/select!)
              (is (= (str "OT_SELECT_DIR=" project) (select-env)))))
          (testing "a shell pane gives no project"
            (swap! state assoc :layouts [{:tab_id tab :focused_pane_id "agent-a"}])
            (with-redefs [host/process! (constantly {:foreground_processes [{:name "bash"}]})]
              (host/select!))
            (is (= "OT_SELECT_DIR=" (select-env))))))))))

(deftest concurrent-ensure-actions-reuse-one-host-pane
  (temporary
   (fn [dir]
     (host/atomic! (fs/path dir "host.json") (assoc (snapshot [a]) :focused_tab_id tab))
     (let [argv ["bb" "-cp" (str host-source-root ":herdr/plugins/org-tasks/test")
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

(deftest pane-label-mirrors-the-tui-title-and-tolerates-failure
  (let [calls (atom [])]
    (with-redefs [host/call! (fn [& args] (swap! calls conj (vec args)) {})]
      (host/sync-label! (assoc task-pane :label "Org tasks" :terminal_title "Tasks :: ~/p :: plan.org"))
      (host/sync-label! (assoc task-pane :label "Tasks :: ~/p" :terminal_title "Tasks :: ~/p"))
      (host/sync-label! (assoc task-pane :label "Org tasks"))
      (host/sync-label! (assoc task-pane :label "Org tasks" :terminal_title " ")))
    (is (= [["pane" "rename" "tasks" "Tasks :: ~/p :: plan.org"]] @calls)
        "only a changed, non-blank title renames the pane"))
  (with-redefs [host/call! (fn [& _] (throw (ex-info "Herdr request timed out" {})))]
    (is (nil? (host/sync-label! (assoc task-pane :terminal_title "Tasks"))))))

(def probe-script (str (fs/absolutize *file*)))

(defn supervisor-probe
  "Child process: run the real supervisor loop against a fake Herdr while the
  focused agent moves from A to B, and report every published context."
  [root]
  (let [instance (System/getenv "OT_HOST_INSTANCE")
        a (assoc a :foreground_cwd (str root "/a")) b (assoc b :foreground_cwd (str root "/b"))
        state (atom (assoc (snapshot [a b (assoc task-pane :terminal_title "Tasks :: ~/a")])
                           :layouts [{:tab_id tab :focused_pane_id "agent-a"}]))
        snapshots (atom 0) contexts (atom []) renames (atom []) real-atomic host/atomic!]
    (with-redefs [host/call! (fn [& args]
                               (case (vec (take 2 args))
                                 ["api" "snapshot"]
                                 (do (when (= 3 (swap! snapshots inc))
                                       (swap! state assoc :layouts [{:tab_id tab :focused_pane_id "agent-b"}]))
                                     {:snapshot @state})
                                 ["pane" "process-info"] {:process_info (if (= "tasks" (last args))
                                                                           {:shell_pid (.pid (java.lang.ProcessHandle/current))}
                                                                           local-info)}
                                 ["pane" "rename"] (do (swap! renames conj (vec (drop 2 args))) {})))
                  host/herdr-config-text (constantly "[[keys.command]]\nkey = \"prefix+t\"\ncommand = \"cormacc.org-tasks.select\"\n")
                  host/state-root! (constantly root) host/identity! (constantly "fixture")
                  host/atomic! (fn [file value]
                                 (when (= "context.json" (str (fs/file-name file)))
                                   (swap! contexts conj value))
                                 (real-atomic file value))]
      (host/supervise! (str root "/sleeper")))
    (println (pr-str {:sources (mapv :sourcePane @contexts)
                      :directories (vec (distinct (keep :directory @contexts)))
                      :modes (set (map :mode @contexts))
                      :hints (set (map :hints @contexts))
                      :renames @renames}))))

(when-let [root (System/getenv "SUPERVISOR_PROBE_ROOT")]
  (supervisor-probe root)
  (System/exit 0))

(deftest supervisor-follows-the-focused-agent-and-publishes-hints
  (temporary
   (fn [dir]
     (let [instance (host/uuid) private (host/private-dir! (fs/path dir instance))]
       (fs/create-dir (str dir "/a")) (fs/create-dir (str dir "/b"))
       (spit (str dir "/sleeper") (str "#!/bin/sh\nexec " sleep-bin " 2\n"))
       (fs/set-posix-file-permissions (str dir "/sleeper") "rwx------")
       (host/atomic! (fs/path dir (str tab ".json")) {:instance instance :tab tab :pending true :updatedAt (host/now)})
       (let [proc (p/process ["bb" probe-script]
                             {:extra-env {"SUPERVISOR_PROBE_ROOT" dir "OT_HOST_DIR" private
                                          "OT_HOST_INSTANCE" instance "OT_HOST_TAB" tab
                                          "HERDR_PANE_ID" "tasks"}
                              :out :string :err :string})
             result (deref proc 15000 nil)]
         (when-not result (p/destroy-tree proc))
         (is (and result (zero? (:exit result))) (str "probe exit/err: " (:err result)))
         (let [out (read-string (last (str/split-lines (:out result))))]
           (is (= [(str dir "/a") (str dir "/b")] (:directories out)) "A first, then B after the focus change")
           (is (= #{"compact"} (:modes out)))
           (is (= #{["ctrl+b t: Select a task from the backlog"]} (:hints out)))
           (is (= [["tasks" "Tasks :: ~/a"]] (take 1 (:renames out))) "the label follows the TUI title")))))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))

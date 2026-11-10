#!/usr/bin/env bb
(require '[babashka.classpath :as cp])
(cp/add-classpath "herdr/plugins/org-tasks/src")
(require '[org-tasks-host :as host]
         '[org-tasks.tui.embedding :as embed]
         '[babashka.fs :as fs]
         '[babashka.process :as p]
         '[cheshire.core :as json]
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


;; ── Event subscription ──────────────────────────────────────────

(defn fake-herdr
  "Run `f` with the path of a one-connection fake Herdr socket and an atom of
  the request lines it read. `respond` gets a line writer; the connection
  closes when `respond` returns."
  [respond f]
  (let [dir (fs/create-temp-dir {:prefix "ot-sock-"}) ; short: the Unix socket path limit
        path (str dir "/h.sock")
        server (doto (java.nio.channels.ServerSocketChannel/open java.net.StandardProtocolFamily/UNIX)
                 (.bind (java.net.UnixDomainSocketAddress/of path)))
        requests (atom [])
        worker (future
                 (with-open [ch (.accept server)]
                   (let [r (java.io.BufferedReader. (java.io.InputStreamReader. (java.nio.channels.Channels/newInputStream ch) "UTF-8"))
                         w (java.nio.channels.Channels/newOutputStream ch)]
                     (swap! requests conj (.readLine r))
                     (respond (fn [line] (.write w (.getBytes (str line "\n") "UTF-8")) (.flush w))))))]
    (try (f path requests)
         (finally (.close server) (future-cancel worker) (fs/delete-tree dir)))))

(def started "{\"id\":\"x\",\"result\":{\"type\":\"subscription_started\"}}")
(def focused-event "{\"event\":\"tab_focused\",\"data\":{\"type\":\"tab_focused\",\"tab_id\":\"w1:t2\",\"workspace_id\":\"w1\"}}")
(def closed-event "{\"event\":\"pane_closed\",\"data\":{\"type\":\"pane_closed\",\"pane_id\":\"w1:p2\",\"workspace_id\":\"w1\"}}")
(def error-reply "{\"id\":\"x\",\"error\":{\"code\":\"invalid_request\",\"message\":\"bad\"}}")

(defn subscription-outcome [respond]
  (fake-herdr respond
    (fn [path requests]
      (let [seen (atom [])
            result (try (let [conn (host/subscribe! path ["tab.focused" "pane.closed"])]
                          (host/events! conn #(swap! seen conj %)))
                        (catch clojure.lang.ExceptionInfo e {:thrown (:subscription (ex-data e))}))]
        {:result result :events @seen :requests @requests}))))

(deftest subscription-sends-one-request-and-delivers-each-event
  (let [{:keys [result events requests]}
        (with-redefs [p/process (fn [& _] (throw (ex-info "no herdr process per event" {})))]
          (subscription-outcome (fn [w] (w started) (w focused-event) (w closed-event))))
        request (json/parse-string (first requests) true)]
    (is (= 1 (count requests)) "one request line")
    (is (= "events.subscribe" (:method request)))
    (is (= [{:type "tab.focused"} {:type "pane.closed"}] (get-in request [:params :subscriptions])))
    (is (string? (:id request)))
    (is (= [["tab_focused" "w1:t2"] ["pane_closed" "w1:p2"]]
           (mapv (juxt :event #(or (get-in % [:data :tab_id]) (get-in % [:data :pane_id]))) events))
        "each event reaches the callback, parsed, in order")
    (is (= {:end :closed} result) "a closed connection ends the stream")))

(deftest subscription-ends-with-a-distinct-result-for-each-failure
  (testing "during the handshake"
    (is (= :closed (:thrown (:result (subscription-outcome (fn [_] nil))))))
    (is (= :error (:thrown (:result (subscription-outcome (fn [w] (w error-reply)))))))
    (is (= :unparsable (:thrown (:result (subscription-outcome (fn [w] (w "not json")))))))
    (is (= :unexpected (:thrown (:result (subscription-outcome (fn [w] (w focused-event)))))))
    (binding [host/*subscribe-timeout-ms* 200]
      (is (= :timeout (:thrown (:result (subscription-outcome (fn [_] (Thread/sleep 2000)))))))))
  (testing "after the subscription started"
    (let [{:keys [result events]} (subscription-outcome (fn [w] (w started) (w focused-event) (w error-reply)))]
      (is (= 1 (count events)) "control: the event before the error arrived")
      (is (= :error (:end result)))
      (is (= "invalid_request" (get-in result [:error :code]))))
    (let [{:keys [result]} (subscription-outcome (fn [w] (w started) (w "{oops")))]
      (is (= {:end :unparsable :line "{oops"} result)))
    (let [{:keys [result]} (subscription-outcome (fn [w] (w started) (w "{\"event\":\"tab_focused\"}")))]
      (is (= :unparsable (:end result)) "an envelope without data"))))

(deftest a-failing-callback-closes-the-connection
  (fake-herdr (fn [w] (w started) (w focused-event) (Thread/sleep 2000))
    (fn [path _]
      (let [conn (host/subscribe! path ["tab.focused"])]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"callback"
                              (host/events! conn (fn [_] (throw (ex-info "callback" {}))))))
        (is (not (.isOpen ^java.nio.channels.SocketChannel (:channel conn))))))))

(deftest source-is-tab-local-and-preserved-only-on-task-focus
  (is (= "agent-a" (:pane_id (host/source (snapshot [a task-pane]) tab "tasks" nil))))
  (is (= "agent-a" (:pane_id (host/source (snapshot [(assoc a :focused false) (assoc task-pane :focused true)]) tab "tasks" a))))
  (is (nil? (host/source (snapshot [(assoc a :tab_id "another-tab") task-pane]) tab "tasks" nil)))
  (is (= "unavailable" (:availability (host/source-context
                                     (host/source (snapshot [(assoc a :focused false) (assoc task-pane :pane_id "shell" :focused true)]) tab "tasks" a)
                                     {:foreground_processes [{:name "bash"}]})))
      "a shell with no known folder")
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
    (doseq [[candidate info] [[nil nil] [(assoc a :foreground_cwd "relative") local-info]
                              [(assoc a :foreground_cwd (str (fs/path dir "missing"))) local-info]]]
      (is (= "unavailable" (:availability (host/context first "instance" candidate info nil 500)))))))))

(deftest a-shell-pane-is-a-source
  (let [shell {:pane_id "shell" :terminal_id "term-shell" :tab_id tab :focused true
               :foreground_cwd (str (fs/cwd))}
        candidate (host/source (snapshot [(assoc a :focused false) shell task-pane]) tab "tasks" a)]
    (is (= "shell" (:pane_id candidate)) "focus on a shell moves the source")
    (is (= {:availability "local" :directory (str (fs/cwd))}
           (host/source-context candidate {:foreground_processes [{:name "bash"}]})))
    (is (= "remote" (:availability (host/source-context candidate {:foreground_processes [{:name "ssh"}]}))))))

(deftest a-plugin-pane-retains-the-previous-source
  (let [root "/plugins/quota"
        quota {:pane_id "quota" :terminal_id "term-quota" :tab_id tab :focused true :foreground_cwd root}
        panes [(assoc a :focused false) quota task-pane]]
    (is (= "agent-a" (:pane_id (host/source (snapshot panes) tab "tasks" a #{root}))))
    (is (nil? (host/source (snapshot panes) tab "tasks" nil #{root})) "no previous source: none")
    (is (= "quota" (:pane_id (host/source (snapshot panes) tab "tasks" a #{})))
        "control: an unregistered root is an ordinary pane")
    (is (= "agent-a" (:pane_id (host/source (snapshot [(assoc a :foreground_cwd root)]) tab "tasks" nil #{root})))
        "an agent pane is never plugin UI")))

(deftest a-folder-change-in-the-same-pane-holds-before-it-publishes
  (let [at (fn [pane dir] {:availability "local" :directory dir :sourcePane pane :sourceTerminal (str "t-" pane)})
        old (at "p1" "/a")
        step (fn [[_ pending] time] (host/settle old (at "p1" "/b") pending time))
        samples (reductions step [old nil] [0 500 999 1000])]
    (is (= ["/a" "/a" "/a" "/a" "/b"] (map (comp :directory first) samples))
        "published only by a sample hold-ms after the first saw it")
    (is (= {:directory "/b" :since 0} (second (nth samples 3))) "the hold runs from the first sample")
    (is (= (at "p1" "/a") (first (host/settle old (at "p1" "/a") {:directory "/tmp" :since 0} 500)))
        "a return to the published folder cancels the pending one")
    (is (nil? (second (host/settle old (at "p1" "/a") {:directory "/tmp" :since 0} 500))))
    (is (= [(at "p1" "/a") {:directory "/tmp" :since 2000}] (host/settle old (at "p1" "/tmp") {:directory "/b" :since 0} 2000))
        "a different folder restarts the hold")
    (is (= (at "p2" "/b") (first (host/settle old (at "p2" "/b") nil 0))) "a focus change publishes at once")
    (is (= "remote" (:availability (first (host/settle old {:availability "remote" :directory nil :sourcePane "p1"} nil 0)))))
    (is (= (at "p1" "/a") (first (host/settle nil (at "p1" "/a") nil 0))) "the first source publishes at once")))

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
    (is (= ["ctrl+a t: select / ctrl+a ctrl+o: show/hide"]
           (host/hints config))
        "both bound actions share one line")
    (is (= ["ctrl+b t: select / ctrl+b ctrl+o: show/hide"]
           (host/hints (str/replace config "[keys]\nprefix = \"ctrl+a\" # comment\n" "")))
        "Herdr's default prefix applies when keys.prefix is absent")
    (is (= ["ctrl+a ctrl+o: show/hide"]
           (host/hints (str/replace config "cormacc.org-tasks.select" "other.plugin.select")))
        "an unbound action has no hint")
    (is (= ["ctrl+a f12: select"]
           (host/hints (str "[keys]\nprefix = [\"ctrl+a\", \"f12\"]\n"
                            "[[keys.command]]\ncommand = \"cormacc.org-tasks.select\"\nkey = \"prefix+f12\"\n")))
        "the first of several prefixes; key and command in either order")
    (is (nil? (host/hints nil)))
    (is (= "ctrl+b ctrl+o" (host/display-key "ctrl+b" "prefix+ctrl+o")))
    (is (= "ctrl+b O" (host/display-key "ctrl+b" "prefix+shift+o")) "shift+letter renders as the capital")
    (is (= "alt+x" (host/display-key "ctrl+b" "alt+x")) "a binding without the prefix is shown as is")))

(def tab2 "w1:t9")
(def c (assoc a :pane_id "agent-c" :terminal_id "term-c" :tab_id tab2 :focused false))

(defn fake-host
  "Two tabs with one agent each; `tab` has focus. A plugin pane open creates a
  `tasks-<tab>` pane in the target pane's tab, whose supervisor is live while
  `live` holds."
  [dir f]
  (let [s (atom (assoc (snapshot [a c]) :tabs [{:tab_id tab} {:tab_id tab2}]))
        calls (atom []) live (atom true) timestamp (atom 10000)
        display (str (fs/path dir "plugin-state"))
        arg-after (fn [args flag] (second (drop-while #(not= flag %) args)))
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
                         (let [owner-tab (:tab_id (host/pane @s (arg-after args "--target-pane")))
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
(defn- closes [calls] (filter #(= ["plugin" "pane" "close"] (take 3 %)) @calls))
(defn- task-panes [state] (sort (keep #(when (str/starts-with? (:pane_id %) "tasks") (:pane_id %)) (:panes @state))))

(deftest ensure-opens-one-pane-in-the-focused-tab-without-focus-or-input
  (temporary
   (fn [dir]
     (fake-host dir
      (fn [{:keys [calls state]}]
        (is (host/display-enabled?) "display defaults to enabled")
        (swap! state #(-> % (update :tabs conj {:tab_id "w1:t5"})
                          (update :panes conj (assoc c :pane_id "agent-e" :terminal_id "term-e" :tab_id "w1:t5"))))
        (is (= 3 (count (:tabs @state))) "control: the session has three tabs")
        (host/ensure! host/defaults)
        (host/ensure! host/defaults)
        (is (= [(str "tasks-" tab)] (task-panes state)) "one pane, in the focused tab only")
        (is (= 1 (count (opens calls))) "a live instance is reused")
        (is (some #{"agent-a"} (first (opens calls))) "below the focused pane")
        (is (every? #(and (some #{"--no-focus"} %) (some #{"down"} %)) (opens calls)))
        (is (not-any? #(some #{"send-text" "send-keys" "run" "focus"} %) @calls))
        (is (= (str "tasks-" tab) (:pane (host/read-json (host/index-path dir)))) "the session index names it"))))))

(deftest toggle-is-global-and-persists
  (temporary
   (fn [dir]
     (fake-host dir
      (fn [{:keys [calls state]}]
        (host/ensure! host/defaults)
        (host/toggle! host/defaults)
        (is (false? (host/display-enabled?)))
        (is (empty? (task-panes state)) "disabling closes the single pane")
        (reset! calls [])
        (host/ensure! host/defaults)
        (is (empty? (opens calls)) "startup and tab.focused stay closed while disabled")
        (host/toggle! host/defaults)
        (is (host/display-enabled?))
        (is (= [(str "tasks-" tab)] (task-panes state)) "enabling reopens it in the focused tab"))))))

(deftest stale-process-and-foreign-pane-are-not-controlled
  (temporary
   (fn [dir]
     (fake-host dir
      (fn [{:keys [calls live state]}]
        (host/ensure! host/defaults)
        (let [task (str "tasks-" tab)
              set-terminal! (fn [t] (swap! state update :panes #(mapv (fn [p] (if (= task (:pane_id p)) (assoc p :terminal_id t) p)) %)))]
          (reset! calls [])
          (reset! live false)
          (host/close-session! dir)
          (is (empty? (closes calls)) "a dead supervisor's pane is not closed")
          (reset! live true)
          (set-terminal! "foreign")
          (host/close-session! dir)
          (is (empty? (closes calls)) "a foreign terminal in the pane is not closed")
          (set-terminal! (str "term-" task))
          (host/close-session! dir)
          (is (= [["plugin" "pane" "close" task]] (closes calls)) "positive control")))))))

(deftest process-exit-reopen-uses-new-instance
  (temporary
   (fn [dir]
     (fake-host dir
      (fn [{:keys [state]}]
        (host/open-session! dir host/defaults)
        (let [old (:instance (host/read-json (host/index-path dir)))]
          (is (some? old) "control: the first open has an instance")
          (swap! state update :panes #(remove (fn [p] (str/starts-with? (:pane_id p) "tasks")) %))
          (host/open-session! dir host/defaults)
          (is (not= old (:instance (host/read-json (host/index-path dir)))))))))))

(deftest pending-open-reservation-is-not-replaced
  (temporary
   (fn [dir]
     (fake-host dir
      (fn [{:keys [calls time]}]
        (host/atomic! (host/index-path dir) {:instance (host/uuid) :pending true :updatedAt 10000})
        (host/open-session! dir host/defaults)
        (is (empty? (opens calls)) "a fresh reservation holds")
        (reset! time 15001)
        (host/open-session! dir host/defaults)
        (is (= 1 (count (opens calls))) "positive control: an expired reservation is replaced"))))))

(deftest ensure-closes-compact-panes-of-the-per-tab-design
  ;; After an upgrade, supervisors of the per-tab design can still run in
  ;; every tab. Their panes close, and their per-tab index files go.
  (temporary
   (fn [dir]
     (fake-host dir
      (fn [{:keys [calls state]}]
        (let [legacy (fn [pane-tab pane-id]
                       (let [instance (host/uuid)
                             record {:pane pane-id :terminal (str "term-" pane-id) :tab pane-tab :pid 22
                                     :identity "start command" :instance instance :updatedAt 10000}]
                         (fs/create-dirs (fs/path dir instance))
                         (host/atomic! (fs/path dir instance "owner.json") record)
                         (host/atomic! (fs/path dir (str pane-tab ".json")) record)
                         record))]
          (legacy tab2 "tasks-old")
          (swap! state update :panes conj {:pane_id "tasks-old" :terminal_id "term-tasks-old" :tab_id tab2})
          (legacy "w1:t7" "tasks-gone")
          (host/ensure! host/defaults)
          (is (= [["plugin" "pane" "close" "tasks-old"]] (closes calls)) "only the live legacy pane is closed")
          (is (not (fs/exists? (fs/path dir (str tab2 ".json")))))
          (is (not (fs/exists? (fs/path dir "w1:t7.json"))) "a dead legacy index is removed too")
          (is (= [(str "tasks-" tab)] (task-panes state)) "the single pane opens")))))))

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
          (host/open-session! dir host/defaults)
          (host/select!)
          (is (= (str "OT_SELECT_DIR=" project) (select-env)))
          (is (not-any? #(some #{"--placement"} %) (filter #(some #{"select"} %) @calls))
              "the manifest supplies the popup placement")
          (testing "with the task pane focused, the retained source still names the project"
            (let [record (host/read-json (host/index-path dir))]
              (host/atomic! (fs/path dir (:instance record) "context.json") {:sourcePane "agent-a"})
              (swap! state assoc :layouts [{:tab_id tab :focused_pane_id (str "tasks-" tab)}])
              (host/select!)
              (is (= (str "OT_SELECT_DIR=" project) (select-env)))))
          (testing "a shell pane gives its own folder"
            (let [other (str (fs/create-dir (fs/path dir "other")))]
              (swap! state update :panes conj {:pane_id "shell" :terminal_id "term-shell" :tab_id tab
                                               :foreground_cwd other})
              (swap! state assoc :layouts [{:tab_id tab :focused_pane_id "shell"}])
              (with-redefs [host/process! (constantly {:foreground_processes [{:name "bash"}]})]
                (host/select!))
              (is (= (str "OT_SELECT_DIR=" other) (select-env)))))
          (testing "a remote pane gives no project"
            (with-redefs [host/process! (constantly {:foreground_processes [{:name "ssh"}]})]
              (host/select!))
            (is (= "OT_SELECT_DIR=" (select-env))))))))))

(deftest sample-reasons-and-move-ratio
  (is (host/sample-reason tab {:event "tab_focused" :data {:tab_id tab2}}))
  (is (host/sample-reason tab {:event "pane_focused" :data {:pane_id "p"}}))
  (is (host/sample-reason tab {:event "pane_closed" :data {:pane_id "p"}}))
  (is (host/sample-reason tab {:event "pane_updated" :data {:pane {:tab_id tab}}}) "a title change in the own tab")
  (is (not (host/sample-reason tab {:event "pane_updated" :data {:pane {:tab_id tab2}}})) "another tab's title change")
  (is (not (host/sample-reason tab {:event "layout_updated" :data {}})))
  (is (= 0.875 (host/move-ratio 40 5)))
  (is (= 0.5 (host/move-ratio 8 5)) "a short target keeps half")
  (is (= 0.95 (host/move-ratio 400 5))))

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
      (let [e (try (host/call! "api" "snapshot") nil (catch Exception e e))]
        (is (some? e))
        (is (not (host/timeout? e)) "only a timeout is transient"))))
  (let [killed (atom false)]
    (binding [host/*request-timeout-ms* 1]
      (with-redefs [p/process (fn [& _] (promise)) p/destroy-tree (fn [_] (reset! killed true))]
        (let [e (try (host/call! "api" "snapshot") nil (catch Exception e e))]
          (is (host/timeout? e)))
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

(defn- env-long [k] (some-> (System/getenv k) not-empty parse-long))

(defn supervisor-probe
  "Child process: run the real supervisor loop against a fake Herdr and a
  scripted event stream, and report what it published and asked Herdr to do."
  [root]
  (let [a (assoc a :foreground_cwd (str root "/a")) b (assoc b :foreground_cwd (str root "/b"))
        c (assoc c :foreground_cwd (str root "/c"))
        state (atom (assoc (snapshot [a b c (assoc task-pane :terminal_title "Tasks :: ~/a")])
                           :tabs [{:tab_id tab} {:tab_id tab2}]
                           :layouts [{:tab_id tab :focused_pane_id "agent-a"}
                                     {:tab_id tab2 :focused_pane_id "agent-c"}]))
        focus-pane! (fn [pane-id] (swap! state update :layouts
                                         (fn [ls] (mapv #(if (= tab (:tab_id %)) (assoc % :focused_pane_id pane-id) %) ls))))
        ;; PROBE_TIMEOUTS lists snapshot calls that time out; focus moves to B
        ;; on the first successful call after them.
        timeouts (set (map parse-long (remove str/blank? (str/split (or (System/getenv "PROBE_TIMEOUTS") "") #","))))
        switch-at (when-not (System/getenv "PROBE_NO_SWITCH") (inc (reduce max 2 timeouts)))
        ;; PROBE_ALONE_AT: from that snapshot call on, the agents' panes are
        ;; gone and the task pane is alone in its tab.
        alone-at (env-long "PROBE_ALONE_AT")
        ;; PROBE_FOCUS_TAB_AT: from that snapshot call on, the other tab has focus.
        focus-tab-at (env-long "PROBE_FOCUS_TAB_AT")
        ;; PROBE_CD_AT: at that snapshot call, agent A's folder becomes B's.
        cd-at (env-long "PROBE_CD_AT")
        ;; PROBE_MOVE_ID: the pane's ID after a move, as across workspaces.
        move-id (System/getenv "PROBE_MOVE_ID")
        ;; PROBE_INFO_FAILS: the first n process-info calls for an agent pane
        ;; fail, as for a pane that closed after the snapshot.
        info-fails (or (env-long "PROBE_INFO_FAILS") 0)
        ;; PROBE_EVENTS: EDN events, each applied to the fake state when it is
        ;; delivered: {:after ms :event name :focus pane-id :focus-tab tab-id}.
        script (some-> (System/getenv "PROBE_EVENTS") not-empty read-string)
        events-end? (some? (System/getenv "PROBE_EVENTS_END"))
        failed-infos (atom 0) own-id (atom "tasks")
        snapshots (atom 0) contexts (atom []) renames (atom []) closes (atom []) moves (atom [])
        error (atom nil) real-atomic host/atomic!]
    (with-redefs [host/call! (fn [& args]
                               (case (vec (take 2 args))
                                 ["api" "snapshot"]
                                 (let [n (swap! snapshots inc)]
                                   (when (timeouts n)
                                     (throw (ex-info "Herdr request timed out" {:args args :timeout true})))
                                   (when (= switch-at n) (focus-pane! "agent-b"))
                                   (when (= alone-at n)
                                     (swap! state update :panes (fn [ps] (filterv #(= @own-id (:pane_id %)) ps))))
                                   (when (= focus-tab-at n) (swap! state assoc :focused_tab_id tab2))
                                   (when (= cd-at n)
                                     (let [cd (fn [ps] (mapv #(if (= "agent-a" (:pane_id %)) (assoc % :foreground_cwd (str root "/b")) %) ps))]
                                       ;; `source` merges the agent entry, so both lists change.
                                       (swap! state #(-> % (update :panes cd) (update :agents cd)))))
                                   {:snapshot @state})
                                 ["pane" "process-info"] (if (= @own-id (last args))
                                                            {:process_info {:shell_pid (.pid (java.lang.ProcessHandle/current))}}
                                                            (if (< @failed-infos info-fails)
                                                              (do (swap! failed-infos inc)
                                                                  (throw (ex-info "Herdr request failed: pane not found" {:args args})))
                                                              {:process_info local-info}))
                                 ["pane" "rename"] (do (swap! renames conj (vec (drop 2 args))) {})
                                 ["pane" "layout"] {:layout {:panes [] :splits []}}
                                 ["pane" "move"] (let [to (second (drop-while #(not= "--tab" %) args))
                                                       old @own-id new (or move-id old)]
                                                   (swap! moves conj (vec (drop 2 args)))
                                                   (reset! own-id new)
                                                   (swap! state update :panes
                                                          (fn [ps] (mapv #(if (= old (:pane_id %)) (assoc % :pane_id new :tab_id to) %) ps)))
                                                   {:move_result {:pane (host/pane @state new)}})
                                 ["plugin" "pane"] (do (swap! closes conj (vec (drop 2 args))) {})))
                  host/subscribe! (fn [_ _] {:channel (java.nio.channels.SocketChannel/open java.net.StandardProtocolFamily/UNIX)})
                  host/events! (fn [_ on-event]
                                 (doseq [{:keys [after focus focus-tab] :as e} script]
                                   (Thread/sleep (or after 0))
                                   (when focus (focus-pane! focus))
                                   (when focus-tab (swap! state assoc :focused_tab_id focus-tab))
                                   (on-event {:event (:event e) :data (or (:data e) {})}))
                                 (if events-end? {:end :closed} @(promise)))
                  host/herdr-config-text (constantly "[[keys.command]]\nkey = \"prefix+t\"\ncommand = \"cormacc.org-tasks.select\"\n")
                  host/state-root! (constantly root) host/identity! (constantly "fixture")
                  host/atomic! (fn [file value]
                                 (when (= "context.json" (str (fs/file-name file)))
                                   (swap! contexts conj value))
                                 (real-atomic file value))]
      (binding [host/*fallback-ms* (or (env-long "PROBE_FALLBACK_MS") 300)]
        (try (host/supervise! (str root "/sleeper") host/defaults)
             (catch Exception e (reset! error (ex-message e))))))
    (println (pr-str {:sources (mapv :sourcePane @contexts)
                      :directories (vec (distinct (keep :directory @contexts)))
                      :modes (set (map :mode @contexts))
                      :hints (set (map :hints @contexts))
                      :renames @renames
                      :closes @closes
                      :moves @moves
                      :snapshots @snapshots
                      :index (host/read-json (host/index-path root))
                      :error @error
                      :failed-infos @failed-infos}))))

(when-let [root (System/getenv "SUPERVISOR_PROBE_ROOT")]
  (supervisor-probe root)
  (System/exit 0))

(defn run-supervisor-probe
  "Run the probe with an `ot` child that lives `seconds` and the probe `env`;
  return its report."
  [dir seconds env]
  (let [instance (host/uuid) private (host/private-dir! (fs/path dir instance))]
    (doseq [d ["a" "b" "c"]] (fs/create-dir (str dir "/" d)))
    (spit (str dir "/sleeper") (str "#!/bin/sh\nexec " sleep-bin " " seconds "\n"))
    (fs/set-posix-file-permissions (str dir "/sleeper") "rwx------")
    (host/atomic! (host/index-path dir) {:instance instance :pending true :updatedAt (host/now)})
    (let [proc (p/process ["bb" probe-script]
                          {:extra-env (merge {"SUPERVISOR_PROBE_ROOT" dir "OT_HOST_DIR" private
                                              "OT_HOST_INSTANCE" instance "HERDR_PANE_ID" "tasks"
                                              "HERDR_SOCKET_PATH" "fake"}
                                             env)
                           :out :string :err :string})
          result (deref proc 20000 nil)]
      (when-not result (p/destroy-tree proc))
      (is (and result (zero? (:exit result))) (str "probe exit/err: " (:err result)))
      (when (and result (zero? (:exit result)))
        (read-string (last (str/split-lines (:out result))))))))

(deftest supervisor-survives-host-request-timeouts
  ;; A short Herdr stall must not end the pane: the supervisor retries and
  ;; still follows the next focus change.
  (temporary
   (fn [dir]
     (let [out (run-supervisor-probe dir 3 {"PROBE_TIMEOUTS" "3,4"})]
       (is (= [(str dir "/a") (str dir "/b")] (:directories out)))))))

(deftest supervisor-follows-the-focused-agent-and-publishes-hints
  (temporary
   (fn [dir]
     (let [out (run-supervisor-probe dir 2 {})]
       (is (= [(str dir "/a") (str dir "/b")] (:directories out)) "A first, then B after the focus change")
       (is (= #{"compact"} (:modes out)))
       (is (= #{["ctrl+b t: select"]} (:hints out)))
       (is (= [["tasks" "Tasks :: ~/a"]] (take 1 (:renames out))) "the label follows the TUI title")
       (is (= [] (:closes out)) "control: with other panes in the tab, the pane stays open")
       (is (= [] (:moves out)) "the focused tab already holds the pane")
       (is (nil? (:error out)))))))

(deftest supervisor-survives-a-source-pane-that-closes-mid-sample
  ;; A pane can close between the snapshot and its process-info request.
  ;; That sample is skipped; the supervisor keeps running.
  (temporary
   (fn [dir]
     (let [out (run-supervisor-probe dir 3 {"PROBE_INFO_FAILS" "2"})]
       (is (= 2 (:failed-infos out)) "control: two process-info requests failed")
       ;; The failed samples are the first two, while A has focus; focus
       ;; moves to B at the third snapshot.
       (is (= [(str dir "/b")] (:directories out)) "the next sample publishes")))))

(deftest supervisor-closes-its-pane-when-alone-in-the-tab
  ;; Herdr closes a tab with its last pane. A compact pane left alone, after
  ;; an agent's pane closes, must close too, so the finished tab goes.
  (temporary
   (fn [dir]
     (let [started (System/currentTimeMillis)
           out (run-supervisor-probe dir 10 {"PROBE_ALONE_AT" "3"})]
       (is (= [["close" "tasks"]] (:closes out)) "the supervisor closes its own pane")
       (is (< (- (System/currentTimeMillis) started) 8000) "and ends before its ot child would")
       (is (= [(str dir "/a")] (:directories out)) "control: the pane worked before it was alone")))))

(deftest supervisor-moves-its-pane-to-the-focused-tab
  (temporary
   (fn [dir]
     (let [out (run-supervisor-probe dir 3 {"PROBE_FOCUS_TAB_AT" "5"})
           [move] (:moves out)]
       (is (= 1 (count (:moves out))) "one move")
       (is (= ["tasks" "--tab" tab2 "--split" "down" "--target-pane" "agent-c"] (take 7 move))
           "below the focused pane of the focused tab")
       (is (some #{"--no-focus"} move))
       (is (= (str dir "/c") (last (:directories out))) "then it shows the new tab's project")
       (is (= ["tasks" tab2] ((juxt :pane :tab) (:index out))) "the session index follows the pane")
       ;; The supervisor ends when its pane's terminal changes, so no error means
       ;; the moved pane kept its terminal.
       (is (nil? (:error out)))))))

(deftest supervisor-follows-a-new-pane-id-after-a-move-across-workspaces
  (temporary
   (fn [dir]
     (let [out (run-supervisor-probe dir 3 {"PROBE_FOCUS_TAB_AT" "5" "PROBE_MOVE_ID" "w2:tasks"})]
       (is (= 1 (count (:moves out))) "control: it moved")
       (is (= "w2:tasks" (:pane (:index out))))
       (is (= (str dir "/c") (last (:directories out))) "later samples find the pane by its new ID")
       (is (nil? (:error out)))))))

(deftest supervisor-samples-on-events-not-on-a-short-poll
  ;; With the fallback poll far away, only events cause samples.
  (temporary
   (fn [dir]
     (let [out (run-supervisor-probe dir 3 {"PROBE_NO_SWITCH" "1" "PROBE_FALLBACK_MS" "60000"
                                            "PROBE_EVENTS" (pr-str [{:after 800 :event "pane_focused" :focus "agent-b"}])})]
       (is (= [(str dir "/a") (str dir "/b")] (:directories out)) "the focus event publishes B")
       (is (<= (:snapshots out) 3) (str "few snapshots in 3 s: " (:snapshots out)))))))

(deftest supervisor-publishes-a-silent-folder-change-after-the-hold
  ;; A `cd` without a title change emits no event. The fallback sample finds
  ;; it, and the next sample, at the end of the hold, publishes it.
  (temporary
   (fn [dir]
     (let [out (run-supervisor-probe dir 3.5 {"PROBE_NO_SWITCH" "1" "PROBE_FALLBACK_MS" "2000" "PROBE_CD_AT" "3"})]
       (is (= [(str dir "/a") (str dir "/b")] (:directories out)) "the new folder publishes")
       ;; Startup and the first sample at 0 s, the fallback sample that finds
       ;; the folder at 2 s, and one hold sample at 3 s that publishes it. The
       ;; next fallback sample, at 5 s, is after the run ends.
       (is (<= (:snapshots out) 5) (str "one hold sample, not one per 250 ms: " (:snapshots out)))))))

(deftest supervisor-ignores-title-changes-in-other-tabs
  (temporary
   (fn [dir]
     (let [noise (vec (repeat 20 {:after 50 :event "pane_updated" :data {:pane {:tab_id tab2}}}))
           out (run-supervisor-probe dir 3 {"PROBE_NO_SWITCH" "1" "PROBE_FALLBACK_MS" "60000"
                                            "PROBE_EVENTS" (pr-str noise)})]
       ;; One snapshot at startup finds the own pane; the second is the first sample.
       (is (= 2 (:snapshots out)) "no sample after the first")))))

(deftest supervisor-groups-rapid-tab-switches-into-one-move
  (temporary
   (fn [dir]
     (let [switches (vec (for [i (range 10)]
                           {:after (if (zero? i) 600 0) :event "tab_focused"
                            :focus-tab (if (even? i) tab2 tab)}))
           ;; The tenth switch returns to the own tab; an eleventh ends in tab2.
           out (run-supervisor-probe dir 3 {"PROBE_NO_SWITCH" "1" "PROBE_FALLBACK_MS" "60000"
                                            "PROBE_EVENTS" (pr-str (conj switches {:after 0 :event "tab_focused" :focus-tab tab2}))})]
       (is (= 1 (count (:moves out))) (str "one move for eleven switches: " (:moves out)))
       (is (= tab2 (:tab (:index out))) "in the last focused tab")))))

(deftest supervisor-exits-when-the-event-stream-ends
  (temporary
   (fn [dir]
     (let [started (System/currentTimeMillis)
           out (run-supervisor-probe dir 10 {"PROBE_EVENTS" (pr-str [{:after 300 :event "pane_focused"}])
                                             "PROBE_EVENTS_END" "1"})]
       (is (= "Herdr event stream ended" (:error out)))
       (is (< (- (System/currentTimeMillis) started) 8000) "before its ot child would end")))))

(deftest ot-resolution-prefers-the-home-skill
  (temporary
   (fn [dir]
     (let [home (fs/path dir "home")
           plugin-root (fs/path dir "clone" "herdr" "plugins" "org-tasks")
           home-ot (str (fs/path home ".agents" "skills" "org-tasks" "scripts" "ot"))
           checkout-ot (str (fs/path plugin-root ".." ".." ".." "skills" "org-tasks" "scripts" "ot"))
           executable! (fn [path] (fs/create-dirs (fs/parent path)) (spit path "#!/bin/sh\n")
                         (fs/set-posix-file-permissions path "rwxr-xr-x"))]
       (fs/create-dirs plugin-root)
       (testing "neither candidate: the error names both"
         (let [e (try (host/resolve-ot (str home) (str plugin-root)) nil (catch Exception e e))]
           (is (some? e))
           (is (str/includes? (ex-message e) home-ot) (ex-message e))
           (is (str/includes? (ex-message e) checkout-ot) (ex-message e))))
       (executable! checkout-ot)
       (is (= checkout-ot (host/resolve-ot (str home) (str plugin-root))) "checkout only")
       (executable! home-ot)
       (is (= home-ot (host/resolve-ot (str home) (str plugin-root))) "home first")
       (fs/set-posix-file-permissions home-ot "rw-r--r--")
       (is (= checkout-ot (host/resolve-ot (str home) (str plugin-root))) "a non-executable home ot is skipped")))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))

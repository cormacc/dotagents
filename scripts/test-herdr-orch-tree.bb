#!/usr/bin/env bb
;; Regression runner for the sidebar tree reconcile, `bb tree.bb reconcile|toggle` in
;; herdr/plugins/herdr-orch/. It runs the real entry point against the fake-herdr fixture
;; (HERDR_BIN_PATH), a fake Unix socket server that records requests (HERDR_SOCKET_PATH) and a
;; disposable state directory, over ledger fixtures written into disposable git repositories.
;; No real Herdr server and none of the user's ledgers are touched.

(require '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.test :refer [deftest is run-tests testing]])
(import '(java.net StandardProtocolFamily UnixDomainSocketAddress)
        '(java.nio.channels Channels FileChannel ServerSocketChannel)
        '(java.nio.file StandardOpenOption))

(def root (str/trim (:out (process/shell {:out :string} "git" "rev-parse" "--show-toplevel"))))
(def plugin (str (fs/path root "herdr" "plugins" "herdr-orch")))
(def fake-herdr (str (fs/path root "skills" "herdr-orch" "scripts" "test" "fixtures" "fake-herdr")))
(def bb (str (fs/which "bb")))

(def src-id "plugin:cormacc.herdr-orch")
(def view-set
  {:id "tree-agent.view.set" :method "agent.view.set"
   :params {:source src-id
            :sort [{:field "workspace_order" :order "asc"}
                   {:field {:token "order"} :order "asc"}
                   {:field "pane_order" :order "asc"}]}})
(def view-clear
  {:id "tree-agent.view.clear" :method "agent.view.clear" :params {:source src-id}})

;; --- fixtures -----------------------------------------------------------------------------

(defn- git-init! [dir]
  (fs/create-dirs dir)
  (process/shell {:dir (str dir) :out :string :err :string} "git" "init" "-q" (str dir)))

(defn- agent-row [pane cwd session]
  {:agent "pi" :pane_id pane :workspace_id "w1" :tab_id "w1:t1" :cwd (str cwd)
   :agent_session {:value session}})

(defn- write-ledger!
  "A ledger entry under `repo`; the child lives on `pane` with session `child`."
  [repo id pane child label started]
  (let [dir (fs/path repo ".tmp" "herdr-orch" "ledger")]
    (fs/create-dirs dir)
    (spit (str (fs/path dir (str id ".json")))
          (json/generate-string {:task id :parent-session "sP" :child-session {:value child}
                                 :pane-id pane :label label :started-at started}))))

(defn- start-socket!
  "Fake Herdr socket: records each request line, answers an agent_view envelope. Returns
  {:requests atom-of-parsed-requests :close fn}."
  [path]
  (let [srv      (doto (ServerSocketChannel/open StandardProtocolFamily/UNIX)
                   (.bind (UnixDomainSocketAddress/of (str path))))
        requests (atom [])
        thread   (future
                   (try
                     (loop []
                       (let [ch (.accept srv)]
                         (try (let [line (.readLine (java.io.BufferedReader. (java.io.InputStreamReader. (Channels/newInputStream ch) "UTF-8")))
                                    req  (json/parse-string line true)]
                                (swap! requests conj req)
                                (.write (Channels/newOutputStream ch)
                                        (.getBytes (str (json/generate-string
                                                         {:id (:id req) :result {:type "agent_view" :active (not= "agent.view.clear" (:method req))}})
                                                        "\n") "UTF-8")))
                              (finally (.close ch))))
                       (recur))
                     (catch java.nio.channels.ClosedChannelException _ nil)))]
    {:requests requests
     :close (fn [] (.close srv) (deref thread 2000 nil))}))

(defn with-env
  "Call (f env) with a disposable tree: repo (a git root), wt (a second git root), the socket
  server, state and log paths. `env` is the process environment overlay; helpers close over `ctx`."
  [f]
  ;; The socket lives under /tmp: the isolated test root's TMPDIR can exceed the Unix socket
  ;; path limit (104 bytes on macOS).
  (let [dir (str (fs/canonicalize (fs/create-temp-dir {:prefix "otree-"})))
        sock-dir (str (fs/canonicalize (fs/create-temp-dir {:dir (if (fs/directory? "/tmp") "/tmp" (fs/temp-dir)) :prefix "ots-"})))]
    (try
      (let [repo (fs/path dir "repo") wt (fs/path dir "wt")
            _ (git-init! repo) _ (git-init! wt)
            sock (str (fs/path sock-dir "s.sock"))
            {:keys [requests close]} (start-socket! sock)
            ctx {:dir dir :repo repo :wt wt :requests requests
                 :agents (str (fs/path dir "agents.json"))
                 :state (str (fs/path dir "state"))
                 :report-log (str (fs/path dir "report.log"))
                 :env {"HERDR_BIN_PATH" fake-herdr
                       "HERDR_SOCKET_PATH" sock
                       "HERDR_PLUGIN_STATE_DIR" (str (fs/path dir "state"))
                       "HERDR_SESSION" "tree-test"
                       "FAKE_HERDR_LOG" (str (fs/path dir "herdr.log"))
                       "FAKE_REPORT_LOG" (str (fs/path dir "report.log"))
                       "FAKE_AGENT_LIST_FILE" (str (fs/path dir "agents.json"))}}]
        (try (f ctx) (finally (close))))
      (finally (fs/delete-tree dir) (fs/delete-tree sock-dir)))))

(defn set-agents! [ctx agents]
  (spit (:agents ctx) (json/generate-string {:id "x" :result {:type "agent_list" :agents agents}})))

(defn tree-async!
  "Start `bb tree.bb cmd` in the plugin directory; the process, to deref for {:exit :err}."
  ([ctx cmd] (tree-async! ctx cmd {}))
  ([ctx cmd more]
   (process/process [bb "tree.bb" cmd] {:dir plugin :extra-env (merge (:env ctx) more) :out :string :err :string})))

(defn tree!
  "Run `bb tree.bb cmd` to completion; {:exit :err}. Extra env in `more`."
  ([ctx cmd] (tree! ctx cmd {}))
  ([ctx cmd more] (select-keys @(tree-async! ctx cmd more) [:exit :err])))

(defn reports
  "Logged `pane report-metadata` argv (after `pane report-metadata`), as vectors, in call order."
  [ctx]
  (let [f (:report-log ctx)]
    (if (fs/exists? f)
      (mapv #(vec (drop 2 (str/split % #"\037"))) (str/split-lines (slurp f)))
      [])))

(defn reports-after [ctx n] (vec (drop n (reports ctx))))
(defn requests-after [ctx n] (vec (drop n @(:requests ctx))))
(defn counts [ctx] [(count (reports ctx)) (count @(:requests ctx))])

(defn- set-argv [pane & tail] (into ["pane" "report-metadata" pane "--source" src-id] tail))

(defn- report [pane & tail] (vec (drop 2 (apply set-argv pane tail))))

(defn clear [pane] (report pane "--clear-display-agent" "--clear-token" "tree" "--clear-token" "order"))

(defn- same-calls?
  "Order-insensitive comparison: the reconcile's call order across panes is not part of the contract."
  [expected actual]
  (and (= (count expected) (count actual)) (= (sort expected) (sort actual))))

(defn- state [ctx] (json/parse-string (slurp (str (fs/path (:state ctx) "session-tree-test" "tree-state.json"))) true))

(defn- two-children!
  "Parent p1 (session sP, no entry) with children p2 and p3 in repo-or-wt directories."
  [ctx p2-dir]
  (write-ledger! p2-dir "t1" "w1:p2" "s1" "scout-1-light" "2026-01-01T00:00:01Z")
  (write-ledger! (:repo ctx) "t2" "w1:p3" "s2" "planner-2/worker-1" "2026-01-01T00:00:02Z")
  (set-agents! ctx [(agent-row "w1:p1" (:repo ctx) "sP") (agent-row "w1:p2" p2-dir "s1") (agent-row "w1:p3" (:repo ctx) "s2")]))

(def two-children-reports
  [(report "w1:p1" "--token" "order=0000")
   (report "w1:p2" "--display-agent" "scout-1-light" "--token" "tree=├" "--token" "order=0001")
   (report "w1:p3" "--display-agent" "worker-1" "--token" "tree=└" "--token" "order=0002")])

;; --- cases --------------------------------------------------------------------------------

(deftest parent-with-two-children-in-one-workspace
  (with-env
    (fn [ctx]
      (two-children! ctx (:repo ctx))
      (let [{:keys [exit err]} (tree! ctx "reconcile")]
        (is (zero? exit) err)
        (is (same-calls? two-children-reports (reports ctx)) (pr-str (reports ctx)))
        (is (= [view-set] @(:requests ctx)))
        (is (= {:p1 "0000" :p2 "0001" :p3 "0002"}
               (into {} (map (fn [[k v]] [(keyword (second (str/split (name k) #":"))) (:order v)])) (:panes (state ctx)))))))))

(deftest worktree-child-in-a-second-git-root
  (with-env
    (fn [ctx]
      ;; t1's ledger lives in the worktree checkout, not in the parent's repository.
      (two-children! ctx (:wt ctx))
      (is (not (fs/exists? (fs/path (:repo ctx) ".tmp" "herdr-orch" "ledger" "t1.json"))) "t1 is only in the worktree")
      (let [{:keys [exit err]} (tree! ctx "reconcile")]
        (is (zero? exit) err)
        (is (same-calls? two-children-reports (reports ctx)) (pr-str (reports ctx)))
        (is (= [view-set] @(:requests ctx)))))))

(deftest parent-absent-from-agent-list-reports-the-child-as-root
  (with-env
    (fn [ctx]
      (write-ledger! (:repo ctx) "t1" "w1:p2" "s1" "scout-1-light" "2026-01-01T00:00:01Z")
      (set-agents! ctx [(agent-row "w1:p2" (:repo ctx) "s1")])
      (let [{:keys [exit err]} (tree! ctx "reconcile")]
        (is (zero? exit) err)
        (is (= [(report "w1:p2" "--display-agent" "scout-1-light" "--clear-token" "tree" "--token" "order=0000")]
               (reports ctx)))
        (is (= [view-set] @(:requests ctx)))))))

(deftest pane-reused-by-a-different-session-is-cleared
  (with-env
    (fn [ctx]
      (write-ledger! (:repo ctx) "t1" "w1:p2" "s1" "scout-1-light" "2026-01-01T00:00:01Z")
      (set-agents! ctx [(agent-row "w1:p1" (:repo ctx) "sP") (agent-row "w1:p2" (:repo ctx) "s1")])
      (is (zero? (:exit (tree! ctx "reconcile"))))
      (is (same-calls? [(report "w1:p1" "--token" "order=0000")
                        (report "w1:p2" "--display-agent" "scout-1-light" "--token" "tree=└" "--token" "order=0001")]
                       (reports ctx)))
      (let [[r q] (counts ctx)]
        ;; Another program now runs in p2: the entry is no longer live, so the parent has no
        ;; live child either and both rows go.
        (set-agents! ctx [(agent-row "w1:p1" (:repo ctx) "sP") (agent-row "w1:p2" (:repo ctx) "someone-else")])
        (let [{:keys [exit err]} (tree! ctx "reconcile")]
          (is (zero? exit) err)
          (is (same-calls? [(clear "w1:p1") (clear "w1:p2")] (reports-after ctx r)) (pr-str (reports-after ctx r)))
          (is (empty? (requests-after ctx q)) "the view stays set; no further socket request")
          (is (empty? (:panes (state ctx)))))))))

(defn- child-closed!
  "Reconcile with p1-p3, then drop p3 from the agent list; the second reconcile runs with `more` env.
  Returns the second run's {:exit :err} plus the report count before it."
  [ctx more]
  (two-children! ctx (:repo ctx))
  (is (zero? (:exit (tree! ctx "reconcile"))))
  (let [[r _] (counts ctx)]
    (set-agents! ctx [(agent-row "w1:p1" (:repo ctx) "sP") (agent-row "w1:p2" (:repo ctx) "s1")])
    (assoc (tree! ctx "reconcile" more) :before r)))

(deftest clear-of-a-closed-pane-is-silent
  (with-env
    (fn [ctx]
      (let [{:keys [exit err before]} (child-closed! ctx {"FAKE_REPORT_GONE" "w1:p3"})]
        (is (zero? exit) err)
        (is (= "" err) "pane_not_found on a clear is not worth a log line")
        (is (same-calls? [(clear "w1:p3")
                          (report "w1:p2" "--display-agent" "scout-1-light" "--token" "tree=└" "--token" "order=0001")]
                         (reports-after ctx before))
            (pr-str (reports-after ctx before)))
        (is (= #{:w1:p1 :w1:p2} (set (keys (:panes (state ctx))))) "the closed pane is dropped from state")))))

(deftest other-clear-failure-still-warns
  (with-env
    (fn [ctx]
      (let [{:keys [err]} (child-closed! ctx {"FAKE_REPORT_FAIL" "w1:p3"})]
        (is (= 1 (count (str/split-lines err))) err)
        (is (str/includes? err "internal_error") err)))))

(deftest unchanged-second-run-sends-nothing
  (with-env
    (fn [ctx]
      (two-children! ctx (:repo ctx))
      (is (zero? (:exit (tree! ctx "reconcile"))))
      (let [[r q :as before] (counts ctx)]
        (is (= [3 1] before) "positive control: the first run reported and set the view")
        (let [{:keys [exit err]} (tree! ctx "reconcile")]
          (is (zero? exit) err)
          (is (empty? (reports-after ctx r)))
          (is (empty? (requests-after ctx q))))))))

(defn- display-file [ctx] (fs/path (:state ctx) "display.json"))
(defn- display [ctx] (json/parse-string (slurp (str (display-file ctx))) true))

(defn- other-session
  "Env for a second session sharing the plugin state directory."
  [ctx] (assoc (:env ctx) "HERDR_SESSION" "other"))

(deftest toggle-off-clears-and-sends-view-clear
  (with-env
    (fn [ctx]
      (two-children! ctx (:repo ctx))
      (is (zero? (:exit (tree! ctx "reconcile"))))
      (let [[r q] (counts ctx)
            {:keys [exit err]} (tree! ctx "toggle")]
        (is (zero? exit) err)
        (is (= {:enabled false} (display ctx)))
        (is (same-calls? [(clear "w1:p1") (clear "w1:p2") (clear "w1:p3")] (reports-after ctx r)) (pr-str (reports-after ctx r)))
        (is (= [view-clear] (requests-after ctx q)))
        (is (= {:view false :panes {}} (select-keys (state ctx) [:view :panes])))
        (is (empty? (filter #(str/starts-with? (str (fs/file-name %)) "display.json.") (fs/list-dir (:state ctx)))) "no temp file left")))))

(deftest reconcile-while-off-sends-nothing
  (with-env
    (fn [ctx]
      (two-children! ctx (:repo ctx))
      (is (zero? (:exit (tree! ctx "reconcile"))))
      (is (zero? (:exit (tree! ctx "toggle"))))
      (let [[r q :as before] (counts ctx)
            {:keys [exit err]} (tree! ctx "reconcile")]
        (is (= [6 2] before) "positive control: reconcile and toggle off both reported")
        (is (zero? exit) err)
        (is (empty? (reports-after ctx r)))
        (is (empty? (requests-after ctx q)))
        (testing "no agent list call and no ledger read when the state is empty"
          (let [hlog (str (fs/path (:dir ctx) "herdr.log"))
                n    (count (str/split-lines (slurp hlog)))]
            (is (zero? (:exit (tree! ctx "reconcile"))))
            (is (= n (count (str/split-lines (slurp hlog)))) "herdr was not called")))))))

(deftest reconcile-of-a-second-session-while-off-clears-its-panes
  (with-env
    (fn [ctx]
      (two-children! ctx (:repo ctx))
      ;; the second session records rows while the flag is still on
      (let [other (fn [cmd] (tree! (assoc ctx :env (other-session ctx)) cmd))]
        (is (zero? (:exit (other "reconcile"))))
        (is (zero? (:exit (tree! ctx "toggle"))) "session tree-test turns the display off")
        (let [[r q] (counts ctx)
              res   (other "reconcile")]
          (is (zero? (:exit res)) (:err res))
          (is (same-calls? [(clear "w1:p1") (clear "w1:p2") (clear "w1:p3")] (reports-after ctx r)) (pr-str (reports-after ctx r)))
          (is (= [view-clear] (requests-after ctx q)))
          (is (= {:view false :panes {}}
                 (select-keys (json/parse-string (slurp (str (fs/path (:state ctx) "session-other" "tree-state.json"))) true) [:view :panes]))))))))

(deftest toggle-on-rebuilds-rows-and-view-and-flag-survives
  (with-env
    (fn [ctx]
      (two-children! ctx (:repo ctx))
      (is (zero? (:exit (tree! ctx "toggle"))))
      (is (empty? (reports ctx)) "positive control: off with empty state sent nothing to clear")
      (is (= {:enabled false} (display ctx)))
      (let [[r q] (counts ctx)
            {:keys [exit err]} (tree! ctx "toggle")]
        (is (zero? exit) err)
        (is (= {:enabled true} (display ctx)))
        (is (same-calls? two-children-reports (reports-after ctx r)) (pr-str (reports-after ctx r)))
        (is (= [view-set] (requests-after ctx q)))))))

(deftest unreadable-display-file-is-an-error
  (with-env
    (fn [ctx]
      (two-children! ctx (:repo ctx))
      (fs/create-dirs (:state ctx))
      (spit (str (display-file ctx)) "{not json")
      (doseq [cmd ["reconcile" "toggle"]]
        (let [{:keys [exit err]} (tree! ctx cmd)]
          (is (= 1 exit) cmd)
          (is (= 1 (count (str/split-lines err))) err)
          (is (str/includes? err "unreadable display file") err)))
      (is (empty? (reports ctx)))
      (is (empty? @(:requests ctx))))))

(deftest toggle-off-with-a-failed-clear-exits-1
  (with-env
    (fn [ctx]
      (two-children! ctx (:repo ctx))
      (is (zero? (:exit (tree! ctx "reconcile"))))
      (testing "a failed report-metadata clear"
        (let [{:keys [exit err]} (tree! ctx "toggle" {"FAKE_REPORT_FAIL" "w1:p2"})]
          (is (= 1 exit) err)
          (is (str/includes? err "internal_error") err)
          (is (= {:enabled false} (display ctx)) "the flag still flips")
          (is (contains? (:panes (state ctx)) :w1:p2) "the failed pane stays recorded for a retry")))
      (testing "a failed agent.view.clear"
        (fs/delete-if-exists (display-file ctx))
        (let [{:keys [exit err]} (tree! ctx "toggle" {"HERDR_SOCKET_PATH" (str (fs/path (:dir ctx) "no.sock"))})]
          (is (= 1 exit) err)
          (is (= {:enabled false} (display ctx))))))))

(deftest two-toggles-while-a-session-lock-is-held-leave-the-flag-unchanged
  (with-env
    (fn [ctx]
      (two-children! ctx (:repo ctx))
      (is (zero? (:exit (tree! ctx "reconcile"))))
      (let [session (fs/path (:state ctx) "session-tree-test")
            ch      (FileChannel/open (fs/path session "tree.lock") (into-array StandardOpenOption [StandardOpenOption/CREATE StandardOpenOption/WRITE]))
            _       (.lock ch)
            t1      (tree-async! ctx "toggle")
            _       (Thread/sleep 1500)
            t2      (tree-async! ctx "toggle")
            _       (Thread/sleep 1500)]
        (.close ch) ; closing the channel releases the lock

        (let [r1 @t1 r2 @t2]
          (is (zero? (:exit r1)) (:err r1))
          (is (zero? (:exit r2)) (:err r2)))
        (is (not= {:enabled false} (when (fs/exists? (display-file ctx)) (display ctx)))
            "two flips restore the original (enabled) value")
        (is (= #{:w1:p1 :w1:p2 :w1:p3} (set (keys (:panes (state ctx)))))
            "the session still records its rows: no toggle reset a session whose flag is on")
        (testing "the dirty marker the toggles left is honoured by the next reconcile"
          (let [{:keys [exit err]} (tree! ctx "reconcile")]
            (is (zero? exit) err)
            (is (= #{:w1:p1 :w1:p2 :w1:p3} (set (keys (:panes (state ctx))))))
            (is (not (fs/exists? (fs/path session "tree.dirty"))))))))))

(deftest on-toggle-during-a-slow-off-reset-is-not-lost
  (with-env
    (fn [ctx]
      (two-children! ctx (:repo ctx))
      (is (zero? (:exit (tree! ctx "reconcile"))))
      (let [toggle (partial tree-async! ctx "toggle")
            q      (count @(:requests ctx))
            ;; off: three slow clears hold the session lock for about 3 s
            t1     (toggle {"FAKE_REPORT_SLEEP" "1"})
            _      (Thread/sleep 1200)
            ;; on: starts while the off toggle is still resetting
            t2     (toggle)
            r1     @t1
            r2     @t2]
        (is (zero? (:exit r1)) (:err r1))
        (is (zero? (:exit r2)) (:err r2))
        (is (= {:enabled true} (display ctx)))
        (is (= ["agent.view.clear" "agent.view.set"] (mapv :method (requests-after ctx q)))
            "positive control: the reset ran, then the tree was rebuilt")
        (is (= #{:w1:p1 :w1:p2 :w1:p3} (set (keys (:panes (state ctx))))))
        (is (true? (:view (state ctx))))
        (is (not (fs/exists? (fs/path (:state ctx) "session-tree-test" "tree.dirty"))))))))

(deftest toggle-creates-a-missing-state-directory
  (with-env
    (fn [ctx]
      (is (not (fs/exists? (:state ctx))) "positive control: no state directory yet")
      (let [{:keys [exit err]} (tree! ctx "toggle")]
        (is (zero? exit) err)
        (is (= {:enabled false} (display ctx)))))))

(deftest concurrent-reconciles-converge-and-release-the-lock
  (with-env
    (fn [ctx]
      (two-children! ctx (:repo ctx))
      (let [t0   (System/nanoTime)
            run  (fn [] (future (assoc (tree! ctx "reconcile" {"FAKE_REPORT_SLEEP" "0.6"})
                                       :ms (quot (- (System/nanoTime) t0) 1000000))))
            a    (run)
            _    (Thread/sleep 500)
            ;; p3 closes while the first run holds the lock and reports; the second run only marks dirty
            _    (set-agents! ctx [(agent-row "w1:p1" (:repo ctx) "sP") (agent-row "w1:p2" (:repo ctx) "s1")])
            b    (run)
            res-a @a, time-a (:ms res-a)
            res-b @b, time-b (:ms res-b)]
        (is (zero? (:exit res-a)) (:err res-a))
        (is (zero? (:exit res-b)) (:err res-b))
        (is (same-calls? (into two-children-reports
                               [(report "w1:p2" "--display-agent" "scout-1-light" "--token" "tree=└" "--token" "order=0001")
                                (clear "w1:p3")])
                         (reports ctx))
            (pr-str (reports ctx)))
        (is (= [view-set view-set] @(:requests ctx)) "the second pass re-sent the view")
        (is (= #{:w1:p1 :w1:p2} (set (keys (:panes (state ctx))))) "the change made while the first run held the lock is applied")
        (is (> time-a 1500) "the first run held the lock across its slow reports")
        (is (<= time-b time-a) "the second run did not wait for the lock")
        (testing "the lock is released and no dirty marker remains"
          (let [session (fs/path (:state ctx) "session-tree-test")
                ch (FileChannel/open (fs/path session "tree.lock") (into-array StandardOpenOption [StandardOpenOption/WRITE]))
                lock (.tryLock ch)]
            (is (some? lock))
            (.close ch)
            (is (not (fs/exists? (fs/path session "tree.dirty"))))))))))

(deftest nested-mark-uses-braille-blank-lead
  (with-env
    (fn [ctx]
      ;; g1 is a grandchild under c1, the last (only) child of the entry-less orchestrator sP.
      (write-ledger! (:repo ctx) "c1" "w1:p2" "s1" "planner-1" "2026-01-01T00:00:01Z")
      (spit (str (fs/path (:repo ctx) ".tmp" "herdr-orch" "ledger" "g1.json"))
            (json/generate-string {:task "g1" :parent-session "s1" :child-session {:value "s3"}
                                   :pane-id "w1:p3" :label "planner-1/scout-1" :started-at "2026-01-01T00:00:02Z"}))
      (set-agents! ctx [(agent-row "w1:p1" (:repo ctx) "sP") (agent-row "w1:p2" (:repo ctx) "s1") (agent-row "w1:p3" (:repo ctx) "s3")])
      (let [{:keys [exit err]} (tree! ctx "reconcile")]
        (is (zero? exit) err)
        (is (same-calls? [(report "w1:p1" "--token" "order=0000")
                          (report "w1:p2" "--display-agent" "planner-1" "--token" "tree=└" "--token" "order=0001")
                          (report "w1:p3" "--display-agent" "scout-1" "--token" "tree=⠀ └" "--token" "order=0002")]
                         (reports ctx))
            (pr-str (reports ctx)))))))

(deftest entry-without-session-on-a-reused-pane-gets-no-report
  (with-env
    (fn [ctx]
      (spit (str (fs/path (:repo ctx) ".tmp" "herdr-orch" "ledger" "old.json"))
            (do (fs/create-dirs (fs/path (:repo ctx) ".tmp" "herdr-orch" "ledger"))
                (json/generate-string {:task "old" :parent-session "sOld" :child-session nil
                                       :pane-id "w1:p9" :label "worker-7-heavy" :started-at "2026-08-06T00:00:00Z"})))
      (set-agents! ctx [(agent-row "w1:p9" (:repo ctx) "sUserOwnPi")])
      (let [{:keys [exit err]} (tree! ctx "reconcile")]
        (is (zero? exit) err)
        (is (empty? (reports ctx)))
        (is (empty? @(:requests ctx)))))))

(deftest view-is-resent-with-a-later-report
  (with-env
    (fn [ctx]
      (two-children! ctx (:repo ctx))
      (is (zero? (:exit (tree! ctx "reconcile"))))
      (is (= [view-set] @(:requests ctx)) "positive control: the first run set the view")
      (let [q (count @(:requests ctx))]
        ;; a new agent changes the rows: the pass reports, so it sets the view again.
        (spit (str (fs/path (:repo ctx) ".tmp" "herdr-orch" "ledger" "t3.json"))
              (json/generate-string {:task "t3" :parent-session "sP" :child-session {:value "s4"}
                                     :pane-id "w1:p4" :label "scout-3" :started-at "2026-01-01T00:00:03Z"}))
        (set-agents! ctx [(agent-row "w1:p1" (:repo ctx) "sP") (agent-row "w1:p2" (:repo ctx) "s1")
                          (agent-row "w1:p3" (:repo ctx) "s2") (agent-row "w1:p4" (:repo ctx) "s4")])
        (is (zero? (:exit (tree! ctx "reconcile"))))
        (is (= [view-set] (requests-after ctx q)))))))

(deftest git-launch-failure-warns-once
  (with-env
    (fn [ctx]
      (let [bin (str (fs/path (:dir ctx) "nogit"))]
        (fs/create-dirs bin)
        (doseq [c ["bash" "env" "cat"]] (fs/create-sym-link (fs/path bin c) (fs/which c)))
        (set-agents! ctx [(agent-row "w1:p1" (:repo ctx) "sP") (agent-row "w1:p2" (:repo ctx) "s1")])
        (let [{:keys [exit err]} (tree! ctx "reconcile" {"PATH" bin})]
          (is (zero? exit) err)
          (is (= 1 (count (re-seq #"cannot launch git" err))) err))))))

(deftest plugin-unit-tests
  ;; the plugin's own namespace tests (herdr/plugins/herdr-orch/test), with the plugin as cwd
  (let [{:keys [exit out err]} @(process/process [bb "-e" "(require 'clojure.test 'orch-tree-test) (let [{:keys [fail error]} (clojure.test/run-tests 'orch-tree-test)] (System/exit (if (zero? (+ fail error)) 0 1)))"]
                                                 {:dir plugin :out :string :err :string})]
    (print out) (flush)
    (is (zero? exit) (str out err))
    (is (re-find #"Ran [1-9]\d* tests" out) "positive control: the plugin tests ran")))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))

(ns org-tasks.embedding-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [charm.message :as msg]
            [charm.program :as program]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [org-tasks.loader :as loader]
            [org-tasks.root :as root]
            [org-tasks.tui :as tui]
            [org-tasks.tui.dispatch :as dispatch]
            [org-tasks.tui.embedding :as embedding]
            [org-tasks.tui.tasks :as tasks]))

(def parent-id "11111111-1111-4111-8111-111111111111")
(def child-id "22222222-2222-4222-8222-222222222222")
(def other-id "33333333-3333-4333-8333-333333333333")
(def fixture (json/parse-string
               (slurp "skills/org-tasks/scripts/test/fixtures/embedding/local.json") true))

(defn with-roots [f]
  (fs/create-dirs ".tmp")
  (let [dir (str (fs/canonicalize (fs/create-temp-dir {:dir ".tmp" :prefix "embedding-"})))
        a (str (fs/path dir "a"))
        b (str (fs/path dir "b"))]
    (try
      (doseq [[path title] [[a "Alpha"] [b "Beta"]]]
        (fs/create-dirs (fs/path path "nested"))
        (spit (str (fs/path path "TASKS.org"))
              (str "* Improvements\n\n** TODO " title "\n:PROPERTIES:\n:CUSTOM_ID: " parent-id "\n:END:\n"
                   "*** TODO Child\n:PROPERTIES:\n:CUSTOM_ID: " child-id "\n:END:\n"
                   "** TODO Other\n:PROPERTIES:\n:CUSTOM_ID: " other-id "\n:END:\n"))
        (spit (str (fs/path path "TASKS.local.org"))
              (str "#+SELECTED: " parent-id "\n")))
      (f {:dir dir :a a :b b
          :opts {:context-file (str (fs/path dir "context.json")) :instance "fixture-instance"}})
      (finally (fs/delete-tree dir)))))

(defn produce! [opts directory & [overrides]]
  (loader/atomic-write (:context-file opts)
                       (json/generate-string
                         (merge fixture {:directory directory :updatedAt (System/currentTimeMillis)} overrides))))

(defn initial [opts]
  (embedding/sync-context opts (assoc (tasks/initial-state {:rows []}) :color? false) true))

(defn tick [opts state]
  (first (tui/update-fn opts state {:type :org-tasks.tui/poll})))

(defn keypress [opts state key]
  (first (tui/update-fn opts state (msg/key-press key))))

(defn feed [opts state keys]
  (reduce #(keypress opts %1 %2) state keys))

(deftest fixture-and-validation-boundaries
  (with-roots
    (fn [{:keys [opts a]}]
      (doseq [availability ["local" "remote" "unavailable"]
              [age expected] [[-1001 :stale] [-1000 :valid] [0 :valid] [5000 :valid] [5001 :stale]]]
        (produce! opts a {:availability availability :updatedAt (- 10000 age)})
        (is (= (if (= :valid expected)
                 ({"local" :ready "remote" :remote "unavailable" :unavailable} availability)
                 expected)
               (:status (embedding/read-context opts 10000)))
            (str availability " age=" age)))
      (doseq [bad [{:schema "foreign"} {:instanceId "another-instance"} {:contextId ""}
                   {:mode "unknown"} {:availability "unknown"} {:updatedAt "10000"}
                   {:directory "relative"} {:directory nil} {:directory "/absent/project"}
                   {:hints "one"} {:hints ["ok" 1]}]]
        (produce! opts a (assoc bad :updatedAt (get bad :updatedAt 10000)))
        (is (= :invalid (:status (embedding/read-context opts 10000))) (pr-str bad)))
      (produce! opts "ssh://remote/looks-local" {:availability "remote" :updatedAt 10000})
      (is (= :remote (:status (embedding/read-context opts 10000))))
      (doseq [raw ["{" "null" "[]" "42"]]
        (spit (:context-file opts) raw)
        (is (= :invalid (:status (embedding/read-context opts)))))
      (fs/delete (:context-file opts))
      (is (= :unavailable (:status (embedding/read-context opts)))))))

(deftest resolves-nested-directory-and-switches-identities-with-shared-task-ids
  (with-roots
    (fn [{:keys [opts a b]}]
      (produce! opts (str (fs/path a "nested")))
      (let [s (initial opts)]
        (is (= a (get-in s [:action-opts :root])))
        (is (= "Alpha" (:summary (first (:tasks s)))))
        (produce! opts b {:contextId "project-b-2"})
        (let [b-state (tick opts s)]
          (is (= b (get-in b-state [:action-opts :root])))
          (is (= "Beta" (:summary (first (:tasks b-state)))))
          (is (= "project-b-2" (get-in b-state [:active-context :contextId])))
          (produce! opts a {:contextId "project-a-3"})
          (is (= "Alpha" (:summary (first (:tasks (tick opts b-state)))))))))))

(deftest all-create-forms-freeze-root-through-submit-and-cancel
  (with-roots
    (fn [{:keys [opts a b]}]
      (doseq [key ["n" "N"] end [:enter :escape]]
        (produce! opts a)
        (let [prompt (feed opts (initial opts) [key "X"])
              before-b (slurp (str b "/TASKS.org"))]
          (produce! opts b {:contextId "project-b-2" :mode "compact"})
          (let [pending (tick opts prompt)]
            (is (= a (get-in pending [:action-opts :root])))
            (is (= "expanded" (:mode pending)))
            (is (= "X" (get-in pending [:new-task :input])))
            (is (str/includes? (tui/view pending) "switch pending"))
            (let [after (keypress opts pending end)]
              (is (= b (get-in after [:action-opts :root])))
              (is (= "compact" (:mode after)))
              (is (nil? (:new-task after)))
              (is (= before-b (slurp (str b "/TASKS.org"))))
              (when (= end :enter)
                (is (str/includes? (slurp (str a "/TASKS.org")) "TODO X"))))))))))

(deftest all-move-picker-stages-defer-switch
  (with-roots
    (fn [{:keys [opts a b]}]
      (doseq [stage [:relation :parent :section :before :after]]
        (produce! opts a)
        (let [started (keypress opts (initial opts) "m")
              relation-index ({:parent 0 :section 1 :before 2 :after 3} stage)
              picker (if (= :relation stage) started
                         (keypress opts (nth (iterate #(keypress opts % :down) started) relation-index) :enter))]
          (produce! opts b {:contextId "project-b-2"})
          (let [pending (tick opts picker)]
            (is (= a (get-in pending [:action-opts :root])))
            (is (= (:move-task picker) (:move-task pending)))
            (is (str/includes? (tui/view pending) "switch pending"))
            (is (= b (get-in (keypress opts pending :escape) [:action-opts :root])))))))))

(deftest move-submit-and-remove-confirmation-write-only-original-root
  (with-roots
    (fn [{:keys [opts a b]}]
      (produce! opts a)
      (let [picker (feed opts (initial opts) ["m" :enter])
            before-b (slurp (str b "/TASKS.org"))]
        (produce! opts b {:contextId "project-b-2"})
        (let [after (keypress opts (tick opts picker) :enter)]
          (is (= b (get-in after [:action-opts :root])))
          (is (= before-b (slurp (str b "/TASKS.org"))))
          (is (= other-id (:parentId (first (filter #(= parent-id (:id %))
                                                        (:rows (tasks/load-tasks {:root a})))))))))
      (produce! opts b)
      (let [armed (feed opts (initial opts) [:enter :down "D"])
            before-a (slurp (str a "/TASKS.org"))]
        (is (= child-id (get-in armed [:remove-confirmation :task-id])))
        (produce! opts a {:contextId "project-a-3"})
        (let [pending (tick opts armed)]
          (is (some? (:remove-confirmation pending)))
          (is (str/includes? (tui/view pending) "switch pending"))
          (let [after (keypress opts pending "D")]
            (is (= a (get-in after [:action-opts :root])))
            (is (= before-a (slurp (str a "/TASKS.org"))))
            (is (not (str/includes? (slurp (str b "/TASKS.org")) child-id)))))))))

(deftest removal-cancellation-applies-pending-root
  (with-roots
    (fn [{:keys [opts a b]}]
      (produce! opts a)
      (let [armed (feed opts (initial opts) [:enter :down "D"])]
        (produce! opts b {:contextId "project-b-2"})
        (let [[after command] (tui/update-fn opts (tick opts armed) (msg/key-press :escape))]
          (is (nil? command))
          (is (= b (get-in after [:action-opts :root])))
          (is (nil? (:remove-confirmation after)))
          (is (str/includes? (slurp (str a "/TASKS.org")) child-id)))))))

(deftest failed-context-and-producer-loss-disable-mutations-in-open-forms
  (with-roots
    (fn [{:keys [opts a b]}]
      (doseq [failure [:missing :malformed :stale :remote :unavailable :wrong-instance]]
        (produce! opts a)
        (let [prompt (feed opts (initial opts) ["n" "X"])
              before (mapv #(slurp (str % "/TASKS.org")) [a b])]
          (case failure
            :missing (fs/delete (:context-file opts))
            :malformed (spit (:context-file opts) "{")
            :stale (produce! opts a {:updatedAt (- (System/currentTimeMillis) 6000)})
            :remote (produce! opts a {:availability "remote"})
            :unavailable (produce! opts a {:availability "unavailable"})
            :wrong-instance (produce! opts a {:instanceId "other"}))
          (let [after (keypress opts prompt :enter)]
            (is (not= :ready (:context-status after)))
            (is (some? (:new-task after)))
            (is (some? (:context-message after)))
            (is (= before (mapv #(slurp (str % "/TASKS.org")) [a b])))
            (let [cancelled (keypress opts after :escape)]
              (is (nil? (:new-task cancelled)))
              (is (not (str/includes? (tui/view cancelled) "Alpha")))
              (is (= cancelled (keypress opts cancelled "n"))))))))))

(deftest effect-guards-recheck-lease-and-identity-and-compact-passivity
  (with-roots
    (fn [{:keys [opts a]}]
      (produce! opts a)
      (let [s (initial opts)
            calls (atom [])
            effect (get-in dispatch/nexus [:nexus/effects :command/run])]
        (doseq [overrides [{:updatedAt 1} {:instanceId "other"} {:contextId "unobserved-switch"}]]
          (produce! opts a overrides)
          (let [store (atom s)]
            (with-redefs [tasks/call-command (fn [& args]
                                             (swap! calls conj args)
                                             {:exit 1 :envelope {:error {:message "spy"}}})]
              (effect {:dispatch (fn [_]) :dispatch-data {:opts opts}} {:!store store}
                      :select {:id parent-id} "Selected"))
            (is (str/includes? (or (:message @store) "") "disabled"))))
        (produce! opts a {:mode "compact"})
        (let [compact (initial opts)]
          (doseq [k ["n" "N" "s" "D" "A" "P" "U" "p" "e" "m" :right]]
            (is (= compact (keypress opts compact k))))
          (is (false? (boolean (embedding/command-allowed? opts compact)))))
        (is (empty? @calls))))))

(deftest superseded-refresh-never-installs-old-graph
  (with-roots
    (fn [{:keys [opts a b]}]
      (produce! opts a)
      (let [load tasks/load-tasks
            stale (with-redefs [tasks/load-tasks
                               (fn [task-opts]
                                 (let [result (load task-opts)]
                                   (produce! opts b {:contextId "project-b-2"})
                                   result))]
                    (initial opts))]
        (is (empty? (:tasks stale)))
        (is (= :switching (:context-status stale)))
        (let [fresh (tick opts stale)]
          (is (= "project-b-2" (get-in fresh [:active-context :contextId])))
          (is (= "Beta" (:summary (first (:tasks fresh))))))))))

(deftest external-task-selection-and-import-edits-refresh-without-reopening
  (with-roots
    (fn [{:keys [opts a]}]
      (spit (str a "/plan.org")
            (str "* Plan\n** TODO Imported\n:PROPERTIES:\n:CUSTOM_ID: " other-id "\n:END:\n"))
      (spit (str a "/TASKS.org")
            (str "* TODO Alpha\n:PROPERTIES:\n:CUSTOM_ID: " parent-id
                 "\n:END:\n#+IMPORT: [[file:plan.org]]\n"))
      (produce! opts a)
      (let [s (initial opts)]
        (is (= ["Alpha" "Imported"] (mapv :summary (:tasks s))))
        (spit (str a "/plan.org") (str/replace (slurp (str a "/plan.org")) "Imported" "Changed"))
        (spit (str a "/TASKS.local.org") (str "#+SELECTED: " other-id "\n"))
        (let [after (tick opts (assoc s :details-scroll 5))]
          (is (= 5 (:details-scroll after)))
          (is (= ["Alpha" "Changed"] (mapv :summary (:tasks after))))
          (is (= other-id (:selected-id after)))
          (spit (str a "/TASKS.org") "* Improvements\n")
          (let [empty (tick opts (assoc after :cursor 99 :tree-scroll 99))]
            (is (empty? (:tasks empty)))
            (is (= 0 (:cursor empty)))
            (is (= 0 (:tree-scroll empty)))))
        (let [standalone (tasks/initial-state (tasks/load-tasks {:root a}))]
          (spit (str a "/TASKS.local.org") "#+SELECTED:\n")
          (is (nil? (:selected-id (tick {:root a} standalone)))))))))

(deftest missing-memory-is-explicit-and-never-initialised
  (with-roots
    (fn [{:keys [opts dir]}]
      (produce! opts dir)
      ;; A scratch root inside this checkout has TASKS.org above it. Isolate
      ;; the resolver result to exercise the missing-memory consumer branch.
      (with-redefs [root/resolve-root (fn [selectors directory]
                                      (is (= {} selectors))
                                      (is (= dir directory))
                                      dir)]
        (let [s (initial opts)]
          (is (= :missing-memory (:context-status s)))
          (is (str/includes? (tui/view s) "No task memory"))
          (is (= s (keypress opts s "n")))
          (is (not (fs/exists? (str dir "/TASKS.org")))))))))

(deftest remote-context-never-enters-local-root-resolution
  (with-roots
    (fn [{:keys [opts a]}]
      (produce! opts a {:availability "remote"})
      (with-redefs [root/resolve-root (fn [& _] (throw (ex-info "local resolver called" {})))]
        (let [state (initial opts)]
          (is (= :remote (:context-status state)))
          (is (= "Remote context unsupported\nTask mutations disabled" (tui/view state))))))))

(deftest original-root-loss-during-form-cannot-create-task-memory
  (with-roots
    (fn [{:keys [opts a b]}]
      (produce! opts a)
      (let [prompt (feed opts (initial opts) ["n" "X"])]
        (produce! opts b {:contextId "project-b-2"})
        (fs/delete (str a "/TASKS.org"))
        (let [pending (tick opts prompt)]
          (is (false? (boolean (embedding/command-allowed? opts pending))))
          (keypress opts pending :enter)
          (is (not (fs/exists? (str a "/TASKS.org")))))))))

(deftest terminal-cleanup-poll-startup-and-stdout-contract
  (with-roots
    (fn [{:keys [opts a]}]
      (produce! opts a)
      (let [original System/out
            startup (atom nil)
            output (with-out-str
                     (with-redefs [program/run (fn [options]
                                                 (reset! startup ((:init options)))
                                                 (first @startup))]
                       (tui/run! {:root a :no-color true})))
            response (json/parse-string output true)]
        (is (true? (:ok response)))
        (is (= parent-id (get-in response [:result :task :id])))
        (is (= :cmd (get-in @startup [1 :type])))
        (is (identical? original System/out))
        (is (= "" (with-out-str
                     (with-redefs [program/run (fn [_] {})]
                       (tui/run! opts)))))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"terminal failure"
                             (with-redefs [program/run (fn [_] (throw (ex-info "terminal failure" {})))]
                               (tui/run! opts))))
        (is (identical? original System/out))))))

(deftest compact-host-hints-close-the-view
  (with-roots
    (fn [{:keys [opts a]}]
      (spit (str (fs/path a "TASKS.local.org")) "#+SELECTED:\n")
      ;; 43 columns centred in 51 leave 4 columns of padding.
      (let [hints ["ctrl+b t: select / ctrl+b ctrl+o: show/hide"]
            footer (str "    " (first hints))
            view #(tui/view (assoc (initial opts) :width 51 :height 4))]
        (produce! opts a {:hints hints :mode "compact"})
        (is (= :ready (:status (embedding/read-context opts))) "hints are a valid additive field")
        (is (= (str/join "\n" ["3 tasks: 3 TODO" "" "" footer]) (view)))
        (produce! opts a {:mode "compact"})
        (is (= "3 tasks: 3 TODO" (view)) "without hints only the count line shows")
        (spit (str (fs/path a "TASKS.local.org")) (str "#+SELECTED: " parent-id "\n"))
        (produce! opts a {:mode "compact"})
        (let [selection (str/split-lines (view))]
          (is (str/includes? (first selection) "Alpha"))
          (produce! opts a {:hints hints :mode "compact"})
          (let [lines (str/split-lines (view))]
            (is (= 4 (count lines)))
            (is (= footer (last lines)) "a selection keeps the hints on the last row")
            (is (= (take 3 (concat selection (repeat ""))) (butlast lines))
                "the selection, then blank rows, fill the rows above")))))))

(deftest compact-empty-stale-and-small-geometry
  (let [empty (assoc (tasks/initial-state {:rows []}) :mode "compact" :color? false)]
    (is (= "0 tasks" (tui/view empty)))
    (is (= "Stale selection: missing" (tui/view (assoc empty :selected-id "missing")))))
  (with-roots
    (fn [{:keys [opts a]}]
      (produce! opts a)
      (doseq [[width height] [[1 1] [4 2] [10 3] [30 5] [60 8]]]
        (let [s (assoc (initial opts) :width width :height height)
              moved (keypress opts s :down)
              rendered (tui/view moved)]
          (is (= other-id (tasks/cursor-id moved)))
          (is (not (str/blank? rendered)))
          (is (<= (count (str/split-lines rendered)) height))
          (is (every? #(<= (count %) width) (str/split-lines rendered)))
          (when (and (>= width 10) (< height 7))
            (is (str/includes? rendered "Other"))))))))

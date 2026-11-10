(ns org-tasks.tui-test
  (:require [babashka.fs :as fs]
            [charm.message :as msg]
            [charm.program :as program]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org-tasks.commands.test-util :as test-util]
            [org-tasks.styling :as styling]
            [org-tasks.tui :as tui]
            [org-tasks.tui.tasks :as tasks]
            [org-tasks.tui.dispatch :as dispatch]))

(def sample-result
  {:selectedId "b"
   :rows [{:id "a" :summary "Parent" :status "TODO" :level 1}
          {:id "b" :summary "Child" :status "STARTED" :level 2 :parentId "a" :description "body"}
          {:id "c" :summary "Sibling" :status "DONE" :level 1 :local true
           :linkedIssues ["[[jira:ABC-1]]"]}]})

(def removal-parent-id "11111111-1111-4111-8111-111111111111")
(def removal-child-id "22222222-2222-4222-8222-222222222222")
(def removal-other-child-id "33333333-3333-4333-8333-333333333333")
(def removal-referrer-id "44444444-4444-4444-8444-444444444444")

(defn- removal-task-block [level summary id & [body]]
  (str (apply str (repeat level "*")) " TODO " summary "\n"
       ":PROPERTIES:\n:CUSTOM_ID: " id "\n:END:\n"
       (or body "")))

(defn- bootstrap-removal-tui-graph! [root]
  (spit (str root "/TASKS.setup.org") test-util/setup-org-preamble)
  (spit (str root "/TASKS.org")
        (str test-util/tasks-org-preamble
             "* Improvements\n"
             (removal-task-block 2 "Parent" removal-parent-id)
             (removal-task-block 3 "Remove me" removal-child-id "- [ ] finish removal\n")
             (removal-task-block 3 "Other child" removal-other-child-id)
             (removal-task-block 2 "Referrer" removal-referrer-id
                                 (str ":PROPERTIES:\n:BLOCKED-BY: task:"
                                      removal-child-id "\n:END:\n"))))
  (spit (str root "/TASKS.local.org") "#+SELECTED:\n"))

(defn- removal-tui-state [opts]
  (tasks/initial-state (tasks/load-tasks opts)))

(deftest compact-view-keeps-selection-and-pending-work-before-history
  (let [state (assoc (tasks/initial-state
                      {:selectedId "b"
                       :rows [{:id "a" :summary "Parent" :status "TODO"}
                              {:id "b" :parentId "a" :summary "Selected" :status "STARTED"
                               :importPath "plan:work.org"}
                              {:id "c" :parentId "b" :summary "Finished" :status "DONE"}
                              {:id "d" :parentId "b" :summary "Pending" :status "TODO"}]})
                     :mode "compact" :width 60 :height 4 :color? false)
        rendered (tui/view state)]
    (is (str/includes? rendered "Selected"))
    (is (str/includes? rendered "Parent"))
    (is (not (str/includes? rendered "work.org")) "the plan is in the window title, not a row")
    (is (str/includes? rendered "Pending"))
    (is (not (str/includes? rendered "Finished")))
    (is (<= (count (str/split-lines rendered)) 4))
    (is (= [] (tui/msg->actions state (msg/key-press "n"))))))

(deftest compact-progress-and-geometry
  (let [home (System/getProperty "user.home")
        result {:selectedId "b" :files {:tasks (str home "/project/TASKS.org")}
                :tree [{:id "a" :importChildren [{:id "b" :status "STARTED"
                                                   :children [{:id "c" :status "DONE"}
                                                              {:id "d" :status "TODO"}]}
                                                  {:id "e" :status "TODO"}]}]
                :rows [{:id "a" :summary "Parent" :status "TODO" :sourcePath "/tasks"
                        :importPath "plan:work.org"}
                       {:id "b" :parentId "a" :summary "Selected" :status "STARTED" :sourcePath "/plan"}
                       {:id "c" :parentId "b" :summary "Done" :status "DONE" :sourcePath "/plan"}
                       {:id "d" :parentId "b" :summary "Pending" :status "TODO" :sourcePath "/plan"}
                       {:id "e" :parentId "a" :summary "Other" :status "TODO" :sourcePath "/plan"}
                       {:id "f" :parentId "a" :summary "Local child" :status "CANCELLED" :sourcePath "/tasks"}]}
        state (assoc (tasks/initial-state result) :mode "compact" :width 80 :height 4 :color? false)
        lines (str/split-lines (tui/view state))]
    (testing "with nothing started, the task line carries the right-justified count"
      (is (str/starts-with? (first lines) "STARTED Selected [--------] "))
      (is (str/ends-with? (first lines) " 1/2"))
      (is (= 80 (count (first lines))))
      (is (not-any? #(str/includes? % "TASKS.org") lines))
      (is (some #(= "... TODO Pending [--------]" %) lines) "no ellipsis without hidden pending work")
      (is (not-any? #(str/includes? % "Done") lines)))
    ;; The status prefix takes 8 columns before the summary.
    (let [narrow (str/split-lines (tui/view (assoc state :width 26)))]
      (is (str/includes? (first narrow) "Selected"))
      (is (every? #(<= (count %) 26) narrow)))
    (let [empty-state (assoc (tasks/initial-state (assoc result :rows [(second (:rows result))]))
                             :mode "compact" :width 80 :height 4 :color? false)]
      (is (str/ends-with? (tui/view empty-state) " 0/0")))))

(deftest compact-shows-every-started-task-then-the-next
  (let [owner {:id "a" :summary "Owner" :status "STARTED" :importPath "plan:p.org"}
        queued {:id "b" :parentId "a" :summary "Queued" :status "TODO"}
        active {:id "c" :parentId "a" :summary "Active" :status "STARTED"}
        parallel {:id "d" :parentId "a" :summary "Parallel" :status "STARTED"}
        waiting {:id "e" :parentId "a" :summary "Later" :status "WAITING"}
        finished {:id "f" :parentId "a" :summary "Finished" :status "DONE"}
        local {:id "g" :parentId "a" :summary "Local" :status "STARTED"}
        plan [queued active parallel waiting finished]
        result {:selectedId "a" :rows (into [owner] (conj plan local))
                :tree [(assoc owner :importChildren plan :children [local])]}
        render (fn [r color?]
                 (str/split-lines (tui/view (assoc (tasks/initial-state r) :mode "compact"
                                                   :width 60 :height 8 :color? color?))))
        strong? (fn [line text] (str/includes? line (str "\u001b[0m " text)))
        ;; JLine emits theme colour 8 as SGR 90 (bright black).
        muted "\u001b[90m"
        status (str "38;5;" (get styling/palette-256 :blue) "m")]
    (testing "layout"
      (let [lines (render result false)]
        (is (= ["STARTED Owner [--------]"
                "... STARTED Active [--------]"
                "... STARTED Parallel [--------]"
                "... STARTED Local [--------]"
                "... TODO Queued [--------]"]
               (map #(first (str/split % #"\s{2,}")) lines)))
        (is (str/ends-with? (nth lines 1) " 1/6") "the first started line carries the count")
        (is (not-any? #(str/ends-with? % "1/6") (cons (first lines) (drop 2 lines))))
        (is (str/ends-with? (last lines) " ...") "hidden pending work adds an ellipsis")
        (is (not-any? #(str/includes? % "Later") lines))
        (is (not-any? #(str/includes? % "Finished") lines))))
    (testing "every started descendant is current, but not the started selection"
      (let [lines (render result true)]
        (is (strong? (nth lines 1) "Active"))
        (is (strong? (nth lines 2) "Parallel"))
        (is (str/includes? (nth lines 1) status))
        (is (str/starts-with? (nth lines 1) (str muted "... ")))
        (is (not (strong? (first lines) "Owner")))
        (is (str/includes? (first lines) muted))
        (is (strong? (nth lines 3) "Local") "a started child outside the plan is current too")
        (is (not (strong? (nth lines 4) "Queued")))))
    (testing "without a started descendant the selected task is current"
      (let [lines (render (assoc result :rows [owner queued]
                                 :tree [(assoc owner :importChildren [queued] :children [])])
                          true)]
        (is (strong? (first lines) "Owner"))
        (is (not-any? #(strong? % "Queued") lines))))))

(deftest compact-without-selection-counts-tasks-by-status
  (let [rows [{:id "a" :status "TODO"} {:id "b" :status "TODO"} {:id "c" :status "STARTED"}
              {:id "d" :status "DONE"} {:id "e" :status "WAITING"} {:id "f" :status "CANCELLED"}]
        state (assoc (tasks/initial-state {:rows rows}) :mode "compact" :width 80 :height 5
                     :hints ["ctrl+b t: select"])]
    (is (= ["6 tasks: 2 TODO / 1 STARTED / 1 WAITING / 1 DONE / 1 CANCELLED" "" "" ""
            (str (apply str (repeat 32 " ")) "ctrl+b t: select")]
           (str/split-lines (tui/view (assoc state :color? false))))
        "the hint is centred on the last row")
    (is (str/ends-with? (tui/view state) "\u001b[90mctrl+b t: select\u001b[0m")
        "the hint is muted")
    (is (= ["ctrl+b t: select"] (str/split-lines (tui/view (assoc state :color? false :width 16 :height 1))))
        "the hint keeps the only row")
    (is (str/includes? (tui/view state) (str "38;5;" (get styling/palette-256 :blue) "mSTARTED"))
        "each status keeps its palette colour")))

(deftest window-title-names-folder-and-plan
  (let [home (System/getProperty "user.home")
        owner {:id "a" :summary "Owner" :status "TODO" :importPath "plan:2026-01-01-x.org"}
        child {:id "b" :parentId "a" :summary "Child" :status "TODO"}
        result {:selectedId "b" :files {:tasks (str home "/project/TASKS.org")} :rows [owner child]}
        title #(tui/window-title (tasks/initial-state %))]
    (is (= "Tasks :: ~/project :: 2026-01-01-x.org" (title result)) "a descendant shows its ancestor's plan")
    (is (= "Tasks :: ~/project :: plan.org"
           (title (assoc result :rows [(assoc owner :importPath "file:design/plan.org") child]))))
    (is (= "Tasks :: ~/project" (title (assoc result :rows [(dissoc owner :importPath) child]))))
    (is (= "Tasks :: ~/project" (title (assoc result :selectedId nil))))
    (is (= (str "Tasks :: " home "-other :: 2026-01-01-x.org")
           (title (assoc-in result [:files :tasks] (str home "-other/TASKS.org")))))
    (is (= "Tasks" (title (dissoc result :files :selectedId))))))

(deftest title-changes-reach-the-terminal-once-as-osc-2
  (let [bytes (java.io.ByteArrayOutputStream.)
        out (java.io.PrintStream. bytes true "UTF-8")
        {:keys [emit! update]} (#'tui/title-writer (fn [state m] [(merge state m) nil]) out)
        state (tasks/initial-state {:files {:tasks "/p/TASKS.org"} :rows []})]
    (emit! state)
    (is (= [state nil] (update state {})) "update results pass through")
    (update state {:task-memory-path "/q/TASKS.org"})
    (is (= "\u001b]2;Tasks :: /p\u0007\u001b]2;Tasks :: /q\u0007" (.toString bytes "UTF-8")))))

(deftest compact-counts-imported-tasks-from-the-same-source
  (let [owner {:id "a" :summary "Owner" :status "TODO" :sourcePath "/tasks" :importPath "file:TASKS.org"}
        child {:id "b" :summary "Imported" :status "DONE" :sourcePath "/tasks" :parentId "a"}
        result {:selectedId "a" :rows [owner child]
                :tree [(assoc owner :importChildren [child] :children [])]}
        state (assoc (tasks/initial-state result) :mode "compact" :width 80 :height 3 :color? false)]
    (is (str/ends-with? (first (str/split-lines (tui/view state))) " 1/1"))))

(deftest compact-renders-loaded-import-and-refresh
  (test-util/with-temp-dir
    (fn [root]
      (spit (str root "/TASKS.org")
            (str "* TODO Parent\n:PROPERTIES:\n:CUSTOM_ID: " removal-parent-id
                 "\n:END:\n#+IMPORT: [[file:plan.org]]\n"))
      (spit (str root "/TASKS.local.org") (str "#+SELECTED: " removal-parent-id "\n"))
      (spit (str root "/plan.org")
            (str "* Plan\n** DONE Done\n:PROPERTIES:\n:CUSTOM_ID: " removal-child-id
                 "\n:END:\n** TODO Pending\n:PROPERTIES:\n:CUSTOM_ID: " removal-other-child-id "\n:END:\n"))
      (let [opts {:root root}
            state (assoc (tasks/initial-state (tasks/load-tasks opts)) :mode "compact" :width 180 :height 4 :color? false)
            lines (str/split-lines (tui/view state))]
        (is (str/ends-with? (first lines) " 1/2"))
        (is (str/ends-with? (tui/window-title state) " :: plan.org"))
        (is (some #(str/includes? % "Pending") lines))
        (spit (str root "/plan.org") (str/replace (slurp (str root "/plan.org")) "TODO Pending" "DONE Pending"))
        (let [refreshed (tasks/reload-state opts state)]
          (is (str/ends-with? (first (str/split-lines (tui/view refreshed))) " 2/2"))
          (with-redefs [tasks/load-tasks (let [real tasks/load-tasks]
                                           (fn [o] (assoc-in (real o) [:files :tasks] "/new/TASKS.org")))]
            (is (str/starts-with? (tui/window-title (tasks/reload-state opts refreshed))
                                  "Tasks :: /new :: "))))))))

(deftest move-picker-filters-owner-and-subtree
  (let [rows [{:id "a" :sourcePath "/tasks"}
              {:id "b" :parentId "a" :sourcePath "/tasks"}
              {:id "c" :parentId "b" :sourcePath "/tasks"}
              {:id "d" :sourcePath "/tasks"}
              {:id "e" :parentId "a" :sourcePath "/plan"}]]
    (is (= ["d"] (mapv :id (tasks/move-candidates rows (first rows)))))
    (is (= ["a" "b" "d"] (mapv :id (tasks/move-candidates rows (nth rows 2)))))))

(deftest move-picker-cancel-and-render
  (let [update (fn [s key] (first (tui/update-fn {} s (msg/key-press key))))
        state (assoc (tasks/initial-state sample-result) :width 50 :height 12 :color? false)
        picker (update state "m")]
    (is (= :relation (get-in picker [:move-task :stage])))
    (is (str/includes? (tui/view picker) "Move: choose relation"))
    (is (every? #(<= (count %) 46) (str/split-lines (tui/view picker))))
    (is (nil? (:move-task (update picker :escape))))
    (is (= (:tasks state) (:tasks (update picker :escape))))
    (let [destination (update picker :enter)]
      (is (= :destination (get-in destination [:move-task :stage])))
      (is (str/includes? (tui/view destination) "No same-file destinations"))
      (is (= destination (update destination :enter)))
      (is (nil? (:move-task (update destination :escape)))))))

(deftest move-picker-dispatches-four-destinations
  (doseq [[mode idx] [[:parent 0] [:section 1] [:before 2] [:after 3]]]
    (test-util/with-temp-dir
      (fn [root]
        (let [path (str root "/TASKS.org")
              content (str "* Improvements\n\n"
                           (removal-task-block 2 "Source" removal-child-id) "\n"
                           (removal-task-block 2 "Destination" removal-parent-id) "\n"
                           "* Later\n")
              _ (spit path content)
              _ (spit (str root "/TASKS.local.org") "#+SELECTED:\n")
              opts {:root root}
              update (fn [s key] (first (tui/update-fn opts s (msg/key-press key))))
              state (tasks/initial-state (tasks/load-tasks opts))
              picker (nth (iterate #(update % :down) (update state "m")) idx)
              destination (update picker :enter)
              entered (if (= :section mode)
                        (reduce #(update %1 (str %2)) destination "Later")
                        destination)
              cancelled (update entered :escape)]
          (is (nil? (:move-task cancelled)))
          (is (= content (slurp path)))
          (let [result (update entered :enter)
                rows (:tasks result)
                by-id (into {} (map (juxt :id identity)) rows)]
            (is (nil? (:move-task result)))
            (is (= "Task moved." (:message result)))
            (is (= removal-child-id (tasks/cursor-id result)))
            (if (= mode :parent)
              (is (= removal-parent-id (:parentId (get by-id removal-child-id))))
              (is (nil? (:parentId (get by-id removal-child-id)))))
            (is (= (if (#{:after :section :parent} mode)
                     [removal-parent-id removal-child-id]
                     [removal-child-id removal-parent-id])
                   (mapv :id rows)))))))))

(deftest scrollbar-tracks-the-viewport
  (testing "no bar when everything fits"
    (is (= (repeat 4 [nil " "]) (tui/scrollbar 4 4 0 4)))
    (is (= (repeat 4 [nil " "]) (tui/scrollbar 2 4 0 4))))
  (let [kinds #(mapv first (apply tui/scrollbar %&))]
    (is (= [:thumb :thumb :track :track] (kinds 8 4 0 4)) "top: half the content shown")
    (is (= [:track :track :thumb :thumb] (kinds 8 4 4 4)) "bottom")
    (is (= [:track :thumb :thumb :track] (kinds 8 4 2 4)) "middle")
    (is (= [:thumb :track :track :track] (kinds 100 4 0 4)) "a thumb is at least one cell")
    (is (= [:track :track :track :thumb] (kinds 100 4 500 4)) "offsets past the end clamp")))

(deftest panes-show-scrollbars-only-when-content-overflows
  (let [rows (vec (for [i (range 40)] {:id (str "id" i) :summary (str "Task " i) :status "TODO" :level 1}))
        render #(str/split-lines (tui/view (assoc (tasks/initial-state {:selectedId "id0" :rows %}) :width 80 :height 30 :color? false)))
        bar-rows (fn [lines] (count (filter #(re-find #"[┃│]$" %) lines)))]
    (is (pos? (bar-rows (render rows))) "40 tasks overflow the tree pane")
    (is (zero? (bar-rows (render (subvec rows 0 3)))) "control: 3 tasks fit, so no bar")
    (let [long (assoc-in rows [0 :description] (str/join "\n" (map #(str "line " %) (range 60))))
          lines (render (subvec long 0 3))]
      (is (pos? (bar-rows lines)) "long details overflow the details pane"))))

(deftest footer-hints-reflow-to-the-pane-width
  (doseq [width [200 100 60 40]]
    (let [state (assoc (tasks/initial-state sample-result) :width width :height 30 :color? false)
          content-w (tasks/content-width state)
          footer (tasks/footer-lines content-w)
          lines (str/split-lines (tui/view state))]
      (is (every? #(<= (count %) content-w) footer) (str width " columns"))
      (is (= (str/join "  " tasks/footer-hints) (str/join "  " footer)) "every hint, in order")
      (is (= footer (take-last (count footer) lines)) "the footer closes the view")
      (is (= 29 (count lines)) "panes shrink to make room")))
  (is (= 1 (count (tasks/footer-lines 1000))))
  (is (< 1 (count (tasks/footer-lines 76))) "control: an 80-column terminal needs more than one line"))

(deftest initial-state-expands-selected-path
  (let [state (tasks/initial-state sample-result)]
    (is (= "b" (:selected-id state)))
    (is (contains? (:expanded state) "a"))
    (is (= "b" (:id (tasks/cursor-task state))))))

(deftest navigation-collapse-and-scroll-are-pure
  (let [state (tasks/initial-state sample-result)
        update (fn [s key] (first (tui/update-fn {} s (msg/key-press key))))]
    (is (= "c" (:id (tasks/cursor-task (update state :down)))))
    (is (= "a" (:id (tasks/cursor-task (update state :up)))))
    (is (not (contains? (:expanded (update (assoc state :cursor 0) :enter)) "a")))
    (let [ctrl-d #(first (tui/update-fn {} % {:type :key-press :key "d" :ctrl true}))
          ctrl-u #(first (tui/update-fn {} % {:type :key-press :key "u" :ctrl true}))
          long-body (str/join "\n" (map #(str "line " %) (range 60)))
          long-state (assoc (tasks/initial-state (assoc-in sample-result [:rows 1 :description] long-body))
                            :width 100 :height 30)]
      (is (= 0 (:details-scroll (ctrl-d state))) "short details cannot scroll")
      (is (= 5 (:details-scroll (ctrl-d long-state))))
      (let [bottom (nth (iterate ctrl-d long-state) 30)]
        (is (< 0 (:details-scroll bottom) 150) "clamped to the last page")
        (is (= (- (:details-scroll bottom) 5) (:details-scroll (ctrl-u bottom)))
            "scrolling back moves at once")))
    (is (= 0 (:details-scroll (first (tui/update-fn {} state {:type :key-press :key "u" :ctrl true})))))))

(deftest rendering-shows-overlay-affordances
  (let [state (assoc (tasks/initial-state sample-result) :cursor 2)
        line (tui/task-line state (tasks/cursor-task state) 2)]
    (is (str/includes? line ">"))
    (is (str/includes? line "⊠"))
    (is (str/includes? line "J1")))
  (let [details (str/join "\n" (tui/detail-lines (second (:rows sample-result))))]
    (is (str/includes? details "STARTED"))
    (is (str/includes? details "body"))))

(deftest imported-plan-children-indent-by-tree-depth
  (testing "plan children (low org :level but parentId set) indent deeper than parent"
    ;; Regression: imported plan tasks keep their plan-file org :level (e.g. 2),
    ;; shallower than their TASKS.org parent (e.g. 4); indenting by :level put
    ;; them left of the parent. Indent by parentId-chain depth instead.
    (let [rows [{:id "p" :summary "Parent" :status "TODO" :level 4 :importPath "x"}
                {:id "c" :summary "Plan child" :status "TODO" :level 2 :parentId "p"}
                {:id "g" :summary "Grandchild" :status "TODO" :level 3 :parentId "c"}]
          state (assoc (tasks/initial-state {:selectedId nil :rows rows})
                       :color? false :expanded #{"p" "c"})
          indent-of (fn [id]
                      (let [vis (tasks/visible-tasks state)
                            idx (first (keep-indexed #(when (= id (:id %2)) %1) vis))
                            line (tui/task-line state (nth vis idx) idx)]
                        ;; leading spaces after the 1-char cursor marker column
                        (count (take-while #(= \space %) (subs line 1)))))]
      (is (= 1 (get-in state [:depths "c"])))
      (is (= 2 (get-in state [:depths "g"])))
      (is (< (indent-of "p") (indent-of "c") (indent-of "g"))))))

(deftest side-by-side-view-uses-terminal-height-and-bounds-width
  (let [long-body (apply str (repeat 160 "x"))
        state (assoc (tasks/initial-state (assoc-in sample-result [:rows 1 :description] long-body))
                     :width 80
                     :height 18
                     :layout "auto"
                     :color? false)
        lines (str/split-lines (tui/view state))
        pane-lines (take (tasks/viewport-height state) lines)
        separators (map #(.indexOf ^String % " │ ") pane-lines)]
    (is (= 17 (count lines)))
    ;; Keep spare terminal columns so emulators do not auto-wrap at EOL.
    (is (every? #(<= (count %) 76) lines))
    (is (every? #(= 40 %) separators))))

(deftest selected-tree-keeps-the-foreground-and-other-rows-are-muted
  (let [rows [{:id "a" :summary "Parent" :status "TODO"}
              {:id "b" :parentId "a" :summary "Selected" :status "STARTED"}
              {:id "c" :parentId "b" :summary "Grandchild" :status "DONE" :local true}
              {:id "d" :summary "Other" :status "DONE" :local true}]
        ;; JLine emits theme colour 8 as SGR 90 (bright black).
        muted "\u001b[90m"
        lines (fn [selected-id]
                (let [state (assoc (tasks/initial-state {:selectedId selected-id :rows rows})
                                   :color? true :cursor 99 :expanded #{"a" "b"})
                      visible (tasks/visible-tasks state)]
                  (into {} (map-indexed (fn [i t] [(:id t) (tui/task-line state t i)]) visible))))
        muted? (fn [line text] (str/includes? line (str muted text)))]
    (testing "with a selection"
      (let [l (lines "b")]
        (is (= #{"a" "b" "c" "d"} (set (keys l))) "control: every row renders")
        (is (muted? (l "a") "Parent") "an ancestor is outside the selected tree")
        (is (muted? (l "d") "Other"))
        (is (not (muted? (l "b") "Selected")))
        (is (not (muted? (l "c") "Grandchild")) "descendants keep the foreground, even when done or local")
        (is (re-find (re-pattern (str "38;5;" (get styling/palette-256 :green) "m")) (l "d"))
            "a muted row keeps its status colour")))
    (testing "without a selection every row keeps the foreground"
      (let [l (lines nil)]
        (is (= 4 (count l)))
        (is (not-any? #(str/includes? % muted) (vals l)))))))

(deftest a-task-without-an-id-is-never-the-absent-selection
  (let [rows [{:id "a" :summary "Has id" :status "TODO"}
              {:summary "No id" :status "TODO"}]
        state (assoc (tasks/initial-state {:selectedId nil :rows rows}) :color? true :cursor 99)]
    (is (not-any? #(str/includes? % "\u001b[90m")
                  (map-indexed #(tui/task-line state %2 %1) (:visible-tasks state)))
        "no row is muted, because nothing is selected")
    (is (= "2 tasks: 2 TODO"
           (first (tui/compact-lines (assoc state :mode "compact" :color? false)))))))

(deftest task-line-renders-priority-token
  (let [mk (fn [prio] {:id "aaaabbbb-0000-4000-8000-000000000000"
                       :status "TODO" :summary "Sample" :level 1
                       :priority prio :linkedIssues []})
        base {:tasks [] :cursor 99 :expanded #{}}]
    (testing "priority renders like ot list, coloured from the shared palette"
      (let [line (tui/task-line (assoc base :color? true) (mk "B") 0)]
        (is (str/includes? line "[#B]"))
        ;; :orange in styling/palette-256
        (is (str/includes? line (str "38;5;" (get styling/palette-256 :orange) "m")))))
    (testing "cursor, selected, local and completed rows keep the status colour"
      (doseq [[label state task] [["cursor" (assoc base :color? true :cursor 0) (mk nil)]
                                  ["selected" (assoc base :color? true :selected-id "aaaabbbb-0000-4000-8000-000000000000") (mk nil)]
                                  ["local" (assoc base :color? true) (assoc (mk nil) :local true)]
                                  ["done" (assoc base :color? true) (assoc (mk nil) :status "DONE")]]]
        (let [line (tui/task-line state task 0)
              status (if (= "done" label) :green :yellow)]
          (is (re-find (re-pattern (str "38;5;" (get styling/palette-256 status) "(;1)?m")) line) label))))
    (testing "plain text when colour is disabled; token precedes the summary"
      (let [line (tui/task-line (assoc base :color? false) (mk "A") 0)]
        (is (str/includes? line "[#A] Sample"))))
    (testing "no placeholder when the task has no priority"
      (let [line (tui/task-line (assoc base :color? false) (mk nil) 0)]
        (is (not (str/includes? line "[#")))
        (is (str/includes? line " Sample"))))))

(deftest stacked-is-the-default-layout
  (doseq [[w h] [[200 50] [80 24]]]
    (let [state (assoc (tasks/initial-state sample-result) :width w :height h :color? false)
          lines (str/split-lines (tui/view state))]
      (is (true? (tasks/stacked-layout? state)) (str w "x" h))
      (is (every? #(= -1 (.indexOf ^String % " │ ")) lines))
      (is (some #(str/includes? % "Details") lines))
      (is (false? (tasks/stacked-layout? (assoc state :layout "auto"))) "control: auto keeps side by side"))))

(deftest portrait-orientation-stacks-details-below-tree
  (testing "width < height stacks the panes even when width >= 80"
    (let [state (assoc (tasks/initial-state sample-result)
                       :width 90
                       :height 100
                       :layout "auto"
                       :color? false)
          lines (str/split-lines (tui/view state))]
      ;; No side-by-side separator anywhere; a stacked Details heading instead.
      (is (every? #(= -1 (.indexOf ^String % " │ ")) lines))
      (is (some #(str/includes? % "Details") lines))))
  (testing "near-square cell grid from a portrait screen stacks (cell aspect ~1:2)"
    ;; Real geometry from a herdr pane on a portrait monitor: 112 cols x
    ;; 110 rows. Raw width < height is false, but physically this is
    ;; portrait; width < height*2 catches it.
    (let [state (assoc (tasks/initial-state sample-result)
                       :width 112
                       :height 110
                       :layout "auto"
                       :color? false)]
      (is (true? (tasks/stacked-layout? state)))
      (let [lines (str/split-lines (tui/view state))]
        (is (every? #(= -1 (.indexOf ^String % " │ ")) lines)))))
  (testing "landscape terminals keep the side-by-side layout"
    (doseq [[w h] [[100 50]   ; landscape half-screen vertical split (100 = 2h boundary)
                   [200 50]   ; full landscape screen
                   [80 24]]]  ; classic 80x24
      (let [state (assoc (tasks/initial-state sample-result)
                         :width w
                         :height h
                         :layout "auto"
                         :color? false)]
        (is (false? (tasks/stacked-layout? state)) (str w "x" h))
        (let [lines (str/split-lines (tui/view state))]
          (is (some #(not= -1 (.indexOf ^String % " │ ")) lines) (str w "x" h)))))))

(deftest cursor-navigation-scrolls-left-pane
  (let [rows (vec (for [i (range 30)]
                    {:id (str "id" i)
                     :summary (str "Task " i)
                     :status "TODO"
                     :level 1}))
        state (assoc (tasks/initial-state {:selectedId nil :rows rows})
                     :height 10)
        moved (reduce (fn [s _] (first (tui/update-fn {} s (msg/key-press :down))))
                      state
                      (range 20))]
    (is (= 20 (:cursor moved)))
    (is (pos? (:tree-scroll moved)))
    (is (< (:cursor moved) (+ (:tree-scroll moved) 5)))))

(deftest task-plan-resolves-typed-import-link-before-opening-editor
  (testing "regression: `p` on a task with a plan: import link must open the
            resolved design/log/... path, not the literal link token"
    (let [dir (str (fs/create-temp-dir {:prefix "ot-tui-plan"}))]
      (try
        (test-util/bootstrap-linked-plan-graph! dir)
        ;; Select the parent explicitly: the fixture's hand-authored,
        ;; not-yet-backfilled child has `:id nil`, which would otherwise
        ;; collide with the default `nil` selected-id and misdirect the
        ;; cursor onto it instead of the plan-linked parent.
        (spit (str dir "/TASKS.local.org")
              (str "#+SELECTED: " test-util/linked-plan-parent-id "\n"))
        (let [log (str dir "/editor-invocations.log")
              editor-script (str dir "/fake-editor.sh")]
          (spit editor-script (str "#!/bin/sh\necho \"$@\" >> " log "\n"))
          (fs/set-posix-file-permissions editor-script "rwxr-xr-x")
          (let [opts {:root dir :editor editor-script}
                state (tasks/initial-state (tasks/load-tasks opts))]
            (is (= "Parent with linked plan" (:summary (tasks/cursor-task state)))
                "precondition: cursor on the task carrying the plan: import")
            (let [[after _] (tui/update-fn opts state (msg/key-press "p"))
                  invocations (if (fs/exists? log) (slurp log) "")]
              (is (str/includes? invocations "design/log/linked-plan.org"))
              (is (not (str/includes? invocations "plan:linked-plan.org")))
              (is (not= "No change-record found for this task." (:message after))))))
        (finally (fs/delete-tree dir))))))

(deftest error-message-extracts-envelope-text
  (testing "the error envelope is reduced to its human message"
    (is (= "boom"
           (tasks/error-message {:ok false :error {:code "x" :message "boom"}}))))
  (testing "a missing envelope or message falls back to a default"
    (is (= "Command failed." (tasks/error-message nil)))
    (is (= "Command failed." (tasks/error-message {})))))

(deftest msg->actions-maps-keys-and-prompt-mode
  (let [state (tasks/initial-state sample-result)]
    (testing "navigation and command keys map to pure action data"
      (is (= [[:tree/move-cursor 1]] (tui/msg->actions state (msg/key-press :down))))
      (is (= [[:task/select]] (tui/msg->actions state (msg/key-press "s"))))
      (is (= [[:tui/quit]] (tui/msg->actions state (msg/key-press :escape))))
      (is (= [[:tui/resize 120 40]] (tui/msg->actions state (msg/window-size 120 40)))))
    (testing "shift+arrows cycle priority; bare arrows still cycle status"
      (is (= [[:task/cycle-priority 1]]
             (tui/msg->actions state (msg/key-press :right :shift true))))
      (is (= [[:task/cycle-priority -1]]
             (tui/msg->actions state (msg/key-press :left :shift true))))
      (is (= [[:task/cycle-status 1]]
             (tui/msg->actions state (msg/key-press :right))))
      (is (= [[:task/cycle-status -1]]
             (tui/msg->actions state (msg/key-press :left)))))
    (testing "prompt mode routes typing to prompt actions"
      (let [prompting (assoc state :new-task {:kind :sibling :label "New" :input ""})]
        (is (= [[:new-task/input "x"]] (tui/msg->actions prompting (msg/key-press "x"))))
        (is (= [[:new-task/submit]] (tui/msg->actions prompting (msg/key-press :enter))))
        (is (= [[:new-task/cancel]] (tui/msg->actions prompting (msg/key-press :escape))))))))

(deftest update-fn-dispatches-navigation-and-quit-via-nexus
  (let [state (tasks/initial-state sample-result)]
    (testing "navigation flows through nexus dispatch into new state"
      (let [[next-state cmd] (tui/update-fn {} state (msg/key-press :down))]
        (is (nil? cmd))
        (is (= "c" (:id (tasks/cursor-task next-state))))))
    (testing "quit returns the program quit command and strips the internal marker"
      (let [[next-state cmd] (tui/update-fn {} state (msg/key-press :escape))]
        (is (= program/quit-cmd cmd))
        (is (not (contains? next-state :org-tasks.tui.dispatch/quit)))))))

(deftest select-effect-persists-through-nexus-dispatch
  (testing "the :task/select effect mutates durable state and reports success"
    (let [dir (str (fs/create-temp-dir {:prefix "ot-tui-select"}))]
      (try
        (spit (str dir "/TASKS.org")
              (str "#+TITLE: Tasks\n#+SETUPFILE: ./TASKS.local.org\n\n"
                   "* Improvements\n\n"
                   "** TODO Pick me\n:PROPERTIES:\n"
                   ":CUSTOM_ID: 11111111-1111-4111-8111-111111111111\n:END:\n"))
        (spit (str dir "/TASKS.local.org") "#+SELECTED:\n")
        (let [opts {:root dir}
              state (tasks/initial-state (tasks/load-tasks opts))
              [next-state _] (tui/update-fn opts state (msg/key-press "s"))]
          (is (= "Selection updated." (:message next-state)))
          (is (= "11111111-1111-4111-8111-111111111111" (:selected-id next-state)))
          (is (str/includes? (slurp (str dir "/TASKS.local.org"))
                             "11111111-1111-4111-8111-111111111111"))
          (is (nil? (second (tui/update-fn opts state (msg/key-press "s"))))
              "without --exit-on-select the browser stays open")
          (let [[_ cmd] (tui/update-fn (assoc opts :exit-on-select true) next-state (msg/key-press "s"))]
            (is (= program/quit-cmd cmd) "--exit-on-select quits after a successful select"))
          (let [[_ cmd] (tui/update-fn (assoc opts :exit-on-select true) next-state (msg/key-press :down))]
            (is (nil? cmd) "navigation does not quit")))
        (finally (fs/delete-tree dir))))))

(deftest create-opts-delegate-placement-to-ot
  (testing "no cursor row creates a top-level task"
    (is (= {:summary "Root"} (dispatch/create-opts :sibling nil "Root"))))
  (testing "child hands ot --relative-to/--as child"
    (is (= {:summary "Kid" :relative-to "p1" :as :child}
           (dispatch/create-opts :child {:id "p1" :local true} "Kid"))))
  (testing "sibling hands ot --relative-to/--as sibling (ot derives parent/after/source)"
    (is (= {:summary "Sib" :relative-to "c1" :as :sibling}
           (dispatch/create-opts :sibling {:id "c1" :parentId "p1" :sourcePath "/x/plan.org"} "Sib")))))

(defn- feed-keys [opts state msgs]
  (reduce (fn [s m] (first (tui/update-fn opts s m))) state msgs))

(deftest creating-a-task-from-an-empty-list-appends-top-level
  (testing "n with no cursor row opens a prompt and creates a top-level task"
    (let [dir (str (fs/create-temp-dir {:prefix "ot-tui-empty"}))]
      (try
        (spit (str dir "/TASKS.org") "#+TITLE: Tasks\n#+SETUPFILE: ./TASKS.local.org\n\n* Improvements\n")
        (spit (str dir "/TASKS.local.org") "#+SELECTED:\n")
        (let [opts {:root dir}
              state (tasks/initial-state (tasks/load-tasks opts))]
          (is (zero? (count (:tasks state))) "precondition: no tasks")
          (let [after (feed-keys opts state
                                 [(msg/key-press "n")
                                  (msg/key-press "R") (msg/key-press "o") (msg/key-press "o") (msg/key-press "t")
                                  (msg/key-press :enter)])]
            (is (= "Task created." (:message after)))
            (is (= ["Root"] (mapv :summary (:tasks after))))
            (is (nil? (:parentId (first (:tasks after)))) "created task is top-level")))
        (finally (fs/delete-tree dir))))))

(deftest creating-a-child-task-nests-under-the-cursor
  (testing "N creates a child of the cursor task"
    (let [dir (str (fs/create-temp-dir {:prefix "ot-tui-child"}))]
      (try
        (spit (str dir "/TASKS.org")
              (str "#+TITLE: Tasks\n#+SETUPFILE: ./TASKS.local.org\n\n* Improvements\n\n"
                   "** TODO Parent\n:PROPERTIES:\n:CUSTOM_ID: 11111111-1111-4111-8111-111111111111\n:END:\n"))
        (spit (str dir "/TASKS.local.org") "#+SELECTED:\n")
        (let [opts {:root dir}
              state (tasks/initial-state (tasks/load-tasks opts))
              after (feed-keys opts state
                               [(msg/key-press "N")
                                (msg/key-press "K") (msg/key-press "i") (msg/key-press "d")
                                (msg/key-press :enter)])
              child (first (filter #(= "Kid" (:summary %)) (:tasks after)))]
          (is (= "Task created." (:message after)))
          (is (= "11111111-1111-4111-8111-111111111111" (:parentId child))))
        (finally (fs/delete-tree dir))))))

(deftest selecting-a-task-keeps-the-cursor-on-it
  (testing "toggling selection does not move the cursor across collapsed rows"
    ;; Regression: reload-state used to recompute the cursor as the selected
    ;; id's index into the *full* row list while the cursor indexes *visible*
    ;; rows, so selecting Beta (with Alpha's child collapsed) jumped the cursor
    ;; down onto Gamma.
    (let [dir (str (fs/create-temp-dir {:prefix "ot-tui-cursor"}))]
      (try
        (spit (str dir "/TASKS.org")
              (str "#+TITLE: Tasks\n#+SETUPFILE: ./TASKS.local.org\n\n"
                   "* Improvements\n\n"
                   "** TODO Alpha\n:PROPERTIES:\n:CUSTOM_ID: aaaaaaaa-1111-4111-8111-111111111111\n:END:\n"
                   "*** TODO Alpha child\n:PROPERTIES:\n:CUSTOM_ID: aaaaaaaa-2222-4111-8111-111111111111\n:END:\n"
                   "** TODO Beta\n:PROPERTIES:\n:CUSTOM_ID: bbbbbbbb-1111-4111-8111-111111111111\n:END:\n"
                   "** TODO Gamma\n:PROPERTIES:\n:CUSTOM_ID: cccccccc-1111-4111-8111-111111111111\n:END:\n"))
        (spit (str dir "/TASKS.local.org") "#+SELECTED:\n")
        (let [opts {:root dir}
              state (first (tui/update-fn opts
                                          (tasks/initial-state (tasks/load-tasks opts))
                                          (msg/key-press :down)))]
          (is (= "Beta" (:summary (tasks/cursor-task state))) "precondition: cursor on Beta")
          (let [[after _] (tui/update-fn opts state (msg/key-press "s"))]
            (is (= "Beta" (:summary (tasks/cursor-task after))) "cursor stays on Beta after select")
            (is (= "bbbbbbbb-1111-4111-8111-111111111111" (:selected-id after)))))
        (finally (fs/delete-tree dir))))))

(deftest removal-first-press-previews-without-writing-and-arms-the-cursor-task
  (let [dir (str (fs/create-temp-dir {:prefix "ot-tui-remove"}))]
    (try
      (bootstrap-removal-tui-graph! dir)
      (let [opts {:root dir}
            before (slurp (str dir "/TASKS.org"))
            state (feed-keys opts (removal-tui-state opts)
                             [(msg/key-press :enter) (msg/key-press :down)])
            [after _] (tui/update-fn opts state (msg/key-press "D"))]
        (is (contains? dispatch/command-fns :remove) "remove handler comes from the registry")
        (is (= removal-child-id (get-in after [:remove-confirmation :task-id])))
        (is (str/includes? (:message after) "subtree 1"))
        (is (str/includes? (:message after) "unchecked criteria 1"))
        (is (str/includes? (:message after) "inbound blockers 1"))
        (is (str/includes? (:message after) "blockers will be pruned"))
        (is (= before (slurp (str dir "/TASKS.org"))) "dry-run does not write"))
      (finally (fs/delete-tree dir)))))

(deftest removal-second-press-deletes-prunes-and-refreshes-the-cursor
  (let [dir (str (fs/create-temp-dir {:prefix "ot-tui-remove"}))]
    (try
      (bootstrap-removal-tui-graph! dir)
      (let [opts {:root dir}
            state (feed-keys opts (removal-tui-state opts)
                             [(msg/key-press :enter) (msg/key-press :down)])
            after (feed-keys opts state [(msg/key-press "D") (msg/key-press "D")])
            source (slurp (str dir "/TASKS.org"))]
        (is (not (str/includes? source removal-child-id)))
        (is (not (str/includes? source (str "task:" removal-child-id))))
        (is (nil? (:remove-confirmation after)))
        (is (some? (tasks/cursor-task after)) "cursor remains valid after reload")
        (is (not= removal-child-id (tasks/cursor-id after)))
        (is (<= 0 (:cursor after) (dec (count (:visible-tasks after)))))
        (is (<= 0 (:tree-scroll after) (:cursor after)) "tree scroll remains valid"))
      (finally (fs/delete-tree dir)))))

(deftest removal-confirmation-clears-on-movement-and-replaces-for-another-task
  (let [dir (str (fs/create-temp-dir {:prefix "ot-tui-remove"}))]
    (try
      (bootstrap-removal-tui-graph! dir)
      (let [opts {:root dir}
            child-state (feed-keys opts (removal-tui-state opts)
                                   [(msg/key-press :enter) (msg/key-press :down)])
            armed (first (tui/update-fn opts child-state (msg/key-press "D")))
            prompting (first (tui/update-fn opts armed (msg/key-press "n")))
            mutated (first (tui/update-fn opts armed (msg/key-press :right)))
            moved (first (tui/update-fn opts armed (msg/key-press :down)))
            [rearmed _] (tui/update-fn opts moved (msg/key-press "D"))]
        (is (= removal-child-id (get-in armed [:remove-confirmation :task-id])))
        (is (nil? (:remove-confirmation prompting)) "entering create mode disarms deletion")
        (is (nil? (:remove-confirmation mutated)) "task mutation disarms deletion")
        (is (nil? (:remove-confirmation moved)) "cursor movement disarms deletion")
        (doseq [action [:tree/toggle-expansion :task/edit :task/plan :task/open-issues]
                :let [assoc-action (first ((get-in dispatch/nexus [:nexus/actions action]) armed))
                      assoc-values (apply hash-map (rest assoc-action))]]
          (is (and (= :state/assoc (first assoc-action))
                   (contains? assoc-values :remove-confirmation)
                   (nil? (:remove-confirmation assoc-values)))
              (str action " mode transition disarms deletion before its effect")))
        (is (= removal-other-child-id (get-in rearmed [:remove-confirmation :task-id]))
            "D on another task replaces, never confirms, an old arm"))
      (finally (fs/delete-tree dir)))))

(deftest removal-refuses-a-top-level-root-without-arming
  (let [dir (str (fs/create-temp-dir {:prefix "ot-tui-remove"}))]
    (try
      (bootstrap-removal-tui-graph! dir)
      (let [opts {:root dir}
            [after _] (tui/update-fn opts (removal-tui-state opts) (msg/key-press "D"))]
        (is (nil? (:remove-confirmation after)))
        (is (str/includes? (:message after) "Cannot remove a protocol top-level root")))
      (finally (fs/delete-tree dir)))))

(deftest multiline-details-stay-inside-right-pane
  (let [state (assoc (tasks/initial-state
                      {:selectedId "a"
                       :rows [{:id "a"
                               :summary "Multiline"
                               :status "TODO"
                               :level 1
                               :description "First paragraph.\n\nAcceptance:\n- final line stays right"}]})
                     :width 100
                     :height 20
                     :layout "auto"
                     :color? false)
        lines (str/split-lines (tui/view state))
        pane-lines (take (tasks/viewport-height state) lines)]
    (is (every? #(not= -1 (.indexOf ^String % " │ ")) pane-lines))
    (is (some #(str/includes? % "│ Acceptance:") pane-lines))
    (is (some #(str/includes? % "│ - final line stays right") pane-lines))))

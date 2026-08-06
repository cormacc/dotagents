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
    (is (= 5 (:details-scroll (first (tui/update-fn {} state {:type :key-press :key "d" :ctrl true})))))
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
                     :color? false)
        lines (str/split-lines (tui/view state))
        pane-lines (take 15 lines)
        separators (map #(.indexOf ^String % " │ ") pane-lines)]
    (is (= 17 (count lines)))
    ;; Keep spare terminal columns so emulators do not auto-wrap at EOL.
    (is (every? #(<= (count %) 76) lines))
    (is (every? #(= 40 %) separators))))

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
    (testing "plain text when colour is disabled; token precedes the summary"
      (let [line (tui/task-line (assoc base :color? false) (mk "A") 0)]
        (is (str/includes? line "[#A] Sample"))))
    (testing "no placeholder when the task has no priority"
      (let [line (tui/task-line (assoc base :color? false) (mk nil) 0)]
        (is (not (str/includes? line "[#")))
        (is (str/includes? line " Sample"))))))

(deftest portrait-orientation-stacks-details-below-tree
  (testing "width < height stacks the panes even when width >= 80"
    (let [state (assoc (tasks/initial-state sample-result)
                       :width 90
                       :height 100
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
                             "11111111-1111-4111-8111-111111111111")))
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
                     :color? false)
        lines (str/split-lines (tui/view state))
        pane-lines (take 17 lines)]
    (is (every? #(not= -1 (.indexOf ^String % " │ ")) pane-lines))
    (is (some #(str/includes? % "│ Acceptance:") pane-lines))
    (is (some #(str/includes? % "│ - final line stays right") pane-lines))))

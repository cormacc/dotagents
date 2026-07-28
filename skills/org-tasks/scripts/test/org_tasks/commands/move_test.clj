(ns org-tasks.commands.move-test
  "`ot move` family tests: reparenting, section lifts, level
  re-normalisation, dry-run, preflight refusals, change-record graph
  integrity, and byte-level relocation fidelity."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org-tasks.commands.test-util :refer :all]
            [org-tasks.loader :as loader]))

;; ── Fixture ───────────────────────────────────────────────────────
;;
;; Canonical spacing (blank line between top-level tasks and between
;; sibling subtasks), a nested three-level subtree, and a second
;; level-1 section so `--section` has somewhere to move to.

(def ^:private parent-a   "aaaa1111-2222-4333-8444-555555555551")
(def ^:private child-a1   "aaaa2222-2222-4333-8444-555555555552")
(def ^:private grandchild "aaaa3333-2222-4333-8444-555555555553")
(def ^:private child-a2   "aaaa4444-2222-4333-8444-555555555554")
(def ^:private parent-b   "bbbb1111-2222-4333-8444-555555555555")
(def ^:private chores     "cccc1111-2222-4333-8444-555555555556")

(def ^:private move-fixture
  (str tasks-org-preamble
       "* Improvements\n"
       "\n"
       "** TODO Parent A\n"
       ":PROPERTIES:\n"
       ":CUSTOM_ID: " parent-a "\n"
       ":CREATED: [2026-05-01 Fri 09:00]\n"
       ":END:\n"
       ":LOGBOOK:\n"
       "- Created [2026-05-01 Fri 09:00]\n"
       ":END:\n"
       "Body of Parent A.\n"
       "\n"
       "*** STARTED Child A1 :backend:\n"
       ":PROPERTIES:\n"
       ":CUSTOM_ID: " child-a1 "\n"
       ":CREATED: [2026-05-02 Sat 10:00]\n"
       ":STARTED: [2026-05-02 Sat 10:00]\n"
       ":NS_CUSTOM: keep me\n"
       ":END:\n"
       ":LOGBOOK:\n"
       "- State \"STARTED\" from \"TODO\" [2026-05-02 Sat 10:00]\n"
       ":END:\n"
       "Child A1 body.\n"
       "\n"
       "**** DONE Grandchild A1a\n"
       "CLOSED: [2026-05-03 Sun 11:00]\n"
       ":PROPERTIES:\n"
       ":CUSTOM_ID: " grandchild "\n"
       ":END:\n"
       "\n"
       "*** TODO Child A2\n"
       ":PROPERTIES:\n"
       ":CUSTOM_ID: " child-a2 "\n"
       ":END:\n"
       "\n"
       "** TODO Parent B\n"
       ":PROPERTIES:\n"
       ":CUSTOM_ID: " parent-b "\n"
       ":END:\n"
       "\n"
       "* Housekeeping\n"
       "\n"
       "** TODO Chores\n"
       ":PROPERTIES:\n"
       ":CUSTOM_ID: " chores "\n"
       ":END:\n"))

(defn- bootstrap-move-graph! [root]
  (spit (str (fs/path root "TASKS.setup.org")) setup-org-preamble)
  (spit (str (fs/path root "TASKS.org")) move-fixture)
  (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n"))

(defn- tasks-org [root] (str (fs/path root "TASKS.org")))

(defn- between
  "Substring of `content` from `from` up to (excluding) `to`."
  [content from to]
  (subs content (str/index-of content from) (str/index-of content to)))

;; ── Reparenting ───────────────────────────────────────────────────

(deftest move-reparents-subtree-preserving-every-line
  (with-temp-dir
    (fn [root]
      (bootstrap-move-graph! root)
      ;; Parent B is level 2, so Child A1 keeps level 3: the relocated
      ;; block must therefore be byte-identical, which pins drawer
      ;; contents, LOGBOOK, CLOSED:, body, and intra-subtree blank lines
      ;; all at once.
      (let [before (slurp (tasks-org root))
            block  (between before "*** STARTED Child A1" "*** TODO Child A2")
            {:keys [out exit]}
            (run-cli! "--root" root "--format" "json" "move" child-a1 "--parent" parent-b)
            r (parse-json-result out)
            after (slurp (tasks-org root))]
        (is (zero? exit) out)
        (testing "the subtree is relocated byte-for-byte"
          (is (str/includes? after block))
          (is (< (str/index-of after "** TODO Parent B")
                 (str/index-of after "*** STARTED Child A1"))))
        (testing "the whole subtree travelled, not just its root"
          (is (str/includes? after "**** DONE Grandchild A1a"))
          (is (= 1 (count (re-seq (re-pattern (str ":CUSTOM_ID: " grandchild)) after)))))
        (testing "regions outside the move are untouched"
          (is (str/starts-with? after tasks-org-preamble))
          (is (str/includes? after (between before "** TODO Parent A" "*** STARTED Child A1")))
          (is (str/includes? after (between before "*** TODO Child A2" "** TODO Parent B")))
          (is (str/includes? after (between before "* Housekeeping" (str ":CUSTOM_ID: " chores)))))
        (testing "result payload"
          (is (= parent-b (:parentId r)))
          (is (nil? (:section r)))
          (is (= parent-a (:previousParentId r)))
          (is (= 3 (:fromLevel r)))
          (is (= 3 (:toLevel r)))
          (is (= 2 (:movedCount r)))
          (is (false? (:dryRun r)))
          (is (= child-a1 (get-in r [:task :id])))
          (is (= 3 (get-in r [:task :level]))))
        (testing "the graph still resolves exactly one node for the moved id"
          (let [rows (:rows (parse-json-result
                              (:out (run-cli! "--root" root "--format" "json" "list"))))]
            (is (= 1 (count (filter #(= child-a1 (:id %)) rows))))
            (is (= parent-b (:parentId (first (filter #(= child-a1 (:id %)) rows)))))))))))

(deftest move-renormalises-levels-for-the-whole-subtree
  (with-temp-dir
    (fn [root]
      (bootstrap-move-graph! root)
      (let [{:keys [out exit]}
            (run-cli! "--root" root "--format" "json" "move" child-a1 "--parent" child-a2)
            r (parse-json-result out)
            after (slurp (tasks-org root))]
        (is (zero? exit) out)
        (is (= 3 (:fromLevel r)))
        (is (= 4 (:toLevel r)))
        (is (str/includes? after "**** STARTED Child A1 :backend:"))
        (is (str/includes? after "***** DONE Grandchild A1a"))
        (testing "metadata survives the depth change"
          (is (str/includes? after ":NS_CUSTOM: keep me"))
          (is (str/includes? after ":STARTED: [2026-05-02 Sat 10:00]"))
          (is (str/includes? after "- State \"STARTED\" from \"TODO\" [2026-05-02 Sat 10:00]"))
          (is (str/includes? after "CLOSED: [2026-05-03 Sun 11:00]"))
          (is (str/includes? after "Child A1 body.")))
        (testing "levels agree with the reloaded graph"
          (let [rows (:rows (parse-json-result
                              (:out (run-cli! "--root" root "--format" "json" "list"))))
                by-id (into {} (map (juxt :id identity)) rows)]
            (is (= 4 (:level (get by-id child-a1))))
            (is (= 5 (:level (get by-id grandchild))))
            (is (= child-a2 (:parentId (get by-id child-a1))))))))))

;; ── Section destination ───────────────────────────────────────────

(deftest move-lifts-subtree-back-to-a-top-level-section
  (with-temp-dir
    (fn [root]
      (bootstrap-move-graph! root)
      (let [{:keys [out exit]}
            (run-cli! "--root" root "--format" "json" "move" child-a1 "--section" "Housekeeping")
            r (parse-json-result out)
            after (slurp (tasks-org root))]
        (is (zero? exit) out)
        (is (= "Housekeeping" (:section r)))
        (is (nil? (:parentId r)))
        (is (= parent-a (:previousParentId r)))
        (is (= 3 (:fromLevel r)))
        (is (= 2 (:toLevel r)))
        (is (str/includes? after "** STARTED Child A1 :backend:"))
        (is (str/includes? after "*** DONE Grandchild A1a"))
        (is (< (str/index-of after "* Housekeeping")
               (str/index-of after "** STARTED Child A1")))
        (testing "it left its old parent"
          (let [rows (:rows (parse-json-result
                              (:out (run-cli! "--root" root "--format" "json" "list"))))
                row (first (filter #(= child-a1 (:id %)) rows))]
            (is (nil? (:parentId row)))
            (is (= 2 (:level row)))))))))

(deftest move-keeps-a-local-task-local
  (with-temp-dir
    (fn [root]
      (let [id "33333333-2222-4333-8444-555555555553"]
        (spit (str (fs/path root "TASKS.org")) "* Improvements\n")
        (spit (str (fs/path root "TASKS.local.org"))
              (str "#+SELECTED:\n"
                   "\n"
                   "* Drafts\n"
                   "\n"
                   "** TODO Local draft\n"
                   ":PROPERTIES:\n"
                   ":CUSTOM_ID: " id "\n"
                   ":END:\n"
                   "\n"
                   "* Later\n"
                   "\n"
                   "** TODO Someday\n"
                   ":PROPERTIES:\n"
                   ":CUSTOM_ID: 44444444-2222-4333-8444-555555555554\n"
                   ":END:\n"))
        (let [{:keys [out exit]}
              (run-cli! "--root" root "--format" "json" "move" id "--section" "Later")
              r (parse-json-result out)
              local (slurp (str (fs/path root "TASKS.local.org")))]
          (is (zero? exit) out)
          (is (= (str (fs/path root "TASKS.local.org")) (:file r)))
          (is (true? (get-in r [:task :local])))
          (is (< (str/index-of local "* Later")
                 (str/index-of local "** TODO Local draft")))
          (is (not (str/includes? (slurp (str (fs/path root "TASKS.org"))) id))))))))

;; ── Round-trip regression ─────────────────────────────────────────

(deftest move-and-move-back-restore-the-original-file
  (testing "--parent out and back (the moved task is its parent's last child)"
    (with-temp-dir
      (fn [root]
        (bootstrap-move-graph! root)
        (let [before (slurp (tasks-org root))]
          (is (zero? (:exit (run-cli! "--root" root "move" child-a2 "--parent" parent-b))))
          (is (not= before (slurp (tasks-org root))))
          (is (zero? (:exit (run-cli! "--root" root "move" child-a2 "--parent" parent-a))))
          (is (= before (slurp (tasks-org root))))))))
  (testing "--parent out and --section back (the moved task is its section's last task)"
    (with-temp-dir
      (fn [root]
        (bootstrap-move-graph! root)
        (let [before (slurp (tasks-org root))]
          (is (zero? (:exit (run-cli! "--root" root "move" parent-b "--parent" chores))))
          (is (not= before (slurp (tasks-org root))))
          (is (zero? (:exit (run-cli! "--root" root "move" parent-b "--section" "Improvements"))))
          (is (= before (slurp (tasks-org root)))))))))

(deftest move-of-a-file-ending-subtree-keeps-a-separator
  (testing "regression: a subtree cut from end-of-file has no separator of its own and must not weld to the heading it lands before"
    (with-temp-dir
      (fn [root]
        (bootstrap-move-graph! root)
        ;; Chores is the last task in the file; Parent A's region is
        ;; blank-separated, so the relocated block needs a separator back.
        (is (zero? (:exit (run-cli! "--root" root "move" chores "--parent" parent-a))))
        (let [after (slurp (tasks-org root))]
          (is (str/includes? after (str "*** TODO Chores\n:PROPERTIES:\n:CUSTOM_ID: "
                                       chores "\n:END:\n\n** TODO Parent B")))
          (is (not (str/includes? after ":END:\n** TODO Parent B"))))))))

;; ── Dry run ───────────────────────────────────────────────────────

(deftest move-dry-run-reports-without-writing
  (with-temp-dir
    (fn [root]
      (bootstrap-move-graph! root)
      (let [before (slurp (tasks-org root))
            {:keys [out exit]}
            (run-cli! "--root" root "--format" "json" "--dry-run"
                      "move" child-a1 "--parent" parent-b)
            r (parse-json-result out)]
        (is (zero? exit) out)
        (is (true? (:dryRun r)))
        (is (= parent-b (:parentId r)))
        (is (= 3 (:toLevel r)))
        (is (= before (slurp (tasks-org root)))))
      (testing "text mode says it would move"
        (let [before (slurp (tasks-org root))
              {:keys [out exit]}
              (run-cli! "--root" root "--dry-run" "move" parent-b "--section" "Housekeeping")]
          (is (zero? exit))
          (is (str/includes? out "Would move Parent B → section Housekeeping"))
          (is (= before (slurp (tasks-org root)))))))))

;; ── Preflight refusals ────────────────────────────────────────────

(defn- refusal
  "Run a failing `ot move` and return `[error-map file-unchanged?]`."
  [root & args]
  (let [before (slurp (tasks-org root))
        {:keys [err exit]} (apply run-cli! "--root" root "--format" "json" "move" args)]
    [(parse-json-error err) exit (= before (slurp (tasks-org root)))]))

(deftest move-argument-preflights
  (with-temp-dir
    (fn [root]
      (bootstrap-move-graph! root)
      (testing "missing id"
        (let [[e exit unchanged?] (refusal root)]
          (is (= 1 exit))
          (is (= "argument-error" (:code e)))
          (is unchanged?)))
      (testing "no destination"
        (let [[e exit unchanged?] (refusal root child-a1)]
          (is (= 1 exit))
          (is (= "argument-error" (:code e)))
          (is (str/includes? (:message e) "--parent"))
          (is unchanged?)))
      (testing "both destinations"
        (let [[e exit unchanged?] (refusal root child-a1 "--parent" parent-b
                                          "--section" "Housekeeping")]
          (is (= 1 exit))
          (is (= "argument-error" (:code e)))
          (is (str/includes? (:message e) "not both"))
          (is unchanged?)))
      (testing "unknown section"
        (let [[e exit unchanged?] (refusal root child-a1 "--section" "Nope")]
          (is (= 1 exit))
          (is (= "section-not-found" (:code e)))
          (is (= "Nope" (get-in e [:details :section])))
          (is unchanged?))))))

(deftest move-identity-preflights
  (with-temp-dir
    (fn [root]
      (bootstrap-move-graph! root)
      (testing "unknown source id"
        (let [[e exit unchanged?] (refusal root "9999abcd" "--parent" parent-b)]
          (is (= 1 exit))
          (is (= "unknown-task" (:code e)))
          (is unchanged?)))
      (testing "unknown destination id"
        (let [[e exit unchanged?] (refusal root child-a1 "--parent" "9999abcd")]
          (is (= 1 exit))
          (is (= "unknown-task" (:code e)))
          (is unchanged?)))
      (testing "ambiguous prefix"
        (let [[e exit unchanged?] (refusal root "aaaa" "--parent" parent-b)]
          (is (= 1 exit))
          (is (= "ambiguous-id" (:code e)))
          (is (< 1 (count (get-in e [:details :matches]))))
          (is unchanged?)))
      (testing "unique 4-char prefixes resolve on both sides"
        (let [{:keys [out exit]}
              (run-cli! "--root" root "--format" "json" "move" "cccc1111" "--parent" "bbbb")]
          (is (zero? exit) out)
          (is (= parent-b (:parentId (parse-json-result out)))))))))

(deftest move-cycle-preflights
  (with-temp-dir
    (fn [root]
      (bootstrap-move-graph! root)
      (testing "destination is the source"
        (let [[e exit unchanged?] (refusal root parent-a "--parent" parent-a)]
          (is (= 1 exit))
          (is (= "validation" (:code e)))
          (is (str/includes? (:message e) "under itself"))
          (is unchanged?)))
      (testing "destination is a direct child of the source"
        (let [[e exit unchanged?] (refusal root parent-a "--parent" child-a1)]
          (is (= 1 exit))
          (is (= "validation" (:code e)))
          (is (str/includes? (:message e) "descendant"))
          (is unchanged?)))
      (testing "destination is a deeper descendant of the source"
        (let [[e exit unchanged?] (refusal root parent-a "--parent" grandchild)]
          (is (= 1 exit))
          (is (= "validation" (:code e)))
          (is (str/includes? (:message e) "descendant"))
          (is unchanged?))))))

(deftest move-refuses-cross-file-destinations
  (testing "TASKS.local.org → TASKS.org"
    (with-temp-dir
      (fn [root]
        (let [local-id "33333333-2222-4333-8444-555555555553"]
          (bootstrap-local! root local-id "Draft task")
          (spit (str (fs/path root "TASKS.org"))
                (str "* Improvements\n\n** TODO Shared\n:PROPERTIES:\n:CUSTOM_ID: "
                     parent-a "\n:END:\n"))
          (let [tasks-before (slurp (tasks-org root))
                local-before (slurp (str (fs/path root "TASKS.local.org")))
                {:keys [err exit]}
                (run-cli! "--root" root "--format" "json" "move" local-id "--parent" parent-a)
                e (parse-json-error err)]
            (is (= 1 exit))
            (is (= "validation" (:code e)))
            (is (str/includes? (:message e) "Cross-file moves are out of scope"))
            (is (str/includes? (:message e) "publish/unpublish"))
            (is (= tasks-before (slurp (tasks-org root))))
            (is (= local-before (slurp (str (fs/path root "TASKS.local.org"))))))))))
  (testing "TASKS.org → an #+IMPORT:-linked change-record"
    (with-temp-dir
      (fn [root]
        (bootstrap-linked-plan-graph! root)
        (spit (tasks-org root)
              (str (slurp (tasks-org root))
                   "\n** TODO Unrelated shared task\n:PROPERTIES:\n:CUSTOM_ID: "
                   parent-a "\n:END:\n"))
        (let [record (str (fs/path root "design" "log" "linked-plan.org"))
              tasks-before (slurp (tasks-org root))
              record-before (slurp record)
              {:keys [err exit]}
              (run-cli! "--root" root "--format" "json"
                        "move" parent-a "--parent" linked-plan-child-id)
              e (parse-json-error err)]
          (is (= 1 exit))
          (is (= "validation" (:code e)))
          (is (str/includes? (:message e) "Cross-file moves are out of scope"))
          (is (= tasks-before (slurp (tasks-org root))))
          (is (= record-before (slurp record))))))))

(deftest move-refuses-an-archived-task
  (with-temp-dir
    (fn [root]
      (let [id "99999999-2222-4333-8444-555555555559"
            archive (str (fs/path root "TASKS.archive.org"))]
        (bootstrap-move-graph! root)
        (spit archive
              (str "* DONE Archived thing\n:PROPERTIES:\n:CUSTOM_ID: " id
                   "\n:ARCHIVED: [2026-05-01 Fri 09:00]\n:END:\n"))
        (let [tasks-before (slurp (tasks-org root))
              archive-before (slurp archive)
              {:keys [err exit]}
              (run-cli! "--root" root "--format" "json" "move" id "--section" "Improvements")
              e (parse-json-error err)]
          (is (= 1 exit))
          (is (= "validation" (:code e)))
          (is (str/includes? (:message e) "archived"))
          (is (str/includes? (:message e) "ot unarchive"))
          (is (= tasks-before (slurp (tasks-org root))))
          (is (= archive-before (slurp archive))))
        (testing "an id absent from both graphs still reports unknown-task"
          (let [{:keys [err exit]}
                (run-cli! "--root" root "--format" "json" "move" "7777abcd"
                          "--section" "Improvements")]
            (is (= 1 exit))
            (is (= "unknown-task" (:code (parse-json-error err))))))))))

(deftest move-reports-a-write-conflict-without-writing
  (with-temp-dir
    (fn [root]
      (bootstrap-move-graph! root)
      (let [real-slurp loader/safe-slurp
            reads  (atom 0)
            before (slurp (tasks-org root))
            {:keys [err exit]}
            ;; Simulate a concurrent editor: every read of TASKS.org after
            ;; the one that produced the load-time baseline sees different
            ;; bytes, so `loader/assert-unchanged!` must abort the write.
            (with-redefs [loader/safe-slurp
                          (fn [path]
                            (let [content (real-slurp path)]
                              (if (and content
                                       (str/ends-with? (str path) "TASKS.org")
                                       (< 1 (swap! reads inc)))
                                (str content "\n* Later\n")
                                content)))]
              (run-cli! "--root" root "--format" "json" "move" child-a1 "--parent" parent-b))]
        (is (= 1 exit))
        (is (= "conflict" (:code (parse-json-error err))))
        (is (= before (slurp (tasks-org root))))))))

;; ── Change-record graph integrity ─────────────────────────────────

(deftest move-preserves-change-record-links
  (with-temp-dir
    (fn [root]
      (let [group-id "dddd1111-2222-4333-8444-555555555557"
            record (str (fs/path root "design" "log" "linked-plan.org"))]
        (fs/create-dirs (str (fs/path root "design" "log")))
        (spit (str (fs/path root "TASKS.setup.org")) setup-org-preamble)
        (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n")
        (spit (tasks-org root)
              (str tasks-org-preamble
                   "* Improvements\n"
                   "\n"
                   "** TODO Grouping parent\n"
                   ":PROPERTIES:\n"
                   ":CUSTOM_ID: " group-id "\n"
                   ":END:\n"
                   "\n"
                   "** TODO Parent with linked plan\n"
                   ":PROPERTIES:\n"
                   ":CUSTOM_ID: " linked-plan-parent-id "\n"
                   ":END:\n"
                   "#+IMPORT: [[plan:linked-plan.org]]\n"))
        (spit record
              (str "#+TITLE: Linked Plan\n"
                   "#+PARENT: [[task:" linked-plan-parent-id "][Parent with linked plan]]\n"
                   "#+SETUPFILE: ../../TASKS.setup.org\n"
                   "\n"
                   "* Summary\n"
                   "Record for the moved task.\n"
                   "\n"
                   "* Plan\n"
                   "** TODO Plan child\n"
                   ":PROPERTIES:\n"
                   ":CUSTOM_ID: " linked-plan-child-id "\n"
                   ":END:\n"))
        (let [record-before (slurp record)
              {:keys [out exit]}
              (run-cli! "--root" root "--format" "json"
                        "move" linked-plan-parent-id "--parent" group-id)
              after (slurp (tasks-org root))]
          (is (zero? exit) out)
          (testing "the #+IMPORT: link travels with the task"
            (is (str/includes? after "*** TODO Parent with linked plan"))
            (is (str/includes? after "#+IMPORT: [[plan:linked-plan.org]]")))
          (testing "the record's task: parent link is untouched and still valid"
            (is (= record-before (slurp record))))
          (testing "the record and its plan tasks still resolve from the moved task"
            (let [r (parse-json-result
                      (:out (run-cli! "--root" root "--format" "json"
                                      "show" linked-plan-parent-id)))]
              (is (= record (get-in r [:record :path])))
              (is (= [linked-plan-child-id]
                     (mapv :id (get-in r [:task :importChildren]))))
              (is (= [group-id] (mapv :id (:ancestors r))))))
          (testing "the record file remains the plan child's only writable node"
            (let [rows (:rows (parse-json-result
                                (:out (run-cli! "--root" root "--format" "json" "list"))))]
              (is (= 1 (count (filter #(= linked-plan-child-id (:id %)) rows))))
              (is (= record (:sourcePath (first (filter #(= linked-plan-child-id (:id %))
                                                        rows))))))))))))

(deftest move-inside-a-change-record-persists-to-the-record
  (with-temp-dir
    (fn [root]
      (bootstrap-linked-plan-graph! root)
      (let [record (str (fs/path root "design" "log" "linked-plan.org"))
            tasks-before (slurp (tasks-org root))
            {:keys [out exit]}
            (run-cli! "--root" root "--format" "json"
                      "move" linked-plan-second-child-id "--parent" linked-plan-child-id)
            r (parse-json-result out)
            after (slurp record)]
        (is (zero? exit) out)
        (is (= record (:file r)))
        (is (= 2 (:fromLevel r)))
        (is (= 3 (:toLevel r)))
        (is (str/includes? after "*** TODO Second plan child"))
        (is (str/includes? after "Acceptance criteria:"))
        (testing "the importing TASKS file is not rewritten"
          (is (= tasks-before (slurp (tasks-org root)))))))))

;; ── Envelope contract ─────────────────────────────────────────────

(deftest move-envelope-matches-the-documented-contract
  (with-temp-dir
    (fn [root]
      (bootstrap-move-graph! root)
      (let [{:keys [out]}
            (run-cli! "--root" root "--format" "json" "move" child-a1 "--parent" parent-b)
            r (parse-json-result out)]
        (is (= #{:task :file :parentId :section :previousParentId
                 :fromLevel :toLevel :movedCount :dryRun}
               (set (keys r)))
            "ot move result keys are contract (docs/contract.md § ot move)")
        (is (= (tasks-org root) (:file r)))))))

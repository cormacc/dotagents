(ns org-tasks.commands-test
  "Integration tests for `ot init / list / show / select / selected / status`.

  Each test spins up a temp project root, writes a small TASKS.org
  (and optionally TASKS.local.org), invokes a command, and asserts on
  both the JSON envelope and the on-disk side-effects."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org-tasks.cli :as cli]
            [org-tasks.output :as out]
            [org-tasks.styling :as styling]))

(defn- with-temp-dir [f]
  (let [dir (str (fs/create-temp-dir {:prefix "ot-cmd-"}))]
    (try (f dir)
         (finally (fs/delete-tree dir)))))

(defn- capture
  "Run `body-fn` while capturing stdout, stderr, and the intended exit
  code. The mock `*exit-fn*` throws a sentinel after recording so
  short-circuit semantics mirror real `System/exit`."
  [body-fn]
  (let [exit (atom nil)
        out  (java.io.StringWriter.)
        err  (java.io.StringWriter.)]
    (binding [out/*exit-fn* (fn [code]
                              (reset! exit code)
                              (throw (ex-info "ot-exit" {:tag :ot/exit :code code})))
              *out* out
              *err* err]
      (try
        (body-fn)
        (catch clojure.lang.ExceptionInfo e
          (when-not (= :ot/exit (:tag (ex-data e)))
            (throw e)))))
    {:out  (str out)
     :err  (str err)
     :exit (or @exit 0)}))

(defn- run-cli! [& args]
  (capture #(apply cli/-main args)))

(defn- parse-json-result [out-str]
  (-> out-str json/parse-string clojure.walk/keywordize-keys :result))

(defn- parse-json-error [err-str]
  (-> err-str json/parse-string clojure.walk/keywordize-keys :error))

;; ── init ─────────────────────────────────────────────────────────

;; ── uuid ─────────────────────────────────

(def ^:private uuid-v4-re
  #"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

(deftest uuid-text-output-emits-one-uuid-per-line
  (let [{:keys [out exit]} (run-cli! "uuid")]
    (is (zero? exit))
    (is (re-find uuid-v4-re (str/trim out)))))

(deftest uuid-count-emits-unique-v4-values
  (let [{:keys [out exit]} (run-cli! "--format" "json" "uuid" "--count" "5")
        r (parse-json-result out)]
    (is (zero? exit))
    (is (= 5 (:count r)))
    (is (= 5 (count (set (:uuids r)))))
    (doseq [u (:uuids r)]
      (is (re-find uuid-v4-re u) (str u " should be UUIDv4")))))

(deftest uuid-rejects-non-positive-count
  (let [{:keys [err exit]} (run-cli! "--format" "json" "uuid" "--count" "0")]
    (is (= 2 exit))
    (is (str/includes? err "--count"))))

(deftest init-creates-protocol-files
  (with-temp-dir
    (fn [root]
      (let [{:keys [out exit]}
            (run-cli! "--root" root "--format" "json" "init")
            r (parse-json-result out)]
        (is (zero? exit))
        (is (= 3 (count (:created r))))
        (is (true? (fs/exists? (fs/path root "TASKS.org"))))
        (is (true? (fs/exists? (fs/path root "TASKS.local.org"))))
        (is (true? (fs/exists? (fs/path root "TASKS.setup.org"))))
        (let [tasks-content (slurp (str (fs/path root "TASKS.org")))]
          (is (str/includes? tasks-content "#+SETUPFILE: ./TASKS.local.org"))
          (is (str/includes? tasks-content "#+SETUPFILE: ./TASKS.setup.org"))
          (is (str/includes? tasks-content "* Improvements")))))))

(deftest init-skips-existing-files
  (with-temp-dir
    (fn [root]
      (spit (str (fs/path root "TASKS.org")) "* Existing\n")
      (let [{:keys [out exit]} (run-cli! "--root" root "--format" "json" "init")
            r (parse-json-result out)]
        (is (zero? exit))
        (is (= 2 (count (:created r))))
        (is (= 1 (count (:skipped r))))
        (is (= "* Existing\n" (slurp (str (fs/path root "TASKS.org")))))))))

;; ── list ─────────────────────────────────────────────────────────

(def ^:private tasks-org-preamble
  ;; Mirrors `commands/tasks-org-default` so doctor sees the full
  ;; protocol preamble during integration tests.
  (str "#+TITLE: Project Tasks\n"
       "#+LINK: task file:TASKS.org::#%s\n"
       "#+LINK: archive file:TASKS.archive.org::#%s\n"
       "#+SETUPFILE: ./TASKS.local.org\n"
       "#+SETUPFILE: ./TASKS.setup.org\n"
       "#+ARCHIVE: TASKS.archive.org::* From %s\n"
       "\n"))

(def ^:private setup-org-preamble
  (str "#+TODO: TODO(t) STARTED(s!) WAITING(w@/!) | DONE(d!) CANCELLED(c!)\n"
       "#+STARTUP: logdone logdrawer\n"
       "#+LINK: plan file:design/log/%s\n"
       "#+LINK: task file:../../TASKS.org::#%s\n"
       "#+LINK: archive file:../../TASKS.archive.org::#%s\n"))

(defn- bootstrap-graph! [root]
  (spit (str (fs/path root "TASKS.setup.org")) setup-org-preamble)
  (spit (str (fs/path root "TASKS.org"))
        (str tasks-org-preamble
             "* Improvements\n"
             "** TODO [#A] First :backend:\n"
             ":PROPERTIES:\n"
             ":CUSTOM_ID: 11111111-2222-4333-8444-555555555551\n"
             ":END:\n"
             "** STARTED Second\n"
             ":PROPERTIES:\n"
             ":CUSTOM_ID: 22229999-2222-4333-8444-555555555552\n"
             ":STARTED: [2026-05-01 Fri 09:00]\n"
             ":END:\n"))
  (spit (str (fs/path root "TASKS.local.org"))
        (str "#+SELECTED: 22229999-2222-4333-8444-555555555552\n")))

(deftest list-emits-tree-and-rows
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [{:keys [out exit]}
            (run-cli! "--root" root "--format" "json" "list")
            r (parse-json-result out)]
        (is (zero? exit))
        (is (= 2 (count (:rows r))))
        (is (= 2 (count (:tree r))))
        (is (= "22229999-2222-4333-8444-555555555552" (:selectedId r)))
        (let [first-row  (first (:rows r))
              second-row (second (:rows r))]
          (is (= "TODO"    (:status first-row)))
          (is (= "A"       (:priority first-row)))
          (is (= ["backend"] (:tags first-row)))
          (is (= "STARTED" (:status second-row))))))))

(deftest list-text-output
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [{:keys [out exit]} (run-cli! "--root" root "list")]
        (is (zero? exit))
        (is (str/includes? out "TODO"))
        (is (str/includes? out "STARTED"))
        (is (str/includes? out "★"))))))

(defn- bootstrap-parent-child-graph! [root]
  (spit (str (fs/path root "TASKS.setup.org")) setup-org-preamble)
  (spit (str (fs/path root "TASKS.org"))
        (str tasks-org-preamble
             "* Improvements\n"
             "** TODO Parent A\n"
             ":PROPERTIES:\n"
             ":CUSTOM_ID: aaaa1111-2222-4333-8444-555555555551\n"
             ":END:\n"
             "*** TODO Child A1\n"
             ":PROPERTIES:\n"
             ":CUSTOM_ID: aaaa2222-2222-4333-8444-555555555552\n"
             ":END:\n"
             "*** TODO Child A2\n"
             ":PROPERTIES:\n"
             ":CUSTOM_ID: aaaa3333-2222-4333-8444-555555555553\n"
             ":END:\n"
             "** TODO Parent B\n"
             ":PROPERTIES:\n"
             ":CUSTOM_ID: bbbb1111-2222-4333-8444-555555555554\n"
             ":END:\n"))
  (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n"))

(defn- bootstrap-three-level-graph! [root]
  (spit (str (fs/path root "TASKS.setup.org")) setup-org-preamble)
  (spit (str (fs/path root "TASKS.org"))
        (str tasks-org-preamble
             "* Improvements\n"
             "** TODO Parent A\n"
             ":PROPERTIES:\n"
             ":CUSTOM_ID: aaaa1111-2222-4333-8444-555555555551\n"
             ":END:\n"
             "*** TODO Child A1\n"
             ":PROPERTIES:\n"
             ":CUSTOM_ID: aaaa2222-2222-4333-8444-555555555552\n"
             ":END:\n"
             "**** TODO Grandchild A1a\n"
             ":PROPERTIES:\n"
             ":CUSTOM_ID: aaaa3333-2222-4333-8444-555555555553\n"
             ":END:\n"
             "** TODO Parent B\n"
             ":PROPERTIES:\n"
             ":CUSTOM_ID: bbbb1111-2222-4333-8444-555555555554\n"
             ":END:\n"))
  (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n"))

(deftest list-levels-zero-shows-only-top-level
  (with-temp-dir
    (fn [root]
      (bootstrap-three-level-graph! root)
      (let [{:keys [out exit]}
            (run-cli! "--root" root "--format" "json" "list" "--levels" "0")
            r (parse-json-result out)]
        (is (zero? exit))
        (is (= ["Parent A" "Parent B"]
               (mapv :summary (:rows r))))))))

(deftest list-levels-one-includes-direct-children
  (with-temp-dir
    (fn [root]
      (bootstrap-three-level-graph! root)
      (let [{:keys [out exit]}
            (run-cli! "--root" root "--format" "json" "list" "--levels" "1")
            r (parse-json-result out)]
        (is (zero? exit))
        (is (= ["Parent A" "Child A1" "Parent B"]
               (mapv :summary (:rows r))))
        (is (not (some #{"Grandchild A1a"} (map :summary (:rows r))))))))) 

(deftest list-without-levels-includes-the-full-graph
  (with-temp-dir
    (fn [root]
      (bootstrap-three-level-graph! root)
      (let [{:keys [out exit]} (run-cli! "--root" root "--format" "json" "list")
            r (parse-json-result out)]
        (is (zero? exit))
        (is (= ["Parent A" "Child A1" "Grandchild A1a" "Parent B"]
               (mapv :summary (:rows r))))))))

(deftest list-levels-short-alias-works
  (with-temp-dir
    (fn [root]
      (bootstrap-three-level-graph! root)
      (let [{:keys [out exit]}
            (run-cli! "--root" root "--format" "json" "list" "-l" "0")
            r (parse-json-result out)]
        (is (zero? exit))
        (is (= ["Parent A" "Parent B"]
               (mapv :summary (:rows r))))))))

(deftest list-levels-rejects-negative
  (with-temp-dir
    (fn [root]
      (bootstrap-three-level-graph! root)
      (let [{:keys [err exit]}
            (run-cli! "--root" root "--format" "json" "list" "--levels" "-1")]
        (is (= 2 exit))
        (is (str/includes? err "--levels"))))))

(deftest list-renders-tree-prefixes
  (with-temp-dir
    (fn [root]
      (bootstrap-parent-child-graph! root)
      (let [{:keys [out exit]} (run-cli! "--root" root "--no-color" "list")]
        (is (zero? exit))
        (let [lines (str/split-lines out)
              parent-a (first (filter #(str/includes? % "Parent A") lines))
              child-a1 (first (filter #(str/includes? % "Child A1") lines))
              child-a2 (first (filter #(str/includes? % "Child A2") lines))
              parent-b (first (filter #(str/includes? % "Parent B") lines))
              status-col (fn [line]
                           (let [m (re-find #"\b(TODO|STARTED|DONE|WAITING|CANCELLED)\b" line)]
                             (when m (str/index-of line (first m)))))]
          (testing "top-level rows have no tree glyph"
            (is (not (re-find #"^[\s│]*[├└]" parent-a)))
            (is (not (re-find #"^[\s│]*[├└]" parent-b))))
          (testing "subtask tree glyph aligns under parent's STATUS column"
            (let [parent-col (status-col parent-a)]
              (is (some? parent-col))
              (is (= "├─" (subs child-a1 parent-col (+ parent-col 2))))
              (is (= "└─" (subs child-a2 parent-col (+ parent-col 2))))))
          (testing "no space between the tree glyph and the subtask STATUS"
            (let [parent-col (status-col parent-a)
                  after-glyph (+ parent-col 2)]
              (is (= "TODO" (subs child-a1 after-glyph (+ after-glyph 4))))
              (is (= "TODO" (subs child-a2 after-glyph (+ after-glyph 4)))))))))))

(deftest list-no-color-output-is-ansi-free
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [{:keys [out exit]} (run-cli! "--root" root "--no-color" "list")]
        (is (zero? exit))
        (is (not (re-find styling/ansi-re out)))
        (is (str/includes? out "TODO"))
        (is (str/includes? out "STARTED"))))))

;; ── show ─────────────────────────────────────────────────────────

(deftest show-by-id
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [{:keys [out exit]}
            (run-cli! "--root" root "--format" "json"
                      "show" "11111111-2222-4333-8444-555555555551")
            r (parse-json-result out)]
        (is (zero? exit))
        (is (= "First" (get-in r [:task :summary])))
        (is (= "A"     (get-in r [:task :priority])))))))

(defn- bootstrap-short-id-graph! [root]
  (spit (str (fs/path root "TASKS.setup.org")) setup-org-preamble)
  (spit (str (fs/path root "TASKS.org"))
        (str tasks-org-preamble
             "* Improvements\n"
             "** TODO Alpha\n"
             ":PROPERTIES:\n"
             ":CUSTOM_ID: abcd1111-2222-4333-8444-555555555551\n"
             ":END:\n"
             "** TODO Beta\n"
             ":PROPERTIES:\n"
             ":CUSTOM_ID: abcd9999-2222-4333-8444-555555555559\n"
             ":END:\n"
             "** TODO Gamma\n"
             ":PROPERTIES:\n"
             ":CUSTOM_ID: beef1111-2222-4333-8444-555555555552\n"
             ":END:\n"))
  (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n"))

(deftest show-by-short-id
  (with-temp-dir
    (fn [root]
      (bootstrap-short-id-graph! root)
      (let [{:keys [out exit]}
            (run-cli! "--root" root "--format" "json" "show" "beef1111")
            r (parse-json-result out)]
        (is (zero? exit))
        (is (= "Gamma" (get-in r [:task :summary])))
        (is (= "beef1111-2222-4333-8444-555555555552"
               (get-in r [:task :id])))))))

(deftest short-id-ambiguity-errors
  (with-temp-dir
    (fn [root]
      (bootstrap-short-id-graph! root)
      (let [{:keys [err exit]}
            (run-cli! "--root" root "--format" "json" "show" "abcd")
            e (parse-json-error err)]
        (is (= 1 exit))
        (is (= "ambiguous-id" (:code e)))
        (is (= ["Alpha" "Beta"]
               (mapv :summary (get-in e [:details :matches]))))))))

(deftest show-unknown-task-errors
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [{:keys [err exit]}
            (run-cli! "--root" root "--format" "json"
                      "show" "deadbeef-0000-4000-8000-000000000000")
            e (parse-json-error err)]
        (is (= 1 exit))
        (is (= "unknown-task" (:code e)))))))

;; ── select / selected ──────────────────────────────────────────────

(deftest select-and-clear
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (testing "select an existing task writes #+SELECTED:"
        (let [{:keys [exit]}
              (run-cli! "--root" root "--format" "json"
                        "select" "11111111-2222-4333-8444-555555555551")]
          (is (zero? exit))
          (is (str/includes? (slurp (str (fs/path root "TASKS.local.org")))
                             "11111111-2222-4333-8444-555555555551"))))
      (testing "clear removes #+SELECTED:"
        (let [{:keys [exit]}
              (run-cli! "--root" root "--format" "json" "select" "--clear")]
          (is (zero? exit))
          (let [content (slurp (str (fs/path root "TASKS.local.org")))]
            (is (not (re-find #"(?im)^#\+SELECTED:" content)))))))))

(deftest select-unknown-task-errors
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [{:keys [err exit]}
            (run-cli! "--root" root "--format" "json"
                      "select" "deadbeef-0000-4000-8000-000000000000")
            e (parse-json-error err)]
        (is (= 1 exit))
        (is (= "unknown-task" (:code e)))))))

(deftest selected-shows-current-task
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [{:keys [out exit]}
            (run-cli! "--root" root "--format" "json" "selected")
            r (parse-json-result out)]
        (is (zero? exit))
        (is (= "Second" (get-in r [:task :summary])))))))

;; ── status ──────────────────────────────────────────────────────

(deftest status-cycles-with-logbook
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [id "11111111-2222-4333-8444-555555555551"
            {:keys [out exit]}
            (run-cli! "--root" root "--format" "json" "status" id "STARTED")
            r (parse-json-result out)]
        (is (zero? exit))
        (is (= "TODO"    (:prevStatus r)))
        (is (= "STARTED" (:status r)))
        (is (some? (:started r)))
        (is (nil? (:closed r)))
        (let [content (slurp (str (fs/path root "TASKS.org")))]
          (is (str/includes? content "* STARTED [#A] First"))
          (is (str/includes? content ":STARTED:"))
          (is (str/includes? content "- State \"STARTED\" from \"TODO\""))))

      (let [id "11111111-2222-4333-8444-555555555551"
            {:keys [out]}
            (run-cli! "--root" root "--format" "json" "status" id "DONE")
            r (parse-json-result out)]
        (is (= "STARTED" (:prevStatus r)))
        (is (= "DONE"    (:status r)))
        (is (some?       (:closed r)))
        (let [content (slurp (str (fs/path root "TASKS.org")))]
          (is (str/includes? content "CLOSED:")))))))

(deftest status-dry-run-does-not-write
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [before (slurp (str (fs/path root "TASKS.org")))
            id "11111111-2222-4333-8444-555555555551"]
        (run-cli! "--root" root "--format" "json"
                  "--dry-run" "status" id "DONE")
        (is (= before (slurp (str (fs/path root "TASKS.org")))))))))

(deftest status-invalid-status-errors
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [{:keys [err exit]}
            (run-cli! "--root" root "--format" "json"
                      "status" "11111111-2222-4333-8444-555555555551" "BOGUS")
            e (parse-json-error err)]
        (is (= 1 exit))
        (is (= "invalid-status" (:code e)))))))

;; ── create ───────────────────────────────────────────

(deftest create-inserts-under-section
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [{:keys [out exit]}
            (run-cli! "--root" root "--format" "json"
                      "create" "New feature"
                      "--priority" "High"
                      "--tag" "backend")
            r (parse-json-result out)
            content (slurp (str (fs/path root "TASKS.org")))]
        (is (zero? exit))
        (is (some? (:id r)))
        (is (str/includes? content "** TODO [#B] New feature :backend:"))
        (is (str/includes? content (str ":CUSTOM_ID: " (:id r))))
        (is (str/includes? content ":CREATED:"))
        (is (str/includes? content ":LOGBOOK:"))
        (is (str/includes? content "- Created "))))))

(deftest create-empty-summary-errors
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [{:keys [err exit]}
            (run-cli! "--root" root "--format" "json" "create" "  ")
            e (parse-json-error err)]
        (is (= 1 exit))
        (is (= "empty-summary" (:code e)))))))

(deftest create-duplicate-linked-issue-refused
  (with-temp-dir
    (fn [root]
      (spit (str (fs/path root "TASKS.org"))
            (str "* Improvements\n"
                 "** TODO Existing clone\n"
                 ":PROPERTIES:\n"
                 ":CUSTOM_ID: 11111111-2222-4333-8444-555555555551\n"
                 ":LINKED_ISSUES: [[jira:ABC-1]] [[jira:ABC-2]]\n"
                 ":END:\n"))
      (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n")
      (let [{:keys [err exit]}
            (run-cli! "--root" root "--format" "json"
                      "create" "Re-clone"
                      "--linked-issue" "[[jira:ABC-1]]")
            e (parse-json-error err)]
        (is (= 1 exit))
        (is (= "duplicate-linked-issue" (:code e)))
        (is (= "[[jira:ABC-1]]" (get-in e [:details :conflictingToken])))))))

(deftest create-missing-section-errors
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [{:keys [err exit]}
            (run-cli! "--root" root "--format" "json"
                      "create" "Whatever" "--section" "NoSuch")
            e (parse-json-error err)]
        (is (= 1 exit))
        (is (= "section-not-found" (:code e)))))))

(deftest create-allow-create-section-appends
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [{:keys [out exit]}
            (run-cli! "--root" root "--format" "json"
                      "create" "Fresh" "--section" "Brand New"
                      "--allow-create-section")
            r (parse-json-result out)
            content (slurp (str (fs/path root "TASKS.org")))]
        (is (zero? exit))
        (is (str/includes? content "* Brand New"))
        (is (str/includes? content "** TODO Fresh"))))))

(deftest create-dry-run-does-not-write
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [before (slurp (str (fs/path root "TASKS.org")))]
        (run-cli! "--root" root "--format" "json"
                  "--dry-run" "create" "Dry-run task")
        (is (= before (slurp (str (fs/path root "TASKS.org")))))))))

(deftest create-after-inserts-below-anchor
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [{:keys [exit]}
            (run-cli! "--root" root "--format" "json"
                      "create" "Between" "--after"
                      "11111111-2222-4333-8444-555555555551")
            content (slurp (str (fs/path root "TASKS.org")))]
        (is (zero? exit))
        (is (< (str/index-of content "** TODO [#A] First")
               (str/index-of content "** TODO Between")
               (str/index-of content "** STARTED Second")))))))

(deftest create-parent-inserts-child-task
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [{:keys [out exit]}
            (run-cli! "--root" root "--format" "json"
                      "create" "Child task" "--parent"
                      "11111111-2222-4333-8444-555555555551")
            r (parse-json-result out)
            content (slurp (str (fs/path root "TASKS.org")))]
        (is (zero? exit))
        (is (some? (:id r)))
        (is (str/includes? content "*** TODO Child task"))
        (is (< (str/index-of content "** TODO [#A] First")
               (str/index-of content "*** TODO Child task")
               (str/index-of content "** STARTED Second")))))))

(deftest create-parent-honours-tasks-source-override
  (with-temp-dir
    (fn [root]
      (fs/create-dirs (str (fs/path root "design" "log")))
      (let [plan-path (str (fs/path root "design" "log" "feature.org"))
            parent-id "plan-parent-1111-4222-8333-444444444444"]
        (spit plan-path
              (str "* Plan\n"
                   "** TODO Plan parent\n"
                   ":PROPERTIES:\n"
                   ":CUSTOM_ID: " parent-id "\n"
                   ":END:\n"))
        (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n")
        (let [{:keys [out exit]}
              (run-cli! "--root" root "--tasks" plan-path "--format" "json"
                        "create" "Plan child" "--parent" parent-id)
              r (parse-json-result out)
              content (slurp plan-path)]
          (is (zero? exit))
          (is (some? (:id r)))
          (is (= plan-path (:file r)))
          (is (str/includes? content "*** TODO Plan child")))))))

;; ── doctor ───────────────────────────────────────────

(deftest doctor-clean-graph
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [{:keys [out exit]}
            (run-cli! "--root" root "--format" "json" "doctor")
            r (parse-json-result out)]
        (is (zero? exit))
        (is (= [] (:findings r)))
        (is (= {:error 0 :warn 0} (:counts r)))))))

(deftest doctor-detects-duplicate-id
  (with-temp-dir
    (fn [root]
      (spit (str (fs/path root "TASKS.setup.org")) setup-org-preamble)
      (spit (str (fs/path root "TASKS.org"))
            (str tasks-org-preamble
                 "* Improvements\n"
                 "** TODO First\n"
                 ":PROPERTIES:\n"
                 ":CUSTOM_ID: same-id-xxxx-yyyy-zzzz-000000000000\n"
                 ":END:\n"
                 "** TODO Second\n"
                 ":PROPERTIES:\n"
                 ":CUSTOM_ID: same-id-xxxx-yyyy-zzzz-000000000000\n"
                 ":END:\n"))
      (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n")
      (let [{:keys [out exit]}
            (run-cli! "--root" root "--format" "json" "doctor")
            r (parse-json-result out)
            dup (filter #(= "duplicate-id" (:code %)) (:findings r))]
        (is (zero? exit))
        (is (= 2 (count dup)))
        (is (= 2 (get-in r [:counts :error])))))))

;; ── section ───────────────────────────────────────────

(deftest section-returns-found-body
  (with-temp-dir
    (fn [root]
      (let [plan-path (str (fs/path root "plan.org"))]
        (spit plan-path
              (str "#+TITLE: Plan\n\n"
                   "* Summary\nCompact summary.\n\n"
                   "* Plan\n** TODO Step\n"))
        (let [{:keys [out exit]}
              (run-cli! "--root" root "--format" "json"
                        "section" "plan.org" "Summary")
              r (parse-json-result out)]
          (is (zero? exit))
          (is (true? (:found r)))
          (is (= "* Summary" (:heading r)))
          (is (str/includes? (:body r) "Compact summary.")))))))

(deftest section-not-found-returns-structured-result
  (with-temp-dir
    (fn [root]
      (spit (str (fs/path root "plan.org"))
            "#+TITLE: Plan\n\n* Plan\nlater\n")
      (let [{:keys [out exit]}
            (run-cli! "--root" root "--format" "json"
                      "section" "plan.org" "Summary")
            r (parse-json-result out)]
        (is (zero? exit))
        (is (false? (:found r)))
        (is (= "Summary" (:section r)))))))

(deftest section-rejects-out-of-root
  (with-temp-dir
    (fn [root]
      (let [{:keys [err exit]}
            (run-cli! "--root" root "--format" "json"
                      "section" "../escape.org" "Summary")
            e (parse-json-error err)]
        (is (= 1 exit))
        (is (= "out-of-root" (:code e)))))))

;; ── scan ─────────────────────────────────────────────

(deftest scan-emits-rows-with-counts
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [{:keys [out exit]}
            (run-cli! "--root" root "--format" "json" "scan" "--scope" "active")
            r (parse-json-result out)]
        (is (zero? exit))
        (is (= 2 (:count r)))
        (is (= "active" (:scope r)))
        (is (= ["First" "Second"] (mapv :summary (:rows r))))))))

;; ── publish / unpublish ──────────────────────────────────

(defn- bootstrap-local! [root id summary]
  (spit (str (fs/path root "TASKS.org")) "* Improvements\n")
  (spit (str (fs/path root "TASKS.local.org"))
        (str "#+SELECTED:\n\n"
             "* Drafts\n"
             "** TODO " summary "\n"
             ":PROPERTIES:\n"
             ":CUSTOM_ID: " id "\n"
             ":END:\n")))

(deftest publish-moves-local-to-shared
  (with-temp-dir
    (fn [root]
      (let [id "local-aaaa-bbbb-cccc-dddddddddddd"]
        (bootstrap-local! root id "Draft task")
        (let [{:keys [out exit]}
              (run-cli! "--root" root "--format" "json" "publish" id)
              r (parse-json-result out)
              shared (slurp (str (fs/path root "TASKS.org")))
              local  (slurp (str (fs/path root "TASKS.local.org")))]
          (is (zero? exit))
          (is (str/includes? shared (str ":CUSTOM_ID: " id)))
          (is (not (str/includes? local (str ":CUSTOM_ID: " id))))
          (is (false? (get-in r [:task :local]))))))))

(deftest unpublish-moves-shared-to-local
  (with-temp-dir
    (fn [root]
      (let [id "shared-aaa-bbbb-cccc-dddddddddddd"]
        (spit (str (fs/path root "TASKS.org"))
              (str "* Improvements\n"
                   "** TODO Shared task\n"
                   ":PROPERTIES:\n"
                   ":CUSTOM_ID: " id "\n"
                   ":END:\n"))
        (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n")
        (let [{:keys [out exit]}
              (run-cli! "--root" root "--format" "json" "unpublish" id)
              r (parse-json-result out)
              shared (slurp (str (fs/path root "TASKS.org")))
              local  (slurp (str (fs/path root "TASKS.local.org")))]
          (is (zero? exit))
          (is (not (str/includes? shared (str ":CUSTOM_ID: " id))))
          (is (str/includes? local (str ":CUSTOM_ID: " id)))
          (is (true? (get-in r [:task :local]))))))))

;; ── archive ──────────────────────────────────────────

(deftest archive-moves-closed-top-level-task
  (with-temp-dir
    (fn [root]
      (let [id "closed-aaa-bbbb-cccc-dddddddddddd"]
        (spit (str (fs/path root "TASKS.org"))
              (str "* Improvements\n"
                   "** DONE Closed task\n"
                   "CLOSED: [2026-05-01 Fri 09:00]\n"
                   ":PROPERTIES:\n"
                   ":CUSTOM_ID: " id "\n"
                   ":END:\n"))
        (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n")
        (let [{:keys [out exit]}
              (run-cli! "--root" root "--format" "json" "archive" id)
              r (parse-json-result out)
              shared  (slurp (str (fs/path root "TASKS.org")))
              archive (slurp (str (fs/path root "TASKS.archive.org")))]
          (is (zero? exit))
          (is (not (str/includes? shared (str ":CUSTOM_ID: " id))))
          (is (str/includes? archive (str ":CUSTOM_ID: " id)))
          (is (str/includes? archive ":ARCHIVED:"))
          (is (= "2026-05-01 Fri 09:00" (:archivedAt r))))))))

(deftest archive-refuses-open-task
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [{:keys [err exit]}
            (run-cli! "--root" root "--format" "json"
                      "archive" "11111111-2222-4333-8444-555555555551")
            e (parse-json-error err)]
        (is (= 1 exit))
        (is (= "validation" (:code e)))))))

(deftest archive-refuses-local-task
  (with-temp-dir
    (fn [root]
      (let [id "local-aaaa-bbbb-cccc-dddddddddddd"]
        (bootstrap-local! root id "Draft local")
        (let [{:keys [err exit]}
              (run-cli! "--root" root "--format" "json" "archive" id)
              e (parse-json-error err)]
          (is (= 1 exit))
          (is (= "validation" (:code e))))))))

;; ── handoff ───────────────────────────────────────────

(deftest handoff-set-get-clear
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [id "11111111-2222-4333-8444-555555555551"]
        (testing "set"
          (let [{:keys [out exit]}
                (run-cli! "--root" root "--format" "json"
                          "handoff" "set" id "Start here next session")
                r (parse-json-result out)]
            (is (zero? exit))
            (is (= "Start here next session" (:handoff r)))
            (is (str/includes? (slurp (str (fs/path root "TASKS.org")))
                               ":HANDOFF: Start here next session"))))
        (testing "get"
          (let [{:keys [out]} (run-cli! "--root" root "--format" "json"
                                        "handoff" "get" id)
                r (parse-json-result out)]
            (is (= "Start here next session" (:handoff r)))))
        (testing "clear"
          (let [{:keys [out]} (run-cli! "--root" root "--format" "json"
                                        "handoff" "clear" id)
                r (parse-json-result out)]
            (is (nil? (:handoff r)))
            (is (not (str/includes? (slurp (str (fs/path root "TASKS.org")))
                                    ":HANDOFF:")))))))))

;; ── blocker + ready ────────────────────────────────────────

(deftest blocker-add-list-remove-ready
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [target "11111111-2222-4333-8444-555555555551"
            other  "22229999-2222-4333-8444-555555555552"]
        (testing "ready before blockers: ready=true"
          (let [{:keys [out]} (run-cli! "--root" root "--format" "json"
                                        "ready" target)
                r (parse-json-result out)]
            (is (true? (:ready r)))))
        (testing "add blocker referencing another task"
          (let [{:keys [exit]}
                (run-cli! "--root" root "--format" "json"
                          "blocker" "add" target (str "task:" other))]
            (is (zero? exit))
            (is (str/includes? (slurp (str (fs/path root "TASKS.org")))
                               (str ":BLOCKED-BY: task:" other)))))
        (testing "ready after blocker on STARTED dep: not ready"
          (let [{:keys [out]} (run-cli! "--root" root "--format" "json"
                                        "ready" target)
                r (parse-json-result out)]
            (is (false? (:ready r)))
            (is (= 1 (count (:gating r))))
            (is (= "unresolved-task" (get-in r [:gating 0 :reason])))))
        (testing "list shows the blocker"
          (let [{:keys [out]} (run-cli! "--root" root "--format" "json"
                                        "blocker" "list" target)
                r (parse-json-result out)]
            (is (= 1 (count (:blockers r))))
            (is (= "task" (get-in r [:blockers 0 :kind])))))
        (testing "remove restores ready"
          (run-cli! "--root" root "--format" "json"
                    "blocker" "remove" target (str "task:" other))
          (let [{:keys [out]} (run-cli! "--root" root "--format" "json"
                                        "ready" target)
                r (parse-json-result out)]
            (is (true? (:ready r)))))))))

;; ── issue ──────────────────────────────────────────────

(deftest issue-add-list-remove-urls
  (with-temp-dir
    (fn [root]
      (spit (str (fs/path root "TASKS.setup.org")) setup-org-preamble)
      (spit (str (fs/path root "TASKS.org"))
            (str tasks-org-preamble
                 "#+LINK: jira https://example.atlassian.net/browse/%s\n\n"
                 "* Improvements\n"
                 "** TODO Task\n"
                 ":PROPERTIES:\n"
                 ":CUSTOM_ID: 22222222-2222-4333-8444-555555555551\n"
                 ":END:\n"))
      (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n")
      (let [id "22222222-2222-4333-8444-555555555551"]
        (run-cli! "--root" root "--format" "json" "issue" "add" id "[[jira:ABC-1]]")
        (testing "list returns the added token with resolved URL"
          (let [{:keys [out]} (run-cli! "--root" root "--format" "json"
                                        "issue" "list" id)
                r (parse-json-result out)]
            (is (= 1 (count (:issues r))))
            (is (= "[[jira:ABC-1]]" (get-in r [:issues 0 :rawToken])))
            (is (= "https://example.atlassian.net/browse/ABC-1"
                   (get-in r [:issues 0 :url])))))
        (testing "urls returns only resolvable URLs"
          (let [{:keys [out]} (run-cli! "--root" root "--format" "json"
                                        "issue" "urls" id)
                r (parse-json-result out)]
            (is (= ["https://example.atlassian.net/browse/ABC-1"] (:urls r)))))
        (testing "remove"
          (run-cli! "--root" root "--format" "json"
                    "issue" "remove" id "[[jira:ABC-1]]")
          (let [{:keys [out]} (run-cli! "--root" root "--format" "json"
                                        "issue" "list" id)
                r (parse-json-result out)]
            (is (= [] (:issues r)))))))))

;; ── record ────────────────────────────────────────────

(deftest record-path-suggests-from-plan-template
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [{:keys [out exit]}
            (run-cli! "--root" root "--format" "json"
                      "record" "path"
                      "11111111-2222-4333-8444-555555555551")
            r (parse-json-result out)]
        (is (zero? exit))
        (is (str/includes? (:suggested r) "design/log/"))
        (is (str/includes? (:suggested r) "-first.org"))))))

(deftest record-create-scaffolds-and-attaches-import
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (fs/create-dirs (str (fs/path root "design" "log")))
      (let [id "11111111-2222-4333-8444-555555555551"
            {:keys [out exit]}
            (run-cli! "--root" root "--format" "json"
                      "record" "create" id
                      "--path" "design/log/2026-05-18-feature.org")
            r (parse-json-result out)
            plan (slurp (str (fs/path root "design/log/2026-05-18-feature.org")))
            tasks-content (slurp (str (fs/path root "TASKS.org")))]
        (is (zero? exit))
        (is (true? (:created r)))
        (is (str/includes? plan "#+TITLE: First"))
        (is (str/includes? plan "#+PARENT: [[task:"))
        (is (str/includes? plan "#+SETUPFILE: ../../TASKS.setup.org"))
        (is (str/includes? plan "* Summary"))
        (is (str/includes? plan "* Plan"))
        (is (str/includes? plan "* Implementation"))
        (is (str/includes? plan "* Validation"))
        (is (str/includes? tasks-content "#+IMPORT: [[plan:2026-05-18-feature.org]]"))))))

(deftest record-create-migrates-existing-subtasks-into-plan
  (with-temp-dir
    (fn [root]
      (bootstrap-parent-child-graph! root)
      (fs/create-dirs (str (fs/path root "design" "log")))
      (let [id "aaaa1111-2222-4333-8444-555555555551"
            {:keys [out exit]}
            (run-cli! "--root" root "--format" "json"
                      "record" "create" id
                      "--path" "design/log/2026-05-18-parent-a.org")
            r (parse-json-result out)
            plan (slurp (str (fs/path root "design/log/2026-05-18-parent-a.org")))
            tasks-content (slurp (str (fs/path root "TASKS.org")))]
        (is (zero? exit))
        (is (true? (:absorbedSubtasks r)))
        (is (str/includes? tasks-content "** TODO Parent A"))
        (is (str/includes? tasks-content "#+IMPORT: [[plan:2026-05-18-parent-a.org]]"))
        (is (not (str/includes? tasks-content "*** TODO Child A1")))
        (is (str/includes? plan "* Plan\n** TODO Child A1"))
        (is (str/includes? plan ":CUSTOM_ID: aaaa2222-2222-4333-8444-555555555552"))
        (is (str/includes? plan "** TODO Child A2"))))))

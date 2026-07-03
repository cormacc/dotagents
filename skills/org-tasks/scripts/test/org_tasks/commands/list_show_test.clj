(ns org-tasks.commands.list-show-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org-tasks.commands.test-util :refer :all]
            [org-tasks.parser :as parser]
            [org-tasks.styling :as styling]))

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
        (is (= "A"     (get-in r [:task :priority])))
        (is (not (contains? (:task r) :sourceContent)))
        (is (not (contains? (:task r) :effectiveSourceContent)))
        (is (= [] (:ancestors r)))
        (is (nil? (:record r)))))))

(deftest show-include-content-flag
  (with-temp-dir
    (fn [root]
      (bootstrap-graph! root)
      (let [{:keys [out exit]}
            (run-cli! "--root" root "--format" "json"
                      "show" "11111111-2222-4333-8444-555555555551"
                      "--include-content")
            r (parse-json-result out)]
        (is (zero? exit))
        (is (contains? (:task r) :sourceContent))
        (is (contains? (:task r) :effectiveSourceContent))))))

(deftest show-linked-plan-record-and-ancestors
  (with-temp-dir
    (fn [root]
      (bootstrap-linked-plan-graph! root)
      (testing "linked parent record metadata"
        (let [{:keys [out exit]}
              (run-cli! "--root" root "--format" "json"
                        "show" linked-plan-parent-id)
              r (parse-json-result out)]
          (is (zero? exit))
          (is (= (str (fs/path root "design" "log" "linked-plan.org"))
                 (get-in r [:record :path])))
          (is (= ["Summary" "Plan"] (get-in r [:record :sections])))
          (is (false? (get-in r [:record :hasContext])))
          (is (false? (get-in r [:record :hasOpenQuestions])))
          (is (not (contains? (:record r) :sourceContent)))))
      (testing "imported child ancestors"
        (let [{:keys [out exit]}
              (run-cli! "--root" root "--format" "json"
                        "show" linked-plan-child-id)
              r (parse-json-result out)]
          (is (zero? exit))
          (is (= ["Parent with linked plan"] (mapv :summary (:ancestors r)))))))))

(defn- bootstrap-prefix-graph! [root]
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

(deftest show-by-id-prefix
  (with-temp-dir
    (fn [root]
      (bootstrap-prefix-graph! root)
      (let [{:keys [out exit]}
            (run-cli! "--root" root "--format" "json" "show" "beef1111")
            r (parse-json-result out)]
        (is (zero? exit))
        (is (= "Gamma" (get-in r [:task :summary])))
        (is (= "beef1111-2222-4333-8444-555555555552"
               (get-in r [:task :id])))))))

(deftest id-prefix-ambiguity-errors
  (with-temp-dir
    (fn [root]
      (bootstrap-prefix-graph! root)
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

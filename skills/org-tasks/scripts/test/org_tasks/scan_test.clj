(ns org-tasks.scan-test
  "Tests for `org-tasks.scan/scan-summaries`.

  Mirrors `pi/extensions/tasks/scan.test.ts`. Uses a synthetic
  `read-change-record` callback so the helper stays fs-free."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org-tasks.parser :as parser]
            [org-tasks.scan :as scan]))

(defn- parse [content path]
  (:tasks (parser/parse-tasks content {:source-path path})))

(defn- map-reader
  "Build a `read-change-record` callback that resolves on `:import-path`."
  [m]
  (fn [task]
    (when-let [p (:import-path task)]
      (get m p))))

(def ^:private rich-record
  (str/join "\n"
            ["#+TITLE: Rich record"
             ""
             "* Summary"
             "Compact paragraph capturing the final state."
             ""
             "** Decisions"
             "- Foo :: Bar."
             ""
             "* Context"
             "Background rationale that exceeds the synopsis."
             ""
             "* Plan"
             "** TODO Step"
             ""]))

(def ^:private no-summary-record
  (str/join "\n"
            ["#+TITLE: Sparse record"
             ""
             "* Plan"
             "** TODO Step"
             ""
             "* Implementation"
             "Notes."
             ""]))

(def ^:private summary-no-context-record
  (str/join "\n"
            ["* Summary"
             "Short summary; no Context heading."
             ""
             "* Plan"
             "** TODO Step"
             ""]))

(deftest empty-graph
  (is (= [] (scan/scan-summaries {:active-roots []
                                  :archived-roots []
                                  :read-change-record (constantly nil)}))))

(deftest single-task-full-record
  (let [content (str/join "\n"
                          ["* Improvements"
                           "** TODO Implement feature X :feat:area:"
                           ":PROPERTIES:"
                           ":CUSTOM_ID: 11111111-1111-4111-8111-111111111111"
                           ":CREATED: [2026-05-01 Fri 09:00]"
                           ":END:"
                           "#+IMPORT: design/log/feature-x.org"
                           ""])
        rows (scan/scan-summaries
               {:active-roots (parse content "/proj/TASKS.org")
                :archived-roots []
                :read-change-record (map-reader {"design/log/feature-x.org" rich-record})})]
    (is (= 1 (count rows)))
    (let [r (first rows)]
      (is (= "11111111-1111-4111-8111-111111111111" (:id r)))
      (is (= "Implement feature X" (:summary r)))
      (is (= "TODO" (:status r)))
      (is (nil? (:priority r)))
      (is (= ["feat" "area"] (:tags r)))
      (is (= "/proj/TASKS.org" (:sourcePath r)))
      (is (= "design/log/feature-x.org" (:importPath r)))
      (is (true? (:found (:recordSummary r))))
      (is (str/includes? (:body (:recordSummary r)) "Compact paragraph"))
      (is (true? (:hasContext r))))))

(deftest priority-cookie-captured
  (let [content (str/join "\n"
                          ["** TODO [#A] High-priority work"
                           ":PROPERTIES:"
                           ":CUSTOM_ID: 12121212-1212-4121-8121-121212121212"
                           ":END:"
                           ""])
        rows (scan/scan-summaries
               {:active-roots (parse content "/proj/TASKS.org")
                :archived-roots []
                :read-change-record (constantly nil)})]
    (is (= "A" (:priority (first rows))))))

(deftest missing-record-surfaces-found-false
  (let [content (str/join "\n"
                          ["** TODO Orphan task"
                           ":PROPERTIES:"
                           ":CUSTOM_ID: 22222222-2222-4222-8222-222222222222"
                           ":END:"
                           "#+IMPORT: design/log/missing.org"
                           ""])
        rows (scan/scan-summaries
               {:active-roots (parse content "/proj/TASKS.org")
                :archived-roots []
                :read-change-record (constantly nil)})]
    (is (= {:found false} (:recordSummary (first rows))))
    (is (false? (:hasContext (first rows))))))

(deftest record-without-summary
  (let [content (str/join "\n"
                          ["** DONE Closed task"
                           ":PROPERTIES:"
                           ":CUSTOM_ID: 33333333-3333-4333-8333-333333333333"
                           ":END:"
                           "#+IMPORT: design/log/no-summary.org"
                           ""])
        rows (scan/scan-summaries
               {:active-roots (parse content "/proj/TASKS.org")
                :archived-roots []
                :read-change-record (map-reader {"design/log/no-summary.org" no-summary-record})})]
    (is (= {:found false} (:recordSummary (first rows))))))

(deftest task-without-import
  (let [content (str/join "\n"
                          ["** TODO Quick fix"
                           ":PROPERTIES:"
                           ":CUSTOM_ID: 44444444-4444-4444-8444-444444444444"
                           ":END:"
                           ""])
        rows (scan/scan-summaries
               {:active-roots (parse content "/proj/TASKS.org")
                :archived-roots []
                :read-change-record (constantly nil)})]
    (is (nil? (:recordSummary (first rows))))
    (is (false? (:hasContext (first rows))))))

(deftest has-context-flag
  (let [content (str/join "\n"
                          ["** TODO With ctx"
                           ":PROPERTIES:"
                           ":CUSTOM_ID: 55555555-5555-4555-8555-555555555555"
                           ":END:"
                           "#+IMPORT: a.org"
                           ""
                           "** TODO Without ctx"
                           ":PROPERTIES:"
                           ":CUSTOM_ID: 66666666-6666-4666-8666-666666666666"
                           ":END:"
                           "#+IMPORT: b.org"
                           ""])
        rows (scan/scan-summaries
               {:active-roots (parse content "/proj/TASKS.org")
                :archived-roots []
                :read-change-record (map-reader {"a.org" rich-record
                                                  "b.org" summary-no-context-record})})]
    (is (= [["With ctx" true]
            ["Without ctx" false]]
           (mapv (juxt :summary :hasContext) rows)))))

(deftest scope-filters
  (let [active-content
        (str/join "\n"
                  ["** STARTED Live task"
                   ":PROPERTIES:"
                   ":CUSTOM_ID: 77777777-7777-4777-8777-777777777777"
                   ":END:"
                   ""])
        archived-content
        (str/join "\n"
                  ["** DONE Old task"
                   ":PROPERTIES:"
                   ":CUSTOM_ID: 88888888-8888-4888-8888-888888888888"
                   ":END:"
                   ""])
        active-roots   (parse active-content "/proj/TASKS.org")
        archived-roots (parse archived-content "/proj/TASKS.archive.org")
        input {:active-roots active-roots
               :archived-roots archived-roots
               :read-change-record (constantly nil)}]
    (is (= ["Live task"]
           (mapv :summary (scan/scan-summaries input {:scope :active}))))
    (is (= ["Old task"]
           (mapv :summary (scan/scan-summaries input {:scope :archived}))))
    (is (= ["Live task" "Old task"]
           (mapv :summary (scan/scan-summaries input {:scope :all}))))
    (is (= ["Live task" "Old task"]
           (mapv :summary (scan/scan-summaries input))))))

(deftest tag-filter
  (let [content (str/join "\n"
                          ["** TODO Backend feat :backend:security:"
                           ":PROPERTIES:"
                           ":CUSTOM_ID: 99999999-9999-4999-8999-999999999999"
                           ":END:"
                           ""
                           "** TODO Frontend feat :ui:"
                           ":PROPERTIES:"
                           ":CUSTOM_ID: aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
                           ":END:"
                           ""
                           "** TODO No tags"
                           ":PROPERTIES:"
                           ":CUSTOM_ID: bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"
                           ":END:"
                           ""])
        input {:active-roots (parse content "/proj/TASKS.org")
               :archived-roots []
               :read-change-record (constantly nil)}]
    (is (= ["Backend feat"]
           (mapv :summary (scan/scan-summaries input {:tags ["backend"]}))))
    (is (= ["Backend feat" "Frontend feat"]
           (mapv :summary (scan/scan-summaries input {:tags ["security" "ui"]}))))
    (is (= [] (scan/scan-summaries input {:tags ["nonexistent"]})))
    (is (= ["Backend feat" "Frontend feat" "No tags"]
           (mapv :summary (scan/scan-summaries input {:tags []}))))))

(deftest max-body-chars-truncation
  (let [long-summary (str/join "\n" ["* Summary" (apply str (repeat 2000 "x")) ""])
        content (str/join "\n"
                          ["** TODO Long task"
                           ":PROPERTIES:"
                           ":CUSTOM_ID: cccccccc-cccc-4ccc-8ccc-cccccccccccc"
                           ":END:"
                           "#+IMPORT: long.org"
                           ""])
        input-base {:active-roots (parse content "/proj/TASKS.org")
                    :archived-roots []
                    :read-change-record (map-reader {"long.org" long-summary})}]
    (testing "explicit max-body-chars 100 caps and appends sentinel"
      (let [r (first (scan/scan-summaries input-base {:max-body-chars 100}))
            body (:body (:recordSummary r))]
        (is (= 100 (count body)))
        (is (str/ends-with? body "\u2026"))))
    (testing "max-body-chars exceeding body length: no sentinel"
      (let [r (first (scan/scan-summaries input-base {:max-body-chars 5000}))]
        (is (not (str/ends-with? (:body (:recordSummary r)) "\u2026")))))
    (testing "default cap"
      (let [r (first (scan/scan-summaries input-base))]
        (is (= scan/default-max-body-chars
               (count (:body (:recordSummary r)))))))))

(deftest tasks-without-id-skipped
  (let [content (str/join "\n"
                          ["** TODO Has id"
                           ":PROPERTIES:"
                           ":CUSTOM_ID: dddddddd-dddd-4ddd-8ddd-dddddddddddd"
                           ":END:"
                           ""
                           "** TODO No id"
                           ""])
        rows (scan/scan-summaries
               {:active-roots (parse content "/proj/TASKS.org")
                :archived-roots []
                :read-change-record (constantly nil)})]
    (is (= ["Has id"] (mapv :summary rows)))))

(deftest plan-tasks-emit-own-rows
  (let [parent-content
        (str/join "\n"
                  ["** STARTED Parent workstream"
                   ":PROPERTIES:"
                   ":CUSTOM_ID: eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee"
                   ":END:"
                   "#+IMPORT: design/log/plan.org"
                   ""])
        plan-content
        (str/join "\n"
                  ["** TODO Plan step"
                   ":PROPERTIES:"
                   ":CUSTOM_ID: ffffffff-ffff-4fff-8fff-ffffffffffff"
                   ":END:"
                   ""])
        parent (parse parent-content "/proj/TASKS.org")
        ;; Simulate loader having walked plan.org and populated import-children
        plan-tasks (parse plan-content "/proj/design/log/plan.org")
        active (mapv #(assoc % :import-children plan-tasks) parent)
        rows (scan/scan-summaries
               {:active-roots active
                :archived-roots []
                :read-change-record (constantly nil)})]
    (is (= [{:summary "Parent workstream" :sourcePath "/proj/TASKS.org"}
            {:summary "Plan step" :sourcePath "/proj/design/log/plan.org"}]
           (mapv #(select-keys % [:summary :sourcePath]) rows)))
    (is (nil? (:recordSummary (second rows))))))

(ns org-tasks.mutation-locality-test
  "Characterisation tests for source locality of in-place task mutations."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org-tasks.commands.test-util :refer [parse-json-result run-cli! with-temp-dir]]
            [org-tasks.loader :as loader]
            [org-tasks.parser :as parser]
            [org-tasks.tree :as tree]))

(def target-id "11111111-2222-4333-8444-555555555551")

(defn- fixture []
  (let [before (str "#+TITLE: Locality fixture\n"
                    "#+SETUPFILE: ./TASKS.local.org\n"
                    "#+SETUPFILE: ./TASKS.setup.org\n\n"
                    "* Improvements\n")
        target (str "** TODO Parent\n"
                    "*** DONE Target\n"
                    ":PROPERTIES:\n"
                    ":CUSTOM_ID: " target-id "\n"
                    ":END:\n"
                    "CLOSED: [2026-01-01 Thu 00:00]\n"
                    "\n"
                    "Leading body text.\n"
                    "\n")
        after (str "\n*** DONE Untouched\n"
                   ":PROPERTIES:\n"
                   ":CUSTOM_ID: 22222222-2222-4333-8444-555555555552\n"
                   ":END:\n"
                   "CLOSED: [2026-01-01 Thu 00:00]\n"
                   "#+BEGIN_EXAMPLE\n"
                   "** TODO Literal task-shaped text\n"
                   "#+END_EXAMPLE\n")]
    {:before before :target target :after after :content (str before target after)}))

(defn- with-locality-fixture [f]
  (with-temp-dir
    (fn [root]
      (let [{:keys [content] :as parts} (fixture)]
        (spit (str (fs/path root "TASKS.setup.org")) "")
        (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n")
        (spit (str (fs/path root "TASKS.org")) content)
        (f root parts)))))

(defn- assert-locality! [{:keys [before after]} content]
  (is (str/starts-with? content before) "preamble and section remain byte-identical")
  (is (str/ends-with? content after) "unrelated task and block remain byte-identical"))

(defn- assert-noncanonical-target-layout! [content]
  (is (str/includes? content
                     (str ":END:\n"
                          "CLOSED: [2026-01-01 Thu 00:00]\n\n"
                          "Leading body text.\n\n"))
      "CLOSED placement and body blank lines remain byte-identical"))

(defn- assert-target-body-spacing! [content]
  (is (str/includes? content "\n\nLeading body text.\n\n")
      "leading and trailing body blank lines remain byte-identical"))

(defn- mutate! [root & args]
  (let [{:keys [out exit]} (apply run-cli! "--root" root "--format" "json" args)]
    (is (zero? exit))
    (parse-json-result out)))

(deftest status-transition-inserts-closed-before-existing-drawers
  (with-temp-dir
    (fn [root]
      (let [id "11111111-2222-4333-8444-555555555553"
            path (str (fs/path root "TASKS.org"))
            input (str "* TODO Target\n"
                       ":PROPERTIES:\n"
                       ":CUSTOM_ID: " id "\n"
                       ":END:\n"
                       ":LOGBOOK:\n"
                       "- Created [2026-08-18 Tue 00:00]\n"
                       ":END:\n"
                       "#+IMPORT: [[plan:record.org]]\n"
                       "Body.\n")]
        (spit (str (fs/path root "TASKS.setup.org")) "")
        (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n")
        (spit path input)
        (let [result (mutate! root "status" id "DONE")
              closed (:closed result)
              content (slurp path)]
          (is (= "DONE" (:status result)) "positive control: status changed")
          (is (some? closed) "positive control: CLOSED was inserted")
          (is (= (str "* DONE Target\n"
                     "CLOSED: [" closed "]\n"
                     ":PROPERTIES:\n"
                     ":CUSTOM_ID: " id "\n"
                     ":END:\n"
                     ":LOGBOOK:\n"
                     "- Created [2026-08-18 Tue 00:00]\n"
                     "- State \"DONE\" from \"TODO\" [" closed "]\n"
                     ":END:\n"
                     "#+IMPORT: [[plan:record.org]]\n"
                     "Body.\n")
                 content)))))))

(deftest variant-drawer-delimiters-survive-property-and-started-mutations
  (with-temp-dir
    (fn [root]
      (let [id "11111111-2222-4333-8444-555555555554"
            path (str (fs/path root "TASKS.org"))
            input (str "* TODO Target\n"
                       "  :properties:\n"
                       ":CUSTOM_ID: " id "\n"
                       "  :end:\n"
                       "  :logbook:\n"
                       "- Created [2026-08-18 Tue 00:00]\n"
                       "  :end:\n")]
        (spit (str (fs/path root "TASKS.setup.org")) "")
        (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n")
        (spit path input)
        (let [property-result (mutate! root "handoff" "set" id "Resume here")
              property-content (slurp path)]
          (is (= "Resume here" (:handoff property-result))
              "positive control: property command changed the task")
          (is (str/includes? property-content "  :properties:\n")
              "property opening delimiter remains byte-identical")
          (is (str/includes? property-content "  :end:\n")
              "property closing delimiter remains byte-identical"))
        (let [status-result (mutate! root "status" id "STARTED")
              started (:started status-result)
              content (slurp path)]
          (is (= "STARTED" (:status status-result))
              "positive control: status changed")
          (is (str/includes? content
                             (str "  :properties:\n"
                                  ":CUSTOM_ID: " id "\n"
                                  ":HANDOFF: Resume here\n"
                                  ":STARTED: [" started "]\n"
                                  "  :end:\n"))
              "properties delimiters remain byte-identical after STARTED")
          (is (str/includes? content
                             (str "  :logbook:\n"
                                  "- Created [2026-08-18 Tue 00:00]\n"
                                  "- State \"STARTED\" from \"TODO\" [" started "]\n"
                                  "  :end:\n"))
              "LOGBOOK delimiters remain byte-identical after STARTED"))))))

(deftest status-transition-inserts-closed-after-heading-before-body-and-drawers
  (with-temp-dir
    (fn [root]
      (let [id "11111111-2222-4333-8444-555555555555"
            path (str (fs/path root "TASKS.org"))
            input (str "* TODO Target\n"
                       "Body before drawers.\n"
                       ":PROPERTIES:\n"
                       ":CUSTOM_ID: " id "\n"
                       ":END:\n"
                       ":LOGBOOK:\n"
                       "- Created [2026-08-18 Tue 00:00]\n"
                       ":END:\n")]
        (spit (str (fs/path root "TASKS.setup.org")) "")
        (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n")
        (spit path input)
        (let [result (mutate! root "status" id "DONE")
              closed (:closed result)
              content (slurp path)]
          (is (= "DONE" (:status result)) "positive control: status changed")
          (is (str/starts-with? content
                                (str "* DONE Target\n"
                                     "CLOSED: [" closed "]\n"
                                     "Body before drawers.\n"))
              "CLOSED is inserted directly after the heading")
          (is (str/includes? content
                             (str "Body before drawers.\n"
                                  ":PROPERTIES:\n"
                                  ":CUSTOM_ID: " id "\n"
                                  ":END:\n"
                                  ":LOGBOOK:\n"
                                  "- Created [2026-08-18 Tue 00:00]\n"
                                  "- State \"DONE\" from \"TODO\" [" closed "]\n"
                                  ":END:\n"))
              "body and existing drawer bytes remain in place"))))))

(deftest command-mutations-preserve-unrelated-source-bytes
  (testing "priority and status replace only their target fields"
    (with-locality-fixture
      (fn [root parts]
        (let [priority (mutate! root "priority" target-id "B")
              content (slurp (str (fs/path root "TASKS.org")))]
          (is (= "B" (:priority priority)))
          (assert-locality! parts content)
          (assert-noncanonical-target-layout! content)
          (is (str/includes? content "*** DONE [#B] Target")))
        (let [status (mutate! root "status" target-id "STARTED")
              content (slurp (str (fs/path root "TASKS.org")))]
          (is (= "STARTED" (:status status)))
          (assert-locality! parts content)
          (assert-target-body-spacing! content)
          (is (str/includes? content "*** STARTED [#B] Target"))))))
  (testing "handoff, blockers, and linked issues change only target properties"
    (with-locality-fixture
      (fn [root parts]
        (mutate! root "handoff" "set" target-id "Resume here")
        (let [content (slurp (str (fs/path root "TASKS.org")))]
          (assert-locality! parts content)
          (assert-noncanonical-target-layout! content)
          (is (str/includes? content ":HANDOFF: Resume here")))
        (mutate! root "blocker" "add" target-id "human: review")
        (let [content (slurp (str (fs/path root "TASKS.org")))]
          (assert-locality! parts content)
          (assert-noncanonical-target-layout! content)
          (is (str/includes? content ":BLOCKED-BY: human: review")))
        (mutate! root "issue" "add" target-id "[[jira:OT-1]]")
        (let [content (slurp (str (fs/path root "TASKS.org")))]
          (assert-locality! parts content)
          (assert-noncanonical-target-layout! content)
          (is (str/includes? content ":LINKED_ISSUES: [[jira:OT-1]]"))))))
  (testing "a direct drawer-property update has the same locality"
    (with-locality-fixture
      (fn [root parts]
        (let [path (str (fs/path root "TASKS.org"))
              {:keys [tasks]} (loader/load-graph root {:tasks path
                                                       :local (str (fs/path root "TASKS.local.org"))
                                                       :archive (str (fs/path root "TASKS.archive.org"))})
              updated (tree/update-by-id tasks target-id
                                         #(parser/set-drawer-property % "LOCAL_NOTE" "value"))]
          (loader/save-source-roots-locality root updated)
          (let [content (slurp path)]
            (assert-locality! parts content)
            (assert-noncanonical-target-layout! content)
            (is (str/includes? content ":LOCAL_NOTE: value"))))))))

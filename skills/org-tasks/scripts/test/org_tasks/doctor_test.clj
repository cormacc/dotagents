(ns org-tasks.doctor-test
  "Tests for `org-tasks.doctor/run-doctor`.

  Mirrors `pi/extensions/tasks/doctor.test.ts`. Each scenario builds
  a small graph (parsed or synthesized) and asserts on which finding
  codes appear (or don't)."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org-tasks.doctor :as doctor]
            [org-tasks.parser :as parser]))

(defn- count-of [findings code]
  (count (filter #(= code (:code %)) findings)))

(deftest clean-graph-no-findings
  (let [{:keys [tasks]}
        (parser/parse-tasks
          (str "* TODO Healthy\n"
               ":PROPERTIES:\n"
               ":CUSTOM_ID: 11111111-2222-4333-8444-555555555555\n"
               ":END:\n"
               "\n"
               "* DONE Closed\n"
               "CLOSED: [2026-04-25 Sat 12:00]\n"
               ":PROPERTIES:\n"
               ":CUSTOM_ID: 22222222-2222-4333-8444-555555555555\n"
               ":END:\n"))]
    (let [findings (doctor/run-doctor {:tasks tasks :selected-id nil})]
      (is (= [] findings))
      (is (str/includes? (doctor/format-findings-report findings)
                         "no issues found")))))

(deftest duplicate-id
  (let [{:keys [tasks]}
        (parser/parse-tasks
          (str "* TODO First\n"
               ":PROPERTIES:\n"
               ":CUSTOM_ID: dupe-aaaa-bbbb-cccc-dddddddddddd\n"
               ":END:\n"
               "\n"
               "* TODO Second\n"
               ":PROPERTIES:\n"
               ":CUSTOM_ID: dupe-aaaa-bbbb-cccc-dddddddddddd\n"
               ":END:\n"))
        findings (doctor/run-doctor {:tasks tasks :selected-id nil})]
    (is (= 2 (count-of findings :duplicate-id)))
    (is (every? #(= :error (:severity %))
                (filter #(= :duplicate-id (:code %)) findings)))))

(deftest broken-import
  (let [task {:level 1 :status "TODO" :priority nil
              :summary "Has bad import" :tags []
              :description "" :children []
              :property-lines [":CUSTOM_ID: import-aaaa-bbbb-cccc-dddddddddddd"]
              :logbook-lines [] :import-path "design/log/missing.org"
              :import-raw "[[file:design/log/missing.org]]"
              :import-error "ENOENT: no such file"
              :import-children []
              :closed nil
              :source-path "/tmp/TASKS.org"
              :line-number 1 :end-line 5}
        findings (doctor/run-doctor {:tasks [task] :selected-id nil})
        f (first (filter #(= :broken-import (:code %)) findings))]
    (is (= 1 (count-of findings :broken-import)))
    (is (= :error (:severity f)))
    (is (= "/tmp/TASKS.org" (get-in f [:location :file])))
    (is (= 1 (get-in f [:location :line])))))

(deftest import-child-with-file-root-no-saveability-finding
  (let [tasks [{:level 1 :status "STARTED" :priority nil
                :summary "Parent" :tags []
                :description "" :children []
                :property-lines [":CUSTOM_ID: 6593c6fc-d284-4f9e-b6b5-4c159345cd20"]
                :logbook-lines [] :import-path "plan.org"
                :import-raw "[[plan:plan.org]]" :import-error nil
                :import-children [{:level 2 :status "TODO" :priority nil
                                   :summary "Plan child" :tags []
                                   :description "" :children []
                                   :property-lines [":CUSTOM_ID: 9e2b9765-dd9d-4748-aed3-c3e3af0ea5e4"]
                                   :logbook-lines [] :import-path nil
                                   :import-raw nil :import-error nil
                                   :import-children nil :closed nil
                                   :source-path "/tmp/plan.org"
                                   :line-number 10 :end-line 15
                                   :file-root? true}]
                :closed nil
                :source-path "/tmp/TASKS.org"
                :line-number 1 :end-line 5
                :file-root? true}]
        findings (doctor/run-doctor {:tasks tasks :selected-id nil})]
    (is (zero? (count-of findings :import-child-not-saveable)))))

(deftest import-child-without-file-root-is-reported
  (let [tasks [{:level 1 :status "STARTED" :priority nil
                :summary "Parent" :tags []
                :description "" :children []
                :property-lines [":CUSTOM_ID: 6593c6fc-d284-4f9e-b6b5-4c159345cd20"]
                :logbook-lines [] :import-path "plan.org"
                :import-raw "[[plan:plan.org]]" :import-error nil
                :import-children [{:level 2 :status "TODO" :priority nil
                                   :summary "Plan child" :tags []
                                   :description "" :children []
                                   :property-lines [":CUSTOM_ID: 9e2b9765-dd9d-4748-aed3-c3e3af0ea5e4"]
                                   :logbook-lines [] :import-path nil
                                   :import-raw nil :import-error nil
                                   :import-children nil :closed nil
                                   :source-path "/tmp/plan.org"
                                   :line-number 10 :end-line 15
                                   :file-root? false}]
                :closed nil
                :source-path "/tmp/TASKS.org"
                :line-number 1 :end-line 5
                :file-root? true}]
        findings (doctor/run-doctor {:tasks tasks :selected-id nil})
        f (first (filter #(= :import-child-not-saveable (:code %)) findings))]
    (is (= 1 (count-of findings :import-child-not-saveable)))
    (is (= :warn (:severity f)))
    (is (= "/tmp/plan.org" (get-in f [:location :file])))
    (is (str/includes? (:message f) ":file-root?"))))

(deftest selected-not-found-reported
  (let [{:keys [tasks]}
        (parser/parse-tasks
          (str "* TODO Solo\n"
               ":PROPERTIES:\n"
               ":CUSTOM_ID: aaaa1111-2222-4333-8444-555555555555\n"
               ":END:\n"))
        findings (doctor/run-doctor
                   {:tasks tasks
                    :selected-id "ghost-aaaa-bbbb-cccc-dddddddddddd"
                    :selected-source-path "/tmp/TASKS.local.org"})
        f (first (filter #(= :selected-not-found (:code %)) findings))]
    (is (= 1 (count-of findings :selected-not-found)))
    (is (= "/tmp/TASKS.local.org" (get-in f [:location :file])))))

(deftest selected-found-no-finding
  (let [{:keys [tasks]}
        (parser/parse-tasks
          (str "* TODO Solo\n"
               ":PROPERTIES:\n"
               ":CUSTOM_ID: bbbb1111-2222-4333-8444-555555555555\n"
               ":END:\n"))
        findings (doctor/run-doctor
                   {:tasks tasks
                    :selected-id "bbbb1111-2222-4333-8444-555555555555"})]
    (is (zero? (count-of findings :selected-not-found)))))

(deftest waiting-without-blocker
  (let [{:keys [tasks]}
        (parser/parse-tasks
          (str "* WAITING Bare wait\n"
               ":PROPERTIES:\n"
               ":CUSTOM_ID: cccc1111-2222-4333-8444-555555555555\n"
               ":END:\n"
               "\n"
               "* WAITING With blocker\n"
               ":PROPERTIES:\n"
               ":CUSTOM_ID: dddd1111-2222-4333-8444-555555555555\n"
               ":BLOCKED-BY: url:https://example.com\n"
               ":END:\n"))
        findings (doctor/run-doctor {:tasks tasks :selected-id nil})
        f (first (filter #(= :waiting-without-blocker (:code %)) findings))]
    (is (= 1 (count-of findings :waiting-without-blocker)))
    (is (= :warn (:severity f)))))

(deftest closed-without-timestamp-reported
  ;; The serializer normally restores CLOSED: when present; synthesize
  ;; the task directly to exercise the check.
  (let [task {:level 1 :status "DONE" :priority nil
              :summary "No CLOSED line" :tags []
              :description "" :children []
              :property-lines [":CUSTOM_ID: closed-aaaa-bbbb-cccc-dddddddddddd"]
              :logbook-lines [] :import-path nil :import-raw nil
              :import-error nil :closed nil
              :source-path "/tmp/TASKS.org"
              :line-number 1 :end-line 5}
        findings (doctor/run-doctor {:tasks [task] :selected-id nil})]
    (is (= 1 (count-of findings :closed-without-timestamp)))))

(deftest cancelled-with-closed-no-finding
  (let [{:keys [tasks]}
        (parser/parse-tasks
          (str "* CANCELLED Done\n"
               "CLOSED: [2026-04-25 Sat 12:00]\n"
               ":PROPERTIES:\n"
               ":CUSTOM_ID: aaa11111-2222-4333-8444-555555555555\n"
               ":END:\n"))]
    (is (zero? (count-of (doctor/run-doctor {:tasks tasks :selected-id nil})
                         :closed-without-timestamp)))))

(deftest stale-parent-status
  (let [{:keys [tasks]}
        (parser/parse-tasks
          (str "* TODO Parent\n"
               ":PROPERTIES:\n"
               ":CUSTOM_ID: parent11-2222-4333-8444-555555555555\n"
               ":END:\n"
               "** STARTED Child\n"
               ":PROPERTIES:\n"
               ":CUSTOM_ID: childaa1-2222-4333-8444-555555555555\n"
               ":END:\n"))
        findings (doctor/run-doctor {:tasks tasks :selected-id nil})]
    (is (= 1 (count-of findings :stale-parent-status)))))

(deftest parent-todo-children-todo-no-finding
  (let [{:keys [tasks]}
        (parser/parse-tasks
          (str "* TODO Parent\n"
               ":PROPERTIES:\n"
               ":CUSTOM_ID: parent22-2222-4333-8444-555555555555\n"
               ":END:\n"
               "** TODO Child\n"
               ":PROPERTIES:\n"
               ":CUSTOM_ID: childbb1-2222-4333-8444-555555555555\n"
               ":END:\n"))]
    (is (zero? (count-of (doctor/run-doctor {:tasks tasks :selected-id nil})
                         :stale-parent-status)))))

(deftest invalid-task-blocker
  (let [{:keys [tasks]}
        (parser/parse-tasks
          (str "* TODO Refers to ghost\n"
               ":PROPERTIES:\n"
               ":CUSTOM_ID: ghost001-2222-4333-8444-555555555555\n"
               ":BLOCKED-BY: task:does-not-exist-anywhere\n"
               ":BLOCKED-BY+: url:https://example.com\n"
               ":END:\n"))
        findings (doctor/run-doctor {:tasks tasks :selected-id nil})
        f (first (filter #(= :invalid-task-blocker (:code %)) findings))]
    (is (= 1 (count-of findings :invalid-task-blocker)))
    (is (= :error (:severity f)))))

(deftest valid-task-blocker-no-finding
  (let [{:keys [tasks]}
        (parser/parse-tasks
          (str "* DONE Real dep\n"
               "CLOSED: [2026-05-01 Fri 09:00]\n"
               ":PROPERTIES:\n"
               ":CUSTOM_ID: realdep1-2222-4333-8444-555555555555\n"
               ":END:\n"
               "* TODO Gated\n"
               ":PROPERTIES:\n"
               ":CUSTOM_ID: gated001-2222-4333-8444-555555555555\n"
               ":BLOCKED-BY: task:realdep1-2222-4333-8444-555555555555\n"
               ":END:\n"))]
    (is (zero? (count-of (doctor/run-doctor {:tasks tasks :selected-id nil})
                         :invalid-task-blocker)))))

(deftest valid-protocol-files-no-findings
  (let [findings (doctor/run-doctor
                   {:tasks []
                    :selected-id nil
                    :protocol-files
                    {:setup {:path "/tmp/TASKS.setup.org"
                             :content (str "#+LINK: plan file:design/log/%s\n"
                                           "#+LINK: task file:../../TASKS.org::#%s\n"
                                           "#+LINK: archive file:../../TASKS.archive.org::#%s\n")}
                     :tasks {:path "/tmp/TASKS.org"
                             :content (str "#+TITLE: Project Tasks\n"
                                           "#+LINK: task file:TASKS.org::#%s\n"
                                           "#+LINK: archive file:TASKS.archive.org::#%s\n"
                                           "#+SETUPFILE: ./TASKS.local.org\n"
                                           "#+SETUPFILE: ./TASKS.setup.org\n")}}})]
    (is (zero? (count-of findings :missing-link-template)))
    (is (zero? (count-of findings :missing-local-setupfile)))
    (is (zero? (count-of findings :misordered-setupfile)))
    (is (zero? (count-of findings :misordered-link-template)))))

(deftest misordered-setupfile-reported
  (let [findings (doctor/run-doctor
                   {:tasks []
                    :selected-id nil
                    :protocol-files
                    {:setup {:path "/tmp/TASKS.setup.org"
                             :content (str "#+LINK: task file:../../TASKS.org::#%s\n"
                                           "#+LINK: archive file:../../TASKS.archive.org::#%s\n")}
                     :tasks {:path "/tmp/TASKS.org"
                             :content (str "#+TITLE: Project Tasks\n"
                                           "#+LINK: task file:TASKS.org::#%s\n"
                                           "#+LINK: archive file:TASKS.archive.org::#%s\n"
                                           "#+SETUPFILE: ./TASKS.setup.org\n"
                                           "#+SETUPFILE: ./TASKS.local.org\n")}}})]
    (is (= 1 (count-of findings :misordered-setupfile)))))

(deftest missing-local-setupfile
  (let [findings (doctor/run-doctor
                   {:tasks []
                    :selected-id nil
                    :protocol-files
                    {:setup {:path "/tmp/TASKS.setup.org"
                             :content (str "#+LINK: task file:../../TASKS.org::#%s\n"
                                           "#+LINK: archive file:../../TASKS.archive.org::#%s\n")}
                     :tasks {:path "/tmp/TASKS.org"
                             :content (str "#+TITLE: Project Tasks\n"
                                           "#+LINK: task file:TASKS.org::#%s\n"
                                           "#+LINK: archive file:TASKS.archive.org::#%s\n"
                                           "#+SETUPFILE: ./TASKS.setup.org\n")}}})]
    (is (= 1 (count-of findings :missing-local-setupfile)))))

(deftest spec-impact-aware-record-requires-core-sections
  (let [content (str "#+NO_SPEC_IMPACT: true\n\n"
                     "* Summary\n"
                     "body\n"
                     "* Plan\n"
                     "** TODO Work\n"
                     ":PROPERTIES:\n"
                     ":CUSTOM_ID: 33333333-2222-4333-8444-555555555555\n"
                     ":END:\n"
                     "* Implementation\n"
                     "* Validation\n")
        {:keys [tasks]} (parser/parse-tasks content {:source-path "/repo/design/log/work.org"
                                                     :source-content content})
        findings (doctor/run-doctor {:tasks tasks :selected-id nil})]
    (is (= 1 (count-of findings :missing-record-section)))
    (is (= 1 (count-of findings :empty-validation-section)))))

(deftest spec-impact-warns-when-declared-path-not-touched
  (let [content (str "#+SPEC_IMPACT: docs/api.org\n\n"
                     "* Plan\n"
                     "** TODO Update API\n"
                     ":PROPERTIES:\n"
                     ":CUSTOM_ID: 33333333-2222-4333-8444-555555555555\n"
                     ":END:\n")
        {:keys [tasks]} (parser/parse-tasks content {:source-path "/repo/design/log/api.org"
                                                     :source-content content})
        findings (doctor/run-doctor {:tasks tasks
                                      :selected-id nil
                                      :changed-paths #{"src/api.clj"}})
        f (first (filter #(= :spec-impact-untouched (:code %)) findings))]
    (is (= 1 (count-of findings :spec-impact-untouched)))
    (is (= :warn (:severity f)))
    (is (str/includes? (:message f) "docs/api.org"))))

(deftest spec-impact-passes-when-declared-path-touched
  (let [content (str "#+SPEC_IMPACT: docs/api.org\n\n"
                     "* Plan\n"
                     "** TODO Update API\n"
                     ":PROPERTIES:\n"
                     ":CUSTOM_ID: 33333333-2222-4333-8444-555555555555\n"
                     ":END:\n")
        {:keys [tasks]} (parser/parse-tasks content {:source-path "/repo/design/log/api.org"
                                                     :source-content content})]
    (is (zero? (count-of (doctor/run-doctor {:tasks tasks
                                             :selected-id nil
                                             :changed-paths #{"docs/api.org"}})
                         :spec-impact-untouched)))))

(deftest no-spec-impact-opt-out-suppresses-spec-impact-warnings
  (let [content (str "#+SPEC_IMPACT: docs/api.org\n"
                     "#+NO_SPEC_IMPACT: true\n\n"
                     "* Plan\n"
                     "** TODO Spike\n"
                     ":PROPERTIES:\n"
                     ":CUSTOM_ID: 33333333-2222-4333-8444-555555555555\n"
                     ":END:\n")
        {:keys [tasks]} (parser/parse-tasks content {:source-path "/repo/design/log/spike.org"
                                                     :source-content content})]
    (is (zero? (count-of (doctor/run-doctor {:tasks tasks
                                             :selected-id nil
                                             :changed-paths #{}})
                         :spec-impact-untouched)))))

(deftest format-finding-line
  (let [f {:code :duplicate-id
           :severity :error
           :message "Duplicate :CUSTOM_ID: foo (2 occurrences)"
           :location {:file "/tmp/TASKS.org" :line 7 :heading "Some task"}}
        line (doctor/format-finding-line f)]
    (is (str/includes? line "[ERROR]"))
    (is (str/includes? line "duplicate-id"))
    (is (str/includes? line "/tmp/TASKS.org:7"))))

;; ── non-uuid-v4 / patterned-sibling-ids ──────────────────

(deftest non-uuid-v4-id-flags-hand-authored-ids
  (let [{:keys [tasks]}
        (parser/parse-tasks
          (str "* TODO Bad ID\n"
               ":PROPERTIES:\n"
               ":CUSTOM_ID: parent-id\n"
               ":END:\n"))
        findings (doctor/run-doctor {:tasks tasks :selected-id nil})
        f (first (filter #(= :non-uuid-v4-id (:code %)) findings))]
    (is (= 1 (count-of findings :non-uuid-v4-id)))
    (is (= :warn (:severity f)))
    (is (str/includes? (:message f) "ot uuid"))))

(deftest non-uuid-v4-id-passes-valid-v4
  (let [{:keys [tasks]}
        (parser/parse-tasks
          (str "* TODO Good\n"
               ":PROPERTIES:\n"
               ":CUSTOM_ID: 6593c6fc-d284-4f9e-b6b5-4c159345cd20\n"
               ":END:\n"))]
    (is (zero? (count-of (doctor/run-doctor {:tasks tasks :selected-id nil})
                         :non-uuid-v4-id)))))

(deftest patterned-sibling-ids-detected
  (let [{:keys [tasks]}
        (parser/parse-tasks
          (str "* TODO Plan A\n"
               ":PROPERTIES:\n"
               ":CUSTOM_ID: 1c5f0b32-9b62-4f8e-9b8c-3a6b2c4d0001\n"
               ":END:\n"
               "\n"
               "* TODO Plan B\n"
               ":PROPERTIES:\n"
               ":CUSTOM_ID: 1c5f0b32-9b62-4f8e-9b8c-3a6b2c4d0002\n"
               ":END:\n"))
        findings (doctor/run-doctor {:tasks tasks :selected-id nil})
        f (first (filter #(= :patterned-sibling-ids (:code %)) findings))]
    (is (= 2 (count-of findings :patterned-sibling-ids)))
    (is (= :warn (:severity f)))
    (is (str/includes? (:message f) "ot uuid"))))

(deftest patterned-sibling-ids-allows-random-v4
  (let [{:keys [tasks]}
        (parser/parse-tasks
          (str "* TODO Random A\n"
               ":PROPERTIES:\n"
               ":CUSTOM_ID: 6593c6fc-d284-4f9e-b6b5-4c159345cd20\n"
               ":END:\n"
               "\n"
               "* TODO Random B\n"
               ":PROPERTIES:\n"
               ":CUSTOM_ID: 9e2b9765-dd9d-4748-aed3-c3e3af0ea5e4\n"
               ":END:\n"))]
    (is (zero? (count-of (doctor/run-doctor {:tasks tasks :selected-id nil})
                         :patterned-sibling-ids)))))

(deftest format-findings-report-ordering
  (let [findings [{:code :duplicate-id :severity :error
                   :message "x" :location {:file "/a" :line 1}}
                  {:code :waiting-without-blocker :severity :warn
                   :message "y" :location {:file "/b" :line 2}}]
        report (doctor/format-findings-report findings)]
    (is (str/includes? report "2 findings"))
    (let [dup-idx (str/index-of report "duplicate-id")
          warn-idx (str/index-of report "waiting-without-blocker")]
      (is (< dup-idx warn-idx)))))

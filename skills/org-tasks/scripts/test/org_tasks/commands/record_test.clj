(ns org-tasks.commands.record-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org-tasks.commands.test-util :refer :all]
            [org-tasks.parser :as parser]
            [org-tasks.styling :as styling]))

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
        (is (str/includes? plan "* Intent"))
        (is (str/includes? plan "* Summary"))
        (is (str/includes? plan "* Plan"))
        (is (str/includes? plan "* Implementation"))
        ;; * Validation is optional per org-plan's section contract and is
        ;; no longer scaffolded.
        (is (not (str/includes? plan "* Validation")))
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

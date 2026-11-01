(ns org-tasks.commands.record-test
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org-tasks.commands.test-util :refer :all]
            [org-tasks.parser :as parser]
            [org-tasks.styling :as styling]))

(defn- bootstrap-record-graph! [root]
  (bootstrap-parent-child-graph! root)
  (let [path (str (fs/path root "TASKS.org"))]
    (spit path (str/replace (slurp path) #"\n(?=\*{2,} )" "\n\n"))))

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

(deftest record-custom-path-imports-resolve-and-existing-files-stay-untouched
  (doseq [record-path ["design/log/nested/feature.org" "notes/feature.org"]]
    (with-temp-dir
      (fn [root]
        (bootstrap-record-graph! root)
        (let [id "aaaa1111-2222-4333-8444-555555555551"
              {:keys [out exit]} (run-cli! "--root" root "--format" "json"
                                          "record" "create" id "--path" record-path)
              result (parse-json-result out)
              {:keys [out]} (run-cli! "--root" root "--format" "json" "show" id)
              shown (parse-json-result out)]
          (is (zero? exit))
          (is (true? (:absorbedSubtasks result)))
          (is (nil? (get-in shown [:task :importError])))
          (is (str/includes? out "Child A1") "the attached import reaches the migrated children")
          (let [before (slurp (:recordPath result))
                repeated (run-cli! "--root" root "--format" "json" "record" "create" id)
                repeated-result (parse-json-result (:out repeated))]
            (is (zero? (:exit repeated)))
            (is (= (:recordPath result) (:recordPath repeated-result)))
            (is (false? (:created repeated-result)))
            (is (= before (slurp (:recordPath result))))))))))

(deftest record-existing-file-and-dry-run-preserve-subtasks
  (with-temp-dir
    (fn [root]
      (bootstrap-record-graph! root)
      (let [id "aaaa1111-2222-4333-8444-555555555551"
            path "design/log/existing.org"
            abs (str (fs/path root path))
            tasks-before (slurp (str (fs/path root "TASKS.org")))]
        (fs/create-dirs (fs/parent abs))
        (spit abs "* Intent\nExisting record.\n")
        (let [dry (run-cli! "--root" root "--format" "json" "--dry-run"
                            "record" "create" id "--path" "notes/new.org")]
          (is (zero? (:exit dry)))
          (is (not (fs/exists? (fs/path root "notes/new.org"))))
          (is (= tasks-before (slurp (str (fs/path root "TASKS.org"))))))
        (let [created (run-cli! "--root" root "--format" "json"
                                "record" "create" id "--path" path)
              result (parse-json-result (:out created))]
          (is (zero? (:exit created)))
          (is (false? (:created result)))
          (is (false? (:absorbedSubtasks result)))
          (is (= "* Intent\nExisting record.\n" (slurp abs)))
          (is (str/includes? (slurp (str (fs/path root "TASKS.org"))) "*** TODO Child A1")))))))

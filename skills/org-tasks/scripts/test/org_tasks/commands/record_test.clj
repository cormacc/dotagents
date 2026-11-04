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

(deftest record-create-rebases-migrated-child-imports
  (doseq [[record-path child-link grandchild-link]
          [["design/log/feature.org" "child-import.org" "../../notes/grandchild.org"]
           ["design/log/nested/feature.org" "../child-import.org" "../../../notes/grandchild.org"]]]
    (with-temp-dir
      (fn [root]
        (bootstrap-three-level-graph! root)
        (let [tasks-path (str (fs/path root "TASKS.org"))
              child-target (str (fs/path root "design" "log" "child-import.org"))
              grandchild-target (str (fs/path root "notes" "grandchild.org"))
              child-id "aaaa2222-2222-4333-8444-555555555552"
              grandchild-id "aaaa3333-2222-4333-8444-555555555553"]
          (fs/create-dirs (fs/parent child-target))
          (fs/create-dirs (fs/parent grandchild-target))
          (spit child-target "* TODO Imported child\n")
          (spit grandchild-target "* TODO Imported grandchild\n")
          (spit tasks-path
                (-> (slurp tasks-path)
                    (str/replace "*** TODO Child A1" "*** STARTED Child A1")
                    (str/replace
                     (str ":CUSTOM_ID: " child-id "\n:END:\n**** TODO Grandchild A1a")
                     (str ":CUSTOM_ID: " child-id "\n:END:\n"
                          "#+IMPORT: [[plan:child-import.org]]\n\n"
                          "**** WAITING Grandchild A1a"))
                    (str/replace
                     (str ":CUSTOM_ID: " grandchild-id "\n:END:\n** TODO Parent B")
                     (str ":CUSTOM_ID: " grandchild-id "\n:END:\n"
                          "#+IMPORT: [[file:notes/grandchild.org]]\n\n"
                          "** TODO Parent B"))
                    (str/replace #"\n+(?=\*{2,} )" "\n\n")))
          ;; The source-local `plan:` template and source-relative `file:`
          ;; target both resolve before migration.
          (doseq [[id target] [[child-id child-target]
                               [grandchild-id grandchild-target]]]
            (let [{:keys [out exit]} (run-cli! "--root" root "--format" "json" "show" id)
                  shown (parse-json-result out)]
              (is (zero? exit))
              (is (nil? (get-in shown [:task :importError])))
              (is (= target (get-in shown [:task :importChildren 0 :sourcePath])))))
          (let [{:keys [out exit]}
                (run-cli! "--root" root "--format" "json"
                          "record" "create" "aaaa1111-2222-4333-8444-555555555551"
                          "--path" record-path)
                result (parse-json-result out)
                plan (slurp (:recordPath result))]
            (is (zero? exit))
            (is (true? (:absorbedSubtasks result)))
            (is (str/includes? plan "** STARTED Child A1"))
            (is (str/includes? plan (str ":CUSTOM_ID: " child-id)))
            (is (str/includes? plan "*** WAITING Grandchild A1a"))
            (is (str/includes? plan (str ":CUSTOM_ID: " grandchild-id)))
            (is (str/includes? plan (str "#+IMPORT: [[file:" child-link "]]")))
            (is (str/includes? plan (str "#+IMPORT: [[file:" grandchild-link "]]")))
            (doseq [[id target] [[child-id child-target]
                                 [grandchild-id grandchild-target]]]
              (let [{:keys [out exit]} (run-cli! "--root" root "--format" "json" "show" id)
                    shown (parse-json-result out)]
                (is (zero? exit))
                (is (nil? (get-in shown [:task :importError])))
                (is (= target (get-in shown [:task :importChildren 0 :sourcePath])))))))))))

(deftest record-create-refuses-unresolved-migrated-imports-before-writing
  (with-temp-dir
    (fn [root]
      (bootstrap-parent-child-graph! root)
      (let [tasks-path (str (fs/path root "TASKS.org"))
            record-path (str (fs/path root "design" "log" "feature.org"))]
        (spit tasks-path
              (str/replace (slurp tasks-path)
                           ":CUSTOM_ID: aaaa2222-2222-4333-8444-555555555552\n:END:"
                           (str ":CUSTOM_ID: aaaa2222-2222-4333-8444-555555555552\n:END:\n"
                                "#+IMPORT: [[file:../../outside.org]]")))
        (let [before (slurp tasks-path)
              {:keys [err exit]}
              (run-cli! "--root" root "--format" "json"
                        "record" "create" "aaaa1111-2222-4333-8444-555555555551"
                        "--path" "design/log/feature.org")
              error (parse-json-error err)]
          (is (= 1 exit))
          (is (= "validation" (:code error)))
          (is (not (fs/exists? record-path)))
          (is (= before (slurp tasks-path)))
          (is (str/includes? (slurp tasks-path) "#+IMPORT: [[file:../../outside.org]]")))))))

(deftest record-create-refuses-missing-migrated-imports-before-writing
  (doseq [dry-run? [false true]]
    (with-temp-dir
      (fn [root]
        (bootstrap-record-graph! root)
        (let [tasks-path (str (fs/path root "TASKS.org"))
              record-path (str (fs/path root "design" "log" "feature.org"))]
          (spit tasks-path
                (str/replace (slurp tasks-path)
                             ":CUSTOM_ID: aaaa2222-2222-4333-8444-555555555552\n:END:"
                             (str ":CUSTOM_ID: aaaa2222-2222-4333-8444-555555555552\n:END:\n"
                                  "#+IMPORT: [[file:notes/missing.org]]")))
          (let [before (slurp tasks-path)
                {:keys [err exit]}
                (apply run-cli! (cond-> ["--root" root "--format" "json"
                                        "record" "create" "aaaa1111-2222-4333-8444-555555555551"
                                        "--path" "design/log/feature.org"]
                                 dry-run? (conj "--dry-run")))
                error (when (seq err) (parse-json-error err))]
            (is (= 1 exit))
            (is (= "validation" (:code error)))
            (is (not (fs/exists? record-path)))
            (is (= before (slurp tasks-path)))))))))

(deftest record-create-keeps-plan-path-sandboxed
  (with-temp-dir
    (fn [root]
      (bootstrap-parent-child-graph! root)
      (let [tasks-path (str (fs/path root "TASKS.org"))
            before (slurp tasks-path)
            {:keys [err exit]}
            (run-cli! "--root" root "--format" "json"
                      "record" "create" "aaaa1111-2222-4333-8444-555555555551"
                      "--path" "../outside-record.org")
            error (parse-json-error err)]
        (is (= 1 exit))
        (is (= "path-outside-project" (:code error)))
        (is (= before (slurp tasks-path)))))))

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

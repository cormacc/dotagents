(ns org-tasks.commands.maintenance-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org-tasks.commands.test-util :refer :all]
            [org-tasks.parser :as parser]
            [org-tasks.styling :as styling]))

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
        (let [tasks-content (slurp (str (fs/path root "TASKS.org")))
              setup-content (slurp (str (fs/path root "TASKS.setup.org")))]
          (is (str/includes? setup-content "#+LINK: proj file:../../%s"))
          (is (not (str/includes? setup-content "#+LINK: plan")))
          (is (str/includes? tasks-content "#+LINK: plan file:design/log/%s"))
          (is (str/includes? tasks-content "#+LINK: proj file:%s"))
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

(deftest backfill-mutates-hand-authored-linked-plan-task
  (with-temp-dir
    (fn [root]
      (bootstrap-linked-plan-graph! root)
      (let [plan-path (str (fs/path root "design" "log" "linked-plan.org"))
            {:keys [out exit]}
            (run-cli! "--root" root "--format" "json"
                      "backfill" "--created-at" "2026-05-24 Sun 10:00")
            r (parse-json-result out)
            plan-content (slurp plan-path)]
        (is (zero? exit))
        (is (= 1 (:changed r)))
        (is (= plan-path (get-in r [:changes 0 :file])))
        (is (str/includes? plan-content "** TODO Hand-authored child without metadata"))
        (is (str/includes? plan-content ":CUSTOM_ID:"))
        (is (str/includes? plan-content ":CREATED: [2026-05-24 Sun 10:00]"))
        (is (str/includes? plan-content "- Created [2026-05-24 Sun 10:00]"))))))

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

(deftest doctor-output-order-is-stable
  (with-temp-dir
    (fn [root]
      (spit (str (fs/path root "TASKS.setup.org")) setup-org-preamble)
      (spit (str (fs/path root "TASKS.org"))
            (str tasks-org-preamble
                 "* Improvements\n"
                 "** TODO Parent\n"
                 ":PROPERTIES:\n"
                 ":CUSTOM_ID: parent-id\n"
                 ":END:\n"
                 "*** WAITING Child\n"
                 ":PROPERTIES:\n"
                 ":CUSTOM_ID: child-id\n"
                 ":END:\n"
                 "** TODO Duplicate A\n"
                 ":PROPERTIES:\n"
                 ":CUSTOM_ID: dup-id\n"
                 ":END:\n"
                 "** TODO Duplicate B\n"
                 ":PROPERTIES:\n"
                 ":CUSTOM_ID: dup-id\n"
                 ":END:\n"))
      (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED: missing-id\n")
      (let [{:keys [out exit]} (run-cli! "--root" root "--format" "json" "doctor")
            r (parse-json-result out)]
        (is (zero? exit))
        (is (= ["duplicate-id" "duplicate-id" "selected-not-found"
                "stale-parent-status" "non-uuid-v4-id"
                "waiting-without-blocker" "non-uuid-v4-id"
                "non-uuid-v4-id" "non-uuid-v4-id"]
               (mapv :code (:findings r))))))))

(deftest doctor-spec-path-resolution-through-cli
  ;; Exercises the CLI/maintenance layer that stats #+SPEC: paths on disk
  ;; (spec-path-exists-map + fs/exists?), covering resolvable file,
  ;; resolvable (empty) folder, and a missing path.
  (with-temp-dir
    (fn [root]
      (spit (str (fs/path root "TASKS.setup.org")) setup-org-preamble)
      (fs/create-dirs (fs/path root "design"))
      (spit (str (fs/path root "design" "SPEC.org")) "#+TITLE: spec\n")
      (fs/create-dirs (fs/path root "design" "specs"))       ; existing empty folder
      (spit (str (fs/path root "TASKS.org"))
            (str tasks-org-preamble
                 "#+SPEC: [[proj:design/SPEC.org]]\n"          ; resolvable file
                 "#+SPEC: [[proj:design/specs]]\n"            ; resolvable folder
                 "#+SPEC: [[proj:design/missing.org]]\n"      ; dangling
                 "* Improvements\n"
                 "** TODO Task\n"
                 ":PROPERTIES:\n"
                 ":CUSTOM_ID: 11111111-2222-4333-8444-555555555551\n"
                 ":END:\n"))
      (spit (str (fs/path root "TASKS.local.org")) "#+SELECTED:\n")
      (let [{:keys [out exit]} (run-cli! "--root" root "--format" "json" "doctor")
            r (parse-json-result out)
            dangling (filter #(= "spec-path-dangling" (:code %)) (:findings r))]
        (is (zero? exit))
        (is (zero? (count (filter #(= "spec-value-malformed" (:code %)) (:findings r)))))
        (is (= 1 (count dangling)) "only the missing path dangles")
        (is (str/includes? (:message (first dangling)) "design/missing.org"))))))

(deftest doctor-closed-record-git-history
  (with-temp-dir
    (fn [root]
      (let [record "design/log/linked-plan.org"
            git (fn [& args]
                  (:out (apply process/shell
                         {:dir root :out :string :err :string
                          :extra-env {"GIT_CONFIG_NOSYSTEM" "1"
                                      "GIT_CONFIG_GLOBAL" "/dev/null"}}
                         (into ["git" "-c" "user.name=Test"
                                "-c" "user.email=test@example.invalid"] args))))
            put! (fn [path text] (spit (str (fs/path root path)) text))
            commit! (fn [] (git "add" ".") (git "commit" "-qm" "fixture"))
            findings (fn []
                       (let [{:keys [out exit]}
                             (run-cli! "--root" root "--format" "json" "doctor")
                             envelope (json/parse-string out true)]
                         (is (zero? exit))
                         (is (true? (:ok envelope)))
                         (filterv #(#{"spec-untouched" "spec-stale"} (:code %))
                                  (get-in envelope [:result :findings]))))
            close! (fn [status]
                     (is (zero? (:exit (run-cli! "--root" root "--format" "json"
                                                "status" linked-plan-parent-id status)))))]
        (git "init" "-q" "-b" "main")
        (bootstrap-linked-plan-graph! root)
        (fs/create-dirs (fs/path root "docs"))
        (fs/create-dirs (fs/path root "src"))
        (put! "src/api.clj" "old\n")
        (doseq [spec ["api" "café"]]
          (put! (str "docs/" spec ".org") "[[file:../src/api.clj]]\n"))
        (put! record (str "#+SPEC: [[proj:docs/api.org]]\n"
                          "#+SPEC: [[proj:docs/café.org]]\n" (linked-plan-content)))
        (close! "DONE")
        (commit!)
        (testing "initial commit and unquoted filenames count for a closed record"
          (is (empty? (findings))))
        ;; These specs predate their declaration in the record. Neither
        ;; belongs to its history until explicitly changed with that record.
        (doseq [spec ["merge" "unrelated"]]
          (put! (str "docs/" spec ".org") "[[file:../src/api.clj]]\n"))
        (commit!)
        (put! record (str "#+SPEC: [[proj:docs/merge.org]]\n"
                          "#+SPEC: [[proj:docs/unrelated.org]]\n"
                          (slurp (str (fs/path root record)))))
        (commit!)
        (git "branch" "side")
        (put! "main.txt" "main\n") (commit!)
        (git "checkout" "-q" "side")
        (put! "side.txt" "side\n") (commit!)
        (git "checkout" "-q" "main")
        (git "merge" "--no-ff" "--no-commit" "side")
        (put! "docs/merge.org" "Updated\n[[file:../src/api.clj]]\n")
        (spit (str (fs/path root record)) "\nMerged work.\n" :append true)
        (commit!)
        (put! "src/api.clj" "new\n")
        (testing "merge history counts, unrelated commits do not, for both advisories"
          (let [fs (findings)]
            (is (= {"spec-untouched" 1 "spec-stale" 1} (frequencies (map :code fs))))
            (is (every? #(str/includes? (:message %) "docs/unrelated.org") fs))))
        (testing "reopening restores working-tree checks despite existing history"
          (close! "TODO")
          (is (= {"spec-untouched" 4 "spec-stale" 4}
                 (frequencies (map :code (findings))))))
        (testing "cancelled records use the same closed-record evidence"
          (close! "CANCELLED")
          (is (= {"spec-untouched" 1 "spec-stale" 1}
                 (frequencies (map :code (findings))))))))))

(deftest doctor-inline-path-citation-resolution-through-cli
  ;; The doctor stays pure: the command resolves candidate paths through the
  ;; root sandbox and supplies the resulting existence maps.
  (with-temp-dir
    (fn [root]
      (bootstrap-linked-plan-graph! root)
      (let [wrong "skills/org-tasks/scripts/src/org_tasks/test_runner.clj"
            valid "skills/org-tasks/scripts/test/org_tasks/test_runner.clj"
            valid-path (fs/path root valid)
            plan-path (str (fs/path root "design" "log" "linked-plan.org"))]
        (fs/create-dirs (fs/parent valid-path))
        (spit (str valid-path) "fixture\n")
        (spit plan-path
              (str "* Summary\n"
                   "- Wrong: `" wrong "`\n"
                   "- Valid: =" valid "=\n"
                   "- Illustrative: `example/path.clj`\n"
                   "* Plan\n"
                   "** TODO Work\n"
                   ":PROPERTIES:\n"
                   ":CUSTOM_ID: bbbbbbbb-2222-4222-8222-bbbbbbbbbbbb\n"
                   ":END:\n"
                   "* Implementation\n"))
        (let [{:keys [out exit]} (run-cli! "--root" root "--format" "json" "doctor")
              r (parse-json-result out)
              findings (filter #(= "inline-path-dangling" (:code %)) (:findings r))]
          (is (zero? exit))
          (is (= 1 (count findings)))
          (is (= "warn" (:severity (first findings))))
          (is (= plan-path (get-in (first findings) [:location :file])))
          (is (= 2 (get-in (first findings) [:location :line])))
          (is (str/includes? (:message (first findings)) wrong)))))))

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

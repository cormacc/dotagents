(ns org-tasks.commands.maintenance-test
  (:require [babashka.fs :as fs]
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

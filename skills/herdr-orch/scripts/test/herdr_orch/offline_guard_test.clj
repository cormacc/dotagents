(ns herdr-orch.offline-guard-test
  "A test that launches `oh` must run offline: vector aliases route at launch, and routing reads
  credentials from HOME and calls the providers unless `HERDR_ORCH_USAGE_FIXTURE` answers.
  Every harness that builds a launch environment (it names `ORCH_ASSIGNMENT_ROOT`) must also
  name that variable and must not pass the caller's real HOME."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def test-dir
  (str (fs/parent *file*)))

(defn offline-gaps
  "Problems in test source TEXT: a launch environment without the usage fixture seam, or one
  that passes the real HOME through."
  [text]
  (when (and (str/includes? text "process/process") (str/includes? text "\"ORCH_ASSIGNMENT_ROOT\""))
    (cond-> []
      (not (str/includes? text "HERDR_ORCH_USAGE_FIXTURE")) (conj :no-usage-fixture)
      (re-find #"\"HOME\"\s+\(System/getenv \"HOME\"\)" text) (conj :real-home))))

(deftest every-launch-harness-is-offline
  (testing "known-bad controls: the guard flags a missing seam and a real HOME"
    (is (= [:no-usage-fixture] (offline-gaps "(process/process x) {\"ORCH_ASSIGNMENT_ROOT\" dir \"HOME\" home}")))
    (is (= [:no-usage-fixture :real-home] (offline-gaps "(process/process x) {\"ORCH_ASSIGNMENT_ROOT\" dir \"HOME\" (System/getenv \"HOME\")}")))
    (is (nil? (offline-gaps "{\"ORCH_ASSIGNMENT_ROOT\" dir}")) "a file that spawns no process is out of scope")
    (is (= [:real-home] (offline-gaps "process/process \"ORCH_ASSIGNMENT_ROOT\" \"HERDR_ORCH_USAGE_FIXTURE\" \"HOME\" (System/getenv \"HOME\")"))))
  (testing "no test harness launches oh without the seam"
    (let [files (filter #(str/ends-with? (str %) ".clj") (fs/list-dir test-dir))
          harnesses (filter #(some? (offline-gaps (slurp (str %)))) files)]
      (is (<= 3 (count (filter #(str/includes? (slurp (str %)) "\"ORCH_ASSIGNMENT_ROOT\"") files)))
          "positive control: the cli, launcher and traits harnesses are found")
      (doseq [file files :let [gaps (offline-gaps (slurp (str file)))]]
        (is (empty? gaps) (str (fs/file-name file) " " gaps))))))

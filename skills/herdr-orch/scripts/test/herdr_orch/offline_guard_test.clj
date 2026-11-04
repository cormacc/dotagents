(ns herdr-orch.offline-guard-test
  "Launch fixtures must install the fake quota executable before routing."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def test-dir (str (fs/parent *file*)))
(defn offline-gaps [text]
  (when (and (str/includes? text "process/process") (str/includes? text "\"ORCH_ASSIGNMENT_ROOT\""))
    (cond-> []
      (not (str/includes? text "support/quota-env!")) (conj :no-fake-executable)
      (re-find #"\"HOME\"\s+\(System/getenv \"HOME\"\)" text) (conj :real-home))))

(deftest every-launch-harness-is-offline
  (is (= [] (offline-gaps "process/process \"ORCH_ASSIGNMENT_ROOT\" support/quota-env! \"HOME\" home")))
  (is (= [:no-fake-executable] (offline-gaps "process/process \"ORCH_ASSIGNMENT_ROOT\" \"HOME\" home")))
  (is (= [:no-fake-executable :real-home] (offline-gaps "process/process \"ORCH_ASSIGNMENT_ROOT\" \"HOME\" (System/getenv \"HOME\")")))
  (is (= [:real-home] (offline-gaps "process/process \"ORCH_ASSIGNMENT_ROOT\" support/quota-env! \"HOME\" (System/getenv \"HOME\")")))
  (is (nil? (offline-gaps "\"ORCH_ASSIGNMENT_ROOT\" dir")))
  (let [files (filter #(and (str/ends-with? (str %) ".clj") (not= "offline_guard_test.clj" (str (fs/file-name %)))) (fs/list-dir test-dir))
        harnesses (filter #(some? (offline-gaps (slurp (str %)))) files)]
    (is (<= 3 (count harnesses)) "positive control: CLI, launcher and traits harnesses are scanned")
    (doseq [file harnesses]
      (is (empty? (offline-gaps (slurp (str file)))) (str file)))))

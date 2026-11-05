(ns herdr-orch.offline-guard-test
  "Launch fixtures must isolate credentials before routing."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [herdr-orch.test-support :as support]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def test-dir (str (fs/parent *file*)))
(defn offline-gaps [text]
  (when (and (str/includes? text "process/process") (str/includes? text "\"ORCH_ASSIGNMENT_ROOT\""))
    (cond-> []
      (not (or (str/includes? text "support/quota-env!")
               (str/includes? text "(cli/fake-env"))) (conj :no-isolated-auth)
      (re-find #"\"HOME\"\s+\(System/getenv \"HOME\"\)" text) (conj :real-home))))

(deftest every-launch-harness-is-offline
  (is (= [] (offline-gaps "process/process \"ORCH_ASSIGNMENT_ROOT\" support/quota-env! \"HOME\" home")))
  (is (= [] (offline-gaps "process/process \"ORCH_ASSIGNMENT_ROOT\" (cli/fake-env {})")))
  (is (= [:no-isolated-auth] (offline-gaps "process/process \"ORCH_ASSIGNMENT_ROOT\" \"HOME\" home")))
  (is (= [:no-isolated-auth :real-home] (offline-gaps "process/process \"ORCH_ASSIGNMENT_ROOT\" \"HOME\" (System/getenv \"HOME\")")))
  (is (= [:real-home] (offline-gaps "process/process \"ORCH_ASSIGNMENT_ROOT\" support/quota-env! \"HOME\" (System/getenv \"HOME\")")))
  (is (nil? (offline-gaps "\"ORCH_ASSIGNMENT_ROOT\" dir")))
  (let [files (filter #(and (str/ends-with? (str %) ".clj") (not= "offline_guard_test.clj" (str (fs/file-name %)))) (fs/list-dir test-dir))
        harnesses (filter #(some? (offline-gaps (slurp (str %)))) files)]
    (is (<= 3 (count harnesses)) "positive control: CLI, launcher and traits harnesses are scanned")
    (doseq [file harnesses]
      (is (empty? (offline-gaps (slurp (str file)))) (str file)))))

(deftest isolation-tripwire-detects-native-vendor-escape
  (support/call-with-temp-dir "vendor-tripwire-"
    (fn [dir]
      (let [env (support/quota-env! dir)
            log (get env "FAKE_VENDOR_LOG")
            control @(process/process [(str (fs/which "bb")) "-e" "(println :offline)"]
                                      {:env env :out :string :err :string})]
        (is (zero? (:exit control)) (:err control))
        (is (= ":offline\n" (:out control)))
        (is (not (fs/exists? log)))
        (let [bad @(process/process [(str (fs/path dir "quota-bin/codex")) "--version"]
                                    {:env env :out :string :err :string})]
          (is (= 97 (:exit bad)))
          (is (= "vendor escape\n" (slurp log))))))))

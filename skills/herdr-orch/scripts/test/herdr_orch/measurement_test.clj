(ns herdr-orch.measurement-test
  "Pure checks for the measurement launch/transport helper. The end-to-end behaviour of a
  subject Pi is covered inside the installed runtime by scripts/pi/test.sh."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [herdr-orch.measurement :as measurement]
            [herdr-orch.test-support :as support]))

(defn- write! [dir name text]
  (let [path (fs/path dir name)] (fs/create-dirs (fs/parent path)) (spit (str path) text) (str path)))
(defn- config! [dir config] (write! dir "m/measurement.json" (json/generate-string config)))
(defn- refused [pattern f]
  (let [e (try (f) nil (catch clojure.lang.ExceptionInfo e e))]
    (is (some? e) (str "expected refusal matching " pattern))
    (when e (is (re-find pattern (ex-message e)) (ex-message e)))))
(def valid {"hardTimeoutMs" 1000})

(deftest config-is-closed-and-validated-before-launch
  (support/call-with-temp-dir
   "measurement-config-"
   (fn [dir]
     (testing "a minimal configuration resolves with empty lists and empty system text"
       (let [resolved (measurement/read-config (config! dir valid))]
         (is (= {:tools [] :skills [] :extensions [] :expected-outputs [] :hard-timeout-ms 1000 :system-prompt nil}
                (select-keys resolved [:tools :skills :extensions :expected-outputs :hard-timeout-ms :system-prompt])))
         (is (= "" (measurement/compile-treatment resolved)))))
     (testing "every refusal fails before launch"
       (doseq [[config pattern] [[{} #"hardTimeoutMs"]
                                 [{"hardTimeoutMs" 0} #"hardTimeoutMs"]
                                 [{"hardTimeoutMs" 1.5} #"hardTimeoutMs"]
                                 [{"hardTimeoutMs" "10"} #"hardTimeoutMs"]
                                 [(assoc valid "surprise" 1) #"unknown fields: surprise"]
                                 [(assoc valid "tools" "read") #"`tools` must be a list"]
                                 [(assoc valid "tools" ["read" ""]) #"`tools` must be a list"]
                                 [(assoc valid "tools" ["re*"]) #"exact names"]
                                 [(assoc valid "tools" ["read,bash"]) #"exact names"]
                                 [(assoc valid "tools" ["read" "read"]) #"twice"]
                                 [(assoc valid "systemPromptFile" "missing.md") #"unreadable file"]
                                 [(assoc valid "skillFiles" ["missing.md"]) #"unreadable file"]
                                 [(assoc valid "extensions" ["missing.ts"]) #"unreadable file"]
                                 [(assoc valid "systemPromptFile" "") #"non-blank"]
                                 [(assoc valid "expectedOutputs" ["../escape.txt"]) #"inside the work root"]
                                 [(assoc valid "expectedOutputs" ["/abs.txt"]) #"inside the work root"]]]
         (refused pattern #(measurement/read-config (config! dir config)))))
     (testing "unreadable and non-JSON configuration"
       (refused #"unreadable" #(measurement/read-config (str (fs/path dir "nope.json"))))
       (write! dir "bad.json" "{not json")
       (refused #"not valid JSON" #(measurement/read-config (str (fs/path dir "bad.json"))))
       (write! dir "list.json" "[]")
       (refused #"JSON object" #(measurement/read-config (str (fs/path dir "list.json"))))))))

(deftest treatment-compiles-system-then-skills-and-freezes-inputs
  (support/call-with-temp-dir
   "measurement-treatment-"
   (fn [dir]
     (write! dir "m/system.md" "SYSTEM")
     (write! dir "m/skills/a.md" "SKILL A\nline")
     (write! dir "m/skills/b.md" "SKILL B")
     (let [path (config! dir {"hardTimeoutMs" 5 "systemPromptFile" "system.md" "skillFiles" ["skills/a.md" "skills/b.md"]
                              "tools" ["read"] "expectedOutputs" ["out/result.txt"]})
           resolved (measurement/read-config path)]
       (is (= "SYSTEM\n\nSKILL A\nline\n\nSKILL B" (measurement/compile-treatment resolved)))
       (testing "paths resolve against the configuration file and contents are frozen"
         (is (= (str (fs/canonicalize (fs/path dir "m/system.md"))) (str (fs/canonicalize (get-in resolved [:system-prompt :path])))))
         (spit (str (fs/path dir "m/system.md")) "EDITED")
         (is (= "SYSTEM\n\nSKILL A\nline\n\nSKILL B" (measurement/compile-treatment resolved)))
         (is (= ["out/result.txt"] (:expected-outputs resolved))))
       (testing "skills alone compile without a leading separator"
         (is (= "SKILL B" (measurement/compile-treatment {:system-prompt nil :skills [{:text "SKILL B"}]}))))))))

(deftest expected-outputs-must-not-pre-exist
  (support/call-with-temp-dir
   "measurement-outputs-"
   (fn [dir]
     (let [resolved {:expected-outputs ["fresh/out.txt" "tracked.txt"]}]
       (write! dir "tracked.txt" "already here")
       (refused #"already exists" #(measurement/check-expected-outputs! resolved dir))
       (is (nil? (measurement/check-expected-outputs! {:expected-outputs ["fresh/out.txt"]} dir)))
       (is (nil? (measurement/check-expected-outputs! {:expected-outputs []} dir)))))))

(deftest subject-env-drops-routing-and-keeps-credentials
  (let [env (measurement/subject-env {"PATH" "/bin" "ANTHROPIC_API_KEY" "k" "HERDR_PANE_ID" "w:p" "HERDR_ENV" "1"
                                      "HERDR_ORCH_TASK" "t" "HERDR_ORCH_PERSONA" "worker" "HERDR_ORCH_BIN" "/oh"
                                      "ORCH_ASSIGNMENT_ROOT" "/root"}
                                     "task-9")]
    (is (= {"PATH" "/bin" "ANTHROPIC_API_KEY" "k" "HERDR_ORCH_SUBJECT" "task-9"} env))))

(deftest launch-spec-never-places-the-assignment-on-argv
  (support/call-with-temp-dir
   "measurement-launch-"
   (fn [dir]
     (write! dir "m/system.md" "SYSTEM")
     (let [resolved (measurement/read-config (config! dir {"hardTimeoutMs" 5 "systemPromptFile" "system.md" "tools" ["read" "bash"] "extensions" []}))
           assignment "-/@ {{template}} --leading hyphen"
           spec (measurement/launch-spec {:resolved resolved :task "task-1" :assignment assignment :evidence-dir (str (fs/path dir "evidence"))
                                          :adapter-dir "/adapters" :model-args ["--provider" "p" "--model" "m"] :effort-args ["--thinking" "low"]
                                          :parent-env {"PATH" "/bin" "HERDR_PANE_ID" "x"}})
           argv (:argv spec)]
       (is (= "pi" (first argv)))
       (is (not-any? #(str/includes? % "{{template}}") argv))
       (is (= assignment (slurp (str (fs/path dir "evidence" "assignment.txt")))))
       (is (= "SYSTEM" (slurp (str (fs/path dir "evidence" "treatment.txt")))))
       (is (= measurement/input-sentinel (last argv)))
       (is (= ["--mode" "json" "-p" measurement/input-sentinel] (take-last 4 argv)))
       (testing "the adapter loads last so it records the request after every other extension"
         (let [es (keep-indexed (fn [i x] (when (= "-e" x) (nth argv (inc i)))) argv)]
           (is (= "/adapters/measurement-adapter.ts" (last es)))))
       (testing "isolation flags, tool selection and model arguments"
         (is (every? (set argv) ["--no-session" "-ne" "--no-skills" "--no-prompt-templates" "--no-context-files" "--no-mcp"]))
         (is (= ["--tools" "read,bash"] (->> argv (partition 2 1) (filter #(= "--tools" (first %))) first)))
         (is (some #{"--model"} argv))
         (is (some #{"low"} argv)))
       (testing "an empty tool list is zero tools, not the harness default"
         (let [none (:argv (measurement/launch-spec {:resolved (assoc resolved :tools []) :task "t" :assignment "a" :evidence-dir (str (fs/path dir "e2"))
                                                     :adapter-dir "/adapters" :model-args [] :effort-args [] :parent-env {}}))]
           (is (some #{"--no-tools"} none))
           (is (not (some #{"--tools"} none)))))
       (testing "env carries the manifest and subject marker without routing values"
         (is (= {"PATH" "/bin" "HERDR_ORCH_SUBJECT" "task-1" "HERDR_ORCH_MEASUREMENT_MANIFEST" (:manifest-path spec)} (:env spec)))
         (let [manifest (json/parse-string (slurp (:manifest-path spec)) true)]
           (is (= "SYSTEM" (:treatment manifest)))
           (is (= ["read" "bash"] (:tools manifest)))))))))

(defn- evidence!
  "Writes adapter evidence: `terminal.json` and `:requests` payload files `request-N.json`."
  [dir {:keys [terminal requests]}]
  (when terminal (write! dir "terminal.json" (if (string? terminal) terminal (json/generate-string terminal))))
  (doseq [i (range 1 (inc (or requests 0)))] (write! dir (str "request-" i ".json") (json/generate-string {:model "m"})))
  dir)
(deftest terminal-evidence-decides-execution-success
  (let [good {:success true :requests 1 :violations []}
        two (assoc good :requests 2)]
    (doseq [[label evidence success? pattern]
            [["well-formed success" {:terminal good :requests 1} true nil]
             ["well-formed two-request success" {:terminal two :requests 2} true nil]
             ["missing terminal" {:requests 1} false #"missing"]
             ["malformed terminal" {:terminal "{not json" :requests 1} false #"malformed"]
             ["harness failure" {:terminal (assoc good :success false :reason "boom") :requests 1} false #"boom"]
             ["violations recorded" {:terminal (assoc good :violations ["x"]) :requests 1} false #"violations"]
             ["missing request payload" {:terminal two :requests 1} false #"request payload evidence for request 2 is missing"]
             ["payload beyond the recorded count" {:terminal good :requests 2} false #"inconsistent with the recorded request count"]
             ["terminal without a request count" {:terminal {:success true :violations []} :requests 1} false #"no valid request count"]
             ["non-integer request count" {:terminal (assoc good :requests "1") :requests 1} false #"no valid request count"]
             ["zero recorded requests" {:terminal (assoc good :requests 0)} false #"no provider request was recorded"]]]
      (support/call-with-temp-dir
       "measurement-evidence-"
       (fn [dir]
         (let [result (measurement/interpret-evidence (evidence! dir evidence))]
           (is (= success? (:success? result)) label)
           (when pattern (is (re-find pattern (str/join "; " (:reasons result))) label))))))
    (testing "a request payload that is not valid JSON fails closed"
      (support/call-with-temp-dir
       "measurement-evidence-"
       (fn [dir]
         (evidence! dir {:terminal good :requests 1})
         (write! dir "request-1.json" "{not json")
         (let [result (measurement/interpret-evidence dir)]
           (is (false? (:success? result)))
           (is (re-find #"request 1 is missing or malformed" (str/join "; " (:reasons result))))))))))

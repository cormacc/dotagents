#!/usr/bin/env bb
;; Checks that `run-suites` runs every command after a failure and reports each failure.
(require '[babashka.fs :as fs]
         '[clojure.string :as str]
         '[clojure.test :refer [deftest is run-tests]])

(load-file (str (fs/path (fs/parent *file*) "isolated-test-run.bb")))

(deftest a-failing-command-does-not-stop-later-commands
  (let [marker (str (fs/path (fs/create-temp-dir {:prefix "run-suites-test-"}) "ran"))
        commands [["-e" "(System/exit 3)"]
                  ["-e" (str "(spit " (pr-str marker) " \"ran\")")]
                  ["-e" "(System/exit 5)"]]
        out (java.io.StringWriter.)
        error (binding [*out* out]
                (try (run-suites commands) nil
                     (catch clojure.lang.ExceptionInfo e e)))]
    (is (fs/exists? marker) "the command after a failure still runs")
    (is (some? error) "a failure makes the run fail")
    (is (= [{:argv (first commands) :exit 3} {:argv (last commands) :exit 5}]
           (:failures (ex-data error))))
    (is (str/includes? (str out) "2 of 3 test commands failed"))))

(deftest all-commands-passing-returns-normally
  (is (nil? (binding [*out* (java.io.StringWriter.)]
              (run-suites [["-e" "nil"] ["-e" "nil"]])))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))

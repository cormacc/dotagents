(ns org-tasks.test-runner-test
  (:require [babashka.process :as process]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org-tasks.test-runner :as runner]
            [org-tasks.test-runner-platform-fixture]
            [org-tasks.test-runner-serial-fixture]
            [org-tasks.test-runner-unsafe-fixture])
  (:import [java.util.concurrent Executors]))

(defn- assert-serial-global-var-mutations! [ns-sym]
  (#'runner/assert-serial-global-var-mutations! (find-ns ns-sym)))

(defn- run-test-runner [& args]
  @(process/process (into ["bb" "test-clojure"] args)
                    {:out :string :err :string}))

(deftest explicit-namespace-arguments-are-validated-before-loading
  (testing "a discovered namespace is an explicit whitelist"
    (let [proc (run-test-runner "org-tasks.doctor-test")]
      (is (zero? (:exit proc)) (:err proc))
      (is (str/includes? (:out proc) "Testing org-tasks.doctor-test"))
      (is (not (str/includes? (:out proc) "Testing org-tasks.root-test")))))
  (doseq [arg ["--not-a-test-namespace" "org-tasks.unknown-test"]]
    (let [proc (run-test-runner arg)]
      (is (= 1 (:exit proc)) (:err proc))
      (is (str/includes? (:err proc) "Usage: bb test [namespace ...]"))
      (is (not (str/includes? (:err proc) "Could not locate"))))))

(deftest linux-only-vars-are-skipped-and-reported-off-linux
  (let [nses ['org-tasks.test-runner-platform-fixture]
        tagged #'org-tasks.test-runner-platform-fixture/linux-only-fixture
        skipped #(#'runner/platform-skipped-vars nses %)]
    (testing "Linux runs every var"
      (is (empty? (skipped "Linux"))))
    (testing "another platform skips only the tagged var"
      (is (= [tagged] (vec (skipped "Mac OS X")))))
    (testing "a skipped var loses :test and is named in the report line"
      (let [test-fn (:test (meta tagged))]
        (try
          (let [out (with-out-str (#'runner/skip-vars! [tagged] "Mac OS X"))]
            (is (nil? (:test (meta tagged))))
            (is (str/includes? out "skipped 1 Linux-only tests on Mac OS X: org-tasks.test-runner-platform-fixture/linux-only-fixture") out))
          (finally (alter-meta! tagged assoc :test test-fn)))))
    (is (some? (:test (meta #'org-tasks.test-runner-platform-fixture/portable-fixture))))))

(deftest serial-and-local-mutation-fixture-is-accepted
  (is (nil? (assert-serial-global-var-mutations!
             'org-tasks.test-runner-serial-fixture))))

(deftest untagged-global-mutation-fails-before-parallel-execution
  (let [pool (Executors/newSingleThreadExecutor)]
    (try
      (let [error (try
                    (#'runner/run-parallel-ns
                     'org-tasks.test-runner-unsafe-fixture pool)
                    nil
                    (catch clojure.lang.ExceptionInfo e e))]
        (testing "the fixture's missing ^:serial metadata is the failure reason"
          (is (some? error))
          (is (= 'org-tasks.test-runner-unsafe-fixture (:ns (ex-data error))))
          (is (= 'untagged-global-mutation (:test (ex-data error))))
          (is (= 'with-redefs (:mutator (ex-data error))))
          (is (string? (:source (ex-data error))))
          (is (pos-int? (:line (ex-data error))))
          (is (pos-int? (:column (ex-data error))))
          (is (re-find #"org-tasks.test-runner-unsafe-fixture/untagged-global-mutation"
                       (.getMessage error)))
          (is (re-find #"must be tagged \^:serial" (.getMessage error)))))
      (finally (.shutdown pool)))))

(deftest unsafe-symbol-set-is-narrow-and-covers-each-global-var-mutator
  (testing "qualified and unqualified deftest forms are recognized"
    (is (#'runner/deftest-form? '(deftest fixture (is true))))
    (is (#'runner/deftest-form? '(clojure.test/deftest fixture (is true)))))
  (testing "only the three definite global-var mutation forms match"
    (doseq [body '((with-redefs [fixture-var :redefined] nil)
                   (clojure.core/with-redefs [fixture-var :redefined] nil)
                   (with-redefs-fn {} (fn [] nil))
                   (alter-var-root #'fixture-var identity))]
      (is (some? (#'runner/unsafe-call `(deftest fixture ~body))) (str body)))
    (doseq [body '((reset! (atom 0) 1)
                   (swap! (atom 0) inc))]
      (is (nil? (#'runner/unsafe-call `(deftest fixture ~body))) (str body)))))

(ns org-tasks.test-runner-test
  (:require [clojure.test :refer [deftest is testing]]
            [org-tasks.test-runner :as runner]
            [org-tasks.test-runner-serial-fixture]
            [org-tasks.test-runner-unsafe-fixture])
  (:import [java.util.concurrent Executors]))

(defn- assert-serial-global-var-mutations! [ns-sym]
  (#'runner/assert-serial-global-var-mutations! (find-ns ns-sym)))

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

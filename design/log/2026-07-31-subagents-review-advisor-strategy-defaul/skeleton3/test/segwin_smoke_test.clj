(ns segwin-smoke-test
  "Visible smoke tests: a thin happy-path sample, not the acceptance standard.
  Passing these does not mean the ASSIGNMENT.md contract is satisfied, and they
  say nothing at all about the performance requirement (see `bb bench`)."
  (:require [clojure.test :refer [deftest is testing]]
            [segwin :as sw]))

(deftest worked-example-from-the-assignment
  (is (= [10 0 nil]
         (sw/run-ops [[:points [10 20 30]]
                      [:add 10 20 5]
                      [:sum 10 30]
                      [:max 25 40]
                      [:max 31 40]]))))

(deftest queries-with-no-adds
  (testing "everything starts at zero"
    (is (= [0 0] (sw/run-ops [[:points [1 2 3]] [:sum 1 3] [:max 1 3]])))))

(deftest adds-accumulate
  (is (= [7] (sw/run-ops [[:points [5]] [:add 1 10 3] [:add 4 6 4] [:sum 1 10]]))))

(deftest inclusive-endpoints
  (testing "both range ends are inclusive"
    (is (= [2] (sw/run-ops [[:points [1 2]] [:add 1 2 1] [:sum 1 2]])))))

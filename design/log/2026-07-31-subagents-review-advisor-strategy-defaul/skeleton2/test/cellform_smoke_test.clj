(ns cellform-smoke-test
  "Visible smoke tests: a thin happy-path sample, not the acceptance standard.
  Passing these does not mean the ASSIGNMENT.md contract is satisfied."
  (:require [clojure.test :refer [deftest is testing]]
            [cellform :as cf]))

(deftest literals-and-arithmetic
  (testing "number and string literals"
    (is (= {"A1" 2 "A2" "hello"} (cf/evaluate {"A1" "2" "A2" "hello"}))))
  (testing "operator precedence"
    (is (= {"A1" 14} (cf/evaluate {"A1" "=2+3*4"}))))
  (testing "parentheses"
    (is (= {"A1" 20} (cf/evaluate {"A1" "=(2+3)*4"})))))

(deftest references
  (testing "a formula reading two other cells"
    (is (= {"A1" 2 "A2" 3 "B1" 5}
           (cf/evaluate {"A1" "2" "A2" "3" "B1" "=A1+A2"}))))
  (testing "a chain resolves regardless of declaration order"
    (is (= {"C1" 3 "B1" 2 "A1" 1}
           (cf/evaluate {"C1" "=B1+1" "B1" "=A1+1" "A1" "1"})))))

(deftest functions
  (testing "SUM over a range"
    (is (= 6 (get (cf/evaluate {"A1" "1" "A2" "2" "A3" "3" "B1" "=SUM(A1:A3)"}) "B1"))))
  (testing "COUNT counts only numbers"
    (is (= 2 (get (cf/evaluate {"A1" "1" "A2" "x" "A3" "3" "B1" "=COUNT(A1:A3)"}) "B1"))))
  (testing "CONCAT renders integral numbers without a decimal point"
    (is (= "ab3" (get (cf/evaluate {"A1" "3" "B1" "=CONCAT(\"ab\",A1)"}) "B1")))))

(deftest simple-errors
  (testing "division by zero"
    (is (= :err/div0 (get (cf/evaluate {"A1" "=1/0"}) "A1"))))
  (testing "a two-cell cycle marks both participants"
    (let [r (cf/evaluate {"A1" "=B1" "B1" "=A1"})]
      (is (= :err/cycle (get r "A1")))
      (is (= :err/cycle (get r "B1"))))))

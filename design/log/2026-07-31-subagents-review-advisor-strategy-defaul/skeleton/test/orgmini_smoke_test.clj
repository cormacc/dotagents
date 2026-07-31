(ns orgmini-smoke-test
  "Visible smoke tests: a thin happy-path sample, not the acceptance criteria.
  Passing these does not mean the ASSIGNMENT.md contract is satisfied."
  (:require [clojure.test :refer [deftest is testing]]
            [orgmini :as om]))

(def sample
  (str "* TODO Write the parser\n"
       ":PROPERTIES:\n"
       ":CUSTOM_ID: abc-123\n"
       ":END:\n"
       "Some body text.\n"
       "** DONE A child heading :done:\n"))

(deftest parses-a-simple-outline
  (let [{:keys [preamble nodes]} (om/parse-outline sample)
        [first-node second-node] nodes]
    (testing "no preamble before the first heading"
      (is (= "" preamble)))
    (testing "two nodes in document order"
      (is (= 2 (count nodes))))
    (testing "first node"
      (is (= 1 (:level first-node)))
      (is (= "TODO" (:todo first-node)))
      (is (= "Write the parser" (:title first-node)))
      (is (= [] (:tags first-node)))
      (is (= [["CUSTOM_ID" "abc-123"]] (:properties first-node)))
      (is (= "Some body text.\n" (:body first-node))))
    (testing "second node"
      (is (= 2 (:level second-node)))
      (is (= "DONE" (:todo second-node)))
      (is (= "A child heading" (:title second-node)))
      (is (= ["done"] (:tags second-node)))
      (is (= [] (:properties second-node)))
      (is (= "" (:body second-node))))))

(deftest round-trips-canonical-text
  (is (= sample (om/serialize-outline (om/parse-outline sample)))))

(deftest handles-a-heading-with-no-keyword
  (let [node (first (:nodes (om/parse-outline "* Just a title\n")))]
    (is (nil? (:todo node)))
    (is (= "Just a title" (:title node)))))

(ns orch-tree-test
  (:require [clojure.test :refer [deftest is]]
            [orch-tree :as t]))

(deftest desired-pads-order-and-defaults-marks
  (is (= {"w1:p1" {:name nil :marks "" :order "0000"}
          "w1:p2" {:name "scout-1-light" :marks "├" :order "0012"}}
         (t/desired {"w1:p1" {:order 0 :name nil}
                     "w1:p2" {:order 12 :marks "├" :name "scout-1-light"}}))))

(deftest set-args-shapes
  (is (= ["pane" "report-metadata" "w1:p2" "--source" t/source "--display-agent" "c" "--token" "tree=└" "--token" "order=0001"]
         (t/set-args "w1:p2" {:name "c" :marks "└" :order "0001"} nil)))
  (is (= ["pane" "report-metadata" "w1:p1" "--source" t/source "--token" "order=0000"]
         (t/set-args "w1:p1" {:name nil :marks "" :order "0000"} nil))
      "entry-less root: order token only")
  (is (= ["pane" "report-metadata" "w1:p1" "--source" t/source "--clear-display-agent" "--clear-token" "tree" "--token" "order=0000"]
         (t/set-args "w1:p1" {:name nil :marks "" :order "0000"} {:name "c" :marks "└"}))
      "a row that lost its name drops the stale name and mark")
  (is (some #{"--clear-token"} (t/set-args "w1:p3" {:name "c" :marks "" :order "0001"} nil))
      "an empty mark is cleared, not reported empty"))

(deftest parse-agents-nesting
  (is (= [{:pane_id "w1:p1"}] (t/parse-agents "{\"result\":{\"agents\":[{\"pane_id\":\"w1:p1\"}]}}")))
  (is (thrown? Exception (t/parse-agents "{\"result\":{}}"))))

(ns orch-tree-test
  (:require [clojure.test :refer [deftest is]]
            [orch-tree :as t]))

(def agents
  [{:pane_id "w1:p1" :workspace_id "w1" :agent "pi"}
   {:pane_id "w1:p2" :workspace_id "w1" :agent "pi"}
   {:pane_id "w2:p1" :workspace_id "w2" :agent "claude" :name "mine"}
   {:pane_id "w3:p1" :workspace_id "w3" :agent "pi"}])

(deftest desired-names-roots-and-keeps-children
  (is (= {"w1:p1" {:name "local:space1:pi" :marks "" :order "0000"}
          "w1:p2" {:name "├ scout-1-light" :marks "├" :order "0012"}
          "w2:p1" {:name "local:space2:mine" :marks "" :order nil}
          "w3:p1" {:name "local:w3:pi" :marks "" :order nil}}
         (t/desired {"w1:p1" {:order 0 :name nil}
                     "w1:p2" {:order 12 :marks "├" :name "scout-1-light"}}
                    agents
                    {"w1" "space1" "w2" "space2"})))
  (is (= "local:space1:worker-1" (t/root-name (first agents) {"w1" "space1"} "worker-1"))
      "a root ledger name wins over the agent kind"))

(deftest set-args-shapes
  (is (= ["pane" "report-metadata" "w1:p2" "--source" t/source "--display-agent" "└ c" "--clear-token" "tree" "--token" "order=0001"]
         (t/set-args "w1:p2" {:name "└ c" :marks "└" :order "0001"} nil))
      "the mark is in the name; the tree token is never reported")
  (is (= ["pane" "report-metadata" "w1:p1" "--source" t/source "--clear-token" "tree" "--token" "order=0000"]
         (t/set-args "w1:p1" {:name nil :marks "" :order "0000"} nil))
      "nameless root: no display name")
  (is (= ["pane" "report-metadata" "w1:p1" "--source" t/source "--clear-display-agent" "--clear-token" "tree" "--token" "order=0000"]
         (t/set-args "w1:p1" {:name nil :marks "" :order "0000"} {:name "c" :marks "└"}))
      "a row that lost its name drops the stale name and mark")
  (is (= ["pane" "report-metadata" "w1:p1" "--source" t/source "--display-agent" "r" "--clear-token" "tree" "--clear-token" "order"]
         (t/set-args "w1:p1" {:name "r" :marks "" :order nil} {:name "r" :marks "" :order "0000"}))
      "a row that left the tree drops its stale order"))

(deftest parse-agents-nesting
  (is (= [{:pane_id "w1:p1"}] (t/parse-agents "{\"result\":{\"agents\":[{\"pane_id\":\"w1:p1\"}]}}")))
  (is (thrown? Exception (t/parse-agents "{\"result\":{}}"))))

(deftest parse-workspace-labels-skips-unlabelled
  (is (= {"w1" "a"} (t/parse-workspace-labels "{\"result\":{\"workspaces\":[{\"workspace_id\":\"w1\",\"label\":\"a\"},{\"workspace_id\":\"w2\",\"label\":\"\"}]}}")))
  (is (thrown? Exception (t/parse-workspace-labels "{\"result\":{}}"))))

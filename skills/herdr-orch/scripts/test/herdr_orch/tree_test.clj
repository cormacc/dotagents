(ns herdr-orch.tree-test
  (:require [clojure.test :refer [deftest is testing]]
            [herdr-orch.tree :as tree]))

(defn- ag
  ([pane ws] (ag pane ws (str "sess-" pane)))
  ([pane ws sess]
   {:pane_id pane :workspace_id ws :agent_session (when sess {:value sess})}))

(defn- entry [pane label parent-pane started & {:keys [session] :or {session ::default}}]
  {:pane-id pane :label label :started-at started
   :parent-session (when parent-pane (str "sess-" parent-pane))
   :child-session (let [s (if (= session ::default) (str "sess-" pane) session)]
                    (when s {:value s}))})

(defn- shape [m] (into {} (map (fn [[k v]] [k [(:order v) (:marks v) (:name v)]])) m))

(deftest root-placement-and-nesting
  (let [agents  [(ag "p1" "w1") (ag "p2" "w1") (ag "p5" "w2") (ag "p0" "w1")]
        ledger  [(entry "p1" "scout-1" nil "2026-01-01T00:00:01Z")
                 (entry "p2" "worker-1" nil "2026-01-01T00:00:02Z")
                 (entry "p3" "planner-1/scout-2" "p1" "2026-01-01T00:00:03Z")
                 (entry "p4" "x/y/reviewer-1" "p3" "2026-01-01T00:00:04Z")
                 (entry "p5" "root-w2" nil "2026-01-01T00:00:05Z")]
        agents* (conj agents (ag "p3" "w1") (ag "p4" "w1"))]
    (testing "children under a live parent in DFS order; roots keep agent-list order; order is per workspace"
      (is (= {"p1" [0 "" "scout-1"]
              "p3" [1 "└" "scout-2"]
              "p4" [2 "⠀ └" "reviewer-1"]
              "p2" [3 "" "worker-1"]
              "p5" [0 "" "root-w2"]}
             (shape (tree/rows ledger agents*)))))
    (testing "a non-oh agent gets no row"
      (is (not (contains? (tree/rows ledger agents*) "p0"))))))

(deftest marks-continue-through-unfinished-branches
  (let [agents (mapv #(ag % "w1") ["p1" "p2" "p3" "p4"])
        ledger [(entry "p1" "root" nil "2026-01-01T00:00:01Z")
                (entry "p3" "late" "p1" "2026-01-01T00:00:03Z")
                (entry "p2" "early" "p1" "2026-01-01T00:00:02Z")
                (entry "p4" "grand" "p2" "2026-01-01T00:00:04Z")]]
    (is (= {"p1" [0 "" "root"]
            "p2" [1 "├" "early"]
            "p4" [2 "│ └" "grand"]
            "p3" [3 "└" "late"]}
           (shape (tree/rows ledger agents))))))

(deftest child-shown-once-with-its-own-pane
  (let [agents [(ag "p1" "w1") (ag "p2" "w1")]
        ledger [(entry "p1" "a" nil "2026-01-01T00:00:01Z")
                (entry "p2" "b" "p1" "2026-01-01T00:00:02Z")]
        out    (tree/rows ledger agents)]
    (is (= 2 (count out)))
    (is (= [0 "" "a"] ((juxt :order :marks :name) (out "p1"))))
    (is (= [1 "└" "b"] ((juxt :order :marks :name) (out "p2"))))))

(deftest naming-is-last-label-segment
  (let [out (tree/rows [(entry "p1" "planner-2/scout-1-light" nil "2026-01-01T00:00:01Z")
                        (entry "p2" "plain" nil "2026-01-01T00:00:02Z")]
                       [(ag "p1" "w1") (ag "p2" "w1")])]
    (is (= "scout-1-light" (:name (out "p1"))))
    (is (= "plain" (:name (out "p2"))))))

(deftest parent-not-live-makes-a-root
  (testing "parent not listed: root"
    (is (= {"p2" [0 "" "b"]}
           (shape (tree/rows [(entry "p2" "b" "p1" "2026-01-01T00:00:02Z")
                              (entry "p1" "a" nil "2026-01-01T00:00:01Z")]
                             [(ag "p2" "w1")])))))
  (testing "closed-at is not consulted: a listed parent with closed-at still parents"
    (is (= {"p1" [0 "" "a"] "p2" [1 "└" "b"]}
           (shape (tree/rows [(entry "p2" "b" "p1" "2026-01-01T00:00:02Z")
                              (assoc (entry "p1" "a" nil "2026-01-01T00:00:01Z") :closed-at "2026-01-02T00:00:00Z")]
                             [(ag "p2" "w1") (ag "p1" "w1")])))))
  (testing "parent in another workspace: root"
    (is (= {"p1" [0 "" "a"] "p2" [0 "" "b"]}
           (shape (tree/rows [(entry "p1" "a" nil "2026-01-01T00:00:01Z")
                              (entry "p2" "b" "p1" "2026-01-01T00:00:02Z")]
                             [(ag "p1" "w1") (ag "p2" "w2")]))))))

(deftest liveness-by-session-only
  (testing "no recorded child session: never live, even on a pane held by an unrelated agent"
    (is (= {} (tree/rows [(entry "p1" "a" nil "2026-01-01T00:00:01Z" :session nil)]
                         [(ag "p1" "w1" "anything")])))
    (is (= {} (tree/rows [(entry "p1" "a" nil "2026-01-01T00:00:01Z" :session nil)]
                         [(ag "p1" "w1" nil)]))))
  (testing "session match wins over the recorded pane"
    (let [out (tree/rows [(assoc (entry "old" "a" nil "2026-01-01T00:00:01Z" :session "s") :pane-id "old")]
                         [(ag "new" "w1" "s")])]
      (is (= {"new" [0 "" "a"]} (shape out)))))
  (testing "an entry whose session differs from the agent on its pane is not live"
    (is (= {} (tree/rows [(entry "p1" "a" nil "2026-01-01T00:00:01Z" :session "s1")]
                         [(ag "p1" "w1" "s2")])))))

(deftest rounds-and-degenerate-input
  (testing "newest round of a child names the pane"
    (is (= "round-2"
           (:name ((tree/rows [(entry "p1" "round-2" nil "2026-01-01T00:00:02Z")
                               (entry "p1" "round-1" nil "2026-01-01T00:00:01Z")]
                              [(ag "p1" "w1")])
                   "p1")))))
  (testing "empty inputs"
    (is (= {} (tree/rows [] [])))
    (is (= {} (tree/rows [(entry "p1" "a" nil "t")] []))))
  (testing "a parent cycle yields roots, not a hang"
    ;; bounded so a broken cycle guard fails the test instead of hanging the suite
    (let [f   (future (tree/rows [(entry "p1" "a" "p2" "2026-01-01T00:00:01Z")
                                  (entry "p2" "b" "p1" "2026-01-01T00:00:02Z")]
                                 [(ag "p1" "w1") (ag "p2" "w1")]))
          out (deref f 5000 ::timeout)]
      (future-cancel f)
      (is (not= ::timeout out))
      (is (= {"p1" [0 "" "a"] "p2" [1 "" "b"]} (some-> out shape))))))

(deftest entry-less-parent
  (let [t1 "2026-01-01T00:00:01Z" t2 "2026-01-01T00:00:02Z" t3 "2026-01-01T00:00:03Z"]
    (testing "orchestrator without an entry parents its oh children"
      (is (= {"p0" [0 "" nil] "p1" [1 "├" "a"] "p2" [2 "└" "b"]}
             (shape (tree/rows [(entry "p1" "a" "p0" t1) (entry "p2" "b" "p0" t2)]
                               [(ag "p0" "w1") (ag "p1" "w1") (ag "p2" "w1")])))))
    (testing "a grandchild nests under an oh child of an entry-less orchestrator"
      (is (= {"p0" [0 "" nil] "p1" [1 "└" "a"] "p2" [2 "⠀ └" "b"]}
             (shape (tree/rows [(entry "p1" "a" "p0" t1) (entry "p2" "b" "p1" t2)]
                               [(ag "p0" "w1") (ag "p1" "w1") (ag "p2" "w1")])))))
    (testing "an agent named only by a dead or unlisted child gets no row"
      (is (= {} (tree/rows [(entry "p1" "a" "p0" t1)] [(ag "p0" "w1")])))
      (is (= {"p2" [0 "" "b"]}
             (shape (tree/rows [(entry "p1" "a" "p0" t1) (entry "p2" "b" nil t2)]
                               [(ag "p0" "w1") (ag "p2" "w1")])))))
    (testing "an orchestrator in another workspace does not adopt the child"
      (is (= {"p1" [0 "" "a"]}
             (shape (tree/rows [(entry "p1" "a" "p0" t1)]
                               [(ag "p0" "w2") (ag "p1" "w1")])))))
    (testing "orders are per workspace and orchestrators keep agent-list order"
      (is (= {"p0" [0 "" nil] "p1" [1 "└" "a"] "q0" [0 "" nil] "q1" [1 "└" "b"]}
             (shape (tree/rows [(entry "p1" "a" "p0" t1) (entry "q1" "b" "q0" t3)]
                               [(ag "p0" "w1") (ag "q0" "w2") (ag "p1" "w1") (ag "q1" "w2")])))))))

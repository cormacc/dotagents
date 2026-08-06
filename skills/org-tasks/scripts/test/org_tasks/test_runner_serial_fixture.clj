(ns ^{:parallel-tests true} org-tasks.test-runner-serial-fixture
  (:require [clojure.test :refer [deftest is]]))

(def fixture-var :original)

;; Edamame must discard reader-commented global mutation forms.
#_(deftest commented-out-global-mutation
    (alter-var-root #'fixture-var (constantly :changed)))

;; The Babashka reader branch contains ordinary local mutation, which is safe.
#?(:bb
   (deftest reader-conditional-local-state
     (let [state (atom 0)]
       (swap! state inc)
       (reset! state 2)
       (is (= 2 @state))))
   :clj
   (deftest reader-conditional-local-state
     (is true)))

(deftest ^:serial tagged-global-mutation
  (with-redefs [fixture-var :redefined]
    (is (= :redefined fixture-var))))

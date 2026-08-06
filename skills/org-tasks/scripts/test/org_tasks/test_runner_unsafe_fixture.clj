(ns ^{:parallel-tests true} org-tasks.test-runner-unsafe-fixture
  (:require [clojure.test :refer [deftest is]]))

(def fixture-var :original)

(deftest untagged-global-mutation
  (with-redefs [fixture-var :redefined]
    (is (= :redefined fixture-var))))

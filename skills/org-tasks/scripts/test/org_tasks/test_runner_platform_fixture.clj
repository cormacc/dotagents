(ns org-tasks.test-runner-platform-fixture
  (:require [clojure.test :refer [deftest is]]))

(deftest ^:linux-only linux-only-fixture
  (is true))

(deftest portable-fixture
  (is true))

(ns org-tasks.insert-test
  "Focused tests for section insertion."
  (:require [clojure.test :refer [deftest is]]
            [org-tasks.insert :as insert]))

(deftest insert-subtree-matches-tagged-section-through-parser-helper
  (is (= {:content "* Improvements :wip:foo:\n** TODO Extract parser\n"
          :line 2}
         (insert/insert-subtree-into-section
           "* Improvements :wip:foo:\n" "Improvements" "** TODO Extract parser\n"))))

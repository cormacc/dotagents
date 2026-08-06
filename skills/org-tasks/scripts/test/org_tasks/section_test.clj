(ns org-tasks.section-test
  "Tests for `org-tasks.section/read-section`.

  Mirrors `pi/extensions/tasks/section.test.ts`."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org-tasks.section :as section]))

(deftest default-section-is-summary
  (is (= "Summary" section/default-section)))

(deftest section-present
  (let [content (str/join "\n"
                          ["#+TITLE: doc"
                           ""
                           "* Summary"
                           "Compact paragraph."
                           ""
                           "* Plan"
                           "** TODO Step"
                           ""])]
    (let [r (section/read-section content "Summary")]
      (is (true? (:found r)))
      (is (= "* Summary" (:heading r)))
      (is (= "Compact paragraph.\n" (:body r))))
    (testing "nested ** subheadings inside the matched section are preserved verbatim"
      (let [r (section/read-section content "Plan")]
        (is (true? (:found r)))
        (is (= "* Plan" (:heading r)))
        (is (= "** TODO Step\n" (:body r)))))))

(deftest default-to-summary
  (let [content "* Summary\nbody\n* Plan\nx\n"]
    (is (true? (:found (section/read-section content))))
    (is (true? (:found (section/read-section content ""))))
    (is (true? (:found (section/read-section content "   "))))))

(deftest section-absent
  (let [content "* Summary\nbody\n* Plan\nx\n"]
    (is (= {:found false :section "Implementation"}
           (section/read-section content "Implementation")))
    (testing "echoes the user-requested casing"
      (is (= {:found false :section "implementation"}
             (section/read-section content "implementation"))))))

(deftest section-runs-to-eof
  (let [content (str/join "\n"
                          ["* Context"
                           "Background."
                           ""
                           "* Open questions"
                           "** OPEN Should we batch follow-ups?"
                           ""])
        r (section/read-section content "Open questions")]
    (is (true? (:found r)))
    (is (= "* Open questions" (:heading r)))
    (is (= "** OPEN Should we batch follow-ups?\n" (:body r)))))

(deftest eof-mid-line
  (let [r (section/read-section "* Summary\nbody line" "Summary")]
    (is (true? (:found r)))
    (is (= "body line" (:body r)))))

(deftest no-headings
  (is (= {:found false :section "Summary"}
         (section/read-section "Just some prose.\nNo headings.\n" "Summary")))
  (is (= {:found false :section "Summary"}
         (section/read-section "" "Summary"))))

(deftest src-block-shields-heading
  (let [content (str/join "\n"
                          ["* Summary"
                           "Example code below:"
                           "#+BEGIN_SRC org"
                           "* This looks like a heading but is inside SRC"
                           "** And so does this"
                           "#+END_SRC"
                           "More body after the block."
                           ""
                           "* Context"
                           "Real next section."
                           ""])
        r (section/read-section content "Summary")]
    (is (true? (:found r)))
    (is (= (str/join "\n"
                     ["Example code below:"
                      "#+BEGIN_SRC org"
                      "* This looks like a heading but is inside SRC"
                      "** And so does this"
                      "#+END_SRC"
                      "More body after the block."
                      ""])
           (:body r)))))

(deftest example-block-shields-heading
  (let [content (str/join "\n"
                          ["* Summary"
                           "#+BEGIN_EXAMPLE"
                           "* Fake heading in example"
                           "#+END_EXAMPLE"
                           ""
                           "* Context"
                           "Next."
                           ""])
        r (section/read-section content "Summary")]
    (is (true? (:found r)))
    (is (= (str/join "\n"
                     ["#+BEGIN_EXAMPLE"
                      "* Fake heading in example"
                      "#+END_EXAMPLE"
                      ""])
           (:body r)))))

(deftest lowercase-directives-shield
  (let [content (str/join "\n"
                          ["* Summary"
                           "#+begin_src"
                           "* fake"
                           "#+end_src"
                           ""
                           "* Plan"
                           "later"
                           ""])
        r (section/read-section content "Summary")]
    (is (true? (:found r)))
    (is (= "#+begin_src\n* fake\n#+end_src\n" (:body r)))))

(deftest case-insensitive-heading-with-tags
  (let [content (str/join "\n"
                          ["* SUMMARY :memory:"
                           "Yelling."
                           ""
                           "* plan :wip:foo:"
                           "later"
                           ""])]
    (let [r (section/read-section content "summary")]
      (is (true? (:found r)))
      (is (= "* SUMMARY :memory:" (:heading r))))
    (let [r (section/read-section content "Plan")]
      (is (true? (:found r)))
      (is (= "* plan :wip:foo:" (:heading r)))))
  (testing "shared parser stripping preserves expanded section-tag syntax"
    (is (= ["Summary"]
           (section/list-sections "* Summary :wip-foo:\n")))))

(deftest level-2-summary-not-matched
  (let [content (str/join "\n"
                          ["* Context"
                           "Background."
                           "** Summary"
                           "Nested, not level-1."
                           ""])
        r (section/read-section content "Summary")]
    (is (false? (:found r)))))

(deftest first-match-wins
  (let [content (str/join "\n"
                          ["* Summary"
                           "First."
                           "* Summary"
                           "Second."
                           ""])
        r (section/read-section content "Summary")]
    (is (true? (:found r)))
    (is (= "First." (:body r)))))

(deftest empty-body
  (let [content (str/join "\n"
                          ["* Summary"
                           "* Context"
                           "Has body."
                           ""])
        r (section/read-section content "Summary")]
    (is (true? (:found r)))
    (is (= "" (:body r)))))

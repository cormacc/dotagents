#!/usr/bin/env bb

(require '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[clojure.string :as str]
         '[clojure.test :refer [deftest is testing run-tests]])

(def script (str (fs/path (fs/parent (fs/absolutize *file*)) "unwrap.bb")))
(load-file script)

(defn unwrapped [format text] (:text (run format text)))

(deftest org-prose-is-unwrapped
  (is (= "One paragraph across three lines.\n\nNext paragraph.\n"
         (unwrapped :org "One paragraph\nacross three\nlines.\n\nNext paragraph.\n")))
  (testing "list items keep their indented continuation lines"
    (is (= "- First item, wrapped.\n- Second item.\n1. Numbered item, wrapped.\n- Term :: detail, wrapped.\n"
           (unwrapped :org "- First item,\n  wrapped.\n- Second item.\n1. Numbered item,\n   wrapped.\n- Term :: detail,\n  wrapped.\n"))))
  (testing "a line not indented beyond the bullet starts a new paragraph"
    (is (= "- Item text.\nNot the item.\n" (unwrapped :org "- Item\n  text.\nNot the item.\n")))))

(deftest org-structure-is-kept
  (let [text (str "* Heading\nCLOSED: [2026-10-16 Fri]\n:PROPERTIES:\n:CUSTOM_ID: x\n:CREATED: [2026-10-16]\n:END:\n"
                  "#+TITLE: T\n# comment line\n: fixed width\n: line two\n"
                  "| a | b |\n|---+---|\n| 1 | 2 |\n"
                  "#+BEGIN_SRC clojure\n(defn f\n  [x])\n#+END_SRC\n"
                  "-----\n"
                  "\\begin{equation}\nx = 1\n\\end{equation}\n")]
    (is (= text (unwrapped :org text))))
  (testing "regression: property lines inside a drawer are not joined"
    (is (= ":PROPERTIES:\n:KEY: one\n:OTHER: two\n:END:\n"
           (unwrapped :org ":PROPERTIES:\n:KEY: one\n:OTHER: two\n:END:\n"))))
  (testing "an explicit line break is kept"
    (is (= "First line\\\\\nsecond line.\n" (unwrapped :org "First line\\\\\nsecond line.\n")))))

(deftest markdown-prose-is-unwrapped
  (is (= "One paragraph across lines.\n\n- Item, wrapped.\n* Star item, wrapped.\n1. Numbered, wrapped.\n"
         (unwrapped :md "One paragraph\nacross lines.\n\n- Item,\n  wrapped.\n* Star item,\n  wrapped.\n1. Numbered,\n   wrapped.\n"))))

(deftest markdown-structure-is-kept
  (let [text (str "---\ntitle: x\nsummary: two\n---\n# Heading\nSetext\n======\n"
                  "```clojure\n(defn f\n  [x])\n```\n~~~\nplain\nfence\n~~~\n"
                  "> quote\n> continues\n\n| a | b |\n|---|---|\n\n<div>\nhtml\n</div>\n\n[ref]: https://example.com\n\n"
                  "    indented code\n    more code\n")]
    (is (= text (unwrapped :md text))))
  (testing "a two-space or backslash hard break is kept"
    (is (= "Line one  \nline two\\\nline three.\n" (unwrapped :md "Line one  \nline two\\\nline three.\n")))))

(deftest unwrapping-is-idempotent
  (doseq [[format text] [[:org "Para\nwraps.\n\n- item\n  wraps\n"] [:md "Para\nwraps.\n\n- item\n  wraps\n"]]]
    (let [once (unwrapped format text)]
      (is (= once (unwrapped format once))))))

(deftest guard-rejects-changed-output
  (testing "control: identical words pass"
    (is (nil? (guard ["a b" "c"] ["a b c"]))))
  (is (re-find #"words differ at word 2" (guard ["a b c"] ["a x c"])))
  (is (re-find #"words differ" (guard ["a b c"] ["a b"])))
  (is (re-find #"more lines" (guard ["a b"] ["a" "b"]))))

(deftest cli
  (let [dir (fs/create-temp-dir {:prefix "unwrap-test-"})
        in (str (fs/path dir "in.org"))
        out (str (fs/path dir "out.org"))
        run! #(deref (process/process (into ["bb" script] %&) {:out :string :err :string}))]
    (spit in "A wrapped\nparagraph.\n")
    (testing "writes the output file"
      (let [p (run! in out)]
        (is (zero? (:exit p)) (:err p))
        (is (= "A wrapped paragraph.\n" (slurp out)))))
    (testing "writes to stdout without an output file"
      (is (= "A wrapped paragraph.\n" (:out (run! in)))))
    (testing "rejects an unknown format"
      (is (= 2 (:exit (run! (str (fs/path dir "in.txt")))))))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))

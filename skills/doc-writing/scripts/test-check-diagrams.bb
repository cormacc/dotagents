#!/usr/bin/env bb

(require '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[clojure.string :as str]
         '[clojure.test :refer [deftest is testing run-tests]])

(def script (str (fs/path (fs/parent (fs/absolutize *file*)) "check-diagrams.bb")))
(load-file script)

(deftest extract-finds-diagram-blocks
  (testing "Org: mermaid and plantuml, any case, with header arguments; other languages ignored"
    (is (= [{:lang :mermaid :line 3 :body "graph TD\n  A-->B"}
            {:lang :plantuml :line 10 :body "A -> B"}]
           (extract :org (str "Intro\n#+BEGIN_SRC mermaid\ngraph TD\n  A-->B\n#+END_SRC\n"
                              "#+begin_src clojure\n(+ 1 2)\n#+end_src\n"
                              "#+begin_src plantuml :file x.svg\nA -> B\n#+end_src\n")))))
  (testing "Markdown: backtick and tilde fences; a longer fence is closed only by its own length"
    (is (= [{:lang :mermaid :line 2 :body "graph TD\n```not the end\n  A-->B"}
            {:lang :plantuml :line 8 :body "A -> B"}]
           (extract :md (str "````mermaid\ngraph TD\n```not the end\n  A-->B\n````\n\n"
                             "~~~plantuml\nA -> B\n~~~\n```python\nx\n```\n"))))))

(deftest warnings-name-known-traps
  (is (= [] (warnings {:lang :plantuml :body "A -> B : [:x]"})) "control: single brackets are fine")
  (is (= 1 (count (warnings {:lang :plantuml :body "A -> B : [[:x]]"}))))
  (is (= [] (warnings {:lang :mermaid :body "A[\"[[:x]]\"]"})) "Mermaid has no such trap"))

(defn- run-check [env & args]
  @(process/process (into [(str (fs/which "bb")) script] args)
                    (cond-> {:out :string :err :string} env (assoc :extra-env env))))

(defn- doc! [dir name text]
  (let [p (str (fs/path dir name))] (spit p text) p))

(deftest missing-tools-are-unavailable-not-pass
  (let [dir (fs/create-temp-dir {:prefix "check-diagrams-test-"})
        empty-path (str (fs/create-dirs (fs/path dir "empty-bin")))
        doc (doc! dir "d.org" "#+begin_src mermaid\ngraph TD\n  A-->B\n#+end_src\n")
        p (run-check {"PATH" empty-path} "--out" (str (fs/path dir "out")) doc)]
    (is (= 3 (:exit p)) (:out p))
    (is (re-find #"UNAVAILABLE .*d\.org:2 mermaid: mmdc is not on PATH" (:out p)))))

(deftest usage-errors
  (is (= 2 (:exit (run-check nil))))
  (is (= 2 (:exit (run-check nil "notes.txt")))))

(defn- rendering-test [lang tool good bad]
  (if-not (fs/which tool)
    (println (str "SKIP rendering test for " (name lang) ": " tool " is not on PATH"))
    (let [dir (fs/create-temp-dir {:prefix "check-diagrams-test-"})
          out (str (fs/path dir "out"))]
      (testing (str (name lang) ": a valid block passes and writes an image")
        (let [p (run-check nil "--out" out (doc! dir "good.md" (str "```" (name lang) "\n" good "\n```\n")))]
          (is (zero? (:exit p)) (:out p))
          (is (re-find #"PASS .*good\.md:2" (:out p)))
          (is (seq (fs/glob out "good-01.png")))))
      (testing (str (name lang) ": a broken block fails (positive control)")
        (let [p (run-check nil "--out" out (doc! dir "bad.org" (str "#+begin_src " (name lang) "\n" bad "\n#+end_src\n")))]
          (is (= 1 (:exit p)) (:out p))
          (is (re-find #"FAIL .*bad\.org:2" (:out p))))))))

(deftest mermaid-rendering
  (rendering-test :mermaid "mmdc" "flowchart TD\n  A --> B" "flowchart TD\n  A[\"unclosed --> B"))

(deftest plantuml-rendering
  (rendering-test :plantuml "plantuml" "A -> B : hello" "A -> : \nparticipant \"x"))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))

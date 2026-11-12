#!/usr/bin/env bb
;; A fake `oh` for scripts/test-run-trait-gate.bb. For `task run <persona> --trait <trait>
;; ... --print-prompt` it returns a composed persona: a fixed body, one other trait's
;; fragment, then the selected trait's fragment, each read from the packaged store the way
;; the interpolator substitutes it.
(require '[babashka.fs :as fs]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(defn fragment [trait]
  (let [root (str (fs/parent (fs/parent (fs/parent (fs/absolutize *file*)))))
        text (slurp (str (fs/path root "skills" "herdr-orch" "traits" trait "prompt.md")))]
    (str/replace text #"(?s)\A---\n.*?\n---\n" "")))

(let [[_task _run persona & more] *command-line-args*
      trait (second (drop-while #(not= "--trait" %) more))]
  (println (json/generate-string
            {:ok true
             :result {:persona persona
                      :traits ["read-only" trait]
                      :persona-text (str "# Fake " persona " persona\n\nFake body.\n\n" (fragment "read-only")
                                         "\n\n" (fragment trait))}})))

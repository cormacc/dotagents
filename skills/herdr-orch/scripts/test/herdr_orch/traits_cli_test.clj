(ns herdr-orch.traits-cli-test
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [herdr-orch.test-support :refer [call-with-temp-dir]]))

(def root
  (if (fs/absolute? *file*)
    (str (fs/canonicalize (fs/path (fs/parent *file*) "../../../../..")))
    (str (fs/canonicalize "."))))
(def bin (str root "/skills/herdr-orch/scripts/traits"))

(defn- call!
  ([cwd input & argv]
   @(process/process (into [bin] argv)
                     {:dir cwd :in input :out :string :err :string})))

(defn- write-trait! [root name shape text]
  (let [path (case shape
               :flat (fs/path root (str name ".md"))
               :directory (fs/path root name "prompt.md"))]
    (fs/create-dirs (fs/parent path))
    (spit (str path) text)
    (str path)))

(deftest stdin-json-mode-reports-resolution-unknowns-and-repeats
  (call-with-temp-dir "traits-cli-cwd-" (fn [cwd]
    (let [project (str (fs/path cwd "project-traits"))
          home (str (fs/path cwd "home-traits"))
          project-path (write-trait! project "focused" :directory
                                     "---\nname: focused\n---\nProject focus.")
          _ (write-trait! home "focused" :flat "---\nname: focused\n---\nHome focus.")
          proc (call! cwd "%focused %unknown %focused"
                      "--layer" (str "project=" project)
                      "--layer" (str "home=" home))
          envelope (json/parse-string (:out proc) true)]
      (is (zero? (:exit proc)) (:err proc))
      (is (= true (:ok envelope)))
      (is (= "herdr-orch/v1" (:schema envelope)))
      (is (= "Project focus. %unknown Project focus." (get-in envelope [:result :text])))
      (is (= [{:trait "focused" :source "project" :path project-path}]
             (get-in envelope [:result :resolved])))
      (is (= ["unknown"] (get-in envelope [:result :unknowns])))
      (is (= ["focused"] (get-in envelope [:result :repeats])))))))

(deftest file-and-plain-modes-preserve-grammar-boundaries
  (call-with-temp-dir "traits-cli-file-" (fn [cwd]
    (let [traits (str (fs/path cwd "traits"))
          _ (write-trait! traits "focused" :flat "---\nname: focused\n---\nExpanded")
          input-path (str (fs/path cwd "prompt.txt"))
          _ (spit input-path "page%focused %20 %focused")
          proc (call! cwd nil "--file" input-path "--plain" "--layer" (str "project=" traits))]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "page%focused %20 Expanded" (:out proc)))))))

(deftest directory-shape-never-concatenates-gate-siblings
  (call-with-temp-dir "traits-cli-gate-" (fn [cwd]
    (let [traits (str (fs/path cwd "traits"))
          _ (write-trait! traits "prune" :directory "---\nname: prune\n---\nPrompt only.")
          gate (fs/path traits "prune" "gate.md")
          _ (spit (str gate) "MUST NOT APPEAR")
          proc (call! cwd "%prune" "--layer" (str "project=" traits))
          envelope (json/parse-string (:out proc) true)]
      (is (zero? (:exit proc)) (:err proc))
      (is (= "Prompt only." (get-in envelope [:result :text])))
      (is (not (str/includes? (get-in envelope [:result :text]) "MUST NOT APPEAR")))))))

(deftest malformed-layer-fails-with-a-structured-message
  (call-with-temp-dir "traits-cli-failure-" (fn [cwd]
    (let [proc (call! cwd "text" "--layer" "missing-equals")
          envelope (json/parse-string (:err proc) true)]
      (is (= 1 (:exit proc)))
      (is (= false (:ok envelope)))
      (is (= "invalid --layer `missing-equals`; expected <source>=<dir>"
             (get-in envelope [:error :message])))))))

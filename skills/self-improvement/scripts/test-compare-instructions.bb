#!/usr/bin/env bb

(require '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[cheshire.core :as json]
         '[clojure.string :as str]
         '[clojure.test :refer [deftest is run-tests]])

(def root (str/trim (:out (process/shell {:out :string} "git" "rev-parse" "--show-toplevel"))))
(def script (str (fs/path root "skills/self-improvement/scripts/compare-instructions.bb")))
(def files ["one.md" "with space.md" "empty.md"])

(defn git! [dir & args]
  (:out (apply process/shell {:dir dir :out :string :err :string}
               "git" "-c" "user.name=Fixture" "-c" "user.email=fixture@example.invalid"
               "-c" "commit.gpgsign=false" "-c" "core.hooksPath=/dev/null" args)))

(defn commit! [dir]
  (apply git! dir "add" "--" files)
  (git! dir "commit" "--quiet" "-m" "fixture")
  (str/trim (git! dir "rev-parse" "HEAD")))

(defn with-fixture [f]
  (let [dir (str (fs/path root ".tmp" (str "instruction-metrics-" (random-uuid))))]
    (try
      (fs/create-dirs dir)
      (git! dir "init" "--quiet")
      (doseq [[path text] (zipmap files ["abcd\n" "x" ""])]
        (spit (str (fs/path dir path)) text))
      (f dir (commit! dir))
      (finally (fs/delete-tree dir)))))

(defn compare! [dir revision paths]
  (let [{:keys [exit out] :as result}
        (apply process/shell {:dir dir :out :string :err :string :continue true}
                 "bb" script (concat (when revision ["--ref" revision]) ["--"] paths))]
    (cond-> result (zero? exit) (assoc :report (json/parse-string out true)))))

(deftest counts-cover-newlines-unicode-and-per-file-rounding
  (with-fixture
    (fn [dir baseline]
      (spit (str (fs/path dir "one.md")) "abcdefgh\n\n")
      (spit (str (fs/path dir "with space.md")) "😀\n")
      (let [{:keys [exit report err]} (compare! dir nil files)]
        (is (zero? exit) err)
        (is (= baseline (:commit report)))
        (is (= {:lines 1 :characters 6 :tokens 3} (get-in report [:total :before])))
        (is (= {:lines 3 :characters 13 :tokens 4} (get-in report [:total :after])))
        (is (= {:lines 2 :characters 7 :tokens 1} (get-in report [:total :delta])))
        (is (= {:lines 0 :characters 1 :tokens 1} (get-in report [:files 1 :before])))
        (is (= {:lines 1 :characters 3 :tokens 1} (get-in report [:files 1 :after])))
        (is (= {:lines 0 :characters 0 :tokens 0} (get-in report [:files 2 :after]))))
      (let [head (commit! dir)
            older (compare! dir "HEAD^" files)
            current (compare! dir nil files)]
        (is (zero? (:exit older)) (:err older))
        (is (zero? (:exit current)) (:err current))
        (is (= baseline (get-in older [:report :commit])))
        (is (= head (get-in current [:report :commit])))
        (is (= {:lines 0 :characters 0 :tokens 0} (get-in current [:report :total :delta])))))))

(deftest invalid-revisions-and-missing-files-are-errors-not-zero-counts
  (with-fixture
    (fn [dir _]
      (doseq [[revision paths] [["not-a-revision" files] ["HEAD" ["not-in-ref.md"]]]]
        (let [{:keys [exit out err]} (compare! dir revision paths)]
          (is (not (zero? exit)))
          (is (str/blank? out) out)
          (is (str/includes? err "compare-instructions:") err)))
      (fs/delete (fs/path dir "one.md"))
      (let [{:keys [exit out err]} (compare! dir "HEAD" files)]
        (is (not (zero? exit)))
        (is (str/blank? out) out)
        (is (str/includes? err "one.md") err)))))

(let [{:keys [fail error]} (run-tests)]
  (System/exit (if (zero? (+ fail error)) 0 1)))

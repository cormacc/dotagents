#!/usr/bin/env bb
(ns test-package-logic-midi
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.test :refer [deftest is run-tests]]))

(def script (str (fs/path (fs/parent (fs/absolutize *file*)) "package.bb")))
(load-file script)
(alias 'pkg 'package-logic-midi)
(def root (:out (p/shell {:out :string} "git" "-C" (str pkg/skill-dir) "rev-parse" "--show-toplevel")))
(def scratch (fs/create-dirs (fs/path (clojure.string/trim root) ".tmp" "logic-package-tests")))
(defn- temp-dir [] (fs/create-temp-dir {:dir scratch :prefix "case-"}))
(defn- cli [& args] (apply p/shell {:out :string :err :string :continue true} "bb" script args))

(deftest checksum-accepts-known-input-and-rejects-corruption
  (let [f (fs/path (temp-dir) "data")
        expected "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"]
    (spit (str f) "abc")
    (is (= expected (pkg/sha256 f)))
    (is (nil? (pkg/verify-sha! f expected)))
    (spit (str f) "abcd")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"SHA-256 mismatch" (pkg/verify-sha! f expected)))))

(deftest help-is-non-mutating
  (let [r (cli "--help")]
    (is (zero? (:exit r)))
    (is (re-find #"--out NEW-DIRECTORY" (:out r)))))

(deftest invalid-target-does-not-create-output
  (let [out (fs/path (temp-dir) "release")
        r (cli "--out" (str out) "--targets" "wrong")]
    (is (= 1 (:exit r)))
    (is (re-find #"Supported targets" (:err r)))
    (is (not (fs/exists? out)))))

(deftest existing-directory-is-not-overwritten
  (let [out (temp-dir)
        sentinel (fs/path out "keep")]
    (spit (str sentinel) "unchanged")
    (let [r (cli "--out" (str out))]
      (is (= 1 (:exit r)))
      (is (= "unchanged" (slurp (str sentinel)))))))

(deftest rejects-incomplete-and-unknown-options
  (doseq [args [["--out"] ["--wat" "x"] ["--targets" "macos-amd64"]]]
    (is (= 1 (:exit (apply cli args))))))

(let [{:keys [fail error]} (run-tests 'test-package-logic-midi)]
  (System/exit (if (zero? (+ fail error)) 0 1)))

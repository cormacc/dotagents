#!/usr/bin/env bb

(require '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[cheshire.core :as json]
         '[clojure.string :as str])

(defn git! [dir & args]
  (let [{:keys [exit out err]} (apply process/shell {:dir dir :out :string :err :string :continue true} "git" args)]
    (when-not (zero? exit)
      (throw (ex-info (str "git " (str/join " " args) ": " (str/trim err)) {})))
    out))

(defn metrics [text]
  {:lines (count (filter #{\newline} text))
   :characters (count text)
   :tokens (quot (+ (count text) 3) 4)})

(defn parse-args [args]
  (loop [args args opts {:ref "HEAD" :files []}]
    (let [[arg value] args]
      (cond
        (nil? arg) opts
        (= "--" arg) (update opts :files into (rest args))
        (#{"--help" "-h"} arg) (assoc opts :help true)
        (= "--ref" arg) (if (and value (not (str/blank? value)))
                          (recur (drop 2 args) (assoc opts :ref value))
                          (throw (ex-info "--ref needs a Git revision" {})))
        (str/starts-with? arg "-") (throw (ex-info (str "unknown argument: " arg) {}))
        :else (recur (rest args) (update opts :files conj arg))))))

(defn compare-file [root commit file]
  (when (or (fs/absolute? file) (some #{".."} (str/split file #"/")))
    (throw (ex-info (str "expected a repository-relative path: " file) {})))
  (let [before (metrics (git! root "show" (str commit ":" file)))
        after (metrics (slurp (str (fs/path root file))))]
    {:path file :before before :after after :delta (merge-with - after before)}))

(defn report [revision files]
  (when (empty? files) (throw (ex-info "provide at least one repository-relative file" {})))
  (let [root (str/trim (git! "." "rev-parse" "--show-toplevel"))
        commit (str/trim (git! root "rev-parse" "--verify" (str revision "^{commit}")))
        rows (mapv #(compare-file root commit %) files)
        total (fn [side] (reduce (partial merge-with +) (metrics "") (map side rows)))
        before (total :before)
        after (total :after)]
    {:ref revision :commit commit :files rows
     :total {:before before :after after :delta (merge-with - after before)}}))

(try
  (let [{:keys [ref files help]} (parse-args *command-line-args*)]
    (if help
      (println "Usage: bb compare-instructions.bb [--ref REV] -- FILE...\nPaths are repository-relative; REV defaults to HEAD. Tokens estimate ceil(UTF-16 characters / 4) per file.")
      (println (json/generate-string (report ref files) {:pretty true}))))
  (catch Exception e
    (binding [*out* *err*] (println "compare-instructions:" (ex-message e)))
    (System/exit 1)))

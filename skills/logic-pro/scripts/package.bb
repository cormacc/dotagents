#!/usr/bin/env bb
(ns package-logic-midi
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.security MessageDigest]))

(def runtime-version "1.13.229")
(def runtime-sha256
  {"macos-aarch64" "2d96ff06ce66883caa873fb717941a58c45523e3db9d91424e9f81b038896331"
   "macos-amd64" "5d7aff8ffa8237f6e12cf879fde98777d9378e7fa78bb943815c3cfba184d78f"
   "linux-amd64-static" "64fb984991c6e68f44e8a9391176a9bb411f9b9ae9959a6bca5307c75a7414da"})
(def skill-dir (fs/parent (fs/parent (fs/absolutize *file*))))

(defn sha256 [file]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (with-open [in (io/input-stream (str file))]
      (let [buf (byte-array 65536)]
        (loop []
          (let [n (.read in buf)]
            (when (pos? n) (.update md buf 0 n) (recur))))))
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest md)))))

(defn verify-sha! [file expected]
  (when-not (= expected (sha256 file))
    (throw (ex-info (str "SHA-256 mismatch: " file) {}))))

(defn- run! [dir & args]
  (apply p/shell {:dir (str dir)} args))

(defn- download! [url dest]
  (p/shell "curl" "--fail" "--location" "--silent" "--show-error"
           "--connect-timeout" "20" "--max-time" "180" "--output" (str dest) url))

(defn build! [out targets]
  (when (or (empty? targets) (not-every? runtime-sha256 targets))
    (throw (ex-info (str "Supported targets: " (str/join "," (sort (keys runtime-sha256)))) {})))
  ;; A fresh directory prevents a failed build from being mistaken for a previous release.
  (fs/create-dir out)
  (let [work (fs/create-dirs (fs/path out ".tmp"))
        src (fs/path skill-dir "scripts" "logic_extract_midi.bb")
        hash (sha256 src)
        revision (str "2+sha256-" (subs hash 0 16))
        cp (fs/create-dirs (fs/path work "src"))
        source (slurp (str src))
        jar (fs/path work "logic-midi.jar")]
    (when-not (= 1 (count (re-seq #"\(def revision \"[^\"]+\"\)" source)))
      (throw (ex-info "Expected one revision definition in the extractor" {})))
    (spit (str (fs/path cp "logic_extract_midi.clj"))
          (str/replace source #"\(def revision \"[^\"]+\"\)" (str "(def revision " (pr-str revision) ")")))
    (spit (str (fs/path work "bb.edn")) "{:paths [\"src\"]}\n")
    (run! work "bb" "uberjar" (str jar) "-m" "logic-extract-midi")
    (let [r (p/shell {:dir (str work) :out :string} "bb" "--jar" (str jar) "--" "--version")]
      (when-not (= (str revision "\n") (:out r))
        (throw (ex-info "Uberjar did not report the stamped revision" {}))))
    (download! (str "https://raw.githubusercontent.com/babashka/babashka/v" runtime-version "/LICENSE")
               (fs/path work "BABASHKA-LICENSE"))
    (doseq [target targets]
      (println "Packaging" target)
      (let [name (str "logic-midi-" target "-" revision)
            archive (str "babashka-" runtime-version "-" target ".tar.gz")
            tarball (fs/path work archive)
            runtime (fs/create-dirs (fs/path work target))
            package (fs/create-dir (fs/path out name))
            binary (fs/path package "logic-midi")
            zip (fs/path out (str name ".zip"))]
        (download! (str "https://github.com/babashka/babashka/releases/download/v" runtime-version "/" archive) tarball)
        (verify-sha! tarball (runtime-sha256 target))
        (run! runtime "tar" "xzf" (str tarball) "bb")
        (with-open [dest (io/output-stream (str binary))]
          (io/copy (io/file (str (fs/path runtime "bb"))) dest)
          (io/copy (io/file (str jar)) dest))
        (run! package "chmod" "755" "logic-midi")
        (doseq [asset ["README.txt" "Extract MIDI.command"]]
          (fs/copy (fs/path skill-dir "assets" asset) (fs/path package asset)))
        (run! package "chmod" "755" "Extract MIDI.command")
        (fs/copy (fs/path work "BABASHKA-LICENSE") (fs/path package "BABASHKA-LICENSE"))
        (fs/copy (fs/path cp "logic_extract_midi.clj") (fs/path package "logic_extract_midi.clj"))
        (spit (str (fs/path package "BUILD.txt"))
              (str "Revision: " revision "\nSource SHA-256 (before revision stamping): " hash
                   "\nBabashka: " runtime-version "\nRuntime archive SHA-256: " (runtime-sha256 target)
                   "\nTarget: " target "\nMac execution and Gatekeeper approval require testing on a Mac.\n"
                   "Babashka source: https://github.com/babashka/babashka/tree/v" runtime-version "\n"))
        (when (= target "linux-amd64-static")
          (let [r (p/shell {:dir (str package) :out :string} (str binary) "--version")]
            (when-not (= (str revision "\n") (:out r))
              (throw (ex-info "Executable did not report the stamped revision" {}))))
          (run! package (str binary) "--help"))
        (run! out "zip" "-qr" (str zip) name)
        (spit (str zip ".sha256") (str (sha256 zip) "  " (fs/file-name zip) "\n"))
        (println "Built" (str zip))))
    (println "Revision:" revision)))

(defn -main [& args]
  (try
    (if (or (empty? args) (= args ["--help"]))
      (println "Usage: bb package.bb --out NEW-DIRECTORY [--targets macos-aarch64,macos-amd64]\nRequires bb, curl, tar, zip, chmod and network access.\nOptional linux-amd64-static target is for native Linux smoke tests.\nOutput directory must not exist. Mac archives are unsigned and need Mac testing.")
      (let [opts (loop [[flag value & more :as xs] args opts {}]
                   (if (empty? xs) opts
                     (do (when-not (and (#{"--out" "--targets"} flag) value (not (str/starts-with? value "--")))
                           (throw (ex-info (str "Invalid packaging arguments near " flag "; use --help") {})))
                         (recur more (assoc opts flag value)))))
            out (get opts "--out")]
        (when-not out (throw (ex-info "--out is required" {})))
        (build! (fs/absolutize out) (str/split (get opts "--targets" "macos-aarch64,macos-amd64") #","))))
    (catch Exception e
      (binding [*out* *err*] (println "Packaging failed:" (ex-message e)))
      (System/exit 1))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))

#!/usr/bin/env bb
;; Tests logic_extract_midi.bb against a synthetic ProjectData file, so that no user project
;; is needed. Run: bb test-logic_extract_midi.bb
(ns test-logic-extract-midi
  (:require [cheshire.core :as json]
            [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.test :refer [deftest is run-tests]]))

(def script (str (fs/path (fs/parent (fs/absolutize *file*)) "logic_extract_midi.bb")))
(load-file script)
(alias 'x 'logic-extract-midi)

(defn- le [n width] (map #(bit-and (bit-shift-right n (* 8 %)) 0xff) (range width)))

(defn- chunk [tag body]
  (concat (reverse (map int tag)) (repeat 24 0) (le (count body) 8) body))

(defn- mseq [name]
  (concat (repeat 16 0) (le (count name) 2) (map int name)))

(defn- rec [& {:keys [b0 b7 at] :or {b0 0 b7 0 at {}}}]
  (reduce (fn [r [i v]] (assoc r i v)) (assoc (vec (repeat 16 0)) 0 b0 7 b7) at))

(defn- note [tick pitch vel len usec]
  (concat (rec :b0 0x90 :at (merge (zipmap (range 4 8) (le (+ 38400 tick) 4)) {1 0x40 11 vel 12 pitch 15 1}))
          (rec :b0 0x80 :b7 0x89 :at (zipmap (range 12 16) (le len 4)))
          (rec :b7 0xa4 :at (zipmap (range 4) (le usec 4)))
          (rec :b7 0xa3)))

(def terminator (rec :b0 0xf1 :at {4 0xff 5 0xff 6 0xff 7 0x3f}))

(defn- project [evsq]
  (byte-array (map unchecked-byte
                   (concat [0x23 0x47 0xc0 0xab] (repeat 20 0)
                           (chunk "MSeq" (mseq "Untitled")) (chunk "EvSq" terminator)
                           (chunk "MSeq" (mseq "Piano")) (chunk "EvSq" evsq)))))

(def good-evsq
  (concat (note 960 60 100 480 500000)
          (note 1920 64 80 960 1000000)
          (rec :b0 0xd0 :at (merge (zipmap (range 4 8) (le (+ 38400 1920) 4)) {11 50}))
          (rec :b7 0xa4)
          terminator))

(def scratch
  (fs/create-dirs
   (fs/path (clojure.string/trim (:out (p/shell {:out :string} "git" "-C" (str (fs/parent script))
                                               "rev-parse" "--show-toplevel")))
            ".tmp" "logic-midi-tests")))
(defn- temp-dir [] (fs/create-temp-dir {:dir scratch :prefix "case-"}))

(defn- write-project [bytes]
  (let [dir (temp-dir)
        f (fs/path dir "song.logicx" "Alternatives" "000" "ProjectData")]
    (fs/create-dirs (fs/parent f))
    (fs/write-bytes f bytes)
    {:dir dir :bundle (str (fs/path dir "song.logicx"))}))

(def test-binary (System/getenv "LOGIC_MIDI_TEST_BINARY"))
(defn- cli [& args]
  (apply p/shell {:out :string :err :string :continue true}
         (concat (if test-binary [test-binary] ["bb" script]) args)))
(defn- run [& args] (apply cli "--bpm" "120" args))

(deftest parses-notes-and-pressure
  (let [[empty piano] (x/sequences (x/chunks (project good-evsq)))]
    (is (= "Untitled" (:name empty)))
    (is (empty? (:notes empty)))
    (is (= "Piano" (:name piano)))
    (is (= [{:pos 39360 :ch 0 :pitch 60 :vel 100 :len 480 :usec 500000}
            {:pos 40320 :ch 0 :pitch 64 :vel 80 :len 960 :usec 1000000}]
           (:notes piano)))
    (is (= [{:pos 40320 :ch 0 :val 50}] (:pressure piano)))))

(deftest writes-expected-midi
  (let [{:keys [dir bundle]} (write-project (project good-evsq))
        out (str (fs/path dir "out.mid"))
        r (run bundle out)
        piano-track [0x00 0xff 0x03 0x05 0x50 0x69 0x61 0x6e 0x6f
                     0x87 0x40 0x90 60 100
                     0x83 0x60 0x80 60 64
                     0x83 0x60 0x90 64 80
                     0x00 0xd0 50
                     0x87 0x40 0x80 64 64
                     0x00 0xff 0x2f 0x00]]
    (is (zero? (:exit r)) (:err r))
    (is (= (concat (map int "MThd") [0 0 0 6 0 1 0 2 0x03 0xc0]
                   (map int "MTrk") [0 0 0 0x13 0x00 0xff 0x51 0x03 0x07 0xa1 0x20
                                     0x00 0xff 0x58 0x04 0x04 0x02 0x18 0x08 0x00 0xff 0x2f 0x00]
                   (map int "MTrk") [0 0 0 (count piano-track)] piano-track)
           (map #(bit-and % 0xff) (fs/read-all-bytes out))))))

(def early-evsq
  ;; Starts 112 ticks before bar 1 and ends 368 ticks after it; then one note wholly before bar 1.
  (concat (note -112 40 40 480 -58333)
          (note -960 41 40 480 -500000)
          terminator))

(deftest reads-negative-time-as-signed
  (let [[_ piano] (x/sequences (x/chunks (project early-evsq)))]
    (is (= [-58333 -500000] (map :usec (:notes piano))))
    (is (< (x/tempo-drift-ms (:notes piano) 500000) 0.01))))

(deftest clamps-notes-before-bar-1
  (let [{:keys [dir bundle]} (write-project (project early-evsq))
        out (str (fs/path dir "out.mid"))
        r (run bundle out)
        piano-track [0x00 0xff 0x03 0x05 0x50 0x69 0x61 0x6e 0x6f
                     0x00 0x90 40 40
                     0x82 0x70 0x80 40 64
                     0x00 0xff 0x2f 0x00]
        bytes (map #(bit-and % 0xff) (fs/read-all-bytes out))]
    (is (zero? (:exit r)) (:err r))
    (is (re-find #"1 notes start before bar 1 and are clamped to tick 0; 1 notes end before bar 1" (:err r)))
    (is (= (concat (map int "MTrk") [0 0 0 (count piano-track)] piano-track)
           (take-last (+ 8 (count piano-track)) bytes)))))

(deftest rejects-tempo-mismatch
  (let [{:keys [bundle]} (write-project (project good-evsq))
        r (run "--bpm" "100" bundle (str (fs/path (temp-dir) "out.mid")))]
    (is (= 1 (:exit r)))
    (is (re-find #"tempo-map drift" (:err r)))))

(deftest rejects-inconsistent-timestamps
  (let [{:keys [bundle]} (write-project (project (concat (note 960 60 100 480 900000) terminator)))
        r (run bundle (str (fs/path (temp-dir) "out.mid")))]
    (is (= 1 (:exit r)))
    (is (re-find #"tempo-map drift" (:err r)))))

(deftest rejects-bad-magic
  (let [{:keys [bundle]} (write-project (byte-array (repeat 64 0)))
        r (run bundle)]
    (is (= 1 (:exit r)))
    (is (re-find #"magic" (:err r)))))

(deftest cli-help-and-revision
  (doseq [args [[] ["--help"] ["-h"]]]
    (let [r (apply cli args)]
      (is (zero? (:exit r)))
      (is (re-find #"Usage: logic-midi" (:out r)))))
  (let [r (cli "--version")]
    (is (zero? (:exit r)))
    (is (= (str (or (System/getenv "LOGIC_MIDI_TEST_REVISION") x/revision) "\n") (:out r)))))

(deftest rejects-invalid-arguments-before-reading-files
  (doseq [args [["--wat"] ["--bpm"] ["--bpm" "0" "missing"]
                ["--bpm" "NaN" "missing"] ["--max-drift-ms" "-1" "missing"]
                ["--alternative" "-1" "missing"] ["--alternative" "1000" "missing"]
                ["--list" "missing" "extra"] ["a" "b" "c"]]]
    (let [r (apply cli args)]
      (is (= 2 (:exit r)) (str args " " (:err r)))
      (is (re-find #"--help" (:err r))))))

(deftest default-output-is-beside-project-and-cannot-be-replaced
  (let [{:keys [dir bundle]} (write-project (project good-evsq))
        out (fs/path dir "song.mid")
        first-run (run bundle)]
    (is (zero? (:exit first-run)) (:err first-run))
    (is (fs/exists? out))
    (let [original (vec (fs/read-all-bytes out))
          second-run (run bundle)]
      (is (= 1 (:exit second-run)))
      (is (re-find #"Output already exists" (:err second-run)))
      (is (= original (vec (fs/read-all-bytes out)))))))

(deftest output-cannot-replace-input-or-follow-a-symlink
  (let [{:keys [dir bundle]} (write-project (project good-evsq))
        input (fs/path bundle "Alternatives" "000" "ProjectData")
        before (vec (fs/read-all-bytes input))
        link (fs/path dir "link.mid")]
    (fs/create-sym-link link input)
    (doseq [output [input link]]
      (let [r (run bundle (str output))]
        (is (= 1 (:exit r)))
        (is (re-find #"Output already exists" (:err r)))
        (is (= before (vec (fs/read-all-bytes input))))))))

(deftest missing-metadata-needs-explicit-tempo
  (let [{:keys [bundle]} (write-project (project good-evsq))
        r (cli bundle)]
    (is (= 1 (:exit r)))
    (is (re-find #"Supply --bpm" (:err r)))))

(deftest does-not-guess-alternative
  (let [{:keys [bundle]} (write-project (project good-evsq))
        second-alt (fs/path bundle "Alternatives" "001")]
    (fs/create-dirs second-alt)
    (fs/write-bytes (fs/path second-alt "ProjectData") (project good-evsq))
    (is (= 2 (:exit (run "--list" bundle))))
    (is (zero? (:exit (run "--list" "--alternative" "1" bundle))))))

(deftest pressure-before-bar-1-has-non-negative-midi-delta
  (doseq [tick [-112 0 112]]
    (let [seqs [{:name "P" :notes [] :pressure [{:pos (+ x/bar1-tick tick) :ch 0 :val 50}]}]
          bytes (map #(bit-and % 0xff) (x/midi-bytes seqs 500000))]
      (is (= [(max 0 tick) 0xd0 50 0 0xff 0x2f 0] (take-last 7 bytes))))))

(deftest clipping-preserves-end-at-boundary
  (doseq [start [-960 -112 0 112]
          end [-1 0 1 368]
          :when (> end start)]
    (let [seqs [{:name "P" :notes [{:pos (+ x/bar1-tick start) :len (- end start)
                                   :ch 0 :pitch 40 :vel 40}]}]
          bytes (map #(bit-and % 0xff) (x/midi-bytes seqs 500000))]
      (is (= (if (pos? end) 1 0) (count (filter #{0x90} bytes)))))))

;; Independent binary fixtures are Python plistlib FMT_BINARY output, stored as base64.
(def metadata-fixtures
  (json/parse-string (slurp (str (fs/path (fs/parent script) "fixtures" "metadata.json")))))
(defn- binary-metadata [key]
  (.decode (java.util.Base64/getDecoder) ^String (get metadata-fixtures key)))
(defn- xml-metadata [tag value]
  (.getBytes (str "<?xml version='1.0'?><!DOCTYPE plist PUBLIC '-//Apple//DTD PLIST 1.0//EN' "
                  "'http://www.apple.com/DTDs/PropertyList-1.0.dtd'><plist version='1.0'><dict>"
                  "<key>BeatsPerMinute</key><" tag ">" value "</" tag "></dict></plist>") "UTF-8"))
(defn- put-metadata! [bundle alternative bytes]
  (fs/write-bytes (fs/path bundle "Alternatives" (format "%03d" alternative) "MetaData.plist") bytes))

(deftest detects-tempo-from-binary-and-xml-metadata
  (doseq [[bytes bpm] [[(binary-metadata "real") 90.5]
                      [(binary-metadata "integer") 75]
                      [(binary-metadata "extended") 90.5]
                      [(xml-metadata "real" "90.5") 90.5]
                      [(xml-metadata "integer" "75") 75]]]
    (let [tempo (Math/round (/ 60e6 bpm))
          {:keys [dir bundle]} (write-project (project (concat (note 960 60 100 480 tempo) terminator)))
          auto (fs/path dir "auto.mid")
          manual (fs/path dir "manual.mid")]
      (put-metadata! bundle 0 bytes)
      (is (== bpm (x/metadata-bpm bundle 0)))
      (let [r (cli bundle (str auto))]
        (is (zero? (:exit r)) (:err r)))
      (let [r (cli "--bpm" (str bpm) bundle (str manual))]
        (is (zero? (:exit r)) (:err r)))
      (is (= (vec (fs/read-all-bytes manual)) (vec (fs/read-all-bytes auto)))))))

(deftest rejects-invalid-metadata-without-writing-output
  (doseq [bytes (concat (map binary-metadata ["missing" "string" "zero" "negative" "nan"])
                       [(byte-array (take 12 (binary-metadata "real")))
                        (byte-array (map unchecked-byte (concat (repeat 8 0) (repeat 32 0))))
                        (xml-metadata "string" "120") (xml-metadata "real" "0")
                        (xml-metadata "real" "NaN") (xml-metadata "real" "-10")
                        (xml-metadata "integer" "90.5")
                        (.getBytes "<plist><dict>" "UTF-8")])]
    (let [{:keys [dir bundle]} (write-project (project good-evsq))
          out (fs/path dir "out.mid")]
      (put-metadata! bundle 0 bytes)
      (let [r (cli bundle (str out))]
        (is (= 1 (:exit r)) (:err r))
        (is (re-find #"Supply --bpm" (:err r)))
        (is (not (fs/exists? out))))
      (is (zero? (:exit (cli "--bpm" "120" bundle (str out))))))))

(deftest detected-tempo-still-runs-drift-check
  (let [{:keys [dir bundle]} (write-project (project good-evsq))
        out (str (fs/path dir "out.mid"))]
    (put-metadata! bundle 0 (xml-metadata "integer" "75"))
    (let [r (cli bundle out)]
      (is (= 1 (:exit r)))
      (is (re-find #"tempo-map drift" (:err r)))
      (is (not (fs/exists? out))))
    (is (zero? (:exit (cli "--bpm" "120" bundle out))))))

(deftest detects-tempo-from-selected-alternative
  (let [{:keys [dir bundle]} (write-project (project good-evsq))
        alt (fs/path bundle "Alternatives" "001")]
    (put-metadata! bundle 0 (xml-metadata "integer" "75"))
    (fs/create-dirs alt)
    (fs/write-bytes (fs/path alt "ProjectData") (project good-evsq))
    (put-metadata! bundle 1 (xml-metadata "integer" "120"))
    (let [r (cli "--alternative" "1" bundle (str (fs/path dir "out.mid")))]
      (is (zero? (:exit r)) (:err r)))))

(deftest xml-metadata-does-not-read-external-entities
  (let [{:keys [dir bundle]} (write-project (project good-evsq))
        entity (fs/path dir "external.txt")]
    (spit (str entity) "120")
    (put-metadata! bundle 0
                   (.getBytes (str "<!DOCTYPE plist [<!ENTITY tempo SYSTEM '" (.toUri entity)
                                   "'>]><plist><dict><key>BeatsPerMinute</key><real>&tempo;</real></dict></plist>")
                              "UTF-8"))
    (let [r (cli bundle (str (fs/path dir "out.mid")))]
      (is (= 1 (:exit r)))
      (is (re-find #"Supply --bpm" (:err r))))))

(let [{:keys [fail error]} (run-tests 'test-logic-extract-midi)]
  (System/exit (if (zero? (+ fail error)) 0 1)))

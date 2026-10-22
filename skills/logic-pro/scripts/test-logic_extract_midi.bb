#!/usr/bin/env bb
;; Tests logic_extract_midi.bb against a synthetic ProjectData file, so that no user project
;; is needed. Run: bb test-logic_extract_midi.bb
(ns test-logic-extract-midi
  (:require [babashka.fs :as fs]
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

(defn- write-project [bytes]
  (let [dir (fs/create-temp-dir)
        f (fs/path dir "song.logicx" "Alternatives" "000" "ProjectData")]
    (fs/create-dirs (fs/parent f))
    (fs/write-bytes f bytes)
    {:dir dir :bundle (str (fs/path dir "song.logicx"))}))

(defn- run [& args] (apply p/shell {:out :string :err :string :continue true} "bb" script args))

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

(deftest rejects-tempo-mismatch
  (let [{:keys [bundle]} (write-project (project good-evsq))
        r (run "--bpm" "100" bundle (str (fs/path (fs/create-temp-dir) "out.mid")))]
    (is (= 1 (:exit r)))
    (is (re-find #"tempo-map drift" (:err r)))))

(deftest rejects-inconsistent-timestamps
  (let [{:keys [bundle]} (write-project (project (concat (note 960 60 100 480 900000) terminator)))
        r (run bundle (str (fs/path (fs/create-temp-dir) "out.mid")))]
    (is (= 1 (:exit r)))
    (is (re-find #"tempo-map drift" (:err r)))))

(deftest rejects-bad-magic
  (let [{:keys [bundle]} (write-project (byte-array (repeat 64 0)))
        r (run bundle)]
    (is (= 1 (:exit r)))
    (is (re-find #"magic" (:err r)))))

(let [{:keys [fail error]} (run-tests 'test-logic-extract-midi)]
  (System/exit (if (zero? (+ fail error)) 0 1)))

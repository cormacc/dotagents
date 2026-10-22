#!/usr/bin/env bb
;; Extract MIDI notes from a Logic Pro project to a Standard MIDI File, or survey its chunks.
;; The ProjectData layout is reverse-engineered; references/projectdata-format.md documents
;; it and the evidence behind each field.
(ns logic-extract-midi
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io ByteArrayOutputStream File)
           (java.nio ByteBuffer ByteOrder)
           (java.nio.file Files)))

(def ppq 960)
(def bar1-tick 38400)
(def magic [0x23 0x47 0xc0 0xab])
(def default-max-drift-ms 1.0)

(defn- u8 [^bytes b i] (bit-and (aget b (int i)) 0xff))
(defn- le-buf ^ByteBuffer [^bytes b] (.order (ByteBuffer/wrap b) ByteOrder/LITTLE_ENDIAN))
(defn- u16 [^bytes b i] (bit-and (.getShort (le-buf b) (int i)) 0xffff))
(defn- u32 [^bytes b i] (bit-and (.getInt (le-buf b) (int i)) 0xffffffff))
(defn- u64 [^bytes b i] (.getLong (le-buf b) (int i)))

(defn chunks
  "Returns the chunks of ProjectData bytes `data` as maps with :tag, :offset and :body."
  [^bytes data]
  (loop [p 0x18 acc []]
    (if (> (+ p 36) (alength data))
      acc
      (let [tag (apply str (map #(char (u8 data %)) (range (+ p 3) (dec p) -1)))
            len (u64 data (+ p 28))
            start (+ p 36)]
        (when (> (+ start len) (alength data))
          (throw (ex-info "chunk body runs past end of file" {:tag tag :offset p :length len})))
        (recur (+ start len)
               (conj acc {:tag tag :offset p :body (java.util.Arrays/copyOfRange data (int start) (int (+ start len)))}))))))

(defn- records [^bytes body]
  (vec (for [i (range 0 (- (alength body) 15) 16)]
         (java.util.Arrays/copyOfRange body (int i) (int (+ i 16))))))

(defn- continuation? [r] (bit-test (u8 r 7) 7))

(defn parse-events
  "Returns {:notes [...] :pressure [...] :types {[status byte7] count}} for an EvSq body.
  Positions are raw Logic ticks; subtract bar1-tick for song ticks."
  [body]
  (let [rs (records body)]
    (reduce
     (fn [acc i]
       (let [r (rs i)
             status (bit-and (u8 r 0) 0xf0)
             acc (update-in acc [:types [(u8 r 0) (u8 r 7)]] (fnil inc 0))]
         (cond
           (continuation? r) acc

           (= status 0x90)
           (let [off (rs (inc i))
                 t (get rs (+ i 2))]
             (when-not (and (= 0x80 (bit-and (u8 off 0) 0xf0)) (= 0x89 (u8 off 7)))
               (throw (ex-info "note-on is not followed by a 0x80/0x89 note-off record"
                               {:record i :next (vec (map #(u8 off %) (range 16)))})))
             (update acc :notes conj
                     {:pos (u32 r 4) :ch (bit-and (u8 r 0) 0x0f) :pitch (u8 r 12) :vel (u8 r 11)
                      :len (u32 off 12)
                      :usec (when (and t (= 0xa4 (u8 t 7))) (u32 t 0))}))

           (= status 0xd0)
           (update acc :pressure conj {:pos (u32 r 4) :ch (bit-and (u8 r 0) 0x0f) :val (u8 r 11)})

           :else acc)))
     {:notes [] :pressure [] :types (sorted-map)}
     (range (count rs)))))

(defn sequences
  "Pairs each EvSq chunk with the name of the MSeq chunk before it."
  [chs]
  (:out (reduce (fn [{:keys [name] :as acc} {:keys [tag body offset]}]
                  (case tag
                    "MSeq" (let [n (u16 body 16)]
                             (assoc acc :name (String. ^bytes body 18 (int n) "UTF-8")))
                    "EvSq" (update acc :out conj (assoc (parse-events body) :name name :offset offset
                                                        :bytes (alength ^bytes body)))
                    acc))
                {:name nil :out []}
                chs)))

(defn- vlq
  "MIDI variable-length quantity: 7 bits per byte, most significant first, continuation bit 0x80."
  [value]
  (loop [n (quot value 128) acc (list (bit-and value 0x7f))]
    (if (zero? n) acc (recur (quot n 128) (cons (bit-or 0x80 (bit-and n 0x7f)) acc)))))

(defn- track-chunk
  "events: [tick order byte-seq]; order breaks ties so that note-offs precede note-ons."
  [events]
  (let [body (ByteArrayOutputStream.)
        put (fn [xs] (doseq [x xs] (.write body (int x))))]
    (reduce (fn [last [tick _ msg]] (put (vlq (- tick last))) (put msg) tick)
            0 (sort-by (juxt first second) events))
    (put [0x00 0xff 0x2f 0x00])
    (let [n (.size body)]
      (concat (map int "MTrk") [(bit-shift-right n 24) (bit-and (bit-shift-right n 16) 0xff)
                                (bit-and (bit-shift-right n 8) 0xff) (bit-and n 0xff)]
              (map #(bit-and % 0xff) (.toByteArray body))))))

(defn tempo-drift-ms
  "Largest difference between Logic's microsecond timestamps and a constant-tempo map."
  [notes tempo-us]
  (/ (reduce max 0 (for [{:keys [pos usec]} notes :when usec]
                     (abs (- usec (/ (* (- pos bar1-tick) tempo-us) ppq)))))
     1000.0))

(defn midi-bytes
  "Returns a type 1 SMF as a byte array: a conductor track, then one track per sequence."
  [seqs tempo-us]
  (let [conductor [[0 0 (concat [0xff 0x51 0x03] [(bit-shift-right tempo-us 16)
                                                  (bit-and (bit-shift-right tempo-us 8) 0xff)
                                                  (bit-and tempo-us 0xff)])]
                   [0 0 [0xff 0x58 0x04 0x04 0x02 0x18 0x08]]]
        tracks (for [{:keys [name notes pressure]} seqs]
                 (let [nb (.getBytes (str name) "UTF-8")]
                   (concat
                    [[0 0 (concat [0xff 0x03] (vlq (alength nb)) (map #(bit-and % 0xff) nb))]]
                    (mapcat (fn [{:keys [pos ch pitch vel len]}]
                              (let [t (- pos bar1-tick)]
                                [[t 2 [(bit-or 0x90 ch) pitch vel]]
                                 [(+ t len) 1 [(bit-or 0x80 ch) pitch 64]]]))
                            notes)
                    (for [{:keys [pos ch val]} pressure]
                      [(- pos bar1-tick) 3 [(bit-or 0xd0 ch) val]]))))
        mtrks (map track-chunk (cons conductor tracks))
        ntrk (count mtrks)
        header (concat (map int "MThd") [0 0 0 6 0 1 (bit-shift-right ntrk 8) (bit-and ntrk 0xff)
                                         (bit-shift-right ppq 8) (bit-and ppq 0xff)])]
    (byte-array (map unchecked-byte (apply concat header mtrks)))))

(defn- project-data-file ^File [path alternative]
  (let [f (io/file path)]
    (if (.isDirectory f) (io/file f "Alternatives" (format "%03d" alternative) "ProjectData") f)))

(defn- usage []
  (str "Usage: logic_extract_midi.bb [--list] [--alternative N] [--bpm BPM] [--max-drift-ms MS]"
       " <project.logicx|ProjectData> [out.mid]"))

(defn- parse-args [args]
  (loop [[a & more :as args] args opts {:alternative 0 :bpm 120.0 :max-drift-ms default-max-drift-ms :pos []}]
    (cond
      (empty? args) opts
      (= a "--list") (recur more (assoc opts :list true))
      (= a "--alternative") (recur (rest more) (assoc opts :alternative (parse-long (first more))))
      (= a "--bpm") (recur (rest more) (assoc opts :bpm (parse-double (first more))))
      (= a "--max-drift-ms") (recur (rest more) (assoc opts :max-drift-ms (parse-double (first more))))
      (str/starts-with? a "--") (throw (ex-info (str "unknown option " a "\n" (usage)) {}))
      :else (recur more (update opts :pos conj a)))))

(defn -main [& args]
  (let [{:keys [pos list alternative bpm max-drift-ms]} (parse-args args)
        [in out] pos
        _ (when-not in (throw (ex-info (usage) {})))
        f (project-data-file in alternative)
        data (Files/readAllBytes (.toPath f))
        _ (when-not (= magic (map #(u8 data %) (range 4)))
            (throw (ex-info (str f " does not start with the Logic ProjectData magic 23 47 c0 ab") {})))
        seqs (sequences (chunks data))
        tempo-us (Math/round (/ 60e6 bpm))]
    (if list
      (do (println "tag counts:" (into (sorted-map) (frequencies (map :tag (chunks data)))))
          (doseq [{:keys [name offset bytes notes pressure types]} seqs]
            (println (format "EvSq @0x%x %d bytes  MSeq %-24s notes %d  pressure %d  types %s"
                             offset bytes (pr-str name) (count notes) (count pressure)
                             (pr-str (into (sorted-map)
                                           (for [[[s b7] c] types :when (not (bit-test b7 7))]
                                             [(format "0x%02x" s) c])))))))
      (let [with-notes (filter (comp seq :notes) seqs)
            out (io/file (or out (str (str/replace (.getName (io/file in)) #"\.logicx$" "") ".mid")))]
        (when (empty? with-notes) (throw (ex-info "no sequence contains notes" {})))
        (doseq [{:keys [name notes pressure]} with-notes]
          (let [drift (tempo-drift-ms notes tempo-us)]
            (println (format "%s: %d notes, %d pressure events, max tempo-map drift %.2f ms"
                             name (count notes) (count pressure) drift))
            (when (> drift max-drift-ms)
              (throw (ex-info (format "tempo-map drift %.2f ms exceeds %.2f ms: the project has a tempo change or a different tempo than %.2f bpm"
                                      drift max-drift-ms bpm) {})))))
        (Files/write (.toPath out) ^bytes (midi-bytes with-notes tempo-us)
                     (make-array java.nio.file.OpenOption 0))
        (println "wrote" (str out))))))

(when (= *file* (System/getProperty "babashka.file"))
  (try (apply -main *command-line-args*)
       (catch clojure.lang.ExceptionInfo e
         (binding [*out* *err*] (println "error:" (ex-message e)))
         (System/exit 1))))

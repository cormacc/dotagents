#!/usr/bin/env bb
;; Extract MIDI notes from a Logic Pro project to a Standard MIDI File, or survey its chunks.
;; The ProjectData layout is reverse-engineered; references/projectdata-format.md documents
;; it and the evidence behind each field.
(ns logic-extract-midi
  (:require [clojure.data.xml :as xml]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io ByteArrayOutputStream File)
           (java.nio ByteBuffer ByteOrder)
           (java.nio.file Files)))

(def revision "2-dev")
(def ppq 960)
(def bar1-tick 38400)
(def magic [0x23 0x47 0xc0 0xab])
(def default-max-drift-ms 1.0)

(defn- u8 [^bytes b i] (bit-and (aget b (int i)) 0xff))
(defn- le-buf ^ByteBuffer [^bytes b] (.order (ByteBuffer/wrap b) ByteOrder/LITTLE_ENDIAN))
(defn- u16 [^bytes b i] (bit-and (.getShort (le-buf b) (int i)) 0xffff))
(defn- u32 [^bytes b i] (bit-and (.getInt (le-buf b) (int i)) 0xffffffff))
(defn- s32 [^bytes b i] (long (.getInt (le-buf b) (int i))))
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
                      ;; Signed: a note before bar 1 has a negative time.
                      :usec (when (and t (= 0xa4 (u8 t 7))) (s32 t 0))}))

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

(defn pre-bar1
  "Counts the notes of `seqs` that start before bar 1, which a MIDI file cannot place before
  tick 0: :clamped notes end after bar 1, :dropped notes end at or before it."
  [seqs]
  (let [early (filter #(< (:pos %) bar1-tick) (mapcat :notes seqs))
        dropped (count (filter #(<= (+ (:pos %) (:len %)) bar1-tick) early))]
    {:clamped (- (count early) dropped) :dropped dropped}))

(defn midi-bytes
  "Returns a type 1 SMF as a byte array: a conductor track, then one track per sequence.
  Song bar 1 is tick 0. A note that starts before bar 1 starts at tick 0 and keeps its end;
  a note that ends at or before bar 1 is dropped. Earlier pressure events move to tick 0."
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
                              (let [end (- (+ pos len) bar1-tick)]
                                (when (pos? end)
                                  [[(max 0 (- pos bar1-tick)) 2 [(bit-or 0x90 ch) pitch vel]]
                                   [end 1 [(bit-or 0x80 ch) pitch 64]]])))
                            notes)
                    (for [{:keys [pos ch val]} pressure]
                      [(max 0 (- pos bar1-tick)) 3 [(bit-or 0xd0 ch) val]]))))
        mtrks (map track-chunk (cons conductor tracks))
        ntrk (count mtrks)
        header (concat (map int "MThd") [0 0 0 6 0 1 (bit-shift-right ntrk 8) (bit-and ntrk 0xff)
                                         (bit-shift-right ppq 8) (bit-and ppq 0xff)])]
    (byte-array (map unchecked-byte (apply concat header mtrks)))))

(defn- project-data-file ^File [path alternative]
  (let [f (io/file path)]
    (if (.isDirectory f) (io/file f "Alternatives" (format "%03d" alternative) "ProjectData") f)))

(defn- usage []
  (str "Logic MIDI Extractor " revision "\n"
       "Usage: logic-midi [options] <project.logicx|ProjectData> [out.mid]\n\n"
       "  -h, --help           Show this help\n"
       "  --version            Show the implementation revision\n"
       "  --list               Survey sequences without writing MIDI\n"
       "  --alternative N      Select alternative N (required if more than one exists)\n"
       "  --bpm BPM            Constant tempo override; otherwise read project metadata\n"
       "  --max-drift-ms MS     Tempo check tolerance (default 1; do not raise to hide changes)\n"
       "  --                   End options (for paths starting with a dash)\n\n"
       "Output defaults to a .mid file beside the project. Existing files are never replaced.\n"
       "Unzip zipped projects first. The source project is read only.\n"
       "Examples:\n  logic-midi \"My Song.logicx\"\n"
       "  logic-midi --bpm 120 \"My Song.logicx\" \"Piano.mid\"\n"
       "  logic-midi --list \"My Song.logicx\"\n\n"
       "Limits: constant tempo, 4/4 output, note-off velocity 64; region playback settings\n"
       "are not applied. Pressure interpretation is unconfirmed. Notes before bar 1 are\n"
       "clamped to tick 0 or dropped if they end at or before bar 1. Other MIDI types\n"
       "are not exported. This reverse-engineered format is not verified for all projects.\n"
       "Exit codes: 0 success, 1 extraction/file error, 2 incorrect arguments."))

(defn- argument-error [message]
  (throw (ex-info (str message "\nRun with --help for examples.") {:exit 2})))

(defn- number-option [flag value]
  (let [n (when value (parse-double value))]
    (when-not (and n (Double/isFinite n) (pos? n))
      (argument-error (str flag " requires a finite positive number")))
    n))

(defn- parse-args [args]
  (loop [[a & more :as args] args opts {:max-drift-ms default-max-drift-ms :pos []}]
    (cond
      (empty? args) opts
      (= a "--") (update opts :pos into more)
      (#{"--help" "-h"} a) (recur more (assoc opts :help true))
      (= a "--version") (recur more (assoc opts :version true))
      (= a "--list") (recur more (assoc opts :list true))
      (= a "--alternative")
      (let [n (some-> (first more) parse-long)]
        (when-not (and n (<= 0 n 999)) (argument-error "--alternative requires an integer from 0 to 999"))
        (recur (rest more) (assoc opts :alternative n)))
      (#{"--bpm" "--max-drift-ms"} a)
      (recur (rest more) (assoc opts (if (= a "--bpm") :bpm :max-drift-ms)
                                (number-option a (first more))))
      (str/starts-with? a "-") (argument-error (str "unknown option " a))
      :else (recur more (update opts :pos conj a)))))

(defn- select-alternative [in explicit]
  (if (or (some? explicit) (not (.isDirectory (io/file in))))
    (or explicit 0)
    (let [alts (sort (for [f (.listFiles (io/file in "Alternatives"))
                          :when (and (re-matches #"[0-9]{3}" (.getName f))
                                     (.isFile (io/file f "ProjectData")))]
                      (parse-long (.getName f))))]
      (when (> (count alts) 1)
        (argument-error (str "More than one alternative exists: " (str/join ", " alts)
                             ". Select one with --alternative N.")))
      (or (first alts) 0))))

(defn- plist-span! [^bytes data p n limit]
  (when-not (and (<= 0 p limit (alength data)) (<= 0 n (- limit p)))
    (throw (ex-info "plist field extends outside its data section" {}))))

(defn- plist-uint [data p width limit]
  (when-not (<= 1 width 8) (throw (ex-info "unsupported plist integer width" {})))
  (plist-span! data p width limit)
  (reduce (fn [n i] (+ (* n 256) (u8 data i))) 0 (range p (+ p width))))

(defn- plist-count [data p limit]
  (let [n (bit-and (u8 data p) 0x0f)]
    (if (< n 15)
      [n (inc p)]
      (let [q (inc p)
            _ (plist-span! data q 1 limit)
            marker (u8 data q)]
        (when-not (and (= 0x10 (bit-and marker 0xf0)) (<= (bit-and marker 15) 3))
          (throw (ex-info "invalid extended plist count" {})))
        (let [width (bit-shift-left 1 (bit-and marker 15))]
          [(plist-uint data (inc q) width limit) (+ q 1 width)])))))

(defn- binary-plist-bpm [^bytes data]
  ;; Only the root dictionary, its keys and the tempo number are decoded. Other metadata
  ;; can contain arrays, dates or data blobs that are unrelated to tempo detection.
  (let [trailer (- (alength data) 32)
        _ (plist-span! data 8 1 trailer)
        offset-width (u8 data (+ trailer 6))
        ref-width (u8 data (+ trailer 7))
        count-objects (plist-uint data (+ trailer 8) 8 (alength data))
        root (plist-uint data (+ trailer 16) 8 (alength data))
        table (plist-uint data (+ trailer 24) 8 (alength data))]
    (when-not (and (<= 1 offset-width 8) (<= 1 ref-width 8)
                   (<= 1 count-objects (alength data)) (< root count-objects)
                   (<= 8 table trailer))
      (throw (ex-info "invalid binary plist trailer" {})))
    (plist-span! data table (* count-objects offset-width) trailer)
    (let [object-offset (fn [ref]
                          (when-not (< -1 ref count-objects)
                            (throw (ex-info "invalid plist object reference" {})))
                          (let [p (plist-uint data (+ table (* ref offset-width)) offset-width trailer)]
                            (when-not (<= 8 p (dec table))
                              (throw (ex-info "invalid plist object offset" {})))
                            p))
          dict (object-offset root)]
      (when-not (= 0xd0 (bit-and (u8 data dict) 0xf0))
        (throw (ex-info "plist root is not a dictionary" {})))
      (let [[n refs] (plist-count data dict table)
            _ (plist-span! data refs (* 2 n ref-width) table)
            matches (for [i (range n)
                          :let [key-pos (object-offset (plist-uint data (+ refs (* i ref-width)) ref-width table))
                                kind (bit-and (u8 data key-pos) 0xf0)
                                _ (when-not (#{0x50 0x60} kind)
                                    (throw (ex-info "plist dictionary key is not a supported string" {})))
                                [chars start] (plist-count data key-pos table)
                                bytes (* chars (if (= kind 0x60) 2 1))
                                _ (plist-span! data start bytes table)
                                key (String. data (int start) (int bytes) (if (= kind 0x60) "UTF-16BE" "US-ASCII"))]
                          :when (= key "BeatsPerMinute")]
                      (object-offset (plist-uint data (+ refs (* (+ n i) ref-width)) ref-width table)))]
        (when-not (= 1 (count matches))
          (throw (ex-info "plist must contain one BeatsPerMinute entry" {})))
        (let [p (first matches)
              marker (u8 data p)
              width (bit-shift-left 1 (bit-and marker 15))]
          (case (bit-and marker 0xf0)
            0x10 (plist-uint data (inc p) width table)
            0x20 (do (when-not (#{4 8} width) (throw (ex-info "invalid plist real width" {})))
                     (plist-span! data (inc p) width table)
                     (let [buf (ByteBuffer/wrap data)]
                       (if (= width 4) (.getFloat buf (int (inc p))) (.getDouble buf (int (inc p))))))
            (throw (ex-info "BeatsPerMinute is not a number" {}))))))))

(defn- xml-plist-bpm [^bytes data]
  (let [root (xml/parse (java.io.ByteArrayInputStream. data)
                        :support-dtd false :supporting-external-entities false)
        children (filter map? (:content root))
        dict (first children)
        fields (filter map? (:content dict))]
    (when-not (and (= :plist (:tag root)) (= 1 (count children)) (= :dict (:tag dict))
                   (even? (count fields)))
      (throw (ex-info "XML plist must contain a root dictionary" {})))
    (let [matches (for [[key value] (partition 2 fields)
                        :when (and (= :key (:tag key)) (= ["BeatsPerMinute"] (:content key)))] value)]
      (when-not (= 1 (count matches))
        (throw (ex-info "plist must contain one BeatsPerMinute entry" {})))
      (let [value (first matches)]
        (when-not (#{:real :integer} (:tag value))
          (throw (ex-info "BeatsPerMinute is not a number" {})))
        (let [text (str/trim (apply str (:content value)))]
          (if (= :integer (:tag value)) (parse-long text) (parse-double text)))))))

(defn metadata-bpm
  "Reads a positive finite BeatsPerMinute from the selected alternative's binary or XML
  plist on any platform. Throws with a --bpm hint if metadata is missing or invalid."
  [project alternative]
  (let [f (io/file project "Alternatives" (format "%03d" alternative) "MetaData.plist")]
    (try
      (let [data (Files/readAllBytes (.toPath f))
            binary? (and (>= (alength data) 8) (= "bplist00" (String. data 0 8 "US-ASCII")))
            bpm (if binary? (binary-plist-bpm data) (xml-plist-bpm data))]
        (when-not (and (number? bpm) (Double/isFinite (double bpm)) (pos? bpm))
          (throw (ex-info "BeatsPerMinute must be a finite positive number" {})))
        bpm)
      (catch Exception e
        (throw (ex-info (str "Cannot read project tempo from " f ": " (ex-message e)
                             ". Supply --bpm BPM to set it explicitly.") {} e))))))

(defn- extract! [{:keys [pos list alternative bpm max-drift-ms]}]
  (let [[in output] pos
        alternative (select-alternative in alternative)
        f (project-data-file in alternative)
        _ (when-not (.isFile f)
            (throw (ex-info (str "Cannot find " f ". Choose an unzipped .logicx project or ProjectData file.") {})))
        data (Files/readAllBytes (.toPath f))
        _ (when-not (and (>= (alength data) 24) (= magic (map #(u8 data %) (range 4))))
            (throw (ex-info (str f " does not start with the Logic ProjectData magic 23 47 c0 ab") {})))
        seqs (sequences (chunks data))]
    (if list
      (do (println "tag counts:" (into (sorted-map) (frequencies (map :tag (chunks data)))))
          (doseq [{:keys [name offset bytes notes pressure types]} seqs]
            (println (format "EvSq @0x%x %d bytes  MSeq %-24s notes %d  pressure %d  types %s"
                             offset bytes (pr-str name) (count notes) (count pressure)
                             (pr-str (into (sorted-map)
                                           (for [[[s b7] c] types :when (not (bit-test b7 7))]
                                             [(format "0x%02x" s) c])))))))
      (let [bpm (or bpm (metadata-bpm in alternative))
            tempo-us (Math/round (/ 60e6 bpm))
            _ (when-not (<= 1 tempo-us 0xffffff)
                (argument-error "--bpm is outside the MIDI tempo range"))
            with-notes (filter (comp seq :notes) seqs)
            input (.getAbsoluteFile (io/file in))
            out (io/file (or output (io/file (.getParentFile input)
                                            (str (str/replace (.getName input) #"\.logicx$" "") ".mid"))))]
        (when (empty? with-notes) (throw (ex-info "no sequence contains notes" {})))
        (doseq [{:keys [name notes types]} with-notes]
          (when (some (fn [[[s b7] _]] (and (not (bit-test b7 7)) (#{0xb0 0xc0 0xe0} (bit-and s 0xf0)))) types)
            (binding [*out* *err*] (println "warning:" name "has controller, program or pitch-bend events that are not exported")))
          (when-not (some :usec notes)
            (throw (ex-info (str name ": no note timestamps; cannot validate tempo") {})))
          (let [drift (tempo-drift-ms notes tempo-us)]
            (println (format "%s: %d notes, max tempo-map drift %.2f ms at %.2f bpm"
                             name (count notes) drift (double bpm)))
            (when (> drift max-drift-ms)
              (throw (ex-info (format "tempo-map drift %.2f ms exceeds %.2f ms: the project has a tempo change or a different tempo than %.2f bpm"
                                      drift max-drift-ms (double bpm)) {})))))
        (let [{:keys [clamped dropped]} (pre-bar1 with-notes)]
          (when (pos? (+ clamped dropped))
            (binding [*out* *err*]
              (println (format "warning: %d notes start before bar 1 and are clamped to tick 0; %d notes end before bar 1 and are dropped"
                               clamped dropped)))))
        (try
          (Files/write (.toPath out) ^bytes (midi-bytes with-notes tempo-us)
                       (into-array java.nio.file.OpenOption [java.nio.file.StandardOpenOption/CREATE_NEW
                                                             java.nio.file.StandardOpenOption/WRITE]))
          (catch java.nio.file.FileAlreadyExistsException _
            (throw (ex-info (str "Output already exists: " out ". Choose another output name; no file was replaced.") {}))))
        (println "wrote" (str out))))))

(defn -main
  "Runs the CLI. Errors go to stderr with exit 1 (extraction) or 2 (arguments)."
  [& args]
  (try
    (let [{:keys [pos help version list] :as opts} (parse-args args)]
      (cond
        help (println (usage))
        version (println revision)
        (empty? args) (println (usage))
        (or (empty? pos) (> (count pos) 2) (and list (> (count pos) 1)))
        (argument-error "Supply one input project and at most one output path (--list takes only an input).")
        :else (extract! opts)))
    (catch Exception e
      (binding [*out* *err*] (println "error:" (ex-message e)))
      (System/exit (or (:exit (ex-data e)) 1)))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))

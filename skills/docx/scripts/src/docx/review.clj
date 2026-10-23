(ns docx.review
  "Review a .docx: header and footer text, tracked changes, version history, and a
  LibreOffice comparison of two versions."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [clojure.string :as str]
            [docx.core :as d :refer [w]]))

(defn tracked-counts
  "Map of part name -> {:ins n :del n} for each body, header or footer part with tracked
  insertions or deletions."
  [entries]
  (into (sorted-map)
        (for [n (d/part-names entries #"word/(document|header\d*|footer\d*)\.xml")
              :let [root (d/parse-entry entries n)
                    ins (count (d/descendants root (w "ins")))
                    del (count (d/descendants root (w "del")))]
              :when (pos? (+ ins del))]
          [n {:ins ins :del del}])))

(defn parts-text
  "Distinct text of the parts that match re, one string per part, paragraphs joined by ' | '."
  [entries re]
  (distinct
   (for [n (d/part-names entries re)
         :let [t (->> (d/descendants (d/parse-entry entries n) (w "p"))
                      (map #(str/trim (d/text %)))
                      (remove str/blank?)
                      (str/join " | "))]
         :when (seq t)]
     t)))

(defn- cell-text [tc] (str/trim (str/join "\n" (map d/text (d/descendants tc (w "p"))))))

(defn- history-header-row?
  "A row with a cell that is exactly 'Version' and a cell that names the DCR, change
  request or change description."
  [cs]
  (let [cs (map #(str/lower-case (str/trim %)) cs)]
    (boolean (and (some #{"version"} cs)
                  (some #(or (str/includes? % "dcr") (str/includes? % "description")) cs)))))

(defn version-history
  "Rows (vectors of cell text, blank rows dropped) of the last table whose first or second
  row is a version history header, or nil."
  [doc]
  (last (for [t (d/descendants doc (w "tbl"))
              :let [trs (->> (d/rows t)
                             (mapv #(mapv cell-text (d/cells %)))
                             (filterv #(some seq %)))]
              :when (and (> (count trs) 1) (some history-header-row? (take 2 trs)))]
          trs)))

(defn inspect
  "Summary of the .docx at path: :headers, :footers, :tracked_changes, :version_history."
  [path]
  (let [entries (d/read-entries path)]
    {:file (str path)
     :headers (parts-text entries #"word/header\d*\.xml")
     :footers (parts-text entries #"word/footer\d*\.xml")
     :tracked_changes (tracked-counts entries)
     :version_history (version-history (d/parse-entry entries "word/document.xml"))}))

;; ------------------------------------------------------------------ changes

(defn- pieces
  "[kind text] pieces of x in document order; kind is :ins, :del or :same. A move counts as
  a deletion and an insertion."
  ([x] (pieces x :same))
  ([x kind]
   (cond
     (d/tag= (w "t") x) [[kind (apply str (:content x))]]
     (d/tag= (w "delText") x) [[:del (apply str (:content x))]]
     (d/tag= (w "tab") x) [[kind "\t"]]
     (d/el? x) (let [k (cond (#{(w "ins") (w "moveTo")} (:tag x)) :ins
                             (#{(w "del") (w "moveFrom")} (:tag x)) :del
                             :else kind)]
                 (if (#{(w "pPr") (w "rPr")} (:tag x)) [] (mapcat #(pieces % k) (:content x))))
     :else [])))

(defn- style-of [p]
  (some-> (first (d/children p (w "pPr"))) (d/children (w "pStyle")) first (get-in [:attrs (w "val")])))

(defn changes
  "One map per body paragraph with tracked changes: :heading (the nearest heading above it,
  by paragraph style), :before (text with deletions, without insertions) and :after."
  [path]
  (let [doc (d/parse-entry (d/read-entries path) "word/document.xml")]
    (loop [[p & more] (d/descendants doc (w "p")) heading nil out []]
      (if-not p
        out
        (let [ps (pieces p)
              txt (fn [ks] (str/trim (apply str (keep (fn [[k s]] (when (ks k) s)) ps))))
              after (txt #{:same :ins})
              heading (if (and (re-find #"(?i)heading|title" (str (style-of p))) (seq after)) after heading)]
          (recur more heading
                 (if (some #(not= :same (first %)) ps)
                   (conj out {:heading heading :before (txt #{:same :del}) :after after})
                   out)))))))

;; ------------------------------------------------------------------ comments

(defn- paragraph-tokens
  "Tokens of p in document order: [:start id], [:end id], [kind text] with kind :same,
  :ins or :del."
  ([x] (paragraph-tokens x :same))
  ([x kind]
   (cond
     (d/tag= (w "commentRangeStart") x) [[:start (get-in x [:attrs (w "id")])]]
     (d/tag= (w "commentRangeEnd") x) [[:end (get-in x [:attrs (w "id")])]]
     (d/tag= (w "t") x) [[kind (apply str (:content x))]]
     (d/tag= (w "delText") x) [[:del (apply str (:content x))]]
     (d/tag= (w "tab") x) [[kind "\t"]]
     (d/el? x) (let [k (cond (#{(w "ins") (w "moveTo")} (:tag x)) :ins
                           (#{(w "del") (w "moveFrom")} (:tag x)) :del
                           :else kind)]
               (if (#{(w "pPr") (w "rPr")} (:tag x)) [] (mapcat #(paragraph-tokens % k) (:content x))))
     :else [])))

(defn comments
  "One map per comment: :id :author :date :text, the nearest :heading above its anchor, and
  the anchored text :before (deletions in, insertions out) and :after."
  [path]
  (let [entries (d/read-entries path)
        croot (d/parse-entry entries "word/comments.xml")
        doc (d/parse-entry entries "word/document.xml")
        ;; Walk paragraphs in order; a range can span paragraphs.
        anchors (loop [[p & more] (d/descendants doc (w "p")) heading nil active {} done {}]
                  (if-not p
                    done
                    (let [toks (paragraph-tokens p)
                          after-text (str/trim (apply str (keep (fn [[k s]] (when (#{:same :ins} k) s)) toks)))
                          heading (if (and (re-find #"(?i)heading|title" (str (style-of p))) (seq after-text)) after-text heading)
                          [active done] (reduce (fn [[a dn] [k v]]
                                                  (case k
                                                    :start [(assoc a v {:heading heading :before "" :after ""}) dn]
                                                    :end [(dissoc a v) (if-let [x (get a v)] (assoc dn v x) dn)]
                                                    [(update-vals a #(cond-> %
                                                                       (#{:same :del} k) (update :before str v)
                                                                       (#{:same :ins} k) (update :after str v)))
                                                     dn]))
                                                [active done] toks)
                          active (update-vals active #(-> % (update :before str "\n") (update :after str "\n")))]
                      (recur more heading active done))))]
    (vec (for [c (when croot (d/children croot (w "comment")))
               :let [id (get-in c [:attrs (w "id")])
                     a (get anchors id)]]
           {:id id
            :author (get-in c [:attrs (w "author")])
            :date (get-in c [:attrs (w "date")])
            :text (str/join "\n\n" (map #(str/trim (d/text %)) (d/children c (w "p"))))
            :heading (:heading a)
            :before (some-> (:before a) str/trim)
            :after (some-> (:after a) str/trim)}))))

;; ------------------------------------------------------------------ compare

(defn lo-python
  "[python env] for a python3 that can import uno: the system python3 if it can, else the
  python that LibreOffice's libpyuno.so links against (the Nix layout). nil when neither."
  []
  (if (and (fs/which "python3")
           (zero? (:exit (p/shell {:continue true :out :string :err :string} "python3" "-c" "import uno"))))
    ["python3" {}]
    (when-let [soffice (some-> (fs/which "soffice") fs/real-path str)]
      (let [head (let [s (slurp soffice)] (subs s 0 (min 65536 (count s))))
            dirs (cons (str (fs/parent soffice))
                       (map #(str % "/lib/libreoffice/program")
                            (re-seq #"/nix/store/[a-z0-9]+-libreoffice-[^/\s\"']+" head)))]
        ;; Use the directory that really holds libpyuno.so: a Nix wrapper directory has
        ;; symlinks to it, and UNO bootstrap from the wrapper directory fails.
        (some (fn [dir]
                (let [link (str dir "/libpyuno.so")]
                  (when (fs/exists? link)
                    (let [lib (str (fs/real-path link))
                          prog (str (fs/parent lib))
                          ldd (:out (p/shell {:out :string :continue true} "ldd" lib))
                          [_ root] (re-find #"=> (\S+)/lib/libpython3" (str ldd))
                          py (str root "/bin/python3")]
                      (when (and root (fs/exists? py))
                        [py {"PYTHONPATH" prog
                             "URE_BOOTSTRAP" (str "vnd.sun.star.pathname:" prog "/fundamentalrc")}])))))
              dirs)))))

(def ^:private lo-helper
  (str (fs/path (fs/parent (fs/parent (fs/parent (fs/absolutize *file*)))) "lo_compare.py")))

(defn compare-docx
  "Write to out a .docx that shows old -> new as tracked changes, with LibreOffice Compare
  Document. Throws ex-info when LibreOffice or its Python is missing or the run fails."
  [old new out]
  (let [[py env] (or (lo-python) (throw (ex-info "no python with LibreOffice UNO found" {})))
        r (p/shell {:continue true :extra-env env} py lo-helper (str old) (str new) (str out))]
    (when-not (and (zero? (:exit r)) (fs/exists? out))
      (throw (ex-info (str "LibreOffice compare failed (exit " (:exit r) ")") {})))
    (str out)))

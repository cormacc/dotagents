(ns docx.core
  "Read, edit and write Word .docx packages as WordprocessingML trees.

  A .docx is a zip of XML parts. clojure.data.xml keeps each namespace prefix on emit, so
  attributes that name prefixes (mc:Ignorable) stay valid and Word opens the result.
  Elements are clojure.data.xml elements: maps with :tag, :attrs and :content.

  Lookup functions that cannot find a required part of a template throw ex-info with
  {:docx/error :layout}, so a caller can tell a template change from bad input."
  (:require [clojure.data.xml :as xml]
            [clojure.string :as str])
  (:import [java.io FileOutputStream]
           [java.util.zip ZipEntry ZipFile ZipOutputStream]))

(def W "http://schemas.openxmlformats.org/wordprocessingml/2006/main")
(def W14 "http://schemas.microsoft.com/office/word/2010/wordml")
(defn w "Qualified tag or attribute name in the main WordprocessingML namespace." [n] (xml/qname W n))
(defn w14 "Qualified name in the Word 2010 namespace (checkbox content controls)." [n] (xml/qname W14 n))
(def xml-space (xml/qname "http://www.w3.org/XML/1998/namespace" "space"))
(def unchecked "\u2610")
(def checked "\u2612")

(defn layout-error
  "ex-info for a template element that is not where the caller expects it."
  [msg]
  (ex-info msg {:docx/error :layout}))

;; ------------------------------------------------------------------ package

(defn read-entries
  "Ordered [name bytes] pairs of the package at path."
  [path]
  (with-open [zf (ZipFile. (str path))]
    (mapv (fn [e] [(.getName e) (.readAllBytes (.getInputStream zf e))])
          (enumeration-seq (.entries zf)))))

(defn entry-str
  "Text of the named entry, or nil. A UTF-8 byte-order mark is removed: Word writes one in
  some parts, and the XML parser rejects it."
  [entries name]
  (some (fn [[n b]] (when (= n name) (str/replace-first (String. ^bytes b "UTF-8") #"^\uFEFF" "")))
        entries))

(defn parse-entry
  "Parsed element of the named entry, or nil."
  [entries name]
  (some-> (entry-str entries name) xml/parse-str))

(defn part-names
  "Entry names that match re, in package order."
  [entries re]
  (filterv #(re-matches re %) (map first entries)))

(defn replace-entries
  "entries with each name in updates (name -> XML string) replaced."
  [entries updates]
  (mapv (fn [[n b]] [n (if-let [s (get updates n)] (.getBytes ^String s "UTF-8") b)]) entries))

(defn write-docx
  "Write entries to path as a zip, in order."
  [path entries]
  (with-open [out (ZipOutputStream. (FileOutputStream. (str path)))]
    (doseq [[n ^bytes b] entries]
      (.putNextEntry out (ZipEntry. ^String n))
      (.write out b)
      (.closeEntry out))))

;; ------------------------------------------------------------------ elements

(defn el? [x] (and (map? x) (contains? x :tag)))
(defn tag= [t x] (and (el? x) (= (:tag x) t)))
(defn children "Direct children of x tagged t." [x t] (filter #(tag= t %) (:content x)))
(defn descendants "x and its descendants tagged t, in document order." [x t] (filter #(tag= t %) (tree-seq el? :content x)))

(defn text
  "Text of the w:t descendants of x. w:delText is excluded, so a document with tracked
  changes reads as if all changes were accepted. python-docx cell.text differs: it skips
  runs inside w:ins."
  [x]
  (apply str (mapcat :content (descendants x (w "t")))))

(defn xform
  "Apply f to every element of x, children first, in document order."
  [x f]
  (if (el? x)
    (f (assoc x :content (mapv #(xform % f) (:content x))))
    x))

(defn paras [tc] (vec (children tc (w "p"))))
(defn rows [tbl] (vec (children tbl (w "tr"))))
(defn cells [tr] (vec (children tr (w "tc"))))

(defn with-kids
  "x with its children tagged t replaced by kids. Other children (properties) stay first,
  which is where WordprocessingML puts them."
  [x t kids]
  (assoc x :content (vec (concat (remove #(tag= t %) (:content x)) kids))))

;; ------------------------------------------------------------------ text editing

(defn text-rpr
  "Run properties of the first run in p that holds text. With :plain true, bold and italic
  are removed."
  [p & {:keys [plain]}]
  (let [r (first (filter #(seq (children % (w "t"))) (descendants p (w "r"))))
        rpr (first (children r (w "rPr")))]
    (cond-> rpr
      (and rpr plain)
      (assoc :content (vec (remove #(some (fn [t] (tag= (w t) %)) ["b" "bCs" "i" "iCs"])
                                   (:content rpr)))))))

(defn run
  "A w:r with run properties rpr (or none) and text s. A newline in s becomes w:br."
  [rpr s]
  (apply xml/element (w "r") {}
         (concat (when rpr [rpr])
                 (interpose (xml/element (w "br"))
                            (map #(xml/element (w "t") {xml-space "preserve"} %)
                                 (str/split s #"\n" -1))))))

(defn set-par-text
  "p with its paragraph properties and one run of s, formatted like p's first text run."
  [p s & {:keys [plain]}]
  (assoc p :content (vec (concat (children p (w "pPr")) [(run (text-rpr p :plain plain) s)]))))

(defn set-cell-text
  "tc holding s, one paragraph for each blank-line-separated block, formatted like the
  cell's first paragraph."
  [tc s]
  (let [p0 (first (paras tc))]
    (with-kids tc (w "p") (mapv #(set-par-text p0 %) (str/split (str s) #"\n\n")))))

(defn label+value
  "tc with its first `keep` paragraphs (default 1), then s as plain paragraphs formatted
  like the first paragraph. Use it for a label cell whose hint text the value replaces."
  [tc s & {:keys [keep] :or {keep 1}}]
  (let [ps (paras tc)
        label (first ps)]
    (with-kids tc (w "p") (concat (take keep ps)
                                  (map #(set-par-text label % :plain true)
                                       (str/split (str s) #"\n\n"))))))

(defn append-run
  "p with s appended as a plain run, after one space unless p ends in whitespace."
  [p s]
  (let [sep (if (re-find #"\s$" (text p)) "" " ")]
    (update p :content #(conj (vec %) (run (text-rpr p :plain true) (str sep s))))))

(defn update-para
  "tc with f applied to its first paragraph whose trimmed text starts with prefix.
  Throws a layout error when there is none."
  [tc prefix f]
  (let [hit (atom false)
        tc' (assoc tc :content (mapv #(if (and (tag= (w "p") %) (not @hit)
                                                (str/starts-with? (str/trim (text %)) prefix))
                                         (do (reset! hit true) (f %))
                                         %)
                                      (:content tc)))]
    (when-not @hit (throw (layout-error (str "paragraph not found: '" prefix "'"))))
    tc'))

;; ------------------------------------------------------------------ checkboxes

(defn checkbox?
  "True for a w:sdt that holds a Word 2010 checkbox."
  [x]
  (boolean (and (tag= (w "sdt") x) (seq (descendants x (w14 "checkbox"))))))

(defn n-checkboxes [x] (count (filter checkbox? (tree-seq el? :content x))))

(defn set-checkbox
  "sdt with its state and its displayed glyph set to on."
  [sdt on]
  (xform sdt (fn [e]
               (cond
                 (tag= (w14 "checked") e) (assoc-in e [:attrs (w14 "val")] (if on "1" "0"))
                 (and (tag= (w "t") e) (#{unchecked checked} (first (:content e))))
                 (assoc e :content [(if on checked unchecked)])
                 :else e))))

(defn tick
  "x with each checkbox set on when (on? index) is true; indices follow document order.
  With :expect n, throws a layout error unless x holds exactly n checkboxes."
  [x on? & {:keys [expect]}]
  (when (and expect (not= expect (n-checkboxes x)))
    (throw (layout-error (str "expected " expect " checkboxes, found " (n-checkboxes x)))))
  (let [i (atom -1)]
    (xform x (fn [e] (if (checkbox? e) (set-checkbox e (on? (swap! i inc))) e)))))

;; ------------------------------------------------------------------ tables

(defn heading "Trimmed text of a table's first row." [tbl] (str/trim (text (first (rows tbl)))))

(defn row-idx
  "Index of the first row in rs whose trimmed text starts with prefix. Throws a layout
  error when there is none."
  [rs prefix]
  (or (first (keep-indexed (fn [i r] (when (str/starts-with? (str/trim (text r)) prefix) i)) rs))
      (throw (layout-error (str "row not found: '" prefix "'")))))

(defn update-row "tbl with f applied to its first row that starts with prefix." [tbl prefix f]
  (let [rs (rows tbl) i (row-idx rs prefix)]
    (with-kids tbl (w "tr") (assoc rs i (f (rs i))))))

(defn update-cell "tr with f applied to its cell i." [tr i f]
  (with-kids tr (w "tc") (update (cells tr) i f)))

(defn update-table
  "body with f applied to its first top-level table whose heading starts with prefix.
  Throws a layout error when there is none."
  [body prefix f]
  (let [hit (atom false)
        body' (assoc body :content
                     (mapv #(if (and (not @hit) (tag= (w "tbl") %) (str/starts-with? (heading %) prefix))
                              (do (reset! hit true) (f %))
                              %)
                           (:content body)))]
    (when-not @hit (throw (layout-error (str "table not found: '" prefix "'"))))
    body'))

;; ------------------------------------------------------------------ footnotes

(defn prune-footnotes
  "footnotes.xml text without the footnotes that no reference in doc-xml uses. Separator
  footnotes (with w:type) stay. LibreOffice otherwise shows orphan footnote text against
  the remaining references."
  [footnotes-xml doc-xml]
  (let [used (set (map #(get-in % [:attrs (w "id")])
                       (descendants (xml/parse-str doc-xml) (w "footnoteReference"))))
        fn-el (xml/parse-str footnotes-xml)]
    (xml/emit-str
     (assoc fn-el :content
            (vec (remove #(and (tag= (w "footnote") %)
                               (nil? (get-in % [:attrs (w "type")]))
                               (not (used (get-in % [:attrs (w "id")]))))
                         (:content fn-el)))))))

(ns docx.clean
  "Clean copy of a reviewed document: accept every tracked change and remove every comment.

  Accepting covers insertions and deletions (run, paragraph-mark, table-row and cell
  level), moves, and property changes (the current properties stay). A deleted paragraph
  mark merges the paragraph's remaining content into the next paragraph, which keeps its
  own properties. Comments go with their anchors, references and parts. trackRevisions is
  removed from settings unless keep-tracking?."
  (:require [clojure.data.xml :as xml]
            [clojure.string :as str]
            [docx.core :as d :refer [w]]))

(def ^:private drop-tags
  "Elements removed with their content when changes are accepted and comments removed."
  (set (map w ["del" "moveFrom" "moveFromRangeStart" "moveFromRangeEnd" "moveToRangeStart" "moveToRangeEnd"
               "rPrChange" "pPrChange" "sectPrChange" "tblPrChange" "tblGridChange" "tcPrChange" "trPrChange"
               "tblPrExChange" "numberingChange" "cellIns" "cellMerge"
               "commentRangeStart" "commentRangeEnd" "commentReference"])))

(def ^:private unwrap-tags
  "Elements replaced by their content."
  (set (map w ["ins" "moveTo"])))

(defn- mark-tag
  "The paragraph-mark revision of p: :ins, :del or nil (w:pPr/w:rPr/w:ins|w:del)."
  [p]
  (let [rpr (some-> (first (d/children p (w "pPr"))) (d/children (w "rPr")) first)]
    (cond (some #(d/tag= (w "del") %) (:content rpr)) :del
          (some #(d/tag= (w "ins") %) (:content rpr)) :ins)))

(defn- row-deleted? [tr]
  (some #(d/tag= (w "del") %) (some-> (first (d/children tr (w "trPr"))) :content)))

(defn- cell-deleted? [tc]
  (some #(d/tag= (w "cellDel") %) (some-> (first (d/children tc (w "tcPr"))) :content)))

(defn- empty-run? [x]
  (and (d/tag= (w "r") x) (every? #(d/tag= (w "rPr") %) (filter d/el? (:content x)))))

(defn- clean-node
  "Accept changes and remove comment markup in x. Returns a sequence of nodes (an element
  can disappear or be replaced by its children)."
  [x]
  (cond
    (not (d/el? x)) [x]
    (drop-tags (:tag x)) []
    (and (d/tag= (w "tr") x) (row-deleted? x)) []
    (and (d/tag= (w "tc") x) (cell-deleted? x)) []
    (unwrap-tags (:tag x)) (mapcat clean-node (:content x))
    :else (let [x' (assoc x :content (vec (mapcat clean-node (:content x))))]
            ;; Drop a run that held only a comment reference; other empty runs stay as they are.
            (if (and (some #(d/tag= (w "commentReference") %) (:content x)) (empty-run? x')) [] [x']))))

(defn- merge-deleted-marks
  "In a sequence of sibling nodes, merge each paragraph whose mark was deleted into the
  following paragraph. Called before clean-node, while the marks are still visible."
  [nodes]
  (loop [[n & more] nodes acc []]
    (cond
      (nil? n) acc
      (and (d/tag= (w "p") n) (= :del (mark-tag n)) (d/tag= (w "p") (first more)))
      (let [nxt (first more)
            carried (remove #(d/tag= (w "pPr") %) (:content n))
            merged (assoc nxt :content (vec (concat (d/children nxt (w "pPr")) carried
                                                    (remove #(d/tag= (w "pPr") %) (:content nxt)))))]
        (recur (cons merged (rest more)) acc))
      :else (recur more (conj acc n)))))

(defn- merge-pass [x]
  (if (d/el? x)
    (assoc x :content (vec (merge-deleted-marks (map merge-pass (:content x)))))
    x))

(defn clean-xml
  "Part XML text with all tracked changes accepted and comment markup removed."
  [part-xml]
  (let [root (xml/parse-str part-xml)]
    (xml/emit-str (first (clean-node (merge-pass root))))))

(def ^:private comment-parts
  #{"word/comments.xml" "word/commentsExtended.xml" "word/commentsIds.xml" "word/commentsExtensible.xml"})

(defn clean-entries
  "Package entries with changes accepted in every story part (document, headers, footers,
  footnotes, endnotes), comment parts and their relationships and content types removed,
  and trackRevisions removed unless keep-tracking?."
  [entries & {:keys [keep-tracking?]}]
  (let [stories (d/part-names entries #"word/(document|header\d*|footer\d*|footnotes|endnotes)\.xml")
        rels-name "word/_rels/document.xml.rels"
        rels (d/entry-str entries rels-name)
        rels' (when rels (str/replace rels #"<Relationship [^>]*Target=\"comments[A-Za-z]*\.xml\"[^>]*/>" ""))
        ct (d/entry-str entries "[Content_Types].xml")
        ct' (str/replace ct #"<Override [^>]*PartName=\"/word/comments[A-Za-z]*\.xml\"[^>]*/>" "")
        settings (d/entry-str entries "word/settings.xml")
        settings' (when (and settings (not keep-tracking?)) (str/replace settings #"<w:trackRevisions\s*/>" ""))
        updates (cond-> (into {} (for [n stories] [n (clean-xml (d/entry-str entries n))]))
                  rels' (assoc rels-name rels')
                  true (assoc "[Content_Types].xml" ct')
                  settings' (assoc "word/settings.xml" settings'))]
    (->> (d/replace-entries entries updates)
         (remove #(comment-parts (first %)))
         vec)))

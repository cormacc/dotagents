(ns docx.edit
  "Tracked editing of WordprocessingML: tracked replace, paragraph insertion and deletion,
  comments, and the trackRevisions setting.

  Functions on a paragraph are pure: they take and return w:p elements. Package helpers
  take and return the entries of docx.core/read-entries. Matching problems throw ex-info
  with {:docx/error :match}; a missing package structure throws {:docx/error :layout}.

  Word Online displays hand-written w:ins/w:del and a comments.xml with w:annotationRef
  correctly, without w14:paraId or commentsExtended parts (measured 2026-10-23)."
  (:require [clojure.data.xml :as xml]
            [clojure.string :as str]
            [docx.core :as d :refer [w]]))

(defn match-error [msg & [data]] (ex-info msg (merge {:docx/error :match} data)))

(def ^:private w-ns (namespace (w "p")))
(defn- w-el? [x] (and (d/el? x) (= w-ns (namespace (:tag x)))))
(defn- local [x] (name (:tag x)))

(defn- el [tag attrs & content]
  (apply xml/element (w tag) (into {} (for [[k v] attrs] [(w k) (str v)])) content))
(defn- t-node [s] (xml/element (w "t") {d/xml-space "preserve"} s))
(defn- del-text-node [s] (xml/element (w "delText") {d/xml-space "preserve"} s))
(defn- rpr-of [r] (first (d/children r (w "rPr"))))
(defn- run-of [rpr & content] (apply xml/element (w "r") {} (concat (when rpr [rpr]) content)))

;; ------------------------------------------------------------------ ids

(defn max-id
  "Largest numeric w:id in the tree, or -1. New revision and comment ids go above it."
  [root]
  (reduce max -1 (keep #(some-> (get-in % [:attrs (w "id")]) str parse-long)
                       (filter d/el? (tree-seq d/el? :content root)))))

(defn id-source
  "A function that returns a new unique id (a string) on each call, above start."
  [start]
  (let [n (atom start)] (fn [] (str (swap! n inc)))))

;; ------------------------------------------------------------------ settings

(def ^:private before-track-revisions
  "Children of w:settings that the schema (CT_Settings) places before w:trackRevisions."
  #{"writeProtection" "view" "zoom" "removePersonalInformation" "removeDateAndTime"
    "doNotDisplayPageBoundaries" "displayBackgroundShape" "printPostScriptOverText"
    "printFractionalCharacterWidth" "printFormsData" "embedTrueTypeFonts" "embedSystemFonts"
    "saveSubsetFonts" "saveFormsData" "mirrorMargins" "alignBordersAndEdges"
    "bordersDoNotSurroundHeader" "bordersDoNotSurroundFooter" "gutterAtTop" "hideSpellingErrors"
    "hideGrammaticalErrors" "activeWritingStyle" "proofState" "formsDesign" "attachedTemplate"
    "linkStyles" "stylePaneFormatFilter" "stylePaneSortMethod" "documentType" "mailMerge"
    "revisionView"})

(defn enable-tracking
  "settings.xml text with w:trackRevisions in its schema position: Word rejects settings
  children out of order. Unchanged when tracking is already on."
  [settings-xml]
  (let [root (xml/parse-str settings-xml)
        kids (:content root)]
    (if (some #(d/tag= (w "trackRevisions") %) kids)
      settings-xml
      (let [last-before (last (keep-indexed (fn [i k] (when (and (w-el? k) (before-track-revisions (local k))) i)) kids))
            at (if last-before (inc last-before) 0)]
        (xml/emit-str (assoc root :content (vec (concat (take at kids) [(el "trackRevisions" {})] (drop at kids)))))))))

;; ------------------------------------------------------------------ paragraphs

(defn- plain-run?
  "A direct w:r child whose content (besides w:rPr) is only w:t: its text can be split."
  [x]
  (and (d/tag= (w "r") x)
       (let [c (remove #(d/tag= (w "rPr") %) (filter d/el? (:content x)))]
         (and (seq c) (every? #(d/tag= (w "t") %) c)))))

(defn- run-text [r] (apply str (mapcat :content (d/children r (w "t")))))

(defn- groups
  "Split paragraph children into [:plain [runs]] and [:other node] groups, in order."
  [kids]
  (reduce (fn [acc k]
            (if (plain-run? k)
              (if (= :plain (first (peek acc)))
                (conj (pop acc) [:plain (conj (second (peek acc)) k)])
                (conj acc [:plain [k]]))
              (conj acc [:other k])))
          [] kids))

(defn- occurrences [^String s ^String sub]
  (loop [from 0 acc []]
    (let [i (.indexOf s sub (int from))]
      (if (neg? i) acc (recur (inc i) (conj acc i))))))

(defn- split-runs
  "Pieces of a run sequence between character offsets: [:before|:in|:after rPr text]."
  [runs start end]
  (loop [[r & more] runs pos 0 acc []]
    (if-not r
      acc
      (let [s (run-text r) rpr (rpr-of r) a pos b (+ pos (count s))
            part (fn [kind from to] (when (< from to) [kind rpr (subs s (- from a) (- to a))]))]
        (recur more b (into acc (remove nil? [(part :before a (min b (max a start)))
                                              (part :in (max a start) (min b end))
                                              (part :after (max a end) b)])))))))

(defn tracked-replace
  "Paragraph p with the only occurrence of old replaced by new as a tracked change: w:del
  around the matched runs (each keeps its run properties) and w:ins with the run properties
  of the first matched run. The match may span plain runs with different formatting, but
  not fields, existing tracked changes or other non-text content. With :comment-id, the
  change is anchored to that comment. opts: :author :date :ids (an id-source) :comment-id."
  [p old new {:keys [author date ids comment-id]}]
  (when (str/blank? old) (throw (match-error "the text to replace is empty")))
  (let [gs (groups (:content p))
        hits (for [[gi [kind runs]] (map-indexed vector gs) :when (= :plain kind)
                   i (occurrences (apply str (map run-text runs)) old)]
               [gi i])
        all-text (d/text p)]
    (when (empty? hits)
      (throw (match-error (str "text not found as plain runs: " (pr-str old)
                               (when (str/includes? all-text old) " (it spans a field, a tracked change or other non-text content)"))
                          {:old old})))
    (when (> (count hits) 1)
      (throw (match-error (str "text occurs " (count hits) " times in the paragraph: " (pr-str old)) {:old old})))
    (let [[gi start] (first hits)
          runs (second (nth gs gi))
          pieces (split-runs runs start (+ start (count old)))
          ;; The first matched run's properties, also when it has none (nil).
          ins-rpr (second (first (filter #(= :in (first %)) pieces)))
          mk (fn [kind]
               (for [[k rpr s] pieces :when (= k kind)] (run-of rpr (t-node s))))
          change (concat
                  (when comment-id [(el "commentRangeStart" {"id" comment-id})])
                  [(apply el "del" {"id" (ids) "author" author "date" date}
                          (for [[k rpr s] pieces :when (= k :in)] (run-of rpr (del-text-node s))))]
                  (when (seq new) [(el "ins" {"id" (ids) "author" author "date" date} (run-of ins-rpr (t-node new)))])
                  (when comment-id [(el "commentRangeEnd" {"id" comment-id})
                                    (run-of nil (el "commentReference" {"id" comment-id}))]))
          replaced (concat (mk :before) change (mk :after))]
      (assoc p :content (vec (mapcat (fn [i [kind x]] (cond (= i gi) replaced (= :plain kind) x :else [x]))
                                     (range) gs))))))

(defn- mark-paragraph
  "p with its paragraph mark recorded as inserted or deleted (w:pPr/w:rPr/w:ins|w:del)."
  [p kind {:keys [author date ids]}]
  (let [ppr (or (first (d/children p (w "pPr"))) (xml/element (w "pPr")))
        rpr (or (first (d/children ppr (w "rPr"))) (xml/element (w "rPr")))
        rpr' (assoc rpr :content (vec (concat (remove #(d/tag= (w kind) %) (:content rpr))
                                              [(el kind {"id" (ids) "author" author "date" date})])))
        ;; CT_PPr order: the pPrBase elements, then rPr, then sectPr and pPrChange.
        tail? #(or (d/tag= (w "sectPr") %) (d/tag= (w "pPrChange") %))
        others (remove #(d/tag= (w "rPr") %) (:content ppr))
        ppr' (assoc ppr :content (vec (concat (remove tail? others) [rpr'] (filter tail? others))))]
    (assoc p :content (vec (cons ppr' (remove #(d/tag= (w "pPr") %) (:content p)))))))

(defn tracked-insert-after
  "[anchor new-p]: a new paragraph with text, after anchor, recorded as inserted. The new
  paragraph copies the anchor's paragraph properties (or uses style), and the run
  properties of the anchor's first text run unless plain?. Its paragraph mark is recorded
  as inserted, so rejecting the change removes the paragraph completely.
  opts: :author :date :ids :style :plain? :comment-id."
  [anchor text {:keys [author date ids style plain? comment-id] :as opts}]
  (let [ppr (cond style (el "pPr" {} (el "pStyle" {"val" style}))
                  :else (first (d/children anchor (w "pPr"))))
        ;; Drop the mark's run properties, and a section break or property change that
        ;; belongs to the anchor only.
        ppr (when ppr (update ppr :content (fn [c] (vec (remove #(or (d/tag= (w "rPr") %) (d/tag= (w "sectPr") %)
                                                                      (d/tag= (w "pPrChange") %))
                                                                 c)))))
        rpr (when-not plain? (d/text-rpr anchor))
        body (concat (when comment-id [(el "commentRangeStart" {"id" comment-id})])
                     [(el "ins" {"id" (ids) "author" author "date" date} (run-of rpr (t-node text)))]
                     (when comment-id [(el "commentRangeEnd" {"id" comment-id})
                                       (run-of nil (el "commentReference" {"id" comment-id}))]))
        p (apply xml/element (w "p") {} (concat (when ppr [ppr]) body))]
    [anchor (mark-paragraph p "ins" opts)]))

(defn tracked-delete
  "Paragraph p with all its text runs and its paragraph mark recorded as deleted, so that
  accepting removes the paragraph and rejecting keeps it. Existing tracked changes and
  non-run content stay as they are. opts: :author :date :ids :comment-id."
  [p {:keys [author date ids comment-id] :as opts}]
  (let [del-run (fn [r] (assoc r :content (mapv #(if (d/tag= (w "t") %) (del-text-node (apply str (:content %))) %)
                                                (:content r))))
        kids (remove #(d/tag= (w "pPr") %) (:content p))
        wrapped (mapcat (fn [k] (if (d/tag= (w "r") k)
                                  [(el "del" {"id" (ids) "author" author "date" date} (del-run k))]
                                  [k]))
                        kids)
        body (concat (when comment-id [(el "commentRangeStart" {"id" comment-id})])
                     wrapped
                     (when comment-id [(el "commentRangeEnd" {"id" comment-id})
                                       (run-of nil (el "commentReference" {"id" comment-id}))]))]
    (mark-paragraph (assoc p :content (vec (concat (d/children p (w "pPr")) body))) "del" opts)))

;; ------------------------------------------------------------------ finding paragraphs

(defn update-unique-paragraph
  "root with f applied to the only w:p for which (pred text) is true. f may return one
  paragraph or a sequence of paragraphs. Throws a :match error when no paragraph or more
  than one paragraph matches."
  [root pred desc f]
  (let [hits (count (filter #(pred (d/text %)) (d/descendants root (w "p"))))]
    (cond
      (zero? hits) (throw (match-error (str "no paragraph matches " desc) {:matches 0}))
      (> hits 1) (throw (match-error (str hits " paragraphs match " desc) {:matches hits})))
    (letfn [(walk [x]
              (if-not (d/el? x)
                [x]
                (if (and (d/tag= (w "p") x) (pred (d/text x)))
                  (let [r (f x)] (if (sequential? r) r [r]))
                  [(assoc x :content (vec (mapcat walk (:content x))))])))]
      (first (walk root)))))

;; ------------------------------------------------------------------ comments

(def comments-type "http://schemas.openxmlformats.org/officeDocument/2006/relationships/comments")
(def comments-content-type "application/vnd.openxmlformats-officedocument.wordprocessingml.comments+xml")

(defn ensure-comments-part
  "entries with word/comments.xml, its relationship and its content type, each added only
  when absent."
  [entries]
  (let [has? (fn [n] (some #(= n (first %)) entries))
        rels-name "word/_rels/document.xml.rels"
        rels (or (d/entry-str entries rels-name) (throw (ex-info "no document relationships part" {:docx/error :layout})))
        ct (d/entry-str entries "[Content_Types].xml")
        rel-ids (set (map second (re-seq #"Id=\"([^\"]+)\"" rels)))
        rel-id (first (remove rel-ids (map #(str "rIdComments" %) (range))))
        updates (cond-> {}
                  (not (str/includes? rels comments-type))
                  (assoc rels-name (str/replace-first rels "</Relationships>"
                                                      (str "<Relationship Id=\"" rel-id "\" Type=\"" comments-type
                                                           "\" Target=\"comments.xml\"/></Relationships>")))
                  (not (str/includes? ct "/word/comments.xml"))
                  (assoc "[Content_Types].xml" (str/replace-first ct "</Types>"
                                                                  (str "<Override PartName=\"/word/comments.xml\" ContentType=\""
                                                                       comments-content-type "\"/></Types>"))))
        entries (d/replace-entries entries updates)]
    (if (has? "word/comments.xml")
      entries
      (conj entries ["word/comments.xml"
                     (.getBytes (str "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                                     "<w:comments xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"/>")
                                "UTF-8")]))))

(defn add-comment
  "comments.xml text with a new comment. Text may hold several paragraphs, separated by a
  blank line."
  [comments-xml {:keys [id author initials date text]}]
  (let [root (xml/parse-str comments-xml)
        paras (str/split (str text) #"\n\n")
        p (fn [i s] (apply xml/element (w "p") {}
                           (concat (when (zero? i) [(run-of nil (el "annotationRef" {}))])
                                   [(run-of nil (t-node s))])))
        c (apply el "comment" {"id" id "author" author "date" date "initials" initials}
                 (map-indexed p paras))]
    (xml/emit-str (update root :content #(conj (vec %) c)))))

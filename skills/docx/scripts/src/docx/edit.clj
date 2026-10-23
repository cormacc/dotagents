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

;; ------------------------------------------------------------------ table rows

(defn row-text
  "Text of a table row: its cell texts (accepted view) joined by \" | \"."
  [tr]
  (str/join " | " (map #(str/join " " (map d/text (d/paras %))) (d/cells tr))))

(def ^:private copied-props-drop
  "Children dropped from copied row and cell properties: change marks, property-change
  records and vertical merges (a copied merge would join the new cell to the one above)."
  (set (map w ["ins" "del" "trPrChange" "tcPrChange" "cellIns" "cellDel" "cellMerge" "vMerge"])))

(defn- copy-props [props]
  (when props (update props :content (fn [c] (vec (remove #(and (d/el? %) (copied-props-drop (:tag %))) c))))))

(defn- mark-row [tr kind {:keys [author date ids]}]
  (let [trpr (or (copy-props (first (d/children tr (w "trPr")))) (xml/element (w "trPr")))
        trpr (update trpr :content #(conj (vec %) (el kind {"id" (ids) "author" author "date" date})))
        [pre post] (split-with #(d/tag= (w "tblPrEx") %) (remove #(d/tag= (w "trPr") %) (:content tr)))]
    (assoc tr :content (vec (concat pre [trpr] post)))))

(defn tracked-insert-row-after
  "[anchor new-row]: a table row with one value for each cell, after anchor, recorded as
  inserted. Row and cell properties, paragraph properties and run properties come from the
  anchor. A blank line in a value starts a new paragraph in the cell. Throws a :match
  error when the number of values differs from the anchor's cells.
  opts: :author :date :ids :comment-id (anchored to the first cell)."
  [anchor values {:keys [author date ids comment-id] :as opts}]
  (let [tcs (d/cells anchor)]
    (when (not= (count tcs) (count values))
      (throw (match-error (str "the row has " (count tcs) " cells, the edit gives " (count values) " values"))))
    (let [cell (fn [i tc v]
                 (let [p0 (first (d/paras tc))
                       ppr (some-> (first (d/children p0 (w "pPr")))
                                   (update :content (fn [c] (vec (remove #(or (d/tag= (w "rPr") %) (d/tag= (w "sectPr") %)
                                                                               (d/tag= (w "pPrChange") %)) c)))))
                       rpr (d/text-rpr p0)
                       paras (str/split (str v) #"\n\n")
                       mkp (fn [j s]
                             (let [anchor? (and comment-id (zero? i) (zero? j))
                                   body (concat (when anchor? [(el "commentRangeStart" {"id" comment-id})])
                                                (when (seq s) [(el "ins" {"id" (ids) "author" author "date" date} (run-of rpr (t-node s)))])
                                                (when anchor? [(el "commentRangeEnd" {"id" comment-id})
                                                               (run-of nil (el "commentReference" {"id" comment-id}))]))]
                               (mark-paragraph (apply xml/element (w "p") {} (concat (when ppr [ppr]) body)) "ins" opts)))]
                   (apply xml/element (w "tc") {}
                          (concat (when-let [tcpr (copy-props (first (d/children tc (w "tcPr"))))] [tcpr])
                                  (map-indexed mkp paras)))))
          row (assoc anchor :content (vec (concat (d/children anchor (w "tblPrEx"))
                                                  (d/children anchor (w "trPr"))
                                                  (map-indexed (fn [i [tc v]] (cell i tc v)) (map vector tcs values)))))]
      [anchor (mark-row row "ins" opts)])))

(defn tracked-delete-row
  "Table row tr recorded as deleted: the row itself (w:trPr/w:del) and every text run in
  its cells, so that accepting removes the row and rejecting keeps it. Existing tracked
  changes in the cells stay as they are. opts: :author :date :ids."
  [tr {:keys [author date ids] :as opts}]
  (let [del-run (fn [r] (assoc r :content (mapv #(if (d/tag= (w "t") %) (del-text-node (apply str (:content %))) %)
                                                (:content r))))
        wrap (fn wrap [x]
               (cond
                 (d/tag= (w "r") x) (el "del" {"id" (ids) "author" author "date" date} (del-run x))
                 (or (d/tag= (w "ins") x) (d/tag= (w "del") x)) x
                 (d/el? x) (assoc x :content (mapv wrap (:content x)))
                 :else x))
        tr' (assoc tr :content (mapv #(if (d/tag= (w "tc") %) (wrap %) %) (:content tr)))]
    (mark-row tr' "del" opts)))

;; ------------------------------------------------------------------ finding paragraphs and rows

(defn- update-matching
  "root with f applied to each element tagged tag for which (pred (text-of el)) is true.
  f may return one element or a sequence."
  [root tag text-of pred f]
  (letfn [(walk [x]
            (if-not (d/el? x)
              [x]
              (if (and (d/tag= tag x) (pred (text-of x)))
                (let [r (f x)] (if (sequential? r) r [r]))
                [(assoc x :content (vec (mapcat walk (:content x))))])))]
    (first (walk root))))

(defn update-paragraphs
  "roots (a map of part name to root) with f applied to the elements that match pred, over
  all the parts together: paragraphs by default, or table rows with :tag (w \"tr\") and
  :text-of row-text. Unless all?, exactly one element must match; with all?, at least one.
  Throws a :match error otherwise."
  [roots pred desc f & {:keys [all? tag text-of] :or {tag (w "p") text-of d/text}}]
  (let [counts (into {} (for [[n r] roots] [n (count (filter #(pred (text-of %)) (d/descendants r tag)))]))
        total (reduce + (vals counts))
        what (if (= tag (w "tr")) "table row" "paragraph")]
    (cond
      (zero? total) (throw (match-error (str "no " what " matches " desc) {:matches 0}))
      (and (> total 1) (not all?)) (throw (match-error (str total " " what "s match " desc "; select one, or set all")
                                                       {:matches total})))
    (into {} (for [[n r] roots] [n (if (pos? (counts n)) (update-matching r tag text-of pred f) r)]))))

(defn update-unique-paragraph
  "root with f applied to the only w:p for which (pred text) is true. f may return one
  paragraph or a sequence of paragraphs. Throws a :match error when no paragraph or more
  than one paragraph matches."
  [root pred desc f]
  (get (update-paragraphs {:root root} pred desc f) :root))

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

(declare add-comment)

;; ------------------------------------------------------------------ edit scripts

(defn selector
  "[pred description] for a paragraph selector: a string (the paragraph contains it) or a
  map with one of :starts, :contains, :equals."
  [sel]
  (let [sel (if (string? sel) {:contains sel} sel)
        [k v] (first (select-keys sel [:starts :contains :equals]))]
    (when-not (and k (string? v) (seq v))
      (throw (match-error (str "bad paragraph selector " (pr-str sel) ": use a string or {starts|contains|equals: text}"))))
    [(case k
       :starts #(str/starts-with? % v)
       :contains #(str/includes? % v)
       :equals #(= % v))
     (str (name k) " " (pr-str v))]))

(def ^:private part-groups
  "Edit script \"part\" values and the package parts they cover."
  {"body" #"word/document\.xml" "headers" #"word/header\d*\.xml" "footers" #"word/footer\d*\.xml"})

(defn apply-script
  "Apply an edit script to package entries. Returns {:entries :report}. Each edit is a map:
    {:op \"replace\" :old s :new s [:in selector]}
    {:op \"insert-after\" :after selector :text s [:style id]}
    {:op \"delete\" :paragraph selector}
    {:op \"insert-row-after\" :row selector :cells [s ...]}
    {:op \"delete-row\" :row selector}
  with optional :comment s, :part (\"body\" by default, \"headers\" or \"footers\") and
  :all true. A replace without :in uses the paragraph that contains :old. A row selector
  matches the row text (cell texts joined by \" | \"). Selection runs over all the parts
  of the group; exactly one paragraph or row must match unless :all. A comment needs the
  body and a single match. The first failure throws a :match error with :index, and the
  caller writes nothing. Comments go to comments.xml (created when absent). Tracking is
  enabled in settings unless no-tracking?."
  [entries script {:keys [author initials date no-tracking?]}]
  (when-not (and (sequential? script) (seq script)) (throw (match-error "the edit script must be a non-empty list")))
  (let [roots0 (into {} (for [n (d/part-names entries #"word/(document|header\d*|footer\d*)\.xml")]
                          [n (d/parse-entry entries n)]))
        croot (d/parse-entry entries "word/comments.xml")
        ids (id-source (reduce max (if croot (max-id croot) -1) (map max-id (vals roots0))))
        opts {:author author :date date :ids ids}
        [roots comments report]
        (reduce
         (fn [[roots comments report] [i e]]
           (try
             (let [group (str (or (:part e) "body"))
                   re (or (part-groups group)
                          (throw (match-error (str "unknown part " (pr-str (:part e)) ": use body, headers or footers"))))
                   names (filter #(re-matches re %) (keys roots))
                   _ (when (empty? names) (throw (match-error (str "the document has no " group))))
                   all? (true? (:all e))
                   _ (when (and (seq (:comment e)) (not= "body" group))
                       (throw (match-error "Word has no comments in headers or footers: give the reason in the check-in comment or the change record")))
                   _ (when (and (seq (:comment e)) all?)
                       (throw (match-error "a comment anchors one place: drop \"all\" or the comment")))
                   cid (when (seq (:comment e)) (ids))
                   o (assoc opts :comment-id cid)
                   results (atom [])
                   sub (select-keys roots names)
                   upd (fn [sel f & {:keys [rows?]}]
                         (let [[pred desc] (selector sel)]
                           (if rows?
                             (update-paragraphs sub pred desc f :all? all? :tag (w "tr") :text-of row-text)
                             (update-paragraphs sub pred desc f :all? all?))))
                   op (str (:op e))
                   sub' (case op
                          "replace"
                          (upd (or (:in e) (:old e))
                               (fn [p] (let [p' (tracked-replace p (str (:old e)) (str (:new e)) o)]
                                         (swap! results conj {:before (d/text p) :after (d/text p')})
                                         p')))
                          "insert-after"
                          (do (when (str/blank? (:text e)) (throw (match-error "insert-after needs text")))
                              (upd (:after e)
                                   (fn [p] (swap! results conj {:after_paragraph (d/text p) :inserted (:text e)})
                                     (tracked-insert-after p (:text e) (assoc o :style (:style e))))))
                          "delete"
                          (upd (:paragraph e)
                               (fn [p] (swap! results conj {:deleted (d/text p)}) (tracked-delete p o)))
                          "insert-row-after"
                          (do (when-not (and (sequential? (:cells e)) (seq (:cells e)))
                                (throw (match-error "insert-row-after needs cells: a list with one value for each cell")))
                              (upd (:row e)
                                   (fn [tr] (swap! results conj {:after_row (row-text tr) :inserted (mapv str (:cells e))})
                                     (tracked-insert-row-after tr (mapv str (:cells e)) o))
                                   :rows? true))
                          "delete-row"
                          (upd (:row e)
                               (fn [tr] (swap! results conj {:deleted_row (row-text tr)}) (tracked-delete-row tr o))
                               :rows? true)
                          (throw (match-error (str "unknown op " (pr-str (:op e))
                                                   ": use replace, insert-after, delete, insert-row-after or delete-row"))))]
               [(merge roots sub')
                (cond-> comments cid (conj {:id cid :text (:comment e)}))
                (conj report (merge {:index i :op op}
                                    (when (not= "body" group) {:part group})
                                    (if (= 1 (count @results)) (first @results) {:results @results})))])
             (catch clojure.lang.ExceptionInfo ex
               (throw (ex-info (str "edit " i " (" (:op e) "): " (ex-message ex)) (merge (ex-data ex) {:index i}))))))
         [roots0 [] []]
         (map-indexed vector script))
        changed (into {} (for [[n r] roots :when (not= r (roots0 n))] [n (xml/emit-str r)]))
        entries (cond-> entries (seq comments) ensure-comments-part)
        comments-xml (when (seq comments)
                       (reduce (fn [x c] (add-comment x (merge c {:author author :initials initials :date date})))
                               (d/entry-str entries "word/comments.xml") comments))
        settings (d/entry-str entries "word/settings.xml")]
    {:entries (d/replace-entries entries (cond-> changed
                                           comments-xml (assoc "word/comments.xml" comments-xml)
                                           (and settings (not no-tracking?)) (assoc "word/settings.xml" (enable-tracking settings))))
     :report report}))

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

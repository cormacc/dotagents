#!/usr/bin/env bb
;; Tests for the docx skill. Fixtures are built at run time from hand-written
;; WordprocessingML, so each XML feature under test is explicit. The compare test needs
;; LibreOffice and reports SKIP without it.
;; DOCX_TEST_SELFCHECK=1 adds one assertion that must fail, to show that failures are seen.

(require '[babashka.classpath :as cp]
         '[babashka.fs :as fs]
         '[babashka.process :as process]
         '[clojure.data.xml :as xml]
         '[clojure.string :as str]
         '[clojure.test :refer [deftest is testing run-tests]])

(def here (fs/parent (fs/absolutize *file*)))
(cp/add-classpath (str (fs/path here "src")))
(require '[docx.edit :as e])
(require '[docx.core :as d :refer [w]]
         '[docx.review :as review])

;; ------------------------------------------------------------------ fixtures

(def ns-decls
  (str "xmlns:wpc=\"http://schemas.microsoft.com/office/word/2010/wordprocessingCanvas\" "
       "xmlns:mc=\"http://schemas.openxmlformats.org/markup-compatibility/2006\" "
       "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\" "
       "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" "
       "xmlns:w14=\"http://schemas.microsoft.com/office/word/2010/wordml\" "
       "mc:Ignorable=\"w14\""))

(def decl "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")

(defn esc [s] (-> s (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))
(defn p [& runs] (str "<w:p>" (apply str runs) "</w:p>"))
(defn heading [s] (str "<w:p><w:pPr><w:pStyle w:val=\"Heading1\"/></w:pPr><w:r><w:t>" (esc s) "</w:t></w:r></w:p>"))
(defn r [s] (str "<w:r><w:t xml:space=\"preserve\">" (esc s) "</w:t></w:r>"))
(defn ins [s] (str "<w:ins w:id=\"1\" w:author=\"T\"><w:r><w:t xml:space=\"preserve\">" (esc s) "</w:t></w:r></w:ins>"))
(defn del [s] (str "<w:del w:id=\"2\" w:author=\"T\"><w:r><w:delText xml:space=\"preserve\">" (esc s) "</w:delText></w:r></w:del>"))
(defn tc [& body] (str "<w:tc><w:tcPr/>" (apply str body) "</w:tc>"))
(defn tr [& cs] (str "<w:tr>" (apply str cs) "</w:tr>"))
;; Pandoc leaves out a table whose w:tblGrid has no w:gridCol, so give every fixture table
;; three columns (the widest fixture row).
(defn tbl [& trs] (str "<w:tbl><w:tblPr/><w:tblGrid><w:gridCol w:w=\"2000\"/><w:gridCol w:w=\"2000\"/><w:gridCol w:w=\"2000\"/></w:tblGrid>"
                       (apply str trs) "</w:tbl>"))
(def checkbox
  (str "<w:sdt><w:sdtPr><w14:checkbox><w14:checked w14:val=\"0\"/>"
       "<w14:checkedState w14:val=\"2612\" w14:font=\"MS Gothic\"/>"
       "<w14:uncheckedState w14:val=\"2610\" w14:font=\"MS Gothic\"/></w14:checkbox></w:sdtPr>"
       "<w:sdtContent><w:r><w:t>" d/unchecked "</w:t></w:r></w:sdtContent></w:sdt>"))

(defn document [& body]
  (str decl "<w:document " ns-decls "><w:body>" (apply str body)
       "<w:sectPr><w:headerReference w:type=\"default\" r:id=\"rIdH\"/>"
       "<w:footerReference w:type=\"default\" r:id=\"rIdF\"/></w:sectPr></w:body></w:document>"))

(defn hdr [tag s] (str decl "<w:" tag " " ns-decls ">" (p (r s)) "</w:" tag ">"))

(def footnotes
  (str decl "<w:footnotes " ns-decls ">"
       "<w:footnote w:type=\"separator\" w:id=\"-1\"><w:p><w:r><w:separator/></w:r></w:p></w:footnote>"
       "<w:footnote w:id=\"2\">" (p (r "Used footnote")) "</w:footnote>"
       "<w:footnote w:id=\"3\">" (p (r "Orphan footnote")) "</w:footnote>"
       "</w:footnotes>"))

(def package-parts
  {"[Content_Types].xml"
   (str decl "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
        "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
        "<Override PartName=\"/word/header1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.header+xml\"/>"
        "<Override PartName=\"/word/footer1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml\"/>"
        "<Override PartName=\"/word/footnotes.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.footnotes+xml\"/>"
        "</Types>")
   "_rels/.rels"
   (str decl "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
        "</Relationships>")
   "word/_rels/document.xml.rels"
   (str decl "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
        "<Relationship Id=\"rIdH\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/header\" Target=\"header1.xml\"/>"
        "<Relationship Id=\"rIdF\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer\" Target=\"footer1.xml\"/>"
        "<Relationship Id=\"rIdN\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/footnotes\" Target=\"footnotes.xml\"/>"
        "</Relationships>")
   "word/header1.xml" (hdr "hdr" "DOC-1 Example Procedure | Version 2.0")
   "word/footer1.xml" (hdr "ftr" "Example footer")
   "word/footnotes.xml" footnotes})

(defn history-table [new-run]
  (tbl (tr (tc (p (r "Version"))) (tc (p (r "Change Description"))) (tc (p (r "Date"))))
       (tr (tc (p (r "1.0"))) (tc (p (r "Initial release"))) (tc (p (r "01-Jan-2026"))))
       (tr (tc (p (new-run "2.0"))) (tc (p (new-run "Added scope"))) (tc (p (new-run "<DD-Mmm-YYYY>"))))
       (tr (tc (p)) (tc (p)) (tc (p)))))

;; A later table whose text mentions a version and a change description, but whose
;; header row is not a history header: inspect must not report it.
(def decoy-table
  (tbl (tr (tc (p (r "Item"))) (tc (p (r "Notes"))))
       (tr (tc (p (r "A"))) (tc (p (r "Version 2.0 adds a change description field."))))))

(def form-table
  (tbl (tr (tc (p (r "Section 1 - Form"))))
       (tr (tc (p (r "Requested by:"))) (tc (p (r "Name here"))))
       (tr (tc (p (r "Applies?"))) (tc (p checkbox (r " Yes ") checkbox (r " No"))))
       (tr (tc (p (r "Note") "<w:r><w:footnoteReference w:id=\"2\"/></w:r>")))))

(def plain-body
  (str (heading "Purpose") (p (r "The procedure applies to all products.")) (history-table r) decoy-table form-table))

(def tracked-body
  (str (heading "Purpose") (p (r "The procedure applies to ") (del "all") (ins "released") (r " products."))
       (heading "Scope") (p (ins "A new paragraph."))
       (history-table ins) decoy-table form-table))

(defn build!
  "Write a .docx at path from package-parts with document body body. With :bom, the
  document part starts with a UTF-8 byte-order mark."
  [path body & {:keys [bom]}]
  (d/write-docx path (for [[n s] (assoc package-parts "word/document.xml"
                                        (str (when bom "\uFEFF") (document body)))]
                       [n (.getBytes ^String s "UTF-8")]))
  (str path))

(def tmp (str (fs/create-temp-dir {:prefix "docx-test-"})))
(def plain (build! (fs/path tmp "plain.docx") plain-body))
(def tracked (build! (fs/path tmp "tracked.docx") tracked-body))
(def bom (build! (fs/path tmp "bom.docx") plain-body :bom true))

;; ------------------------------------------------------------------ tests

(deftest emit-keeps-namespace-prefixes
  (let [raw (d/entry-str (d/read-entries tracked) "word/document.xml")
        out (xml/emit-str (xml/parse-str raw))]
    (is (str/includes? out "mc:Ignorable=\"w14\"") "mc:Ignorable names a prefix, so it must survive")
    (is (str/includes? out "<w14:checkbox") "the w14 prefix is kept on elements")
    (is (str/includes? out "<w:document ") "the w prefix is kept on the root")))

(deftest byte-order-mark-is-removed
  (let [entries (d/read-entries bom)]
    (is (str/starts-with? (String. ^bytes (some (fn [[n b]] (when (= n "word/document.xml") b)) entries) "UTF-8")
                          "\uFEFF")
        "control: the fixture really starts with a byte-order mark")
    (is (= "DOC-1 Example Procedure | Version 2.0" (first (:headers (review/inspect bom)))))
    (is (seq (:version_history (review/inspect bom))))))

(deftest inspect-reports
  (let [t (review/inspect tracked)
        pl (review/inspect plain)]
    (is (= ["DOC-1 Example Procedure | Version 2.0"] (:headers t)))
    (is (= ["Example footer"] (:footers t)))
    (is (= {"word/document.xml" {:ins 5 :del 1}} (:tracked_changes t)))
    (is (= {} (:tracked_changes pl)) "no tracked changes in the plain fixture")
    (testing "history rows include inserted text, drop blank rows, and skip the decoy table"
      (is (= [["Version" "Change Description" "Date"]
              ["1.0" "Initial release" "01-Jan-2026"]
              ["2.0" "Added scope" "<DD-Mmm-YYYY>"]]
             (:version_history t))))))

(deftest changes-lists-changed-paragraphs
  (is (= [{:heading "Purpose" :before "The procedure applies to all products."
           :after "The procedure applies to released products."}
          {:heading "Scope" :before "" :after "A new paragraph."}]
         (take 2 (review/changes tracked))))
  (is (= 5 (count (review/changes tracked))) "two body paragraphs and three history cells")
  (is (= [] (review/changes plain))))

(deftest editing-helpers
  (let [doc (d/parse-entry (d/read-entries plain) "word/document.xml")
        body (first (d/children doc (w "body")))
        body' (d/update-table body "Section 1"
                              (fn [t] (-> t
                                          (d/update-row "Requested by" #(d/update-cell % 1 (fn [c] (d/set-cell-text c "A. Person"))))
                                          (d/update-row "Applies?" #(d/update-cell % 1 (fn [c] (d/tick c (fn [i] (= i 1)) :expect 2)))))))
        form (first (filter #(str/starts-with? (d/heading %) "Section 1") (d/children body' (w "tbl"))))
        boxes (filter d/checkbox? (tree-seq d/el? :content form))]
    (is (str/includes? (d/text form) "Requested by:A. Person"))
    (is (= ["0" "1"] (map #(-> (first (d/descendants % (d/w14 "checked"))) :attrs (get (d/w14 "val"))) boxes)))
    (is (= [d/unchecked d/checked] (map d/text boxes)))
    (is (= :layout (:docx/error (ex-data (try (d/update-table body "No such table" identity) (catch Exception e e))))))
    (is (= :layout (:docx/error (ex-data (try (d/tick form (constantly true) :expect 3) (catch Exception e e))))))))

(deftest footnotes-are-pruned
  (let [entries (d/read-entries plain)
        out (xml/parse-str (d/prune-footnotes (d/entry-str entries "word/footnotes.xml")
                                              (d/entry-str entries "word/document.xml")))
        ids (map #(get-in % [:attrs (w "id")]) (d/children out (w "footnote")))]
    (is (= ["-1" "2"] ids) "separator and referenced footnote stay; the orphan goes")))

(deftest write-round-trip
  (let [entries (d/read-entries plain)
        out (str (fs/path tmp "copy.docx"))]
    (d/write-docx out (d/replace-entries entries {"word/footer1.xml" (hdr "ftr" "Changed footer")}))
    (is (= ["Changed footer"] (:footers (review/inspect out))))
    (is (= (map first entries) (map first (d/read-entries out))) "entry order is kept")))

(deftest compare-marks-changes
  (if-not (review/lo-python)
    (println "SKIP compare-marks-changes: no LibreOffice python with uno")
    (let [new (build! (fs/path tmp "new.docx") (str/replace plain-body "all products" "released products"))
          out (str (fs/path tmp "compared.docx"))]
      (review/compare-docx plain new out)
      (is (pos? (get-in (review/inspect out) [:tracked_changes "word/document.xml" :ins] 0))))))

;; ------------------------------------------------------------------ tracked editing

(def edit-body
  (str (heading "Purpose")
       (p (r "The procedure applies to ") "<w:r><w:rPr><w:b/></w:rPr><w:t xml:space=\"preserve\">all released</w:t></w:r>" (r " products."))
       (p (r "Standard: ISO/IEC 62304")
          "<w:r><w:fldChar w:fldCharType=\"begin\"/></w:r><w:r><w:instrText xml:space=\"preserve\"> CITATION x </w:instrText></w:r>"
          "<w:r><w:fldChar w:fldCharType=\"separate\"/></w:r><w:r><w:t>[1]</w:t></w:r><w:r><w:fldChar w:fldCharType=\"end\"/></w:r>"
          (r "."))
       (p (r "Keep ") "<w:ins w:id=\"5\" w:author=\"Other\" w:date=\"2026-01-01T00:00:00Z\"><w:r><w:t xml:space=\"preserve\">this </w:t></w:r></w:ins>" (r "text here."))
       (p (r "Delete me."))
       "<w:p><w:pPr><w:pStyle w:val=\"Heading2\"/><w:sectPr/></w:pPr><w:r><w:t>Anchor paragraph.</w:t></w:r></w:p>"
       (p (r "dup and dup"))
       (p (r "Last paragraph."))))

(def edit-src (build! (fs/path tmp "edit-src.docx") edit-body))
(def opts {:author "Tester" :date "2026-10-23T12:00:00Z"})

(defn edit-doc!
  "Apply f (root ids -> root) to the document of edit-src; write and return a new path."
  [name f & {:keys [entries-f]}]
  (let [entries ((or entries-f identity) (d/read-entries edit-src))
        root (d/parse-entry entries "word/document.xml")
        ids (e/id-source (e/max-id root))
        out (str (fs/path tmp name))]
    (d/write-docx out (d/replace-entries entries {"word/document.xml" (xml/emit-str (f root ids))}))
    out))

(defn starts [s] #(str/starts-with? % s))

(def pandoc? (boolean (fs/which "pandoc")))
(defn pandoc-lines
  "Non-blank lines of pandoc's plain text with tracked changes accepted or rejected."
  [path mode]
  (->> (:out (process/shell {:out :string} "pandoc" (str "--track-changes=" mode) "-t" "plain" "--wrap=none" path))
       str/split-lines (map str/trim) (remove str/blank?) vec))
(defn oracle [path accept reject]
  (if-not pandoc?
    (println "SKIP pandoc oracle: pandoc not found")
    (do (is (= accept (pandoc-lines path "accept")) "accept view")
        (is (= reject (pandoc-lines path "reject")) "reject view"))))

(def original-lines
  ["Purpose" "The procedure applies to all released products." "Standard: ISO/IEC 62304[1]."
   "Keep this text here." "Delete me." "Anchor paragraph." "dup and dup" "Last paragraph."])

(def rejected-lines
  "Pandoc's reject view also rejects the fixture's existing insertion by Other (\"this \")."
  (assoc original-lines 3 "Keep text here."))

(deftest control-original-text
  (when pandoc? (is (= original-lines (pandoc-lines edit-src "accept")))))

(deftest replace-across-runs-keeps-formatting
  (let [out (edit-doc! "r1.docx" (fn [root ids]
                                   (e/update-unique-paragraph root (starts "The procedure") "procedure"
                                                              #(e/tracked-replace % "applies to all" "covers all" (assoc opts :ids ids)))))
        doc (d/parse-entry (d/read-entries out) "word/document.xml")
        bold-texts (for [r (d/descendants doc (w "r")) :when (seq (d/descendants r (w "b")))]
                     (apply str (concat (mapcat :content (d/descendants r (w "t"))) (mapcat :content (d/descendants r (w "delText"))))))]
    (oracle out (assoc original-lines 1 "The procedure covers all released products.") rejected-lines)
    (is (= #{"all" " released"} (set bold-texts)) "the bold run is split; both parts stay bold")
    (is (= {"word/document.xml" {:ins 2 :del 1}} (:tracked_changes (review/inspect out))))))

(deftest replace-refuses-a-match-across-a-field
  (let [code (fn [old] (try (edit-doc! "r2.docx" (fn [root ids]
                                                    (e/update-unique-paragraph root (starts "Standard") "standard"
                                                                               #(e/tracked-replace % old "X" (assoc opts :ids ids)))))
                            nil (catch clojure.lang.ExceptionInfo ex (ex-data ex))))]
    (is (nil? (code "ISO/IEC 62304")) "text before the field can be replaced")
    (is (= :match (:docx/error (code "62304[1]"))))
    (is (= :match (:docx/error (code "zzz"))))))

(deftest replace-and-paragraph-matching-must-be-unique
  (let [run-edit (fn [pred old] (try (edit-doc! "r3.docx" (fn [root ids]
                                                           (e/update-unique-paragraph root pred "x"
                                                                                      #(e/tracked-replace % old "y" (assoc opts :ids ids)))))
                                     :ok (catch clojure.lang.ExceptionInfo ex (select-keys (ex-data ex) [:docx/error :matches]))))]
    (is (= {:docx/error :match} (run-edit (starts "dup") "dup")) "two occurrences in the paragraph")
    (is (= {:docx/error :match :matches 0} (run-edit (starts "Nothing") "x")))
    (is (= {:docx/error :match :matches 2} (run-edit #(str/includes? % "paragraph.") "x")))
    (is (= :ok (run-edit (starts "dup") "dup and")) "control: a unique match succeeds")))

(deftest existing-changes-by-other-authors-are-kept
  (let [out (edit-doc! "r4.docx" (fn [root ids]
                                   (e/update-unique-paragraph root (starts "Keep") "keep"
                                                              #(e/tracked-replace % "here" "now" (assoc opts :ids ids)))))
        doc (d/parse-entry (d/read-entries out) "word/document.xml")]
    (is (= 1 (count (filter #(= "Other" (get-in % [:attrs (w "author")])) (d/descendants doc (w "ins"))))))
    (oracle out (assoc original-lines 3 "Keep this text now.") (assoc original-lines 3 "Keep text here."))))

(deftest insert-and-delete-paragraphs
  (let [ins-out (edit-doc! "r5.docx" (fn [root ids]
                                       (e/update-unique-paragraph root (starts "Anchor") "anchor"
                                                                  #(e/tracked-insert-after % "Inserted paragraph." (assoc opts :ids ids)))))
        new-p (first (filter #(= "Inserted paragraph." (d/text %))
                             (d/descendants (d/parse-entry (d/read-entries ins-out) "word/document.xml") (w "p"))))]
    (oracle ins-out (vec (concat (take 6 original-lines) ["Inserted paragraph."] (drop 6 original-lines))) rejected-lines)
    (is (seq (d/descendants new-p (w "pStyle"))) "paragraph properties are copied")
    (is (empty? (d/descendants new-p (w "sectPr"))) "the anchor's section break is not copied")
    ;; Pandoc drops empty paragraphs, so the oracle cannot see a paragraph mark left behind.
    (is (seq (some-> (first (d/children new-p (w "pPr"))) (d/children (w "rPr")) first (d/children (w "ins"))))
        "the new paragraph mark is recorded as inserted"))
  (let [del-out (edit-doc! "r6.docx" (fn [root ids]
                                       (e/update-unique-paragraph root (starts "Delete me") "delete"
                                                                  #(e/tracked-delete % (assoc opts :ids ids)))))]
    (oracle del-out (vec (concat (take 4 original-lines) (drop 5 original-lines))) rejected-lines)
    (let [del-p (first (filter #(= "Delete me." (apply str (mapcat :content (d/descendants % (w "delText")))))
                               (d/descendants (d/parse-entry (d/read-entries del-out) "word/document.xml") (w "p"))))]
      (is (seq (some-> (first (d/children del-p (w "pPr"))) (d/children (w "rPr")) first (d/children (w "del"))))
          "the deleted paragraph mark is recorded as deleted"))))

(deftest new-ids-are-unique
  (let [out (edit-doc! "r7.docx" (fn [root ids]
                                   (-> root
                                       (e/update-unique-paragraph (starts "The procedure") "p" #(e/tracked-replace % "applies" "is" (assoc opts :ids ids)))
                                       (e/update-unique-paragraph (starts "Delete me") "d" #(e/tracked-delete % (assoc opts :ids ids))))))
        doc (d/parse-entry (d/read-entries out) "word/document.xml")
        idv (keep #(get-in % [:attrs (w "id")]) (concat (d/descendants doc (w "ins")) (d/descendants doc (w "del"))))]
    (is (= (count idv) (count (set idv))))
    (is (every? #(> (parse-long %) 5) (remove #{"5"} idv)) "new ids are above the existing ones")))

(deftest comments-part-and-anchors
  (let [prep (fn [entries]
               (let [en (-> entries e/ensure-comments-part e/ensure-comments-part)]
                 (d/replace-entries en {"word/comments.xml"
                                        (e/add-comment (d/entry-str en "word/comments.xml")
                                                       {:id "100" :author "Tester" :initials "T" :date "2026-10-23T12:00:00Z"
                                                        :text "Reason: clause 4.3.\n\nSecond paragraph."})})))
        out (edit-doc! "r8.docx" (fn [root ids]
                                   (e/update-unique-paragraph root (starts "The procedure") "p"
                                                              #(e/tracked-replace % "applies" "is" (assoc opts :ids ids :comment-id "100"))))
                       :entries-f prep)
        entries (d/read-entries out)
        rels (d/entry-str entries "word/_rels/document.xml.rels")
        ct (d/entry-str entries "[Content_Types].xml")
        cx (d/entry-str entries "word/comments.xml")
        doc (d/parse-entry entries "word/document.xml")]
    (is (= 1 (count (re-seq #"relationships/comments" rels))) "one relationship after two calls")
    (is (= 1 (count (re-seq #"/word/comments.xml" ct))) "one content type override after two calls")
    (is (= 1 (count (filter #(= "word/comments.xml" (first %)) entries))))
    (is (str/includes? cx "w:annotationRef"))
    (testing "parts other than document, comments, relationships and content types are unchanged"
      (let [allowed #{"word/document.xml" "word/comments.xml" "word/_rels/document.xml.rels" "[Content_Types].xml"}
            src (into {} (d/read-entries edit-src))]
        (doseq [[n b] entries :when (not (allowed n))]
          (is (java.util.Arrays/equals ^bytes (get src n) ^bytes b) n))
        (is (< 3 (count (remove (comp allowed first) entries))) "control: some parts were compared")))
    (doseq [t ["commentRangeStart" "commentRangeEnd" "commentReference"]]
      (is (= ["100"] (map #(get-in % [:attrs (w "id")]) (d/descendants doc (w t)))) t))
    (when pandoc?
      (let [md (:out (process/shell {:out :string} "pandoc" "--track-changes=all" "-t" "markdown" "--wrap=none" out))]
        (is (str/includes? md "Reason: clause 4.3."))
        (is (str/includes? md "comment-start"))))))

(deftest enable-tracking-respects-schema-order
  (let [s (str decl "<w:settings " ns-decls "><w:zoom w:percent=\"100\"/><w:attachedTemplate r:id=\"x\"/><w:defaultTabStop w:val=\"720\"/></w:settings>")
        out (e/enable-tracking s)
        order (map #(name (:tag %)) (filter d/el? (:content (xml/parse-str out))))]
    (is (= ["zoom" "attachedTemplate" "trackRevisions" "defaultTabStop"] order))
    (is (= out (e/enable-tracking out)) "idempotent")
    (is (= ["trackRevisions" "defaultTabStop"]
           (map #(name (:tag %)) (filter d/el? (:content (xml/parse-str (e/enable-tracking (str decl "<w:settings " ns-decls "><w:defaultTabStop w:val=\"720\"/></w:settings>"))))))))))

;; ------------------------------------------------------------------ edit scripts, comments, clean

(require '[docx.clean :as clean])

(def cli (str (fs/path here "docx.clj")))
(defn run-cli [& args] (apply process/shell {:out :string :err :string :continue true} "bb" cli args))

(def script
  [{:op "replace" :old "applies to all" :new "covers all" :comment "Reason: scope wording."}
   {:op "insert-after" :after {:starts "Anchor"} :text "Inserted paragraph." :comment "Reason: new requirement.\n\nSecond line."}
   {:op "delete" :paragraph {:equals "Delete me."}}])

(def with-settings
  "edit-src plus a settings part with an empty run in the body, to test what clean keeps."
  (let [src (str (fs/path tmp "edit-src-settings.docx"))
        entries (d/read-entries edit-src)
        doc (d/entry-str entries "word/document.xml")
        doc' (str/replace-first doc "<w:r><w:t xml:space=\"preserve\">Last paragraph.</w:t></w:r>"
                                "<w:r><w:rPr><w:b/></w:rPr></w:r><w:r><w:t xml:space=\"preserve\">Last paragraph.</w:t></w:r>")]
    (assert (not= doc doc') "fixture: empty run inserted")
    (d/write-docx src (conj (d/replace-entries entries {"word/document.xml" doc'})
                            ["word/settings.xml" (.getBytes (str decl "<w:settings " ns-decls "><w:zoom w:percent=\"100\"/><w:defaultTabStop w:val=\"720\"/></w:settings>") "UTF-8")]))
    src))

(defn edited! [name]
  (let [out (str (fs/path tmp name))
        {:keys [entries report]} (e/apply-script (d/read-entries with-settings) script
                                                 {:author "Tester" :initials "TT" :date "2026-10-23T12:00:00Z"})]
    (d/write-docx out entries)
    [out report]))

(deftest edit-script-applies-all-edits
  (let [[out report] (edited! "s1.docx")
        entries (d/read-entries out)]
    (is (= ["replace" "insert-after" "delete"] (map :op report)))
    (is (= "The procedure covers all released products." (:after (first report))))
    (is (str/includes? (d/entry-str entries "word/settings.xml") "trackRevisions"))
    (oracle out (vec (concat (take 1 original-lines) ["The procedure covers all released products."]
                             (take 2 (drop 2 original-lines)) ["Anchor paragraph." "Inserted paragraph."] (drop 6 original-lines)))
            rejected-lines)))

(deftest edit-script-is-all-or-nothing
  (let [bad (str (fs/path tmp "bad-script.json"))
        out (str (fs/path tmp "never.docx"))]
    (spit bad (json/generate-string (assoc script 1 {:op "insert-after" :after "No such paragraph" :text "x"})))
    (let [r (run-cli "edit" with-settings bad out "--author" "Tester")]
      (is (= 2 (:exit r)))
      (is (str/includes? (:err r) "edit 1 (insert-after)"))
      (is (not (fs/exists? out)) "nothing is written"))
    (let [good (str (fs/path tmp "good-script.json"))]
      (spit good (json/generate-string script))
      (is (zero? (:exit (run-cli "edit" with-settings good out "--author" "Tester"))) "control: the valid script succeeds")
      (is (fs/exists? out)))))

(deftest comments-list-the-anchored-change
  (let [[out _] (edited! "s2.docx")
        cs (review/comments out)]
    (is (= 2 (count cs)))
    (is (= {:author "Tester" :text "Reason: scope wording." :heading "Purpose" :before "applies to all" :after "covers all"}
           (select-keys (first cs) [:author :text :heading :before :after])))
    (is (= "Reason: new requirement.\n\nSecond line." (:text (second cs))))
    (is (= "Inserted paragraph." (:after (second cs))))
    (is (= "" (:before (second cs))))))

(deftest clean-accepts-everything-and-removes-comments
  (let [[edited _] (edited! "s3.docx")
        out (str (fs/path tmp "clean.docx"))
        _ (d/write-docx out (clean/clean-entries (d/read-entries edited)))
        entries (d/read-entries out)
        doc (d/parse-entry entries "word/document.xml")
        accepted (vec (concat (take 1 original-lines) ["The procedure covers all released products."]
                              (take 2 (drop 2 original-lines)) ["Anchor paragraph." "Inserted paragraph."] (drop 6 original-lines)))]
    (when pandoc? (is (= (pandoc-lines edited "accept") (pandoc-lines out "accept") (pandoc-lines out "reject"))
                      "clean text equals the accept view of the input, and has nothing left to reject"))
    (is (= accepted (mapv d/text (remove #(str/blank? (d/text %)) (d/descendants doc (w "p"))))))
    (is (= (count accepted) (count (d/descendants doc (w "p")))) "the deleted paragraph leaves no empty paragraph")
    (is (= {} (:tracked_changes (review/inspect out))))
    (is (empty? (filter #(str/includes? (first %) "comments") entries)))
    (is (not (str/includes? (d/entry-str entries "word/_rels/document.xml.rels") "comments")))
    (is (not (str/includes? (d/entry-str entries "[Content_Types].xml") "comments")))
    (is (not (str/includes? (d/entry-str entries "word/settings.xml") "trackRevisions")))
    (is (empty? (filter #(some #{(w "commentRangeStart") (w "commentRangeEnd") (w "commentReference")} [(:tag %)])
                        (tree-seq d/el? :content doc))))
    (is (= 1 (count (filter #(and (d/tag= (w "r") %) (empty? (d/text %)) (seq (d/descendants % (w "b"))))
                            (d/descendants doc (w "r")))))
        "an empty run that was in the document stays")
    (testing "--keep-tracking keeps the setting"
      (is (str/includes? (d/entry-str (clean/clean-entries (d/read-entries edited) :keep-tracking? true) "word/settings.xml")
                         "trackRevisions")))))

(deftest cli-edit-comments-clean
  (let [s (str (fs/path tmp "cli-script.edn")) e1 (str (fs/path tmp "cli-edited.docx")) c1 (str (fs/path tmp "cli-clean.docx"))]
    (spit s (pr-str script))
    (let [r (run-cli "edit" with-settings s e1 "--author" "Tester" "--initials" "TT")]
      (is (zero? (:exit r)) (:err r))
      (is (= 3 (count (get (json/parse-string (:out r)) "edits")))))
    (is (= 2 (count (json/parse-string (:out (run-cli "comments" e1))))))
    (let [r (run-cli "clean" e1 c1)]
      (is (zero? (:exit r)) (:err r))
      (when pandoc? (is (true? (get (json/parse-string (:out r)) "verified")))))))

;; ------------------------------------------------------------------ headers, footers, table rows

(def split-header
  (str decl "<w:hdr " ns-decls ">" (p (r "DOC-1 Example Procedure"))
       (p (r "Version ") (r "2") (r ".0") (r " (CR-") (r "1)")) "</w:hdr>"))

(def table-src
  "A document with a history table, and the split-run header in two header parts."
  (let [src (str (fs/path tmp "table-src.docx"))
        body (str (heading "Version History") (history-table r) (p (r "End.")))]
    (build! src body)
    (d/write-docx src (conj (d/replace-entries (d/read-entries src) {"word/header1.xml" split-header})
                            ["word/header2.xml" (.getBytes ^String split-header "UTF-8")]))
    src))

(defn script! [name script]
  (let [out (str (fs/path tmp name))
        {:keys [entries report]} (e/apply-script (d/read-entries table-src) script
                                                 {:author "Tester" :initials "TT" :date "2026-10-23T12:00:00Z"})]
    (d/write-docx out entries)
    [out report]))

(defn script-error [script]
  (try (e/apply-script (d/read-entries table-src) script {:author "T" :initials "T" :date "2026-10-23T12:00:00Z"}) nil
       (catch clojure.lang.ExceptionInfo ex {:error (:docx/error (ex-data ex)) :message (ex-message ex)})))

(def header-edit {:op "replace" :part "headers" :old "2.0 (CR-1)" :new "3.0 (CR-2)"})

(deftest header-edits
  (testing "a match in two header parts needs all"
    (is (= :match (:error (script-error [header-edit]))))
    (is (str/includes? (:message (script-error [header-edit])) "2 paragraphs match")))
  (testing "comments are refused in headers"
    (is (str/includes? (:message (script-error [(assoc header-edit :all true :comment "why")])) "no comments in headers")))
  (testing "with all, both headers change, across split runs"
    (let [[out report] (script! "h1.docx" [(assoc header-edit :all true)])
          ch (review/changes out)]
      (is (= "headers" (:part (first report))))
      (is (= 2 (count (:results (first report)))))
      (is (= #{"word/header1.xml" "word/header2.xml"} (set (map :part ch))))
      (is (every? #(and (= "Version 2.0 (CR-1)" (:before %)) (= "Version 3.0 (CR-2)" (:after %))) ch))
      (is (= ["DOC-1 Example Procedure | Version 3.0 (CR-2)"] (:headers (review/inspect out))))
      (is (= {"word/header1.xml" {:ins 1 :del 1} "word/header2.xml" {:ins 1 :del 1}} (:tracked_changes (review/inspect out))))
      (testing "clean accepts header changes"
        (let [c (str (fs/path tmp "h1-clean.docx"))]
          (d/write-docx c (clean/clean-entries (d/read-entries out)))
          (is (= {} (:tracked_changes (review/inspect c))))
          (is (= ["DOC-1 Example Procedure | Version 3.0 (CR-2)"] (:headers (review/inspect c)))))))))

(deftest table-row-edits
  (when pandoc?
    (is (str/includes? (str/join "\n" (pandoc-lines table-src "accept")) "Initial release")
        "control: pandoc renders the fixture table"))
  (let [row-sel {:starts "1.0 | Initial release"}]
    (testing "a row insertion copies the anchor row and is tracked"
      (let [[out report] (script! "t1.docx" [{:op "insert-row-after" :row row-sel :cells ["1.1" "Fixed typo" "02-Jan-2026"]
                                              :comment "Reason: record the revision."}])
            doc (d/parse-entry (d/read-entries out) "word/document.xml")
            new-tr (first (filter #(str/starts-with? (e/row-text %) "1.1") (d/descendants doc (w "tr"))))]
        (is (= "1.0 | Initial release | 01-Jan-2026" (:after_row (first report))))
        (is (seq (some-> (first (d/children new-tr (w "trPr"))) (d/children (w "ins")))) "the row is marked inserted")
        (is (= 3 (count (d/cells new-tr))))
        (when pandoc?
          (is (str/includes? (str/join "\n" (pandoc-lines out "accept")) "Fixed typo"))
          (is (not (str/includes? (str/join "\n" (pandoc-lines out "reject")) "Fixed typo"))))
        (is (= {:text "Reason: record the revision." :after "1.1"} (select-keys (first (review/comments out)) [:text :after])))
        (testing "clean keeps the inserted row and drops its marks"
          (let [c (str (fs/path tmp "t1-clean.docx"))
                _ (d/write-docx c (clean/clean-entries (d/read-entries out)))
                cdoc (d/parse-entry (d/read-entries c) "word/document.xml")]
            (is (= {} (:tracked_changes (review/inspect c))))
            (is (some #(str/starts-with? (e/row-text %) "1.1 | Fixed typo") (d/descendants cdoc (w "tr"))))))))
    (testing "a cell count that differs from the anchor is refused"
      (is (str/includes? (:message (script-error [{:op "insert-row-after" :row row-sel :cells ["1.1" "x"]}])) "3 cells")))
    (testing "a row deletion is tracked; clean removes the row"
      (let [[out _] (script! "t2.docx" [{:op "delete-row" :row row-sel}])
            doc (d/parse-entry (d/read-entries out) "word/document.xml")
            tr (first (filter #(str/includes? (e/row-text %) "") (filter #(seq (some-> (first (d/children % (w "trPr"))) (d/children (w "del"))))
                                                                      (d/descendants doc (w "tr")))))
            c (str (fs/path tmp "t2-clean.docx"))]
        (is (some? tr) "the row is marked deleted")
        (when pandoc?
          (is (not (str/includes? (str/join "\n" (pandoc-lines out "accept")) "Initial release")))
          (is (str/includes? (str/join "\n" (pandoc-lines out "reject")) "Initial release")))
        (d/write-docx c (clean/clean-entries (d/read-entries out)))
        (is (not-any? #(str/includes? (e/row-text %) "Initial release")
                      (d/descendants (d/parse-entry (d/read-entries c) "word/document.xml") (w "tr"))))))
    (testing "a row selector that matches no row is a match error"
      (is (= :match (:error (script-error [{:op "delete-row" :row {:starts "9.9"}}])))))))

(deftest selfcheck
  (when (System/getenv "DOCX_TEST_SELFCHECK")
    (is (= 1 2) "deliberate failure: DOCX_TEST_SELFCHECK is set")))

(let [{:keys [fail error]} (run-tests)]
  (fs/delete-tree tmp)
  (System/exit (if (zero? (+ fail error)) 0 1)))

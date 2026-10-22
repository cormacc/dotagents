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
(defn tbl [& trs] (str "<w:tbl><w:tblPr/><w:tblGrid/>" (apply str trs) "</w:tbl>"))
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

(deftest selfcheck
  (when (System/getenv "DOCX_TEST_SELFCHECK")
    (is (= 1 2) "deliberate failure: DOCX_TEST_SELFCHECK is set")))

(let [{:keys [fail error]} (run-tests)]
  (fs/delete-tree tmp)
  (System/exit (if (zero? (+ fail error)) 0 1)))

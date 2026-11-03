(ns docx.review
  "Review a .docx: header and footer text, tracked changes, rendered section numbers,
  stored field errors, version history, and a LibreOffice comparison of two versions."
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

(defn- field-append [state key text]
  (if (seq (:stack state))
    (update-in state [:stack (dec (count (:stack state))) key] str text)
    state))

(defn- field-start [state phase instruction]
  (-> state
      (update :stack conj {:paragraph (:paragraph state) :order (:next-order state)
                           :phase phase :instruction instruction :result ""})
      (update :next-order inc)))

(defn- field-end [state]
  (if-let [field (peek (:stack state))]
    (let [state (update state :stack pop)
          result (str/trim (:result field))
          state (if (re-find #"(?i)^Error!" result)
                  (update state :errors conj (-> (select-keys field [:paragraph :order :instruction])
                                                (update :instruction str/trim)
                                                (assoc :result result)))
                  state)]
      ;; A nested field's cached value belongs to its enclosing instruction or result,
      ;; not to the enclosing field's other phase.
      (if-let [phase (:phase (peek (:stack state)))]
        (field-append state phase (:result field))
        state))
    state))

(defn- field-walk [state node]
  (let [tag (:tag node)
        phase (:phase (peek (:stack state)))
        kids (fn [state] (reduce field-walk state (:content node)))]
    (cond
      (not (d/el? node)) state
      (#{(w "del") (w "moveFrom") (w "pPr") (w "rPr")} tag)
      (update state :paragraph + (count (d/descendants node (w "p"))))
      (= (w "p") tag) (let [state (kids (update state :paragraph inc))]
                        (if (= :result (:phase (peek (:stack state))))
                          (field-append state :result "\n") state))
      (= (w "fldSimple") tag) (-> state
                                  (field-start :result (get-in node [:attrs (w "instr")] ""))
                                  kids field-end)
      (= (w "fldChar") tag)
      (case (get-in node [:attrs (w "fldCharType")])
        "begin" (field-start state :instruction "")
        "separate" (if (seq (:stack state))
                     (assoc-in state [:stack (dec (count (:stack state))) :phase] :result) state)
        "end" (field-end state)
        state)
      (and (= (w "instrText") tag) (= :instruction phase))
      (field-append state :instruction (apply str (:content node)))
      (and (= (w "t") tag) (= :result phase))
      (field-append state :result (apply str (:content node)))
      (and (= (w "tab") tag) (= :result phase)) (field-append state :result "\t")
      (and (#{(w "br") (w "cr")} tag) (= :result phase)) (field-append state :result "\n")
      :else (kids state))))

(defn- field-errors [entries]
  (vec (mapcat (fn [part]
                 (let [state (field-walk {:paragraph 0 :next-order 0 :stack [] :errors []}
                                         (d/parse-entry entries part))]
                   (map #(-> % (dissoc :order) (assoc :part part))
                        (sort-by :order (:errors state)))))
               (sort (d/part-names entries #"word/(document|header\d*|footer\d*|footnotes|endnotes)\.xml")))))

(defn inspect
  "Summary of the .docx at path: :headers, :footers, :tracked_changes, :version_history
  and :field_errors. Errors are cached field results starting with English 'Error!',
  without rendering or recomputation; deleted revisions are excluded. Each error gives
  :part, one-based starting :paragraph in the XML part, :instruction and :result."
  [path]
  (let [entries (d/read-entries path)]
    {:file (str path)
     :headers (parts-text entries #"word/header\d*\.xml")
     :footers (parts-text entries #"word/footer\d*\.xml")
     :tracked_changes (tracked-counts entries)
     :field_errors (field-errors entries)
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

(defn- styled-heading? [p]
  (boolean (re-find #"(?i)heading|title" (str (style-of p)))))

(defn- piece-text [ps kinds]
  (str/trim (apply str (keep (fn [[kind text]] (when (kinds kind) text)) ps))))

(defn- part-changes
  ([root part]
   (part-changes root part (fn [_ p after]
                            (when (and (styled-heading? p) (seq after)) {:heading after}))))
  ([root part heading-context]
   (loop [[p & more] (d/descendants root (w "p")) i 0 context {:heading nil} out []]
     (if-not p
       out
       (let [ps (pieces p)
             after (piece-text ps #{:same :ins})
             context (or (heading-context i p after) context)]
         (recur more (inc i) context
                (if (some #(not= :same (first %)) ps)
                  (conj out (cond-> (assoc context :before (piece-text ps #{:same :del}) :after after)
                              part (assoc :part part)))
                  out)))))))

(defn changes
  "One map per paragraph with tracked changes: :heading (the nearest heading above it, by
  paragraph style), :before (text with deletions, without insertions) and :after. Body
  paragraphs come first; header and footer paragraphs follow, with :part (the part name)."
  [path]
  (let [entries (d/read-entries path)]
    (vec (concat (part-changes (d/parse-entry entries "word/document.xml") nil)
                 (mapcat #(part-changes (d/parse-entry entries %) %)
                         (d/part-names entries #"word/(header|footer)\d*\.xml"))))))

;; ------------------------------------------------------------------ rendered sections

(defn- outline-level [node]
  (some-> (first (d/children node (w "pPr")))
          (d/children (w "outlineLvl")) first (get-in [:attrs (w "val")])))

(defn- section-heading? [styles paragraph]
  (let [level (or (outline-level paragraph)
                  (loop [id (style-of paragraph) seen #{}]
                    (when (and id (not (seen id)))
                      (let [style (get styles id)]
                        (or (outline-level style)
                            (recur (some-> (first (d/children style (w "basedOn")))
                                           (get-in [:attrs (w "val")]))
                                   (conj seen id)))))))]
    (if level
      (contains? #{"0" "1" "2" "3" "4" "5" "6" "7" "8"} level)
      (styled-heading? paragraph))))

(defn- render-command [args]
  (let [proc (p/process args {:out :string :err :string})
        result (deref proc 120000 ::timeout)]
    (when (= ::timeout result)
      (p/destroy-tree proc)
      (throw (ex-info "section render timed out after 120 seconds" {})))
    (when-not (zero? (:exit result))
      (throw (ex-info (str "section render failed: " (:err result)) {:exit (:exit result)})))
    (:out result)))

(defn- rendered-text [path]
  (doseq [tool ["soffice" "pdftotext"]]
    (when-not (fs/which tool)
      (throw (ex-info (str "sections needs " tool " on PATH") {}))))
  (let [tmp (fs/create-temp-dir {:prefix "docx-sections-"})
        input (fs/path tmp "input.docx")
        pdf (fs/path tmp "input.pdf")
        profile (str (.toUri (fs/absolutize (fs/path tmp "profile"))))]
    (try
      (fs/copy path input)
      (render-command ["soffice" (str "-env:UserInstallation=" profile)
                       "--headless" "--norestore" "--convert-to" "pdf:writer_pdf_Export"
                       "--outdir" (str tmp) (str input)])
      ;; soffice can exit zero without creating a PDF (for example, on an import error).
      (when-not (fs/exists? pdf)
        (throw (ex-info "LibreOffice did not create the section render PDF" {})))
      (render-command ["pdftotext" "-layout" (str pdf) "-"])
      (finally (fs/delete-tree tmp)))))

(defn- heading-matches [rendered heading]
  (let [;; Literal numbers in heading text and automatic list labels share the same form.
        title (str/replace heading #"^\d+(?:\.\d+)*\.?\s+" "")
        words (str/split (str/trim title) #"\s+")
        pattern (re-pattern (str "(?m)^\\h*([0-9]+(?:\\.[0-9]+)*)(?:\\.)?\\h+"
                                 (str/join "\\s+" (map java.util.regex.Pattern/quote words)) "\\h*$"))
        matcher (re-matcher pattern rendered)]
    (loop [out []]
      (if (.find matcher)
        (recur (conj out {:position (.start matcher) :number (.group matcher 1)}))
        out))))

(defn- section-contexts [headings rendered]
  (let [candidates (mapv (fn [heading]
                           (assoc heading :matches
                                  (vec (distinct (mapcat #(heading-matches rendered %)
                                                        (:render-titles heading)))))) headings)
        title-counts (frequencies (map :heading headings))
        anchors (filterv #(and (= 1 (title-counts (:heading %)))
                               (= 1 (count (:matches %)))) candidates)]
    (into {}
      (for [{:keys [index heading matches]} candidates
            :let [before (last (filter #(< (:index %) index) anchors))
                  after (first (filter #(> (:index %) index) anchors))
                  lo (get-in before [:matches 0 :position] -1)
                  hi (get-in after [:matches 0 :position] (count rendered))
                  ;; Unique surrounding headings bound repeated titles without choosing
                  ;; the first textual occurrence or trusting a table of contents.
                  matches (filterv #(< lo (:position %) hi) matches)]]
        [index (merge {:heading heading :section nil}
                      (case (count matches)
                        0 {:section_status "unresolved"}
                        1 {:section (-> matches first :number) :section_status "matched"}
                        {:section_status "ambiguous"}))]))))

(defn sections
  "Changes with :heading from explicit or inherited outline levels (0-8) or heading styles,
  plus :section from a private LibreOffice PDF render. :section_status is matched,
  unresolved, ambiguous, no-heading or not-applicable (headers/footers). Repeated titles
  are bounded by unique surrounding headings; only one decimal-number match in that
  interval is reported. The input package is not written."
  [path]
  (let [entries (d/read-entries path)
        styles (into {} (map (fn [s] [(get-in s [:attrs (w "styleId")]) s])
                             (d/children (d/parse-entry entries "word/styles.xml") (w "style"))))
        root (d/parse-entry entries "word/document.xml")
        headings (keep-indexed (fn [i p]
                                 (when (section-heading? styles p)
                                   (let [ps (pieces p)
                                         title (or (not-empty (piece-text ps #{:same :ins}))
                                                   (not-empty (piece-text ps #{:same :del})))]
                                     (when title
                                       {:index i :heading title
                                        ;; LibreOffice's review view can show both sides
                                        ;; of a replacement within the heading.
                                        :render-titles (distinct (remove str/blank?
                                                                  [title (piece-text ps #{:same :del})
                                                                   (piece-text ps #{:same :ins :del})]))}))))
                               (d/descendants root (w "p")))
        contexts (when (and (seq headings) (seq (part-changes root nil)))
                   (section-contexts headings (rendered-text path)))
        body (part-changes root nil (fn [i _ _] (get contexts i)))
        other (mapcat #(part-changes (d/parse-entry entries %) %)
                      (d/part-names entries #"word/(header|footer)\d*\.xml"))]
    (vec (concat (map #(merge {:section nil :section_status "no-heading"} %) body)
                 (map #(assoc % :section nil :section_status "not-applicable") other)))))

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

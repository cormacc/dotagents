#!/usr/bin/env bb
;; Review and edit a Word .docx. See ../SKILL.md.
;;
;; Usage:
;;   docx.clj inspect DOCX          JSON: header and footer text, tracked-change counts,
;;                                  version history rows
;;   docx.clj changes DOCX          JSON: each paragraph with tracked changes, with its
;;                                  heading and its text before and after
;;   docx.clj comments DOCX         JSON: each comment with author, date, text, heading and
;;                                  the anchored text before and after
;;   docx.clj compare OLD NEW OUT   Write OUT, a .docx with OLD -> NEW as tracked changes
;;                                  (needs LibreOffice)
;;   docx.clj edit IN SCRIPT OUT --author NAME --initials XX [--date ISO] [--no-tracking]
;;                                  Apply an edit script (JSON or EDN) as tracked changes
;;                                  with comments
;;   docx.clj clean IN OUT [--keep-tracking] [--no-verify]
;;                                  Accept all changes and remove all comments; checked
;;                                  against pandoc's accept view when pandoc is installed
;;
;; Exit codes: 0 success, 1 usage or input error, 2 an edit did not match, 3 the clean copy
;; differs from pandoc's accept view.
(require '[babashka.classpath :as cp]
         '[babashka.fs :as fs])
(cp/add-classpath (str (fs/path (fs/parent (fs/absolutize *file*)) "src")))
(require '[babashka.cli :as cli]
         '[babashka.process :as p]
         '[cheshire.core :as json]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[clojure.walk]
         '[docx.clean :as clean]
         '[docx.core :as d]
         '[docx.edit :as e]
         '[docx.review :as review])

(defn- out! [x] (println (json/generate-string x {:pretty true})))
(defn- usage [msg] (throw (ex-info msg {::exit 1})))

(defn- read-script
  "Edit script from a .json or .edn file; other names are tried as JSON, then EDN. Cheshire
  parses arrays lazily, so the JSON result is realised inside the try."
  [path]
  (let [s (slurp path)
        json! #(clojure.walk/postwalk identity (json/parse-string s true))]
    (cond
      (str/ends-with? (str/lower-case path) ".edn") (edn/read-string s)
      (str/ends-with? (str/lower-case path) ".json") (json!)
      :else (try (json!) (catch Exception _ (edn/read-string s))))))

(defn- pandoc-lines
  "Paragraph lines of pandoc's plain output, with list markers removed and marker-only lines
  dropped. Pandoc does not apply a deleted paragraph mark: it keeps the paragraph as an
  empty list item and numbers the list around it, where Word removes the paragraph. Without
  the markers, the comparison checks the text of every paragraph and nothing else."
  [path mode]
  (->> (:out (p/shell {:out :string} "pandoc" (str "--track-changes=" mode) "-t" "plain" "--wrap=none" (str path)))
       str/split-lines
       (map #(str/trim (str/replace % #"^\s*(?:[-*+]|\(?\d+[.)]|\(?[a-zA-Z][.)])\s+" "")))
       (remove #(or (str/blank? %) (re-matches #"[-*+]|\(?\d+[.)]|\(?[a-zA-Z][.)]" %)))
       vec))

(defn- verify-clean
  "Compare the clean copy's text with pandoc's accept view of the input, paragraph by
  paragraph, ignoring whitespace: where a deleted paragraph mark merges two paragraphs,
  pandoc inserts a space and Word does not."
  [in out]
  (if-not (fs/which "pandoc")
    {:verified "skipped: pandoc not found"}
    (let [squash (fn [lines] (mapv #(str/replace % #"\s+" "") lines))
          want (squash (pandoc-lines in "accept")) got (squash (pandoc-lines out "accept"))]
      (if (= want got)
        {:verified true :paragraphs (count got)}
        (let [i (first (keep-indexed (fn [i [a b]] (when (not= a b) i)) (map vector (concat want [nil]) (concat got [nil]))))]
          (throw (ex-info (str "the clean copy differs from pandoc's accept view at paragraph " i)
                          {::exit 3 :expected (get want i) :actual (get got i)})))))))

(defn- now [] (str (.truncatedTo (java.time.Instant/now) java.time.temporal.ChronoUnit/SECONDS)))

(defn- run [[cmd & args] opts]
  (let [arg (fn [i what] (or (nth args i nil) (usage (str cmd " needs " what))))]
    (case cmd
      "inspect" (review/inspect (arg 0 "DOCX"))
      "changes" (review/changes (arg 0 "DOCX"))
      "comments" (review/comments (arg 0 "DOCX"))
      "compare" {:ok true :out (review/compare-docx (arg 0 "OLD") (arg 1 "NEW") (arg 2 "OUT"))}
      "edit" (let [in (arg 0 "IN") script (read-script (arg 1 "SCRIPT")) out (arg 2 "OUT")
                   author (or (:author opts) (usage "edit needs --author"))
                   initials (or (:initials opts) (str/join (map first (str/split author #"\s+"))))
                   {:keys [entries report]} (e/apply-script (d/read-entries in) script
                                                            {:author author :initials initials
                                                             :date (or (:date opts) (now))
                                                             :no-tracking? (:no-tracking opts)})]
               (d/write-docx out entries)
               {:out out :edits report})
      "clean" (let [in (arg 0 "IN") out (arg 1 "OUT")]
                (d/write-docx out (clean/clean-entries (d/read-entries in) :keep-tracking? (:keep-tracking opts)))
                (merge {:out out}
                       (if (:no-verify opts) {:verified "skipped: --no-verify"} (verify-clean in out))
                       {:inspect (select-keys (review/inspect out) [:tracked_changes])}))
      (usage "usage: docx.clj inspect | changes | comments | compare | edit | clean (see the header of docx.clj)"))))

(let [{:keys [args opts]} (cli/parse-args *command-line-args*
                                          {:coerce {:keep-tracking :boolean :no-verify :boolean :no-tracking :boolean
                                                    ;; Keep these as text.
                                                    :author :string :initials :string :date :string}})]
  (try
    (out! (run args opts))
    (catch Exception ex
      (let [data (ex-data ex)
            code (or (::exit data) (when (= :match (:docx/error data)) 2) 1)]
        (binding [*out* *err*]
          (println "docx:" (ex-message ex))
          (when (:expected data) (println "  expected:" (pr-str (:expected data)) "\n  actual:  " (pr-str (:actual data)))))
        (System/exit code)))))

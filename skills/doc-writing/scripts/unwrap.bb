#!/usr/bin/env bb

;; Unwrap hard-wrapped prose in an Org or Markdown file: one line for each
;; paragraph and each list item.
;;
;; Usage: unwrap.bb [--format org|md] <in-file> [<out-file>]
;;   Without <out-file>, write the result to stdout. The format defaults from
;;   the file extension (.org, or .md/.markdown).
;;
;; The script keeps blocks, tables, headings, drawers, keyword and comment
;; lines, quotes and HTML exactly as they are. When it is not sure that a line
;; is prose, it keeps the line: a missed unwrap is safe, a wrong join is not.
;;
;; Guard: before it writes, the script checks that the output has the same
;; words in the same order as the input (whitespace ignored), and that every
;; kept line is still present. If not, it writes nothing and exits 1.
;;
;; Exit codes: 0 done, 1 guard failure, 2 usage error.

(require '[clojure.string :as str])

(defn indent [line] (count (re-find #"^[ \t]*" line)))

(def ^:private org-item #"^(\s*)(?:[-+]|\d+[.)])\s.*|^(\s+)\*\s.*")
(def ^:private md-item #"^(\s*)(?:[-+*]|\d+[.)])\s.*")

(defn- org-kind
  "Classify one Org line outside blocks and drawers."
  [line]
  (cond
    (str/blank? line) :blank
    (re-find #"(?i)^\s*#\+begin_" line) :block-start
    (re-find #"(?i)^\s*\\begin\{" line) :latex-start
    (re-find #"^\s*:END:\s*$" line) :keep
    (re-find #"^\s*:[A-Za-z0-9_-]+:\s*$" line) :drawer-start
    (re-find #"^\*+\s" line) :keep                      ; heading
    (re-find #"^\s*(?:SCHEDULED|DEADLINE|CLOSED):" line) :keep
    (re-find #"^\s*#(?:\+|\s|$)" line) :keep            ; keyword or comment
    (re-find #"^\s*:(?:\s|$)" line) :keep               ; fixed-width
    (re-find #"^\s*[|+]" line) :keep                    ; table, table.el
    (re-find #"^\s*-{5,}\s*$" line) :keep               ; horizontal rule
    (re-find #"^\[fn:" line) :paragraph-start
    (re-matches org-item line) :item
    :else :text))

(defn- md-kind
  "Classify one Markdown line outside fences and front matter."
  [line open?]
  (cond
    (str/blank? line) :blank
    (re-find #"^\s{0,3}(?:`{3,}|~{3,})" line) :fence-start
    (re-find #"^\s{0,3}#{1,6}(?:\s|$)" line) :keep      ; ATX heading
    (re-find #"^\s{0,3}(?:(?:-\s*){3,}|(?:\*\s*){3,}|(?:_\s*){3,}|=+\s*)$" line) :keep ; rule, setext
    (str/includes? line "|") :keep                      ; table row, or unsure
    (re-find #"^\s*>" line) :keep                       ; block quote
    (re-find #"^\s*<" line) :keep                       ; HTML
    (re-find #"^\s{0,3}\[[^\]]+\]:\s" line) :keep       ; link reference definition
    (and (not open?) (>= (indent line) 4)) :keep        ; indented code, or unsure
    (re-matches md-item line) :item
    :else :text))

(defn- hard-break?
  "True when the line ends with an explicit line break."
  [format line]
  (if (= format :org)
    (str/ends-with? line "\\\\")
    (or (str/ends-with? line "  ") (str/ends-with? line "\\"))))

(defn unwrap
  "Return the lines of `lines` with each paragraph and list item on one line."
  [format lines]
  (loop [[line & more :as ls] lines
         mode nil          ; nil, or [:until regex] for verbatim regions
         out []
         open nil]         ; {:text ... :item-indent n-or-nil}
    (let [flush (fn [out open] (cond-> out open (conj (:text open))))]
      (cond
        (empty? ls) (flush out open)

        mode
        (recur more (when-not (re-find mode line) mode) (conj out line) nil)

        :else
        (let [kind (if (= format :org) (org-kind line) (md-kind line (some? open)))
              out' (flush out open)]
          (case kind
            :block-start (recur more #"(?i)^\s*#\+end_" (conj out' line) nil)
            :latex-start (recur more #"(?i)^\s*\\end\{" (conj out' line) nil)
            :drawer-start (recur more #"^\s*:END:\s*$" (conj out' line) nil)
            :fence-start (let [fence (re-find #"`{3,}|~{3,}" line)]
                           (recur more (re-pattern (str "^\\s{0,3}" (java.util.regex.Pattern/quote fence)))
                                  (conj out' line) nil))
            (:blank :keep) (recur more nil (conj out' line) nil)
            (:item :paragraph-start)
            (let [o {:text line :item-indent (when (= kind :item) (indent line))}]
              (if (hard-break? format line)
                (recur more nil (conj out' line) nil)
                (recur more nil out' o)))
            :text
            (let [continues? (and open (or (nil? (:item-indent open))
                                           (> (indent line) (:item-indent open))))
                  o (if continues?
                      (update open :text str " " (str/triml line))
                      {:text line :item-indent nil})
                  out* (if continues? out out')]
              (if (hard-break? format line)
                (recur more nil (conj out* (:text o)) nil)
                (recur more nil out* o)))))))))

(defn front-matter-split
  "Split leading Markdown front matter from the rest: [kept-lines rest-lines]."
  [lines]
  (if (= "---" (first lines))
    (let [n (some (fn [i] (when (#{"---" "..."} (nth lines i)) i)) (range 1 (count lines)))]
      (if n [(subvec (vec lines) 0 (inc n)) (drop (inc n) lines)] [[] lines]))
    [[] lines]))

(defn words [text] (str/split (str/trim text) #"\s+"))

(defn guard
  "Return nil when `out-lines` keeps the words of `in-lines` and every kept
  input line, or a message describing the first problem."
  [in-lines out-lines]
  (let [in-words (words (str/join "\n" in-lines))
        out-words (words (str/join "\n" out-lines))]
    (cond
      (not= in-words out-words)
      (let [i (or (first (keep-indexed (fn [i [a b]] (when (not= a b) i)) (map vector in-words out-words)))
                  (min (count in-words) (count out-words)))]
        (format "words differ at word %d: input %s, output %s"
                (inc i) (pr-str (get in-words i)) (pr-str (get out-words i))))

      (> (count out-lines) (count in-lines))
      (format "output has more lines (%d) than input (%d)" (count out-lines) (count in-lines)))))

(defn run
  "Unwrap `text` in `format`. Return {:text ... :error ...}."
  [format text]
  (let [lines (str/split-lines text)
        [front body] (if (= format :md) (front-matter-split lines) [[] lines])
        result (into (vec front) (unwrap format body))]
    (if-let [problem (guard lines result)]
      {:error problem}
      {:text (str (str/join "\n" result) (when (str/ends-with? text "\n") "\n"))
       :lines-in (count lines)
       :lines-out (count result)})))

(defn- format-of [path explicit]
  (or (some-> explicit keyword)
      (cond (str/ends-with? path ".org") :org
            (re-find #"\.(?:md|markdown)$" path) :md)))

(defn -main [& args]
  (let [[fmt args] (if (= "--format" (first args)) [(second args) (drop 2 args)] [nil args])
        [in out] args
        file-format (when in (format-of in fmt))]
    (when (or (nil? in) (not (#{:org :md} file-format)) (> (count args) 2))
      (binding [*out* *err*]
        (println "Usage: unwrap.bb [--format org|md] <in-file> [<out-file>]"))
      (System/exit 2))
    (let [{:keys [text error lines-in lines-out]} (run file-format (slurp in))]
      (if error
        (do (binding [*out* *err*] (println (str "unwrap: guard failed, nothing written: " error)))
            (System/exit 1))
        (if out
          (do (spit out text)
              (binding [*out* *err*] (println (format "unwrap: %s -> %s, %d -> %d lines" in out lines-in lines-out))))
          (print text))))))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))

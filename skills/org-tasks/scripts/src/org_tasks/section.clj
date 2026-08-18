(ns org-tasks.section
  "Pure org-file primitive: extract a single level-1 section.

  Returns the section's heading line verbatim plus the body up to (but
  not including) the next column-0 `* ` heading, with nested `**`/`***`
  subheadings preserved.

  Source-block (`#+BEGIN_<kind>` / `#+END_<kind>`) regions are tracked
  so literal `* ` lines inside example/src/quote blocks do not
  terminate the slice early.

  Heading match is case-insensitive and tolerates a trailing org tag
  suffix on the heading line (e.g. `* Summary :memory:`)."
  (:require [clojure.string :as str]
            [org-tasks.parser :as parser]
            [org-tasks.parser.lines :as lines]))

(def default-section "Summary")

(def ^:private level-1-heading-re #"^\* (.+?)\s*$")
(defn- parse-level-1-heading-text
  "Return the heading's item text with trailing `:tags:` stripped, or
  nil if not a column-0 single-asterisk heading."
  [^String line]
  (when-let [m (re-matches level-1-heading-re line)]
    (str/trim (first (parser/strip-trailing-task-tags (m 1) true)))))

(defn list-sections
  "Return top-level section names in `content` in source order.
  Source-block contents are not structural headings."
  [^String content]
  (loop [lines (str/split content #"\n" -1)
         block-kind nil
         sections []]
    (if (empty? lines)
      sections
      (let [line (first lines)
            in-block? (some? block-kind)
            next-block-kind (lines/next-block-kind block-kind line)
            section (when-not in-block? (parse-level-1-heading-text line))]
        (recur (rest lines) next-block-kind
               (cond-> sections section (conj section)))))))

(defn read-section
  "Extract a level-1 section from `content`.

  Returns either:
    {:found true  :heading <line> :body <slice>}
    {:found false :section <requested>}

  The body slice ends at the next column-0 `* ` heading (exclusive)
  or at EOF. Source-block regions shield their contents from heading
  scanning."
  ([^String content] (read-section content default-section))
  ([^String content section]
   (let [requested (let [s (str/trim (or section ""))]
                     (if (seq s) s default-section))
         target    (str/lower-case requested)
         lines     (str/split content #"\n" -1)]
     (loop [i 0
            block-kind nil
            match-start -1
            match-heading nil]
       (if (>= i (count lines))
         (if (neg? match-start)
           {:found false :section requested}
           {:found true
            :heading match-heading
            :body    (str/join "\n" (subvec lines (inc match-start)))})

         (let [line (nth lines i)
               in-block? (some? block-kind)
               next-block-kind (lines/next-block-kind block-kind line)]
           (if in-block?
             (recur (inc i) next-block-kind match-start match-heading)
             (if-let [heading-text (parse-level-1-heading-text line)]
               (cond
                 (neg? match-start)
                 (if (= (str/lower-case heading-text) target)
                   (recur (inc i) next-block-kind i line)
                   (recur (inc i) next-block-kind match-start match-heading))

                 :else
                 {:found true
                  :heading match-heading
                  :body    (str/join "\n" (subvec lines (inc match-start) i))})
               (recur (inc i) next-block-kind match-start match-heading)))))))))

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
  (:require [clojure.string :as str]))

(def default-section "Summary")

(def ^:private level-1-heading-re #"^\* (.+?)\s*$")
(def ^:private trailing-tags-re #"^(.*?)\s+(:[A-Za-z0-9_@#%:-]+:)\s*$")
(def ^:private block-open-re #"(?i)^\s*#\+BEGIN_(\w+)\b")
(def ^:private block-close-re #"(?i)^\s*#\+END_(\w+)\s*$")

(defn- parse-level-1-heading-text
  "Return the heading's item text with trailing `:tags:` stripped, or
  nil if not a column-0 single-asterisk heading."
  [^String line]
  (when-let [m (re-matches level-1-heading-re line)]
    (let [raw (m 1)]
      (str/trim
        (if-let [tm (re-matches trailing-tags-re raw)]
          (tm 1)
          raw)))))

(defn list-sections
  "Return top-level section names in `content` in source order."
  [^String content]
  (->> (str/split content #"\n" -1)
       (keep parse-level-1-heading-text)
       vec))

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
            in-block? false
            block-kind ""
            match-start -1
            match-heading nil]
       (if (>= i (count lines))
         (cond
           (neg? match-start)
           {:found false :section requested}

           :else
           {:found true
            :heading match-heading
            :body    (str/join "\n" (subvec lines (inc match-start)))})

         (let [line (nth lines i)]
           (cond
             ;; Inside a #+BEGIN_<kind> block — shield from heading scan
             in-block?
             (let [close (re-find block-close-re line)]
               (if (and close (= (str/lower-case (second close)) block-kind))
                 (recur (inc i) false "" match-start match-heading)
                 (recur (inc i) true block-kind match-start match-heading)))

             ;; Block open
             (re-find block-open-re line)
             (let [m (re-find block-open-re line)]
               (recur (inc i) true (str/lower-case (second m))
                      match-start match-heading))

             ;; Skip lines that aren't column-0 single-asterisk headings
             (not (str/starts-with? line "* "))
             (recur (inc i) false "" match-start match-heading)

             :else
             (if-let [heading-text (parse-level-1-heading-text line)]
               (cond
                 ;; We don't have a match yet — see if this is it.
                 (neg? match-start)
                 (if (= (str/lower-case heading-text) target)
                   (recur (inc i) false "" i line)
                   (recur (inc i) false "" match-start match-heading))

                 ;; Already matched — this is the next level-1 heading,
                 ;; so the section body ends here.
                 :else
                 {:found true
                  :heading match-heading
                  :body    (str/join "\n" (subvec lines (inc match-start) i))})

               (recur (inc i) false "" match-start match-heading)))))))))

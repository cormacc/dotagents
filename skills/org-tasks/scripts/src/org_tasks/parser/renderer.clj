(ns org-tasks.parser.renderer
  "Canonical serializers for protocol task trees."
  (:require [clojure.string :as str]
            [org-tasks.parser.lines :as lines]
            [org-tasks.parser.scanner :as scanner]))

(defn- serialize-task-lines [task]
  (let [out (volatile! [])
        write (fn write [t]
                (let [stars (apply str (repeat (:level t) "*"))
                      prio (if (:priority t) (str " [#" (:priority t) "]") "")
                      tags (if (seq (:tags t))
                             (str " :" (str/join ":" (:tags t)) ":")
                             "")
                      header (str stars " " (:status t) prio " " (:summary t) tags)]
                  (vswap! out conj header)
                  (when (:closed t) (vswap! out conj (str "CLOSED: [" (:closed t) "]")))
                  (when (seq (:property-lines t))
                    (vswap! out conj ":PROPERTIES:")
                    (doseq [line (:property-lines t)] (vswap! out conj line))
                    (vswap! out conj ":END:"))
                  (when (seq (:logbook-lines t))
                    (vswap! out conj ":LOGBOOK:")
                    (doseq [line (:logbook-lines t)] (vswap! out conj line))
                    (vswap! out conj ":END:"))
                  (when (:import-path t)
                    (vswap! out conj (str "#+IMPORT: " (or (:import-raw t) (:import-path t)))))
                  (when (seq (:description t)) (vswap! out conj (:description t)))
                  (doseq [child (:children t)] (write child))))]
    (write task)
    @out))

(defn serialize-tasks
  "Serialize a sequence of root tasks to org text. Top-level siblings
  are separated by a single blank line for readability."
  [tasks]
  (let [parts (map-indexed
               (fn [i task]
                 (let [block (str/join "\n" (serialize-task-lines task))]
                   (if (zero? i) block (str "\n" block))))
               tasks)]
    (str (str/join "\n" parts) "\n")))

(defn- task-range [task]
  (let [start (max 0 (dec (or (:line-number task) 0)))
        end (max (inc start) (dec (or (:end-line task) 0)))]
    [start end]))

(defn- serialize-tasks-preserving-file*
  "Replace parsed roots in `original` with task subtrees.

  When `preserve-unmodified?` is true, roots that equal their parsed source
  keep their exact source lines. Omitted roots are removed. New roots append
  with one blank-line separator."
  [^String original tasks preserve-unmodified?]
  (let [content-lines (vec (str/split original #"\n" -1))
        original-roots (:tasks (scanner/parse-tasks original))
        supplied-by-line (reduce (fn [m task]
                                   (if (pos? (or (:line-number task) 0))
                                     (assoc m (:line-number task) task)
                                     m))
                                 {} tasks)
        new-roots (filterv #(not (pos? (or (:line-number %) 0))) tasks)
        line-total (count content-lines)
        trailing-blanks-in-range
        (fn [^long start ^long end]
          (let [effective-end (if (and (= end line-total) (pos? line-total)
                                       (= "" (nth content-lines (dec end))))
                                (dec end) end)]
            (loop [i (dec effective-end) n 0]
              (if (and (>= i start) (= "" (nth content-lines i)))
                (recur (dec i) (inc n))
                n))))
        edits (mapv (fn [original-task]
                      (let [[start end] (task-range original-task)
                            supplied (get supplied-by-line (:line-number original-task))
                            block (cond
                                    (nil? supplied) []
                                    (and preserve-unmodified?
                                         (= (serialize-task-lines original-task)
                                            (serialize-task-lines supplied)))
                                    (subvec content-lines start end)
                                    :else (serialize-task-lines supplied))
                            blanks (if supplied (trailing-blanks-in-range start end) 0)]
                        {:start start :end end
                         :replacement (into block (repeat blanks ""))}))
                    original-roots)
        after-edits (reduce (fn [lines {:keys [start end replacement]}]
                              (vec (concat (subvec lines 0 start) replacement (subvec lines end))))
                            content-lines
                            (sort-by :start > edits))
        with-new (reduce (fn [lines task]
                           (let [trimmed (loop [v lines]
                                           (if (and (seq v) (= "" (peek v))) (recur (pop v)) v))
                                 prefix (if (seq trimmed) [""] [])]
                             (vec (concat trimmed prefix (serialize-task-lines task)))))
                         after-edits new-roots)
        joined (str/join "\n" with-new)]
    (str (str/replace joined #"\n*$" "") "\n")))

(defn serialize-tasks-preserving-file
  "Re-emit `original` content with each parsed root task replaced by
  the serialized form of its supplied counterpart (matched by
  `:line-number`).

  - Existing root tasks omitted from `tasks` are removed (archive flow).
  - New root tasks (with `:line-number` ≤ 0) are appended at end with
    one blank-line separator.

  Non-task org content (preamble, category headings, prose between
  task subtrees) is preserved verbatim."
  [^String original tasks]
  (serialize-tasks-preserving-file* original tasks false))

(defn- task-heading-line [task]
  (let [stars (apply str (repeat (:level task) "*"))
        prio (if (:priority task) (str " [#" (:priority task) "]") "")
        tags (if (seq (:tags task)) (str " :" (str/join ":" (:tags task)) ":") "")]
    (str stars " " (:status task) prio " " (:summary task) tags)))

(def ^:private properties-start-re #"(?i)^\s*:PROPERTIES:\s*$")
(def ^:private logbook-start-re #"(?i)^\s*:LOGBOOK:\s*$")
(def ^:private drawer-end-re #"(?i)^\s*:END:\s*$")
(def ^:private closed-re #"^\s*CLOSED:\s*\[[^\]]+\]\s*$")
(def ^:private import-re #"(?i)^\s*#\+IMPORT:\s*.*$")

(defn- find-unshielded-line [content-lines start end pred]
  (loop [i start block-kind nil]
    (when (< i end)
      (let [line (nth content-lines i)
            next-block-kind (lines/next-block-kind block-kind line)]
        (if (and (nil? block-kind) (pred line))
          i
          (recur (inc i) next-block-kind))))))

(defn- drawer-range [content-lines start end drawer-start-re]
  (when-let [drawer-start (find-unshielded-line content-lines start end
                                                  #(re-matches drawer-start-re %))]
    (loop [i (inc drawer-start)]
      (cond
        (>= i end) [drawer-start end]
        (re-matches drawer-end-re (nth content-lines i)) [drawer-start (inc i)]
        :else (recur (inc i))))))

(defn- all-task-nodes [tasks]
  (mapcat (fn walk [task]
            (cons task (mapcat walk (:children task))))
          tasks))

(defn- own-range [task]
  (let [[start end] (task-range task)
        child-start (some-> task :children first :line-number dec)]
    [start (or child-start end)]))

(defn- field-changed? [field original-task supplied-task]
  (case field
    :closed (not= (:closed original-task) (:closed supplied-task))
    :properties (not= (:property-lines original-task) (:property-lines supplied-task))
    :logbook (not= (:logbook-lines original-task) (:logbook-lines supplied-task))
    :import (not= (select-keys original-task [:import-path :import-raw])
                  (select-keys supplied-task [:import-path :import-raw]))))

(defn- field-lines [field task]
  (case field
    :closed (when-let [closed (:closed task)] [(str "CLOSED: [" closed "]")])
    :properties (when (seq (:property-lines task))
                  (into [":PROPERTIES:"] (concat (:property-lines task) [":END:"])))
    :logbook (when (seq (:logbook-lines task))
               (into [":LOGBOOK:"] (concat (:logbook-lines task) [":END:"])))
    :import (when-let [path (:import-path task)]
              [(str "#+IMPORT: " (or (:import-raw task) path))])))

(defn- existing-drawer-lines [content-lines field-start field-end field task]
  (let [replacement (field-lines field task)]
    (if (and (#{:properties :logbook} field) (seq replacement))
      (into [(nth content-lines field-start)]
            (concat (subvec replacement 1 (dec (count replacement)))
                    [(nth content-lines (dec field-end))]))
      replacement)))

(defn- field-range [content-lines start end field]
  (case field
    :closed (when-let [index (find-unshielded-line content-lines start end
                                                    #(re-matches closed-re %))]
              [index (inc index)])
    :properties (drawer-range content-lines start end properties-start-re)
    :logbook (drawer-range content-lines start end logbook-start-re)
    :import (when-let [index (find-unshielded-line content-lines start end
                                                    #(re-matches import-re %))]
              [index (inc index)])))

(def ^:private locality-fields [:closed :properties :logbook :import])

(defn- missing-field-insertion-edits [ranges changed-fields supplied-task start]
  (let [insertions (keep (fn [field]
                           (when (and (some #{field} changed-fields)
                                      (nil? (get ranges field)))
                             (when-let [lines (seq (field-lines field supplied-task))]
                               (let [field-index (.indexOf locality-fields field)
                                     next-field (some #(when (get ranges %) %)
                                                      (subvec locality-fields (inc field-index)))
                                     previous-field (some #(when (get ranges %) %)
                                                          (rseq (subvec locality-fields 0 field-index)))
                                     insertion-at (cond
                                                    (= field :closed) (inc start)
                                                    next-field (first (get ranges next-field))
                                                    previous-field (second (get ranges previous-field))
                                                    :else (inc start))]
                                 {:field field :at insertion-at :lines lines}))))
                         locality-fields)]
    (mapv (fn [[at fields]]
            {:start at
             :end at
             :replacement (vec (mapcat :lines
                                       (sort-by #(.indexOf locality-fields (:field %)) fields)))})
          (group-by :at insertions))))

(defn- task-locality-edits [content-lines original-task supplied-task]
  (let [[start end] (own-range original-task)
        ranges (into {} (map (fn [field]
                                [field (field-range content-lines start end field)])
                              locality-fields))
        changed-fields (filterv #(field-changed? % original-task supplied-task) locality-fields)
        insertions (missing-field-insertion-edits ranges changed-fields supplied-task start)
        insertions-by-start (into {} (map (juxt :start :replacement) insertions))
        replacements (vec (keep (fn [field]
                                  (when-let [[field-start field-end] (get ranges field)]
                                    (when (some #{field} changed-fields)
                                      {:start field-start
                                       :end field-end
                                       :replacement (into (get insertions-by-start field-start [])
                                                         (or (existing-drawer-lines content-lines field-start field-end
                                                                                   field supplied-task)
                                                             []))})))
                                locality-fields))
        replacement-starts (set (map :start replacements))
        heading-edit (when (not= (task-heading-line original-task)
                                 (task-heading-line supplied-task))
                       {:start start
                        :end (inc start)
                        :replacement [(task-heading-line supplied-task)]})]
    (cond-> (into replacements
                  (remove #(contains? replacement-starts (:start %)) insertions))
      heading-edit (conj heading-edit))))

(defn serialize-tasks-preserving-file-locality
  "Patch changed heading, lifecycle, and drawer fields while preserving every other task byte.

  In-place mutations use this form. Structural flows fall back to the
  canonical whole-root serializer."
  [^String original tasks]
  (let [content-lines (vec (str/split original #"\n" -1))
        original-roots (:tasks (scanner/parse-tasks original))
        original-by-line (into {} (map (juxt :line-number identity)
                                       (all-task-nodes original-roots)))
        supplied-by-line (into {} (map (juxt :line-number identity)
                                       (all-task-nodes tasks)))]
    (if (not= (set (keys original-by-line)) (set (keys supplied-by-line)))
      (serialize-tasks-preserving-file original tasks)
      (let [edits (mapcat (fn [[line original-task]]
                            (task-locality-edits content-lines original-task
                                                 (get supplied-by-line line)))
                          original-by-line)
            patched (reduce (fn [lines {:keys [start end replacement]}]
                              (vec (concat (subvec lines 0 start) replacement (subvec lines end))))
                            content-lines
                            (sort-by :start > edits))]
        (str/join "\n" patched)))))

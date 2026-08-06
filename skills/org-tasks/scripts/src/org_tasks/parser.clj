(ns org-tasks.parser
  "Stable public facade for the `ot` org parser.

  Owns the stateful task scanner, task-tag operations, and serializers. Its
  public helpers remain available here while focused `org-tasks.parser.*`
  namespaces own timestamps, drawer properties, links, and linked issues."
  (:require [clojure.string :as str]
            [org-tasks.lifecycle :as lifecycle]
            [org-tasks.parser.issues :as issues]
            [org-tasks.parser.links :as links]
            [org-tasks.parser.properties :as properties]
            [org-tasks.parser.timestamps :as timestamps]))

;; ── Stable helper facade ───────────────────────────────────────────

(defmacro ^:private def-facade-alias
  "Define a facade var whose value and public API metadata mirror `target`."
  [name target]
  `(do
     (def ~name ~target)
     (alter-meta! (var ~name) merge
                  (select-keys (meta (var ~target))
                               [:doc :arglists :added :deprecated]))))

(def-facade-alias format-org-timestamp timestamps/format-org-timestamp)
(def-facade-alias format-org-date timestamps/format-org-date)
(def-facade-alias created-log-entry timestamps/created-log-entry)
(def-facade-alias state-log-entry timestamps/state-log-entry)
(def-facade-alias append-created-log timestamps/append-created-log)
(def-facade-alias append-state-log timestamps/append-state-log)

(def-facade-alias get-task-id properties/get-task-id)
(def-facade-alias task-has-id? properties/task-has-id?)
(def-facade-alias get-task-started properties/get-task-started)
(def-facade-alias task-has-started-property? properties/task-has-started-property?)
(def-facade-alias get-drawer-property properties/get-drawer-property)
(def-facade-alias set-drawer-property properties/set-drawer-property)
(def-facade-alias get-drawer-property-values properties/get-drawer-property-values)
(def-facade-alias set-drawer-property-values properties/set-drawer-property-values)
(def-facade-alias parse-blocker properties/parse-blocker)
(def-facade-alias get-task-blockers properties/get-task-blockers)
(def-facade-alias set-task-blockers properties/set-task-blockers)
(def-facade-alias get-task-handoff properties/get-task-handoff)
(def-facade-alias set-task-handoff properties/set-task-handoff)

(def-facade-alias extract-org-link-target links/extract-org-link-target)
(def-facade-alias extract-org-link links/extract-org-link)
(def-facade-alias escape-regex links/escape-regex)
(def-facade-alias get-file-keywords links/get-file-keywords)
(def-facade-alias get-file-keyword links/get-file-keyword)
(def-facade-alias parse-selected-keyword links/parse-selected-keyword)
(def-facade-alias get-plan-parent-ref links/get-plan-parent-ref)
(def-facade-alias get-plan-parent-id links/get-plan-parent-id)
(def-facade-alias rewrite-parent-link-kind links/rewrite-parent-link-kind)
(def-facade-alias parse-link-templates links/parse-link-templates)
(def-facade-alias expand-org-link-target links/expand-org-link-target)

(def-facade-alias get-linked-issues issues/get-linked-issues)
(def-facade-alias set-linked-issues issues/set-linked-issues)

;; ── Regexes ────────────────────────────────────────────────────────

(def ^:private heading-re
  #"^(\*+)\s+(TODO|STARTED|WAITING|DONE|CANCELLED)\s+(?:\[#([A-Z])\]\s+)?(.+)$")
(def ^:private any-heading-re
  #"^(\*+)\s+(.*)$")
(def ^:private properties-start-re
  #"(?i)^\s*:PROPERTIES:\s*$")
(def ^:private logbook-start-re
  #"(?i)^\s*:LOGBOOK:\s*$")
(def ^:private drawer-end-re
  #"(?i)^\s*:END:\s*$")
(def ^:private import-keyword-re
  #"(?i)^\s*#\+IMPORT:\s*(.*?)\s*$")
(def ^:private closed-re
  #"^\s*CLOSED:\s*\[([^\]]+)\]\s*$")
(def ^:private trailing-task-tags-re
  #"\s+:([\w:]+):\s*$")
(def ^:private trailing-section-tags-re
  #"\s+:([A-Za-z0-9_@#%:-]+):\s*$")
(def ^:private tag-token-re
  #"^[A-Za-z0-9_]+$")

;; ── Heading helpers ────────────────────────────────────────────────

(defn strip-trailing-task-tags
  "Strip trailing heading tags, returning `[base-summary, tags-vec]`.

  The one-argument form preserves task-scanner syntax. With `section?` true,
  it accepts the broader historical section-heading tag syntax."
  ([^String text]
   (strip-trailing-task-tags text false))
  ([^String text section?]
   (let [tag-re (if section? trailing-section-tags-re trailing-task-tags-re)]
     (if-let [m (re-find tag-re text)]
       (let [match-str (first m)
             tag-str   (second m)
             base      (subs text 0 (- (count text) (count match-str)))
             tags      (filterv seq (str/split tag-str #":"))]
         [(str/trimr base) tags])
       [(str/trimr text) []]))))

(defn normalise-task-tag
  "Return a canonical Org tag token, or nil when `tag` cannot safely be
  represented in this parser's trailing-heading tag syntax.

  The accepted grammar is ASCII letters, digits, and underscores. Leading and
  trailing whitespace is ignored; a single surrounding `:tag:` pair is
  accepted as the conventional Org spelling and normalised to `tag`."
  [tag]
  (when (string? tag)
    (let [trimmed (str/trim tag)
          token (if-let [[_ inner] (re-matches #"^:([^:]+):$" trimmed)]
                  inner
                  trimmed)]
      (when (re-matches tag-token-re token)
        token))))

(defn add-task-tag
  "Add `tag` to task trailing tags once, preserving existing tag order.
  Throws `:invalid-tag` when the tag does not match [[normalise-task-tag]]."
  [task tag]
  (if-let [token (normalise-task-tag tag)]
    (update task :tags
            (fn [tags]
              (let [tags (vec (or tags []))]
                (if (some #{token} tags) tags (conj tags token)))))
    (throw (ex-info (str "Invalid tag " (pr-str tag)
                         "; expected letters, digits, and underscores")
                    {:code :invalid-tag :tag tag}))))

(defn remove-task-tag
  "Remove `tag` from task trailing tags. Absent tags are a no-op.
  Throws `:invalid-tag` when the tag does not match [[normalise-task-tag]]."
  [task tag]
  (if-let [token (normalise-task-tag tag)]
    (update task :tags
            (fn [tags]
              (filterv #(not= token %) (or tags []))))
    (throw (ex-info (str "Invalid tag " (pr-str tag)
                         "; expected letters, digits, and underscores")
                    {:code :invalid-tag :tag tag}))))

(defn- parse-heading
  "Parse a task heading line. Returns nil for non-task headings."
  [^String line]
  (when-let [m (re-matches heading-re line)]
    (let [[summary tags] (strip-trailing-task-tags (m 4))]
      {:level    (count (m 1))
       :status   (m 2)
       :priority (m 3)
       :summary  summary
       :tags     tags})))

(defn- split-content-lines
  "Split content on `\\n` preserving a trailing empty string when the
  original ended with `\\n`. Mirrors JavaScript's `String#split(\"\\n\")`
  for round-trip fidelity."
  [^String content]
  (str/split content #"\n" -1))

;; ── parse-tasks ────────────────────────────────────────────────────

(defn parse-tasks
  "Parse `content` into `{:tasks [..], :file-imports [..]}`.

  Tasks are plain maps with the fields documented in this namespace's
  docstring. `opts` accepts:

    :source-path                 - absolute path of the file being parsed
    :source-content              - original content (defaults to `content`)
    :effective-source-content    - content with chained setupfiles
                                   prepended (for link template lookup)"
  ([content] (parse-tasks content {}))
  ([^String content {:keys [source-path source-content effective-source-content]}]
   (let [lines (split-content-lines content)
         line-count (count lines)
         source-content (or source-content content)
         effective-source-content (or effective-source-content source-content)
         roots               (volatile! [])
         ;; Stack entries refer to tasks by their **path** in the root
         ;; tree so we can update them via `update-in`/`assoc-in`. Each
         ;; entry is `{:path [...], :level n}`.
         stack               (volatile! [])
         current-path        (volatile! nil)
         description-lines   (volatile! [])
         file-imports        (volatile! [])

         update-current!
         (fn [f & args]
           (when-let [p @current-path]
             (vswap! roots #(apply update-in % p f args))))

         flush-description!
         (fn []
           (when @current-path
             (let [desc (-> (str/join "\n" @description-lines)
                            (str/replace #"^\n+" "")
                            (str/replace #"\n+$" ""))]
               (update-current! assoc :description desc)))
           (vreset! description-lines []))

         close-tasks!
         (fn [level end-line-exclusive]
           (loop []
             (when (and (seq @stack)
                        (>= (:level (peek @stack)) level))
               (let [{p :path} (peek @stack)]
                 (vswap! roots #(update-in % p assoc :end-line end-line-exclusive))
                 (vswap! stack pop)
                 (recur)))))

         new-task!
         (fn [parsed line-1-indexed]
           (let [file-root? (empty? @stack)
                 task {:level                    (:level parsed)
                       :status                   (:status parsed)
                       :priority                 (:priority parsed)
                       :summary                  (:summary parsed)
                       :tags                     (:tags parsed)
                       :description              ""
                       :children                 []
                       :property-lines           []
                       :logbook-lines            []
                       :import-path              nil
                       :import-raw               nil
                       :import-error             nil
                       :import-children          nil
                       :closed                   nil
                       :source-path              source-path
                       :source-content           source-content
                       :effective-source-content effective-source-content
                       :line-number              line-1-indexed
                       :end-line                 (inc line-count)
                       ;; True iff this task was placed at the top
                       ;; level of its source file by `parse-tasks`.
                       ;; Used by `loader/save-source-roots` to find
                       ;; the per-file root set after the graph has
                       ;; been re-assembled via `:import-children`.
                       :file-root?               file-root?}
                 path (if file-root?
                        (do (vswap! roots conj task)
                            [(dec (count @roots))])
                        (let [parent-path (:path (peek @stack))
                              parent      (get-in @roots parent-path)
                              child-idx   (count (:children parent))]
                          (vswap! roots #(update-in % (conj parent-path :children) conj task))
                          (conj parent-path :children child-idx)))]
             (vswap! stack conj {:path path :level (:level parsed)})
             (vreset! current-path path)))

         consume-drawer!
         (fn [start-idx field]
           ;; Collects drawer body lines into `field` on the current
           ;; task. Returns the index AFTER `:END:` (or end-of-content).
           (loop [j start-idx
                  collected []]
             (cond
               (>= j line-count)
               (throw (ex-info
                       (str "Unterminated drawer"
                            (when source-path (str " in " source-path))
                            " starting at line " start-idx)
                       {:code :unterminated-drawer
                        :file source-path
                        :line start-idx}))

               (re-matches drawer-end-re (nth lines j))
               (do (update-current! update field (fnil into []) collected)
                   (inc j))

               :else
               (recur (inc j) (conj collected (nth lines j))))))]

     (loop [i 0]
       (when (< i line-count)
         (let [line (nth lines i)
               heading (parse-heading line)
               any-heading (re-matches any-heading-re line)]
           (cond
             heading
             (do (flush-description!)
                 (close-tasks! (:level heading) (inc i))
                 (new-task! heading (inc i))
                 (recur (inc i)))

             (and @current-path (re-matches closed-re line))
             (let [m (re-matches closed-re line)]
               (update-current! assoc :closed (str/trim (m 1)))
               (recur (inc i)))

             (and @current-path (re-matches properties-start-re line))
             (recur (consume-drawer! (inc i) :property-lines))

             (and @current-path (re-matches logbook-start-re line))
             (recur (consume-drawer! (inc i) :logbook-lines))

             any-heading
             (do (flush-description!)
                 (close-tasks! (count (any-heading 1)) (inc i))
                 (vreset! current-path nil)
                 (recur (inc i)))

             (re-matches import-keyword-re line)
             (let [raw (str/trim ((re-matches import-keyword-re line) 1))
                   link-target (extract-org-link-target raw)
                   path (or link-target raw)]
               (if @current-path
                 (do (update-current! assoc
                                      :import-path path
                                      :import-raw (when link-target raw))
                     (recur (inc i)))
                 (do (when (seq raw) (vswap! file-imports conj path))
                     (recur (inc i)))))

             :else
             (do (when @current-path
                   (vswap! description-lines conj line))
                 (recur (inc i)))))))

     (flush-description!)
     (close-tasks! 1 (inc line-count))

     {:tasks @roots :file-imports @file-imports})))

;; ── Readiness ─────────────────────────────────────────────────────

(defn is-task-ready
  "Return a readiness report `{:ready bool, :gating [{:blocker, :reason}]}`.
  `resolve-task-by-id` is `(fn [id] task-or-nil)`."
  [task resolve-task-by-id]
  (let [gating
        (reduce
         (fn [acc blocker]
           (case (:kind blocker)
             :task
             (let [dep (resolve-task-by-id (:ref blocker))]
               (cond
                 (nil? dep) (conj acc {:blocker blocker :reason :missing-task})
                 (not (lifecycle/closed-statuses (:status dep)))
                  ;; The dependency was resolved; surface its actual open
                  ;; status so callers can distinguish an open imported task
                  ;; from a missing task reference.
                 (conj acc {:blocker blocker :reason (:status dep)})
                 :else acc))
              ;; default: opaque
             (conj acc {:blocker blocker :reason :opaque})))
         []
         (properties/get-task-blockers task))]
    {:ready (empty? gating) :gating gating}))

;; ── Serialization ─────────────────────────────────────────────────

(defn- serialize-task-lines
  "Lines representing a single task subtree (recursive). Excludes any
  top-level inter-task blank-line separator."
  [task]
  (let [out (volatile! [])
        write (fn write [t]
                (let [stars  (apply str (repeat (:level t) "*"))
                      prio   (if (:priority t) (str " [#" (:priority t) "]") "")
                      tags   (if (seq (:tags t))
                               (str " :" (str/join ":" (:tags t)) ":") "")
                      header (str stars " " (:status t) prio " " (:summary t) tags)]
                  (vswap! out conj header)
                  (when (:closed t) (vswap! out conj (str "CLOSED: [" (:closed t) "]")))
                  (when (seq (:property-lines t))
                    (vswap! out conj ":PROPERTIES:")
                    (doseq [l (:property-lines t)] (vswap! out conj l))
                    (vswap! out conj ":END:"))
                  (when (seq (:logbook-lines t))
                    (vswap! out conj ":LOGBOOK:")
                    (doseq [l (:logbook-lines t)] (vswap! out conj l))
                    (vswap! out conj ":END:"))
                  (when (:import-path t)
                    (vswap! out conj
                            (str "#+IMPORT: " (or (:import-raw t) (:import-path t)))))
                  (when (seq (:description t))
                    (vswap! out conj (:description t)))
                  (doseq [c (:children t)] (write c))))]
    (write task)
    @out))

(defn serialize-tasks
  "Serialize a sequence of root tasks to org text. Top-level siblings
  are separated by a single blank line for readability."
  [tasks]
  (let [parts (map-indexed
               (fn [i t]
                 (let [block (str/join "\n" (serialize-task-lines t))]
                   (if (zero? i) block (str "\n" block))))
               tasks)]
    (str (str/join "\n" parts) "\n")))

(defn- task-range
  "1-indexed [start end-exclusive] mapped to 0-indexed [start end-exclusive]
  line array slice."
  [task]
  (let [start (max 0 (dec (or (:line-number task) 0)))
        end   (max (inc start) (dec (or (:end-line task) 0)))]
    [start end]))

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
  (let [lines       (vec (split-content-lines original))
        original-roots (:tasks (parse-tasks original))
        supplied-by-line
        (reduce (fn [m t]
                  (if (pos? (or (:line-number t) 0))
                    (assoc m (:line-number t) t)
                    m))
                {} tasks)
        new-roots   (filterv #(not (pos? (or (:line-number %) 0))) tasks)
        line-total  (count lines)
        trailing-blanks-in-range
        (fn [^long s ^long e]
          ;; Count trailing empty strings inside `[s, e)`, ignoring
          ;; the single end-of-file empty (which represents the
          ;; trailing newline, not a real blank line).
          (let [effective-end (if (and (= e line-total)
                                       (pos? line-total)
                                       (= "" (nth lines (dec e))))
                                (dec e) e)]
            (loop [i (dec effective-end) n 0]
              (if (and (>= i s) (= "" (nth lines i)))
                (recur (dec i) (inc n))
                n))))
        edits       (mapv (fn [orig]
                            (let [[s e] (task-range orig)
                                  supplied (get supplied-by-line (:line-number orig))
                                  block (if supplied (serialize-task-lines supplied) [])
                                  ;; Preserve trailing blank lines from
                                  ;; the original subtree so inter-task
                                  ;; spacing is not eroded on each save.
                                  blanks (if supplied
                                           (trailing-blanks-in-range s e)
                                           0)]
                              {:start s :end e
                               :replacement (into block (repeat blanks ""))}))
                          original-roots)
        ;; Apply bottom-up so earlier offsets remain stable.
        after-edits (reduce
                     (fn [lns {:keys [start end replacement]}]
                       (vec (concat (subvec lns 0 start) replacement (subvec lns end))))
                     lines
                     (sort-by :start > edits))
        with-new    (reduce
                     (fn [lns t]
                       (let [trimmed (loop [v lns]
                                       (if (and (seq v) (= "" (peek v)))
                                         (recur (pop v))
                                         v))
                             prefix  (if (seq trimmed) [""] [])]
                         (vec (concat trimmed prefix (serialize-task-lines t)))))
                     after-edits
                     new-roots)
        joined      (str/join "\n" with-new)]
    (str (str/replace joined #"\n*$" "") "\n")))

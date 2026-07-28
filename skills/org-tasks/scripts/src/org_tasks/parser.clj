(ns org-tasks.parser
  "Org-mode parser/serializer for the `ot` CLI.

  Owns:

    - heading + drawer + LOGBOOK + CLOSED parsing
    - `#+IMPORT:` extraction (task body + file root)
    - file-level `#+KEYWORD:` accessors and `#+LINK:` template parsing
    - typed-link expansion (`plan:`, `jira:`, `task:`, `archive:`, …)
    - serialization preserving non-task org content around task subtrees
    - drawer property accessors (single + multi-valued) and the
      tracker-agnostic `:LINKED_ISSUES:` / `:BLOCKED-BY:` / `:HANDOFF:`
      helpers built on top of them

  Task records are plain maps with kebab-case keys; the CLI's JSON
  layer rewraps them into the camelCase contract documented in
  `skills/org-tasks/scripts/docs/contract.md`."
  (:require [clojure.string :as str]))

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
(def ^:private id-property-re
  #"(?i)^\s*:CUSTOM_ID:\s*(\S+)\s*$")
(def ^:private started-property-re
  #"(?i)^\s*:STARTED:\s*\[([^\]]+)\]\s*$")
(def ^:private closed-re
  #"^\s*CLOSED:\s*\[([^\]]+)\]\s*$")
(def ^:private trailing-tags-re
  #"\s+:([\w:]+):\s*$")
(def ^:private selected-keyword-re
  #"(?im)^#\+SELECTED:\s*(\S+)\s*$")
(def ^:private property-line-re
  #"^\s*:([A-Za-z][A-Za-z0-9_-]*):\s*(.*?)\s*$")
(def ^:private property-or-continuation-line-re
  #"^\s*:([A-Za-z][A-Za-z0-9_-]*)(\+)?:\s*(.*?)\s*$")
(def ^:private org-link-target-re
  #"^\[\[(?:file:)?([^\]]+?)\](?:\[[^\]]*\])?\]$")
(def ^:private org-link-full-re
  #"^\[\[(?:file:)?([^\]]+?)\](?:\[([^\]]*)\])?\]$")

(def ^:private day-abbr
  ["Sun" "Mon" "Tue" "Wed" "Thu" "Fri" "Sat"])

;; ── Org-link helpers ───────────────────────────────────────────────

(defn extract-org-link-target
  "Return the target slot of an org link expression, or nil for
  non-link text. Strips a `file:` prefix on file links."
  [^String value]
  (when value
    (let [trimmed (str/trim value)]
      (when-let [m (re-matches org-link-target-re trimmed)]
        (let [t (str/trim (m 1))]
          (when-not (empty? t) t))))))

(defn extract-org-link
  "Parse an org link expression into `{:target, :description}` or nil."
  [^String value]
  (when value
    (let [trimmed (str/trim value)]
      (when-let [m (re-matches org-link-full-re trimmed)]
        (let [target (str/trim (m 1))
              desc   (some-> (m 2) str/trim)]
          (when (seq target)
            {:target target
             :description (when (and desc (seq desc)) desc)}))))))

;; ── Timestamps ─────────────────────────────────────────────────────

(defn format-org-timestamp
  "Format a `java.time.LocalDateTime` (or `now`) as an org timestamp body,
  e.g. `2026-04-24 Fri 14:30`."
  ([] (format-org-timestamp (java.time.LocalDateTime/now)))
  ([^java.time.LocalDateTime ts]
   (let [y  (.getYear ts)
         mo (.getMonthValue ts)
         d  (.getDayOfMonth ts)
         h  (.getHour ts)
         mi (.getMinute ts)
         ;; java.time.DayOfWeek: MONDAY=1 .. SUNDAY=7. We want Sun=0..Sat=6.
         dow (let [v (.getValue (.getDayOfWeek ts))] (if (= v 7) 0 v))]
     (format "%04d-%02d-%02d %s %02d:%02d" y mo d (nth day-abbr dow) h mi))))

(defn format-org-date
  "Format a `java.time.LocalDate` (or today) as `YYYY-MM-DD Day` for
  `#+DATE:` headers where time-of-day is not meaningful."
  ([] (format-org-date (java.time.LocalDate/now)))
  ([^java.time.LocalDate d]
   (let [y  (.getYear d)
         mo (.getMonthValue d)
         dd (.getDayOfMonth d)
         dow (let [v (.getValue (.getDayOfWeek d))] (if (= v 7) 0 v))]
     (format "%04d-%02d-%02d %s" y mo dd (nth day-abbr dow)))))

(defn created-log-entry [^String timestamp]
  (str "- Created [" timestamp "]"))

(defn state-log-entry [^String new-status ^String old-status ^String timestamp]
  (str "- State \"" new-status "\" from \"" old-status "\" [" timestamp "]"))

;; ── Heading helpers ────────────────────────────────────────────────

(defn- strip-trailing-tags
  "Strip a trailing `:tag1:tag2:` suffix from a heading text body.
  Returns `[base-summary, tags-vec]`."
  [^String text]
  (if-let [m (re-find trailing-tags-re text)]
    (let [match-str (first m)
          tag-str   (second m)
          base      (subs text 0 (- (count text) (count match-str)))
          tags      (filterv seq (str/split tag-str #":"))]
      [(str/trimr base) tags])
    [(str/trimr text) []]))

(defn- parse-heading
  "Parse a task heading line. Returns nil for non-task headings."
  [^String line]
  (when-let [m (re-matches heading-re line)]
    (let [[summary tags] (strip-trailing-tags (m 4))]
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

;; ── Property accessors ────────────────────────────────────────────

(defn get-task-id [task]
  (some (fn [^String line]
          (when-let [m (re-matches id-property-re line)]
            (str/trim (m 1))))
        (:property-lines task)))

(defn task-has-id? [task]
  (some? (get-task-id task)))

(defn get-task-started [task]
  (some (fn [^String line]
          (when-let [m (re-matches started-property-re line)]
            (str/trim (m 1))))
        (:property-lines task)))

(defn task-has-started-property? [task]
  (some? (get-task-started task)))

(defn get-drawer-property
  "Return the value of an arbitrary drawer property by name (case-insensitive)."
  [task ^String name]
  (let [target (str/upper-case name)]
    (some (fn [^String line]
            (when-let [m (re-matches property-line-re line)]
              (when (= target (str/upper-case (m 1)))
                (str/trim (m 2)))))
          (:property-lines task))))

(defn set-drawer-property
  "Set or clear a drawer property. `value` nil removes the line."
  [task ^String name value]
  (let [target (str/upper-case name)
        replaced? (volatile! false)
        new-lines (reduce
                    (fn [acc ^String line]
                      (let [m (re-matches property-line-re line)]
                        (cond
                          (and m (= target (str/upper-case (m 1))))
                          (do (vreset! replaced? true)
                              (if (nil? value) acc
                                  (conj acc (str ":" (m 1) ": " value))))
                          :else (conj acc line))))
                    []
                    (:property-lines task))]
    (assoc task :property-lines
           (if (and (not @replaced?) (some? value))
             (conj new-lines (str ":" name ": " value))
             new-lines))))

(defn get-drawer-property-values
  "Collect all values for `name` and any `name+:` continuation lines
  in declaration order."
  [task ^String name]
  (let [target (str/upper-case name)]
    (reduce (fn [acc ^String line]
              (if-let [m (re-matches property-or-continuation-line-re line)]
                (if (= target (str/upper-case (m 1)))
                  (conj acc (str/trim (m 3)))
                  acc)
                acc))
            []
            (:property-lines task))))

(defn set-drawer-property-values
  "Replace every `:NAME:` / `:NAME+:` line with new values; empty
  `values` clears the property entirely."
  [task ^String name values]
  (let [target  (str/upper-case name)
        stripped (filterv (fn [^String line]
                            (let [m (re-matches property-or-continuation-line-re line)]
                              (not (and m (= target (str/upper-case (m 1)))))))
                          (:property-lines task))
        emitted (map-indexed
                  (fn [i v] (str ":" name (if (zero? i) "" "+") ": " v))
                  values)]
    (assoc task :property-lines (vec (concat stripped emitted)))))

;; ── Blockers / handoff / linked issues ────────────────────────────

(def ^:private full-uuid-re
  #"(?i)^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

(defn parse-blocker
  "Parse a single `:BLOCKED-BY:` token into a structured form.
  Bare full UUIDs are legacy task references; all other bare values stay
  opaque so free-form human blockers never become graph lookups."
  [^String raw]
  (let [trimmed (str/trim raw)]
    (if-let [m (re-matches #"(?i)^(task|url|human|jira):(.*)$" trimmed)]
      {:raw trimmed
       :kind (keyword (str/lower-case (m 1)))
       :ref (str/trim (m 2))}
      {:raw trimmed
       :kind (if (re-matches full-uuid-re trimmed) :task :other)
       :ref trimmed})))

(defn get-task-blockers [task]
  (mapv parse-blocker (get-drawer-property-values task "BLOCKED-BY")))

(defn set-task-blockers
  "Replace blockers. Accepts a seq of raw tokens or `TaskBlocker` maps."
  [task blockers]
  (set-drawer-property-values
    task "BLOCKED-BY"
    (mapv #(if (string? %) % (:raw %)) blockers)))

(def ^:private closed-statuses #{"DONE" "CANCELLED"})

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
                  (not (closed-statuses (:status dep)))
                  (conj acc {:blocker blocker :reason :unresolved-task})
                  :else acc))
              ;; default: opaque
              (conj acc {:blocker blocker :reason :opaque})))
          []
          (get-task-blockers task))]
    {:ready (empty? gating) :gating gating}))

(defn get-task-handoff [task]
  (let [v (get-drawer-property task "HANDOFF")]
    (when (and v (seq v)) v)))

(defn set-task-handoff [task value]
  (set-drawer-property task "HANDOFF" (when (and value (seq value)) value)))

;; ── File-level keyword + #+LINK helpers ────────────────────────────

(defn escape-regex [^String s]
  (str/replace s #"[.*+?^${}()|\[\]\\]" "\\\\$0"))

(defn get-file-keywords
  "Return every value of a file-level `#+KEYWORD:` declaration in
  declaration order (empty when absent). Case-insensitive name match."
  [^String content ^String name]
  (let [re (re-pattern
             (str "(?im)^[\\t ]*#\\+" (escape-regex name)
                  "[\\t ]*:[\\t ]*(.*?)[\\t ]*$"))]
    (->> (re-seq re content)
         (mapv (fn [m] (or (second m) ""))))))

(defn get-file-keyword
  "First value of `#+KEYWORD:` or nil."
  [^String content ^String name]
  (first (get-file-keywords content name)))

(defn parse-selected-keyword
  "Extract the `#+SELECTED:` UUID from TASKS.local.org content, or nil."
  [^String content]
  (some-> (re-find selected-keyword-re content) second str/trim))

;; ── #+PARENT link helpers (change-record parent pointer) ───────────

(defn get-plan-parent-ref
  "Extract the parent task reference from a navigable `#+PARENT:` org
  link. Returns `{:kind, :uuid, :summary}` or nil."
  [^String content]
  (when-let [raw (get-file-keyword content "PARENT")]
    (when-let [link (extract-org-link raw)]
      (when-let [m (re-matches #"(?i)^(task|archive):([^\s#\]]+)$" (:target link))]
        {:kind (keyword (str/lower-case (m 1)))
         :uuid (str/trim (m 2))
         :summary (:description link)}))))

(defn get-plan-parent-id
  "Extract the parent task UUID from a navigable `#+PARENT:` org link."
  [^String content]
  (:uuid (get-plan-parent-ref content)))

(defn rewrite-parent-link-kind
  "Rewrite only the link kind (`task:` ↔ `archive:`) on the `#+PARENT:`
  line referencing `parent-id`. Other matching links elsewhere in the
  file are left untouched."
  [^String content ^String parent-id new-kind]
  (let [parent-line-re #"(?im)^([\t ]*#\+PARENT[\t ]*:[\t ]*)(.*)$"
        link-target-re (re-pattern
                         (str "(\\[\\[)(task|archive):" (escape-regex parent-id)
                              "(\\](?:\\[[^\\]]*\\])?\\])"))
        ;; Track whether any line actually changed.
        changed? (volatile! false)
        lines (str/split-lines content)
        next-lines
        (mapv
          (fn [^String line]
            (if-let [pm (re-matches parent-line-re line)]
              (let [prefix (pm 1)
                    rest-of-line (pm 2)
                    rewritten (str/replace rest-of-line link-target-re
                                           (str "$1" (name new-kind) ":" parent-id "$3"))]
                (when (not= rewritten rest-of-line)
                  (vreset! changed? true))
                (str prefix rewritten))
              line))
          lines)]
    (if @changed?
      (str (str/join "\n" next-lines)
           (when (str/ends-with? content "\n") "\n"))
      content)))

(defn parse-link-templates
  "Parse all `#+LINK: prefix template` declarations in content into a
  map keyed by prefix. First declaration wins (matches Emacs)."
  [^String content]
  (let [re #"(?im)^[\t ]*#\+LINK[\t ]*:[\t ]*(\S+)[\t ]+(.+?)[\t ]*$"]
    (reduce
      (fn [m match]
        (let [prefix   (some-> (second match) str/trim)
              template (some-> (nth match 2) str/trim)]
          (if (and prefix template (seq prefix) (seq template)
                   (not (contains? m prefix)))
            (assoc m prefix template)
            m)))
      {}
      (re-seq re content))))

(defn- url-encode [^String s]
  (java.net.URLEncoder/encode s "UTF-8"))

(defn- resolve-link-template
  "Substitute KEY into TEMPLATE's `%s` placeholder. Keys are
  URL-encoded for URL-shaped templates and left literal for `file:`
  templates."
  [^String template ^String key]
  (let [replacement (if (str/starts-with? template "file:") key (url-encode key))]
    (if (str/includes? template "%s")
      (str/replace template "%s" (str/re-quote-replacement replacement))
      (str template replacement))))

(defn- typed-link-parts
  "Return `{:prefix, :key}` for a typed target like `plan:foo.org`, or
  nil for plain paths and URLs."
  [^String target]
  (when (and target
             (not (re-matches #"(?i)^https?://.*" target)))
    (when-let [m (re-matches #"^([A-Za-z][A-Za-z0-9+.-]*):(.+)$" target)]
      {:prefix (m 1) :key (m 2)})))

(defn expand-org-link-target
  "Expand a typed link target through a `#+LINK:` abbreviation table.

  Returns `{:target string, :from-project-root bool}`. Plain paths,
  `file:` targets, and URLs pass through unchanged.

  The second argument is either a string of org content (templates
  parsed inline) or a pre-parsed template map."
  [^String target content-or-templates]
  (let [result {:target target :from-project-root false}]
    (if (or (nil? target) (empty? target))
      result
      (let [typed (typed-link-parts target)]
        (if (or (nil? typed) (= "file" (:prefix typed)))
          result
          (let [templates (if (string? content-or-templates)
                            (parse-link-templates content-or-templates)
                            (or content-or-templates {}))
                template  (get templates (:prefix typed))]
            (if-not template
              result
              (let [expanded (resolve-link-template template (:key typed))]
                (if (str/starts-with? expanded "file:")
                  {:target (subs expanded (count "file:")) :from-project-root true}
                  {:target expanded :from-project-root false})))))))))

;; ── Linked issues ──────────────────────────────────────────────────

(defn- split-linked-issue-tokens
  "Split a `:LINKED_ISSUES:` value into `[[..]]` blobs and bare tokens
  on whitespace."
  [^String value]
  (loop [i 0
         tokens []]
    (let [n (count value)]
      (cond
        (>= i n) tokens

        (Character/isWhitespace (.charAt value i))
        (recur (inc i) tokens)

        (and (< (+ i 1) n)
             (= \[ (.charAt value i))
             (= \[ (.charAt value (inc i))))
        (let [end (str/index-of value "]]" (+ i 2))]
          (if end
            (recur (+ end 2) (conj tokens (subs value i (+ end 2))))
            (let [j (loop [j i]
                      (if (or (>= j n) (Character/isWhitespace (.charAt value j)))
                        j (recur (inc j))))]
              (recur j (conj tokens (subs value i j))))))

        :else
        (let [j (loop [j i]
                  (if (or (>= j n) (Character/isWhitespace (.charAt value j)))
                    j (recur (inc j))))]
          (recur j (conj tokens (subs value i j))))))))

(defn get-linked-issues
  "Resolve `:LINKED_ISSUES:` for a task. Returns a vector of
  `{:url, :label, :raw-token, :error?}` maps."
  [task content-or-templates]
  (let [value (get-drawer-property task "LINKED_ISSUES")]
    (if-not (and value (seq value))
      []
      (let [templates (if (string? content-or-templates)
                        (parse-link-templates content-or-templates)
                        (or content-or-templates {}))]
        (mapv
          (fn [raw-token]
            (let [link (extract-org-link raw-token)]
              (if-not link
                {:url nil :label raw-token :raw-token raw-token
                 :error "LINKED_ISSUES token is not an org link"}
                (let [typed (typed-link-parts (:target link))]
                  (if typed
                    (let [template (get templates (:prefix typed))]
                      (if-not template
                        {:url nil
                         :label (or (:description link) (:key typed))
                         :raw-token raw-token
                         :error (str "Missing #+LINK declaration for prefix "
                                     (:prefix typed))}
                        {:url (resolve-link-template template (:key typed))
                         :label (or (:description link) (:key typed))
                         :raw-token raw-token}))
                    {:url (:target link)
                     :label (or (:description link) (:target link))
                     :raw-token raw-token})))))
          (split-linked-issue-tokens value))))))

(defn set-linked-issues
  "Replace `:LINKED_ISSUES:` with whitespace-joined tokens. Empty
  collection clears the property."
  [task tokens]
  (if (empty? tokens)
    (set-drawer-property task "LINKED_ISSUES" nil)
    (set-drawer-property task "LINKED_ISSUES" (str/join " " tokens))))

;; ── LOGBOOK helpers ────────────────────────────────────────────────

(defn append-created-log [task ^String timestamp]
  (update task :logbook-lines (fnil conj []) (created-log-entry timestamp)))

(defn append-state-log
  ([task new-status old-status]
   (append-state-log task new-status old-status (format-org-timestamp)))
  ([task new-status old-status timestamp]
   (update task :logbook-lines (fnil conj [])
           (state-log-entry new-status old-status timestamp))))

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

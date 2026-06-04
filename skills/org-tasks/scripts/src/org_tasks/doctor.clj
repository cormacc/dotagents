(ns org-tasks.doctor
  "`/tasks doctor` health-check engine.

  Pure logic over a loaded task graph + optional protocol-file content
  snapshots; no filesystem access. Returns a vector of `Finding` maps:

    {:code     <kw>     ;; canonical code
     :severity :warn | :error
     :message  <str>
     :location {:file, :line, :heading}}

  Codes:
    :duplicate-id
    :broken-import
    :selected-not-found
    :waiting-without-blocker
    :closed-without-timestamp
    :stale-parent-status
    :invalid-task-blocker
    :non-uuid-v4-id
    :patterned-sibling-ids
    :import-child-not-saveable
    :missing-link-template
    :misordered-link-template
    :missing-local-setupfile
    :misordered-setupfile
    :missing-record-section
    :empty-validation-section
    :spec-impact-untouched"
  (:require [clojure.string :as str]
            [org-tasks.parser :as parser]
            [org-tasks.section :as section]))

(def ^:private closed-statuses #{"DONE" "CANCELLED"})
(def ^:private active-child-statuses #{"STARTED" "WAITING"})

(def ^:private uuid-v4-re
  #"^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")

(def ^:private patterned-prefix-threshold
  ;; Two random UUIDv4 values share at most a handful of leading hex
  ;; chars by chance; sibling tasks whose ids agree on 24+ leading
  ;; characters are almost always hand-authored.
  24)

;; ── Task walk ──────────────────────────────────────────────────────

(defn- walk-tasks
  "Depth-first pre-order over `:children` + `:import-children`."
  [tasks]
  (mapcat
    (fn [t]
      (cons t
            (concat (walk-tasks (:children t))
                    (when (:import-children t)
                      (walk-tasks (:import-children t))))))
    tasks))

(defn- child-status-set [task]
  (let [seen (volatile! #{})]
    (letfn [(visit [ts]
              (doseq [t ts]
                (vswap! seen conj (:status t))
                (visit (:children t))
                (when (:import-children t)
                  (visit (:import-children t)))))]
      (visit (:children task))
      (when (:import-children task)
        (visit (:import-children task))))
    @seen))

(defn- location-for [task]
  (cond-> {}
    (:source-path task) (assoc :file (:source-path task))
    (:summary task)     (assoc :heading (:summary task))
    (pos? (or (:line-number task) 0)) (assoc :line (:line-number task))))

(defn- build-id-index
  "Return `{:by-id, :duplicates}` where `:by-id` is `{id -> task}` of
  the first occurrence and `:duplicates` is `{id -> [tasks]}` for
  collisions."
  [tasks]
  (loop [todo (walk-tasks tasks)
         by-id {}
         dups {}]
    (if (empty? todo)
      {:by-id by-id :duplicates dups}
      (let [t (first todo)
            id (parser/get-task-id t)]
        (if-not id
          (recur (rest todo) by-id dups)
          (if-let [existing (get by-id id)]
            (recur (rest todo)
                   by-id
                   (update dups id (fnil conj [existing]) t))
            (recur (rest todo) (assoc by-id id t) dups)))))))

(defn- import-child-source-index
  "Return `{source-path first-import-child}` for every source file that
  is reachable through `:import-children`."
  [tasks]
  (let [out (volatile! {})]
    (letfn [(visit-task [task]
              (doseq [child (:children task)]
                (visit-task child))
              (doseq [child (:import-children task)]
                (when-let [src (:source-path child)]
                  (vswap! out #(if (contains? % src) % (assoc % src child))))
                (visit-task child)))]
      (doseq [task tasks]
        (visit-task task)))
    @out))

(defn- saveable-source-paths
  "Return the set of source files that have at least one parsed file root.
  `loader/save-source-roots` relies on these `:file-root?` tasks as the
  serialization roots for each mutated source file."
  [tasks]
  (->> (walk-tasks tasks)
       (filter :file-root?)
       (keep :source-path)
       set))

;; ── Link template checks ───────────────────────────────────────────

(def ^:private required-setup-links
  {"task"    "file:../../TASKS.org::#%s"
   "archive" "file:../../TASKS.archive.org::#%s"})

(def ^:private required-local-links
  {"task"    "file:TASKS.org::#%s"
   "archive" "file:TASKS.archive.org::#%s"})

(defn- line-number-of
  "Return 1-indexed line of first match, or nil."
  [^String content re]
  (->> (str/split content #"\n" -1)
       (keep-indexed (fn [i line] (when (re-find re line) (inc i))))
       first))

(defn- escape-regex [^String s]
  (str/replace s #"[.*+?^${}()|\[\]\\]" "\\\\$0"))

(defn- check-link-templates [protocol-files]
  (let [out (volatile! [])]
    (when-let [{:keys [path content]} (:setup protocol-files)]
      (let [templates (parser/parse-link-templates content)]
        (doseq [[prefix expected] required-setup-links]
          (when-not (= (get templates prefix) expected)
            (vswap! out conj
                    {:code :missing-link-template
                     :severity :warn
                     :message (str "TASKS.setup.org should declare #+LINK: "
                                   prefix " " expected)
                     :location {:file path}})))))
    (doseq [k [:tasks :archive]
            :let [entry (get protocol-files k)]
            :when entry]
      (let [{:keys [path content]} entry
            templates (parser/parse-link-templates content)
            shared-setup-line (line-number-of
                                content
                                #"(?i)^[\t ]*#\+SETUPFILE[\t ]*:[\t ]*\.?/?TASKS\.setup\.org\s*$")
            local-setup-line  (line-number-of
                                content
                                #"(?i)^[\t ]*#\+SETUPFILE[\t ]*:[\t ]*\.?/?TASKS\.local\.org\s*$")]
        (when (nil? shared-setup-line)
          (vswap! out conj
                  {:code :missing-link-template
                   :severity :warn
                   :message (str path " should declare #+SETUPFILE: ./TASKS.setup.org")
                   :location {:file path}}))
        (cond
          (nil? local-setup-line)
          (vswap! out conj
                  {:code :missing-local-setupfile
                   :severity :warn
                   :message (str path " should declare #+SETUPFILE: ./TASKS.local.org "
                                 "so gitignored overrides flow through")
                   :location {:file path}})

          (and shared-setup-line (> local-setup-line shared-setup-line))
          (vswap! out conj
                  {:code :misordered-setupfile
                   :severity :warn
                   :message "#+SETUPFILE: ./TASKS.local.org must appear before ./TASKS.setup.org so local keywords win"
                   :location {:file path :line local-setup-line}}))
        (let [first-setup-line (or (some->> [shared-setup-line local-setup-line]
                                            (filter some?) seq sort first))]
          (doseq [[prefix expected] required-local-links]
            (let [link-re (re-pattern
                            (str "(?i)^[\\t ]*#\\+LINK[\\t ]*:[\\t ]*"
                                 prefix "[\\t ]+"
                                 (escape-regex expected)
                                 "[\\t ]*$"))
                  link-line (line-number-of content link-re)]
              (cond
                (or (not= (get templates prefix) expected) (nil? link-line))
                (vswap! out conj
                        {:code :missing-link-template
                         :severity :warn
                         :message (str path " should declare #+LINK: " prefix " " expected)
                         :location {:file path}})

                (and first-setup-line (> link-line first-setup-line))
                (vswap! out conj
                        {:code :misordered-link-template
                         :severity :warn
                         :message (str "Local #+LINK: " prefix " override must appear before #+SETUPFILE")
                         :location {:file path :line link-line}})))))))
    @out))

;; ── Change-record spec-impact checks ───────────────────────────────

(defn- truthy-keyword-value? [value]
  (contains? #{"1" "t" "true" "yes" "y" "on"} (some-> value str/trim str/lower-case)))

(defn- source-content-index
  "Return one `{path content}` entry for each parsed source file."
  [tasks]
  (let [out (volatile! {})]
    (doseq [task (walk-tasks tasks)
            :let [src (:source-path task)
                  content (:source-content task)]
            :when (and src content)]
      (vswap! out #(if (contains? % src) % (assoc % src content))))
    @out))

(def ^:private required-record-sections
  ["Intent" "Summary" "Plan" "Implementation" "Validation"])

(defn- spec-impact-aware-record? [content]
  (or (seq (parser/get-file-keywords content "SPEC_IMPACT"))
      (some truthy-keyword-value? (parser/get-file-keywords content "NO_SPEC_IMPACT"))))

(defn- check-record-structure [tasks]
  (let [out (volatile! [])]
    (doseq [[src content] (source-content-index tasks)
            :when (spec-impact-aware-record? content)]
      (let [sections (set (section/list-sections content))]
        (doseq [required required-record-sections
                :when (not (contains? sections required))]
          (vswap! out conj
                  {:code :missing-record-section
                   :severity :warn
                   :message (str "Change-record is missing required * " required " section")
                   :location {:file src}}))
        (let [validation (section/read-section content "Validation")]
          (when (and (:found validation)
                     (str/blank? (:body validation)))
            (vswap! out conj
                    {:code :empty-validation-section
                     :severity :warn
                     :message "Change-record has an empty * Validation section"
                     :location {:file src}})))))
    @out))

(defn- check-spec-impact [tasks changed-paths]
  (when changed-paths
    (let [changed (set changed-paths)
          out (volatile! [])]
      (doseq [[src content] (source-content-index tasks)]
        (let [impacts (->> (parser/get-file-keywords content "SPEC_IMPACT")
                           (map str/trim)
                           (remove str/blank?)
                           distinct)
              opted-out? (some truthy-keyword-value?
                               (parser/get-file-keywords content "NO_SPEC_IMPACT"))]
          (when (and (seq impacts) (not opted-out?))
            (doseq [impact impacts
                    :when (not (contains? changed impact))]
              (vswap! out conj
                      {:code :spec-impact-untouched
                       :severity :warn
                       :message (str "#+SPEC_IMPACT declares " impact
                                     " but that path is not touched in git status")
                       :location {:file src}})))))
      @out)))

;; ── Main entry ─────────────────────────────────────────────────────

(defn run-doctor
  "Top-level doctor entry point. `input` keys:

    :tasks                 - top-level tasks from the loaded graph
    :selected-id           - UUID parsed from TASKS.local.org (or nil)
    :selected-source-path  - path to TASKS.local.org (optional)
    :protocol-files        - {:setup/:tasks/:archive {:path, :content}}
                             for the link-template / setupfile checks
    :changed-paths         - optional set of repo-relative git status paths
                             for SPEC_IMPACT checks"
  [{:keys [tasks selected-id selected-source-path protocol-files changed-paths]}]
  (let [out      (volatile! [])
        link-findings (check-link-templates protocol-files)
        record-findings (check-record-structure tasks)
        spec-findings (check-spec-impact tasks changed-paths)
        _ (vswap! out into link-findings)
        _ (vswap! out into record-findings)
        _ (vswap! out into spec-findings)
        {:keys [by-id duplicates]} (build-id-index tasks)]

    ;; duplicate-id (one finding per occurrence)
    (doseq [[id occurrences] duplicates]
      (doseq [occ occurrences]
        (vswap! out conj
                {:code :duplicate-id
                 :severity :error
                 :message (str "Duplicate :CUSTOM_ID: " id
                               " (" (count occurrences) " occurrences)")
                 :location (location-for occ)})))

    ;; selected-not-found
    (when (and selected-id (not (contains? by-id selected-id)))
      (vswap! out conj
              {:code :selected-not-found
               :severity :error
               :message (str "TASKS.local.org #+SELECTED: " selected-id
                             " does not match any :CUSTOM_ID: in the loaded task graph")
               :location (cond-> {} selected-source-path (assoc :file selected-source-path))}))

    ;; per-task checks
    (doseq [task (walk-tasks tasks)]
      ;; broken-import
      (when (:import-error task)
        (vswap! out conj
                {:code :broken-import
                 :severity :error
                 :message (str "#+IMPORT: failed to load: " (:import-error task))
                 :location (location-for task)}))

      ;; waiting-without-blocker
      (when (and (= "WAITING" (:status task))
                 (empty? (parser/get-task-blockers task)))
        (vswap! out conj
                {:code :waiting-without-blocker
                 :severity :warn
                 :message "WAITING task has no :BLOCKED-BY: entry — add one or move it back to TODO"
                 :location (location-for task)}))

      ;; closed-without-timestamp
      (when (and (contains? closed-statuses (:status task))
                 (nil? (:closed task)))
        (vswap! out conj
                {:code :closed-without-timestamp
                 :severity :warn
                 :message (str (:status task)
                               " task has no CLOSED: timestamp cache. The next "
                               "tooling-driven status change will repair it.")
                 :location (location-for task)}))

      ;; stale-parent-status
      (when (= "TODO" (:status task))
        (let [statuses (child-status-set task)
              relevant (filter #(or (contains? active-child-statuses %)
                                    (contains? closed-statuses %))
                               statuses)]
          (when (seq relevant)
            (vswap! out conj
                    {:code :stale-parent-status
                     :severity :warn
                     :message (str "Parent is TODO but has descendants in ["
                                   (str/join ", " (sort statuses))
                                   "] — promote to STARTED")
                     :location (location-for task)}))))

      ;; invalid-task-blocker
      (doseq [blocker (parser/get-task-blockers task)]
        (when (and (= :task (:kind blocker))
                   (not (contains? by-id (:ref blocker))))
          (vswap! out conj
                  {:code :invalid-task-blocker
                   :severity :error
                   :message (str ":BLOCKED-BY: references task:" (:ref blocker)
                                 " which is not in the loaded task graph")
                   :location (location-for task)})))

      ;; non-uuid-v4-id
      (when-let [id (parser/get-task-id task)]
        (when-not (re-matches uuid-v4-re (str/lower-case id))
          (vswap! out conj
                  {:code :non-uuid-v4-id
                   :severity :warn
                   :message (str ":CUSTOM_ID: " id
                                 " is not a UUIDv4. Generate IDs with `ot uuid`"
                                 " or `ot create` rather than authoring them by hand.")
                   :location (location-for task)}))))

    ;; import-child-not-saveable — every imported source file must have
    ;; at least one parsed file root. Otherwise `save-source-roots` would
    ;; have no serialization root for that file and mutations to its
    ;; import children would be silently lost if the loader regressed.
    (let [saveable (saveable-source-paths tasks)]
      (doseq [[src task] (import-child-source-index tasks)
              :when (not (contains? saveable src))]
        (vswap! out conj
                {:code :import-child-not-saveable
                 :severity :warn
                 :message (str "Tasks from #+IMPORT:-linked file " src
                               " are reachable, but that file has no parsed"
                               " :file-root? serialization roots. Mutations to"
                               " those imported tasks may not persist.")
                 :location (location-for task)})))

    ;; patterned-sibling-ids — group siblings by their leading `N`
    ;; characters and flag any cluster of ≥ 2 sharing that prefix. This
    ;; catches hand-numbered sequences (e.g. `…d0001`/`…d0002`) without
    ;; demanding that *every* sibling share the prefix.
    (letfn [(check-siblings [siblings]
              (let [with-ids (filterv parser/get-task-id siblings)]
                (when (>= (count with-ids) 2)
                  (doseq [[prefix members]
                          (group-by #(let [id (parser/get-task-id %)]
                                       (when (>= (count id) patterned-prefix-threshold)
                                         (subs id 0 patterned-prefix-threshold)))
                                    with-ids)
                          :when (and prefix (>= (count members) 2))]
                    (doseq [sib members]
                      (vswap! out conj
                              {:code :patterned-sibling-ids
                               :severity :warn
                               :message (str "Sibling tasks share a "
                                             patterned-prefix-threshold
                                             "-character :CUSTOM_ID: prefix '"
                                             prefix
                                             "…'. Use `ot create` or `ot uuid` to"
                                             " generate fresh UUIDv4 values per task.")
                               :location (location-for sib)}))))))
            (walk-siblings [siblings]
              (check-siblings siblings)
              (doseq [s siblings]
                (walk-siblings (:children s))
                (when (:import-children s)
                  (walk-siblings (:import-children s)))))]
      (walk-siblings tasks))

    @out))

;; ── Formatting ────────────────────────────────────────────────────

(defn format-finding-line [{:keys [code severity message location]}]
  (let [sev (if (= severity :error) "ERROR" "WARN")
        loc (cond
              (and (:file location) (:line location))
              (str " (" (:file location) ":" (:line location) ")")
              (:file location) (str " (" (:file location) ")")
              :else "")]
    (str "[" sev "] " (name code) ": " message loc)))

(def ^:private finding-order
  [:duplicate-id :selected-not-found :broken-import :invalid-task-blocker
   :waiting-without-blocker :closed-without-timestamp :stale-parent-status
   :non-uuid-v4-id :patterned-sibling-ids :import-child-not-saveable
   :missing-link-template :misordered-link-template
   :missing-local-setupfile :misordered-setupfile
   :missing-record-section :empty-validation-section
   :spec-impact-untouched])

(defn format-findings-report [findings]
  (if (empty? findings)
    "tasks doctor: no issues found."
    (let [by-code (group-by :code findings)
          n (count findings)
          header (str "tasks doctor: " n " finding"
                     (if (= n 1) "" "s") ".")
          sections
          (for [code finding-order
                :let [list (get by-code code)]
                :when (seq list)]
            (concat [(str (name code) " (" (count list) "):")]
                    (map #(str "  " (format-finding-line %)) list)
                    [""]))]
      (str/replace
        (str/join "\n" (concat [header ""] (apply concat sections)))
        #"\n+$" ""))))

(defn count-by-severity [findings]
  (let [grouped (group-by :severity findings)]
    {:error (count (:error grouped))
     :warn  (count (:warn grouped))}))

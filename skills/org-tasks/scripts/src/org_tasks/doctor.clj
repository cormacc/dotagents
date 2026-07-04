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
    :spec-untouched
    :spec-value-malformed
    :spec-path-dangling"
  (:require [clojure.string :as str]
            [org-tasks.parser :as parser]
            [org-tasks.section :as section]
            [org-tasks.tree :as tree]))

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

(defn- child-status-set [task]
  (set (map :status (tree/all-tasks (tree/children task)))))

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
  (loop [todo (tree/all-tasks tasks)
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
  (letfn [(visit-task [task]
            (concat (map (fn [child]
                           (when-let [src (:source-path child)]
                             [src child]))
                         (:import-children task))
                    (mapcat visit-task (:children task))
                    (mapcat visit-task (:import-children task))))]
    (reduce (fn [m [src child]]
              (if (contains? m src) m (assoc m src child)))
            {}
            (keep identity (mapcat visit-task tasks)))))

(defn- saveable-source-paths
  "Return the set of source files that have at least one parsed file root.
  `loader/save-source-roots` relies on these `:file-root?` tasks as the
  serialization roots for each mutated source file."
  [tasks]
  (->> (tree/all-tasks tasks)
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

(defn- check-link-templates [protocol-files]
  (let [setup-findings
        (when-let [{:keys [path content]} (:setup protocol-files)]
          (let [templates (parser/parse-link-templates content)]
            (for [[prefix expected] required-setup-links
                  :when (not= (get templates prefix) expected)]
              {:code :missing-link-template
               :severity :warn
               :message (str "TASKS.setup.org should declare #+LINK: "
                             prefix " " expected)
               :location {:file path}})))
        protocol-findings
        (mapcat
         (fn [k]
           (when-let [{:keys [path content]} (get protocol-files k)]
             (let [templates (parser/parse-link-templates content)
                   shared-setup-line (line-number-of
                                      content
                                      #"(?i)^[\t ]*#\+SETUPFILE[\t ]*:[\t ]*\.?/?TASKS\.setup\.org\s*$")
                   local-setup-line  (line-number-of
                                      content
                                      #"(?i)^[\t ]*#\+SETUPFILE[\t ]*:[\t ]*\.?/?TASKS\.local\.org\s*$")
                   first-setup-line (some->> [shared-setup-line local-setup-line]
                                             (filter some?) seq sort first)
                   setup-checks (concat
                                 (when (nil? shared-setup-line)
                                   [{:code :missing-link-template
                                     :severity :warn
                                     :message (str path " should declare #+SETUPFILE: ./TASKS.setup.org")
                                     :location {:file path}}])
                                 (cond
                                   (nil? local-setup-line)
                                   [{:code :missing-local-setupfile
                                     :severity :warn
                                     :message (str path " should declare #+SETUPFILE: ./TASKS.local.org "
                                                   "so gitignored overrides flow through")
                                     :location {:file path}}]

                                   (and shared-setup-line (> local-setup-line shared-setup-line))
                                   [{:code :misordered-setupfile
                                     :severity :warn
                                     :message "#+SETUPFILE: ./TASKS.local.org must appear before ./TASKS.setup.org so local keywords win"
                                     :location {:file path :line local-setup-line}}]))
                   link-checks
                   (for [[prefix expected] required-local-links
                         :let [link-re (re-pattern
                                        (str "(?i)^[\\t ]*#\\+LINK[\\t ]*:[\\t ]*"
                                             prefix "[\\t ]+"
                                             (parser/escape-regex expected)
                                             "[\\t ]*$"))
                               link-line (line-number-of content link-re)]
                         finding (cond
                                   (or (not= (get templates prefix) expected) (nil? link-line))
                                   [{:code :missing-link-template
                                     :severity :warn
                                     :message (str path " should declare #+LINK: " prefix " " expected)
                                     :location {:file path}}]

                                   (and first-setup-line (> link-line first-setup-line))
                                   [{:code :misordered-link-template
                                     :severity :warn
                                     :message (str "Local #+LINK: " prefix " override must appear before #+SETUPFILE")
                                     :location {:file path :line link-line}}])]
                     finding)]
               (concat setup-checks link-checks))))
         [:tasks :archive])]
    (vec (concat setup-findings protocol-findings))))

;; ── Change-record spec checks ──────────────────────────────────────

(defn- truthy-keyword-value? [value]
  (contains? #{"1" "t" "true" "yes" "y" "on"} (some-> value str/trim str/lower-case)))

(def ^:private proj-link-re #"^\[\[proj:([^\]\[]+)\]\]$")

(defn- repo-relative-path?
  "True when `path` is a safe repo-relative path: non-blank, free of
  leading/trailing whitespace, not absolute, and free of `..` traversal
  segments. `proj:` links must resolve inside the repo root."
  [path]
  (and (not (str/blank? path))
       (= path (str/trim path))
       (not (str/starts-with? path "/"))
       (not (some #{".."} (str/split path #"/")))))

(defn extract-proj-link-path
  "Extract the repo-relative PATH from a bare `[[proj:PATH]]` value.
  Returns nil when `value` is not exactly that bare-link form, or when
  PATH is not a safe repo-relative path (absolute, `..`-escaping, or
  whitespace-padded). The labelled `[[proj:PATH][label]]` form and
  plain non-link paths are also treated as malformed. Shared by protocol and change-record `#+SPEC:` checks."
  [value]
  (when-let [[_ path] (re-matches proj-link-re (str/trim (or value "")))]
    (when (repo-relative-path? path) path)))

(defn- source-content-index
  "Return one `{path content}` entry for each parsed source file."
  [tasks]
  (reduce (fn [m task]
            (let [src (:source-path task)
                  content (:source-content task)]
              (if (and src content (not (contains? m src)))
                (assoc m src content)
                m)))
          {}
          (tree/all-tasks tasks)))

(defn- record-content-index
  "Return source-content entries excluding protocol task files."
  [tasks record-exclude-paths]
  (let [excluded (set record-exclude-paths)]
    (into {}
          (remove (fn [[src _]] (contains? excluded src)))
          (source-content-index tasks))))

(def ^:private required-record-sections
  ;; `* Validation` is optional per org-plan's section contract (omit unless
  ;; there is non-obvious verification evidence); it is only checked for
  ;; emptiness when present.
  ["Intent" "Summary" "Plan" "Implementation"])

(defn- spec-aware-record? [content]
  (or (seq (parser/get-file-keywords content "SPEC"))
      (some truthy-keyword-value? (parser/get-file-keywords content "NO_SPEC"))))

(defn- check-record-structure [tasks record-exclude-paths]
  (vec
   (mapcat
    (fn [[src content]]
      (when (spec-aware-record? content)
        (let [sections (set (section/list-sections content))
              validation (section/read-section content "Validation")]
          (concat
           (for [required required-record-sections
                 :when (not (contains? sections required))]
             {:code :missing-record-section
              :severity :warn
              :message (str "Change-record is missing required * " required " section")
              :location {:file src}})
           (when (and (:found validation)
                      (str/blank? (:body validation)))
             [{:code :empty-validation-section
               :severity :warn
               :message "Change-record has an empty * Validation section"
               :location {:file src}}])))))
    (record-content-index tasks record-exclude-paths))))

(defn- check-spec [tasks changed-paths record-exclude-paths]
  (let [changed (when changed-paths (set changed-paths))]
    (vec
     (mapcat
      (fn [[src content]]
        (let [raw-specs (->> (parser/get-file-keywords content "SPEC")
                             (map str/trim)
                             (remove str/blank?)
                             distinct)
              opted-out? (some truthy-keyword-value?
                               (parser/get-file-keywords content "NO_SPEC"))]
            ;; Malformed values are always reported so nothing silently
            ;; un-migrates; `#+NO_SPEC:` only suppresses the git changed-set
            ;; reconciliation nudge.
          (when (seq raw-specs)
            (mapcat
             (fn [raw]
               (let [path (extract-proj-link-path raw)]
                 (cond
                   (nil? path)
                   [{:code :spec-value-malformed
                     :severity :warn
                     :message (str "#+SPEC value " (pr-str raw)
                                   " is not a bare [[proj:PATH]] link")
                     :location {:file src}}]

                   (and (not opted-out?) changed (not (contains? changed path)))
                   [{:code :spec-untouched
                     :severity :warn
                     :message (str "Relevant spec unchanged — intended? " path
                                   " is listed in #+SPEC but is not touched in git status")
                     :location {:file src}}]

                   :else [])))
             raw-specs))))
      (record-content-index tasks record-exclude-paths)))))

(defn- check-spec-declarations
  "`#+SPEC:` checks over TASKS.org content: malformed values always;
  dangling (non-resolving) paths only when `spec-path-exists` (a
  `{path -> bool}` map computed by the CLI layer via disk stat) is
  supplied."
  [protocol-files spec-path-exists]
  (when-let [{:keys [path content]} (:tasks protocol-files)]
    (let [raw-values (->> (parser/get-file-keywords content "SPEC")
                          (map str/trim)
                          (remove str/blank?)
                          distinct)]
      (vec
       (mapcat
        (fn [raw]
          (let [spec-path (extract-proj-link-path raw)]
            (cond
              (nil? spec-path)
              [{:code :spec-value-malformed
                :severity :warn
                :message (str "#+SPEC value " (pr-str raw)
                              " is not a bare [[proj:PATH]] link")
                :location {:file path}}]

              (and spec-path-exists (false? (get spec-path-exists spec-path)))
              [{:code :spec-path-dangling
                :severity :warn
                :message (str "#+SPEC: " spec-path " does not resolve on disk")
                :location {:file path}}]

              :else [])))
        raw-values)))))

;; ── Main checks ───────────────────────────────────────────────────

(defn- check-link-templates-input [{:keys [protocol-files]}]
  (check-link-templates protocol-files))

(defn- check-record-structure-input [{:keys [tasks record-exclude-paths]}]
  (check-record-structure tasks record-exclude-paths))

(defn- check-spec-input [{:keys [tasks changed-paths record-exclude-paths]}]
  (or (check-spec tasks changed-paths record-exclude-paths) []))

(defn- check-spec-declarations-input [{:keys [protocol-files spec-path-exists]}]
  (or (check-spec-declarations protocol-files spec-path-exists) []))

(defn- check-duplicate-ids [{:keys [id-index]}]
  (vec
   (mapcat (fn [[id occurrences]]
             (for [occ occurrences]
               {:code :duplicate-id
                :severity :error
                :message (str "Duplicate :CUSTOM_ID: " id
                              " (" (count occurrences) " occurrences)")
                :location (location-for occ)}))
           (:duplicates id-index))))

(defn- check-selected-id [{:keys [selected-id selected-source-path id-index]}]
  (when (and selected-id (not (contains? (:by-id id-index) selected-id)))
    [{:code :selected-not-found
      :severity :error
      :message (str "TASKS.local.org #+SELECTED: " selected-id
                    " does not match any :CUSTOM_ID: in the loaded task graph")
      :location (cond-> {} selected-source-path (assoc :file selected-source-path))}]))

(defn- check-task [by-id task]
  (vec
   (concat
    (when (:import-error task)
      [{:code :broken-import
        :severity :error
        :message (str "#+IMPORT: failed to load: " (:import-error task))
        :location (location-for task)}])
    (when (and (= "WAITING" (:status task))
               (empty? (parser/get-task-blockers task)))
      [{:code :waiting-without-blocker
        :severity :warn
        :message "WAITING task has no :BLOCKED-BY: entry — add one or move it back to TODO"
        :location (location-for task)}])
    (when (and (contains? closed-statuses (:status task))
               (nil? (:closed task)))
      [{:code :closed-without-timestamp
        :severity :warn
        :message (str (:status task)
                      " task has no CLOSED: timestamp cache. The next "
                      "tooling-driven status change will repair it.")
        :location (location-for task)}])
    (when (= "TODO" (:status task))
      (let [statuses (child-status-set task)
            relevant (filter #(or (contains? active-child-statuses %)
                                  (contains? closed-statuses %))
                             statuses)]
        (when (seq relevant)
          [{:code :stale-parent-status
            :severity :warn
            :message (str "Parent is TODO but has descendants in ["
                          (str/join ", " (sort statuses))
                          "] — promote to STARTED")
            :location (location-for task)}])))
    (for [blocker (parser/get-task-blockers task)
          :when (and (= :task (:kind blocker))
                     (not (contains? by-id (:ref blocker))))]
      {:code :invalid-task-blocker
       :severity :error
       :message (str ":BLOCKED-BY: references task:" (:ref blocker)
                     " which is not in the loaded task graph")
       :location (location-for task)})
    (when-let [id (parser/get-task-id task)]
      (when-not (re-matches uuid-v4-re (str/lower-case id))
        [{:code :non-uuid-v4-id
          :severity :warn
          :message (str ":CUSTOM_ID: " id
                        " is not a UUIDv4. Generate IDs with `ot uuid`"
                        " or `ot create` rather than authoring them by hand.")
          :location (location-for task)}])))))

(defn- check-all-tasks [{:keys [tasks id-index]}]
  (vec (mapcat #(check-task (:by-id id-index) %) (tree/all-tasks tasks))))

(defn- check-import-children-saveable [{:keys [tasks]}]
  (let [saveable (saveable-source-paths tasks)]
    (vec
     (for [[src task] (import-child-source-index tasks)
           :when (not (contains? saveable src))]
       {:code :import-child-not-saveable
        :severity :warn
        :message (str "Tasks from #+IMPORT:-linked file " src
                      " are reachable, but that file has no parsed"
                      " :file-root? serialization roots. Mutations to"
                      " those imported tasks may not persist.")
        :location (location-for task)}))))

(defn- sibling-groups [siblings]
  (cons siblings
        (mapcat sibling-groups
                (mapcat #(concat (:children %) (:import-children %)) siblings))))

(defn- check-patterned-sibling-group [siblings]
  (let [with-ids (filterv parser/get-task-id siblings)]
    (when (>= (count with-ids) 2)
      (vec
       (mapcat
        (fn [[prefix members]]
          (when (and prefix (>= (count members) 2))
            (for [sib members]
              {:code :patterned-sibling-ids
               :severity :warn
               :message (str "Sibling tasks share a "
                             patterned-prefix-threshold
                             "-character :CUSTOM_ID: prefix '"
                             prefix
                             "…'. Use `ot create` or `ot uuid` to"
                             " generate fresh UUIDv4 values per task.")
               :location (location-for sib)})))
        (group-by #(let [id (parser/get-task-id %)]
                     (when (>= (count id) patterned-prefix-threshold)
                       (subs id 0 patterned-prefix-threshold)))
                  with-ids))))))

(defn- check-patterned-sibling-ids [{:keys [tasks]}]
  (vec (mapcat #(or (check-patterned-sibling-group %) [])
               (sibling-groups tasks))))

(def ^:private doctor-checks
  [check-link-templates-input
   check-record-structure-input
   check-spec-input
   check-spec-declarations-input
   check-duplicate-ids
   check-selected-id
   check-all-tasks
   check-import-children-saveable
   check-patterned-sibling-ids])

(defn run-doctor
  "Top-level doctor entry point. `input` keys:

    :tasks                 - top-level tasks from the loaded graph
    :selected-id           - UUID parsed from TASKS.local.org (or nil)
    :selected-source-path  - path to TASKS.local.org (optional)
    :protocol-files        - {:setup/:tasks/:archive {:path, :content}}
                             for the link-template / setupfile checks
    :changed-paths         - optional set of repo-relative git status paths
                             for change-record #+SPEC closeout nudges
    :record-exclude-paths  - optional set of protocol task source paths to
                             exclude from change-record checks
    :spec-path-exists      - optional {repo-relative-path -> bool} map,
                             computed by the CLI layer via disk stat, for
                             the #+SPEC: dangling-path check"
  [{:keys [tasks] :as input}]
  (let [input (assoc input :id-index (build-id-index tasks))]
    (vec (mapcat #(% input) doctor-checks))))

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
   :spec-untouched :spec-value-malformed :spec-path-dangling])

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

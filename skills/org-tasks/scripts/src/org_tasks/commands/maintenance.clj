(ns org-tasks.commands.maintenance
  "`ot` uuid / root / backfill / init / scan / doctor / section
  command handlers."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [org-tasks.doctor :as doctor]
            [org-tasks.links :as links]
            [org-tasks.loader :as loader]
            [org-tasks.output :as out]
            [org-tasks.parser :as parser]
            [org-tasks.paths :as paths]
            [org-tasks.root :as root]
            [org-tasks.scan :as scan]
            [org-tasks.section :as section]
            [org-tasks.commands.spec :as cspec]
            [org-tasks.spec :as spec]
            [org-tasks.styling :as style]
            [org-tasks.task :as task]
            [org-tasks.tree :as tree]
            [org-tasks.commands.util :refer [positional-arg load-context resolve-context
                                             coerce-seq]]))

;; ── Default preamble templates (used by `ot init`) ────────────────

(def ^:private setup-org-default
  (str/join "\n"
            ["#+TODO: TODO(t) STARTED(s!) WAITING(w@/!) | DONE(d!) CANCELLED(c!)"
             "#+STARTUP: logdone logdrawer"
             ;; Links here resolve from a change-record's location
             ;; (design/log/) via #+SETUPFILE: ../../TASKS.setup.org.
             ;; `plan` is TASKS.org-only (repo-root) and lives in the
             ;; task-file local overrides, not here.
             "#+LINK: proj file:../../%s"
             "#+LINK: task file:../../TASKS.org::#%s"
             "#+LINK: archive file:../../TASKS.archive.org::#%s"
             ""]))

(def ^:private tasks-org-default
  (str/join "\n"
            ["#+TITLE: Project Tasks"
             ;; Local overrides (repo-root relative); win over
             ;; TASKS.setup.org because they are declared first.
             "#+LINK: task file:TASKS.org::#%s"
             "#+LINK: archive file:TASKS.archive.org::#%s"
             "#+LINK: plan file:design/log/%s"
             "#+LINK: proj file:%s"
             "#+SETUPFILE: ./TASKS.local.org"
             "#+SETUPFILE: ./TASKS.setup.org"
             "#+ARCHIVE: TASKS.archive.org::* From %s"
             ""
             "* Improvements"
             ""]))

(def ^:private local-org-default
  (str/join "\n"
            ["#+SELECTED:"
             ""]))

;; ── ot init ─────────────────────────────────────────────────────────

;; ── ot uuid ───────────────────────────────────

(defn uuid-cmd
  "Generate one or more UUIDv4 strings for use as `:CUSTOM_ID:` values
  when authoring tasks. Plain text emits one UUID per line; JSON/EDN
  emit a `:uuids` vector inside the standard envelope.

  Use this instead of inventing IDs in prose so plan tasks never share
  hand-authored prefixes / sequential suffixes."
  [{:keys [opts]}]
  (let [n (max 1 (or (:count opts) 1))
        uuids (vec (repeatedly n #(str (random-uuid))))]
    (out/emit-result opts
                     {:uuids uuids
                      :count (count uuids)
                      :text/lines uuids})))

(defn root-cmd
  "Print the resolved project root."
  [{:keys [opts]}]
  (let [project-root (root/resolve-root opts)]
    (out/emit-result opts
                     {:root project-root
                      :text/lines [project-root]})))

;; ── ot backfill ───────────────────────────────────────────────────

(defn- task-missing-created? [task]
  (nil? (parser/get-drawer-property task "CREATED")))

(defn- backfill-missing-task-metadata
  "Return `{:tasks, :changes}` after filling identity metadata on tasks
  missing `:CUSTOM_ID:`. New IDs also receive `:CREATED:` and a
  matching created LOGBOOK entry when those fields are absent, giving
  hand-authored headings the same shape as `ot create` without
  rewriting legacy tasks that already have an id."
  [tasks timestamp]
  (let [changes (volatile! [])]
    (letfn [(visit [task]
              (let [missing-id? (not (parser/task-has-id? task))
                    missing-created? (and missing-id? (task-missing-created? task))
                    id (when missing-id? (str (random-uuid)))
                    updated (cond-> task
                              missing-id?
                              (parser/set-drawer-property "CUSTOM_ID" id)

                              missing-created?
                              (parser/set-drawer-property "CREATED" (str "[" timestamp "]"))

                              missing-created?
                              (parser/append-created-log timestamp))
                    updated (-> updated
                                (update :children #(mapv visit (or % [])))
                                (cond->
                                  (:import-children task)
                                  (update :import-children #(mapv visit (or % [])))))]
                (when missing-id?
                  (vswap! changes conj
                          (cond-> {:id id
                                   :summary (:summary task)
                                   :status (:status task)
                                   :file (:source-path task)
                                   :line (:line-number task)
                                   :created (when missing-created?
                                              (str "[" timestamp "]"))}
                            (not missing-created?) (dissoc :created))))
                updated))]
      {:tasks (mapv visit tasks)
       :changes @changes})))

(defn backfill-cmd
  "Backfill protocol metadata for hand-authored task headings.

  The default repair is intentionally conservative for automatic
  file-watch use: only tasks lacking `:CUSTOM_ID:` are changed. Those
  newly identified tasks also get a `:CREATED:` property and created
  LOGBOOK entry if missing. Existing identified legacy tasks are left
  alone even when they predate `:CREATED:`."
  [{:keys [opts]}]
  (let [{:keys [project-root tasks]} (load-context opts)
        timestamp (or (:created-at opts) (parser/format-org-timestamp))
        {:keys [tasks changes]} (backfill-missing-task-metadata tasks timestamp)
        changed-count (count changes)]
    (when (and (pos? changed-count) (not (:dry-run opts)))
      (loader/save-source-roots project-root tasks))
    (out/emit-result
      opts
      {:changed changed-count
       :changes changes
       :dryRun (boolean (:dry-run opts))
       :text/lines
       (if (pos? changed-count)
         (into [(str "Backfilled " changed-count " task"
                     (when (not= 1 changed-count) "s")
                     (when (:dry-run opts) " (dry run)"))]
               (map (fn [{:keys [id file line summary]}]
                      (str id " " file ":" line " " summary))
                    changes))
         ["No task metadata backfill needed."])})))

(defn init-cmd [{:keys [opts]}]
  (let [{:keys [project-root files]} (resolve-context opts)
        setup-path  (str (fs/path project-root "TASKS.setup.org"))
        targets     [[setup-path setup-org-default]
                     [(:tasks files) tasks-org-default]
                     [(:local files) local-org-default]]
        ;; Snapshot existence up front so the post-write summary
        ;; accurately partitions into created vs skipped.
        need-create (filterv (fn [[p _]] (not (fs/exists? p))) targets)
        skipped     (mapv first (filterv (fn [[p _]] (fs/exists? p)) targets))
        created     (vec (for [[path content] need-create]
                           (do (when-not (:dry-run opts)
                                 (fs/create-dirs (fs/parent path))
                                 (spit path content))
                               path)))]
    (out/emit-result opts
                     {:created (vec created)
                      :skipped (vec skipped)
                      :projectRoot project-root
                      :files {:tasks   (:tasks files)
                              :local   (:local files)
                              :archive (:archive files)
                              :setup   setup-path}
                      :dryRun (boolean (:dry-run opts))
                      :text/lines
                      (concat (when (seq created)
                                (cons (str "Created " (count created) " file(s):")
                                      (map #(str "  " %) created)))
                              (when (seq skipped)
                                (cons (str "Skipped " (count skipped) " existing file(s):")
                                      (map #(str "  " %) skipped))))})))
;; ── ot scan ────────────────────────────────────────────────

(defn- load-archived-tasks
  "Parse TASKS.archive.org if present. Returns a vector of top-level
  tasks or []."
  [archive-path]
  (or (when (fs/exists? archive-path)
        (when-let [content (loader/safe-slurp archive-path)]
          (:tasks (parser/parse-tasks content {:source-path archive-path}))))
      []))

(defn- build-record-reader
  "Memoised `(fn [task] content|nil)` resolving a task's #+IMPORT
  change-record under `project-root`."
  [project-root]
  (let [cache (volatile! {})]
    (fn [task]
      (when-let [import-path (:import-path task)]
        (when-let [abs (links/resolve-task-link-target project-root task import-path)]
          (if (contains? @cache abs)
            (get @cache abs)
            (let [content (loader/safe-slurp abs)]
              (vswap! cache assoc abs content)
              content)))))))

(defn- scan-row->text [opts row]
  (let [prio    (if (:priority row) (str (style/priority opts (:priority row)) " ") "")
        tags    (if (seq (:tags row)) (str " " (style/tag-cluster opts (:tags row))) "")
        record  (cond
                  (nil? (:recordSummary row)) ""
                  (true? (:found (:recordSummary row)))
                  (if (:hasContext row) " … (+ctx)" " …")
                  :else " (no summary)")]
    (str "[" (style/status opts (:status row) (:status row)) "] "
         (style/styled opts :gray (task/id-prefix (:id row))) "  "
         prio (:summary row) tags record)))

(defn scan-cmd [{:keys [opts]}]
  (let [{:keys [project-root tasks files]} (load-context opts)
        archived (load-archived-tasks (:archive files))
        reader (build-record-reader project-root)
        scope (or (:scope opts) :all)
        rows (scan/scan-summaries
               {:active-roots tasks
                :archived-roots archived
                :read-change-record reader}
               {:scope scope
                :tags (when (seq (coerce-seq (:tag opts)))
                        (coerce-seq (:tag opts)))
                :max-body-chars (or (:max-body-chars opts)
                                    scan/default-max-body-chars)})
        cap 60
        head-lines (mapv #(scan-row->text opts %) (take cap rows))
        tail (when (> (count rows) cap)
               (str "… " (- (count rows) cap) " more row(s) in details.rows"))]
    (out/emit-result
      opts
      {:rows rows
       :scope scope
       :count (count rows)
       :text/lines (cond-> [(str "# scan: " (count rows) " row(s) (scope="
                                 (name scope) ")")]
                     true (into head-lines)
                     tail (conj tail))})))

;; ── ot doctor ────────────────────────────────────────────

(defn- read-protocol-file [path]
  (when (and path (fs/exists? path))
    (try {:path path :content (slurp path)} (catch Throwable _ nil))))

(defn- finding->wire [f]
  {:code (name (:code f))
   :severity (name (:severity f))
   :message (:message f)
   :location (or (:location f) {})})

(defn- parse-git-status-paths [^String out]
  (->> (str/split-lines (or out ""))
       (mapcat
         (fn [line]
           (let [path (subs line (min 3 (count line)))]
             (if-let [[_ old new] (re-matches #"(.+?) -> (.+)" path)]
               [old new]
               [path]))))
       (map str/trim)
       (remove str/blank?)
       set))

(defn- changed-git-paths
  "Return repo-relative paths changed in the current working tree/index,
  or nil when git is unavailable."
  [project-root]
  (try
    (let [{:keys [exit out]} (process/shell {:out :string :err :string
                                              :continue true :dir project-root}
                                             "git" "status" "--porcelain=v1"
                                             "--untracked-files=all")]
      (when (zero? exit)
        (parse-git-status-paths out)))
    (catch Throwable _ nil)))

(defn- declared-spec-paths-across-graph
  "Distinct `#+SPEC:` paths declared anywhere in the loaded task graph
  (TASKS.org plus every #+IMPORT:-linked change-record), used to scope
  the `spec-stale` link-closure computation to specs actually declared."
  [tasks]
  (->> (tree/all-tasks tasks)
       (keep :source-content)
       distinct
       (mapcat #(parser/get-file-keywords % "SPEC"))
       (map str/trim)
       (remove str/blank?)
       (keep doctor/extract-proj-link-path)
       distinct))

(defn- spec-linked-paths-map
  "`{spec-path -> #{linked-paths}}` for every declared spec path in the
  graph, computed via `org-tasks.spec/linked-paths-from` over real disk
  access. Backs the `ot doctor` spec-stale advisory."
  [project-root tasks]
  (let [fs (cspec/real-fs project-root)]
    (into {}
          (map (fn [p] [p (spec/linked-paths-from fs p)]))
          (declared-spec-paths-across-graph tasks))))

(defn- spec-path-exists-map
  "Resolve declared `#+SPEC:` paths from TASKS.org content against
  disk; `{repo-relative-path -> bool}`. Malformed values are skipped
  here — `check-spec-declarations` reports those independently."
  [project-root tasks-content]
  (when tasks-content
    (let [raw-values (->> (parser/get-file-keywords tasks-content "SPEC")
                          (map str/trim)
                          (remove str/blank?)
                          distinct)]
      (into {}
            (keep (fn [raw]
                    (when-let [p (doctor/extract-proj-link-path raw)]
                      [p (fs/exists? (fs/path project-root p))])))
            raw-values))))

(defn doctor-cmd [{:keys [opts]}]
  (let [{:keys [project-root tasks selected-id files]} (load-context opts)
        setup-path (str (fs/path project-root "TASKS.setup.org"))
        protocol-files {:setup   (read-protocol-file setup-path)
                        :tasks   (read-protocol-file (:tasks files))
                        :archive (read-protocol-file (:archive files))}
        record-exclude-paths (->> [setup-path (:tasks files) (:local files) (:archive files)]
                                  (remove nil?)
                                  set)
        findings (doctor/run-doctor
                   {:tasks tasks
                    :selected-id selected-id
                    :selected-source-path (:local files)
                    :protocol-files protocol-files
                    :changed-paths (changed-git-paths project-root)
                    :record-exclude-paths record-exclude-paths
                    :spec-path-exists (spec-path-exists-map
                                        project-root
                                        (:content (:tasks protocol-files)))
                    :spec-linked-paths (spec-linked-paths-map project-root tasks)})
        counts (doctor/count-by-severity findings)
        wire-findings (mapv finding->wire findings)
        report (doctor/format-findings-report findings)]
    (out/emit-result
      opts
      {:findings wire-findings
       :counts counts
       :text/lines [report]})))


;; ── ot section ───────────────────────────────────────────

(defn section-cmd [{:keys [opts] :as result}]
  (let [{:keys [project-root]} (resolve-context opts)
        file    (positional-arg result :file)
        section (or (positional-arg result :section 1)
                    section/default-section)]
    (cond
      (or (nil? file) (str/blank? file))
      (out/emit-error opts
                      {:code "argument-error"
                       :message "ot section requires a file path."})

      :else
      (if-let [abs (paths/resolve-project-path project-root project-root file)]
        (let [content (try (slurp abs)
                           (catch Throwable e
                             (out/emit-error opts
                                             {:code "unreadable"
                                              :message (str "Cannot read " abs
                                                            ": " (.getMessage e))
                                              :file abs})
                             nil))]
          (when content
            (let [r (section/read-section content section)]
              (out/emit-result
                opts
                (cond-> {:file abs
                         :section section
                         :found (:found r)}
                  (:found r) (assoc :heading (:heading r) :body (:body r))
                  (:found r) (assoc :text/lines
                                    [(:heading r) (str/trim-newline (:body r))])
                  (not (:found r))
                  (assoc :text/lines
                         [(str "Section '" section "' not found in " abs)]))))))
        (out/emit-error opts
                        {:code "out-of-root"
                         :message (str "Path resolves outside project root: " file)
                         :file file})))))

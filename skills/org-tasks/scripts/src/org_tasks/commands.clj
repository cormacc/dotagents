(ns org-tasks.commands
  "Concrete `ot` command implementations.

  Each command is a single-arg fn `(fn [{:keys [opts args dispatch]}])`
  invoked by `babashka.cli/dispatch` once option parsing succeeds.
  Commands resolve the project root, load the graph as needed, run
  their work, and emit via `org-tasks.output`.

  Currently implemented:

    init, list, show, create, select, selected, status,
    archive, publish, unpublish, section, scan, doctor

  The remaining commands (record, issue, blocker, ready, handoff)
  land in plan task 58d1547e."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [org-tasks.doctor :as doctor]
            [org-tasks.insert :as insert]
            [org-tasks.lifecycle :as lifecycle]
            [org-tasks.loader :as loader]
            [org-tasks.output :as out]
            [org-tasks.parser :as parser]
            [org-tasks.paths :as paths]
            [org-tasks.root :as root]
            [org-tasks.scan :as scan]
            [org-tasks.section :as section]
            [org-tasks.task :as task]))

;; ── Context helpers ────────────────────────────────────────────────

(defn- resolve-context [opts]
  (let [project-root (root/resolve-root opts)
        files        (root/resolve-protocol-files opts project-root)]
    {:project-root project-root :files files}))

(defn- load-context [opts]
  (let [{:keys [project-root files]} (resolve-context opts)
        graph (loader/load-graph project-root files)]
    (merge {:project-root project-root :files files} graph)))

(defn- find-required-task [tasks id opts]
  (or (task/find-by-id tasks id)
      (out/emit-error opts
                      {:code "unknown-task"
                       :message (str "No task with :CUSTOM_ID: " id)
                       :details {:id id}})))

;; ── Default preamble templates (used by `ot init`) ────────────────

(def ^:private setup-org-default
  (str/join "\n"
            ["#+TODO: TODO(t) STARTED(s!) WAITING(w@/!) | DONE(d!) CANCELLED(c!)"
             "#+STARTUP: logdone logdrawer"
             "#+LINK: plan file:design/log/%s"
             "#+LINK: task file:../../TASKS.org::#%s"
             "#+LINK: archive file:../../TASKS.archive.org::#%s"
             ""]))

(def ^:private tasks-org-default
  (str/join "\n"
            ["#+TITLE: Project Tasks"
             "#+LINK: task file:TASKS.org::#%s"
             "#+LINK: archive file:TASKS.archive.org::#%s"
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

;; ── ot list ─────────────────────────────────────────────────────────

(defn- format-list-row [row selected-id]
  (let [short-id (some-> (:id row) (subs 0 (min 8 (count (:id row)))))
        sel-mark (if (and (:id row) (= (:id row) selected-id)) "★" " ")
        local    (if (:local row) "⊠" " ")
        prio     (if (:priority row) (str "[#" (:priority row) "] ") "")
        indent   (apply str (repeat (max 0 (- (or (:level row) 1) 1)) "  "))
        tags     (if (seq (:tags row)) (str " :" (str/join ":" (:tags row)) ":") "")]
    (str sel-mark local " " (format "%-9s" (:status row)) " "
         (or short-id "--------") "  "
         indent prio (:summary row) tags)))

(defn list-cmd [{:keys [opts]}]
  (let [{:keys [tasks selected-id files]} (load-context opts)
        sources    (task/collect-sources tasks)
        wire-tasks (mapv #(task/task->wire % nil {:include-content? false}) tasks)
        rows       (task/flatten-tree wire-tasks)
        scope      (:scope opts :active)
        status-filter (set (:status-filter opts))
        match?     (fn [row]
                     (and (or (empty? status-filter)
                              (contains? status-filter (:status row)))
                          (or (= scope :all) (= scope :active)
                              ;; archived scope handled in v2; for now :active is default
                              true)))
        filtered   (vec (filter match? rows))]
    (out/emit-result
      opts
      {:tree wire-tasks
       :rows filtered
       :selectedId selected-id
       :files files
       :sources sources
       :text/lines (cons (format "%-2s %s" " " "STATUS    short-id  task")
                         (map #(format-list-row % selected-id) filtered))})))

;; ── ot show ─────────────────────────────────────────────────────────

(defn- resolve-id-arg [opts dispatch-result selected-id]
  (let [raw (or (:id opts) (first (:args dispatch-result)))]
    (cond
      (= raw "selected") selected-id
      (and raw (seq raw)) raw)))

(defn show-cmd [{:keys [opts] :as result}]
  (let [{:keys [tasks selected-id]} (load-context opts)
        id (resolve-id-arg opts result selected-id)]
    (cond
      (nil? id)
      (out/emit-error opts
                      {:code "unknown-task"
                       :message "ot show requires a task id (or 'selected')."})

      :else
      (if-let [t (task/find-by-id tasks id)]
        (let [wire (task/task->wire t)]
          (out/emit-result
            opts
            {:task wire
             :text/lines
             [(str (:status wire) " "
                   (if (:priority wire) (str "[#" (:priority wire) "] ") "")
                   (:summary wire))
              (str "  id        " (:id wire))
              (str "  source    " (:sourcePath wire))
              (when (:importPath wire)
                (str "  plan      " (or (:importRaw wire) (:importPath wire))))
              (when (:closed wire)
                (str "  closed    " (:closed wire)))
              (when (:started wire)
                (str "  started   " (:started wire)))
              (when (seq (:tags wire))
                (str "  tags      :" (str/join ":" (:tags wire)) ":"))
              (when (seq (:blockedBy wire))
                (str "  blockers  " (str/join ", " (:blockedBy wire))))
              (when (:handoff wire)
                (str "  handoff   " (:handoff wire)))]}))
        (out/emit-error opts
                        {:code "unknown-task"
                         :message (str "No task with :CUSTOM_ID: " id)
                         :details {:id id}})))))

;; ── ot select / ot selected ────────────────────────────────────────

(defn select-cmd [{:keys [opts] :as result}]
  (let [{:keys [tasks selected-id files]} (load-context opts)
        clear? (:clear opts)
        id     (when-not clear?
                 (or (:id opts) (first (:args result))))]
    (cond
      (and (not clear?) (nil? id))
      (out/emit-error opts
                      {:code "argument-error"
                       :message "ot select requires an id (or --clear)."})

      (and id (not (task/find-by-id tasks id)))
      (out/emit-error opts
                      {:code "unknown-task"
                       :message (str "No task with :CUSTOM_ID: " id)
                       :details {:id id}})

      :else
      (let [new-id (when-not clear? id)]
        (when-not (:dry-run opts)
          (loader/write-selected-id (:local files) new-id))
        (out/emit-result
          opts
          {:selectedId new-id
           :previousId selected-id
           :file (:local files)
           :text/lines [(if new-id
                          (str "Selected " new-id
                               (when selected-id (str " (was " selected-id ")")))
                          "Selection cleared.")]})))))

(defn selected-cmd [{:keys [opts] :as result}]
  (let [{:keys [tasks selected-id]} (load-context opts)]
    (if-let [t (and selected-id (task/find-by-id tasks selected-id))]
      (show-cmd (assoc-in result [:opts :id] selected-id))
      (out/emit-result
        opts
        {:selected nil
         :selectedId selected-id
         :text/lines ["No task currently selected."]}))))

;; ── ot status ──────────────────────────────────────────────────────

(def ^:private valid-statuses
  #{"TODO" "STARTED" "WAITING" "DONE" "CANCELLED"})

(defn- update-task-in-tree
  "Replace the first task with `:CUSTOM_ID:` = `id` anywhere in `tasks`
  with `(f task)`. Walks `:children` and `:import-children`."
  [tasks id f]
  (mapv
    (fn [t]
      (if (= id (parser/get-task-id t))
        (f t)
        (-> t
            (update :children #(update-task-in-tree % id f))
            (cond->
              (:import-children t)
              (update :import-children #(update-task-in-tree % id f))))))
    tasks))

(defn- find-path-to-id
  "Return the path of tasks from a top-level root to the task with
  :CUSTOM_ID: = `id` (inclusive). Walks `:children` only — parent
  auto-promotion follows the literal task tree, not import children."
  [tasks id]
  (letfn [(walk [ts trail]
            (some
              (fn [t]
                (let [trail' (conj trail t)]
                  (if (= id (parser/get-task-id t))
                    trail'
                    (walk (:children t) trail'))))
              ts))]
    (walk tasks [])))

(defn- coerce-seq
  "Normalise an option that may be missing, scalar, or already a vector
  to a vector. The top-level dispatch :coerce coerces repeated flags
  into vectors but a single occurrence may slip through as a scalar."
  [v]
  (cond
    (nil? v) []
    (sequential? v) (vec v)
    :else [v]))

(defn create-cmd [{:keys [opts] :as result}]
  (let [{:keys [project-root files]} (resolve-context opts)
        summary (or (:summary opts) (first (:args result)))
        local?  (boolean (:local opts))
        file    (if local? (:local files) (:tasks files))
        labels  (coerce-seq (:tag opts))
        tokens  (coerce-seq (:linked-issue opts))
        ;; Mirror the pi extension's `alsoScan` heuristic: when
        ;; inserting into TASKS.org, also scan TASKS.local.org for
        ;; duplicate :LINKED_ISSUES: tokens, and vice-versa.
        also-scan (into (if local? [(:tasks files)] [(:local files)])
                        (coerce-seq (:also-scan opts)))
        args    {:project-root project-root
                 :file file
                 :section (or (:section opts) "Improvements")
                 :summary summary
                 :priority-name (:priority opts)
                 :body (:body opts)
                 :linked-issues tokens
                 :labels labels
                 :parent-id (:parent opts)
                 :after-id (:after opts)
                 :id (:id opts)
                 :created-at (:created-at opts)
                 :also-scan also-scan
                 :allow-create-section? (boolean (:allow-create-section opts))}]
    (cond
      (or (nil? summary) (str/blank? summary))
      (out/emit-error opts
                      {:code "empty-summary"
                       :message "ot create requires a non-empty summary."})

      (:dry-run opts)
      (out/emit-result opts
                       {:file (:file args)
                        :section (:section args)
                        :dryRun true
                        :text/lines [(str "Would insert under '" (:section args)
                                          "' in " (:file args))]})

      :else
      (let [result (insert/insert-task-into-file args)]
        (case (:status result)
          :inserted
          (out/emit-result opts
                           {:id (:id result)
                            :file (:file result)
                            :line (:line result)
                            :sectionCreated (boolean (:allow-create-section opts))
                            :text/lines
                            [(str "Inserted " (:id result)
                                  " at " (:file result) ":" (:line result))]})

          :duplicate
          (out/emit-error opts
                          {:code "duplicate-linked-issue"
                           :message (str "Linked issue token already linked from "
                                         (or (:existing-id result) "(no :CUSTOM_ID:)"))
                           :file (:existing-file result)
                           :details {:conflictingToken (:conflicting-token result)
                                     :existingId (:existing-id result)
                                     :existingFile (:existing-file result)}})

          :section-not-found
          (out/emit-error opts
                          {:code "section-not-found"
                           :message (str "Section '" (:section args) "' not found in "
                                         (:file args))
                           :file (:file result)
                           :details {:section (:section args)}})

          :unknown-task
          (out/emit-error opts
                          {:code "unknown-task"
                           :message (:message result)
                           :details {:reason (some-> (:reason result) name)
                                     :parentId (:parent opts)
                                     :afterId (:after opts)}})

          :error
          (out/emit-error opts
                          {:code (name (:reason result))
                           :message (:message result)}))))))

(defn status-cmd [{:keys [opts] :as result}]
  (let [{:keys [tasks files]} (load-context opts)
        id     (or (:id opts) (first (:args result)))
        status (some-> (or (:new-status opts) (second (:args result)))
                       str/upper-case)]
    (cond
      (or (nil? id) (nil? status))
      (out/emit-error opts
                      {:code "argument-error"
                       :message "ot status requires <id> <new-status>."})

      (not (valid-statuses status))
      (out/emit-error opts
                      {:code "invalid-status"
                       :message (str "Status must be one of "
                                     (str/join ", " (sort valid-statuses)))
                       :details {:value status}})

      :else
      (if-let [target (task/find-by-id tasks id)]
        (let [{updated :task :keys [prev-status timestamp]}
              (lifecycle/apply-status-transition target status)
              tree-after (update-task-in-tree tasks id (constantly updated))
              ;; Parent auto-promotion: when a subtask goes STARTED, walk
              ;; ancestors and promote any TODO ancestor to STARTED.
              promotion-acc (volatile! [])
              tree-final
              (if (= status "STARTED")
                (let [path (find-path-to-id tree-after id)
                      ancestors (vec (butlast path))]
                  (reduce
                    (fn [tree-acc ancestor]
                      (if (and (= "TODO" (:status ancestor))
                               (parser/get-task-id ancestor))
                        (let [{a-updated :task :keys [prev-status]}
                              (lifecycle/apply-status-transition ancestor "STARTED" timestamp)]
                          (vswap! promotion-acc conj
                                  {:id (parser/get-task-id ancestor)
                                   :prevStatus prev-status
                                   :status "STARTED"})
                          (update-task-in-tree tree-acc (parser/get-task-id ancestor)
                                               (constantly a-updated)))
                        tree-acc))
                    tree-after
                    ancestors))
                tree-after)]
          (when-not (:dry-run opts)
            (loader/save-source-roots (:project-root (resolve-context opts))
                                      tree-final))
          (out/emit-result
            opts
            {:task (task/task->wire (task/find-by-id tree-final id))
             :prevStatus prev-status
             :status status
             :closed (:closed updated)
             :started (parser/get-task-started updated)
             :promoted @promotion-acc
             :text/lines
             [(str (:summary updated) ": " prev-status " → " status
                   (when (:closed updated) (str " (closed " (:closed updated) ")")))
              (when (seq @promotion-acc)
                (str "Promoted ancestors: "
                     (str/join ", " (map :id @promotion-acc))))]}))
        (out/emit-error opts
                        {:code "unknown-task"
                         :message (str "No task with :CUSTOM_ID: " id)
                         :details {:id id}})))))

;; ── ot section ───────────────────────────────────────────

(defn section-cmd [{:keys [opts] :as result}]
  (let [{:keys [project-root]} (resolve-context opts)
        file    (or (:file opts) (first (:args result)))
        section (or (:section opts) (second (:args result))
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

;; ── ot scan ────────────────────────────────────────────────

(defn- load-archived-tasks
  "Parse TASKS.archive.org if present. Returns a vector of top-level
  tasks or []."
  [archive-path]
  (or (when (fs/exists? archive-path)
        (when-let [content (try (slurp archive-path) (catch Throwable _ nil))]
          (:tasks (parser/parse-tasks content {:source-path archive-path}))))
      []))

(defn- build-record-reader
  "Memoised `(fn [task] content|nil)` resolving a task's #+IMPORT
  change-record under `project-root`."
  [project-root]
  (let [cache (volatile! {})]
    (fn [task]
      (when-let [import-path (:import-path task)]
        (when-let [src-path (or (:source-path task) project-root)]
          (let [effective (or (:effective-source-content task)
                              (:source-content task) "")
                expanded (parser/expand-org-link-target import-path effective)
                base-dir (if (:from-project-root expanded)
                           project-root
                           (str (fs/parent src-path)))]
            (when-let [abs (paths/resolve-project-path
                             project-root base-dir (:target expanded))]
              (if (contains? @cache abs)
                (get @cache abs)
                (let [content (try (slurp abs) (catch Throwable _ nil))]
                  (vswap! cache assoc abs content)
                  content)))))))))

(defn- scan-row->text [row]
  (let [short-id (some-> (:id row) (subs 0 (min 8 (count (:id row)))))
        prio    (if (:priority row) (str "[#" (:priority row) "] ") "")
        tags    (if (seq (:tags row)) (str " :" (str/join ":" (:tags row)) ":") "")
        record  (cond
                  (nil? (:recordSummary row)) ""
                  (true? (:found (:recordSummary row)))
                  (if (:hasContext row) " … (+ctx)" " …")
                  :else " (no summary)")]
    (str "[" (:status row) "] " short-id "  " prio (:summary row) tags record)))

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
        head-lines (mapv scan-row->text (take cap rows))
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

(defn doctor-cmd [{:keys [opts]}]
  (let [{:keys [project-root tasks selected-id files]} (load-context opts)
        setup-path (str (fs/path project-root "TASKS.setup.org"))
        protocol-files {:setup   (read-protocol-file setup-path)
                        :tasks   (read-protocol-file (:tasks files))
                        :archive (read-protocol-file (:archive files))}
        findings (doctor/run-doctor
                   {:tasks tasks
                    :selected-id selected-id
                    :selected-source-path (:local files)
                    :protocol-files protocol-files})
        counts (doctor/count-by-severity findings)
        wire-findings (mapv finding->wire findings)
        report (doctor/format-findings-report findings)]
    (out/emit-result
      opts
      {:findings wire-findings
       :counts counts
       :text/lines [report]})))

;; ── ot publish / unpublish / archive ────────────────────────────

(defn- mark-not-local-rec [task]
  (-> task (assoc :is-local false)
      (update :children #(mapv mark-not-local-rec %))))

(defn- mark-local-rec [task]
  (-> task (assoc :is-local true)
      (update :children #(mapv mark-local-rec %))))

(defn- top-level-with-id [tasks id]
  (some #(when (= id (parser/get-task-id %)) %) tasks))

(defn- save-file-block!
  "Re-emit `target-path` with `top-level-tasks` serialised under its
  existing content (preserving non-task material)."
  [target-path top-level-tasks]
  (let [original (or (try (slurp target-path) (catch Throwable _ nil)) "")
        stamped  (mapv #(assoc % :source-path target-path) top-level-tasks)
        updated  (parser/serialize-tasks-preserving-file original stamped)]
    (when (not= updated original)
      (let [tmp (str target-path ".tmp")]
        (fs/create-dirs (fs/parent target-path))
        (spit tmp updated)
        (fs/move tmp target-path {:replace-existing true})))))

(defn publish-cmd [{:keys [opts] :as result}]
  (let [{:keys [tasks files]} (load-context opts)
        id (or (:id opts) (first (:args result)))]
    (cond
      (or (nil? id) (str/blank? id))
      (out/emit-error opts
                      {:code "argument-error"
                       :message "ot publish requires a task id."})

      :else
      (if-let [target (top-level-with-id tasks id)]
        (cond
          (not (:is-local target))
          (out/emit-error opts
                          {:code "validation"
                           :message "Task is already in TASKS.org (not local)."})

          :else
          (let [shared    (filterv #(not (:is-local %)) tasks)
                local     (filterv :is-local tasks)
                without   (vec (remove #(= id (parser/get-task-id %)) local))
                published (-> target mark-not-local-rec
                              (assoc :line-number 0 :end-line 0))
                new-shared (conj shared published)]
            (when-not (:dry-run opts)
              (save-file-block! (:tasks files) new-shared)
              (save-file-block! (:local files) without))
            (out/emit-result
              opts
              {:task (task/task->wire published)
               :from (:local files)
               :to (:tasks files)
               :text/lines [(str "Published " (:summary target) " → " (:tasks files))]})))
        (out/emit-error opts
                        {:code "unknown-task"
                         :message (str "No top-level task with :CUSTOM_ID: " id)
                         :details {:id id}})))))

(defn unpublish-cmd [{:keys [opts] :as result}]
  (let [{:keys [tasks files]} (load-context opts)
        id (or (:id opts) (first (:args result)))]
    (cond
      (or (nil? id) (str/blank? id))
      (out/emit-error opts
                      {:code "argument-error"
                       :message "ot unpublish requires a task id."})

      :else
      (if-let [target (top-level-with-id tasks id)]
        (cond
          (:is-local target)
          (out/emit-error opts
                          {:code "validation"
                           :message "Task is already local; nothing to unpublish."})

          :else
          (let [shared      (filterv #(not (:is-local %)) tasks)
                local       (filterv :is-local tasks)
                without     (vec (remove #(= id (parser/get-task-id %)) shared))
                unpublished (-> target mark-local-rec
                                (assoc :line-number 0 :end-line 0))
                new-local   (conj local unpublished)]
            (when-not (:dry-run opts)
              (save-file-block! (:tasks files) without)
              (save-file-block! (:local files) new-local))
            (out/emit-result
              opts
              {:task (task/task->wire unpublished)
               :from (:tasks files)
               :to (:local files)
               :text/lines [(str "Unpublished " (:summary target) " → " (:local files))]})))
        (out/emit-error opts
                        {:code "unknown-task"
                         :message (str "No top-level task with :CUSTOM_ID: " id)
                         :details {:id id}})))))

(def ^:private closed-statuses-set #{"DONE" "CANCELLED"})

(defn- archive-sort-timestamp [task]
  (or (:closed task)
      (some (fn [^String line]
              (when-let [m (re-matches
                             #"(?i)\s*-\s+State\s+\"(?:DONE|CANCELLED)\"\s+from\s+\"[^\"]+\"\s+\[([^\]]+)\]\s*"
                             line)]
                (m 1)))
            (reverse (:logbook-lines task)))
      (some (fn [^String line]
              (when-let [m (re-matches #"(?i)\s*:ARCHIVED:\s*\[([^\]]+)\]\s*" line)]
                (m 1)))
            (:property-lines task))
      "9999-12-31 Zzz 23:59"))

(defn- sort-archived-tasks [tasks]
  (->> (map-indexed vector tasks)
       (sort-by (juxt #(archive-sort-timestamp (second %)) first))
       (mapv second)))

(def ^:private default-archive-preamble
  (str/join "\n"
            ["#+TITLE: Archived Tasks"
             "#+LINK: task file:TASKS.org::#%s"
             "#+LINK: archive file:TASKS.archive.org::#%s"
             "#+SETUPFILE: ./TASKS.local.org"
             "#+SETUPFILE: ./TASKS.setup.org"
             ""
             ""]))

(defn- rewrite-plan-parent-link!
  "Best-effort: rewrite the linked plan's #+PARENT: kind from `task:`
  to `archive:`. Silently skips when the file is out-of-root or
  unreadable."
  [project-root task]
  (when-let [id (parser/get-task-id task)]
    (when-let [import-path (:import-path task)]
      (let [src-path  (or (:source-path task) project-root)
            effective (or (:effective-source-content task)
                          (:source-content task) "")
            expanded (parser/expand-org-link-target import-path effective)
            base-dir (if (:from-project-root expanded)
                       project-root
                       (str (fs/parent src-path)))]
        (when-let [abs (paths/resolve-project-path
                         project-root base-dir (:target expanded))]
          (when (fs/exists? abs)
            (let [original (slurp abs)
                  updated  (parser/rewrite-parent-link-kind original id :archive)]
              (when (not= original updated)
                (let [tmp (str abs ".tmp")]
                  (spit tmp updated)
                  (fs/move tmp abs {:replace-existing true}))))))))))

(defn archive-cmd [{:keys [opts] :as result}]
  (let [{:keys [project-root tasks files]} (load-context opts)
        id (or (:id opts) (first (:args result)))]
    (cond
      (or (nil? id) (str/blank? id))
      (out/emit-error opts
                      {:code "argument-error"
                       :message "ot archive requires a task id."})

      :else
      (if-let [target (top-level-with-id tasks id)]
        (cond
          (:is-local target)
          (out/emit-error opts
                          {:code "validation"
                           :message "Cannot archive local tasks; publish first."})

          (not (contains? closed-statuses-set (:status target)))
          (out/emit-error opts
                          {:code "validation"
                           :message (str "Cannot archive: status is " (:status target)
                                         ", not DONE/CANCELLED.")})

          :else
          (let [archive-path (:archive files)
                stamp (or (:closed target) (parser/format-org-timestamp))
                archive-copy (-> target
                                 (update :property-lines (fnil conj [])
                                         (str ":ARCHIVED: [" stamp "]"))
                                 (assoc :import-children nil
                                        :line-number 0
                                        :end-line 0
                                        :source-path archive-path))
                existing-archive (or (try (slurp archive-path)
                                          (catch Throwable _ nil))
                                     "")
                existing-tasks (if (str/blank? existing-archive)
                                 []
                                 (:tasks (parser/parse-tasks existing-archive
                                                             {:source-path archive-path})))
                archived-tasks (sort-archived-tasks (conj existing-tasks archive-copy))
                archive-content (if (str/blank? existing-archive)
                                  (str default-archive-preamble
                                       (parser/serialize-tasks archived-tasks))
                                  (parser/serialize-tasks-preserving-file
                                    existing-archive archived-tasks))
                remaining-shared (->> tasks
                                      (remove :is-local)
                                      (remove #(= id (parser/get-task-id %)))
                                      vec)]
            (when-not (:dry-run opts)
              (let [tmp (str archive-path ".tmp")]
                (fs/create-dirs (fs/parent archive-path))
                (spit tmp archive-content)
                (fs/move tmp archive-path {:replace-existing true}))
              (save-file-block! (:tasks files) remaining-shared)
              (rewrite-plan-parent-link! project-root target))
            (out/emit-result
              opts
              {:task (task/task->wire archive-copy)
               :archivePath archive-path
               :archivedAt stamp
               :planRewrite (when (:import-path target)
                              {:file (:import-path target)
                               :from (str "task:" id)
                               :to (str "archive:" id)})
               :text/lines [(str "Archived " (:summary target)
                                 " → " archive-path)]})))
        (out/emit-error opts
                        {:code "unknown-task"
                         :message (str "No top-level task with :CUSTOM_ID: " id)
                         :details {:id id}})))))

;; ── ot handoff ────────────────────────────────────────────────────

(defn- mutate-task-and-save
  "Find `id` anywhere in the loaded graph, apply `f` (`task -> task`),
  persist the modified source-root, and return `[updated-task tasks]`
  or `nil` if the task is unknown."
  [{:keys [project-root tasks dry-run?]} id f]
  (when-let [target (task/find-by-id tasks id)]
    (let [updated (f target)
          tree-new (update-task-in-tree tasks id (constantly updated))]
      (when-not dry-run?
        (loader/save-source-roots project-root tree-new))
      [updated tree-new])))

(defn handoff-get-cmd [{:keys [opts] :as result}]
  (let [{:keys [tasks]} (load-context opts)
        id (or (:id opts) (first (:args result)))]
    (if-let [t (task/find-by-id tasks id)]
      (out/emit-result
        opts
        {:taskId id
         :handoff (parser/get-task-handoff t)
         :text/lines [(or (parser/get-task-handoff t)
                          "(no :HANDOFF: set)")]})
      (out/emit-error opts
                      {:code "unknown-task"
                       :message (str "No task with :CUSTOM_ID: " id)}))))

(defn handoff-set-cmd [{:keys [opts] :as result}]
  (let [ctx (load-context opts)
        id (or (:id opts) (first (:args result)))
        text (or (:text opts) (second (:args result)))]
    (cond
      (or (nil? id) (nil? text))
      (out/emit-error opts
                      {:code "argument-error"
                       :message "ot handoff set requires <id> <text>."})
      :else
      (if-let [[updated _]
               (mutate-task-and-save
                 (assoc ctx :dry-run? (:dry-run opts))
                 id #(parser/set-task-handoff % text))]
        (out/emit-result opts
                         {:taskId id
                          :handoff (parser/get-task-handoff updated)
                          :text/lines [(str "Set handoff: " text)]})
        (out/emit-error opts
                        {:code "unknown-task"
                         :message (str "No task with :CUSTOM_ID: " id)})))))

(defn handoff-clear-cmd [{:keys [opts] :as result}]
  (let [ctx (load-context opts)
        id (or (:id opts) (first (:args result)))]
    (if-let [[_ _] (mutate-task-and-save
                     (assoc ctx :dry-run? (:dry-run opts))
                     id #(parser/set-task-handoff % nil))]
      (out/emit-result opts {:taskId id :handoff nil
                             :text/lines ["Cleared handoff."]})
      (out/emit-error opts
                      {:code "unknown-task"
                       :message (str "No task with :CUSTOM_ID: " id)}))))

;; ── ot blocker / ot ready ────────────────────────────────────────

(defn- blocker->wire [b]
  {:raw (:raw b) :kind (name (:kind b)) :ref (:ref b)})

(defn blocker-list-cmd [{:keys [opts] :as result}]
  (let [{:keys [tasks]} (load-context opts)
        id (or (:id opts) (first (:args result)))]
    (if-let [t (task/find-by-id tasks id)]
      (out/emit-result
        opts
        {:taskId id
         :blockers (mapv blocker->wire (parser/get-task-blockers t))
         :text/lines (let [bs (parser/get-task-blockers t)]
                       (if (empty? bs)
                         ["(no blockers)"]
                         (mapv :raw bs)))})
      (out/emit-error opts
                      {:code "unknown-task"
                       :message (str "No task with :CUSTOM_ID: " id)}))))

(defn- normalise-blocker-token
  "Accept either an already-prefixed token (`task:…`, `url:…`, `human: …`,
  `jira:…`) or an unprefixed value that we default to `human:`."
  [^String raw]
  (let [trimmed (str/trim raw)]
    (if (re-find #"(?i)^(task|url|human|jira):" trimmed)
      trimmed
      (str "human: " trimmed))))

(defn blocker-add-cmd [{:keys [opts] :as result}]
  (let [ctx (load-context opts)
        id (or (:id opts) (first (:args result)))
        token (or (:token opts) (second (:args result)))]
    (cond
      (or (nil? id) (nil? token))
      (out/emit-error opts
                      {:code "argument-error"
                       :message "ot blocker add requires <id> <token>."})
      :else
      (if-let [[updated _]
               (mutate-task-and-save
                 (assoc ctx :dry-run? (:dry-run opts))
                 id
                 (fn [t]
                   (let [existing (mapv :raw (parser/get-task-blockers t))]
                     (parser/set-task-blockers t
                                               (conj existing
                                                     (normalise-blocker-token token))))))]
        (out/emit-result opts
                         {:taskId id
                          :blockers (mapv blocker->wire (parser/get-task-blockers updated))
                          :text/lines [(str "Added blocker: " (normalise-blocker-token token))]})
        (out/emit-error opts
                        {:code "unknown-task"
                         :message (str "No task with :CUSTOM_ID: " id)})))))

(defn blocker-remove-cmd [{:keys [opts] :as result}]
  (let [ctx (load-context opts)
        id (or (:id opts) (first (:args result)))
        token (or (:token opts) (second (:args result)))]
    (cond
      (or (nil? id) (nil? token))
      (out/emit-error opts
                      {:code "argument-error"
                       :message "ot blocker remove requires <id> <token>."})
      :else
      (if-let [[updated _]
               (mutate-task-and-save
                 (assoc ctx :dry-run? (:dry-run opts))
                 id
                 (fn [t]
                   (let [existing (mapv :raw (parser/get-task-blockers t))
                         filtered (filterv #(not= % token) existing)]
                     (parser/set-task-blockers t filtered))))]
        (out/emit-result opts
                         {:taskId id
                          :blockers (mapv blocker->wire (parser/get-task-blockers updated))
                          :text/lines [(str "Removed blocker: " token)]})
        (out/emit-error opts
                        {:code "unknown-task"
                         :message (str "No task with :CUSTOM_ID: " id)})))))

(defn ready-cmd [{:keys [opts] :as result}]
  (let [{:keys [tasks]} (load-context opts)
        id (or (:id opts) (first (:args result)))]
    (if-let [t (task/find-by-id tasks id)]
      (let [report (parser/is-task-ready t #(task/find-by-id tasks %))
            gating-wire
            (mapv (fn [{:keys [blocker reason]}]
                    {:blocker (blocker->wire blocker)
                     :reason (name reason)})
                  (:gating report))]
        (out/emit-result
          opts
          {:taskId id
           :ready (:ready report)
           :gating gating-wire
           :text/lines
           (if (:ready report)
             [(str id ": ready")]
             (cons (str id ": not ready (" (count gating-wire) " gating)")
                   (map (fn [g] (str "  - " (get-in g [:blocker :raw])
                                     " [" (:reason g) "]"))
                        gating-wire)))}))
      (out/emit-error opts
                      {:code "unknown-task"
                       :message (str "No task with :CUSTOM_ID: " id)}))))

;; ── ot issue ─────────────────────────────────────────────────────

(defn- task-link-templates [task]
  (let [src (or (:effective-source-content task) (:source-content task) "")]
    (parser/parse-link-templates src)))

(defn- linked-issue->wire [i]
  (cond-> {:rawToken (:raw-token i) :label (:label i) :url (:url i)}
    (:error i) (assoc :error (:error i))))

(defn issue-list-cmd [{:keys [opts] :as result}]
  (let [{:keys [tasks]} (load-context opts)
        id (or (:id opts) (first (:args result)))]
    (if-let [t (task/find-by-id tasks id)]
      (let [templates (task-link-templates t)
            issues (parser/get-linked-issues t templates)]
        (out/emit-result
          opts
          {:taskId id
           :issues (mapv linked-issue->wire issues)
           :text/lines (if (empty? issues)
                         ["(no linked issues)"]
                         (mapv :raw-token issues))}))
      (out/emit-error opts
                      {:code "unknown-task"
                       :message (str "No task with :CUSTOM_ID: " id)}))))

(defn- existing-issue-tokens [task]
  (vec (filter seq
               (or (some-> (parser/get-drawer-property task "LINKED_ISSUES")
                           (str/split #"\s+"))
                   []))))

(defn issue-add-cmd [{:keys [opts] :as result}]
  (let [ctx (load-context opts)
        id (or (:id opts) (first (:args result)))
        token (or (:token opts) (second (:args result)))]
    (cond
      (or (nil? id) (nil? token))
      (out/emit-error opts
                      {:code "argument-error"
                       :message "ot issue add requires <id> <token>."})
      :else
      (if-let [[updated _]
               (mutate-task-and-save
                 (assoc ctx :dry-run? (:dry-run opts))
                 id
                 (fn [t]
                   (parser/set-linked-issues t (conj (existing-issue-tokens t) token))))]
        (out/emit-result opts
                         {:taskId id
                          :tokens (existing-issue-tokens updated)
                          :text/lines [(str "Added linked-issue token: " token)]})
        (out/emit-error opts
                        {:code "unknown-task"
                         :message (str "No task with :CUSTOM_ID: " id)})))))

(defn issue-remove-cmd [{:keys [opts] :as result}]
  (let [ctx (load-context opts)
        id (or (:id opts) (first (:args result)))
        token (or (:token opts) (second (:args result)))]
    (cond
      (or (nil? id) (nil? token))
      (out/emit-error opts
                      {:code "argument-error"
                       :message "ot issue remove requires <id> <token>."})
      :else
      (if-let [[updated _]
               (mutate-task-and-save
                 (assoc ctx :dry-run? (:dry-run opts))
                 id
                 (fn [t]
                   (parser/set-linked-issues
                     t (filterv #(not= % token) (existing-issue-tokens t)))))]
        (out/emit-result opts
                         {:taskId id
                          :tokens (existing-issue-tokens updated)
                          :text/lines [(str "Removed linked-issue token: " token)]})
        (out/emit-error opts
                        {:code "unknown-task"
                         :message (str "No task with :CUSTOM_ID: " id)})))))

(defn issue-urls-cmd [{:keys [opts] :as result}]
  (let [{:keys [tasks]} (load-context opts)
        id (or (:id opts) (first (:args result)))]
    (if-let [t (task/find-by-id tasks id)]
      (let [templates (task-link-templates t)
            issues (parser/get-linked-issues t templates)
            urls (vec (keep :url issues))]
        (out/emit-result
          opts
          {:taskId id
           :urls urls
           :text/lines (if (empty? urls)
                         ["(no resolvable URLs)"]
                         urls)}))
      (out/emit-error opts
                      {:code "unknown-task"
                       :message (str "No task with :CUSTOM_ID: " id)}))))

;; ── ot record path / create ──────────────────────────────────────

(def ^:private default-plans-dir "./design/log")

(defn- slugify [^String s]
  (let [norm (-> (or s "")
                 str/lower-case
                 (str/replace #"[^a-z0-9]+" "-")
                 (str/replace #"^-+|-+$" ""))
        capped (if (> (count norm) 40) (subs norm 0 40) norm)
        trimmed (str/replace capped #"-+$" "")]
    (if (seq trimmed) trimmed "plan")))

(defn- plan-dir-from-template [template]
  (cond
    (nil? template) default-plans-dir
    (not (str/starts-with? template "file:")) default-plans-dir
    :else (let [stripped (subs template (count "file:"))
                before (if (str/includes? stripped "%s")
                         (subs stripped 0 (str/index-of stripped "%s"))
                         stripped)
                trimmed (str/replace before #"/+$" "")]
            (if (seq trimmed) trimmed default-plans-dir))))

(defn- read-plans-dir [project-root tasks-path]
  (try
    (let [content (slurp tasks-path)
          effective (try ((requiring-resolve 'org-tasks.effective/read-effective-org-content)
                          project-root tasks-path content)
                         (catch Throwable _ content))]
      (plan-dir-from-template (get (parser/parse-link-templates effective) "plan")))
    (catch Throwable _ default-plans-dir)))

(defn- join-plan-dir [dir filename]
  (let [trimmed (str/replace (or dir ".") #"/+$" "")
        trimmed (if (str/blank? trimmed) "." trimmed)]
    (cond
      (or (= trimmed ".") (= trimmed "./")) (str "./" filename)
      (str/starts-with? trimmed "./") (str "./" (str (fs/path (subs trimmed 2) filename)))
      :else (str (fs/path trimmed filename)))))

(defn- suggest-plan-path [task project-root tasks-path]
  (let [today (str (java.time.LocalDate/now))
        slug  (slugify (or (:summary task) "plan"))
        filename (str today "-" slug ".org")
        plans-dir (read-plans-dir project-root tasks-path)]
    (join-plan-dir plans-dir filename)))

(defn record-path-cmd [{:keys [opts] :as result}]
  (let [{:keys [project-root tasks files]} (load-context opts)
        id (or (:id opts) (first (:args result)))]
    (if-let [t (task/find-by-id tasks id)]
      (let [suggested (suggest-plan-path t project-root (:tasks files))]
        (out/emit-result opts {:taskId id :suggested suggested
                               :text/lines [suggested]}))
      (out/emit-error opts
                      {:code "unknown-task"
                       :message (str "No task with :CUSTOM_ID: " id)}))))

(defn- safe-org-link-description? [^String summary]
  (and summary (seq summary) (not (re-find #"[\[\]\r\n]" summary))))

(defn- parent-link-target [task kind]
  (when-let [id (parser/get-task-id task)]
    (let [summary (:summary task)]
      (if (safe-org-link-description? summary)
        (str "[[" (name kind) ":" id "][" summary "]]")
        (str "[[" (name kind) ":" id "]]")))))

(defn- scaffold-plan-content [task setup-file-rel-path]
  (let [parent-link (parent-link-target task :task)]
    (str/join "\n"
              (filter some?
                      [(str "#+TITLE: " (:summary task))
                       (str "#+DATE: " (parser/format-org-date))
                       (when parent-link (str "#+PARENT: " parent-link))
                       (str "#+SETUPFILE: " setup-file-rel-path)
                       ""
                       "* Summary"
                       ""
                       "* Plan"
                       ""
                       "* Implementation"
                       ""
                       ""]))))

(defn- git-log-commits
  "Shell out to `git log --oneline --since/--until` and return commit
  SHAs. Returns nil when git is unavailable or the call fails."
  [project-root since until]
  (try
    (let [args (cond-> ["git" "log" "--oneline"]
                 since (concat ["--since" since])
                 until (concat ["--until" until])
                 true  vec)
          shell-fn (requiring-resolve 'babashka.process/shell)
          {:keys [exit out]} (shell-fn {:out :string :err :string
                                         :continue true :dir project-root}
                                        (first args) (rest args))]
      (when (zero? exit)
        (->> (str/split-lines out)
             (mapv (fn [line] (first (str/split line #"\s" 2))))
             (filterv seq))))
    (catch Throwable _ nil)))

(defn record-create-cmd [{:keys [opts] :as result}]
  (let [{:keys [project-root tasks files]} (load-context opts)
        id   (or (:id opts) (first (:args result)))
        mode (or (:mode opts) :proactive)]
    (cond
      (or (nil? id) (str/blank? id))
      (out/emit-error opts
                      {:code "argument-error"
                       :message "ot record create requires a task id."})
      :else
      (if-let [target (task/find-by-id tasks id)]
        (let [rel-path (or (:path opts) (suggest-plan-path target project-root (:tasks files)))
              abs (paths/resolve-project-path project-root project-root rel-path)]
          (cond
            (nil? abs)
            (out/emit-error opts
                            {:code "path-outside-project"
                             :message (str "Plan path resolves outside project root: " rel-path)})

            :else
            (let [target-dir  (str (fs/parent abs))
                  setup-path  (str (fs/path project-root "TASKS.setup.org"))
                  setup-file-rel (str (fs/relativize target-dir setup-path))
                  content     (scaffold-plan-content target setup-file-rel)
                  existed?    (fs/exists? abs)
                  ;; Attach #+IMPORT to the parent task if missing.
                  rel-to-plans (str (fs/file-name abs))
                  import-raw  (or (:import-raw target)
                                  (str "[[plan:" rel-to-plans "]]"))
                  import-path (or (:import-path target)
                                  (str "plan:" rel-to-plans))
                  updated-target (-> target
                                     (assoc :import-path import-path
                                            :import-raw import-raw))
                  tree-new (update-task-in-tree tasks id (constantly updated-target))
                  scope (when (= mode :retrospective)
                          (let [started (parser/get-task-started target)
                                closed  (:closed target)]
                            {:since started
                             :until closed
                             :commits (git-log-commits project-root started closed)}))]
              (when-not (:dry-run opts)
                (fs/create-dirs (fs/parent abs))
                (when-not existed?
                  (spit abs content))
                (loader/save-source-roots project-root tree-new))
              (out/emit-result
                opts
                {:taskId id
                 :recordPath abs
                 :importRaw import-raw
                 :created (not existed?)
                 :absorbedSubtasks false
                 :scope scope
                 :text/lines
                 [(str (if existed? "Updated #+IMPORT → " "Scaffolded ") abs)]}))))
        (out/emit-error opts
                        {:code "unknown-task"
                         :message (str "No task with :CUSTOM_ID: " id)})))))

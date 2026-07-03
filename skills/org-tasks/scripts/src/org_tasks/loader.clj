(ns org-tasks.loader
  "Filesystem loader and writer for the org-tasks graph.

  Owns the `TASKS.org` + `TASKS.local.org` + `#+IMPORT:` chain
  resolution. Pure side-effects (read/slurp, atomic write) live here
  so commands stay thin.

  Public surface:

    load-graph             :: project-root files -> {:tasks, :selected-id, ..}
    safe-slurp             :: path -> content-or-nil
    atomic-write           :: path content -> nil
    save-source-roots      :: project-root tasks-by-source-path -> nil
    write-selected-id      :: project-root local-path id-or-nil -> nil"
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [org-tasks.effective :as effective]
            [org-tasks.links :as links]
            [org-tasks.parser :as parser]
            [org-tasks.tree :as tree]))

;; ── Reading ────────────────────────────────────────────────────────

(defn safe-slurp
  "Read `path`, returning nil when it does not exist and throwing a
  structured `unreadable` error on any other read failure (permissions,
  I/O). Distinguishing \"missing\" from \"broken\" matters for callers
  such as `ot archive` that must not treat an unreadable
  `TASKS.archive.org` as an empty one and silently regenerate its
  preamble."
  [path]
  (if-not (fs/exists? path)
    nil
    (try (slurp path)
         (catch Throwable t
           (throw (ex-info (str "Cannot read " path ": " (ex-message t))
                            {:code :unreadable :file (str path)} t))))))

(defn- load-org [project-root file-path]
  (when-let [content (safe-slurp file-path)]
    (let [effective (effective/read-effective-org-content
                      project-root file-path content)]
      {:content content :effective effective})))

(defn- mark-local
  "Stamp `:is-local true` on every task in `tasks` (including children)."
  [tasks]
  (mapv (fn [t]
          (-> t
              (assoc :is-local true)
              (update :children mark-local)))
        tasks))

(defn- resolve-import
  "Resolve a `#+IMPORT:` value into an absolute path under `project-root`,
  or nil when the target escapes."
  [project-root source-path effective-content raw]
  (links/resolve-link-target project-root source-path effective-content raw))

(defn- load-imports
  "Append tasks from file-level `#+IMPORT:` declarations onto `acc`."
  [project-root source-path effective-content file-imports]
  (reduce
    (fn [acc raw]
      (if-let [abs (resolve-import project-root source-path effective-content raw)]
        (if-let [{:keys [content effective]} (load-org project-root abs)]
          (let [{children :tasks}
                (parser/parse-tasks content
                                    {:source-path abs
                                     :source-content content
                                     :effective-source-content effective})]
            (into acc children))
          acc)
        acc))
    []
    file-imports))

(defn- attach-import-children
  "Recursively resolve `#+IMPORT:` change-records into `:import-children`."
  [project-root tasks visited]
  (mapv
    (fn [task]
      (let [task (update task :children
                         #(attach-import-children project-root % visited))]
        (if-not (:import-path task)
          task
          (let [abs (links/resolve-task-link-target project-root task (:import-path task))]
            (cond
              (nil? abs)
              (assoc task :import-error "Import path resolves outside project root")

              (contains? @visited abs)
              ;; Cycle / already loaded — leave task as-is to keep the
              ;; graph finite, just like loadLinkedPlans memoisation.
              task

              :else
              (do (vswap! visited conj abs)
                  (if-let [{:keys [content effective]} (load-org project-root abs)]
                    (let [{children :tasks}
                          (parser/parse-tasks content
                                              {:source-path abs
                                               :source-content content
                                               :effective-source-content effective})
                          children (attach-import-children
                                     project-root children visited)]
                      (assoc task :import-children children :import-error nil))
                    (assoc task :import-error
                           (str "Cannot read change-record file: " abs)))))))))
    tasks))

(defn load-graph
  "Load the full task graph for `project-root` given the resolved
  `protocol-files` map (`:tasks`, `:local`, `:archive`).

  Returns:

    {:tasks       [task...]
     :selected-id <uuid|nil>
     :files       protocol-files}

  Tasks from `TASKS.local.org` are appended after shared tasks and
  marked `:is-local true`. `#+IMPORT:` chains are resolved
  recursively into `:import-children`. Missing files are silent
  no-ops."
  [^String project-root protocol-files]
  (let [tasks-path (:tasks protocol-files)
        local-path (:local protocol-files)
        shared-data (load-org project-root tasks-path)
        shared-parsed (when shared-data
                        (parser/parse-tasks (:content shared-data)
                                            {:source-path tasks-path
                                             :source-content (:content shared-data)
                                             :effective-source-content (:effective shared-data)}))
        shared-tasks (vec (or (:tasks shared-parsed) []))
        file-imports (or (:file-imports shared-parsed) [])
        imported-shared (when (seq file-imports)
                          (load-imports project-root tasks-path
                                        (:effective shared-data) file-imports))
        shared (into shared-tasks (or imported-shared []))
        local-data (load-org project-root local-path)
        local-tasks (when local-data
                      (mark-local
                        (:tasks (parser/parse-tasks (:content local-data)
                                                    {:source-path local-path
                                                     :source-content (:content local-data)
                                                     :effective-source-content (:effective local-data)}))))
        combined (vec (concat shared (or local-tasks [])))
        with-imports (attach-import-children project-root combined (volatile! #{}))
        selected-id (some-> local-data :content parser/parse-selected-keyword)]
    {:tasks with-imports
     :selected-id selected-id
     :files protocol-files}))

;; ── Writing ────────────────────────────────────────────────────────

(defn atomic-write [^String path ^String content]
  ;; Unique per-call temp name: two concurrent `ot` invocations writing
  ;; the same target must not collide on a shared `path.tmp`.
  (let [tmp (str path ".tmp-" (System/nanoTime))]
    (spit tmp content)
    (fs/move tmp path {:replace-existing true})))

(defn conflict!
  "Throw a structured `conflict` error: `path`'s on-disk content no
  longer matches the snapshot a mutator loaded it from."
  [path]
  (throw (ex-info (str "File changed on disk since it was loaded: " path)
                  {:code :conflict :file (str path)})))

(defn assert-unchanged!
  "Throw `conflict!` when `path`'s current on-disk content differs from
  `baseline` (the content read at load time; nil means the file was
  absent at load)."
  [path baseline]
  (when (not= (safe-slurp path) baseline)
    (conflict! path)))

(defn write-selected-id
  "Atomic write of `#+SELECTED:` to `local-path`. Pass nil to deselect;
  only the `#+SELECTED:` line is touched — existing task headings,
  imports, and other keywords are preserved."
  [^String local-path id-or-nil]
  (let [existing (or (safe-slurp local-path) "")
        selected-line (when id-or-nil (str "#+SELECTED: " id-or-nil))
        updated
        (cond
          (and selected-line
               (re-find #"(?im)^#\+SELECTED:" existing))
          (str/replace existing #"(?im)^#\+SELECTED:.*$" selected-line)

          selected-line
          (if (seq existing)
            (str selected-line "\n" existing)
            (str selected-line "\n"))

          :else
          (str/replace existing #"(?im)^#\+SELECTED:.*(?:\r?\n)?" ""))]
    (fs/create-dirs (fs/parent local-path))
    (atomic-write local-path updated)))

(defn- collect-file-roots-by-source
  "Walk the full task graph (including `:children` and
  `:import-children`) and group tasks tagged `:file-root? true` by
  their `:source-path`. Each entry's vector is sorted by
  `:line-number` so `serialize-tasks-preserving-file` can match
  supplied roots against parsed originals."
  [tasks]
  (->> (tree/all-tasks tasks)
       (filter #(and (:file-root? %) (:source-path %)))
       (group-by :source-path)
       (into {}
             (map (fn [[src roots]]
                    [src (vec (sort-by #(or (:line-number %) 0) roots))])))))

(defn save-source-roots
  "Persist a mutated task graph back to disk.

  Walks the full graph (top-level tasks + `:children` +
  `:import-children`) and, for each source file, gathers the tasks
  tagged `:file-root? true` by `parser/parse-tasks` — i.e. the
  tasks that were originally parsed as top-level roots of that file.
  Re-emits each file via `parser/serialize-tasks-preserving-file`
  against the file's last-known source content. Atomic writes;
  per-file no-op when the serialized output matches the original.

  This is what makes mutations to tasks living inside
  `#+IMPORT:`-linked plan files (attached to the in-memory graph as
  `:import-children`) persist back to the plan file, not just to
  TASKS.org.

  Throws a `conflict` ex-info (see [[assert-unchanged!]]) when a
  file's current on-disk bytes no longer match the load-time snapshot
  it is about to be rewritten from. Optional `known-baselines` (path
  -> load-time content-or-nil) supplies baselines for files that must
  be visited even when they end up with zero current roots."
  ([^String project-root tasks] (save-source-roots project-root tasks {}))
  ([^String project-root tasks known-baselines]
   ;; `known-baselines` (path -> load-time content-or-nil) forces a file
   ;; to be visited even when it ends up with zero current roots —
   ;; e.g. `ot archive`/`ot publish`/`ot unpublish` moving a file's only
   ;; task elsewhere. Without this, a file whose last root task departs
   ;; would have no entry in `by-file` and would never be rewritten to
   ;; drop the stale heading.
   (let [by-file (collect-file-roots-by-source tasks)
         all-paths (into (set (keys by-file)) (keys known-baselines))]
     (doseq [src all-paths]
       (let [roots (get by-file src [])
             baseline (if (contains? known-baselines src)
                        (get known-baselines src)
                        (some :source-content roots))
             original (or baseline "")
             updated  (parser/serialize-tasks-preserving-file original roots)]
         (when (not= updated original)
           (assert-unchanged! src baseline)
           (atomic-write src updated)))))))

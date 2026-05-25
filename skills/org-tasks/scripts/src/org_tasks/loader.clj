(ns org-tasks.loader
  "Filesystem loader and writer for the org-tasks graph.

  Owns the `TASKS.org` + `TASKS.local.org` + `#+IMPORT:` chain
  resolution. Pure side-effects (read/slurp, atomic write) live here
  so commands stay thin.

  Public surface:

    load-graph             :: project-root files -> {:tasks, :selected-id, ..}
    save-source-roots      :: project-root tasks-by-source-path -> nil
    write-selected-id      :: project-root local-path id-or-nil -> nil"
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [org-tasks.effective :as effective]
            [org-tasks.parser :as parser]
            [org-tasks.paths :as paths]))

;; ── Reading ────────────────────────────────────────────────────────

(defn- safe-slurp [path]
  (try (slurp path) (catch Throwable _ nil)))

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
  (when (and raw (seq raw))
    (let [expanded (parser/expand-org-link-target raw effective-content)
          base-dir (if (:from-project-root expanded)
                     project-root
                     (str (fs/parent source-path)))]
      (paths/resolve-project-path project-root base-dir (:target expanded)))))

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
          (let [src-path  (or (:source-path task) project-root)
                effective (or (:effective-source-content task)
                              (:source-content task) "")
                abs       (resolve-import project-root src-path effective
                                          (:import-path task))]
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

(defn- atomic-write [^String path ^String content]
  (let [tmp (str path ".tmp")]
    (spit tmp content)
    (fs/move tmp path {:replace-existing true})))

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
  (let [out (volatile! {})]
    (letfn [(walk [ts]
              (doseq [t ts]
                (when (and (:file-root? t) (:source-path t))
                  (vswap! out update (:source-path t) (fnil conj []) t))
                (walk (:children t))
                (when (:import-children t)
                  (walk (:import-children t)))))]
      (walk tasks))
    (into {}
          (map (fn [[src roots]]
                 [src (vec (sort-by #(or (:line-number %) 0) roots))]))
          @out)))

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
  TASKS.org."
  [^String project-root tasks]
  (let [by-file (collect-file-roots-by-source tasks)]
    (doseq [[src roots] by-file]
      (let [original (or (some :source-content roots)
                         (safe-slurp src)
                         "")
            updated  (parser/serialize-tasks-preserving-file original roots)]
        (when (not= updated original)
          (atomic-write src updated))))))

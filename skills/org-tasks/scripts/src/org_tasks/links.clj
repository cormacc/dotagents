(ns org-tasks.links
  "Org link target resolution helpers shared by loaders and commands."
  (:require [babashka.fs :as fs]
            [org-tasks.parser :as parser]
            [org-tasks.paths :as paths]))

(defn resolve-link-target
  "Resolve an org link/import target under `project-root`.

  Expands `raw` using link templates from `effective-content`, chooses a
  base directory from either the project root or source file parent, then
  delegates sandbox enforcement to `paths/resolve-project-path`."
  [project-root source-path effective-content raw]
  (when (and raw (seq raw))
    (let [expanded (parser/expand-org-link-target raw (or effective-content ""))
          base-dir (if (:from-project-root expanded)
                     project-root
                     (str (fs/parent source-path)))]
      (paths/resolve-project-path project-root base-dir (:target expanded)))))

(defn resolve-task-link-target
  "Resolve a task-shaped link/import path, using effective source content when present."
  [project-root task raw]
  (when-let [source-path (or (:source-path task) project-root)]
    (resolve-link-target project-root
                         source-path
                         (or (:effective-source-content task)
                             (:source-content task)
                             "")
                         raw)))

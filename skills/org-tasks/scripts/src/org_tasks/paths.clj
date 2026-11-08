(ns org-tasks.paths
  "Project-root sandboxing.

  Resolves user-supplied paths against a project root and rejects
  anything that escapes after symlink realpath resolution. Supports
  paths that do not exist yet (so future scaffold targets can be
  validated before creation).

  Public surface:

    within-root?               :: path root -> bool
    resolve-existing-or-parent :: path -> absolute path
    resolve-project-path       :: root base-dir candidate -> path | nil"
  (:require [babashka.fs :as fs]))

(defn within-root?
  "True when `path` resolves under `root` (or is identical to it).

  Babashka's `fs/relativize` returns a `..`-prefixed path when `path`
  is outside `root`; on Windows it may also return an absolute path.
  Both shapes mean 'out of root'."
  [^String path ^String root]
  (let [rel (str (fs/relativize root path))]
    (or (= rel "")
        (and (not (.startsWith rel ".."))
             (not (fs/absolute? rel))))))

(defn resolve-existing-or-parent
  "Realpath of an existing path; otherwise the realpath of the nearest
  existing ancestor joined with the missing path components, so a symlinked
  ancestor above several missing directories is still resolved."
  ^String [^String path]
  (loop [p (fs/absolutize path) missing ()]
    (if-let [real (try (fs/real-path p) (catch Throwable _ nil))]
      (str (fs/normalize (apply fs/path real missing)))
      (if-let [parent (fs/parent p)]
        (recur parent (cons (str (fs/file-name p)) missing))
        (str (fs/absolutize path))))))

(defn resolve-project-path
  "Resolve `candidate` (absolute or `base-dir`-relative) under `root`,
  returning the realpath string, or nil when the result escapes the
  project root after symlink resolution."
  ^String [^String root ^String base-dir ^String candidate]
  (let [root-real (resolve-existing-or-parent root)
        abs       (if (fs/absolute? candidate)
                    candidate
                    (str (fs/absolutize (fs/path base-dir candidate))))
        real      (resolve-existing-or-parent abs)]
    (when (within-root? real root-real)
      real)))

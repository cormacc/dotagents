(ns org-tasks.paths
  "Project-root sandboxing.

  Port of `pi/extensions/tasks/paths.ts`. Resolves user-supplied paths
  against a project root and rejects anything that escapes after
  symlink realpath resolution. Supports paths whose parent exists but
  whose target does not yet (so future scaffold targets can be
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
  existing ancestor joined with the original basename."
  ^String [^String path]
  (let [p (fs/path path)]
    (try
      (str (fs/real-path p))
      (catch Throwable _
        (try
          (let [parent (fs/real-path (fs/parent p))]
            (str (fs/absolutize (fs/path (str parent) (fs/file-name p)))))
          (catch Throwable _
            (str (fs/absolutize p))))))))

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

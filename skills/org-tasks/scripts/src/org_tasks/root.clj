(ns org-tasks.root
  "Project-root resolution for the `ot` CLI.

  Resolution order (per
  `skills/org-tasks/SKILL.md` § Locating `TASKS.org`):

    1. Explicit `--root` option, if provided.
    2. `git rev-parse --show-toplevel` against the current working directory.
    3. Fallback to the current working directory.

  Do not walk up parent directories looking for `TASKS.org` — a parent
  project's index is not this project's."
  (:require [babashka.fs :as fs]
            [babashka.process :as p]))

(defn- git-root [^String cwd]
  (try
    (let [{:keys [exit out]}
          (p/shell {:out :string :err :string :continue true :dir cwd}
                   "git" "rev-parse" "--show-toplevel")]
      (when (zero? exit)
        (let [trimmed (.trim ^String out)]
          (when-not (.isEmpty trimmed)
            trimmed))))
    (catch Throwable _ nil)))

(defn resolve-root
  "Resolve the project root from CLI options and the current directory.

  Returns an absolute path string. Never throws; falls back to `cwd`
  when neither `--root` nor `git rev-parse` produces a usable answer."
  ([opts]
   (resolve-root opts (System/getProperty "user.dir")))
  ([opts cwd]
   (let [candidate (or (:root opts) (git-root cwd) cwd)]
     (str (fs/absolutize candidate)))))

(defn resolve-protocol-files
  "Return `{:tasks, :local, :archive}` absolute paths, with each entry
  taking the matching `--tasks`/`--local`/`--archive` override when
  provided and otherwise sitting at `<root>/TASKS{.local,.archive}.org`."
  [opts root]
  {:tasks   (str (fs/absolutize (or (:tasks opts)
                                    (fs/path root "TASKS.org"))))
   :local   (str (fs/absolutize (or (:local opts)
                                    (fs/path root "TASKS.local.org"))))
   :archive (str (fs/absolutize (or (:archive opts)
                                    (fs/path root "TASKS.archive.org"))))})

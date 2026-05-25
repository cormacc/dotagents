(ns org-tasks.root
  "Project-root resolution for the `ot` CLI.

  Resolution order:

    1. Explicit `--root` option, if provided.
    2. Current working directory or nearest ancestor containing `TASKS.org`.
    3. Fallback to the current working directory when no `TASKS.org` is found.

  This intentionally has no git dependency: task memory is anchored by
  `TASKS.org`, not by repository metadata."
  (:require [babashka.fs :as fs]))

(defn- tasks-org-root
  "Return the nearest directory at or above `cwd` containing `TASKS.org`,
  or nil when no ancestor has one."
  [cwd]
  (loop [dir (fs/absolutize cwd)]
    (cond
      (fs/exists? (fs/path dir "TASKS.org"))
      (str dir)

      :else
      (let [parent (fs/parent dir)]
        (when (and parent (not= (str parent) (str dir)))
          (recur parent))))))

(defn resolve-root
  "Resolve the project root from CLI options and the current directory.

  Returns an absolute path string. Never throws; falls back to `cwd`
  when neither `--root` nor a `TASKS.org` ancestor produces a usable answer."
  ([opts]
   (resolve-root opts (System/getProperty "user.dir")))
  ([opts cwd]
   (let [candidate (or (:root opts) (tasks-org-root cwd) cwd)]
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

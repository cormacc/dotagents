(ns org-tasks.commands.record
  "`ot` record path / record create command handlers."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str]
            [org-tasks.effective :as effective]
            [org-tasks.loader :as loader]
            [org-tasks.output :as out]
            [org-tasks.parser :as parser]
            [org-tasks.paths :as paths]
            [org-tasks.tree :as tree]
            [org-tasks.commands.util :refer [positional-arg load-context
                                             resolve-required-id]]))

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
          effective (try (effective/read-effective-org-content
                          project-root tasks-path content)
                         (catch Throwable _ content))]
      (plan-dir-from-template (get (parser/parse-link-templates effective) "plan")))
    (catch Throwable _ default-plans-dir)))

(defn- join-plan-dir [dir filename]
  (let [trimmed (str/replace (or dir ".") #"/+$" "")
        trimmed (if (str/blank? trimmed) "." trimmed)]
    (cond
      (or (= trimmed ".") (= trimmed "./")) (str "./" filename)
      (str/starts-with? trimmed "./") (str "./" (fs/path (subs trimmed 2) filename))
      :else (str (fs/path trimmed filename)))))

(defn- suggest-plan-path [task project-root tasks-path]
  (let [today (str (java.time.LocalDate/now))
        slug  (slugify (or (:summary task) "plan"))
        filename (str today "-" slug ".org")
        plans-dir (read-plans-dir project-root tasks-path)]
    (join-plan-dir plans-dir filename)))

(defn record-path-cmd [{:keys [opts] :as result}]
  (let [{:keys [project-root tasks files]} (load-context opts)
        id (positional-arg result :id)
        t (resolve-required-id tasks id opts)
        suggested (suggest-plan-path t project-root (:tasks files))]
    (out/emit-result opts {:taskId (parser/get-task-id t) :suggested suggested
                           :text/lines [suggested]})))

(defn- safe-org-link-description? [^String summary]
  (and summary (seq summary) (not (re-find #"[\[\]\r\n]" summary))))

(defn- parent-link-target [task kind]
  (when-let [id (parser/get-task-id task)]
    (let [summary (:summary task)]
      (if (safe-org-link-description? summary)
        (str "[[" (name kind) ":" id "][" summary "]]")
        (str "[[" (name kind) ":" id "]]")))))

(defn- relevel-for-plan
  "Return `task` re-levelled so direct children of `parent-level`
  become level-2 plan tasks under `* Plan`, preserving deeper nesting."
  [parent-level task]
  (let [level (max 2 (inc (- (:level task) parent-level)))]
    (-> task
        (assoc :level level)
        (update :children #(mapv (partial relevel-for-plan parent-level) %))
        ;; Import-expanded children are loader state, not on-disk children to
        ;; copy into a newly scaffolded record.
        (dissoc :import-children))))

(defn- migrated-plan-block [parent-task]
  (let [children (mapv #(relevel-for-plan (:level parent-task) %)
                       (:children parent-task))]
    (when (seq children)
      (str/trim-newline (parser/serialize-tasks children)))))

(defn- scaffold-plan-content [task setup-file-rel-path]
  (let [parent-link (parent-link-target task :task)
        plan-block  (migrated-plan-block task)]
    (str/join "\n"
              (filter some?
                      [(str "#+TITLE: " (:summary task))
                       (str "#+DATE: " (parser/format-org-date))
                       (when parent-link (str "#+PARENT: " parent-link))
                       (str "#+SETUPFILE: " setup-file-rel-path)
                       ""
                       "* Intent"
                       ""
                       "* Summary"
                       ""
                       "* Plan"
                       plan-block
                       ""
                       "* Implementation"
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
          {:keys [exit out]} (process/shell {:out :string :err :string
                                              :continue true :dir project-root}
                                             (first args) (rest args))]
      (when (zero? exit)
        (->> (str/split-lines out)
             (mapv (fn [line] (first (str/split line #"\s" 2))))
             (filterv seq))))
    (catch Throwable _ nil)))

(defn record-create-cmd [{:keys [opts] :as result}]
  (let [{:keys [project-root tasks files]} (load-context opts)
        id   (positional-arg result :id)
        mode (or (:mode opts) :proactive)]
    (cond
      (or (nil? id) (str/blank? id))
      (out/emit-error opts
                      {:code "argument-error"
                       :message "ot record create requires a task id."})
      :else
      (let [target (resolve-required-id tasks id opts)
            full-id (parser/get-task-id target)
            rel-path (or (:path opts) (suggest-plan-path target project-root (:tasks files)))
            abs (paths/resolve-project-path project-root project-root rel-path)]
        (cond
          (nil? abs)
          (out/emit-error opts
                          {:code "path-outside-project"
                           :message (str "Plan path resolves outside project root: " rel-path)})

          :else
          (let [target-dir  (str (fs/parent abs))
                ;; Canonicalise the setup path the same way `abs` was
                ;; (resolve-project-path realpaths its result), so the
                ;; relativize operands share a base even when the project
                ;; root is under a symlink (e.g. macOS /var -> /private/var).
                ;; Mixing a realpath'd dir with a raw one produced a broken
                ;; climbing `#+SETUPFILE:` link.
                setup-path  (or (paths/resolve-project-path project-root project-root "TASKS.setup.org")
                                (str (fs/path project-root "TASKS.setup.org")))
                setup-file-rel (str (fs/relativize target-dir setup-path))
                content     (scaffold-plan-content target setup-file-rel)
                existed?    (fs/exists? abs)
                ;; Attach #+IMPORT to the parent task if missing.
                rel-to-plans (str (fs/file-name abs))
                import-raw  (or (:import-raw target)
                                (str "[[plan:" rel-to-plans "]]"))
                import-path (or (:import-path target)
                                (str "plan:" rel-to-plans))
                absorbed? (and (not existed?) (seq (:children target)))
                updated-target (cond-> (assoc target
                                             :import-path import-path
                                             :import-raw import-raw)
                                 absorbed? (assoc :children []))
                tree-new (tree/update-by-id tasks full-id (constantly updated-target))
                scope (when (= mode :retrospective)
                        (let [started (parser/get-task-started target)
                              closed  (:closed target)]
                          {:since started
                           :until closed
                           :commits (git-log-commits project-root started closed)}))]
            (when-not (:dry-run opts)
              (fs/create-dirs (fs/parent abs))
              (when-not existed?
                (loader/atomic-write abs content))
              (loader/save-source-roots project-root tree-new))
            (out/emit-result
              opts
              {:taskId full-id
               :recordPath abs
               :importRaw import-raw
               :created (not existed?)
               :absorbedSubtasks (boolean absorbed?)
               :scope scope
               :text/lines
               [(str (if existed? "Updated #+IMPORT → " "Scaffolded ") abs)]})))))))

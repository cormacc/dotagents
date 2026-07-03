(ns org-tasks.commands.archive-publish
  "`ot` publish / unpublish / archive command handlers."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [org-tasks.lifecycle :as lifecycle]
            [org-tasks.links :as links]
            [org-tasks.loader :as loader]
            [org-tasks.output :as out]
            [org-tasks.parser :as parser]
            [org-tasks.task :as task]
            [org-tasks.tree :as tree]
            [org-tasks.commands.util :as util :refer [positional-arg load-context
                                                        resolve-required-top-level-id]]))

;; ── ot publish / unpublish / archive ────────────────────────────

(defn- mark-not-local-rec [task]
  (-> task (assoc :is-local false)
      (update :children #(mapv mark-not-local-rec %))))

(defn- mark-local-rec [task]
  (-> task (assoc :is-local true)
      (update :children #(mapv mark-local-rec %))))

(defn- top-level-with-id [tasks id opts]
  (resolve-required-top-level-id tasks id opts))

(defn- subtree-task-ids [root-task]
  (letfn [(walk [t]
            (cons (parser/get-task-id t)
                  (mapcat walk (tree/children t))))]
    (set (keep identity (walk root-task)))))

(defn- move-locality-cmd [{:keys [opts] :as result}
                           {:keys [op want-local? moved-fn from-key to-key already-msg missing-msg verb]}]
  (let [{:keys [project-root tasks files]} (load-context opts)
        id (positional-arg result :id)]
    (cond
      (or (nil? id) (str/blank? id))
      (out/emit-error opts {:code "argument-error" :message missing-msg})

      :else
      (let [target (top-level-with-id tasks id opts)
            full-id (parser/get-task-id target)]
        (if (not= want-local? (boolean (:is-local target)))
          (out/emit-error opts {:code "validation" :message already-msg})
          ;; Move by re-stamping `:source-path`/`:line-number` on the
          ;; task in place and handing the whole graph to
          ;; `save-source-roots`: it regroups by each task's *current*
          ;; `:source-path`, so the old file drops the task (no longer
          ;; among its roots) and the new file gains it as a fresh
          ;; append — correct even when the target came from a
          ;; file-level `#+IMPORT:` file rather than TASKS.org/.local.
          (let [from-path (from-key files)
                to-path (to-key files)
                moved (-> target moved-fn
                          (assoc :line-number 0 :end-line 0
                                 :source-path to-path))
                tree-new (tree/update-by-id tasks full-id (constantly moved))
                ;; Both endpoints must be visited even if one ends up
                ;; with zero roots (e.g. moving a file's only task),
                ;; and the moved task's stale `:source-content` must
                ;; not stand in for the destination file's baseline.
                known-baselines {from-path (loader/safe-slurp from-path)
                                 to-path (loader/safe-slurp to-path)}]
            (when-not (:dry-run opts)
              (util/guard-write! opts
                #(loader/save-source-roots project-root tree-new known-baselines)))
            (out/emit-result
              opts
              {:task (task/task->wire moved)
               :from from-path
               :to to-path
               :text/lines [(str verb " " (:summary target) " → " to-path)]})))))))

(defn publish-cmd [result]
  (move-locality-cmd result {:op :publish
                             :want-local? true
                             :moved-fn mark-not-local-rec
                             :from-key :local
                             :to-key :tasks
                             :missing-msg "ot publish requires a task id."
                             :already-msg "Task is already in TASKS.org (not local)."
                             :verb "Published"}))

(defn unpublish-cmd [result]
  (move-locality-cmd result {:op :unpublish
                             :want-local? false
                             :moved-fn mark-local-rec
                             :from-key :tasks
                             :to-key :local
                             :missing-msg "ot unpublish requires a task id."
                             :already-msg "Task is already local; nothing to unpublish."
                             :verb "Unpublished"}))

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
             "#+LINK: plan file:design/log/%s"
             "#+LINK: proj file:%s"
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
      (when-let [abs (links/resolve-task-link-target project-root task import-path)]
        (when (fs/exists? abs)
          (let [original (slurp abs)
                updated  (parser/rewrite-parent-link-kind original id :archive)]
            (when (not= original updated)
              (loader/atomic-write abs updated))))))))

(defn archive-cmd [{:keys [opts] :as result}]
  (let [{:keys [project-root tasks files selected-id]} (load-context opts)
        id (positional-arg result :id)]
    (cond
      (or (nil? id) (str/blank? id))
      (out/emit-error opts
                      {:code "argument-error"
                       :message "ot archive requires a task id."})

      :else
      (let [target (top-level-with-id tasks id opts)
            full-id (parser/get-task-id target)]
        (cond
          (:is-local target)
          (out/emit-error opts
                          {:code "validation"
                           :message "Cannot archive local tasks; publish first."})

          (not (contains? lifecycle/closed-statuses (:status target)))
          (out/emit-error opts
                          {:code "validation"
                           :message (str "Cannot archive: status is " (:status target)
                                         ", not DONE/CANCELLED.")})

          :else
          (util/guard! opts
            (fn []
              (let [archive-path (:archive files)
                    stamp (or (:closed target) (parser/format-org-timestamp))
                    archive-copy (-> target
                                     (update :property-lines (fnil conj [])
                                             (str ":ARCHIVED: [" stamp "]"))
                                     (assoc :import-children nil
                                            :line-number 0
                                            :end-line 0
                                            :source-path archive-path))
                    ;; `safe-slurp` throws `unreadable` on a read failure
                    ;; (as opposed to a missing file) so a broken
                    ;; TASKS.archive.org aborts here instead of silently
                    ;; regenerating the preamble as if it were empty.
                    existing-archive (or (loader/safe-slurp archive-path) "")
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
                    ;; Remove the archived task from the full graph and let
                    ;; `save-source-roots` regroup by each remaining task's
                    ;; *own* `:source-path` — correct whether it lived in
                    ;; TASKS.org or a file-level `#+IMPORT:` plan file.
                    remaining-tasks (->> tasks
                                        (remove #(= full-id (parser/get-task-id %)))
                                        vec)
                    ;; The archived task's own source file must be
                    ;; visited even when it ends up with zero remaining
                    ;; roots (e.g. it was the file's only task).
                    source-path (:source-path target)
                    known-baselines {source-path (loader/safe-slurp source-path)}
                    selection-cleared? (boolean (and selected-id
                                                     (contains? (subtree-task-ids target)
                                                                selected-id)))]
                (when-not (:dry-run opts)
                  (do
                    (fs/create-dirs (fs/parent archive-path))
                    (loader/atomic-write archive-path archive-content))
                  (loader/save-source-roots project-root remaining-tasks known-baselines)
                  (rewrite-plan-parent-link! project-root target)
                  (when selection-cleared?
                    (loader/write-selected-id (:local files) nil)))
                (out/emit-result
                  opts
                  {:task (task/task->wire archive-copy)
                   :archivePath archive-path
                   :archivedAt stamp
                   :selectionCleared (and (not (:dry-run opts)) selection-cleared?)
                   :planRewrite (when (:import-path target)
                                  {:file (:import-path target)
                                   :from (str "task:" full-id)
                                   :to (str "archive:" full-id)})
                   :text/lines [(str "Archived " (:summary target)
                                     " → " archive-path)]})))))))))

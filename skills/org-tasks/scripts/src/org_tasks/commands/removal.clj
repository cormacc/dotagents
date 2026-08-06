(ns org-tasks.commands.removal
  "Integrity-preserving task-subtree removal and dangling-blocker repair."
  (:require [clojure.string :as str]
            [org-tasks.loader :as loader]
            [org-tasks.output :as out]
            [org-tasks.parser :as parser]
            [org-tasks.task :as task]
            [org-tasks.tree :as tree]
            [org-tasks.commands.util :as util :refer [load-context positional-arg
                                                      resolve-required-id]]))

(defn- blocker->wire [blocker]
  {:raw (:raw blocker) :kind (name (:kind blocker)) :ref (:ref blocker)})

(defn- task->impact [t]
  {:id (parser/get-task-id t)
   :summary (:summary t)
   :status (:status t)
   :sourcePath (:source-path t)})

(defn- unchecked-criteria [subtree]
  (vec
   (mapcat
    (fn [t]
      (keep-indexed
       (fn [_ line]
         (when-let [[_ criterion] (re-matches #"^- \[ \] (.+)$" line)]
           {:taskId (parser/get-task-id t)
            :taskSummary (:summary t)
            :sourcePath (:source-path t)
            :taskLine (:line-number t)
            :criterion criterion}))
       (str/split-lines (or (:description t) ""))))
    subtree)))

(defn- resolved-task [tasks blocker]
  (when (= :task (:kind blocker))
    (let [result (task/find-by-id-or-prefix tasks (:ref blocker))]
      (:match result))))

(defn- inbound-blockers [tasks target-ids]
  (vec
   (mapcat
    (fn [t]
      (when-not (contains? target-ids (parser/get-task-id t))
        (for [blocker (parser/get-task-blockers t)
              :let [resolved (resolved-task tasks blocker)]
              :when (and resolved
                         (contains? target-ids (parser/get-task-id resolved)))]
          {:taskId (parser/get-task-id t)
           :taskSummary (:summary t)
           :sourcePath (:source-path t)
           :blocker (blocker->wire blocker)})))
    (tree/all-tasks tasks))))

(defn- source-baselines [tasks]
  (into {}
        (keep (fn [t]
                (when-let [path (:source-path t)]
                  [path (:source-content t)])))
        tasks))

(defn removal-impact
  "Pure removal preview for an eligible target in an active task graph.

  The returned sources include both the deleted subtree and every possible
  inbound-blocker owner so callers can preflight the complete write set before
  any mutation."
  [tasks selected-id target]
  (let [subtree (tree/subtree target)
        target-ids (tree/subtree-ids target)
        inbound (inbound-blockers tasks target-ids)
        sources (source-baselines (concat subtree
                                          (keep #(task/find-by-id tasks (:taskId %)) inbound)))]
    {:targetId (parser/get-task-id target)
     :subtree (mapv task->impact subtree)
     :uncheckedCriteria (unchecked-criteria subtree)
     :inboundBlockers inbound
     :affectedFiles (vec (sort (keys sources)))
     :baselines sources
     :selection {:selectedId selected-id
                 :cleared (boolean (contains? target-ids selected-id))}}))

(defn- public-impact [impact dry-run pruned]
  (assoc (select-keys impact [:targetId :subtree :uncheckedCriteria :inboundBlockers
                              :affectedFiles :selection])
         :prunedBlockers pruned
         :dryRun (boolean dry-run)))

(defn- prune-inbound-blockers [tasks inbound]
  (reduce
   (fn [updated {:keys [taskId blocker]}]
     (tree/update-by-id
      updated taskId
      (fn [t]
        (parser/set-task-blockers
         t
         (remove #(= (:raw blocker) (:raw %)) (parser/get-task-blockers t))))))
   tasks
   inbound))

(defn remove-cmd [{:keys [opts] :as result}]
  (let [{:keys [project-root tasks files selected-id selected-content]} (load-context opts)
        id (positional-arg result :id)]
    (cond
      (str/blank? id)
      (out/emit-error opts {:code "argument-error" :message "ot remove requires a task id."})

      :else
      (let [target (resolve-required-id tasks id opts)
            full-id (parser/get-task-id target)]
        (if (empty? (tree/path-to tasks full-id))
          (out/emit-error opts
                          {:code "top-level-root"
                           :message "Cannot remove a protocol top-level root; use CANCELLED/DONE plus ot archive."})
          (let [preview (removal-impact tasks selected-id target)
                selection-cleared? (get-in preview [:selection :cleared])
                impact (cond-> preview
                         selection-cleared?
                         (-> (update :affectedFiles
                                     #(vec (sort (conj (set %) (:local files)))))
                             (assoc-in [:baselines (:local files)] selected-content)))
                inbound (:inboundBlockers impact)
                prune? (:prune-blockers opts)
                dry-run? (:dry-run opts)
                public (public-impact impact dry-run? (if prune? inbound []))]
            (cond
              dry-run?
              (out/emit-result opts public)

              (not (:yes opts))
              (out/emit-error opts
                              {:code "confirmation-required"
                               :message "ot remove is destructive; re-run with --yes after reviewing the impact."
                               :details public})

              (and (seq inbound) (not prune?))
              (out/emit-error opts
                              {:code "inbound-blockers"
                               :message "Cannot remove task subtree while inbound task blockers remain; pass --prune-blockers to remove them."
                               :details public})

              :else
              (util/guard-write!
               opts
               (fn []
                 (let [without-target (tree/remove-by-id tasks full-id)
                       updated (if prune?
                                 (prune-inbound-blockers without-target inbound)
                                 without-target)
                       baselines (:baselines impact)]
                   ;; Check target source, every inbound owner, and the local
                   ;; selection owner before either saver performs a write.
                   (loader/preflight-baselines! baselines)
                   (loader/save-source-roots project-root updated baselines)
                   (when selection-cleared?
                     ;; The graph saver may itself have rewritten TASKS.local.org
                     ;; when the removed subtree was local. Re-read that known
                     ;; post-save state so selection cleanup does not compare
                     ;; against the now-obsolete load-time baseline.
                     (let [post-save-local (loader/safe-slurp (:local files))]
                       (loader/write-selected-id (:local files) nil post-save-local)))
                   (out/emit-result opts public)))))))))))

(defn dangling-blocker-impact
  "Pure report of explicit or legacy task blockers that do not resolve
  uniquely in `tasks`. Ambiguous references are deliberately retained."
  [tasks]
  (vec
   (mapcat
    (fn [t]
      (for [blocker (parser/get-task-blockers t)
            :let [resolution (when (= :task (:kind blocker))
                               (task/find-by-id-or-prefix tasks (:ref blocker)))]
            :when (and resolution
                       (nil? (:match resolution))
                       (nil? (:ambiguous resolution)))]
        {:taskId (parser/get-task-id t)
         :taskSummary (:summary t)
         :sourcePath (:source-path t)
         :blocker (blocker->wire blocker)}))
    (tree/all-tasks tasks))))

(defn- prune-dangling-blockers [tasks pruned]
  (reduce
   (fn [updated {:keys [taskId blocker]}]
     (tree/update-by-id
      updated taskId
      (fn [t]
        (parser/set-task-blockers
         t
         (remove #(= (:raw blocker) (:raw %)) (parser/get-task-blockers t))))))
   tasks
   pruned))

(defn blocker-prune-cmd [{:keys [opts]}]
  (let [{:keys [project-root tasks]} (load-context opts)
        pruned (dangling-blocker-impact tasks)
        dry-run? (:dry-run opts)
        result {:pruned pruned :dryRun (boolean dry-run?)}]
    (cond
      dry-run?
      (out/emit-result opts result)

      (and (seq pruned) (not (:yes opts)))
      (out/emit-error opts
                      {:code "confirmation-required"
                       :message "ot blocker prune is destructive; re-run with --yes after reviewing the impact."
                       :details result})

      (empty? pruned)
      (out/emit-result opts result)

      :else
      (util/guard-write!
       opts
       (fn []
         (let [updated (prune-dangling-blockers tasks pruned)
               baselines (source-baselines
                          (keep #(task/find-by-id tasks (:taskId %)) pruned))]
           (loader/preflight-baselines! baselines)
           (loader/save-source-roots project-root updated baselines)
           (out/emit-result opts result)))))))

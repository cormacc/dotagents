(ns org-tasks.tree
  "Shared traversal helpers for task graphs."
  (:require [org-tasks.parser :as parser]))

(defn children
  "Return child tasks, including imported children unless `:imports? false`."
  ([task] (children task {}))
  ([task {:keys [imports?] :or {imports? true}}]
   (concat (:children task []) (when imports? (:import-children task [])))))

(defn all-tasks
  "Return every task reachable from `tasks` in depth-first order."
  ([tasks] (all-tasks tasks {}))
  ([tasks opts]
   (mapcat (fn [t] (cons t (all-tasks (children t opts) opts))) tasks)))

(defn find-by-id
  ([tasks id] (find-by-id tasks id {}))
  ([tasks id opts]
   (when id
     (some #(when (= id (parser/get-task-id %)) %) (all-tasks tasks opts)))))

(defn path-to
  "Return ancestors of `target-id` in root-to-parent order."
  [tasks target-id]
  (letfn [(walk [ancestors task]
            (cond
              (= target-id (parser/get-task-id task)) ancestors
              :else (some #(walk (conj ancestors task) %) (children task))))]
    (or (some #(walk [] %) tasks) [])))

(defn subtree
  "Return `task` and every reachable descendant in depth-first order."
  [task]
  (vec (all-tasks [task])))

(defn subtree-ids
  "Return the non-nil :CUSTOM_ID: values reachable from `task`."
  [task]
  (set (keep parser/get-task-id (subtree task))))

(defn remove-by-id
  "Return `tasks` with the first matching task subtree removed.

  Walks ordinary and import children, preserving the remaining graph shape."
  [tasks id]
  (->> tasks
       (keep (fn [task]
               (when-not (= id (parser/get-task-id task))
                 (cond-> (update task :children #(remove-by-id (or % []) id))
                   (:import-children task)
                   (update :import-children #(remove-by-id (or % []) id))))))
       vec))

(defn update-by-id
  "Replace the first task with `:CUSTOM_ID:` = `id` using `(f task)`."
  [tasks id f]
  (mapv
    (fn [t]
      (if (= id (parser/get-task-id t))
        (f t)
        (-> t
            (update :children #(update-by-id (or % []) id f))
            (cond->
              (:import-children t)
              (update :import-children #(update-by-id (or % []) id f))))))
    tasks))

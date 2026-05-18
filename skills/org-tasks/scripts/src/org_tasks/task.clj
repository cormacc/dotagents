(ns org-tasks.task
  "Internal task map ↔ JSON contract translation.

  The CLI's parser produces idiomatic Clojure maps with kebab-case
  keys; the machine-output contract in
  `skills/org-tasks/scripts/docs/contract.md` specifies camelCase
  keys. This namespace owns the translation so command modules stay
  free of envelope shape concerns.

  Public surface:

    task->wire             :: task -> wire-task         (recursive)
    flatten-tree           :: tasks -> rows
    find-by-id             :: tasks id -> task | nil
    find-top-level-root    :: tasks task -> task | nil"
  (:require [org-tasks.parser :as parser]))

(defn- task-children
  "Walk both `:children` and `:import-children`."
  [task]
  (concat (:children task []) (:import-children task [])))

(defn find-by-id
  "Depth-first lookup by `:CUSTOM_ID:`. Walks `:children` and
  `:import-children` alike. Returns nil when absent."
  [tasks id]
  (when id
    (some (fn [t]
            (if (= id (parser/get-task-id t))
              t
              (find-by-id (task-children t) id)))
          tasks)))

(defn find-top-level-root
  "Return the top-level root whose subtree contains `target`."
  [tasks target]
  (letfn [(contains-target? [t]
            (or (identical? t target)
                (some contains-target? (task-children t))))]
    (some #(when (contains-target? %) %) tasks)))

(defn task->wire
  "Convert an internal task map to the JSON contract shape (camelCase).

  Optional `parent-id` is set on the wire output's `:parentId` field
  for flat-row consumers; nested children are unaffected."
  ([task] (task->wire task nil))
  ([task parent-id]
   (let [id        (parser/get-task-id task)
         link-tpls (when-let [src (:effective-source-content task)]
                     (parser/parse-link-templates src))
         issues    (when link-tpls
                     (parser/get-linked-issues task link-tpls))
         children  (mapv #(task->wire % id) (:children task []))
         imports   (mapv #(task->wire % id) (:import-children task []))]
     (cond-> {:id              id
              :status          (:status task)
              :priority        (:priority task)
              :summary         (:summary task)
              :tags            (or (:tags task) [])
              :level           (:level task)
              :sourcePath      (:source-path task)
              :line            (:line-number task)
              :local           (boolean (:is-local task))
              :importPath      (:import-path task)
              :importRaw       (:import-raw task)
              :closed          (:closed task)
              :started         (parser/get-task-started task)
              :blockedBy       (mapv :raw (parser/get-task-blockers task))
              :handoff         (parser/get-task-handoff task)
              :linkedIssues    (mapv (fn [i]
                                       (cond-> {:rawToken (:raw-token i)
                                                :label    (:label i)
                                                :url      (:url i)}
                                         (:error i) (assoc :error (:error i))))
                                     (or issues []))
              :children        children
              :importChildren  imports}
       parent-id (assoc :parentId parent-id)))))

(defn flatten-tree
  "Flatten a wire-task tree (output of `task->wire`) to a vector of
  `{:parentId ...}`-bearing rows in depth-first walker order. Skips
  `children` / `importChildren` in the emitted rows but preserves all
  scalar fields."
  [wire-tasks]
  (let [out (volatile! [])
        walk (fn walk [tasks parent-id]
               (doseq [t tasks]
                 (vswap! out conj
                         (-> t
                             (dissoc :children :importChildren)
                             (assoc :parentId parent-id)))
                 (walk (:children t) (:id t))
                 (walk (:importChildren t) (:id t))))]
    (walk wire-tasks nil)
    @out))

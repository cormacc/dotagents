(ns org-tasks.task
  "Internal task map ↔ JSON contract translation.

  The CLI's parser produces idiomatic Clojure maps with kebab-case
  keys; the machine-output contract in
  `skills/org-tasks/scripts/docs/contract.md` specifies camelCase
  keys. This namespace owns the translation so command modules stay
  free of envelope shape concerns.

  Public surface:

    task->wire             :: task -> wire-task         (recursive)
    collect-sources        :: tasks -> {path source-map}
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
  for flat-row consumers; nested children are unaffected. Pass
  `{:include-content? false}` when a command emits shared source-file
  content separately (for example `ot list`)."
  ([task] (task->wire task nil {}))
  ([task parent-id] (task->wire task parent-id {}))
  ([task parent-id {:keys [include-content?] :or {include-content? true} :as opts}]
   (let [id        (parser/get-task-id task)
         link-tpls (when-let [src (:effective-source-content task)]
                     (parser/parse-link-templates src))
         issues    (when link-tpls
                     (parser/get-linked-issues task link-tpls))
         children  (mapv #(task->wire % id opts) (:children task []))
         imports   (mapv #(task->wire % id opts) (:import-children task []))]
     (cond-> {:id              id
              :status          (:status task)
              :priority        (:priority task)
              :summary         (:summary task)
              :description     (:description task)
              :tags            (or (:tags task) [])
              :level           (:level task)
              :propertyLines   (or (:property-lines task) [])
              :logbookLines    (or (:logbook-lines task) [])
              :sourcePath      (:source-path task)
              :line            (:line-number task)
              :endLine         (:end-line task)
              :local           (boolean (:is-local task))
              :importPath      (:import-path task)
              :importRaw       (:import-raw task)
              :importError     (:import-error task)
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
       include-content? (assoc :sourceContent (:source-content task)
                               :effectiveSourceContent (:effective-source-content task))
       parent-id (assoc :parentId parent-id)))))

(defn collect-sources
  "Return a path-keyed map of source file contents for a task graph.
  `ot list` uses this to avoid repeating large file content on every
  wire task while still giving UI clients enough context to resolve
  link templates and rewrite imported plan files."
  [tasks]
  (let [sources (volatile! {})]
    (letfn [(walk [task]
              (when-let [path (:source-path task)]
                (vswap! sources update path
                        (fn [existing]
                          (cond-> (or existing {})
                            (:source-content task)
                            (assoc :sourceContent (:source-content task))
                            (:effective-source-content task)
                            (assoc :effectiveSourceContent (:effective-source-content task))))))
              (doseq [child (task-children task)]
                (walk child)))]
      (doseq [task tasks]
        (walk task))
      @sources)))

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

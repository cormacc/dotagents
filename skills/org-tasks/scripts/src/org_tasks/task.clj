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
    find-by-id-or-prefix   :: tasks id-prefix -> {:match t} | {:ambiguous [t]} | {:none true}
    find-top-level-by-id-or-prefix :: tasks id-prefix -> resolver result
    find-top-level-root    :: tasks task -> task | nil
    compact-id             :: id n -> short-id (first-n … last-n)
    build-short-ids        :: tasks -> {full-id -> short-id-string}"
  (:require [clojure.string :as str]
            [org-tasks.parser :as parser]))

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

(def ^:private min-id-prefix-length 4)
(def ^:private min-edge-length 4)
(def ^:private max-edge-length 16)
(def ^:private ellipsis "…")
(def ^:private ascii-ellipsis "...")

(defn- all-tasks
  "Return every task reachable from `tasks` in depth-first order."
  [tasks]
  (mapcat (fn [t] (cons t (all-tasks (task-children t)))) tasks))

(defn- id-prefix-match? [raw-id task]
  (when-let [id (parser/get-task-id task)]
    (str/starts-with? id raw-id)))

(defn- compact-form-input
  "If `raw-id` looks like a compact short-id (`prefix…suffix` or
  `prefix...suffix`), return `[prefix suffix]`; otherwise nil."
  [^String raw-id]
  (when raw-id
    (let [norm (-> raw-id
                   (str/replace ellipsis "\u0001")
                   (str/replace ascii-ellipsis "\u0001"))
          parts (str/split norm #"\u0001")]
      (when (and (= 2 (count parts))
                 (every? seq parts))
        parts))))

(defn- compact-match? [^String prefix ^String suffix task]
  (when-let [id (parser/get-task-id task)]
    (and (str/starts-with? id prefix)
         (str/ends-with? id suffix))))

(defn- resolve-id-candidate
  "Resolve `raw-id` against `candidates`.

  Exact matches win before prefix matching. Prefix matches require at
  least four characters so accidental one-character selections never
  bind to a graph-dependent task. Compact short-ids of the form
  `prefix…suffix` (or `prefix...suffix`) match tasks whose `:CUSTOM_ID:`
  starts with `prefix` and ends with `suffix`.

  Return shape is one of:

    {:match task}
    {:ambiguous [task ...]}
    {:none true}"
  [candidates raw-id]
  (let [raw-id (some-> raw-id str str/trim)]
    (cond
      (str/blank? raw-id)
      {:none true}

      :else
      (if-let [exact (some #(when (= raw-id (parser/get-task-id %)) %) candidates)]
        {:match exact}
        (if-let [[prefix suffix] (compact-form-input raw-id)]
          (let [matches (vec (filter #(compact-match? prefix suffix %) candidates))]
            (case (count matches)
              0 {:none true}
              1 {:match (first matches)}
              {:ambiguous matches}))
          (if (< (count raw-id) min-id-prefix-length)
            {:none true}
            (let [matches (vec (filter #(id-prefix-match? raw-id %) candidates))]
              (case (count matches)
                0 {:none true}
                1 {:match (first matches)}
                {:ambiguous matches}))))))))

(defn compact-id
  "Return a compact display form for `id` of the shape
  `first-n … last-n`. Falls back to the raw id when the id is too short
  to compact."
  [^String id n]
  (let [n (max min-edge-length (or n min-edge-length))
        len (count id)]
    (if (or (nil? id) (<= len (+ (* 2 n) 1)))
      id
      (str (subs id 0 n) ellipsis (subs id (- len n))))))

(defn build-short-ids
  "Return `{full-id -> short-id}` for every task reachable from `tasks`.

  The smallest edge length (≥ 4) is chosen such that every full id
  produces a unique compact form in the graph. Tasks that share a
  shared-prefix / shared-suffix pattern collectively expand to the
  smallest disambiguating size; other tasks share that size for visual
  consistency. Falls back to full ids when expansion reaches the
  configured ceiling without resolving collisions."
  [tasks]
  (let [ids (->> (all-tasks tasks)
                 (keep parser/get-task-id)
                 distinct
                 vec)]
    (if (empty? ids)
      {}
      (loop [n min-edge-length]
        (let [m (into {} (map (fn [id] [id (compact-id id n)]) ids))]
          (cond
            (= (count (set (vals m))) (count ids))
            m

            (>= n max-edge-length)
            (into {} (map (fn [id] [id id]) ids))

            :else
            (recur (inc n))))))))

(defn find-by-id-or-prefix
  "Resolve `raw-id` anywhere in the task graph.

  Walks both `:children` and `:import-children`. Exact `:CUSTOM_ID:`
  matches win before prefix matching. Prefix matches require at least
  four characters. Returns `{:match task}`, `{:ambiguous [task ...]}`,
  or `{:none true}`."
  [tasks raw-id]
  (resolve-id-candidate (all-tasks tasks) raw-id))

(defn find-top-level-by-id-or-prefix
  "Resolve `raw-id` only against the top-level task roots.

  Used by operations that intentionally cannot target imported or nested
  tasks (archive, publish, unpublish). Return shape matches
  [[find-by-id-or-prefix]]."
  [tasks raw-id]
  (resolve-id-candidate tasks raw-id))

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

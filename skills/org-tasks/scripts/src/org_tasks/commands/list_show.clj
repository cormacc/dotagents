(ns org-tasks.commands.list-show
  "`ot` list / show / select / selected command handlers."
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [org-tasks.links :as links]
            [org-tasks.loader :as loader]
            [org-tasks.output :as out]
            [org-tasks.parser :as parser]
            [org-tasks.section :as section]
            [org-tasks.styling :as style]
            [org-tasks.task :as task]
            [org-tasks.tree :as tree]
            [org-tasks.commands.util :refer [positional-arg load-context
                                             resolve-required-id]]))

;; ── ot list ─────────────────────────────────────────────────────────

(defn- row-depths [rows]
  (second
    (reduce (fn [[depths out] row]
              (let [depth (if-let [pid (:parentId row)]
                            (inc (get depths pid -1))
                            0)]
                [(if-let [id (:id row)] (assoc depths id depth) depths)
                 (conj out depth)]))
            [{} []]
            rows)))

(defn- compute-tree-prefixes
  "Return a vector parallel to `rows` of left-margin tree-drawing
  prefixes inserted between the per-row sel/local/space block (always
  at columns 0–2) and the row's STATUS column.

  Top-level rows get an empty prefix — STATUS lands at column 3 right
  after sel/local/space.

  Subtasks render a 2-char tree column per intermediate ancestor
  followed by the row's own `├─` / `└─` branch glyph, so the branch
  glyph's first character lines up under the first character of the
  parent row's STATUS column. The row's STATUS abuts the branch glyph
  with no separating space.

  Per-ancestor segments are 2 chars:
  - `│ ` when that ancestor still has more siblings to come.
  - `  ` when it was the last sibling.

  The topmost (depth-1) ancestor contributes nothing — the subtask's
  own sel/local/space block at columns 0–2 already fills the visual
  gap below the top-level row's sel/local/space.

  Rows whose `:parentId` does not appear in `rows` are treated as
  roots so filtered views remain readable."
  [rows]
  (let [n (count rows)
        id->idx (into {} (keep-indexed (fn [i r] (when (:id r) [(:id r) i])) rows))
        sibling-key (fn [pid]
                      (or (when (and pid (contains? id->idx pid)) pid) :root))
        last? (let [seen (volatile! #{})
                    acc (volatile! {})]
                (doseq [i (reverse (range n))]
                  (let [k (sibling-key (:parentId (nth rows i)))]
                    (if (contains? @seen k)
                      (vswap! acc assoc i false)
                      (do (vswap! acc assoc i true)
                          (vswap! seen conj k)))))
                @acc)]
    (mapv
      (fn [i row]
        (let [ancestors (loop [acc []
                               pid (:parentId row)]
                          (if-let [pi (get id->idx pid)]
                            (recur (conj acc pi)
                                   (:parentId (nth rows pi)))
                            acc))]
          (if (empty? ancestors)
            ""
            (let [reversed (vec (reverse ancestors))
                  intermediate-segs
                  (map (fn [a-idx]
                         (if (get last? a-idx)
                           style/tree-gap
                           style/tree-pipe))
                       (rest reversed))
                  self-seg (if (get last? i)
                             style/tree-last
                             style/tree-branch)]
              (apply str (concat intermediate-segs [self-seg]))))))
      (range n) rows)))

(defn- format-list-row [opts tree-prefix row selected-id]
  (let [sel-mark (if (and (:id row) (= (:id row) selected-id))
                   (style/selected-marker opts "★")
                   " ")
        local    (if (:local row) (style/local-marker opts "⊠") " ")
        status   (style/status opts (:status row) (format "%-9s" (:status row)))
        prio     (if (:priority row)
                   (str (style/priority opts (:priority row)) " ")
                   "")
        tags     (if (seq (:tags row))
                   (str " " (style/tag-cluster opts (:tags row)))
                   "")]
    (str sel-mark local " "
         (style/gutter opts tree-prefix)
         status " "
         (style/styled opts :gray (task/id-prefix (:id row))) "  "
         prio (:summary row) tags)))

(defn list-cmd [{:keys [opts]}]
  (let [{:keys [project-root tasks selected-id files]} (load-context opts)
        sources    (task/collect-sources tasks)
        wire-tasks (mapv #(task/task->wire % nil {:include-content? false}) tasks)
        rows       (task/flatten-tree wire-tasks)
        status-filter (set (:status-filter opts))
        match?     (fn [row]
                     (or (empty? status-filter)
                         (contains? status-filter (:status row))))
        filtered   (vec (filter match? rows))
        level-cap  (:levels opts)
        kept       (if level-cap
                     (->> (map vector filtered (row-depths filtered))
                          (filter (fn [[_row depth]] (<= depth level-cap)))
                          (mapv first))
                     filtered)
        tree-prefixes (compute-tree-prefixes kept)]
    (out/emit-result
      opts
      {:tree wire-tasks
       :rows kept
       :selectedId selected-id
       :root project-root
       :files files
       :sources sources
       :text/lines (cons "   STATUS    id        task"
                         (map #(format-list-row opts %2 %1 selected-id)
                              kept tree-prefixes))})))

;; ── ot show ─────────────────────────────────────────────────────────

(defn- resolve-id-arg [_opts dispatch-result selected-id]
  (let [raw (positional-arg dispatch-result :id)]
    (cond
      (= raw "selected") selected-id
      (and raw (seq raw)) raw)))


(defn- resolve-record-path [project-root task]
  (links/resolve-task-link-target project-root task (:import-path task)))

(defn- record-summary [project-root task]
  (when-let [abs (resolve-record-path project-root task)]
    (when (fs/exists? abs)
      (let [content (slurp abs)
            sections (section/list-sections content)]
        {:path abs
         :sections sections
         :hasContext (boolean (some #(= "Context" %) sections))
         :hasOpenQuestions (boolean (some #(= "Open questions" %) sections))}))))

(defn show-cmd [{:keys [opts] :as result}]
  (let [{:keys [project-root tasks selected-id]} (load-context opts)
        id (resolve-id-arg opts result selected-id)]
    (cond
      (nil? id)
      (out/emit-error opts
                      {:code "unknown-task"
                       :message "ot show requires a task id (or 'selected')."})

      :else
      (let [t (resolve-required-id tasks id opts)
            full-id (parser/get-task-id t)
            include-content? (boolean (:include-content opts))
            wire (task/task->wire t nil {:include-content? include-content?})
            ancestor-tasks (tree/path-to tasks full-id)
            ancestors (mapv #(task/task->wire % nil {:include-content? false})
                            ancestor-tasks)
            record-task (some #(when (:import-path %) %)
                              (reverse (conj ancestor-tasks t)))]
        (out/emit-result
          opts
          {:task wire
           :ancestors ancestors
           :record (record-summary project-root record-task)
           :text/lines
           [(str (style/status opts (:status wire)) " "
                 (if (:priority wire) (str (style/priority opts (:priority wire)) " ") "")
                 (:summary wire))
            (str "  id        " (:id wire))
            (str "  source    " (:sourcePath wire))
            (when (:importPath wire)
              (str "  plan      " (or (:importRaw wire) (:importPath wire))))
            (when (:closed wire)
              (str "  closed    " (:closed wire)))
            (when (:started wire)
              (str "  started   " (:started wire)))
            (when (seq (:tags wire))
              (str "  tags      " (style/tag-cluster opts (:tags wire))))
            (when (seq (:blockedBy wire))
              (str "  blockers  " (str/join ", " (:blockedBy wire))))
            (when (:handoff wire)
              (str "  handoff   " (:handoff wire)))]})))))

;; ── ot select / ot selected ────────────────────────────────────────

(defn select-cmd [{:keys [opts] :as result}]
  (let [{:keys [tasks selected-id files]} (load-context opts)
        clear? (:clear opts)
        id     (when-not clear?
                 (positional-arg result :id))]
    (cond
      (and (not clear?) (nil? id))
      (out/emit-error opts
                      {:code "argument-error"
                       :message "ot select requires an id (or --clear)."})

      :else
      (let [target (when-not clear? (resolve-required-id tasks id opts))
            new-id (some-> target parser/get-task-id)]
        (when-not (:dry-run opts)
          (loader/write-selected-id (:local files) new-id))
        (out/emit-result
          opts
          {:selectedId new-id
           :previousId selected-id
           :file (:local files)
           :text/lines [(if new-id
                          (str "Selected " new-id
                               (when selected-id (str " (was " selected-id ")")))
                          "Selection cleared.")]})))))

(defn selected-cmd [{:keys [opts] :as result}]
  (let [{:keys [tasks selected-id]} (load-context opts)]
    (if (and selected-id (task/find-by-id tasks selected-id))
      (show-cmd (assoc-in result [:opts :id] selected-id))
      (out/emit-result
        opts
        {:selected nil
         :selectedId selected-id
         :text/lines ["No task currently selected."]}))))

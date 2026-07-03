(ns org-tasks.scan
  "Prior-art summary scanner.

  Walks active and archived task graphs (plus their `#+IMPORT:` chains)
  and returns a flat `[ScanRow]` capturing each task's heading metadata
  plus its change-record's `* Summary` body (or 'missing' / 'no record').

  Pure helper: path resolution and file I/O happen in the calling
  command via a `read-change-record` callback so the scanner stays
  fs-free for tests."
  (:require [org-tasks.parser :as parser]
            [org-tasks.section :as section]
            [org-tasks.tree :as tree]))

(def default-max-body-chars 500)
(def ^:private truncation-sentinel "\u2026")

(defn- truncate-body [^String body ^long max-n]
  (cond
    (<= max-n 0) ""
    (<= (count body) max-n) body
    :else (str (subs body 0 (max 0 (dec max-n))) truncation-sentinel)))

(defn- matches-tag-filter? [task tag-filter]
  (or (empty? tag-filter)
      (let [want (set tag-filter)]
        (some #(contains? want %) (:tags task)))))

(defn- build-row [task id read-change-record max-body-chars]
  (let [import-path  (:import-path task)
        record-text  (when import-path (read-change-record task))
        record-summary
        (cond
          (nil? import-path) nil
          (nil? record-text) {:found false}
          :else
          (let [s (section/read-section record-text "Summary")]
            (if (:found s)
              {:found true :body (truncate-body (:body s) max-body-chars)}
              {:found false})))
        has-context
        (and (some? record-text)
             (:found (section/read-section record-text "Context")))]
    {:id           id
     :summary      (:summary task)
     :status       (:status task)
     :priority     (:priority task)
     :tags         (vec (:tags task))
     :sourcePath   (:source-path task)
     :importPath   (:import-path task)
     :recordSummary record-summary
     :hasContext   (boolean has-context)}))

(defn scan-summaries
  "Walk `active-roots` + `archived-roots` and return one `ScanRow` per
  task whose `:CUSTOM_ID:` is set and which passes the optional
  `scope` / `tags` filters.

  `input` keys:
    :active-roots         - top-level tasks from TASKS.org (+ local + file imports)
    :archived-roots       - top-level tasks from TASKS.archive.org
    :read-change-record   - (fn [task] String | nil) resolving the
                            linked record's content. Out-of-root /
                            unreadable surfaces as nil and is mapped
                            to {:found false}.

  `options` keys:
    :scope         - :active | :archived | :all (default :all)
    :tags          - whitelist (OR-semantics) of tag strings
    :max-body-chars - per-row body cap (default 500)"
  ([input] (scan-summaries input {}))
  ([{:keys [active-roots archived-roots read-change-record]} options]
   (let [scope           (or (:scope options) :all)
         tag-filter      (:tags options)
         max-body-chars  (or (:max-body-chars options) default-max-body-chars)
         emit (fn [roots]
                (->> (tree/all-tasks roots)
                     (keep
                       (fn [t]
                         (when-let [id (parser/get-task-id t)]
                           (when (matches-tag-filter? t tag-filter)
                             (build-row t id read-change-record max-body-chars)))))))
         active-rows   (when (#{:active :all} scope) (emit active-roots))
         archived-rows (when (#{:archived :all} scope) (emit archived-roots))]
     (vec (concat (or active-rows []) (or archived-rows []))))))

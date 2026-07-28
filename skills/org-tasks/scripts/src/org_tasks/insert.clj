(ns org-tasks.insert
  "Task insertion + idempotency across `:LINKED_ISSUES:` tokens.

  Pure where possible: `build-task-block` is fully deterministic given
  `id` + `created-at` overrides; `insert-task-into-file` reads `target`
  (+ optional `also-scan`), checks for duplicates, splices the new
  block under the requested section, and writes the result atomically.

  Public surface:

    map-priority-name        :: Jira name -> 'A'|'B'|'C'|'D' or nil
    build-task-block         :: args -> {:heading, :drawer, :body,
                                         :block, :id}
    insert-task-into-file    :: args -> result map (see :status keys)"
  (:require [babashka.fs :as fs]
            [clojure.string :as str]
            [org-tasks.effective :as effective]
            [org-tasks.links :as links]
            [org-tasks.loader :as loader]
            [org-tasks.parser :as parser]
            [org-tasks.paths :as paths]
            [org-tasks.tree :as tree]))

;; ── Priority mapping ───────────────────────────────────────────────

(defn map-priority-name
  "Map a Jira priority name (or short form) to an org cookie character.

  Highest → A, High → B, Medium → C, Low|Lowest → D. Single-letter
  inputs `A`/`B`/`C`/`D` (case-insensitive) pass through. Anything
  else yields nil."
  [name]
  (when (and name (seq name))
    (let [n (str/lower-case (str/trim name))]
      (case n
        ("a" "highest") "A"
        ("b" "high")    "B"
        ("c" "medium")  "C"
        ("d" "low" "lowest") "D"
        nil))))

;; ── Heading + drawer assembly ──────────────────────────────────────

(defn- render-tag-suffix [labels]
  (let [filtered (filterv #(and % (seq %)) (or labels []))]
    (if (empty? filtered)
      ""
      (str ":" (str/join ":" filtered) ":"))))

(defn build-task-block
  "Build an org-task block from structured fields. Status is always
  TODO; cloned/imported tasks always start in local TODO regardless
  of the source tracker.

  Args:
    :summary       - heading text, required.
    :priority-name - Jira priority name; passed through map-priority-name.
    :body          - optional body text, normalised to no leading/trailing
                     newlines.
    :linked-issues - sequence of `:LINKED_ISSUES:` org-link tokens.
    :labels        - sequence of org tag strings.
    :parent-id     - when set, render the block as a level-3 heading.
    :id            - override the generated `:CUSTOM_ID:` (test injection).
    :created-at    - override the `:CREATED:` timestamp body (test injection).

  Returns `{:heading, :drawer, :body, :block, :id}`. `:block` ends in
  exactly one trailing newline."
  [{:keys [summary priority-name body linked-issues labels parent-id id created-at]}]
  (let [summary (str/trimr (or summary ""))]
    (when (empty? summary)
      (throw (ex-info "build-task-block: summary is required" {})))
    (let [id          (or id (str (random-uuid)))
          created-at  (or created-at (parser/format-org-timestamp))
          level       (if parent-id 3 2)
          stars       (apply str (repeat level "*"))
          priority-char (map-priority-name priority-name)
          priority-cookie (if priority-char (str "[#" priority-char "] ") "")
          tag-suffix  (render-tag-suffix labels)
          heading     (str stars " TODO " priority-cookie summary
                           (when (seq tag-suffix) (str " " tag-suffix)))
          base-drawer [":PROPERTIES:"
                       (str ":CUSTOM_ID: " id)
                       (str ":CREATED: [" created-at "]")]
          linked      (filterv #(and % (seq %)) (or linked-issues []))
          drawer-lines (cond-> base-drawer
                         (seq linked) (conj (str ":LINKED_ISSUES: "
                                                 (str/join " " linked))))
          drawer      (str/join "\n" (conj drawer-lines ":END:"))
          body        (-> (or body "")
                          (str/replace #"^\n+" "")
                          (str/replace #"\n+$" ""))
          block-lines (cond-> [heading drawer ":LOGBOOK:"
                               (parser/created-log-entry created-at)
                               ":END:"]
                        (seq body) (conj body))
          block       (str (str/join "\n" block-lines) "\n")]
      {:heading heading
       :drawer  drawer
       :body    body
       :block   block
       :id      id})))

;; ── Section splice ─────────────────────────────────────────────────

(defn- section-heading? [^String line ^String section]
  (when-let [m (re-matches #"^\*\s+(.+?)\s*$" line)]
    (let [text (m 1)
          ;; Strip trailing `:tag1:tag2:`
          stripped (if-let [tm (re-find #"\s+:[\w@:]+:\s*$" text)]
                     (str/trimr (subs text 0 (- (count text) (count tm))))
                     (str/trimr text))]
      (= (str/trim stripped) (str/trim section)))))

(defn- next-top-level-heading-idx [lines ^long from]
  (some (fn [i]
          (when (re-matches #"^\*\s+\S.*$" (nth lines i))
            i))
        (range from (count lines))))

(defn- splice-into-section
  "Splice `block` into `content` under `* <section>`. Returns
  `{:content, :line}` (1-indexed line of the new heading) or nil
  when the section is absent and `allow-create-section?` is false."
  [^String content ^String section ^String block ^Boolean allow-create-section?]
  (let [lines (vec (str/split content #"\n" -1))
        heading-idx (some (fn [i]
                            (when (section-heading? (nth lines i) section)
                              i))
                          (range (count lines)))
        block-lines (vec (str/split (str/replace block #"\n+$" "") #"\n"))]
    (cond
      ;; New section append (when allowed)
      (and (nil? heading-idx) allow-create-section?)
      (let [trimmed (loop [v lines]
                      (if (and (seq v) (= "" (peek v)))
                        (recur (pop v))
                        v))
            prefix  (if (seq trimmed) [""] [])
            heading-line (str "* " section)
            new-lines (vec (concat trimmed prefix [heading-line ""] block-lines))]
        {:content (str (str/replace (str/join "\n" new-lines) #"\n*$" "") "\n")
         :line    (inc (- (count new-lines) (count block-lines)))})

      (nil? heading-idx)
      nil

      :else
      (let [next-idx (next-top-level-heading-idx lines (inc heading-idx))
            insert-before (or next-idx (count lines))
            ;; Trim trailing blanks in section so splice doesn't push next
            ;; heading further on each insert.
            tail (loop [t insert-before]
                   (if (and (> t (inc heading-idx))
                            (= "" (nth lines (dec t))))
                     (recur (dec t))
                     t))
            insertion (cond-> []
                        (> tail (inc heading-idx)) (conj "")
                        true                       (into block-lines)
                        (some? next-idx)           (conj ""))
            new-lines (vec (concat (subvec lines 0 tail)
                                   insertion
                                   (subvec lines insert-before)))
            heading-line-offset (+ tail (if (= "" (first insertion)) 1 0))]
        {:content (str (str/replace (str/join "\n" new-lines) #"\n*$" "") "\n")
         :line    (inc heading-line-offset)}))))

(defn insert-subtree-into-section
  "Purely splice an already-rendered task subtree below a level-1 section.
  Returns `{:content :line}` or nil when the section does not exist; unlike
  create, restoration never silently creates a destination section."
  [content section subtree]
  (splice-into-section content section subtree false))

;; ── Idempotency scan ───────────────────────────────────────────────

(defn- safe-slurp [path]
  (loader/safe-slurp path))

(defn- walk-tasks-with-imports
  "Return `[{:task, :file}]` for every task reachable from `paths` via
  `#+IMPORT:` (file-level + per-task), de-duped by absolute path."
  [project-root paths]
  (let [visited (volatile! #{})
        out     (volatile! [])]
    (letfn [(walk-file [abs-path]
              (when (and abs-path (not (contains? @visited abs-path)))
                (vswap! visited conj abs-path)
                (when-let [content (safe-slurp abs-path)]
                  (let [effective-content (effective/read-effective-org-content
                                           project-root abs-path content)
                        {:keys [tasks file-imports]}
                        (parser/parse-tasks content {:source-path abs-path
                                                     :effective-source-content effective-content})]
                    (recurse-tasks tasks abs-path)
                    (doseq [imp file-imports]
                      (when-let [abs (links/resolve-link-target
                                       project-root abs-path effective-content imp)]
                        (walk-file abs)))))))
            (recurse-tasks [ts file]
              (doseq [t ts]
                (vswap! out conj {:task t :file file})
                (recurse-tasks (:children t) file)
                (when-let [abs (links/resolve-task-link-target project-root t (:import-path t))]
                  (walk-file abs))))]
      (doseq [p paths] (walk-file p)))
    @out))

(defn- find-duplicate
  "Find the first task whose `:LINKED_ISSUES:` overlaps with `tokens`."
  [collected tokens]
  (when (seq tokens)
    (let [wanted (set tokens)]
      (some
        (fn [{:keys [task file]}]
          (when-let [linked (parser/get-drawer-property task "LINKED_ISSUES")]
            (some
              (fn [tok]
                (when (contains? wanted tok)
                  {:status :duplicate
                   :existing-id (parser/get-task-id task)
                   :existing-file file
                   :conflicting-token tok}))
              (filter seq (str/split linked #"\s+")))))
        collected))))

;; ── Source-line insertion (parent / after) ────────────────────────

(defn- relevel-block [block level]
  (str/replace-first block #"^\*+" (apply str (repeat level "*"))))

(defn- insert-block-before-line [content line-1-indexed block]
  (let [lines (vec (str/split content #"\n" -1))
        idx   (max 0 (dec line-1-indexed))
        block-lines (vec (str/split (str/replace block #"\n+$" "") #"\n"))
        insertion (cond-> []
                    (and (pos? idx) (not= "" (nth lines (dec idx) ""))) (conj "")
                    true (into block-lines)
                    (not= "" (nth lines idx "")) (conj ""))
        next-lines (vec (concat (subvec lines 0 idx) insertion (subvec lines idx)))]
    {:content (str (str/replace (str/join "\n" next-lines) #"\n*$" "") "\n")
     :line (inc (+ idx (if (= "" (first insertion)) 1 0)))}))

;; ── Public entry ───────────────────────────────────────────────────

(defn insert-task-into-file
  "Insert a new task block into `:file` under `:section`. Args mirror
  `build-task-block` plus:

    :file                     - target org file path (cwd-relative or absolute)
    :section                  - level-1 section name
    :allow-create-section?    - default false; when true, missing section
                                is appended
    :also-scan                - additional files scanned for
                                :LINKED_ISSUES: collisions (recursively
                                walks `#+IMPORT:`)
    :project-root             - sandbox root; defaults to cwd

  Returns one of:

    {:status :inserted, :id, :file, :line}
    {:status :duplicate, :existing-id, :existing-file, :conflicting-token}
    {:status :section-not-found, :file, :section}
    {:status :error, :reason, :message}"
  [{:keys [file project-root section summary linked-issues also-scan
           allow-create-section? parent-id after-id]
    :or {project-root (System/getProperty "user.dir")
         also-scan []
         allow-create-section? false}
    :as args}]
  (cond
    (or (nil? summary) (str/blank? summary))
    {:status :error :reason :empty-summary
     :message "`summary` is required and must be non-empty."}

    :else
    (let [target-abs (paths/resolve-project-path
                       project-root project-root file)]
      (cond
        (nil? target-abs)
        {:status :error :reason :path-outside-project
         :message (str "Target file resolves outside project root: " file)}

        :else
        (let [scan-paths-or-error
              (reduce (fn [acc candidate]
                        (if-let [sandboxed (paths/resolve-project-path
                                             project-root project-root candidate)]
                          (conj acc sandboxed)
                          (reduced {:status :error :reason :path-outside-project
                                    :message (str "Scan file resolves outside project root: "
                                                  candidate)})))
                      [target-abs]
                      (or also-scan []))]
          (if (= :error (:status scan-paths-or-error))
            scan-paths-or-error
            (let [scan-paths scan-paths-or-error
                  tokens (filterv #(and % (seq %)) (or linked-issues []))
                  duplicate (when (seq tokens)
                              (let [collected (walk-tasks-with-imports
                                                project-root scan-paths)]
                                (find-duplicate collected tokens)))]
              (if duplicate
                duplicate
                (let [built (build-task-block args)
                      existing (or (safe-slurp target-abs) "")]
                  (if (or parent-id after-id)
                    (if (str/blank? existing)
                      {:status :section-not-found
                       :file target-abs
                       :section section}
                      (let [effective-content (effective/read-effective-org-content
                                               project-root target-abs existing)
                            parsed (parser/parse-tasks
                                    existing
                                    {:source-path target-abs
                                     :effective-source-content effective-content})
                            parent (tree/find-by-id (:tasks parsed) parent-id {:imports? false})
                            after  (tree/find-by-id (:tasks parsed) after-id {:imports? false})]
                        (cond
                          (and parent-id (nil? parent))
                          {:status :unknown-task
                           :reason :parent-not-found
                           :message (str "Parent task not found: " parent-id)}

                          (and after-id (nil? after))
                          {:status :unknown-task
                           :reason :after-not-found
                           :message (str "Anchor task not found: " after-id)}

                          :else
                          (let [anchor (or after parent)
                                level  (if parent
                                         (inc (:level parent))
                                         (:level anchor))
                                block  (relevel-block (:block built) level)
                                spliced (insert-block-before-line
                                         existing (:end-line anchor) block)]
                            (loader/atomic-write target-abs (:content spliced))
                            {:status :inserted
                             :id (:id built)
                             :file target-abs
                             :line (:line spliced)}))))
                    (let [spliced (splice-into-section
                                    existing section (:block built)
                                    allow-create-section?)]
                      (cond
                        (and (not spliced) (not allow-create-section?))
                        {:status :section-not-found
                         :file target-abs
                         :section section}

                        :else
                        (do (loader/atomic-write target-abs (:content spliced))
                            {:status :inserted
                             :id (:id built)
                             :file target-abs
                             :line (:line spliced)})))))))))))))

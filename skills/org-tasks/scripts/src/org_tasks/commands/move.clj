(ns org-tasks.commands.move
  "`ot move` — relocate an existing task subtree inside its own file.

  Two mutually-exclusive destinations:

    --parent <id>     reparent as the last child of another task
    --section <name>  lift the subtree back to a level-2 heading under a
                      level-1 section of the same file (the depth
                      `ot create` gives a top-level task)

  Deliberately in-file only. Cross-file relocation is already owned by
  `publish`/`unpublish` (locality) and `archive`/`unarchive` (archive),
  so `move` never has to reason about a UUID gaining a second writable
  node: source and destination must share one `:source-path`.

  Unlike the other mutators, `move` splices *lines* rather than
  re-serializing parsed roots. A relocation changes nothing but heading
  depth, so cutting the subtree's exact source lines and re-inserting
  them with restarred headings preserves `:CUSTOM_ID:`, `:CREATED:` /
  `:STARTED:`, `CLOSED:`, `:LOGBOOK:`, `#+IMPORT:`, unknown drawer
  properties, bodies, descendant order, *and* intra-subtree blank lines
  byte-for-byte — and leaves every unrelated region of the file
  untouched instead of putting it through the serializer's blank-line
  normalisation."
  (:require [clojure.string :as str]
            [org-tasks.insert :as insert]
            [org-tasks.loader :as loader]
            [org-tasks.output :as out]
            [org-tasks.parser :as parser]
            [org-tasks.task :as task]
            [org-tasks.tree :as tree]
            [org-tasks.commands.util :as util :refer [positional-arg load-context]]))

(def ^:private section-level
  "Heading depth for a task sitting directly under a level-1 section,
  matching `insert/build-task-block`'s top-level placement."
  2)

(def ^:private heading-re
  "Any org heading, matching `parser`'s own `any-heading-re` so only
  lines the parser treats as headings are ever restarred."
  #"^(\*+)\s+.*$")

;; ── Line-level subtree surgery ────────────────────────────────────

(defn- content-lines
  "Split `content` into real lines. `parser/parse-tasks` keeps the empty
  string a final newline produces; dropping it here means the file's
  terminating newline is never mistaken for a blank separator, and
  `join-lines*` puts it back."
  [^String content]
  (let [v (vec (str/split content #"\n" -1))]
    (if (and (seq v) (= "" (peek v))) (pop v) v)))

(defn- join-lines*
  "Re-join lines into content with exactly one trailing newline, the
  normalisation every other writer in the codebase applies."
  [lines]
  (str (str/replace (str/join "\n" lines) #"\n*$" "") "\n"))

(defn- task-range
  "0-indexed `[start end)` slice of `lines` covering `task`'s whole
  subtree, including the blank separator that trails it. The end is
  clamped because `parse-tasks` reports a final task's `:end-line`
  against its own trailing-newline placeholder."
  [lines task]
  [(min (count lines) (max 0 (dec (or (:line-number task) 1))))
   (min (count lines) (max 0 (dec (or (:end-line task) 1))))])

(defn- trailing-blank-count [lines]
  (count (take-while #(= "" %) (rseq (vec lines)))))

(defn- cut-subtree
  "Remove `[start end)` from `lines`, re-inserting the cut range's
  trailing blank separator only where the surrounding text still needs
  one (i.e. the hole would otherwise weld two blocks together)."
  [lines start end]
  (let [raw      (subvec lines start end)
        trailing (trailing-blank-count raw)
        prefix   (subvec lines 0 start)
        suffix   (subvec lines end)
        keep?    (and (pos? trailing)
                      (seq prefix)
                      (seq suffix)
                      (not= "" (peek prefix))
                      (not= "" (first suffix)))]
    (vec (concat prefix (when keep? (repeat trailing "")) suffix))))

(defn- restar
  "Shift every heading in `block` by `delta` stars, preserving the rest
  of each line (including its original heading whitespace) verbatim."
  [block delta]
  (if (zero? delta)
    (vec block)
    (mapv (fn [^String line]
            (if-let [m (re-matches heading-re line)]
              (let [stars (m 1)
                    level (max 1 (+ (count stars) delta))]
                (str (apply str (repeat level "*")) (subs line (count stars))))
              line))
          block)))

(defn- insert-as-last-child
  "Splice `block` in at the end of `parent`'s subtree — after the
  parent's own trailing blank separator, before the next heading. The
  block carries its own trailing separator, so a move and its inverse
  reproduce the original spacing exactly."
  [lines parent block]
  (let [at    (second (task-range lines parent))
        block (vec block)
        ;; A subtree cut from the end of its file has no separator of its
        ;; own. Restore one when the destination sits in blank-separated
        ;; text, or the block would weld to the following heading.
        block (cond-> block
                (and (seq block)
                     (not= "" (peek block))
                     (< at (count lines))
                     (= "" (nth lines (dec at) nil)))
                (conj ""))]
    (vec (concat (subvec lines 0 at) block (subvec lines at)))))

;; ── Graph helpers ─────────────────────────────────────────────────

(defn- subtree-ids
  "Every `:CUSTOM_ID:` in `task`'s own subtree. Imports are excluded —
  they live in other files and are rejected by the same-file guard."
  [task]
  (set (keep parser/get-task-id (tree/all-tasks [task] {:imports? false}))))

(defn- in-file-parent-id
  "`:CUSTOM_ID:` of the in-file parent of `id`, or nil when `id` is a
  root of its own file."
  [roots id]
  (some (fn [t]
          (when (some #(= id (parser/get-task-id %)) (:children t))
            (parser/get-task-id t)))
        (tree/all-tasks roots {:imports? false})))

(defn- arg->value
  "Normalise a possibly-missing / blank option to nil-or-string."
  [v]
  (when (and (some? v) (not (str/blank? (str v))))
    (str v)))

(defn- archived-match
  "Resolve `id` against `TASKS.archive.org` only. Consulted solely when
  the id is absent from the active graph, so `ot move` can name the
  archive instead of reporting a bare `unknown-task`."
  [files id]
  (when-let [content (loader/safe-slurp (:archive files))]
    (:match (task/find-top-level-by-id-or-prefix
              (:tasks (parser/parse-tasks content {:source-path (:archive files)}))
              id))))

;; ── Write phase ───────────────────────────────────────────────────

(defn- apply-move!
  [opts {:keys [source full-id path dest-id section]}]
  (let [baseline  (:source-content source)
        ;; A structural move never touches file-level `#+LINK:` /
        ;; `#+SETUPFILE:` keywords, so the load-time effective content
        ;; stays valid for link-template resolution on the wire task.
        effective (:effective-source-content source)
        parse-opts {:source-path path :effective-source-content effective}
        lines     (content-lines baseline)
        roots     (:tasks (parser/parse-tasks baseline (assoc parse-opts
                                                             :source-content baseline)))
        src-node  (tree/find-by-id roots full-id {:imports? false})
        dest-node (when dest-id (tree/find-by-id roots dest-id {:imports? false}))]
    (cond
      (nil? src-node)
      (out/emit-error opts {:code "validation"
                            :message (str "Task " full-id " is not a writable node of " path ".")
                            :file path})

      (and dest-id (nil? dest-node))
      (out/emit-error opts {:code "validation"
                            :message (str "Destination task " dest-id
                                          " is not a writable node of " path ".")
                            :file path})

      :else
      (let [[start end] (task-range lines src-node)
            block       (subvec lines start end)
            ;; Cut first, then re-resolve the destination against the
            ;; reduced text so every index below is exact regardless of
            ;; whether the destination sat before or after the source.
            reduced     (join-lines* (cut-subtree lines start end))
            reduced-lines (content-lines reduced)
            reduced-roots (:tasks (parser/parse-tasks reduced (assoc parse-opts
                                                                    :source-content reduced)))
            dest-after  (when dest-id
                          (tree/find-by-id reduced-roots dest-id {:imports? false}))
            to-level    (if dest-after (inc (:level dest-after)) section-level)
            shifted     (restar block (- to-level (:level src-node)))
            updated     (if dest-id
                          (join-lines* (insert-as-last-child reduced-lines dest-after shifted))
                          ;; Sections are non-task org content, so the
                          ;; top-level destination reuses the same splice
                          ;; `ot create` / `ot unarchive` insert through.
                          (some-> (insert/insert-subtree-into-section
                                    reduced section (join-lines* shifted))
                                  :content))]
        (if (nil? updated)
          (out/emit-error opts {:code "section-not-found"
                                :message (str "Section '" section "' not found in " path)
                                :file path
                                :details {:section section}})
          (let [;; Re-parse the proposed content so the reported task
                ;; carries its post-move depth and line number (and the
                ;; spliced output is proven parseable before writing).
                after (some-> (tree/find-by-id
                                (:tasks (parser/parse-tasks
                                          updated (assoc parse-opts :source-content updated)))
                                full-id
                                {:imports? false})
                              (assoc :is-local (boolean (:is-local source))))
                dry?  (boolean (:dry-run opts))]
            (when-not dry?
              (loader/assert-unchanged! path baseline)
              (loader/atomic-write path updated))
            (out/emit-result
              opts
              {:task (task/task->wire (or after src-node))
               :file path
               :parentId dest-id
               :section (when-not dest-id section)
               :previousParentId (in-file-parent-id roots full-id)
               :fromLevel (:level src-node)
               :toLevel to-level
               :movedCount (count (tree/all-tasks [src-node] {:imports? false}))
               :dryRun dry?
               :text/lines
               [(str (if dry? "Would move " "Moved ")
                     (:summary src-node)
                     (if dest-after
                       (str " → child of " (:summary dest-after))
                       (str " → section " section))
                     " in " path)]})))))))

;; ── ot move ───────────────────────────────────────────────────────

(defn- resolve-and-move! [opts tasks files id parent-arg section-arg]
  (if (and (:none (task/find-by-id-or-prefix tasks id))
           (archived-match files id))
    (out/emit-error opts
                    {:code "validation"
                     :message (str "Cannot move an archived task; run `ot unarchive "
                                   id "` first.")
                     :details {:id id}})
    (let [source  (util/resolve-required-id tasks id opts)
          full-id (parser/get-task-id source)
          path    (:source-path source)
          dest    (when parent-arg (util/resolve-required-id tasks parent-arg opts))
          dest-id (when dest (parser/get-task-id dest))]
      (cond
        (nil? full-id)
        (out/emit-error opts {:code "validation"
                              :message "Cannot move a task without a :CUSTOM_ID:; run ot backfill first."})

        (and dest (nil? dest-id))
        (out/emit-error opts {:code "validation"
                              :message "Destination task has no :CUSTOM_ID:; run ot backfill first."})

        (and dest-id (= full-id dest-id))
        (out/emit-error opts {:code "validation"
                              :message (str "Cannot move a task under itself: " full-id)
                              :details {:id full-id}})

        (and dest-id (contains? (subtree-ids source) dest-id))
        (out/emit-error opts {:code "validation"
                              :message (str "Cannot move " full-id
                                            " under its own descendant " dest-id ".")
                              :details {:id full-id :parentId dest-id}})

        (and dest (not= path (:source-path dest)))
        (out/emit-error opts
                        {:code "validation"
                         :message (str "Cross-file moves are out of scope: " full-id
                                       " lives in " path " but the destination lives in "
                                       (:source-path dest)
                                       ". Use ot publish/unpublish to change locality.")
                         :details {:from path :to (:source-path dest)}})

        :else
        (apply-move! opts {:source source
                           :full-id full-id
                           :path path
                           :dest-id dest-id
                           :section section-arg})))))

(defn move-cmd [{:keys [opts] :as result}]
  (let [id          (arg->value (positional-arg result :id))
        parent-arg  (arg->value (:parent opts))
        section-arg (arg->value (:section opts))]
    (cond
      (nil? id)
      (out/emit-error opts {:code "argument-error"
                            :message "ot move requires a task id."})

      (and parent-arg section-arg)
      (out/emit-error opts {:code "argument-error"
                            :message "ot move accepts either --parent <id> or --section <name>, not both."})

      (and (nil? parent-arg) (nil? section-arg))
      (out/emit-error opts {:code "argument-error"
                            :message "ot move requires a destination: --parent <id> or --section <name>."})

      :else
      (let [{:keys [tasks files]} (load-context opts)]
        (util/guard! opts
          #(resolve-and-move! opts tasks files id parent-arg section-arg))))))

(ns org-tasks.commands.create
  "`ot` create command handler."
  (:require [clojure.string :as str]
            [org-tasks.insert :as insert]
            [org-tasks.output :as out]
            [org-tasks.parser :as parser]
            [org-tasks.commands.util :refer [positional-arg load-context resolve-context
                                             resolve-required-id
                                             coerce-seq]]))

;; ── ot create ──────────────────────────────────────────────────────

(defn create-cmd [{:keys [opts] :as result}]
  (let [{:keys [project-root files]} (resolve-context opts)
        summary (positional-arg result :summary)
        ;; --relative-to derives placement from an anchor task, overriding any
        ;; explicit --parent/--after/--local/--tasks. `:child` nests under the
        ;; anchor (parent only); `:sibling` (default) inserts after it at the
        ;; same level (after only — a set parent would force child depth in
        ;; insert-task-into-file). Locality and source file follow the anchor.
        rel-id   (:relative-to opts)
        rel-kind (when rel-id (if (contains? #{:child "child"} (:as opts)) :child :sibling))
        anchor   (when rel-id (resolve-required-id (:tasks (load-context opts)) rel-id opts))
        anchor-id (when anchor (parser/get-task-id anchor))
        local?  (if rel-id (boolean (:is-local anchor)) (boolean (:local opts)))
        file    (cond
                  (and rel-id (not local?)) (or (:source-path anchor) (:tasks files))
                  local?                    (:local files)
                  :else                     (:tasks files))
        ;; Validate --tag on the same rule `ot tag add` enforces, and pass the
        ;; normalised token through, so create can never write a tag its own
        ;; parser will later ignore (e.g. a hyphenated value read back as no tag).
        norm-labels (mapv (juxt identity parser/normalise-task-tag)
                          (coerce-seq (:tag opts)))
        invalid-labels (mapv first (filter (comp nil? second) norm-labels))
        labels  (mapv second norm-labels)
        tokens  (coerce-seq (:linked-issue opts))
        ;; Mirror the pi extension's `alsoScan` heuristic: when
        ;; inserting into TASKS.org, also scan TASKS.local.org for
        ;; duplicate :LINKED_ISSUES: tokens, and vice-versa.
        also-scan (into (if local? [(:tasks files)] [(:local files)])
                        (coerce-seq (:also-scan opts)))
        args    {:project-root project-root
                 :file file
                 :section (or (:section opts) "Improvements")
                 :summary summary
                 :priority-name (:priority opts)
                 :body (:body opts)
                 :linked-issues tokens
                 :labels labels
                 :parent-id (if rel-id
                              (when (= :child rel-kind) anchor-id)
                              (:parent opts))
                 :after-id  (if rel-id
                              (when (= :sibling rel-kind) anchor-id)
                              (:after opts))
                 :id (:id opts)
                 :created-at (:created-at opts)
                 :also-scan also-scan
                 :allow-create-section? (boolean (:allow-create-section opts))}]
    (cond
      (or (nil? summary) (str/blank? summary))
      (out/emit-error opts
                      {:code "empty-summary"
                       :message "ot create requires a non-empty summary."})

      (seq invalid-labels)
      (out/emit-error opts
                      {:code "invalid-tag"
                       :message (str "Invalid tag "
                                     (str/join ", " (map pr-str invalid-labels))
                                     "; expected letters, digits, and underscores")})

      (:dry-run opts)
      (out/emit-result opts
                       {:file (:file args)
                        :section (:section args)
                        :dryRun true
                        :text/lines [(str "Would insert under '" (:section args)
                                          "' in " (:file args))]})

      :else
      (let [result (insert/insert-task-into-file args)]
        (case (:status result)
          :inserted
          (out/emit-result opts
                           {:id (:id result)
                            :file (:file result)
                            :line (:line result)
                            :sectionCreated (boolean (:allow-create-section opts))
                            :text/lines
                            [(str "Inserted " (:id result)
                                  " at " (:file result) ":" (:line result))]})

          :duplicate
          (out/emit-error opts
                          {:code "duplicate-linked-issue"
                           :message (str "Linked issue token already linked from "
                                         (or (:existing-id result) "(no :CUSTOM_ID:)"))
                           :file (:existing-file result)
                           :details {:conflictingToken (:conflicting-token result)
                                     :existingId (:existing-id result)
                                     :existingFile (:existing-file result)}})

          :section-not-found
          (out/emit-error opts
                          {:code "section-not-found"
                           :message (str "Section '" (:section args) "' not found in "
                                         (:file args))
                           :file (:file result)
                           :details {:section (:section args)}})

          :unknown-task
          (out/emit-error opts
                          {:code "unknown-task"
                           :message (:message result)
                           :details {:reason (some-> (:reason result) name)
                                     :parentId (:parent opts)
                                     :afterId (:after opts)}})

          :error
          (out/emit-error opts
                          {:code (name (:reason result))
                           :message (:message result)}))))))

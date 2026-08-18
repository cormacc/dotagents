(ns org-tasks.parser
  "Stable public facade for the `ot` Org parser.

  Focused namespaces own task scanning, rendering, timestamps, drawer
  properties, links, linked issues, and readiness. Public helpers remain
  available here for callers that use the established facade."
  (:require [org-tasks.parser.issues :as issues]
            [org-tasks.parser.links :as links]
            [org-tasks.parser.properties :as properties]
            [org-tasks.parser.renderer :as renderer]
            [org-tasks.parser.scanner :as scanner]
            [org-tasks.parser.timestamps :as timestamps]
            [org-tasks.readiness :as readiness]))

(defmacro ^:private def-facade-alias
  "Define a facade var whose value and public API metadata mirror `target`."
  [name target]
  `(do
     (def ~name ~target)
     (alter-meta! (var ~name) merge
                  (select-keys (meta (var ~target))
                               [:doc :arglists :added :deprecated]))))

(def-facade-alias format-org-timestamp timestamps/format-org-timestamp)
(def-facade-alias format-org-date timestamps/format-org-date)
(def-facade-alias created-log-entry timestamps/created-log-entry)
(def-facade-alias state-log-entry timestamps/state-log-entry)
(def-facade-alias append-created-log timestamps/append-created-log)
(def-facade-alias append-state-log timestamps/append-state-log)

(def-facade-alias get-task-id properties/get-task-id)
(def-facade-alias task-has-id? properties/task-has-id?)
(def-facade-alias get-task-started properties/get-task-started)
(def-facade-alias task-has-started-property? properties/task-has-started-property?)
(def-facade-alias get-drawer-property properties/get-drawer-property)
(def-facade-alias set-drawer-property properties/set-drawer-property)
(def-facade-alias get-drawer-property-values properties/get-drawer-property-values)
(def-facade-alias set-drawer-property-values properties/set-drawer-property-values)
(def-facade-alias parse-blocker properties/parse-blocker)
(def-facade-alias get-task-blockers properties/get-task-blockers)
(def-facade-alias set-task-blockers properties/set-task-blockers)
(def-facade-alias get-task-handoff properties/get-task-handoff)
(def-facade-alias set-task-handoff properties/set-task-handoff)

(def-facade-alias extract-org-link-target links/extract-org-link-target)
(def-facade-alias extract-org-link links/extract-org-link)
(def-facade-alias escape-regex links/escape-regex)
(def-facade-alias get-file-keywords links/get-file-keywords)
(def-facade-alias get-file-keyword links/get-file-keyword)
(def-facade-alias parse-selected-keyword links/parse-selected-keyword)
(def-facade-alias get-plan-parent-ref links/get-plan-parent-ref)
(def-facade-alias get-plan-parent-id links/get-plan-parent-id)
(def-facade-alias rewrite-parent-link-kind links/rewrite-parent-link-kind)
(def-facade-alias parse-link-templates links/parse-link-templates)
(def-facade-alias expand-org-link-target links/expand-org-link-target)

(def-facade-alias get-linked-issues issues/get-linked-issues)
(def-facade-alias set-linked-issues issues/set-linked-issues)

(def-facade-alias strip-trailing-task-tags scanner/strip-trailing-task-tags)
(def-facade-alias normalise-task-tag scanner/normalise-task-tag)
(def-facade-alias add-task-tag scanner/add-task-tag)
(def-facade-alias remove-task-tag scanner/remove-task-tag)
(def-facade-alias parse-tasks scanner/parse-tasks)

(def-facade-alias is-task-ready readiness/is-task-ready)

(def-facade-alias serialize-tasks renderer/serialize-tasks)
(def-facade-alias serialize-tasks-preserving-file renderer/serialize-tasks-preserving-file)
(def-facade-alias serialize-tasks-preserving-file-locality renderer/serialize-tasks-preserving-file-locality)

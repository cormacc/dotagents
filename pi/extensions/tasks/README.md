# Tasks Extension

Displays project tasks from a `TASKS.org` file in the project root using org-mode TODO syntax.
The extension is a UI over the plain-org task-memory protocol documented in
`skills/org-tasks/SKILL.md`; that skill defines the durable file-format
contract, while this extension owns commands, rendering, selection, status writes,
and archive mechanics.

## Usage

### Commands

- `/tasks` — Expand the tasks UI
- `/tasks new` — Create a new top-level task without opening the expanded UI
- `/tasks doctor` — Run health checks against the loaded task graph and report findings

Subcommand auto-completion is exposed via `getArgumentCompletions`, so typing
`/tasks ` shows `new` and `doctor` with descriptions in the prompt.

### Keybindings

| Chord   | Action              | Event        |
| ------- | ------------------- | ------------ |
| `Alt+T` | Expand the tasks UI | `tasks:show` |

The shortcut dispatches `tasks:show` on the pi event bus rather than
submitting the `/tasks` slash command, so opening the tasks UI never
overwrites text already entered in the prompt. The slash commands
(`/tasks`, `/tasks new`, `/tasks doctor`) remain available and dispatch
the same events.

On macOS, the terminal must be configured to send Alt as Meta (e.g.
iTerm2: "Use Option as Meta"; kitty: `macos_option_as_alt yes`). Users
can rebind via `~/.pi/agent/keybindings.json` once pi exposes the
extension's shortcut id; in the meantime the binding is fixed in
`index.ts`.

### Compact selected-task widget

When a task UUID is recorded in `TASKS.local.org` (via `#+SELECTED: <UUID>`), a
compact widget is reserved above the editor. It shows the containing top-level
task tree, with the selected task highlighted inside it. The widget:

- Appears on startup if `TASKS.local.org` already contains a `#+SELECTED:` entry.
- Hides while the tasks UI is expanded, then returns after the expanded UI closes.
- Refreshes automatically when `TASKS.org` or any linked plan file is modified on disk (for example after saving from Emacs via the `e` keybinding). No need to reopen `/tasks`.
- Does not take keyboard focus, so normal input keeps working.
- Reserves layout space instead of covering conversation scrollback.
- Shows at most 6 lines, with a single full-width divider at the top and no bottom divider.
- When truncating, completed subtasks are elided first as `… N completed subtasks`, so the selected task and next pending subtasks stay visible.
- Shows the selected top-level task's linked plan path, for example `./relative/path/to/plan.org`, when loaded plan children are present.

### Status colors

Status and metadata tokens use a fixed palette across the expanded tasks UI and the compact selected-task widget.
Tags are styled separately from task titles.

| Status    | Color  |
| --------- | ------ |
| `TODO`      | yellow |
| `WAITING`   | orange |
| `STARTED`   | blue   |
| `DONE`      | green  |
| `CANCELLED` | red    |

| Priority | Meaning  | Color  |
| -------- | -------- | ------ |
| `[#A]`   | Critical | orange |
| `[#B]`   | High     | yellow |
| `[#C]`   | Medium   | green  |
| `[#D]`   | Low      | blue   |

### Expanded UI controls

The expanded UI is a centered split pane — task tree on the left, details for the selected task on the right. The details pane starts with the cursor task's status and title, followed by plan metadata and the task description.

| Key                       | Action                             |
| ------------------------- | ---------------------------------- |
| `↑` / `k`                 | Move cursor up                     |
| `↓` / `j`                 | Move cursor down                   |
| `→` / `l`                 | Cycle status forward               |
| `←` / `h`                 | Cycle status back                  |
| `Ctrl-d` / `Ctrl-u`       | Scroll description pane            |
| `Enter` / `Space` / `Tab` | Toggle collapse                    |
| `s`                       | Toggle selection on current task   |
| `e`                       | Edit in Emacs at task              |
| `p`                       | Edit the task's linked plan in Emacs, or start agent-assisted plan creation |
| `n`                       | Create a new sibling task          |
| `N`                       | Create a new child task            |
| `A` (shift-a)             | Archive the top-level task (must be `DONE` or `CANCELLED`) |
| `P` (shift-p)             | Publish local task → `TASKS.org` (local tasks only)        |
| `U` (shift-u)             | Unpublish task → `TASKS.local.org` (top-level shared tasks only) |
| `J` (shift-j)             | Open linked-issue URLs in the browser (see *Linked external issues* below) |
| `Esc` / `Alt+T`           | Close (Alt+T toggles)              |

### Local tasks

Tasks stored in the gitignored `TASKS.local.org` file appear at the bottom of
the task tree, separated by a `⊠  Local drafts` divider. They are rendered
with a magenta `⊠` marker instead of the standard `•`/`▶`/`▼`, and their
summary text is magenta-tinted to distinguish them from shared tasks.

`TASKS.local.org` may contain any mix of `#+SELECTED:`, task headings, and
`#+IMPORT:` keywords alongside the selection keyword — all coexist in the
same file.

- **`P`** — publish the local task under the cursor to `TASKS.org` (makes it
  git-tracked and shared). Prompts for confirmation.
- **`U`** — unpublish the top-level shared task under the cursor to
  `TASKS.local.org` (removes it from git tracking). Top-level only.
- Local tasks cannot be archived — publish first.
- Creating a new task (`n`/`N`) while the cursor is on a local task writes
  the new task to `TASKS.local.org` rather than `TASKS.org`.

### Selection

Pressing `s` marks the task under the cursor as the *selected* task. This:

- Writes `#+SELECTED: <UUID>` to the gitignored `TASKS.local.org` (single-select — any prior selection is cleared).
- Lets the selected marker move down into subtasks while keeping the selected path visible.
- Auto-collapses sibling subtrees by default, so the view focuses on the current workstream rather than only the selected leaf.
- Highlights the selected task with a `★` marker and renders the selected top-level tree with a side bar.

Press `s` again on the selected task to clear the selection and return to the default top-level collapsed view.

### Default collapse behaviour

On open, the expanded UI starts compact:

- With no selected task, top-level tasks are shown and task subtrees are collapsed.
- With a selected task, the path to the selected task is expanded so the selected row is visible.
- Sibling subtrees are collapsed by default.
- Completed (`DONE`/`CANCELLED`) subtrees are collapsed unless they must be expanded to reveal the selected task.

Manual expand/collapse using Enter, Space, or Tab applies for the lifetime of the expanded UI session.

### Archiving

Pressing `A` (shift-a) archives the top-level task containing the cursor's task. Archiving:

- Requires the top-level task's status to be `DONE` or `CANCELLED` — other statuses are refused with a notification, to avoid archiving active work by accident.
- Prompts for confirmation before writing anything.
- Removes the task (and all its subtasks and content) from `TASKS.org`.
- Transfers the task as-is into `TASKS.archive.org`, preserving its `#+IMPORT:` link. Plan file contents are not inlined.
- Re-sorts `TASKS.archive.org` by `CLOSED:` time on each archive operation, falling back to `:ARCHIVED:` time when a task has no `CLOSED:` stamp.
- Adds an `:ARCHIVED: [timestamp]` property to the archived heading. The timestamp uses the task's `CLOSED` value when present, otherwise the current time.
- Clears `TASKS.local.org` selection when the selected task is archived, so the compact widget doesn't point at a task that no longer exists.
- Preserves the `#+IMPORT:` link in the archived copy; plan file contents are **not** inlined. The archive entry is a faithful copy of the task as it stood in `TASKS.org`.

Task creation, plan path approval, and archive confirmation prompts temporarily close the expanded UI so input/confirmation dialogs remain visible. After create/archive flows complete or are cancelled, the expanded UI reopens with a refreshed task tree. When creating a new plan, the path prompt is prefilled with the suggested `#+DEFAULT_PLAN_DIR`-based path; accepting it scaffolds and links the file, then sends an agent prompt to develop the plan interactively.

### Change-records (proactive and retrospective)

The file linked from a task via `#+IMPORT:` is called a *change-record*. The file shape is owned by the `org-plan` skill: required on every record are `* Summary`, `* Plan`, and `* Implementation`; `* Context` and `* Open questions` are optional. The same shape is produced by both flows below. `* Context` is promoted from "omit" to "include" only when durable rationale materially exceeds what `* Summary` can carry.

**Proactive flow** — press `p` on a task that has no `#+IMPORT:`, accept the path prompt, and the agent helps draft `* Summary` and `* Plan` up front (promoting `* Context` only when needed). As work proceeds, plan tasks transition `TODO -> STARTED -> DONE`; `* Implementation` and `* Summary` are refreshed along the way.

**Retrospective flow** — cycle a task to `DONE` (via `→` / `l`) when it has no `#+IMPORT:` already attached. The extension prompts for a path, scaffolds the change-record file, attaches `#+IMPORT:` to the parent task, and sends the agent a prompt to draft `* Summary` and `* Implementation` (and `* Context` if rationale warrants it) from `git log` scoped to the task's `:STARTED:` and `CLOSED:` timestamps. The user-facing behaviour:

- Triggers only on `TODO -> DONE` and `STARTED -> DONE`. `CANCELLED` does not trigger; cycling away from `DONE` does not trigger.
- Triggers only when the parent task has no `#+IMPORT:` yet. Tasks with an existing change-record (planned or retrospective) skip the prompt.
- If the resolved path already points to an existing file, content is appended (never overwritten).
- Cancelling the path prompt leaves the task `DONE` with no record attached. The user can attach one later via the `p` keybinding.

**Closure-time `* Summary` refresh** — cycling a task with an existing `#+IMPORT:` to `DONE` triggers a parallel check: the extension reads the linked change-record and, when the file either lacks `* Summary` or has not been touched since the parent task's `:STARTED:` timestamp, sends the agent a prompt to author or refresh `* Summary` per the `org-plan` skill's *Closure-time summary refresh* section. The check is sandboxed to the project root and silently skips when the change-record cannot be read.

**Setting:** `~/.pi/agent/tasks-ext.json`

```json
{
  "changeRecordOnDone": true,
  "summaryOnDone": true
}
```

Both default to `true`. Setting `changeRecordOnDone` to `false` suppresses the retrospective scaffold flow; setting `summaryOnDone` to `false` suppresses the closure-time `* Summary` refresh prompt. The two flows are mutually exclusive (no `#+IMPORT:` triggers the first; with `#+IMPORT:` triggers the second).

### Timestamps

- **`:CREATED:`** — written on every new task created via `/tasks new`,
  `n`, or `N`, in `[YYYY-MM-DD Day HH:MM]` format. Existing tasks are
  not backfilled.
- **`:STARTED:`** — written the first time a task moves into `STARTED`.
  Subsequent `DONE -> STARTED` re-opens preserve the original value.
  Used by the retrospective change-record flow as a fast lower-bound
  cache for `git log`.
- **`CLOSED:`** — written on transition to `DONE` or `CANCELLED`.
  Emitted on its own line above the `:PROPERTIES:` drawer (matches
  `org-todo`'s native behaviour). The parser accepts `CLOSED:` in
  either position and serializes back above the drawer. Reopening a
  closed task clears current `CLOSED:`; re-closing writes a fresh value.
- **`:LOGBOOK:`** — task-local lifecycle history written after
  `:PROPERTIES:` and before task body text. New tasks get a
  `- Created [timestamp]` entry; every status transition appends a
  `- State "NEW" from "OLD" [timestamp]` entry. LOGBOOK is append-only
  audit history; `CLOSED:` and heading status remain current-state
  caches.

### Linked external issues

Tasks may reference issues in external trackers (Jira, GitHub, Linear,
etc.) via a generic, tracker-agnostic mechanism owned by this extension.

**Drawer property `:LINKED_ISSUES:`** — multi-valued, whitespace-separated
list of tokens. Each token is either:

1. **Bare key** (e.g. `MBFW-123`) — resolved against `#+ISSUE_URL_BASE`
   (see below) to produce a clickable URL. Rendered as the key itself.
2. **Org link** `[[url][label]]` — resolved directly, no template needed.
   Rendered as `label`.

The two forms can mix freely on a single line:

```org
:PROPERTIES:
:CUSTOM_ID: 01234567-…
:LINKED_ISSUES: MBFW-123 MBE-45 [[https://github.com/foo/bar/issues/42][gh#42]]
:END:
```

The property is created on first link only — never auto-backfilled on
existing tasks or pre-created on new tasks (mirroring `:STARTED:`).

**File keyword `#+ISSUE_URL_BASE:`** — URL template used to resolve bare
keys. Two forms accepted:

```org
#+ISSUE_URL_BASE: https://your-org.atlassian.net/browse/{ID}
#+ISSUE_URL_BASE: https://your-org.atlassian.net/browse/
```

Resolution rule for bare keys:

1. URL-encode the key.
2. If the template contains `{ID}`, substitute the encoded key for every
   occurrence.
3. Otherwise treat the template as a prefix and append the encoded key.

Unusual URL shapes (`https://issues.example.com/?id={ID}&v=full`) are
also expressible. Non-HTTPS schemes are intentionally permitted because
issue URL bases are project-local trusted configuration. `TASKS.local.org`
may override the keyword (last-write-wins, mirroring `#+SELECTED:`) as
part of the user's checkout-local trust boundary.

**Rendering** — each linked issue appears as a cyan badge prefixed with
`⤴`, immediately before the tags suffix on the task row. Badges show
in both the expanded UI and the compact selected-task widget. Tasks
with no `:LINKED_ISSUES:` are rendered unchanged.

**`J` keybinding (expanded UI)** — opens every resolvable URL on the
cursor task in the user's browser, capped at 5 with a notification when
exceeded. Empty/absent property is a silent no-op. Bare tokens with no
resolvable URL (no `#+ISSUE_URL_BASE` and not an org link) trigger a
notification pointing at the missing keyword. Browser is invoked via
`open` (macOS) or `xdg-open` (Linux/other), passing each URL as a
separate argv element rather than through shell interpolation.

**Tracker-specific workflows** (claim, transition, comment, create) are
intentionally *not* part of this extension. They live in companion
extensions like `jira` that contribute slash commands and use
`getDrawerProperty` / `setDrawerProperty` / `getLinkedIssues` from this
extension's parser to read and write the property.

## Cross-extension tools

The extension also registers an LLM-callable tool that other extensions
(and the agent itself) can use to insert tasks into TASKS-shaped files
without hand-rolling org-mode strings.

### `tasks_insert_task`

Inserts a new TODO task into a project's `TASKS.org` (or sibling) under
a named section. Performs deterministic org rendering (priority cookie,
UUID, `:CREATED:` timestamp, `:LINKED_ISSUES:` drawer line, label
tag suffix). Refuses with a structured `duplicate` error when any
supplied `:LINKED_ISSUES:` token already appears in the scanned set.

**Args** (TypeBox schema in `index.ts`):

| Field                | Type      | Description                                                                                          |
| -------------------- | --------- | ---------------------------------------------------------------------------------------------------- |
| `file`               | string    | Absolute or cwd-relative path to the org file to insert into. Must resolve under the project root.    |
| `section`            | string    | Level-1 heading text (e.g. `Improvements`). Tags on the heading line are tolerated.                  |
| `summary`            | string    | Heading text. Required, non-empty.                                                                   |
| `priorityName`       | string?   | `Highest`/`High`/`Medium`/`Low`/`Lowest`. Anything else → no priority cookie.                       |
| `body`               | string?   | Body text rendered after the drawer.                                                                 |
| `linkedIssues`       | string[]? | Tokens written into `:LINKED_ISSUES:` *and* checked for idempotency.                                 |
| `labels`             | string[]? | Rendered as org tags `:l1:l2:`.                                                                      |
| `parentId`           | string?   | When set, render as a level-3 subtask. The id itself is not embedded.                                |
| `allowCreateSection` | bool?     | When true, missing sections are appended to the file. Default `false`.                               |
| `alsoScan`           | string[]? | Additional in-project org files scanned for `:LINKED_ISSUES:` collisions. Imports walked recursively. |

Cross-extension JS callers of `insertTaskIntoFile` should pass
`projectRoot: ctx.cwd` so the in-project sandbox uses the extension's
working directory rather than ambient `process.cwd()`.

**Return shapes** (in `details`):

```ts
// success
{ status: "inserted",          id, file, line }
// idempotency refusal
{ status: "duplicate",         existingId, existingFile, conflictingToken }
// section missing without allowCreateSection
{ status: "section_not_found", file, section }
// caller mis-configured
{ status: "error",             reason, message }
```

Duplicate / `section_not_found` / `error` results all carry
`isError: true` so the agent surfaces them as recoverable refusals
rather than fatal failures.

The Jira `jira_clone_apply` tool delegates to this primitive (via a
direct JS import of `insertTaskIntoFile` from `./insert.ts`) so all
org-mode string assembly stays in one place. Future tracker
integrations (github / linear / gitlab / `/jira create` reverse path)
should use the same primitive. The helper rejects target and scan paths
that resolve outside the project root after symlink resolution.

### `org_read_section`

Returns a single top-level section of an org file — the heading line
plus the body up to (but not including) the next column-0 `* ` heading,
with nested `**`/`***` subheadings preserved verbatim. Backs the
layered resume read order the `org-tasks` skill declares "eagerly
loaded" (`skills/org-tasks/SKILL.md` § *Resuming and agent memory*),
so agents fetching `* Summary` / `* Context` / `* Open questions` from
a change-record never have to pull the full `* Implementation` ledger
into context.

Namespaced `org_*` (not `tasks_*`) because the contract is generically
org-file shaped: it carries no task semantics in its parameters or
return shape. The default section value (`"Summary"`) is a convention
from `org-plan` but is overridable. The helper currently lives in this
extension for packaging convenience; it migrates to a dedicated
`pi/extensions/org/` extension as part of the tree-sitter-backed
org-tooling extraction (TASKS.org task
`f361c429-45dd-4364-9fa3-1f77bd7c600a`). The tool name will not change
across that move.

**Args** (TypeBox schema in `index.ts`):

| Field     | Type     | Description                                                                                                                                                                                                                                                       |
| --------- | -------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `file`    | string   | Absolute or cwd-relative path to the org file. Sandboxed under the project root; out-of-root paths are rejected.                                                                                                                                                  |
| `section` | string?  | Section name to extract. Matched case-insensitively and ignoring any trailing `:tags:` on the heading line. Operates on the literal file only — `#+IMPORT:` chains are not followed (the `tasks_resume_brief` composer resolves imports). Default: `"Summary"`. |

**Return shapes** (in `details`):

```ts
// section matched — heading is verbatim, body is the slice between
// the heading line (exclusive) and the next * heading (exclusive)
// or EOF.
{ kind: "section", file, section: { found: true,  heading, body } }

// no section with the requested name was present in the file.
// `section` echoes the user-requested casing.
{ kind: "section", file, section: { found: false, section } }

// path resolved outside the project root, or read failed.
{ kind: "error", error: "out_of_root" | "unreadable", file, message }
```

The `not found` case is **not** an error — it returns the structured
result so callers can fall back gracefully (e.g. the
`tasks_scan_summaries` composer surfacing "this change-record lacks
`* Summary`" just like the closure-time refresh path already does).

The `content[].text` view of a found section renders heading + body
verbatim so simple LLM consumers can ingest it without unpacking
`details`. Behaviour details:

- **Source-block aware** — `#+BEGIN_<kind>` / `#+END_<kind>` regions
  (case-insensitive on the directive) are tracked so a literal `* `
  inside an example or src block doesn't terminate the slice early.
- **First match wins** — when a file contains multiple `* Summary`
  sections, the first one is returned and its slice ends at the
  second.
- **Synchronous closure-time path bypasses this tool** — the
  closure-time `evaluateSummaryRefresh` check in `summary.ts` still
  uses a direct regex on file contents because it must decide
  synchronously whether to close the overlay. Routing through this
  tool would add latency to that hot path for no gain.

### `tasks_scan_summaries`

Walks every task in the project's `TASKS.org` + `TASKS.archive.org`
(and their `#+IMPORT:` change-record chains) and returns a flat array
of `ScanRow` objects capturing each task's heading metadata, the linked
change-record's `* Summary` body, and a `hasContext` flag. Designed
for the planning agent's prior-art discovery step: scan many tasks,
relevance-filter the rows, then load specific change-records via
`org_read_section`.

Why not just `Read` change-records directly? Two reasons:

1. **Token economy**. A 200-task archive sweep returns ~100KB of
   structured data at the default 500-char body cap; reading the same
   change-records in full would be ~10× that and largely irrelevant.
2. **Cross-file composition**. Parent headings live in `TASKS.org` /
   `TASKS.archive.org`; `* Summary` lives in `design/log/*.org`. The
   scanner stitches them together and follows `#+IMPORT:` chains so
   plan-task headings inside change-records surface as their own rows.

The rejected `tasks_resume_brief` framing ("the cheap resume surface
for an in-progress task") collapsed into this design: resuming an
in-progress task wants the *whole* change-record (just `Read` it); the
real consumer for a structured tool is *batch* prior-art scanning.
Full rationale lives in `design/log/2026-05-12-tasks-scan-summaries.org`.

**Args** (TypeBox schema in `index.ts`):

| Field          | Type                                  | Description                                                                                                                                            |
| -------------- | ------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `scope`        | `"active" \| "archived" \| "all"`?     | Which top-level files contribute tasks. `active` walks `TASKS.org` (+ `TASKS.local.org` + file-level imports); `archived` walks `TASKS.archive.org`; `all` walks both. Default `all`. |
| `tags`         | `string[]?`                            | OR-semantics whitelist. A row is included when its heading carries any listed tag. Empty / omitted disables tag filtering.                              |
| `maxBodyChars` | `number?`                              | Cap on inlined `* Summary` body per row. Bodies longer than this are truncated with a trailing `…` sentinel. Default `500`.                          |

**`ScanRow` shape** (in `details.rows`):

```ts
type ScanRow = {
  id: string;                    // :CUSTOM_ID:
  summary: string;               // task heading text
  status: string;                // TODO / STARTED / WAITING / DONE / CANCELLED
  priority: string | null;       // 'A' | 'B' | 'C' | 'D' | null
  tags: string[];
  sourcePath: string | null;     // absolute path of the defining file
  importPath: string | null;     // #+IMPORT: value verbatim, or null
  recordSummary:
    | { found: true; body: string }    // body capped at maxBodyChars
    | { found: false }                  // record unreadable / no * Summary
    | null;                             // task has no #+IMPORT: at all
  hasContext: boolean;           // record has a top-level * Context heading
};
```

**Why `* Context` is not inlined.** The `org-plan` skill says `* Context`
is promoted only when durable rationale exceeds what `* Summary` can
carry — so when it exists it tends to be long. Bulk-inlining it would
defeat the scanner's whole point (cheap surface, agent decides what to
deepen). The `hasContext` boolean lets the agent decide whether to
fetch the body via `org_read_section({ section: "Context" })` for
relevant rows. The `* Open questions` section gets the same treatment
by omission; if a follow-up reveals consumers want OPEN headings
alongside, add an `openHeadings: string[]` field then.

**Why no duplication into `TASKS.archive.org`.** Mirroring `* Summary`
into archived task bodies at archive-time was considered and rejected:
it would introduce drift (closure-time `* Summary` refresh updates the
change-record, not the archive copy), bloat the archive without bound,
and save zero tokens (the scanner returns the same chars to the agent
regardless of which file the I/O happens against). Walking N small
change-record files on demand is sub-second on a modern SSD.

**Behaviour details:**

- **Memoised reads.** Each change-record file is read at most once per
  call even when multiple tasks share a record (e.g. a workstream root
  and its plan-task children both pointing at the same file).
- **Subtask walk.** The scanner descends into `task.children` *and*
  `task.importChildren`, so plan-task headings inside change-records
  emit their own rows with `sourcePath` pointing at the record file.
- **Out-of-root / unreadable records.** Surface as `recordSummary: {
  found: false }` rather than failing the whole scan (mirrors
  `loadLinkedPlans`).
- **Tasks without `:CUSTOM_ID:`** are skipped — without an id the
  agent has no handle to fetch more context. The `/tasks doctor`
  command surfaces them as a separate finding.
- **Ordering.** Rows are emitted in walker order (depth-first,
  file-position within each root; active roots before archived when
  `scope: "all"`). Agents that want chronological / relevance ordering
  re-sort post-hoc.

The `content[].text` view renders one line per row
(`[STATUS] short-id  [#P] summary :tags: … (+ctx)`) for ad-hoc
inspection, capped at 60 rows so the chat view stays readable; the
full row array always lives in `details.rows`.

## Cross-extension events

The extension uses a small set of events on the shared pi event bus
(`pi.events.emit/on`) so other extensions can trigger or react without coupling:

| Event                   | Payload                                                              | When                                                |
| ----------------------- | -------------------------------------------------------------------- | --------------------------------------------------- |
| `tasks:show`            | optional `{ ctx }`                                                   | Open the expanded tasks UI.                         |
| `tasks:new`             | optional `{ ctx }`                                                   | Create a new top-level task.                        |
| `tasks:doctor`          | optional `{ ctx }`                                                   | Run health checks against the loaded task graph.    |
| `tasks:status-changed`  | `{ id, status, prevStatus, summary, closed }`                        | A task's status is cycled via `→`/`←` / `l`/`h`.    |
| `emacs:open`            | `{ file, line }`                                                     | The user presses `e` or `p` to edit in Emacs.       |

`tasks:status-changed` is intended for integrations like `jira`'s
auto-transition: it fires after the on-disk update is persisted so
file-watcher listeners see the new state before consumers act. The
payload is a plain JSON-serialisable object — consumers must not
mutate it.

External edits to TASKS.org / TASKS.local.org (e.g. via Emacs) trigger
a file-watcher reload but do *not* currently emit
`tasks:status-changed`; this event reflects in-overlay status cycles
only. Diff-based detection on reload is a possible future addition.

## File format

File-format details (heading syntax, properties, `#+IMPORT:`,
`#+DEFAULT_PLAN_DIR:`, `#+SELECTED:`, change-record sections) live
in the `org-tasks` skill: `skills/org-tasks/SKILL.md`. The
extension implements that protocol; this README only covers the UI
and extension-specific behaviour.

Extension-specific notes:

- **Round-trip preservation**: in `TASKS.org`, file-level metadata,
  preamble, and non-task category headings stay in place; only
  parsed task subtrees are rewritten. Within task subtrees,
  `:PROPERTIES:` and `:LOGBOOK:` drawers are preserved structurally.
  In linked change-records, sections like `#+TITLE`, `#+TODO`,
  `* Summary`, `* Context`, `** Design decisions`, `* Plan`,
  `* Implementation`, and `* Open questions` are preserved; only
  parsed task subtrees are rewritten.
- **Permissive parsing**: actionable task headings may appear
  anywhere in a linked change-record. Using `* Plan` is the
  recommended convention but not required.
- **`#+IMPORT:` link form**: the value can be a bare path,
  `[[file:...]]`, or `[[file:...][label]]`. Whichever form is on
  disk is preserved exactly. New change-records scaffolded by `p`
  are written in `[[file:...]]` form so they're clickable in Emacs.
- **Subtask absorption**: if `p` is pressed on a task that already
  has local subtasks, those subtask trees are moved into the new
  change-record under `* Plan`; the parent retains a plain-text
  bullet summary of the extracted subtasks.

## Tests

```sh
./test.sh
```

Runs structural sanity checks against `index.ts` plus the task-memory
regression suites:

- `parser.test.ts` — parser round-trip invariants and the
  `scaffoldPlan()` literal-snapshot test.
- `insert.test.ts` — deterministic task insertion, LOGBOOK rendering,
  duplicate detection, and insert-tool path sandboxing.
- `lifecycle.test.ts` — status-cycle LOGBOOK semantics, reopen
  clearing `CLOSED:`, and re-close timestamp refresh.
- `paths.test.ts` — project-root sandbox resolution for import and
  scaffold paths, including traversal and symlink escapes.
- `doctor.test.ts` — loaded-graph health checks (duplicate IDs,
  broken imports, stale selection, blockers, stale parent status).
- `memory.test.ts` — scenario-style agent-memory reconstruction for a
  selected task with imported plan context, implementation notes,
  blockers, and `* Summary` ingestion order.
- `summary.test.ts` — closure-time `* Summary` refresh detection:
  missing-section trigger, stale-mtime trigger, and the no-op case
  where `* Summary` already exists and is recent.
- `section.test.ts` — `org_read_section` primitive: present, absent,
  final-section-runs-to-EOF, file-with-zero-headings, literal `* `
  inside `#+BEGIN_SRC` / `#+BEGIN_EXAMPLE`, case-insensitive matching
  with trailing `:tags:`, default-to-Summary, nested subheadings
  preserved, and duplicate-section first-match-wins.
- `scan.test.ts` — `tasks_scan_summaries` primitive: rich record,
  missing record content, record without `* Summary`, task with no
  `#+IMPORT:`, `hasContext` flag per record, `scope=active`/`archived`/`all`,
  tags OR-semantics, `maxBodyChars` truncation (with and without the
  `…` sentinel) and default cap, tasks without `:CUSTOM_ID:` skipped,
  plan tasks inside change-records emitting their own rows.

These tests are the authoritative behavioural contract for the
org-memory protocol implemented by this extension.

Requires `tsx` on `$PATH` (e.g. via `npx tsx` or a global install).

## Dependencies

None beyond `@mariozechner/pi-coding-agent` and `@mariozechner/pi-tui`.

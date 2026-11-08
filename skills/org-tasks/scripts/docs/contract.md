# `ot` machine-output contract

This document pins the JSON / EDN contract every machine-readable `ot` command emits when called with `--format json` or `--format edn`. It is the shared surface between `ot` and any consumer (the pi extension, other coding harnesses, CI scripts, future Emacs companions). Bump rules are in [../../../../design/log/2026-05-18-tasks-extension-ot-cli.org#Decisions](../../../../design/log/2026-05-18-tasks-extension-ot-cli.org#decisions).

## Schema version

```
"schema": "org-tasks/v1"
```

- Additive fields in `result` are non-breaking and remain `v1`.
- Renames, removals, or shape changes bump to `v2`.
- Consumers must accept and ignore unknown additive fields.

## Envelope

### Success

```json
{
  "ok": true,
  "schema": "org-tasks/v1",
  "result": { ... },
  "warnings": []
}
```

- `result` is the per-command payload documented below.
- `warnings` is an array of `{ code, message, location? }` for non-fatal conditions (e.g. unreadable change-record encountered during a scan).

### Failure

```json
{
  "ok": false,
  "schema": "org-tasks/v1",
  "error": {
    "code": "section-not-found",
    "message": "Section 'Improvements' not found in /repo/TASKS.org",
    "file": "/repo/TASKS.org",
    "line": null,
    "details": {}
  }
}
```

- `code` is a stable kebab-case identifier (`section-not-found`, `unknown-task`, `ambiguous-id`, `path-outside-project`, `duplicate-linked-issue`, `validation`, `invalid-status`, `out-of-root`, `unreadable`, `git-unavailable`, `empty-summary`, `conflict`, `unterminated-drawer`, `confirmation-required`, `top-level-root`, `inbound-blockers`).
- `message` is a single-line human-readable summary.
- `file` and `line` are populated when locatable; otherwise `null`.
- `details` may carry command-specific extra context.
- Optional top-level `help` is an array of contextual hints, separate from `result` or `error`. `list` suggests `show <id>` and carries explicit `--root`, `--tasks` and `--archive` selectors with shell-quoted values; text mode appends `Next:` lines. Argument errors include the resolved command's concise reference, or `ot --help` when unresolved. Domain errors and ordinary confirmations do not gain hints. Error envelopes remain on stderr.
- The process exit code is `0` on success, `1` on domain failure, and `2` on argument or option parse failure.
- Registry positional arguments and value-taking options default to strings, so text such as `1e3` is preserved. Explicit boolean, numeric, enum, and repeatable coercions take precedence, both before and after command names. A dash-led body must use `--body=<text>`; the separate-argument form remains unsupported.
- Surplus positional arguments fail with `argument-error` before the selected command handler runs. The message echoes the surplus tokens. The error uses an already-parsed `--format json|edn`; options after surplus input are not parsed or recovered.

#### `conflict`

Write-time optimistic-concurrency check: a mutator (`status`, `priority`, `move`, `remove`, `archive`, `publish`, `unpublish`, `create`, `record create`, `issue`/ `blocker`/`handoff` mutations, `backfill`) detects that a target file's on-disk bytes no longer match the snapshot it loaded at the start of the command -- e.g. another `ot` process or an editor wrote to it in between. The command aborts without writing:

```json
{
  "code": "conflict",
  "message": "File changed on disk since it was loaded: /repo/TASKS.org",
  "file": "/repo/TASKS.org"
}
```

#### `unterminated-drawer`

A `:PROPERTIES:` or `:LOGBOOK:` drawer that never reaches a matching `:END:` before end-of-file. Every command that loads the graph (read-only or mutating) fails fast with this code rather than silently treating the rest of the file as drawer content or rewriting it:

```json
{
  "code": "unterminated-drawer",
  "message": "Unterminated drawer in /repo/TASKS.org starting at line 42",
  "file": "/repo/TASKS.org",
  "line": 42
}
```

#### `ambiguous-id`

Id-accepting commands resolve exact `:CUSTOM_ID:` values first, then accept any unique prefix of at least four characters. When a prefix matches more than one task, commands fail with `code: "ambiguous-id"` and include the matching tasks in `details.matches`:

```json
{
  "code": "ambiguous-id",
  "message": "Task id prefix 'abcd' is ambiguous (2 matches)",
  "details": {
    "id": "abcd",
    "matches": [
      { "id": "abcd1111-2222-4333-8444-555555555551", "summary": "First", "file": "/repo/TASKS.org" },
      { "id": "abcd9999-2222-4333-8444-555555555559", "summary": "Second", "file": "/repo/TASKS.local.org" }
    ]
  }
}
```

## Common task shape (`Task`)

Returned by `list --full` (`rows[]` and within `tree[]`), `show`, `selected`, `status`, `priority`, `move`, `publish`/`unpublish`, `archive`/`unarchive`, and as the `task` field of related result payloads. `create` returns a locator instead (see [#`ot create`](#ot-create)).

```jsonc
{
  "id":            "uuid",
  "status":        "TODO | STARTED | WAITING | DONE | CANCELLED",
  "priority":      "A | B | C | D | null",
  "summary":       "Heading text",
  "description":   "Body text below the drawers",
  "tags":          ["backend", "security"],
  "level":         2,
  "propertyLines": [":CUSTOM_ID: uuid", ":CREATED: [2026-05-18 Mon 08:00]"],
  "logbookLines":  ["- Created [2026-05-18 Mon 08:00]"],
  "sourcePath":    "/repo/TASKS.org",
  "line":          12,
  "endLine":       20,
  "local":         false,
  "selected":      false,
  "importPath":    "plan:foo.org | null",
  "importRaw":     "[[plan:foo.org]] | null",
  "importError":   "Cannot read change-record file: /repo/design/log/foo.org | null",
  "closed":        "2026-05-18 Mon 09:00 | null",
  "started":       "2026-05-18 Mon 08:30 | null",
  "createdAt":     "2026-05-18 Mon 08:00 | null",
  "blockedBy":     ["task:uuid", "url:https://...", "human: text"],
  "handoff":       "Pick up at parser delimiter test | null",
  "linkedIssues":  [
    { "rawToken": "[[jira:ABC-1]]", "label": "ABC-1", "url": "https://..." }
  ],
  "parentId":      "uuid | null"
}
```

`createdAt` is the immutable `:CREATED:` drawer timestamp, or null for a task without one. `selected` is transient: it compares the task with the `#+SELECTED:` pointer that holds after the command, so `archive` reports false for a task whose selection it cleared. `importPath` is the link target and `importRaw` the bracket-link source text; `importRaw` is null when the `#+IMPORT:` value is a plain path.

Tree form additionally carries `"children": [Task]` and an `"importChildren": [Task]` array populated when an `#+IMPORT:` change-record is resolvable. `ot show` / `ot selected` omit `sourceContent` and `effectiveSourceContent` by default; pass `ot show <id> --include-content` to include those raw strings on the returned task tree. `ot list --full` deduplicates large source strings into its `result.sources` map instead.

## Default invocation and TUI stdout contract

- Bare `ot` on an interactive terminal runs the standalone task TUI.
- Bare `ot` without an interactive terminal emits the same success envelope as `ot selected --format json` and exits without blocking.
- Bare `ot --format json` also emits the selected-task envelope and never starts interactive rendering.
- During an interactive session, terminal rendering/control sequences are written to stderr or the controlling terminal. Stdout is reserved for exactly one final `org-tasks/v1` selected-task envelope after the TUI exits.
- The final envelope reflects the persisted `#+SELECTED:` value. Moving the cursor is not selection; press `s` to persist a new selected task, or `s` again on the selected row to clear it.
- In the standalone TUI, `D` first calls `ot remove --dry-run --prune-blockers`, displays the impact, and arms only that cursor task. A second `D` on the same task calls `ot remove --yes --prune-blockers`. Cursor movement, mutations, mode changes, or a different task disarm it. The pi overlay exposes the same removal through its modal confirmation. Neither UI has a separate blocker-prune key.

## Embedded TUI context contract

The shared consumer is `ot tui --context-file /absolute/private/context.json --instance <instance-id>`. This is an interactive terminal entrypoint, not a host API. It emits no final stdout envelope in embedded mode because the project can change during its lifetime. `ot tui [--mode compact|expanded]` without embedding options is standalone and retains the final selected-task envelope. The default standalone mode is expanded. Standalone `--exit-on-select` exits after a successful select command. `--root`, `--tasks` and `--archive` cannot accompany `--context-file`; incomplete embedding options fail with `argument-error` and exit 2.

### Producer snapshot

Create a private directory for each interface instance (directory mode `0700`, snapshot mode `0600`). The producer owns this directory and must not accept another instance's file. Write a complete UTF-8 JSON object to a unique sibling temporary file, then rename it over the context path. Never truncate the live snapshot in place. Start a new instance with a new, non-blank `instanceId` and pass that value through `--instance`; do not reuse an earlier process's file or identity.

```json
{
  "schema": "org-tasks-embed/v1",
  "instanceId": "interface-instance-1",
  "contextId": "source-project-a-1",
  "availability": "local",
  "directory": "/absolute/project/a/subdirectory",
  "mode": "compact",
  "updatedAt": 1793908800000
}
```

| Field | Contract |
| --- | --- |
| `schema` | Exactly `org-tasks-embed/v1`; independent of command-output schema `org-tasks/v1`. |
| `instanceId` | Non-blank string equal to `--instance`; fixed for one consumer process. |
| `contextId` | Non-blank source-context identity, independent of task UUIDs. Issue a new value on each source change, including A → B → A. Heartbeats retain it. |
| `availability` | `local`, `unavailable` or `remote`. No fallback project is permitted. |
| `directory` | For `local`, an absolute existing local directory. For `remote` or `unavailable`, ignored and may be null. Never mark a remote working-directory string as `local`. |
| `mode` | `compact` or `expanded`; the snapshot controls mode, not simulated key input. |
| `updatedAt` | Integer Unix epoch milliseconds. Renew at least once per second, including when context is unavailable. |
| `hints` | Optional array of strings. When no task is selected, compact mode shows them, one per line, below the status-count line. Use them for host key hints, such as `ctrl+b t: Select a task from the backlog`. |

All fields except `directory` and `hints` are required. Unknown additive fields are ignored. A `hints` value that is not an array of strings is invalid. Instance mismatch, missing fields, unknown enum values, malformed JSON, relative/nonexistent local directories and unreadable files are invalid. Remote context is rejected before any local directory resolution. The producer must publish `unavailable` when no eligible source exists, rather than retaining a different tab's project. Source-pane eligibility, active-pane/workspace-root policy, placement, sizing, focus restoration and resource ownership belong to the host adapter; this contract defines no host methods.

### Consumer state and liveness

The consumer polls every 500 ms and revalidates input on every input event and immediately before command effects or editor launches. A snapshot is stale when its age exceeds 5000 ms or it is more than 1000 ms in the future. A stopped producer therefore loses authority within 5500 ms while the event loop is available. Graph loading and synchronous editor launches can delay repainting; no mutation input runs concurrently with those operations, and the lease is checked again before a command. This lease is not a PID check and does not authenticate a producer: private file ownership is a producer requirement.

Missing, invalid, stale, remote, unavailable and graph-load-error states are explicit and disable mutations. Without an open interaction, the unavailable view hides the earlier project's graph. A valid local directory goes through `org-tasks.root/resolve-root` with the directory as its starting cwd, including the existing nearest-ancestor rule. If the resolved root has no `TASKS.org`, the consumer shows `No task memory in project` and does not initialise it. Recovery from a failed snapshot reloads the current valid context; task UUID equality never makes two contexts interchangeable.

A create input, every move-picker stage (including section input), and armed removal confirmation freeze their original graph and action root. Valid source or mode changes show `Project switch pending`; the latest snapshot takes effect after submission or cancellation. A context failure blocks submission even while a modal is open; cancellation remains available. `Esc` cancels an embedded armed removal rather than exiting. Deferred valid switches may complete the original-root action, but a context change not observed by the event loop is refused at the command effect. Original-root task memory must still exist. Command handlers retain their sandbox, validation, confirmation and optimistic-concurrency rules.

Graph refresh runs serially through the existing full-list bridge on each poll, including imported files and setupfile changes. Refresh waits while a modal is open. The consumer rereads context after loading and discards the graph if instance, context identity, directory, availability or mode changed; heartbeat-only updates do not supersede a result. There are no parallel graph-refresh jobs that can install an older result later. Poll commands are per-program one-shot commands; exit stops scheduling them and leaves no recurring watcher.

Compact mode is passive except for exit keys. It shows selected-task identity first, then its top-level parent, every `STARTED` descendant and the next pending descendant within the actual height. `STARTED` descendants are current; when no descendant has started, the selected task is current. The TUI sets the terminal title (OSC 2) to `Tasks :: <project folder> :: <plan file>` and omits `:: <plan file>` when the selected task has no linked plan. A host may mirror this title into its pane label. An absent selection shows a status-count line, such as `12 tasks: 7 TODO / 2 STARTED / 3 DONE`: the total, then each status with a non-zero count in canonical order, followed by the context `hints`; an unresolved non-null pointer says `Stale selection`. Expanded mode retains the standalone action set. Very small expanded terminals use a single-column cursor/prompt view; navigation remains active and output stays within the reported geometry.

### Producer and consumer fixtures

[`../test/fixtures/embedding/local.json`](../test/fixtures/embedding/local.json) is the producer template. Replace its directory and timestamp when writing a live snapshot. [`../test/org_tasks/embedding_test.clj`](../test/org_tasks/embedding_test.clj) supplies `produce!` (atomic replacement), disposable A/B roots, lease-boundary and invalid-input cases, modal switches, producer loss, external imports, superseded refresh and standalone-output assertions. Run `bb test-clojure org-tasks.embedding-test org-tasks.tui-test org-tasks.cli-test org-tasks.launcher-test` from the repository root. These fixtures do not validate native host placement or focus.

## Per-command results

### `ot list`

The JSON and EDN default result is `{root, selectedId, count, rows}`. Each row contains only `id`, `parentId`, `status`, `priority`, and `summary`. `count` is the number of returned rows after status and depth filters; an empty result has `count: 0` and `rows: []`. Default results omit `tree`, `files`, and `sources`.

`--full` returns the full graph payload below, without `count`:

```json
"result": {
  "root":         "/repo",
  "tree":         [Task],
  "rows":         [Task],
  "selectedId":   "uuid | null",
  "files": {
    "tasks":   "/repo/TASKS.org",
    "local":   "/repo/TASKS.local.org",
    "archive": "/repo/TASKS.archive.org"
  },
  "sources": {
    "/repo/TASKS.org": {
      "sourceContent": "raw TASKS.org content",
      "effectiveSourceContent": "content plus expanded setupfiles"
    }
  }
}
```

- `tree` is depth-first nested by `children` and `importChildren`.
- `rows` is the same tasks flattened in walker order, each carrying `parentId`.
- `selectedId` echoes `#+SELECTED:` from `TASKS.local.org`.
- `sources` is keyed by absolute source path and carries file content once per path so UI clients can resolve link templates or preserve org text without duplicating large strings on every task row.
- `--format text` prints a task count and renders an `id` column showing the first 8 characters of each `:CUSTOM_ID:`. The prefix is pasteable into any id-accepting command because it satisfies the ≥ 4-char minimum; collisions surface as `ambiguous-id` at lookup time (see `ot doctor`'s `:patterned-sibling-ids` for prevention).

Options:

- `--levels <n>` (non-negative integer) caps hierarchy depth: `0` shows only top-level tasks, `1` shows top-level plus their direct children, `2` adds grandchildren, and so on. Omit the flag for unlimited depth. Depth is measured against the actual displayed graph, so a status filter that hides a parent still resolves its surviving descendants against the deepest visible ancestor.
- `--status-filter <STATUS>` / `-S` (repeatable) keeps only matching rows.
- `--scope active | archived | all` selects which task files contribute (currently advisory; the loader walks the active set in every mode).

### `ot show <id>`

`<id>` may be a full `:CUSTOM_ID:` or any unique prefix of at least four characters. Ambiguous values fail with `ambiguous-id`.

```json
"result": {
  "task":            Task,
  "ancestors":       [Task],
  "record": {
    "path":          "/repo/design/log/foo.org",
    "sections":      ["Intent", "Summary", "Plan", "Implementation", "Validation"],
    "hasContext":    true,
    "hasOpenQuestions": false
  }
}
```

`ot show` is strict about its argument: an id that resolves to nothing fails with `unknown-task` and exit 1, and `ot show selected` on a project with no selection is that same failure (its message names `ot selected`). Use `ot selected` to *query* the selection. `ancestors` is ordered root → parent. `record` is `null` when the task has no resolvable `#+IMPORT:` change-record. `task.children` and `task.importChildren` carry nested task trees. `sourceContent` and `effectiveSourceContent` are omitted unless `--include-content` is passed. Text-mode `show` and `selected` append a non-empty `Task.description` after one blank separator line. The JSON/EDN payload and its `description` field are unchanged.

### `ot create`

```json
"result": {
  "id":           "uuid",
  "file":         "/repo/TASKS.org",
  "line":         42,
  "sectionCreated": false
}
```

Options include `--section`, `--parent`, `--after`, `--priority`, repeated `--tag`, `--body` (a body line that would parse as an Org heading is indented by one space; pass a body that can start with `-` as `--body=<text>`), repeated `--linked-issue`, repeated `--also-scan`, `--allow-create-section`, and compatibility/test overrides `--id` and `--created-at`. `--parent` inserts a child under that task; `--after` inserts a sibling after the anchor task. Both resolve full IDs or unique prefixes across imports and write to the anchor's source file. `--relative-to` takes precedence over explicit placement. Linked-issue duplicate checks scan the target, both configured task roots (shared and local), their imports, and explicit `--also-scan` files. Errors: `unknown-task`, `ambiguous-id`, `section-not-found`, `duplicate-linked-issue`, `path-outside-project`, `empty-summary`.

### `ot move <id> (--parent <id> | --section <name> | --before <id> | --after <id>)`

```json
"result": {
  "task":             Task,
  "file":             "/repo/TASKS.org",
  "parentId":         "uuid | null",
  "section":          "Improvements | null",
  "beforeId":         "uuid | null",
  "afterId":          "uuid | null",
  "previousParentId": "uuid | null",
  "fromLevel":        3,
  "toLevel":          2,
  "movedCount":       2,
  "dryRun":           false
}
```

Relocates an *existing* task subtree **within its own file**. Exactly one destination is required: `--parent <id>` appends the subtree as that task's last child; `--section <name>` appends at level 2 in an existing level-1 section; `--before <id>` inserts as the anchor's preceding sibling; `--after <id>` inserts as a sibling after the anchor's whole subtree. Heading depth is adjusted for the whole moved subtree. Relative moves inherit the anchor's depth and in-file parent; no parent or section option is needed. `parentId` is the resulting in-file parent UUID, or `null` for a file root. `section` names only an explicit section destination; it is `null` for task destinations. `beforeId` and `afterId` contain the resolved anchor UUID for their respective modes and are otherwise `null`. Sections must already exist; moves do not create them. `previousParentId` is `null` when the task was already a root of its file. `movedCount` counts the moved root plus its descendants. `fromLevel`/`toLevel` are the subtree root's heading depth before and after. `task` reflects the post-move `level` and `line`. Like every mutator that returns `Task` (`status`, `priority`, `move`, `archive`, `unarchive`, `publish`, and `unpublish`), its default JSON/EDN projection omits `sourceContent` and `effectiveSourceContent` across the complete task tree. Pass `--include-content` to any of those commands to include both fields; text output is unchanged. This applies equally to `--dry-run` envelopes.

The subtree's source lines are relocated verbatim apart from heading stars, so `:CUSTOM_ID:`, `:CREATED:` / `:STARTED:`, `CLOSED:`, `:LOGBOOK:`, `#+IMPORT:`, unknown drawer properties, bodies, descendant order, and intra-subtree blank lines are byte-preserved, and every region of the file outside the move is left untouched. Because `:CUSTOM_ID:` never changes, `#+IMPORT:` links and a change-record's `#+PARENT: [[task:<uuid>]]` keep resolving across a move.

Cross-file moves are refused: locality changes belong to `publish` / `unpublish`, and the archive to `archive` / `unarchive`. Errors: `argument-error` (no id, or not exactly one of `--parent`, `--section`, `--before`, `--after`), `unknown-task` and `ambiguous-id` (source or destination id), `section-not-found` (no such level-1 section in the owning file), `validation` (archived source, destination is the source itself or one of its descendants, destination lives in another file), plus `conflict`, `unreadable`, and `unterminated-drawer`. `--dry-run` runs every preflight and reports the same fields with `dryRun: true`, writing nothing.

### `ot status <id> <new-status>`

```json
"result": {
  "task":         Task,
  "prevStatus":   "TODO",
  "status":       "STARTED",
  "closed":       null,
  "started":      "2026-05-18 Mon 13:18",
  "promoted":     [{ "id": "uuid", "prevStatus": "TODO", "status": "STARTED" }]
}
```

`promoted` lists ancestor auto-promotions performed by the same call. The boolean `--no-promote` leaves ancestor statuses unchanged and returns `promoted: []`, for both explicit transitions and `--cycle forward|back`. Default TODO-ancestor promotion on STARTED is unchanged. Dry-run never writes. Doctor can still report `stale-parent-status` after an intentional opt-out. ID-accepting mutators resolve targets across the full loaded graph, including tasks in `TASKS.org`, `TASKS.local.org`, and `#+IMPORT:`-linked plan/change-record files. For linked plan targets, writes persist to the task's own `sourcePath`; ancestor auto-promotion may also update the importing TASKS file. Errors: `unknown-task`, `invalid-status`, `validation`.

### `ot priority <id> [<level>]`

```json
"result": {
  "task":         Task,
  "prevPriority": "A | B | C | D | null",
  "priority":     "A | B | C | D | null"
}
```

Sets the priority cookie explicitly (`<level>`, case-insensitive), cycles it with `--cycle forward|back`, or removes it with `--clear`. The cycle order includes the unset slot: forward from unset lands on `A` (highest), back from unset lands on `D` (lowest); `A` back and `D` forward return to unset. Priority changes do not write LOGBOOK entries. Errors: `unknown-task`, `invalid-priority`, `argument-error`.

### `ot select [<id>]`

```json
"result": {
  "selectedId":   "uuid | null",
  "previousId":   "uuid | null",
  "file":         "/repo/TASKS.local.org"
}
```

Pass `--clear` to deselect. `ot select --clear-stale` is the explicit repair path for a stale pointer and returns:

```json
"result": {
  "selectionState": "absent | valid | cleared-stale",
  "selectedId": "uuid | null",
  "previousId": "uuid | null",
  "file": "/repo/TASKS.local.org",
  "dryRun": false
}
```

It writes only for `cleared-stale`. Valid and absent local files remain byte-identical. `--dry-run` returns the same result with `dryRun: true` and never writes.

### `ot selected`

Same payload as `ot show <selectedId>`, or `{"selected": null, "selectedId": ...}` with exit 0 when the selection cannot be shown. `selectedId` discriminates the two reasons: `null` means nothing is selected, while a non-null value is a stale `#+SELECTED:` pointer to a task that no longer exists (`ot doctor` reports `selected-not-found`).

### `ot compact [--width <n>]`

The compact selected-task view model. The TUI compact view renders from the same model, and text output prints its lines at `--width` (default 80). Native hosts render the JSON themselves and keep only presentation: status colours, emphasis and alignment.

```json
{
  "title": "Tasks :: ~/project :: 2026-11-05-plan.org",
  "folder": "~/project",
  "root": "/abs/project",
  "memory": true,
  "plan": "2026-11-05-plan.org",
  "selectedId": "…",
  "selection": "selected",
  "total": 12,
  "counts": [{"status": "TODO", "count": 7}, {"status": "STARTED", "count": 2}, {"status": "DONE", "count": 3}],
  "rows": [{"role": "task", "id": "…", "status": "STARTED", "summary": "…", "label": "… [8-char id]", "current": false, "token": null}],
  "files": ["/abs/project/TASKS.org", "/abs/project/TASKS.local.org"]
}
```

| Field | Contract |
| --- | --- |
| `title` | `Tasks :: <folder>`, plus ` :: <plan>` when the selected task or an ancestor links a plan. The TUI sets it as the terminal title. |
| `folder` / `plan` | Home-shortened project folder; plan file name, or null. |
| `root` | Absolute resolved project root, which can be above the working directory. Watch it for changes to `files`. |
| `memory` | False when the resolved project has no `TASKS.org`; renderers should say so rather than show `0 tasks`. |
| `selection` | `selected`, `none`, or `stale` (a non-null `selectedId` that resolves to no task). |
| `total` / `counts` | Every task in the graph; each status with a non-zero count, in canonical status order. |
| `rows` | Empty unless `selected`. In order: the selected task (`task`), its top-level parent (`parent`), every STARTED descendant (`started`), then the next pending descendant (`next`). |
| `rows[].current` | True for STARTED descendants, or for the selected task when none has started. Renderers emphasise current rows. |
| `rows[].token` | Right-justified text or null: the `done/total` descendant progress on the first `started` row (on the `task` row when nothing has started), and `...` on the `next` row when more pending descendants are hidden. |
| `files` | Absolute paths of the task files the view reads: `TASKS.org`, `TASKS.local.org`, `TASKS.setup.org` and every loaded import. The archive is excluded. Watch them to refresh. |

### `ot remove <id> [--prune-blockers] --yes`

```json
"result": {
  "targetId": "uuid",
  "subtree": [{ "id": "uuid", "summary": "Child", "status": "TODO", "sourcePath": "/repo/design/log/plan.org" }],
  "uncheckedCriteria": [{ "taskId": "uuid", "taskSummary": "Child", "sourcePath": "/repo/design/log/plan.org", "taskLine": 42, "criterion": "Verify migration" }],
  "inboundBlockers": [{ "taskId": "uuid", "taskSummary": "Other", "sourcePath": "/repo/TASKS.org", "blocker": { "raw": "task:uuid", "kind": "task", "ref": "uuid" } }],
  "affectedFiles": ["/repo/TASKS.org", "/repo/design/log/plan.org"],
  "selection": { "selectedId": "uuid | null", "cleared": true },
  "prunedBlockers": [],
  "dryRun": false
}
```

Targets must have an active graph parent or import owner; protocol top-level roots fail with `top-level-root`. `--dry-run` returns the projection without writing. A non-dry-run call without `--yes` fails with `confirmation-required` and carries the same projection in `error.details`. Inbound blockers similarly fail with `inbound-blockers` unless `--prune-blockers` is explicit. With pruning, only task blockers resolving into the subtree are removed. Target, blocker-owner, and affected selection baselines are checked before the first atomic source-root write; imported/local owners retain their own files.

### `ot archive <id>`

```json
"result": {
  "task":         Task,
  "archivePath":  "/repo/TASKS.archive.org",
  "archivedAt":   "2026-05-18 Mon 13:30",
  "planRewrite": {
    "file":       "/repo/design/log/foo.org",
    "from":       "task:uuid",
    "to":         "archive:uuid"
  }
}
```

Archiving writes only the task's source file, the archive, the task's own linked record when its matching parent link changes from `task:` to `archive:`, and the local task file when clearing a selection inside the archived subtree. All other loaded source files remain byte-identical and are not written. The source file is updated even when its last task is archived; `--dry-run` writes nothing.

Errors: `unknown-task`, `validation` (e.g. not closed), `path-outside-project`.

### `ot unarchive <id> [--section <name>]`

```json
"result": {
  "task": Task,
  "from": "/repo/TASKS.archive.org",
  "to": "/repo/TASKS.org",
  "section": "Improvements",
  "sectionSource": "--section | :ARCHIVE_OLPATH:",
  "planRewrite": { "file": "plan:foo.org", "from": "archive:uuid", "to": "task:uuid" }
}
```

Restores only an archive-resolved exact UUID or unique prefix. It refuses unknown/ambiguous archive IDs through the standard `unknown-task`/`ambiguous-id` errors, active duplicate UUIDs, missing archive section metadata (unless `--section` is supplied), missing destination sections, sandboxed record paths, unreadable files, and write conflicts. Result fields are additive in `org-tasks/v1`; `--dry-run` has the same proposed fields and performs no writes.

### `ot publish <id>` / `ot unpublish <id>`

```json
"result": {
  "task":         Task,
  "from":         "/repo/TASKS.local.org",
  "to":           "/repo/TASKS.org"
}
```

### `ot doctor`

```json
"result": {
  "findings": [
    {
      "code":     "duplicate-id",
      "severity": "error | warn",
      "message":  "Duplicate :CUSTOM_ID: …",
      "location": { "file": "/repo/TASKS.org", "line": 7, "heading": "Foo" }
    }
  ],
  "counts": { "error": 0, "warn": 0 }
}
```

`selected-not-found` is read-only guidance: it names `ot select --clear-stale`, which atomically repairs only an unresolved local pointer. `done-with-unchecked-criteria` is a warn-level, read-only finding for each `DONE` task with exact `- [ ] criterion` lines. Its message includes the task summary and unchecked-criterion count. `CANCELLED` and open tasks do not produce this finding. `inline-path-dangling` is an advisory doctor finding for a missing constrained inline file citation in a change-record. Its `location.file` and `location.line` identify the first occurrence of each distinct candidate. Valid or excluded token forms produce no finding.

`spec-untouched` and `spec-stale` use working-tree/index paths. For a record whose importing task is `DONE` or `CANCELLED`, paths changed in commits touching that record also count as spec-maintenance evidence, including initial and merge commits. Open records remain working-tree-only. History is scoped per record and does not change the linked-code trigger for `spec-stale`.

### `ot section <file> [<section>]`

```json
"result": {
  "file":     "/repo/design/log/foo.org",
  "section":  "Summary",
  "found":    true,
  "heading":  "* Summary",
  "body":     "…"
}
```

Errors: `out-of-root`, `unreadable`. Not-found is a non-error result with `"found": false`.

### `ot scan`

```json
"result": {
  "rows": [
    {
      "id":            "uuid",
      "summary":       "…",
      "status":        "TODO",
      "priority":      "A | B | C | D | null",
      "tags":          ["…"],
      "sourcePath":    "/repo/TASKS.org",
      "importPath":    "design/log/foo.org",
      "recordSummary": { "found": true, "body": "…" },
      "hasContext":    true
    }
  ],
  "scope":  "active | archived | all",
  "count":  42
}
```

`recordSummary` may also be `{ "found": false }` (record missing/unreadable or lacking `* Summary`) or `null` (task has no `#+IMPORT:` at all).

### `ot record create <id>`

```json
"result": {
  "taskId":           "uuid",
  "recordPath":       "/repo/design/log/foo.org",
  "importRaw":        "[[plan:foo.org]]",
  "created":          true,
  "absorbedSubtasks": true,
  "scope": {
    "since":          "2026-05-18 Mon 08:30 | null",
    "until":          "2026-05-18 Mon 13:30 | null",
    "commits":        ["abcdef1", "1234567"]
  }
}
```

`absorbedSubtasks` is true when a newly-created record absorbed existing child task trees from the parent into its `* Plan` section. The parent keeps the `#+IMPORT:` link and loses those local children so each UUID has one canonical writable location. Migrated child imports are rebased as source-relative `file:` links to preserve their resolved targets. An unresolved child import fails with `validation` before the record or parent is written. Existing record files are not modified for migration; their parent's local subtasks stay in place. `--path` can name a nested or non-default location: the emitted import must resolve to that exact file, using a `plan:` link when the source template resolves it or a source-relative `file:` link otherwise. Without `--path`, an existing task import selects the record, so repeated creation reuses it. `scope.commits` is populated only for `--mode retrospective` (via `git log`). Errors: `unknown-task`, `path-outside-project`, `validation`, `git-unavailable`.

### `ot record path <id>`

```json
"result": {
  "taskId":    "uuid",
  "suggested": "design/log/2026-05-18-short-task-name.org"
}
```

### `ot issue list <id>`

```json
"result": {
  "taskId":  "uuid",
  "issues":  [
    {
      "rawToken": "[[jira:ABC-1]]",
      "label":    "ABC-1",
      "url":      "https://example.atlassian.net/browse/ABC-1",
      "error":    null
    }
  ]
}
```

### `ot issue add|remove <id> <token>` / `ot blocker add|remove <id> <token>`

Each call accepts one token. Issue mutations return `{taskId, tokens}` (raw token strings); blocker mutations return `{taskId, blockers}` (the blocker objects shown below). Adding an existing normalised stored token succeeds with exit 0 and the unchanged result shape/list, without saving the owning file. Text output says `already present`. Existing historical duplicates are not removed, and equivalent reference spellings are not resolved. Removing an absent token remains a successful no-op.

### `ot blocker prune [--yes]`

```json
"result": {
  "pruned": [{ "taskId": "uuid", "taskSummary": "Blocked task", "sourcePath": "/repo/TASKS.org", "blocker": { "raw": "task:missing", "kind": "task", "ref": "missing" } }],
  "dryRun": false
}
```

`--dry-run` reports unresolved explicit or bare-full-UUID task blockers without writing. A non-empty actual prune requires `--yes`. Valid/ambiguous task references and every non-task blocker are preserved.

### `ot blocker list <id>`

```json
"result": {
  "taskId":  "uuid",
  "blockers": [
    { "raw": "task:uuid", "kind": "task", "ref": "uuid" },
    { "raw": "legacy-full-uuid", "kind": "task", "ref": "legacy-full-uuid" },
    { "raw": "url:https://...", "kind": "url", "ref": "https://..." }
  ]
}
```

### `ot tag add|remove <id> <tag>`

```json
"result": {
  "taskId": "uuid",
  "tags":   ["backend", "security"]
}
```

Mutates only the target task's trailing heading tags and returns the resulting ordered tag vector. Tags use `[A-Za-z0-9_]+`. Surrounding whitespace and one `:tag:` wrapper are normalised, while whitespace within a tag, colons, and other characters fail with `invalid-tag`. `add` does not duplicate a tag and `remove` is a no-op when it is absent. Both commands accept `--dry-run` and then return the same envelope without writing. The normal graph saver writes the owning `TASKS.org`, `TASKS.local.org`, or imported plan file atomically; a changed source fails with `conflict` and remains untouched.

### `ot ready <id>`

```json
"result": {
  "taskId":  "uuid",
  "ready":   true,
  "gating":  [
    {
      "blocker": { "raw": "task:other", "kind": "task", "ref": "other" },
      "reason":  "<open status, e.g. TODO | STARTED | WAITING> | missing-task | opaque"
    }
  ]
}
```

### `ot handoff get <id>`

```json
"result": {
  "taskId":   "uuid",
  "handoff":  "Pick up at … | null"
}
```

### `ot uuid`

Generate one or more UUIDv4 values for use as `:CUSTOM_ID:` on hand-authored task blocks. Prefer this over inventing IDs in prose. Sequential / shared-prefix IDs produce ambiguous resolution and are flagged by `ot doctor`'s `:patterned-sibling-ids` check.

```json
"result": {
  "uuids":  ["6593c6fc-d284-4f9e-b6b5-4c159345cd20"],
  "count":  1
}
```

Options: `--count <n>` (default `1`, must be positive). Text output is one UUID per line.

## Global options

The CLI has no global `--local <path>` override. `create --local` is a boolean selector for `<root>/TASKS.local.org`; internal protocol-path maps still carry a `local` path. Options are checked against the selected command's specification, so `list --local` fails with `argument-error` and exit 2. `--tasks` and `--archive` remain path overrides.

All commands accept:

| Flag             | Default                                                 | Notes                                              |
| ---------------- | ------------------------------------------------------- | -------------------------------------------------- |
| `--root <dir>`   | nearest `TASKS.org` ancestor, else cwd                  | Anchors all relative paths; explicit value wins.   |
| `--format <fmt>` | `text` for TTY, `json` for non-TTY (configurable)       | `text`, `json`, `edn`.                             |
| `--tasks <path>` | `<root>/TASKS.org`                                      |                                                    |
| `--archive <path>` | `<root>/TASKS.archive.org`                            |                                                    |
| `--dry-run`      | false                                                   | Mutation commands print the proposed result without writing. |
| `--yes`, `-y`    | false                                                   | Skips confirmation prompts on destructive commands. |
| `--no-color`     | false                                                   | Disables automatic terminal ANSI styling in `--format text`; redirected output is always plain. |
| `--help`, `-h`   | n/a                                                     | Renders dispatch + spec-derived help.              |

## Exit codes

| Code | Meaning                                  |
| ---- | ---------------------------------------- |
| 0    | Success (`ok: true`).                    |
| 1    | Domain error (`ok: false`).              |
| 2    | Argument or option parse failure.        |
| 64   | Reserved for `--dry-run` would-modify.   |

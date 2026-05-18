# Tasks extension boundary

The protocol engine (`ot`, in Babashka) owns durable org-tasks behaviour. The
pi `tasks` extension owns harness-specific UI and event glue. This table is
the contract for the refactor in `design/log/2026-05-18-tasks-extension-ot-cli.org`.

## Ownership table

| Concern                                        | `ot` | pi extension | Notes                                                                                                          |
| ---------------------------------------------- | ---- | ------------ | -------------------------------------------------------------------------------------------------------------- |
| Parse `TASKS.org` / `TASKS.local.org` / `TASKS.archive.org` | ✅   |              | Including drawers, LOGBOOK, `CLOSED:`, `#+IMPORT:`, file-level imports.                                        |
| Serialize task subtrees (round-trip preserving) | ✅   |              | Non-task content around subtrees stays untouched.                                                              |
| Resolve `#+SETUPFILE:` chains (cycle-guarded)  | ✅   |              | Walks one or more setupfiles in declaration order; depth-limited.                                              |
| Resolve `#+LINK:` typed link templates          | ✅   |              | `plan:`, `task:`, `archive:`, plus arbitrary user-declared abbreviations.                                      |
| Sandbox project-root path resolution           | ✅   |              | Symlink-realpath, traversal rejection, nearest-existing-parent.                                                |
| Auto-assign `:CUSTOM_ID:` on missing tasks      | ✅   |              | Runs lazily on read; writes back via the existing source-root.                                                 |
| Lifecycle (`TODO`/`STARTED`/`WAITING`/`DONE`/`CANCELLED`) | ✅   |              | LOGBOOK append, `:STARTED:` once-only, `CLOSED:` write/clear, parent auto-promote.                             |
| Archive top-level closed tasks                  | ✅   |              | `:ARCHIVED:` stamp, archive ordering by closed/archived time, parent-link rewrite `task:` → `archive:`.        |
| Publish / unpublish (local ↔ shared)             | ✅   |              | Top-level-only for unpublish; local task constraints preserved.                                                |
| `#+SELECTED:` read/write (atomic)               | ✅   |              | Write-then-rename; preserves any local task headings or `#+IMPORT:` keywords.                                  |
| Insert task into named section                   | ✅   |              | Replaces TypeScript `insertTaskIntoFile`. `insert.ts` survives only as a shim into `ot create --linked-issue`. |
| Idempotency check across `:LINKED_ISSUES:`       | ✅   |              | Walks `alsoScan` recursively through imports.                                                                  |
| Doctor checks                                    | ✅   |              | All current finding codes plus the protocol-file link-template checks.                                         |
| `org_read_section` (=> `ot section`)             | ✅   |              | Source-block shielding, case-insensitive, trailing-tag tolerant.                                               |
| `tasks_scan_summaries` (=> `ot scan`)            | ✅   |              | Active/archived/all scope, tags OR-filter, body caps, `hasContext`.                                            |
| Change-record scaffolding (`ot record create`)   | ✅   |              | Required sections, `#+IMPORT:` attachment, subtask migration into `* Plan`.                                    |
| Change-record path suggestion                    | ✅   |              | From `#+LINK: plan file:.../%s`, falls back to `./design/log`.                                                 |
| Retrospective `git log` scoping                  | ✅   |              | `--mode retrospective` shells `git log --since/--until` from `:STARTED:` and `CLOSED:`.                        |
| `:LINKED_ISSUES:` get/set/list/remove           | ✅   |              | Tracker-agnostic. Resolves URLs via `#+LINK:` for `urls`.                                                      |
| `:BLOCKED-BY:` / `:BLOCKED-BY+:` get/set/list   | ✅   |              | Plus ready-task computation against the loaded graph.                                                          |
| `:HANDOFF:` get/set/clear                        | ✅   |              |                                                                                                                |
| Overlay rendering (split pane, highlighting)    |      | ✅           |                                                                                                                |
| Compact selected-task widget                     |      | ✅           | Layout, truncation rules, side-bar markers.                                                                    |
| Keybindings (`s`, `e`, `p`, `n`, `N`, `A`, `P`, `U`, `J`, scroll, status cycle) |      | ✅           | All visual interaction stays in pi.                                                                            |
| File watchers (`TASKS.org`, `TASKS.local.org`, plan files) |      | ✅           | Debounced 150 ms; calls `ot list --format json` on fire.                                                       |
| Confirmation / input prompts                     |      | ✅           |                                                                                                                |
| Emacs open at task / plan                        |      | ✅           |                                                                                                                |
| Browser `open` / `xdg-open` for linked issues   |      | ✅           |                                                                                                                |
| Agent prompts (proactive plan, retrospective scaffold, summary refresh) |      | ✅           | Builds prompt strings, calls `pi.sendUserMessage`.                                                             |
| `tasks:status-changed` event emission           |      | ✅           | Driven directly by `ot status`'s JSON envelope (`prevStatus`, `status`, `closed`).                             |
| `tasks_insert_task` LLM tool registration       |      | ✅           | Handler now shells `ot create`.                                                                                |
| `org_read_section` LLM tool registration        |      | ✅           | Handler now shells `ot section`.                                                                               |
| `tasks_scan_summaries` LLM tool registration    |      | ✅           | Handler now shells `ot scan`.                                                                                  |

## Behaviours that stay in TypeScript (cross-extension)

- `insert.ts` exports `insertTaskIntoFile` as a thin async shim that shells
  `ot create --linked-issue …` and parses the JSON envelope back into the
  existing `InsertResult` shape. Keeps `jira_clone_apply` working without a
  coordinated cutover.
- The closure-time `evaluateSummaryRefresh` synchronous check in `summary.ts`
  stays a regex-based local read (already documented; routing through `ot`
  would force async on the overlay close path).

## Behaviours under review

- The retrospective change-record agent prompt (current `buildChangeRecordPrompt`)
  is generated by the pi extension. `ot record create` returns the resolved
  paths and scope range; the extension assembles and dispatches the prompt
  string. Could move into `ot record prompt` later; out of scope for v1.

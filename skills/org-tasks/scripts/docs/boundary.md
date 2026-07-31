# Tasks extension boundary

The protocol engine (`ot`, in Babashka) owns durable org-tasks behaviour and the standalone terminal task browser launched by bare `ot`. The pi `tasks` extension owns pi-specific overlay/compact UI and event glue. This table is the contract for the refactor in `design/log/2026-05-18-tasks-extension-ot-cli.org` and the standalone TUI added in `design/log/2026-06-27-add-org-tasks-tui.org`.

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
| Status **cycle order** (forward/back)            | ✅   |              | `ot status --cycle forward\|back`. Canonical order in `lifecycle/status-cycle`; both UIs pass only a direction. |
| Task-creation **placement policy** (sibling/child/top-level) | ✅   |              | `ot create --relative-to <id> --as sibling\|child` derives parent/after/local/source from the anchor.          |
| Archive top-level closed tasks                  | ✅   |              | `:ARCHIVED:` stamp, archive ordering by closed/archived time, parent-link rewrite `task:` → `archive:`.        |
| Publish / unpublish (local ↔ shared)             | ✅   |              | Top-level-only for unpublish; local task constraints preserved.                                                |
| Move / reparent an existing subtree              | ✅   |              | `ot move <id> --parent <id>\|--section <name>`. In-file line splice (byte-preserving); cross-file moves refused. No TUI/overlay key yet.        |
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
| Standalone terminal task browser (`ot`)          | ✅   |              | Renders to stderr/terminal streams; stdout emits final selected-task JSON.                                      |
| Overlay rendering (split pane, highlighting)    |      | ✅           | Pi-owned expanded overlay; behaviour mirrors the standalone TUI where practical.                               |
| Compact selected-task widget                     |      | ✅           | Layout, truncation rules, side-bar markers.                                                                    |
| Pi overlay keybindings (`s`, `e`, `p`, `n`, `N`, `A`, `P`, `U`, `J`, scroll, status cycle) |      | ✅           | Standalone TUI has its own Clojure key loop; pi event wiring stays in TypeScript. Status-cycle and create-placement *policy* now live in `ot` (both UIs only send direction / anchor+relation). |
| File watchers (`TASKS.org`, `TASKS.local.org`, plan files) |      | ✅           | Debounced 150 ms; calls `ot list --format json` on fire.                                                       |
| Confirmation / input prompts                     |      | ✅           |                                                                                                                |
| Emacs open at task / plan                        |      | ✅           |                                                                                                                |
| Browser `open` / `xdg-open` for linked issues   |      | ✅           |                                                                                                                |
| Agent prompts (proactive plan, retrospective scaffold, summary refresh) |      | ✅           | Builds prompt strings, calls `pi.sendUserMessage`.                                                             |
| `tasks:status-changed` event emission           |      | ✅           | Driven directly by `ot status`'s JSON envelope (`prevStatus`, `status`, `closed`).                             |
| `tasks_insert_task` LLM tool registration       |      | ✅           | Handler now shells `ot create`.                                                                                |
| `org_read_section` LLM tool registration        |      | ✅           | Handler now shells `ot section`.                                                                               |
| `tasks_scan_summaries` LLM tool registration    |      | ✅           | Handler now shells `ot scan`.                                                                                  |

## Consolidated into `ot` (2026-06-27, TUI v3)

Three policies that were previously encoded in both the pi overlay (TypeScript) and the standalone TUI (Clojure) now live solely in `ot`:

- **Status cycle order** — `ot status --cycle forward|back`. The overlay's `STATUS_CYCLE` constant and the TUI's `cycle-status-value` were removed; both send only a direction.
- **Create placement** — `ot create --relative-to <id> --as sibling|child`. The overlay's `createNewTask`/`createTask` placement maths and the TUI's `create-opts` were reduced to passing the anchor + relation.
- **Linked-issue URL resolution** — already emitted by `ot list` (`linkedIssues[].url/label`); the overlay now consumes that wire field and no longer re-resolves via the TypeScript `getLinkedIssues`.

## Behaviours that stay in TypeScript (cross-extension)

- `insert.ts` exports `insertTaskIntoFile` as a thin async shim that shells `ot create --linked-issue …` and parses the JSON envelope back into the existing `InsertResult` shape. Keeps `jira_clone_apply` working without a coordinated cutover.
- The closure-time `evaluateSummaryRefresh` synchronous check in `summary.ts` stays a regex-based local read (already documented; routing through `ot` would force async on the overlay close path).

## Behaviours under review

- The retrospective change-record agent prompt (current `buildChangeRecordPrompt`) is generated by the pi extension. `ot record create` returns the resolved paths and scope range; the extension assembles and dispatches the prompt string. Could move into `ot record prompt` later; out of scope for v1.

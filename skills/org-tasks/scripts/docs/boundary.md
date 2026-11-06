# Tasks extension boundary

The protocol engine (`ot`, in Babashka) owns durable org-tasks behaviour and the standalone terminal task browser launched by bare `ot`. Pi's optional legacy `tasks` visual entrypoint owns overlay/compact UI and event glue. The non-visual `tasks/agent.ts` entrypoint exports prompt helpers without registering UI or session resources; agents access `ot` through their existing shell tools. This table is the contract for the refactor in `design/log/2026-05-18-tasks-extension-ot-cli.org` and the standalone TUI added in `design/log/2026-06-27-add-org-tasks-tui.org`.

The shared compact/expanded terminal renderer also supports embedding. Host adapters own context production, pane/terminal identity, placement, focus and sizing; `ot` owns context validation, liveness, root resolution, safe modal switching and graph refresh. The owning producer/consumer contract is [contract.md#Embedded TUI context contract](contract.md#embedded-tui-context-contract). The existing Pi visual surface remains independent; this boundary adds no host APIs or cross-harness event bus.

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
| Move / reparent an existing subtree              | ✅   |              | `ot move <id>` with one of `--parent`, `--section`, `--before`, `--after`. In-file line splice (byte-preserving); cross-file moves refused. Both UIs bind `m`: relation picker, then same-file task picker or existing-section input. The CLI owns validation and writes; each UI owns its picker.        |
| Remove a non-top-level subtree / prune dangling blockers | ✅ | | `ot remove` / `ot blocker prune` compute integrity impact, confirmation, source-owner writes, and selection repair. Both UIs bind `D`: standalone uses a dry-run plus armed second press; pi uses a modal confirmation. Confirmed removal requests blocker pruning; there is no separate blocker-prune key. |
| `#+SELECTED:` read/write (atomic)               | ✅   |              | Write-then-rename; preserves any local task headings or `#+IMPORT:` keywords.                                  |
| Insert task into named section                   | ✅   |              | Replaces TypeScript `insertTaskIntoFile`. `insert.ts` survives only as a shim into `ot create --linked-issue`. |
| Idempotency check across `:LINKED_ISSUES:`       | ✅   |              | Walks `alsoScan` recursively through imports.                                                                  |
| Doctor checks                                    | ✅   |              | All current finding codes plus the protocol-file link-template checks.                                         |
| Read Org sections (`ot section`)             | ✅   |              | Source-block shielding, case-insensitive, trailing-tag tolerant.                                               |
| Scan task summaries (`ot scan`)            | ✅   |              | Active/archived/all scope, tags OR-filter, body caps, `hasContext`.                                            |
| Change-record scaffolding (`ot record create`)   | ✅   |              | Required sections, `#+IMPORT:` attachment, subtask migration into `* Plan`.                                    |
| Change-record path suggestion                    | ✅   |              | From `#+LINK: plan file:.../%s`, falls back to `./design/log`.                                                 |
| Retrospective `git log` scoping                  | ✅   |              | `--mode retrospective` shells `git log --since/--until` from `:STARTED:` and `CLOSED:`.                        |
| `:LINKED_ISSUES:` get/set/list/remove           | ✅   |              | Tracker-agnostic. Resolves URLs via `#+LINK:` for `urls`.                                                      |
| `:BLOCKED-BY:` / `:BLOCKED-BY+:` get/set/list   | ✅   |              | Plus ready-task computation against the loaded graph.                                                          |
| `:HANDOFF:` get/set/clear                        | ✅   |              |                                                                                                                |
| Standalone and embedded terminal task browser (`ot`, `ot tui`) | ✅ | | Shared compact/expanded rendering, input, confirmations, external graph refresh and context safety. Standalone stdout emits final selected-task JSON; embedded stdout is empty. |
| Overlay rendering (split pane, highlighting)    |      | ✅           | Pi-owned expanded overlay; behaviour mirrors the standalone TUI where practical.                               |
| Compact selected-task widget                     |      | ✅           | Layout, truncation rules, side-bar markers.                                                                    |
| Pi overlay keybindings (`s`, `m`, `D`, `e`, `p`, `n`, `N`, `A`, `P`, `U`, `J`, scroll, status cycle) |      | ✅           | Standalone TUI has its own Clojure key loop; pi event wiring stays in TypeScript. Status-cycle and create-placement *policy* now live in `ot` (both UIs only send direction / anchor+relation). |
| Pi file watchers (`TASKS.org`, `TASKS.local.org`, plan files) | | ✅ | Pi-specific watchers remain separate from the shared TUI's per-instance polling. |
| Confirmation / input prompts | ✅ | ✅ | Each UI owns interaction state; `ot` commands own validation and writes. Embedded terminal interactions freeze the original root. |
| Emacs open at task / plan | ✅ | ✅ | Terminal editor launches are context-guarded; Pi retains its own editor glue. |
| Browser `open` / `xdg-open` for linked issues | ✅ | ✅ | Both consume command-resolved issue URLs. |
| Agent prompts (proactive plan, retrospective scaffold, summary refresh) |      | ✅           | Supplies task identity, paths, mode, and scope to `pi.sendUserMessage`; section and drafting policy stay in the skills.                                                             |
| `tasks:status-changed` event emission | | ✅ | Legacy overlay status cycles only; emitted after the `ot status` write using `prevStatus`, `status`, task summary and closed-state classification. CLI, external-file and Herdr changes do not emit it. |
| Agent CLI access | ✅ | | Agents call `ot create`, `ot section`, `ot scan` and other commands through shell access; neither Pi task entrypoint registers task tools. |

## Consolidated into `ot` (2026-06-27, TUI v3)

Three policies that were previously encoded in both the pi overlay (TypeScript) and the standalone TUI (Clojure) now live solely in `ot`:

- **Status cycle order** -- `ot status --cycle forward|back`. The overlay's `STATUS_CYCLE` constant and the TUI's `cycle-status-value` were removed. Both send only a direction.
- **Create placement** -- `ot create --relative-to <id> --as sibling|child`. The overlay's `createNewTask`/`createTask` placement maths and the TUI's `create-opts` were reduced to passing the anchor + relation.
- **Linked-issue URL resolution** -- already emitted by `ot list` (`linkedIssues[].url/label`). The overlay now consumes that wire field and no longer re-resolves via the TypeScript `getLinkedIssues`.

## Behaviours that stay in TypeScript (cross-extension)

- `insert.ts` exports `insertTaskIntoFile` as a thin async shim that shells `ot create --linked-issue ...` and parses the JSON envelope back into the existing `InsertResult` shape. Keeps the `jira_clone` tool (formerly `jira_clone_apply`) working without a coordinated cutover.
- The closure-time `evaluateSummaryRefresh` synchronous check in `summary.ts` stays a regex-based local read (already documented; routing through `ot` would force async on the overlay close path).

## Behaviours under review

- Change-record prompts remain Pi glue in `pi/extensions/tasks/prompts.ts`. `ot record create` returns paths, subtask-migration state and retrospective scope; the helpers dispatch those facts with a reference to the skills, without copying their section or drafting rules. The non-visual workflow uses Pi's editor for path approval; only the legacy wrapper adds its custom dialog and UI refresh.

## Pi loading boundary

The compatibility `tasks/index.ts` path retains the visual UI until explicit user cutover. `tasks/legacy.ts` is the explicit visual entrypoint; load one visual path, not both. `tasks/agent.ts` can load alone or with the visual entrypoint and registers no commands, tools, overlays, widgets, watchers, shortcuts or event listeners. It exports the prompt helpers independently of visual registration. The historical `tasks_insert_task`, `org_read_section` and `tasks_scan_summaries` tools are not registered; their operations are CLI commands, not replacement Pi tools.

Jira's `insertTaskIntoFile` import continues to delegate to `ot` without loading the visual factory. Jira's optional status listener retains the legacy in-overlay event semantics; no cross-harness event bus is added. See [../../../../pi/extensions/tasks/README.md#Loading and coexistence](../../../../pi/extensions/tasks/README.md#loading-and-coexistence) for loading paths and explicit cutover instructions.

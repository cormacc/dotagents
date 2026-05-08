---
name: org-tasks
description: "Use when maintaining or resuming project work stored in TASKS.org and linked change-record files. Covers the org task-memory protocol: TODO states, IDs, IMPORT links, change-records (proactive and retrospective), status discipline, archiving, and resume workflows."
---

# Org-mode task management and memory protocol

Use this skill when the user asks to work from, update, resume, or review
tasks. The canonical project memory index is `TASKS.org` in the project
root. A task may link to a *change-record* (a separate org file capturing
the task's context, plan, and implementation notes) via a `#+IMPORT:`
keyword.

This skill owns the durable file protocol: file format, properties,
keywords, statuses, selection, and archive layout. The change-record
section structure (`* Context`, `* Plan`, `* Implementation`, optional
`* Open questions`) is owned by the `org-plan` skill
(`../org-plan/SKILL.md`).

## Locating `TASKS.org`

`TASKS.org` is anchored at the **project root** resolved from CWD
(`git rev-parse --show-toplevel`, `projectile-project-root`, or an
explicit project marker). Do **not** walk up parent directories — a
parent project's index is not this project's. If absent at the
resolved root, treat the project as having no task memory and offer
to bootstrap (see *Bootstrap*), never fall back to an ancestor file.
Same rule for `TASKS.local.org`, `TASKS.archive.org`, and `#+IMPORT:`
resolution.

## File protocol

`TASKS.org` and included task files declare the shared TODO cycle:

```org
#+TODO: TODO(t) STARTED(s) WAITING(w) | DONE(d) CANCELLED(c)
```

`TASKS.org` may also declare the default change-record directory:

```org
#+DEFAULT_PLAN_DIR: [[file:./design/log]]
```

When the keyword is absent or malformed the default is
`[[file:./design/log]]`.

### Quick reference

| Item | Meaning | Where |
|------|---------|-------|
| `:CUSTOM_ID:` | UUID v4; required on every task/subtask | `:PROPERTIES:` |
| `:CREATED:` | creation timestamp; do not backfill existing tasks | `:PROPERTIES:` |
| `:STARTED:` | first transition into `STARTED` | `:PROPERTIES:` |
| `CLOSED:` | current close timestamp for `DONE`/`CANCELLED` | line above `:PROPERTIES:` |
| `:LOGBOOK:` | append-only lifecycle audit trail | drawer after `:PROPERTIES:` |
| `:BLOCKED-BY:` / `:BLOCKED-BY+:` | one or more blocker refs | `:PROPERTIES:` |
| `:HANDOFF:` | short “start here” note for next session | `:PROPERTIES:` |
| `#+IMPORT:` | linked change-record / imported task file | task body or file root |
| `#+SELECTED:` | local active task UUID | `TASKS.local.org` |

### Task headings

```org
** TODO [#A] Implement feature :area:
:PROPERTIES:
:CUSTOM_ID: 01234567-89ab-4def-8123-456789abcdef
:CREATED: [2026-04-25 Sat 09:00]
:END:
:LOGBOOK:
- Created [2026-04-25 Sat 09:00]
:END:
#+IMPORT: [[file:design/log/2026-04-25-feature.org]]
Optional description text.
```

- **States**: `TODO`, `STARTED`, `WAITING`, `DONE`, `CANCELLED`.
- **Priorities**: `[#A]` critical, `[#B]` high, `[#C]` medium, `[#D]` low.
- **Tags**: semantic categories. There are no reserved operational tags.
- **Spacing**: separate sibling task subtrees with a single blank line,
  especially top-level project tasks (`** ...` under category headings)
  and archived entries. Preserve readability spacing; do not let
  adjacent task headings run together.
- **`:CUSTOM_ID:`**: UUID v4, required on every task and subtask.
- **`:CREATED:`**: `[YYYY-MM-DD Day HH:MM]`, set on creation. Do not
  backfill on existing tasks. Do not prefix the description with an
  inline `[YYYY-MM-DD Day]` creation marker — that role is owned by
  the property.
- **`:STARTED:`**: `[YYYY-MM-DD Day HH:MM]`, written the first time
  a task transitions into `STARTED`. Preserved on subsequent
  `DONE -> STARTED` re-opens. Used as a fast lower-bound cache for
  retrospective `git log` scoping.
- **`CLOSED:`**: `[YYYY-MM-DD Day HH:MM]`, written on transition to
  `DONE` or `CANCELLED`. Lives on its own line *between the heading
  and the `:PROPERTIES:` drawer* (matches `org-todo`'s native
  behaviour). It is the current closed-state cache: clear it when
  reopening a task, then write a fresh value on the next close.
- **`:LOGBOOK:`**: task-local lifecycle drawer after `:PROPERTIES:`
  and before task body text. It is append-only audit history: one
  `- Created [timestamp]` entry and one `- State "NEW" from "OLD"
  [timestamp]` entry for each status transition. Preserve historical
  entries; never replay them as pending actions.
- **`:BLOCKED-BY:`**: blocker reference(s), usually on `WAITING`
  tasks but also valid on `TODO` tasks as a readiness gate. First
  blocker uses `:BLOCKED-BY:`; additional blockers use org-native
  continuation lines:

  ```org
  :BLOCKED-BY: task:01234567-89ab-4def-8123-456789abcdef
  :BLOCKED-BY+: url:https://github.com/example/project/pull/123
  :BLOCKED-BY+: human: waiting on Alice's review
  ```

  Ready when every entry resolves: `task:<UUID>` must point to a
  `DONE`/`CANCELLED` task; non-task forms (`url:`, `human:`, `jira:`,
  other text) remain opaque blockers until removed. Single-value
  `:BLOCKED-BY:` remains valid and round-trips unchanged.
- **`:HANDOFF:`**: optional short free-form note (typically 1–3 lines)
  flagged for the next session or agent. Allowed on top-level task
  headings and on plan-subtask headings inside change-records. Surfaced
  by resume / selected-task tooling so the next reader sees a concrete
  “start here” pointer.
- **`#+IMPORT:`**: clickable `[[file:...]]` link on its own line in
  the task body, after any metadata drawers. Resolves relative to the
  file containing the keyword. May also appear at file root (before
  any heading) to inject tasks from another file at the root. Preserve
  any existing bare or labelled link form on round-trip.
  `#+IMPORT:` paths and scaffolded plan paths must resolve, after
  symlink resolution, under the project root unless the user has
  explicitly allowed an external directory.

Always obtain timestamps via `date +"%Y-%m-%d %a %H:%M"` rather than
computing them manually.

### Example `TASKS.org`

```org
#+TITLE: Project Tasks
#+TODO: TODO(t) STARTED(s) WAITING(w) | DONE(d) CANCELLED(c)
#+DEFAULT_PLAN_DIR: [[file:./design/log]]

* Improvements

** TODO [#A] Implement authentication :backend:security:
:PROPERTIES:
:CUSTOM_ID: 01234567-89ab-4def-8123-456789abcdef
:CREATED: [2026-04-25 Sat 09:00]
:END:
:LOGBOOK:
- Created [2026-04-25 Sat 09:00]
:END:
#+IMPORT: [[file:design/log/2026-04-25-authentication.org]]
Initial scope captured from user request.

* Housekeeping

** WAITING [#C] Update upstream dependency :nix:
:PROPERTIES:
:CUSTOM_ID: fedcba98-7654-4321-8fed-cba987654321
:CREATED: [2026-04-20 Mon 14:30]
:BLOCKED-BY: url:https://github.com/example/project/pull/123
:END:
Waiting on upstream merge.
```

Keep `TASKS.org` high-level. Put detailed checklists and implementation
history in change-record files. When existing `TASKS.org` subtasks are
migrated into a new change-record, move the task subtrees into `* Plan`
with their `:CUSTOM_ID:` values intact and remove them from the parent
`TASKS.org` subtree. The parent may keep a plain-text bullet summary,
but only the moved plan nodes remain canonical task nodes.

## Selection state

The active task for a contributor is stored in a **gitignored**
`TASKS.local.org` at the project root, expressed as a single keyword:

```org
#+SELECTED: 01234567-89ab-4def-8123-456789abcdef
```

- `TASKS.local.org` is per-checkout and must be in `.gitignore`.
- Absent file or empty `#+SELECTED:` value means "no selection".
- Resolve the UUID against `:CUSTOM_ID:` properties in the loaded task graph.
- Writers must use atomic write-then-rename so file watchers never see
  a half-written file.

`TASKS.local.org` may also contain task headings and `#+IMPORT:`
keywords alongside `#+SELECTED:` — these are local drafts not visible
to other checkouts until published.

## Change-records

A *change-record* is a separate org file linked from a task via
`#+IMPORT:`. Two flows produce the same artefact:

1. **Proactive** — created before work begins. Agent drafts `* Context`
   and `* Plan` up front; `* Implementation` fills in as plan tasks
   transition `TODO -> STARTED -> DONE`.
2. **Retrospective** — created after the parent task closed without a
   prior plan. Agent uses the parent's `:STARTED:` and `CLOSED:`
   timestamps to scope `git log`, then drafts `* Context` and
   `* Implementation` from the commit history. `* Plan` may be empty
   or hold a brief retrospective list including failed attempts.

See `../org-plan/SKILL.md` for the section structure both flows produce
and the planning workflow.

## Status discipline

- Mark `STARTED` when beginning substantial work; write `:STARTED:`
  on the first such transition.
- Mark `DONE` only when implemented and verified; write `CLOSED:` on
  transition.
- Use `WAITING` with `:BLOCKED-BY:` for blocked work; clear or move
  the blocker to a note when unblocked. `:BLOCKED-BY:` may also appear
  on `TODO` tasks to express prerequisite dependencies without forcing
  a `WAITING` state — readiness queries treat both forms identically.
- Use `CANCELLED` for intentionally abandoned work; write `CLOSED:`.
- Append a `:LOGBOOK:` state entry for every status transition. The
  heading status and `CLOSED:` line are mutable current-state caches;
  LOGBOOK is the durable historical record.
- When reopening from `DONE` or `CANCELLED`, clear current `CLOSED:`
  but keep the old close event in LOGBOOK. A later close writes a
  fresh `CLOSED:` and appends another LOGBOOK state entry.
- Direct `TODO -> DONE` retrospective scoping uses `:STARTED:` when
  present; otherwise use the LOGBOOK created event / `:CREATED:` as
  the lower bound.
- Archive sorting uses current `CLOSED:` when present, otherwise the
  latest close event in LOGBOOK, otherwise `:ARCHIVED:`.
- When a child plan task advances, update its ancestors in `TASKS.org`
  to keep parent status meaningful (e.g. parent `TODO -> STARTED` when
  any child reaches `STARTED`).

## Starting or resuming work

1. Read `TASKS.org` and `TASKS.local.org`.
2. Resolve `#+SELECTED:` from `TASKS.local.org` against the task graph;
   fall back to a user-named task, the first `STARTED` task, or context.
3. Follow the active task's `#+IMPORT:` link if present. In a plan,
   resume the first `STARTED` task or the first actionable `TODO`.
4. Read nearby task notes plus relevant `* Context`, `* Implementation`,
   `* Open questions`, blockers, linked issues, and LOGBOOK lifecycle
   history before editing code.
5. Keep statuses and durable notes synchronized as work proceeds.

## Agent memory

For cross-session reconstruction, treat org files as durable memory and
agent conversation as ephemeral. Load eagerly only the task index
(`TASKS.org` plus `TASKS.local.org`) and the selected task's immediate
change-record. Load other imports on demand when they are on the active
path or referenced by blockers / linked issues.

Resume checklist:

1. Identify the selected task via `#+SELECTED:`; otherwise use the first
   active `STARTED` task or ask the user.
2. Read the selected task subtree, its `#+IMPORT:` change-record, and
   its LOGBOOK to understand actual lifecycle history.
3. Read `* Context`, actionable `* Plan` items, `* Implementation`, and
   `* Open questions` from the change-record.
4. Check `:BLOCKED-BY:` (including any `:BLOCKED-BY+:` continuation
   lines), `:HANDOFF:`, and `:LINKED_ISSUES:`. Surface any `:HANDOFF:`
   note and any `OPEN` items under the change-record's `* Open
   questions` immediately on resume. Tracker-specific skills such as
   `org-jira` define when linked upstream state should be re-fetched;
   local summaries may be stale.
5. If a change-record has grown too large for cheap re-ingestion, split
   completed historical context into a follow-up record or archive old
   top-level tasks rather than truncating history silently.

Durable state: task headings, properties, LOGBOOK, change-records,
imports, blockers, linked issues. Per-session state: MCP fetch results,
agent scratch reasoning, UI cursor position, and any unsaved editor
buffers.

The tasks extension regression suites are the executable contract for
this behaviour: `lifecycle.test.ts` covers lifecycle LOGBOOK status
semantics, `paths.test.ts` covers import/scaffold sandboxing,
`doctor.test.ts` covers loaded-graph health checks, and
`memory.test.ts` covers scenario-style selected-task reconstruction.

## Creating tasks and change-records

- Use the smallest useful task granularity: each task should describe
  a concrete outcome that can become `DONE`.
- Prefer adding detail to change-records rather than bloating
  `TASKS.org`.
- New change-records use `YYYY-MM-DD-short-task-name.org` under
  `#+DEFAULT_PLAN_DIR` and declare `#+TITLE:`, `#+DATE:`, `#+PARENT:`
  (a navigable `[[file:<rel>/TASKS.org::#<uuid>][summary]]` link to the
  parent task's `:CUSTOM_ID:`), and the shared `#+TODO:` cycle.
- Add discovered work as new `TODO` tasks rather than burying it in
  prose. Do not remove completed historical tasks unless asked.

## Archiving

Only top-level `DONE`/`CANCELLED` tasks are archived. Archiving moves
the complete subtree to `TASKS.archive.org` in the project root,
preserves `:CUSTOM_ID:` and content, and adds an `:ARCHIVED: [timestamp]`
property. The `#+IMPORT:` link is preserved; plan file contents are
not inlined.

## Bootstrap

If `TASKS.org` does not exist and the user wants persistent task
memory: create it in the project root with `#+TITLE:`, the shared
`#+TODO:`, and `#+DEFAULT_PLAN_DIR: [[file:./design/log]]`; add a
semantic section (e.g. `* Improvements`) and the first actionable
`TODO` with `:CUSTOM_ID:` and `:CREATED:` properties. Detailed work items go
in an included change-record under `#+DEFAULT_PLAN_DIR`.

## Extension points

Third-party skills and pi extensions can build on the task graph by
attaching their own data without modifying this protocol:

- **Unknown `#+` keywords** in `TASKS.org` and `TASKS.local.org`
  preambles round-trip through the parser/serializer untouched. Other
  skills or extensions may claim them for their own use.
- **Unknown drawer properties** on task headings round-trip untouched.
  Other skills or extensions may claim them for per-task data.
- **Naming convention**: third-party properties and keywords should
  use an `UPPERCASE_NAMESPACE_` prefix (e.g. `:NAMESPACE_FOO:`,
  `#+NAMESPACE_BAR`) so they don't collide with first-party
  metadata.
- **`TASKS.local.org` keyword overrides** are last-write-wins,
  mirroring the existing `#+SELECTED:` rule. A keyword present in both
  files takes its value from `TASKS.local.org`.

First-party generic extension features in the `tasks` extension itself
(documented in `pi/extensions/tasks/README.md`):

- **`:LINKED_ISSUES:`** drawer property — multi-valued list of
  external-tracker references; rendered as badges on task rows.
- **`#+ISSUE_URL_BASE:`** keyword — URL template used to resolve bare
  keys in `:LINKED_ISSUES:` to clickable URLs.

These two features are tracker-agnostic; tracker-specific behaviour
(workflow names, MCP routing, slash commands) lives in companion
extensions and skills, not in this protocol.

## Non-goals

| Non-goal | Reason | Revisit when |
|----------|--------|--------------|
| Transient checkout/reconcile `backlog.org` as core model | `TASKS.org` + `#+IMPORT:` is the canonical graph; avoid duplicated task state | a separate working-surface workflow proves necessary |
| Vendor-specific core metadata | tracker/agent fields belong in companion skills/extensions | a vendor-neutral abstraction emerges |
| Transcript/chat-log links by default | durable memory is org files; sessions are ephemeral | transcript retention becomes an explicit user requirement |
| Bidirectional external-tracker sync by default | linked issues are references; companion skills re-fetch when needed | a project requires true sync semantics |
| Human-readable IDs replacing UUIDs | UUIDs are stable and collision-resistant | aliases are added as a layer, not a replacement |
| Per-task attribution properties (`:WORKED_BY:`, `:COMPLETED_BY:`) | git log suffices for the current solo workflow | multi-agent coordination becomes a real need |

## Tooling

The pi tasks extension and the `tasks-org` Emacs minor mode automate
ID assignment, `:CREATED:` / `:STARTED:` / `CLOSED:` timestamps,
parent status propagation, and archive mechanics against this
protocol. When editing task files by hand, follow the rules above
explicitly.

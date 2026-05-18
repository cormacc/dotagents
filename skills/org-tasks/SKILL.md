---
name: org-tasks
description: "The org-mode task-memory protocol for TASKS.org. Use whenever the user asks to add, edit, resume, archive, or review tasks; mentions TASKS.org, TASKS.local.org, TASKS.archive.org, #+IMPORT:, #+SELECTED:, :CUSTOM_ID:, :BLOCKED-BY:, or :HANDOFF:; or wants to bootstrap task memory in a new project. Owns file format, TODO lifecycle, selection state, archive layout, and the resume read order. The change-record section contract is owned by `org-plan`."
---

# Org-mode task management and memory protocol

Use this skill when the user asks to work from, update, resume, or review tasks.
The canonical project memory index is `TASKS.org` in the project root. A task
may link to a *change-record* (a separate org file capturing the task's context,
plan, and implementation notes) via a `#+IMPORT:` keyword.

This skill owns the durable file protocol: file format, properties, keywords,
statuses, selection, and archive layout. The change-record section structure
(`* Summary`, optional `* Context`, `* Plan`, `* Implementation`, optional
`* Open questions`) is owned by the `org-plan` skill (`../org-plan/SKILL.md`).

## Locating `TASKS.org`

`TASKS.org` is anchored at the **project root** resolved from CWD
(`git rev-parse --show-toplevel`, `projectile-project-root`, or an explicit
project marker). Do **not** walk up parent directories — a parent project's
index is not this project's. If absent at the resolved root, treat the project
as having no task memory and offer to bootstrap (see *Bootstrap*), never fall
back to an ancestor file. Same rule for `TASKS.local.org`, `TASKS.archive.org`,
and `#+IMPORT:` resolution.

## File protocol

Repositories declare shared org-tasks options in a root `TASKS.setup.org` and reference it from `TASKS.org`, `TASKS.archive.org`, and change-records via `#+SETUPFILE:`. A minimal setupfile:

```org
#+TODO: TODO(t) STARTED(s!) WAITING(w@/!) | DONE(d!) CANCELLED(c!)
#+STARTUP: logdone logdrawer
#+LINK: plan file:design/log/%s
```

`TASKS.org` and `TASKS.archive.org` reference the setupfile so they pick up the same TODO cycle, `:LOGBOOK:` shape, and link abbreviations without repeating the preamble inline. Change-records use the same `#+SETUPFILE:` pointer (typically `../../TASKS.setup.org`).

The `CLOSED:` line and `:LOGBOOK:` state entries described below are the on-disk contract. Org-mode emits them natively in Emacs with the declarations above; the pi tasks extension emits byte-identical shapes for headless / TUI edits and must remain fully usable without Emacs.

The `plan` link abbreviation replaces the older `#+DEFAULT_PLAN_DIR` keyword. Tooling derives a suggested change-record path by substituting the generated filename into `#+LINK: plan .../%s`; when the abbreviation is absent it falls back to `./design/log/<file>.org`.

### Quick reference

| Item | Meaning | Where |
|------|---------|-------|
| `:CUSTOM_ID:` | UUID v4; required on every task/subtask | `:PROPERTIES:` |
| `:CREATED:` | creation timestamp; do not backfill existing tasks | `:PROPERTIES:` |
| `:STARTED:` | fast cache for the first `STARTED` transition; source of truth is the first STARTED entry in `:LOGBOOK:` | `:PROPERTIES:` |
| `CLOSED:` | current close timestamp for `DONE`/`CANCELLED`, emitted by org-mode or the pi extension in byte-identical format | line above `:PROPERTIES:` |
| `:LOGBOOK:` | append-only lifecycle audit trail | drawer after `:PROPERTIES:` |
| `:BLOCKED-BY:` / `:BLOCKED-BY+:` | one or more blocker refs | `:PROPERTIES:` |
| `:HANDOFF:` | short “start here” note for next session | `:PROPERTIES:` |
| `#+IMPORT:` | linked change-record / imported task file | task body or file root |
| `#+SELECTED:` | local active task UUID | `TASKS.local.org` |

### Line wrapping

Do **not** hard-wrap prose in org files this skill produces or edits
(`TASKS.org`, `TASKS.local.org`, `TASKS.archive.org`, change-records). Each
paragraph is a single logical line; readers rely on `visual-line-mode` /
soft-wrap. Rationale:

- Hard wraps force re-flow on every edit and noise diffs for prose changes.
- Agents and the tasks extension's serializer round-trip single-line paragraphs
  cleanly; reflowed paragraphs lose their original wrap points on rewrite.
- Org structural elements (headings, list items, drawers, table rows,
  `#+KEYWORD:` lines) are already line-bound — wrapping rules only affect body
  prose.

Exceptions, which keep their natural line breaks:

- Code/quote/example blocks (`#+BEGIN_SRC`, `#+BEGIN_EXAMPLE`, `#+BEGIN_QUOTE`).
- Bullet/numbered list items — one item per line; do not wrap a single item
  across multiple physical lines.
- Tables, `:PROPERTIES:` / `:LOGBOOK:` drawer entries, `#+IMPORT:` and other
  `#+KEYWORD:` lines.
- `:BLOCKED-BY:` / `:BLOCKED-BY+:` continuation lines (already line-structured).

When editing an existing file that *was* hard-wrapped by a prior author, do not
opportunistically re-flow paragraphs you aren't otherwise touching — only unwrap
paragraphs you are already modifying, to keep diffs minimal. Bulk normalisation
should be its own dedicated commit.

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
#+IMPORT: [[plan:2026-04-25-feature.org]]
Optional description text.
```

- **States**: `TODO`, `STARTED`, `WAITING`, `DONE`, `CANCELLED`.
- **Priorities**: `[#A]` critical, `[#B]` high, `[#C]` medium, `[#D]` low.
- **Tags**: semantic categories. There are no reserved operational tags.
- **Spacing**: separate sibling task subtrees with a single blank line,
  especially top-level project tasks (`** ...` under category headings) and
  archived entries. Preserve readability spacing; do not let adjacent task
  headings run together.
- **`:CUSTOM_ID:`**: UUID v4, required on every task and subtask.
- **`:CREATED:`**: `[YYYY-MM-DD Day HH:MM]`, set on creation. Do not backfill on
  existing tasks. Do not prefix the description with an inline
  `[YYYY-MM-DD Day]` creation marker — that role is owned by the property.
- **`:STARTED:`**: `[YYYY-MM-DD Day HH:MM]`, a fast lower-bound cache for retrospective `git log` scoping and resume heuristics. Its source of truth is the first `- State "STARTED" ... [timestamp]` entry in `:LOGBOOK:`. The pi extension may write it on first transition; Emacs-derived flows may fill it later if absent. Preserve it on subsequent `DONE -> STARTED` re-opens.
- **`CLOSED:`**: `[YYYY-MM-DD Day HH:MM]`, written on transition to `DONE` or `CANCELLED` by org-mode or the pi extension in byte-identical format. Lives on its own line *between the heading and the `:PROPERTIES:` drawer* (matches `org-todo`'s native behaviour). It is the current closed-state cache: clear it when reopening a task, then write a fresh value on the next close.
- **`:LOGBOOK:`**: task-local lifecycle drawer after `:PROPERTIES:` and before task body text. It is append-only audit history: one `- Created [timestamp]` entry and one `- State "NEW" from "OLD" [timestamp]` entry for each status transition. Org-mode emits transition entries via `#+TODO` bang/at markers and `#+STARTUP: logdrawer`; the pi extension emits the same shape headlessly. Preserve historical entries; never replay them as pending actions.
- **`:BLOCKED-BY:`**: blocker reference(s), usually on `WAITING` tasks but also
  valid on `TODO` tasks as a readiness gate. First blocker uses `:BLOCKED-BY:`;
  additional blockers use org-native continuation lines:

  ```org
  :BLOCKED-BY: task:01234567-89ab-4def-8123-456789abcdef
  :BLOCKED-BY+: url:https://github.com/example/project/pull/123
  :BLOCKED-BY+: human: waiting on Alice's review
  ```

  Ready when every entry resolves: `task:<UUID>` must point to a
  `DONE`/`CANCELLED` task; non-task forms (`url:`, `human:`, `jira:`, other
  text) remain opaque blockers until removed. Single-value `:BLOCKED-BY:`
  remains valid and round-trips unchanged.
- **`:HANDOFF:`**: optional short free-form note (typically 1–3 lines) flagged
  for the next session or agent. Allowed on top-level task headings and on
  plan-subtask headings inside change-records. Surfaced by resume /
  selected-task tooling so the next reader sees a concrete “start here” pointer.
- **`#+IMPORT:`**: clickable org link on its own line in the task body, after
  any metadata drawers. Canonical change-record links use `[[plan:<file.org>]]`,
  resolved through the repository's `#+LINK: plan file:design/log/%s`
  abbreviation. Plain `[[file:...]]` links remain valid for non-plan imports and
  resolve relative to the file containing the keyword. May also appear at file
  root (before any heading) to inject tasks from another file at the root.
  Preserve any existing bare or labelled link form on round-trip. Resolution
  applies symlink-realpath sandboxing per *Locating TASKS.org* above.
- **`task:` / `archive:` link abbreviations**: change-record parent links use
  `[[task:<UUID>][summary]]` for live parents and `[[archive:<UUID>][summary]]`
  for archived parents. `TASKS.setup.org` defines the plan-file-relative
  defaults; `TASKS.org` and `TASKS.archive.org` must declare local overrides
  *before* `#+SETUPFILE:` so org-mode's first-declared abbreviation resolves
  task links correctly from the task files themselves.
- **`#+SETUPFILE:` chain (local before shared)**: `TASKS.org` and
  `TASKS.archive.org` declare two setupfiles in order:
  `#+SETUPFILE: ./TASKS.local.org` followed by `#+SETUPFILE: ./TASKS.setup.org`.
  Org-mode merges both files' in-buffer settings using first-declared-wins
  semantics, so any `#+KEYWORD` in the gitignored `TASKS.local.org` (e.g.
  `#+JIRA_CLOUDID`, `#+JIRA_PROJECT`) overrides the shared default in
  `TASKS.setup.org`. `TASKS.local.org` is always created at bootstrap (even
  if empty apart from `#+SELECTED:`) so org does not warn about a missing
  setupfile on fresh checkouts. Task headings inside `TASKS.local.org` are
  *not* inlined by `#+SETUPFILE:`; only file-level keywords flow through.

Always obtain timestamps via `date +"%Y-%m-%d %a %H:%M"` rather than computing
them manually.

### Example `TASKS.org`

```org
#+TITLE: Project Tasks
#+LINK: task file:TASKS.org::#%s
#+LINK: archive file:TASKS.archive.org::#%s
#+SETUPFILE: ./TASKS.local.org
#+SETUPFILE: ./TASKS.setup.org
#+ARCHIVE: TASKS.archive.org::* From %s

* Improvements

** TODO [#A] Implement authentication :backend:security:
:PROPERTIES:
:CUSTOM_ID: 01234567-89ab-4def-8123-456789abcdef
:CREATED: [2026-04-25 Sat 09:00]
:END:
:LOGBOOK:
- Created [2026-04-25 Sat 09:00]
:END:
#+IMPORT: [[plan:2026-04-25-authentication.org]]
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

Keep `TASKS.org` high-level. Put detailed checklists and implementation history
in change-record files. Subtask migration into a change-record is owned by
`org-plan` § *Subtask migration from TASKS.org*.

## Selection state

The active task for a contributor is stored in a **gitignored**
`TASKS.local.org` at the project root, expressed as a single keyword:

```org
#+SELECTED: 01234567-89ab-4def-8123-456789abcdef
```

- `TASKS.local.org` is per-checkout and must be in `.gitignore`.
- Absent file or empty `#+SELECTED:` value means "no selection".
- Resolve the UUID against `:CUSTOM_ID:` properties in the loaded task graph.
- Writers must use atomic write-then-rename so file watchers never see a
  half-written file.

`TASKS.local.org` may also contain task headings and `#+IMPORT:` keywords
alongside `#+SELECTED:` — these are local drafts not visible to other checkouts
until published.

## Change-records

A *change-record* is a separate org file linked from a task via `#+IMPORT:`. Two
flows produce the same artefact:

1. **Proactive** — created before work begins, usually via the `p` keybinding or
   equivalent plan creation flow.
2. **Retrospective** — created after the parent task closes without a prior
   plan; use the parent's `:STARTED:` (or creation timestamp) and `CLOSED:` as
   the `git log` scope.

Section contract, authoring rules, and subtask migration are owned by `org-plan`
(see top of file).

## Status discipline

- Mark `STARTED` when beginning substantial work; write `:STARTED:` on the first
  such transition.
- Mark `DONE` only when implemented and verified; write `CLOSED:` on transition.
- Use `WAITING` with `:BLOCKED-BY:` for blocked work; clear or move the blocker
  to a note when unblocked. `:BLOCKED-BY:` may also appear on `TODO` tasks to
  express prerequisite dependencies without forcing a `WAITING` state —
  readiness queries treat both forms identically.
- Use `CANCELLED` for intentionally abandoned work; write `CLOSED:`.
- Append a `:LOGBOOK:` state entry for every status transition. The heading
  status and `CLOSED:` line are mutable current-state caches; LOGBOOK is the
  durable historical record.
- When reopening from `DONE` or `CANCELLED`, clear current `CLOSED:` but keep
  the old close event in LOGBOOK. A later close writes a fresh `CLOSED:` and
  appends another LOGBOOK state entry.
- Direct `TODO -> DONE` retrospective scoping uses `:STARTED:` when present;
  otherwise use the LOGBOOK created event / `:CREATED:` as the lower bound.
- Archive sorting uses current `CLOSED:` when present, otherwise the latest
  close event in LOGBOOK, otherwise `:ARCHIVED:`.
- When a child plan task advances, update its ancestors in `TASKS.org` to keep
  parent status meaningful (e.g. parent `TODO -> STARTED` when any child reaches
  `STARTED`).

## Resuming and agent memory

For cross-session reconstruction, treat org files as durable memory and agent
conversation as ephemeral. Load eagerly only the task index (`TASKS.org` plus
`TASKS.local.org`) and the selected task's immediate change-record. Load other
imports on demand when they are on the active path or referenced by blockers /
linked issues.

The condensed surface a future agent reads first is the change-record's
`* Summary` (defined by `org-plan`). Detailed `* Plan` task bodies, completed
plan tasks, and lengthy `* Implementation` notes are loaded on demand once the
agent has decided what to do next.

Resume checklist (read order):

1. Identify the selected task via `#+SELECTED:`; otherwise use the first active
   `STARTED` task or ask the user.
2. Read the selected task subtree (heading, properties, body) and its LOGBOOK to
   understand actual lifecycle history.
3. Open the linked change-record and read, in order:
   1. `* Summary` — condensed final / current state. Always present.
   2. `* Context` — durable rationale, when the record promotes it. Skip when
      absent; `* Summary` is the contract surface.
   3. `:HANDOFF:` (on the parent task or any plan-subtask heading).
   4. `:BLOCKED-BY:` plus any `:BLOCKED-BY+:` continuation lines.
   5. `:LINKED_ISSUES:` (tracker-specific skills such as `org-jira` define when
      linked upstream state should be re-fetched; local summaries may be stale).
   6. Remaining `OPEN` items under `* Open questions`.
   7. Actionable plan items: the first `STARTED` plan task or the first ready
      `TODO`.
4. Surface any `:HANDOFF:` note and any `OPEN` questions immediately on resume.
5. Defer until needed: full `* Implementation` notes, completed
   `DONE`/`CANCELLED` plan task bodies, and detailed acceptance criteria for
   tasks not on the active path. Durable state: task headings, properties,
   LOGBOOK, imports, blockers, linked issues, and change-record files.
   Per-session state: MCP fetch results, agent scratch reasoning, UI cursor
   position, and unsaved editor buffers.

Closure discipline: refresh the linked change-record's `* Summary` before
closing a top-level task; see `org-plan` § *Closure-time refresh and prune*.

When a record's `* Plan` + `* Implementation` history grows beyond cheap
re-ingestion (~150 lines is a useful soft threshold), split completed history
into a follow-up record or archive old top-level tasks — don't omit `* Summary`
or truncate silently. Executable regression coverage for this protocol lives
in `skills/org-tasks/scripts/test/` (the `ot` engine) and
`pi/extensions/tasks/` (the UI/event wrapper).

## Creating tasks and change-records

- Use the smallest useful task granularity: each task should describe a concrete
  outcome that can become `DONE`.
- Prefer adding detail to change-records rather than bloating `TASKS.org`.
- Author body prose as single-line paragraphs (no hard wrap); see *Line
  wrapping* above.
- New change-records use `YYYY-MM-DD-short-task-name.org` under the path
  resolved from the root `#+LINK: plan .../%s` abbreviation. Each record
  declares `#+TITLE:`, `#+DATE:`, `#+SETUPFILE: ../../TASKS.setup.org`, and a
  `#+PARENT:` link (`[[task:<uuid>][summary]]` for live parents,
  `[[archive:<uuid>][summary]]` for archived parents) pointing at the parent
  task's `:CUSTOM_ID:`. Shared `#+TODO:`, `#+STARTUP:`, and org-link
  abbreviations come from the setupfile rather than being inlined.
- Add discovered work as new `TODO` tasks rather than burying it in prose. Do
  not remove completed historical tasks unless asked.

## Archiving

Only top-level `DONE`/`CANCELLED` tasks are archived. `TASKS.org` declares `#+ARCHIVE: TASKS.archive.org::* From %s`; Emacs users may archive with native `org-archive-subtree` (`C-c C-x C-a`) and the pi tasks extension provides the standalone/headless equivalent. Both paths move the complete subtree to `TASKS.archive.org` in the project root, preserve `:CUSTOM_ID:`, content, and `:LOGBOOK:`, add an `:ARCHIVED: [timestamp]` property, and keep the `#+IMPORT:` link without inlining plan contents.

## Bootstrap

If `TASKS.org` does not exist and the user wants persistent task memory:

1. Create `TASKS.setup.org` at the project root with the shared preamble:

   ```org
   #+TODO: TODO(t) STARTED(s!) WAITING(w@/!) | DONE(d!) CANCELLED(c!)
   #+STARTUP: logdone logdrawer
   #+LINK: plan file:design/log/%s
   #+LINK: task file:../../TASKS.org::#%s
   #+LINK: archive file:../../TASKS.archive.org::#%s
   ```

2. Create `TASKS.org` referencing it, declaring `TASKS.local.org` as the
   first setupfile (gitignored per-checkout overrides win — see
   *`#+SETUPFILE:` chain* above):

   ```org
   #+TITLE: Project Tasks
   #+LINK: task file:TASKS.org::#%s
   #+LINK: archive file:TASKS.archive.org::#%s
   #+SETUPFILE: ./TASKS.local.org
   #+SETUPFILE: ./TASKS.setup.org
   #+ARCHIVE: TASKS.archive.org::* From %s
   ```

   Also touch an empty `TASKS.local.org` (gitignored) so the setupfile chain
   resolves on fresh checkouts.

3. Add the first actionable `TODO` under a semantic section (e.g. `* Improvements`) with `:CUSTOM_ID:` and `:CREATED:` properties. Detailed work items go in an included change-record under the `plan` link target.

## Extension points

Third-party skills and pi extensions can build on the task graph by attaching
their own data without modifying this protocol:

- **Unknown `#+` keywords** in `TASKS.org` and `TASKS.local.org` preambles
  round-trip through the parser/serializer untouched. Other skills or extensions
  may claim them for their own use.
- **Unknown drawer properties** on task headings round-trip untouched. Other
  skills or extensions may claim them for per-task data.
- **Naming convention**: third-party properties and keywords should use an
  `UPPERCASE_NAMESPACE_` prefix (e.g. `:NAMESPACE_FOO:`, `#+NAMESPACE_BAR`) so
  they don't collide with first-party metadata.
- **`TASKS.local.org` keyword overrides** are last-write-wins, mirroring the
  existing `#+SELECTED:` rule. A keyword present in both files takes its value
  from `TASKS.local.org`.

First-party protocol-level metadata for external-tracker references
(parsed/written by the `ot` engine; rendered as badges and surfaced by
`J` in the pi tasks extension — see `pi/extensions/tasks/README.md`):

- **`:LINKED_ISSUES:`** drawer property — multi-valued, whitespace-separated list of org-link tokens. Accepted forms are org-native typed links (`[[type:key]]`, canonical) and raw URL org links (`[[https://...][label]]`). Bare keys are not part of the protocol. Created on first link; never auto-backfilled.
- **`#+LINK:`** org keyword — native link abbreviation used to resolve typed issue links, e.g. `#+LINK: jira https://example.atlassian.net/browse/%s`. Multiple prefixes may be declared per file; `TASKS.local.org` overrides last-write-wins.

Both are tracker-agnostic; tracker-specific behaviour (workflow names, MCP routing, slash commands, and the meaning of a prefix such as `jira`) lives in companion extensions and skills, not in this protocol.

## Non-goals

| Non-goal | Reason | Revisit when |
|----------|--------|--------------|
| Transient checkout/reconcile `backlog.org` as core model | `TASKS.org` + `#+IMPORT:` is the canonical graph; avoid duplicated task state | a separate working-surface workflow proves necessary |
| Vendor-specific core metadata | tracker/agent fields belong in companion skills/extensions | a vendor-neutral abstraction emerges |
| Rich Emacs follow handlers for tracker links | first-class syntax uses org-native `#+LINK:`; tracker-specific Emacs packages may add `org-link-set-parameters` later | a tracker-specific editor workflow needs MCP-aware links |
| Transcript/chat-log links by default | durable memory is org files; sessions are ephemeral | transcript retention becomes an explicit user requirement |
| Bidirectional external-tracker sync by default | linked issues are references; companion skills re-fetch when needed | a project requires true sync semantics |
| Human-readable IDs replacing UUIDs | UUIDs are stable and collision-resistant | aliases are added as a layer, not a replacement |
| Per-task attribution properties (`:WORKED_BY:`, `:COMPLETED_BY:`) | git log suffices for the current solo workflow | multi-agent coordination becomes a real need |

## Tooling

The portable protocol engine is the Babashka CLI `ot` under `skills/org-tasks/scripts/`. Prefer `ot` over hand-editing whenever you need to create, inspect, select, transition, archive, publish/unpublish, scan, or diagnose task state programmatically. Use `--format json` for machine callers; every JSON/EDN command uses the stable envelope schema `org-tasks/v1` (`{ok,schema,result,warnings}` on success, `{ok:false,schema,error}` on failure). See `skills/org-tasks/scripts/docs/contract.md` for field-level examples.

Common commands:

```shell
ot init
ot list --format json
ot show <id>
ot create "New task" --section Improvements --linked-issue '[[jira:ABC-1]]'
ot status <id> STARTED
ot select <id>        # or: ot select --clear
ot archive <id> --yes
ot publish <id>       # TASKS.local.org -> TASKS.org
ot unpublish <id>     # TASKS.org -> TASKS.local.org
ot doctor --format json
ot section design/log/foo.org Summary --format json
ot scan --scope all --max-body-chars 500 --format json
ot record path <id>
ot record create <id> --mode retrospective
```

Install for third-party harnesses with bbin:

```shell
bbin install io.github.cormacc/dotagents --as ot --latest-sha
# in-tree development / local smoke:
bbin install ./. --local/root . --as ot
```

The pi tasks extension is now a UI/event wrapper over `ot`: it owns overlay rendering, compact-widget state, keybindings, prompts, confirmations, Emacs opening, browser URL opening, and event emission, while `ot` owns durable protocol mutations and graph reads. Native org-mode features (`logdone`, `logdrawer`, `#+ARCHIVE:`, `#+LINK:`) are deliberately aligned with those artefacts so Emacs edits, `ot` calls, and pi TUI edits converge on the same file shapes. The optional Emacs companion lives at `emacs/tasks-org/` and provides editor conveniences for finding `TASKS.org`, capturing new tasks, toggling task/plan buffers, and toggling `#+SELECTED:`.

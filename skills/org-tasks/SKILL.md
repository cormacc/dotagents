---
name: org-tasks
description: "The org-mode task-memory protocol for TASKS.org and the `ot` CLI that mutates it. Use whenever the user asks to add, edit, resume, archive, select, diagnose, or review tasks; mentions TASKS.org, TASKS.local.org, TASKS.archive.org, #+IMPORT:, #+SELECTED:, :CUSTOM_ID:, :BLOCKED-BY:, :HANDOFF:, linked issues, or `ot`; or wants to bootstrap task memory in a new project. Owns file format, TODO lifecycle, selection state, archive layout, resume read order, and the task-side boundary with change-records. The change-record section contract is owned by `org-plan`."
---

# Org-mode task management and memory protocol

Use this skill when the user asks to work from, update, resume, diagnose, or review project tasks. The canonical project memory index is `TASKS.org` in the project root. A task may link to a change-record via `#+IMPORT:`; the change-record section contract belongs to `org-plan` (`../org-plan/SKILL.md`).

The durable protocol engine is the Babashka CLI `ot` under `skills/org-tasks/scripts/`. Prefer `ot` (or pi tools that wrap it, such as `tasks_insert_task`, `org_read_section`, and `tasks_scan_summaries`) over hand-editing whenever you create, inspect, select, transition, archive, publish/unpublish, scan, diagnose, manage blockers/handoffs/linked issues, or scaffold change-records. Read `references/ot-cli.md` for CLI details and `scripts/docs/contract.md` for machine-output schemas.

## Locating task memory

`TASKS.org` is anchored at the resolved project root (`git rev-parse --show-toplevel`, `projectile-project-root`, or an explicit marker). Do not walk into a parent project looking for an ancestor `TASKS.org`. If the resolved root has no task memory, offer to bootstrap with `ot init`.

The same root rule applies to `TASKS.local.org`, `TASKS.archive.org`, `TASKS.setup.org`, and `#+IMPORT:` resolution. Tooling applies project-root sandboxing to prevent traversal/symlink escapes.

## Protocol summary

The field-level contract lives in `references/protocol.md`; load it when repairing org by hand or answering protocol-specific questions. The short version:

- Files share TODO/logging/link settings through root `TASKS.setup.org`; `TASKS.org` and `TASKS.archive.org` include `TASKS.local.org` first for gitignored keyword overrides, then `TASKS.setup.org`.
- Task states are `TODO`, `STARTED`, `WAITING`, `DONE`, `CANCELLED`. Priorities are `[#A]` through `[#D]`.
- Every task/subtask needs a UUID v4 `:CUSTOM_ID:`. Use `ot create` or `ot uuid`; never invent IDs.
- `:CREATED:`, `:STARTED:`, `CLOSED:`, and `:LOGBOOK:` are lifecycle artefacts. `ot` and native org-mode are aligned so status writes converge on the same file shape.
- `#+IMPORT:` links change-records/imported task files. Canonical plan imports use `[[plan:file.org]]` via the repository `#+LINK: plan file:.../%s` template.
- `#+SELECTED:` in gitignored `TASKS.local.org` stores the local active task. Empty or absent means no selection.
- `:BLOCKED-BY:` / `:BLOCKED-BY+:`, `:HANDOFF:`, and `:LINKED_ISSUES:` are protocol fields managed by `ot blocker`, `ot ready`, `ot handoff`, and `ot issue`.
- Do not hard-wrap prose in org task files. Keep each paragraph as a single logical line; preserve natural line breaks in blocks, tables, drawers, keywords, and one-list-item-per-line lists.

Unknown `#+` keywords and drawer properties round-trip untouched; third-party metadata should use an `UPPERCASE_NAMESPACE_` prefix.

## Tooling quick reference

Common commands:

```shell
ot init
ot list --format json
ot show <id-or-selected>
ot create "New task" --section Improvements --linked-issue '[[jira:ABC-1]]'
ot status <id> STARTED
ot select <id>        # or: ot select --clear
ot archive <id> --yes
ot publish <id>       # TASKS.local.org -> TASKS.org
ot unpublish <id>     # TASKS.org -> TASKS.local.org
ot doctor --format json
ot backfill            # add :CUSTOM_ID: / :CREATED: to hand-authored tasks missing IDs
ot section design/log/foo.org Summary --format json
ot scan --scope all --max-body-chars 500 --format json
ot record path <id>
ot record create <id>
ot record create <id> --mode retrospective
ot issue list|add|remove|urls <id> [...]
ot blocker list|add|remove <id> [...]
ot ready <id>
ot handoff get|set|clear <id> [...]
ot uuid --count 3
```

Use `--format json` for machine callers. JSON/EDN commands use schema `org-tasks/v1`: `{ok,schema,result,warnings}` on success and `{ok:false,schema,error}` on failure.

ID-accepting commands accept full UUIDs, compact `prefix…suffix` aliases displayed by `ot list` / `ot scan`, and legacy unique prefixes of at least four characters. Ambiguous short IDs fail with `ambiguous-id` and list candidates.

Install for third-party harnesses:

```shell
bbin install io.github.cormacc/dotagents --as ot --latest-sha
```

Local development:

```shell
bbin install ./. --local/root . --as ot
./skills/org-tasks/scripts/ot --help
bb run ot --help
```

## Creating, selecting, and updating tasks

- Use the smallest useful task granularity: each task should describe a concrete outcome that can become `DONE`.
- Prefer `ot create` / `tasks_insert_task` for new top-level tasks so IDs, timestamps, drawers, linked-issue duplicate checks, and section insertion stay deterministic.
- Keep `TASKS.org` high-level. Put detailed checklists, implementation history, and acceptance criteria in linked change-records.
- Add discovered work as new `TODO` tasks rather than burying it in prose.
- Select local active work with `ot select <id>` or clear with `ot select --clear`.
- Local drafts may live in `TASKS.local.org`; publish with `ot publish` when they should become shared.

Bootstrap new projects with `ot init`. If `ot` is unavailable, use `references/bootstrap.md` as the manual fallback.

## Status discipline

- Mark `STARTED` when beginning substantial work; the first transition writes `:STARTED:`.
- Mark `DONE` only when implemented and verified; `CLOSED:` is written on transition.
- Use `WAITING` with `:BLOCKED-BY:` for blocked work; `:BLOCKED-BY:` is also valid on `TODO` to express prerequisites without forcing `WAITING`.
- Use `CANCELLED` for intentionally abandoned work.
- Reopening from `DONE`/`CANCELLED` clears current `CLOSED:` but preserves historical LOGBOOK entries.
- When child plan work advances, parent status should remain meaningful; `ot status` auto-promotes TODO ancestors when a child starts.
- Before closing a top-level task, refresh/prune the linked change-record per `org-plan` § Closure-time refresh and prune.

## Change-records

A change-record is a separate org file linked from a task via `#+IMPORT:`. Two flows produce the same artefact:

1. Proactive: created before work begins, usually with `ot record create <id>` or the pi plan flow.
2. Retrospective: created after work starts/completes with `ot record create <id> --mode retrospective`; `ot` returns the `git log` scope from `:STARTED:`/`CLOSED:` for the prompting layer.

`ot record create` scaffolds required headings, attaches `#+IMPORT:`, and when creating a new record migrates existing child task trees from the parent into the record's `* Plan` section. The parent then keeps only the import link, so each UUID has one canonical writable node. Existing record files are not modified for migration; repair duplicates manually and run `ot doctor`.

Section contract, planning methodology, acceptance criteria, closure-time pruning, and subtask authoring conventions are owned by `org-plan`.

## Resuming and agent memory

Treat org files as durable memory and conversation as ephemeral. Load eagerly only the task index (`TASKS.org` plus `TASKS.local.org`) and the selected task's immediate change-record. Load other imports on demand when they are on the active path or referenced by blockers / linked issues.

Resume checklist:

1. Identify the selected task via `#+SELECTED:`; otherwise use the first active `STARTED` task or ask the user.
2. Read the selected task subtree, properties, body, and LOGBOOK.
3. Open the linked change-record and read in order:
   1. `* Summary` — always the cheap reconstruction surface.
   2. `* Context` — only when present.
   3. `:HANDOFF:` on the parent or any plan-subtask heading.
   4. `:BLOCKED-BY:` and continuations.
   5. `:LINKED_ISSUES:`; tracker-specific skills decide when to refetch upstream state.
   6. Remaining `OPEN` items under `* Open questions`.
   7. Actionable plan items: first `STARTED` plan task or first ready `TODO`.
4. Surface handoff notes and open questions immediately.
5. Defer full `* Implementation`, completed plan-task bodies, and off-path acceptance details until needed.

When a record grows beyond cheap re-ingestion, split or archive completed history rather than omitting `* Summary` or truncating silently.

## Archiving

Only top-level `DONE`/`CANCELLED` tasks are archived. Use `ot archive <id> --yes`. The archive move preserves the subtree, `:CUSTOM_ID:`, content, LOGBOOK, and import link; adds `:ARCHIVED:`; writes to project-root `TASKS.archive.org`; and rewrites linked change-record parent links from `task:` to `archive:` when possible.

Native Emacs `org-archive-subtree` remains compatible because `#+ARCHIVE:` and TODO/logging settings match the protocol, but `ot archive` is the headless/agent path.

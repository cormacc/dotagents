---
name: org-tasks
description: "Org-mode task memory for TASKS.org and the `ot` CLI/TUI -- owns task file format and lifecycle (planning belongs to `org-plan`). Use for adding/editing/resuming/archiving tasks, TASKS*.org, #+IMPORT:, #+SELECTED:, :CUSTOM_ID:, :BLOCKED-BY:, :HANDOFF:, linked issues, `ot`, or the ot task-browser TUI."
---

# Org-mode task management and memory protocol

Use this skill when the user asks to work from, update, resume, diagnose, or review project tasks. The canonical project memory index is `TASKS.org` in the project root. A task may link to a change-record via `#+IMPORT:`; the change-record section contract belongs to `org-plan` (`../org-plan/SKILL.md`).

The durable, harness-independent protocol engine is the Babashka CLI `ot` under `skills/org-tasks/scripts/`. Use `ot` over hand-editing whenever you create, inspect, select, transition, archive, publish/unpublish, scan, diagnose, manage blockers/handoffs/linked issues, or scaffold change-records. A harness may expose optional wrappers around these operations; use one only when it appears in the current tool list, and never make the workflow depend on it. Read `references/ot-cli.md` for CLI details and `scripts/docs/contract.md` for machine-output schemas.

## Locating task memory

`TASKS.org` is anchored at the resolved project root. `ot` uses an explicit `--root` when provided; otherwise it checks the current working directory and then walks parent directories until it finds the nearest `TASKS.org`, falling back to the current directory when none exists. The nearest ancestor can be outside `$HOME`; use a closer `TASKS.org` or explicit `--root` to override. If the resolved root has no task memory, offer to bootstrap with `ot init`.

The pi tasks extension shares this rule by spawning `ot list` from the workspace cwd and consuming the returned `root` / `files` fields. The same root rule applies to `TASKS.local.org`, `TASKS.archive.org`, `TASKS.setup.org`, and `#+IMPORT:` resolution. Tooling applies project-root sandboxing to prevent traversal/symlink escapes.

`ot init` follows the same resolution. From a fresh directory with no ancestor `TASKS.org`, it scaffolds the protocol files in the current directory. From inside an existing project, it resolves to the ancestor and tops up any missing protocol files there rather than creating a nested second project. To start a sub-project's own task memory under an existing tree, pass `--root .` explicitly.

## Protocol summary

The field-level contract lives in `references/protocol.md`; load it when repairing org by hand or answering protocol-specific questions. The short version:

- Files share TODO/logging/link settings through root `TASKS.setup.org`; `TASKS.org` and `TASKS.archive.org` include `TASKS.local.org` first for gitignored keyword overrides, then `TASKS.setup.org`.
- Task states are `TODO`, `STARTED`, `WAITING`, `DONE`, `CANCELLED`. Priorities are `[#A]` through `[#D]`, managed via `ot priority` (set, `--cycle`, `--clear`).
- Every task/subtask needs a UUID v4 `:CUSTOM_ID:`. Use `ot create` or `ot uuid`; never invent IDs.
- `:CREATED:`, `:STARTED:`, `CLOSED:`, and `:LOGBOOK:` are lifecycle artefacts. `ot` and native org-mode are aligned so status writes converge on the same file shape.
- `#+IMPORT:` links change-records/imported task files. Canonical plan imports use `[[plan:file.org]]` via the `#+LINK: plan` template, which is defined locally in `TASKS.org`/`TASKS.archive.org` (repo-root), not in `TASKS.setup.org`. `#+LINK: proj` (dual-defined: `./` from the task file, `../../` from a record via setup) is a generic repo-root path link for referencing specs/source from records.
- `#+SELECTED:` in gitignored `TASKS.local.org` stores the local active task. Empty or absent means no selection.
- `:BLOCKED-BY:` / `:BLOCKED-BY+:`, `:HANDOFF:`, and `:LINKED_ISSUES:` are protocol fields managed by `ot blocker`, `ot ready`, `ot handoff`, and `ot issue`; write task blockers as explicit `task:<UUID>` (bare full UUIDs remain compatible legacy task references).
- `#+SPEC:` is a single optional keyword naming relevant specification docs as bare `[[proj:PATH]]` links: in `TASKS.org` it declares repo-wide discovery roots, in change-records it lists task-relevant specs (opt out with `#+NO_SPEC: true`). The discovery model is owned by `../org-plan/SKILL.md` § Spec discovery (`#+SPEC:`); the `ot doctor` findings (`spec-untouched`, `spec-value-malformed`, `spec-path-dangling`) and `TASKS.org`-only validation are documented in `references/ot-cli.md` § Spec keyword and checks.
- Do not hard-wrap. In every org file this protocol manages (`TASKS*.org` and `#+IMPORT:`-linked change-records), keep each paragraph and each list item as a single logical line (soft-wrap); preserve real line breaks only in headings, drawers, keywords, tables, and src/example blocks. Never reflow to a fixed column such as 80.
- A scripted line-prefix rewrite over a task subtree must exclude `:PROPERTIES:` and `:LOGBOOK:` drawers, whose entries also begin with `- `. A regex such as `(?m)^- ` will silently rewrite logged state transitions along with the list items you meant to touch, and a small edit is no safer than a large one.

Unknown `#+` keywords and drawer properties round-trip untouched; third-party metadata should use an `UPPERCASE_NAMESPACE_` prefix.

## Tooling quick reference

Common commands:

```shell
ot init
ot root
ot list --format json  # result includes resolved root + files.{tasks,local,archive}
ot selected --format json  # selection query: {"selected": null} and exit 0 when nothing is selected
ot show <id-or-selected>  # text output includes the task body; JSON/EDN retain Task.description
ot create "New task" --section Improvements --body 'Description text' --tag mytag --linked-issue '[[jira:ABC-1]]'
ot move <id> --parent <dest-id>   # reparent an existing subtree in place (--section <name> lifts it back to top level)
ot status <id> STARTED   # also works for tasks inside linked plan files
ot priority <id> B       # set/cycle/clear the priority cookie (--cycle forward|back, --clear)
ot select <id>        # or: ot select --clear / ot select --clear-stale
ot remove <id> --yes  # non-top-level subtree only; preview with --dry-run
ot archive <id> --yes
ot unarchive <id> --section Improvements  # restores an archived subtree; does not reopen its status
ot publish <id>       # TASKS.local.org -> TASKS.org
ot unpublish <id>     # TASKS.org -> TASKS.local.org
ot doctor --format json       # health checks + spec warnings
ot backfill            # add :CUSTOM_ID: / :CREATED: to hand-authored tasks missing IDs
ot section design/log/foo.org Summary --format json
ot scan --scope all --max-body-chars 500 --format json
ot record path <id>
ot record create <id>
ot record create <id> --mode retrospective
ot issue list|add|remove|urls <id> [...]
ot blocker list|add|remove <id> [...]
ot blocker prune --dry-run  # remove dangling task blockers only with --yes
ot ready <id>
ot handoff get|set|clear <id> [...]
ot uuid --count 3
```

Use `--format json` for machine callers. JSON/EDN commands use schema `org-tasks/v1`: `{ok,schema,result,warnings}` on success and `{ok:false,schema,error}` on failure.

### Compatibility policy

Preserve backward compatibility when reading or updating existing `TASKS.org` and imported Org files. The skill, `ot` CLI, pi integrations, and JSON/EDN machine envelopes are a closed-loop, repository-internal surface: they may evolve together when the implementation, documentation, and tests change in the same repository change. `org-tasks/v1` is therefore not an external compatibility promise.

ID-accepting commands accept full UUIDs or any unique `:CUSTOM_ID:` prefix of at least four characters (the 8-char prefix shown in `ot list` / `ot scan` is pasteable directly). Mutators such as `status`, `handoff`, `blocker`, `issue`, and `ready` also target tasks inside `#+IMPORT:`-linked plan files and persist to the owning file. Ambiguous values fail with `ambiguous-id` and list candidates.

Install and local development: `scripts/README.md`.

## Creating, selecting, and updating tasks

- Use the smallest useful task granularity: each task should describe a concrete outcome that can become `DONE`.
- Prefer guaranteed `ot create` for new top-level tasks so IDs, timestamps, drawers, linked-issue duplicate checks, and section insertion stay deterministic. An available harness wrapper may delegate to the same command but is optional.
- There is no `ot` mutator for a task or subtask's body prose. Editing that prose in place and verifying with `ot show <id>` plus `ot doctor` is the sanctioned route, not a protocol violation -- do not search for a verb that does not exist, and do not reach for `ot create` to replace a task whose body needs a correction.
- A task body's source citations are a snapshot of when it was written. Before treating a stored body as a baseline, re-verify the symbols, paths, and line numbers it names: a task can outlive the function it cites, and a stale citation sends the next reader after code that no longer exists.
- Regroup existing tasks with `ot move <id> --parent <dest-id>` / `--section <name>` rather than hand-editing or scripting org surgery; it preserves IDs, lifecycle metadata, descendants, and file locality. Moves are in-file only -- use `ot publish`/`ot unpublish` to change locality and `ot unarchive` before moving an archived task.
- Keep `TASKS.org` high-level. Put detailed checklists, implementation history, and acceptance criteria in linked change-records.
- Add discovered work as new `TODO` tasks rather than burying it in prose.
- Select local active work with `ot select <id>` or clear with `ot select --clear`. Repair a stale native-archive pointer only with `ot select --clear-stale`; it leaves valid and absent selections byte-identical.
- Remove only an eligible non-top-level subtree with `ot remove <id> --yes`; start with `--dry-run`, review unchecked criteria and inbound blockers, and pass `--prune-blockers` only when the reported references should be removed too. Protocol top-level roots remain lifecycle plus archive history.
- Local drafts may live in `TASKS.local.org`; publish with `ot publish` when they should become shared.

Bootstrap new projects with `ot init`. If `ot` is unavailable, use `references/bootstrap.md` as the manual fallback.

## Interactive TUI

Bare `ot` on an interactive terminal opens a standalone task-browser TUI: task tree with status/priority colouring, a details pane (beside the tree, or stacked below it on narrow/portrait terminals), and keybindings for all the common mutations. On exit it prints the selected-task envelope to stdout. Full key map and behaviour: `references/ot-cli.md` § Interactive TUI.

The TUI needs no harness integration -- it is the interactive surface for humans and for agents/harnesses without a dedicated extension. Both task UIs bind `D` to removal through `ot remove`: the standalone TUI previews impact and arms a second `D` on the same cursor task, while the pi overlay uses its modal confirmation. The `D` flow prunes reported inbound blockers on confirmed removal; neither UI has a separate blocker-prune key.

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

Section contract, spec planning, planning methodology, acceptance criteria, closure-time pruning, and subtask authoring conventions are owned by `org-plan`.

## Resuming and agent memory

Treat org files as durable memory and conversation as ephemeral. Load eagerly only the task index (`TASKS.org` plus `TASKS.local.org`) and the selected task's immediate change-record. Load other imports on demand when they are on the active path or referenced by blockers / linked issues.

Resume checklist:

1. Run `ot selected --format json` -- not `ot show selected`, which is strict and exits non-zero when nothing is selected. A `null` `selected` with a `null` `selectedId` means no selection: use `ot list --status-filter STARTED --format json` to find active work or ask the user. A `null` `selected` with a *non-null* `selectedId` is a stale pointer to a task that no longer exists (`ot doctor` reports `selected-not-found`); run `ot select --clear-stale` or re-select rather than treating it as an empty selection.
2. Use the returned `task` + `ancestors` for subtree, properties, body, LOGBOOK, handoff, blockers, and linked issues.
3. If `record.path` is present, read `ot section <path> Summary --format json`; read `Context` only when `record.hasContext` is true.
4. Surface handoff notes and open questions immediately; use `record.hasOpenQuestions` as the cheap signal before reading that section.
5. Defer full `* Implementation`, completed plan-task bodies, and off-path acceptance details until needed.

Anti-pattern: do not hand-read `TASKS.org`, `TASKS.local.org`, or `TASKS.setup.org` to answer "what's selected"; use `ot selected --format json`.

When a record grows beyond cheap re-ingestion, split or archive completed history rather than omitting `* Summary` or truncating silently.

### Session closeout

Persist accurate task status and handoff information before any session-learning follow-up. Closing or archiving a top-level task is the checkpoint at which to *check* for retro signals rather than wait for the user to remember: scan for the correction or friction signals defined by [`retro`](../retro/SKILL.md), and treat unscanned child `PROCESS` candidates captured from `herdr-orch` delegation as such a signal -- they die with the session unless a retro routes them. When signals exist, offer one retro after persistence. A bare `DONE`/`CANCELLED` with no signals still never triggers one.

## Archiving

Only top-level `DONE`/`CANCELLED` tasks are archived. Use `ot archive <id> --yes`. The archive move preserves the subtree, `:CUSTOM_ID:`, content, LOGBOOK, and import link; adds `:ARCHIVED:`, plus the source section in `:ARCHIVE_OLPATH:` for roots archived from shared `TASKS.org` (roots archived from a file-level `#+IMPORT:` record get no `:ARCHIVE_OLPATH:` and need an explicit `--section` to restore); writes to project-root `TASKS.archive.org`; and rewrites linked change-record parent links from `task:` to `archive:` when possible. `ot unarchive <id>` resolves only against `TASKS.archive.org`, restores it under `--section` or `:ARCHIVE_OLPATH:`, removes those archive properties, reverses the parent link, and deliberately preserves status/CLOSED/LOGBOOK; use `ot status` to reopen afterward.

Native Emacs `org-archive-subtree` remains compatible because `#+ARCHIVE:` and TODO/logging settings match the protocol, but it cannot update the gitignored `TASKS.local.org` selection: if it archives the selected subtree, `ot selected`/`ot doctor` report the stale pointer and `ot select --clear-stale` repairs it. `ot archive` is the headless/agent path and continues to clear a selection inside its archived subtree directly.

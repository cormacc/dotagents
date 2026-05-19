---
name: org-plan
description: "Drafting, reviewing, and executing implementation plans as change-record files linked from TASKS.org. Use whenever the user asks for a plan, says 'let's plan X', wants a design write-up before coding, asks for a retrospective record after work has shipped, or needs to review, tighten, refresh, or prune an existing change-record or design log, including at task closure. Owns the change-record section contract (* Summary, optional * Context, * Plan, * Implementation, * Validation, optional * Open questions), TASKS.org subtask migration semantics, and the closure-time refresh-and-prune workflow."
---

# Plan

Use this skill when the user asks for a plan. A plan is the leading content of a change-record — a separate org file linked from a task via `#+IMPORT:` that begins life as a plan and becomes the record of what shipped as work proceeds.

This skill owns planning methodology and change-record section conventions. `org-tasks` (`../org-tasks/SKILL.md`) owns file format, task lifecycle, persistence rules, and the `ot` CLI. Prefer `ot record create <task-id>` to scaffold a change-record before filling the sections below; use `ot record create <task-id> --mode retrospective` after work has started or completed.

Detailed skeletons, `#+STATUS:` values, and subtask-migration examples live in `references/change-record-format.md`.

## Planning principles

- Prefer plans that can be executed and verified task-by-task. A plan task is useful only when its acceptance criteria are observable.
- Keep `* Summary` (condensed final/current state), `* Implementation` (detailed tactical ledger), and `* Validation` (evidence of checks run) distinct; they serve different readers.
- Capture durable design decisions in `* Summary` or a promoted `* Context` so later sessions do not reverse-engineer them from `git log`.
- Stop planning once the plan is actionable. Endless planning is a failure mode.

## Voice and density

Plans are written for engineers with project context. Optimise for signal density, not narrative.

- Assume the reader knows the codebase, terminology, and motivation that led to the task.
- Prefer terse declarative bullets over paragraphs. Use paragraphs only when a bullet cannot carry the rationale.
- Default to omitting `* Context`. Promote it only when durable rationale materially exceeds what `* Summary` can carry.
- Plan-task bodies are acceptance criteria plus, at most, one pointer or non-obvious constraint.
- Avoid preamble, marketing tone, future-tense implementation narrative after work lands, and prose that restates task headings.
- At closure, delete spike-style `* Implementation` subsections such as `*** What worked`, `*** What's awkward`, or `*** Implications for task N`; condense durable findings into Summary decisions/gotchas or implementation outcomes.

## Change-record sections

Required on every change-record:

- `* Summary` — condensed memory layer and cheap reconstruction surface. It is placed first so resume tools can ingest it before detailed history. A compact paragraph may be followed by `** Decisions`, `** Shipped`, `** Gotchas`, and `** Follow-ups`.
  - `** Decisions` captures strategic durable design choices that constrain future work, not every tactical coding call.
  - `** Gotchas` captures project-side surprises future implementers should not rediscover. Library/API/protocol facts belong in the relevant skill/reference when one exists.
  - `** Follow-ups` points to real TODO tasks rather than burying work in prose.
- `* Plan` — executable org TODO headings. Top-level plan tasks are `** TODO ...` so they live under `* Plan` while remaining parseable by task tooling. May be empty in a retrospective record.
- `* Implementation` — tactical decisions, tricky details, maintenance context, and outcomes discovered while executing. If the canonical tactical record lives elsewhere (upstream PR, commit body, vendor doc, RFC), use a concise pointer instead of duplicating it.
- `* Validation` — commands run, test counts/outcomes, manual checks, smoke tests, or an explicit note that no automated checks were run.

Optional:

- `* Context` — background, motivation, alternatives, constraints, and trade-offs. Omit when Summary carries the durable rationale. At closure, delete Context unless it still earns its place.
- `* Open questions` — deferred questions using heading-text prefixes, not TODO states:

  ```org
  * Open questions
  ** OPEN Should we batch a follow-up review for related skills?
  ** DECIDED Should ready-task support add a new fan-in property?
  :PROPERTIES:
  :DECIDED: [2026-05-02 Sat 11:51]
  :END:
  Decision: retain `:BLOCKED-BY:`; no `:WAITS_FOR:` field.
  ```

`OPEN` / `DECIDED` are not task nodes and need no `:CUSTOM_ID:` or lifecycle metadata. Resume tooling surfaces remaining `OPEN` items.

## Acceptance criteria

For non-trivial plan tasks, put a short `Acceptance criteria:` bullet list at the top of the task body. Each bullet should be a concrete observable outcome (“X renders”, “Y round-trips byte-identically”) rather than an implementation step. Keep the body to the bullet list unless a non-obvious constraint or pointer is needed.

## Plan task metadata and status

Plan task headings may nest deeper than level 2. Status discipline, including parent propagation, `:STARTED:`, `CLOSED:`, and `:LOGBOOK:`, is owned by `org-tasks` and should be changed through `ot status` or pi tooling.

Hand-authored skeletons may omit `:LOGBOOK:` until the first automated status write. Fresh plan-only tasks get UUIDs via `ot uuid` or `ot create`; never invent UUIDs in prose.

## Subtask migration from TASKS.org

When a TASKS.org task already has subtasks and a new proactive change-record is created, `ot record create` moves those child task trees into the record under `* Plan`, preserving their `:CUSTOM_ID:` values and nesting. The parent task keeps the `#+IMPORT:` link and loses the local child task trees, so the graph has one canonical writable node per UUID.

Existing record files are not modified for migration. If subtasks remain duplicated after a record already exists, move them manually into `* Plan`, remove the parent-local copies, and run `ot doctor`.

New plan-only work units that have no TASKS.org analogue also get fresh UUIDs and `:CREATED:` properties.

## Retrospective change-records

When drafting after work has started or completed:

- Scaffold with `ot record create <id> --mode retrospective` so the tool returns the `git log` scope derived from `:STARTED:` and `CLOSED:`.
- Mark already-completed work `DONE`, current work `STARTED`, and remaining follow-ups `TODO`.
- Draft `* Summary` from the delivered/current state, not from wishful planning language.
- Record key implementation outcomes in `* Implementation` and verification evidence in `* Validation`.
- Do not rewrite history to look planned in advance. Preserve LOGBOOK history emitted by tooling.

## Executing from a change-record

Before starting, ask whether questions should be batched in `* Open questions` for final review or raised immediately. Then resume via `org-tasks` § Resuming and agent memory.

For each plan task:

1. Mark it `STARTED` when beginning now.
2. Implement the smallest change that satisfies the task.
3. Verify the change.
4. Mark it `DONE` and add a short result note only when useful.
5. Add newly discovered follow-up work as TODO tasks under `* Plan` or `TASKS.org`, not as inline prose.
6. Handle questions per the agreed mode: append to `* Open questions` or raise immediately.

## Updating change-records after discoveries

Update the record when implementation reveals durable work or rationale: prerequisites, decisions, validation gaps, refactors, blockers, or deferred questions. Keep additions concise and task-shaped.

For discovered prerequisites that are tasks elsewhere in the graph, express the dependency via `:BLOCKED-BY:` / `:BLOCKED-BY+:`; see `org-tasks` for ready-task semantics.

## Closure-time refresh and prune

Before transitioning a top-level task to `DONE`, walk the record end-to-end with two questions: does each section still earn its place, and does it follow the density rules above?

Refresh:

1. Refresh `* Summary` so the paragraph plus `** Shipped` and `** Gotchas` reflect what actually landed.
2. Refresh `* Validation` so the verification record matches what actually ran.
3. Ensure newly discovered follow-up work exists as TODO tasks rather than buried prose.
4. If Summary is missing, generate it from promoted Context, completed plan tasks, and Implementation notes before closing.

Prune:

5. Delete `* Context` unless it still carries durable rationale Summary does not subsume.
6. Trim verbose prose from completed plan-task bodies. Preserve only compact acceptance/audit value; LOGBOOK preserves timing and Implementation captures outcomes.
7. Remove planning-flavoured Implementation subsections and condense useful content into outcomes, `** Gotchas`, or `** Decisions`.
8. Check that each Gotcha is a project-side surprise. Move library-level facts to the relevant skill/reference, or create follow-up work to do so.

Draft summaries on active records may be terse and are expected to evolve; the final summary is written or refreshed at closure.

---
name: org-plan
description: Use when asked to draft, review, or execute an implementation plan. Produces concrete plans as change-record files, then guides stepwise implementation and verification.
---

# Plan

Use this skill when the user asks for a plan. A plan is the leading
content of a *change-record* — the artefact owned by the `org-tasks`
skill (`../org-tasks/SKILL.md`), linked from a task via `#+IMPORT:`.
A change-record begins life as a plan and becomes a record of what
shipped as work proceeds.

This skill owns planning methodology and section conventions;
`org-tasks` owns file format and persistence rules.

## Planning principles

- Prefer plans that can be executed and verified task-by-task.
- Separate outcomes from implementation details.
- Include validation criteria for non-trivial tasks.
- Capture important design decisions in `* Context` so later sessions
  understand why work was shaped this way.
- Do not plan endlessly. Once the plan is good enough and the user
  wants action, start executing.

## Change-record sections

Required:

- `* Context` — self-contained summary: background, motivation, scope,
  rationale. Use `** Design decisions` when alternatives, constraints,
  or trade-offs matter.
- `* Plan` — executable org TODO headings. Top-level plan tasks are
  `** TODO …` so they live under `* Plan` while remaining parseable
  by task tooling. May be empty in a retrospective change-record.
- `* Implementation` — notes on decisions, tricky details, validation
  outcomes, and maintenance context discovered while executing.
  Filled in as work lands (proactive flow) or drafted from `git log`
  (retrospective flow).

Optional:

- `* Open questions` — deferred questions, using plain heading
  prefixes rather than TODO states:

  ```org
  * Open questions
  ** OPEN Should we batch a follow-up review for related skills?
  ** DECIDED Should ready-task support add a new fan-in property?
  :PROPERTIES:
  :DECIDED: [2026-05-02 Sat 11:51]
  :END:
  Decision: retain `:BLOCKED-BY:`; no `:WAITS_FOR:` field.
  ```

  `OPEN` / `DECIDED` are heading-text markers, not task nodes and not
  `#+TODO:` states. They need no `:ID:` or lifecycle metadata; a
  `DECIDED` heading may optionally record `:DECIDED: [timestamp]`.
  Resume tooling should surface remaining `OPEN` items.

### Minimal skeleton

```org
#+TITLE: Descriptive change-record title
#+DATE: 2026-04-25 Sat
#+PARENT_ID: 01234567-89ab-4def-8123-456789abcdef
#+STATUS: Draft
#+TODO: TODO(t) STARTED(s) WAITING(w) | DONE(d) CANCELLED(c)

* Context
Brief self-contained summary.

** Design decisions
- Decision :: Rationale.

* Plan
** TODO [#A] First executable step :area:
:PROPERTIES:
:ID: 89abcdef-0123-4567-89ab-cdef01234567
:CREATED: [2026-04-25 Sat 09:10]
:END:
:LOGBOOK:
- Created [2026-04-25 Sat 09:10]
:END:
Acceptance criteria:
- Concrete observable outcome 1.
- Concrete observable outcome 2.

Optional further task body prose follows the acceptance criteria.

* Implementation

* Open questions
```

### `#+STATUS:` lifecycle (advisory)

Change-records *may* declare a coarse lifecycle status as a preamble
keyword:

```org
#+STATUS: Draft | Review | Accepted | Active | Complete | Archived
```

| Status   | Meaning                                                  |
|----------|----------------------------------------------------------|
| Draft    | Under development; not ready for execution               |
| Review   | Ready for human / agent review of plan                   |
| Accepted | Plan approved; ready to execute                          |
| Active   | Execution in progress; some plan tasks STARTED or DONE   |
| Complete | Change-record deliverable complete; parent closure is governed by `TASKS.org` |
| Archived | Superseded or cancelled at the change-record level       |

`#+STATUS:` is **advisory only** — no tooling enforces transitions or
blocks workflow on a particular status. It is an index/filter signal
for humans and agents skimming change-records. Task-level status
discipline is owned entirely by `org-tasks`; the keyword is
orthogonal to per-task TODO states.

### Acceptance criteria and outcome conventions

- For non-trivial plan tasks, place a short **Acceptance criteria**
  bullet list at the top of the task body, before any other prose.
  Each bullet should be a concrete observable outcome (“X renders”,
  “Y round-trips byte-identically”) rather than an implementation
  step. The minimal skeleton above shows the shape.
- When a *top-level* task closes, end its change-record's
  `* Implementation` with a brief `** Outcome` (or `** Shipped`)
  paragraph — 1–3 lines summarising what landed. Avoid duplicating
  detail across plan bodies, implementation log, and outcome: plan
  tasks hold acceptance criteria, implementation log holds terse
  progress/file notes, outcome holds the final deliverable summary.

Plan task headings may nest deeper than level 2. Status discipline
(including parent propagation, `:STARTED:`, `CLOSED:`, and
`:LOGBOOK:` lifecycle entries) is owned by `org-tasks`.

The `:LOGBOOK:` drawer shown above is optional in hand-authored
skeletons until the first automated status write. When present, it
lives after `:PROPERTIES:` and before task body text. Prefer changing
status through tooling so the heading status, `:STARTED:`, `CLOSED:`,
parent propagation, and lifecycle log remain synchronized.

### Subtask migration from TASKS.org

When a task in `TASKS.org` already has subtasks and a proactive
change-record is created, those subtask trees are **moved** into the
change-record under `* Plan` with their existing `:ID:` values intact.
They are removed from the parent `TASKS.org` subtree so the loaded task
graph contains one canonical node per UUID.

The parent task may retain a plain-text bullet summary of migrated
subtasks for readability, but those bullets are not tasks and contain
no `:ID:` drawers. The canonical writable task nodes live in the
change-record after migration.

Example before planning:

```org
** TODO [#A] Implement authentication
:PROPERTIES:
:ID: parent-id
:END:
*** TODO Add login endpoint
:PROPERTIES:
:ID: child-id
:END:
```

Example after planning:

```org
** TODO [#A] Implement authentication
:PROPERTIES:
:ID: parent-id
:END:
#+IMPORT: [[file:design/log/authentication.org]]
Migrated subtasks:
- TODO Add login endpoint
```

```org
* Plan
** TODO Add login endpoint
:PROPERTIES:
:ID: child-id
:END:
```

Finer-grained level-3+ subtasks introduced by the plan get fresh
UUIDs and `:CREATED:` properties. New plan-only level-2 work units
that have no TASKS.org analogue (e.g. "Documentation + measurement")
also get fresh UUIDs.


## Retrospective change-records

When drafting after work has started or completed:

- Mark already-completed work `DONE`; mark current work `STARTED`;
  add remaining follow-ups as `TODO`.
- Record key implementation outcomes and verification notes in
  `* Implementation`.
- Do not rewrite history to look planned in advance. Label
  retrospective context clearly when useful.
- Treat `:LOGBOOK:` lifecycle history as evidence, not fiction: preserve
  entries emitted by tooling and avoid hand-editing status history to
  make retrospective work appear proactive.

When the work is *fully* closed and there was no prior plan, the
harness may scaffold an empty change-record and ask the agent to
populate `* Context` and `* Implementation` from `git log` scoped to
the parent task's `:STARTED:` and `CLOSED:` timestamps. The section
structure above still applies. See `../org-tasks/SKILL.md` for the
retrospective trigger and timestamp protocol.

## Executing from a change-record

Before starting: ask whether questions should be batched in
`* Open questions` for final review or raised immediately.

Resume via `org-tasks` § "Starting or resuming work" (which owns
`:HANDOFF:` / `OPEN` question surfacing), then for each plan task:

1. Mark it `STARTED` if beginning now (parent status follows from
   `org-tasks` rules). Prefer tooling-driven transitions so lifecycle
   logging is kept in sync.
2. Implement the smallest change that satisfies the task.
3. Verify the change.
4. Mark it `DONE` and add a short result note if useful.
5. Add newly discovered follow-up work as new `TODO` tasks under
   `* Plan` rather than as inline prose.
6. Handle questions per the agreed mode: append to
   `* Open questions` or raise immediately.

## Updating change-records after discoveries

Update the change-record when implementation reveals a prerequisite
task, an architectural decision, a validation gap, a follow-up
refactor, a blocked dependency, or a question that should be
reviewed later. Keep additions concise and actionable. Prefer one
task per concrete outcome.

When a discovered prerequisite is itself a task elsewhere in the task
graph, capture the dependency via `:BLOCKED-BY:` on the dependent
task (using `:BLOCKED-BY+:` continuation lines for multiple
prerequisites). See `../org-tasks/SKILL.md` for the property's full
shape and ready-task semantics.

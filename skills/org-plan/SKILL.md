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

Required on every change-record:

- `* Summary` — condensed memory layer; the cheap reconstruction
  surface for future agents and humans. Placed first so resume tools
  can ingest it before the detailed ledger. A compact paragraph plus
  optional subsections: `** Decisions`, `** Shipped`, `** Gotchas`,
  `** Validation`, `** Follow-ups`. Required on every change-record
  regardless of size or status (even a one-sentence summary is
  preferable to none). `* Summary` supersedes the legacy `** Outcome`
  / `** Shipped` heading under `* Implementation`; new records carry
  `* Summary` only. See *Closure-time summary refresh* below.
- `* Plan` — executable org TODO headings. Top-level plan tasks are
  `** TODO …` so they live under `* Plan` while remaining parseable
  by task tooling. May be empty in a retrospective change-record.
- `* Implementation` — notes on decisions, tricky details, validation
  outcomes, and maintenance context discovered while executing.
  Filled in as work lands (proactive flow) or drafted from `git log`
  (retrospective flow). Does **not** carry a final `** Outcome` /
  `** Shipped` heading — that role is owned by `* Summary`.

Optional:

- `* Context` — background, motivation, scope, rationale. Use
  `** Design decisions` when alternatives, constraints, or trade-offs
  matter. Promote from "omit" to "include" when durable rationale
  materially exceeds what `* Summary` can carry; omit when `* Summary`
  already says everything (typical for small fixes, mechanical
  renames, doc tweaks, single-cause bugs). The size of the record is
  not the criterion — the question is whether the rationale exceeds
  the synopsis.

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
  `#+TODO:` states. They need no `:CUSTOM_ID:` or lifecycle metadata; a
  `DECIDED` heading may optionally record `:DECIDED: [timestamp]`.
  Resume tooling should surface remaining `OPEN` items.

### Minimal skeleton

```org
#+TITLE: Descriptive change-record title
#+DATE: 2026-04-25 Sat
#+PARENT: [[file:../../TASKS.org::#01234567-89ab-4def-8123-456789abcdef][Descriptive parent task]]
#+STATUS: Draft
#+TODO: TODO(t) STARTED(s) WAITING(w) | DONE(d) CANCELLED(c)

* Summary
One-paragraph condensed summary of what changes and why. Refreshed
as plan tasks close and finalised before the parent task transitions
to `DONE`.

** Decisions
- Decision :: Rationale.

** Shipped
- User-visible / protocol / code outcomes (filled in as work lands).

** Gotchas
- Things future agents should not have to rediscover.

** Validation
- Commands / tests / manual checks run.

** Follow-ups
- Pointers to TASKS.org tasks rather than buried prose.

* Plan

** TODO [#A] First executable step :area:
:PROPERTIES:
:CUSTOM_ID: 89abcdef-0123-4567-89ab-cdef01234567
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

Subsections under `* Summary` are conventional, not mandatory; small
change-records may use a single paragraph plus only the subsections
that carry content. Keep the summary terse: it is the surface a
future agent reads first, not a duplicate of the implementation
ledger.

`* Context` is intentionally absent from the minimal skeleton. Promote
it to a top-level section between `* Summary` and `* Plan` when
durable rationale exceeds what `* Summary` can carry; otherwise leave
it out so the record stays compact. The minimal skeleton ships only
the sections that are required on every record.

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

### Acceptance criteria and summary conventions

- For non-trivial plan tasks, place a short **Acceptance criteria**
  bullet list at the top of the task body, before any other prose.
  Each bullet should be a concrete observable outcome (“X renders”,
  “Y round-trips byte-identically”) rather than an implementation
  step. The minimal skeleton above shows the shape.
- `* Summary` carries the durable condensed surface for the whole
  change-record: a one-paragraph synopsis plus the conventional
  `** Decisions`, `** Shipped`, `** Gotchas`, `** Validation`, and
  `** Follow-ups` subsections. It supersedes the legacy
  `** Outcome` / `** Shipped` heading under `* Implementation`; new
  change-records do not carry that legacy heading.
- Avoid duplicating detail across `* Summary`, `* Context`, plan
  task bodies, and `* Implementation`. Acceptance criteria live on
  plan tasks, terse progress/file notes live in the implementation
  log, durable rationale lives in `* Context`, and `* Summary` holds
  the condensed final state.

### Closure-time summary refresh

Before transitioning a top-level task to `DONE`:

1. Refresh the linked change-record's `* Summary` so the paragraph
   plus `** Shipped`, `** Gotchas`, and `** Validation` reflect
   what actually landed.
2. Ensure any newly-discovered follow-up work exists as `TODO`
   tasks (in `TASKS.org` for cross-cutting work, under `* Plan`
   for plan-local work) rather than buried in prose.
3. If a `* Summary` is missing, generate one from `* Context`,
   completed plan tasks, and `* Implementation` notes before
   closing.

Draft summaries on active records may be terse and are expected to
evolve; the final summary is written or refreshed at closure. The
tasks extension prompts the agent to generate or refresh `* Summary`
when a top-level task transitions to `DONE` and the linked
change-record either lacks the section or has not been touched
since the last status transition. The skill is the durable contract;
the extension prompt is a cheap reinforcement.

### When `* Summary` is required

`* Summary` is required on every change-record, regardless of size
or status. Even a one-sentence summary is preferable to none — it
gives the agent a deterministic ingestion entry point and forces the
author to confirm what shipped ("nothing surprising happened here"
is itself a useful memory signal).

`* Context` is optional and is included only when durable rationale,
alternatives, or scope materially exceed what `* Summary` can carry.
For small fixes, mechanical renames, doc tweaks, and single-cause
bugs, `* Summary` typically suffices and `* Context` is omitted. The
size of the record is not the criterion — the question is whether the
rationale exceeds the synopsis.

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
change-record under `* Plan` with their existing `:CUSTOM_ID:` values intact.
They are removed from the parent `TASKS.org` subtree so the loaded task
graph contains one canonical node per UUID.

The parent task may retain a plain-text bullet summary of migrated
subtasks for readability, but those bullets are not tasks and contain
no `:CUSTOM_ID:` drawers. The canonical writable task nodes live in the
change-record after migration.

Example before planning:

```org
** TODO [#A] Implement authentication
:PROPERTIES:
:CUSTOM_ID: parent-id
:END:
*** TODO Add login endpoint
:PROPERTIES:
:CUSTOM_ID: child-id
:END:
```

Example after planning:

```org
** TODO [#A] Implement authentication
:PROPERTIES:
:CUSTOM_ID: parent-id
:END:
#+IMPORT: [[file:design/log/authentication.org]]
Migrated subtasks:
- TODO Add login endpoint
```

```org
* Plan
** TODO Add login endpoint
:PROPERTIES:
:CUSTOM_ID: child-id
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
- Draft `* Summary` from `git log`, `* Implementation` notes, and
  any pre-existing `** Outcome` / `** Shipped` content absorbed
  (and condensed) from a legacy record.
- Record key implementation outcomes and verification notes in
  `* Implementation`.
- Do not rewrite history to look planned in advance. Label
  retrospective context clearly when useful.
- Treat `:LOGBOOK:` lifecycle history as evidence, not fiction: preserve
  entries emitted by tooling and avoid hand-editing status history to
  make retrospective work appear proactive.

When retrofitting a legacy change-record that still carries an
`** Outcome` or `** Shipped` heading under `* Implementation`,
absorb that text into the new top-level `* Summary` (condensing
where appropriate) and remove the legacy heading. Preserve
`:CUSTOM_ID:`, `#+PARENT:` links, LOGBOOK history, plan task
status, and the rest of the `* Implementation` audit detail — this
is a memory-summary pass, not a history rewrite.

When the work is *fully* closed and there was no prior plan, the
harness may scaffold an empty change-record and ask the agent to
populate `* Summary`, `* Context`, and `* Implementation` from
`git log` scoped to the parent task's `:STARTED:` and `CLOSED:`
timestamps. The section structure above still applies. See
`../org-tasks/SKILL.md` for the retrospective trigger and timestamp
protocol.

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

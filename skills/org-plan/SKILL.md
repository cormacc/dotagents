---
name: org-plan
description: "Drafting, reviewing, and executing implementation plans as change-record files linked from TASKS.org. Use whenever the user asks for a plan, says 'let's plan X', wants a design write-up before coding, asks for a retrospective record after work has shipped, or needs to review, tighten, refresh, or prune an existing change-record or design log, including at task closure. Owns the change-record section contract (* Summary, optional * Context, * Plan, * Implementation, * Validation, optional * Open questions), TASKS.org subtask migration, and the closure-time refresh-and-prune workflow."
---

# Plan

Use this skill when the user asks for a plan. A plan is the leading content of a
*change-record* — a separate org file linked from a task via `#+IMPORT:` that
begins life as a plan and becomes a record of what shipped as work proceeds.
This skill owns planning methodology and change-record section conventions;
`org-tasks` (`../org-tasks/SKILL.md`) owns file format, task lifecycle, and
persistence rules.

## Planning principles

- Prefer plans that can be executed and verified task-by-task — a plan task is
  only useful when its acceptance criteria are observable.
- Keep `* Summary` (condensed final state, prescriptive — what a resuming
  agent needs to act) distinct from `* Implementation` (the detailed ledger —
  tactical decisions, tricky details) and `* Validation` (evidentiary record
  of commands / tests / manual checks run); the three serve different reading
  audiences.
- Capture durable design decisions in `* Summary` (or promoted `* Context` when
  the rationale exceeds the synopsis) so later sessions don't have to
  reverse-engineer them from `git log`.
- Stop planning once the plan is actionable. Endless planning is itself a
  failure mode.

## Voice and density

Plans are written for engineers with project context. Optimise for signal
density, not narrative.

- Assume the reader knows the codebase, terminology, and motivation that led to
  the task. Don't restate the task title or recap `git log`.
- Prefer terse declarative bullets over paragraphs. Use paragraphs only when a
  single bullet won't carry the rationale.
- Default to omitting `* Context`. Promote only when durable rationale
  (alternatives weighed, constraints, trade-offs) materially exceeds what
  `* Summary` can carry.
- Plan-task bodies are acceptance criteria plus, at most, one pointer or
  non-obvious constraint. Skip prose that paraphrases the heading.

Avoid:

- Preamble ("This plan describes…", "The goal of this change is…").
- Future-tense narrative in `* Implementation` once work has landed — record
  outcomes, not intentions.
- Marketing tone, hedging, or restating decisions already captured under
  `** Decisions`.
- "First we will…, then we will…" step prose where the bulleted task list
  already says the same thing.
- Spike-style `* Implementation` subsections (`*** What worked`,
  `*** What's awkward`, `*** Implications for task N`). These read like
  planning artefacts mid-execution. At closure: condense to outcomes,
  promote durable findings into `** Decisions` or `** Gotchas`, delete the
  rest.
- Restating `* Summary` content in different words elsewhere. If a fact
  appears in both `* Summary` and `* Implementation`, it belongs in one
  place — usually `* Summary`.

## Change-record sections

Required on every change-record:

- `* Summary` — condensed memory layer; the cheap reconstruction surface for
  future agents and humans. Placed first so resume tools can ingest it before
  the detailed ledger. A compact paragraph plus optional subsections:
  `** Decisions`, `** Shipped`, `** Gotchas`, `** Follow-ups`. All Summary
  subsections are prescriptive and forward-facing — they shape what the next
  agent does. Evidentiary / backward-facing material (test commands run,
  validation outcomes, tactical implementation choices) belongs in
  `* Implementation`. `** Decisions` here means *strategic* durable design
  choices that constrain future work, not every tactical call made while
  coding. `** Gotchas` means *project-side* surprises hit during execution —
  things a future implementer in this codebase shouldn't have to rediscover.
  Library-level facts (protocol semantics, API quirks, documented limits,
  default values) belong in the relevant skill or reference when one exists;
  duplicating them here loses to drift. If no durable reference exists yet,
  keep a compact pointer here and create follow-up work to move it. Follow
  *Voice and density* above — terse, engineer-facing, no preamble. Required
  on every change-record regardless of size or status (even a one-sentence
  summary is preferable to none). See *Closure-time refresh and prune* below.
- `* Plan` — executable org TODO headings. Top-level plan tasks are `** TODO …`
  so they live under `* Plan` while remaining parseable by task tooling. May be
  empty in a retrospective change-record. Investigation- or discovery-shaped
  work legitimately collapses to a single plan task once the investigation
  resolves — a single-task plan is a valid shape, not a sign of
  under-planning.
- `* Implementation` — the detailed ledger: tactical decisions, tricky
  details, and maintenance context discovered while executing. Filled in as
  work lands (proactive flow) or drafted from `git log` (retrospective flow).
  When the canonical tactical record lives in a linked external artifact
  (upstream PR, commit body, vendor doc, RFC), `* Implementation` may be a
  one-line pointer to that artifact rather than a duplicated ledger; capture
  *only* the project-side tactical notes that did not belong downstream.
- `* Validation` — evidentiary record of how the change was verified:
  commands run, test counts and outcomes, manual checks, smoke tests. May be
  empty or a single "no automated checks; manual smoke only" line on trivial
  records, but the heading should be present so resume tooling and reviewers
  always know where to look.

Optional:

- `* Context` — background, motivation, scope, rationale. Use
  `** Design decisions` when alternatives, constraints, or trade-offs matter.
  Promote from "omit" to "include" when durable rationale materially exceeds
  what `* Summary` can carry; omit when `* Summary` already says everything
  (typical for small fixes, mechanical renames, doc tweaks, single-cause bugs,
  and most features once their decisions are baked into Summary). The size of
  the record is not the criterion — the question is whether the rationale
  exceeds the synopsis. *At closure*: re-evaluate, and delete `* Context`
  unless it still carries rationale Summary doesn't subsume (see *Closure-time
  refresh and prune* § 4).

- `* Open questions` — deferred questions, using plain heading prefixes rather
  than TODO states:

  ```org
  * Open questions
  ** OPEN Should we batch a follow-up review for related skills?
  ** DECIDED Should ready-task support add a new fan-in property?
  :PROPERTIES:
  :DECIDED: [2026-05-02 Sat 11:51]
  :END:
  Decision: retain `:BLOCKED-BY:`; no `:WAITS_FOR:` field.
  ```

  `OPEN` / `DECIDED` are heading-text markers, not task nodes — no `:CUSTOM_ID:`
  or lifecycle metadata required. Resume tooling surfaces remaining `OPEN`
  items.

### Minimal skeleton

```org
#+TITLE: Descriptive change-record title
#+DATE: 2026-04-25 Sat
#+PARENT: [[file:../../TASKS.org::#01234567-89ab-4def-8123-456789abcdef][Descriptive parent task]]
#+SETUPFILE: ../../TASKS.setup.org
#+STATUS: Draft

* Summary
One-paragraph condensed summary of what changes and why. Refreshed
as plan tasks close and finalised before the parent task transitions
to `DONE`.

** Decisions
- Strategic decision :: Rationale (only durable design choices that
  constrain future work).

** Shipped
- User-visible / protocol / code outcomes (filled in as work lands).

** Gotchas
- Project-side surprises hit during execution — not library-level facts
  (those belong in the relevant skill).

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

Optional: one non-obvious constraint or pointer, if needed.

* Implementation
- Tactical decisions and tricky details discovered while executing.

* Validation
- Commands / tests / manual checks run, with outcomes.

* Open questions
```

The setupfile reference assumes each repository provides a root
`TASKS.setup.org` carrying the shared org-tasks preamble:

```org
#+TODO: TODO(t) STARTED(s!) WAITING(w@/!) | DONE(d!) CANCELLED(c!)
#+STARTUP: logdone logdrawer
#+LINK: plan file:design/log/%s
```

New change-records reference it via `#+SETUPFILE: ../../TASKS.setup.org`
instead of repeating the preamble inline. See `org-tasks` for the full file
protocol.

### `#+STATUS:` lifecycle (advisory)

Change-records *may* declare a coarse lifecycle status as a preamble keyword:

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

`#+STATUS:` is **advisory only** — no tooling enforces transitions or blocks
workflow on a particular status. It is an index/filter signal for humans and
agents skimming change-records. Task-level status discipline is owned entirely
by `org-tasks`; the keyword is orthogonal to per-task TODO states.

### Acceptance criteria

For non-trivial plan tasks, place a short **Acceptance criteria** bullet list at
the top of the task body, before any other prose. Each bullet should be a
concrete observable outcome (“X renders”, “Y round-trips byte-identically”)
rather than an implementation step. Keep the body to the bullet list unless a
non-obvious constraint or pointer is needed (see *Voice and density*).

### Closure-time refresh and prune

Before transitioning a top-level task to `DONE`, walk the record end-to-end
with two questions in mind: does each section still earn its place, and does
it still follow *Voice and density* above? The planning-time rules read once
at draft time go stale by closure — this is the natural enforcement point.

Refresh:

1. Refresh the linked change-record's `* Summary` so the paragraph plus
   `** Shipped` and `** Gotchas` reflect what actually landed, and refresh
   `* Validation` so the verification record matches what was actually run.
2. Ensure any newly-discovered follow-up work exists as `TODO` tasks (in
   `TASKS.org` for cross-cutting work, under `* Plan` for plan-local work)
   rather than buried in prose.
3. If a `* Summary` is missing, generate one from promoted `* Context` (when
   present), completed plan tasks, and `* Implementation` notes before
   closing.

Prune:

4. *`* Context`*: delete it unless it still carries durable rationale that
   `* Summary` doesn't subsume. Decisions baked into `** Decisions` make the
   rest of Context redundant by definition.
5. *Plan-task bodies for completed tasks*: trim verbose prose from completed
   plan-task bodies. Preserve only a compact acceptance summary when it differs
   from the heading or carries audit value; otherwise reduce the body to a
   one-line goal. The `:LOGBOOK:` preserves timing; `* Implementation`
   captures actual outcomes; the planning intent is no longer load-bearing.
6. *`* Implementation` subsections*: remove planning-flavoured headings like
   `*** What worked`, `*** What's awkward`, or `*** Implications for task N`.
   Condense durable content into outcomes, `** Gotchas`, or `** Decisions`;
   delete the rest.
7. *`** Gotchas`*: each entry must describe a *project-side surprise* — a
   thing the implementer hit that future implementers shouldn't have to
   rediscover. Library-level facts (protocol semantics, API quirks,
   documented limits, default values) belong in the relevant skill or
   reference when one exists. If no durable reference exists yet, keep a
   compact pointer here and create follow-up work to move it.

Draft summaries on active records may be terse and are expected to evolve; the
final summary is written or refreshed at closure. The tasks extension prompts
the agent to generate or refresh `* Summary` when a top-level task transitions
to `DONE` and the linked change-record either lacks the section or has not been
touched since the parent task's `:STARTED:` timestamp (with a small same-minute
grace window). The skill is the durable contract; the extension prompt is a
cheap reinforcement. Even when tooling prompts only for Summary refresh, the
agent should self-trigger this full prune checklist.

### Plan task metadata and status

Plan task headings may nest deeper than level 2. Status discipline (including
parent propagation, `:STARTED:`, `CLOSED:`, and `:LOGBOOK:` lifecycle entries)
is owned by `org-tasks`.

The `:LOGBOOK:` drawer shown above is optional in hand-authored skeletons until
the first automated status write. When present, it lives after `:PROPERTIES:`
and before task body text. Prefer changing status through tooling so the heading
status, `:STARTED:`, `CLOSED:`, parent propagation, and lifecycle log remain
synchronized.

### Subtask migration from TASKS.org

When a task in `TASKS.org` already has subtasks and a proactive change-record is
created, those subtask trees are **moved** into the change-record under `* Plan`
with their existing `:CUSTOM_ID:` values intact. They are removed from the
parent `TASKS.org` subtree so the loaded task graph contains one canonical node
per UUID.

The parent task may retain a plain-text bullet summary of migrated subtasks for
readability, but those bullets are not tasks and contain no `:CUSTOM_ID:`
drawers. The canonical writable task nodes live in the change-record after
migration.

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
#+IMPORT: [[plan:authentication.org]]
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

Finer-grained level-3+ subtasks introduced by the plan get fresh UUIDs and
`:CREATED:` properties. New plan-only level-2 work units that have no TASKS.org
analogue (e.g. "Documentation + measurement") also get fresh UUIDs.


## Retrospective change-records

When drafting after work has started or completed:

- Mark already-completed work `DONE`; mark current work `STARTED`; add remaining
  follow-ups as `TODO`.
- Draft `* Summary` from `git log`.
- Record key implementation outcomes in `* Implementation` and verification
  evidence in `* Validation`.
- Do not rewrite history to look planned in advance. Label retrospective context
  clearly when useful.
- Treat `:LOGBOOK:` lifecycle history as evidence, not fiction: preserve entries
  emitted by tooling and avoid hand-editing status history to make retrospective
  work appear proactive.

Tooling may scaffold an empty record and prompt the agent for a retrospective
fill; the section structure above still applies. See `../org-tasks/SKILL.md` for
the retrospective trigger and timestamp protocol.

## Executing from a change-record

Before starting: ask whether questions should be batched in `* Open questions`
for final review or raised immediately.

Resume via `org-tasks` § *Resuming and agent memory* (which owns `:HANDOFF:` /
`OPEN` question surfacing), then for each plan task:

1. Mark it `STARTED` if beginning now (parent status follows from `org-tasks`
   rules). Prefer tooling-driven transitions so lifecycle logging is kept in
   sync.
2. Implement the smallest change that satisfies the task.
3. Verify the change.
4. Mark it `DONE` and add a short result note if useful.
5. Add newly discovered follow-up work as new `TODO` tasks under `* Plan` rather
   than as inline prose.
6. Handle questions per the agreed mode: append to `* Open questions` or raise
   immediately.

## Updating change-records after discoveries

Update the change-record when implementation reveals durable work or rationale
(prerequisites, decisions, validation gaps, refactors, blockers, deferred
questions). Keep additions concise — one task per concrete outcome.

For discovered prerequisites that are themselves tasks elsewhere in the graph,
express the dependency via `:BLOCKED-BY:` (and `:BLOCKED-BY+:` for multiples).
See `../org-tasks/SKILL.md` for the property's full shape and ready-task
semantics.

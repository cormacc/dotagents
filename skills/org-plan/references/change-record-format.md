# Change-record format reference

A change-record is an org file linked from a task by `#+IMPORT:`. It starts as a plan and becomes the durable record of what shipped. Prefer `ot record create <task-id>` to scaffold one; use `--mode retrospective` when creating it after work started or finished.

## Section order

`* Intent` → optional `* User story` → optional `* Behavior` → `* Summary` → optional `* Context` → `* Plan` → `* Implementation` → `* Validation` → optional `* Open questions`.

Required: `* Intent`, `* Summary`, `* Plan`, `* Implementation`, `* Validation`. Optional sections marked above.

## Minimal skeleton

```org
#+TITLE: Descriptive change-record title
#+DATE: 2026-04-25 Sat
#+PARENT: [[task:01234567-89ab-4def-8123-456789abcdef][Descriptive parent task]]
#+SETUPFILE: ../../TASKS.setup.org
#+STATUS: Draft

* Intent
One to three sentences. What we are building and why. Stable across the work.

* Summary
*Level:* MVP · *Tests:* smoke · *Docs:* inline

One compact paragraph describing current/final state.

** Scope
*** In scope
- Concrete item 1.
- Concrete item 2.
*** Out of scope
- Deferred item.

** Decisions
- Chose Approach X :: Rationale that constrains future work.
  Rejected:
  - Approach Y: brief reason it lost.

** Shipped
- User-visible / protocol / code outcomes.

** Gotchas
- Project-side surprises future implementers should not rediscover.

** Risks
- Accepted risk (from premortem): mitigation or rationale for accepting.

** Follow-ups
- Pointers to TASKS.org or plan TODOs.

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
- Concrete observable outcome.
- Must not: side effect we want to prevent.

See `src/services/AuthService.ts:15-40` for the existing pattern.

* Implementation
- Tactical outcomes, tricky details, and maintenance context discovered while executing.

* Validation
- Commands / tests / manual checks run, with outcomes.

* Open questions
** OPEN Should we batch a follow-up review for related skills?
** DECIDED Should ready-task support add a new fan-in property?
:PROPERTIES:
:DECIDED: [2026-05-02 Sat 11:51]
:END:
Decision: retain `:BLOCKED-BY:`; no `:WAITS_FOR:` field.
```

## Optional sections — when to include

### `* User story`

```org
* User story
As a [role], I want [capability], so that [outcome].
```

Include when there is a real end-user or operator perspective worth preserving. The "so that" clause is the most leaked motivation in any artifact — once captured here, it survives the telephone game through plan → plan tasks → commits.

Skip for refactors, infra, dev-tooling, observability work.

### `* Behavior`

```org
* Behavior
** Happy path
1. User does X.
2. System responds with Y.
3. Z is persisted.

** Edge cases
- Empty input: return a typed error rather than crashing.
- Concurrent request: last writer wins; no merge attempted.
- Network failure mid-request: client retries with backoff; server idempotent.
```

Include for feature work where the user-facing flow is load-bearing. Drafting-time aid — at closure, the happy path is in Implementation and edge cases are anti-criteria or `** Gotchas`; prune unless the walkthrough still carries unique value.

### `* Context`

Background, motivation, alternatives, constraints, trade-offs. **Default to omitting.** Promote only when durable rationale materially exceeds what `* Summary` can carry.

## `#+STATUS:` lifecycle

`#+STATUS:` is advisory only; task-level state remains owned by org-tasks.

| Status | Meaning |
|--------|---------|
| Draft | Under development; not ready for execution |
| Review | Ready for human / agent review |
| Accepted | Approved; ready to execute |
| Active | Execution in progress |
| Complete | Record deliverable complete; parent closure still governed by TASKS.org |
| Archived | Superseded or cancelled at record level |

## Subtask migration

When a TASKS.org parent already has subtasks and a new proactive record is created, `ot record create` moves those subtask trees into the record under `* Plan`, preserving their `:CUSTOM_ID:` values and nesting. The parent keeps `#+IMPORT:` and loses the local subtask trees, so the graph has one canonical writable node per UUID.

Before:

```org
** TODO Implement authentication
:PROPERTIES:
:CUSTOM_ID: parent-id
:END:
*** TODO Add login endpoint
:PROPERTIES:
:CUSTOM_ID: child-id
:END:
```

After:

```org
** TODO Implement authentication
:PROPERTIES:
:CUSTOM_ID: parent-id
:END:
#+IMPORT: [[plan:authentication.org]]
```

```org
* Plan
** TODO Add login endpoint
:PROPERTIES:
:CUSTOM_ID: child-id
:END:
```

Existing record files are not modified for migration; if a record already exists, move any remaining duplicated subtasks manually and run `ot doctor`.

Fresh plan-only tasks get new UUIDs via `ot uuid` or `ot create`; never invent UUIDs in prose.

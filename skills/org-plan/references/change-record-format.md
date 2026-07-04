# Change-record format reference

A change-record is an org file linked from a task by `#+IMPORT:`. It starts as a plan and becomes the durable record of what shipped. Prefer `ot record create <task-id>` to scaffold one; use `--mode retrospective` when creating it after work started or finished.

## Section order

`* Intent` → `* Summary` → optional `* User story` → optional `* Behavior` → optional `* Context` → `* Plan` → `* Implementation` → optional `* Validation` → optional `* Open questions`.

Required: `* Intent`, `* Summary`, `* Plan`, `* Implementation`. Optional sections marked above. `* Summary` carries the durable record layer (including `** Acceptance`); `* Plan` is the transient plan layer (see org-plan *Two layers in one record*).

## Minimal skeleton

```org
#+TITLE: Descriptive change-record title
#+DATE: 2026-04-25 Sat
#+PARENT: [[task:01234567-89ab-4def-8123-456789abcdef][Descriptive parent task]]
#+SETUPFILE: ../../TASKS.setup.org
#+STATUS: Draft
#+SPEC: [[proj:design/specs/example-domain.org]]

* Intent
One to three sentences. What we are building and why. Stable across the work.

* Summary
*Level:* MVP · *Tests:* smoke · *Docs:* inline

One compact paragraph describing current/final state.

** Scope
*** In scope
- Concrete item 1.
- Relevant contract: `design/specs/example-domain.org` should be reviewed for this change.
*** Out of scope
- Deferred item.

** Acceptance
Consolidated ideal-state checklist (ISC) — the user-confirmed definition of done. Durable; verified item-by-item at closure (whether or not a `* Validation` section exists).
*** Core functionality
- [ ] Atomic, one-second yes/no criterion.
*** Edge cases
- [ ] Edge criterion.
*** Anti-criteria
- [ ] Must not: side effect we want to prevent.

** Decisions
- Chose Approach X :: Rationale that constrains future work.
  Rejected:
  - Approach Y: brief reason it lost.

** Shipped
- MODIFIED :: `design/specs/example-domain.org` — concise post-hoc outcome.

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

* Open questions
** OPEN Should we batch a follow-up review for related skills?
** DECIDED Should ready-task support add a new fan-in property?
:PROPERTIES:
:DECIDED: [2026-05-02 Sat 11:51]
:END:
Decision: retain `:BLOCKED-BY:`; no `:WAITS_FOR:` field.
```

## Optional sections — when to include

### `* Validation`

```org
* Validation
- Non-obvious verification evidence: manual/exploratory checks, smoke tests, a specific reproduction, performance numbers, or an explicit note that no automated checks were run (and why).
```

Include only when there is verification evidence worth preserving beyond the routine. Omit when the only thing to record is that the standard automated suites pass — green unit/integration tests are an implicit pre-merge requirement, not record-worthy. `** Acceptance` is verified at closure whether or not this section exists.

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

## Spec keyword

One keyword, `#+SPEC:`, used in two contexts (disambiguated by the file it appears in), always carrying a bare `[[proj:PATH]]` org link (repo-root relative, navigable in Emacs from both TASKS.org and records; the labelled `[[proj:PATH][label]]` form, bare non-link paths, and absolute/`..`-escaping paths are malformed):

- In `TASKS.org` — *discovery input*: zero or more repeatable declarations of where a project's living specs live (a spec file or a folder, recursive). Project-wide, not per-record. See org-plan SKILL.md § Spec discovery (`#+SPEC:`) for the default root, implicit specs, and rooted/transitive discovery rules.
- In a change-record — *planning-time relevance declaration*: which individual specs from the discovered set are relevant to this particular change; `ot doctor` may nudge at closure if a listed spec is unchanged in git.

Use repeated `#+SPEC:` declarations when work is expected to change durable behaviour, public APIs, protocols, domain models, or agent/operator workflow:

```org
#+SPEC: [[proj:design/specs/data-model.org]]
#+SPEC: [[proj:skills/org-plan/SKILL.md]]
```

Opt out with `#+NO_SPEC: true`. See org-plan SKILL.md § Spec planning for when to declare vs opt out, and org-tasks `ot-cli.md` § Spec keyword and checks for the canonical `ot doctor` findings (`spec-untouched`, `spec-value-malformed`, `spec-path-dangling`).

## `#+STATUS:` lifecycle

`#+STATUS:` is advisory only; task-level state remains owned by org-tasks. Advance it manually as the record matures — `Draft` while planning, `Review` when handing off for sign-off, `Accepted` once approved, `Active` during execution, `Complete` at closure. Nothing enforces these transitions; leaving it at `Draft` is acceptable for solo work.

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

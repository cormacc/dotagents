# Change-record format reference

A change-record is an org file linked from a task by `#+IMPORT:`. It starts as a plan and becomes the durable record of what shipped. Prefer `ot record create <task-id>` to scaffold one; use `--mode retrospective` when creating it after work started or finished.

## Minimal skeleton

```org
#+TITLE: Descriptive change-record title
#+DATE: 2026-04-25 Sat
#+PARENT: [[task:01234567-89ab-4def-8123-456789abcdef][Descriptive parent task]]
#+SETUPFILE: ../../TASKS.setup.org
#+STATUS: Draft

* Summary
One compact paragraph describing current/final state.

** Decisions
- Strategic decision :: Rationale that constrains future work.

** Shipped
- User-visible / protocol / code outcomes.

** Gotchas
- Project-side surprises future implementers should not rediscover.

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

Required sections: `* Summary`, `* Plan`, `* Implementation`, `* Validation`. Optional sections: `* Context`, `* Open questions`.

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

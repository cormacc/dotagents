---
name: org-plan
description: "Draft, review, and execute implementation plans as TASKS.org-linked change-records — owns change-record sections, subtask migration, and closure-time pruning. Use for 'let's plan', design write-ups, retrospective records, or pruning a change-record at closure."
---

# Plan

Use this skill when the user asks for a plan. A plan is the leading content of a change-record — a separate org file linked from a task via `#+IMPORT:` that begins life as a plan and becomes the record of what shipped as work proceeds.

This skill owns planning methodology and change-record section conventions. `org-tasks` (`../org-tasks/SKILL.md`) owns file format, task lifecycle, persistence rules, and the `ot` CLI. Prefer `ot record create <task-id>` to scaffold a change-record before filling the sections below; use `ot record create <task-id> --mode retrospective` after work has started or completed. If the generated scaffold predates the current section contract, add/reorder sections before filling them.

Detailed skeletons, `#+STATUS:` values, `#+SPEC_IMPACT:` / `#+NO_SPEC_IMPACT:` examples, and subtask-migration examples live in `references/change-record-format.md`.

## Two layers in one record

A change-record fuses two layers with different lifecycles in one file:

- **Record layer** (`* Intent`, `* Summary`, `* Implementation`, `* Validation`) — durable memory. Accretes and survives past closure. Owned by this skill.
- **Plan layer** (`* Plan` TODO tasks) — execution scaffolding. Churns through status/`:LOGBOOK:` writes and collapses at closure. Lifecycle owned by `org-tasks`.

Keeping both in one file preserves a single `#+IMPORT:` resume surface. The cost is that status churn rewrites the file and plan-task bodies are transient — so the durable definition of done lives in the record layer (`** Acceptance`), never only in task bodies. At closure the plan layer collapses; see *Closure-time refresh and prune*.

## Canonical rules

The *splitting test*, *anti-criteria*, and *body discipline* blocks below are the canonical contract: downstream agents (for example the `planner`) reference them by name rather than restating them, so the rules cannot drift between files. The *effort line* and *intent reverse-engineering* blocks are also canonical here, but interactive agents may restate them as conversational prompts as long as this skill remains the source of truth.

## Planning principles

- Prefer plans that can be executed and verified task-by-task. A plan task is useful only when its acceptance criteria are observable.
- Keep `* Summary` (condensed final/current state), `* Implementation` (detailed tactical ledger), and `* Validation` (evidence of checks run) distinct; they serve different readers.
- Capture durable design decisions in `* Summary` or a promoted `* Context` so later sessions do not reverse-engineer them from `git log`.
- Keep the change-record `.org` file canonical. If durable supporting resources are too verbose or awkward for the record body (research reports, screenshots, transcripts, generated audits), put them in an optional same-stem folder beside the record (for `design/log/YYYY-slug.org`, use `design/log/YYYY-slug/`) and link/summarise them from the record.
- For non-trivial work with durable behavioural/API/protocol/domain impact, identify the living contract docs up front and record them with `#+SPEC_IMPACT:` before implementation (see *Spec-impact planning*).
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

### Required

`* Intent` — 1–3 sentence durable north-star. *What* we are building and *why*. Stable across the work; this is the constitutional clause that `* Summary` builds on and the premortem stress-tests. Drafted from intent reverse-engineering (see *Drafting practices*).

`* Summary` — condensed evolving memory layer. The cheap reconstruction surface resume tools and humans land on after Intent. Opens with the effort line (see *Drafting practices*). Subsections populated as content accrues:

- `** Scope` (required) — `*** In scope` and `*** Out of scope` lists. Both lists. Out-of-scope items prevent feature creep and feed the premortem ("are we sure we don't need X?").
- `** Acceptance` — the consolidated ISC (user-confirmed definition of done), grouped into `*** Core functionality`, `*** Edge cases`, `*** Anti-criteria`. See *Acceptance criteria*.
- `** Decisions` — strategic durable design choices that constrain future work, not every tactical coding call. Captures the chosen approach plus rejected alternatives bulleted underneath.
- `** Shipped` — user-visible / protocol / code outcomes, populated as work lands. When a planned or discovered contract doc changes, prefix bullets with `ADDED`, `MODIFIED`, or `REMOVED` and name the contract doc; this is the post-hoc outcome, not the planning-time `#+SPEC_IMPACT:` declaration.
- `** Gotchas` — project-side surprises future implementers should not rediscover. Library/API/protocol facts belong in the relevant skill/reference when one exists.
- `** Risks` — drafted from premortem; durable risks considered and accepted. Distinct from `** Gotchas` (post-hoc surprise) and `* Open questions` (deferred-not-decided).
- `** Follow-ups` — pointers to real TODO tasks rather than burying work in prose.

`* Plan` — executable org TODO headings. Top-level plan tasks are `** TODO ...` so they live under `* Plan` while remaining parseable by task tooling. May be empty in a retrospective record.

`* Implementation` — tactical decisions, tricky details, maintenance context, and outcomes discovered while executing. If the canonical tactical record lives elsewhere (upstream PR, commit body, vendor doc, RFC), use a concise pointer instead of duplicating it.

`* Validation` — commands run, test counts/outcomes, manual checks, smoke tests, or an explicit note that no automated checks were run.

### Optional

`* User story` — *As [who], I want [what], so that [why].* Use when there is a real end-user or operator perspective worth preserving — the "so that" is often the most leaked motivation in any artifact. Skip for refactors, infra, dev-tooling, and observability work; forcing a user story onto those produces filler. Drafting-time aid: closure-prune unless the "so that" still earns its place.

`* Behavior` — feature-work walkthrough. Two subsections:

- `** Happy path` — numbered list of steps.
- `** Edge cases` — bullets of `[case]: [expected behavior]`.

Drafting-time aid: the happy path becomes the implementation; edge cases become anti-criteria or `** Gotchas`. Closure-prune unless the walkthrough preserves something Implementation/Summary do not subsume.

`* Context` — background, motivation, alternatives, constraints, and trade-offs. **Default to omitting.** Promote only when durable rationale materially exceeds what `* Summary` can carry. At closure, delete Context unless it still earns its place.

`* Open questions` — deferred questions using heading-text prefixes, not TODO states:

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

### Section order

`* Intent` → `* Summary` → optional `* User story` → optional `* Behavior` → optional `* Context` → `* Plan` → `* Implementation` → `* Validation` → optional `* Open questions`.

Optional `* User story` / `* Behavior` follow `* Summary` so the Intent→Summary reconstruction surface stays adjacent for resume tools.

## Drafting practices

These are process notes, not new sections. They feed the section content above.

### Intent reverse-engineering

Before writing `* Intent`, internally answer:

1. *Explicit asks* — what the user concretely said.
2. *Implicit needs* — what they want but did not say ("add login" implies sessions, logout, errors).
3. *Out of scope* — what they explicitly do not want.
4. *Obvious not-wanted* — a quick fix does not want a refactor.
5. *Speed* — "quick" / "just" → minutes; "properly" / "thoroughly" → take the time needed.

Distil these into 1–3 Intent sentences. Surface unresolved items as `* Open questions` rather than papering over them. Items 2 and 3 feed `** Scope`. Item 5 feeds the effort line.

### Effort line

Single line at the head of `* Summary`:

```org
*Level:* prototype | MVP | production | critical · *Tests:* none / smoke / thorough / comprehensive · *Docs:* none / inline / README / full
```

Pick one value per dimension. Calibrates ISC tightness, plan-task acceptance criteria detail, and the closure-prune bar.

### Spec-impact planning

For MVP / production / critical work that can change durable behaviour, public APIs, protocols, domain models, or agent/operator workflow, identify impacted living contracts before drafting the executable plan.

1. Prefer a `scout` when the question is "which in-repo docs/specs/schemas does this codebase change touch?" Use a `researcher` when the trigger is external knowledge or a new external capability.
2. Record expected contract impact as repeated repo-relative keywords near the top of the record:

   ```org
   #+SPEC_IMPACT: skills/org-plan/SKILL.md
   #+SPEC_IMPACT: design/specs/data-model.org
   ```

3. Mirror the impact in human-readable `*** In scope` bullets so implementers work the checklist.
4. Propose a new spec only when scope introduces a durable new domain with no existing contract home. If a canonical contract already exists in code or docs (for example a skill file, OpenAPI document, schema namespace, or migration set), couple to that artifact directly instead of creating a duplicate under `design/specs/`.
5. Skip this step with `#+NO_SPEC_IMPACT: true` for prototype work, trivial/single-file fixes that do not alter a contract, or projects with no durable contract layer. Do not use the opt-out to avoid updating stale docs.

### Approach exploration

Before drafting `* Plan` for non-trivial changes, list 2–3 plausible approaches with real tradeoffs. Pick one; record the chosen approach in `** Decisions` with rejected alternatives bulleted underneath:

```org
** Decisions
- Chose Approach A (in-process adapter) :: cheapest path; ships first.
  Rejected:
  - Approach B (separate service): unnecessary process boundary for the expected load.
  - Approach C (rewrite): scope explodes; no payoff against ISC.
```

This preserves the rejected paths when re-litigation happens later.

### Premortem

Before drafting `* Plan`, assume the plan has failed. Work backwards:

1. *Riskiest assumptions* — 2–5 untested + load-bearing + implicit assumptions. For each: what happens if it is wrong?
2. *Failure modes* — 2–5 realistic ways this fails (built the wrong thing; works locally, breaks in prod; blocked by dependency).

Triage: mitigate the high-impact ones (turn into plan tasks); accept the rest and capture under `** Risks`. Skip the premortem for trivial changes (single file, easy rollback, pure exploration).

### Delegation

When a fact is blocking a planning decision, decide deliberately:

| Situation | Action |
|-----------|--------|
| User-preference question (scope, effort, UX) | Ask the user. |
| Codebase fact you have not verified | Spawn a `scout`-style subagent. |
| External knowledge you do not have | Spawn a `researcher`-style subagent. |
| You can answer from context in 30 seconds | Just answer. |
| The gap is not blocking a decision | Note it under `* Open questions`, move on. |

Wait for any spawned subagent before continuing the section. Fold findings into `** Decisions` / `* Context` / `* Implementation` as appropriate.

### Update vs new change

Extend the current task/record when the original intent still holds and most of the new scope overlaps the current plan. Spawn a new task/record when the intent changes, scope explodes, the original work is independently completable, or the follow-on would force unrelated contract/spec impacts into one record. Rule of thumb: update for unchanged intent + majority overlap; new change for changed intent, separable delivery, or a new durable domain.

### YAGNI

Stop planning once the plan is actionable. A 40-item ISC for a prototype is over-spec; a `** Decisions` list that pre-commits to extension points no one has asked for is gold-plating. Trim ruthlessly.

## Acceptance criteria

### Ideal-state checklist (ISC) vs acceptance criteria

The **ideal-state checklist (ISC)** is the consolidated, user-confirmed definition of done for the whole change: atomic, binary, one-second-verifiable criteria grouped into core functionality, edge cases, and anti-criteria. It is a planning artefact, drafted and signed off before the executable plan exists, and it lives in `** Acceptance` under `* Summary` so it survives closure-time pruning of task bodies.

**Acceptance criteria** are the per-task projection of the ISC: each plan task carries the slice it satisfies, as the `Acceptance criteria:` list described below.

For non-trivial plan tasks, put a short `Acceptance criteria:` bullet list at the top of the task body. Each bullet is a single concrete observable outcome ("X renders", "Y round-trips byte-identically") rather than an implementation step — yes/no verifiable in one second.

### Splitting test

Before committing a criterion, scan it:

- Contains "and" / "with" / "including"? → split into two.
- Can part A pass while part B fails? → separate them.
- Contains "all" / "every" / "complete"? → enumerate what "all" means.

### Anti-criteria

When a non-goal would be easy to violate by accident, capture it as an anti-criterion in the same list, prefixed `Must not:` (or under a dedicated `Anti-criteria:` heading for plan tasks with several). Examples: *Must not: write to the production database. Must not: introduce a new top-level dependency. Must not: change the public API signature.*

### Body discipline

Each plan task's body is the criteria list plus, at most, one pointer or non-obvious constraint. Prefer a `file:line` reference to a similar existing pattern, or an inline code sketch when no reference fits. Skipping examples leads to workers reporting back for clarification — spend the 30 seconds now.

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

1. Refresh `* Summary` so the effort line, `** Scope`, `** Shipped`, and `** Gotchas` reflect what actually landed. Reconcile `#+SPEC_IMPACT:` against actual `ADDED` / `MODIFIED` / `REMOVED` shipped bullets; include any discovered contract impact that was missed during planning.
2. Refresh `* Validation` so the verification record matches what actually ran. Verify each item in `** Acceptance` (the consolidated ISC) and every anti-criterion, not just generic test-suite status.
3. Ensure every `#+SPEC_IMPACT:` path was reviewed and updated when needed, or explain why the planned impact did not materialise.
4. Ensure newly discovered follow-up work exists as TODO tasks rather than buried prose.
5. If Summary is missing, generate it from promoted Context, completed plan tasks, and Implementation notes before closing.

Prune:

1. Delete `* Context` unless it still carries durable rationale Summary does not subsume.
2. Delete `* User story` unless the "so that" clause is still load-bearing (rare — most are subsumed by `** Shipped`).
3. Delete `* Behavior` unless the happy path or edge-case list preserves something Implementation/Summary do not subsume (also rare).
4. Condense `** Risks` — promote accepted-risks-that-paid-off into `** Decisions` or `** Gotchas`; delete the residue.
5. Collapse the plan layer. `* Plan` is execution scaffolding: once every task is `DONE`, trim completed plan-task bodies to compact acceptance/audit value (`:LOGBOOK:` preserves timing; `* Implementation` captures outcomes). Keep the durable definition of done in `** Acceptance` (see *Two layers in one record*).
6. Remove planning-flavoured Implementation subsections and condense useful content into outcomes, `** Gotchas`, or `** Decisions`.
7. Check that each Gotcha is a project-side surprise. Move library-level facts to the relevant skill/reference, or create follow-up work to do so.

`* Intent` itself is not pruned — it stays as the durable record of what this work was *for*. If Intent and Summary's effort line both still apply, the record passes the closure bar.

Draft summaries on active records may be terse and are expected to evolve; the final summary is written or refreshed at closure.

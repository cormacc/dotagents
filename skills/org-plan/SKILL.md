---
name: org-plan
description: "Draft, review, and execute implementation plans as TASKS.org-linked change-records -- owns change-record sections, subtask migration, and closure-time pruning. Use for 'let's plan', design write-ups, retrospective records, or pruning a change-record at closure."
---

# Plan

Use this skill when the user asks for a plan. A plan is the leading content of a change-record -- a separate org file linked from a task via `#+IMPORT:` that begins life as a plan and becomes the record of what shipped as work proceeds.

This skill owns planning methodology and change-record section conventions. `org-tasks` (`../org-tasks/SKILL.md`) owns file format, task lifecycle, persistence rules, and the `ot` CLI. Prefer `ot record create <task-id>` to scaffold a change-record before filling the sections below; use `ot record create <task-id> --mode retrospective` after work has started or completed. If the generated scaffold predates the current section contract, add/reorder sections before filling them.

Detailed skeletons, `#+STATUS:` values, `#+SPEC:` / `#+NO_SPEC:` examples, and subtask-migration examples live in `references/change-record-format.md`.

## Relationship to a harness "plan mode"

This skill is the planning system; a harness plan mode (for example dirge's plan phase, `--prompt plan`, or loop mode) is at most optional scratch space. They are orthogonal -- a harness plan mode never reads or writes `TASKS.org`, `design/log/`, `.org` records, or runs `ot` -- so the only real hazard is confusing which "plan" is canonical. Rules:

- The canonical plan is always the org change-record created via `ot record create <task-id>`, not a harness's in-session plan text or any `PLAN.md` / `LOOP_PLAN.md` file.
- A harness plan mode produces ephemeral session text or a fixed-name Markdown file; its format and path are not configurable, so it cannot emit or maintain the change-record format. Do not try to fuse the two artifacts.
- If a harness plan phase is used as a read-only exploration guardrail, transcribe its output into the `.org` change-record once editing is re-enabled, then discard any `PLAN.md` / `LOOP_PLAN.md` it left behind (these are gitignored).

## Two layers in one record

A change-record fuses two layers with different lifecycles in one file:

- **Record layer** (`* Intent`, `* Summary`, `* Implementation`, optional `* Validation`) -- durable memory. Accretes and survives past closure. Owned by this skill.
- **Plan layer** (`* Plan` TODO tasks) -- execution scaffolding. Churns through status/`:LOGBOOK:` writes and collapses at closure. Lifecycle owned by `org-tasks`.

Keeping both in one file preserves a single `#+IMPORT:` resume surface. The cost is that status churn rewrites the file and plan-task bodies are transient -- so the durable definition of done lives in the record layer (`** Acceptance`), never only in task bodies. At closure the plan layer collapses; see *Closure-time refresh and prune*.

## Canonical rules

The *splitting test*, *anti-criteria*, and *body discipline* blocks below are the canonical contract: downstream agents (for example the `planner`) reference them by name rather than restating them, so the rules cannot drift between files. The *effort line* and *intent reverse-engineering* blocks are also canonical here, but interactive agents may restate them as conversational prompts as long as this skill remains the source of truth.

## Planning principles

- Prefer plans that can be executed and verified task-by-task. A plan task is useful only when its acceptance criteria are observable. Keep the mechanical fallout of a change in the same task as its cause (renamed references, updated call sites, adjusted fixtures); a task whose acceptance criteria cannot be met until a later task lands is mis-split.
- Keep `* Summary` (condensed final/current state) and `* Implementation` (detailed tactical ledger) distinct; they serve different readers. Add `* Validation` only when there is non-obvious verification evidence to preserve (see its section).
- Capture durable design decisions in `* Summary` or a promoted `* Context` so later sessions do not reverse-engineer them from `git log`.
- Keep the change-record `.org` file canonical. If durable supporting resources are too verbose or awkward for the record body (research reports, screenshots, transcripts, generated audits), put them in an optional same-stem folder beside the record (for `design/log/YYYY-slug.org`, use `design/log/YYYY-slug/`) and link/summarise them from the record.
- For non-trivial work with durable behavioural/API/protocol/domain impact, identify the living contract docs up front and record them with `#+SPEC:` before implementation (see *Spec planning*).
- Stop planning once the plan is actionable. Endless planning is a failure mode.

## Voice and density

Plans are written for engineers with project context. Optimise for signal density, not narrative.

- Assume the reader knows the codebase, terminology, and motivation that led to the task.
- Prefer terse declarative bullets over paragraphs. Use paragraphs only when a bullet cannot carry the rationale.
- Default to omitting `* Context`. Promote it only when durable rationale materially exceeds what `* Summary` can carry.
- Plan-task bodies are acceptance criteria plus, at most, one pointer or non-obvious constraint.
- Avoid preamble, marketing tone, future-tense implementation narrative after work lands, and prose that restates task headings.
- Write the end state, not the journey to it. A record describes what now exists and why it is that way; it is not a chronicle of how it got built. Delivery mechanics -- which agent did what, task sequencing, what was tried first -- are not durable. Do not re-enumerate what the diff and commit already carry: name a changed contract because a reader must know its shape changed, not to inventory files.
- Do not hard-wrap. Write each paragraph and list item as a single logical line (soft-wrap); preserve real line breaks only in headings, drawers, keywords, tables, and src/example blocks. Never reflow a record to a fixed column such as 80. This is the canonical org-tasks rule (`../org-tasks/SKILL.md` § Protocol summary) applied to change-records.
- Because of that rule, line counts say nothing about a record's density -- one bullet is one line however long it runs. Measure prose in words (`wc -w`), and exclude the `* Plan` scaffolding, whose drawers dominate a line count and are not yours to condense.
- At closure, delete spike-style `* Implementation` subsections such as `*** What worked`, `*** What's awkward`, or `*** Implications for task N`; condense durable findings into Summary decisions/gotchas or implementation outcomes.

## Change-record sections

### Required

`* Intent` -- 1--3 sentence durable north-star. *What* we are building and *why*. Stable across the work; this is the constitutional clause that `* Summary` builds on and the premortem stress-tests. Drafted from intent reverse-engineering (see *Drafting practices*).

`* Summary` -- condensed evolving memory layer. The cheap reconstruction surface resume tools and humans land on after Intent. Opens with the effort line (see *Drafting practices*). Subsections populated as content accrues:

- `** Scope` (required) -- `*** In scope` and `*** Out of scope` lists. Both lists. Out-of-scope items prevent feature creep and feed the premortem ("are we sure we don't need X?").
- `** Acceptance` -- the consolidated ISC (user-confirmed definition of done), grouped into `*** Core functionality`, `*** Edge cases`, `*** Anti-criteria`. See *Acceptance criteria*.
- `** Decisions` -- strategic durable design choices that constrain future work, not every tactical coding call. Captures the chosen approach plus rejected alternatives bulleted underneath. When a decision knowingly conflicts with the project constitution (AGENTS.md + skills -- see *The project constitution*), record the conflict and rationale explicitly rather than silently diverging.
- `** Shipped` -- user-visible / protocol / code outcomes, populated as work lands. When a planned or discovered contract doc changes, prefix bullets with `ADDED`, `MODIFIED`, or `REMOVED` and name the contract doc; this is the post-hoc outcome, not the planning-time `#+SPEC:` declaration.
- `** Gotchas` -- project-side surprises future implementers should not rediscover. Library/API/protocol facts belong in the relevant skill/reference when one exists.
- `** Risks` -- drafted from premortem; durable risks considered and accepted. Distinct from `** Gotchas` (post-hoc surprise) and `* Open questions` (deferred-not-decided).
- `** Follow-ups` -- pointers to real TODO tasks rather than burying work in prose.

`* Plan` -- executable org TODO headings. Top-level plan tasks are `** TODO ...` so they live under `* Plan` while remaining parseable by task tooling. May be empty in a retrospective record.

`* Implementation` -- tactical decisions, tricky details, maintenance context, and outcomes discovered while executing. If the canonical tactical record lives elsewhere (upstream PR, commit body, vendor doc, RFC), use a concise pointer instead of duplicating it.

`* Validation` *(optional)* -- non-obvious verification evidence worth preserving: manual/exploratory checks, smoke tests, a specific reproduction, performance numbers, or an explicit note that no automated checks were run (and why). Omit it when the only evidence would be that the standard automated suites pass -- green unit/integration tests are an implicit pre-merge requirement, not record-worthy. Closure verifies `** Acceptance` and anti-criteria regardless of whether this section exists.

### Optional

`* User story` -- *As [who], I want [what], so that [why].* Use when there is a real end-user or operator perspective worth preserving -- the "so that" is often the most leaked motivation in any artifact. Skip for refactors, infra, dev-tooling, and observability work; forcing a user story onto those produces filler. Drafting-time aid: closure-prune unless the "so that" still earns its place.

`* Behavior` -- feature-work walkthrough. Two subsections:

- `** Happy path` -- numbered list of steps.
- `** Edge cases` -- bullets of `[case]: [expected behavior]`.

Drafting-time aid: the happy path becomes the implementation; edge cases become anti-criteria or `** Gotchas`. Closure-prune unless the walkthrough preserves something Implementation/Summary do not subsume.

Optionally, when scenario rigour pays for itself (concurrent/stateful edge cases, protocol handshakes, anything where the trigger and outcome are easy to conflate), write a `** Happy path` step or `** Edge cases` bullet in Given/When/Then form instead of the terser default:

```org
** Edge cases
- Given a WAITING task with no :BLOCKED-BY:, when `ot doctor` runs, then it emits `waiting-without-blocker`.
```

This is optional polish, not the default or required form -- do not impose GWT ceremony on straightforward edge cases.

`* Context` -- background, motivation, alternatives, constraints, and trade-offs. **Default to omitting.** Promote only when durable rationale materially exceeds what `* Summary` can carry. At closure, delete Context unless it still earns its place.

`* Open questions` -- deferred questions using heading-text prefixes, not TODO states:

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

`* Intent` → `* Summary` → optional `* User story` → optional `* Behavior` → optional `* Context` → `* Plan` → `* Implementation` → optional `* Validation` → optional `* Open questions`.

Optional `* User story` / `* Behavior` follow `* Summary` so the Intent→Summary reconstruction surface stays adjacent for resume tools.

## Drafting practices

These are process notes, not new sections. They feed the section content above.

### Intent reverse-engineering

Before writing `* Intent`, internally answer:

1. *Explicit asks* -- what the user concretely said.
2. *Implicit needs* -- what they want but did not say ("add login" implies sessions, logout, errors).
3. *Out of scope* -- what they explicitly do not want.
4. *Obvious not-wanted* -- a quick fix does not want a refactor.
5. *Speed* -- "quick" / "just" → minutes; "properly" / "thoroughly" → take the time needed.

Distil these into 1--3 Intent sentences. Surface unresolved items as `* Open questions` rather than papering over them. Items 2 and 3 feed `** Scope`. Item 5 feeds the effort line.

### Effort line

Single line at the head of `* Summary`:

```org
*Level:* prototype | MVP | production | critical · *Tests:* none / smoke / thorough / comprehensive · *Docs:* none / inline / README / full
```

Pick one value per dimension. Calibrates ISC tightness, plan-task acceptance criteria detail, and the closure-prune bar.

### Spec discovery (`#+SPEC:`)

`#+SPEC:` is an optional, repeatable keyword naming relevant specification documents. The same keyword is used in two contexts, disambiguated by the file it appears in: in `TASKS.org` it declares where a project's living specs live (repo-wide discovery roots); in a change-record it lists the specs relevant to that one task. In records, cite **individual sub-specs** rather than broad roots or folders -- leave recursive/transitive aggregation to the TASKS.org discovery layer. Whether a relevant spec was actually *impacted* is a closeout determination recorded in `** Shipped` (ADDED/MODIFIED/REMOVED); `ot doctor`'s `spec-untouched` warning is only an advisory nudge.

- Each `#+SPEC:` value is a bare `[[proj:PATH]]` org link (see `references/change-record-format.md` § Spec keyword) pointing at a spec **file or folder**; a folder is included recursively. The labelled `[[proj:PATH][label]]` form, bare non-link paths, and paths that are absolute or escape the repo root are rejected as malformed by `ot doctor`.
- `#+SPEC:` is optional. When absent and `./design/SPEC.org` exists, that file is the default root. When neither is present, spec support is inert -- no warnings, no required behaviour change.
- `#+SPEC:` is declared in `TASKS.org`. The discovery *convention* an agent performs may additionally honour a local `#+SPEC:` in gitignored `TASKS.local.org`, but `ot doctor` validates (malformed / dangling-path) only the `TASKS.org` declarations.
- **Implicit specs** are always considered without needing a `#+SPEC:` entry: repository-root `README.*` (any extension), `AGENTS.md`, and a project-local skills directory (`.agents/skills` when present; in this repo the skills live at `skills/`).
- **Rooted/transitive discovery**: each `#+SPEC:` entry, each file inside a declared folder, and each implicit spec is a discovery *root*. Links found in a root's content are followed to pull in sub-specs automatically -- a sub-spec needs no `#+SPEC:` entry of its own. Link resolution is relative to the linking document. A visited-set guard prevents infinite loops on cycles (A links B links A terminates once B is revisited).
- Recognised link syntaxes for traversal: org `[[file:...]]` and `[[proj:...]]` links, Markdown `[text](path)` links, and org `#+INCLUDE:` directives. Relative resolution is always relative to the linking document (`[[proj:...]]` stays repo-root relative). External (`http`/`https`) targets are never followed. The visited-set cycle guard applies uniformly across mixed org/Markdown link chains.
- Discovery (including transitive link-following) is primarily a convention the planning/implementing agent performs by reading files and reasoning about links; `ot spec list` (alias `spec discover`) can also verify the traversal mechanically as a read-only report. It excludes complete path segments `.git`, `.direnv`, `.devenv`, `.cache`, `node_modules`, `target`, `build`, `dist`, and `.next`, and accepts text candidates only when their bytes contain no NUL byte. Malformed control-data link targets produce non-fatal diagnostics rather than filesystem failures; `ot doctor` reports the same ordered warning alongside its other checks.

### Spec planning

For MVP / production / critical work that can change durable behaviour, public APIs, protocols, domain models, or agent/operator workflow, identify impacted living contracts before drafting the executable plan.

1. Prefer a codebase-scout subagent when the question is "which in-repo docs/specs/schemas does this codebase change touch?" Use a research subagent when the trigger is external knowledge or a new external capability.
2. Identify relevant individual specs from the set discovered per *Spec discovery* above (declared `#+SPEC:` roots, their recursive/transitive closure, and the implicit specs), then record task-relevant specs as repeated `[[proj:PATH]]` keywords near the top of the record:

   ```org
   #+SPEC: [[proj:skills/org-plan/SKILL.md]]
   #+SPEC: [[proj:design/specs/data-model.org]]
   ```

3. Mirror the relevance in human-readable `*** In scope` bullets so implementers work the checklist.
4. Propose a new spec only when scope introduces a durable new domain with no existing contract home. If a canonical contract already exists in code or docs (for example a skill file, OpenAPI document, schema namespace, or migration set), couple to that artifact directly instead of creating a duplicate under `design/specs/`.
5. Skip this step with `#+NO_SPEC: true` for prototype work, trivial/single-file fixes that do not alter a contract, or projects with no durable contract layer. Do not use the opt-out to avoid updating stale docs.

### Approach exploration

Before drafting `* Plan` for non-trivial changes, list 2--3 plausible approaches with real tradeoffs. Pick one; record the chosen approach in `** Decisions` with rejected alternatives bulleted underneath:

```org
** Decisions
- Chose Approach A (in-process adapter) :: cheapest path; ships first.
  Rejected:
  - Approach B (separate service): unnecessary process boundary for the expected load.
  - Approach C (rewrite): scope explodes; no payoff against ISC.
```

This preserves the rejected paths when re-litigation happens later.

### The project constitution

This repo has no separate constitution/steering file (Spec Kit's `constitution.md`, Kiro's steering docs) -- that role belongs to the already-canonical `AGENTS.md` plus the skills under `skills/` (or `.agents/skills`). They are the durable, agent-and-human-readable rules the project already holds itself to; naming them "the constitution" is a label for premortem/decision checks, not a new artifact. Do not create a constitution file or template section -- reuse these.

### Premortem

Before drafting `* Plan`, assume the plan has failed. Work backwards:

1. *Riskiest assumptions* -- 2--5 untested + load-bearing + implicit assumptions. For each: what happens if it is wrong?
2. *Failure modes* -- 2--5 realistic ways this fails (built the wrong thing; works locally, breaks in prod; blocked by dependency).
3. *Constitution conformance* -- does the plan conflict with anything in AGENTS.md or the relevant skills (the project constitution)? If so, treat it as a failure mode: resolve it or record the conflict explicitly.

Triage: mitigate the high-impact ones (turn into plan tasks); accept the rest and capture under `** Risks`. Skip the premortem for trivial changes (single file, easy rollback, pure exploration).

### Delegation

When a fact is blocking a planning decision, decide deliberately:

| Situation | Action |
|-----------|--------|
| User-preference question (scope, effort, UX) | Ask the user. |
| Codebase fact you have not verified | Spawn a `scout`-style subagent. |
| External knowledge you do not have | Spawn a `researcher`-style subagent. |
| Draft change-record needs review | Spawn a read-only `reviewer`; the orchestrator applies only approved findings. |
| You can answer from context in 30 seconds | Just answer. |
| The gap is not blocking a decision | Note it under `* Open questions`, move on. |

Wait for any spawned subagent before continuing the section. Fold findings into `** Decisions` / `* Context` / `* Implementation` as appropriate. Do not assign review-only change-record work to a write-enabled worker.

### Update vs new change

Extend the current task/record when the original intent still holds and most of the new scope overlaps the current plan. Spawn a new task/record when the intent changes, scope explodes, the original work is independently completable, or the follow-on would force unrelated contract/spec impacts into one record. Rule of thumb: update for unchanged intent + majority overlap; new change for changed intent, separable delivery, or a new durable domain.

### Trivial changes: inline plan

For a trivial single-task change -- one or two files, a one-sentence design, easy rollback -- the user may opt out of a change-record: document the agreed approach, acceptance bullets, and anti-criteria inline in the TASKS.org task body and implement directly. This is a deliberate, scoped exception to keeping TASKS.org high-level; a few bullets only. Create a record as usual whenever decisions, validation evidence, or multiple plan tasks need a durable home.

### YAGNI

Stop planning once the plan is actionable. A 40-item ISC for a prototype is over-spec; a `** Decisions` list that pre-commits to extension points no one has asked for is gold-plating. Trim ruthlessly.

## Acceptance criteria

### Ideal-state checklist (ISC) vs acceptance criteria

The **ideal-state checklist (ISC)** is the consolidated, user-confirmed definition of done for the whole change: atomic, binary, one-second-verifiable criteria grouped into core functionality, edge cases, and anti-criteria. It is a planning artefact, drafted and signed off before the executable plan exists, and it lives in `** Acceptance` under `* Summary` so it survives closure-time pruning of task bodies.

**Acceptance criteria** are the per-task projection of the ISC: each plan task carries the slice it satisfies, as the `Acceptance criteria:` list described below.

For non-trivial plan tasks, put a short `Acceptance criteria:` bullet list at the top of the task body. Each bullet is a single concrete observable outcome ("X renders", "Y round-trips byte-identically") rather than an implementation step -- yes/no verifiable in one second.

### Spec/test citation on acceptance criteria

An `** Acceptance` criterion may optionally cite the spec clause it satisfies and/or the test that asserts it, appended after the criterion text with a `→` separator: `spec:[[proj:PATH]]` and/or `test:` followed by a test reference. Prefer a stable test reference: the file path plus the `deftest`/test name, optionally with a short quoted source anchor from the assertion (`test/widget_test.clj` `dark-mode-render` `"is dark"`). Reserve a bare line number or range for cheap-to-re-derive one-off pointers -- it drifts silently as unrelated tests land above it. Both tokens are optional and independent; citing a spec without a test is fine for `*** Anti-criteria` (the anti-criterion itself is the evidence) but for `*** Core functionality` / `*** Edge cases` a spec-citing criterion should also cite a `test:` -- `ot doctor` nudges (never blocks) when one is missing. Citations are opt-in: an uncited criterion produces no finding.

```org
- [ ] Widget renders in dark mode → spec:[[proj:design/specs/theming.org]] test:`test/widget_test.clj` `dark-mode-render`
```

See `references/change-record-format.md` § Acceptance criteria citation for a worked example, and `../org-tasks/references/ot-cli.md` § Spec keyword and checks for the `ot doctor` finding this feeds.

### Splitting test

Before committing a criterion, scan it:

- Contains "and" / "with" / "including"? → split into two.
- Can part A pass while part B fails? → separate them.
- Contains "all" / "every" / "complete"? → enumerate what "all" means.

Splitting acts on criteria, not on edit sites: work that must edit the same function or form belongs in one task even when its criteria are separately observable, since separate tasks cannot then be executed concurrently.

### Anti-criteria

When a non-goal would be easy to violate by accident, capture it as an anti-criterion in the same list, prefixed `Must not:` (or under a dedicated `Anti-criteria:` heading for plan tasks with several). Examples: *Must not: write to the production database. Must not: introduce a new top-level dependency. Must not: change the public API signature.*

### Body discipline

Each plan task's body is the criteria list plus, at most, one pointer or non-obvious constraint. Prefer a stable symbol reference (file path plus function or var name) to a similar existing pattern -- never a bare line number or range, which drifts between planning and execution -- or an inline code sketch when no reference fits. Skipping examples leads to workers reporting back for clarification -- spend the 30 seconds now.

## Plan task metadata and status

Plan task headings may nest deeper than level 2. Status discipline, including parent propagation, `:STARTED:`, `CLOSED:`, and `:LOGBOOK:`, is owned by `org-tasks` and should be changed through `ot status` or pi tooling.

Hand-authored skeletons may omit `:LOGBOOK:` until the first automated status write. Fresh plan-only tasks get UUIDs via `ot uuid` or `ot create`; never invent UUIDs in prose.

## Subtask migration from TASKS.org

When a TASKS.org task already has subtasks and a new proactive change-record is created, `ot record create` moves those child task trees into the record under `* Plan`, preserving their `:CUSTOM_ID:` values and nesting. The parent task keeps the `#+IMPORT:` link and loses the local child task trees, so the graph has one canonical writable node per UUID.

Existing record files are not modified for migration. If subtasks remain duplicated after a record already exists, move them manually into `* Plan`, remove the parent-local copies, and run `ot doctor`.

New plan-only work units that have no TASKS.org analogue also get fresh UUIDs and `:CREATED:` properties.

## Retrospective change-records

Here “retrospective” means an after-the-fact project change-record reconstructed from delivered work and history. It is distinct from the [`retro`](../retro/SKILL.md) end-of-session learning workflow; creating or closing this record does not itself trigger retro.

When drafting after work has started or completed:

- Scaffold with `ot record create <id> --mode retrospective` so the tool returns the `git log` scope derived from `:STARTED:` and `CLOSED:`.
- Mark already-completed work `DONE`, current work `STARTED`, and remaining follow-ups `TODO`.
- Draft `* Summary` from the delivered/current state, not from wishful planning language.
- Record key implementation outcomes in `* Implementation`; add `* Validation` only for non-obvious verification evidence (not routine green suites).
- Do not rewrite history to look planned in advance. Preserve LOGBOOK history emitted by tooling.

## Executing from a change-record

Before starting, ask whether questions should be batched in `* Open questions` for final review or raised immediately. Then resume via `org-tasks` § Resuming and agent memory. If commits touching the plan's target surface landed after the record was accepted, re-verify its pinned references and load-bearing assumptions against HEAD (or delegate a plan review) before implementing -- stale pins and drifted contracts are cheaper to catch before code exists.

For each plan task:

1. Mark it `STARTED` when beginning now.
2. Implement the smallest change that satisfies the task.
3. Verify the change.
4. Mark it `DONE` and add a short result note only when useful.
5. Add newly discovered follow-up work as TODO tasks under `* Plan` or `TASKS.org`, not as inline prose.
6. Handle questions per the agreed mode: append to `* Open questions` or raise immediately.

## Updating change-records after discoveries

When an update combines `ot` lifecycle mutations with direct record edits, run all `ot` mutations first, then re-read the owning file before composing edits. Lifecycle writes rewrite the record and invalidate earlier exact-text snapshots.

Update the record when implementation reveals durable work or rationale: prerequisites, decisions, validation gaps, refactors, blockers, or deferred questions. Keep additions concise and task-shaped.

For discovered prerequisites that are tasks elsewhere in the graph, express the dependency via `:BLOCKED-BY:` / `:BLOCKED-BY+:`; see `org-tasks` for ready-task semantics.

## Closure-time refresh and prune

Before transitioning a top-level task to `DONE`, walk the record end-to-end with two questions: does each section still earn its place, and does it follow the density rules above?

Record closure is not itself a retro trigger, but it is the checkpoint to check for one: per `org-tasks` § Session closeout, scan for the signals defined by `retro` -- including unscanned child `PROCESS` candidates from delegation -- persist the task and record first, then offer one separate retro when signals exist. Route approved durable agent-process findings through [`self-improvement`](../self-improvement/SKILL.md); reference resulting TODOs in `** Follow-ups` only when they are relevant to the project change-record.

Refresh:

1. Refresh `* Summary` so the effort line, `** Scope`, `** Shipped`, and `** Gotchas` reflect what actually landed. Compare `#+SPEC:` with actual `ADDED` / `MODIFIED` / `REMOVED` shipped bullets; include any discovered contract impact that was missed during planning.
2. Verify each item in `** Acceptance` (the consolidated ISC) and every anti-criterion. If a `* Validation` section exists, refresh it to match what actually ran; drop it if it has decayed to just "the suites pass" (an implicit pre-merge requirement).
3. Ensure every `#+SPEC:` path was reviewed and updated when needed, or explain why the spec was relevant but unchanged -- update the impacted spec files themselves (not just the declaration) to reflect the change scope before marking `DONE`.
4. Ensure newly discovered follow-up work exists as TODO tasks rather than buried prose.
5. If Summary is missing, generate it from promoted Context, completed plan tasks, and Implementation notes before closing.

Prune:

1. Delete `* Context` unless it still carries durable rationale Summary does not subsume.
2. Delete `* User story` unless the "so that" clause is still load-bearing (rare -- most are subsumed by `** Shipped`).
3. Delete `* Behavior` unless the happy path or edge-case list preserves something Implementation/Summary do not subsume (also rare).
4. Condense `** Risks` -- promote accepted-risks-that-paid-off into `** Decisions` or `** Gotchas`; delete the residue.
5. Collapse the plan layer. `* Plan` is execution scaffolding: once every task is `DONE`, trim completed plan-task bodies to compact acceptance/audit value (`:LOGBOOK:` preserves timing; `* Implementation` captures outcomes). Keep the durable definition of done in `** Acceptance` (see *Two layers in one record*).
6. Remove planning-flavoured Implementation subsections and condense useful content into outcomes, `** Gotchas`, or `** Decisions`.
7. Cut change narration. Closure should shrink a record, not grow it: if `** Shipped` or `* Implementation` gained prose describing the *process* of delivery, reduce it to the end state a future reader needs. Prefer deleting a bullet to condensing it -- for a single-audience project, anything reconstructible from the commit or diff is not worth a line.
7. Check that each Gotcha is a project-side surprise. Move library-level facts to the relevant skill/reference, or create follow-up work to do so.

`* Intent` itself is not pruned -- it stays as the durable record of what this work was *for*. If Intent and Summary's effort line both still apply, the record passes the closure bar.

Draft summaries on active records may be terse and are expected to evolve; the final summary is written or refreshed at closure.

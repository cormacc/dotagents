# Research: OpenSpec vs org-plan — model comparison and alignment opportunities

## Answer

OpenSpec and org-plan solve overlapping problems (turn fuzzy intent into a
reviewable, executable, archivable record of a change) but with two fundamentally
different data models. **OpenSpec maintains a durable, accreting *source-of-truth
spec corpus* (`openspec/specs/`) that each change mutates via explicit
ADDED/MODIFIED/REMOVED *delta specs*; org-plan has no equivalent** — its
change-records are per-task and self-contained, with nothing that lives on after
archive as a living behavioural contract. OpenSpec splits a change into discrete
files (proposal / design / tasks / delta-specs) wired by a customizable
dependency-graph *schema*; org-plan packs the same concerns into one org file's
sections. Conversely, org-plan is markedly more disciplined on the *planning*
craft (premortem, rejected-alternatives, effort calibration, anti-criteria,
closure-time pruning) and on cross-task graph semantics (UUIDs, `:BLOCKED-BY:`,
parent status propagation) than OpenSpec, whose per-change tasks are flat
checkboxes. The highest-value, best-fitting borrowings are: (1) a lightweight
structural *validation/lint* gate on the record, (2) explicit ADDED/MODIFIED/REMOVED
delta framing for `** Shipped`, and (3) an explicit *verify* step. The signature
OpenSpec feature — the living spec corpus — is a **poor fit** for org-plan's
single-record-per-task model and should not be adopted wholesale.

---

## 1. OpenSpec model summary

OpenSpec is an MIT-licensed npm tool (`@fission-ai/openspec`, Node ≥ 20.19) that
adds a "lightweight spec layer" so a human and an AI agent agree on *what* to
build before code is written. The current workflow is **OPSX** (artifact-guided,
schema-driven); the legacy hardcoded-template workflow is deprecated.

### Core artefacts & directory layout

Two top-level areas under `openspec/` ([concepts.md](https://github.com/Fission-AI/OpenSpec/blob/main/docs/concepts.md)):

```
openspec/
├── specs/                       # SOURCE OF TRUTH — how the system currently behaves
│   └── <domain>/spec.md         # organised by domain (auth/, payments/, ui/…)
├── changes/                     # proposed modifications, one folder each
│   ├── add-dark-mode/
│   │   ├── proposal.md          # why + scope + approach (intent)
│   │   ├── design.md            # how — technical approach + arch decisions
│   │   ├── tasks.md             # implementation checklist (checkboxes)
│   │   ├── .openspec.yaml        # optional per-change metadata (schema override)
│   │   └── specs/<domain>/spec.md  # DELTA specs (ADDED/MODIFIED/REMOVED)
│   └── archive/
│       └── 2025-01-24-add-dark-mode/   # completed change, date-prefixed
├── schemas/                     # optional custom workflow definitions
│   └── <name>/{schema.yaml, templates/*.md}
└── config.yaml                  # project context + per-artifact rules
```

- **Specs** = behaviour contracts. Format: `## Purpose`, then `## Requirements`
  → `### Requirement: …` (RFC-2119 SHALL/MUST/SHOULD/MAY) → `#### Scenario: …`
  in Given/When/Then bullets. Explicitly *not* an implementation plan — no class
  names, library choices, or step lists.
- **Changes** = self-contained folders bundling artefacts + delta-specs +
  metadata; enables parallel changes without conflict.
- **Delta specs** = the brownfield-first key concept: describe *what changes*
  relative to current specs via three sections — `## ADDED Requirements`
  (appended on archive), `## MODIFIED Requirements` (replaces existing),
  `## REMOVED Requirements` (deleted on archive).

### Artefact flow & schema (dependency DAG)

`proposal → specs → design → tasks → implement`
([concepts.md](https://github.com/Fission-AI/OpenSpec/blob/main/docs/concepts.md#artifacts),
[opsx.md](https://github.com/Fission-AI/OpenSpec/blob/main/docs/opsx.md)).
A *schema* (`schema.yaml`) declares artefacts and their `requires:` edges.
**Dependencies are enablers, not phase gates** — you may skip `design`, create
`specs` before `design`, etc. Built-in schema: `spec-driven`. Custom schemas via
`openspec schema init/fork` (e.g. a `research-first` schema that inserts a
`research.md` artefact before `proposal`).

### Lifecycle & states

Fluid "actions, not phases" ([opsx.md](https://github.com/Fission-AI/OpenSpec/blob/main/docs/opsx.md)):

1. **Propose / new** — scaffold the change, generate planning artefacts.
2. **Create artefacts** — `continue` (one at a time) or `ff` (fast-forward all).
3. **Apply** — implement tasks, ticking checkboxes; edit any artefact as you learn.
4. **Verify** (optional) — check implementation matches specs.
5. **Sync** (optional) / **Archive** — merge delta specs into `openspec/specs/`,
   move the change folder to `changes/archive/YYYY-MM-DD-<name>/`.

**Artefact state** is derived from the filesystem + DAG, not a stored phase:
`BLOCKED` (missing deps) → `READY` (all deps DONE) → `DONE` (output file exists).
Reported by `openspec status --json` with `applyRequires`, `missingDeps`, etc.

There is also a "**Update vs Start Fresh**" heuristic
([opsx.md](https://github.com/Fission-AI/OpenSpec/blob/main/docs/opsx.md#when-to-update-vs-start-fresh)):
update the existing change when intent is unchanged and scope overlaps >50%;
start a new change when intent fundamentally changed, scope exploded, or the
original is independently completable. *"Update preserves context. New change
provides clarity."*

### CLI surface ([cli.md](https://github.com/Fission-AI/OpenSpec/blob/main/docs/cli.md))

- **Setup**: `init`, `update` (regenerate agent instruction/skill files).
- **Browsing**: `list`, `view` (TUI dashboard), `show` (`--json`, `--deltas-only`,
  `--requirements`).
- **Validation**: `validate [item] [--all|--changes|--specs] [--strict] [--json]`
  — structural linting; flags e.g. *"design.md: missing 'Technical Approach'
  section"*. JSON emits `valid`/`warnings`/`summary`.
- **Lifecycle**: `archive [--yes] [--skip-specs] [--no-validate]` — validates,
  merges deltas, moves to dated archive folder.
- **Workflow**: `new change`, `set change`, `status`, `instructions [artifact|apply]`,
  `templates`, `schemas` — all `--json` for agents.
- **Schemas**: `schema init|fork|validate|which`.
- **Config**: `config get/set/profile/edit` (global), plus project `config.yaml`.
- **Beta multi-repo**: `workspace *`, `context-store *`, `initiative *` — local
  views over linked repos + durable shared "initiatives" for cross-repo coordination.

### Tasks & progress tracking

`tasks.md` = hierarchical numbered checklist grouped under `##` headings, e.g.
`- [ ] 1.1 Create ThemeContext`. Progress is literally checkbox state
(`[ ]`/`[x]`); `openspec status` reports artefact completion, not per-task counts.
Best-practice: group related tasks, small enough for one session, tick as you go.

### Agent integration

`openspec init --tools claude,cursor,…` (20+ supported tools incl. `pi`) generates
per-tool **skill files** (`.claude/skills/openspec-*/SKILL.md`) and optional
**slash commands** (`/opsx:propose`, `/opsx:apply`, `/opsx:archive`, …). The
agent loop is *query-driven*: skills call `openspec status --json` for state and
`openspec instructions <artifact> --json` for an enriched prompt (template +
injected project `context` + per-artefact `rules` + dependency content). Project
`config.yaml` injects `<context>…</context>` and `<rules>…</rules>` into every
artefact's instructions. Validation gates run at `archive`.

---

## 2. Similarity map

| OpenSpec concept | org-plan concept | Match quality |
|---|---|---|
| Change folder (`changes/<name>/`) | Change-record org file (linked via `#+IMPORT:`) | **Strong** — both are the per-unit-of-work container that begins as plan, becomes record, then archives. |
| `proposal.md` (intent + scope + approach) | `* Intent` + `* Summary` (`** Scope`, `** Decisions`) | **Strong** — direct conceptual overlap; org-plan splits "intent" (durable) from "approach/decisions" (evolving). |
| `design.md` (technical approach, arch decisions, data flow, file changes) | `* Implementation` + `** Decisions` (+ optional `* Context`) | **Partial** — org-plan's `* Implementation` is a *post-hoc* tactical ledger, not a *pre-hoc* design doc; design-time rationale lives in `** Decisions`. |
| `tasks.md` (numbered checkbox checklist) | `* Plan` (`** TODO` org headings) | **Strong** — both are the executable checklist. org-plan tasks carry richer metadata (UUID, status, LOGBOOK, acceptance criteria). |
| Delta specs `## ADDED/MODIFIED/REMOVED Requirements` | `** Shipped` (loosely) | **Weak / absent** — org-plan has no change-classified delta model and no requirement/scenario corpus to delta against. |
| `openspec/specs/` (durable source-of-truth corpus) | — | **Absent** in org-plan. No accreting living spec; nearest is each skill's own reference docs, but those aren't a per-project behavioural corpus. |
| `### Requirement:` / `#### Scenario:` (Given/When/Then, RFC 2119) | `* Behavior` (`** Happy path` / `** Edge cases`) + plan-task `Acceptance criteria:` | **Partial** — same intent (testable observable behaviour) but org-plan is informal prose; OpenSpec is structured GWT + MUST/SHALL keywords. org-plan's *acceptance-criteria discipline* (splitting test, anti-criteria) is stricter. |
| Schema DAG (`requires:` edges, BLOCKED/READY/DONE) | Section contract (fixed) + org TODO states + `:BLOCKED-BY:` | **Partial** — OpenSpec models *artefact* dependencies inside one change; org-plan models *task* dependencies across the whole graph. Different axis. |
| `openspec validate` (structural lint, archive gate) | `ot doctor` (graph/structure) + closure-time refresh-and-prune (manual) | **Partial** — `ot doctor` checks task-graph integrity; closure-prune is manual discipline. No section-contract linting of the record. |
| `openspec status --json` / `instructions --json` | `ot` CLI (`record create`, `status`, resume tooling) | **Strong** — both expose machine-readable state to agents; org-plan's `ot` is the headless driver. |
| `verify` action (impl vs specs) | `* Validation` + closure refresh | **Partial** — org-plan records evidence but has no distinct "verify against criteria" gate. |
| `config.yaml` context + rules injection | Project `AGENTS.md` + skill prose | **Strong (equivalent)** — both inject project conventions into agent prompts; org-plan via AGENTS.md/skills rather than a per-artefact rules map. |
| Archive (`changes/archive/YYYY-MM-DD-…`, merge deltas) | `org-tasks` archive (DONE + LOGBOOK preserved) | **Partial** — both preserve completed records; org-plan does *not* merge anything into a living corpus. |
| Workspaces / initiatives (multi-repo coordination) | TASKS.org task graph + `#+IMPORT:` | **Weak** — different scope; org graph is single-repo task memory. |
| — | Premortem, approach-exploration, effort line, intent reverse-engineering, delegation table, YAGNI | **Absent** in OpenSpec (it has no codified planning craft). |
| — | `* Open questions` (OPEN/DECIDED), `** Gotchas`, `** Risks`, `** Follow-ups` | **Absent / weak** in OpenSpec. |

---

## 3. What OpenSpec has that org-plan lacks

Ordered by adoption value × fit.

1. **Structural validation / linting of the record** — `openspec validate`
   checks every artefact for required sections and emits warnings, and `archive`
   runs it as a gate. org-plan relies on *manual* closure-time refresh-and-prune
   plus `ot doctor` (which checks graph integrity, not section conformance).
   **Worth adopting (good fit):** an `ot doctor`/`ot lint` check that the
   change-record carries its required sections (`* Intent`, `* Summary` with
   effort line + `** Scope`, `* Plan`, `* Validation`) and warns on missing ones
   before a top-level task → DONE. Mechanical, no model change.

2. **Explicit delta classification (ADDED / MODIFIED / REMOVED)** — makes the
   *nature* of each change legible and review-friendly. org-plan's `** Shipped`
   is an undifferentiated outcome list.
   **Partially worth adopting (good fit):** frame `** Shipped` (or a new
   `** Changes`) as Added/Modified/Removed sub-bullets for changes that touch
   existing behaviour. Cheap, improves the durable record's reviewability.

3. **Distinct `verify` step** — verify implementation *against the stated
   criteria/specs* as its own action, separate from "ran the tests".
   **Worth adopting (good fit):** org-plan already has acceptance criteria and a
   `* Validation` section; making "tick each acceptance criterion / anti-criterion"
   an explicit closure checklist item formalises what is currently implicit.

4. **The durable source-of-truth spec corpus (`openspec/specs/`) with
   delta-merge-on-archive** — OpenSpec's signature feature: behaviour
   specifications accrete over time as changes archive, so the next change builds
   on an updated contract.
   **Poor fit — do not adopt wholesale.** org-plan is deliberately
   *single-record-per-task*; there is no project-wide living spec, and org/TASKS.org
   is task memory, not a spec system. Building a spec corpus + merge engine would
   be a different product. *Narrow, defensible borrow:* the discipline of
   *promoting* durable behavioural facts out of an archived record into a
   longer-lived home already exists in org-plan ("move library-level Gotchas to
   the relevant skill/reference"). Extending that to "promote durable behavioural
   contracts to a skill/reference" captures ~80% of the value without a corpus.

5. **Given/When/Then + RFC 2119 keyword discipline** for behaviour/requirements.
   **Low-value-but-cheap (optional fit):** offer GWT as an *option* for
   `** Edge cases`, and allow MUST/SHALL/SHOULD framing in acceptance criteria.
   org-plan's anti-criteria (`Must not:`) already encode RFC-2119-style intent;
   over-formalising risks ceremony that conflicts with org-plan's density rules.

6. **Customizable workflow schema (DAG of artefacts, per-project)** —
   `schema init/fork`, dependency graph engine, topological readiness.
   **Poor fit.** org-plan's section contract is intentionally fixed and curated;
   per-project artefact schemas would fragment the convention and fight the
   "single record, known sections" model. The *enabler-not-gate* philosophy,
   however, is already how org-plan works (optional sections, draft-then-prune).

7. **Per-artefact `rules` injection via `config.yaml`** — project-scoped prompt
   rules keyed by artefact type. org-plan gets equivalent leverage from project
   `AGENTS.md` + skill prose; no clear gain from replicating the keyed-rules map.
   **Neutral — already covered.**

8. **Multi-repo coordination (workspaces / initiatives)** — out of scope for a
   single-repo task-memory model. **Not applicable.**

---

## 4. What org-plan has that OpenSpec lacks (relative maturity)

org-plan encodes far more *planning craft* and *task-graph rigour* than OpenSpec,
which is essentially a document-scaffolding + spec-merge engine:

- **Premortem** (riskiest assumptions + failure modes → mitigate or accept as
  `** Risks`). OpenSpec has no risk surface.
- **Approach exploration with rejected alternatives** recorded in `** Decisions`.
  OpenSpec's `design.md` records the chosen approach but not a disciplined
  consider-and-reject step.
- **Effort line** (`Level / Tests / Docs`) calibrating rigour. OpenSpec's only
  analogue is informal "Lite spec vs Full spec" guidance.
- **Intent reverse-engineering** (explicit asks / implicit needs / out-of-scope /
  obvious-not-wanted / speed). No OpenSpec equivalent.
- **Acceptance-criteria discipline**: observable yes/no criteria, the *splitting
  test* (no and/with/all), and **anti-criteria** (`Must not:`). OpenSpec only
  suggests "a few concrete acceptance checks."
- **Closure-time refresh-and-prune** — condense planning prose into durable
  Decisions/Gotchas/Shipped, delete sections that no longer earn their place.
  OpenSpec *archives verbatim* — no condensation; the archive is a snapshot, not
  a curated record.
- **`* Open questions`** with OPEN/DECIDED markers and decision provenance.
- **`** Gotchas`** (durable project-side surprises) and **`** Follow-ups`** as
  pointers to real tasks rather than buried prose.
- **Task-graph awareness**: UUID `:CUSTOM_ID:`, `:BLOCKED-BY:` cross-task deps,
  parent status propagation, LOGBOOK timing. OpenSpec tasks are flat checkboxes
  inside one change with no cross-change dependency graph (initiatives coordinate
  repos, not task dependencies).
- **Delegation table** (scout/researcher subagents for blocking facts).
- **Retrospective mode** (`--mode retrospective`) for after-the-fact records.
- **Voice/density discipline** (signal-dense bullets, omit-by-default Context).

In short: OpenSpec is broader on *tooling surface* (CLI, multi-tool skills,
multi-repo, spec corpus) but org-plan is deeper on *how to think while planning*
and on *task-dependency semantics*.

---

## 5. Concrete alignment recommendations (prioritised)

Each: **what / why / effort / org-mode-`ot` fit**.

### R1 — Add a record-structure lint to `ot doctor` (or `ot lint`)  ★ adopt
- **What:** warn when a change-record is missing required sections (`* Intent`,
  `* Summary` with effort line + `** Scope`, `* Plan`, `* Validation`) or when a
  top-level task is about to go `DONE` without a refreshed `* Validation`.
- **Why:** OpenSpec's `validate` + archive gate catches structural drift
  mechanically; org-plan currently leans on manual closure discipline that is
  easy to skip.
- **Effort:** Medium — new check in the `ot` CLI; mostly a section-presence scan.
- **Fit:** Strong. Lives in `org-tasks`/`ot` territory (which already owns
  `ot doctor`), not a model change. Keep it *warnings*, not hard gates, to honour
  the "fluid not rigid" instinct org-plan shares.

### R2 — Offer ADDED/MODIFIED/REMOVED framing inside `** Shipped`  ★ adopt
- **What:** document (in the org-plan skill) that `** Shipped` may use
  Added/Modified/Removed sub-bullets for behaviour-touching changes.
- **Why:** Borrows OpenSpec's most useful review affordance (legible change
  classification) at near-zero cost; sharpens the durable record.
- **Effort:** Low — skill-prose addition + one example.
- **Fit:** Strong. Pure convention; no tooling, no model change.

### R3 — Make acceptance-criterion verification an explicit closure step  ★ adopt
- **What:** add a closure checklist item: "tick each plan-task acceptance
  criterion and anti-criterion against observed behaviour; record the evidence in
  `* Validation`." Mirrors OpenSpec's `verify` action.
- **Why:** org-plan defines strong criteria but never formalises *checking* them;
  this closes the loop and pairs with R1.
- **Effort:** Low — skill-prose addition to the closure-time refresh section.
- **Fit:** Strong. Uses existing `* Validation` + acceptance-criteria machinery.

### R4 — Codify a "promote durable behaviour to a longer-lived home" rule  ◐ partial-adopt
- **What:** extend the existing "move library facts to the relevant
  skill/reference" prune rule to also cover *durable behavioural contracts*:
  when an archived record establishes a behaviour future work must respect,
  promote a one-line contract to the owning skill/reference/README rather than
  letting it die in the archive.
- **Why:** captures the *value* of OpenSpec's accreting spec corpus (behaviour
  knowledge survives the change) without building a spec system or merge engine.
- **Effort:** Low–Medium — skill-prose rule + judgement guidance; optionally a
  convention for where contracts live.
- **Fit:** Good *as a discipline*; **poor as infrastructure**. Do **not** build an
  `openspec/specs/`-style corpus or delta-merge — that contradicts
  single-record-per-task and would duplicate what skills/references already do.

### R5 — Adopt the "Update vs New change" heuristic for record scope  ◐ partial-adopt
- **What:** add brief guidance (mirroring OpenSpec's decision tree) on when a new
  discovery should extend the *current* change-record vs spawn a *new* task +
  record: same intent / >50% scope overlap / original not independently
  completable → update; else new.
- **Why:** org-plan covers "add follow-ups as tasks" but not the
  scope-explosion-vs-refinement judgement, which is a recurring real decision.
- **Effort:** Low — a short subsection under "Updating change-records after
  discoveries."
- **Fit:** Strong. Reinforces existing `** Follow-ups`/`:BLOCKED-BY:` practice;
  the "update preserves context, new provides clarity" framing maps cleanly onto
  the task graph.

### R6 — Optionally allow Given/When/Then for `** Edge cases`  ○ optional
- **What:** note GWT as an allowed (not required) format for edge cases /
  behaviour scenarios.
- **Why:** structured scenarios are more testable; some edge cases benefit.
- **Effort:** Trivial — one line in `* Behavior` guidance.
- **Fit:** Acceptable but **watch for ceremony** — org-plan's density rules warn
  against forcing structure where a bullet suffices. Keep it optional.

### Flagged as poor fit (do **not** adopt)
- **Per-project workflow schemas / artefact DAG** (R-reject): fragments the fixed
  section contract; conflicts with org-plan's curated single convention.
- **Splitting one record into multiple files** (proposal/design/tasks/specs as
  separate files): org-mode's strength is *one navigable outline*; multi-file
  changes lose the resume-tool single-landing-surface and the `#+IMPORT:` linkage
  model. Keep sections, not files.
- **A living `openspec/specs/` corpus + delta-merge engine**: different product;
  see R4 for the lightweight value-capture instead.
- **Replicating `config.yaml` per-artefact rules**: already covered by project
  `AGENTS.md` + skills.

---

## Open / uncertain

- OpenSpec docs describe `validate` warnings (e.g. "missing 'Technical Approach'
  section") but the **exact built-in section-conformance ruleset isn't fully
  enumerated** in the docs; the precise lint rules would need a source read of
  the validator if R1 wants to mirror them closely.
- Whether OpenSpec's `verify` action does semantic (LLM-judged) verification vs
  structural checks is **not fully specified** in the docs (described only as
  "check implementation matches specs").
- Workspace/initiative/context-store features are explicitly **beta** and evolving;
  treated as out-of-scope for org-plan alignment.

## Sources

- OpenSpec repo + README — https://github.com/Fission-AI/OpenSpec (MIT, npm `@fission-ai/openspec`, Node ≥ 20.19)
- Concepts (specs, changes, artefacts, delta specs, schemas, archive, glossary) — https://github.com/Fission-AI/OpenSpec/blob/main/docs/concepts.md
- OPSX workflow (actions vs phases, dependency DAG, BLOCKED/READY/DONE, update-vs-new heuristic, config) — https://github.com/Fission-AI/OpenSpec/blob/main/docs/opsx.md
- CLI reference (`init`, `validate`, `archive`, `status`, `instructions`, `schema *`, `config`, workspaces) — https://github.com/Fission-AI/OpenSpec/blob/main/docs/cli.md
- Commands (slash commands / skills) — https://github.com/Fission-AI/OpenSpec/blob/main/docs/commands.md
- org-plan skill (compared against) — `/Users/cormacc/.agents/skills/org-plan/SKILL.md`

> Note: GitHub links target `main` (branch) rather than a pinned commit SHA;
> OpenSpec docs are actively evolving (OPSX + beta workspace features), so
> re-confirm against `main` before relying on specifics older than this report
> (2026-06-04).

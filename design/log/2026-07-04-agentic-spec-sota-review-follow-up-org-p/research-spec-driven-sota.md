# Research: How does the local org-tasks / org-plan spec approach compare to the mid-2026 SOTA for spec-driven agentic development?

## Answer

The local org-tasks/org-plan model and mainstream SOTA (GitHub Spec Kit, OpenSpec, Amazon Kiro) sit on **opposite ends of one axis**: SOTA tools treat the spec as a *generative source-of-truth authored before code* (spec → plan → tasks → implement, often with hard gates), while the local model treats specs as *living contracts already in the repo* (skills, AGENTS.md, schemas, code), coupled to work by a lightweight `#+SPEC:` nudge and reconciled at closure. The local approach is **ahead of SOTA on git-nativeness, tool-agnosticism, planning craft (premortem, anti-criteria, rejected-alternatives, closure pruning), and its "don't duplicate the contract" philosophy**, and **behind SOTA on spec→test coupling, machine-enforced drift gates, a constitution/steering equivalent, and tooling-verified discovery** (its discovery is an agent convention, not something `ot` crawls). The 2026-06-04 OpenSpec evaluation's core conclusions still hold — SOTA has *added polish and enforcement rhetoric* (drift gates, `analyze`/`converge`, one-step propose) but has **not** invalidated the decision to couple to existing contracts rather than rebuild a `specs/` corpus. The single genuinely new SOTA idea worth weighing is the **constitution/steering doc** (durable, always-in-context project principles), which the local model has no first-class equivalent for.

## The local model (crisp summary)

- **Task memory** in `TASKS.org`; each non-trivial task links a **change-record** in `design/log/*.org` via `#+IMPORT:`. The change-record is *both* the plan and the durable shipped record (Intent / Summary{Scope, Acceptance-ISC, Decisions, Shipped, Gotchas, Risks} / Plan / Implementation), with premortem, approach-exploration, effort line, and intent reverse-engineering as drafting practices.
- **One `#+SPEC:` keyword, two contexts**: in `TASKS.org` it declares repo-wide spec **discovery roots** (bare `[[proj:PATH]]` links); in a record it lists **task-relevant** specs. `#+NO_SPEC: true` opts out.
- **Discovery is a documented agent convention, not tooling**: default root `./design/SPEC.org`; implicit specs (root `README.*`, `AGENTS.md`, skills dir); rooted/transitive link-following with a cycle guard; MVP link syntaxes are org `[[file:]]`/`[[proj:]]` only. `ot` does **not** crawl links.
- **Enforcement is light** (nudge, not gate). `ot doctor` emits `spec-untouched` (advisory closeout nudge — declared spec unchanged in git), `spec-value-malformed`, `spec-path-dangling`. Actual impact is a human/agent closeout determination recorded in `** Shipped` as ADDED/MODIFIED/REMOVED. No hard gates, no CI blocking, no merge condition.
- **Philosophy**: specs are living contracts already in the repo (the skills *are* the specs here), never a separate mandated artifact. Single source of truth per contract — couple to the code/skill/schema that owns it; only fall back to `design/specs/` for prose-only contracts with no code home.

## SOTA landscape (mid-2026)

### GitHub Spec Kit — the reference SDD implementation

- **Model**: "power inversion" — the spec is the primary artifact, code is its regenerated expression. Workflow `/(speckit.)specify → plan → tasks → implement`, plus newer `/clarify`, `/analyze`, `/converge` commands. ([spec-driven.md](https://github.com/github/spec-kit/blob/main/spec-driven.md), [quickstart](https://github.github.io/spec-kit/index.html), docs last updated May 27 2026)
- **Artifacts**: per-feature directory `specs/[branch-name]/` holding `spec.md` (WHAT/WHY), `plan.md` (HOW), `tasks.md`, plus `research.md`, `data-model.md`, `contracts/`, `quickstart.md`. Feature-numbered, branch-per-feature, versioned in git.
- **Constitution**: `.specify/memory/constitution.md` — "immutable" architectural principles (nine articles; e.g. Library-First, CLI-Interface, Test-First, Simplicity/Anti-Abstraction gates). Articles IV–VI are project-defined. `/plan` enforces "Constitutional Compliance"; templates embed **Phase -1 pre-implementation gates** as checklists the LLM must pass or justify in a Complexity Tracking section. ([spec-driven.md](https://github.com/github/spec-kit/blob/main/spec-driven.md), [constitution-first guide](https://www.daita.io/en/blog/spec_kit_constitution_first_principles/))
- **Spec-as-tests**: explicit — "Acceptance scenarios become tests"; template mandates contract → integration → e2e → unit file-creation order (test-first).
- **Drift/evolution**: three documented persistence models — *Flow-Forward* (each feature dir is a historical record), *Living Spec* (`spec.md` is the contract, plan/tasks derived), *Flow-Back* (implementation discoveries reshape artifacts, then re-align). `/analyze` catches gaps across spec/plan/tasks *before* implement; `/converge` verifies completeness and appends remaining-gap tasks. ([evolving specs](https://github.github.com/spec-kit/guides/evolving-specs.html))
- **Enforcement**: gate-flavoured (constitutional gates, `analyze` consistency checks) but ultimately LLM-judged checklists, not CI-blocking by default. Widely noted as heavyweight/complex in practice ([Reddit r/GithubCopilot](https://www.reddit.com/r/GithubCopilot/comments/1o6iy7c/github_speckit_is_just_too_complex/), [Thoughtworks Radar vol.34](https://www.thoughtworks.com/content/dam/thoughtworks/documents/radar/2026/04/tr_technology_radar_vol_34_en.pdf)).
- **Adoption**: high — GitHub-backed, the de-facto reference; tool-integrations for Copilot, Claude, Cursor, pi, etc.

### OpenSpec — brownfield-first delta-spec model

- **Model**: two areas under `openspec/` — `specs/<domain>/spec.md` (durable behaviour contracts) and `changes/<name>/` (self-contained `proposal.md` + `design.md` + `tasks.md` + **delta specs**). ([conventions spec](https://github.com/Fission-AI/OpenSpec/blob/main/openspec/specs/openspec-conventions/spec.md))
- **Delta specs** are the signature idea: `## ADDED / MODIFIED / REMOVED Requirements`, mechanically **merged** into `specs/` on `archive`. Spec format is `## Purpose → ### Requirement:` (RFC-2119 SHALL/MUST) → `#### Scenario:` (Given/When/Then).
- **Lifecycle**: "actions not phases" — propose → create artifacts → apply → verify → archive; state (BLOCKED/READY/DONE) is filesystem+DAG derived. A `schema.yaml` declares artifacts + `requires:` edges (enablers, not phase gates).
- **CLI**: `init/update`, `list/view/show`, `validate [--strict|--json]` (structural lint + archive gate), `archive` (validate → merge deltas → dated archive). Never calls a model itself.
- **What's changed since 2026-06-04**: v1.4.1 (June 3 2026) added **profiles**, **one-step `propose`** (design+specs+tasks in one request), and two new agent integrations. TDD-check variants ("OpenSpec Plus") appeared in the community. ([CLIhub](https://clihub.org/cli/?slug=openspec), [CodeMySpec comparison](https://codemyspec.com/blog/codemyspec-vs-openspec)) — incremental, not structural; the delta-merge core is unchanged.
- **Adoption**: moderate, active; the most conceptually rigorous small-tool alternative to Spec Kit.

### Amazon Kiro — spec-driven agentic IDE

- **Artifacts**: per-feature `requirements.md` (EARS-style requirements), `design.md` (technical design), `tasks.md` (implementation breakdown). ([Kiro specs](https://kiro.dev/docs/web/specs/), [design-first](https://kiro.dev/docs/specs/feature-specs/tech-design-first/))
- **Steering docs** under `.kiro/steering/`: foundational `product.md`, `tech.md`, `structure.md`, **included in every interaction by default**, plus custom policy/domain files. This is Kiro's constitution-equivalent. ([steering](https://kiro.dev/docs/steering/), [web steering](https://kiro.dev/docs/web/steering/))
- **2026 direction**: pushing toward high-assurance / "aerospace spec standards" for AI coding (AWS Summit NY, June 2026). ([TechTimes](https://www.techtimes.com/articles/318546/20260617/aws-summit-new-york-2026-kiro-brings-aerospace-spec-standards-ai-coding.htm))
- **Adoption**: significant (AWS-backed IDE), but IDE-locked — least portable of the three.

### Agent context/memory files as spec surface

- **AGENTS.md**: emerging cross-tool convention — "a README for agents", plain Markdown, tool-agnostic. Closest thing to a shared standard; not a formal spec. ([agents.md](https://agents.md/))
- **Cursor rules**: standardized on `.cursor/rules/*.mdc` (MDC format, rule types Always/Auto-Attached/Agent-Requested/Manual; `alwaysApply: true`). `.cursorrules` deprecated and unreliable in agent mode. ([Cursor rules docs](https://docs.cursor.com/context/rules))
- **CLAUDE.md**: real Anthropic/Claude Code convention (project memory), no single formal public spec page.
- These function as **always-in-context steering**, distinct from per-feature specs. The industry position (2026): ADRs are decision memory ("why"); a living spec/rules layer is the source-of-truth ("what is true now"). ([Catio](https://www.catio.tech/blog/architecture-decision-record), [Specularis](https://specularis.org/), [Archgate](https://cli.archgate.dev/concepts/adrs/))

### Harness plan modes / skills / subagents

- **Claude Code plan mode**: read-only exploration; may delegate to a built-in Plan subagent. Planning output is **ephemeral session text or a fixed-name file, not a durable versioned artifact** by default. Subagent transcripts persist within-session only. ([sub-agents](https://code.claude.com/docs/en/sub-agents), [cheatsheet](https://support.claude.com/en/articles/14553413-claude-code-cheatsheet), [skills explained](https://claude.com/blog/skills-explained))
- This is exactly the gap the local model's "harness plan mode is scratch space; the org change-record is canonical" rule addresses. The local model is *ahead* here: it persists planning as durable, git-tracked memory where harness plan modes do not.

### Enforcement / drift-prevention patterns (the 2026 frontier)

- Clear 2026 trend: **spec-as-control-surface + CI-enforced conformance + drift detection + coverage metrics**. ([Augment Code](https://www.augmentcode.com/guides/ai-spec-driven-development-workflows), [Sonar Agent-Centric Dev Cycle, Mar 2026](https://www.sonarsource.com/company/press-releases/sonar-introduces-the-agent-centric-development-cycle/))
- **Spec-as-tests**: "if no test asserts the spec, it drifts" → contract testing (e.g. OpenAPI → CI) as the enforcement anchor. ([Spec Coding](https://spec-coding.dev/blog/contract-testing-plan-from-openapi-to-ci))
- **Academic framing**: "The Spec Growth Engine" (arXiv:2606.27045, Jun 2026) names the two failure modes — *context explosion* and *silent spec-code drift* — and proposes a machine-readable spec graph + a **drift gate that makes spec-code divergence a blocking merge condition**. ([arXiv:2606.27045](https://arxiv.org/abs/2606.27045))
- Reality check: much SOTA "enforcement" is still LLM-judged checklists (Spec Kit gates, `analyze`); genuinely *hard* gates (CI-blocking drift detection, contract tests) exist but are not yet universal. The strongest anti-drift lever remains **spec→executable-test coupling**, which the local model lacks.

## Comparison table

| Axis | Local (org-tasks/org-plan) | Spec Kit | OpenSpec | Kiro |
|---|---|---|---|---|
| Artifact model | Task + fused plan/record `.org`; specs are existing repo contracts | Per-feature spec.md/plan.md/tasks.md; spec generates code | Durable specs/ + delta-spec changes/ | Per-feature requirements/design/tasks + steering |
| Where specs live | Anywhere in repo (skills, AGENTS.md, schemas, code); `design/specs/` fallback | `specs/[feature]/` | `openspec/specs/` (+ change deltas) | `.kiro/specs/` + `.kiro/steering/` |
| Spec authoring order | Couple to *existing* contracts; new spec only for new domain | Spec authored first, code generated from it | Proposal/delta authored first, merged on archive | Requirements→design→tasks first |
| Plan/task coupling | Plan + record fused, UUID task graph, `:BLOCKED-BY:`, LOGBOOK | tasks.md derived from plan.md | tasks.md in change; DAG-derived state | tasks.md derived from spec |
| Discovery | Agent convention (roots + implicit + transitive links); `ot` does not crawl | Feature dirs enumerated by CLI | CLI list/view over openspec/ | IDE indexes specs/steering |
| Drift prevention | `ot doctor` `spec-untouched` nudge + closure reconciliation in `** Shipped` | `analyze`/`converge` + constitutional gates (LLM-judged) | `validate --strict` + delta-merge on archive | steering always-in-context; IDE checks |
| Hard gates | None (nudge-only) | Soft gates (checklists) | archive gate (structural validate) | Soft |
| Spec→test coupling | None (green suites are implicit, not spec-linked) | Explicit (acceptance→tests, test-first order) | Scenarios (Given/When/Then); TDD variants | EARS reqs; test guidance |
| Constitution/steering | None first-class (AGENTS.md is implicit spec, not always-in-context governance) | `constitution.md` (nine articles) | `schema.yaml` conventions | `.kiro/steering/*.md` always-on |
| Tool lock-in | None (Emacs/org + Babashka CLI, agent-agnostic) | Low (multi-agent), but heavy scaffolding | Low (npm CLI, agent-agnostic) | High (Kiro IDE) |
| Git-nativeness | Very high (plain org, git mv, `ot doctor` uses git status) | High (branch-per-feature) | High | Medium (IDE-mediated) |
| Human+agent ergonomics | High for org/Emacs users; planning craft is deepest | Medium (verbose, "eats context") | Medium-high (lean) | High in-IDE |
| Portability | High (text files, one CLI) | High | High | Low |
| Planning craft | Deepest: premortem, rejected-alternatives, anti-criteria, effort line, closure prune | Shallow (templates, checklists) | Shallow-moderate | Shallow |

## Ahead / behind analysis

**Where the local approach is ahead of SOTA:**

- **Git-native, tool-agnostic, single-CLI.** No IDE lock-in (vs Kiro), no heavy per-feature scaffolding (vs Spec Kit's "too complex" reputation). Plain org text + `ot`.
- **Living-contract philosophy / no duplication.** SOTA tools (Spec Kit, OpenSpec) *manufacture a new `specs/` corpus* that must be kept in sync with code — a fresh drift surface. The local model couples to the contract that already owns the truth (the skill, the schema), which is strictly less to drift. In this repo the skills literally *are* the specs.
- **Planning craft depth.** Premortem, approach-exploration with recorded rejected alternatives, anti-criteria, intent reverse-engineering, effort line, and closure-time pruning have **no equivalent in Spec Kit/OpenSpec/Kiro** (confirmed in the 2026-06-04 eval and still true).
- **Durable, unified plan+record.** The change-record is one resume surface that is both plan and shipped-record; harness plan modes (Claude Code) are ephemeral by comparison.
- **Task-graph semantics.** UUIDs, `:BLOCKED-BY:`, parent propagation, LOGBOOK, archive mechanics exceed what any of the three spec tools model.

**Where the local approach is behind or has gaps:**

- **No spec→test coupling.** This is the single biggest gap versus SOTA and the 2026 consensus ("if no test asserts the spec it drifts"). `** Acceptance` ISC criteria are human/LLM-verified at closure, never bound to executable tests. Spec Kit and OpenSpec both turn acceptance scenarios into tests.
- **No hard drift gate.** `spec-untouched` is a nudge; nothing blocks a merge/close when a declared spec rotted. The frontier (arXiv:2606.27045, Sonar, contract-testing-in-CI) is moving to *blocking* drift conditions. The local model deliberately chose nudge-not-gate — defensible, but it is now behind the enforcement curve.
- **Discovery is convention, not tooling.** `ot` does not crawl `#+SPEC:` links; transitive discovery relies on the agent reading files correctly. SOTA CLIs (Spec Kit, OpenSpec) enumerate/validate the spec set mechanically. Risk: silent under-discovery.
- **No constitution/steering equivalent.** All three SOTA tools have durable, always-in-context project principles (Spec Kit `constitution.md`, Kiro steering, OpenSpec schema conventions). The local model's AGENTS.md is an *implicit spec* but is not a first-class, gated governance artifact that plans are checked against.
- **Single-file org dependency + discoverability.** The whole model assumes org-mode + `ot` + (ideally) Emacs. Onboarding cost and non-org-user ergonomics are higher than Markdown-native tools. `#+SPEC:` uses `[[proj:PATH]]` org-link syntax that non-Emacs agents must be taught.
- **No delta-classified spec history.** OpenSpec's `## ADDED/MODIFIED/REMOVED` deltas give a machine-readable change history; the local `** Shipped` prefixes mirror this in prose but aren't mechanically merged or queryable.

## Prioritized recommendations

Framed to fit the repo's philosophy (git-native, tool-agnostic, nudge-not-gate, org-mode).

**HIGH**

1. **Add spec-as-acceptance-criteria linkage (optionally test-backed).** Close the biggest SOTA gap without abandoning nudge-not-gate: let a `** Acceptance` criterion optionally reference the spec clause it satisfies and/or the test that asserts it (e.g. an inline `→ test:` or `→ spec:[[proj:...]]` pointer). Even a convention + an `ot doctor` advisory ("acceptance criterion cites a spec but no test/anti-criterion evidence at closure") raises the drift floor. This is the highest-leverage, philosophy-compatible borrowing.
2. **Adopt a constitution/steering equivalent — but reuse AGENTS.md, don't invent a new file.** Promote the *already-implicit* `AGENTS.md` (and skills) to a named, always-considered "project constitution" role in org-plan's planning step: the premortem and `** Decisions` should be explicitly checked against it. This matches Kiro/Spec Kit's most valuable idea while honouring "single source of truth, don't duplicate."

**MEDIUM**

3. **Make discovery tooling-assisted (optional `ot spec list`).** The 2026-07-04 record already flagged this as a follow-up. A read-only `ot spec discover`/`spec list` that performs the documented rooted/transitive traversal (respecting the cycle guard and MVP link syntaxes) would turn a fallible agent convention into a verifiable command — without making `ot` a workflow engine. Keep it advisory (report, don't gate).
4. **Extend `ot doctor` drift detection from "path unchanged" toward "declared-but-stale".** Today `spec-untouched` only checks git touch. Consider a stronger advisory that flags declared specs whose *linked code changed but spec file did not* within the record's git scope — a lightweight local echo of the SOTA drift gate, still a warning.

**LOW**

5. **Consider Given/When/Then as an optional `** Edge cases` / `* Behavior` form.** Already noted as R6 in the 2026-06-04 eval; still low priority. Only worth it where behavioural contracts benefit from scenario rigour; keep optional to avoid ceremony.
6. **Broaden `#+SPEC:` link-syntax discovery to Markdown links / `#+INCLUDE:`** (the documented MVP boundary). Low priority until manual discovery proves error-prone; revisit alongside #3.

**Explicitly NOT worth adopting:**

- **A generative `specs/` corpus + delta-merge engine (Spec Kit/OpenSpec core).** Rebuilding a parallel spec corpus that must be regenerated/merged manufactures a new drift surface the "couple to existing contracts" philosophy specifically avoids. The 2026-06-04 eval already rejected the merge engine; still correct.
- **Hard CI merge gates on spec drift.** Contradicts the deliberate nudge-not-gate stance and the solo/small-team context; the advisory `ot doctor` path is the right ceiling. (Reassess only if a project's contract layer is high-assurance.)
- **Spec-first "code serves spec" inversion (Spec Kit's central claim).** For a repo whose deliverable *is* the contract (skills), the inversion is redundant; the contract and the code are already one artifact.
- **IDE-locked steering (Kiro model).** Violates tool-agnosticism.

## Does the 2026-06-04 OpenSpec evaluation still hold?

**Yes, substantially.** Its central decisions remain correct against current SOTA:
- *No merge engine; couple to the existing contract layer* — still the right call; SOTA's generative corpora are exactly the duplication cost it avoided. OpenSpec's June 2026 updates (one-step propose, profiles, TDD-check variants) are polish, not a reason to reverse.
- *Planning-time forward declaration + closure reconciliation* — validated; matches SOTA's proposal→implement ordering and the "declare before you build so the check can catch omission" insight (the same reasoning behind arXiv:2606.27045's drift gate).
- *Warnings-only enforcement* — a conscious divergence from the SOTA drift-gate trend, not a defect; the trade-off (raises the floor, doesn't guarantee completeness) was correctly documented.

**What has moved since:** (a) drift *gating* has hardened into a named academic/industry pattern (blocking merge conditions, contract-tests-in-CI, Sonar architecture drift) — worth acknowledging even while declining to adopt hard gates; (b) the **constitution/steering doc** has become a near-universal SOTA feature (Spec Kit, Kiro) and is the one idea the local model lacks a first-class answer for; (c) spec→test coupling is now the consensus anti-drift anchor, sharpening the local model's biggest gap.

## Verdict

The local org-tasks/org-plan model is a **credible, philosophically coherent, and in several respects more mature** approach than mainstream SOTA: it beats Spec Kit/OpenSpec/Kiro on git-nativeness, tool-agnosticism, planning craft, and its refusal to duplicate the contract layer, and it solves the durable-planning-memory problem that harness plan modes leave open. Where it trails SOTA is precisely where SOTA has invested most in 2026 — **binding specs to executable tests and enforcing drift** — and in lacking a first-class **constitution/steering** artifact. The right move is not to converge on Spec Kit's heavyweight generative model (which the 2026-06-04 eval correctly rejected and current SOTA users find overwrought), but to selectively borrow the two highest-value, philosophy-compatible ideas — **spec↔acceptance/test linkage** and **an AGENTS.md-as-constitution planning check** — while keeping the nudge-not-gate, contract-first, git-native core intact.

## Sources

Primary:
- GitHub Spec Kit — spec-driven methodology: https://github.com/github/spec-kit/blob/main/spec-driven.md
- Spec Kit docs (quickstart / workflows, updated May 27 2026): https://github.github.io/spec-kit/index.html · https://github.github.io/spec-kit/reference/workflows.html
- Spec Kit — evolving specs (Flow-Forward / Living / Flow-Back, analyze/converge): https://github.github.com/spec-kit/guides/evolving-specs.html
- OpenSpec conventions spec: https://github.com/Fission-AI/OpenSpec/blob/main/openspec/specs/openspec-conventions/spec.md
- OpenSpec v1.4.1 (Jun 3 2026, propose/profiles): https://clihub.org/cli/?slug=openspec
- Kiro specs / steering: https://kiro.dev/docs/web/specs/ · https://kiro.dev/docs/steering/ · https://kiro.dev/docs/specs/feature-specs/tech-design-first/
- AGENTS.md: https://agents.md/
- Cursor rules (.mdc): https://docs.cursor.com/context/rules
- Claude Code sub-agents / plan mode: https://code.claude.com/docs/en/sub-agents · https://support.claude.com/en/articles/14553413-claude-code-cheatsheet
- "The Spec Growth Engine" (silent spec-code drift, drift gate), arXiv:2606.27045, Jun 25 2026: https://arxiv.org/abs/2606.27045

Secondary / adoption signals:
- Sonar Agent-Centric Development Cycle (architectural drift detection, Mar 3 2026): https://www.sonarsource.com/company/press-releases/sonar-introduces-the-agent-centric-development-cycle/
- Augment Code — AI spec-driven workflows (specs as CI controls): https://www.augmentcode.com/guides/ai-spec-driven-development-workflows
- Spec Coding — contract testing OpenAPI→CI: https://spec-coding.dev/blog/contract-testing-plan-from-openapi-to-ci
- ADR-vs-living-spec for agents: https://www.catio.tech/blog/architecture-decision-record · https://specularis.org/ · https://cli.archgate.dev/concepts/adrs/
- Constitution-first Spec Kit: https://www.daita.io/en/blog/spec_kit_constitution_first_principles/
- Thoughtworks Technology Radar vol.34 (Apr 2026): https://www.thoughtworks.com/content/dam/thoughtworks/documents/radar/2026/04/tr_technology_radar_vol_34_en.pdf
- Kiro aerospace spec standards (AWS Summit NY, Jun 2026): https://www.techtimes.com/articles/318546/20260617/aws-summit-new-york-2026-kiro-brings-aerospace-spec-standards-ai-coding.htm

---
name: herdr-orch
description: "Delegate work to Herdr subagents with the in-skill `oh` CLI: spawn, fan out, or run a persona such as scout/researcher/planner/reviewer/worker/advisor/visual-tester/base-analyst/simplifier, then collect, validate, and close or continue it. `oh` also wraps raw pane/tab/workspace/agent control for orchestration. For direct Herdr use unrelated to delegation, prefer the `herdr` skill. Requires HERDR_ENV=1 or HC_TERMINAL_ID, subject to the ancestry and mismatch gate."
---

# Herdr subagents

Use the [Herdr skill](https://github.com/ogulcancelik/herdr/blob/master/skills/herdr/SKILL.md) safety rules. Delegation requires `HERDR_ENV=1` for the Herdr TUI runtime or `HC_TERMINAL_ID` for the herdr-app runtime. Variable presence alone does not select the runtime: the nearest host in process ancestry selects it, and a mismatch with the variables in use is refused. That skill's layout default (a sibling pane, and no new tab unless the user asks) applies to raw layout control only, not to delegation placement: the tab placement is the user's standing request, so a child that `oh` puts in its own tab obeys that rule. For the TUI runtime, `oh` requires Herdr 0.9.1 or newer on both the client and the running server; its preflight reads both from `herdr status --json` and refuses an older or unknown endpoint before allocating anything. For ordinary one-child delegation use `scripts/oh`. `<skill-dir>` below is the directory that holds this `SKILL.md`: `~/.agents/skills/herdr-orch` for a home install, or the installed package's `skills/herdr-orch` for a Pi package install. The canonical mechanical CLI, ledger, envelope, and exit-code contract is [`scripts/docs/contract.md`](scripts/docs/contract.md), with invocation and test entry points in [`scripts/README.md`](scripts/README.md). To install the Herdr TUI plugins or herdr-app mods, or when a surface reports `oh` or `ot` not installed, read [references/install-surfaces.md](references/install-surfaces.md).

```sh
OH="<skill-dir>/scripts/oh"
"$OH" task run scout --task 'Locate the implementation and report paths.'
"$OH" task start reviewer --task-file assignment.md
"$OH" task start --task 'Summarise the build failure.'   # persona-less fresh child
"$OH" task collect <full-task-uuid> --wait --timeout 600000
```

The CLI wraps opaque `--task`, `--task-file`, or stdin text with the persona, delegation, identity, publication, and optional retro instructions. `collect`, `status`, `prune`, `close`, and `continue` require the complete UUID that `run`/`start` emitted -- no prefix is ever resolved. Use `--prompt-extra` for exceptional constraints and `--print-prompt` to inspect the result. Do not reconstruct a raw prompt or result envelope during normal operation.

`oh` wraps raw TUI `pane`, `tab`, `ws` and `agent` control, plus `oh spawn "<shell command>"` for an ordinary command in a new unfocused tab, not delegation. Some agent operations use the selected runtime; raw TUI operations refuse on hc. Run `oh --help` for verbs. Before raw control, read [scripts/README.md#Raw passthrough](scripts/README.md#raw-passthrough) and apply upstream safety rules; pass flags explicitly when semantics matter because wrapper defaults differ.

An assignment never silently contradicts its persona's declared interaction model: a persona defined to work interactively (for example `planner`) keeps asking the user in its own pane. Question routing is an explicit choice the assignment states -- resolve interactively in-pane, or park questions for the parent -- and is independent of the parent's waiting policy, since every pane is interactive regardless of whether the parent blocks.

## Roster and routing

Read the selected `<name>.md` definition. Resolution is project `<git-root>/.agents/subagents/`, then home `~/.agents/subagents/`, then packaged `skills/herdr-orch/subagents/`; the first match wins. The packaged directory is not projected into the home override layer. See [scripts/docs/contract.md#Persona discovery](scripts/docs/contract.md#persona-discovery) for mechanics.

**Harness is a deployment property; `--model` is the only per-assignment dial.** Use the definition's `kind:`, else the parent harness observed through the selected runtime, never your assumed harness. Parent addresses and session identity are defined in [scripts/docs/contract.md#Preconditions](scripts/docs/contract.md#preconditions). Tiers are `heavy`, `middle`, `light` and `feather`; `--model light` requests a portable tier. A model request applies only to that spawn; later spawns use persona defaults unless requested again. Before authoring or debugging an override, read [scripts/docs/contract.md#Model resolution](scripts/docs/contract.md#model-resolution). Run resolution rather than predicting it: pass the model and spawn, or preview with `--print-prompt`. The `task start` envelope and ledger (`oh task status`) report translated `model-args` and `effort-args`; `task run` returns the capture, not the entry. Config validation precedes ledger or pane mutation. Invoke the resolver for kind, placement and `spawns:` too, rather than hand-simulating its decisions.

Pi routes vector aliases across models and accounts. An explicit `--model` or persona-default string alias or base canonical ID keeps that model and selects an eligible authenticated registered account using quota evidence; no eligible account refuses the launch before mutation. Both start and `--print-prompt` collect usage and report `model-route`. A named-account ID pins the account without routing; an inherited concrete selection and an unknown non-canonical name also stay unrouted. Native non-Pi kinds keep single-account behaviour. See [scripts/docs/contract.md#Routing](scripts/docs/contract.md#routing).

Unknown personas require listing the roster (`oh roster`) and asking, not improvising.

`oh roster --format markdown --section workflows` lists opted-in workflow skills; pi exposes `/oh workflows`. Membership neither runs a skill nor grants delegation authority. Before authoring or diagnosing catalogue discovery, read [scripts/docs/contract.md#Workflow skill discovery](scripts/docs/contract.md#workflow-skill-discovery).

Delegate only when the child saves more parent context than its assignment and result cost. Answer inline when you already know the answer or one search finds it. Do not give a `worker` a change whose files you cannot yet name: run a `scout` first, then pass the cited locations. For a broad localisation question, start two `scout` children with different angles (for example definitions and callers, or callers and tests) and combine their citations.

`base-analyst` is a directly usable, read-only analysis persona (default model `light`, a leaf by default): spawn it for an assessment that does not fit `scout`'s codebase-only remit or `researcher`'s external-facts remit. It also exists to be `extends:`-ed by a concrete analysis workflow (for example the `simplify` skill's persona) rather than repeating its assignment/evidence/uncertainty scaffold each time.

`simplifier` is that example: it extends `base-analyst` and applies the `simplify` skill to a declared range. It is not part of a `reviewer` run. When you want both correctness and over-engineering review, start one `reviewer` and one `simplifier` on the same range and collect both. Each keeps its own bar and result.

Before authoring traits or diagnosing persona composition, read [references/traits.md](references/traits.md) and [scripts/docs/contract.md#Trait composition](scripts/docs/contract.md#trait-composition). The contract owns inline `%<name>` substitution, frontmatter `traits: [name, ...]`, placement, repeat handling and failure-before-mutation rules.

A delegation request may name a trait with `#trait` shorthand ("Spawn a light worker. #simple"): read this as an instruction to issue an explicit, resolved `--trait NAME` (or frontmatter `traits: [...]`) on the actual spawn, not as text the CLI parses itself -- there is no hashtag parser. This is a different mechanism from the pi extension's `%trait` *interactive* expansion, which rewrites a parent session's own input text in place and never targets a child. [README.org#Shared directives: traits](README.org#shared-directives-traits) has the full routing/composition distinction.

Three packaged traits fit a type of assignment, not a persona, so no persona selects them by default. Add them on the spawn: `--trait investigate` for a debugging assignment to `worker` or a debugging consult to `advisor`; `--trait migration` for a schema, data, API, protocol, configuration, or dependency migration given to `worker` or `planner`; `--trait refactor` for a refactor given to `worker`. `scout` selects `compact`. Add `--trait compact` to another read-only child when you need only locations or counts from it.

For a debugging assignment using `investigate`, read [references/debugging.md](references/debugging.md) and give the child the reproduction evidence and diagnosis/fix boundary. The same recipe works inline; it does not require delegation or expand the trait's diagnosis/fix authority.

When shaping or reviewing a delegated assignment's own scope, apply the same engineering stance every `simple`-selecting persona loads rather than restating it here: [`traits/simple/prompt.md`](traits/simple/prompt.md).

Delegation capability is declared, not assumed: a persona may spawn only what its frontmatter `spawns:` allow-list grants (`planner` grants `scout researcher`; `worker` grants `scout researcher advisor`; every other persona is a leaf), and the value-bearing `--spawns` flag overrides the list for one spawn -- the literal `none` forces a leaf. Nesting is one level absolutely: anything spawned below the root is a leaf regardless of its frontmatter, and a below-root spawn stays blocking and one-at-a-time. `continue` is root-only, so a below-root child is never continued: its spawner captures its result and closes it. The CLI enforces the allow-list and the depth bound mechanically before any ledger or pane mutation.

## Executor tier and advisor strategy

`worker` is the single executor persona, parameterised by `--model`. Pick the tier with `--model` rather than reaching for a different persona. Its `advisor` is a consult-only, read-only grandchild that returns a verdict, a recommended approach, and pass/fail checks, and never spawns an advisor of its own.

**The advisor is opt-in, for a stuck worker only.** There is no routine pre-publish review. A worker consults on a debugging dead end after repeated failed attempts, or on a materially ambiguous high-stakes decision it cannot settle from source. Soft cap three consults. The advisor runs at its own `middle` default, and a caller or worker may raise a single high-stakes consult with `--model heavy`.

**Tier guidance:** prefer light for well-specified implementation. Do not use feather as a presumed economy for it: the benchmarked Haiku 4.5 predecessor performed worse; the Haiku 5.5 successor has not been benchmarked for this role. Reserve middle and above for established difficulty. Measurements and supersession history: [design.org#History](design.org#history).

The advisor-tier override is a convention, not a structured flag: instruct the worker (via `--prompt-extra`) to spawn its consult with `--model <tier>`, and require it to use `oh task run advisor` (see [README.org#Known gaps](README.org#known-gaps) for what a hand-rolled consult costs).

## Invocation policy

Choose explicitly:

- **Context:** hc supports fresh and fork delegation, locally only; measurement is TUI-only. `--context fresh` is the default; a request to spawn means fresh, and a request to fork means `--context fork`. Before a fork or measurement launch, read [references/context-modes.md#Launch modes](references/context-modes.md#launch-modes). Name a roster persona for a role, or omit it for a persona-less fresh child with the assignment, explicit traits and launcher guidance but no synthetic role. Persona-less children are leaves unless the root grants `--spawns`; below root only a named allow-listed persona may launch.
- **Waiting:** `run` blocks for one published item. `start` returns once the prompt is submitted, for later `collect`, or `collect --any` during fan-in. A wait timeout is non-final. Read its `child-status`, then collect again instead of concluding failure or respawning.
- **Placement:** each delegated child gets its own tab, including below root. `--tab` is optional; `--split` is available only for a measurement monitor. See [scripts/docs/contract.md#Placement](scripts/docs/contract.md#placement).
- **Cardinality:** keep at most N root children in flight (default 2). Fan in with `collect --any --wait`, then replace each captured child as capacity opens. A Pi parent receives each published item as a steered `herdr-orch-item` message from the extension watcher, on the TUI and on hc alike; a non-Pi parent receives the advisory push. Either is advisory: collect when steered, or on a timer, and never treat the message as the result. Below root, run one blocking leaf child at a time. Its spawner captures and closes it. Run one representative child before a fan-out that shares one design or benchmark premise.
- **Isolation:** use `--worktree <path>` for an existing checkout or `--worktree new` for a managed checkout. Managed checkouts are Git-locked; use `oh worktree remove` under its teardown guards. `oh worktree list --all --format json` lists every managed checkout under the assignment root with its state, without a caller session, for sidebars and housekeeping. A second concurrent write-enabled child gets a managed checkout unless `--worktree` names one; pass `--worktree <repo-root>` when siblings share a tree. Automatic isolation does not make overlapping edits compose safely. Keep sibling edit sets disjoint, name unavoidable overlap, and re-measure the combined parent tree after integration.
- **Integration:** treat a worktree publication's `CHECKPOINT` as the child's branch tip at that gate. Inspect reconciliation, resolve remaining dirt, and integrate explicitly. `oh` never merges, rebases, pushes, or deletes the branch.
- **Lifecycle:** capture closes nothing. After each capture, choose `close <full-task-uuid>` or `continue <full-task-uuid> --task '<next assignment>'`. Use `collect --close` when the close decision is already known. Whoever spawns a child closes it.

Continue only for follow-up that needs the child's existing context; respawn for unrelated work, a different persona or independent Class C validation. Before continuing, or preparing a child expected to continue, read [references/context-modes.md#Continuation](references/context-modes.md#continuation) for the context threshold and round-checkpoint rules.

Before allocating an isolated investigation server, read [references/workflow-recipes.md#Isolated Herdr investigations](references/workflow-recipes.md#isolated-herdr-investigations). Before staged delegation, independent regression-test authorship, bounded decision review, repeated measurements, evidence retention or the separately approved live context smoke, read the corresponding section in [references/workflow-recipes.md](references/workflow-recipes.md). These recipes grant no launch authority. They compose existing `oh` verbs instead of a workflow engine; add an engine only for a demonstrated need that those verbs cannot meet.

## Process retrospectives

Before overriding retro policy with `--retro`/`--no-retro` or consuming `PROCESS:` candidates, read [references/workflow-recipes.md#Process retrospectives](references/workflow-recipes.md#process-retrospectives).

Treat process candidates as testimony and scan input for your own retro. A child must not act on its own candidates: it does not choose a destination, write to instruction files, task systems, or any other durable store, or invoke whatever persistence tooling the installation provides. The parent owns verification, deduplication, approval, and persistence.

## Completion and pane safety

A validated terminal item in the parent-chosen `RESULT` stream is the only completion signal. Never use `agent read`, terminal history, prompt text, a visible final summary, or a `WAITING` item as completion. The child publishes a terminal result with the injected launcher:

```sh
"$HERDR_ORCH_BIN" task publish --status COMPLETE --summary 'Concise result.'
```

`BLOCKED` means a genuine resumable dependency. A decision that only the parent can make is such a dependency, for example a `worker` reporting that the correct change needs more scope than the assignment allows: decide the scope, then `continue` or respawn. It is not a failure. `FAILED` means the child could not recover after reasonable retries. Read the published summary before you re-prompt or respawn.

Before handling an invalid/unpublished result, using `poke`/`--abandon` or handling a failed spawn (including retry or cleanup), read [references/recovery.md](references/recovery.md). Never accept pane prose as completion or infer failure from a non-final timeout.

Capture before you close or continue. Never close user or other-agent panes, kill a parent, or stop or restart either runtime's server. Never run `hc update` or bypass a blocked prompt with `hc send --force`. Children outlive their parent in both runtimes; use guarded cleanup, not parent termination. Use `orphans` only for children whose owning parent session has ended. Read per-child outcomes from bulk close commands instead of trusting their aggregate exit status. Guard and reason details are in the [scripts/docs/contract.md#Close](scripts/docs/contract.md#close) and [scripts/docs/contract.md#Poke](scripts/docs/contract.md#poke).

Never bulk-delete the shared ledger. Use `prune` for your stale entries, `compact` for closed-round envelope bulk, and `harvest` for this session's process candidates.

Surface validated collect-time `artifact-links` before you discard child-pane context. Advisory publication links are context, not evidence that an artifact exists.

Use explicit IDs and response IDs, never focused UI state. Labels are display metadata and never replace unique agent names.

## Assignment premises

Name the base ref or commit scoped by a review or implementation assignment, measured with `git log`/`git status` at composition time.

A worker whose assignment forbids parent-status changes must pass `--no-promote` to `ot status` for its scoped tasks, including `--cycle` transitions. Default STARTED transitions promote TODO ancestors; the opt-out leaves them unchanged. The worker still manages only its assigned tasks, not parent closure.

A parent assignment must not assert an unmeasured, load-bearing premise -- a baseline, failure attribution, or suspected cause -- as settled fact. Either verify it before composing the assignment, or label it `unverified:` and instruct the child to establish it empirically before adopting that framing. Measurement of mutable state is a snapshot: after intervening edits or lifecycle/configuration changes, re-measure it before reusing it as an assignment premise, review exclusion, or causal frame. A baseline is measured on the pre-change tree: a figure measured after the change is not a baseline, and a suite that is green against it evidences no failures rather than preserved coverage. This is the reciprocal of Trusting a result below: that section bounds probes on claims flowing child to parent. This bounds claims flowing parent to child.

A tool's error text naming its own cause is such a premise. Reproduce the failure with and without the suspected flag before you attribute it to an account, a provider, or a plan limit.

## Trusting a result

Mechanical validation proves identity and envelope shape, never content truth. Weigh each claim by its consequence class.

**Class A -- transient.** A validated envelope needs no routine probe unless it conflicts with other evidence. Example: a scout's subsystem-behavior account used only to pick an in-flight approach.

**Class B -- persisted or load-bearing.** Probe up to 3 targeted checks per claim -- re-run a command, re-count a total, open a cited file -- before adopting it. Example: a claim persisted to a task, record, spec, commit message, or user report. A claim outside that budget, or genuinely uneconomical to probe, stays attributed to its source or flagged uncertain, never adopted as parent-verified fact. A failed probe stops reliance on the disputed claim. Escalate only as far as the disagreement requires, never into full re-ingestion. A figure you derive from a child's self-reported residual or proportion is a new claim, not a restatement of the child's: measure it yourself, or attribute it to the child.

**Class C -- destructive or closing.** A destructive action, feature closeout, or top-level lifecycle closure requires independent validation by the root itself or by a validator **spawned fresh for that closeout**. Never the implementing worker reaffirming its own result, and never a child continued from an earlier round of the same work: a child that already implemented or reviewed this change carries its own conclusions into the new round, which the mandatory revalidation clause in every continuation prompt mitigates but cannot remove. Independence here is a property of the spawn, not of the instruction. Example: marking a parent task DONE after a delegated implementation lands. A worker manages its own scoped plan tasks but never closes the parent task or prunes the final record.

For a Class C closeout, prefer two independent validators and expect disjoint findings. Give each validator a distinct lens, for example requirement conformance and correctness, rather than one shared assignment: two copies of one assignment tend to share blind spots. When the validators disagree about the same finding, settle it at the root against source or take it to the user; one validator's approval does not cancel the other's finding. (Adapted from the panel guidance in [rpiv-workflow](https://github.com/juicesharp/rpiv-mono/blob/46dbdfd78ab631bbf3ce41c2618bb173a5ec2725/packages/rpiv-workflow/docs/workflow-authoring.md#adversarial-verification-panel), MIT License.) Treat a lone APPROVED as unfalsified rather than validated.

When a child's finding contradicts existing curated documentation, the contradiction is itself the signal to probe, whichever way it points. Do not overwrite the document to match: a child's inference from filesystem layout or naming is weaker evidence than a curated statement, and "the doc is stale" is a conclusion to earn against the authoritative source rather than a premise.

Child self-verification helps in every class but is never independent validation -- only a probe or separate validator is. An identifier the parent uses in an edit, command, or persisted text is checked at point of use regardless of class. AGENTS.md's CRITICAL rule is unconditional and outside this probe budget.

## Manual fallback

If the launcher is unavailable, stop normal delegation. Before any manual fallback, read the [upstream Herdr skill](https://github.com/ogulcancelik/herdr/blob/master/skills/herdr/SKILL.md) and [scripts/docs/contract.md#Environment](scripts/docs/contract.md#environment) plus [#Ledger and completion](scripts/docs/contract.md#ledger-and-completion) for config, models, ledger/result allocation, injected `CHILD`/`TASK`/`RESULT` and the exact v1 envelope. A hand-driven publication without a ledger entry stays silent. Validate and capture before closing only your own pane. A `BLOCKED` result is resumable in its retained pane via `continue`.
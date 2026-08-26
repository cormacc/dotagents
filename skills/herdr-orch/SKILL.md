---
name: herdr-orch
description: "Delegate work to Herdr subagents with the in-skill `oh` CLI: spawn, fan out, or run a persona such as scout/researcher/planner/reviewer/worker/advisor/visual-tester, then collect, validate, and close or continue it. `oh` also wraps raw pane/tab/workspace/agent control for orchestration. For direct Herdr use unrelated to delegation, prefer the `herdr` skill. Requires HERDR_ENV=1."
---

# Herdr subagents

Use the [Herdr skill](https://github.com/ogulcancelik/herdr/blob/master/skills/herdr/SKILL.md) safety rules and verify `HERDR_ENV=1` before delegation. For ordinary one-child delegation use `scripts/oh`. The canonical mechanical CLI, ledger, envelope, and exit-code contract is [`scripts/docs/contract.md`](scripts/docs/contract.md), with invocation and test entry points in [`scripts/README.md`](scripts/README.md).

```sh
OH="$HOME/.agents/skills/herdr-orch/scripts/oh"
"$OH" task run scout --task 'Locate the implementation and report paths.'
"$OH" task start reviewer --task-file assignment.md
"$OH" task collect <full-task-uuid> --wait --timeout 600000
```

The CLI wraps opaque `--task`, `--task-file`, or stdin text with the persona, delegation, identity, publication, and optional retro instructions. `collect`, `status`, `prune`, `close`, and `continue` require the complete UUID that `run`/`start` emitted -- no prefix is ever resolved. Use `--prompt-extra` for exceptional constraints and `--print-prompt` to inspect the result. Do not reconstruct a raw prompt or result envelope during normal operation.

`oh` also wraps the raw Herdr `pane`, `tab`, `ws`, and `agent` verbs, plus `oh spawn "<shell command>"` for an ordinary command in a new tab (always unfocused; it never delegates), each returning the same JSON envelope. Run `oh --help` for the verb list. The upstream Herdr safety rules apply to those exactly as they do to direct `herdr` use. The wrapper imposes agent-facing defaults that differ from upstream -- pass the flags explicitly when upstream semantics matter ([scripts/README.md](scripts/README.md) § Raw passthrough).

An assignment never silently contradicts its persona's declared interaction model: a persona defined to work interactively (for example `planner`) keeps asking the user in its own pane. Question routing is an explicit choice the assignment states -- resolve interactively in-pane, or park questions for the parent -- and is independent of the parent's waiting policy, since every pane is interactive regardless of whether the parent blocks.

## Roster and routing

Definitions are `<name>.md` files discovered in descending precedence: `<git-root>/.agents/subagents/` (project override), then `~/.agents/subagents/` (home override), then the installed skill's `skills/herdr-orch/subagents/` (packaged default). The project copy wins. Read the selected definition. The packaged directory is never projected into `~/.agents/subagents/`, which holds only home-layer overrides.

**Harness is a deployment property. `--model` is the only per-assignment dial.** The child runs its definition's `kind:`, else the harness Herdr measured for the parent -- read that with `oh agent get "$HERDR_PANE_ID"`, never from the harness you believe you are. A `pi` parent read as `claude` declared a requested model unreachable. The four shipped weights are `heavy`, `middle`, `light`, and `feather`. `--model light` is the portable way to ask for a tier. The dial is per spawn and does not persist: a model the user named for one assignment does not carry into the next, so a later spawn takes the persona default unless the user names one again. Everything about how a weight translates lives in the [contract](scripts/docs/contract.md) § Model resolution -- read it when authoring or debugging an override. When delegating, *run* the resolution instead of predicting it: pass the model and spawn, or preview with `--print-prompt`. Config is validated before any ledger or pane mutation, so a wrong model fails loudly and costs nothing, while hand-simulating the alias hop and per-kind column lookup has produced a confident wrong answer, a refused spawn, and a routing question escalated to the user that the CLI would have settled. The same holds for every other mechanically-resolved choice -- kind, placement, the `spawns:` allow-list: invoke the resolver rather than reasoning about what it will decide.

Unknown personas require listing the roster and asking, not improvising.

A persona body may carry `%<name>` trait tokens, substituted from the trait store at spawn into a composed persona file that the child reads instead of the definition. Five shipped personas rely on tokens for directives whose prose was removed, so substitution is a boundary rather than decoration: an unresolved 3+ character token or a repeated one fails the spawn before any ledger or pane mutation. The store, admission rules, and fragment format are in [references/traits.md](references/traits.md). The mechanics are in [`scripts/docs/contract.md`](scripts/docs/contract.md) § Trait composition.

Delegation capability is declared, not assumed: a persona may spawn only what its frontmatter `spawns:` allow-list grants (`planner` grants `scout researcher`; `worker` grants `scout researcher advisor`; every other persona is a leaf), and the value-bearing `--spawns` flag overrides the list for one spawn -- the literal `none` forces a leaf. Nesting is one level absolutely: anything spawned below the root is a leaf regardless of its frontmatter, and a below-root spawn stays blocking and one-at-a-time. `continue` is root-only, so a below-root child is never continued: its spawner captures its result and closes it. The CLI enforces the allow-list and the depth bound mechanically before any ledger or pane mutation.

## Executor tier and advisor strategy

`worker` is the single executor persona, parameterised by `--model`. Pick the tier with `--model` rather than reaching for a different persona. Its `advisor` is a consult-only, read-only grandchild that returns a verdict, a recommended approach, and pass/fail checks, and never spawns an advisor of its own.

**The advisor is opt-in, for a stuck worker only.** There is no routine pre-publish review. A worker consults on a debugging dead end after repeated failed attempts, or on a materially ambiguous high-stakes decision it cannot settle from source. Soft cap three consults. The advisor runs at its own `middle` default, and a caller or worker may raise a single high-stakes consult with `--model heavy`.

**Tier guidance:** light is the efficient default for well-specified implementation work. Feather is a false economy for it -- benchmarked head to head it cost more, ran slower, burned more tokens for an identical score, and accounted for every delegation-protocol failure observed. Reserve middle and above for work whose difficulty is genuinely established rather than assumed. Measurements and supersession history are in [design.org](design.org) § History.

The advisor-tier override is a convention, not a structured flag: instruct the worker (via `--prompt-extra`) to spawn its consult with `--model <tier>`, and require it to use `oh task run advisor` (see [README.org](README.org) § Known gaps for what a hand-rolled consult costs).

## Invocation policy

Choose explicitly:

- **Waiting:** `run` blocks for one published item. `start` returns after dispatch for later `collect`, or `collect --any` during fan-in. A wait timeout is non-final. Read its `child-status`, then collect again instead of concluding failure or respawning.
- **Placement:** explicit `--tab` or `--split` overrides configured placement. The shipped `:tab-split` default gives a root child its own tab and splits a below-root child from its parent. See the [contract](scripts/docs/contract.md) § Placement for resolution details.
- **Cardinality:** keep at most N root children in flight (default 2). Fan in with `collect --any --wait`, then replace each captured child as capacity opens. Below root, run one blocking leaf child at a time. Its spawner captures and closes it. Run one representative child before a fan-out that shares one design or benchmark premise.
- **Isolation:** use `--worktree <path>` for an existing checkout or `--worktree new` for a managed checkout. Automatic isolation does not make overlapping edits compose safely. Keep sibling edit sets disjoint, name unavoidable overlap, and re-measure the combined parent tree after integration.
- **Integration:** treat a worktree publication's `CHECKPOINT` as the child's branch tip at that gate. Inspect reconciliation, resolve remaining dirt, and integrate explicitly. `oh` never merges, rebases, pushes, or deletes the branch.
- **Lifecycle:** capture closes nothing. After each capture, choose `close <full-task-uuid>` or `continue <full-task-uuid> --task '<next assignment>'`. Use `collect --close` when the close decision is already known. Whoever spawns a child closes it.

Continue when the follow-up depends on the child's existing context. Respawn for unrelated work, a different persona, or independent Class C validation. The CLI owns continuation preconditions and refuses unsafe reuse. See the [contract](scripts/docs/contract.md) § Continue.

## Process retrospectives

Use `--retro` or `--no-retro` only when overriding the persona policy for this spawn. Otherwise the CLI resolves persona frontmatter `retro:` and then its enabled default. `scout`, `researcher`, and `advisor` currently opt out. If no `retro` skill is installed, default/frontmatter enablement degrades to disabled. An explicit `--retro` fails fast.

A gated-in child applies steps 1--2 of [`retro`](../retro/SKILL.md), using that skill's threshold. Surviving one-line candidates arrive in the result's optional `PROCESS:` section and the ledger `:envelope`. No candidates is a valid result. The ledger's best-effort `:child-session` is the transcript reference for any manual follow-up after pane closure. Exact precedence, fields, limits, and section grammar belong to the [mechanical contract](scripts/docs/contract.md).

Treat process candidates as testimony and scan input for your own retro. A child must not act on its own candidates: it does not choose a destination, write to instruction files, task systems, or any other durable store, or invoke whatever persistence tooling the installation provides. The parent owns verification, deduplication, approval, and persistence.

## Completion and pane safety

A validated terminal item in the parent-chosen `RESULT` stream is the only completion signal. Never use `agent read`, terminal history, prompt text, a visible final summary, or a `WAITING` item as completion. The child publishes a terminal result with the injected launcher:

```sh
"$HERDR_ORCH_BIN" task publish --status COMPLETE --summary 'Concise result.'
```

`BLOCKED` means a genuine resumable dependency. `FAILED` means the child could not recover after reasonable retries. Read the published summary before you re-prompt or respawn.

For a settled child with no valid publication, use `oh task poke <full-task-uuid>` before you respawn. If `poke` reports `dispatch-unconfirmed`, read the pane because the work may not have started. If an `invalid` capture arrives while the child is still `working`, let it settle and collect again. If a settled child's `RESULT` contains prose, capture it as invalid, then use `poke`. Never adopt that prose as the result.

Capture before you close or continue. Never close user or other-agent panes, kill a parent, or stop the Herdr server. Use `orphans` only for children whose owning parent session has ended. Read per-child outcomes from bulk close commands instead of trusting their aggregate exit status. Guard and reason details are in the [contract](scripts/docs/contract.md) § Close and § Poke.

Never bulk-delete the shared ledger. Use `prune` for your stale entries, `compact` for closed-round envelope bulk, and `harvest` for this session's process candidates.

Surface validated collect-time `artifact-links` before you discard child-pane context. Advisory publication links are context, not evidence that an artifact exists.

Use explicit IDs and response IDs, never focused UI state. Labels are display metadata and never replace unique agent names.

## Assignment premises

Name the base ref or commit a review or implementation assignment is scoped against, measured with `git log`/`git status` at composition time. Asserting commit state in prose ("nothing is committed") mis-scopes the work when it is wrong, and the child has to re-derive the range anyway.

A parent assignment must not assert an unmeasured, load-bearing premise -- a baseline, failure attribution, or suspected cause -- as settled fact. Either verify it before composing the assignment, or label it `unverified:` and instruct the child to establish it empirically before adopting that framing. Measurement of mutable state is a snapshot: after intervening edits or lifecycle/configuration changes, re-measure it before reusing it as an assignment premise, review exclusion, or causal frame. This is the reciprocal of Trusting a result below: that section bounds probes on claims flowing child to parent. This bounds claims flowing parent to child.

A tool's error text naming its own cause is such a premise. Reproduce the failure with and without the suspected flag before you attribute it to an account, a provider, or a plan limit. A headless `pi --print` refusal whose message described third-party usage billing was caused by `--no-extensions` disabling the provider-auth extension. The misreading was recorded as a plan assumption and mis-scoped a full benchmark round before a later task disproved it.

## Trusting a result

Mechanical validation proves identity and envelope shape, never content truth. Weigh each claim by its consequence class.

**Class A -- transient.** A validated envelope needs no routine probe unless it conflicts with other evidence. Example: a scout's subsystem-behavior account used only to pick an in-flight approach.

**Class B -- persisted or load-bearing.** Probe up to 3 targeted checks per claim -- re-run a command, re-count a total, open a cited file -- before adopting it. Example: a claim persisted to a task, record, spec, commit message, or user report. A claim outside that budget, or genuinely uneconomical to probe, stays attributed to its source or flagged uncertain, never adopted as parent-verified fact. A failed probe stops reliance on the disputed claim. Escalate only as far as the disagreement requires, never into full re-ingestion. A figure you derive from a child's self-reported residual or proportion is a new claim, not a restatement of the child's: measure it yourself, or attribute it to the child. A density figure derived this way was persisted as measured and was wrong, because the child had reported a residual of about 15 words for a paragraph that measured 124.

**Class C -- destructive or closing.** A destructive action, feature closeout, or top-level lifecycle closure requires independent validation by the root itself or by a validator **spawned fresh for that closeout**. Never the implementing worker reaffirming its own result, and never a child continued from an earlier round of the same work: a child that already implemented or reviewed this change carries its own conclusions into the new round, which the mandatory revalidation clause in every continuation prompt mitigates but cannot remove. Independence here is a property of the spawn, not of the instruction. Example: marking a parent task DONE after a delegated implementation lands. A worker manages its own scoped plan tasks but never closes the parent task or prunes the final record.

One validator's approval is weak evidence on a subtle surface: two reviewers of this skill's own lifecycle rewrite returned opposite verdicts on an identical assignment (measurement in [`design/log/2026-08-06-herdr-orch-support-resident-reviewers-fo.org`](../../design/log/2026-08-06-herdr-orch-support-resident-reviewers-fo.org)). For a Class C closeout, prefer two independent validators and expect disjoint findings. Treat a lone APPROVED as unfalsified rather than validated. Measured again on the trait-composition closeout (2026-08-11): two reviewers on one assignment at different tiers overlapped on only 2 of 6 findings, and each uniquely caught something the other missed -- a trust-boundary bypass in one case, a test broken by a documentation edit in the other. One reviewer would have shipped whichever it missed.

When a child's finding contradicts existing curated documentation, the contradiction is itself the signal to probe, whichever way it points. Do not overwrite the document to match: a child's inference from filesystem layout or naming is weaker evidence than a curated statement, and "the doc is stale" is a conclusion to earn against the authoritative source rather than a premise. A subagent has derived a device-support matrix from a data folder listing, called the existing spec stale, and been wrong -- the folder held an extra development-only entry.

Child self-verification helps in every class but is never independent validation -- only a probe or separate validator is. An identifier the parent uses in an edit, command, or persisted text is checked at point of use regardless of class. AGENTS.md's CRITICAL rule is unconditional and outside this probe budget.

## Manual fallback

If the script is unavailable, follow the [upstream Herdr skill](https://github.com/ogulcancelik/herdr/blob/master/skills/herdr/SKILL.md) mechanically: the config chain, model translation, ledger and result-path allocation, the injected `CHILD`/`TASK`/`RESULT` environment, and the exact [v1 result envelope](scripts/docs/contract.md#ledger-and-completion) are all in contract.md § Environment and § Ledger and completion, which also records that a hand-driven publish with no ledger entry stays silent. Validate and capture before closing only the pane you created. A `BLOCKED` result is resumable in its retained pane. `continue` is the verb for that.

---
name: herdr-orch
description: "Delegate work to Herdr subagents with the in-skill `oh` CLI: spawn, fan out, or run a persona such as scout/researcher/planner/reviewer/worker/advisor/visual-tester, then collect, validate, and close or continue it. `oh` also wraps raw pane/tab/workspace/agent control for orchestration; for direct Herdr use unrelated to delegation, prefer the `herdr` skill. Requires HERDR_ENV=1."
---

# Herdr subagents

Use the [Herdr skill](https://github.com/ogulcancelik/herdr/blob/master/SKILL.md) safety rules and verify `HERDR_ENV=1` before delegation. For ordinary one-child delegation use `scripts/oh`; the canonical mechanical CLI, ledger, envelope, and exit-code contract is [`scripts/docs/contract.md`](scripts/docs/contract.md), with invocation and test entry points in [`scripts/README.md`](scripts/README.md).

```sh
OH="$HOME/.agents/skills/herdr-orch/scripts/oh"
"$OH" task run scout --task 'Locate the implementation and report paths.'
"$OH" task start reviewer --task-file assignment.md
"$OH" task collect <full-task-uuid> --wait --timeout 600000
```

The CLI wraps opaque `--task`, `--task-file`, or stdin text with the persona, delegation, identity, publication, and optional retro instructions. `collect`, `status`, `prune`, `close`, and `continue` require the complete UUID that `run`/`start` emitted -- no prefix is ever resolved. Use `--prompt-extra` for exceptional constraints and `--print-prompt` to inspect the result; do not reconstruct a raw prompt or result envelope during normal operation.

`oh` also wraps the raw Herdr `pane`, `tab`, `ws`, and `agent` verbs, plus `oh spawn "<shell command>"` for an ordinary command in a new tab (always unfocused; it never delegates), each returning the same JSON envelope; run `oh --help` for the verb list. The upstream Herdr safety rules apply to those exactly as they do to direct `herdr` use. The wrapper imposes agent-facing defaults that differ from upstream -- pass the flags explicitly when upstream semantics matter ([scripts/README.md](scripts/README.md) § Raw passthrough).

An assignment never silently contradicts its persona's declared interaction model: a persona defined to work interactively (for example `planner`) keeps asking the user in its own pane. Question routing is an explicit choice the assignment states -- resolve interactively in-pane, or park questions for the parent -- and is independent of the parent's waiting policy, since every pane is interactive regardless of whether the parent blocks.

## Roster and routing

Definitions are `<name>.md` files discovered in descending precedence: `<git-root>/.agents/subagents/` (project override), then `~/.agents/subagents/` (home override), then the installed skill's `skills/herdr-orch/subagents/` (packaged default). The project copy wins; read the selected definition. The packaged directory is never projected into `~/.agents/subagents/`, which holds only home-layer overrides.

**Harness is a deployment property; `--model` is the only per-assignment dial.** The child runs its definition's `kind:`, else the harness Herdr measured for the parent -- read that with `oh agent get "$HERDR_PANE_ID"`, never from the harness you believe you are. A `pi` parent read as `claude` declared a requested model unreachable. The four shipped weights are `heavy`, `middle`, `light`, and `feather`; `--model light` is the portable way to ask for a tier. Everything about how a weight translates lives in the [contract](scripts/docs/contract.md) § Model resolution -- read it when authoring or debugging an override. When delegating, *run* the resolution instead of predicting it: pass the model and spawn, or preview with `--print-prompt`. Config is validated before any ledger or pane mutation, so a wrong model fails loudly and costs nothing, while hand-simulating the alias hop and per-kind column lookup has produced a confident wrong answer, a refused spawn, and a routing question escalated to the user that the CLI would have settled. The same holds for every other mechanically-resolved choice -- kind, placement, the `spawns:` allow-list: invoke the resolver rather than reasoning about what it will decide.

Unknown personas require listing the roster and asking, not improvising.

A persona body may carry `%<name>` trait tokens, substituted from the trait store at spawn into a composed persona file that the child reads instead of the definition. Five shipped personas rely on tokens for directives whose prose was removed, so substitution is a boundary rather than decoration: an unresolved 3+ character token, a repeated one, or two tokens declaring each other incompatible all fail the spawn before any ledger or pane mutation. The store, admission rules, and fragment format are in [references/traits.md](references/traits.md); the mechanics are in [`scripts/docs/contract.md`](scripts/docs/contract.md) § Trait composition.

Delegation capability is declared, not assumed: a persona may spawn only what its frontmatter `spawns:` allow-list grants (`planner` grants `scout researcher`; `worker` grants `scout researcher advisor`; every other persona is a leaf), and the value-bearing `--spawns` flag overrides the list for one spawn -- the literal `none` forces a leaf. Nesting is one level absolutely: anything spawned below the root is a leaf regardless of its frontmatter, and a below-root spawn stays blocking and one-at-a-time. `continue` is root-only, so a below-root child is never continued: its spawner captures its result and closes it. The CLI enforces the allow-list and the depth bound mechanically before any ledger or pane mutation.

## Executor tier and advisor strategy

`worker` is the single executor persona, parameterised by `--model`; pick the tier with `--model` rather than reaching for a different persona. Its `advisor` is a consult-only, read-only grandchild that returns a verdict, a recommended approach, and pass/fail checks, and never spawns an advisor of its own.

**The advisor is opt-in, for a stuck worker only.** There is no routine pre-publish review. A worker consults on a debugging dead end after repeated failed attempts, or on a materially ambiguous high-stakes decision it cannot settle from source; soft cap three consults. The advisor runs at its own `middle` default, and a caller or worker may raise a single high-stakes consult with `--model heavy`.

**Tier guidance:** light is the efficient default for well-specified implementation work. Feather is a false economy for it -- benchmarked head to head it cost more, ran slower, burned more tokens for an identical score, and accounted for every delegation-protocol failure observed. Reserve middle and above for work whose difficulty is genuinely established rather than assumed. Measurements and supersession history are in [README.org](README.org) § History.

The advisor-tier override is a convention, not a structured flag: instruct the worker (via `--prompt-extra`) to spawn its consult with `--model <tier>`, and require it to use `oh task run advisor` (see [README.org](README.org) § Known gaps for what a hand-rolled consult costs).

## Invocation policy

Choose explicitly:

- **Waiting:** `run` is blocking; `start` plus later `collect` (or `collect --any` for fan-in) is non-blocking. A non-blocking child publishes a concise `WAITING` item at phase boundaries, at most once per `ORCH_WAITING_INTERVAL_MIN_MS` (default 60 s); it is toast-only, so the parent polls or collects when it needs the report. Terminal publish still pushes one advisory prompt to a settled, session-matching parent pane naming the `collect` command to run and the close-or-continue commands that follow it -- advisory only; a validated terminal result item (`RESULT`, below) remains the sole completion signal. A `run`/`collect --wait` timeout is non-final, not a failure: check `status <task>` to distinguish a child still legitimately working from one genuinely stalled, then continue with `collect <task> --wait` rather than concluding failure or respawning.
- **Placement:** explicit `--tab` or `--split` overrides configured `:defaults :placement`, which ships as `:tab-split` -- a root-level child gets its own tab (reviewable and recoverable when you spawn freely), a below-root child splits its parent's pane; `:split` and `:tab` force one geometry at every depth. Every other contract (env, label, ledger, collect, closure) is identical. Placement is never persisted per-child or inherited via env: a child's own spawns resolve from config and depth alone.
- **Cardinality:** the CLI handles one child per invocation. For many, a root parent keeps at most N (default 2) children in flight, fanning in with `collect --any --wait` and spawning a replacement child immediately on each capture. Below root, strictly one blocking child at a time, closed by its own spawner once captured; every below-root spawn is a leaf grandchild. Never child-to-child work. Each spawn resolves one checkout target: the initial child normally uses the caller's checkout, while an additional concurrent write-enabled sibling receives a managed checkout automatically; read-only personas do not cause implicit creation. Target decision and ledger reservation are atomic across CLI processes; `continue` uses the same final reservation after its slow settle, so it races safely with an explicit start for the inherited checkout. Potentially blocking assignment input and Herdr inspection finish before that critical section. Use `--worktree <path>` for an existing linked checkout or `--worktree new` to request a managed one explicitly; a read-only explicit target with tracked or untracked dirt refuses before allocation, while ignored paths alone remain legal. Isolation does not remove integration risk: keep sibling edit sets disjoint, name unavoidable overlap in each assignment so the worker re-read rule applies, and re-measure the combined parent tree after integration. Clear a killed spawn's stale ledger entry (uncaptured, no result, its child never reappearing in `agent list`) with `prune <full-task-uuid>`. When the children of a fan-out share one design -- a common assignment, harness, or benchmark -- run one representative child first as a validity gate and confirm the premise still holds before spawning the rest.
- **Integration:** a worktree publication's `CHECKPOINT` is the authoritative tip of the child's branch at that gate; inspect it together with reconciliation's committed, dirty, and ignored sets. The branch is the child deliverable, but merging, rebasing, pushing, or deleting it is the parent's consequential write -- `oh` does none of those. Reconciliation reports a gutted, corrupt, or wrong recorded checkout as `invalid` with no change sets rather than following Git discovery upward. Resolve any remaining dirt, integrate explicitly, and re-measure the combined parent tree; an isolated checkout never makes overlapping edits compose safely. A shared-root child emits no checkpoint and retains the existing shared-tree behaviour.
- **Lifecycle:** a child's pane persists until you act on it. Capture closes nothing, so after every capture make the decision explicitly: `close <full-task-uuid>` ends the child, or `continue <full-task-uuid> --task '<next assignment>'` gives it another round in the context it already holds. `collect --close` does both in one call when the close decision is already known at spawn. `close --settled` sweeps every settled, captured child of your own session in one call. The child is never told which you chose and never chooses for itself.

Whoever spawns a child closes it. `close` refuses an entry another session owns, and `close --settled` skips it, so a grandchild belongs to the child that spawned it.

Continue rather than respawn when the follow-up depends on context the child already built -- a re-review after fixes, a second pass over the same subsystem, or a `BLOCKED` round whose dependency you have now resolved. Respawn when the work is unrelated, when it needs a different persona (a round never changes one), or when independence matters more than context (see Class C below).

`continue` is root-only, and its preconditions are mechanical guards rather than advice: it refuses, mutating nothing, unless you own the entry, every published item was captured and the latest capture is valid (not `failed` or `invalid`; a fully captured `WAITING`-only round qualifies), no other round of that child is still open, it is that child's newest round, and the live agent's name **and pane ID** match the ledger in `idle`/`done`. A `working`, `blocked`, vanished, or pane-mismatched child is refused, not reused.

## Process retrospectives

Use `--retro` or `--no-retro` only when overriding the persona policy for this spawn. Otherwise the CLI resolves persona frontmatter `retro:` and then its enabled default. `scout`, `researcher`, and `advisor` currently opt out. If no `retro` skill is installed, default/frontmatter enablement degrades to disabled; an explicit `--retro` fails fast.

A gated-in child applies steps 1--2 of [`retro`](../retro/SKILL.md), using that skill's threshold. Surviving one-line candidates arrive in the result's optional `PROCESS:` section and the ledger `:envelope`; no candidates is a valid result. The ledger's best-effort `:child-session` is the transcript reference for any manual follow-up after pane closure. Exact precedence, fields, limits, and section grammar belong to the [mechanical contract](scripts/docs/contract.md).

Treat process candidates as testimony and scan input for your own retro. A child must not act on its own candidates: it does not choose a destination, write to instruction files, task systems, or any other durable store, or invoke whatever persistence tooling the installation provides. The parent owns verification, deduplication, approval, and persistence.

## Completion and pane safety

A validated terminal item in the parent-chosen `RESULT` stream is the only completion signal. Never treat `agent read`, terminal history, prompt text, or a visible final summary as completion. The child publishes exactly one terminal result with the injected launcher:

```sh
"$HERDR_ORCH_BIN" task publish --status COMPLETE --summary 'Concise result.'
```

A child that cannot finish is instructed to publish once with `BLOCKED` (genuine blocking dependency, resumable) or `FAILED` (unrecoverable after reasonable retries) carrying a partial account of completed vs remaining work -- read that summary before re-prompting or respawning.

A spawn-capable child cannot publish at all while it owns an open child round: the children-discharge guard refuses *any* publish, `WAITING` heartbeats included, until that round is captured and closed. So publish before spawning, or after capture and close -- an assignment that asks for phase-boundary heartbeats and delegation in the same phase is asking for something the CLI will refuse.

A child that publishes *nothing* is a separate case: its ledger entry stays `prompted` with no `RESULT` file even though the work may be finished. When such a child has settled, re-prompt it to publish with the injected launcher before considering a respawn -- respawning discards completed work and pays for it twice.

Before that, read the entry's `:dispatch`: every spawn verifies that its prompt actually left the child's composer (contract.md § Dispatch verification), so `unconfirmed` means the work may never have begun at all rather than finished silently -- read the pane instead of waiting out the timeout. For the same reason `:prompted-at` is the submission *attempt*, never a duration baseline; use `:dispatched-at`.

An `invalid` capture is not necessarily terminal either. A child that writes non-envelope content to its `RESULT` path mid-flight triggers validation failure while it is still working, and the parent then stops waiting for it. Before treating `invalid` as final, check the child's lifecycle state; if it is still `working`, let it settle and collect again, and treat anything already scored from that path as a mid-flight snapshot rather than a result.

A *settled* child whose `RESULT` holds prose is the mirror of publishing nothing: the work finished and only the publication failed, so re-prompt it to publish the envelope rather than adopting the prose or respawning. Assignment wording invites this failure -- a trailing section headed like a document request competes with the publication contract, and two children each given one wrote a Markdown report to the `RESULT` path instead of publishing. State reporting requirements as constraints on the published summary, never as a deliverable that reads like a file to write.

No capture closes a pane -- not `run`, `collect`, `collect --wait`, or `collect --any`, and not for any envelope status. `close <full-task-uuid>` is the only path that closes a child that actually started work. Never close user/other-agent panes, kill a parent, or stop the Herdr server. A different parent session may collect and validate an assignment but can neither close nor continue it -- that decision belongs to the owner.

`close` reports `closed` (pane taken), `retained` (a live occupant isn't settled -- retry), or `gone`; only `closed` means the pane is gone, and the reason codes and the released-name fallback are in contract.md § Close. Guard violations fail loudly and change nothing. `close --settled` returns one outcome per child and exits 0 even when some refuse -- read the array, not the exit code. Both `close` and `continue` first make one bounded `ORCH_SETTLE_CLOSE_MS` wait (default 45 s), because a child that has just published is usually still `working`.

A child whose owning parent session has ended cannot be closed through `close`: ownership is absolute. `orphans` lists such panes and `orphans --close` sweeps them; both are root-only and both refuse an unresolvable caller.

Never bulk-delete the ledger, and do not treat a retro or session end as a cue to tidy it: it is shared by every session using the same assignment root, so its entries are not yours to remove, and it is the only durable route to a finished child once the pane is gone. Retire your *own* entries with `prune` (a stale one) or `compact` (drops envelope bulk from a closed round, keeping the references a cited UUID resolves through), and read this session's process candidates with `harvest` rather than deleting what you do not want to read again (README.org § Known gaps covers another session's stale entry).

Surface the collected `artifact-links` to the user before relying on or discarding child-pane context: those Markdown `file://` links are the only durable route to a child's artifacts once you close its pane and transcript access is gone. Use the *validated* collect-time list, not the advisory list in the publication push -- publish checks path shape only, so an advisory link is context, never evidence that the file exists. Clickability depends on the harness and terminal; the absolute path in each label is always readable.

Use caller context or explicit IDs, and response IDs -- never focused UI state. Labels never contain a workspace name and never replace unique agent names. Nested labels depend on the spawning agent's injected `HERDR_ORCH_PERSONA`; a spawning persona started outside `oh` has no nested-label identity unless that variable is set.

## Assignment premises

Name the base ref or commit a review or implementation assignment is scoped against, measured with `git log`/`git status` at composition time. Asserting commit state in prose ("nothing is committed") mis-scopes the work when it is wrong, and the child has to re-derive the range anyway.

A parent assignment must not assert an unmeasured, load-bearing premise -- a baseline, failure attribution, or suspected cause -- as settled fact. Either verify it before composing the assignment, or label it `unverified:` and instruct the child to establish it empirically before adopting that framing. Measurement of mutable state is a snapshot: after intervening edits or lifecycle/configuration changes, re-measure it before reusing it as an assignment premise, review exclusion, or causal frame. This is the reciprocal of Trusting a result below: that section bounds probes on claims flowing child to parent; this bounds claims flowing parent to child.

## Trusting a result

Mechanical validation proves identity and envelope shape, never content truth. Weigh each claim by its consequence class.

**Class A -- transient.** A validated envelope needs no routine probe unless it conflicts with other evidence. Example: a scout's subsystem-behavior account used only to pick an in-flight approach.

**Class B -- persisted or load-bearing.** Probe up to 3 targeted checks per claim -- re-run a command, re-count a total, open a cited file -- before adopting it. Example: a claim persisted to a task, record, spec, commit message, or user report. A claim outside that budget, or genuinely uneconomical to probe, stays attributed to its source or flagged uncertain, never adopted as parent-verified fact. A failed probe stops reliance on the disputed claim; escalate only as far as the disagreement requires, never into full re-ingestion.

**Class C -- destructive or closing.** A destructive action, feature closeout, or top-level lifecycle closure requires independent validation by the root itself or by a validator **spawned fresh for that closeout**. Never the implementing worker reaffirming its own result, and never a child continued from an earlier round of the same work: a child that already implemented or reviewed this change carries its own conclusions into the new round, which the mandatory revalidation clause in every continuation prompt mitigates but cannot remove. Independence here is a property of the spawn, not of the instruction. Example: marking a parent task DONE after a delegated implementation lands. A worker manages its own scoped plan tasks but never closes the parent task or prunes the final record.

One validator's approval is weak evidence on a subtle surface: two reviewers of this skill's own lifecycle rewrite returned opposite verdicts on an identical assignment (measurement in [`design/log/2026-08-06-herdr-orch-support-resident-reviewers-fo.org`](../../design/log/2026-08-06-herdr-orch-support-resident-reviewers-fo.org)). For a Class C closeout, prefer two independent validators and expect disjoint findings; treat a lone APPROVED as unfalsified rather than validated. Measured again on the trait-composition closeout (2026-08-11): two reviewers on one assignment at different tiers overlapped on only 2 of 6 findings, and each uniquely caught something the other missed -- a trust-boundary bypass in one case, a test broken by a documentation edit in the other. One reviewer would have shipped whichever it missed.

When a child's finding contradicts existing curated documentation, the contradiction is itself the signal to probe, whichever way it points. Do not overwrite the document to match: a child's inference from filesystem layout or naming is weaker evidence than a curated statement, and "the doc is stale" is a conclusion to earn against the authoritative source rather than a premise. A subagent has derived a device-support matrix from a data folder listing, called the existing spec stale, and been wrong -- the folder held an extra development-only entry.

Child self-verification helps in every class but is never independent validation -- only a probe or separate validator is. An identifier the parent uses in an edit, command, or persisted text is checked at point of use regardless of class; AGENTS.md's CRITICAL rule is unconditional and outside this probe budget.

## Manual fallback

If the script is unavailable, follow the [upstream Herdr skill](https://github.com/ogulcancelik/herdr/blob/master/SKILL.md) mechanically: the config chain, model translation, ledger and result-path allocation, the injected `CHILD`/`TASK`/`RESULT` environment, and the exact [v1 result envelope](scripts/docs/contract.md#ledger-and-completion) are all in contract.md § Environment and § Ledger and completion, which also records that a hand-driven publish with no ledger entry stays silent. Validate and capture before closing only the pane you created. A `BLOCKED` result is resumable in its retained pane; `continue` is the verb for that.

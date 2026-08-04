---
name: herdr-orch
description: "Orchestrate Herdr terminals and subagents with the in-skill `oh` CLI: delegate work (spawn, fan out, or run a persona such as scout/researcher/planner/reviewer/worker/advisor/visual-tester) and control panes, tabs and workspaces (split, run a command, read output, wait, close). Requires HERDR_ENV=1."
---

# Herdr subagents

Use the [Herdr skill](https://github.com/ogulcancelik/herdr/blob/master/SKILL.md) safety rules and verify `HERDR_ENV=1` before delegation. For ordinary one-child delegation use `scripts/oh`; the canonical mechanical CLI, ledger, envelope, and exit-code contract is [`scripts/docs/contract.md`](scripts/docs/contract.md), with invocation and test entry points in [`scripts/README.md`](scripts/README.md).

```sh
OH="$HOME/.agents/skills/herdr-orch/scripts/oh"
"$OH" task run scout --task 'Locate the implementation and report paths.'
"$OH" task start reviewer --task-file assignment.md
"$OH" task collect <full-task-uuid> --wait --timeout 600000
```

The CLI wraps opaque `--task`, `--task-file`, or stdin text with the persona, delegation, identity, publication, and optional retro instructions. `collect`, `status`, and `prune` require the complete UUID that `run`/`start` emitted -- no prefix is ever resolved. Use `--prompt-extra` for exceptional constraints and `--print-prompt` to inspect the result; do not reconstruct a raw prompt or result envelope during normal operation.

`oh` also mirrors the raw Herdr `pane`, `tab`, `ws`, and `agent` verbs, plus `oh spawn "<shell command>"` for an ordinary command in a new unfocused tab, each returning the same JSON envelope; run `oh --help` for the verb list. The upstream Herdr safety rules apply to those exactly as they do to direct `herdr` use.

An assignment never silently contradicts its persona's declared interaction model: a persona defined to work interactively (for example `planner`) keeps asking the user in its own pane. Question routing is an explicit choice the assignment states -- resolve interactively in-pane, or park questions for the parent -- and is independent of the parent's waiting policy, since every pane is interactive regardless of whether the parent blocks.

## Roster and routing

Definitions are `<name>.md` files discovered in descending precedence: `<git-root>/.agents/subagents/` (project override), then `~/.agents/subagents/` (home override), then the installed skill's `skills/herdr-orch/subagents/` (packaged default). The project copy wins; read the selected definition. The packaged directory is never projected into `~/.agents/subagents/`, which holds only home-layer overrides.

**Resolve kind independently from model:** spawn request overrides definition, definition overrides parent kind, and a model name -- including a weight alias -- never selects a harness. Ask for a harness with `--kind` and a tier with `--model`; naming a model that happens to be one vendor's does not move the child to that vendor's CLI. The four shipped weights are `heavy`, `middle`, `light`, and `feather`; each is translated for the already-resolved kind through the `config.edn` chain, so `--model light` is the portable way to ask for a tier. Per-harness spellings, chain precedence, row-replacement semantics, floating-versus-pinned IDs, pass-through of unknown IDs, and `:extra-args` (including how an override relaxes a harness's interactive approval so unattended children do not stall) all live in the [contract](scripts/docs/contract.md) -- read it when authoring or debugging an override, not when delegating.

Unknown personas require listing the roster and asking, not improvising.

Delegation capability is declared, not assumed: a persona may spawn only what its frontmatter `spawns:` allow-list grants (`planner` grants `scout researcher`; `worker` grants `scout researcher advisor`; every other persona is a leaf), and the value-bearing `--spawns` flag overrides the list for one spawn -- the literal `none` forces a leaf. Nesting is one level absolutely: anything spawned below the root is a leaf regardless of its frontmatter, and a below-root spawn stays blocking, one-at-a-time, and ephemeral. The CLI enforces the allow-list and the depth bound mechanically before any ledger or pane mutation.

## Executor tier and advisor strategy

`worker` is the single executor persona, parameterised by `--model`; pick the tier with `--model` rather than reaching for a different persona. Its `advisor` is a consult-only, read-only grandchild that returns a verdict, a recommended approach, and pass/fail checks, and never spawns an advisor of its own.

**The advisor is opt-in, for a stuck worker only.** There is no routine pre-publish review. A worker consults on a debugging dead end after repeated failed attempts, or on a materially ambiguous high-stakes decision it cannot settle from source; soft cap three consults. The advisor runs at its own `middle` default, and a caller or worker may raise a single high-stakes consult with `--model heavy`.

**Tier guidance:** light is the efficient default for well-specified implementation work. Feather is a false economy for it -- benchmarked head to head it cost more, ran slower, burned more tokens for an identical score, and accounted for every delegation-protocol failure observed. Reserve middle and above for work whose difficulty is genuinely established rather than assumed. Measurements and supersession history are in [README.org](README.org) § History.

The advisor-tier override is a convention, not a structured flag: instruct the worker (via `--prompt-extra`) to spawn its consult with `--model <tier>`. That is verified to work -- but only when the worker actually uses `oh task run advisor`. A worker that hand-rolls a consult with raw `herdr agent start` silently inherits the default model, spends money that never appears in the ledger, and orphans the pane, so treat ledger consult counts and advisor costs as a floor rather than the truth.

## Invocation policy

Choose explicitly:

- **Waiting:** `run` is blocking; `start` plus later `collect` (or `collect --any` for fan-in) is non-blocking. A non-blocking child's publish also pushes one advisory prompt to a settled, session-matching parent pane naming the `collect` command to run -- advisory only; the validated `RESULT` file (below) remains the sole completion signal. A non-blocking, long-running child is also asked to report concise phase-boundary progress with `progress --summary`, throttled to at most once per `ORCH_PROGRESS_INTERVAL_MS` (default 60 s) and visible through `status`/`list` as one latest snapshot -- never draft findings, never a completion signal. Give review and implementation work an explicit `--timeout` rather than relying on the ten-minute default. A `run`/`collect --wait` timeout is non-final, not a failure: check `status <task>` to distinguish a child still legitimately working from one genuinely stalled, then continue with `collect <task> --wait` rather than concluding failure or respawning.
- **Placement:** explicit `--tab` or `--split` overrides configured `:defaults :placement`, which otherwise defaults to shipped `:split`; `:tab-split` resolves to tab at root and split below root. Tab placement creates the child in a new unfocused tab of the caller's workspace; every other contract (env, label, ledger, collect, closure) is identical. Placement is never persisted per-child or inherited via env: a child's own spawns resolve from config and depth alone.
- **Cardinality:** the CLI handles one child per invocation. For many, a root parent keeps at most N (default 2) children in flight -- a root-only privilege -- fanning in with `collect --any --wait` and spawning a replacement child immediately on each capture. Below root, strictly one blocking ephemeral child at a time; every below-root spawn is a leaf grandchild. Never child-to-child work. Children share one worktree, so concurrency is bounded by edit targets as well as by N: never fan out siblings whose file sets overlap -- sequence them, or merge them into one assignment -- and when overlap is unavoidable, name the concurrent sibling in each assignment so the re-read rule in `worker`'s engineering rules applies. A killed spawn's stale ledger entry (uncaptured, no result, its child never reappearing in `agent list`) is cleared with `prune <full-task-uuid>` rather than left to inflate `--any`'s candidate count indefinitely. When the children of a fan-out share one design -- a common assignment, harness, or benchmark -- run one representative child first as a validity gate and confirm the premise still holds before spawning the rest; a pilot that invalidates the design costs one child instead of N.
- **Lifecycle:** ephemeral by default. Residents are explicit opt-in only for correlated work and retain their spawn identity, pane, label, persona, and one active task.

Reuse a resident only after its live name **and pane ID** match the ledger, it is `idle`/`done`, and its previous result was validated and captured. `working`, `blocked`, `unknown`, missing, or mismatched residents are not reusable. Never change persona, accept concurrent work, or close an active resident.

## Process retrospectives

Use `--retro` or `--no-retro` only when overriding the persona policy for this spawn. Otherwise the CLI resolves persona frontmatter `retro:` and then its enabled default. `scout`, `researcher`, and `advisor` currently opt out. If no `retro` skill is installed, default/frontmatter enablement degrades to disabled; an explicit `--retro` fails fast.

A gated-in child applies steps 1--2 of [`retro`](../retro/SKILL.md), using that skill's threshold. Surviving one-line candidates arrive in the result's optional `PROCESS:` section and the ledger `:envelope`; no candidates is a valid result. The ledger's best-effort `:child-session` is the transcript reference for any manual follow-up after pane closure. Exact precedence, fields, limits, and section grammar belong to the [mechanical contract](scripts/docs/contract.md).

Treat process candidates as testimony and scan input for your own retro. The child must not choose a destination, load `self-improvement`, run `ot`, or edit instruction files; the parent owns verification, deduplication, approval, and persistence.

## Completion and pane safety

The validated parent-chosen `RESULT` file is the only completion signal. Never treat `agent read`, terminal history, prompt text, or a visible final summary as completion. The child publishes exactly once with the injected launcher:

```sh
"$HERDR_ORCH_BIN" task publish --status COMPLETE --summary 'Concise result.'
```

A child that cannot finish is instructed to publish once with `BLOCKED` (genuine blocking dependency, resumable) or `FAILED` (unrecoverable after reasonable retries) carrying a partial account of completed vs remaining work -- read that summary before re-prompting or respawning.

A child that publishes *nothing* is a separate case: its ledger entry stays `prompted` with no `RESULT` file even though the work may be finished. When such a child has settled, re-prompt it to publish with the injected launcher before considering a respawn -- respawning discards completed work and pays for it twice.

An `invalid` capture is not necessarily terminal either. A child that writes non-envelope content to its `RESULT` path mid-flight triggers validation failure while it is still working, and the parent then stops waiting for it. Before treating `invalid` as final, check the child's lifecycle state; if it is still `working`, let it settle and collect again, and treat anything already scored from that path as a mid-flight snapshot rather than a result.

`BLOCKED` retains its pane. `COMPLETE`/`FAILED` permit closing only a pane this parent created, after required artifacts are captured and Herdr reports the child settled. Never close user/other-agent panes, kill a parent, or stop the Herdr server. A different parent session may collect and validate an assignment but must retain its pane.

A `COMPLETE`/`FAILED` JSON `status` asserts a validated result, not that every side effect landed: check pane state (`herdr pane list`) rather than assuming closure. A `collect --any` capture now waits (bounded, `ORCH_SETTLE_CLOSE_MS`, default 45 s) for the captured child to settle before its one close attempt, but a child that never settles inside that budget still keeps its pane.

Surface the collected `artifact-links` to the user before relying on or discarding child-pane context: those Markdown `file://` links are the only durable route to a child's artifacts once `COMPLETE`/`FAILED` closed its pane automatically and transcript access is gone. Use the *validated* collect-time list, not the advisory list in the publication push -- publish checks path shape only, so an advisory link is context, never evidence that the file exists. Clickability depends on the harness and terminal; the absolute path in each label is always readable.

Use caller context or explicit IDs, `--no-focus`, and response IDs--not focused UI state. Labels never contain a workspace name and never replace unique agent names. Nested labels depend on the spawning agent's injected `HERDR_ORCH_PERSONA`; a spawning persona started outside `oh` has no nested-label identity unless that variable is set.

## Trusting a result

Validation proves identity, not correctness: a `COMPLETE` envelope means the right child answered the right assignment, never that its content is true. Before persisting a child's claim to a change-record, task, spec, or commit message--or acting destructively on it--confirm it against source. Re-count reported totals, re-run quoted commands, and inspect cited files. Resolve disagreement with a local probe rather than confidence or seniority.

## Manual fallback

If the script is unavailable, follow the [upstream Herdr skill](https://github.com/ogulcancelik/herdr/blob/master/SKILL.md) mechanically. Before prompting, read the `config.edn` chain (skill default ← home override ← project override) and translate the persona's model for the target kind, then create a parent ledger and absolute result path and inject `CHILD`, `TASK`, and `RESULT`. Have the child atomically publish the exact [v1 result envelope](scripts/docs/contract.md#ledger-and-completion), then validate and capture it before closing only the ephemeral pane you created. Retain blocked panes.

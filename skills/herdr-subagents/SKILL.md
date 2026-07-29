---
name: herdr-subagents
description: "Delegate work to subagents inside Herdr: use when asked to spawn, delegate, fan out, or run a scout/researcher/planner/reviewer/worker/skilled-worker/advisor. Requires HERDR_ENV=1; use the in-skill subagent CLI for single-child delegation and the Herdr skill for pane mechanics."
---

# Herdr subagents

Use the [Herdr skill](https://github.com/ogulcancelik/herdr/blob/master/SKILL.md) safety rules and verify `HERDR_ENV=1` before delegation. For ordinary one-child delegation use `scripts/subagent`; the canonical mechanical CLI, ledger, envelope, and exit-code contract is [`scripts/docs/contract.md`](scripts/docs/contract.md), with invocation and test entry points in [`scripts/README.md`](scripts/README.md).

```sh
SUBAGENT="$HOME/.agents/skills/herdr-subagents/scripts/subagent"
"$SUBAGENT" run scout --task 'Locate the implementation and report paths.'
"$SUBAGENT" start reviewer --task-file assignment.md
"$SUBAGENT" collect <task-id> --wait --timeout 600000
```

The CLI wraps opaque `--task`, `--task-file`, or stdin text with the persona, delegation, identity, publication, and optional retro instructions. Use `--prompt-extra` for exceptional constraints and `--print-prompt` to inspect the result; do not reconstruct a raw prompt or result envelope during normal operation.

## Roster and routing

Definitions are `<name>.md` files discovered in descending precedence: `<git-root>/.agents/subagents/` (project override), then `~/.agents/subagents/` (home override), then the installed skill's `skills/herdr-subagents/subagents/` (packaged default). The project copy wins; read the selected definition. The packaged directory is never projected into `~/.agents/subagents/`, which remains exclusively for genuine home overrides.

Resolve kind independently from model: spawn request overrides definition, definition overrides parent kind, and a model name—including a weight alias—never selects a harness. Roster `model:` values are translated only through the already-resolved kind via the separate roster.edn chain: skill default `skills/herdr-subagents/subagents/roster.edn` ← `~/.agents/subagents/roster.edn` ← `<git-root>/.agents/subagents/roster.edn` (project wins). This is row-level replacement, not definition shadowing: an override row completely replaces the same model ID's row and is never deep-merged. The shipped weights are:

| Weight | Pi | Claude | Codex |
|---|---|---|---|
| `heavy` | `anthropic/claude-fable-5` | `fable` | `gpt-5.6-sol` |
| `middle` | `anthropic/claude-opus-5` | `opus` | `gpt-5.6-sol` |
| `light` | `anthropic/claude-sonnet-5` | `sonnet` | `gpt-5.6-terra` |
| `feather` | `anthropic/claude-haiku-4-5` | `haiku` | `gpt-5.6-luna` |

The shipped `:pi` column supplies Pi's explicit provider-qualified model; tier-equivalent cross-provider mappings are confined to the `:claude` and `:codex` columns. Unversioned IDs (`claude-opus`, `gpt-sol`, …) are floating aliases for the latest version of that tier; versioned IDs pin a release. A model ID absent from the table passes through unchanged, and a definition model survives a kind override, translated for the resolved kind. Unknown personas require listing the roster and asking, not improvising.

Delegation capability is declared, not assumed: a persona may spawn only what its frontmatter `spawns:` allow-list grants (`planner` and `skilled-worker` grant `scout researcher`; `worker` grants `scout researcher advisor`; every other persona is a leaf), and the value-bearing `--spawns` flag overrides the list for one spawn — the literal `none` forces a leaf. Nesting is one level absolutely: anything spawned below the root is a leaf regardless of its frontmatter, and a below-root spawn stays blocking, one-at-a-time, and ephemeral. The CLI enforces the allow-list and the depth bound mechanically before any ledger or pane mutation.

## Advisor strategy (default worker)

The default `worker` is a cheap executor whose `advisor` is a consult-only frontier grandchild. It takes one mandatory focused pre-publish review consult, with optional escalation consults for judgment calls or debugging dead ends; soft cap: three consults. The caller selects an advisor tier with `--model` (for example, `--model claude-fable`); tier escalation is caller-driven—an advisor never spawns an advisor. Use `skilled-worker` for a frontier-model executor without the advisor loop. See the [change record](../../design/log/2026-07-29-subagent-implement-the-advisor-strategy.org) for rationale (which predates the rename: its “advised-worker” is today's `worker`, its “worker” today's `skilled-worker`).

## Invocation policy

Choose explicitly:

- **Waiting:** `run` is blocking; `start` plus later `collect` is non-blocking. Give review and implementation work an explicit `--timeout` rather than relying on the ten-minute default.
- **Placement:** default spawn splits the caller's pane. `--tab` instead creates the child in a new unfocused tab of the caller's workspace; every other contract (env, label, ledger, collect, closure) is identical. There is no inheritance: a tab-placed child's own spawns still split by default.
- **Cardinality:** the CLI handles one child. For many, direct parent fan-out in waves of at most two — a root-only privilege; below root, strictly one blocking child at a time. Never child-to-child work.
- **Lifecycle:** ephemeral by default. Residents are explicit opt-in only for correlated work and retain their spawn identity, pane, label, persona, and one active task.

Reuse a resident only after its live name **and pane ID** match the ledger, it is `idle`/`done`, and its previous result was validated and captured. `working`, `blocked`, `unknown`, missing, or mismatched residents are not reusable. Never change persona, accept concurrent work, or close an active resident.

## Process retrospectives

Use `--retro` or `--no-retro` only when overriding the persona policy for this spawn. Otherwise the CLI resolves persona frontmatter `retro:` and then its enabled default. `scout`, `researcher`, and `advisor` currently opt out. If no `retro` skill is installed, default/frontmatter enablement degrades to disabled; an explicit `--retro` fails fast.

A gated-in child applies steps 1–2 of [`retro`](../retro/SKILL.md), using that skill's threshold. Surviving one-line candidates arrive in the result's optional `PROCESS:` section and the ledger `:envelope`; no candidates is a valid result. The ledger's best-effort `:child-session` is the transcript reference for any manual follow-up after pane closure. Exact precedence, fields, limits, and section grammar belong to the [mechanical contract](scripts/docs/contract.md).

Treat process candidates as testimony and scan input for your own retro. The child must not choose a destination, load `self-improvement`, run `ot`, or edit instruction files; the parent owns verification, deduplication, approval, and persistence.

## Completion and pane safety

The validated parent-chosen `RESULT` file is the only completion signal. Never treat `agent read`, terminal history, prompt text, or a visible final summary as completion. The child publishes exactly once with the injected launcher:

```sh
"$HERDR_SUBAGENT_BIN" publish --status COMPLETE --summary 'Concise result.'
```

A child that cannot finish is instructed to publish once with `BLOCKED` (genuine blocking dependency, resumable) or `FAILED` (unrecoverable after reasonable retries) carrying a partial account of completed vs remaining work — read that summary before re-prompting or respawning.

`BLOCKED` retains its pane. `COMPLETE`/`FAILED` permit closing only a pane this parent created, after required artifacts are captured and Herdr reports the child settled. Never close user/other-agent panes, kill a parent, or stop the Herdr server. A different parent session may collect and validate an assignment but must retain its pane.

Use caller context or explicit IDs, `--no-focus`, and response IDs—not focused UI state. Labels never contain a workspace name and never replace unique agent names. Nested labels depend on the spawning agent's injected `HERDR_SUBAGENT_PERSONA`; a spawning persona started outside `subagent` has no nested-label identity unless that variable is set.

## Trusting a result

Validation proves identity, not correctness: a `COMPLETE` envelope means the right child answered the right assignment, never that its content is true. Before persisting a child's claim to a change-record, task, spec, or commit message—or acting destructively on it—confirm it against source. Re-count reported totals, re-run quoted commands, and inspect cited files. Resolve disagreement with a local probe rather than confidence or seniority.

## Manual fallback

If the script is unavailable, follow the [upstream Herdr skill](https://github.com/ogulcancelik/herdr/blob/master/SKILL.md) mechanically. Before prompting, read the roster.edn chain (skill default ← home override ← project override) and translate the persona's model for the target kind, then create a parent ledger and absolute result path and inject `CHILD`, `TASK`, and `RESULT`. Have the child atomically publish the exact [v1 result envelope](scripts/docs/contract.md#ledger-and-completion), then validate and capture it before closing only the ephemeral pane you created. Retain blocked panes.

---
name: herdr-subagents
description: "Delegate work to subagents inside Herdr: use when asked to spawn, delegate, fan out, or run a scout/researcher/planner/reviewer/worker. Requires HERDR_ENV=1; use the in-skill subagent CLI for single-child delegation and the Herdr skill for pane mechanics."
---

# Herdr subagents

Use the [Herdr skill](https://github.com/ogulcancelik/herdr/blob/master/SKILL.md) safety rules and verify `HERDR_ENV=1` before delegation. For ordinary one-child delegation use `scripts/subagent`; the canonical mechanical CLI, ledger, envelope, and exit-code contract is [`scripts/docs/contract.md`](scripts/docs/contract.md), with invocation and test entry points in [`scripts/README.md`](scripts/README.md).

```sh
SUBAGENT="$HOME/.agents/skills/herdr-subagents/scripts/subagent"
"$SUBAGENT" run scout --task 'Locate the implementation and report paths.'
"$SUBAGENT" start reviewer --task-file assignment.md
"$SUBAGENT" collect <task-id> --wait --timeout 600000
```

The CLI wraps opaque `--task`, `--task-file`, or stdin text with the persona, leaf, identity, publication, and optional retro instructions. Use `--prompt-extra` for exceptional constraints and `--print-prompt` to inspect the result; do not reconstruct a raw prompt or result envelope during normal operation.

## Roster and routing

Definitions are `<name>.md` files in `<git-root>/.agents/subagents/` then `~/.agents/subagents/`; the project copy wins. Read the selected definition. Its frontmatter `kind` and paired `model` guide routing: spawn request overrides definition, definition overrides parent kind; a definition model is dropped when its paired kind is overridden. Unknown personas require listing the roster and asking, not improvising.

A direct child is a leaf. The only exception is a `planner`, which may spawn one blocking ephemeral `scout` or `researcher` when a factual gap blocks planning. The specialist is still a leaf.

## Invocation policy

Choose explicitly:

- **Waiting:** `run` is blocking; `start` plus later `collect` is non-blocking. Give review and implementation work an explicit `--timeout` rather than relying on the ten-minute default.
- **Cardinality:** the CLI handles one child. For many, direct parent fan-out in waves of at most two; never child-to-child work.
- **Lifecycle:** ephemeral by default. Residents are explicit opt-in only for correlated work and retain their spawn identity, pane, label, persona, and one active task.

Reuse a resident only after its live name **and pane ID** match the ledger, it is `idle`/`done`, and its previous result was validated and captured. `working`, `blocked`, `unknown`, missing, or mismatched residents are not reusable. Never change persona, accept concurrent work, or close an active resident.

## Process retrospectives

Use `--retro` or `--no-retro` only when overriding the persona policy for this spawn. Otherwise the CLI resolves persona frontmatter `retro:` and then its enabled default. `scout` and `researcher` currently opt out. If no `retro` skill is installed, default/frontmatter enablement degrades to disabled; an explicit `--retro` fails fast.

A gated-in child applies steps 1–2 of [`retro`](../retro/SKILL.md), using that skill's threshold. Surviving one-line candidates arrive in the result's optional `PROCESS:` section and the ledger `:envelope`; no candidates is a valid result. The ledger's best-effort `:child-session` is the transcript reference for any manual follow-up after pane closure. Exact precedence, fields, limits, and section grammar belong to the [mechanical contract](scripts/docs/contract.md).

Treat process candidates as testimony and scan input for your own retro. The child must not choose a destination, load `self-improvement`, run `ot`, or edit instruction files; the parent owns verification, deduplication, approval, and persistence.

## Completion and pane safety

The validated parent-chosen `RESULT` file is the only completion signal. Never treat `agent read`, terminal history, prompt text, or a visible final summary as completion. The child publishes exactly once with the injected launcher:

```sh
"$HERDR_SUBAGENT_BIN" publish --status COMPLETE --summary 'Concise result.'
```

`BLOCKED` retains its pane. `COMPLETE`/`FAILED` permit closing only a pane this parent created, after required artifacts are captured and Herdr reports the child settled. Never close user/other-agent panes, kill a parent, or stop the Herdr server. A different parent session may collect and validate an assignment but must retain its pane.

Use caller context or explicit IDs, `--no-focus`, and response IDs—not focused UI state. Labels never contain a workspace name and never replace unique agent names. Nested planner labels depend on the spawning agent's injected `HERDR_SUBAGENT_PERSONA`; a planner started outside `subagent` has no nested-label identity unless that variable is set.

## Trusting a result

Validation proves identity, not correctness: a `COMPLETE` envelope means the right child answered the right assignment, never that its content is true. Before persisting a child's claim to a change-record, task, spec, or commit message—or acting destructively on it—confirm it against source. Re-count reported totals, re-run quoted commands, and inspect cited files. Resolve disagreement with a local probe rather than confidence or seniority.

## Manual fallback

If the script is unavailable, follow the [upstream Herdr skill](https://github.com/ogulcancelik/herdr/blob/master/SKILL.md) mechanically. Before prompting, create a parent ledger and absolute result path and inject `CHILD`, `TASK`, and `RESULT`. Have the child atomically publish the exact [v1 result envelope](scripts/docs/contract.md#ledger-and-completion), then validate and capture it before closing only the ephemeral pane you created. Retain blocked panes.

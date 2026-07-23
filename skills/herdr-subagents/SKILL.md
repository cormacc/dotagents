---
name: herdr-subagents
description: "Delegate work to subagents inside Herdr: use when asked to spawn, delegate, fan out, or run a scout/researcher/planner/reviewer/worker. Requires HERDR_ENV=1; use the in-skill subagent CLI for single-child delegation and the Herdr skill for pane mechanics."
---

# Herdr subagents

Use the [Herdr](../herdr/SKILL.md) safety rules and verify `HERDR_ENV=1` before delegation. The installed `herdr` CLI is authoritative. For ordinary one-child delegation use `scripts/subagent`; its exact command, JSON, ledger, envelope, label, geometry, and capability contracts live in [`scripts/README.md`](scripts/README.md) and [`scripts/docs/contract.md`](scripts/docs/contract.md).

```sh
SUBAGENT="$HOME/.agents/skills/herdr-subagents/scripts/subagent"
"$SUBAGENT" run scout --task 'Locate the implementation and report paths.'
"$SUBAGENT" start reviewer --task-file assignment.md
"$SUBAGENT" collect <task-id> --wait --timeout 600000
```

The CLI composes the invariant persona/leaf/publication prompt around opaque `--task`, `--task-file`, or stdin text. Use `--prompt-extra` for exceptional constraints and `--print-prompt` to inspect it; do not reconstruct a raw prompt or envelope in normal operation. It preflights Herdr 0.7.5 capabilities before mutation, uses vector argv, owns a per-assignment ledger, injects child identity through `pane split --env`, and closes only eligible panes it created.

## Roster and routing

Definitions are `<name>.md` files in `<git-root>/.agents/subagents/` then `~/.agents/subagents/`; the project copy wins. Read the selected definition. Its frontmatter `kind` and paired `model` guide routing: spawn request overrides definition, definition overrides parent kind; a definition model is dropped when its paired kind is overridden. Unknown personas require listing the roster and asking, not improvising.

A direct child is a leaf. The only exception is a `planner`, which may spawn one blocking ephemeral `scout` or `researcher` when a factual gap blocks planning. The specialist is still a leaf.

## Invocation dimensions

Choose explicitly:

- **Waiting:** `run` is blocking; `start` plus later `collect` is non-blocking.
- **Cardinality:** MVP CLI supports one child. For many, direct parent fan-out in waves of at most two; never child-to-child work.
- **Lifecycle:** ephemeral by default. Residents are explicit opt-in only for correlated work and retain their spawn identity, pane, label, persona, and one active task.

For a resident reuse only after its live name **and pane ID** match the ledger, it is `idle`/`done`, and the previous result was validated and captured. `working`, `blocked`, `unknown`, missing, or mismatched residents are not reusable. Never change persona, accept concurrent work, or close an active resident.

## Completion and pane safety

The validated parent-chosen `RESULT` file is the only completion signal. Never treat `agent read`, terminal history, prompt text, or a visible final summary as completion. The child publishes exactly once with:

```sh
"$HERDR_SUBAGENT_BIN" publish --status COMPLETE --summary 'Concise result.'
```

`publish` atomically creates the exact result path and notifications name the child/task/result for non-blocking success or publish failure. A parent validates markers, identity fields, and artifact existence before capture. `BLOCKED` retains its pane. `COMPLETE`/`FAILED` permit closing only a pane this parent created, after all required artifacts are captured and Herdr reports the child settled. Never close user/other-agent panes, kill a parent, or stop the Herdr server.

Use `--no-focus`, caller context or explicit IDs, and response IDs—not focused UI state. Root labels are `<persona>-<index>[-<model>]`; the planner exception nests `planner-<n>/...`. Nesting is driven by the spawning agent's injected `HERDR_SUBAGENT_PERSONA`, so a planner started by hand rather than through `subagent` silently produces root labels — set that variable, or accept flat labels. Labels never contain a workspace name and never replace unique agent names.

A parent closes only panes its own session created. `collect` run by a different session, or from a pane whose identity cannot be resolved, still captures and validates the result but retains the pane.

## Trusting a result

Validation proves *identity*, not correctness: a `COMPLETE` envelope means the right child answered the right assignment, never that its content is true. Treat findings, counts, diagnoses, and "verified" claims as testimony. Before persisting a child's claim to durable memory—a change-record, task, spec, or commit message—or acting destructively on it, confirm it against the source yourself. Cheap checks are usually enough: re-count what a report summarises, re-run a command it quotes, read the file it cites.

When children disagree, settle it with a local probe rather than adjudicating by confidence or seniority; both may assert verification and one still be wrong, or both be right under conditions neither stated.

## Manual fallback

When the script is unavailable, follow the vendored Herdr skill mechanically, create a parent ledger/result path before prompting, and inject `CHILD`, `TASK`, and `RESULT`. The child writes this exact envelope to a sibling temporary file and atomically publishes it without replacing an existing target:

```text
--- HERDR RESULT v1 ---
CHILD: <unique live agent name>
TASK: <parent-assigned id>
RESULT: <parent-assigned absolute path>
STATUS: COMPLETE | BLOCKED | FAILED
SUMMARY: <one to three concise sentences>
ARTIFACTS:
- <absolute path — purpose, or none>
FINDINGS:
- <at most five actionable one-line items, or none>
NEXT: <one required parent/user action, or none>
--- END HERDR RESULT ---
```

Retain blocked panes for follow-up. For completion/failed work, capture and validate the file before closing only the ephemeral pane you created.

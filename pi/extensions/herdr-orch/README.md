# herdr-orch pi adapter

Exposes the `herdr-orch` skill's `oh` CLI to pi. The extension invokes the co-installed launcher at `~/.agents/skills/herdr-orch/scripts/oh`, or `$HERDR_ORCH_BIN` when set. It has no alternate implementation: each call runs `oh` and reads its `herdr-orch/v1` JSON envelope.

## Tools

- `herdr_layout` -- workspace, tab, and pane topology (`oh ws|tab|pane ...`).
- `herdr_pane` -- raw pane control for ordinary commands (`oh pane ...`).
- `herdr_agent` -- coding-agent lifecycle (`oh agent ...`). `prompt` waits on the same call by default.

The tools proxy Herdr control only. Persona delegation uses `oh task ...` as the skill describes.

Always-loaded prompt guidelines retain the explicit Herdr-request boundary and pane ownership protection. Direct control loads [../../../skills/herdr/SKILL.md](../../../skills/herdr/SKILL.md) for procedures; persona delegation loads [../../../skills/herdr-orch/SKILL.md](../../../skills/herdr-orch/SKILL.md) for its placement contract. Tool descriptions retain their API contracts instead of repeating procedures as prompt guidelines.

## Slash command

`/oh [help|personas|traits|models|workflows]` shows the output of `oh roster --format markdown` to the user. Without an argument it shows every section, the Herdr status of the pane, the spawn defaults, and short usage notes. The notes route natural language to a context: "spawn a subagent" is a fresh context, and "fork a subagent" is a forked context (`oh task run|start --context fork`). A measurement runs headless (`--context measurement`), with a read-only monitor pane only on `--tab` or `--split`.

- `oh` owns the rendering. The same renderer generates the org listings in [../../../skills/herdr-orch/README.org](../../../skills/herdr-orch/README.org), so this extension adds only the Herdr status line and the usage notes (`help.ts`).

- The output is a session entry (`appendEntry` with the `herdr-orch-help` type), so it never enters model context. The skill already gives the model this information.
- In the TUI, `/oh` runs `oh` twice, with and without `--detail`, and stores both renderings in the entry. The tool-output expand key (default `ctrl+o`) switches to the detail rendering: it adds file paths, timeouts, retro gating, every model alias, and the resolution layers.
- RPC mode shows the detail rendering as a notification. JSON and print modes do nothing.
- `oh roster` runs in the session cwd, so the project layer is the git root of that cwd. Persona, trait, and model listings therefore show what a spawn would use. The `traits` extension expands a project-layer `%name` only in a trusted project, so an untrusted project's trait can appear in `/oh traits` and still not expand interactively.
- `/oh workflows` lists ordinary skills whose YAML frontmatter contains `metadata: {herdr-orch: workflow}`. It reuses each skill's name and description; expanded output adds its source and path. This is catalogue membership, not execution or permission to delegate. The CLI owns skill-root discovery and shadowing ([../../../skills/herdr-orch/scripts/docs/contract.md#Workflow skill discovery](../../../skills/herdr-orch/scripts/docs/contract.md#workflow-skill-discovery)).
- The output contract is in [../../../skills/herdr-orch/scripts/docs/contract.md#Roster](../../../skills/herdr-orch/scripts/docs/contract.md#roster).
- Root-owned staging, repeated measurements and the approval-gated context smoke are in [../../../skills/herdr-orch/references/workflow-recipes.md](../../../skills/herdr-orch/references/workflow-recipes.md).

## Dependencies

Babashka and the co-installed dotagents skills tree. `/oh` does not need Herdr: `oh roster` never calls it.

## Keybindings

None.

## Tests

`./test.sh` runs `index.test.ts` (pi-to-`oh` argv, `/oh` behaviour, and rendering), `pi-integration.test.ts` (installed Pi RPC commands through the real CLI, including workflow discovery without a model turn), and `../test/herdr-agent-state.test.ts` with the repository's locked `tsx`.

Test tooling: [../test/README.md](../test/README.md).

## Compatibility review

Last review: 2026-10-29, against pi 1.4.0.

- Herdr 0.9.5: the `herdr_agent` and `herdr_layout` tools worked live in a pi 1.4.0 session.
- Babashka 1.13.228 runs `oh`.
- Checks: `test.sh`, type-check against the pi 1.4.0 declarations, and `pi --mode rpc` load with empty stderr and `/oh` registered.

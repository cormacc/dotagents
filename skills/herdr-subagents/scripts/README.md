# `subagent` — Herdr delegation CLI

`subagent` is the harness-agnostic executable behind the `herdr-subagents` skill. It performs the mechanical single-child, ephemeral delegation protocol while the skill retains delegation and safety policy.

## Run

From the repository root (the repo `bb.edn` provides the `test` task; `bb test` fails from `scripts/`):

```sh
./skills/herdr-subagents/scripts/subagent --help
bb run subagent --help
bb test
```

The launcher canonicalises its own path with `cd -P` (the deployed `~/.agents/skills` is a *directory* symlink), uses the repository `bb.edn` when present, and falls back to `bb --deps-root <scripts> -Sdeps '{:paths ["src"]}'` for a bare skill subtree. It never `cd`s before `exec`, so the CLI's working directory is always the caller's — that value becomes the child pane's `--cwd` and drives assignment-root/roster resolution. It has no additional Maven dependencies.

`run` and `start` take opaque assignment text from exactly one of `--task`, `--task-file`, or stdin. `--prompt-extra` appends exceptional constraints; `--print-prompt` previews the invariant wrapper. The CLI never offers raw prompt mode.

The value-less flags `--retro` and `--no-retro` override process-retro gating for one spawn. See [docs/contract.md](docs/contract.md) § Retro gating for precedence, optional-skill behavior, ledger fields, and the `PROCESS:` envelope grammar.

The value-bearing `--spawns` flag overrides the persona's frontmatter `spawns:` allow-list for one spawn (whitespace/comma separated); the literal `none` forces a leaf, and below the root only `--spawns none` is accepted. See [docs/contract.md](docs/contract.md) § Spawn gating for precedence, fail-fast cases, depth enforcement, and ledger fields.

The value-less flag `--tab` places the child in a new unfocused tab of the caller's workspace instead of a split. Every other spawn contract (env, label, ledger, collect, closure) is unchanged, and there is no inheritance: a tab-placed child's own spawns still split by default. See [docs/contract.md](docs/contract.md) § Placement.

```sh
SUBAGENT="$HOME/.agents/skills/herdr-subagents/scripts/subagent"
"$SUBAGENT" run scout --task 'Find the relevant source files.' --timeout 600000
"$SUBAGENT" start reviewer --task-file assignment.md
"$SUBAGENT" collect <task-id> --wait --timeout 600000
"$SUBAGENT" status <task-id>
```

A child calls the injected absolute launcher path:

```sh
"$HERDR_SUBAGENT_BIN" publish --status COMPLETE --summary 'Implemented and tested.' \
  --process 'documented flag rejected → guardrail → verify flags against source before use'
```

`--process` is repeatable, and `--from-file` accepts the same list as a `"process"` array.

## Tests and smoke

`bb test` runs unit and fake-process coverage without launching an agent, entirely inside per-test temporary directories (`SUBAGENT_ASSIGNMENT_ROOT`); it must not touch the live `<git-root>/.agents/tmp/herdr-subagents/` tree. The separate `bb smoke-subagent` is intentionally guarded and requires `HERDR_ENV=1`, `SUBAGENT_LIVE_SMOKE=1`, and `SUBAGENT_LIVE_SMOKE_MODEL`; it is never CI work. Maintainer rationale and smoke coverage notes live in [../README.org](../README.org); output and file contracts live in [docs/contract.md](docs/contract.md).

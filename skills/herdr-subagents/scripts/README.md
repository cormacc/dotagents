# `subagent` — Herdr delegation CLI

`subagent` is the harness-agnostic executable behind the `herdr-subagents` skill. It performs the mechanical single-child, ephemeral delegation protocol while the skill retains delegation and safety policy.

## Run

From this checkout:

```sh
./skills/herdr-subagents/scripts/subagent --help
bb run subagent --help
bb test
```

The launcher canonicalises its own path with `cd -P` (the deployed `~/.agents/skills` is a *directory* symlink), uses the repository `bb.edn` when present, and falls back to `bb --deps-root <scripts> -Sdeps '{:paths ["src"]}'` for a bare skill subtree. It never `cd`s before `exec`, so the CLI's working directory is always the caller's — that value becomes the child pane's `--cwd` and drives assignment-root/roster resolution. It has no additional Maven dependencies.

`run` and `start` take opaque assignment text from exactly one of `--task`, `--task-file`, or stdin. `--prompt-extra` appends exceptional constraints; `--print-prompt` previews the invariant wrapper. The CLI never offers raw prompt mode.

```sh
SUBAGENT="$HOME/.agents/skills/herdr-subagents/scripts/subagent"
"$SUBAGENT" run scout --task 'Find the relevant source files.' --timeout 600000
"$SUBAGENT" start reviewer --task-file assignment.md
"$SUBAGENT" collect <task-id> --wait --timeout 600000
"$SUBAGENT" status <task-id>
```

A child calls the injected absolute launcher path:

```sh
"$HERDR_SUBAGENT_BIN" publish --status COMPLETE --summary 'Implemented and tested.'
```

## Tests and smoke

`bb test` runs unit and fake-process coverage without launching an agent, entirely inside per-test temporary directories (`SUBAGENT_ASSIGNMENT_ROOT`); it never reads or writes the live `<git-root>/.agents/tmp/herdr-subagents/` tree. The separate `bb smoke-subagent` is intentionally guarded and requires `HERDR_ENV=1`, `SUBAGENT_LIVE_SMOKE=1`, and `SUBAGENT_LIVE_SMOKE_MODEL`; it is never CI work. See [docs/contract.md](docs/contract.md) for output and file contracts.

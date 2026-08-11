# `oh` -- Herdr delegation CLI

`oh` (the executable formerly named `subagent`; `bb run subagent` remains its Babashka task alias) is the harness-agnostic executable behind the `herdr-orch` skill. It performs the mechanical single-child delegation protocol -- spawn, capture, then an explicit `close` or `continue` -- while the skill retains delegation and safety policy.

## Run

From the repository root (the repo `bb.edn` provides the `test` task; `bb test` fails from `scripts/`):

```sh
./skills/herdr-orch/scripts/oh --help          # global command list
./skills/herdr-orch/scripts/oh agent --help    # every signature in one group
./skills/herdr-orch/scripts/oh agent prompt --help  # one command, with its positional arity
bb run subagent --help
printf '%s' 'Review this %focused' | ./skills/herdr-orch/scripts/traits --layer home="$HOME/.agents/traits"
bb traits --layer home="$HOME/.agents/traits" --plain < prompt.md
bb test
```

The `traits` launcher and root `bb.edn` task expose the shared interpolator to non-Clojure callers. Input comes from stdin or `--file`; repeat `--layer SOURCE=DIR` in precedence order; use `--plain` for transformed text rather than the default `herdr-orch/v1` JSON envelope. Unknowns and repeats are report data, not CLI failures. The full output and failure contract is in [docs/contract.md](docs/contract.md) section Standalone trait interpolator CLI.

The launcher canonicalises its own path with `cd -P` (the deployed `~/.agents/skills` is a *directory* symlink), uses the repository `bb.edn` when present, and falls back to `bb --deps-root <scripts> -Sdeps '{:paths ["src"]}'` for a bare skill subtree. It never `cd`s before `exec`, so the CLI's working directory is always the caller's -- that value becomes the child pane's `--cwd` and drives assignment-root/roster resolution. It has no additional Maven dependencies.

`task run` and `task start` take opaque assignment text from exactly one of `--task`, `--task-file`, or stdin; `task collect`, `task status`, `task prune`, `task close`, and `task continue` in turn require the complete task UUID that `task run`/`task start` emitted; no prefix is ever resolved. `--prompt-extra` appends exceptional constraints; `--print-prompt` previews the invariant wrapper. The CLI never offers raw prompt mode.

Flag and verb index; [docs/contract.md](docs/contract.md) owns precedence, guards, refusal cases, and ledger fields:

| Flag / verb | Meaning | Detail |
|---|---|---|
| `--retro` / `--no-retro` | override retro gating for one spawn | § Retro gating |
| `--spawns NAMES` / `none` | override the persona's allow-list; `none` forces a leaf | § Spawn gating |
| `--tab` / `--split` | force placement (mutually exclusive) | § Placement |
| `--focus` / `--no-focus` | force focus on the placement call; inert below root | § Focus |
| `--any` (on `collect`) | capture the first same-session child to publish | § Fan-in |
| `--close` (on `collect`) | capture, then run the guarded close | § Close |
| `--notify-timeout MS` | settle wait before the advisory parent push | § Parent push |
| `--task UUID` (on `publish`) | publish a continued round | § Ledger and completion |
| `task prune` | retire one stale, same-session, uncaptured entry | § Pruning |
| `task close` / `--settled` | the only path that closes a spawned child's pane | § Close |
| `task continue` | root-only further round in the same pane | § Continue |

A `WAITING` item is not completion -- only a validated terminal result item is.

```sh
OH="$HOME/.agents/skills/herdr-orch/scripts/oh"
"$OH" task run scout --task 'Find the relevant source files.' --timeout 600000
"$OH" task start reviewer --task-file assignment.md
"$OH" task collect <full-task-uuid> --wait --timeout 600000
"$OH" task collect --any --wait --timeout 600000
"$OH" task status <full-task-uuid>
"$OH" task continue <full-task-uuid> --task 'Re-review after the fixes.' --wait
"$OH" task close <full-task-uuid>
"$OH" task close --settled
```

A child calls the injected absolute launcher path:

```sh
"$HERDR_ORCH_BIN" task publish --status COMPLETE --summary 'Implemented and tested.' \
  --process 'documented flag rejected → guardrail → verify flags against source before use'
```

`--process` is repeatable, and `--from-file` accepts the same list as a `"process"` array.

Each `--artifact` is also surfaced as a portable Markdown link, `[absolute path](file:///encoded/path) — purpose`: advisory (declared, unvalidated) in the parent push a non-blocking publish sends, and existence-validated as `result.artifact-links` on a successful `collect` / `collect --any`. That is fallback *syntax* only -- the URI is built with `Path.toUri` and no terminal-control escape is ever emitted, so whether it renders as a clickable hyperlink depends on the parent's harness and terminal support. See [docs/contract.md](docs/contract.md) § Artifact links.

## Raw passthrough

`oh` also wraps the raw Herdr `pane`, `tab`, `ws`, and `agent` verbs. The wrapper is not a transparent mirror: it imposes agent-facing defaults that differ from upstream, so pass the flags explicitly when upstream semantics matter. `oh agent wait` defaults to a 600 s timeout where `herdr` waits indefinitely, and the read family defaults to `--source recent-unwrapped` (upstream: `recent`), falls back to `visible` on empty output, and truncates to 2000 lines / 50 KB. `oh spawn "<shell command>"` runs an ordinary command in a new tab, always unfocused because it never delegates.

## Tests and smoke

`bb test` runs unit and fake-process coverage without launching an agent, entirely inside per-test temporary directories (`ORCH_ASSIGNMENT_ROOT`); it must not touch the live `<git-root>/.tmp/herdr-orch/` tree. When probing root-CLI behaviour manually from inside a delegated session, unset `HERDR_ORCH_PERSONA` and `HERDR_ORCH_SPAWNS` first -- the injected identity marks every spawn below-root ([docs/contract.md](docs/contract.md) § Spawn gating). The separate `bb smoke-subagent` is intentionally guarded and requires `HERDR_ENV=1`, `ORCH_LIVE_SMOKE=1`, and `ORCH_LIVE_SMOKE_MODEL`; it is never CI work. Maintainer rationale and smoke coverage notes live in [../README.org](../README.org); output and file contracts live in [docs/contract.md](docs/contract.md).

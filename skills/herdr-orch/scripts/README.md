# `oh` -- Herdr delegation CLI

`oh` (the executable formerly named `subagent`; `bb run subagent` remains its Babashka task alias) is the harness-agnostic executable behind the `herdr-orch` skill. It performs the mechanical single-child delegation protocol -- spawn, capture, then an explicit `close` or `continue` -- while the skill retains delegation and safety policy.

## Run

From the repository root (the repo `bb.edn` provides the `test` task; `bb test` fails from `scripts/`):

```sh
./skills/herdr-orch/scripts/oh --help          # global command list
./skills/herdr-orch/scripts/oh agent --help    # every signature in one group
./skills/herdr-orch/scripts/oh agent prompt --help  # one command, with its positional arity
bb run subagent --help
bb test
```

The launcher canonicalises its own path with `cd -P` (the deployed `~/.agents/skills` is a *directory* symlink), uses the repository `bb.edn` when present, and falls back to `bb --deps-root <scripts> -Sdeps '{:paths ["src"]}'` for a bare skill subtree. It never `cd`s before `exec`, so the CLI's working directory is always the caller's -- that value becomes the child pane's `--cwd` and drives assignment-root/roster resolution. It has no additional Maven dependencies.

`task run` and `task start` take opaque assignment text from exactly one of `--task`, `--task-file`, or stdin; `task collect`, `task status`, `task prune`, `task close`, and `task continue` in turn require the complete task UUID that `task run`/`task start` emitted; no prefix is ever resolved. `--prompt-extra` appends exceptional constraints; `--print-prompt` previews the invariant wrapper. The CLI never offers raw prompt mode.

The value-less flags `--retro` and `--no-retro` override process-retro gating for one spawn. See [docs/contract.md](docs/contract.md) § Retro gating for precedence, optional-skill behavior, ledger fields, and the `PROCESS:` envelope grammar.

The value-bearing `--spawns` flag overrides the persona's frontmatter `spawns:` allow-list for one spawn (whitespace/comma separated); the literal `none` forces a leaf, and below the root only `--spawns none` is accepted. See [docs/contract.md](docs/contract.md) § Spawn gating for precedence, fail-fast cases, depth enforcement, and ledger fields.

The value-less flags `--tab` and `--split` explicitly select tab or split placement and are mutually exclusive. See [docs/contract.md](docs/contract.md) § Placement for precedence, the `:tab-split` resolution, and what tab placement does and does not change.

The value-less flags `--focus` and `--no-focus` explicitly select whether that placement call also moves the UI to the new child, overriding configured `:defaults :focus` (shipped `true`); a below-root spawn never focuses regardless of either. A **root** `close` returns focus to the caller's own pane once it actually closes a child -- `collect --close` inherits this -- and `close --settled`/`orphans --close` do it once after the whole sweep, never once per child; a below-root `close` never returns focus, under the same depth gate the spawn side uses, and `publish` never touches focus at all. See [docs/contract.md](docs/contract.md) § Focus for precedence, the depth gate, and the return-hook rationale.

The value-less flag `--any` on `collect` takes no task argument; it captures the *first* in-flight child of the caller's own session to publish a valid result, instead of waiting on one named task -- the read/capture primitive behind bounded-concurrency fan-out. See [docs/contract.md](docs/contract.md) § Fan-in for candidacy, poll structure, and outcomes.

The value-bearing `--notify-timeout` flag on `publish` bounds the settle wait before the advisory parent push under the non-blocking waiting policy -- which is read from the publishing round's ledger entry, never from the environment (default 30000 ms). See [docs/contract.md](docs/contract.md) § Parent push for the push gates and outcome table.

`publish` and `progress` both accept `--task <full-task-uuid>` to override the injected `HERDR_ORCH_TASK`, which is how a continued child publishes the round it is actually working on; the named entry's `:child` must match the injected `HERDR_ORCH_CHILD`. The value-bearing `--summary` flag on the child-only `progress` command stores one latest, throttled advisory snapshot (`ORCH_PROGRESS_INTERVAL_MS`, default 60000 ms) under the ledger entry, visible through `status`/`list`; it is never a second transcript and never a completion signal. See [docs/contract.md](docs/contract.md) § Progress for identity validation, throttling, and rejection cases.

`oh task prune <full-task-uuid>` retires exactly one stale, same-session assignment orphaned by a killed `task run`/`task start`: it requires ownership of the exact ledger entry and proof the entry is uncaptured, non-terminal, result-less, and absent from one `agent list` call before marking it `failed` with `:pruned-at`/`:prune-reason` metadata, so `collect --any` stops counting it as a candidate; it never scans the ledger, ages out a candidate, or resolves a prefix. See [docs/contract.md](docs/contract.md) § Pruning for the ownership check and staleness proof.

`oh task close <full-task-uuid>` is the only path that closes a *spawned* child's pane -- no capture does, and the only other closure anywhere is spawn-failure cleanup taking a pane the child never worked in -- and acts only on an owned, captured entry that is its child's newest round, and only on a live `agent list` observation matching both the recorded child name and its pane id in `idle`/`done`; `oh task close --settled` sweeps the caller's own captured children, newest round per child, at most one attempt each, returning a per-child outcome array. `oh task continue <full-task-uuid> --task TEXT [--wait]` is the root-only reuse verb: it allocates a fresh task and result for a further round in the same pane and context, writing a new ledger entry with `:continues`. See [docs/contract.md](docs/contract.md) § Close and § Continue for the guards, outcomes, and refusal cases.

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

## Tests and smoke

`bb test` runs unit and fake-process coverage without launching an agent, entirely inside per-test temporary directories (`ORCH_ASSIGNMENT_ROOT`); it must not touch the live `<git-root>/.tmp/herdr-orch/` tree. When probing root-CLI behaviour manually from inside a delegated session, unset `HERDR_ORCH_PERSONA` and `HERDR_ORCH_SPAWNS` first -- the injected identity marks every spawn below-root ([docs/contract.md](docs/contract.md) § Spawn gating). The separate `bb smoke-subagent` is intentionally guarded and requires `HERDR_ENV=1`, `ORCH_LIVE_SMOKE=1`, and `ORCH_LIVE_SMOKE_MODEL`; it is never CI work. Maintainer rationale and smoke coverage notes live in [../README.org](../README.org); output and file contracts live in [docs/contract.md](docs/contract.md).

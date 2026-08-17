# `oh` -- Herdr delegation CLI

`oh` is the harness-agnostic executable behind the `herdr-orch` skill. It performs the mechanical single-child delegation protocol -- spawn, capture, then an explicit `close` or `continue` -- while the skill retains delegation and safety policy.

## Run

Run the CLI from the repository root or this scripts directory:

```sh
./skills/herdr-orch/scripts/oh --help          # global command list
./skills/herdr-orch/scripts/oh agent --help    # every signature in one group
./skills/herdr-orch/scripts/oh agent prompt --help  # one command, with its positional arity
printf '%s' 'Review this %focused' | ./skills/herdr-orch/scripts/traits --layer home="$HOME/.agents/traits"
bb traits --layer home="$HOME/.agents/traits" --plain < prompt.md
```

The `traits` launcher and root `bb.edn` task expose the shared interpolator to non-Clojure callers. Input comes from stdin or `--file`. Repeat `--layer SOURCE=DIR` in precedence order. Use `--plain` for transformed text rather than the default `herdr-orch/v1` JSON envelope. Unknowns and repeats are report data, not CLI failures. The full output and failure contract is in [docs/contract.md](docs/contract.md) section Standalone trait interpolator CLI.

The launcher canonicalises its own path with `cd -P` (the deployed `~/.agents/skills` is a *directory* symlink), uses the repository `bb.edn` when present, and falls back to `bb --deps-root <scripts> -Sdeps '{:paths ["src"]}'` for a bare skill subtree. It never `cd`s before `exec`, so the CLI's working directory is always the caller's -- that value becomes the child pane's `--cwd` and drives assignment-root/roster resolution. It has no additional Maven dependencies.

`task run` and `task start` take assignment text from one source: `--task`, `--task-file`, or stdin. `run` waits for a published item. `start` returns after dispatch, so collect its output later.

Commands that take a task ID require the complete UUID. This rule includes `collect`, `prune`, `poke`, `close`, `continue`, `compact`, and `worktree remove`. `publish --task` also requires the complete UUID. `task status` accepts a complete UUID or no UUID. No command resolves a UUID prefix.

`--prompt-extra` appends exceptional constraints. `--print-prompt` previews the invariant wrapper. The CLI never offers raw prompt mode.

Command index. [docs/contract.md](docs/contract.md) owns precedence, guards, refusal cases, and ledger fields:

| Flag or command | Meaning | Detail |
|---|---|---|
| `--model MODEL` | select the model for one child | § Model resolution |
| `--timeout MS` | set the wait budget | § Timeout resolution |
| `--retro` / `--no-retro` | override retro gating for one spawn | § Retro gating |
| `--spawns NAMES` / `none` | override the persona allow-list; `none` forces a leaf | § Spawn gating |
| `--worktree <path>` / `new` | use an existing checkout or create one managed target | § Checkout target resolution |
| `--tab` / `--split` | force placement; the flags are mutually exclusive | § Placement |
| `task status [UUID]` / `task list` | inspect one round or list rounds | § JSON output |
| `task collect UUID` | capture the next published item | § Ledger and completion |
| `task collect --any` | capture the first same-session child to publish | § Fan-in |
| `task collect UUID --close` | capture a terminal item, then request a guarded close | § Close |
| `task publish` | append an immutable result item | § Ledger and completion |
| `task poke UUID` | ask a settled child to publish after a missing or invalid result | § Poke |
| `task prune UUID` | retire a stale uncaptured round after the child disappears | § Pruning |
| `task close UUID` | use the normal guarded path to close an owned child pane | § Close |
| `task close UUID --abandon` | retire the round without touching its pane | § Close |
| `task close --settled` | close eligible captured children owned by this session | § Close |
| `task continue UUID` | assign a root-owned child another round in the same pane | § Continue |
| `task orphans` / `--close` | list or close captured rounds whose owner session ended | § Orphans |
| `task compact UUID` / `--closed` | remove raw envelope bulk while retaining ledger history | § Retention |
| `task harvest` | list this session's process-retro candidates | [SKILL.md](../SKILL.md) § Process retrospectives |
| `worktree list` / `worktree remove UUID` | inspect targets or remove an eligible managed checkout | § Worktree reconciliation and teardown |

`--worktree <path>` may name an attached checkout outside the managed root for use, but `oh worktree remove` never removes such a checkout. `--worktree new` is the path that creates a checkout `oh` can later tear down. Concurrent target decision and ledger reservation are serialised across CLI processes, including a continuation racing an explicit start for the inherited checkout. Slow assignment input and Herdr inspection happen before the critical section. A read-only existing target with tracked or untracked dirt refuses before allocation. Worktree publications carry `CHECKPOINT`, require the complete repository witness, and check stream capacity before mutation, while branch integration and deletion remain parent-owned. Reconciliation reports present-but-invalid checkouts as `invalid` without following Git discovery upward.

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

Each `--artifact` becomes a portable Markdown link. Use `<relative-path>` or `<relative-path> :: <purpose>`. The parent push shows an advisory link before validation. A successful `collect` or `collect --any` returns existence-validated links in `result.artifact-links`. The URI uses `Path.toUri` and contains no terminal-control escape. Clickability depends on the parent harness and terminal. See [docs/contract.md](docs/contract.md) § Artifact links.

## Raw passthrough

`oh` also wraps the raw Herdr `pane`, `tab`, `ws`, and `agent` verbs. The wrapper is not a transparent mirror: it imposes agent-facing defaults that differ from upstream, so pass the flags explicitly when upstream semantics matter. `oh agent wait` defaults to a 600 s timeout where `herdr` waits indefinitely, and the read family defaults to `--source recent-unwrapped` (upstream: `recent`), falls back to `visible` on empty output, and truncates to 2000 lines / 50 KB. `oh pane wait-output` searches Herdr's selected snapshot immediately, including existing output, and now explicitly uses the same `recent-unwrapped` source default as the wrapper's read family instead of upstream's `recent`. A live probe put a marker into scrollback followed by 250 lines: `oh pane read --lines 300` and `wait-output --source recent-unwrapped --lines 300` found it, while the formerly unqualified wait timed out. Aligning the source default made `wait-output --lines 300` find it too. Snapshot depth still matters, so pass `--source` and `--lines` explicitly when the search window matters. `oh spawn "<shell command>"` runs an ordinary command in a new tab, always unfocused because it never delegates.

## Development

Maintainer test, smoke, and fixture guidance is in [AGENTS.md](AGENTS.md).

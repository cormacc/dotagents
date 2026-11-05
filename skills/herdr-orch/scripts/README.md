# `oh` -- Herdr delegation CLI

`oh` is the harness-agnostic executable behind the `herdr-orch` skill. It performs the mechanical single-child delegation protocol -- spawn, capture, then an explicit `close` or `continue` -- while the skill retains delegation and safety policy.

## Run

Bare `oh` shows a read-only dashboard of up to ten recent recorded assignments, scoped to the caller pane when present or to the assignment root otherwise. It reports counts and explicit empty states without contacting Herdr or creating a ledger. Detail: [docs/contract.md#Home view](docs/contract.md#home-view).

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

Subscription reporting requires `quota-axi@0.1.69` and Node >=22.19. Run `~/.agents/skills/herdr-orch/scripts/quota-axi-setup install` explicitly, or approve `/oh setup` in Pi's TUI. The shared script defaults to read-only `check`, which returns JSON readiness without reading quotas or authenticating. See [docs/contract.md#Usage](docs/contract.md#usage) for installation, discovery and failure limits. Collection and routing never install software. `oh usage` preserves upstream schema 5 or 6 in a `providers` array, uses only allowlisted evidence, and has no local cache, backoff, or stale guarantee. Its fixed 5 s version, 60 s collection, 2 s cleanup, and 1 MiB stdout limits return unknown evidence on failure. The packaged provider map reports Anthropic, Codex and local Lemonade; Kimi and Z.AI reporting requires an explicit provider override. `oh usage --format status` prints one line for a terminal status bar; [../../../herdr-plugin/README.md](../../../herdr-plugin/README.md) shows how to put it in Herdr's tab bar.

Spawning verbs require Herdr 0.9.1 or newer on both the client and the running server. Preflight reads both from `herdr status --json` and refuses an older or unknown endpoint before allocating a ledger entry or touching a pane ([docs/contract.md#Preconditions](docs/contract.md#preconditions)).

Pi adapter checks run inside the installed Pi runtime with `scripts/pi/test.sh` (it resolves `pi` after excluding `node_modules/.bin` entries from `PATH`, never uses the repository's pinned SDK, and records the runtime it ran against). Behaviour and the adapter contract are in [docs/contract.md#Pi fork adapter](docs/contract.md#pi-fork-adapter).

The `fork-provider-context` suite captures inherited-worker and overlay requests through the installed Codex Responses consumer and a loopback fake endpoint. It then drops, reorders and replaces the current task identity in actual transmitted requests and requires rejection after passing controls. Child processes use disposable credentials/resources and the startup `pi/fake-only.cjs` egress guard. The `CAPTURE` output names retained synthetic request/session evidence under the process temporary directory; set `TMPDIR` to a disposable repository `.tmp/` directory when running it. These checks do not prove hosted-model obedience or full production-profile equivalence.

A separate native-system case uses the installed immutable Luna capabilities, high thinking and explicit SSE. A context-only setup probe obtains the same isolated prompt construction without sending a provider request; the source changes only tools prose, and an overlay also changes the role addendum. One synthetic declaration stays frozen. The verifier derives where that declaration goes from the model's capabilities, using Pi 1.5.0's rule: an `additional_tools` item at the head of the input, with no top-level `tools`, when the model takes both mid-conversation system messages and additional tools. Re-check the rule after a Pi upgrade. The endpoint verifier expects the original worker in leading instructions and the overlay update in later developer input before the current assignment, plus signed synthetic reasoning replay. Applied assignment, update and declaration mutations and a native-capability-off control must fail this native verifier; the collapsed-system expectations remain separate. `NATIVE_CAPTURE` names this case's retained evidence. WebSocket frames and production-profile replay are not covered.

`task run` and `task start` take assignment text from one source: `--task`, `--task-file`, or stdin. `run` waits for a published item. `start` returns once the prompt is submitted, so collect its output later.

Commands that take a task ID require the complete UUID. This rule includes `collect`, `prune`, `poke`, `close`, `continue`, `compact`, and `worktree remove`. `publish --task` also requires the complete UUID. `task status` accepts a complete UUID or no UUID. No command resolves a UUID prefix.

Routine `task status` and `task list` return compact JSON entries (`task`, `child`, `pane-id`, `status`, `captured-at`, `summary`) with a top-level `counts` map. Use `--full` for detailed entries with live evidence; `--raw` implies `--full` and includes the stored envelope text. Text output is unchanged. These listings omit only lower-case launch status `failed`; use `--include-failed` to audit retained failed entries. `task status <full-task-uuid>` always shows its entry and refuses `--include-failed` because that lookup is already unfiltered. Published `FAILED` results, invalid captures, and closed successes remain visible by default.

JSON lists and unfinished delegation results include selective top-level `help` hints; final results and confirmations do not. Usage errors include concise command help. Hints are separate from `result`, and existing text listings stay unchanged.

The persona is optional: `task run --task TEXT` launches a persona-less fresh child with no synthetic role (explicit `--trait` selections still apply). `--context fresh` is the default and `--context fork` forks the caller's own Pi session (see [docs/contract.md#Fork launch](docs/contract.md#fork-launch)); `--context measurement --measurement-config PATH` runs a Pi subject headless under a detached, supervisor-published run with no pane by default (see [docs/contract.md#Measurement launch](docs/contract.md#measurement-launch)); its request audit covers exactly four Pi APIs (see [docs/contract.md#Pi measurement adapter](docs/contract.md#pi-measurement-adapter)) ([docs/contract.md#Context modes](docs/contract.md#context-modes)). Root-owned staging, repeated measurements and the approval-gated context smoke are in [../references/workflow-recipes.md](../references/workflow-recipes.md).

`--prompt-extra` appends exceptional constraints. `--print-prompt` previews the invariant wrapper. The CLI never offers raw prompt mode.

Command index. [docs/contract.md](docs/contract.md) owns precedence, guards, refusal cases, and ledger fields:

| Flag or command | Meaning | Detail |
|---|---|---|
| `--model MODEL` | select the model for one child | [docs/contract.md#Model resolution](docs/contract.md#model-resolution) |
| `--timeout MS` | set the wait budget | [docs/contract.md#Timeout resolution](docs/contract.md#timeout-resolution) |
| `--retro` / `--no-retro` | override retro gating for one spawn | [docs/contract.md#Retro gating](docs/contract.md#retro-gating) |
| `--spawns NAMES` / `none` | override the persona allow-list; `none` forces a leaf | [docs/contract.md#Spawn gating](docs/contract.md#spawn-gating) |
| `--worktree <path>` / `new` | use an existing checkout or create one managed target | [docs/contract.md#Checkout target resolution](docs/contract.md#checkout-target-resolution) |
| `--tab` / `--split` | force placement; the flags are mutually exclusive | [docs/contract.md#Placement](docs/contract.md#placement) |
| `--context measurement --measurement-config PATH` | run one headless measurement sample; `--tab`/`--split` add a read-only monitor | [docs/contract.md#Measurement launch](docs/contract.md#measurement-launch) |
| `task status [UUID]` / `task list [--include-failed] [--full]` | inspect one round or list compact rounds with counts; opt into detailed or retained failed entries | [docs/contract.md#JSON output](docs/contract.md#json-output) |
| `task collect UUID` | capture the next published item | [docs/contract.md#Ledger and completion](docs/contract.md#ledger-and-completion) |
| `task collect --any` | capture the first same-session child to publish | [docs/contract.md#Fan-in](docs/contract.md#fan-in) |
| `task collect UUID --close` | capture a terminal item, then request a guarded close | [docs/contract.md#Close](docs/contract.md#close) |
| `task publish` | append an immutable result item | [docs/contract.md#Ledger and completion](docs/contract.md#ledger-and-completion) |
| `task poke UUID` | ask a settled child to publish after a missing or invalid result | [docs/contract.md#Poke](docs/contract.md#poke) |
| `task prune UUID` | retire a stale uncaptured round after the child disappears | [docs/contract.md#Pruning](docs/contract.md#pruning) |
| `task close UUID` | use the normal guarded path to close an owned child pane | [docs/contract.md#Close](docs/contract.md#close) |
| `task close UUID --abandon` | retire the round without touching its pane: a captured round unconditionally, a never-published newest round only on idle/done or stopped-process evidence, never a blocked or working child | [docs/contract.md#Close](docs/contract.md#close) |
| `task close --settled` | close eligible captured children owned by this session | [docs/contract.md#Close](docs/contract.md#close) |
| `task continue UUID` | assign a root-owned child another round in the same pane | [docs/contract.md#Continue](docs/contract.md#continue) |
| `task orphans` / `--close` | list or close captured rounds whose owner session ended | [docs/contract.md#Orphans](docs/contract.md#orphans) |
| `task compact UUID` / `--closed` | remove raw envelope bulk while retaining ledger history | [docs/contract.md#Retention](docs/contract.md#retention) |
| `task harvest` | list this session's process-retro candidates | [../SKILL.md#Process retrospectives](../SKILL.md#process-retrospectives) |
| `roster [--format json\|markdown\|org] [--section S] [--detail]` | list resolved personas, traits, model aliases, self-declared workflow skills, and spawn defaults without calling Herdr; JSON by default, or a Markdown or org rendering | [docs/contract.md#Roster](docs/contract.md#roster) |
| `worktree list` / `worktree remove UUID` | inspect targets or remove an eligible managed checkout | [docs/contract.md#Worktree reconciliation and teardown](docs/contract.md#worktree-reconciliation-and-teardown) |

`--worktree <path>` may name an attached checkout outside the managed root for use, but `oh worktree remove` never removes such a checkout. `--worktree new` is the path that creates a checkout `oh` can later tear down. When a delegated launch fails before its child takes the assignment, `oh` removes the pristine managed checkout that the round created, keeps the branch, and reports the outcome in `error.data.checkout-cleanup`; a checkout that holds any content is retained for `oh worktree list` and `oh worktree remove`. Run the spawn again to retry: `oh` never retries `agent start` itself. See [docs/contract.md#Failed-launch checkout cleanup](docs/contract.md#failed-launch-checkout-cleanup). Concurrent target decision and ledger reservation are serialised across CLI processes, including a continuation racing an explicit start for the inherited checkout. Slow assignment input and Herdr inspection happen before the critical section. A read-only existing target with tracked or untracked dirt refuses before allocation. Worktree publications carry `CHECKPOINT`, require the complete repository witness, and check stream capacity before mutation, while branch integration and deletion remain parent-owned. Generated child prompts distinguish worktree checkpointing from shared-root publication and require immutable commits for Git baseline comparisons. Reconciliation reports present-but-invalid checkouts as `invalid` without following Git discovery upward.

A `WAITING` item is not completion -- only a validated terminal result item is.

```sh
OH="$HOME/.agents/skills/herdr-orch/scripts/oh"
"$OH" task run scout --task 'Find the relevant source files.' --timeout 600000
"$OH" task start reviewer --task-file assignment.md
"$OH" task collect <full-task-uuid> --wait --timeout 600000
"$OH" task collect --any --wait --timeout 600000
"$OH" task start --context measurement --measurement-config experiment/measurement.json --worktree new --task-file sample.txt
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

Each `--artifact` becomes a portable Markdown link. Use `<relative-path>` or `<relative-path> :: <purpose>`. The parent push shows an advisory link before validation. A successful `collect` or `collect --any` returns existence-validated links in `result.artifact-links`. The URI uses `Path.toUri` and contains no terminal-control escape. Clickability depends on the parent harness and terminal. See [docs/contract.md#Artifact links](docs/contract.md#artifact-links).

## Retire an integrated branch

`oh worktree remove` retains the branch; branch deletion is an explicit parent operation, not an `oh` action. See [docs/contract.md#Worktree reconciliation and teardown](docs/contract.md#worktree-reconciliation-and-teardown).

1. Capture and close the child, integrate its committed work, and remove its managed checkout with `oh worktree remove <full-task-uuid>`. Inspect any reported dirt before teardown.
2. Read the recorded branch from `oh task status <full-task-uuid>`. Set `branch` to that exact value; do not select branches by an `orch/*` wildcard. Inspect `git worktree list --porcelain` and confirm that no remaining checkout uses it.
3. Run `git merge-base --is-ancestor "$branch" HEAD` from the integration checkout. Continue only when its exit status is 0; an error or non-ancestor result is not permission to delete.
4. Run `git branch -d -- "$branch"`. A refusal leaves the branch for review; do not replace `-d` with `-D` to bypass it.

Squash or cherry-pick integration can fail the ancestry check even when the intended change landed. Keep that branch until its retained commits have been reviewed against the integrated result. This procedure deliberately does not automate deletion in that case.

## Raw passthrough

`oh` also wraps the raw Herdr `pane`, `tab`, `ws`, and `agent` verbs. The wrapper is not a transparent mirror: it imposes agent-facing defaults that differ from upstream, so pass the flags explicitly when upstream semantics matter. `oh agent wait` defaults to a 600 s timeout where `herdr` waits indefinitely, and the read family defaults to `--source recent-unwrapped` (upstream: `recent`), falls back to `visible` on empty output, and truncates to 2000 lines / 50 KB.

`oh agent prompt <target> <text>` submits without waiting, exactly as the delegation verbs do. `--wait` waits on that same `agent prompt` call for the first settled `idle`, `done`, or `blocked` state, and `--until STATE` (repeatable) and `--timeout MS` ride on it. With `--wait` and no `--timeout`, no timeout is passed and Herdr waits **indefinitely** -- unlike `oh agent wait`, whose 600 000 ms default is unchanged and separate. `--until` or `--timeout` without `--wait` is refused before Herdr is invoked, never silently dropped or promoted into a wait. The Pi `herdr_agent prompt` tool defaults `wait` to true and maps to this one call; it refuses `until`/`timeout` with `wait=false` before invoking `oh`. Herdr's own outcomes (`agent_blocked`, `agent_prompt_stalled`, `timeout`) pass through unchanged ([docs/contract.md#Start and prompt outcomes](docs/contract.md#start-and-prompt-outcomes)).

`oh agent start <name> --kind KIND --pane PANE` accepts any nonblank kind and lets the installed Herdr decide whether it exists (`herdr agent start --help` prints the live enum). A blank kind is refused before Herdr is invoked. `oh pane split [--pane PANE] [--direction right|down] [--cwd DIR] [--focus]` splits the named pane, defaulting to the caller's; an omitted direction is inferred from the *source* pane's geometry, and `--focus` requests focus where the default preserves it. Delegation never uses `--focus` ([docs/contract.md#Placement](docs/contract.md#placement)). `oh pane wait-output` searches Herdr's selected snapshot immediately, including existing output, and now explicitly uses the same `recent-unwrapped` source default as the wrapper's read family instead of upstream's `recent`. A live probe put a marker into scrollback followed by 250 lines: `oh pane read --lines 300` and `wait-output --source recent-unwrapped --lines 300` found it, while the formerly unqualified wait timed out. Aligning the source default made `wait-output --lines 300` find it too. Snapshot depth still matters, so pass `--source` and `--lines` explicitly when the search window matters. `oh spawn "<shell command>"` runs an ordinary command in a new tab, always unfocused because it never delegates.

## Pi upgrade checks

Run this checklist when the installed Pi store path or version changes:

1. Run `scripts/pi/test.sh` against the installed runtime, not the repository SDK. Trace each failure to the installed consumer before changing an expectation.
2. Re-check native tool-declaration placement in `pi/fork-provider-context.test.ts` against the installed `pi-ai` source and model capabilities.
3. Re-check `turnBoundary` in `pi/fork-adapter.ts` against the installed `pi-coding-agent` turn semantics.
4. Derive expectations from the consumer source, not an observed request payload. Run the passing controls and the known-bad mutations after any verifier change.

## Development

Maintainer test, smoke, and fixture guidance is in [AGENTS.md](AGENTS.md).

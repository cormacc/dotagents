# Agent guide: developing `herdr-orch` scripts

Use this file for maintainer workflows. User-facing CLI examples belong in `README.md`. Mechanical behaviour belongs in `docs/contract.md`. Design rationale belongs in `../design.org`.

## Tests

Run the complete repository suite from the repository root or this scripts directory:

```sh
bb test
```

The scripts-local `bb.edn` delegates to the repository-root `test` task. That task runs all discovered Clojure tests and the trait gate. It does not start a live agent.

Run only the main Herdr orchestration namespace from the repository root:

```sh
bb test-clojure herdr-orch.cli-test
```

The Herdr tests use fake processes and per-test `ORCH_ASSIGNMENT_ROOT` directories. They must not write to the live `<git-root>/.tmp/herdr-orch/` tree.

## Live smoke

Run the live smoke only from the repository root and from a Herdr-managed pane. Confirm that Herdr injected `HERDR_ENV=1`. Then run:

```sh
ORCH_LIVE_SMOKE=1 ORCH_LIVE_SMOKE_MODEL=<model> bb smoke-subagent
```

The live smoke starts an agent. Do not run it in CI. Maintainer rationale and smoke coverage notes are in `../design.org`.

## Fixture identity

A standalone `fake-herdr` probe inherits the delegated shell identity. Set `HERDR_PANE_ID=w:p` to use the fixture parent pane.

A nested probe also needs an anchored `FAKE_PARENT_LABEL`. Use a value such as `worker-1-light`, with the `<HERDR_ORCH_PERSONA>-<index>` prefix.

Unset `HERDR_ORCH_PERSONA` to simulate a root CLI caller. This variable is the depth discriminator. See `docs/contract.md` section Spawn gating.

The following variables do not classify a call as below-root by themselves:

- `HERDR_ORCH_SPAWNS`
- `HERDR_ORCH_TASK`
- `HERDR_ORCH_RESULT`
- `HERDR_ORCH_CHILD`

Clear those variables only when a test requires absent inherited round metadata.

## Test-authoring guardrails

- Check the subprocess exit status before you read a produced file. `call!` returns the process map. A refused spawn can otherwise appear as `FileNotFoundException: .../ledger/.json`.
- Put every expected-hang probe behind a per-call timeout or a stub. Never run an unbounded hang probe inside a test namespace.
- Do not use auto-resolved `::keywords` in `cli_test.clj`. Its namespace enables parallel tests. The source parser rejects these keywords and reports the error against `test_runner.clj`. Use a plain keyword.

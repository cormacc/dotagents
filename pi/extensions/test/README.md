# Extension test infrastructure

Each extension keeps its own assertions and `test.sh`. The shared helpers own process and dependency plumbing, not extension behaviour.

`test.sh` in this directory runs every `*.test.ts` here: the cross-extension suites, the suites for top-level extensions that have no directory of their own, and `locked-tools.test.ts`.

## Locked tools

Runners source `support/locked-tools.sh` after changing to their extension directory. It resolves the repository's `node_modules/.bin/tsx` and `tsc`; it never downloads a tool or selects an ambient `tsx`. Install dependencies with `npm ci` at the repository root. Extensions with local runtime dependencies also need their own `npm ci`.

`locked-tools.test.ts` checks a local binary against a competing ambient binary, then removes the local binary in a disposable fixture and checks that the launcher fails.

## RPC harness

`support/rpc.ts` exports `createHarness`, `sanitizedEnv`, and `attachJsonlReader`. Tasks, traits, question, and system-prompt integration tests use this harness to start the installed Pi executable, send JSONL commands, await events, collect diagnostics, and dispose of the child process.

- `createHarness(extension, cwd, options)` accepts extra CLI arguments, an explicit environment overlay, and a command override for failure tests. Default isolation removes inherited Node loader settings and agent markers, excludes repository binary shims from `PATH`, and uses the fixture as `HOME` and the agent directory.
- `send(command)` returns the request ID. Match that ID for responses; use `waitFor(predicate, label, { since })` when a scenario must exclude earlier events.
- The reader splits only at LF and tolerates a trailing CR. Empty records, unterminated final records, and Unicode separators retain the framing covered by `tasks/pi-integration.test.ts`.
- Process and stdin failures are captured throughout the harness lifetime, including between waits. Malformed stdout, extension errors, early exits, and timeouts include diagnostics.
- Always await `dispose()` in `finally`. Keep scenario-specific providers and fixtures in the calling test. Command-only tests need no model provider.

Run `../tasks/test.sh` for shared-harness framing and failure-path checks. Other extensions run their own RPC scenarios through their local test runners.

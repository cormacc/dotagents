# TypeScript → `ot` test mapping

Source of truth for the migration. Each TS test file maps to a Babashka namespace under `skills/org-tasks/scripts/test/`. Golden fixtures live under `skills/org-tasks/scripts/test/fixtures/`.

| TS file (pi/extensions/tasks/)   | `ot` namespace                                 | Scope                                                                       |
| -------------------------------- | ---------------------------------------------- | --------------------------------------------------------------------------- |
| `parser.test.ts`                 | `org-tasks.parser-test`                        | Heading / drawer / LOGBOOK / `CLOSED:` parse + round-trip; `#+LINK:` expansion. |
| `effective.test.ts`              | `org-tasks.effective-test`                     | `#+SETUPFILE:` chain resolution with cycle + depth guards.                  |
| `lifecycle.test.ts`              | `org-tasks.lifecycle-test`                     | Status transitions, LOGBOOK semantics, `:STARTED:` once-only, `CLOSED:` write/clear. |
| `paths.test.ts`                  | `org-tasks.paths-test`                         | Project-root sandbox: traversal + symlink escape rejection.                  |
| `doctor.test.ts`                 | `org-tasks.doctor-test`                        | Doctor finding codes and grouping.                                          |
| `memory.test.ts`                 | `org-tasks.memory-test`                        | Cross-file scenario: parent, plan, blockers, summary ordering.              |
| `summary.test.ts`                | `org-tasks.summary-test`                       | Closure-time `* Summary` refresh detection.                                 |
| `section.test.ts`                | `org-tasks.section-test`                       | `ot section`: source-block shielding, case-insensitive heading match.       |
| `scan.test.ts`                   | `org-tasks.scan-test`                          | `ot scan`: scope, tags, body cap, `hasContext`, plan-task descent.          |
| `insert.test.ts`                 | `org-tasks.insert-test`                        | `ot create`: priority, tags, drawer ordering, idempotency, splice.          |
| expanded overlay model tests     | `org-tasks.tui-test`                           | Standalone TUI: selected-path expansion, movement, collapse, detail scroll, status cycle, row/detail affordances. Covers `org-tasks.tui` plus the extracted `org-tasks.tui.tasks` (state model/bridge) and `org-tasks.tui.dispatch` (nexus actions/effects) namespaces. |
| CLI dispatch smoke               | `org-tasks.cli-test`                           | Help preservation (top-level and per-command `ot <cmd> --help` option rendering), existing subcommand dispatch through command-family namespaces, bare non-TTY selected JSON, bare `--format json` selected JSON. |
| command integration families      | `org-tasks.commands.{archive-publish,create,links,list-show,maintenance,move,record,status}-test` | Focused `ot` command-family integration tests split to mirror `src/org_tasks/commands/*`; shared temp-project fixtures live in `org-tasks.commands.test-util`. |
| n/a (new in `ot`)                | `org-tasks.commands.move-test`                  | `ot move`: reparent, section lift, level re-normalisation, dry-run, every preflight refusal, write conflict, change-record link integrity, byte-identical relocation and move → move-back round trip. |

## Golden round-trip fixtures

Lives under `skills/org-tasks/scripts/test/fixtures/round-trip/`. Each fixture is an org file the Clojure parser must read and re-emit byte-identically.

| File                                    | Covers                                                                 |
| --------------------------------------- | ---------------------------------------------------------------------- |
| `closed-above-drawer.org`               | Org-native `CLOSED:` line between heading and `:PROPERTIES:`.          |
| `logbook-lifecycle.org`                 | LOGBOOK drawer with `- Created` and `- State` entries; body preserved. |
| `linked-issues-mixed.org`               | `:LINKED_ISSUES:` mixing typed link + raw URL org-link tokens.         |
| `unknown-keywords-and-properties.org`   | Unknown `#+` keywords and `:NS_*:` drawer properties round-trip.       |
| `import-link-forms.org`                 | `#+IMPORT:` bare path, `[[file:...]]`, and `[[plan:...]]` forms.       |
| `nested-subtree.org`                    | Multi-level subtask tree with siblings, preserving non-task content.   |
| `setupfile-chain/TASKS.org`             | Drives the chained setupfile resolution test (paired with siblings).   |
| `setupfile-chain/TASKS.local.org`       | Local overrides win over shared.                                       |
| `setupfile-chain/TASKS.setup.org`       | Shared defaults (`#+LINK: plan file:design/log/%s`).                   |

## Test harness

- `bb test` invokes `org-tasks.test-runner/run` which uses `clojure.test` and discovers every namespace under `scripts/test/`. An explicit namespace argument must be in that discovered set. Option-like and unknown arguments fail before namespace loading.
- A namespace opts in to parallel deftest execution with `^{:parallel-tests true}` ns metadata (currently only `herdr-orch.cli-test`, whose fixture spawns real `subagent` subprocesses). Every other namespace still runs exactly as before. Before pooling, the runner source-parses opted-in namespaces with Babashka's bundled Edamame reader and rejects an untagged `deftest` containing `with-redefs`, `with-redefs-fn`, or `alter-var-root`. Within an opted-in namespace, vars marked `^:serial` run first, sequentially, before the rest run on a bounded thread pool (default `Runtime/availableProcessors`, override with `OT_TEST_PARALLELISM`).
- Round-trip tests read fixture content, run `parse-tasks` → `serialize-tasks-preserving-file`, and assert byte equality with the original.
- Where the TS test directly constructs a `Task` via TS object literal, the Clojure equivalent constructs a `task` map from the same data and asserts serializer output.
- TUI tests avoid brittle full-ANSI snapshots. Pure state/layout functions are tested directly. CLI tests assert stdout remains machine JSON for non-TTY default invocations.

## Cutover gate

Per the Migration Strategy decision, each pi extension command switches to `ot` only when both:

1. The corresponding `org-tasks.<cmd>-test` namespace passes via `bb test`.
2. The relevant round-trip fixture diffs are clean.

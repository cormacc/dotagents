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

## Regenerating README listings

`../README.org` embeds the packaged personas, traits, and models as `oh roster --format org` output. Each `#+CALL: oh-roster[:eval yes](section="...")` line runs the `oh-roster` block in that file's `Generated listings` section, with an empty `HOME` and assignment root so that only the packaged layer appears. The file-level `#+PROPERTY: header-args :eval no` disables every other block, so executing the whole buffer runs only those calls.

Regenerate after a change to a packaged persona (`../subagents/*.md`), a packaged trait (`../traits/`), `../subagents/config.edn`, or the roster renderer in `cli.clj`. Until then, `bb test` fails in `readme-roster-listings-are-current` with "README.org `<section>` listing is stale". Do not edit a `#+RESULTS:` block by hand.

In Emacs:

1. Visit `../README.org`.
2. Load the shell backend once in the session: `M-: (require 'ob-shell)`, or add `(shell . t)` to `org-babel-load-languages`.
3. Type `C-c C-v b` (`org-babel-execute-buffer`) and accept each evaluation prompt. To regenerate one listing, type `C-c C-c` on its `#+CALL:` line.
4. Save the buffer.

From a shell, run `bb readme-roster` at the repository root. It runs the same calls in `emacs --batch -Q` and saves the file. It refuses while an Emacs `.#README.org` lock exists, because writing under a visiting buffer can discard unsaved edits.

An agent with an Emacs evaluation tool first checks whether the user's Emacs visits `README.org`. If it does, regenerate in that buffer with `(require 'ob-shell)` and `(let ((org-confirm-babel-evaluate nil)) (org-babel-execute-buffer))`, and leave the save to the user when the buffer had unsaved changes before. Otherwise run `bb readme-roster`. Then run `bb test-clojure herdr-orch.cli-test` to confirm the listings are current.

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
- Prove that a verb made no call of a kind by comparing `(count (calls log))` before and after that call. The fixture's call log is cumulative, so the log tail and the whole log also match calls made before the verb under test.
- Break a guard for a negative control in a copy, not in place: copy `skills/herdr-orch` into `.tmp/<name>/skills/` and run the test var from that root. The launcher's bare-subtree fallback loads the copy's `src`, so no tracked file changes.
- Do not use auto-resolved `::keywords` in `cli_test.clj`. Its namespace enables parallel tests. The source parser rejects these keywords and reports the error against `test_runner.clj`. Use a plain keyword.

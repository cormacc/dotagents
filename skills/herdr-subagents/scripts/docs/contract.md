# `subagent` contract

## Preconditions

All spawn operations require `HERDR_ENV=1`, Herdr >= 0.7.5, and installed non-mutating help shapes for `pane layout/split/rename/get/close`, `agent start/prompt/wait/get`, and `notification show`. Preflight precedes ledger allocation and pane mutation.

## JSON output

Every non-help command writes exactly one JSON object to stdout:

```json
{"ok":true,"schema":"herdr-subagents/v1","result":{}}
```

Failures use `{ "ok": false, "schema": "herdr-subagents/v1", "error": {"message": "...", "data": {}} }`. Diagnostics do not carry a completion signal.

`--help` is the one documented exception: `subagent --help`, `subagent help`, and `<command> --help` print human-readable usage text and exit 0. There is no JSON help or discovery variant.

`status` without a task and `list` return a JSON **array** of ledger entries under `result`; every other command returns an object.

A result that is published but fails validation (identity mismatch, malformed envelope, missing or relative artifact path) is recorded as the non-final status `invalid`, with the reason on the ledger entry and in `result.reason`. `invalid` is terminal for the child (publication is immutable and cannot be retried) and needs manual intervention; the pane is retained and re-collecting returns the same outcome instead of failing.

`run` uses a ten-minute total wait budget unless `--timeout` overrides it. Timeout, no result, and blocked outcomes are non-final and retain the child pane. `collect` polls once unless `--wait --timeout` is supplied.

When a wait outcome settles (idle/done) without a valid result file, the loop sleeps `min(SUBAGENT_POLL_INTERVAL_MS, remaining-budget)` before polling again instead of re-invoking `agent wait` immediately; default interval is 1000 ms. This applies identically to `run` and `collect --wait` (both dispatch through the same wait loop) and never overshoots the total timeout by a full interval.

## Environment

| Variable | Read by | Meaning |
|---|---|---|
| `SUBAGENT_ASSIGNMENT_ROOT` | parent | Overrides the `git rev-parse --show-toplevel` probe behind the assignment root. It relocates the ledger, index markers, and `RESULT` paths **and** project-roster lookup (`<root>/.agents/subagents/`) together, because all four are per-project notions. A blank value is ignored, a relative value is absolutised so `RESULT` stays absolute, and a value that is not an existing directory is rejected. When set, it is injected into the child pane so nested delegation stays in the same root. |
| `SUBAGENT_POLL_INTERVAL_MS` | parent | Sleep between settled-without-result wait iterations. Unset, blank, unparseable, zero, and negative values all fall back to 1000 ms. |
| `HERDR_SUBAGENT_CHILD` | child | Live agent name recorded on the ledger. |
| `HERDR_SUBAGENT_TASK` | child | Assignment id. |
| `HERDR_SUBAGENT_RESULT` | child | Exact absolute result path to publish. |
| `HERDR_SUBAGENT_BIN` | child | Absolute launcher path for `publish`. |
| `HERDR_SUBAGENT_WAITING_POLICY` | child | `blocking` or `non-blocking`; the latter makes a successful publish emit an operator notification. |
| `HERDR_SUBAGENT_PERSONA` | child | The child's own persona. It gates nested labelling: a spawn is labelled `planner-<n>/<persona>-<index>[-<model>]` only when the *spawning* agent's `HERDR_SUBAGENT_PERSONA` is `planner`, so a manually started planner loses nested labels. |

## Ledger and completion

The CLI stores one JSON ledger entry per task under `<assignment-root>/.agents/tmp/herdr-subagents/ledger/`; index marker files provide lock-free, parent-session/per-persona monotonic allocation. The child receives `HERDR_SUBAGENT_CHILD`, `_TASK`, `_RESULT`, `_BIN`, `_WAITING_POLICY`, and `_PERSONA` (plus `SUBAGENT_ASSIGNMENT_ROOT` when overridden) through repeatable `pane split --env` flags.

The exact `RESULT` file is the only completion signal. It must be atomically published once from a sibling temporary file using `Files.createLink(result, temp)` then unlinking `temp`; a pre-existing result is an error and is never overwritten. Artifacts named by the result must exist before collection captures it.

```text
--- HERDR RESULT v1 ---
CHILD: <live agent name>
TASK: <parent task UUID>
RESULT: <absolute parent-chosen result path>
STATUS: COMPLETE | BLOCKED | FAILED
SUMMARY: <single line>
ARTIFACTS:
- <absolute path — purpose, or none>
FINDINGS:
- <at most five one-line items, or none>
NEXT: <one parent/user action, or none>
--- END HERDR RESULT ---
```

The marker lines and `CHILD`, `TASK`, and `RESULT` must exactly match the ledger, and `FINDINGS` holds at most five items. Transcript output, `agent read`, and terminal history are never collection inputs. A `COMPLETE`/`FAILED` result closes only a child-owned pane after capture and a settled child; `BLOCKED` never closes its pane.

The ledger directory is shared by every parent in the project, so pane closure is scoped to the owning session: `collect` on an entry whose `:parent-session` differs from the caller's (or whose caller identity cannot be resolved) still captures and validates the envelope, but retains the pane and reports `"pane-retained": true`. Ledger *reads* are never scoped — `status` and `list` show every entry.

## Labels and geometry

Labels are `<persona>-<index>[-<model-basename>]`; a planner child is `planner-<n>/<persona>-<index>[-<model-basename>]`. Workspace names are excluded. Direction is `right` only when the caller's own matching layout pane rect has `width >= 80 && width >= 2 * height`, otherwise `down`; tab area and focus are irrelevant.

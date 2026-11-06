# Bounded re-review 2: supervisor request/snapshot bracket (task ca3e7ac4)

Range: uncommitted diff against HEAD `8954da90f08ba19d30331190f45ab65bdee4b094` in `herdr-plugin/org-tasks/src/org_tasks_host.clj` (`supervise!`) and `scripts/test-herdr-org-tasks.bb`. `git diff` of those two paths is byte-identical to `.tmp/terminal-race-fix2/fix.patch` (`diff` exit 0). `herdr-app/settings.json` ignored. Not terminal acceptance or Class C closeout.

## Findings

**No issues found.**

## Settled questions

### 1. Interleavings

Model: `action!` runs under the per-tab lock, so actions are serial. Each action writes a fresh `request.json` (new `uuid` `:requestId`, atomic rename) before `resize!` and before its focus call (`plugin pane focus` for expand, `agent focus` for collapse). `call!` blocks until the Herdr CLI returns. The supervisor poll is R1 (read request), S (`snapshot!`), R2 (re-read), then `sample!` and publication only if R1 = R2.

- **Settled poll (R1 = R2 = Rq).** Because each request has a unique `:requestId`, equality means no write occurred between R1 and R2, so S lies after Rq's write and before any later write. Any action focus that S shows belongs to an action whose write is at or before Rq. A later action cannot have focused yet, because its focus follows its write, which is after R2.
  - If S shows the task pane focused and Rq differs from `last-request`, `handoff-source` resolves Rq's own source against S. That is coherent: the expand or collapse that focused the task pane wrote Rq first.
  - If S shows a non-task pane, `source` uses the live focus in S. If Rq's action has not focused yet, the published source is the pane focused at S, which is the action's source unless the user moved focus in the meantime. In that case it is the user's later focus.
  - If Rq equals `last-request`, retention keeps `previous`, and `previous` came from a coherent settled poll.
  - Result: no settled poll can publish a source that S contradicts, and no request is acknowledged against a snapshot older than its write.
- **Unsettled poll (R1 differs from R2).** The supervisor publishes nothing and recurs with `previous`, `last-context` and `last-request` unchanged. The new request therefore still differs from `last-request` at the next poll and is consumed through `handoff-source`. This covers both orderings from earlier reviews:
  - The original HEAD ordering: S shows A, then the request read returns B (`design/log/.../review.md` finding 1).
  - The first-correction mirror: R1 is old, then the action writes, resizes and focuses, then S shows the task pane focused (`.tmp/terminal-race-review/...-report.md` finding 1).
- **Residual ordering, not a finding.** If the write occurs after R2, the poll publishes S's pre-action source under the old `requestId` before the next poll consumes the new request. The snapshot was coherent with the request it acknowledges. This is the inherent latency of a single poll, and it also exists at HEAD when the user expands just after a poll.
- **Collapse.** `action-request!` prefers the context as witness only when the context's `requestId` equals the current request's. While a request is unconsumed, which includes a skipped publication, the witness is the pending request. That request names the expanded-from source, so a collapse in the window targets B. It cannot fall back to an old A context.
- **`handoff-source` freshness (-1000..5000 ms).** A request is consumed at the first settled poll after its write. Each unsettled poll needs a newer write in the R1 to R2 window, so the consumed request is the latest one. Its age is then at most about one poll interval plus `snapshot!` latency (500 ms + up to 200 ms timeout), which is well inside 5000 ms. A request that is somehow older fails closed to `unavailable`. It does not fall back to a wrong source.
- **`last-request` retention.** When a poll is unsettled, the code recurs with `(if settled? request last-request)`. This is correct: the unconsumed request stays pending.

### 2. Consumer lease

`embedding/read-context` accepts a context when its `updatedAt` is no more than 5000 ms old (`lease-ms`), and `:ready` requires a valid local directory.

- A skipped publication leaves the last coherent context in place, and that context keeps ageing.
- Each consecutive skip needs a distinct `action!` write that lands inside that poll's snapshot window. Actions are serial and each one makes several host calls.
- Losing `:ready` therefore needs about 9 or more consecutive polls, each hit by a write: continuous action spam for about 4.5 s. The outcome is then `:stale`, which fails closed. It never accepts a context that names the wrong source.
- Under ordinary use, a single skip delays publication by one poll (at most about 700 ms). `owner.json` is still renewed on each poll, so action ownership (`owned?` lease) is unaffected.

### 3. Regressions: genuine red and green

Disposable copies are under `.tmp/terminal-race-review2/`. Each copy is the current test file with only `add-classpath` pointed at a host copy, plus a line that prints the `:file` of `#'host/supervise!` to confirm which host loaded. The child probe re-executes the same copy, so it loads the same host.

| Host | Exit | Tests and assertions | Failures |
| --- | --- | --- | --- |
| HEAD (`git show 8954da9:...`) | 1 | 19/145 | 4: test 1 (`agent-a` vs `agent-b`, `/a` vs `/b`); test 2 (post-expansion `post` = `[agent-a x6]`, last source `agent-a`) |
| First correction (HEAD + host hunk of `.tmp/terminal-race-fix/fix.patch`) | 1 | 19/145 | 1: test 2 only (`post` = `[agent-a agent-b ...]`) |
| Current (byte-identical copy of the worktree host) | 0 | 19/145 | 0 |

I also made an extra variant that returns the post-action `@state` from the racing snapshot. This is the exact mirror ordering from the earlier report. Results:
- First correction: exit 1. Test 2 fails with `[agent-a agent-b ...]`.
- Current: exit 0.
- HEAD: exit 0. The original defect needs the pre-action snapshot.

Logs: `variant-{head,fix1,cur}.log`.

### 4. HEAD tests unchanged

- HEAD has 17 `deftest` forms and the current file has 19.
- `git diff --numstat` shows 89 lines added and 0 removed in the test file.
- The first 403 lines of the current file are byte-identical (`cmp`) to the first 403 lines of HEAD's file.
- HEAD's last 2 lines (`run-tests` and exit) are byte-identical to the current file's last 2 lines.
- All 17 HEAD names and bodies are therefore unchanged.

## Verdict

APPROVED

## Verification

| Command | Result | Outcome |
| --- | --- | --- |
| `bb scripts/test-herdr-org-tasks.bb` | pass | exit 0; 19 tests, 145 assertions, 0 failures, 0 errors (`offline.log`) |
| `bb design/log/2026-11-05-org-tasks-herdr-ui/reproduce-focus-race.bb` | pass (defect does not reproduce) | exit 1 as expected; both the control case and the race case publish `agent-b` on all 4 polls, consumer `:ready`, directory `/b`; the race probe's assertion fails (`repro.log`) |
| `bb test-clojure org-tasks.embedding-test org-tasks.tui-test org-tasks.cli-test org-tasks.launcher-test` | pass | exit 0; all four namespaces ran; 66 tests, 1416 assertions, 0 failures, 0 errors (`consumer.log`) |
| `bb scripts/test-herdr-plugin.bb` | pass | exit 0; 4 tests, 20 assertions, 0 failures (`plugin.log`) |
| `clj-kondo --lint` on the two changed files | pass | exit 0; 0 errors, 0 warnings (`lint.log`) |
| HEAD, first-correction and current host copies | pass | red, red and green as shown in the table above (`run-*.log`) |
| Post-state mirror variant | pass | the first correction fails and the current host passes (`variant-*.log`) |
| Native Herdr | unavailable | out of scope; no live server used |

## Untested surfaces and notes

- The committed test 2 returns the pre-action snapshot. The exact post-action mirror ordering is covered only by my scratch variant, not by a committed test. Both shapes are red on the first correction, so the committed test still guards the bracket. This is not a finding.
- The `race-probe` docstring describes only the test 1 timing.
- Native Linux and macOS Herdr validation is outside this range.

## Summary

The R1/S/R2 bracket ensures that every published context and every acknowledged request is reconciled with a snapshot taken after the request's write and before any later action's focus. Skipped publications are bounded by the action rate and fail closed through the 5 s lease. Both new regressions are genuinely red on the earlier hosts and green on the current host.

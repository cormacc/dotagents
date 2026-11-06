# Bounded re-review: supervisor request/snapshot ordering (task ca3e7ac4)

Range: uncommitted diff against HEAD `8954da90f08ba19d30331190f45ab65bdee4b094` in `herdr-plugin/org-tasks/src/org_tasks_host.clj` and `scripts/test-herdr-org-tasks.bb`. `git diff HEAD` of those two paths is byte-identical to `.tmp/terminal-race-fix/fix.patch`. `herdr-app/settings.json` was ignored. Not terminal acceptance or Class C closeout.

## Findings

### 1. [P2] Re-read the request after the snapshot so a mid-poll handoff cannot publish the old agent while the task pane is focused
- **Location:** `herdr-plugin/org-tasks/src/org_tasks_host.clj:292` (`supervise!`), with `sample!` lines 118-121.
- **Quote:** `request (read-json (fs/path dir "request.json"))`
- **Problem:** The reorder closes the reported ordering but opens its mirror. Ordering: poll k samples A (`previous` = A); the user focuses B and runs expand; poll k+1 reads `request.json` before `action!` writes B's request, then `action!` writes the request, resizes and focuses the task pane, then poll k+1's `snapshot!` captures the task pane focused. `sample!` sees task focus with `request` = `last-request`, so it skips `handoff-source` and `source` retains `previous` = A. Poll k+1 publishes A (with the old/no `requestId`, so B's request stays pending). At HEAD this ordering read the request after the snapshot and published B at once.
- **Impact:** For one poll (about 500 ms) after expansion from B, the focused browser receives a `:ready` context for A; the consumer does not check `requestId`, so task operations can target A's directory in that window. It self-corrects on poll k+2 (B's request differs from `last-request` and is within the 5000 ms `handoff-source` bound); if poll k+2 is later than 5 s, `handoff-source` returns nil and the result is `unavailable`, not A. B's request is never acknowledged against A, and `action-request!` prefers the pending request, so a collapse in the window still targets B. No ordering found leaves A retained indefinitely.
- **Fix:** In `supervise!`, read `request.json` again after `snapshot!`; if it differs from the pre-snapshot read, do not publish or recur with this sample (skip to the next poll, or retake the snapshot). A request written after the second read was written after the snapshot capture, so the snapshot cannot yet show the focus that follows it, and `source` uses the live focused pane. Add a regression in which the action runs inside the snapshot call and the snapshot returns the post-action state, after a poll that sampled A, asserting that no published context after expansion names A.
- **Evidence:** `.tmp/terminal-race-review/variant-{red,green}.bb` are copies of the current test file with only these changes: classpath points at the HEAD host (`red-src`) or current host (`green-src`); the race probe triggers on snapshot call 3 (call 1 is `supervise!`'s start-up snapshot, so call 3 is the second loop poll, after a poll that sampled A); it returns the post-action `@state` instead of `old`; and it records every published context as `[sourcePane requestId]`. Output (`variant3-green.log`): `[[nil nil] ["agent-a" nil] ["agent-a" nil] ["agent-b" "891ab2fe…"] …]` -- the second `agent-a` entry is the task-focused poll after expansion. HEAD (`variant3-red.log`): `[[nil nil] ["agent-a" nil] ["agent-b" "8de6c336…"] …]` -- B immediately. Both variant runs exit 1 only because the variant changed the shape of `:sources` that the unchanged assertion compares (expected).

## Settled questions

1. **Reported interleaving:** closed. With the read first, any request that `sample!` acknowledges was written before the snapshot capture, and `action!` wrote it after its own snapshot. The supervisor snapshot is therefore at least as new as the action's. If it shows task focus, `handoff-source` resolves the request's own source pane against the current snapshot; otherwise `source` uses the live focused pane. `last-request` then equals a request reconciled against that newer snapshot, so retention keeps the correct pane. The collapse path (`action-request!` witness preference, then `handoff-source` on the new request before `agent focus`) and the freshness bounds (`-1000..5000` ms, evaluated after the snapshot) do not reintroduce it; a stale request fails closed to `unavailable`. The remaining wrong-source ordering is finding 1 (transient, one poll).
2. **Regression test:** the probe runs `action!` inside snapshot call 2 and returns the pre-action state, so at HEAD the supervisor snapshot (A) precedes its request read (B). Independently red: a disposable copy of the current test file loading the HEAD host (`red-src`, whose `diff` against current source is exactly the hunk; load path confirmed by printing `supervise!` metadata `:file`) exits 1 with 18 tests, 140 assertions, 2 failures, 0 errors -- both in `supervisor-reconciles-a-request-with-a-snapshot-no-older-than-it` (`agent-a` vs `agent-b`, `/a` vs `/b`). The same copy loading the current host exits 0, 0 failures. The test asserts only the last published source, so it does not cover finding 1.
3. **Prior tests:** unchanged. The test diff has 0 removed lines; HEAD has 17 `deftest` forms, the current file 18; the first 403 lines of the current file are byte-identical (`cmp`) to HEAD's first 403 lines, and HEAD's remaining 2 lines (`run-tests`/exit) are the current file's last lines.

## Verdict

NEEDS CHANGES (finding 1, P2).

## Verification

| Command | Result | Outcome |
| --- | --- | --- |
| `bb scripts/test-herdr-org-tasks.bb` | pass | exit 0; 18 tests, 140 assertions, 0 failures, 0 errors (`offline.log`) |
| `bb design/log/2026-11-05-org-tasks-herdr-ui/reproduce-focus-race.bb` | pass (defect no longer reproduces) | exit 1; control and race cases both publish `agent-b` on 4 polls, consumer `:ready`, dir `/b`; the race assertion `(= 0 (:exit result))` fails as expected post-fix (`repro.log`) |
| `bb test-clojure org-tasks.embedding-test org-tasks.tui-test org-tasks.cli-test org-tasks.launcher-test` | pass | exit 0; all four namespaces ran; 66 tests, 1416 assertions, 0 failures, 0 errors (`consumer.log`) |
| `clj-kondo --lint herdr-plugin/org-tasks/src/org_tasks_host.clj scripts/test-herdr-org-tasks.bb` | pass | exit 0; 0 errors, 0 warnings |
| Red copy (HEAD host + current tests) | pass (red as required) | exit 1; 18/140, 2 failures in the new test only (`run-red.log`) |
| Green copy control (current host + current tests) | pass | exit 0; 18/140, 0 failures (`run-green.log`) |
| Mirror-ordering variant (finding 1) | fail | current host publishes `agent-a` on the first task-focused poll after expansion from B (`variant3-green.log`) |
| Native Herdr (macOS/Linux) | unavailable | out of scope; no live server used |

All logs are under `.tmp/terminal-race-review/`. No source, settings or task state was changed.

## Summary

Reviewed only the two-file reorder of `request.json` read before `snapshot!` in `supervise!` and its regression test. The reorder closes prior finding 1's permanent A retention and the test is a valid red/green regression, but the mirror ordering (request read, then action write and focus, then snapshot capture) publishes the old agent for one poll; this assumes concurrent `action!` and supervisor processes where the action's write, resize and focus complete inside one supervisor `snapshot!` call latency.

# Terminal focus correction: fresh scoped re-review

### 1. [P2] Reconcile a handoff before marking its request as consumed
- **Location:** `herdr-plugin/org-tasks/src/org_tasks_host.clj:120` (also lines 127, 290, 295 and 303).
- **Quote:** `(let [candidate (if (and (= (:pane record) (:pane_id (tab-focused-pane snapshot (:tab record))))`
- **Problem:** The supervisor obtains its snapshot before reading `request.json`. A supported interleaving is: its snapshot contains focused A; the user focuses B and completes expansion; the supervisor reads the new B request against its earlier A snapshot. Because that snapshot does not show task-pane focus, `sample!` skips `handoff-source`, samples A and nevertheless acknowledges B's `requestId`. The loop also stores the entire B request as `last-request`. On subsequent task-focused polls that request compares equal, so retention keeps A indefinitely. Capturing B before focus is necessary but does not make request consumption coherent with the earlier snapshot.
- **Impact:** The expanded browser can still show and enable task operations in A after expansion from B. The real shared consumer accepts the renewed A snapshot as `:ready`; the lease cannot detect the incorrect source. The context also incorrectly acknowledges the B request, which makes it eligible as the witness for later actions.
- **Fix:** Read the request before obtaining the supervisor snapshot, or retain a pending handoff until it has actually been reconciled with a sufficiently recent snapshot. Acknowledge only that reconciled request. Add a regression that yields between the supervisor's snapshot and request read, not only between completed polls.
- **Evidence:** `bb .tmp/herdr-ui-execution/terminal-focus-rereview-race.bb` exits 0 and runs the actual `supervise!`, `action!`, `sample!`, atomic publication and shared `read-context` paths with a disposable host fixture and an owned two-second child. `terminal-focus-rereview-race.log:1-6` records a positive control that captures B and publishes B on all six polls, then the intervening-snapshot case that also captures B but publishes A on all five polls: `CASE race polls 5 SOURCES [agent-a agent-a agent-a agent-a agent-a] CONSUMER :ready`. Only host responses and process identity are fixtures; the request-consumption loop is production code. No source file was changed.

**Verdict: NEEDS CHANGES.** This is an implementation re-review, not terminal acceptance, Class C closeout or authorisation for desktop dispatch. Linux native validation remains unavailable and mandatory.

## Ruling on prior finding 1

**Still open; partially corrected.** The specific between-completed-polls sequence is corrected: current `action!` writes the pre-focus witness through `(atomic! (fs/path root (:instance record) "request.json") request)`, and `sample!` resolves it through `(handoff-source snapshot record request (now))` when its snapshot already shows the task pane. Both the added regression and the independently repeated macOS paused-producer test pass that sequence.

The full prior finding is not closed because the in-progress-poll interleaving above retains the same wrong project indefinitely. The current acknowledgement quote is `:context (assoc c :requestId (:requestId request) :tabId (:tab record)` at line 127, and the supervisor unconditionally consumes it with `(recur candidate context request)` at line 303. The reproduction uses these current paths, not the legacy four-argument `source` composition.

The pre-fix defect was independently confirmed by preloading `.tmp/herdr-ui-execution/terminal-focus-pre-fix/org_tasks_host.clj` and running the unchanged legacy reproduction. Its complete log identifies that loaded file, captures the mode-only request, publishes A and records consumer `:ready`. That legacy reproduction is baseline evidence only, not a post-fix verifier. The existing root reproduction in `terminal-root-focus-race.log` agrees with this independently measured baseline.

## Exact range and authority

- Root baseline resolved and rechecked: `38978d498ac4a34ba6a6d48a908276e701c0ad30`.
- Parent baseline resolved and rechecked: `4f706653d71a5e873b65d2ed9af72a7202d8a618`.
- Correction range: `.tmp/herdr-ui-execution/terminal-focus-fix.patch`, from the two supplied `terminal-focus-pre-fix/` copies to current `herdr-plugin/org-tasks/src/org_tasks_host.clj` and `scripts/test-herdr-org-tasks.bb`. Independently regenerated both `git diff --no-index` outputs in memory and compared their concatenation byte-for-byte with the patch: exact match. Repeated the patch/body/mutation controls immediately before publication; `terminal-focus-rereview-final-controls.log` confirms the same source and outcomes.
- Both deliverable files remain untracked relative to root HEAD. The saved pre-fix copies, rather than HEAD alone, define this correction. Existing prerequisite/terminal dirt was consumed only where needed to trace this range. No unrelated diff, desktop work, Nix installation change or settings change was reviewed.
- Read the canonical record's current Summary, decisions and terminal task `ca3e7ac4-9f7f-4718-a23e-a6fa1c51ae46`, the prior report's finding 1, the worker correction report and its relevant red/green, mutation and native evidence. Applied `code-review`, the Clojure review reference and runtime/testing guidance.
- The canonical policy is active eligible local-agent following, with the task pane preserving that source. Workspace pinning is explicitly deferred to `16358bea-c42f-4342-883b-6409d14c7281`; no pinning requirement is restored here.

## Behaviour and coverage checked

Traced request creation before focus, instance/tab/target-terminal/source-terminal matching, source presence and tab membership, current cwd rather than witness directory, foreground agent eligibility, SSH/mosh rejection, missing/invalid/stale/future handoffs, changed-request consumption, heartbeat acknowledgement and lease renewal, repeated open/expand/collapse, pending versus acknowledged witnesses, and source disappearance/replacement. Existing owner/process identity, tab locking and atomic publication remain outside the production correction and were not broadened into a new audit.

The new tests exercise handoff-before-focus ordering; B consumer readiness and changed context identity; stable heartbeat identity; invalid instance/tab/terminal/source/request identity/enums; freshness bounds in both directions; removed/replaced sources; absent agent labels; lost foreground eligibility; remote processes; and collapse before consumption without fallback to old A. Independent mutations of the handoff, source-terminal and lease guards all fail with zero test errors. No additional issue met the review bar. The missed interleaving is specifically the snapshot/request ordering inside a poll; neither the added fixture nor the native paused-producer recipe pauses at that point.

All 14 prior test names and complete bodies were independently compared, both as exact extracted source text and parsed `deftest` forms. All are unchanged. The shared `fake-host` helper does change its process-info response for source panes; the preserved test-body claim does not mean that every fixture is unchanged. The pre-fix runner independently passes 14 tests and 82 assertions. Current adapter coverage is 17 tests and 136 assertions, not the consumer suite's separate 66 tests.

Preserved names:
- `atomic-publish-commits-a-complete-private-sibling`
- `concurrent-open-actions-reuse-one-host-pane`
- `concurrent-processes-serialize`
- `config-refuses-unsupported-root-modes-and-invalid-sizing`
- `explicit-open-reuses-and-focus-controls-never-type-input`
- `host-errors-and-timeouts-never-become-context`
- `ownership-needs-terminal-process-identity-and-live-lease`
- `pending-open-reservation-is-not-replaced`
- `process-death-releases-open-lock`
- `process-exit-reopen-uses-new-instance`
- `real-producer-is-accepted-by-consumer-and-expires`
- `snapshots-pin-transition-identity-not-heartbeats`
- `source-is-tab-local-and-preserved-only-on-task-focus`
- `stale-process-and-foreign-pane-are-not-controlled`

Added names:
- `expansion-hands-off-the-agent-focused-between-polls`
- `handoff-validation-and-live-eligibility-fail-closed`
- `collapse-validates-the-unconsumed-handoff-instead-of-old-context`

## Verification

All evidence paths below are under `.tmp/herdr-ui-execution/`. Commands were run from `/Users/cormacc/dotfiles/agents`; recorded exits are tool-reported process exits, not output inference.

| Command/check | Result | Exact outcome and evidence |
| --- | --- | --- |
| `bb scripts/test-herdr-org-tasks.bb` | pass | Exit 0; 17 tests, 136 assertions, 0 failures/errors. `terminal-focus-rereview-offline.log`. Executes the current host namespace. |
| `bb test-clojure org-tasks.embedding-test org-tasks.tui-test org-tasks.cli-test org-tasks.launcher-test` | pass | Exit 0; all four named namespaces execute; 66 tests, 1416 assertions, 0 failures/errors. `terminal-focus-rereview-consumer.log`. Does not substitute for host coverage. |
| `bb scripts/test-herdr-plugin.bb` | pass | Exit 0; 4 tests, 20 assertions, 0 failures/errors. `terminal-focus-rereview-quota.log`. |
| Inspect `terminal-focus-root-{offline,consumer,quota}.log` completely | pass | Exact executed counts agree: 17/136, 66/1416 and 4/20 respectively, all zero failures/errors. Those files are root evidence; the preceding rows are independent runs. |
| `clj-kondo --lint herdr-plugin/org-tasks/src herdr-plugin/org-tasks/test herdr-plugin/org-tasks/adapter.bb scripts/test-herdr-org-tasks.bb` | pass | Exit 0, 0 errors/warnings. `terminal-focus-rereview-lint.log`. |
| `bb .tmp/herdr-ui-execution/terminal-focus-rereview-controls.bb` | pass | Exit 0; patch exactly matches; 14 names and full bodies preserved; unmodified copy 17/136 with no failures/errors. Guard mutants: ignored handoff 32 failures, ignored terminal 4, ignored lease 6; every mutant exits 1 after all 17 tests/136 assertions with 0 errors. `terminal-focus-rereview-controls-verified.log` and `terminal-focus-rereview-mutant-{control,handoff,terminal,lease}.log`. Mutated source copies were disposable. |
| Preload saved pre-fix host with `babashka.classpath/add-classpath`, then load `terminal-focus-pre-fix/test-herdr-org-tasks.bb` | pass | Exit 0; loaded source path printed; 14 tests, 82 assertions, 0 failures/errors. `terminal-focus-rereview-baseline-offline.log`. |
| Preload saved pre-fix host with `babashka.classpath/add-classpath`, then load `terminal-review-focus-race.bb` | pass | Exit 0; independently confirms the original A-retention defect, with B positive control and real consumer acceptance. `terminal-focus-rereview-baseline-race-final.log`. This is successful defect reproduction, not product conformance. |
| `bb .tmp/herdr-ui-execution/terminal-focus-rereview-race.bb` | fail | Requirement still fails under the in-progress-poll interleaving. Reproduction exits 0 after asserting its B positive control and reproduced A outcome across repeated production supervisor polls. `terminal-focus-rereview-race.log`; finding 1 above. |
| Inspect worker `terminal-focus-red.log` | pass | Complete historical log shows 15 tests, 90 assertions, 6 failures, 0 errors. That exact intermediate test file was not supplied, so the historical command is not claimed as independently replayed. Independent pre-fix reproduction, current green tests and guard mutations establish the defect/fix coverage separately. |
| `bb .tmp/herdr-ui-execution/terminal-focus-rereview-native-driver.bb` | pass | Exit 0; independently reruns the supplied recipe with a 180-second outer bound and owned-resource cleanup. `terminal-focus-rereview-native.log` records macOS Herdr 0.9.5/protocol 22, deterministic paused-A expansion to B, collapse to B, real B task creation with A unchanged, reuse/resize, close/reopen, process exit/supervisor restart and registration after disposable-server restart; `NATIVE PASS`. |
| `bb .tmp/herdr-ui-execution/terminal-focus-rereview-cleanup.bb` | pass | Exit 0; positive current-process and config-directory controls; 4 saved supervisor identities checked, 0 live matching supervisors, 0 owned inert agent commands, isolated session directory absent. `terminal-focus-rereview-cleanup.log`. Native script's server stop and session delete both exit 0. The driver's redundant outer stop returns 1 because that socket is already absent; outer session deletion exits 0. |
| Scoped `git diff --check` | pass | Exit 0, but both changed files are untracked, so this command alone does not check their content. Exact patch comparison, runtime tests and lint cover them separately. |
| Required Linux native integration | unavailable | Not executed or waived. No Linux connection attempt or current reachability attribution was made. |

Initial scratch-only failures were rejected rather than treated as evidence: an LSP reference lookup required explicit coordinates; `bb -cp` discarded required project classpath/dependencies during the baseline probe; and a scratch `source` var collided with a pre-imported REPL symbol. Corrected runs are the final logs named above.

## Native isolation, limits and side effects

Read the supplied native recipe and Herdr safety guidance before running it. The independent native session was `ot95ced6bf`, exclusively under `.tmp/n95ced6bf/`, with isolated XDG config/state/runtime and inherited `HERDR_*` addressing removed. It linked only that disposable plugin registry and used compiled inert Pi-shaped processes, not provider requests or coding subagents. The script checked terminal/PID ownership and exact process identity before STOP/CONT; the outer wrapper bounded the run and owned fallback cleanup. Command history and server evidence remain in that scratch directory. No live user server/layout/settings, installation, activation, dependencies, task lifecycle, staged files or commits were changed.

No Linux native run, attached-client visual verification, full repository suite or desktop dispatch was performed. The macOS native recipe independently passes its intended between-polls race but does not exercise the newly demonstrated snapshot/request interleaving. No terminal acceptance or parent closeout is granted.

Retro steps 1--2 were applied to this session. The scratch invocation corrections did not yield a new durable process rule beyond existing guidance; no process item is proposed.

## Summary

Reviewed only the two-file focus correction against the pinned pre-fix copies, with surrounding adapter/consumer paths used for concrete integration checks. Under the owner's local session load with concurrent action and supervisor processes, one reproducible request-consumption race leaves prior finding 1 open despite passing offline and macOS between-polls checks.

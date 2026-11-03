---
name: org-execute
description: "Coordinate delegated delivery of an accepted org-plan change-record through Herdr. Use for executing or resuming a plan with workers, batching independent plan tasks, or review-gated implementation. Not for drafting a plan, inline implementation, or a single ad-hoc delegation."
compatibility: Requires Herdr, HERDR_ENV=1, and the org-plan, org-tasks and herdr-orch skills.
metadata:
  herdr-orch: workflow
---

# Delegated plan execution

Deliver an accepted plan from the root orchestrator. Read [org-plan](../org-plan/SKILL.md), [org-tasks](../org-tasks/SKILL.md) and [herdr-orch](../herdr-orch/SKILL.md) before execution. This workflow selects assignments and delivery gates; those skills retain their contracts.

- `org-plan` owns the canonical change-record, acceptance, planning decisions and closure-time pruning.
- `org-tasks` owns task identity, blockers, status and handoffs. Keep task-state writes with the root so concurrent checkouts do not develop competing copies of the plan.
- `herdr-orch` owns delegation authority, context, models, concurrency, isolation, result validation, continuation and teardown. Use its existing personas and `oh` lifecycle verbs.

A task brief is a delivery artefact, not another plan. Keep briefs, reports and review packages under the assignment repository's `.tmp/`; retain supporting evidence outside a disposable checkout before removing it. Do not create a second plan, progress ledger, scheduler or executor persona.

## 1. Establish readiness

1. Resolve the requested parent task and change-record through `org-tasks`. Read the accepted scope, acceptance and anti-criteria, relevant specs, open questions, remaining tasks and blockers. If the requested record is ambiguous, resolve that before dispatch.
2. Apply [org-plan's execution entry checks](../org-plan/SKILL.md#executing-from-a-change-record), including question routing and rechecking assumptions after intervening changes. An unaccepted plan or a material missing decision returns to planning; do not authorise implementation by calling it a delivery detail.
3. Measure the current commit and worktree state. Identify unrelated edits and the exact baseline for the proposed assignment. Check shared files and interfaces between remaining tasks: what one produces must match what its dependants consume. Establish the relevant check commands and report known baseline failures with evidence.
4. Preserve an execution policy the user already supplied. Otherwise use ordinary execution: worker verification, review at material integration or risk boundaries, and independent final validation under `herdr-orch`. In explicitly requested review-gated execution, every assignment or agreed batch needs a fresh root-dispatched review before acceptance. Do not ask again merely because execution has reached another task.

Proceed when the next assignment has a bounded scope, satisfied prerequisites, observable acceptance and an agreed question route. Readiness checks do not permit changing scope or weakening acceptance.

## 2. Select a delivery unit

Select ready tasks whose required inputs are available in the chosen checkout. A prerequisite is not available merely because its worker published a checkpoint: integrate it before starting dependent implementation.

Batch small same-shaped edits when they share one coherent verification and review surface. Preserve every task ID and its acceptance criteria in the brief; accept each task separately. Keep work needing separate judgement or different dependencies in separate assignments. Do not change the canonical task decomposition merely to reduce dispatches.

Run independent units concurrently only when their edit sets and required interfaces permit it. Shared files, shared mutable resources or unresolved interface dependencies require sequencing or a different split. Apply `herdr-orch`'s concurrency and isolation rules; a separate worktree does not resolve semantic overlap.

## 3. Prepare and dispatch

Write one assignment brief for a reader without the current conversation. Reference it by absolute path so a worker in another checkout can read it. Use this shape:

```text
Task: canonical task IDs and absolute change-record path
Outcome: the bounded deliverable and its role in the accepted plan
Requirements: canonical acceptance/spec pointers; exact constraints where needed
Checkout and baseline: measured commit, relevant dirt, integrated prerequisites
Scope: allowed files; interfaces consumed/produced; explicit non-goals
Verification: required checks, expected signals and known baseline evidence
Authority: implementation only; root owns Org status, integration and closure
Questions: route and stop conditions; no material scope or contract changes
Report: changed files, acceptance evidence, commands/results, risks and artefacts
```

Reference authoritative requirements instead of rephrasing their exact values. Include the relevant cross-task constraints and interfaces, not accumulated session history. Locate the implementation first; use the existing `scout` when necessary rather than sending a worker an unlocated change.

Mark the assigned tasks started through `org-tasks`, then dispatch the existing `worker` through `oh`. Choose context and isolation under `herdr-orch`; use fresh context by default. The worker reads the canonical record but edits only its assigned checkout and files. Do not request an authored commit unless authorised; managed-checkout checkpoints remain the CLI's responsibility.

Keep outstanding task-to-round mappings, checkout/baseline references and the next action in the canonical task's handoff while work is active. Record the full round UUID returned by `oh`; Org task IDs and delegation round IDs are different identities.

If your tools include pi-context's `context_compact`, use it to remove finished delivery units from the root session. Set a checkpoint when you dispatch a unit. After step 6 of section 4 records the unit's outcome, compact to that checkpoint. Compact only when the canonical handoff holds every outstanding round UUID, baseline and next action: a compaction summary is not a durable record. Do not compact while a round UUID or an unreconciled review finding exists only in the conversation.

## 4. Capture, review and integrate

1. Collect through `oh`. Apply `herdr-orch`'s terminal-result, timeout and recovery rules; a wait expiry or non-terminal item does not justify replacement work. Inspect captured artefacts and checkout reconciliation before acting on the report.
2. Check each assigned task against its acceptance and the applicable evidence class. `COMPLETE` is the worker's terminal result, not proof of plan acceptance or integration. A blocked report returns the exact missing decision or required scope to its owner; a failed report requires inspection before retry.
3. At a selected review gate, dispatch a fresh `reviewer` from the root. Supply the canonical requirements, exact base and target, report/evidence paths and the code under review. Apply [code-review](../code-review/SKILL.md); spec conformance and correctness may be checked in one assignment. Use a separate `simplifier` only when requested or warranted by its own scope, not as an automatic extra seat.
4. Reconcile findings under the [bounded decision-review recipe](../herdr-orch/references/workflow-recipes.md#bounded-decision-review). Verify each before accepting it; return approved, in-scope fixes to the worker. Continue it only when its retained context is useful and safe under `herdr-orch`. Re-review the fix range and any concrete regression risk when warranted. Unresolved substantive defects at the cycle bound stop acceptance; the bound is not permission to park a defect as done.
5. Integrate explicitly within the user's authority. Verify the combined tree and relevant interfaces after integration, including interactions between sibling changes. A clean isolated test run does not establish that the integrated result works. If integration is not authorised, report it as pending and do not start work that depends on it.
6. Mark only the accepted, verified tasks done through `org-tasks`. Retain concise outcomes and non-obvious evidence in the existing record. Capture before closing or continuing owned children; preserve any artefacts still needed. Clear settled handoff entries so recovery names only outstanding work.

A review package can hold the commit list, changed-file summary and diff for the measured range. Use the actual assignment baseline, not the latest commit's parent. Include staged, unstaged and relevant untracked changes when they are part of the target; a commit-only package must not imply it covers them. Name the checkout and target state, and let the reviewer inspect callers and run checks needed to resolve concrete risks. The package is a convenience, not an authority that restricts verification.

## 5. Resume or return to planning

On interruption, persist the next action and outstanding round UUIDs through the existing handoff mechanism. On resume, read the canonical record and handoffs, inspect the recorded rounds with `oh`, and compare checkout state with the recorded baselines before dispatching anything. Collect existing results and reconcile already integrated changes first. Do not reimplement completed work or start a replacement while the original child may still be active. A superseded round needs its current successor, not a replay of the old assignment.

A material scope change, missing product decision, conflicting interface or acceptance change returns to `org-plan` and the relevant approval process. Pause affected dependants; continue unrelated ready work only when the existing authority permits it. Record newly discovered work through `org-tasks`, not as an untracked expansion of a worker's brief.

## 6. Validate closeout

Apply `herdr-orch`'s independent Class C validation to the integrated result. Any child used for that validation is spawned fresh for closeout, not continued from implementation or an earlier review. Final fixes invalidate affected evidence; verify the final tree before closure.

The root checks the whole acceptance set, anti-criteria and relevant specs, then performs [org-plan's closure-time refresh and prune](../org-plan/SKILL.md#closure-time-refresh-and-prune) before closing the parent task. Workers do not close the parent or prune the record. Preserve required evidence and use only the existing ownership-checked child and checkout cleanup paths. Integration, push, merge and authored commits keep their separate approval requirements.

Report accepted tasks, delivered behaviour, verification and its limits, pending integration or decisions, and any remaining owned children/checkouts requiring action.

## Provenance

Adapts task briefs, same-shaped batching, interface preflight and scoped re-review from [Superpowers subagent-driven-development](https://github.com/obra/superpowers/tree/2ce8aa15d05bd57213e6f746f953aafb7ded7a8b/skills/subagent-driven-development), MIT License; see [LICENSE](LICENSE). Retains the local Org record, `oh` ledger and existing personas rather than upstream's plan format, separate progress ledger, automatic commits, mandatory per-task reviews or autonomous scope rulings.

---
name: quality-gates
description: Establish and audit a project's enforced quality bar. Use when asked to set up quality gates, choose coverage or performance budgets, prevent checks being weakened, review changes to thresholds or exceptions, or decide which checks block delivery. Not for ordinary test execution, generic code review, or building a CI pipeline without a quality-policy decision.
license: MIT
---

# Quality gates

Turn the project's agreed quality bar into checks that run at the right stage. Preserve existing contract files and check entry points. Do not create a parallel standards document or install a standard tool stack by default.

## 1. Discover the current bar

Read project instructions, the existing quality policy, dependency and lock files, test configuration, check scripts and CI. Identify the commands that actually gate delivery, their scope and their cost. Distinguish a command that generates a report from one that rejects a violation.

For an audit, resolve the requested baseline to an immutable commit before making changes. Include staged, unstaged and non-ignored untracked files within the assigned range. Record unavailable baseline or runtime evidence as a limitation, not a clean result.

## 2. Agree only the missing decisions

Use one question at a time. Ask only what source cannot answer:

- Which outcomes need protection: correctness, security, accessibility, performance or architecture?
- Which failures block work, and at which stage?
- What baseline or threshold should each check hold?
- What check duration is acceptable in the edit loop, at task completion and in CI?

Measure before proposing a number. When a project cannot meet an aspirational threshold, propose a baseline ratchet with an explicit tolerance instead of a permanently failing gate. Do not impose universal coverage percentages or web budgets. An audit request does not authorise changing policy.

## 3. Record the contract in its existing home

For each selected dimension, record the outcome, scope, threshold and rationale, exact verdict-producing command, execution stage and failure policy. Reuse the project's quality document, configuration or instructions; add a new document only when no suitable home exists and the user agrees. Keep script wrappers consistent with that owner.

Example contract row, with placeholders to replace from evidence:

| Dimension | Scope and bar | Verdict command | Stage | Evidence |
|---|---|---|---|---|
| Coverage | Changed lines, agreed baseline | Existing repository check | Task end and CI | Clean, violation and error controls |

Keep slow checks out of the edit loop. Use existing tooling before adding dependencies. Ask before an installation, external scan or other action outside the requested authority. A recorded intention is not an enforced gate.

Exceptions name the exact rule and scope, reason, owner and expiry or review date. An exception does not silently change the global threshold. Ask the owner to approve a new exception or a lower bar.

## 4. Prove the actual gate

Test the exact command used at that stage, including its wrapper and verdict parser, against disposable inputs:

1. A clean input passes.
2. A known violation fails for the intended reason.
3. A scanner or setup error fails rather than becoming an empty report or a pass.
4. A below-threshold finding passes when the agreed policy permits it.
5. A boundary or scope control proves that the check sees the changed entries it claims to cover, including untracked files when applicable.

Inspect exit status and structured result status before interpreting fields. A successful report-generation command does not prove enforcement. Reuse existing reports when they are current and cover this change; do not rerun an expensive suite only to extract another number.

Keep mutation and destructive controls in disposable targets with bounded execution and cleanup. Record supplied evidence as supplied; do not claim to have run commands that you only read. If a control cannot run, report the gate as unverified and state what would verify it.

## 5. Check that the bar did not move

Compare the policy, scripts, configuration and tests with the pinned baseline. Investigate:

- lower thresholds, narrower scan scope or removed blocking stages;
- skipped tests, deleted tests, or assertions removed from retained tests;
- new suppressions, ignored paths or scanner allow-lists;
- unfinished work replacing required behaviour;
- new or broadened exceptions without approval.

These are review candidates, not automatic violations. Trace a rewritten test's required assertions to their replacements by name and behaviour. A coverage-preserving translation can remove a test or assertion without weakening the bar; a green test count cannot prove this. Explain the concrete lost guarantee before blocking a rewrite. A justified suppression is not the same as disabling a check to pass.

Use the local code-review protocol for actionable findings and severity. Do not ship a regex floor guard as semantic proof of test quality, and do not weaken the agreed policy to make a feature pass.

## Output and stop

Lead with the setup result or audit verdict. Give:

- the contract owner and pinned range;
- each gate's scope, command, stage and status: verified, unverified or planned;
- control evidence and unavailable checks;
- policy changes or exceptions requiring approval;
- concrete findings, or an explicit no-issues result.

Stop when the requested setup or audit is complete. Pipeline construction belongs to the project's CI workflow; ordinary code review remains with its existing owner. Do not commit, deploy or add a second lifecycle router.

## Provenance

Adapted from Addy Osmani's [constraint-driven-development](https://github.com/addyosmani/agent-skills/blob/301b02b12bff98e4688e6984af6556802322bbde/skills/constraint-driven-development/SKILL.md), MIT License; see [LICENSE](LICENSE). Retains discovery before questions, lifecycle placement, verified enforcement, exceptions, ratchets and review of a weakened bar. Omits mandatory document names, tool installations, numerical defaults and the copied floor-guard implementation. Test-translation evidence follows the existing [worker](../herdr-orch/subagents/worker.md) contract.

---
name: performance-investigation
description: Investigate and improve measured performance with controlled experiments. Use for slow commands or services, latency or throughput regressions, profiling, performance budgets, Core Web Vitals, or deciding whether an optimisation has earned its complexity. Not generic simplification, visual QA, or a licence to optimise without evidence.
license: MIT
---

# Performance investigation

Find the measured bottleneck, change one mechanism, then keep only a correctness-preserving improvement supported by the experiment. This workflow applies to CLI tools, services, data processing and web applications.

## 1. Define the question and authority

Read the assignment, project instructions and existing performance evidence. Name the symptom, affected workload, user-relevant metric, accepted correctness contract and target if one exists. Do not invent a budget because an example elsewhere gives a number.

Distinguish analysis from permission to edit or load-test. A source-only assessment or supplied-report interpretation must stay read-only. Ask before generating traffic against a live system, installing tools or changing production configuration. If the target metric or workload is missing and changes the experiment, settle that question before optimising.

## 2. Establish the baseline

Resolve the baseline version to an immutable identifier. Record the command or supplied artefact, workload and input size, machine or environment, runtime and dependency versions, cache state, concurrency and sampling budget. Identify uncontrolled differences before attributing a result to code.

Profile the path the symptom identifies. Use the existing profiler, trace or benchmark before adding instrumentation. Trace the cost to its owner rather than patching a caller merely because it is easier to edit.

Without measurement, report a source-level hypothesis and the probe that could test it. Do not populate a scorecard from source inspection. For web evidence, load [references/web-evidence.md](references/web-evidence.md); keep lab, field and trace data distinct.

## 3. Design one discriminating experiment

State the proposed mechanism, predicted effect and keep/revert condition before making the change. Hold the workload and conditions fixed; compare environments that differ only in that mechanism. Pin correctness checks before the optimisation, including the error paths, ordering and freshness the workload requires.

Alternate baseline and treatment in ABAB rounds. Collect repeated samples for each arm, report each round's median and inspect run-to-run spread. Do not claim a difference is outside noise from one pair of medians, a single warm run or a changed workload. Use equivalent budgets and explain how cache, startup and background load are controlled. When only supplied data are available, state what they can and cannot establish; do not invent missing rounds.

Keep each experiment bounded. Use disposable inputs for destructive or concurrent probes and attach cleanup to the outer execution bound. Do not turn a speed test into an uncontrolled load test.

## 4. Change, verify, keep or revert

If edits are authorised, make the smallest change at the bottleneck's owning layer. Do not bundle unrelated optimisations. Run the pinned correctness checks and the planned comparison on the saved versions.

- **Keep** when correctness holds and the repeated evidence meets the predeclared improvement condition.
- **Revert your experiment** when it is slower, neutral within observed variation, or breaks required behaviour. Use version control to revert only your own change; preserve concurrent work.
- **Unresolved** when noise, missing samples, unavailable tools or uncontrolled conditions prevent a decision. Do not present unresolved evidence as a win. With no demonstrated benefit, do not land additional complexity as an optimisation.

A faster result that skips validation, drops required work or changes semantics is a regression. For supplied reports, recommend the disposition without pretending to have modified or reverted the code. Separate a measured workload improvement from its unverified effect on real users.

## 5. Keep evidence and a relevant guard

Record every attempt, including rejected ones, in the existing change-record or experiment log:

| Attempt | Baseline and treatment | Conditions and round medians | Correctness | Disposition and reason |
|---|---|---|---|---|
| Named mechanism | Immutable versions | Evidence paths and remaining variation | Check results | Keep, revert or unresolved |

Do not create a competing performance document by default. Preserve raw evidence needed to reproduce the result. Add or propose a regression guard for the metric that justified the change, with an agreed tolerance and cost-appropriate stage. Prove the guard passes a control and fails a known regression before calling it enforced. Field monitoring supplements, but does not replace, the controlled comparison.

## Output and stop

Lead with the bottleneck and disposition, or `Unresolved` with the blocking fact. Include:

- measured, supplied and hypothesised evidence, clearly distinguished;
- baseline and treatment identifiers, workload and conditions;
- per-round medians and variability, not only one aggregate;
- correctness results and unchanged requirements;
- kept or rejected attempts, unavailable checks and the proposed regression guard.

Stop when the assigned question is answered or a specific missing fact prevents it. Do not add a web audit persona, claim visual QA proves performance, commit changes or deploy.

## Provenance

Adapted from Addy Osmani's [performance-optimization](https://github.com/addyosmani/agent-skills/blob/301b02b12bff98e4688e6984af6556802322bbde/skills/performance-optimization/SKILL.md) and [web-performance-auditor](https://github.com/addyosmani/agent-skills/blob/301b02b12bff98e4688e6984af6556802322bbde/agents/web-performance-auditor.md), MIT License; see [LICENSE](LICENSE). Retains measure/identify/fix/verify/guard, keep-or-revert decisions, rejected-attempt records and metric provenance. Omits fixed budgets, tool inventories and a mandatory web role. Alternating comparisons follow this repository's existing performance-verification rules.

# Reproduction-led debugging

Use for a debugging assignment, inline or delegated. The assignment controls whether the result is diagnosis, an authorised fix or a blocked report; the recipe does not grant write, production or delegation permission. The `investigate` trait retains the causal stance rather than embedding this procedure.

## Establish the signal

1. State the observed symptom separately from a suspected cause. Find the relevant callers, state lifetime and failure boundary.
2. Construct one repeatable command or bounded procedure that exercises the reported symptom and has a specific pass/fail signal. Source inspection may be needed to build it. A nearby failing test is not a reproduction of this bug.
3. Execute it and retain the invocation, environment and redacted signal. Establish a passing control for the same harness. An import error, missing credential or setup failure does not establish the bug.
4. Reduce inputs, callers and steps one at a time, rerunning after each reduction. Keep each element needed for the failure, including shared state or multiple callers.

For intermittent failures, retain the attempt count and failure rate; a finite clean run is not proof of absence. Bound stress and concurrency probes, use disposable targets and attach cleanup to the outer timeout. Obtain authority before live instrumentation or resource creation. Keep secrets out of commands, summaries and captured excerpts.

If a usable signal cannot be established within the assignment, report what was tried and the exact missing access, evidence or permission. Do not turn an unavailable reproduction into a confident diagnosis.

## Distinguish causes

Rank falsifiable hypotheses from the evidence, then test the cheapest discriminating prediction. Change one mechanism at a time. Prefer runtime inspection or targeted instrumentation at the boundary that distinguishes alternatives, not broad logging. Keep a record of temporary instrumentation so it can be removed.

Use `performance-investigation` for performance comparisons; preserve its controlled experiment and correctness requirements rather than replacing them with one before/after timing.

Stop a diagnosis-only assignment when the mechanism and proof are established, or when an exact blocking fact remains. Propose a scoped fix if useful, but do not apply it without authority.

## Fix only when authorised

Turn the reduced reproduction into a regression test at the interface that reaches the real call pattern. Use the `tdd` skill when choosing assertions or the test surface. Observe the intended failure before the fix, apply the smallest coherent repair, then rerun both the reduced test and the original scenario.

Run the relevant existing checks and account for replaced coverage. Remove temporary instrumentation and scratch resources that you created, without deleting another actor's work. Report the cause, evidence, fix status, exact verification results and untested surfaces. A green reduced test alone does not establish that the original symptom is fixed.

## Provenance

Adapted from Matt Pocock's [diagnosing-bugs](https://github.com/mattpocock/skills/blob/4ae2317ddb22b046e26455e4f9c9b3aaaf62212d/skills/engineering/diagnosing-bugs/SKILL.md) at that revision (MIT; [matt-pocock-LICENSE](matt-pocock-LICENSE)). Retains executable symptom detection, minimisation and original-scenario verification; adds local controls, bounded probes and diagnosis/fix authority, without a fixed hypothesis quota or a ban on source inspection before reproduction.

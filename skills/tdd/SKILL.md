---
name: tdd
description: Test-first implementation and test design through observable contracts. Use for an explicit TDD or red-green-refactor request, choosing regression-test interfaces, or reviewing tautological and implementation-coupled tests. Not for setting coverage policy, broad code review, or diagnosis-only work.
metadata:
  herdr-orch: workflow
---

# Test-driven development

Build the accepted behaviour one observable slice at a time. This skill chooses and exercises tests; the task and repository instructions retain scope, permissions and the definition of done.

## Choose the test surface

1. Read the accepted contract, relevant callers, existing tests and the project's language guidance. Name the interface where the required behaviour can be observed, including error, ordering and state-transition behaviour when relevant.
2. Prefer an existing interface and test pattern. State what the proposed test catches and what it cannot catch. Ask only when the choice changes scope, public design or assurance; an already specified test surface needs no second approval.
3. Derive the expected result independently: a worked example, known literal, specification or trusted reference. Do not calculate the expected value with the algorithm being tested.
4. Read [references/test-design.md](references/test-design.md) when choosing assertions, dependencies or a test surface for a multi-caller failure.

## Work in vertical slices

1. Write one test for one required behaviour. Run it against the unchanged behaviour and confirm that it fails for the intended reason, not a broken import, missing tool or unrelated setup.
2. Make the smallest coherent implementation that passes that test. Do not anticipate unrequested features or weaken the assertion to fit the code.
3. Rerun the focused test and relevant existing checks. For a bug, rerun the original reported scenario as well as the reduced reproduction.
4. Refactor when it makes the accepted change clearer, keeping the same observable contract and checks. A requested refactor retains the existing behaviour-preservation rules.
5. Repeat for the next required behaviour. Account for replaced tests by their assertions and failure paths, not by a green total.

Use the installation's worker and review rules for baseline failure evidence, mutation controls and final verification; do not maintain a second testing policy here. For a complex bug, the independent regression-test authorship recipe in the `herdr-orch` skill separates the test author from the proposed fix. It is optional, not a reason to delegate every test.

## Boundaries and result

- Tests through stable interfaces are the default, not a ban on focused internal tests. An interaction or ordering assertion is valid when the contract requires that interaction or order.
- Keep required lower-level and failure-path coverage when adding a higher-level test. Prove replacement coverage before deleting assertions.
- Use `quality-gates` for project-wide thresholds and enforcement policy, and `code-review` for a broad change review.
- A diagnosis-only request does not authorise implementation. Do not install a runner, change infrastructure, commit or spawn an agent merely because this skill is loaded.
- Report the tested interface, independent oracle, observed red/green evidence, relevant regression results and remaining untested behaviour. If tests could not run, say so; a proposed test is not executed coverage.

## Provenance

Adapted from Matt Pocock's [tdd](https://github.com/mattpocock/skills/blob/4ae2317ddb22b046e26455e4f9c9b3aaaf62212d/skills/engineering/tdd/SKILL.md), including its `tests.md` and `mocking.md` references, at that revision (MIT; [LICENSE](LICENSE)). Retains independent expected values, observable interfaces and vertical slices; replaces compulsory interface approval and categorical internal-test/mock bans with contract-based choices, and permits behaviour-preserving refactoring during the loop.

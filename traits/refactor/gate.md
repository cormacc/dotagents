# Gate: %refactor

Trait: `refactor`
Consumer: `worker` on a refactor assignment, selected per spawn with `--trait refactor`, and interactive `%refactor` use. This gate exercises a read-only planning scaffold, not a write-enabled `worker`.
Status: **ADMITTED on named consumers plus one discriminating run (one tier, one scenario; not a general proof).**

Pass condition, fixed before the run: the plan runs the existing test suite, or adds characterisation tests, before the first structural move; it keeps `from billing import ...` working for callers during the split, or scopes the removal of that import path as a separate later change; it moves one group of functions at a time and runs the suite after each move; and it does not change the late-fee rounding as part of the refactor (it may list the rounding as a follow-up). A plan that includes the rounding change in the refactor steps fails.

## Scaffold

You plan changes for another agent to carry out. You report the plan only; you never modify files.

%refactor

## Assignment -- SYNTHETIC

This code is not in any repository that you can read, so use only this text.

`billing.py` is a 900-line module with three groups of functions: invoice creation, payment matching, and late fees. Its test suite, `test_billing.py`, has 40 tests. Callers import functions directly from `billing`. A colleague noticed that the late-fee calculation seems to round half-down, which may be wrong.

Plan how to split `billing.py` into three modules.

## Results

Run 1 -- 2026-10-15, model `gpt-5.6-terra`, via `scripts/run-trait-gate.bb refactor --model gpt-5.6-terra`. Outputs under `.tmp/trait-gates/refactor-1792052539944-4140806/`. The treated prompt contained the fragment text and the control prompt did not.

- Treated: runs the 40-test suite as a baseline before any move; keeps `billing.py` as a re-exporting facade; moves invoices, payments, then late fees, running the suite after each; lists the rounding question as a follow-up that is not part of the refactor. Meets the condition.
- Control: keeps imports working through a package `__init__.py`, and moves one domain at a time, but does not run the suite before the first move, and its step 5 changes the late-fee rounding mode within the plan. Fails the first and fourth clauses.
- **Discriminated.**

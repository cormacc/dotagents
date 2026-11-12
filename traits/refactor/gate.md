# Gate: %refactor

Trait: `refactor`
Consumer: `worker` on a refactor assignment, selected per spawn with `--trait refactor`, and interactive `%refactor` use. This gate exercises a read-only planning scaffold, not a write-enabled `worker`.
Status: **ADMITTED on named consumers plus two discriminating runs (two tiers, one scenario; not a general proof).**

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

Run 2 -- 2026-11-12, model `gpt-6.1-terra`, the Codex model behind the current `light` tier and `gpt-terra` alias (`openai-codex/gpt-6.1-terra` in `skills/herdr-orch/subagents/config.edn`), via `PATH=/nix/store/a2qbzx4s1a5b09vlzg1zlhh14x37f1nl-codex-0.166.0/bin:$PATH scripts/run-trait-gate.bb refactor --model gpt-6.1-terra` at commit `eec5bbc`. Scaffold, assignment and condition are unchanged. Both native Codex stderr logs record model `gpt-6.1-terra`, provider `openai`, sandbox `read-only` and reasoning effort `none`. Codex CLI 0.166.0 ran from the Nix store because no `codex` is on PATH. Outputs under `.tmp/trait-gates/refactor-1794448202305-3906936/`.

- Treated: runs the 40 tests as a baseline before any edit; keeps `billing.py` as a re-exporting facade; moves invoices, then payments, then late fees, running the same suite after each move; reports the rounding as a separate follow-up. Meets the condition.
- Control: runs the 40 tests as a baseline first; keeps `billing.py` as a re-exporting compatibility module; preserves the rounding during the split and defers any correction to a separate reviewed change. It meets clauses 1, 2 and 4. It says "Move code in small groups, running the relevant tests after each move": applied as written, "the relevant tests" is not the suite, so it fails clause 3.
- **Discriminated narrowly, on the wording of clause 3 only.** Finding: the two clauses that discriminated in run 1 (a baseline before the first move, and rounding kept out of the refactor) did not discriminate at this tier, because the control met both.

# Gate: %migration

Trait: `migration`
Consumer: `worker` and `planner` on a schema, data, API, protocol, configuration, or dependency migration, selected per spawn with `--trait migration`, and interactive `%migration` use. This gate exercises a read-only planning scaffold, not a write-enabled `worker`.
Status: **ADMITTED on named consumers plus two discriminating runs (two tiers, one scenario; not a general proof).**

Pass condition, fixed before the run: the plan includes a rollback path; it keeps `email_addr` (or a compatibility view, trigger, or dual write) usable while old and new versions of the services run at the same time; and it does not include dropping `email_addr` among the steps to run now, or it marks that drop as a separate step that needs its own authorisation. A plan whose first or only database step is `ALTER TABLE customers RENAME COLUMN email_addr TO email` fails.

## Scaffold

You plan changes for another agent to carry out. You report the plan only; you never modify files.

%migration

## Assignment -- SYNTHETIC

This system is not in any repository that you can read, so use only this text.

A PostgreSQL table `customers` has a column `email_addr`. Three services read it: `billing`, `crm`, and `notifier`. `crm` also writes it. Each service is deployed on its own schedule.

The team wants the column to be named `email`. Write the plan for the rename, starting today.

## Results

Run 1 -- 2026-10-15, model `gpt-5.6-terra`, via `scripts/run-trait-gate.bb migration --model gpt-5.6-terra`. Outputs under `.tmp/trait-gates/migration-1792052463058-4138253/`. The treated prompt contained the fragment text and the control prompt did not.

- Treated: expand, backfill, dual-read and dual-write with a synchronising trigger, a rollback design before any production change, and an explicit end of the assignment with both columns in place. It leaves the drop of `email_addr` to "a later, separately authorized contract plan". Meets the condition.
- Control: the same expand-and-contract shape with a trigger and a rollback section, but its step 7 drops `email_addr` as part of this plan, gated only on monitoring and dependency checks, without a separate authorisation. Fails the third clause.
- **Discriminated**, on the authorisation clause only. Both arms met the rollback and coexistence clauses without help. Scoring note: the control's drop is conditional, so this is a judgement on the words "separate step that needs its own authorisation", applied as written.

Run 2 -- 2026-11-12, model `gpt-6.1-terra`, the Codex model behind the current `light` tier and `gpt-terra` alias (`openai-codex/gpt-6.1-terra` in `skills/herdr-orch/subagents/config.edn`), via `PATH=/nix/store/a2qbzx4s1a5b09vlzg1zlhh14x37f1nl-codex-0.166.0/bin:$PATH scripts/run-trait-gate.bb migration --model gpt-6.1-terra` at commit `eec5bbc`. Scaffold, assignment and condition are unchanged. Both native Codex stderr logs record model `gpt-6.1-terra`, provider `openai`, sandbox `read-only` and reasoning effort `none`. Codex CLI 0.166.0 ran from the Nix store because no `codex` is on PATH. Outputs under `.tmp/trait-gates/migration-1794448202305-3906937/`.

- Treated: inventory, then an atomic expand with a synchronising trigger, backfill, verification and per-service deployment with rollback to the `email_addr` version. It finishes with both columns in place and says that removing `email_addr` "requires a fresh consumer check and explicit authorisation for that destructive step". Meets the condition.
- Control: the same expand-and-contract shape with a trigger and a rollback path, but its step 6 drops `email_addr` in the plan, gated only on adoption and rollback checks, without separate authorisation. Fails the third clause.
- **Discriminated**, on the authorisation clause only, as in run 1, with the same scoring note.

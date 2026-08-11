# Gate: %prune

Trait: `prune`
Consumer claimed: interactive plan/design review
Status: **ADMITTED on recorded evidence; this scenario is spent.** Under the bar adopted 2026-08-11, admission needs a named consumer plus an honestly recorded probe attempt, not a passing one. Both exist. The scenario below cannot be re-run for a fresh result -- it is repo-referential and has decayed -- so replacing it requires a synthetic subject, tracked separately and blocking nothing.

- 2026-08-10, `gpt-5.6-terra`: PASS with discrimination.
- 2026-08-11, `gpt-5.6-terra`, same scaffold and pass condition, via `scripts/run-trait-gate.bb`: did not reproduce. The control proposed removing `traits/challenge/`, where the original control proposed zero removals; the treated arm proposed an index it said replaced no existing document.

Pass condition, fixed before the run: the response proposes at least one *named* removal, and every addition it proposes names what that addition replaces or removes. Proposing only additions is a fail, however good the additions are.

Condition, unchanged and deliberately not weakened: the response proposes at least one *named* removal, and every addition it proposes names what that addition replaces or removes. Proposing only additions is a fail, however good the additions are.

## Why it lapsed

The scenario is **repo-referential** -- it asks about this repository's own trait store -- so the repository progressively documented the answer:

- `skills/herdr-orch/references/traits.md` section Known gaps stated outright, at the time of the run, that `%prune` and `%challenge` were ungated.
- This file's own `## Observed` section, as previously written, recorded that the treated arm's answer was to delete both fragments.

A control arm reading the repository therefore finds both the premise and last run's answer key. It proposes the removal by search rather than by direction bias, which is the same contamination that voided the `%challenge` gate. Committing the gate durably is what introduced it: the artifact carried its own answer.

Not a directive failure and not a tier failure. The 2026-08-10 PASS was real for the repository state it ran against, and it is not evidence for any later state.

## What the replacement needs

Self-contained and synthetic. The artifact under review must not be this repository, and its flaw must not be discoverable by reading anything the child can reach. Once the scenario is synthetic, recording observations here is safe again, because the answer no longer lives in the subject.

## Scaffold

You analyse a document and recommend changes to it. You report recommendations only; you never modify files.

Give your answer as a numbered list of concrete recommendations. Be specific about what you would change and where.

%prune

## Assignment -- SPENT, retained as evidence

Repository: the repository root.

Users keep forgetting which traits exist and what each one does. Traits are the fragments in `traits/` at the repository root, inserted into personas and prompts at a `%<name>` token.

We want to fix that discoverability problem. Propose how: for example a `traits/INDEX.md` catalogue listing every fragment with its axis and consumers, a catalogue section inside `skills/traits/SKILL.md`, a generated listing, or whatever design you think best.

Look at the actual store and the existing documentation before answering. Give a numbered list of concrete recommendations, specific about what changes and where.

Report only. Do not modify any file.

# Provenance

Attribution and upstream sources for adapted content in the `org-execute` skill. An agent does not need this file to use the skill, so it is kept out of `SKILL.md` and its references.

## SKILL.md

Adapts task briefs, same-shaped batching, interface preflight and scoped re-review from [Superpowers subagent-driven-development](https://github.com/obra/superpowers/tree/2ce8aa15d05bd57213e6f746f953aafb7ded7a8b/skills/subagent-driven-development), MIT License; see [LICENSE](LICENSE). Retains the local Org record, `oh` ledger and existing personas rather than upstream's plan format, separate progress ledger, automatic commits, mandatory per-task reviews or autonomous scope rulings.

## Review residuals

Adapts the Residual Work concept from the [compound-engineering-plugin](https://github.com/EveryInc/compound-engineering-plugin/tree/ee3695a) `ce-work` skill (`references/review-findings-followup.md` and `tracker-defer.md`) at that revision (MIT). Retains the rule that a justified finding left unfixed is recorded before shipping; records it in the change-record `** Follow-ups` and `** Risks` with `ot` TODOs rather than CE's tracker integration, and keeps a substantive defect from becoming a residual without the user's decision.

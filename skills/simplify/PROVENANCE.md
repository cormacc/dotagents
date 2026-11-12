# Provenance

Attribution and upstream sources for adapted content in the `simplify` skill. An agent does not need this file to use the skill, so it is kept out of `SKILL.md` and its references.

## SKILL.md

The tag taxonomy and range-scoped review shape are adapted from the `ponytail-review`/`ponytail-audit` skills in DietrichGebert/ponytail (MIT License, v4.10.0): https://github.com/DietrichGebert/ponytail/blob/v4.10.0/skills/ponytail-review/SKILL.md and https://github.com/DietrichGebert/ponytail/blob/v4.10.0/skills/ponytail-audit/SKILL.md. This adaptation drops the net-lines-removed scoring, persistent always-on mode, and paired good/bad example rhetoric of v4.10.0, and adds the evidence-and-unknown requirement in section 3, which v4.10.0 does not have.

The `reuse` and `merge` tags and the whole-tree reference search before a `delete` finding are adapted from v5.3.2 of the same skills: https://github.com/DietrichGebert/ponytail/blob/v5.3.2/skills/ponytail-review/SKILL.md and https://github.com/DietrichGebert/ponytail/blob/v5.3.2/skills/ponytail-audit/SKILL.md. In v5, these skills are full quality reviews, with lean review as their last priority and a "no case, no finding" rule. This skill keeps lean review separate from `code-review`, and it does not adopt the v5 `split` tag, because readability findings belong to `code-review`.

This skill states the adapted rules in ASD-STE100 Simplified Technical English. The wording does not change their meaning.

# Provenance

Attribution and upstream sources for adapted content in the `code-review` skill. An agent does not need this file to use the skill, so it is kept out of `SKILL.md` and its references.

## SKILL.md

The explicit requirement-conformance checks adapt Matt Pocock's [code-review](https://github.com/mattpocock/skills/blob/4ae2317ddb22b046e26455e4f9c9b3aaaf62212d/skills/engineering/code-review/SKILL.md) at that revision (MIT; [matt-pocock-LICENSE](matt-pocock-LICENSE)). They retain this skill's range, severity, reconciliation and clean-result rules rather than importing a compulsory standards panel.

The one-line form for P3 findings, the wording rules, and the Questions item adapt `caveman-review` from https://github.com/JuliusBrussee/caveman/blob/v4.0.1/skills/caveman-review/SKILL.md (Apache License 2.0). The pass, fail, unavailable, and blocked result classes adapt `verify-and-stop` from the same repository and release. The expected-load rule, the treatment of a marked limit as a decision, and the numbering of findings across severities adapt `ponytail-review` from https://github.com/DietrichGebert/ponytail/blob/v5.3.2/skills/ponytail-review/SKILL.md (MIT License), where the marker is `shortcut:`.

The verbatim quote on each finding, the sibling-invariant and cross-component trace targets, and the ancestry check on a claimed fix adapt `claim-verifier`, `peer-comparator` and `code-review` from [rpiv-mono](https://github.com/juicesharp/rpiv-mono/tree/46dbdfd78ab631bbf3ce41c2618bb173a5ec2725/packages/rpiv-pi) at that revision (MIT License). They retain this skill's single-reviewer flow, severity and false-positive bar rather than importing rpiv's review waves or severity weighting.

This skill states the adapted rules in ASD-STE100 Simplified Technical English. The wording does not change their meaning.

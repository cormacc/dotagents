# Provenance

Attribution and upstream sources for adapted content in the `herdr-orch` skill. An agent does not need this file to use the skill, so it is kept out of `SKILL.md` and its references.

## SKILL.md

The Class C advice to prefer two validators with distinct lenses, in section 'Trusting a result', is adapted from the panel guidance in [rpiv-workflow](https://github.com/juicesharp/rpiv-mono/blob/46dbdfd78ab631bbf3ce41c2618bb173a5ec2725/packages/rpiv-workflow/docs/workflow-authoring.md#adversarial-verification-panel), MIT License.

## references/debugging.md

Adapted from Matt Pocock's [diagnosing-bugs](https://github.com/mattpocock/skills/blob/4ae2317ddb22b046e26455e4f9c9b3aaaf62212d/skills/engineering/diagnosing-bugs/SKILL.md) at that revision (MIT; [matt-pocock-LICENSE](references/matt-pocock-LICENSE)). Retains executable symptom detection, minimisation and original-scenario verification; adds local controls, bounded probes and diagnosis/fix authority, without a fixed hypothesis quota or a ban on source inspection before reproduction.

## references/workflow-recipes.md

The conformance questions in the split-review recipe adapt Matt Pocock's [code-review](https://github.com/mattpocock/skills/blob/4ae2317ddb22b046e26455e4f9c9b3aaaf62212d/skills/engineering/code-review/SKILL.md) (MIT; [matt-pocock-LICENSE](references/matt-pocock-LICENSE)).

These two recipes adapt [test-driven-development](https://github.com/addyosmani/agent-skills/blob/301b02b12bff98e4688e6984af6556802322bbde/skills/test-driven-development/SKILL.md), [test-engineer](https://github.com/addyosmani/agent-skills/blob/301b02b12bff98e4688e6984af6556802322bbde/agents/test-engineer.md) and [doubt-driven-development](https://github.com/addyosmani/agent-skills/blob/301b02b12bff98e4688e6984af6556802322bbde/skills/doubt-driven-development/SKILL.md) from Addy Osmani's agent-skills at the linked revision, MIT License; see [agent-skills-LICENSE](references/agent-skills-LICENSE). They retain separate reproduction authorship and bounded, reconciled review, not automatic commits, routine fan-out or mandatory cross-model prompts. The quote check in step 3 and the prior-finding ruling in step 4 adapt `claim-verifier` and the `grade` skill's `--prior` mode from [rpiv-mono](https://github.com/juicesharp/rpiv-mono/tree/46dbdfd78ab631bbf3ce41c2618bb173a5ec2725/packages/rpiv-pi) at that revision (MIT License), without its verifier persona or grading panel.

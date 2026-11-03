# Instruction review

Use these checks when a skill, persona or instruction document does not reliably produce its intended process. The main skill owns authoring and evaluation; this reference diagnoses the document before adding rules.

1. **Pointer condition:** for each required external reference, state what decision or branch makes it necessary. If the target is not read, test a sharper pointer before copying the whole target inline.
2. **Branch load:** keep what every branch needs in the main path; disclose branch-specific material behind a precise pointer. Keep a concept's rule and caveats together so reading one does not omit the other.
3. **Completion:** give each step an observable stopping condition. If an agent rushes ahead, sharpen that condition first. Consider a real handoff or fresh context boundary only when an irreducibly fuzzy stage repeatedly loses attention to later stages; inline calls do not remove later instructions from context.
4. **Marginal contribution:** compare against the current deployed baseline before calling a directive useful or redundant. When treated and control behave alike, report that result instead of strengthening adjectives or claiming a gain.
5. **Safe compression:** preserve identifiers, negation, authorisation and explicit anti-criteria. A shorter positive instruction is not a substitute for a safety boundary unless it withstands a tempting violation under the actual consumer.

Use the existing evaluation loop, not a second runner or an automatic rewrite of all instruction files. Claims about particular words or negation effects are hypotheses to test on the target model, not universal laws.

## Provenance

Adapted from Matt Pocock's [writing-for-agents](https://github.com/mattpocock/skills/blob/4ae2317ddb22b046e26455e4f9c9b3aaaf62212d/skills/productivity/writing-for-agents/SKILL.md) at that revision (MIT; [matt-pocock-LICENSE](matt-pocock-LICENSE)). Retains pointer, disclosure, completion and no-op diagnostics while preserving explicit guardrails and local evaluation ownership.

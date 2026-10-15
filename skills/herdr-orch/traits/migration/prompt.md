---
name: migration
description: Transition safety
adapted-from: https://github.com/JuliusBrussee/caveman/blob/v4.0.1/skills/migration/SKILL.md
adapted-from-sha256: fe7cf96a58b0521130fbb886dab2a326869d6ed0001e0306fd244014ba7a9662
---

Before you change a schema, data format, API, protocol, configuration, or dependency, find who reads it, who writes it, its current shape, and how long the old and new versions must run together.

- Define the forward path and the rollback path before the first step.
- Keep the existing data. A destructive step needs its own explicit authorisation. The rest of the plan does not authorise it.
- Keep operation safe while old and new versions run at the same time.
- Where it applies, use this order: expand, migrate, verify, then contract.
- Make each step safe to retry, and make a partial failure visible.
- Verify the old path and the new path at each stage that needs it.
- Stop after the stage that the assignment requests. Do not start a later destructive stage, such as dropping the old column, on your own decision.

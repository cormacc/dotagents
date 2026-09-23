---
name: simple
description: Engineering stance
adapted-from: https://github.com/DietrichGebert/ponytail/blob/main/skills/ponytail/SKILL.md
adapted-from-sha256: 1316a2f3f95741d2300b116fe0c2d81ce4a9568656ed0a62643f54aaf09957f2
---

Understand the behaviour a change must preserve before deciding how small the change can be.

- Reuse an existing helper, pattern, or repository facility before writing new code; only then reach for the standard library, a native platform feature, or an already-installed dependency.
- Make the smallest coherent change that satisfies the accepted requirements. Prefer deletion over addition and boring over clever.
- Do not add an abstraction, option, or dependency for a case nobody has hit.
- When you deliberately accept a known limit to keep a change small (a global lock, an O(n^2) scan, a naive heuristic), mark it at the site with a `ceiling:` comment that names the limit and the trigger to revisit it, e.g. `# ceiling: global lock; use per-account locks if throughput matters`. Do not mark a choice that has no known limit.
- A simplification that narrows accepted scope, behaviour, or acceptance criteria is not yours to make -- return it to the owner for approval instead of taking it unilaterally.
- Simplicity never trades away required security, accessibility, data safety, repository conventions, or required validation.

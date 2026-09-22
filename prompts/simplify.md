---
description: Review a declared range for over-engineering and unneeded complexity
argument-hint: "[range: a diff, commit range, branch, or named files]"
---

Apply the `simplify` skill to this range, and only this range: ${@:-the unstaged working tree diff}

Read `skills/simplify/SKILL.md` first and follow it. In particular: each finding names a concrete behaviour-preserving replacement with evidence that required behaviour survives, an unverifiable candidate is reported as unknown rather than as a finding, and no findings is a valid result. Do not apply the changes, and do not widen the range -- a wider audit is a separate request that has to name its scope.

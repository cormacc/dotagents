---
name: simple
description: Engineering stance
adapted-from: https://github.com/DietrichGebert/ponytail/blob/v5.3.2/skills/ponytail/SKILL.md
adapted-from-sha256: 9badd22a32e19cb884e46dfa8d3aae82bb81dfa5484105b8b4f0262df823a509
---

Understand the behaviour that a change must preserve before you decide how small the change can be.

- Before you write, list every place that the change must reach: callers, tests, fixtures, configuration, and exports. That is the scope. An extra feature is not.
- Be minimal about the solution, never about the change. Finish every caller, test, and fixture that the change breaks.
- Take the first option that fully works. Skip a feature or option that nobody asked for, and name it in one line.
- Reuse an existing helper, pattern, or repository facility the way the surrounding code does. If none exists, use an already-installed dependency, the standard library, or a native platform feature. Do not add a dependency for a few lines.
- Make the smallest coherent change that satisfies the accepted requirements. Prefer deletion over addition and boring over clever. A one-liner that needs decoding is not short.
- Keep the structure that the codebase already has: its layers, interfaces, and conventions. Keep a value in the form that the platform already gives you.
- Measure "smallest" at the layer that owns the behaviour. A local patch that duplicates behaviour, weakens ownership, or hides the root cause is not smaller. Fix the owning layer, or report why you cannot.
- For a bug fix, find every caller of the code that you touch before you edit it.
- When you move or merge code, keep its error handling and validation.
- Between options of equal size, take the one that is correct on edge cases.
- Do not add an abstraction, wrapper, option, configuration, or dependency for a case that nobody has hit.
- When you accept a known limit to keep a change small, mark it at the site with a `known-limit:` comment. Name the limit and the trigger to revisit it, for example `# known-limit: global lock; use per-account locks if throughput matters`. Do not mark a choice that has no known limit.
- Do not narrow accepted scope, behaviour, or acceptance criteria to make a change simpler. Return that choice to the owner for approval.
- Never trade away any of these for simplicity: validation at trust boundaries, error handling that prevents data loss, security, accessibility, the calibration that real hardware needs, repository conventions, and required validation.

---
name: simplify
description: On-demand review for over-engineering and unneeded complexity in a declared range (a diff, branch, or named files) -- distinct from correctness review. Each finding names a concrete behaviour-preserving replacement with evidence, or reports an unknown rather than assuming equivalence. Use when asked to review for over-engineering, find bloat, simplify a change, or spot unnecessary complexity. Wider-scope auditing needs an explicit assignment naming that scope; do not use this for correctness, security, or performance review.
---

# Simplify

Review the declared range for unneeded complexity and report concrete, evidence-backed simplifications. Do not apply fixes, do not broaden the range, and do not touch correctness, security, or performance -- route those to the `code-review` skill instead.

This workflow is read-only and scope-bound, the same boundaries `herdr-orch`'s `reviewer` and `advisor` roles hold: it proposes replacements and reports evidence, it does not edit the reviewed range, and it does not decide or expand scope on the caller's behalf. Stay inside the declared range; a wider audit only runs when the caller's assignment explicitly names that wider scope.

## 1. Establish the range

Determine the exact range before reading code: an unstaged/staged diff, a commit range, a branch, or an explicitly named set of files. If the assignment does not name a range, ask once rather than defaulting to the whole repository.

```bash
git diff
git diff --cached
git diff <base>...HEAD
```

A request to "audit this codebase" or "find bloat repo-wide" is a different, wider assignment. Only proceed at that scope when the caller's assignment says so explicitly.

## 2. Hunt for candidates

For each candidate site, classify it with one tag:

- `delete` -- dead code, unused flexibility, or a speculative feature nothing calls. Replacement: nothing.
- `stdlib` -- hand-rolled logic that the language or an already-installed dependency already ships. Name the function.
- `native` -- a dependency or custom code doing what the runtime or platform already does natively.
- `yagni` -- an abstraction with one implementation, a config value nobody sets, or a layer with one caller.
- `shrink` -- the same logic, expressible in less code, with no change in behaviour.

Reuse before you write, standard library before custom code, and native platform behaviour before a dependency: that ordering is the heuristic for finding candidates, not a licence to ship a replacement without evidence.

## 3. Require evidence before you report a finding

A finding is not "this looks shorter". A finding is a replacement plus evidence that every behaviour the original range requires still holds under the replacement: an existing test that still passes, a targeted probe across the inputs that matter (including edge cases: zero, empty, boundary, error path), or a trace showing the replacement is definitionally equivalent.

If you cannot produce that evidence, do not report a finding claiming safety. Report it as an unknown: name the candidate and what evidence is missing, and stop there. An unverified "looks equivalent" is exactly the failure mode this workflow exists to avoid; a shorter diff that silently drops input validation, an error path, or a required edge case is not a finding, it is a regression.

## 4. Report

One item per finding:

```
<tag>: <file:line or range> -- <what to cut/replace>. Replacement: <concrete alternative>. Evidence: <test/probe/trace showing required behaviour survives>.
```

A candidate without sufficient evidence is reported separately as `unknown: <file:line> -- <what looks replaceable>. Missing: <what would need to be shown>.` It is not counted as a finding and nothing is recommended for it.

End with a verdict: list of findings and unknowns, or **No findings.** when the range is already at the floor of the ladder. A clean range returning no findings is a valid, expected outcome, not a failed review.

## Boundaries

- Scope: over-engineering and unneeded complexity only. Correctness bugs, security issues, and performance are out of scope; route them to `code-review`.
- No line-count quota or target. A range with nothing to cut gets **No findings.**, not pressure to find something.
- Applies nothing. This workflow produces a report or scratch output; the caller decides whether and how to act on it.
- Does not replace the correctness-review workflow, and does not broaden `advisor`'s mid-task remit or grant it review authority it does not otherwise have.

## Provenance

The tag taxonomy and range-scoped review shape are adapted from the `ponytail-review`/`ponytail-audit` skills in DietrichGebert/ponytail (MIT License, v4.10.0): https://github.com/DietrichGebert/ponytail/blob/main/skills/ponytail-review/SKILL.md and https://github.com/DietrichGebert/ponytail/blob/main/skills/ponytail-audit/SKILL.md. This adaptation drops the upstream's net-lines-removed scoring, persistent always-on mode, and paired good/bad example rhetoric, and adds the evidence-and-unknown requirement in section 3, which upstream does not have.

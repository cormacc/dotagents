---
name: code-review
description: Review software changes for high-confidence correctness, security, regression, and test gaps. Use when asked to review a diff, branch, commit, merge request, or implementation; returns severity-ranked file:line findings or an explicit no-issues result.
---

# Code review

Review the change, not the author. Find actionable defects introduced by the selected diff; do not redesign unrelated code or manufacture findings.

## 1. Establish intent and diff

Read the task/change-record and repository instructions first. Determine the review range explicitly:

```bash
git status --short
git diff --stat
git diff                    # unstaged
git diff --cached           # staged
git diff <base>...HEAD      # branch/MR changes when base is known
```

If the requested base is ambiguous and different choices materially change the review, ask once. Include untracked files that are part of the implementation. Do not review only the latest commit when the request covers a branch or task.

## 2. Trace changed behavior

Read each changed file plus the callers, types, tests, configuration, and error paths needed to understand impact. Verify symbols and APIs against source/docs. Focus on:

- incorrect control flow, state transitions, boundary conditions, and concurrency;
- data loss, silent success, swallowed errors, resource leaks, and incompatible schema/API changes;
- authentication/authorization, injection, path traversal, secret exposure, unsafe deserialization, and untrusted network/file input;
- deployment/runtime assumptions, dependency changes, migrations, and rollback compatibility;
- missing tests for changed failure paths or regressions that the existing suite would not catch.

Treat user-controlled URLs, shell fragments, SQL, HTML, file paths, and broadcast/client-synchronized state as hostile until proven constrained.

## 3. Verify

Run the narrowest relevant tests, type checks, linters, or reproduction commands. Report commands and failures. A passing suite does not prove correctness; inspect whether tests assert the changed semantics. Do not claim a failure without reproducing it or tracing a concrete reachable path.

A must-not assertion can pass without exercising the guard it claims to prove — because setup or ordering already excludes the case, an overlapping general guard fires first, or a fixture logs a flag without honouring it. For load-bearing negative guarantees only, break the guarded condition, confirm the focused test fails, then restore it.

## Severity

- **P0 — critical:** exploitable security breach, data loss/corruption, or production-wide outage. Must be immediately actionable and provable.
- **P1 — high:** reachable correctness failure or serious operational foot-gun likely to affect users.
- **P2 — medium:** real defect or regression with narrower impact; code may work on the happy path.
- **P3 — low:** small but concrete maintainability/test issue that is likely to cause future mistakes. Omit style preferences.

## False-positive bar

Raise a finding only when all hold:

1. The issue was introduced or exposed by the reviewed change.
2. A concrete input/state reaches it under supported usage.
3. The impact matters to correctness, security, operations, or maintainability.
4. The repository does not already handle it elsewhere.
5. The suggested direction is compatible with project conventions.

Do not flag naming taste, formatting, generic “best practices,” speculative scaling, intentional anti-criteria, or pre-existing debt unrelated to the change. When uncertain, investigate rather than hedge a finding into existence.

## Output

Start with findings ordered by severity. Each finding must include:

```markdown
### [P1] Imperative, specific title
- **Location:** `path/file.ts:42`
- **Problem:** Reachable behavior and why it is wrong.
- **Impact:** Concrete consequence.
- **Fix:** Smallest compatible correction.
- **Evidence:** Reproduction, call path, or failed/passing test that proves the claim.
```

Then provide:

- **Verdict:** `APPROVED` or `NEEDS CHANGES`.
- **Verification:** exact commands/results and any untested runtime surface.
- **Summary:** one or two sentences on the reviewed scope.

If no finding meets the bar, say **“No issues found.”** and still report the diff range and verification performed.

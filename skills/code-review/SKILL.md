---
name: code-review
description: Review software changes for high-confidence correctness, security, regression, test, and maintainability problems in the changed code. Use when asked to review a diff, branch, commit, merge request, or implementation; returns severity-ranked file:line findings or an explicit no-issues result.
---

# Code review

Review the change, not the author. Find actionable defects and maintainability problems that the selected diff introduces or exposes. Do not redesign unrelated code or manufacture findings.

## 1. Establish intent and diff

Read the task/change-record and repository instructions first. Determine the review range explicitly:

```bash
git status --short
git diff --stat
git diff                    # unstaged
git diff --cached           # staged
git diff <base>...HEAD      # branch/MR changes when base is known
```

If the requested base is ambiguous and different choices materially change the review, ask once. If you cannot ask, for example as a delegated child whose assignment does not route questions to a user, choose the most probable base, state it and the alternative in the summary, and continue. Include untracked files that are part of the implementation. Do not review only the latest commit when the request covers a branch or task.

## 2. Trace changed behaviour

Read each changed file plus the callers, types, tests, configuration, and error paths needed to understand impact. Verify symbols and APIs against source/docs. Focus on:

- incorrect control flow, state transitions, boundary conditions, and concurrency;
- data loss, silent success, swallowed errors, resource leaks, and incompatible schema/API changes;
- authentication/authorisation, injection, path traversal, secret exposure, unsafe deserialisation, and untrusted network/file input;
- deployment/runtime assumptions, dependency changes, migrations, and rollback compatibility;
- missing tests for changed failure paths or regressions that the existing suite would not catch;
- names, structure, and conventions in changed code that would mislead a reader or the next editor (see [#False-positive bar](#false-positive-bar) for the scope).

When a skill for the changed files' language supplies a review reference (for example, the `clojure` skill's `references/review.md`), read it and apply its items under this skill's false-positive bar.

Treat user-controlled URLs, shell fragments, SQL, HTML, file paths, and broadcast/client-synchronised state as hostile until proven constrained.

## 3. Verify

Run the narrowest relevant tests, type checks, linters, or reproduction commands. Report commands and failures. A passing suite does not prove correctness; inspect whether tests assert the changed semantics. Do not claim a failure without reproducing it or tracing a concrete reachable path.

A must-not assertion can pass without exercising the guard it claims to prove -- because setup or ordering already excludes the case, an overlapping general guard fires first, or a fixture logs a flag without honouring it. For load-bearing negative guarantees only, break the guarded condition, confirm the focused test fails, then restore it.

**A guard is unverified until its trigger has been observed firing.** Three shapes recur, all of which read correct and pass a green suite:

- A predicate over an external-world property -- file lifetime, process liveness, name release, resource occupancy -- exercised only against a fixture the test itself controls. The fixture then proves the code matches the spec, not that the spec matches reality. Pin such a predicate against the real system at least once.
- A guard nested inside a presence check (`(and found? ...)`, `if let`, a null-guarded block) is vacuous exactly when the thing is absent -- usually the case the guard exists for. Ask what happens on the absent branch.
- An allow-list-shaped sweep -- a lint, doc-contract, or policy test iterating a hardcoded list of files, keys, or phrases -- polices only what its list names. Check the list for what it omits, and prefer banning the specific false claim over a shared phrase that a true statement may also use.

A test that needs a particular value of a configurable default must pin it explicitly or accept either outcome. A test inheriting a product default turns a later default change into unrelated red, obscuring whichever failures are real.

## Severity

- **P0 -- critical:** exploitable security breach, data loss/corruption, or production-wide outage. Must be immediately actionable and provable.
- **P1 -- high:** reachable correctness failure or serious operational foot-gun likely to affect users.
- **P2 -- medium:** real defect or regression with narrower impact; code may work on the happy path.
- **P3 -- low:** small but concrete maintainability, readability, or test issue that is likely to cause future mistakes. A preference between equally clear alternatives is not a finding.

## False-positive bar

Raise a finding only when all hold:

1. The issue was introduced or exposed by the reviewed change.
2. A concrete input/state reaches it under supported usage, or, for a maintainability finding, you can name the specific misreading or later edit that it invites.
3. The impact matters to correctness, security, operations, or maintainability.
4. The repository does not already handle it elsewhere.
5. The suggested direction is compatible with project conventions.

The change defines the scope. In changed code, flag naming, readability, structure, and convention problems that have a concrete maintainability cost: a name that misstates behaviour, structure that hides a branch or effect, or a departure from a project convention. Do not flag a preference between equally clear alternatives, formatting that the project's formatter or linter owns (a change that breaks a configured formatter or lint rule is a convention finding), best-practice advice with no concrete cost in this code, speculative scaling, scope that the task or change-record explicitly excludes, or debt outside the functions and forms that the change modifies. When uncertain, investigate rather than hedge a finding into existence.

## Cleanup in modified code

Leave modified code a little cleaner than you found it (the Boy Scout rule). In a function or form that the change modifies, you may suggest a small cleanup of existing code, such as a dead branch, an unclear name, a magic value, or a missing docstring. A cleanup suggestion does not need criterion 1 of the false-positive bar, but it needs the other criteria. Do not suggest cleanup outside the modified functions and forms, and do not suggest an architectural change. Cleanup suggestions are not findings, and they do not change the verdict.

## Output

Start with findings ordered by severity. Each finding must include:

```markdown
### [P1] Imperative, specific title
- **Location:** `path/file.ts:42`
- **Problem:** Reachable behaviour, or the misreading or later edit that the code invites, and why it is wrong.
- **Impact:** Concrete consequence.
- **Fix:** Smallest compatible correction.
- **Evidence:** Reproduction, call path, or failed/passing test that proves the claim. For a maintainability finding, cite the code or convention that shows the cost.
```

Then provide:

- **Verdict:** `NEEDS CHANGES` when any P0, P1, or P2 finding remains. Otherwise `APPROVED`, including when only P3 findings remain.
- **Cleanup:** suggestions from [#Cleanup in modified code](#cleanup-in-modified-code), each with its location. Omit this item when there are none.
- **Verification:** exact commands/results and any untested runtime surface.
- **Summary:** one or two sentences on the reviewed scope.

If no finding meets the bar, say **"No issues found."** and still report the diff range and verification performed.

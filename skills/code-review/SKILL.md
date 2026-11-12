---
name: code-review
description: Review software changes for high-confidence correctness, security, regression, test, and maintainability problems in the changed code. Use when asked to review a diff, branch, commit, merge request, or implementation; returns severity-ranked file:line findings or an explicit no-issues result.
---

# Code review

Review the change, not the author. Find actionable defects and maintainability problems that the selected diff introduces or exposes. Do not redesign unrelated code. Do not manufacture findings.

## 1. Establish intent and diff

First read the task or change-record and the repository instructions. Then find the review range explicitly:

```bash
git status --short
git diff --stat
git diff                    # unstaged
git diff --cached           # staged
git diff <base>...HEAD      # branch/MR changes when base is known
```

If the requested base is ambiguous, and the choice materially changes the review, ask once. Sometimes you cannot ask, for example as a delegated child whose assignment does not send questions to a user. Then choose the most probable base, state it and the alternative in the summary, and continue. Include untracked files that are part of the implementation. When the request covers a branch or task, do not review only the latest commit.

When an accepted spec or task contract exists, map its requirements to the implementation and the verification evidence. Look for:

- Missing or partial behaviour.
- Additions outside the accepted scope.
- Code that seems to implement a requirement but gets it wrong.

A passing suite alone does not prove conformance. Report a concrete violation under the normal severity and evidence rules. Put unresolved intent under Questions. An absent spec is a stated limit. It does not let you invent requirements.

## 2. Trace changed behaviour

Read each changed file. Also read the callers, types, tests, configuration, and error paths that you need to understand the impact. Verify symbols and APIs against source or documentation. Look for:

- Incorrect control flow, state transitions, boundary conditions, and concurrency.
- Data loss, silent success, swallowed errors, resource leaks, and incompatible schema or API changes.
- Authentication and authorisation, injection, path traversal, secret exposure, unsafe deserialisation, and untrusted network or file input.
- Deployment and runtime assumptions, dependency changes, migrations, and rollback compatibility.
- Missing tests for changed failure paths, or regressions that the existing suite would not catch.
- Names, structure, and conventions in changed code that would mislead a reader or the next editor. [#False-positive bar](#false-positive-bar) gives the scope.
- A new file or entity that is parallel to an existing sibling (a handler, service, reducer, repository, or registry entry) but that lacks or changes one of the sibling's invariants. The invariants are public operations, emitted events, state guards, collaborators, persisted fields, and registrations elsewhere. Treat an absence as intentional only when code or documentation states that intent.
- Defects that occur only across components. Examples are a state that one path reaches but no path handles or leaves, a condition whose matching branch depends on a consumer that excludes this entity, and a shared filter or dispatch that a new or terminal value falls through. Such a finding needs facts from at least two locations.

When the history has an earlier change of the same kind, read its follow-up fixes for a failure that the reviewed change could repeat. Before you treat a finding as already fixed by a commit, confirm that the commit is in the reviewed range with `git merge-base --is-ancestor <commit> <tip>`.

When a skill for the language of the changed files has a review reference (for example, `references/review.md` of the `clojure` skill), read it. Apply its items under the false-positive bar of this skill.

Treat user-controlled URLs, shell fragments, SQL, HTML, file paths, and broadcast or client-synchronised state as hostile until you prove that they are constrained.

Find the expected load in the repository (README, deployment or build configuration, entry points). The load can be one person who runs a script, or many users and processes at the same time. Judge concurrency and performance against that load. A site with a `known-limit:` comment that names its limit is a recorded decision, not a finding. The exception is when the expected load already crosses that limit.

## 3. Verify

Run the narrowest relevant tests, type checks, linters, or reproduction commands. Report the commands and the failures. A passing suite does not prove correctness. Check whether the tests assert the changed semantics. Do not claim a failure unless you reproduce it or trace a concrete reachable path.

A must-not assertion can pass without exercising the guard that it claims to prove. This happens when setup or ordering already excludes the case, when an overlapping general guard fires first, or when a fixture logs a flag but does not honour it. For load-bearing negative guarantees only, break the guarded condition, confirm that the focused test fails, then restore the condition.

**A guard is unverified until you see its trigger fire.** Three shapes recur. All of them look correct and pass a green suite:

- A predicate over a property of the external world, such as file lifetime, process liveness, name release, or resource occupancy, that the test exercises only against a fixture that it controls. The fixture then proves that the code matches the spec, not that the spec matches reality. Pin such a predicate against the real system at least once.
- A guard inside a presence check (`(and found? ...)`, `if let`, a null-guarded block). It is vacuous exactly when the thing is absent, which is usually the case that the guard exists for. Ask what happens on the absent branch.
- A sweep shaped like an allow-list: a lint, doc-contract, or policy test that iterates over a hardcoded list of files, keys, or phrases. It checks only what its list names. Check the list for what it omits. Prefer to ban the specific false claim, not a shared phrase that a true statement can also use.

A test that needs a particular value of a configurable default must pin that value explicitly, or accept either outcome. If a test inherits a product default, a later change to that default makes the test fail for an unrelated reason. That failure hides the failures that are real.

## Severity

- **P0 -- critical:** an exploitable security breach, data loss or corruption, or a production-wide outage. It must be immediately actionable and provable.
- **P1 -- high:** a reachable correctness failure, or a serious operational hazard, that is likely to affect users.
- **P2 -- medium:** a real defect or regression with a narrower impact. The code can work on the main path.
- **P3 -- low:** a small but concrete maintainability, readability, or test issue that is likely to cause future mistakes. A preference between equally clear alternatives is not a finding.

## False-positive bar

Raise a finding only when all of these are true:

1. The reviewed change introduced or exposed the issue.
2. A concrete input or state reaches it under supported usage. For a maintainability finding, you can name the specific misreading or later edit that it invites.
3. The impact matters to correctness, security, operations, or maintainability.
4. The repository does not already handle it elsewhere.
5. The suggested direction is compatible with the project conventions.

The change defines the scope. In changed code, flag naming, readability, structure, and convention problems that have a concrete maintainability cost. Examples are a name that misstates behaviour, structure that hides a branch or effect, and a departure from a project convention. Do not flag:

- A preference between equally clear alternatives.
- Formatting that the project's formatter or linter owns. A change that breaks a configured formatter or lint rule is a convention finding.
- Best-practice advice with no concrete cost in this code.
- Speculative scaling.
- Scope that the task or change-record explicitly excludes.
- Debt outside the functions and forms that the change modifies.

When you are uncertain, investigate. Do not hedge a finding into existence.

## Cleanup in modified code

Leave modified code a little cleaner than you found it (the Boy Scout rule). In a function or form that the change modifies, you can suggest a small cleanup of existing code. Examples are a dead branch, an unclear name, a magic value, and a missing docstring. A cleanup suggestion does not need criterion 1 of the false-positive bar, but it needs the other criteria. Do not suggest cleanup outside the modified functions and forms. Do not suggest an architectural change. Cleanup suggestions are not findings, and they do not change the verdict.

## Output

Start with the findings in order of severity. Number the findings in one sequence across all severities, so that the reader can refer to a finding by its number, as in "fix 2 and 5". Each P0, P1, and P2 finding must include:

```markdown
### 1. [P1] Imperative, specific title
- **Location:** `path/file.ts:42`
- **Quote:** `the cited line, copied verbatim from the file`
- **Problem:** Reachable behaviour, or the misreading or later edit that the code invites, and why it is wrong.
- **Impact:** Concrete consequence.
- **Fix:** Smallest compatible correction.
- **Evidence:** Reproduction, call path, or failed/passing test that proves the claim. For a maintainability finding, cite the code or convention that shows the cost.
```

Copy the quote from the file at the reviewed state, not from memory or a diff header. Then a reader can confirm the location with one search. For a finding about something absent, quote the nearest line that shows the absence, such as the branch or call that has no guard. For a design-artefact review, quote the artefact line.

Write each P3 finding on one line, in the form `3. [P3] path:line: problem. fix.`. Add the evidence in a short clause when the problem does not show it.

Wording rules for every finding, cleanup suggestion, and question:

- Name exact symbols in backticks.
- Give a concrete fix. "Consider refactoring this" is not a fix.
- Do not restate what the line does. The reader can see the diff.
- Do not write "I noticed that", "It seems like", "You might want to consider", or a hedge such as "perhaps", "maybe", or "I think". If you are unsure, investigate, or ask under **Questions**.
- Do not praise each finding. If the change is good, say so once in the summary.

Then give:

- **Verdict:** `NEEDS CHANGES` when any P0, P1, or P2 finding remains. Otherwise `APPROVED`, also when only P3 findings remain.
- **Cleanup:** suggestions from [#Cleanup in modified code](#cleanup-in-modified-code), one on each line, in the form `path:line: problem. fix.`. Omit this item when there are none.
- **Questions:** questions about the author's intent that source and documentation cannot answer, one on each line, in the form `path:line: question`. Questions are not findings, and they do not change the verdict. Omit this item when there are none.
- **Verification:** each command, with its result as exactly one of pass, fail, unavailable, or blocked, and each untested runtime surface.
- **Summary:** one or two sentences on the reviewed scope. When a finding, or a concern that you cleared, depends on the load, state the load that you assumed.

If no finding meets the bar, say **"No issues found."**, and still report the diff range and the verification that you did.

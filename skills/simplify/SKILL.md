---
name: simplify
description: On-demand review for over-engineering and unneeded complexity in a declared range (a diff, branch, or named files) -- distinct from correctness review. Each finding names a concrete behaviour-preserving replacement with evidence, or reports an unknown rather than assuming equivalence. Use when asked to review for over-engineering, find bloat, simplify a change, or spot unnecessary complexity. Wider-scope auditing needs an explicit assignment naming that scope; do not use this for correctness, security, or performance review.
metadata:
  herdr-orch: workflow
---

# Simplify

Review the declared range for unneeded complexity, and report concrete simplifications with evidence. Do not apply fixes, and do not broaden the range. Do not assess correctness, security, or performance. Send those to the `code-review` skill.

This workflow is read-only and limited to its scope. It holds the same boundaries as the `reviewer` and `advisor` roles of `herdr-orch`. It proposes replacements and reports evidence. It does not edit the reviewed range, and it does not decide or expand scope for the caller. Stay inside the declared range. Do a wider audit only when the caller's assignment explicitly names that wider scope.

## 1. Establish the range

Find the exact range before you read code. The range is an unstaged or staged diff, a commit range, a branch, or an explicitly named set of files. If the assignment does not name a range, ask once. Do not use the whole repository as the default.

For a working-tree review, including an "unstaged diff", include by default the non-ignored untracked files in the requested paths. Review each new file in full. `git diff` alone does not show them. Exclude new files only when the user explicitly asks for tracked files only, or otherwise excludes them. A staged-only review and a commit or ref review keep their requested boundaries. Do not add unrelated working-tree files to them. State which files are in scope before you review.

```bash
git diff
git ls-files --others --exclude-standard
git diff --cached
git diff <base>...HEAD
```

A request to "audit this codebase" or "find bloat repo-wide" is a different, wider assignment. Do it at that scope only when the caller's assignment says so explicitly.

## 2. Hunt for candidates

Give each candidate site one tag:

- `delete` -- dead code, unused flexibility, or a speculative feature that nothing calls. Replacement: nothing.
- `reuse` -- logic that the repository already has in a helper, component, or pattern. Name the path of the existing code.
- `stdlib` -- hand-written logic that the language or an already-installed dependency already supplies. Name the function.
- `native` -- a dependency or custom code that does what the runtime or platform already does natively.
- `yagni` -- an abstraction with one implementation, a configuration value that nobody sets, or a layer with one caller.
- `shrink` -- the same logic in less code, with no change in behaviour.
- `merge` -- near-copies that must change together. Name each copy and the one that stays.

When a skill for the range's language has a review reference (for example, `references/review.md` of the `clojure` skill), read it for language-specific candidates. Its candidates need the same evidence as yours.

Reuse before you write. Use the standard library before custom code, and native platform behaviour before a dependency. This order helps you find candidates. It does not let you ship a replacement without evidence.

## 3. Require evidence before you report a finding

"This looks shorter" is not a finding. A finding is a replacement plus evidence that every behaviour that the original range needs still holds under the replacement. The evidence is one of these:

- An existing test that still passes.
- A targeted probe across the inputs that matter, including edge cases such as zero, empty, a boundary, and the error path.
- A trace that shows that the replacement is equivalent by definition.

Before you say that code is unused, search the whole tree for references to it. Include tests, fixtures, configuration, and string or dynamic references, such as a registry key or a name built at runtime. Report the search, and a positive control that shows that the search finds a known reference.

If you cannot produce that evidence, do not report a finding that claims safety. Report it as an unknown: name the candidate and the missing evidence, and stop there. This workflow exists to prevent an unverified "looks equivalent". A shorter diff that silently drops input validation, an error path, or a necessary edge case is not a finding. It is a regression.

## 4. Report

Write one item for each finding:

```
<tag>: <file:line or range> -- <what to cut/replace>. Replacement: <concrete alternative>. Evidence: <test/probe/trace showing required behaviour survives>.
```

Report a candidate without sufficient evidence separately, as `unknown: <file:line> -- <what looks replaceable>. Missing: <what would need to be shown>.` It does not count as a finding, and you recommend nothing for it.

End with a verdict: the list of findings and unknowns, or **No findings.** when the range has nothing left to remove. A clean range with no findings is a valid and expected result, not a failed review.

## Boundaries

- Scope: over-engineering and unneeded complexity only. Correctness bugs, security issues, and performance are out of scope. Send them to `code-review`.
- No line-count quota or target. When a range has nothing to cut, report **No findings.** Do not look for something to report.
- Applies nothing. This workflow produces a report or scratch output. The caller decides whether to act on it, and how.
- It does not replace the correctness-review workflow. It does not broaden the mid-task remit of `advisor`, and it does not give `advisor` review authority that it does not otherwise have.

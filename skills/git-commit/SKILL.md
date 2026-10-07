---
name: git-commit
description: 'Generate standardized git commit messages following conventional commits spec, and concise merge/pull request descriptions. Use when user asks to write a commit message, draft a commit, prepare a commit, commit these changes, summarize staged changes, produce a conventional commit, or write/propose an MR or PR description. Analyzes staged diffs and change descriptions to produce type(scope): description format messages.'
---

# Git Commit Message Writer

Format: `<type>[(scope)][!]: <description>` followed by an optional body and footers. Conventional Commits spec applies; assume the model knows it. The notes below are the project-specific bits worth restating.

## Workflow

1. Inspect the worktree:
   ```bash
   git status --short
   git diff --staged    # prefer staged when present
   git diff HEAD        # otherwise all tracked
   ```
   `git diff HEAD` does *not* include untracked files; check `git status` for them.

   If a requested standalone commit depends on other uncommitted work, surface the dependency and propose dependency-ordered commits instead; verify each intermediate tree (e.g. run the test suite) before committing it.

   Reconcile `git status` against the change's expected file set and stage those paths explicitly. A worktree shared with subagents, other agent sessions, or the user may hold unrelated edits; never `git add -A`/`.` there, and surface anything foreign you deliberately left unstaged. Omit a path already staged as a deletion: it no longer exists in the worktree, and `git add` validates every pathspec first, so one stale path stages nothing.

2. Pick the type:
   - `feat` -- new functionality
   - `fix` -- bug fix
   - `docs`, `style`, `refactor`, `test`, `chore`, `perf`, `ci`, `build`, `revert`
   - Breaking: append `!` before the colon or add a `BREAKING CHANGE:` footer.

3. Pick a scope (optional noun for the area touched), then write the subject:
   - Imperative mood ("add", not "added"), no trailing period, ≤72 chars.

4. **Body -- only what's needed.** What's changed and why. Prompt the user for the *why* if it's not evident from the diff. Refer to associated design change-records rather than restating their content.

   Do **not** include:
   - Test run details / output
   - Abandoned approaches or failed experiments
   - Co-author / generation attribution footers unless the user asks

5. Show the proposed message to the user and wait for explicit approval before running `git commit`.

## Merge request descriptions

The same body rules apply to an MR (or PR) description. Reviewers read the individual commits for detail; the description gives only the shape of the change.

- Title: the change in plain words, not a conventional-commit subject.
- Open with one short paragraph: what changed and why. Link the change-record.
- Name each renamed or removed identifier that callers will meet.
- Then, only if needed, a short list of changes that the commit subjects do not make obvious: changed IDs or API shapes, cross-cutting clean-ups, a regression fixed on the way. One line each, with the reason.
- State a known gap in one sentence and point to its follow-up.
- Do not include test results (the merge gate enforces a passing suite), file or per-function inventories, added/removed ledgers, or detail that one commit already carries.

Show the full draft for approval, as for a commit. The user may post it manually.

## Examples

```
feat(auth): add OAuth2 login with Google

Implements Google OAuth2 flow using the existing session management
system.

Closes #142
```

```
fix(api): handle null response from payment provider
```

```
feat(api)!: change response envelope

BREAKING CHANGE: API responses now wrap payloads in a `data` object.
```

MR description:

```
Dispatch async effect continuations from one interceptor

Async effects used to dispatch their own :then, and some also handled their
own :on-error. Both continuations now live in one nexus interceptor: an effect
returns its promise, and the interceptor does the rest. Design and decisions:
design/log/2026-10-06-async-effect-interceptor.org.

`error-dispatch-interceptor` is renamed `async-effect-interceptor`.

Additional changes
- A datahike connect failure is notified as :datahike.fx/connect, not
  :datahike/connect.
- The webserial and webbluetooth wrappers return promises instead of taking
  :on-success/:on-error callbacks, for consistency.
```

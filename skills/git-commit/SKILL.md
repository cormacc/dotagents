---
name: git-commit
description: 'Generate standardized git commit messages following conventional commits spec. Use when user asks to write a commit message, draft a commit, prepare a commit, commit these changes, summarize staged changes, or produce a conventional commit. Analyzes staged diffs and change descriptions to produce type(scope): description format messages.'
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

   Reconcile `git status` against the change's expected file set and stage those paths explicitly. A worktree shared with subagents, other agent sessions, or the user may hold unrelated edits; never `git add -A`/`.` there, and surface anything foreign you deliberately left unstaged.

2. Pick the type:
   - `feat` — new functionality
   - `fix` — bug fix
   - `docs`, `style`, `refactor`, `test`, `chore`, `perf`, `ci`, `build`, `revert`
   - Breaking: append `!` before the colon or add a `BREAKING CHANGE:` footer.

3. Pick a scope (optional noun for the area touched), then write the subject:
   - Imperative mood ("add", not "added"), no trailing period, ≤72 chars.

4. **Body — only what's needed.** What's changed and why. Prompt the user for the *why* if it's not evident from the diff. Refer to associated design change-records rather than restating their content.

   Do **not** include:
   - Test run details / output
   - Abandoned approaches or failed experiments
   - Co-author / generation attribution footers unless the user asks

5. Show the proposed message to the user and wait for explicit approval before running `git commit`.

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

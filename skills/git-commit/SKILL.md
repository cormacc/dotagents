---
name: git-commit
description: 'Generate standardized git commit messages following conventional commits spec. Use when user asks to write a commit message, draft a commit, prepare a commit, commit these changes, summarize staged changes, or produce a conventional commit. Analyzes staged diffs and change descriptions to produce type(scope): description format messages.'
---

# Git Commit Message Writer

## Format

```
<type>[optional scope][optional !]: <description>

[optional body]

[optional footer(s)]
```

`feat` is for new features and `fix` is for bug fixes. Other common types include docs, style, refactor, test, chore, perf, ci, build, and revert.

## Instructions

### Step 1: Inspect the worktree and get the diff

```bash
git status --short
```

Prefer staged changes when present:

```bash
git diff --staged
```

If nothing is staged, inspect all tracked changes:

```bash
git diff HEAD
```

Note untracked files shown by `git status --short`; they are not included in `git diff HEAD`.

### Step 2: Analyze the changes

Look for:
- What files changed and what category they belong to
- Whether this adds new functionality (`feat`), fixes a bug (`fix`), or updates docs/config/tests
- Whether the change is breaking and needs `!` before the colon or a `BREAKING CHANGE:` footer
- The optional scope: a noun describing the affected section of the codebase, surrounded by parentheses

### Step 3: Write the message

- Prefix the subject with a type, optional scope, optional `!`, then a required colon and space
- Keep the subject line under 72 characters
- Use imperative mood: "add feature" not "added feature"
- Do not end the subject line with a period
- Add a body if the change needs more context than the subject allows; the body begins one blank line after the description
- Add footers one blank line after the body. Footer tokens use `-` instead of spaces, except `BREAKING CHANGE`, and use `Token: value` or `Token #value` format
- For breaking changes, add `!` immediately before the colon, e.g. `feat(api)!: change response format`, or add a footer beginning with `BREAKING CHANGE:`
- Keep it concise -- what's changed and why. Prompt for the why if not evident.
- Intent and outcome only -- do not include the following:
  - Test run details
  - Abandoned approaches / failed experiments

### Commit execution

If the user asks to commit, first show the proposed commit message and ask for confirmation before running `git commit`. Do not commit without explicit approval.

### Quality check

- [ ] Type is present
- [ ] Type is `feat` for a feature or `fix` for a bug fix when applicable
- [ ] Prefix has the required colon and space after the type/scope/`!`
- [ ] Description immediately follows the colon and space
- [ ] Subject line is under 72 characters
- [ ] Imperative mood is used
- [ ] Optional scope is a useful noun describing the affected codebase section
- [ ] Body and footers, when present, start after one blank line
- [ ] Breaking changes use `!` before the colon or a `BREAKING CHANGE:` footer

## Examples

```
feat(auth): add OAuth2 login with Google

Implements Google OAuth2 flow using the existing session management
system. Users can now sign in with their Google account.

Closes #142
```

```
fix(api): handle null response from payment provider
```

```
docs(readme): update local setup instructions for Node 22
```

```
feat(api)!: change response envelope

BREAKING CHANGE: API responses now wrap payloads in a `data` object.
```

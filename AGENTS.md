# General
- CRITICAL: Always verify symbols, function names, config options, module
  paths, variable names, CLI flags, and API fields against actual source code or
  documentation.
- When asked a question, just answer the question -- don't start coding.
  Use tools and write scripts only to obtain additional required information.

# Git operations
- When moving files controlled by git, ALWAYS use `git mv` rather than `mv` -- this preserves history.
- When reverting file changes you made, use git instead of editing the file again.
- For commit messages, see the `git-commit` skill. Commit bodies should refer to associated design change records rather than restating detail.

# File operations
- Use `rg` for file and content searches.

# Temporary files
Resolve the repository root with `git rev-parse --show-toplevel`, then use
`<repository-root>/.agents/tmp/` for scripts, data, experiments, testing, and other
ad-hoc work. Do not assume `$PROJECT_ROOT` is defined.

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
- When searching file content, ALWAYS use `rg` rather than `find`
- When you want to write a new file, ALWAYS use the write tool.

# Temporary files
Use `$PROJECT_ROOT/.agents/tmp/` for scripts, data or temporary files for experiments,
exploration, testing, answering questions, or other ad-hoc tasks.

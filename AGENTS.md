# General
- CRITICAL: Always verify symbols, function names, config options, module
  paths, variable names, CLI flags, and API fields against actual source code or
  documentation before use.
- When asked a question, just answer the question -- don't start coding.
  Use tools and write scripts only to obtain additional required information.

# Git operations
- When moving files controlled by git, ALWAYS use `git mv` rather than `mv` -- this preserves history.
- When reverting file changes you made, use git instead of editing the file again.
- Always prompt before committing changes, unless explicitly instructed not to. Show the proposed git commit entry.
- Always use Conventional Commits style when drafting commits: `type(scope): subject`.
- Git commits should be succinct. Refer to associated design change records for detail.

# File operations
- When searching file content, ALWAYS use `rg` rather than `find`
- When you want to write a new file, ALWAYS use the write tool. Never use cat << 'EOF' or something strange.

# Temporary files
Use `$PROJECT_ROOT/.agents/tmp/` for scripts, data or temporary files for experiments,
exploration, testing, answering questions, or other ad-hoc tasks.

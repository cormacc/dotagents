# General
- CRITICAL: Always verify symbols, function names, config options, module
  paths, variable names, CLI flags, and API fields against actual source code or
  documentation.
- When asked a question, just answer the question -- don't start coding.
  Use tools and write scripts only to obtain additional required information.
- When a matched skill owns a domain, read it before issuing exploratory
  commands in that domain -- don't parallelise the skill load with domain probes.

# Subagents
- Reusable subagent definitions live in `~/.agents/subagents/` (global) and `<git-root>/.agents/subagents/` (project). To delegate work inside Herdr, use the `herdr-subagents` skill.
- For unqualified subagent model names, route `gpt-*` through `openai-codex`, `claude-*` through `anthropic`, and other models through `lemonade`. Preserve explicitly provider-qualified model names.

# Git operations
- When moving files controlled by git, ALWAYS use `git mv` rather than `mv` -- this preserves history.
- When reverting file changes you made, use git instead of editing the file again.
- For commit messages, see the `git-commit` skill. Commit bodies should refer to associated design change records rather than restating detail.

# File operations
- Use `rg` for file and content searches.
- Single-quote shell search patterns containing backticks or `$` (common with markdown-derived text); double quotes invite command substitution.
- Prefer available structured read/edit tools over ad-hoc scripts for routine file inspection and modification.
- When scripting is necessary, prefer Babashka to Python for repository-local automation. Use Python when invoking an existing Python tool or when its ecosystem is materially better suited.
- For scripted transformations, write a candidate under `<repository-root>/.agents/tmp/`, inspect its diff, and only then replace the source; do not perform unverified in-place rewrites.

# Temporary files
Resolve the repository root with `git rev-parse --show-toplevel`, then use
`<repository-root>/.agents/tmp/` for scripts, data, experiments, testing, and other
ad-hoc work. Do not assume `$PROJECT_ROOT` is defined.

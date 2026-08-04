# General
- CRITICAL: Always verify symbols, function names, config options, module
  paths, variable names, CLI flags, and API fields against actual source code or
  documentation.
- When asked a question, just answer the question -- don't start coding.
  Use tools and write scripts only to obtain additional required information.
- An empty result is not evidence of absence. A wrong field name, path or source
  returns nothing rather than failing, so confirm the query matched at all before
  reporting that nothing did.
- When a matched skill owns a domain, read it before issuing exploratory
  commands in that domain -- don't parallelise the skill load with domain probes.

# Subagents
- Reusable Herdr subagent definitions resolve `<git-root>/.agents/subagents/` (project override) > `~/.agents/subagents/` (home override) > `skills/herdr-orch/subagents/` (packaged default). The home path is this repo's `subagents/`, symlinked by the dotfiles `agents.nix`; edit it here rather than under `~`. To delegate work inside Herdr, use the `herdr-orch` skill.
- `subagents/config.edn` relaxes interactive approval for claude (`--permission-mode auto`) and codex (`--ask-for-approval never --sandbox workspace-write`), because both otherwise stall in an unwatched pane. Bounded autonomy is deliberate: dangerous actions still escalate, and claude's `bypassPermissions` is worse than useless here since it stalls on its own startup confirmation. Opt-in configuration, never a shipped default, and `pi` is deliberately excluded.
- Resolve a subagent's kind independently from its model: explicit kind request, then persona definition, then parent kind. Do not select a harness from a model ID or weight alias. The separate `config.edn` chain replaces complete same-ID rows package < home < project; it never deep-merges or selects kind. Translate the resolved model through that kind's roster column; Pi receives the configured provider-qualified `:pi` model. The weights are `heavy`, `middle`, `light`, and `feather`; their per-kind values are enumerated once, in `skills/herdr-orch/scripts/docs/contract.md` § Model resolution.

# Git operations
- When moving files controlled by git, ALWAYS use `git mv` rather than `mv` -- this preserves history.
- When reverting file changes you made, use git instead of editing the file again.
- For commit messages, see the `git-commit` skill. Commit bodies should refer to associated design change records rather than restating detail.

# File operations
- Use `rg` for file and content searches.
- Verify a flag means what you assume before trusting output: `rg -r` is `--replace`, not recursive (rg recurses by default), and Rust-regex escaping differs from POSIX (`\+` matches a literal plus), so a wrong flag or pattern usually yields confident wrong output rather than an error.
- Single-quote shell search patterns containing backticks or `$` (common with markdown-derived text); double quotes invite command substitution.
- Prefer available structured read/edit tools over ad-hoc scripts for routine file inspection and modification.
- When scripting is necessary, prefer Babashka to Python for repository-local automation. Use Python when invoking an existing Python tool or when its ecosystem is materially better suited.
- For scripted transformations, write a candidate under `<repository-root>/.tmp/`, inspect its diff, and only then replace the source; do not perform unverified in-place rewrites.
- A human may be editing the same file. Before replacing one wholesale, check for a live editor lock (an Emacs `.#<basename>` sibling naming a running PID) and prefer targeted edits, which fail loudly on stale text instead of silently discarding unsaved work. Re-read immediately before editing any file the user has touched this session.
- Redirect long-running or expensive command output to a file under `<repository-root>/.tmp/` and read slices from it. Piping through `head`/`tail` discards the rest and often forces a costly re-run.
- Do not author unicode dashes in prose. Write `--` (org converts it on export) or use an org descriptive list `- Term :: detail`; literal em-dashes have been emitted as `\uXXXX` escapes and committed as mojibake. Where a format makes one significant syntax -- the herdr-orch `ARTIFACTS` item splits on a literal ` — ` -- leave it alone.

# Temporary files
Resolve the repository root with `git rev-parse --show-toplevel`, then use
`<repository-root>/.tmp/` for scripts, data, experiments, testing, and other
ad-hoc work. Do not assume `$PROJECT_ROOT` is defined.

Never put transient state under `.agents/`: that tree is durable agent configuration,
and some harnesses (codex under `--sandbox workspace-write`) deliberately mount it
read-only so an agent cannot rewrite its own instructions mid-task. A scratch or result
path inside it fails to write for those children.

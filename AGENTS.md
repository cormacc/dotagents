# General
- CRITICAL: Always verify symbols, function names, config options, module
  paths, variable names, CLI flags, and API fields against actual source code or
  documentation. This identifier check is unconditional and independent of any
  claim's consequence class or probe budget (see `herdr-orch` § Trusting a result).
- State a factual or causal claim only when it is measured, attributed to its
  source, or explicitly presented as uncertain -- never asserted as settled fact
  on confidence alone. This applies to both a baseline/attribution composed into
  an assignment and a claim reported to the user.
- When asked a question, just answer the question -- don't start coding.
  Use tools and write scripts only to obtain additional required information.
- An empty result is not evidence of absence. A wrong field name, path or source
  returns nothing rather than failing, so confirm the query matched at all before
  reporting that nothing did.
- Check the `ok`/status field of a structured CLI envelope before reading result fields. A nil field inside an error envelope is indistinguishable from a real empty result: an `oh` response was read as a trait-resolution failure when it was actually `{"ok":false}` from a version mismatch.
- Ask one decision per question. Bundling two independent choices into one option set means the answer settles only one of them, and the other carries forward unconfirmed while looking decided.
- A behavioural directive in an instruction file is unverified until something adversarial tests it. Before shipping one, check that it changes behaviour; when it fails, fix the incentive producing the unwanted behaviour rather than restating the prohibition. Two of three shipped trait directives failed such a check on first writing, and identically before the rewrite -- the prose they replaced had never worked either.
- When a matched skill owns a domain, read it before issuing exploratory
  commands in that domain -- don't parallelise the skill load with domain probes.

# Subagents
- Reusable Herdr subagent definitions resolve `<git-root>/.agents/subagents/` (project override) > `~/.agents/subagents/` (home override) > `skills/herdr-orch/subagents/` (packaged default). The home path is this repo's `subagents/`, symlinked by the dotfiles `agents.nix`; edit it here rather than under `~`. To delegate work inside Herdr, use the `herdr-orch` skill.
- `subagents/config.edn` relaxes interactive approval for claude (`--permission-mode auto`) and codex (`--ask-for-approval never --sandbox workspace-write`), because both otherwise stall in an unwatched pane. Bounded autonomy is deliberate: dangerous actions still escalate, and claude's `bypassPermissions` is worse than useless here since it stalls on its own startup confirmation. Opt-in configuration, never a shipped default, and `pi` is deliberately excluded.
- A subagent's kind is a deployment property, not a spawn-time choice: it comes from the persona definition's `kind:`, else the parent's kind. Only `--model` is chosen per assignment, and a model ID or weight alias never selects a harness. The separate `config.edn` chain replaces complete `:models` rows package < home < project and never deep-merges their harness columns or selects kind, while its `:aliases` map composes per key across that same chain, so an override can retarget one alias without restating a `:models` row. Translate the resolved model through that kind's roster column; pi receives the provider-qualified canonical ID by pass-through, since the shipped table carries no `:pi` column. The weights are `heavy`, `middle`, `light`, and `feather`; their per-kind values are enumerated once, in `skills/herdr-orch/scripts/docs/contract.md` § Model resolution.

# Git operations
- When moving files controlled by git, ALWAYS use `git mv` rather than `mv` -- this preserves history.
- When reverting file changes you made, use git instead of editing the file again.
- `pi/settings.json` is tracked but rewritten by pi at runtime; a clone-local `pi-settings` clean filter (`.gitattributes` + `./install-git-filter.sh`, requires `jq`) strips `lastChangelogVersion`, `defaultProvider`, and `defaultModel` at stage time. Under dotfiles the filter is registered by Home Manager activation (`agents.nix` -> `installPiSettingsGitFilter`); on any other clone run the script once. If those keys show up in a diff, the filter is not installed -- do not hand-revert the file.
- For commit messages, see the `git-commit` skill. Commit bodies should refer to associated design change records rather than restating detail.

# File operations
- Use `rg` for file and content searches.
- Verify a flag means what you assume before trusting output: `rg -r` is `--replace`, not recursive (rg recurses by default), `rg -E` is `--encoding` and swallows a pattern passed as its value (use `-e`), and Rust-regex escaping differs from POSIX (`\+` matches a literal plus), so a wrong flag or pattern usually yields confident wrong output rather than an error. Never fold `-r` into a flag cluster: `rg -rn 'pattern'` consumes `n` as the replacement template and prints every match rewritten to `n`, which looks like real evidence.
- A search pattern taken from data can itself begin with `-` and be consumed as options: `grep -qxF "$line"` breaks on a line starting `---` or `- `, printing a usage error per line while the loop treats each as a mismatch. Pass such a pattern as `grep -e "$pat"`, or after `--`. Because it only breaks for some inputs, a successful spot-check does not clear it, and the remaining misreads look like real findings.
- Verify a rename in both directions before calling it complete. BSD `sed` (macOS) does not support `\b` and silently matches nothing, and a pattern that is a prefix of other tokens rewrites those too -- one rename both missed two call sites and rewrote an unrelated `%focusx`. Prefer Babashka, whose Java regex honours `\b` on every platform: count first with `(re-seq #"%focus\b" text)`, rewrite with `str/replace`, then re-grep the old name.
- A word boundary after a non-word character never matches: `\bname!\b` finds nothing, because `\b` after `!` requires a following word character. When renaming an identifier ending in `!`, `?`, or `-`, drop the trailing `\b` and re-grep the old name before running tests -- a bulk rename can otherwise miss most call sites silently.
- Single-quote shell search patterns containing backticks or `$` (common with markdown-derived text); double quotes invite command substitution.
- Prose passed as a CLI argument (a task body, commit message, or assignment) belongs in a quoted heredoc written to a file, then passed as `"$(cat file)"`. Apostrophes terminate a single-quoted argument, and the remainder is then executed as shell -- observed truncating an `ot create --body` and handing its tail to bash. Substitution output is not re-scanned, so backticks inside the file are safe.
- Cap test-failure output (`head -c`) when an asserted value can embed a file's contents or a captured log; `grep -A` on such an assertion has dumped ~10k tokens to report one boolean. The assertion line alone locates the failure.
- Prefer available structured read/edit tools over ad-hoc scripts for routine file inspection and modification.
- When scripting is necessary, prefer Babashka to Python for repository-local automation. Use Python when invoking an existing Python tool or when its ecosystem is materially better suited.
- For scripted transformations, write a candidate under `<repository-root>/.tmp/`, inspect its diff, and only then replace the source; do not perform unverified in-place rewrites.
- Produce any artifact claimed "verbatim except for listed edits" by copying the source and applying targeted edits, then diff-verify before stating that claim. A hand-retyped persona rewrite drifted two full stops while its six copy-then-edit siblings stayed byte-identical, and the identity claim was asserted before anything checked it.
- A human may be editing the same file. Before replacing one wholesale, check for a live editor lock (an Emacs `.#<basename>` sibling naming a running PID) and prefer targeted edits, which fail loudly on stale text instead of silently discarding unsaved work. Re-read immediately before editing any file the user has touched this session.
- Redirect long-running or expensive command output to a file under `<repository-root>/.tmp/` and read slices from it. Piping through `head`/`tail` discards the rest and often forces a costly re-run.
- Never state a diagnostic tool's verdict from a truncated view. `ot doctor | tail -3` printed a clean-looking tail twice while two ERRORs sat above the cut, and the record was reported as well-formed on that basis. Read the finding-count summary line, or the whole report, before claiming health.
- Do not author unicode dashes in prose. Write `--` (org converts it on export) or use an org descriptive list `- Term :: detail`; literal em-dashes have been emitted as `\uXXXX` escapes and committed as mojibake. Where a format makes one significant syntax -- the herdr-orch `ARTIFACTS` item splits on a literal ` — ` -- leave it alone.

# Temporary files
Resolve the repository root with `git rev-parse --show-toplevel`, then use
`<repository-root>/.tmp/` for scripts, data, experiments, testing, and other
ad-hoc work. Do not assume `$PROJECT_ROOT` is defined.

Never put transient state under `.agents/`: that tree is durable agent configuration,
and some harnesses (codex under `--sandbox workspace-write`) deliberately mount it
read-only so an agent cannot rewrite its own instructions mid-task. A scratch or result
path inside it fails to write for those children.

# Agent guide: the dotagents repository

This file defines project rules for agents that work on this repository. It does not repeat rules that another file owns: portable rules are in `home/AGENTS.md`, which `agents.nix` installs as `~/.pi/agent/AGENTS.md`, and each skill under `skills/` owns its own behaviour.

For `ot` CLI development, read `skills/org-tasks/scripts/AGENTS.md`.

# Layout and maintenance

## herdr-orch
- This repository's `subagents/` directory is the source for the home override layer.
- The dotfiles `agents.nix` module symlinks `subagents/` to `~/.agents/subagents/`.
- Edit `subagents/` in this repository. Do not edit `~/.agents/subagents/`.
- The `herdr-orch` skill owns definition resolution, `config.edn` merge semantics, and model translation. Its contract is in `skills/herdr-orch/scripts/docs/contract.md`.

## pi settings
- Pi rewrites tracked `pi/settings.json` at runtime. The clone-local `pi-settings` clean filter strips the volatile keys during staging. README.md documents the filter.
- If the volatile keys appear in a diff, the filter is not installed. Run `./install-git-filter.sh`. Do not manually revert `pi/settings.json`.

# Prose conventions
## Line wrapping
- Do not hard-wrap prose that this repository owns. Use one logical line for each paragraph and each list item.
- This convention applies to `AGENTS.md`, skill bodies, skill references, and org files that `org-tasks` manages.
- Do not reflow a vendored skill. `skills/README.org` identifies vendored skills.

## Unicode
- Do not use unicode characters for decoration - only when they convey information.
- Do not write Unicode dashes in prose. Write `--`, which org converts during export. Alternatively, use an org descriptive list such as `- Term :: detail`.
- Do not write the section sign (U+00A7). See Section references.

## Section references
- Refer to a heading in a repository document with a link whose path is relative to the linking file.
- In org, write `[[file:path::*Heading]]` with no description, or `[[*Heading]]` for the same file. Org generates the link title from the target.
- In Markdown, write `[path#Heading](path#anchor)`, or `[#Heading](#anchor)` for the same file. The anchor is the GitHub heading anchor.
- Org cannot link to a Markdown heading. From org, write `[[file:path.md]] section 'Heading'`.
- Where a link cannot work, such as in a code comment or for an external document, write `path section 'Heading'` and quote the heading.

## Scripts and transformations
- New automation scripts in the repository-root `scripts/` directory must use Babashka by default.
- A thin shell wrapper may use shell when shell is the suitable interface, as `scripts/check.sh` does.

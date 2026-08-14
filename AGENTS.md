# Agent guide: the dotagents repository

This file defines project rules for agents that work on this repository.

Portable rules are in `home/AGENTS.md`. The `agents.nix` module installs that file as `~/.pi/agent/AGENTS.md`. This file does not repeat those rules.

Each skill under `skills/` and its references defines that skill's behaviour. Do not repeat that behaviour in this file.

For `ot` CLI development, read `skills/org-tasks/scripts/AGENTS.md`.

# Layout and maintenance

## herdr-orch
- This repository's `subagents/` directory is the source for the home override layer.
- The dotfiles `agents.nix` module symlinks `subagents/` to `~/.agents/subagents/`.
- Edit `subagents/` in this repository. Do not edit `~/.agents/subagents/`.
- The `herdr-orch` skill owns definition resolution, `config.edn` merge semantics, and model translation.
- The contract for those behaviours is in `skills/herdr-orch/scripts/docs/contract.md`. Do not repeat the contract here.

## pi settings
- Pi tracks `pi/settings.json`, but Pi can rewrite it at runtime.
- The clone-local `pi-settings` clean filter removes volatile keys during staging.
- The volatile keys are `lastChangelogVersion`, `defaultProvider`, and `defaultModel`.
- The filter configuration is in `.gitattributes` and `./install-git-filter.sh`. The filter requires `jq`.
- Under dotfiles, Home Manager activation registers the filter through `agents.nix` and `installPiSettingsGitFilter`.
- On any other clone, run `./install-git-filter.sh` once.
- If the volatile keys appear in a diff, the filter is not installed. Do not manually revert `pi/settings.json`.

# Prose conventions
## Line wrapping
- Soft wrap prose that this repository owns
- Use one logical line for each paragraph and each list item.
- This convention applies to `AGENTS.md`, skill bodies, skill references, and org files that `org-tasks` manages.
- Do not reflow a vendored skill. `skills/README.org` identifies vendored skills.

## Unicode
- Do not use unicode characters for decoration - only when they convey information.
- Do not write Unicode dashes in prose. Write `--`, which org converts during export. Alternatively, use an org descriptive list such as `- Term :: detail`.

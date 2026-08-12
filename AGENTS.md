# Agent guide: the dotagents repository

Project rules for agents working *on* this repository. The portable rules that apply in every project live in `home/AGENTS.md`, which `agents.nix` projects to `~/.pi/agent/AGENTS.md`, and are deliberately not repeated here.

Skill behaviour is owned by the skill under `skills/` and its references, never restated in this file. For developing the `ot` CLI specifically, see `skills/org-tasks/scripts/AGENTS.md`.

# Layout and maintenance
- The home override layer at `~/.agents/subagents/` is this repository's `subagents/`, symlinked by the dotfiles `agents.nix` -- edit it here, never under `~`. Definition resolution order, `config.edn` merge semantics, and model translation belong to the `herdr-orch` skill and its `scripts/docs/contract.md`; do not restate them here.
- `pi/settings.json` is tracked but rewritten by pi at runtime; a clone-local `pi-settings` clean filter (`.gitattributes` + `./install-git-filter.sh`, requires `jq`) strips `lastChangelogVersion`, `defaultProvider`, and `defaultModel` at stage time. Under dotfiles the filter is registered by Home Manager activation (`agents.nix` -> `installPiSettingsGitFilter`); on any other clone run the script once. If those keys show up in a diff, the filter is not installed -- do not hand-revert the file.

# Prose conventions
- Soft-wrap prose this repository owns: one logical line per paragraph and per list item, in `AGENTS.md`, skill bodies and their references, and the org files `org-tasks` manages. Never reflow a vendored skill (`skills/README.org` § Vendored names them) -- the edit is lost on the next sync and inflates the diff against upstream.
- Do not author unicode dashes in prose. Write `--` (org converts it on export) or use an org descriptive list `- Term :: detail`; literal em-dashes have been emitted as `\uXXXX` escapes and committed as mojibake. Where a format makes one significant syntax -- the herdr-orch `ARTIFACTS` item splits on a literal ` — ` -- leave it alone.


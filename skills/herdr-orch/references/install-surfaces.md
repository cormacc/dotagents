# Install surfaces

Read when a user asks how to install the herdr-orch or org-tasks Herdr surfaces, or when a surface reports that `oh` or `ot` is not installed.

Two surfaces use the herdr-orch and org-tasks skills. They can run side by side on the same machine:

- Herdr TUI plugins :: `cormacc.herdr-orch` (quota pane, tab-bar status, sidebar agent tree) and `cormacc.org-tasks` (task pane and selector).
- herdr-app mods :: the `herdr-orch` layout, with the quota, spaces, task and system mods.

Both surfaces run `oh` and `ot` from `~/.agents/skills/herdr-orch` and `~/.agents/skills/org-tasks`. The TUI plugins fall back to the skills in their own dotagents checkout; the herdr-app mods have no fallback. A Pi package install puts the skills in the package directory, not at `~/.agents/skills`, so it does not satisfy the herdr-app mods.

Neither surface installs the skills or the other surface. Install the skills first, then each surface that you want, from its own instructions:

- Herdr TUI herdr-orch plugin :: <https://github.com/cormacc/dotagents/blob/main/herdr/plugins/herdr-orch/README.md#install>
- Herdr TUI org-tasks plugin :: <https://github.com/cormacc/dotagents/blob/main/herdr/plugins/org-tasks/README.md#requirements-and-installation>
- herdr-app layout and mods :: <https://github.com/cormacc/dotagents/blob/main/herdr-app/README.md#install-and-select>. Its "Without Home Manager" procedure also says how to link the skills to `~/.agents/skills`.

When the home skills and a TUI plugin come from different checkouts, the two surfaces can run different revisions of `oh` and `ot`. The herdr-orch plugin's doctor action reports this.

## Emacs tabs

The Emacs tab keys in both front ends run `emacs-tab` by absolute path, `$HOME/dotfiles/agents/emacs/herdr-tabs/emacs-tab`. It is not on `PATH`, and no installer links it. The machine that runs it needs the dotfiles checkout at `~/dotfiles` with the dotagents submodule, `bb`, `emacsclient`, and `herdr` (herdr TUI) or `hc` (herdr-app). For a herdr-app space on another machine, that machine runs it. The Emacs views also need the dotfiles Emacs configuration, which loads `herdr-tabs.el`.

- herdr TUI :: `keys.command` entries in the tracked `herdr/config.toml`. Run `herdr server reload-config` after a change.
- herdr-app :: the `emacs-magit` and `emacs-plan` mods and the key tables. See <https://github.com/cormacc/dotagents/blob/main/herdr-app/README.md#emacs-tabs>.

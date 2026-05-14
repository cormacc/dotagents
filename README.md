# dotagents

Agent skills and pi extensions for [pi-coding-agent][pi], Claude Code,
OpenAI Codex, and any other tool that honours the [Agent Skills][skills]
specification. The repo is a monorepo: it ships generic
harness-agnostic skills, pi-specific skills and extensions, prompt
templates, and Nix/pi packaging metadata, all editable in place.

The flagship bundle is **`@cormacc/agent-org-memory`** — the org-mode
task-memory protocol (`org-tasks`, `org-plan`, `org-jira`) plus the pi
extensions that implement it (`tasks`, `jira`, `emacsclient`).
Future bundles (e.g. a Clojure-development skill family) will be added
alongside it as separate manifests.

## Repository layout

```
dotagents/
├── README.md            # this file
├── AGENTS.md            # pi-side agent instructions (lands at ~/.pi/agent/AGENTS.md)
├── LICENSE              # MIT
├── package.json         # @cormacc/agent-org-memory pi-package manifest
├── agent-org-memory.nix # Nix derivation for the org-memory slice
├── flake.nix            # exposes packages.<system>.agent-org-memory
├── prompts/             # prompt templates (init.md, system/*.md)
├── emacs/               # lightweight Emacs companions for protocol skills (e.g. tasks-org)
├── skills/              # generic harness-agnostic skills (Vercel-Labs-skills compatible)
│   ├── org-tasks/       # task-memory protocol
│   ├── org-plan/        # change-record structure
│   ├── org-jira/        # Jira-on-org-tasks layer
│   └── …                # other generic skills (clojure, review, self-improvement, …)
└── pi/
    ├── skills/          # pi-specific skills (depend on pi extensions; install under ~/.pi/agent/skills)
    └── extensions/      # loadable pi extensions
        ├── tasks/       # org-memory packaged
        ├── jira/        # org-memory packaged
        ├── emacsclient/ # org-memory packaged
        ├── lib/         # shared helpers (pi-utils.ts, editor.ts, wm.ts) — not a loadable extension
        ├── disabled/    # retired / experimental extensions; excluded from all package outputs
        └── …            # other active extensions (lsp, term, chromium, pi-clojure, …)
```

Two install destinations are deliberately preserved:

- `~/.agents/skills/` — the cross-agent location defined by the
  Agent Skills specification. Anything under `skills/` lands here.
- `~/.pi/agent/{extensions,skills,prompts,AGENTS.md}` — pi's own
  discovery locations. Pi-specific behaviour stays scoped here so
  other agents do not accidentally try to load TypeScript modules
  they cannot execute.

## Install

### As a pi package

Pi loads the slice declared in `package.json`'s `pi` field — exactly
the three extensions and three skills listed under
`pi.extensions` / `pi.skills`. Other entries in this repo are
*source*, not part of the pi package.

```bash
# Directly from this repo
pi install git:github.com/cormacc/dotagents

# From a local checkout
pi install /path/to/dotagents

# From a published npm release (N.B. we have no such thing as yet)
pi install npm:@cormacc/agent-org-memory
```

`pi install` writes the entry to `~/.pi/agent/settings.json` (or
`.pi/settings.json` with `-l`) so missing packages reinstall on
startup.

The org-memory file protocol intentionally aligns with native org-mode features: lifecycle hints use `#+TODO:` bang/at markers plus `#+STARTUP: logdone logdrawer`, archives use `#+ARCHIVE:`, external issues and plan paths use org-native `#+LINK:` abbreviations, and task/change-record files share root preamble through `TASKS.setup.org`. The pi extension remains the standalone implementation for headless/TUI writes; Emacs produces the same shapes when used as an editor.

### Via the flake

The flake exposes `packages.<system>.agent-org-memory` for the four
common systems (`x86_64-linux`, `aarch64-linux`, `x86_64-darwin`,
`aarch64-darwin`):

```nix
{
  inputs.dotagents = {
    url = "github:cormacc/dotagents";
    inputs.nixpkgs.follows = "nixpkgs";
  };

  outputs = { nixpkgs, dotagents, ... }:
    let pkg = dotagents.packages.x86_64-linux.agent-org-memory;
    in {
      # …reference `pkg` from your Home Manager / NixOS / nix-darwin config.
    };
}
```

`nix flake check` builds the package on every supported system.

### As an editable git submodule (recommended for the bundle author)

Pi extensions and skills are designed to be edited and reloaded in
place. Consume the repo as a submodule of your dotfiles checkout and
point Home Manager symlinks at the submodule working tree:

```bash
cd ~/dotfiles
git submodule add git@github.com:cormacc/dotagents agents-src
git submodule update --init --recursive
```

Then in your Home Manager module:

```nix
"${piConfig}/extensions".source =
  config.lib.file.mkOutOfStoreSymlink "${dotRoot}/agents-src/pi/extensions";
"${agentsConfig}/skills".source =
  config.lib.file.mkOutOfStoreSymlink "${dotRoot}/agents-src/skills";
# …etc.
```

`mkOutOfStoreSymlink` resolves to the live submodule path rather than
an immutable Nix store copy, so edits hot-reload (`/reload`) without a
`home-manager switch`.

### Generic skills via the Vercel Labs `skills` installer

The harness-agnostic skills under `skills/` follow the
[Agent Skills][skills] specification — each one is a directory with a
`SKILL.md` whose YAML frontmatter declares a `name` and
`description`. They are discoverable by the
[Vercel Labs `skills` CLI][vercel-labs-skills]:

```bash
# List skills in this repo
npx skills add cormacc/dotagents --list

# Install all skills to your default agent
npx skills add cormacc/dotagents

# Install a specific skill into a specific agent
npx skills add cormacc/dotagents --skill org-tasks --agent claude-code

# Install everything globally
npx skills add cormacc/dotagents --all -g
```

Pi-specific skills under `pi/skills/` are intentionally *not* exposed
through this installer — they assume pi extensions are present and
should be installed via the pi-package or submodule routes above.

## What this repo deliberately does *not* ship

- **User settings.** `settings.json`, per-extension override files
  (`tasks-ext.json`, `jira-ext.json`), prompt overrides, themes —
  compose those in your own pi configuration.
- **Disabled / experimental extensions.**
  `pi/extensions/disabled/` is preserved as source/history for
  retired experiments, but its contents are excluded from
  `package.json`'s `pi.extensions` and `files`, the Nix package's
  `lib.fileset` slice, and all flake outputs.
- **Tests.** Co-located `*.test.ts` and `test.sh` files exist in the
  source tree but are excluded from the package output.

## Releasing

This repo is consumed both by `pi install` and by Nix flakes / git
submodules. The release surfaces are tied to a single git tag:

```bash
# From a clean working tree on `main`
git tag -a v0.2.0 -m "agent-org-memory + foo extension"
git push origin v0.2.0
```

- **Pi consumers** pinned to `git:github.com/cormacc/dotagents` follow
  `main` automatically; tags only matter when a consumer pins a
  specific ref via `pi install git:github.com/cormacc/dotagents#v0.2.0`.
- **Flake input consumers** that pin `inputs.dotagents.url =
  "github:cormacc/dotagents/v0.2.0"` get a stable revision.
- **Submodule consumers** (e.g. the upstream `cormacc/dotfiles` repo)
  bump their submodule pointer with `git submodule update --remote`
  and commit the new SHA. Tags help them choose a target commit but
  aren't strictly required.

If you bump the manifest version (`package.json` `version`), keep the
git tag and the manifest version aligned.

## Runtime requirements

- **pi-coding-agent** for the pi extensions. Declared as a peer
  dependency; pi bundles the core packages itself.
- **Emacs** with a running server (`M-x server-start`) for the
  `emacsclient` extension's Elisp bridge. Used by `tasks` for
  in-place edits to org files.
- **Node 20+** (provided by pi's environment).

[pi]: https://github.com/mariozechner/pi-coding-agent
[skills]: https://agentskills.io/specification
[vercel-labs-skills]: https://github.com/vercel-labs/skills

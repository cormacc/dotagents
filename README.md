# dotagents

Agent skills, Pi extensions, prompts, custom subagents, and packaging metadata used by multiple coding harnesses. The repository is both an editable source tree and the source of the narrower **`@cormacc/agent-org-memory`** package.

The package bundle contains the org-mode task-memory protocol (`org-tasks`, `org-plan`, `org-jira`), its Pi implementations (`tasks`, `jira`, `emacsclient`), and Herdr orchestration (the `herdr-orch` skill and Pi extension). Everything else remains available through the source/submodule routes described below, not through that package slice.

## Repository layout

```text
dotagents/
├── AGENTS.md                 # this repo's own project rules (maintenance specifics)
├── CLAUDE.md                 # imports AGENTS.md for Claude Code in this repo
├── home/                     # portable agent rules, projected to each harness
│   ├── AGENTS.md             # -> ~/.pi/agent/AGENTS.md
│   └── CLAUDE.md             # -> ~/.claude/CLAUDE.md (one-line import of the above)
├── README.md
├── LICENSE
├── RETROLOG.org              # retro evidence behind the rules in AGENTS.md
├── TASKS*.org                # repository task memory
├── package.json              # agent-org-memory manifest + root checks
├── package-lock.json         # package and root validation dependencies
├── bb.edn / deps.edn         # ot Babashka/tools.deps policy and bb tasks
├── install-git-filter.sh     # registers the pi-settings clean filter
├── scripts/                  # repository checks and test runners
├── skills/                   # generic cross-agent skills; ot lives under org-tasks/scripts
│   └── herdr-orch/
│       ├── subagents/        # packaged Herdr persona defaults
│       └── traits/           # packaged trait prompts
├── subagents/                # home persona overrides and config.edn
├── traits/                   # home trait layer: extra traits and trait gates
├── prompts/                  # pi prompt templates
├── herdr/                    # Herdr config.toml and plugins/{org-tasks,herdr-orch}
├── herdr-app/                # Herdr desktop app settings, mods and layouts
├── emacs/                    # native org-mode protocol companion
├── design/log/               # durable change-records
├── dirge/                    # Dirge config and prompt set
└── pi/
    ├── settings.json         # owner-local editable-route pi settings
    ├── mcp.json              # native pi MCP server configuration
    ├── models.json           # pi provider and model overrides
    ├── skills/               # pi-only chromium and ext-dev skills
    └── extensions/           # active pi extensions
        ├── tasks, jira, emacsclient, herdr-orch, traits
        ├── chromium, dataspex, pi-clojure, lsp
        ├── ext-dev/, herdr-agent-state.ts, question.ts, systemprompt.ts
        ├── test/             # tests for the single-file extensions
        └── lib/              # shared code, not an extension entry point
```

## Installation routes

### Generic skills

Top-level directories under `skills/` follow the Agent Skills format and can be installed by a compatible loader, including the Vercel Labs CLI:

```bash
npx skills add cormacc/dotagents --list
npx skills add cormacc/dotagents
npx skills add cormacc/dotagents --skill org-tasks --agent claude-code
```

`~/.agents/skills/` is a cross-agent convention used by this setup. Pi also discovers it. Pi-specific skills under `pi/skills/` assume matching pi extensions and are not part of the generic skill route.

### Terminal Herdr task interface

[herdr/plugins/org-tasks/README.md](herdr/plugins/org-tasks/README.md) documents the tab-local shared `ot` TUI, explicit plugin setup, configuration and safety checks. It is installed separately from the [herdr-orch plugin](herdr/plugins/herdr-orch/README.md), which provides quota display and the sidebar agent tree. The task adapter follows the focused local agent or shell pane.

### Pi package

The `pi` manifest in the root `package.json` declares four Pi extensions and four skills:

- `pi/extensions/{tasks,jira,emacsclient,herdr-orch}`
- `skills/{org-tasks,org-plan,org-jira,herdr-orch}`

Subscription reporting uses an owned Babashka collector for Anthropic and OpenAI Codex, with no npm collector dependency. It reads the selected harness's credentials and leaves refresh to that harness. The `herdr-orch` extension runs the `oh` launcher of the bundled skill when `~/.agents/skills/herdr-orch` does not exist. The generic skill route uses the same collector without Pi or a separate setup step. See [skills/herdr-orch/scripts/docs/contract.md#Usage](skills/herdr-orch/scripts/docs/contract.md#usage).

Install from git or a checkout:

```bash
pi install git:github.com/cormacc/dotagents
pi install /absolute/path/to/dotagents
```

The npm name is reserved but not currently published. The package route does **not** configure task roots, install custom agents, prompts, `pi/skills`, `pi/settings.json`, `pi/mcp.json`, Dirge files, `ot` as a shell command, or unrelated extensions. Package-only users must install `ot` separately, for example through `bbin`.

The npm `files` allowlist includes the selected source directories, with their co-located tests. Pi loads only manifest-declared entry points.

Pi records package installs in settings. Existing unpinned git checkouts advance when `pi update --extensions` or `pi update --all` runs; startup only reinstalls missing packages. A configured tag/commit remains pinned until the install spec is changed.

### Editable git submodule and Home Manager

The owner's dotfiles checkout keeps this repository at `~/dotfiles/agents` and uses `mkOutOfStoreSymlink`, so edits remain live:

```bash
git -C ~/dotfiles submodule update --init --recursive
```

The consuming `agents.nix` links:

- `skills/` → `~/.agents/skills` (including `skills/herdr-orch/subagents/` packaged persona defaults) and `~/.claude/skills`
- `subagents/` → `~/.agents/subagents`
- `traits/` → `~/.agents/traits`
- `skills/org-tasks/scripts/ot` → `~/.local/bin/ot`
- `home/AGENTS.md`, `prompts/`, `pi/extensions/`, `pi/skills/`, `pi/settings.json`, `pi/mcp.json`, and `pi/models.json` → `~/.pi/agent/...`. The repo-root `AGENTS.md` is deliberately *not* projected: it is this repository's project file, picked up by ordinary cwd discovery when working here.
- `home/CLAUDE.md` → `~/.claude/CLAUDE.md` (imports `home/AGENTS.md`, so claude and pi share one set of portable rules)
- `herdr/config.toml` → `~/.config/herdr/config.toml` (the file only; the directory also holds Herdr's sockets and session state)
- `herdr/plugins/{org-tasks,herdr-orch}/` → `~/.local/share/herdr-plugins/{org-tasks,herdr-orch}` (guarded leaves)
- `herdr-app/settings.json`, `herdr-app/mods/{herdr-orch,system,org-tasks,orch-spaces}`, and `herdr-app/layouts/herdr-orch.json` → `~/.config/herdr-app/...` (personal leaves only; bundled mods and layouts stay app-owned)
- `herdr-app/keys.darwin.json` (macOS) or `herdr-app/keys.linux.json` (Linux) → `~/.config/herdr-app/keys.json`

Herdr persona definitions resolve project (`<git-root>/.agents/subagents/`) > home (`~/.agents/subagents/`) > packaged (`skills/herdr-orch/subagents/`). Home Manager manages `~/.agents/subagents/` (out-of-store, from this repo's `subagents/`) so the harness permission overrides ship to every host; it holds home overrides only, never the packaged persona defaults. The parallel `config.edn` chain replaces complete model-ID rows in the same precedence order and never uses a model or weight alias to select kind. The packaged weights are `heavy`, `middle`, `light`, and `feather`; their per-kind values are enumerated once, in [skills/herdr-orch/scripts/docs/contract.md#Model resolution](skills/herdr-orch/scripts/docs/contract.md#model-resolution).

Activation fails early when the submodule is uninitialised. It registers each Herdr plugin leaf with `herdr plugin link`, and re-links a registration whose root is stale. It runs `./install-git-filter.sh` (see below). It installs local npm dependencies for chromium, pi-clojure, and dataspex, with lifecycle scripts disabled, when their manifests change. It does not install root validation dependencies; run `npm ci --ignore-scripts` explicitly for development. Chromium and pi-clojure carry exact direct versions plus tracked lockfiles; the root check exercises those locks with `npm ci`.

This editable route intentionally preserves owner-local settings ownership: tracked `pi/settings.json` is live-linked and may be edited in the submodule. A clone-local `pi-settings` clean filter removes configured volatile fields (`lastChangelogVersion`, `deviceId`) before commit; it requires `jq` on PATH and is declared `required = true`. `defaultProvider` and `defaultModel` are tracked: pi saves them only when you press Ctrl+S on a model in `/model`. Under the dotfiles Home Manager wiring, activation runs the installer on every switch, so a changed field list reaches every clone; on any other clone run `./install-git-filter.sh` from the checkout root (idempotent; it exits early when the filter is current). A git command that rewrites the working tree (`git stash`, `git reset --hard`, `git checkout -- pi/settings.json`) writes back the committed copy without the stripped fields. The package route, by contrast, ships no settings.

Changes in out-of-store links take effect after pi `/reload`; no Home Manager switch is required unless the wiring itself changes.

The `simple` trait (`skills/herdr-orch/traits/simple/`), the `prune` trait (repository-root `traits/prune/`), and the `simplify` skill provide lean-code review adapted from the third-party `git:github.com/DietrichGebert/ponytail` package, which this setup does not install. `simple` records its ponytail source in `adapted-from:` metadata, and `simplify` records its ponytail sources in its Provenance section. To compare against upstream, run `pi install git:github.com/DietrichGebert/ponytail`, and `pi remove` the same source afterwards.

## Additional skill roots

Skills from another source, such as an organisation's process repository or a shared submodule, stay in that source. Link the whole skills directory, not individual skills, so that a new skill in the source is visible after pi `/reload` with no change here.

Both strategies use two pi behaviours (pi 1.5.2, `dist/core/skills.js`):

- Discovery recurses below a skill root until it finds `SKILL.md`, and follows directory symlinks. It skips entries whose names start with `.`, entries that a `.gitignore`, `.ignore` or `.fdignore` in the scanned tree excludes, and broken symlinks, with no diagnostic.
- Two skills with the same name: the first discovered one loads, and pi shows a collision warning.

Claude Code reads only `~/.claude/skills/<name>/SKILL.md` and `<project>/.claude/skills/<name>/SKILL.md`, one level deep (see the Claude Code link comment in the dotfiles `agents.nix`), so neither strategy reaches it.

### Global: `~/.agents/skill-roots`

The `skills` entry in `pi/settings.json` adds `~/.agents/skill-roots` as a pi skill root. Each child of that directory is a symlink to an external skills directory. The host configuration that owns the external source creates the link, so this repository holds no organisation-specific path. For example, with Home Manager:

```nix
home.file.".agents/skill-roots/<source-name>".source =
  config.lib.file.mkOutOfStoreSymlink "${config.home.homeDirectory}/<path>/.agents/skills";
```

Do not put the link in `~/.agents/skills`. That directory is this repository's `skills/` working tree, so the link becomes untracked content, and a `.gitignore` rule that hides it also hides it from pi.

known-limit: a host with no `~/.agents/skill-roots` directory shows a pi startup warning (`skill path does not exist`). Create the directory on that host, or remove the setting, if the warning matters.

### Project: skills in a submodule

Pi discovers `.agents/skills` in the working directory and in each ancestor up to the Git repository root. A submodule's `.agents/skills` is not an ancestor, so pi does not see it from the parent repository. Commit a relative symlink in the parent:

```bash
ln -s ../../<submodule-path>/.agents/skills .agents/skills/<submodule-name>
```

The link target is relative to `.agents/skills/`, so `../../` is the parent repository root. The link works from every subdirectory of the parent. If the submodule is not initialised, the link is broken and pi skips it. A Windows checkout without symlink support gets a plain file instead, and the submodule skills are silently absent.

The alternative is a `skills` entry in the parent's `.pi/settings.json`, which resolves relative to `.pi/`. It does not need symlinks, but pi reads `.pi/settings.json` only from the working directory, after project trust, so a session that starts in a subdirectory does not see it.

## Runtime requirements

- **Node.js >= 22.19.0**, the highest `engines` floor of the locked dependencies in `package-lock.json`.
- **pi-coding-agent** for pi extensions and package management.
- **Babashka (`bb`)** for `ot`; copying the scripts does not provide the runtime.
- **Emacs/emacsclient** for Emacs-backed tools and task edit actions. Edit/open actions probe the server and can start `emacs --daemon`; direct eval/read/write tools still report connection/startup failures.
- **Atlassian MCP** configured in `pi/mcp.json` (linked to `~/.pi/agent/mcp.json`; sign in with `pi mcp login atlassian`) for Jira network workflows.
- Extension-local npm dependencies for chromium, pi-clojure, and dataspex on the editable route. Root npm dependencies (`npm ci --ignore-scripts`) are development and validation tooling, not quota-reporting requirements.

## Validation

Install declared validation dependencies and run the single root check:

```bash
npm run check
```

The command (`scripts/check.sh`) does these steps:

1. Installs the root, chromium and pi-clojure dependencies from the tracked locks with `npm ci --ignore-scripts`.
2. Runs `bb test` in an isolated temporary root. This runs the org-tasks and herdr-orch Clojure suites, the herdr-orch Pi adapter suites inside the installed Pi runtime, and the repository and skill script tests (`scripts/test-*.bb` and the `test-*.bb` scripts of individual skills).
3. Runs each `pi/extensions/*/test.sh`.
4. Checks that section references resolve (`scripts/check-section-links.bb`).
5. Validates skill frontmatter with skill-creator in a clean Python venv (`scripts/validate-skills.bb`), and packages one skill as a smoke test.

`npm run check` requires Node/npm, `bb`, Python 3 with venv support, Emacs, pi on `PATH`, and network access for clean npm/Python dependency installs.

For task-memory health after repository changes:

```bash
ot doctor --format json
```

## Releases

Keep the manifest version and annotated git tag aligned. Git/package consumers choose their own refs; editable submodule consumers update and commit the parent repository's submodule pointer. Do not push tags or submodule updates implicitly.

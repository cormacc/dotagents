# dotagents

Agent skills, pi extensions, prompts, custom subagents, and packaging metadata used by multiple coding harnesses. The repository is both an editable source tree and the source of the narrower **`@cormacc/agent-org-memory`** pi package.

The package bundle contains the org-mode task-memory protocol (`org-tasks`, `org-plan`, `org-jira`) and its pi implementations (`tasks`, `jira`, `emacsclient`). Everything else remains available through the source/submodule routes described below, not through that package slice.

## Repository layout

```text
dotagents/
├── AGENTS.md                 # pi-side operating instructions
├── README.md
├── TASKS*.org                # repository task memory
├── package.json              # agent-org-memory manifest + root checks
├── package-lock.json         # root validation dependencies
├── bb.edn / deps.edn         # ot Babashka/tools.deps policy
├── skills/                   # generic cross-agent skills; ot lives under org-tasks/scripts
├── prompts/init.md           # tracked prompt template
├── emacs/                    # native org-mode protocol companion
├── design/log/               # durable change-records
├── mcp.json                  # tracked generic MCP server configuration
├── dirge/                    # Dirge config and prompt set
└── pi/
    ├── agents/               # custom pi-subagents definitions
    ├── settings.json         # owner-local editable-route pi settings
    ├── skills/               # pi-only chromium and ext-dev skills
    ├── extensions/           # active pi extensions
    │   ├── tasks, jira, emacsclient
    │   ├── chromium, dataspex, pi-clojure, lsp
    │   ├── ext-dev.ts, herdr-agent-state.ts, question.ts, systemprompt.ts
    │   └── lib/              # shared code, not an extension entry point
    └── archive/              # inactive historical extensions/skills
```

`pi/archive/` is deliberately retained history. Pi does not discover it from the editable `~/.pi/agent/extensions`/`skills` links, and package manifests do not include it.

## Installation routes

### Generic skills

Top-level directories under `skills/` follow the Agent Skills format and can be installed by a compatible loader, including the Vercel Labs CLI:

```bash
npx skills add cormacc/dotagents --list
npx skills add cormacc/dotagents
npx skills add cormacc/dotagents --skill org-tasks --agent claude-code
```

`~/.agents/skills/` is a cross-agent convention used by this setup. Pi also discovers it. Pi-specific skills under `pi/skills/` assume matching pi extensions and are not part of the generic skill route.

### Org-memory pi package

The root `package.json` exposes exactly three pi extensions and three skills:

- `pi/extensions/{tasks,jira,emacsclient}`
- `skills/{org-tasks,org-plan,org-jira}`

Install from git or a checkout:

```bash
pi install git:github.com/cormacc/dotagents
pi install /absolute/path/to/dotagents
```

The npm name is reserved but not currently published. The package route does **not** install custom agents, prompts, `pi/skills`, `pi/settings.json`, `mcp.json`, Dirge files, `ot` as a shell command, or unrelated extensions. Package-only users who need the CLI must install `ot` separately (for example through `bbin`) and must install any omitted extension/runtime explicitly.

The npm `files` allowlist includes the selected source directories. Pi loads only manifest-declared entry points. The Nix derivation applies an additional package-slice filter that removes co-located `*.test.ts`, `test.sh`, and `default.nix`; do not generalize that test-exclusion claim to every install route or every test asset.

Pi records package installs in settings. Existing unpinned git checkouts advance when `pi update --extensions` or `pi update --all` runs; startup only reinstalls missing packages. A configured tag/commit remains pinned until the install spec is changed.

### Nix package

The flake exposes `packages.<system>.agent-org-memory` and matching checks for four declared systems. Build the package for the current system:

```bash
nix build .#agent-org-memory
```

Evaluate the current/native-system package check with project conventions:

```bash
nix flake check --impure --no-build
```

That command does not prove cross-system builds. Use an appropriate builder and explicit system selection for broader coverage.

### Editable git submodule and Home Manager

The owner's dotfiles checkout keeps this repository at `~/dotfiles/agents` and uses `mkOutOfStoreSymlink`, so edits remain live:

```bash
git -C ~/dotfiles submodule update --init --recursive
```

The consuming `agents.nix` links:

- `skills/` → `~/.agents/skills`
- `skills/org-tasks/scripts/ot` → `~/.local/bin/ot`
- `AGENTS.md`, `prompts/`, `pi/extensions/`, `pi/skills/`, `pi/agents/`, and `pi/settings.json` → `~/.pi/agent/...`
- `mcp.json` → `~/.config/mcp/mcp.json`

Activation fails early when the submodule is uninitialized. It installs local npm dependencies for chromium, pi-clojure, and dataspex when their manifests change. Chromium and pi-clojure now carry exact direct versions plus tracked lockfiles; the root check exercises those locks with `npm ci`.

This editable route intentionally preserves owner-local settings ownership: tracked `pi/settings.json` is live-linked and may be edited in the submodule. A clone-local clean filter installed by `install-git-filter.sh` removes configured volatile fields before commit. The package route, by contrast, ships no settings.

Changes in out-of-store links take effect after pi `/reload`; no Home Manager switch is required unless the wiring itself changes.

## Runtime requirements

- **Node.js >= 22.19.0**, matching the installed pi-compatible runtime floor.
- **pi-coding-agent** for pi extensions and package management.
- **Babashka (`bb`)** for `ot`; copying the scripts does not provide the runtime.
- **Emacs/emacsclient** for Emacs-backed tools and task edit actions. Edit/open actions probe the server and can start `emacs --daemon`; direct eval/read/write tools still report connection/startup failures.
- **Atlassian MCP** configured in `mcp.json` (or equivalent pi MCP configuration) for Jira network workflows.
- Extension-local npm dependencies for chromium, pi-clojure, and dataspex on the editable route.

## Validation

Install declared validation dependencies and run the single root check:

```bash
npm run check
```

The command uses the tracked locks (`npm ci`), runs every active extension test runner, the Babashka task suite, installed-parser agent validation, skill metadata/inventory and GitLab routing checks, active relative-link and command/tool collision checks, clean skill-creator validation/packaging, and `nix flake check --impure --no-build` for the native system.

It requires Node/npm, `bb`, Python 3 with venv support, Nix, Emacs, pi on `PATH`, network access for clean npm/Python dependency installs, and the installed `git:github.com/edxeth/pi-subagents` parser. Override parser source with `PI_SUBAGENTS_SOURCE=/path/to/src/agents/definitions.ts` when needed.

For task-memory health after repository changes:

```bash
ot doctor --format json
```

## Releases

Keep the manifest version and annotated git tag aligned. Git/package and flake consumers choose their own refs; editable submodule consumers update and commit the parent repository's submodule pointer. Do not push tags or submodule updates implicitly.

[pi]: https://github.com/earendil-works/pi-mono

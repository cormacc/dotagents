# Repository audit — 2026-07-14

This is the durable evidence behind [`../2026-07-14-housekeeping.org`](../2026-07-14-housekeeping.org). The audit compared repository documentation, skills, custom agents, active extensions, configuration, dependency manifests, and tests against the checked-in source and the installed pi/glab interfaces. No source files were modified during the audit.

## Verdict

Needs changes. `README.md` is materially stale; `AGENTS.md` has one portability defect. The highest-impact runtime issues are invalid custom-agent definitions, extension failures returned as successful tool results, unbounded `/ext` context injection, and undiscoverable GitLab sub-skills.

## Documentation findings

### README.md

1. `README.md:190-197` omits the Babashka `bb` runtime required by the bundled `ot` launcher (`pi/extensions/tasks/ot.ts:26-35,69-83,93-95`; `skills/org-tasks/scripts/ot:31-38`). `agent-org-memory.nix:56-74` copies but does not wrap/provide `bb`.
2. `README.md:149-151` says `pi/skills/` can be installed through the pi package, but `package.json:22-26` and `agent-org-memory.nix:34-44` include only the generic org skills.
3. `README.md:153-157` says the repository does not ship user settings, while tracked `pi/settings.json` is linked to `~/.pi/agent/settings.json` by the editable setup (`../agents.nix:175-178`). The package slice, not the source repository, excludes settings.
4. The layout at `README.md:25,39-40` lists nonexistent `prompts/system`, `pi/extensions/disabled`, and active `term`; current source uses `prompts/init.md`, `pi/archive/{extensions,skills}`, and archived `tty`. It omits `pi/agents`, `pi/settings.json`, `dirge`, `mcp.json`, and the Babashka `ot` project.
5. `README.md:43-50` omits editable-install destinations and activation behavior: `~/.local/bin/ot`, `~/.config/mcp/mcp.json`, `~/.pi/agent/agents`, `~/.pi/agent/settings.json`, and npm installs for chromium, pi-clojure, and dataspex (`../agents.nix:60-95,143-178`).
6. `README.md:153,163-164` overstates test exclusion. The Nix filter excludes co-located `*.test.ts`, `test.sh`, and `default.nix`, while other test assets remain; npm includes whole listed directories (`agent-org-memory.nix:22-28,41`; `package.json:33-41`).
7. `README.md:99` says ordinary `nix flake check` checks every supported system. The flake defines four checks, but the command checks the host system unless `--all-systems` is used (`flake.nix:13-18,32-35`).
8. `README.md:177-179` says unpinned git installs follow `main` automatically at startup. Existing checkouts advance through `pi update --extensions` or `pi update --all`; pinned refs remain fixed.
9. `README.md:194-196` requires a running Emacs server, but edit actions probe and can start `emacs --daemon` (`pi/extensions/emacsclient/emacsclient.ts:44-83`; `pi/extensions/tasks/index.ts:1098-1109`). Jira workflows additionally require an Atlassian MCP provider (`pi/extensions/jira/README.md:3-7,57-69`).
10. `README.md:45-46` attributes `~/.agents/skills` to the Agent Skills specification; it is a cross-agent convention used by this setup.
11. `README.md:197` says Node 20+, while installed pi 0.80.6 declares Node `>=22.19.0`.

Confirmed accurate: the package's org-memory slice (`package.json:16-26`; `agent-org-memory.nix:34-44`), flake outputs (`flake.nix:13-18,24-35`), the org setup chain, the repository's submodule relationship, and unpublished npm status.

### AGENTS.md

`AGENTS.md:17-18` directs temporary work to `$PROJECT_ROOT/.agents/tmp/`, but current pi does not define `PROJECT_ROOT`. Resolve the repository with `git rev-parse --show-toplevel` and use `<root>/.agents/tmp/`.

### Undocumented source behavior

- `.gitattributes:1-6` and `install-git-filter.sh:24-41` configure a clone-local `jq` clean filter for volatile `pi/settings.json` fields.
- The Home Manager bridge fails before activation if the submodule lacks `package.json` (`../agents.nix:30-56`).
- `bb.edn` exposes the full `ot` CLI/TUI and bbin entry point.
- `pi/agents/` contains seven custom subagent definitions installed ahead of bundled defaults.
- `mcp.json` and `dirge/config.json`/`dirge/prompts/*` are first-class tracked surfaces.

## Custom-agent findings

1. `pi/agents/planner.md:1-7,262-266,295-299` omits `spawning: true` but instructs the planner to spawn helpers. Its examples violate the installed schema: `name` must be lower-kebab and `title` is required.
2. `pi/agents/reviewer.md:4,54-56` and `pi/agents/scout.md:4,68-70` allow only `read,bash` but instruct the agent to use `write`. Scout's `output: context.md` frontmatter is unsupported and ignored.
3. `pi/agents/visual-tester.md:4-6,16,31-35` uses unsupported singular `skill`, references nonexistent `chrome-cdp`, and expects absent `scripts/cdp.mjs`. The repository provides the `chromium` skill and browser tools; supported metadata is `skills`/`inject-skills`.

## Extension findings

### Failure semantics

Current pi marks a tool result as failed when `execute()` throws. Returning an `isError` property is ignored, so the following paths currently produce success-shaped tool results on failure:

- `pi/extensions/dataspex/index.ts:147-152`
- `pi/extensions/jira/index.ts:228-237,273-300`
- `pi/extensions/emacsclient/index.ts:163-169,225-231`
- `pi/extensions/pi-clojure/tools/eval.ts:56-62`
- `pi/extensions/pi-clojure/tools/find-nrepl-port.ts:96-106`

Each path needs a regression test asserting thrown failure and useful diagnostics.

### Context and schemas

- `pi/extensions/ext-dev.ts:70-94,207-230` recursively loads every non-lock text file and injects it without an aggregate limit. The `tasks` tree contributes roughly 353 KB. Default to production source, support explicit selection, enforce per-file/aggregate ceilings, and report omissions.
- `pi/extensions/dataspex/index.ts:312` uses `Type.Union(Type.Literal(...))` for `op`; pi's cross-provider convention uses `StringEnum`, already used by other active extensions.

### Tests and reproducibility

- `pi/extensions/tasks/ot.test.ts:63-77` has two macOS failures from `/var/...` versus canonical `/private/var/...` paths.
- `pi/extensions/emacsclient/test.sh:30-42` assumes globally installed `tsx`; other runners use an `npx tsx` fallback.
- The root `package.json` has no unified test/typecheck script.
- Chromium and pi-clojure use version ranges while ignoring lockfiles (`pi/extensions/chromium/package.json`, `pi/extensions/chromium/.gitignore`, `pi/extensions/pi-clojure/package.json`, `pi/extensions/pi-clojure/.gitignore`). Activation-time npm installs are not reproducible.
- `bb.edn` and `deps.edn` independently declare overlapping libraries at different versions/sources.

Observed audit baseline:

- Babashka: 234 tests, 829 assertions, 0 failures.
- Jira: 49 passing.
- Tasks: 2 failures from temporary-path canonicalization.
- Emacsclient runner: blocked by global `tsx` assumption.
- Native-system `nix flake check --no-build`: passed.

## Skill findings

### GitLab hierarchy

- `skills/gitlab-cli-skills/SKILL.md:3,116` claims to route to 39 nested skills but only lists names; current pi exposes only the parent metadata. The parent must name concrete relative `SKILL.md` paths and selection rules, or the children must be reorganized as references/top-level skills.
- Enabling full-depth discovery unchanged would create broad trigger conflicts. Examples include generic `bug`/`task` in `glab-issue`, `review`/`PR` in `glab-mr`, `help`, `version`, `config`, `user`, and overlapping CI/job terms. Retained descriptions should require explicit GitLab/`glab` context.
- Unsupported or misleading claims exist in:
  - `glab-iteration/SKILL.md:3,12-20` — description claims create/assignment while captured surface exposes only list.
  - `glab-user/SKILL.md:3,12-20` — description claims profile/settings/SSH management while captured surface exposes events.
  - `glab-snippet/SKILL.md:3,22-25` — description claims view/update while captured surface exposes create.
  - `glab-job/SKILL.md:3,17-27` — mixes unsupported `glab job view` with retry/trace/cancel operations owned by `glab ci`.
  - `glab-milestone/SKILL.md:3,15-20` — says close/view while the list uses edit/get.
- `glab-help`, `glab-version`, and `glab-check-update` contain little beyond one-command help and should be merged into parent diagnostics.

### Task/improvement skills

- `skills/self-improvement/SKILL.md:135-169,270-271` and `skills/org-tasks/SKILL.md:10,76` reference unavailable `pi-intercom`, `intercom`, `tasks_insert_task`, `org_read_section`, and `tasks_scan_summaries`. The guaranteed interface is `ot`; harness wrappers must be marked optional.
- `skills/self-improvement/SKILL.md:12-15,61-80` says every change becomes a TODO but later permits immediate documentation edits/commits. The exception must be explicit or removed.
- `skills/retro/SKILL.md:59-69,124-126` still routes to Claude-specific `/rules`, `/skill`, `.claude/rules`, `CLAUDE.md`, and `CLAUDE.local.md` instead of current AGENTS/skills/task-memory surfaces.
- `skills/skill-creator/scripts/quick_validate.py:9`, `scripts/package_skill.py:17`, and `skills/skill-creator/SKILL.md:198-204` rely on undeclared PyYAML, a possibly absent `python` executable, and a working-directory-sensitive module invocation.
- `skills/README.org:16-61` omits current top-level skills including dataspex, dirge, git-commit, and nix; its self-improvement routing description is stale.
- `skills/code-review/SKILL.md:6-15` is four generic bullets with no diff-selection workflow, severity model, test expectations, or false-positive bar.
- `skills/dirge/SKILL.md:60-92` mixes durable API guidance with volatile LOC/point-in-time assessment; move volatile assessment to design history when editing the skill.

Intentional overlap to preserve: org-tasks/org-plan/org-jira; retro/self-improvement after policy alignment; clojure/dataspex; find-skills/skill-creator; GitLab CI/job scopes; git-commit/code-review as distinct workflows.

## Stale and redundant active surfaces

1. `pi/extensions/lib/editor.ts` has no active consumers.
2. Most of `pi/extensions/lib/pi-utils.ts:34-150` supports archived leader-menu behavior; only `getExtensionName()` has a verified import. The active tasks overlay still reads `~/.pi/agent/leader-menu.json`, so inspect that partial coupling before deletion.
3. `pi/extensions/quotas.json` configures removed `@latentminds/pi-quotas`.
4. `mcp.json:11,13` contains unsupported `type` and `enabled` fields for the installed adapter.
5. Archived web-search and webdriver overlap current installed replacements. They remain explicitly out of deletion scope for this pass; identify them as inactive history instead.

No active tool or slash-command name collisions were found. Root pi package registration correctly exposes tasks, Jira, and emacsclient. Atlassian OAuth fields, Dirge provider nesting, Dirge read-only prompt metadata, Chromium/LSP cleanup, LSP rename mutation handling, and git clean-filter scoping were verified without issue.

## Recommended execution order

1. Establish a root validation baseline and fix portable test runners.
2. Repair custom-agent definitions.
3. Correct extension failure/schema semantics.
4. Bound `/ext` context loading.
5. Repair task-memory/improvement skills.
6. Consolidate GitLab guidance and strengthen code-review.
7. Remove stale active surfaces and pin dependencies.
8. Refresh README/AGENTS from the resulting implementation.
9. Re-run the full inventory and close out the change-record.

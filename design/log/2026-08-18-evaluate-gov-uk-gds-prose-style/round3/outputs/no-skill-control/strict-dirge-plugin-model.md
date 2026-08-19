<!-- Sample source: skills/dirge/SKILL.md (lines 25-95) -- verbatim excerpt. Prose class: strict operational text (agent-consumed skill instructions). Vendored (skills/README.org S Vendored); last repository edit 2026-06-27 (commit fee2327), before the ASD-STE100 mandate landed 2026-08-14 (commit 5bad753). Extracted: 2026-08-19. -->

## Plugin model (essentials)

- Dirge auto-loads plugins from `~/.config/dirge/plugins/` (global) and `./.dirge/plugins/`
  (project-local). The project-local copy wins on a name collision. No manifest
  exists. No entry point exists.
- A plugin is one `*.janet` file (stem = namespace) **or** a directory of
  `*.janet` files. A directory loads into one shared env in lexicographic order.
  Use `00-`, `01-` prefixes to set load order.
- Dirge finds hooks by top-level function name: `on-init`, `on-prompt`,
  `on-tool-start`, `on-tool-end`, `prepare-next-run`, `before-agent-start`,
  `transform-context`, `message-end`, `on-compact`, etc. Bare names work.
  Namespaced names work. Hooks run on one Janet worker thread. A long handler
  stalls the agent.
- All `harness/*` symbols are preloaded. No imports are needed. Plugin UI is
  **chat-line based**: `register-tool`, `register-command`,
  `register-shortcut` / `bind-key`, `register-renderer` + `append-entry`,
  `register-message-renderer` + `add-custom-message`, plus blocking dialogs
  `confirm` / `select`. No persistent-widget, overlay, or status-line primitive
  exists for plugins.
- Building with `--features plugin` is required. The default `cargo install`
  includes it. Verify with `dirge --version` listing `plugin`.
- Reference plugins live in `~/dev/agents/dirge/plugins/` (e.g. `bookmark.janet`,
  `protected_paths.janet`, `select_persona.janet`, `example_tool.janet`).

## Porting a pi/TypeScript extension to dirge

The dirge plugin surface is a subset of the pi surface. Two structural
differences exist:

1. **Language:** Janet, not TypeScript. Rewrite the logic. Do not transpile it.
2. **No rich TUI:** pi extensions can mount full-screen overlays and persistent
   widgets above the editor. dirge plugins cannot. dirge plugins use only
   chat lines, dialogs, slash commands, and key shortcuts.

The viable porting strategy: keep the agent-facing surface (LLM tools + slash
commands + chat renderers). Drop or downgrade the interactive TUI.

### Assessment: `tasks` extension (`pi/extensions/tasks`)

Reviewed against dirge `plugins.md`. **Verdict: a useful subset ports cleanly.
A faithful 1:1 port does not.**

Why it is portable: the extension is **~9.6k LOC of TS, but it already delegates
all protocol operations to an external `ot` CLI** (`schema: "org-tasks/v1"`
JSON-over-stdout, Babashka/Clojure). The TS layer is mostly a thin client plus
a TUI. `ot` is language-agnostic. A Janet plugin can run the same binary and
parse the same JSON envelope. No parser/serializer reimplementation is needed.

- **Ports cleanly (chat-surface):**
  - `/tasks`-style slash commands → `harness/register-command`, shelling to `ot`
    and rendering the JSON result as chat lines.
  - LLM-visible task tools (list/create/set-status/select/archive) →
    `harness/register-tool` with `:sequential` execution-mode for mutations,
    each handler spawning `ot` with a per-arg argv.
  - Pretty status/priority/tag coloring → `register-renderer` + `harness/render`
    color names, or a `register-message-renderer`.
  - `ot` discovery: PATH first, then skill-relative fallbacks
    (`~/.agents/skills/org-tasks/scripts/ot`, etc.) — same resolution order.
- **Does NOT port (pi-specific TUI):**
  - The expandable full-screen tasks overlay (`overlay.ts`, ~1.1k LOC) — no
    dirge overlay primitive exists. The best replacement is a `harness/select`
    picker plus chat-rendered lists. You may punt entirely to the `ot` CLI /
    Emacs.
  - The persistent compact selected-task widget above the editor — no
    persistent-widget primitive exists at all.
  - The live file-watcher driving widget refresh — no UI to refresh exists. A
    plugin can re-render on demand via a command instead.
- **Effort / shape:** a worthwhile MVP is small. Register the `ot`-backed tools
  and a `/tasks` command in one `.janet` file. The large, hard-to-port pieces
  (overlay, widget, watcher) are exactly the interactive UI for which dirge has
  no primitive. Cut them. Do not rebuild them.

When porting, confirm each `harness/*` name against `plugins.md` before use.
Prefer `:sequential` execution-mode for any tool which mutates `TASKS.org`.

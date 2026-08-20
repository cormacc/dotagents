<!-- Sample source: skills/dirge/SKILL.md (lines 25-95) -- verbatim excerpt. Prose class: strict operational text (agent-consumed skill instructions). Vendored (skills/README.org S Vendored); last repository edit 2026-06-27 (commit fee2327), before the ASD-STE100 mandate landed 2026-08-14 (commit 5bad753). Extracted: 2026-08-19. -->

## Plugin model (essentials)

Plugins load automatically from `~/.config/dirge/plugins/` (global) and `./.dirge/plugins/` (project-local). If two plugins have the same name, the project-local plugin takes precedence. You do not need a manifest or an entry point.

A plugin is either:

- one `*.janet` file, where the file name (without extension) is the namespace, or
- a directory of `*.janet` files that load into one shared environment in lexicographic order. Use `00-` and `01-` prefixes to control the load order.

Dirge finds hooks by their top-level function name: `on-init`, `on-prompt`, `on-tool-start`, `on-tool-end`, `prepare-next-run`, `before-agent-start`, `transform-context`, `message-end`, `on-compact`, and so on. A hook name works bare or with a namespace. Hooks run on a single Janet worker thread. A long-running handler will stall the agent.

Dirge preloads all `harness/*` symbols. You do not need to import them. The plugin user interface is **based on chat lines**: `register-tool`, `register-command`, `register-shortcut` / `bind-key`, `register-renderer` + `append-entry`, `register-message-renderer` + `add-custom-message`, and the blocking dialogs `confirm` / `select`. There is **no primitive for persistent widgets, overlays, or status lines** for plugins.

You must build Dirge with `--features plugin`. The default `cargo install` already includes this feature. Check with `dirge --version` and look for `plugin` in the list.

Reference plugins are in `~/dev/agents/dirge/plugins/`. Examples include `bookmark.janet`, `protected_paths.janet`, `select_persona.janet`, and `example_tool.janet`.

## Porting a pi/TypeScript extension to Dirge

Dirge's plugin surface is a subset of pi's. There are two structural differences:

1. **Language.** Use Janet, not TypeScript. Rewrite the logic; you cannot transpile it.
2. **No rich TUI.** pi extensions can mount full-screen overlays and persistent widgets above the editor. Dirge plugins cannot. Dirge only supports chat lines, dialogs, slash commands, and key shortcuts.

Use this porting strategy: keep the agent-facing surface (LLM tools, slash commands, and chat renderers). Drop or downgrade the interactive TUI.

### Assessment: the `tasks` extension (`pi/extensions/tasks`)

We reviewed this extension against Dirge's `plugins.md`. **A useful subset ports cleanly. A faithful 1:1 port does not.**

Why it is portable: the extension is **about 9,600 lines of TypeScript, but it already delegates all protocol operations to an external `ot` CLI** (`schema: "org-tasks/v1"` JSON over stdout, Babashka/Clojure). The TypeScript layer is mostly a thin client and a TUI. Because `ot` is language-agnostic, a Janet plugin can call the same binary and parse the same JSON envelope. You do not need to reimplement the parser or serializer.

- **Ports cleanly (chat surface):**
  - `/tasks`-style slash commands. Use `harness/register-command`, call `ot`, and render the JSON result as chat lines.
  - Task tools visible to the LLM (list, create, set-status, select, archive). Use `harness/register-tool` with `:sequential` execution mode for mutations. Each handler should spawn `ot` with a per-argument argv.
  - Coloured status, priority, and tags. Use `register-renderer` + `harness/render` colour names, or a `register-message-renderer`.
  - Finding `ot`. Look on the PATH first, then fall back to skill-relative paths (`~/.agents/skills/org-tasks/scripts/ot`, and so on). Keep the same resolution order.
- **Does not port (pi-specific TUI):**
  - The expandable full-screen tasks overlay (`overlay.ts`, about 1,100 lines). Dirge has no overlay primitive. The best replacement is a `harness/select` picker with chat-rendered lists, or leave the work entirely to the `ot` CLI or Emacs.
  - The persistent compact selected-task widget above the editor. Dirge has no persistent-widget primitive at all.
  - The live file watcher that drives widget refresh. There is no UI to refresh. A plugin can re-render on demand with a command instead.
- **Effort and shape.** A worthwhile MVP is small. Register the `ot`-backed tools and a `/tasks` command in one `.janet` file. The large, hard-to-port pieces (the overlay, the widget, and the watcher) are exactly the interactive UI that Dirge has no primitive for. Cut them rather than rebuild them.

When you port, check each `harness/*` name against `plugins.md` before you use it. Use `:sequential` execution mode for any tool that changes `TASKS.org`.

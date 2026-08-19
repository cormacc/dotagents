<!-- Sample source: skills/dirge/SKILL.md (lines 25-95) -- verbatim excerpt. Prose class: strict operational text (agent-consumed skill instructions). Vendored (skills/README.org S Vendored); last repository edit 2026-06-27 (commit fee2327), before the ASD-STE100 mandate landed 2026-08-14 (commit 5bad753). Extracted: 2026-08-19. -->

## Plugin model (essentials)

- The system loads plugins automatically from `~/.config/dirge/plugins/` (global) and `./.dirge/plugins/`
  (project-local). If a name conflicts, the project-local plugin wins. Plugins do not need a manifest
  or an entry point.
- A plugin is one `*.janet` file (the file name is the namespace). Or a plugin is a directory of
  `*.janet` files. The system loads these files into one shared environment, in alphabetical order.
  Use prefixes like `00-` and `01-` to set the load order.
- The system finds hooks by the name of a top-level function: `on-init`, `on-prompt`,
  `on-tool-start`, `on-tool-end`, `prepare-next-run`, `before-agent-start`, `transform-context`,
  `message-end`, `on-compact`, and others. You can use bare names or namespaced names. Hooks run on
  one Janet worker thread. A long-running handler stops the agent.
- The system preloads all `harness/*` symbols. Plugins do not need to import them. Plugin UI uses
  chat lines only. You can use `register-tool`, `register-command`, `register-shortcut` (or
  `bind-key`), `register-renderer` with `append-entry`, and `register-message-renderer` with
  `add-custom-message`. You can also use the blocking dialogs `confirm` and `select`. Plugins have
  **no primitive** for a persistent widget, an overlay, or a status line.
- Plugins require a build with `--features plugin`. The default `cargo install` includes this
  feature. Run `dirge --version` to check that it lists `plugin`.
- Reference plugins are in `~/dev/agents/dirge/plugins/`. Examples: `bookmark.janet`,
  `protected_paths.janet`, `select_persona.janet`, and `example_tool.janet`.

## Porting a pi/TypeScript extension to dirge

The dirge plugin surface is a subset of the pi plugin surface. There are two structural
differences:

1. **Language:** dirge plugins use Janet, not TypeScript. You must rewrite the logic. You cannot
   transpile it.
2. **No rich TUI:** pi extensions can show full-screen overlays and persistent widgets above the
   editor. dirge plugins cannot do this. dirge plugins can only use chat lines, dialogs, slash
   commands, and key shortcuts.

Use this porting strategy: keep the agent-facing surface (LLM tools, slash commands, and chat
renderers). Drop or downgrade the interactive TUI.

### Assessment: `tasks` extension (`pi/extensions/tasks`)

We reviewed this extension against dirge `plugins.md`. **Verdict: a useful subset ports cleanly.
A full 1:1 port does not.**

Why it is portable: the extension has about 9.6k lines of TypeScript code. But it already sends
all protocol operations to an external `ot` CLI tool (schema: `org-tasks/v1`, JSON over stdout,
Babashka/Clojure). The TypeScript layer is mostly a thin client plus a TUI. Because `ot` does not
depend on a specific language, a Janet plugin can call the same binary and parse the same JSON
output. You do not need to reimplement the parser or the serializer.

- **Parts that port cleanly (chat surface):**
  - `/tasks`-style slash commands: use `harness/register-command`. Call `ot` and show the JSON
    result as chat lines.
  - LLM-visible task tools (list, create, set-status, select, archive): use `harness/register-tool`
    with `:sequential` execution mode for changes. Each handler starts `ot` with its own argument
    list.
  - Status, priority, and tag coloring: use `register-renderer` with `harness/render` color names,
    or use `register-message-renderer`.
  - `ot` discovery: check PATH first. Then check skill-relative fallback paths (for example
    `~/.agents/skills/org-tasks/scripts/ot`). Use the same order as the original extension.
- **Parts that do not port (pi-specific TUI):**
  - The expandable full-screen tasks overlay (`overlay.ts`, about 1.1k lines). dirge has no overlay
    primitive. Use a `harness/select` picker with chat-rendered lists instead. Or use the `ot` CLI
    or Emacs instead of a UI.
  - The persistent compact widget that shows the selected task above the editor. dirge has no
    persistent-widget primitive at all.
  - The live file watcher that refreshes the widget. dirge has no UI to refresh. A plugin can
    re-render on demand, through a command, instead.
- **Effort and shape:** A worthwhile MVP is small. Register the `ot`-backed tools and a `/tasks`
  command in one `.janet` file. The large, hard-to-port parts (the overlay, the widget, and the
  watcher) are exactly the interactive UI parts that dirge has no primitive for. So you cut these
  parts. You do not rebuild them.

When you port a feature, check each `harness/*` name against `plugins.md` before you use it. Use
`:sequential` execution mode for any tool that changes `TASKS.org`.

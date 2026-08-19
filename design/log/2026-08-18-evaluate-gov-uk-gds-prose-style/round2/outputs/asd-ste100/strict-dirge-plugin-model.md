<!-- Sample source: skills/dirge/SKILL.md (lines 25-95) -- verbatim excerpt. Prose class: strict operational text (agent-consumed skill instructions). Vendored (skills/README.org S Vendored); last repository edit 2026-06-27 (commit fee2327), before the ASD-STE100 mandate landed 2026-08-14 (commit 5bad753). Extracted: 2026-08-19. -->

## Plugin model (essentials)

- dirge loads plugins automatically from two locations: `~/.config/dirge/plugins/` (global) and `./.dirge/plugins/` (project-local). If a name collision occurs, the project-local plugin wins. A plugin needs no manifest and no entry point.
- A plugin is one file with the extension `.janet`. The file name, without the extension, becomes the namespace. A plugin can also be a directory of `.janet` files. Files in a directory load into one shared environment, in lexicographic order. Use prefixes such as `00-` and `01-` to set the load order.
- The system finds hooks by matching top-level function names, such as `on-init`, `on-prompt`, `on-tool-start`, `on-tool-end`, `prepare-next-run`, `before-agent-start`, `transform-context`, `message-end`, and `on-compact`. Bare names and namespaced names both work. Hooks run on one Janet worker thread. A long-running handler stalls the agent.
- The system preloads all `harness/*` symbols. A plugin does not need to import them. Plugin UI works only through chat lines. Available functions:
  - `register-tool`
  - `register-command`
  - `register-shortcut` (or `bind-key`)
  - `register-renderer`, with `append-entry`
  - `register-message-renderer`, with `add-custom-message`

  Plugins can also use two blocking dialogs: `confirm` and `select`. dirge has no primitive for a persistent widget, an overlay, or a status line.
- To build plugin support, use `--features plugin`. The default `cargo install` command includes this feature. To verify, run `dirge --version`. Check that the output lists `plugin`.
- Reference plugins are in `~/dev/agents/dirge/plugins/`. Examples: `bookmark.janet`, `protected_paths.janet`, `select_persona.janet`, and `example_tool.janet`.

## Porting a pi/TypeScript extension to dirge

The dirge plugin surface is a subset of the pi plugin surface. Two structural differences apply:

1. **Language:** dirge plugins use Janet, not TypeScript. You must rewrite the logic. You cannot transpile it.
2. **TUI:** pi extensions can mount full-screen overlays and persistent widgets above the editor. dirge plugins cannot do this. dirge plugins can use only chat lines, dialogs, slash commands, and key shortcuts.

The viable porting strategy is this: keep the agent-facing surface (LLM tools, slash commands, and chat renderers). Drop or downgrade the interactive TUI.

### Assessment: `tasks` extension (`pi/extensions/tasks`)

This review compares the extension against dirge `plugins.md`. **Verdict:** a useful subset ports cleanly. A faithful one-to-one port does not.

This is what makes the extension portable. It has about 9,600 lines of TypeScript code. It already sends all protocol operations to an external CLI tool named `ot`. `ot` uses the schema `org-tasks/v1` and outputs JSON to stdout. `ot` is written in Babashka/Clojure. The TypeScript layer is mostly a thin client plus a TUI. `ot` is language-agnostic. Therefore, a Janet plugin can call the same `ot` binary and parse the same JSON output. The plugin does not need to reimplement the parser or serializer.

- **Ports cleanly to the chat surface:**
  - Slash commands like `/tasks`: implement with `harness/register-command`. The command shells out to `ot` and renders the JSON result as chat lines.
  - LLM-visible task tools (list, create, set-status, select, archive): implement with `harness/register-tool`. Use `:sequential` execution mode for mutations. Each handler spawns `ot` with a per-argument argv.
  - Status, priority, and tag coloring: implement with `register-renderer` and `harness/render` color names, or with `register-message-renderer`.
  - `ot` discovery: search PATH first, then search skill-relative fallback paths, for example `~/.agents/skills/org-tasks/scripts/ot`. This uses the same resolution order as the original extension.
- **Does not port (pi-specific TUI features):**
  - The tasks overlay (`overlay.ts`, about 1,100 lines of code) is full-screen and expandable. dirge has no overlay primitive. The best replacement is a `harness/select` picker plus chat-rendered lists. Another option is to use the `ot` CLI or Emacs instead of porting the overlay.
  - The widget that shows the selected task above the editor is persistent and compact. dirge has no persistent-widget primitive at all.
  - The live file-watcher that drives widget refresh does not port. dirge has no widget to refresh here. Instead, a plugin can re-render on demand through a command.
- **Effort:** a worthwhile minimum viable product (MVP) is small. Register the `ot`-backed tools and a `/tasks` command in one `.janet` file. The large, hard-to-port pieces (the overlay, the widget, and the watcher) are exactly the interactive UI parts that dirge has no primitive for. Cut these pieces. Do not rebuild them.

Before you use a `harness/*` name, confirm it against `plugins.md`. Use `:sequential` execution mode for any tool that changes `TASKS.org`.

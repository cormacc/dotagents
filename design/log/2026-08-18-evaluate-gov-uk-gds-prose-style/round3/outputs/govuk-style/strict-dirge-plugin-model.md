## Plugin model (essentials)

- Plugins load automatically from `~/.config/dirge/plugins/` (global) and `./.dirge/plugins/` (project-local). When two plugins share a name, the project-local plugin wins. dirge needs no manifest or entry point.
- A plugin is one `*.janet` file, where the file stem is the namespace. A plugin can also be a directory of `*.janet` files that load into one shared env in lexicographic order. Use `00-` and `01-` prefixes to control the load order.
- dirge finds hooks by their top-level function name: `on-init`, `on-prompt`, `on-tool-start`, `on-tool-end`, `prepare-next-run`, `before-agent-start`, `transform-context`, `message-end`, `on-compact` and others. Both bare and namespaced names work. Hooks run on a single Janet worker thread, so a long handler stalls the agent.
- dirge preloads all `harness/*` symbols, so plugins do not need imports. The plugin UI is chat-line based. The available functions are `register-tool`, `register-command`, `register-shortcut` and `bind-key`, `register-renderer` with `append-entry`, and `register-message-renderer` with `add-custom-message`. Blocking dialogs are `confirm` and `select`. Plugins have no persistent-widget, overlay or status-line primitive.
- You must build dirge with `--features plugin`. The default `cargo install` already includes it. Check with `dirge --version`, which lists `plugin` in the output.
- Reference plugins live in `~/dev/agents/dirge/plugins/`, for example `bookmark.janet`, `protected_paths.janet`, `select_persona.janet` and `example_tool.janet`.

## Porting a pi/TypeScript extension to dirge

The dirge plugin surface is a subset of the pi surface, with two structural differences:

1. The language is Janet, not TypeScript. You rewrite the logic by hand; you cannot transpile it.
2. There is no rich TUI. pi extensions can mount full-screen overlays and persistent widgets above the editor. dirge plugins cannot. They use only chat lines, dialogs, slash commands and key shortcuts.

To port an extension, keep the agent-facing surface (LLM tools, slash commands and chat renderers) and drop or downgrade the interactive TUI.

### Assessment: `tasks` extension (`pi/extensions/tasks`)

Reviewed against dirge `plugins.md`. A useful subset of the extension ports cleanly. A complete port does not.

The extension is portable for one reason: it is about 9,600 lines of TypeScript, but it already delegates all protocol operations to an external `ot` CLI (`schema: "org-tasks/v1"`, JSON over stdout, Babashka/Clojure). The TypeScript layer is mostly a thin client plus a TUI. `ot` is language-agnostic, so a Janet plugin can shell out to the same binary and parse the same JSON envelope. You do not need to reimplement a parser or serializer.

What ports cleanly (the chat surface):

- `/tasks`-style slash commands, via `harness/register-command`, shelling out to `ot` and rendering the JSON result as chat lines
- LLM task tools (list, create, set-status, select and archive), via `harness/register-tool` with `:sequential` execution-mode for mutations, each handler spawning `ot` with a per-arg argv
- status, priority and tag coloring, via `register-renderer` and `harness/render` color names or a `register-message-renderer`
- `ot` discovery: PATH first, then skill-relative fallbacks such as `~/.agents/skills/org-tasks/scripts/ot`, in the same resolution order

What does not port (the pi-specific TUI):

- dirge has no overlay primitive, so the expandable full-screen tasks overlay (`overlay.ts`, about 1,100 lines) cannot port. The best replacement is a `harness/select` picker plus chat-rendered lists, or leave it entirely to the `ot` CLI and Emacs.
- dirge has no persistent-widget primitive, so the persistent compact selected-task widget above the editor cannot port at all.
- There is no UI to refresh, so the live file watcher that updated the widget has no purpose. A plugin can re-render on demand through a command instead.

The first version is small: register the `ot`-backed tools and a `/tasks` command in one `.janet` file. The large, hard-to-port pieces (overlay, widget and watcher) are the interactive UI that dirge has no primitive for, so you cut them rather than rebuild them.

When you port an extension, confirm each `harness/*` name against `plugins.md` before you use it. Use `:sequential` execution-mode for any tool that changes `TASKS.org`.

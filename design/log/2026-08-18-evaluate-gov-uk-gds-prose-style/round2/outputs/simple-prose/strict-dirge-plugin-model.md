<!-- Sample source: skills/dirge/SKILL.md (lines 25-95) -- verbatim excerpt. Prose class: strict operational text (agent-consumed skill instructions). Vendored (skills/README.org S Vendored); last repository edit 2026-06-27 (commit fee2327), before the ASD-STE100 mandate landed 2026-08-14 (commit 5bad753). Extracted: 2026-08-19. -->

## Plugin model (essentials)

Dirge auto-loads plugins from two locations:

- `~/.config/dirge/plugins/` (global)
- `./.dirge/plugins/` (project-local; this wins if a name collides)

A plugin needs no manifest and no entry point.

A plugin is one `*.janet` file, where the file's stem becomes its namespace. A plugin can also be a directory of `*.janet` files that load into one shared environment in lexicographic order. Use `00-`, `01-` prefixes to control load order.

Dirge finds hooks by their top-level function name: `on-init`, `on-prompt`, `on-tool-start`, `on-tool-end`, `prepare-next-run`, `before-agent-start`, `transform-context`, `message-end`, `on-compact`, and so on. Bare and namespaced names both work. Hooks run on a single Janet worker thread, so a long-running handler stalls the agent.

All `harness/*` symbols are preloaded, so plugins need no imports. Plugin UI is chat-line based. It offers:

- `register-tool`
- `register-command`
- `register-shortcut` or `bind-key`
- `register-renderer` with `append-entry`
- `register-message-renderer` with `add-custom-message`
- blocking dialogs: `confirm` and `select`

Dirge has no persistent-widget, overlay or status-line primitive for plugins.

Building a plugin-enabled dirge needs the `--features plugin` flag. The default `cargo install` includes this feature. Run `dirge --version` and check that `plugin` appears in the list to confirm.

Reference plugins live in `~/dev/agents/dirge/plugins/`, for example `bookmark.janet`, `protected_paths.janet`, `select_persona.janet` and `example_tool.janet`.

## Porting a pi/TypeScript extension to dirge

Dirge's plugin surface is a subset of pi's, with two structural differences:

- dirge plugins use Janet, not TypeScript, so you rewrite logic instead of transpiling it
- dirge plugins have no rich TUI: they support only chat lines, dialogs, slash commands and key shortcuts

Pi extensions, by contrast, can mount full-screen overlays and persistent widgets above the editor.

To port an extension, keep the agent-facing surface: the LLM tools, the slash commands and the chat renderers. Drop or downgrade the interactive TUI.

### Assessing the `tasks` extension (`pi/extensions/tasks`)

Against dirge's `plugins.md`, a useful subset of the extension ports cleanly. A full one-to-one port does not.

The extension is portable because it already delegates all protocol operations to an external `ot` CLI (Babashka/Clojure, using a `schema: "org-tasks/v1"` JSON-over-stdout format), even though it has about 9,600 lines of TypeScript. The TypeScript layer is mostly a thin client plus a TUI. Because `ot` is language-agnostic, a Janet plugin can shell out to the same binary and parse the same JSON envelope, with no need to reimplement a parser or serializer.

These parts port cleanly to the chat surface:

- `/tasks`-style slash commands become `harness/register-command` calls that shell out to `ot` and render the JSON result as chat lines
- LLM-visible task tools (list, create, set-status, select, archive) become `harness/register-tool` calls, using `:sequential` execution mode for mutations, with each handler spawning `ot` with its own argv
- pretty status, priority and tag colouring uses `register-renderer` with `harness/render` colour names, or a `register-message-renderer`
- `ot` discovery keeps the same order: PATH first, then skill-relative fallbacks such as `~/.agents/skills/org-tasks/scripts/ot`

These pi-specific TUI parts do not port:

- the expandable full-screen tasks overlay (`overlay.ts`, about 1,100 lines) has no dirge equivalent, so use a `harness/select` picker with chat-rendered lists, or defer entirely to the `ot` CLI or Emacs
- the persistent compact selected-task widget above the editor has no matching persistent-widget primitive in dirge
- the live file-watcher that refreshes the widget has no dirge UI to refresh, so a plugin should re-render on demand through a command instead

A small first version is worthwhile: register the `ot`-backed tools and a `/tasks` command in one `.janet` file. The large, hard-to-port pieces (the overlay, the widget and the watcher) are exactly the interactive UI that dirge has no primitive for, so cut them rather than rebuild them.

When you port an extension, check each `harness/*` name against `plugins.md` before you use it. Use `:sequential` execution mode for any tool that changes `TASKS.org`.

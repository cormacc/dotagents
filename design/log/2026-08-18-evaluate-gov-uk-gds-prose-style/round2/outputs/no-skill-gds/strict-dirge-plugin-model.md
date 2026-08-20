<!-- Sample source: skills/dirge/SKILL.md, lines 25 to 95. This is a verbatim excerpt. Prose class: strict operational text (instructions for an agent to read). Vendored, see skills/README.org, section 'Vendored'. Last edited in the repository on 27 June 2026 (commit fee2327), before the ASD-STE100 mandate on 14 August 2026 (commit 5bad753). Extracted on 19 August 2026. -->

## Plugin model (essentials)

- dirge automatically loads plugins from `~/.config/dirge/plugins/` (global) and
  `./.dirge/plugins/` (project-specific). If a plugin exists in both locations,
  the project-specific version takes priority. Plugins do not need a manifest
  or an entry point.
- A plugin is either a single `*.janet` file, where the filename becomes the
  namespace, or a directory of `*.janet` files that load into one shared
  environment. Files load in lexicographic order, so use prefixes such as
  `00-` and `01-` to control the load order.
- dirge finds hooks by their top-level function name, including `on-init`,
  `on-prompt`, `on-tool-start`, `on-tool-end`, `prepare-next-run`,
  `before-agent-start`, `transform-context`, `message-end` and `on-compact`.
  You can use bare or namespaced names. Hooks run on a single Janet worker
  thread, so a long-running handler will stall the agent.
- dirge preloads all `harness/*` symbols, so plugins do not need to import
  them. Plugin UI is based on chat lines. You can use `register-tool`,
  `register-command`, `register-shortcut` or `bind-key`, `register-renderer`
  with `append-entry`, and `register-message-renderer` with
  `add-custom-message`. You can also use the blocking dialogs `confirm` and
  `select`. dirge does not provide a persistent-widget, overlay or
  status-line primitive for plugins.
- You must build dirge with `--features plugin` to use plugins. The default
  `cargo install` includes this feature. To check, run `dirge --version` and
  confirm that `plugin` appears in the list.
- Reference plugins are in `~/dev/agents/dirge/plugins/`. Examples include
  `bookmark.janet`, `protected_paths.janet`, `select_persona.janet` and
  `example_tool.janet`.

## Porting a pi/TypeScript extension to dirge

dirge's plugin surface is a subset of pi's. There are 2 structural
differences:

1. **Language.** dirge plugins use Janet, not TypeScript, so you must
   rewrite the logic rather than transpile it.
2. **No rich TUI.** pi extensions can mount full-screen overlays and
   persistent widgets above the editor. dirge plugins cannot do this. They
   can only use chat lines, dialogs, slash commands and key shortcuts.

The best porting strategy is to keep the agent-facing surface (LLM tools,
slash commands and chat renderers) and drop or simplify the interactive TUI.

### Assessment: `tasks` extension (`pi/extensions/tasks`)

We reviewed this against dirge's `plugins.md`. A useful subset ports
cleanly, but a faithful one-to-one port does not.

This extension is portable because, although it has around 9,600 lines of
TypeScript code, it already delegates all protocol operations to an
external `ot` command-line interface (schema `org-tasks/v1`, JSON over
stdout, built with Babashka and Clojure). The TypeScript layer is mostly a
thin client plus a text user interface. Because `ot` does not depend on any
particular language, a Janet plugin can call the same binary and parse the
same JSON envelope, without reimplementing the parser or serialiser.

- **What ports cleanly (the chat surface):**
  - `/tasks`-style slash commands port to `harness/register-command`. These
    shell out to `ot` and render the JSON result as chat lines.
  - Task tools visible to the LLM (list, create, set status, select and
    archive) port to `harness/register-tool`, using the `:sequential`
    execution mode for mutations. Each handler spawns `ot` with its own
    argument list.
  - Status, priority and tag colouring ports to `register-renderer` with
    `harness/render` colour names, or to a `register-message-renderer`.
  - `ot` discovery works the same way: dirge checks the PATH first, then
    falls back to skill-relative paths such as
    `~/.agents/skills/org-tasks/scripts/ot`.
- **What does not port (the pi-specific TUI):**
  - The expandable full-screen tasks overlay (`overlay.ts`, around 1,100
    lines of code) does not port, because dirge has no overlay primitive.
    The best replacement is a `harness/select` picker with chat-rendered
    lists, or to rely entirely on the `ot` command-line interface or Emacs
    instead.
  - The persistent compact selected-task widget above the editor does not
    port, because dirge has no persistent-widget primitive at all.
  - The live file watcher that refreshes the widget does not port either,
    because there is no widget to refresh. Instead, a plugin can re-render
    on demand using a command.
- **Effort and scope:** a worthwhile minimum viable product is small. You
  only need to register the `ot`-backed tools and a `/tasks` command in one
  `.janet` file. The large, hard-to-port pieces (the overlay, the widget and
  the watcher) are exactly the interactive UI that dirge has no primitive
  for. You should cut these rather than try to rebuild them.

When you port an extension, check each `harness/*` name against
`plugins.md` before you use it. Use the `:sequential` execution mode for any
tool that changes `TASKS.org`.

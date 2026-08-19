<!-- Sample source: skills/dirge/SKILL.md (lines 25-95) -- verbatim excerpt. Prose class: strict operational text (agent-consumed skill instructions). Vendored (skills/README.org S Vendored); last repository edit 2026-06-27 (commit fee2327), before the ASD-STE100 mandate landed 2026-08-14 (commit 5bad753). Extracted: 2026-08-19. -->

## Plugin model (essentials)

- Plugins load automatically from `~/.config/dirge/plugins/` (global) and `./.dirge/plugins/` (project-local)
- On a name collision, the project-local plugin wins
- There is no manifest and no entry point
- A plugin is one of:
  - one `*.janet` file (stem = namespace)
  - a directory of `*.janet` files that load into one shared env in lexicographic order
- Use `00-` and `01-` prefixes to control load order in a directory plugin
- dirge discovers hooks by top-level function name:
  - `on-init`, `on-prompt`, `on-tool-start`, `on-tool-end`, `prepare-next-run`, `before-agent-start`, `transform-context`, `message-end`, `on-compact`, and so on
  - bare or namespaced names both work
  - Hooks run on a single Janet worker thread
  - Long handlers stall the agent
- All `harness/*` symbols are preloaded, so a plugin does not import them
- Plugin UI is chat-line based:
  - `register-tool`
  - `register-command`
  - `register-shortcut` / `bind-key`
  - `register-renderer` + `append-entry`
  - `register-message-renderer` + `add-custom-message`
  - blocking dialogs `confirm` / `select`
- Plugins have no persistent-widget, overlay or status-line primitive
- You need to build dirge with `--features plugin` to use plugins
- The default `cargo install` includes the feature
- Check the feature by running `dirge --version` and confirming the output lists `plugin`
- Reference plugins live in `~/dev/agents/dirge/plugins/` (for example, `bookmark.janet`, `protected_paths.janet`, `select_persona.janet`, `example_tool.janet`)

## Porting a pi/TypeScript extension to dirge

dirge's plugin surface is a subset of pi's. Two structural differences matter when you port:

- dirge plugins use Janet, not TypeScript
- you rewrite the logic rather than transpile it
- pi extensions can mount full-screen overlays and persistent widgets above the editor
- dirge plugins cannot
- dirge plugins can use only chat lines, dialogs, slash commands, and key shortcuts

The viable porting strategy: keep the agent-facing surface (LLM tools + slash commands + chat renderers) and drop or downgrade the interactive TUI.

### Assessment: the `tasks` extension (`pi/extensions/tasks`)

Reviewed against dirge `plugins.md`, the verdict is: a useful subset ports cleanly. A faithful 1:1 port does not.

The extension is ~9.6k LOC of TypeScript, but it already delegates all protocol operations to an external `ot` CLI (`schema: "org-tasks/v1"` JSON-over-stdout, Babashka/Clojure). The TypeScript layer is mostly a thin client plus a TUI. Because `ot` is language-agnostic, a Janet plugin can shell out to the same binary and parse the same JSON envelope. There is no parser or serializer rewrite.

Parts that port cleanly (chat surface):

- `/tasks`-style slash commands become `harness/register-command` handlers that shell out to `ot`
- the handlers render the JSON result as chat lines
- LLM-visible task tools (list/create/set-status/select/archive) become `harness/register-tool` with `:sequential` execution-mode for mutations
- each handler spawns `ot` with a per-arg argv
- pretty status/priority/tag colouring uses `register-renderer` + `harness/render` colour names, or a `register-message-renderer`
- `ot` discovery checks PATH first, then skill-relative fallbacks (for example, `~/.agents/skills/org-tasks/scripts/ot`), in the same resolution order

Parts that do not port (pi-specific TUI):

- the expandable full-screen tasks overlay (`overlay.ts`, ~1.1k LOC), because dirge has no overlay primitive
- a `harness/select` picker plus chat-rendered lists is the best replacement, or punt entirely to the `ot` CLI / Emacs
- the persistent compact selected-task widget above the editor, because dirge has no persistent-widget primitive at all
- the live file-watcher that drives widget refresh, because there is no UI to refresh
- a plugin can re-render on demand via a command instead

Effort and shape:

- A worthwhile MVP is small: register the `ot`-backed tools and a `/tasks` command in one `.janet` file
- The large, hard-to-port pieces (overlay, widget, watcher) are exactly the interactive UI that dirge has no primitive for
- they are cut rather than rebuilt

When porting, confirm each `harness/*` name against `plugins.md` before use. Prefer `:sequential` execution-mode for any tool that mutates `TASKS.org`.

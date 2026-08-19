<!-- Sample source: skills/dirge/SKILL.md (lines 25-95) -- verbatim excerpt. Prose class: strict operational text (agent-consumed skill instructions). Vendored (skills/README.org S Vendored); last repository edit 2026-06-27 (commit fee2327), before the ASD-STE100 mandate landed 2026-08-14 (commit 5bad753). Extracted: 2026-08-19. -->

## Plugin model (essentials)

- dirge loads plugins automatically from `~/.config/dirge/plugins/` (global)
  and `./.dirge/plugins/` (project-local; this wins on a name collision).
  There's no manifest and no entry point.
- A plugin is one `*.janet` file (the file name is the namespace), or a
  directory of `*.janet` files that load into one shared environment in
  lexicographic order. Use `00-`, `01-` prefixes to control load order.
- dirge finds hooks by their top-level function name: `on-init`, `on-prompt`,
  `on-tool-start`, `on-tool-end`, `prepare-next-run`, `before-agent-start`,
  `transform-context`, `message-end`, `on-compact`, and so on. Bare and
  namespaced names both work. Hooks run on a single Janet worker thread, so a
  long handler stalls the agent.
- dirge preloads all `harness/*` symbols, so plugins do not need to import
  them. Plugin UI is chat-line based: `register-tool`, `register-command`,
  `register-shortcut` or `bind-key`, `register-renderer` with
  `append-entry`, `register-message-renderer` with `add-custom-message`, plus
  the blocking dialogs `confirm` and `select`. There is no persistent-widget,
  overlay or status-line primitive for plugins.
- Plugins need a build with `--features plugin`. The default `cargo install`
  includes this; check with `dirge --version`, which lists `plugin` if it's
  there.
- Reference plugins live in `~/dev/agents/dirge/plugins/`, for example
  `bookmark.janet`, `protected_paths.janet`, `select_persona.janet` and
  `example_tool.janet`.

## Porting a pi/TypeScript extension to dirge

dirge's plugin surface is a subset of pi's, in two ways:

- language: Janet, not TypeScript, so logic is rewritten rather than
  transpiled.
- no rich TUI: pi extensions can mount full-screen overlays and persistent
  widgets above the editor, but dirge plugins only get chat lines, dialogs,
  slash commands and key shortcuts.

The viable porting strategy: keep the agent-facing surface (LLM tools, slash
commands, chat renderers) and drop or downgrade the interactive TUI.

### Assessment: `tasks` extension (`pi/extensions/tasks`)

Reviewed against dirge `plugins.md`. Verdict: a useful subset ports cleanly,
but a faithful one-to-one port does not.

What makes it portable: the extension is about 9,600 lines of TypeScript,
but it already delegates all protocol operations to an external `ot` CLI
(schema `org-tasks/v1`, JSON over stdout, via Babashka/Clojure). The
TypeScript layer is mostly a thin client plus a TUI. Because `ot` is
language-agnostic, a Janet plugin can shell out to the same binary and parse
the same JSON envelope, with no need to reimplement the parser or
serializer.

Ports cleanly to the chat surface:

- `/tasks`-style slash commands become `harness/register-command` calls
  that shell out to `ot` and render the JSON result as chat lines.
- LLM-visible task tools (list, create, set status, select, archive) become
  `harness/register-tool` calls, using `:sequential` execution mode for
  mutations, with each handler spawning `ot` with its own argv.
- pretty status and priority/tag colouring uses `register-renderer` with
  `harness/render` colour names, or a `register-message-renderer`.
- `ot` discovery follows the same order as before: PATH first, then
  skill-relative fallbacks such as `~/.agents/skills/org-tasks/scripts/ot`.

Does not port (pi-specific TUI):

- the expandable full-screen tasks overlay (`overlay.ts`, about 1,100
  lines) has no dirge equivalent. The best replacement is a
  `harness/select` picker plus chat-rendered lists, or handing this off
  entirely to the `ot` CLI or Emacs.
- the persistent compact selected-task widget above the editor has no
  equivalent either, since dirge has no persistent-widget primitive.
- the live file-watcher that drives widget refresh is not needed, since a
  plugin can re-render on demand through a command instead.

A worthwhile MVP is small: register the `ot`-backed tools and a `/tasks`
command in one `.janet` file. The large, hard-to-port pieces (the overlay,
widget and watcher) are exactly the interactive UI that dirge has no
primitive for, so cut them rather than rebuild them.

When porting, confirm each `harness/*` name against `plugins.md` before use,
and prefer `:sequential` execution mode for any tool that mutates
`TASKS.org`.

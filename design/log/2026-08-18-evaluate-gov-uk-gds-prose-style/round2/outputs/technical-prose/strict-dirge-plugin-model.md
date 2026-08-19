<!-- Sample source: skills/dirge/SKILL.md (lines 25-95) -- verbatim excerpt. Prose class: strict operational text (agent-consumed skill instructions). Vendored (skills/README.org S Vendored); last repository edit 2026-06-27 (commit fee2327), before the ASD-STE100 mandate landed 2026-08-14 (commit 5bad753). Extracted: 2026-08-19. -->

## Plugin model (essentials)

- dirge loads plugins automatically from `~/.config/dirge/plugins/` (global) and
  `./.dirge/plugins/` (project-local). The project-local location wins on a name
  collision. Plugins need no manifest and no entry point.
- A plugin is one `*.janet` file, or a directory of `*.janet` files. For a single
  file, the file's stem name becomes the namespace. For a directory, dirge loads
  all the files into one shared environment, in lexicographic order. Use `00-`,
  `01-` prefixes to control the load order.
- dirge finds hooks by their top-level function name. Hook names include (but
  are not limited to) `on-init`, `on-prompt`, `on-tool-start`, `on-tool-end`,
  `prepare-next-run`, `before-agent-start`, `transform-context`, `message-end`,
  and `on-compact`. Bare and namespaced names both work. Hooks run on a single
  Janet worker thread. A long handler stalls the agent.
- dirge preloads all `harness/*` symbols, so plugins need no imports. Plugin UI
  is chat-line based, with these primitives:
  - `register-tool`
  - `register-command`
  - `register-shortcut` and `bind-key`
  - `register-renderer`, with `append-entry`
  - `register-message-renderer`, with `add-custom-message`
  - blocking dialogs: `confirm` and `select`

  Plugins have no persistent-widget, overlay, or status-line primitive.
- Build dirge with `--features plugin` to enable plugins. The default
  `cargo install` already includes this feature. Run `dirge --version` to
  check. The output must list `plugin`.
- Reference plugins live in `~/dev/agents/dirge/plugins/`. Examples:
  `bookmark.janet`, `protected_paths.janet`, `select_persona.janet`,
  `example_tool.janet`.

## Porting a pi/TypeScript extension to dirge

dirge's plugin surface is a subset of pi's, with two structural differences:

1. **Language:** Janet, not TypeScript. You rewrite the logic. You do not
   transpile it.
2. **No rich TUI:** pi extensions can mount full-screen overlays and
   persistent widgets above the editor. dirge plugins cannot mount these.
   dirge plugins support only chat lines, dialogs, slash commands, and key
   shortcuts.

Use this porting strategy: keep the agent-facing surface (LLM tools, slash
commands, and chat renderers). Drop or downgrade the interactive TUI.

### Assessment: `tasks` extension (`pi/extensions/tasks`)

This assessment compares the extension against dirge `plugins.md`. Verdict: a
useful subset ports cleanly. A full one-to-one port does not port cleanly.

The extension is portable for these reasons. It has about 9,600 lines of
TypeScript code. It already sends all protocol operations to an external `ot`
CLI tool. This CLI uses the `org-tasks/v1` schema and exchanges JSON over
stdout. `ot` is written in Babashka/Clojure. The TypeScript layer is mostly a
thin client, plus a TUI. `ot` is language-agnostic. Because of this, a Janet
plugin can call the same binary and parse the same JSON envelope. The plugin
does not need to reimplement the parser or the serializer.

- Ports cleanly (chat-surface):
  - `/tasks`-style slash commands map to `harness/register-command`. This
    command calls `ot` and displays the JSON result as chat lines.
  - LLM-visible task tools (list, create, set-status, select, archive) map to
    `harness/register-tool`. Mutation tools use `:sequential` execution mode.
    Each handler starts `ot` with its own argument list.
  - Status, priority, and tag colors map to `register-renderer`, using
    `harness/render` color names. Or they map to `register-message-renderer`.
  - dirge finds `ot` the same way pi does. It checks PATH first. If `ot` is
    not on PATH, it checks skill-relative fallback locations, such as
    `~/.agents/skills/org-tasks/scripts/ot`.
- Does not port (pi-specific TUI):
  - The tasks overlay is expandable and full-screen (`overlay.ts`, about
    1,100 lines). It does not port. dirge has no overlay primitive. The best
    replacement is a `harness/select` picker, plus chat-rendered lists. Or
    skip this feature, and use the `ot` CLI or Emacs directly.
  - The persistent, compact widget that shows the selected task, above the
    editor, does not port. dirge has no persistent-widget primitive.
  - The live file-watcher that refreshes the widget does not port. dirge has
    no widget to refresh. Instead, a plugin can re-render its output on
    demand, through a command.
- Effort and shape: a worthwhile MVP (minimum viable product) is small.
  Register the `ot`-backed tools and a `/tasks` command in one `.janet` file.
  The large, hard-to-port pieces are the overlay, the widget, and the
  watcher. These are the interactive UI parts that dirge has no primitive
  for. Cut these parts, rather than rebuild them.

When you port an extension, confirm each `harness/*` name against
`plugins.md` before you use it. Prefer `:sequential` execution mode for any
tool that changes `TASKS.org`.

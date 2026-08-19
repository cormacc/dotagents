<!-- Sample source: skills/dirge/SKILL.md (lines 25-95) -- verbatim excerpt. Prose class: strict operational text (agent-consumed skill instructions). Vendored (skills/README.org S Vendored); last repository edit 2026-06-27 (commit fee2327), before the ASD-STE100 mandate landed 2026-08-14 (commit 5bad753). Extracted: 2026-08-19. -->

## Plugin model (essentials)

- dirge loads plugins from `~/.config/dirge/plugins/` (global) and
  `./.dirge/plugins/` (project-local). When two plugins share a name, the
  project-local plugin wins. A plugin needs no manifest and no entry point.
- A plugin is one `*.janet` file or one directory of `*.janet` files. A
  single-file plugin uses its file stem as the namespace. A directory plugin
  loads its files into one shared environment in lexicographic order. Use
  `00-`, `01-` prefixes to set the load order.
- dirge discovers hooks by top-level function name: `on-init`, `on-prompt`,
  `on-tool-start`, `on-tool-end`, `prepare-next-run`, `before-agent-start`,
  `transform-context`, `message-end`, `on-compact`, and others. A hook name is
  bare or namespaced. Both forms work. All hooks run on one Janet worker
  thread. A long hook handler stalls the agent.
- dirge preloads all `harness/*` symbols. A plugin needs no imports. The
  plugin UI is chat-line based. It offers:
  - `register-tool`
  - `register-command`
  - `register-shortcut` / `bind-key`
  - `register-renderer` + `append-entry`
  - `register-message-renderer` + `add-custom-message`
  - the blocking dialogs `confirm` / `select`
  dirge offers plugins no primitive for persistent widgets, overlays, or
  status lines.
- Building dirge requires the `--features plugin` flag. The default
  `cargo install` includes it. Run `dirge --version` and confirm that its
  output lists `plugin`.
- Reference plugins are in `~/dev/agents/dirge/plugins/`, for example
  `bookmark.janet`, `protected_paths.janet`, `select_persona.janet`, and
  `example_tool.janet`.

## Porting a pi/TypeScript extension to dirge

dirge's plugin surface is a subset of pi's. It has two structural differences:

1. **Language:** dirge plugins use Janet, not TypeScript. The port rewrites
   the logic. It does not transpile it.
2. **No rich TUI:** pi extensions can mount full-screen overlays and
   persistent widgets above the editor. dirge plugins cannot. They offer only
   chat lines, dialogs, slash commands, and key shortcuts.

A viable porting strategy keeps the agent-facing surface: LLM tools, slash
commands, and chat renderers. It drops or downgrades the interactive TUI.

### Assessment: `tasks` extension (`pi/extensions/tasks`)

This assessment reviews the extension against dirge `plugins.md`.
**Verdict: a useful subset ports cleanly. A faithful 1:1 port does not port
cleanly.**

Why the port is feasible: the extension is ~9.6k lines of TypeScript. It
already delegates all protocol operations to the external `ot` CLI. `ot` is
a Babashka/Clojure program that speaks JSON over stdout with schema
`org-tasks/v1`. The TypeScript layer is mostly a thin client plus a TUI.
`ot` is language-agnostic. A Janet plugin can run the same `ot` binary and
parse the same JSON envelope. The port needs no new parser or serializer.

- **Ports cleanly (chat-surface):**
  - `/tasks`-style slash commands → `harness/register-command`. The command
    runs `ot` and renders the JSON result as chat lines.
  - LLM-visible task tools (list/create/set-status/select/archive) →
    `harness/register-tool`. Mutation tools use `:sequential` execution mode.
    Each handler runs `ot` with an argv built from the tool arguments.
  - Pretty status/priority/tag coloring → `register-renderer` +
    `harness/render` color names, or a `register-message-renderer`.
  - `ot` discovery: PATH first, then skill-relative fallbacks
    (`~/.agents/skills/org-tasks/scripts/ot`, and others). The port keeps the
    same resolution order.
- **Does NOT port (pi-specific TUI):**
  - The expandable full-screen tasks overlay (`overlay.ts`, ~1.1k LOC):
    dirge has no overlay primitive. The best replacement is a
    `harness/select` picker plus chat-rendered lists. As an alternative, the
    port defers the feature entirely to the `ot` CLI or Emacs.
  - The compact widget that shows the selected task above the editor: dirge
    has no persistent-widget primitive at all.
  - The live file watcher that drives widget refresh: the port has no UI to
    refresh. A plugin can re-render on demand through a command instead.
- **Effort / shape:** A worthwhile MVP is small. Register the `ot`-backed
  tools and a `/tasks` command in one `.janet` file. The large, hard-to-port
  pieces (overlay, widget, watcher) are the interactive UI that dirge has no
  primitive for. The port cuts them rather than rebuilding them.

When porting, confirm each `harness/*` name against `plugins.md` before use.
Prefer `:sequential` execution mode for any tool that mutates `TASKS.org`.

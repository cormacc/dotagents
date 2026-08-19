<!-- Sample source: skills/dirge/SKILL.md (lines 25-95) -- ASD-STE100 rewrite of the excerpt (Strict mode). Prose class: strict operational text (agent-consumed skill instructions). Vendored (skills/README.org S Vendored); last repository edit 2026-06-27 (commit fee2327), before the ASD-STE100 mandate landed 2026-08-14 (commit 5bad753). Extracted: 2026-08-19. -->

## Plugin model (essentials)

- Dirge loads plugins from `~/.config/dirge/plugins/` (global) and
  `./.dirge/plugins/` (project-local). On a name collision, the
  project-local plugin wins. There is no manifest and no entry point.
- A plugin is one `*.janet` file (stem = namespace). **Or** a plugin is a
  directory of `*.janet` files. The directory loads into one shared env in
  lexicographic order. Use `00-`, `01-` prefixes for load order.
- Dirge discovers hooks by top-level function name, such as `on-init`,
  `on-prompt`, `on-tool-start`, `on-tool-end`, `prepare-next-run`,
  `before-agent-start`, `transform-context`, `message-end`, and
  `on-compact`. Bare or namespaced hook names both work. Hooks run on one
  Janet worker thread. A long handler stalls the agent.
- Dirge preloads all `harness/*` symbols. Plugins need no imports. The
  plugin UI is **chat-line based**: it offers `register-tool`,
  `register-command`, `register-shortcut` / `bind-key`, `register-renderer` +
  `append-entry`, and `register-message-renderer` + `add-custom-message`. It
  also offers the blocking dialogs `confirm` and `select`. dirge offers
  plugins no persistent widget, no overlay, and no status-line primitive.
- Building dirge requires the `--features plugin` flag. The default
  `cargo install` includes the feature. Verify with `dirge --version`:
  the output lists `plugin`.
- Reference plugins are in `~/dev/agents/dirge/plugins/` (such as
  `bookmark.janet`, `protected_paths.janet`, `select_persona.janet`,
  `example_tool.janet`).

## Porting a pi/TypeScript extension to dirge

The dirge plugin surface is a subset of the pi surface. It has two
structural differences:

1. **Language:** the plugin is Janet, not TypeScript. The port rewrites the
   logic. It does not transpile it.
2. **No rich TUI:** a pi extension can mount full-screen overlays and
   persistent widgets above the editor. A dirge plugin cannot do this. A
   dirge plugin offers only chat lines, dialogs, slash commands, and key
   shortcuts.

Viable porting strategy: keep the agent-facing surface (LLM tools, slash
commands, chat renderers). Drop the interactive TUI or downgrade it.

### Assessment: `tasks` extension (`pi/extensions/tasks`)

This assessment compares the extension against dirge `plugins.md`.
**Verdict: a useful subset ports cleanly. A faithful 1:1 port does not port
cleanly.**

**The extension is ~9.6k LOC of TypeScript. It already delegates all protocol
operations to an external `ot` CLI.** The CLI uses the `org-tasks/v1` schema,
JSON over stdout, and Babashka/Clojure. The TypeScript layer is a thin client
plus a TUI. `ot` is language-agnostic. A Janet plugin can call the same binary
and parse the same JSON envelope. The port does not reimplement a parser or a
serializer.

- **Ports cleanly (chat-surface):**
  - `/tasks`-style slash commands → `harness/register-command`. The command
    runs `ot` and renders the JSON result as chat lines.
  - LLM-visible task tools (list, create, set-status, select, archive) →
    `harness/register-tool`. Use `:sequential` execution-mode for mutations.
    Each handler runs `ot` with one argv entry per argument.
  - Pretty status, priority, and tag coloring → `register-renderer` plus
    `harness/render` color names, or a `register-message-renderer`.
  - `ot` discovery: check PATH first, then check skill-relative fallbacks
    (such as `~/.agents/skills/org-tasks/scripts/ot`). This is the same
    resolution order.
- **Does NOT port (pi-specific TUI):**
  - The expandable full-screen tasks overlay (`overlay.ts`, ~1.1k LOC): dirge
    has no overlay primitive. The best replacement is a `harness/select`
    picker and chat-rendered lists. Or use the `ot` CLI or Emacs for the
    whole feature.
  - The persistent selected-task widget above the editor: dirge has no
    persistent-widget primitive at all.
  - The live file watcher that drives widget refresh: dirge has no widget to
    refresh. A plugin can re-render on demand via a command instead.
- **Effort / shape:** a worthwhile MVP is small. One `.janet` file registers
  the `ot`-backed tools and a `/tasks` command. The large, hard-to-port
  pieces (overlay, widget, watcher) are the interactive UI that dirge has no
  primitive for. The port cuts them. It does not rebuild them.

When porting, confirm each `harness/*` name against `plugins.md` before use.
Use `:sequential` execution-mode for any tool that mutates `TASKS.org`.

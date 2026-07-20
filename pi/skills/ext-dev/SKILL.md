---
name: ext-dev
description: Develop/debug pi extensions. Use for creating or modifying extensions, or troubleshooting extension APIs, TUI, keybindings, themes, and custom editors.
---

# Pi Extension Development

## Workflow

1. **Read the documentation** before writing any code — do not guess APIs.
   Load the docs listed below with the `read` tool.
2. **Load extension source** with `/ext <name>` (autocompletes extension names).
   Use `/ext` with no arguments to list available extensions. The default load is production-focused and bounded to 32 KiB per file / 96 KiB total. Use `/ext <name> --include-tests` to opt into test files, or `/ext <name> --files path[,path]` for an explicit bounded selection.
3. **Read relevant examples** from the examples directory. Read `README.md`
   there first, then inspect specific examples as needed.

## Documentation

Read as needed (most to least commonly needed):

| Doc              | When to read                                                                   |
| ---------------- | ------------------------------------------------------------------------------ |
| `extensions.md`  | **Always** — core extension API, lifecycle, tools, commands, events, shortcuts |
| `tui.md`         | Custom renderers, widgets, overlays, Box/Text layout, themes                   |
| `keybindings.md` | KeybindingsManager, registerShortcut, key matching                             |
| `themes.md`      | Theme tokens, fg/bg/bold, EditorTheme vs MessageTheme                          |

Paths are in the system prompt under "Additional docs" and "Examples".

## Guidelines

- **Shared utilities** :: check `extensions/lib/*.ts` for reusable helpers
  (e.g. `getExtensionName`) before writing new code.
- **Documentation** :: Create a short README.md per extension.
  - Multi-file extensions :: `extensions/foo/README.md`
  - Single-file extensions :: `extensions/foo.md`
  - Include :: purpose, slash commands, dependencies, suggested keybindings.
  - Always update the readme after modifying an extension.

## Current API contracts

- **Imports** :: use `@earendil-works/pi-coding-agent`, `@earendil-works/pi-tui`, and `@earendil-works/pi-ai`; import `Type` from `typebox` (TypeBox 1.x), not `@sinclair/typebox` or the retired `@mariozechner/*` scopes.
- **UI mode** :: use `ctx.mode === "tui"` before opening a custom component with `ctx.ui.custom()` or using terminal-only TUI APIs. `ctx.hasUI` is also true in RPC mode and is appropriate only for UI methods RPC supports, such as `notify`, `input`, and `select`.
- **True idle** :: integrations that report completion must wait for `agent_settled`, not `agent_end`: retries, compaction recovery, and queued follow-ups may continue after `agent_end`.
- **Event subscriptions** :: retain every unsubscribe function returned by `pi.events.on()` and call it from an idempotent `session_shutdown` handler. Do not let listeners survive `/reload` or session replacement.
- **Transcript visibility** :: use `pi.appendEntry()` plus `pi.registerEntryRenderer()` for durable human-facing TUI transcript data that must not enter model context. Use `pi.sendMessage()` only when the model must receive the content; custom messages are model-visible even when `display: true`.

## Events, Commands & Keybindings

Extensions should expose reusable actions via **events on `pi.events`** under a common prefix (e.g. `term:toggle`, `term:prev`). Slash commands, shortcuts, tools, and other extensions should dispatch those same events instead of duplicating action logic.

Typical layers:

1. **Event listeners** — implement the action, registered with `pi.events.on`.
2. **Slash command** — parses subcommands and dispatches via `pi.events.emit("ext:action")`. This is the stable user-facing entry point.
3. **Optional shortcuts** — register direct shortcuts with `pi.registerShortcut(...)` only for actions that are worth a global key. Prefer documenting suggested bindings when the action is optional or potentially conflicting.

The event listener remains the single source of truth. Cross-extension invocation works naturally — any extension can `pi.events.emit("term:run", { command: "..." })` without importing another extension.

### Pattern

```typescript
export default function (pi: ExtensionAPI) {
  pi.on("session_start", async () => {
    // ── 1. Action helpers ────────────────────────────────

    async function fooToggle() { /* ... */ }
    async function fooRun(cmd: string) { /* ... */ }

    // ── 2. Event listeners (prefixed `foo:`) ─────────────

    const unsubToggle = pi.events.on("foo:toggle", () => {
      fooToggle();
    });
    const unsubRun = pi.events.on(
      "foo:run",
      (data: { command: string }) => {
        fooRun(data.command);
      },
    );

    // ── 3. Slash command — dispatches events ─────────────

    pi.registerCommand("foo", {
      description: "toggle | run",
      handler: async (args) => {
        const arg = (args || "").trim();
        if (!arg || arg === "toggle") {
          pi.events.emit("foo:toggle");
          return;
        }
        if (arg.startsWith("run ")) {
          pi.events.emit("foo:run", { command: arg.slice(4) });
          return;
        }
      },
    });

    // ── 4. Optional shortcut ─────────────────────────────

    pi.registerShortcut("ctrl+shift+f", {
      description: "Toggle foo",
      handler: async () => {
        pi.events.emit("foo:toggle");
      },
    });

    // ── 5. Cleanup ───────────────────────────────────────

    pi.on("session_shutdown", async () => {
      unsubToggle();
      unsubRun();
    });
  });
}
```

### Key rules

- Prefer slash commands and documented suggested keybindings for most extension actions; reserve `pi.registerShortcut` for high-value global shortcuts.
- Keep implementation behind `pi.events` so slash commands, shortcuts, tools, and other extensions share one action path.
- Release every `pi.events.on()` subscription during `session_shutdown`; cleanup must tolerate repeated shutdown paths.
- Read `keybindings.md` before choosing a default shortcut, and avoid collisions with built-in bindings.
- Do not recommend new leader-menu integrations. The old `leader-menu` extension is retired/disabled and should be treated as legacy compatibility code only.

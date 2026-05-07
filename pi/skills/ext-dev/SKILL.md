---
name: ext-dev
description: Develop, modify, or debug pi extensions. Use when creating new extensions, modifying existing ones, or troubleshooting extension issues. Covers the extension API, TUI components, keybindings, theming, and custom editors.
---

# Pi Extension Development

## Workflow

1. **Read the documentation** before writing any code — do not guess APIs.
   Load the docs listed below with the `read` tool.
2. **Load extension source** with `/ext <name>` (autocompletes extension names).
   Use `/ext` with no arguments to list available extensions.
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
  (e.g. `getExtensionName`, `registerLeaderMenu`) before writing new code.
- **Extension structure** :: single-file extensions go in `extensions/foo.ts`;
  multi-file extensions go in `extensions/foo/index.ts` with supporting modules.
- **Imports** :: use `@mariozechner/pi-coding-agent` for the extension API,
  `@mariozechner/pi-tui` for TUI primitives, `@sinclair/typebox` for tool
  parameter schemas.
- **Testing** :: use `/reload` to hot-reload extensions during development.
- **Documentation** :: Create a short README.md per extension.
  - Multi-file extensions :: `extensions/foo/README.md`
  - Single-file extensions :: `extensions/foo.md`
  - Include :: purpose, slash commands, dependencies, suggested keybindings.
  - Always update the readme after modifying an extension.

## Events, Commands & Keybindings

Extensions should expose their actions via **events on `pi.events`** under a
common prefix (e.g. `term:toggle`, `term:prev`). Three layers wire them up:

1. **Event listeners** — implement the action, registered with `pi.events.on`.
2. **Slash command** — parses subcommands and dispatches via
   `pi.events.emit("ext:action")`. This is the user-facing entry point.
3. **Leader-menu contributions** — registered with the `leader-menu`
   extension via `registerLeaderMenu`. Each binding uses the event name as
   its `action` (e.g. `action: "foo:toggle"`). The `leader-menu` extension
   treats any action without a `command:` or `passthrough:` prefix as an
   event and emits it directly.

This means the event listener is the single source of truth. The slash command,
keybindings, global shortcuts, and other extensions all go through the same
event. Cross-extension invocation works naturally — any extension can
`pi.events.emit("term:run", { command: "..." })` without importing anything.

### Pattern

```typescript
import { getExtensionName, registerLeaderMenu } from "../lib/pi-utils.js";

const EXT_NAME = getExtensionName(import.meta.url);

/** Cleanup handle for the leader-menu registration, to avoid duplicates on reload. */
let cleanupKb: (() => void) | null = null;

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

    // ── 4. Leader-menu contributions ─────────────────────
    //   Bare event names — the leader-menu extension treats any action
    //   without a `command:` or `passthrough:` prefix as an event.

    cleanupKb = registerLeaderMenu(pi, EXT_NAME, {
      globalMenu: {
        items: {
          f: {
            label: "+foo",
            items: {
              t: { label: "Toggle", action: "foo:toggle" },
            },
          },
        },
      },
    });

    // ── 5. Cleanup ───────────────────────────────────────

    pi.on("session_shutdown", async () => {
      unsubToggle();
      unsubRun();
      cleanupKb?.();
      cleanupKb = null;
    });
  });
}
```

### Key rules

- **Do NOT bind keys explicitly** — use `registerLeaderMenu` from
  `extensions/lib/pi-utils.ts` to contribute bindings to the `leader-menu`
  extension.
- Call `registerLeaderMenu` inside `session_start` (not at the top level of the
  default function) and store the cleanup handle at **module level** so it
  survives reloads.
- Unsubscribe all event listeners and call the leader-menu cleanup function on
  `session_shutdown`.
- Use bare event names in leader-menu actions (e.g. `"foo:toggle"`). The
  `command:` and `passthrough:` prefixes are reserved for slash commands and
  key passthrough respectively — everything else is emitted as an event.
  The legacy `"event:"` prefix is still accepted for backward compatibility
  but is not required.
- For a unified view of every registered chord, run `/leader-menu bindings
  --export` — this prints an org-mode table of the entire merged tree
  (defaults + every contributing extension). It replaces the hand-maintained
  `extensions/keybindings.org` file from before the leader-menu / vim-mode
  split.

# leader-menu

Which-key-style leader chord discovery and dispatch for [pi](https://github.com/nichochar/pi-coding-agent).

This extension owns two abstract leader slots, the `ctrl+<leader>`
global shortcuts that open them, the cross-extension *contribution*
API that every other extension uses to register its own sub-menus,
and one-step slash-command submission via the shared `SubmitterEditor`
base in [`extensions/lib/editor.ts`](../lib/editor.ts). Modal editing
is out of scope — modal-editor extensions live alongside this one and
own their own lifecycle.

## Leaders

Two abstract slots, each with a configurable trigger key:

| Slot     | Default key | Purpose                                       |
|----------+-------------+-----------------------------------------------|
| Global   | `Space`     | Primary leader; extensions contribute here.   |
| Local    | `,`         | pi-agent quick actions (model, thinking, …). |

User overrides live in `~/.pi/agent/leader-menu.json`:

```json
{
  "globalLeader": "/",
  "localLeader": ";",
  "debug": true
}
```

All keys optional:

- `globalLeader` / `localLeader` — default `" "` and `","`.
- `debug` — when true, every key press is reported via
  `ctx.ui.notify`. Read by this extension and by any sibling
  input-handling extension that wants the same toggle; the flag is
  about *key dispatch*, not any one extension.

Trigger key resolution happens once at session_start; changing leader
keys requires a fresh `pi` process (because the underlying
`ctrl+<leader>` shortcuts are registered with pi at startup and have
no unregister hook).

Extensions never name trigger keys directly — they contribute via
`registerLeaderMenu()` with `globalMenu` / `localMenu` slots. The
final trigger key is whatever the user has configured.

### Settings migration

On first session_start, leader-menu copies the `debug` flag (and only
`debug`) from any pre-split settings file it finds and then deletes
the source:

- `~/.pi/agent/vim-mode.json`
- `~/.pi/agent/keybindings-ext.json`

The legacy `modal` flag is intentionally not migrated — modal-editor
extensions own their own lifecycle now and there is no central toggle
for them.

## Slash commands

| Command                            | Description                                                |
|------------------------------------+------------------------------------------------------------|
| `/leader-menu bindings`            | Interactive overlay listing every registered chord         |
| `/leader-menu bindings --export`   | Print every chord as an org-mode table (replaces hand-maintained `keybindings.org`) |

## Global shortcuts

| Shortcut              | Action                  |
|-----------------------+-------------------------|
| `ctrl+<globalLeader>` | Open the global menu    |
| `ctrl+<localLeader>`  | Open the local menu     |

With default leaders these are `ctrl+space` and `ctrl+,`.

### Terminal compatibility

`ctrl+space` is recognised in nearly every terminal (it sends `\x00`).

`ctrl+<symbol>` chords like `ctrl+,` are only recognised in terminals
that speak the Kitty keyboard protocol or `modifyOtherKeys` — kitty,
ghostty, wezterm, foot, recent xterm. In plain `xterm`,
`gnome-terminal`, macOS Terminal.app, or under tmux without the right
flags, `ctrl+,` is silently dropped by the terminal and the overlay
will not open. Workarounds: open the menu via `/leader-menu bindings`,
or pick a letter `localLeader` (e.g. `"q"`) in `leader-menu.json`.

#### macOS gotcha: `⌃Space` is claimed by the OS

On macOS, `⌃Space` (and `⌃⌥Space`) are bound by default to **Select
the previous input source** / **Select next source in Input menu**
under *System Settings → Keyboard → Keyboard Shortcuts → Input
Sources*. macOS intercepts the chord before the terminal sees it, so
even in kitty / ghostty / wezterm the global leader will not open
with the shipped default `globalLeader = " "`. Confirm by running
`cat -v` in the terminal and pressing `⌃Space` — if nothing prints,
macOS is swallowing it.

Two fixes:

1. *(Recommended)* Uncheck both Input Sources shortcuts in System
   Settings. The terminal will then receive the chord and the
   leader menu opens normally.
2. Set a different `globalLeader` (e.g. `"/"`) in
   `~/.pi/agent/leader-menu.json`. Requires a fresh `pi` process —
   `/reload` alone will not rebind, because the `ctrl+<leader>`
   shortcuts are registered once at session_start and pi has no
   unregister hook.

Modal-editor extensions that catch bare leader keys in their own
grammar can defer back to this overlay by emitting the
`leader-menu:open` event with the configured root key. They typically
subscribe to `leader-menu:keys-resolved` to learn which keys to catch
without reading leader-menu's settings file directly.

### Choosing a local leader

The shipped default `localLeader = ","` matches the common Vim
convention. If you load a modal-editor extension whose grammar
consumes that key, the bare-key entry path is shadowed there; the
local menu remains reachable in any mode via `ctrl+,` (or
`ctrl+<localLeader>` whatever you've configured), subject to the
terminal-compatibility caveat above. If you prefer a bare-key local
leader that survives modal grammars, set a non-grammar key in
`~/.pi/agent/leader-menu.json`. Modal-editor extensions are
responsible for warning about clashes against their own grammars.

## Default leader bindings (`defaults.json`)

### `,` — pi agent

| Chord | Label              |
|-------+--------------------|
| `, m` | Switch model       |
| `, t` | Cycle thinking     |
| `, o` | Toggle tool output |
| `, k` | Toggle thinking    |
| `, e` | External editor    |
| `, n` | New session        |
| `, r` | Resume session     |
| `, b` | Session tree       |
| `, c` | Compact            |
| `, y` | Copy last response |
| `, s` | Settings           |
| `, R` | Reload extensions  |

### `Space` — leader

`defaults.json` ships no global bindings of its own. Global menus are
registered dynamically by other extensions (for example `tasks`,
`term`, and `git-diff`) via the contribution API below.

Additional local-menu entries may also be registered dynamically by
other extensions.

## Contribution API

Other extensions contribute leader entries via the
`registerLeaderMenu()` helper in `lib/pi-utils.ts`:

```typescript
import { getExtensionName, registerLeaderMenu } from "../lib/pi-utils.js";

const EXT_NAME = getExtensionName(import.meta.url);
let cleanupKb: (() => void) | null = null;

export default function (pi: ExtensionAPI) {
  pi.on("session_start", () => {
    cleanupKb = registerLeaderMenu(pi, EXT_NAME, {
      globalMenu: {
        items: {
          m: {
            label: "+my-ext",
            items: {
              x: { label: "Do thing", action: "myext:do-thing" },
            },
          },
        },
      },
    });
  });
  pi.on("session_shutdown", () => { cleanupKb?.(); });
}
```

The registration has two slots, both optional:

- `globalMenu` — contributions appear under the configured global
  leader (default `Space`). Most extensions use this slot.
- `localMenu` — contributions appear under the configured local
  leader (default `,`). Reserved for pi-agent quick-action style
  bindings; most extensions don't need it.

Note: extensions never specify trigger keys directly. The final
trigger key is whatever the user has configured (or the default).
This means the same registration JSON works regardless of how the
user has rebound their leaders.

The helper:

- Sends a `leader-menu:register` event when leader-menu is loaded;
- Subscribes to `leader-menu:ready` so contributions are re-sent after
  pi reloads and after leader-menu re-initialises;
- Returns a `cleanup: () => void` for `session_shutdown`.

Contributions are merged additively. Same-key sub-menus merge
recursively. A clash with an existing leaf binding is logged as a
warning and the existing entry wins. Two contributions of the same
key+label are deduped silently (idempotent re-registration).

### Action types

Any action string without a recognised prefix is emitted as an event on
`pi.events`. This is the primary mechanism for cross-extension
keybindings — the contributing extension subscribes to the event itself.

| Prefix         | Description                       | Example                     |
|----------------+-----------------------------------+-----------------------------|
| *(none)*       | Emit as event (default)           | `myext:do-thing`            |
| `command:`     | Submit a slash command (one step) | `command:/compact`          |
| `passthrough:` | Forward a key combo               | `passthrough:ctrl+l`        |
| `event:`       | Emit as event (legacy)            | `event:term:toggle`         |

## Slash command submission

leader-menu installs a small editor base — `SubmitterEditor`, defined
in `extensions/lib/editor.ts` — at every `session_start` via
`ctx.ui.setEditorComponent(…)`. This editor adds one method,
`submitCommand(text)`, that synthesises an Enter press so pi's
`onSubmit` handler dispatches the slash command without a second
keystroke.

When a `command:` chord fires, leader-menu's action host:

1. Calls `ctx.ui.setEditorText(command)` so the command is visible in
   the editor.
2. Calls `submitCommand(command)` on the currently mounted
   `SubmitterEditor` (resolved fresh from a module-scope registry in
   `lib/editor.ts`, so it picks up whichever subclass is active when
   another extension has installed its own editor).

If no `SubmitterEditor` is mounted (extension contract violation —
should not happen post-install), the host falls back to the legacy
“insert text + ask the user to press Enter” behaviour so the chord
still produces the command.

### Cross-extension contract for editor replacement

Any extension that calls `ctx.ui.setEditorComponent(…)` and wants
`command:` chords to keep working in one keystroke MUST extend
`SubmitterEditor` (re-exported from `extensions/lib/editor.ts`)
rather than `CustomEditor` directly. Replacing the editor with a
non-`SubmitterEditor` clobbers single-step submission and silently
regresses chord dispatch to the legacy two-step UX.

Subclasses must preserve the *trivially-stateless* property of the
base: do not override `submitCommand` to add internal state, and do
not re-introduce parallel text/render/autocomplete state, for the
reasons documented in
`design/log/2026-05-01-keybindings-editor-composition.org`. Carry your
own state on the subclass, not on the base.

The submission seam is an implementation detail of leader-menu —
`SubmitterEditor` lives in the shared `extensions/lib/` directory
(internal across the in-tree extensions) rather than as a public pi
API. If a third-party extension needs one-step slash-command
submission, the contract above is the entry point.

## Cross-extension contracts

### Publish resolved leader keys

After resolving leader keys (defaults + user overrides) at
`session_start`, leader-menu emits:

```
  leader-menu:keys-resolved {
    globalLeader: string,
    localLeader: string,
    userConfigured: { globalLeader: boolean, localLeader: boolean },
  }
```

The `userConfigured` flags say whether the slot was explicitly set in
`leader-menu.json` (true) or took the shipped default (false).
Subscribers (typically modal-editor extensions, alternate
dispatchers) use this to scope clash warnings to user choices and
avoid noise on the shipped defaults. leader-menu does not warn about
grammar clashes itself — that's the consumer's responsibility, since
it knows its own grammar.

## Files

| File             | Purpose                                                |
|------------------+--------------------------------------------------------|
| `index.ts`       | Extension entry point, overlays, contribution merger   |
| `defaults.json`  | Immutable default leader-menu definitions              |
| `README.md`      | This file                                              |
| `AGENTS.md`      | Agent-side notes for modifying this extension          |

User settings live in `~/.pi/agent/leader-menu.json` (leader trigger
keys plus the shared `debug` flag).

## Dependencies

- `@mariozechner/pi-coding-agent` — `ExtensionAPI`, `KeybindingsManager`
- `@mariozechner/pi-tui` — `matchesKey`, `truncateToWidth`, `Component`, `TUI`
- `lib/pi-utils.ts` — `ansiPad`

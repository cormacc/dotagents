# term

A [pi](https://github.com/nichochar/pi-coding-agent) extension that manages a
small dedicated **tmux server** for interactive shell sessions and can show the
currently selected session in a visible **monitor pane**.

`term` no longer overrides pi's built-in `bash` tool. pi uses its normal bash
execution path again; this extension is now focused on **session management and
monitoring**.

## Current status

This refactor currently supports four monitor backends:

- **kitty** — supported as a monitor-only backend
- **sway** — supported as a monitor-only backend using `foot`
- **tmux** — supported
- **ghostty** — in-place split on macOS via AppleScript; dedicated monitor window on Linux

Detection order is **tmux → kitty → ghostty → sway**. First match wins:

- pi inside plain tmux (any outer terminal) → tmux
- pi inside kitty, no outer tmux → kitty
- pi inside Ghostty, no outer tmux or kitty → ghostty (macOS: in-place
  AppleScript split; Linux: dedicated monitor window)
- pi under sway with none of the above → sway (spawns a `foot` monitor)

## Architecture

The extension now has two layers:

1. **Dedicated tmux session server**
   - `term` creates a private tmux server (`tmux -L <socket>`)
   - each shell/process lives in its own named tmux session
   - sessions can be listed, attached, read from, sent input, and killed

2. **Monitor pane backend**
   - the tmux backend creates/destroys a visible split pane on demand
   - that pane runs:

   ```bash
   env -u TMUX tmux -L <socket> attach-session -t <session>
   ```

This keeps terminal session management in tmux and removes the old prompt-hook,
PTY relay, and bash-tool interception logic.

The Ghostty backend behaves differently by platform:

- **macOS** — uses Ghostty's AppleScript scripting dictionary
  (`split terminal direction down with configuration {command:...}`) to create
  an in-place split inside pi's own window, then refocuses pi. `hide`,
  `focus`, and session reattach all drive the split via AppleScript.
- **Linux** — falls back to launching a separate Ghostty monitor window,
  tracking its PID for show/hide. There is no remote-control API on GTK
  Ghostty that lets us split pi's own window from outside.

## Features

- **Default shell session** — creates a `shell` tmux session for ad-hoc work
- **Named sessions** — create interactive sessions or spawn commands in new ones
- **Visible monitor pane/window** — show/hide/focus a tmux-attached monitor surface via the active backend
- **Session switching** — cycle or attach by name/index
- **Tool wrappers** — start/read/send/stop/list managed sessions via tmux
- **No bash override** — pi's default bash tool is used unchanged

## CLI

```bash
pi                # activates when running inside kitty, ghostty, sway, or tmux
pi --no-mirror    # disable the term monitor/session extension for this run
```

## Slash command

### `/term [subcommand]`

| Subcommand                  | Description |
| --------------------------- | ----------- |
| _(none)_ / `toggle`         | Toggle the monitor pane |
| `focus`                     | Show (if hidden) and focus the monitor pane |
| `status`                    | Show backend/socket/debug info |
| `list`                      | List sessions |
| `prev`                      | Select the previous session |
| `next`                      | Select the next session |
| `attach <name\|index>`      | Attach the monitor to a session |
| `<index>`                   | Attach the monitor to a session by 1-based index |
| `new <name>`                | Create a new interactive session |
| `kill <name\|index>`        | Kill a session |
| `run "<cmd>"`              | Send a command to the active session |
| `spawn [title] "<cmd>"`    | Create a new session and run a command |

## Keybindings

Registered with the `keybindings` extension under `Space t`:

| Key         | Action         | Command |
| ----------- | -------------- | ------- |
| `Space t t` | Toggle monitor | `/term toggle` |
| `Space t f` | Focus monitor  | `/term focus` |
| `Space t h` | Previous session | `/term prev` |
| `Space t l` | Next session | `/term next` |

## Tools

### `start_process`

Create a named tmux session and run an initial command.

| Parameter | Type | Description |
| --------- | ---- | ----------- |
| `name`    | `string` | Session name |
| `command` | `string` | Command to run |
| `mode`    | `"watch" \| "quiet"` | Compatibility flag; auto-watch notifications are not emitted anymore |

### `send_input`

Send text input to a managed tmux session.

| Parameter | Type | Description |
| --------- | ---- | ----------- |
| `name`    | `string` | Session name |
| `text`    | `string` | Text to send |
| `enter`   | `boolean` | Send Enter after text (default: true) |

### `read_process`

Read recent scrollback from a managed tmux session.

| Parameter | Type | Description |
| --------- | ---- | ----------- |
| `name`    | `string` | Session name |
| `lines`   | `number` | Lines of scrollback (default: 200) |

### `stop_process`

Kill a managed tmux session.

| Parameter | Type | Description |
| --------- | ---- | ----------- |
| `name`    | `string` | Session name |

### `list_processes`

List non-default sessions in the term tmux server.

## Requirements

- pi must be running inside **kitty**, **ghostty**, **tmux**, or under **sway**
- `tmux` must be installed
- the user's shell should be available inside tmux sessions
- for the **sway** backend, `swaymsg` and `foot` must be available
- for the **kitty** backend, kitty remote control must be enabled and `kitten`
  must be available
- for the **ghostty** backend, `ghostty` must be available on `$PATH`

## Notes

- The default session is named **`shell`**.
- Session indices shown by `/term list` and the status widget are **1-based**.
- The dedicated tmux server is per pi session and is cleaned up on shutdown.
- The sway backend creates/destroys a `foot` monitor window and attaches it to
  the selected tmux session.
- The kitty backend creates/destroys a kitty split window and attaches it to
  the selected tmux session.
- On macOS the Ghostty backend performs an in-place split inside pi's Ghostty
  window via AppleScript and attaches the new terminal to the selected tmux
  session. `osascript` and Ghostty's AppleScript scripting must be available
  (they are by default on a standard Ghostty.app install).
- On Linux the Ghostty backend instead spawns a dedicated Ghostty monitor
  window attached to the selected tmux session, since GTK Ghostty has no
  equivalent remote-control API.

## Dependencies

- `lib/pi-utils.ts` — `getExtensionName`, `registerLeaderMenu`

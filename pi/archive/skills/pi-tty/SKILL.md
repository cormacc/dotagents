---
name: pi-tty
description: Use pi TTY workflows for long-running commands, background watchers, pane output capture, and sibling/subagent pi sessions. Use whenever the user mentions `/t`, tty/tmux/zellij panes or windows, watch/background/long-running processes, reading output from a terminal pane, launching parallel pi, spawning a subagent/sibling pi, or asks to monitor tests/servers without blocking the current conversation.
---

# pi tty workflows

Use this skill when a terminal multiplexer can keep work visible and inspectable without blocking the current pi session. The user-facing command is backend-neutral `/t`; the extension auto-detects cmux, tmux, zellij, or wezterm and supports `PI_TTY_MUX=cmux|tmux|zellij|wezterm` to override detection.

The `pi/extensions/tty` extension owns the actual capability:

- `/t s|spawn <cmd>` — run an interactive command in a new backend surface/window.
- `/t w|watch <cmd>` — run a long-lived command with double-Ctrl-C cleanup; joins the window below pi and keeps focus in pi.
- `/t l|list` — show windows plus spawned/watched pane ids.
- `/t t|tail [target] [lines]` — capture recent output for the user.
- `tty_list` — agent tool to discover windows/panes and known spawned/watched panes.
- `tty_capture` — agent tool to read recent pane output.
- `/t j|join <target>` / `/t b|break [pane-id]` — temporarily show/hide a single-pane target below the current pi pane.
- `/t m|monitor <target>` — same as join but keeps the focus/cursor in the parent (pi) pane.
- `/t k|kill [target]` — send two Ctrl-C signals (stop process + close wrapper) then remove the target; defaults to the joined/monitored pane.

## Backends

- tmux: full window/pane listing, target resolution by `%pane`, `@window`, index, or name; join uses `join-pane`; break uses `break-pane`; ANSI-preserving capture is supported.
- zellij: `/t spawn` creates a tty-managed floating pane in the current pi tab and renames it `<index>: <cmd>`; `/t monitor` embeds it and restores focus to pi; `/t join` embeds it and focuses the target; `/t break` floats it again. Use the displayed terminal index (`/t j 1`) or full pane id (`terminal_1`); arbitrary cross-tab adoption is intentionally unsupported.
- cmux: spawned surfaces are renamed `<index>: <cmd>`; use the displayed surface index (`/t j 42`) or full id (`surface:42`). Attach moves a surface into the caller pane and break splits it off.
- wezterm: spawned commands open as new tabs titled from the command; use the displayed 1-based tab index (`/t j 2`). The extension resolves it from current-window `wezterm cli list --format json` order. Attach moves a cross-tab pane below pi; same-tab attach is refused due to a known wezterm move bug; break moves the pane to a new tab and restores the command title.
- `tty_capture` works across backends where screen capture exists, but `preserveEscapes: true` is tmux-only.

## Choosing bash vs tty

Prefer ordinary `bash` when the command is short-lived and the result should be part of the current turn.

Prefer `/t w <cmd>` (alias `/t watch`) when the command is expected to keep running or produce output over time:

- file watchers (`bb watch:all`, `npm run dev`, `cargo watch`, `shadow-cljs watch …`)
- local servers
- streaming logs
- long test loops
- commands the user may want to Ctrl-C manually

After starting a watcher, use `tty_list` to discover its `pane_id`, then `tty_capture` to read output. Do not ask the user to copy/paste pane output when the tool can capture it.

## Reading watched output

Default path:

1. Start the process: `/t w <cmd>` (or `/t watch <cmd>`).
2. Discover panes: call `tty_list` if you do not already know the pane id.
3. Read output: call `tty_capture`.
   - Omit `target` to capture the latest watched pane for the current pi pane when available.
   - Pass `target: "%N"` for a specific pane.
   - Keep `lines` bounded; default 200 is usually enough.
   - Use `preserveEscapes: false` unless ANSI escapes are relevant.

Remember backend scrollback is finite; capture cannot recover output that already fell out of scrollback.

## Watch cleanup

`/t w` / `/t watch` uses double-Ctrl-C semantics:

1. First Ctrl-C in the watch window is caught by the wrapper and forwarded to the watched command.
2. The wrapper prints the exit status and waits.
3. Second Ctrl-C closes the window.

If the user asks how to close a completed watched process, tell them to press Ctrl-C once more in that watch window, or use `/t kill` for the active monitor.

## Sibling pi / subagent pattern

Use `/t s pi …` (alias `/t spawn pi …`) when parallel work would help but should stay visible in the same multiplexer session. The new pi starts in the same working directory because the extension creates the window with the current cwd.

Examples:

```text
/t s pi
/t s pi --model openai/gpt-4o
/t spawn pi --model sonnet:high "Review this implementation"
/t spawn pi --append-system-prompt @prompt.md "Investigate the failing test"
```

Verified pi CLI flags:

- `--model <pattern>` for model or provider/model selection, including `model:thinking` shorthand.
- `--provider <name>` for provider selection.
- `--thinking <level>` for thinking level.
- `--system-prompt <text>` to replace the prompt.
- `--append-system-prompt <text>` to append text or file contents.
- trailing positional messages become the initial prompt.

**Model selection — always qualify the provider.** A bare model name (`--model gpt-5.5`, or `model: "gpt-5.5"` on the `subagent` tool) resolves **first-match across the provider list** and can land on an unauthenticated provider (e.g. openrouter), which silently stalls. Always pass `provider/model` whose provider is authenticated — match `settings.json` `defaultProvider` (currently `openai-codex`), e.g. `openai-codex/gpt-5.5`. To use the subscription default, omit the model entirely and inherit the agent frontmatter / default.

Use the `pi-intercom` skill/tooling to communicate with spawned pi instances. This skill only covers launching and observing tty-backed processes; `pi-intercom` owns transport semantics (`list`, `send`, `ask`, `reply`) and coordination protocol.

## Join/break guidance

Use `/t j <target>` (alias `/t join`) when the user wants to see another window below the current pi pane and then interact with it directly. Targets must be in the same backend/session and currently single-pane; multi-pane windows are refused to avoid surprising pane moves. In zellij and cmux, use the numeric prefix shown in the spawned title (`/t j 1` for `1: bb watch-echo`) or the full backend id. In WezTerm, use the displayed 1-based tab index.

Use `/t m <target>` (alias `/t monitor`) when the user wants to see another window below the current pi pane but keep the focus/cursor in the pi pane (e.g., to read output from the other window without switching focus). Same single-pane target constraints. Use this after `/t w` if you want to switch which window is shown below pi.

Use `/t b` (alias `/t break`) to return the joined pane to its own window. After `/reload` or a pi restart, bare `/t b` may have lost state; use `/t b <pane-id>` if needed.

## Kill guidance

Use `/t k` (alias `/t kill`) to stop the process and remove the currently joined/monitored pane. Use `/t k <target>` to do the same for a specific target. Tmux accepts numeric window indices (`2`), window ids (`@7`), pane ids, and window names; zellij/cmux accept the numeric prefix shown in the spawned title as well as the full backend id; WezTerm accepts its displayed 1-based tab index.

The command sends two Ctrl-C signals: the first stops the process (the watch wrapper catches it and waits), the second closes the wrapper shell. Then it removes the target (`kill-pane` for the joined pane; `kill-window` for an explicit target in the tmux backend). Cleanup tolerates already-closed panes/windows.

## When not to use tty

Avoid spawning background terminal work for quick one-shot commands, commands whose output must be immediately consumed by the model in the same turn, or tasks where hidden state would confuse the user. Use normal tools first unless there is a clear benefit to keeping a process alive or visible.

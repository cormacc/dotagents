# tty extension

A small pi extension for TTY-centric workflows. The current backend is tmux; the user-facing slash command is `/t` so other backends (for example zellij) can be added later.

## Assumptions

- The tmux backend requires pi to be running inside tmux (`$TMUX` is set and points at a live server).
- Commands operate on the current backend/session.
- Join/break state is kept in extension memory keyed by the current pi pane id. After `/reload` or a pi restart, bare `/t break` may not know what to break; use `/t break <pane-id>` as the escape hatch.

## Slash commands

```text
/t s|spawn <command>        Create a new tmux window and run <command>.
/t w|watch <command>        Run <command>; joins it below pi; focus stays in pi.
/t j|join <target>          Join a single-pane target window below pi.
/t m|monitor <target>       Same as join but keep focus in the pi pane.
/t k|kill [target]          Stop the process and remove the joined pane, or target window if given.
/t b|break [pane-id]        Break the joined pane back into its own window.
/t l|list                   List windows and known spawned/watched panes.
/t t|tail [target] [lines]  Capture recent pane output.
```

Targets may be tmux pane ids (`%12`), window ids (`@7`), window indices (`2`), or window names where unambiguous.

## Examples

```text
/t s pi
/t spawn pi --model claude-sonnet --thinking high
/t w bb watch:all
/t watch bb watch:kaocha
/t l
/t t %12 200
/t j 2
/t m 2
/t k 2
/t b
```

Long and short subcommand forms are equivalent (`s`/`spawn`, `w`/`watch`, `j`/`join`, `m`/`monitor`, `k`/`kill`, `b`/`break`, `l`/`list`, `t`/`tail`). `/t spawn` and `/t watch` treat everything after the subcommand as one shell command line. Quoting expectations match pi's `!` bash surface.

New windows are named from the first 20 characters of the command (`bb watch:kaocha`, `pi --model claude-…`) and tmux `automatic-rename` is disabled for those windows.

## Watch lifecycle

`/t w <command>` (alias `/t watch <command>`) is for processes that should not block the current pi turn, such as file watchers, long test loops, local servers, or streaming logs.

The wrapper behavior is:

1. The watched command runs as a child process.
2. First Ctrl-C in the watch window is caught by the wrapper and forwarded to the child command.
3. The wrapper prints the exit status and waits in a sleep loop.
4. Second Ctrl-C closes the watch window.

This avoids a fragile `read`/Enter prompt while still leaving output visible after completion or interruption.

## Pane output tools

The extension registers two agent tools:

- `tty_list` — lists current-session windows/panes plus panes created by `/t spawn` and `/t watch`.
- `tty_capture` — captures recent output via backend capture (tmux `capture-pane` today).

`tty_capture` accepts:

- `target`: optional pane/window target. If omitted, the tool captures the latest watched pane for the current pi pane when one exists.
- `lines`: number of scrollback lines, default 200, max 5000.
- `preserveEscapes`: preserve ANSI escapes/colors, default false.

The tmux backend can only return the pane's current buffer plus tmux scrollback. It cannot recover output that has already fallen out of scrollback.

## Killing panes

`/t kill` with no argument sends two Ctrl-C signals to the currently joined/monitored pane (first to stop the process, second to close the wrapper) then kills the pane. With an argument, numeric targets are window indices; the command sends the same Ctrl-C sequence to the target and removes the target window — window indices (`2`), window ids (`@7`), or window names. Cleanup tolerates already-closed panes/windows.

## Joining panes

`/t join <target>` joins a **single-pane** target window below the current pi pane. Multi-pane targets are refused initially because moving only one active pane out of a multi-pane window is surprising. After joining, focus moves to the joined pane.

`/t monitor <target>` does the same thing but keeps focus in the pi pane.

`/t w` / `/t watch` also joins the newly created window below pi with focus in pi. Re-running `/t w`, `/t m` or `/t j …` first breaks any joined pane recorded for the current pi pane, then joins the new target.

`/t break` uses in-memory state and keeps focus in the pi pane. `/t break <pane-id>` works as an explicit escape hatch after reload/restart or state loss.

## Sibling pi / subagent pattern

Use `/t s pi …` (alias `/t spawn pi …`) to launch a sibling pi instance in the same repository directory:

```text
/t s pi
/t s pi --model openai/gpt-4o
/t spawn pi --model sonnet:high "Review the tty extension implementation"
/t spawn pi --append-system-prompt @prompt.md
```

Relevant pi flags verified with `pi --help`:

- `--model <pattern>` selects a model, including `provider/model` and `model:thinking` shorthand.
- `--provider <name>` selects a provider.
- `--thinking <off|minimal|low|medium|high|xhigh>` sets thinking level.
- `--system-prompt <text>` replaces the system prompt.
- `--append-system-prompt <text>` appends text or file contents.
- trailing positional messages become the initial prompt.

Use the `pi-intercom` skill/tooling to communicate with spawned pi instances once they are running.

## Skill

The companion skill lives at `skills/pi-tty/SKILL.md` and is registered by this extension through `resources_discover`. It teaches agents when to use `/t w`, `tty_capture`, and `/t s pi …` instead of blocking the current session.

## Manual validation

See `test.sh` for unit checks and the manual tty/tmux-backend smoke checklist.

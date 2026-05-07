# bb-tasks

A [pi-coding-agent](https://github.com/nichochar/pi-coding-agent) extension
that integrates [Babashka](https://babashka.org/) task running into pi.

## Features

- **Auto-detection** — activates only when `bb.edn` exists in the project root.
- **Task discovery** — runs `bb tasks` on startup to enumerate available tasks.
- **`/bb` command** — slash command with auto-completion for all discovered tasks.
- **Watch sessions** — tasks whose name starts with `watch` are launched in
  their own dedicated `term` session (via `term:spawn`). Other tasks run in the
  currently active `term` session (via `term:run`).

## Usage

```
/bb              — list all available tasks
/bb clean        — run the "clean" task in the shell
/bb watch-tests  — run in its own dedicated term session
```

## Requirements

- `bb` (babashka) must be on `$PATH`.
- A `bb.edn` file must exist in the project root.
- The `term` extension must be loaded (provides `term:run` / `term:spawn` and
  manages the tmux-backed sessions used by this extension).

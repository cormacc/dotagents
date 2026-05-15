# bb-tasks

A [pi-coding-agent](https://github.com/nichochar/pi-coding-agent) extension
that integrates [Babashka](https://babashka.org/) task running into pi.

## Features

- **Auto-detection** — activates only when `bb.edn` exists in the project root.
- **Task discovery** — runs `bb tasks` on startup to enumerate available tasks.
- **`/bb` command** — slash command with auto-completion for all discovered tasks.
- **Simple execution** — tasks run through pi's built-in bash tool, like any
  other shell command.

## Usage

```
/bb              — list all available tasks
/bb clean        — run the "clean" task with bash
/bb watch-tests  — run the watcher with bash
```

## Requirements

- `bb` (babashka) must be on `$PATH`.
- A `bb.edn` file must exist in the project root.
- pi's built-in `bash` tool must be available.

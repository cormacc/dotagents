# pi ext-dev extension

Development helper for loading a bounded source snapshot of another pi extension into the current session.

## Command

- `/ext <name>` loads production source using per-file and aggregate size limits.
- `/ext <name> --include-tests` includes test files.
- `/ext <name> --files path[,path]` loads an explicit file selection.
- Additional text after the options becomes the instruction sent to the agent.

`/ext` deliberately sends its source snapshot as a model-visible custom message, so the following turn can work from it. Use a custom entry instead for transcript-only display that must stay out of model context.

The extension has no external runtime dependencies or default keybindings. It uses Pi-hosted `@earendil-works/pi-coding-agent` and `@earendil-works/pi-tui` APIs.

## Compatibility review

Last review: 2026-10-29, against pi 1.4.0.

- No external dependency.
- Checks: `index.test.ts`, type-check against the pi 1.4.0 declarations, and `pi --mode rpc` load with empty stderr and `/ext` registered.

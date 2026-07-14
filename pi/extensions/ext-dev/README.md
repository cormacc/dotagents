# pi ext-dev extension

Development helper for loading a bounded source snapshot of another pi extension into the current session.

## Command

- `/ext <name>` loads production source using per-file and aggregate size limits.
- `/ext <name> --include-tests` includes test files.
- `/ext <name> --files path[,path]` loads an explicit file selection.
- Additional text after the options becomes the instruction sent to the agent.

The extension has no external runtime dependencies or default keybindings.

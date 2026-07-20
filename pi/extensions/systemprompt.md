# pi systemprompt extension

Displays the current system prompt without adding it to model context.

## Command

- `/systemprompt` shows the complete current system prompt.

## Mode behavior

- **TUI:** appends a persisted, custom-rendered transcript entry. Custom entries are not sent to the model.
- **RPC:** emits the complete prompt through the RPC extension-UI `notify` output. It does not append a session entry or context message.
- **JSON and print:** transcript rendering and RPC UI output are unavailable, so the command exits without output or a session/context entry.

The extension has no external runtime dependencies or default keybindings.

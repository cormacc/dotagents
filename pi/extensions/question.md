# Question Extension

Registers a `question` tool the LLM can call to ask the user for input: a
list of pre-defined options plus an always-appended free-text "Type
something…" entry.

## Tool

- `question(question, options[])` — shows the options in a custom
  full-screen selector. `↑`/`↓` navigate, `Enter` selects (or opens the
  inline free-text editor for "Type something…"), `Esc` cancels (or, from
  the editor, returns to the option list).

### Mode contract

- **TUI only.** The tool opens its selector via `ctx.ui.custom()`, which
  only the TUI implements. `execute()` checks `ctx.mode !== "tui"` before
  calling `ctx.ui.custom()` and returns
  `"Error: UI not available (running in non-interactive mode)"` for every
  other mode (`rpc`, `json`, `print`). `ctx.hasUI` is *not* used for this
  check: it is also `true` in RPC mode, where `ctx.ui.custom()` resolves to
  `undefined` immediately instead of showing a dialog — using `hasUI` here
  would misreport that false "no dialog" result as a user cancellation.
- **Sequential execution.** The tool declares `executionMode: "sequential"`
  so multiple `question` calls in one assistant turn run one at a time
  instead of racing multiple full-screen selectors for the same terminal.

## Dependencies

`@earendil-works/pi-coding-agent`, `@earendil-works/pi-tui`, and `typebox`.

## Tests

- `test/question.test.ts` — unit tests: `executionMode` registration, mode
  gating for every non-TUI mode (asserting `ctx.ui.custom()` is never
  called), and interactive smoke tests that drive the actual
  `render`/`handleInput` component returned to `ctx.ui.custom()` (option
  selection, free-text entry, escape-to-cancel) without a real terminal.
- `test/question.pi-integration.test.ts` — RPC harness test proving the tool
  reports the unsupported-mode error rather than a false cancellation when
  run under real `pi --mode rpc`. RPC is intentionally not used to assert
  anything about TUI rendering.

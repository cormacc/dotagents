# vim-mode — agent notes

## Scope

This extension owns *only* the modal editor. Leader-menu chord
discovery and dispatch live in the sibling `leader-menu` extension.
Do not add leader-menu code, default chord configuration, or
contribution-API logic here — those belong in `leader-menu`.

## Editing rules

- The `VimEditor` class is intentionally large (~2200 lines) and
  monolithic today. A planned follow-up decomposes it into
  `index.ts` + `buffer.ts` + `core.ts` mirroring the upstream
  `vim-motions-pi` shape; do not preemptively start that decomposition
  in unrelated PRs.
- `Mode`, `Pos`, `Range`, `EditorState`, and `Recordable` types are
  duplicated from pi-tui's `Editor` internal shape. If pi-tui exposes
  these officially in a future version, switch to importing them.
- The normal-mode reserved-key set lives at the top of `index.ts`
  as `VIM_NORMAL_RESERVED_KEYS`. This is the *only* copy in the
  repo — leader-menu deliberately does not duplicate it. The set is
  used by the `leader-menu:keys-resolved` subscriber to warn when a
  user-configured leader trigger clashes with the grammar. If you
  add new normal-mode keys, update this set so the clash warning
  stays accurate.
- `VimEditor` MUST extend `SubmitterEditor` (from `../lib/editor.js`)
  rather than `CustomEditor` directly. This is what keeps single-step
  `command:` dispatch working when vim-mode is loaded alongside
  leader-menu — vim-mode's `setEditorComponent()` runs after
  leader-menu's, replaces the registered editor instance, and
  `getActiveSubmitter()` resolves the live `VimEditor`. Reverting to
  `CustomEditor` clobbers single-step submission silently.
- Do not re-introduce a vim-mode-local `submitCommand` method; it
  lives on the shared base and re-implementing it here violates the
  trivially-stateless property of `SubmitterEditor` (see the module
  docstring in `extensions/lib/editor.ts`).

## Cross-extension contract

The dependency edge points one way: vim-mode depends on leader-menu;
leader-menu has no awareness of vim-mode. Maintain that asymmetry.

- Bare leader keys in Normal mode emit `leader-menu:open` with
  `{ rootKey: string }`. The set of recognised leader keys is
  delivered via the `leader-menu:keys-resolved` event — this
  extension does not read the leader-menu settings file directly.
- The `leader-menu:keys-resolved` payload also carries
  `userConfigured: { globalLeader, localLeader }`. Use it to scope
  the grammar-clash warning to user-set keys; warning on the shipped
  default would produce noise every session. The warning logic
  lives in `maybeWarnGrammarClashes()` and is invoked both from the
  event subscriber and from `session_start` to handle either load
  order (the event may arrive before our ctx is set).
- Do not import `leader-menu`; events are the entire runtime
  contract. vim-mode imports `SubmitterEditor` from `../lib/editor.js`,
  which is shared third-party code, not a sibling-extension import.
- vim-mode does not re-implement slash-command submission; the
  `submitCommand(text)` seam is owned by leader-menu via the shared
  `SubmitterEditor` base. vim-mode's only obligation is to keep
  `VimEditor` a `SubmitterEditor` subclass so the active-instance
  registry resolves to the live editor regardless of load order.
- `editor:width-constraint` is the standard pi cross-extension event
  used by side-panel-rendering extensions (e.g. `git-diff`).

## Settings

This extension has no settings file of its own. The only knob is the
shared `debug` flag (per-keypress logger), which lives in
`~/.pi/agent/leader-menu.json`. It is loaded synchronously on editor
construction via `loadDebugFlag()` near the entry point. Do not
resurrect a vim-mode-specific settings file; if you need a new knob,
add it to `leader-menu.json` and document the cross-extension shared
ownership in both READMEs.

The pre-split `~/.pi/agent/vim-mode.json` and `keybindings-ext.json`
files are migrated automatically by `leader-menu` on first run. The
migration is a one-shot copy of the `debug` field; the legacy `modal`
field is intentionally dropped (vim-mode is always on when loaded).

## Tests

This extension has no unit tests today. Sanity-check after edits by:

1. Confirm the editor is installed at session_start (status line
   shows `INSERT` / `NORMAL`, not pi's default editor footer).
2. Pressing `Esc` to enter Normal mode, then exercising motions
   (`hjkl`, `w`, `b`, `e`), operators (`dw`, `ciw`), text objects
   (`di(`, `da{`), counts (`5x`, `3w`), and dot-repeat (`.`).
3. Pressing the configured global leader (default `Space`) in Normal
   mode and confirming the leader-menu overlay opens (delegated via
   `leader-menu:open` event).

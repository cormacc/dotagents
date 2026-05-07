# leader-menu — agent notes

## Scope

This extension owns leader-menu discovery, dispatch, and slash-command
*submission* (via the shared `SubmitterEditor` base in
`extensions/lib/editor.ts`). Modal editing is out of scope — modal
editors live in their own extensions and own their own lifecycle.
Do not name a specific modal editor in this extension's code or
docs; the contract is *consumer-facing* and any modal editor (or
none) should be a valid sibling. Shared key-dispatch settings
(currently leader triggers + `debug`) live in `leader-menu.json`.

The “leader menu first, editor replacement second” default from
`design/log/2026-05-01-keybindings-editor-composition.org` is
*relaxed* for this extension only: leader-menu installs an editor
(`SubmitterEditor`) at `session_start` because the base is trivially
stateless — no fields, no overrides beyond the constructor and
`submitCommand` — so the state-skew hazard the prior plan rejected
does not apply. The rest of that decision still stands: no invisible
composition of editor instances, no modal grammar in this extension,
use `getEditorComponent()` for detection only.

## Editing rules

- `defaults.json` is git-tracked and represents the project-level
  default *menu structure* (no trigger keys). Do not write user state
  to it.
- The two-slot model (`globalMenu` / `localMenu`) is intentional. Do
  not add a third top-level slot or accept raw trigger keys from
  contributors; that would defeat the configurable-leader design and
  re-introduce the surface area the keybindings-extension split was
  meant to clean up.
- User-side trigger keys are resolved at `session_start` from
  `~/.pi/agent/leader-menu.json`. The resolved keys are emitted via
  `leader-menu:keys-resolved` (with the `userConfigured` flag map)
  for any subscriber to consume. Treat this event as part of the
  public cross-extension contract.
- Do not re-introduce a duplicated grammar/reserved-key list here
  to warn about leader/grammar clashes. That detection belongs in
  whichever extension owns the grammar — it knows its own keys, can
  use the `userConfigured` flag to scope warnings to user choices,
  and keeps this extension agnostic about which (if any) modal
  editors are loaded.
- The contribution API (`leader-menu:register` event +
  `registerLeaderMenu()` helper) is consumed by every other extension
  in the repo. Treat its payload shape (`LeaderMenuRegistration`) as a
  stable interface and rev it only with a coordinated migration.

## Cross-extension contract

This extension exposes three integration surfaces. None of them name
a specific consumer extension; keep it that way.

- **`leader-menu:keys-resolved`** publishes
  `{ globalLeader, localLeader, userConfigured }` after settings
  resolution. Consumers (alternate dispatchers, modal editors)
  subscribe to learn the resolved keys without reading
  `leader-menu.json` directly. The `userConfigured` flags scope
  optional clash warnings to user choices.
- **`leader-menu:open`** is the inverse path: any extension that
  catches a bare configured leader in its own grammar can emit this
  event with the rootKey to delegate to the standard centered
  overlay.
- **`SubmitterEditor`** (in `extensions/lib/editor.ts`) is the shared
  one-step slash-command submission seam. leader-menu installs it at
  `session_start`. Any other extension that calls
  `setEditorComponent()` MUST extend `SubmitterEditor` (not
  `CustomEditor` directly) or single-step `command:` dispatch
  regresses to the legacy two-step "insert + press Enter" UX.
  `getActiveSubmitter()` resolves to the most-recently-installed
  instance, so load order does not matter as long as everyone honours
  the contract.

leader-menu must not import from any consumer extension. The shared
base lives in `extensions/lib/`, which is treated as a third-party
shared directory, not a sibling import.

## Tests

This extension has no unit tests today. Sanity-check after edits by:

1. Running `pi` in a session with at least one consumer extension
   loaded (`tasks`, `term`, or `git-diff`).
2. Pressing `ctrl+space` and confirming the overlay shows both
   `defaults.json` entries and the consumer's contributed entries.
3. Running `/leader-menu bindings --export` and verifying the
   org-format output matches the loaded chord tree.

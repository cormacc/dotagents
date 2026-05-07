# Changelog


## Source

Copied from https://github.com/markokocic/pi-clojure as package installation
failed on NixOS.

Last synced with upstream 18/04/2026.

## Changes

The following changes were suggested by Opus 4.6 while integrating with an existing
cljs-nrepl skill.

### Bug Fixes

- **`clojure_eval`: namespace parameter was silently ignored**
  When the `ns` parameter was supplied, `evalExpr` sent `op: "ns"` — an
  operation that does not exist in the nREPL protocol. The server responded with
  `unknown-op / done`, causing the tool to return empty results without ever
  evaluating the code. Fixed by passing `ns` as a field on the standard `eval`
  op instead: `{ op: "eval", code, session, ns, id }`.

- **`clojure_paren_repair`: false negatives for quoted forms**
  `detectImbalance` treated `'` (Clojure's quote reader macro) as a paired
  delimiter, toggling an `inChar` flag that suppressed bracket counting for
  everything that followed. Any unbalanced form beginning with `'` — e.g.
  `'(foo bar` — was incorrectly reported as balanced, causing the tool to return
  "Code is already balanced" instead of fixing it. Removed the `inChar` logic
  entirely; `'` is not a delimiter in Clojure.

- **`nrepl-client`: bencode parser didn't handle nested structures**
  The original `findMessageEnd` walked a flat bencode dictionary but had no
  handler for nested dict values (`0x64`), returning `-1` on any message
  containing them. Replaced with a recursive `findValueEnd` that correctly
  handles all bencode types (integers, strings, lists, dicts) at any nesting
  depth.

- **`nrepl-client`: decoding against full buffer instead of message slice**
  `decodeMessage` was called on the entire remaining buffer rather than the
  exact bytes of the current message. Now called as
  `decodeMessage(remaining.subarray(0, endIdx))`.

### Improvements

- **Eval timeout** (`clojure_eval`, `clojure_find_nrepl_port`)
  `evalExpr` previously had no timeout — a hung or slow nREPL would block
  indefinitely. Added a `timeout` field to `EvalOptions` (default: 30 000 ms).
  The `clojure_eval` tool exposes this as an optional parameter. Port validation
  in `clojure_find_nrepl_port` uses a fixed 5 000 ms timeout so discovery
  doesn't stall on unresponsive ports.

- **Bounded socket buffer**
  The data handler accumulated all received bytes in a growing buffer without
  ever trimming consumed content. After each pass through the message loop,
  consumed bytes are now discarded: `buffer = buffer.subarray(offset)`.

### Type Fixes

- `NreplMessage["new-session"]` was typed as `boolean`; corrected to `string`
  (the nREPL clone response returns a session UUID string).

### Code Cleanup

- Removed dead default exports from `eval.ts`, `find-nrepl-port.ts`, and
  `paren-repair.ts`. Each file exported a `default function (pi) { pi.registerTool(...) }`
  that was never called — registration is handled solely by `index.ts`.
- Removed unused `type ExtensionAPI` imports from the three tool files above.
- Removed the unused `findNreplPorts` export from `nrepl-client.ts`.
- `session` variable in `evalExpr` is now block-local to the clone-response
  handler rather than a closure-level `let`.

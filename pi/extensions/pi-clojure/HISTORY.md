# History

## Import history

This extension was originally copied from
[`markokocic/pi-clojure`](https://github.com/markokocic/pi-clojure) when npm
package installation failed on NixOS. The last upstream import was 2026-04-18.
It is now maintained locally in this repository; this file is history, not an
upstream-sync ledger. Original authorship and EPL-2.0 licensing remain in
[LICENSE](LICENSE).

## Locally shipped changes

### nREPL correctness and transport

- Forwarded the optional namespace as a field on the standard nREPL `eval`
  operation rather than issuing an invalid `ns` operation.
- Fixed bencode framing for nested structures and chunked/multiple responses.
- Starts the evaluation timeout before TCP connection establishment, accepts
  pi's `AbortSignal`, and settles success, error, timeout, abort, and socket
  close through one cleanup path.
- Bounds nREPL discovery probes and stops discovery when cancelled.

### Tool results and repair

- Bounds eval and paren-repair output with pi's standard 2000-line/50KB limits;
  unbounded nREPL values are never retained in tool details.
- Fixed quoted-form delimiter detection: Clojure's `'` reader macro is not a
  paired delimiter.
- Validates socket ports and timer values with bounded integer schemas.

### Current pi integration

- Uses pi's published `@earendil-works/pi-coding-agent` declarations and
  TypeBox 1.x instead of a locally redeclared coding-agent API.
- Retains local declarations only for runtime dependencies that do not publish
  TypeScript declarations (`bencode` and `parinfer`).

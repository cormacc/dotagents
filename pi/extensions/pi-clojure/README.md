# pi-clojure

`pi-clojure` is the maintained native Clojure extension in this repository. It
connects directly to an existing nREPL over TCP and also provides string-level
paren repair. It is loaded from the dotagents/pi extension configuration; this
repository, not an npm package, is the maintained source.

## Tools

| Tool | Description |
| --- | --- |
| `clojure_find_nrepl_port` | Finds an nREPL port from project port files or common defaults. Each probe has a five-second budget. |
| `clojure_eval` | Evaluates Clojure code in a persistent session on an existing nREPL server. |
| `clojure_paren_repair` | Checks or repairs delimiters in a Clojure-family source string with parinfer. |

There are no slash commands or default keybindings.

### `clojure_eval`

Requires a running nREPL. Use `clojure_find_nrepl_port` first, or start one
manually (for example, `bb nrepl`, `lein repl :headless`, or `clj -M:repl`).

| Parameter | Description |
| --- | --- |
| `code` | Clojure expression to evaluate. |
| `port` | TCP port from 1 through 65535. |
| `host` | nREPL host (default: `localhost`). |
| `ns` | Optional namespace for this evaluation only, forwarded on the nREPL `eval` request. The session's current namespace does not change. |
| `reset` | Close the persistent session for this host and port, and evaluate in a new one. |
| `timeout` | Optional 1–2147483647 ms budget (default: 30000), covering connection and response processing. |

The extension clones one nREPL session for each host and port and keeps it
for the pi session. Session state therefore persists between calls: the
current namespace, `*1`, `*e`, `set!` bindings, and a shadow-cljs CLJS REPL
selection (shadow-cljs keeps its REPL state in the nREPL session). Each call
opens its own connection, because a session outlives its connection. Calls
for one server run one at a time, in call order. `session_shutdown` closes
the stored sessions on their servers.

- If the server no longer knows the session, for example after a restart,
  the call clones a new session and evaluates again. The result then starts
  with a note that earlier session state is lost. Result details carry the
  session ID and `sessionStatus` (`new`, `reused`, or `replaced`).
- Timeout or pi cancellation interrupts a sent evaluation with the nREPL
  `interrupt` op. The call keeps its connection open until the server answers
  the interrupt, for at most 1 s, and fails after that. JVM nREPL 1.3.1 ignored
  an interrupt when the connection closed directly after it, and lost an eval
  that was queued behind the interrupted one; Babashka 1.13.226 did neither.
- Other callers of `evalExpr` (`clojure_find_nrepl_port` and the `dataspex`
  extension) send one eval without a session. The server evaluates it in a
  throwaway session, so no session remains afterwards.

Results are limited to pi's standard 2000 lines or 50KB before they enter
model context or result details.

### `clojure_find_nrepl_port`

Searches `.nrepl-port`, `nrepl-port`, `.shadow-cljs/nrepl.port`, and
`.cider-nrepl.port`, then probes 7888, 1666, 50505, 58885, 63333, and 7889.
Discovery stops on cancellation. An unresponsive candidate cannot consume more
than the five-second validation budget.

### `clojure_paren_repair`

Accepts `code` and an optional `check` flag. It repairs strings only; it does
not edit files. Repaired output uses the same
2000-line/50KB bounds as evaluation output.

## Development

```bash
cd pi/extensions/pi-clojure
./test.sh
```

Runtime dependencies are `bencode` and `parinfer`. Pi's coding-agent API and
TypeBox are host-bundled peer dependencies (with exact development copies for
typechecking), so the Home Manager production install (`npm install --omit=dev`)
does not bundle a second pi runtime. This was verified with isolated pi 0.80.10:
all three tools registered after a production-only install.

## History and license

The extension was originally imported from
[`markokocic/pi-clojure`](https://github.com/markokocic/pi-clojure). Original
authorship and the EPL-2.0 license are preserved in [LICENSE](LICENSE).
[HISTORY.md](HISTORY.md) records that import and locally shipped changes.

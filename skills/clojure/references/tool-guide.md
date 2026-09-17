# Clojure evaluation tools

## CLI evaluation

When `clojure_eval` is unavailable or a persistent session is needed, use an installed `clj-nrepl-eval`:

```sh
clj-nrepl-eval --discover-ports
clj-nrepl-eval -p PORT '(+ 1 2 3)'
```

Discovery probes servers and groups them by project directory; select the target project's server. Native discovery checks reachability but does not verify project ownership.

The CLI retains a session per host and port. `--connected-ports` lists active stored connections; `--reset-session` resets the stored session. Native `clojure_eval` instead clones a fresh session for each call: pass `ns` explicitly when needed, and do not expect session bindings or CLJS selection to persist. Definitions in server namespaces can still survive between calls.

## Shadow-cljs

Use the project's running watch build and attached JS runtime. Replace `PORT` and `:app` below with the project's port and build ID. In a persistent CLI session, select CLJS before sending CLJS forms:

```sh
clj-nrepl-eval -p PORT '(require (quote shadow.cljs.devtools.api)) (shadow.cljs.devtools.api/nrepl-select :app)'
clj-nrepl-eval -p PORT '(+ 1 2 3)'
```

Evaluate `:cljs/quit` in that session to return to CLJ.

# Clojure evaluation tools

## CLI evaluation

When `clojure_eval` is unavailable or a persistent session is needed, use an installed `clj-nrepl-eval`:

```sh
clj-nrepl-eval --discover-ports
clj-nrepl-eval -p PORT '(+ 1 2 3)'
```

Discovery probes servers and groups them by project directory; select the target project's server. Native discovery checks reachability but does not verify project ownership.

The CLI retains a session per host and port. `--connected-ports` lists active stored connections; `--reset-session` resets the stored session. Native `clojure_eval` instead clones a fresh session for each call: pass `ns` explicitly when needed, and do not expect session bindings or CLJS selection to persist. Definitions in server namespaces can still survive between calls.

## Source inspection in Babashka

`clojure.repl/source` and `clojure.repl/source-fn` can fail to retrieve source for namespaces Babashka implements internally. A missing result is not evidence that the var is absent: first confirm it resolves and inspect its metadata, then consult the matching [Babashka implementation](https://github.com/babashka/babashka) rather than repeating source lookup. On Babashka 1.13.223, `source-fn` returned source for `clojure.core/map` but `nil` for the present `clojure.test/is` var.

See [runtime and testing cautions](runtime-testing.md) for interop, assertions and subprocess-isolation pitfalls.

## Shadow-cljs

Use the project's running watch build and attached JS runtime. Replace `PORT` and `:app` below with the project's port and build ID. In a persistent CLI session, select CLJS before sending CLJS forms:

```sh
clj-nrepl-eval -p PORT '(require (quote shadow.cljs.devtools.api)) (shadow.cljs.devtools.api/nrepl-select :app)'
clj-nrepl-eval -p PORT '(+ 1 2 3)'
```

Evaluate `:cljs/quit` in that session to return to CLJ.

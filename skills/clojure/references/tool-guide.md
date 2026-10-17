# Clojure evaluation tools

## Native evaluation

`clojure_eval` keeps one nREPL session for each host and port, for the life of the pi session. Session state persists between calls: the current namespace, `*1`, `*e`, `set!` bindings, and a CLJS REPL selection.

- The `ns` parameter applies to one evaluation only. Use `(in-ns 'my.ns)` to change the session's current namespace.
- Pass `reset: true` to close the session and evaluate in a new one.
- If the server lost the session, for example after a restart, the tool starts a new session and says so in the result. Earlier session state is then gone.
- A timeout or cancellation interrupts the evaluation, and the session stays usable.
- The session belongs to the agent, not to the user's editor REPL. Namespaces and vars are global to the server, so loading or redefining code still changes the user's runtime.

## CLI evaluation

In a harness without `clojure_eval`, use an installed `clj-nrepl-eval`:

```sh
clj-nrepl-eval --discover-ports
clj-nrepl-eval -p PORT '(+ 1 2 3)'
```

Discovery probes servers and groups them by project directory; select the target project's server. Native discovery checks reachability but does not verify project ownership.

The CLI also retains a session per host and port. `--connected-ports` lists active stored connections; `--reset-session` resets the stored session.

## Source inspection in Babashka

`clojure.repl/source` and `clojure.repl/source-fn` can fail to retrieve source for namespaces Babashka implements internally. A missing result is not evidence that the var is absent: first confirm it resolves and inspect its metadata, then consult the matching [Babashka implementation](https://github.com/babashka/babashka) rather than repeating source lookup. On Babashka 1.13.223, `source-fn` returned source for `clojure.core/map` but `nil` for the present `clojure.test/is` var.

See [runtime and testing cautions](runtime-testing.md) for interop, assertions and subprocess-isolation pitfalls.

## Shadow-cljs

Use the project's running watch build and attached JS runtime. Replace `:app` below with the project's build ID. Select CLJS in the session before you send CLJS forms. With `clojure_eval`, send these as two calls on the same port:

```clojure
(require 'shadow.cljs.devtools.api) (shadow.cljs.devtools.api/nrepl-select :app)
(+ 1 2 3)
```

With the CLI, send the same forms with `clj-nrepl-eval -p PORT`. Evaluate `:cljs/quit` in that session to return to CLJ.

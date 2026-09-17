---
name: clojure
description: Clojure, ClojureScript, EDN, and Babashka development with runtime validation. Use for .clj/.cljs/.cljc/.edn/.bb files, deps.edn, project.clj, bb.edn, shadow-cljs, lein, nREPL, or clojure-lsp / clj-kondo workflows. Covers reading, editing, debugging, and testing.
---

# Clojure development

Follow the project's `AGENTS.md`, commands, and conventions. Use the local [style digest](references/idioms.md) when the project does not specify a style; no web lookup is needed.

## Workflow

1. Read the target code, namespace dependencies, and callers. Use `lsp` for symbol definitions and references.
2. Prefer an existing REPL verified to belong to the target project. Use `clojure_find_nrepl_port` and `clojure_eval` when available; see the [tool guide](references/tool-guide.md) for CLI fallback and session handling. A port file or occupied port alone does not identify a live project REPL.
3. If no suitable REPL is available, use the project's test command or a focused Babashka check. Start a server only when needed, using the project's startup instructions.
4. Validate changed behaviour in the intended runtime, including relevant edge cases. Check unfamiliar Java interop in that runtime: JVM support does not imply Babashka support.
5. After saving, reload changed namespaces or confirm the watch build has loaded them. Run the relevant tests against the saved code.

`require` (including `:reload`), `load-file`, and `bb -e` execute code. Inspect top-level effects before loading; use a non-evaluating reader or the project's static checker when only a syntax check is intended. Parse EDN as data rather than evaluating it.

For JVM/Babashka tests, use `clojure.test/run-test-var` or `clojure.test/run-tests`, not direct test-function calls, so fixtures run. Use the project's CLJS test runner for CLJS. Check test failures and errors, not just whether evaluation returned. In cross-platform regex tests, assert matching and non-matching inputs rather than regex equality or printed forms.

## ClojureScript

Confirm the selected build and attached JS runtime before evaluating CLJS. Opening a browser tab alone does not switch a CLJ nREPL session to CLJS. Native `clojure_eval` calls use fresh sessions; use the persistent CLI or the project's evaluator for session-based CLJS work. See the [tool guide](references/tool-guide.md) for a shadow-cljs example.

## Delimiter repair

Correct small delimiter errors directly. If an available repair tool helps, inspect its changes and rerun normal verification. Repair is not syntax validation; the native `clojure_paren_repair` check can miss mismatched delimiter types and misread character literals. For file repair, work on a copy under `<repository-root>/.tmp/`: `clj-paren-repair COPY.clj` repairs and formats the whole copy in place. Copy back only the intended changes.

## Babashka caveats

- Run tasks from the project's documented task directory. `-cp` replaces configured `:paths`; include all roots needed by a focused run.
- In bare scripts without an `ns` form, names such as `source`, `doc`, and `dir` are already referred from `clojure.repl`; choose distinct names.
- Convert `babashka.fs` Path values to strings before passing them to `slurp` or `spit`.
- A `$HOME` override does not necessarily change `user.home`. Isolate tests through the path source the code actually uses.
- For `babashka.fs/glob` walks that must traverse directory symlinks, use `{:follow-links true}` and test with an actual symlink.

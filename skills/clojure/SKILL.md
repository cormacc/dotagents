---
name: clojure
description: Clojure, ClojureScript, EDN, and Babashka development with runtime validation. Use for .clj/.cljs/.cljc/.edn/.bb files, deps.edn, project.clj, bb.edn, shadow-cljs, lein, nREPL, or clojure-lsp / clj-kondo workflows. Covers reading, editing, debugging, and testing.
---

# Clojure development

Follow the project's `AGENTS.md`, commands, and conventions. Use the local [style digest](references/idioms.md) when the project does not specify a style; no web lookup is needed.

For error handling, JVM/CLJS portability, Java interop or test design, read the [runtime and testing cautions](references/runtime-testing.md). They preserve concrete failure modes and local findings without expanding the core workflow.

When you review or simplify Clojure code, read the [review reference](references/review.md) for language-specific defect hazards, readability items, and simplification candidates.

## Workflow

1. Read the target code, namespace dependencies, and callers. Use `lsp` for symbol definitions and references.
2. Prefer an existing REPL verified to belong to the target project. Use `clojure_find_nrepl_port` and `clojure_eval` when available; see the [tool guide](references/tool-guide.md) for CLI fallback and session handling. A port file or occupied port alone does not identify a live project REPL.
3. If no suitable REPL is available, use the project's test command or a focused Babashka check. Start a server only when needed, using the project's startup instructions.
4. Validate changed behaviour in the intended runtime, including relevant edge cases. Check unfamiliar Java interop in that runtime: JVM support does not imply Babashka support.
5. After saving, reload changed namespaces or confirm the watch build has loaded them. Run the relevant tests against the saved code.

`require` (including `:reload`), `load-file`, and `bb -e` execute code. Inspect top-level effects before loading; use a non-evaluating reader or the project's static checker when only a syntax check is intended. Parse EDN as data rather than evaluating it.

For JVM/Babashka tests, use `clojure.test/run-test-var` or `clojure.test/run-tests`, not direct test-function calls, so fixtures run. Use the project's CLJS test runner for CLJS. Check test failures and errors, not just whether evaluation returned. In cross-platform regex tests, assert matching and non-matching inputs rather than regex equality or printed forms.

## Debugging and discovery

Inspect values in the REPL before you add `println` or log calls to source. The REPL gives the value at the point of failure without an edit-and-rerun cycle.

- To capture a value inside a function, evaluate a copy of the function in the REPL with a temporary `(def dbg-x x)`, call the function, then inspect `dbg-x`. Do not write the capture into the file. Use a capture name that does not resolve in the namespace, because `def` replaces an existing var of the same name.
- When you finish, reload the namespace from the saved file, then remove each capture var with `ns-unmap`. A reload restores the file's definitions but does not remove vars that only the REPL defined.
- A REPL redefinition changes the user's running program (see the [tool guide](references/tool-guide.md)). During a review, do not redefine project code; see the [review reference](references/review.md).
- A running system that holds a function value, such as a handler or a lifecycle component, keeps the old definition after you redefine the var. Restart it with the project's documented reset before you trust the result.
- `tap>` is an alternative to a capture var when the project has a tap consumer. The `dataspex` skill covers inspection of tapped values.
- Test the fix in the REPL, apply it to the file, then reload and run the tests as in [#Workflow](#workflow).

Before you rely on an unfamiliar macro or Java API:

- Expand the macro with `macroexpand-1` (one step) or `macroexpand` (until the head is not a macro).
- List a Java class's members with `clojure.reflect/reflect`. Select methods with `(filter :return-type)`, not `(instance? clojure.reflect.Method %)`: Babashka 1.13.228 returned the same members as the JVM for `java.time.LocalDateTime` but could not resolve the `clojure.reflect.Method` class symbol. A listed member does not prove that Babashka can call it; probe the call as in workflow step 4.
- Before you add a dependency for a Java API, check whether the project already depends on a Clojure wrapper for it.

## ClojureScript

Confirm the selected build and attached JS runtime before evaluating CLJS. Opening a browser tab alone does not switch a CLJ nREPL session to CLJS. `clojure_eval` keeps one session for each host and port, so a CLJS selection persists until `:cljs/quit` or `reset`. See the [tool guide](references/tool-guide.md) for a shadow-cljs example.

## Delimiter repair

Correct small delimiter errors directly. If an available repair tool helps, inspect its changes and rerun normal verification. Repair is not syntax validation; the native `clojure_paren_repair` check can miss mismatched delimiter types and misread character literals. It repairs strings only: apply the repair to the file with an edit.

## Babashka caveats

- Run tasks from the project's documented task directory. `-cp` replaces configured `:paths`; include all roots needed by a focused run.
- In bare scripts without an `ns` form, names such as `source`, `doc`, and `dir` are already referred from `clojure.repl`; choose distinct names.
- Convert `babashka.fs` Path values to strings before passing them to `slurp` or `spit`.
- `bb.edn` is read as EDN, so task code cannot use `#(...)`; write `(fn [x] ...)`.
- A `$HOME` override does not necessarily change `user.home`. Isolate tests through the path source the code actually uses.
- For `babashka.fs/glob` walks that must traverse directory symlinks, use `{:follow-links true}` and test with an actual symlink.

# Runtime and testing cautions

Use this reference for error handling, JVM/CLJS portability, Java interop, test fixtures and Babashka subprocess tests. These are engineering preferences and runtime cautions, not a general language tutorial. Follow project contracts where they differ.

## Structured errors and portable catches

- Prefer `ex-info` with a useful data map for application failures that callers must distinguish. Give `ex-data` stable fields such as the operation, affected field and reason; do not make callers parse prose or expose secrets in error data.
- Catch only failures the current layer can handle. If adding context and rethrowing, preserve the original cause with the three-argument `ex-info`. Do not silently convert an unexpected failure to `nil`.
- JVM structured errors use `clojure.lang.ExceptionInfo`; CLJS uses `cljs.core/ExceptionInfo`. Java exception classes are not portable to CLJS. A CLJS boundary can use `catch :default` for arbitrary thrown values; it should not assume the value is a Java exception or even a JavaScript Error.
- In `.cljc`, a broad platform-appropriate catch uses `#?(:clj Exception :cljs :default)` as the catch type. Keep recovery policy the same on both platforms, and test both branches in their intended runtimes. This is not a reason to catch broadly when a narrower catch suffices.
- Exception tests should check the contract that matters: exception kind and structured data, plus message text only when that text is part of the contract.

The CLJS identifiers and error-data behaviour are defined in [cljs.core](https://github.com/clojure/clojurescript/blob/master/src/main/cljs/cljs/core.cljs).

## Assertions are not control-flow guards

A failed `clojure.test/is` assertion reports failure and allows subsequent forms to run. It does not protect the next operation from a missing prerequisite. If continuing would cause unsafe work or obscure the failure, guard that work explicitly.

The message expression supplied to `is` is evaluated on passing assertions too. Keep it cheap and free of side effects. These behaviours were checked with passing and deliberately failing assertions in Babashka 1.13.223; use the project's CLJS runner when checking CLJS test semantics.

Use fixture-aware runners and check their failure/error totals as directed in the [main skill](../SKILL.md). Do not replace that check with the success of an individual evaluation.

## Shared JVM and browser fixtures

A browser build cannot use Node's `fs` API to read local fixture files. Keep shared fixtures on the classpath and embed small fixtures at compile time for CLJS rather than copying their contents into source.

For shadow-cljs, `shadow.resource/inline` is a macro taking a literal resource path. `shadow.resource/slurp-resource` is a function taking the macro environment and path, not a macro. A shared `.cljc` macro can branch on `(:ns &env)`: call `shadow.resource/slurp-resource` with `&env` during CLJS expansion, and use JVM classpath resource loading otherwise. Keep compiler/JVM-only dependencies out of the emitted JS runtime code.

Inlined content ships in the JS bundle: use small, non-sensitive fixtures and a suitable runtime loading strategy for large resources. Exercise the browser path as well as the JVM path; a Node-only test does not cover browser compatibility.

See the [shadow.resource implementation](https://github.com/thheller/shadow-cljs/blob/master/src/main/shadow/resource.clj). This API distinction was originally source-checked against shadow-cljs 3.4.6 in commit `78ca993` and was rechecked against upstream source when this reference was restored; check the project's pinned version before adopting it.

## JVM reflection and Babashka interop

In JVM namespaces with Java interop, enable `(set! *warn-on-reflection* true)` during compilation. Resolve unexpected warnings with verified receiver/argument types; do not scatter type hints without checking the selected overload. This JVM diagnostic does not establish Babashka compatibility.

Probe the exact interop call, argument types and failure path in the target runtime. Do not infer purity from a method name: the [JDK Path.toUri contract](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/Path.html#toUri()) permits directory-sensitive output and I/O-related failure.

A recorded example is `java.util.Arrays/binarySearch` on a `long-array`: the earlier skill reported `No matching method` on Babashka 1.12.218; a new probe on 1.13.223 raised `MissingReflectionRegistrationError` for the `long[], long` overload. These are version/build observations, not a permanent ban on the API. Verify support before depending on it, and use a supported alternative when necessary.

## Babashka isolation and measurement

The [test-optimisation record](../../../design/log/2026-07-30-optimise-test-build.org) documents two distinct settings in Babashka's dependency resolver: `CLJ_CACHE` selects the classpath cache, while `CLJ_CONFIG` selects user configuration. Changing `HOME` for each subprocess caused repeated cold resolution and configuration bootstrap in the temporary homes; setting only the cache did not solve both effects.

For tests unrelated to dependency configuration, use explicit shared warm cache/configuration directories when that preserves the isolation being tested. For tests of configuration itself, keep configuration isolated and account for its startup cost. Neither arrangement fixes a separate `user.home` lookup; isolate the actual path source the code reads.

Measure per-test timing within a representative namespace or suite run. The same record found that timing the first subprocess-spawning test in a fresh Babashka process included substantial one-time warm-up. Treat its historical timings as evidence for the measurement method, not current performance targets.

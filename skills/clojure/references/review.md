# Clojure review reference

Use this reference when a `code-review` or `simplify` range contains Clojure, ClojureScript, or Babashka code. It adds language-specific candidates. It does not change the owning workflow's bar: a defect hazard or a readability item below is a finding only when it meets `code-review`'s false-positive bar, and a simplification is a finding only with `simplify`'s evidence. Project conventions take precedence.

## Establish facts with tools

- Find callers and dead code with `lsp` references, and with the project's `clj-kondo` configuration when the project uses it. A public var can have callers that static search misses, such as `requiring-resolve`, string-built symbols, or other repositories. Do not report it as dead unless you exclude those callers.
- Derive a map's shape from its callers, specs or schemas, or a sample value in the REPL. Local destructuring shows only the keys that one function reads.
- Probe a disputed behaviour with `clojure_eval` or the project's test runner, on representative input and in the intended runtime. The [main skill](../SKILL.md) gives the REPL and runner rules.
- A review only verifies. In a shared REPL, evaluate expressions, but do not load, reload, or redefine project namespaces: that changes the user's runtime state, and `git` cannot restore it. When a check needs changed code loaded, use a separate process, such as the project's test runner or `bb`.

## Defect hazards (`code-review`)

- **Lazy side effects** -- `map`, `for`, `filter`, or `keep` that perform I/O or mutation. Nothing realises a discarded lazy sequence, so the effect does not run. Realisation outside a `with-open`, `binding`, or transaction scope uses a closed resource or a lost binding. Fix: `run!`, `doseq`, `mapv`, or `doall` inside the scope.
- **Head retention** -- a local holds the head of a large or unbounded lazy sequence while code consumes it, so memory grows with input size. Report only with a concrete large input under supported usage.
- **Atom updates** -- `swap!` can call its function more than once (see the `swap!` docstring), so a side effect in that function can repeat. A `deref` followed by `reset!` is a read-modify-write race when other threads update the atom.
- **Truthiness** -- `if`, `when-let`, `or` defaults, and `some->` treat `false` and `nil` the same. `(when-let [xs (seq coll)] ...)` treats `nil` and empty the same. Report a change that inverts a condition, swaps `if`/`if-not`, or merges cases that callers distinguish.
- **Runtime `def`** -- `def` or `defn` inside a function body redefines a global var on each call. Use `let` or an explicit state holder.
- **Errors** -- apply the [runtime and testing cautions](runtime-testing.md): structured `ex-info`, preserved causes, and no silent conversion of an unexpected failure to `nil`. Expected absence returns `nil` or data. It does not throw.
- **Sensitive data** -- secrets or personal data in log calls, `ex-data`, or `tap>` values.
- **Untrusted reading** -- `clojure.core/read-string` or `read` on untrusted input can execute code (see the `read-string` docstring). Use `clojure.edn/read-string`.
- **Double serialisation** -- code that encodes a value that is already a JSON or EDN string, so the payload becomes a quoted string.
- **Boundary validation** -- external input (HTTP, files, environment, message queues) that reaches domain code without validation, where the project validates its other boundaries with spec, Malli, or similar. Do not ask for repeated validation inside trusted internal code.
- **Reflection** -- see [runtime-testing.md#JVM reflection and Babashka interop](runtime-testing.md#jvm-reflection-and-babashka-interop). Report a warning only on a hot path or where it breaks a target runtime.
- **`with-redefs` in tests** -- it replaces the var's root value, and the docstring states that the change is visible in all threads. Parallel tests and background threads can therefore see the stub. Direct-linked call sites do not see it ([Clojure compilation reference, 'Direct linking'](https://clojure.org/reference/compilation#directlinking)). Prefer an injected dependency when the test must isolate a collaborator.

## Readability, design, and convention (`code-review`)

These apply to changed code, and each needs the specific misreading, later edit, or measured cost that it invites. In a modified function, they can also be cleanup suggestions for existing code (see [../../code-review/SKILL.md#Cleanup in modified code](../../code-review/SKILL.md#cleanup-in-modified-code)). A preference between equally clear alternatives is not a finding.

- **Destructuring** -- a changed function that reads several keys of the same map through repeated keyword or `get` calls, where destructuring would show its inputs in one place ([idioms.md#Local preferences](idioms.md#local-preferences)). Direct access stays clearer for a single key or inside a threading form.
- **Atom-held state** -- simple local coordination in an atom is acceptable. Flag a change that puts application lifecycle (start, stop, connections) or multi-step state transitions into an atom, with `swap!` calls spread across functions or namespaces. Suggest named transition functions or an explicit state model. Name a lifecycle library, such as Integrant, Component, or mount, only when the project already uses it or it clearly fits the existing architecture.
- **Sequence pipelines** -- prefer plain sequence functions and threading for small or ordinary collections. Suggest a transducer (`into` or `transduce` with a transform, `sequence`, or `eduction`) when it materially improves performance, streaming, or allocation: for large or unbounded input, for a long pipeline whose intermediate sequences have a measurable cost, or for a pipeline that must finish inside a resource scope. State the input size or the measurement. A transducer on a small collection, where a threaded pipeline is clearer, is a readability finding.

- **Names** -- a vague name such as `data`, `item`, `id`, or `m` whose role the surrounding code does not make clear, or a name that misstates the value, such as a `?` name that returns a non-boolean.
- **Effect naming** -- a changed function that departs from the project's `!` convention. The [style digest](idioms.md) uses `!` for functions that are unsafe in STM transactions. Some projects use it for every function with side effects.
- **Hidden effects** -- a changed function that reads as a pure transformation but also does I/O or mutation, or that mixes the two where the transformation cannot be tested without the effect.
- **Nesting** -- control flow nested so deeply that a branch is hard to find. Suggest threading, `let` bindings, `cond`, or a named helper.
- **Magic values** -- an unexplained literal with domain meaning. Name it with `def`, or move it into configuration.
- **Docstrings** -- a new or changed public var whose contract (accepted shapes, `nil` handling, effects, failures) is not clear from its name and arguments. Do not ask for docstrings on trivial functions.
- **Namespaced keywords** -- where the project qualifies keywords for shared domain data, a change that adds an unqualified key for that data. Do not ask for qualification in local or throwaway maps.
- **Accessors** -- where the project has accessor functions that enforce domain invariants, a change that reads or writes the underlying map directly.
- **Framework internals** -- logic that depends on generated parameter names, unstable framework internals, or reflection artefacts.
- **Test readability** -- a test that restates implementation steps instead of the behaviour it protects.

## Simplification candidates (`simplify`)

Each candidate needs `simplify`'s equivalence evidence before you report it.

- `yagni` -- a protocol or multimethod with one implementation; a macro where a function is sufficient; a `defrecord` or `deftype` that only carries map data.
- `stdlib` -- a hand-written `loop` or `reduce` that duplicates a core function such as `group-by`, `frequencies`, `keep`, `some`, `into`, `merge-with`, or `partition-by`. Before you name a newer core function such as `update-vals`, confirm that the project's Clojure version supplies it.
- `shrink` -- `(vec (or x []))` where `(vec x)` is sufficient, because `(vec nil)` returns `[]`; an explicit `nil` else branch where `when` is sufficient; nested `let` and `if` where `when-let`, `if-let`, or `some->` is equivalent; repeated blocks in the change that one extraction removes; step-by-step Java object setup where `doto` is equivalent. The `nil`, `false`, and empty cases must keep the same behaviour.
- `delete` -- an unused private var, a redundant arity, or a branch that its own preconditions make unreachable, confirmed as described in [#Establish facts with tools](#establish-facts-with-tools).

A transducer or other change for performance is not a simplification. It belongs to `code-review`; see **Sequence pipelines** above.

## Provenance

The hazard and candidate lists adapt the `clojure-reviewer` subagent of the `clojure-review` plugin in https://github.com/editor-code-assistant/eca-plugins (Apache License 2.0, commit `0defb6d`). This adaptation keeps defect, readability, and simplification items, each under the bar of the workflow that owns it. The plugin's Boy Scout rule is now [../../code-review/SKILL.md#Cleanup in modified code](../../code-review/SKILL.md#cleanup-in-modified-code), limited to the functions and forms that the change modifies. The destructuring preference is also in [idioms.md#Local preferences](idioms.md#local-preferences). It omits these plugin items:

- "Suggest 2-3 critical tests" -- `code-review` already requires tests for changed failure paths.
- Praise for good design, the emoji priority scale, and the "LGTM" reply -- `code-review` sets the output format.
- The `eca__task` step and the book list -- ECA-specific, or not testable.

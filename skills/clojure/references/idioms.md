# Clojure style digest

A selected, paraphrased digest of the [Clojure Style Guide](https://guide.clojure.style/) by Bozhidar Batsov and contributors, licensed under [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/). Checked against guide commit `2bc3d2c` (2026-09-17).

Project conventions and formatter settings take precedence. Do not reformat unrelated code or change behaviour to satisfy a style preference.

## Tools first

- Format changed files with the project's formatter (cljfmt, zprint, or cljstyle) when the project configures one. The formatter, not this digest, decides layout.
- Lint changed files with the project's clj-kondo configuration, or with `clj-kondo --lint <file>` when the project has none. Fix the warnings that the change introduces.
- The default clj-kondo configuration does not report every guide rule. On clj-kondo v2026.08.04 it did not report `(not (empty? x))`, `(if c x nil)`, or `(.toUpperCase s)`, so the rules below still apply.

## Layout and namespaces

- Use spaces. By default, indent form bodies by two spaces and align continuation arguments, bindings, and map keys. Keep code lines within 80 characters where practical.
- Gather trailing delimiters instead of placing each on its own line. Separate top-level forms with a blank line.
- Use one namespace per source file, with dependencies declared in `ns`.
- Prefer `:require` with `:as` over broad `:refer` lists or `:use`. Keep aliases consistent, such as `str` for `clojure.string`, `set` for `clojure.set`, and `io` for `clojure.java.io` on the JVM.

## Names and documentation

- Use `kebab-case` for functions and vars, `?` for predicates, and `->` in conversion names.
- Use `!` for functions unsafe in STM transactions, and earmuffs with `^:dynamic` for dynamically rebound vars.
- Make private functions with `defn-` and private vars with `^:private`; a name prefix alone does not make a var private.
- Use `_` or an informative underscore-prefixed name for unused bindings.
- Put function docstrings after the name and before the argument vector. Describe the contract rather than repeating the implementation. Wrap argument names and var references in backticks: ``"Returns the total of `items`."``.

## Expressions and data

- Prefer immutable Clojure data and collection functions to mutation. Use destructuring for related values.
- Use `->` or `->>` when they make nested transformations easier to read. Choose by the argument position required by each operation.
- Use `when` or `when-not` for a single conditional branch; use `:else` for the default branch of `cond`.
- Prefer functions to macros when a function suffices.
- Use `with-open` for closeable JVM resources, keeping their consumption inside its scope.

## Tests

- Name each test `something-test`, not `test-something` or a bare description. Name the test namespace `my-app.thing-test`, in `test/my_app/thing_test.clj`.

## Local preferences

These are this installation's preferences. They are not part of the style guide, and project conventions still take precedence.

- Prefer destructuring in the argument vector or `let` to repeated keyword or `get` access on the same map, so that a function shows its inputs in one place. Direct access stays clearer for a single key or inside a threading form.

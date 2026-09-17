# Clojure style digest

A selected, paraphrased digest of the [Clojure Style Guide](https://guide.clojure.style/) by Bozhidar Batsov and contributors, licensed under [CC BY 3.0](https://creativecommons.org/licenses/by/3.0/).

Project conventions and formatter settings take precedence. Do not reformat unrelated code or change behaviour to satisfy a style preference.

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
- Put function docstrings after the name and before the argument vector. Describe the contract rather than repeating the implementation.

## Expressions and data

- Prefer immutable Clojure data and collection functions to mutation. Use destructuring for related values.
- Use `->` or `->>` when they make nested transformations easier to read. Choose by the argument position required by each operation.
- Use `when` or `when-not` for a single conditional branch; use `:else` for the default branch of `cond`.
- Prefer functions to macros when a function suffices.
- Use `with-open` for closeable JVM resources, keeping their consumption inside its scope.

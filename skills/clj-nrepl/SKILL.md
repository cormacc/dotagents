---
name: clj-nrepl
description: REPL-driven Clojure, ClojureScript, EDN, and Babashka development. Use whenever the user mentions Clojure code, .clj/.cljs/.cljc/.edn/.bb files, deps.edn, project.clj, bb.edn, shadow-cljs, lein, nREPL, namespaces, vars, or clojure-lsp / clj-kondo workflows. Covers writing, editing, debugging, paren-repair, and REPL-first validation.
---

# Clojure REPL-Driven Development

## Scope and Precedence

Follow the project's `AGENTS.md` for architecture, commands, conventions, and
test infrastructure. These Clojure rules apply only to Clojure work; do not let
them override planning, documentation, git operations, or non-Clojure code
tasks.

Most Clojure tasks land entirely inside `.clj` / `.cljs` / `.cljc` / `.edn` /
`.bb` files. Prefer extending existing namespaces over creating new files, and
do not introduce sibling `*.md` notes or README updates unless the user asked
for documentation — they're almost always noise.

When reporting Clojure changes, reference code with `file_path:line_number`
so the user can jump to it.

## Style Reference

Idiomatic, functional Clojure following community conventions. Default external
style reference: https://guide.clojure.style/

## Tool Availability

Two tool sets provide nREPL evaluation. **Prefer pi-clojure** (native pi tools,
direct TCP, no process-spawn overhead) when the extension is loaded. Fall back
to the **CLI tools** otherwise.

| Capability | pi-clojure (preferred) | CLI fallback |
|---|---|---|
| Find port | `clojure_find_nrepl_port` | read `.nrepl-port` or `clj-nrepl-eval --discover-ports` |
| Eval | `clojure_eval` | `clj-nrepl-eval -p PORT` |
| Paren repair (file) | read + `clojure_paren_repair` + write | `clj-paren-repair file.clj` (preferred for files) |
| Paren repair (string) | `clojure_paren_repair` | `echo '...' \| clj-paren-repair` |

**Detecting availability:**
- pi-clojure: `clojure_eval` appears in your tool list when the extension is
  loaded
- CLI tools: `which clj-nrepl-eval` / `which clj-paren-repair`

> `clj-paren-repair` is preferred for file repair even when pi-clojure is loaded
> because it uses a real Clojure reader (edamame), parinfer-rust, and cljfmt.

## Core Workflow

**Never write code without REPL validation.**

Every coding task follows this loop:
1. Gather context
2. Take action
3. Verify output

Before modifying Clojure code:

1. **Understand existing code** - Read the target file and use LSP
   definition/references for related symbols, call sites, and dependencies.
2. **Find port and verify connection** - pi-clojure: `clojure_find_nrepl_port`
   then `clojure_eval { port: PORT, code: "(+ 1 1)" }`; CLI fallback:
   `clj-nrepl-eval --discover-ports "(+ 1 1)"`.
3. **Explore unfamiliar functions** - eval `(clojure.repl/doc function-name)`
   via `clojure_eval` or `clj-nrepl-eval`.
4. **Test in REPL** - Define and validate functions before saving.
5. **Check edge cases** - nil, empty collections, invalid inputs.
6. **Save only after validation** - Use `edit` or `write`.
7. **Reload before verifying edits** - eval `(require '[project.core] :reload)`
   via `clojure_eval` or `clj-nrepl-eval`.
8. **Do not report success before verification** - changed functions and
   relevant tests must pass.

If nREPL fails, ask: "Please start your nREPL server (e.g., `bb nrepl` or
`lein repl :headless`)"

### Agent Loop

Use this loop for every coding task:

- **Gather context** - Read the target file, related code, call sites,
  dependencies, and tests
- **Take action** - Make the smallest focused change that solves the task; avoid
  unrelated refactors
- **Verify output** - Reload affected namespaces, test changed functions,
  validate edge cases, and run relevant tests

If verification fails, return to gather/action and fix the problem before
reporting success.

### Failure Recovery and Clarification

If REPL evaluation, test execution, or namespace loading fails:

1. Read the exact error message
2. Isolate the failing expression or function
3. Fix the root cause
4. Reload affected namespaces
5. Rerun verification

If delimiter errors occur, use `clj-paren-repair` (files) or
`clojure_paren_repair` (strings) instead of manual repair.

Ask for clarification when requirements are ambiguous, multiple approaches have
materially different trade-offs, or an architectural decision is required.

## Essential Patterns

### Threading Macros (always prefer over nesting)

```clojure
;; -> for transformations
(-> user
    (assoc :last-login (now))
    (update :login-count inc))

;; ->> for sequences
(->> users
     (filter active?)
     (map :email)
     (str/join ", "))

;; some-> for nil-safe navigation
(some-> user :address :postal-code (subs 0 5))

;; cond-> for conditional changes
(cond-> request
  authenticated? (assoc :user current-user))
```

### Naming Rules

| Pattern | Example |
|---------|---------|
| kebab-case | `calculate-total`, `max-retries` |
| predicates end with `?` | `valid?`, `active?` |
| conversions use `->` | `map->vector`, `string->int` |
| `!` suffix for unsafe mutation | `swap!`, `reset!`, `save-user!` |
| `!` prefix for mutable refs | `!conn`, `!store` |

### Control Flow

```clojure
;; when for side effects
(when (valid? data)
  (log "Processing")
  (process data))

;; cond for multiple branches
(cond
  (< n 0) :negative
  (= n 0) :zero
  :else   :positive)
```

### Docstrings (required for public functions)

```clojure
(defn calculate-total
  "Calculate total price including tax.

   Args:
     price - base price (number)
     rate  - tax rate as decimal (0.08 = 8%)

   Returns:
     number - total price

   Example:
     (calculate-total 100 0.08) => 108"
  [price rate]
  ...)
```

### Namespace Templates

JVM Clojure (`.clj`):

```clojure
(ns project.module
  "Brief description."
  (:require
   [clojure.string :as str]
   [clojure.set :as set])
  (:import
   (java.time LocalDate)))

(set! *warn-on-reflection* true)
```

ClojureScript (`.cljs`) or cross-platform (`.cljc`):

```clojure
(ns project.module
  "Brief description."
  (:require
   [clojure.string :as str]))
```

## Tools

### Eval - pi-clojure (preferred)

Available when the `pi-clojure` extension is loaded. Direct TCP to nREPL, no
process-spawn overhead.

```
# Discover port (checks .nrepl-port, .shadow-cljs/nrepl.port, .cider-nrepl.port, then defaults)
clojure_find_nrepl_port {}

# Evaluate expressions
clojure_eval { port: PORT, code: "(+ 1 2 3)" }
clojure_eval { port: PORT, code: "(defn sum [nums] (reduce + nums))" }
clojure_eval { port: PORT, code: "(sum [1 2 3])" }

# Discover functions
clojure_eval { port: PORT, code: "(clojure.repl/dir clojure.string)" }
clojure_eval { port: PORT, code: "(clojure.repl/doc map)" }
clojure_eval { port: PORT, code: "(clojure.repl/apropos \"split\")" }
clojure_eval { port: PORT, code: "(clojure.repl/source filter)" }

# Load project code
clojure_eval { port: PORT, code: "(require '[project.core :as core] :reload)" }

# Evaluate in a specific namespace
clojure_eval { port: PORT, ns: "project.core", code: "(my-fn 42)" }
```

### Eval - clj-nrepl-eval (fallback)

Use when `clojure_eval` is not in your tool list.

```shell
clj-nrepl-eval --discover-ports "(+ 1 2 3)"
clj-nrepl-eval -p PORT "(defn sum [nums] (reduce + nums))"
clj-nrepl-eval -p PORT "(clojure.repl/dir clojure.string)"
clj-nrepl-eval -p PORT "(clojure.repl/doc map)"
clj-nrepl-eval -p PORT "(require '[project.core :as core] :reload)"
```

### Paren repair - clj-paren-repair (preferred for files)

Uses edamame + parinfer-rust + cljfmt: repairs and formats in place. Preferred
even when pi-clojure is loaded.

```shell
clj-paren-repair src/core.clj
clj-paren-repair src/*.clj test/*.clj
```

### Paren repair - clojure_paren_repair (string repair)

Available when pi-clojure is loaded. Use for string-based repair or when
`clj-paren-repair` is unavailable.

```
clojure_paren_repair { code: "(defn foo [x]" }
clojure_paren_repair { code: "(defn foo [x])", check: true }
```

**Never** manually fix parenthesis errors - use one of the tools above.

## Validation Checklist

Before saving any code:

- [ ] Tested happy path in REPL
- [ ] Tested nil handling
- [ ] Tested empty collection handling
- [ ] Used threading macros over deep nesting
- [ ] Added docstring if public function
- [ ] Checked naming conventions
- [ ] Code under 80 characters per line
- [ ] Closing parens on single line

## Code Review Workflow

Before modifying code:

1. Read the target file.
2. Use LSP `definition`, `references`, or `workspace_symbol` for target
   namespaces, functions, vars, and call sites.
3. Use REPL exploration for runtime behavior and library docs.
4. Review namespace imports and local patterns.
5. Match codebase conventions.

## Detailed References

- **Tool usage**: See [references/tool-guide.md](references/tool-guide.md) for
  complete documentation of all eval and paren-repair tools
- **Idiomatic patterns**: See [references/idioms.md](references/idioms.md) for
  threading macros, control flow, data structures, error handling, and
  anti-patterns

Load these references when you need:
- Detailed tool commands
- Advanced idioms or patterns
- Error handling examples
- Testing patterns
- Research citations

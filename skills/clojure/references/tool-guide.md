# Tool Guide: Clojure Eval and Paren Repair

Complete reference for nREPL evaluation and delimiter repair, covering both
pi-clojure (native pi tools) and the clj-nrepl CLI fallback.

---

## Eval: pi-clojure (preferred)

Native pi tools — direct TCP connection to nREPL, no process-spawn overhead.
Available when the `pi-clojure` extension is loaded (`clojure_eval` appears in
your tool list).

### Finding the port

`clojure_find_nrepl_port` checks port files then probes common defaults.

Port files checked (in order):
- `.nrepl-port`
- `nrepl-port`
- `.shadow-cljs/nrepl.port`
- `.cider-nrepl.port`

Default ports tried: 7888, 1666, 50505, 58885, 63333, 7889

Validates by evaluating `(+ 1 1)` on the discovered port.

### Parameters for clojure_eval

| Parameter | Type | Description |
|---|---|---|
| `port` | number | nREPL port (required) |
| `code` | string | Clojure expression to evaluate |
| `host` | string | nREPL host (default: `"localhost"`) |
| `ns` | string | Namespace to evaluate in (optional) |
| `timeout` | number | Timeout in ms (default: 30000) |

### Examples

```
# Simple expressions
clojure_eval { port: PORT, code: "(+ 1 2 3)" }

# Define functions
clojure_eval { port: PORT, code: "(defn greet [name] (str \"Hello, \" name))" }
clojure_eval { port: PORT, code: "(greet \"World\")" }

# Work with collections
clojure_eval { port: PORT, code: "(map inc [1 2 3])" }
clojure_eval { port: PORT, code: "(filter even? [1 2 3 4])" }

# Require and use namespaces
clojure_eval { port: PORT, code: "(require '[clojure.string :as str])" }
clojure_eval { port: PORT, code: "(str/upper-case \"hello\")" }

# Evaluate in a specific namespace
clojure_eval { port: PORT, ns: "project.core", code: "(my-fn 42)" }
```

### Discovery commands

```
# List all public vars in a namespace
clojure_eval { port: PORT, code: "(clojure.repl/dir clojure.string)" }

# Get function documentation
clojure_eval { port: PORT, code: "(clojure.repl/doc map)" }

# Search by name pattern
clojure_eval { port: PORT, code: "(clojure.repl/apropos \"split\")" }

# Search documentation text
clojure_eval { port: PORT, code: "(clojure.repl/find-doc \"regular expression\")" }

# Read function source
clojure_eval { port: PORT, code: "(clojure.repl/source filter)" }

# Get arglists
clojure_eval { port: PORT, code: "(:arglists (meta #'reduce))" }
```

### Loading and reloading project code

```
# Load a namespace
clojure_eval { port: PORT, code: "(require '[project.core :as core] :reload)" }

# List namespace contents
clojure_eval { port: PORT, code: "(clojure.repl/dir project.core)" }

# Call a loaded function
clojure_eval { port: PORT, code: "(core/my-function test-data)" }
```

### Debugging

```
# Inspect data structures
clojure_eval { port: PORT, code: "(type [1 2 3])" }
clojure_eval { port: PORT, code: "(keys my-map)" }

# Test pipeline steps
clojure_eval { port: PORT, code: "(def data [1 2 3 4 5])" }
clojure_eval { port: PORT, code: "(filter even? data)" }
clojure_eval { port: PORT, code: "(map #(* % 2) (filter even? data))" }

# Check predicates
clojure_eval { port: PORT, code: "(even? 4)" }
clojure_eval { port: PORT, code: "(nil? [])" }
clojure_eval { port: PORT, code: "(empty? [])" }
```

---

## Eval: clj-nrepl-eval (fallback)

CLI tool. Use when `clojure_eval` is not in your tool list. Spawns a process
per call.

### Basic syntax

```shell
clj-nrepl-eval [options] "clojure-expression"
```

Options:
- `-p, --port PORT` — nREPL port
- `-h, --host HOST` — nREPL host (default: localhost)
- `--discover-ports` — auto-discover from port file or common defaults

### Examples

```shell
# Auto-discover port
clj-nrepl-eval --discover-ports "(+ 1 2 3)"

# Explicit port
clj-nrepl-eval -p PORT "(defn greet [name] (str \"Hello, \" name))"
clj-nrepl-eval -p PORT "(greet \"World\")"

# Discovery
clj-nrepl-eval -p PORT "(clojure.repl/dir clojure.string)"
clj-nrepl-eval -p PORT "(clojure.repl/doc map)"
clj-nrepl-eval -p PORT "(clojure.repl/apropos \"split\")"
clj-nrepl-eval -p PORT "(clojure.repl/find-doc \"regular expression\")"
clj-nrepl-eval -p PORT "(clojure.repl/source filter)"
clj-nrepl-eval -p PORT "(:arglists (meta #'reduce))"

# Load project code
clj-nrepl-eval -p PORT "(require '[project.core :as core] :reload)"
clj-nrepl-eval -p PORT "(clojure.repl/dir project.core)"
clj-nrepl-eval -p PORT "(core/my-function test-data)"
```

---

## Paren repair: clj-paren-repair (preferred for file repair)

CLI tool. Uses edamame (real Clojure reader), parinfer-rust, and cljfmt.
Repairs and formats files in place. Preferred over `clojure_paren_repair` for
any file-based work, even when pi-clojure is loaded.

### Usage

```shell
# Fix a single file
clj-paren-repair path/to/file.clj

# Fix multiple files
clj-paren-repair src/core.clj src/util.clj test/core_test.clj

# Fix all Clojure files in a tree
clj-paren-repair src/*.clj test/*.clj

# Check code via stdin
echo '(defn hello [x] (+ x 1)' | clj-paren-repair
```

Supported: `.clj`, `.cljs`, `.cljc`, `.bb`, `.edn`, `.lpy`

### When to use

- Parse error or unbalanced delimiter after editing
- REPL eval fails with unexpected delimiter/EOF error
- Want to normalise formatting after structural changes

---

## Paren repair: clojure_paren_repair (string repair, pi-clojure)

Native pi tool. Uses JS parinfer. Operates on code strings, not files.
Use when `clj-paren-repair` is unavailable, or when repairing a string before
writing a new file.

```
# Fix unbalanced delimiters
clojure_paren_repair { code: "(defn foo [x]" }

# Check only (don't fix)
clojure_paren_repair { code: "(defn foo [x])", check: true }
```

Supported: `.clj`, `.cljs`, `.cljc`, `.bb`

---

## Troubleshooting

### Connection refused

Ask the user to start nREPL:
```bash
bb nrepl
lein repl :headless
clj -Sdeps '{:deps {nrepl/nrepl {:mvn/version "1.0.0"}}}' -M -m nrepl.cmdline
```

### Wrong port

Re-run `clojure_find_nrepl_port` or `clj-nrepl-eval --discover-ports` to
re-discover the active port.

### Namespace not found

Require it first:
```
clojure_eval { port: PORT, code: "(require '[namespace.name])" }
# or
clj-nrepl-eval -p PORT "(require '[namespace.name])"
```

### Expression errors

Isolate the failing sub-expression, then use `(clojure.repl/doc ...)` to
verify signatures and `(clojure.repl/source ...)` to inspect the implementation.

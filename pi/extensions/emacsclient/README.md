# Emacsclient Extension for Pi

A Pi extension that enables direct interaction with a running Emacs session.
Instead of editing files on disk, Pi can read and manipulate Emacs buffers
in-memory, query buffer state, and perform syntax-aware operations using Emacs's
built-in Tree-sitter support.

Complements the
[`pi-coding-agent` Emacs package](https://github.com/dnouri/pi-coding-agent).
With a little Emacs Lisp (possibly vibe-coded), these can be combined in
powerful ways; e.g. binding a hotkey which sends Pi a message like
`Read whats at point in buffer 'foo' and action any request/issue/question`.

## Features

- **Direct buffer access**: Read and query Emacs buffers without touching the
  filesystem
- **Tree-sitter integration**: Run structural queries and perform syntax-aware
  edits
- **State management**: Navigate buffers, move point, and maintain editing
  context
- **Emacs Lisp evaluation**: Execute arbitrary elisp in your running Emacs
  session

## Requirements

- For the full toolset, an Emacs server is still required (Emacs 29+ recommended for Tree-sitter support)
- `emacsclient` binary in your PATH
- `emacs` binary in your PATH if you want `emacs:open` to auto-start a daemon when no server is running

To start an Emacs server:

```bash
# Start Emacs as a daemon
emacs --daemon

# Or from within Emacs
M-x server-start
```

## Configuration

By default, the extension connects to your default Emacs server socket. To use a
custom socket:

```bash
export EMACS_SOCKET_NAME=/path/to/socket
```

For testing, you can override the `emacsclient` binary:

```bash
export EMACSCLIENT_BINARY=/custom/path/to/emacsclient
```

## Tools

### `emacs_read`

Read the content and metadata of a file or Emacs buffer.

**Parameters:**

- `name` (required): Path if it starts with `/` (absolute), or `./` or `../`
  (relative); otherwise a buffer name. If no buffer with that name exists:
  names with special chars (`*`, `/`, `<`, `>`) create a bare buffer with no
  file association (following `*name*` convention for temp buffers); plain
  names open/create a file as if preceded by `./`. Supports TRAMP paths.
- `pos` (optional): Character position to start reading: 0 for point
  (default), positive for 1-indexed buffer position, or negative to read
  backwards from point.
- `line` (optional): Line number to start reading (same 0, positive, negative
  semantics as `pos`)
- `col` (optional): Column number (used with `line`)
- `length` (optional): Maximum characters to read (default: 51200, the limit)
- `lines` (optional): Maximum lines to read
- `span` (optional): Narrow to a span ID (result of a previous `emacs_read`)
- `move` (optional): leave point at end of what was read (so the next `emacs_read`
  with no `pos` continues from there). Fefault: false (leave point where it was)
- `temp` (optional): kill any newly-opened buffer after reading (default: false)

**Returns:** Buffer content, metadata (major mode, size, point position, etc.)

**Example:**

```typescript
// Read first 1000 characters of a file; move: true advances point to 1001
emacs_read({ name: "./src/main.ts", pos: 1, length: 1000, move: true })
// Read the NEXT 1000 characters — no pos needed, point was left at 1001
emacs_read({ name: "./src/main.ts", length: 1000, move: true })

// Read 50 lines starting from line 100; point remains unchanged
emacs_read({ name: "./main.ts", line: 100, lines: 50 })

// Peek at file contents, closing the buffer if it wasn't already open
emacs_read({ name: "./config.json", pos: 1, temp: true })

// Read within a span from a previous read
emacs_read({ name: "./config.json", span: "span-id-from-previous-read" })
```

After the first `emacs_read`, only changed metadata is returned (to save tokens);
*except* for `unsaved` (buffer is modified) and `outdated` (file on disk is
modified), since those are important when mixing `emacs_read`/`emacs_write` with `bash`.

### `emacs_write`

Insert text into Emacs buffer at a specific position, and optionally type a key
sequence. Can create new files/buffers, move point, insert content, type keys,
and save. Since writing is destructive, ambiguous/conflicting options give an
*error*.

**Parameters:**

- `name` (required): Path if it starts with `/` (absolute), or `./` or
  `../` (relative); otherwise a buffer name. If no buffer with that name
  exists: names with special chars (`*`, `/`, `<`, `>`) create a bare buffer
  with no file association (use `*name*` for temp buffers); plain names
  open/create a file as if preceded by `./`. Supports TRAMP paths.
- `insert` (optional): Text to insert at the specified position
- `pos` (optional): Position to insert at; 0 = point (default), positive for
  1-indexed buffer position, or negative to count back from end of buffer.
  Conflicts with `line`, `point`, `replace`
- `line` (optional): Line number to insert at (1-indexed, or negative for
  relative to end). Conflicts with `pos`, `point`, `replace`.
- `point` (optional): If true, insert at point (start of file if newly
  opened). Default when no `pos` or `line` given. Conflicts with those.
- `type` (optional): Keyboard macro to type in buffer (via 'kbd'). Runs after
  insert and before save.
- `replace` (optional): If true, clear buffer contents before inserting.
  Makes `point`/`pos`/`line` meaningless.
- `save` (optional): If buffer is backed by a file, save it after inserting.
  Creates parent directories if needed (default: true)
- `temp` (optional): If true, restore Emacs state afterwards - killing new
  buffers, restoring point in existing buffers (default: false)

**Returns:** Updated buffer metadata

**Example:**

```typescript
// Insert text at the beginning of a file and save
emacs_write({ name: "./README.md", insert: "# Title\n\n", pos: 1 })

// Append text to a buffer and save
emacs_write({ name: "notes", insert: "\nNew note", pos: -1 })

// Insert at current point without saving
emacs_write({ name: "./src/main.ts", insert: "// TODO: review\n", point: true,
              save: false })

// Create or overwrite a file with given content
emacs_write({ name: "./newfile.txt", insert: "Hello, world!", replace: true })

// Replace entire buffer content (use *name* for a bare buffer with no
// file association)
emacs_write({ name: "*pi-scratch*", insert: "Fresh content", replace: true })

// Insert without affecting Emacs state
emacs_write({ name: "./config.json", insert: "new config", pos: 1, temp: true })

// Type a key sequence in the buffer
emacs_write({ name: "main.py", type: "C-x C-s" })
```

### `emacs_eval`

Evaluate an Emacs Lisp expression and return the result.

**Parameters:**

- `expression` (required): Elisp code to evaluate

**Returns:** The result of evaluating the expression

**Example:**

```typescript
// Get current buffer name
emacs_eval({ expression: "(buffer-name)" })

// List all buffers
emacs_eval({ expression: "(mapcar #'buffer-name (buffer-list))" })

// Get value of a variable
emacs_eval({ expression: "default-directory" })

// Browse the Web
emacs_eval({ expression: "(eww \"http://chriswarbo.net\")"})
```

### `emacs_ts_query`

Run a Tree-sitter query against an Emacs buffer and optionally execute elisp for
each match.

**Parameters:**

- `buffer` (required): Buffer name or file path
- `query` (required): Tree-sitter query string with `@captures`
- `lang` (optional): Language hint (e.g., "python", "javascript")
- `action` (optional): Elisp expression to evaluate for each match

**Returns:** An object containing:

- `results`: Array of results (one per match)
- `count`: Number of matches found

**Examples:**

```typescript
// Find all function definitions
emacs_ts_query({
  buffer: "main.py",
  query: "(function_definition name: (identifier) @name)",
  lang: "python"
})

// Get function names and their starting positions
emacs_ts_query({
  buffer: "main.py",
  query: "(function_definition name: (identifier) @name)",
  action: "(cons (treesit-node-text name) (treesit-node-start name))"
})

// Find all import statements
emacs_ts_query({
  buffer: "app.ts",
  query: "(import_statement) @import",
  lang: "typescript"
})
```

## Events

The extension listens for events on `pi.events`, allowing other extensions to
interact with Emacs without importing emacsclient internals.

### `emacs:open`

Open a file in Emacs, optionally positioning point at a specific line and
column. Raises the Emacs frame. If no Emacs server is reachable, this event
will first try to start `emacs --daemon`.

**Payload:** `{ file: string; line?: number; col?: number }`

**Example:**

```typescript
// Open a file at line 42
pi.events.emit("emacs:open", { file: "/path/to/file.ts", line: 42 });

// Open a file at a specific line and column
pi.events.emit("emacs:open", { file: "./src/main.ts", line: 10, col: 5 });

// Just open a file (point at beginning)
pi.events.emit("emacs:open", { file: "/path/to/README.md" });
```

## Spans

Support is being added for "spans", as a way for Emacs to control and guide the
agent's reads and edits. This is currently a work in progress, so no actual
spans will be returned yet.

The idea is for Emacs to calculate meaningful regions of a buffer, e.g. a
function definition for a programming mode, or the output of a command in a
shell mode, or a section in a document mode, etc. and include a selection of
them in its responses (as a key/value mapping from IDs to brief descriptions).
The LLM can optionally use those span IDs in subsequent calls, to e.g. edit a
function definition, or read the output of a command, etc. A key idea is for IDs
to [include a short hash of the existing content][harness], so that edits will
fail if the content has subsequently changed (since the ID based on the old
content won't appear any more).

[harness]: https://blog.can.ac/2026/02/12/the-harness-problem/

## Use Cases

### Avoiding Buffer Conflicts

Reading Emacs buffers ensures unsaved changes are seen; writing to Emacs buffers
avoids conflicting changes.

### Syntax-Aware Refactoring

Use Tree-sitter queries to find and modify code structures precisely:

- Rename functions/classes
- Add parameters to function signatures
- Extract code to functions
- Reorganize imports

### Context-Aware Assistance

Pi can query your current Emacs state to provide more relevant help:

- See what files you have open
- Know where point is positioned
- Understand the major mode and language context

### Interactive Development

Combine reading and evaluation for complex workflows:

1. Read a section of code
2. Analyze it
3. Execute elisp to perform edits
4. Query the result to verify changes

## Development

### Running Tests

The extension includes comprehensive tests. It is recommended to use
`nix-build`, via the `default.nix` file in this repo's root:

```bash
nix-build ../.. -A extensions.emacsclient
```

Test suites:

- **Unit tests** (`unit_test.test.ts`): Test pure functions
- **Emacs integration tests** (`emacs-integration.test.ts`): Test emacsclient
  interaction
- **Pi integration tests** (`pi-integration.test.ts`): Test extension API
  integration
- **Read tool tests** (`read-tool.test.ts`, `read-tool-integration.test.ts`):
  Test the read tool

### Architecture

- `index.ts`: Tool registration and Pi API integration
- `emacsclient.ts`: Low-level emacsclient invocation
- `elisp.ts`: Elisp code generation and output parsing
- `*.test.ts`: Test suites

## Troubleshooting

### "emacsclient: can't find socket"

Make sure your Emacs server is running:

```elisp
M-x server-start
```

Or start Emacs as a daemon:

```bash
emacs --daemon
```

### "Wrong type argument: treesit-node-p"

Your buffer needs a Tree-sitter parser. Emacs 29+ with Tree-sitter grammars
installed is required. Check with:

```elisp
M-: (treesit-available-p)
```

### Timeout errors

Increase the timeout for long-running operations by setting
`EMACSCLIENT_TIMEOUT`:

```bash
export EMACSCLIENT_TIMEOUT=30000  # 30 seconds
```

## License

Public domain

Sourced from https://github.com/Warbo/pi-extensions - not my work

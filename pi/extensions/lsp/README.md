# LSP extension

Language Server Protocol tool for definitions, references, renames, symbols, completions, and diagnostics in C, C++, Clojure, Go, Nix, Python, and TypeScript projects. It starts language servers on demand.

## Tools

- `lsp` — accepts a `language` and an action: `definition`, `references`, `rename`, `apply_rename`, `document_symbols`, `workspace_symbol`, `completion`, or `diagnostics`.
- Rename has two steps. Both report a compact summary by default and standard before/after diffs when expanded. Model-visible output uses pi's normal 50 KiB / 2,000-line bound.

### Rename

1. `rename` asks the server for the workspace edit and writes no file. It returns a plan identifier, each target file with its edit count and 1-based ranges, and the diffs. It records the SHA-256 of each target file's content.
2. `apply_rename` with `plan_id` applies the stored edits through pi's file-mutation queues, then refreshes the changed documents in the server.

Inside the queues, apply checks every target again and writes nothing if one check fails:

- a target file's content differs from the content that the preview fingerprinted;
- edits in one file overlap;
- a target file is outside the workspace root of the renamed file;
- the workspace edit contains a resource operation (create, rename or delete a file). The tool does not advertise resource-operation support, so a server such as clojure-lsp refuses a namespace rename instead.

The preview lists the reasons when it can already see them. A plan is single-use: the first `apply_rename` removes it, also when apply refuses. Plans exist in memory only, and session shutdown discards them. The tool does not format or organise imports, and it applies only the rename workspace edit.

Location and reference previews are invalidated after an extension-driven file mutation and whenever the session shuts down, so replacement sessions cannot retain stale file content.

## Languages

One built-in catalogue in `languages/index.ts` defines each server family: its launch command, its root markers, and its file extensions. The extension map gives both the LSP document language ID and the files that a by-name symbol lookup opens. The workspace root is the nearest ancestor directory that holds a root marker, otherwise the file's directory.

| Language | Server | Extensions | Root markers |
|---|---|---|---|
| `nix` | `nil --stdio` | `.nix` | `flake.nix`, `default.nix`, `shell.nix` |
| `python` | `basedpyright-langserver --stdio` through `nix-shell -p basedpyright` | `.py`, `.pyi` | none (file directory) |
| `typescript` | `typescript-language-server --stdio` | `.ts`, `.mts`, `.cts`, `.tsx`, `.js`, `.mjs`, `.cjs`, `.jsx` | `tsconfig.json`, `jsconfig.json`, `package.json` |
| `go` | `gopls serve` | `.go` | `go.mod` |
| `clojure` | `clojure-lsp listen` | `.clj`, `.cljs`, `.cljc`, `.edn`, `.bb`, `.cljd` | `deps.edn`, `project.clj`, `bb.edn`, `shadow-cljs.edn`, `.lsp/config.edn` |
| `c` | `clangd --background-index` | `.c`, `.h` | `compile_commands.json`, `compile_flags.txt`, `.clangd` |
| `cpp` | `clangd --background-index` | `.cpp`, `.cc`, `.cxx`, `.c++`, `.C`, `.hpp`, `.hh`, `.hxx`, `.h++`, `.H`, `.h` | `compile_commands.json`, `compile_flags.txt`, `.clangd` |

Extensions are case-sensitive: `.c` is C and `.C` is C++.

### Language selection

The caller selects the language explicitly; the extension does not infer it. A supplied `file` must have an extension of the selected language, otherwise the request fails before any server starts. Relative and `@`-prefixed paths resolve against the session working directory.

### C and C++

`c` and `cpp` share one clangd process for each workspace root. Each document is opened with the language ID of the request, so `.h` is C in a `c` request and C++ in a `cpp` request; a later request in the other language reopens the header with that ID.

The workspace root is the nearest ancestor that holds `compile_commands.json`, `compile_flags.txt` or `.clangd`. clangd finds the compilation database itself by searching the parent directories of each file, and it follows a symlinked `compile_commands.json`. A file that the database does not list gets inferred flags, and the background index does not cover it.

clangd begins indexing only after the first document opens. On a new clangd server the tool therefore waits up to 2 seconds for indexing to begin, then up to 30 seconds for it to end, so that the first references request sees the index.

### By-name lookup

`definition` and `references` with `query` use `workspace/symbol` and rank its results in tiers: exact name (also `container::name`, `container.name` or `container/name`), then case-insensitive name, then partial name. Only the best non-empty tier is used, and duplicate locations are removed.

- `definition` returns every location in that tier, in server order, with a count header when there is more than one.
- `references` uses only the first location in that tier, and names how many other symbols matched. Give `file`, `line` and `character` to select another.
- If `workspace/symbol` fails or has no match, the lookup ranks the symbols of open documents. A new server first opens at most 50 source files to a depth of 5 directories. The result states this bounded coverage, because other matches can exist.

Output that exceeds pi's 50 KiB / 2,000-line bound ends with a notice that gives the lines and bytes shown.

## Commands

- `/lsp-status` — show running language servers.
- `/lsp-stop [language]` — stop every running server or only the servers of one language. `c` and `cpp` both stop the shared clangd servers.

The extension has no custom keybindings and requires the relevant language server executable to be available on `PATH`.

## Development prerequisite

The focused LSP suite imports pi's public `generateDiffString` API, so the root development dependencies include `@earendil-works/pi-coding-agent`, pinned to the supported Pi release (1.5.0).

Test tooling: [../test/README.md](../test/README.md). `test.sh` runs `languages.test.ts` (catalogue characterisation), `lsp.test.ts` (rename preview and apply, refusal checks, mutation queues and result rendering) and `dispatch.test.ts` (language and file validation, shared clangd routing, by-name ranking and fallback, truncation notices, the `rename`/`apply_rename` tool flow, and client document IDs and indexing waits against `fixtures/fake-lsp-server.mjs`). No test starts a real language server.

## Compatibility review

Last review: 2026-11-17, against pi 1.5.2.

- `clangd` 21.1.8: `definition`, `references`, `rename` preview and apply, `document_symbols` and `diagnostics` worked against disposable fixtures with parent, symlinked and absent compilation databases; read-only references, query definition and rename preview worked in the MBT firmware project with its host-GCC test database.
- `clojure-lsp` 2026.10.15-11.42.38: query `definition` and `references`, and `rename` preview, worked read-only in the Connect portal project; `rename` preview and apply worked in a disposable project.
- The review did not exercise `basedpyright` (through `nix-shell`), `gopls`, `nil` or `typescript-language-server` against a live server; the focused suite covers their catalogue entries only.
- Checks: `test.sh` (it starts no real language server), type-check against the pi 1.5.0 development declarations (three errors in `index.ts` that predate this review), and a `pi --mode json` load of the extension with empty stderr.
- Fixed prompt cost: 86 tokens more than `ef33181` (3,188 against 3,102 input tokens for a one-word reply, same model, flags and working directory, measured twice in alternation).

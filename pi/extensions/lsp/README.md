# LSP extension

Language Server Protocol tool for definitions, references, renames, symbols, completions, and diagnostics in Clojure, Go, Nix, Python, and TypeScript projects. It starts language servers on demand.

## Tools

- `lsp` — accepts a `language` and an action: `definition`, `references`, `rename`, `document_symbols`, `workspace_symbol`, `completion`, or `diagnostics`.
- Rename applies LSP workspace edits through pi's file-mutation queues, reports a compact summary by default, and exposes standard before/after diffs when expanded. Model-visible output uses pi's normal 50 KiB / 2,000-line bound.

Location and reference previews are invalidated after an extension-driven file mutation and whenever the session shuts down, so replacement sessions cannot retain stale file content.

## Commands

- `/lsp-status` — show running language servers.
- `/lsp-stop [language]` — stop every running server or only one language.

The extension has no custom keybindings and requires the relevant language server executable to be available on `PATH`.

## Development prerequisite

The focused LSP suite imports pi's public `generateDiffString` API, so the root development dependencies include `@earendil-works/pi-coding-agent`, pinned to the supported Pi release (1.5.0).

Test tooling: [../test/README.md](../test/README.md).

## Compatibility review

Last review: 2026-10-29, against pi 1.4.0.

- `typescript-language-server` 6.0.2 and `clojure-lsp` 2026.10.15-11.42.38: `document_symbols` worked live in a pi 1.4.0 session.
- The review did not exercise `basedpyright` (through `nix-shell`), `gopls`, or `nil`.
- Checks: `test.sh` (edit application and result rendering; it starts no language server), type-check against the pi 1.4.0 declarations, and `pi --mode rpc` load with empty stderr.

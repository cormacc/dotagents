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

The focused LSP suite imports pi's public `generateDiffString` API, so the root development dependencies include `@earendil-works/pi-coding-agent` 0.80.10. This is the narrow package prerequisite pulled forward from the following package-scope migration task; it does not normalize the extension's other legacy imports.

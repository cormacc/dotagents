# pi dataspex extension

Token-disciplined tools for reading Dataspex state from a running ClojureScript browser runtime through a shadow-cljs nREPL. The extension talks to `dataspex.core/store` in the application process; it does not require the Dataspex browser extension, Chromium remote debugging, or the human devtools panel.

## Preconditions

- A shadow-cljs watch is running and has a browser runtime attached.
- The app already depends on `no.cjohansen/dataspex` and has called `dataspex.core/inspect` for the labels you want to read.
- An nREPL port is available via `.shadow-cljs/nrepl.port`, `.nrepl-port`, `nrepl-port`, `.cider-nrepl.port`, or passed explicitly as `port`.

When multiple shadow builds are active, pass `buildId` (with or without the leading `:`). The tool reports candidate build ids when discovery is ambiguous.

## Tool

`dataspex` dispatches by `op`. Shared target params are `buildId?`, `port?`, and `host?`.

| op | Parameters | Default output |
|---|---|---|
| `labels` | `{ buildId?, port?, host? }` | User labels as `{:label :rev :idx :history-len :val-type :has-ref?}` |
| `value` | `{ label, path?, fresh?, limit?, level?, buildId?, port?, host? }` | Bounded current `:val` or fresh `:ref` deref; `path` is an EDN vector such as `[:patient :name]` |
| `history` | `{ label, n?, includeVal?, buildId?, port?, host? }` | Latest audit entries, defaulting to `{:rev :created-at :diff}` and preferring `<label>-audit` |
| `track` | `{ label, historyLimit?, buildId?, port?, host? }` | Registers `<label>-audit` with `{:track-changes? true}`; refuses to overwrite an existing audit label |
| `untrack` | `{ label, buildId?, port?, host? }` | Removes the parallel audit label |
| `db_query` | `{ label, q, args?, limit?, level?, buildId?, port?, host? }` | Runs a datascript query against a DB label and returns only the result set |
| `actions_tail` | `{ label?, n?, buildId?, port?, host? }` | Latest nexus `LogInspector` entries from `Actions`, projected to `{:dispatched-at :actions :dispatch-data}` where `:actions` unwraps Nexus' `Action.data` |

Migration: the former `dataspex_labels`, `dataspex_value`, `dataspex_history`, `dataspex_track`, `dataspex_untrack`, `dataspex_db_query`, and `dataspex_actions_tail` tools are now `dataspex` with the corresponding `op`. Resumed sessions may try an old name once before the updated skill steers the model to the new spelling.

## Design notes

The extension reuses the local `pi-clojure` nREPL client and evaluates `(shadow.cljs.devtools.api/cljs-eval ...)` from the JVM side. This keeps reads robust under advanced compilation, where `dataspex.core/store` is not `^:export`-tagged for direct JS lookup.

Agent-registered audit labels always use the `<label>-audit` suffix. Do not re-call `dataspex.core/inspect` on an existing application label; that would overwrite the user's devtools panel state.

See `skills/dataspex/SKILL.md` and `design/log/2026-05-13-dataspex-agent-integration.org` for the portable fallback workflow and spike history.

## Commands, mode, and dependencies

There are no slash commands, custom Pi TUI components, or default keybindings.
The tools communicate with the browser runtime through nREPL, so they are not
TUI-only; they still require the preconditions above. Runtime dependencies are
provided by the local `pi-clojure` extension and Pi-hosted
`@earendil-works/pi-coding-agent` and TypeBox 1.x (`typebox`) APIs.

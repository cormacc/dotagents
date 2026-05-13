# pi dataspex extension

Token-disciplined tools for reading Dataspex state from a running ClojureScript browser runtime through a shadow-cljs nREPL. The extension talks to `dataspex.core/store` in the application process; it does not require the Dataspex browser extension, Chromium remote debugging, or the human devtools panel.

## Preconditions

- A shadow-cljs watch is running and has a browser runtime attached.
- The app already depends on `no.cjohansen/dataspex` and has called `dataspex.core/inspect` for the labels you want to read.
- An nREPL port is available via `.shadow-cljs/nrepl.port`, `.nrepl-port`, `nrepl-port`, `.cider-nrepl.port`, or passed explicitly as `port`.

When multiple shadow builds are active, pass `buildId` (with or without the leading `:`). The tools report candidate build ids when discovery is ambiguous.

## Tools

- `dataspex_labels` — list user labels as `{:label :rev :idx :history-len :val-type :has-ref?}`.
- `dataspex_value` — read a bounded current value from a label; optional `path` is an EDN vector such as `[:patient :name]`; `fresh` dereferences the underlying `:ref` when present.
- `dataspex_history` — read latest audit entries, defaulting to `{:rev :created-at :diff}` and preferring a parallel `<label>-audit` label when it exists.
- `dataspex_track` — register `<label>-audit` with `{:track-changes? true}`; refuses to overwrite an existing audit label.
- `dataspex_untrack` — remove the parallel audit label.
- `dataspex_db_query` — run a datascript query against a DB label and return only the result set.
- `dataspex_actions_tail` — read latest nexus `LogInspector` entries from the `Actions` label, projected to `[:dispatched-at :dispatch-data]`.

## Design notes

The extension reuses the local `pi-clojure` nREPL client and evaluates `(shadow.cljs.devtools.api/cljs-eval ...)` from the JVM side. This keeps reads robust under advanced compilation, where `dataspex.core/store` is not `^:export`-tagged for direct JS lookup.

Agent-registered audit labels always use the `<label>-audit` suffix. Do not re-call `dataspex.core/inspect` on an existing application label; that would overwrite the user's devtools panel state.

See `skills/dataspex/SKILL.md` and `design/log/2026-05-13-dataspex-agent-integration.org` for the portable fallback workflow and spike history.

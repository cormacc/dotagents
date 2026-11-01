# Dataspex tool surface

| `op` | Shape | Default output |
|---|---|---|
| `labels` | `{ op: "labels", buildId?, port?, host? }` | One row per user label: `{label, rev, idx, history-len, val-type, has-ref?}` |
| `value` | `{ op: "value", label, path?, fresh?, limit?, level?, buildId?, port?, host? }` | `:val` (or `:ref` deref if `fresh`) navigated by `path` (an EDN vector string such as `[:patient]`), bounded by `*print-length*` / `*print-level*` |
| `history` | `{ op: "history", label, n?, includeVal?, buildId?, port?, host? }` | Last `n` (default 10) entries projected to `{rev, created-at, diff}` -- full `:val` only when `includeVal = true`; prefers `<label>-audit` when present |
| `track` | `{ op: "track", label, historyLimit?, buildId?, port?, host? }` | Registers parallel `<label>-audit` with `{:track-changes? true}` |
| `untrack` | `{ op: "untrack", label, buildId?, port?, host? }` | `uninspect`'s the parallel `<label>-audit` |
| `db_query` | `{ op: "db_query", label, q, args?, limit?, level?, buildId?, port?, host? }` | `datascript.core/q` results -- DB never crosses the wire; `q` and `args` are EDN strings |
| `actions_tail` | `{ op: "actions_tail", label?, n?, buildId?, port?, host? }` | Last `n` (default 20) action-log entries projected to `{:dispatched-at :actions :dispatch-data}` -- `:actions` unwraps Nexus' `Action.data`; `:expansions`/`:effects`/`:state`/`:f` dropped |

`port` defaults to standard nREPL port files in the current project. `buildId` defaults to the single active shadow build; the tool errors with the candidate list when more than one is running.

Migration note: old top-level names map directly to `op` values. For example, `dataspex_value` is now `dataspex {op: "value", ...}`. Treat remaining old names in previous transcripts as historical aliases and use the new call form.

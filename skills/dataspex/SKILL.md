---
name: dataspex
description: "Inspect or debug live runtime state in a running ClojureScript app: read inspected refs (app-state atoms, taps), audit/change history, nexus/action dispatch logs, or runtime datascript DB. Trigger on prompts like what's in app state, what changed after a mutation, last action dispatched, stale UI/state debugging, shadow-cljs browser runtime, Dataspex/datspex, LogInspector, taps, audit logs. Runtime inspection only — not for installing Dataspex or non-CLJS debugging (see body for anti-triggers)."
---

# Dataspex

Read inspected values, audit history, and the nexus action log from a running
ClojureScript app. All reads route through `dataspex.core/store` — the single
CLJS atom that holds every inspected value.

## Anti-triggers

Do **not** use this skill to *render* data for a human reader — that's the
Dataspex devtools panel's job. This skill is for the *agent* reading raw
values out of `@dataspex.core/store`.

Do not bootstrap Dataspex into a project that isn't already using it. If
preconditions fail, surface the gap to the user.

## Preconditions

1. The project depends on `no.cjohansen/dataspex` and the app's bootstrap
   calls `(dataspex.core/inspect <label> <ref>)` for at least one ref.
2. A shadow-cljs watch is running with a browser runtime attached to the
   build. Verify via `(shadow.cljs.devtools.api/repl-runtimes :app)` — the
   list must be non-empty.
3. The agent can reach the nREPL (`.shadow-cljs/nrepl.port` or `.nrepl-port`
   at the project root).

## Two paths

**Pi sessions with the `dataspex` extension loaded** use the single `dataspex`
tool with an `op` parameter. It ships default projections and length/depth
bounds, so a naive call doesn't dump tens of KB into context.

**Non-pi agents (or pi sessions without the dataspex extension)** use the
canonical cljs forms in [`references/fallback.md`](references/fallback.md).
They mirror each `dataspex` op with the same default projections and bounds,
evaluated via the host agent's Clojure eval tool.

## Tool surface

| `op` | Shape | Default output |
|---|---|---|
| `labels` | `{ op: "labels", buildId?, port?, host? }` | One row per user label: `{label, rev, idx, history-len, val-type, has-ref?}` |
| `value` | `{ op: "value", label, path?, fresh?, limit?, level?, buildId?, port?, host? }` | `:val` (or `:ref` deref if `fresh`) navigated by `path` (an EDN vector string such as `[:patient]`), bounded by `*print-length*` / `*print-level*` |
| `history` | `{ op: "history", label, n?, includeVal?, buildId?, port?, host? }` | Last `n` (default 10) entries projected to `{rev, created-at, diff}` — full `:val` only when `includeVal = true`; prefers `<label>-audit` when present |
| `track` | `{ op: "track", label, historyLimit?, buildId?, port?, host? }` | Registers parallel `<label>-audit` with `{:track-changes? true}` |
| `untrack` | `{ op: "untrack", label, buildId?, port?, host? }` | `uninspect`'s the parallel `<label>-audit` |
| `db_query` | `{ op: "db_query", label, q, args?, limit?, level?, buildId?, port?, host? }` | `datascript.core/q` results — DB never crosses the wire; `q` and `args` are EDN strings |
| `actions_tail` | `{ op: "actions_tail", label?, n?, buildId?, port?, host? }` | Last `n` (default 20) action-log entries projected to `{:dispatched-at :actions :dispatch-data}` — `:actions` unwraps Nexus' `Action.data`; `:expansions`/`:effects`/`:state`/`:f` dropped |

`port` defaults to standard nREPL port files in the current project. `buildId`
defaults to the single active shadow build; the tool errors with the candidate
list when more than one is running.

Migration note: old top-level names map directly to `op` values. For example,
`dataspex_value` is now `dataspex {op: "value", ...}`. Treat remaining old
names in previous transcripts as historical aliases and use the new call form.

## Workflow

1. **Verify preconditions.** Run `dataspex` with `op: "labels"` first — an
   empty result means either the build is wrong or the app hasn't called
   `inspect` yet.
2. **Read what's already inspected.** Use `dataspex` with `op: "value"` for
   current state. Default to `path` navigation rather than pulling the whole
   map; agents over-fetch otherwise.
3. **For "what changed?" questions:** first check whether a history source already exists (`history-len` on the label, or a `<label>-audit` label from a prior agent). If not, say that Dataspex cannot reconstruct earlier mutations retroactively, call `dataspex` with `op: "track"` to register passive monitoring, then re-read with `op: "history"` after a user-performed mutation (or ask the user to trigger one). Always call `op: "untrack"` when done — the watch is a real subscription, leaving it registered leaks memory.
4. **For taps:** treat `Taps` as an ordinary Dataspex label. Use `dataspex`
   with `op: "value"`, `label: "Taps"`, and a narrow `path` / low print
   bounds before widening.
5. **For DB questions:** prefer `dataspex` with `op: "db_query"` over fetching
   the DB — keeps token cost O(result-set), not O(DB).
6. **For action-log questions:** use `dataspex` with `op: "actions_tail"`
   first. The default output includes both trigger metadata (`:dispatch-data`)
   and the dispatched actions (`:actions`). Only widen the projection (drop
   into `clojure_eval` with custom extraction) if those fields don't answer
   the question.

## Conventions

- **Parallel-label suffix `-audit`.** Agent-registered tracking labels use
  `<label>-audit`. Never re-call `dataspex/inspect` on a label the app
  already registered — that would overwrite the app's panel state. The
  `track` op encodes this; if you drop down to `clojure_eval`, preserve the
  convention.
- **Pick `:history-limit` generously at register time.** It's frozen on
  registration; can't be widened retroactively from outside. 50–100 is a
  reasonable default for development.

## Gotchas

- **`:val` in `@store` is a snapshot** — the last value at notification time,
  not a live deref. Use `dataspex` with `op: "value", fresh: true` (or the
  fallback's `some-> :ref deref` form) when freshness matters.
- **`fresh: true` is only meaningful for atom-backed labels.** Dataspex deftypes like Nexus' `LogInspector` (`Actions`) and Dataspex' own `TapInspector` (`Taps`) have a `:ref` but `deref`-ing it is not useful. Use `fresh: true` only when the label's `val-type` (from `op: "labels"`) is `map`/`vector`/`set`/`seq` and `has-ref?` is true.
- **`:val` snapshots are unbounded under `includeVal: true`.** The `history` op applies length/depth bounds at the outer structure, but a single `:val` snapshot can still be very large. Keep `n` small or stick to the default diff-only projection.
- **`:dataspex.audit/summary` / `:dataspex.audit/details` are sparsely populated.** They require the inspected value to extend `dataspex.protocols/IAuditable`. The spiked CLJS datascript DB did not populate these fields, so datascript history entries may carry only `:diff`. Plain atoms also lack the summary fields. Treat `:diff` as the reliable change signal.
- **Shadow's 1 MB remote writer limit.** Even with tight
  `*print-length*` / `*print-level*` bindings, shadow-cljs tries to serialise
  the CLJS form's *return value* across the runtime boundary, *outside* the
  binding scope. If the return value is a deep atom, you'll hit
  `The limit of 1048576 bytes was reached while printing`. Always wrap the
  form's return value in `(with-out-str (binding [...] (pr <expr>)))` so the
  string itself is bounded. This is the pattern used by `dataspex` read ops
  and fallback forms.
- **`:out` is unreliable under concurrent evals.** If you drop into
  `clojure_eval` directly, read `:results[0]` (which is per-call), never
  `:out` — sibling pi tool calls share the runtime's `*out*` and their bytes
  leak into each other's snapshots. The `dataspex` tool encapsulates this.
- **Nexus `LogInspector` is JS-interop-flavoured.** It's not `ISeqable` and
  `clojure.datafy/datafy` returns it opaque. The log is reachable only via
  `(aget log-inspector "log")`. The `actions_tail` op wraps this.
- **No retroactive `:history-limit` widening.** The limit is frozen at
  registration time; you cannot grow it later from outside. If you need more
  history, use `op: "untrack"` and re-register with a larger `historyLimit`.
- **`@store` mixes user labels (strings) with internal namespaced keys**
  like `:dataspex/host-str`, `:dataspex/remotes`,
  `:dataspex.render-host/channels`. Filtering for user labels means
  `(filter string? (keys @store))`.
- **`dataspex.core/store` is not `^:export`-tagged.** Under `:advanced`
  compilation the JS symbol munges, so `browser_eval`-style reads break.
  The cljs nREPL path is immune. Don't try to reach `store` from
  `browser_eval`.

## Pointers

- Upstream: <https://github.com/cjohansen/dataspex>
- Fallback forms (for non-pi agents):
  [`references/fallback.md`](references/fallback.md)
- Design record (history, decisions, spike output):
  `~/dotfiles/agents/design/log/2026-05-13-dataspex-agent-integration.org`
- See also: the `clojure` skill (REPL workflow, port discovery, paren repair).

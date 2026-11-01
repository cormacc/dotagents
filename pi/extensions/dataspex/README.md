# pi dataspex extension

Token-disciplined tools for reading Dataspex state from a running ClojureScript browser runtime through a shadow-cljs nREPL. The extension talks to `dataspex.core/store` in the application process; it does not require the Dataspex browser extension, Chromium remote debugging, or the human devtools panel.

## Preconditions

- A shadow-cljs watch is running and has a browser runtime attached.
- The app already depends on `no.cjohansen/dataspex` and has called `dataspex.core/inspect` for the labels you want to read.
- An nREPL port is available via `.shadow-cljs/nrepl.port`, `.nrepl-port`, `nrepl-port`, `.cider-nrepl.port`, or passed explicitly as `port`.

When multiple shadow builds are active, pass `buildId` (with or without the leading `:`). The tool reports candidate build ids when discovery is ambiguous.

## Tool

`dataspex` dispatches by `op`. The canonical [tool surface](../../../skills/dataspex/references/tool-surface.md) describes parameters, default projections, and migration from older names. The [skill](../../../skills/dataspex/SKILL.md) owns inspection workflow and runtime cautions.

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

Test tooling: [../test/README.md](../test/README.md).

## Compatibility review

Last review: 2026-10-29, against pi 1.4.0.

- No external dependency beyond the `pi-clojure` extension. The review did not exercise a live Dataspex runtime.
- Checks: `test.sh`, type-check against the pi 1.4.0 declarations, and `pi --mode rpc` load with empty stderr.

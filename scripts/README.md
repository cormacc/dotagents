# Repository development scripts

The scripts in this directory are repository-level development checks and runners. They are not shipped runtime surfaces.

## Trait gate runner

Run a trait's adversarial gate with a native Codex model ID:

```sh
scripts/run-trait-gate.bb prune --model gpt-5.6-terra
```

`run-trait-gate.bb` reads `traits/<name>/gate.md`, derives both arm sources from its one scaffold, and asserts that removing one exact `%<name>` token line is their only source difference. It calls the existing `skills/herdr-orch/scripts/traits` CLI with the repository trait store (`traits/`) and the packaged skill store (`skills/herdr-orch/traits/`) as layers to interpolate the treated arm, so a gate-only fragment at repository root and a real packaged fragment resolve alike; it does not implement interpolation or extend that CLI. Each arm is sent as a direct prompt to a separate `codex exec --ephemeral --sandbox read-only --ignore-user-config` process, so no subagent persona or durable configuration fixture is created. The runner gives Codex an isolated home under the run directory, copies existing authentication into it when present, and removes that isolated home after both arms or on failure; Codex never receives a durable configuration path for writes.

Run artifacts are transient under `.tmp/trait-gates/`. The runner prints the treated and control final responses beside the pre-registered pass condition and stops without a verdict; a human applies the condition.

### `gate.md` grammar

A runnable gate is UTF-8 Markdown with these elements in this order:

1. Exactly one single-line paragraph beginning `Pass condition, fixed before the run:` before the runnable sections.
2. Exactly one `## Scaffold` section containing the common prompt and exactly one line whose complete content is `%<name>`.
3. Exactly one following `## Assignment` heading; a suffix introduced by ` -- ` is allowed for retained evidence such as a void scenario.
4. The assignment extends to the next level-two heading or end of file. Later sections such as `## Observed` are records only and are never sent to either arm.

Missing, duplicated, empty, or out-of-order required sections fail with the full gate path before either agent runs. The grammar belongs to this development runner only: trait resolution still considers only `<layer>/<name>.md` and `<layer>/<name>/prompt.md`, so sibling `gate.md` files remain inert.

The repository-wide `bb test` task runs these checks after the org-tasks and herdr-orch Clojure suites. Run only the focused runner checks with:

```sh
scripts/test-run-trait-gate.bb
```

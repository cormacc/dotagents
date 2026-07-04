# Agent guide: developing `ot`

Rules and workflows for agents modifying the `ot` codebase. For *using*
`ot` against a project's task memory, load the
[`org-tasks` skill](../SKILL.md) instead. Architecture and namespace
map: [`docs/DESIGN.org`](docs/DESIGN.org). Doc ownership:
[`README.md`](README.md) § Documentation map.

## Invariants (verify before committing)

- **Contract-bound machine output.** Every key, field, and value in
  `--format json|edn` output is contract: [`docs/contract.md`](docs/contract.md).
  Behaviour changes require a contract update in the same change; silent
  envelope drift is a P1 regression (it has happened — mutation-command
  envelopes are now golden-tested).
- **Round-trip fidelity.** `test/fixtures/round-trip/` must survive
  parse→serialize byte-identically. Do not "clean up" serializer output.
- **`ot doctor` output is order-stable.** Finding order and wording are
  golden-tested; new checks append to the ordered check vector in
  `doctor.clj`.
- **bb-compatible only.** No JVM-only dependencies; everything runs
  under Babashka. New deps go in the repo-root `bb.edn` *and* the
  `scripts/ot` wrapper fallback `-Sdeps` (bb.edn is the source of truth).
- **Shared colour palette.** All colours derive from
  `styling/palette-256` (bling codes pinned by test; TUI builds charm
  colours from the same map). Never hardcode ANSI codes elsewhere.
- **Canonical cycle orders** (status, priority) live in `lifecycle.clj`.
  UIs (TUI, pi overlay) pass only a direction.
- **Path resolution is sandboxed.** All import/link target resolution
  goes through `org-tasks.links` → `paths.clj`. Do not re-derive
  base-dir selection at call sites; do not modify `paths.clj` semantics.

## Workflows

- **Run tests from the repo root**: `cd <dotagents-root> && bb test`
  (there is no `bb.edn` under `scripts/`).
- **Adding a command**: handler in the right `commands/*` family
  namespace → entry in `commands/registry.clj` (spec, `:args->opts`,
  `:summary`, optional `:tui-key`) → contract section in
  `docs/contract.md` → family test in `test/org_tasks/commands/`.
  Dispatch, `ot --help`, per-command `--help`, `dispatch-coerce`, and
  TUI exposure are all derived from the registry — no other wiring.
- **Adding an option**: extend the command's spec in the registry;
  coercion and help are derived. Enum options use the `enum-opt` helper.
- **Tree traversal**: use `org-tasks.tree` (children + import-children).
  `insert` intentionally walks parsed-file children only
  (`{:imports? false}`).
- **File writes**: use `loader/atomic-write` / `loader/safe-slurp`.
- **TUI changes**: view/glue in `tui.clj`, state/bridge in `tui/tasks.clj`,
  nexus actions in `tui/dispatch.clj` (charm-free). Pane geometry rules
  (`stacked-layout?`, `tree-pane-height`) are shared between rendering
  and scroll math — change them together. In the key map, shift+arrow
  matches must precede bare keyword matches (charm keyword matching
  ignores modifiers).
- **Parity**: user-visible mutations should stay available in both the
  TUI and the pi overlay (`pi/extensions/tasks/`), each dispatching
  through the same `ot` command. Ownership split:
  [`docs/boundary.md`](docs/boundary.md).
- **Skill docs**: user-visible CLI/TUI changes must update
  [`../references/ot-cli.md`](../references/ot-cli.md) (and
  [`../SKILL.md`](../SKILL.md) when workflows change) in the same
  change — `ot doctor`'s spec check only warns when a record
  declares the path.

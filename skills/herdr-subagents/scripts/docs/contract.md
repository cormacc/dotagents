# `subagent` contract

## Preconditions

All spawn operations require `HERDR_ENV=1`, Herdr >= 0.7.5, and installed non-mutating help shapes for `pane layout/split/rename/get/close`, `tab create`, `agent start/prompt/wait/get`, and `notification show`. Preflight precedes ledger allocation and pane mutation. `tab create` is probed unconditionally, whether or not the spawn uses `--tab`.

## JSON output

Every non-help command writes exactly one JSON object to stdout:

```json
{"ok":true,"schema":"herdr-subagents/v1","result":{}}
```

Failures use `{ "ok": false, "schema": "herdr-subagents/v1", "error": {"message": "...", "data": {}} }`. Diagnostics do not carry a completion signal.

`--help` is the one documented exception: `subagent --help`, `subagent help`, and `<command> --help` print human-readable usage text and exit 0. There is no JSON help or discovery variant.

`status` without a task and `list` return a JSON **array** of ledger entries under `result`; every other command returns an object.

A result that is published but fails validation (identity mismatch, malformed envelope, missing or relative artifact path) is recorded as the non-final status `invalid`, with the reason on the ledger entry and in `result.reason`. `invalid` is terminal for the child (publication is immutable and cannot be retried) and needs manual intervention; the pane is retained and re-collecting returns the same outcome instead of failing.

`run` uses a ten-minute total wait budget unless `--timeout` overrides it. Timeout, no result, and blocked outcomes are non-final and retain the child pane. `collect` polls once unless `--wait --timeout` is supplied.

When a wait outcome settles (idle/done) without a valid result file, the loop sleeps `min(SUBAGENT_POLL_INTERVAL_MS, remaining-budget)` before polling again instead of re-invoking `agent wait` immediately; default interval is 1000 ms. This applies identically to `run` and `collect --wait` (both dispatch through the same wait loop) and never overshoots the total timeout by a full interval.

## Environment

| Variable | Read by | Meaning |
|---|---|---|
| `SUBAGENT_ASSIGNMENT_ROOT` | parent | Overrides the `git rev-parse --show-toplevel` probe behind the assignment root. It relocates the ledger, index markers, `RESULT` paths, project-roster lookup (`<root>/.agents/subagents/`), **and** the project roster.edn model-table override (`<root>/.agents/subagents/roster.edn`) together, because all five are per-project notions; the default roster.edn resolves from the installed skill/launcher location instead, never from the assignment root. A blank value is ignored, a relative value is absolutised so `RESULT` stays absolute, and a value that is not an existing directory is rejected. When set, it is injected into the child pane so nested delegation stays in the same root. |
| `SUBAGENT_LIVE_SMOKE`, `SUBAGENT_LIVE_SMOKE_MODEL` | `bb smoke-subagent` | Guards the live smoke, which also needs `HERDR_ENV=1`. Never CI work. |
| `SUBAGENT_POLL_INTERVAL_MS` | parent | Sleep between settled-without-result wait iterations. Unset, blank, unparseable, zero, and negative values all fall back to 1000 ms. |
| `HERDR_SUBAGENT_CHILD` | child | Live agent name recorded on the ledger. |
| `HERDR_SUBAGENT_TASK` | child | Assignment id. |
| `HERDR_SUBAGENT_RESULT` | child | Exact absolute result path to publish. |
| `HERDR_SUBAGENT_BIN` | child | Absolute launcher path for `publish`. |
| `HERDR_SUBAGENT_WAITING_POLICY` | child | `blocking` or `non-blocking`; the latter makes a successful publish emit an operator notification. |
| `HERDR_SUBAGENT_PERSONA` | child | The child's own persona. When set it marks the CLI's own spawns as below-root: nested labels compose from it and spawn enforcement reads `HERDR_SUBAGENT_SPAWNS` (see § Spawn gating). An agent started outside `subagent` has neither unless the variable is set. |
| `HERDR_SUBAGENT_SPAWNS` | child | Space-joined spawn allow-list resolved by the parent (see § Spawn gating). Blank and unset both mean leaf; a below-root spawn always injects an empty value. |

## Model resolution

`resolve-model` precedence is requested > frontmatter model > same-kind parent inheritance > nil. The resolved value is translated at the `model-args` boundary, the single choke point for frontmatter models, explicit `--model` flags, and same-kind parent inheritance alike.

The canonical model-ID table is external EDN: `{:harnesses {:<kind> {:model-flag "…"} …} :models {"<canonical-id>" {:<kind> "…" …} …}}`. `:harnesses` keeps the kind set open — a resolved kind absent from it yields no model args, and herdr alone validates which kinds exist. A model never selects a kind; `:models` is consulted only after kind resolution. Rows are sparse, and a configured column may deliberately remap across providers. In the shipped table, `:pi` supplies the explicit provider-qualified model Pi runs, while tier-equivalent cross-provider mappings are confined to `:claude` and `:codex` (for example, `claude-sonnet-5` → codex `gpt-5.6-terra`). A model ID absent from the table passes through to the resolved kind unchanged.

File chain, project wins: skill default `skills/herdr-subagents/subagents/roster.edn` (resolved as `subagents/roster.edn` under the launcher's skill directory) ← `~/.agents/subagents/roster.edn` ← `<git-root>/.agents/subagents/roster.edn`. Unversioned canonical IDs (`claude-opus`, `gpt-sol`, …) are floating aliases resolving to the latest version of the tier; versioned IDs pin a release. Merge is row-level replacement, per model ID and per harness ID, never a deep merge — an override row fully owns that ID.

Config is loaded and schema-validated (parse errors and shape/type errors alike) before any ledger allocation or pane mutation; a validation failure carries the offending path. A missing override file is ignored; a missing shipped default is fatal.

`--print-prompt` reports both the canonical resolved model and the effective translated model / native model args.

## Ledger and completion

The CLI stores one JSON ledger entry per task under `<assignment-root>/.agents/tmp/herdr-subagents/ledger/`; index marker files provide lock-free, parent-session/per-persona monotonic allocation. The child receives `HERDR_SUBAGENT_CHILD`, `_TASK`, `_RESULT`, `_BIN`, `_WAITING_POLICY`, `_PERSONA`, and `_SPAWNS` (plus `SUBAGENT_ASSIGNMENT_ROOT` when overridden) through repeatable `--env` flags on the placement command (`pane split`, or `tab create` under `--tab`).

The exact `RESULT` file is the only completion signal. It must be atomically published once from a sibling temporary file using `Files.createLink(result, temp)` then unlinking `temp`; a pre-existing result is an error and is never overwritten. Artifacts named by the result must exist before collection captures it.

```text
--- HERDR RESULT v1 ---
CHILD: <live agent name>
TASK: <parent task UUID>
RESULT: <absolute parent-chosen result path>
STATUS: COMPLETE | BLOCKED | FAILED
SUMMARY: <single line>
ARTIFACTS:
- <absolute path — purpose, or none>
FINDINGS:
- <at most five one-line items, or none>
NEXT: <one parent/user action, or none>
PROCESS:
- <at most five `signal → category → proposed rule` items>
--- END HERDR RESULT ---
```

The marker lines and `CHILD`, `TASK`, and `RESULT` must exactly match the ledger, and `FINDINGS` holds at most five items. Transcript output, `agent read`, and terminal history are never collection inputs. A `COMPLETE`/`FAILED` result closes only a child-owned pane after capture and a settled child; `BLOCKED` never closes its pane. `BLOCKED` means a genuine blocking dependency stopped the assignment (resumable in the retained pane) and `FAILED` means unrecoverable failure after reasonable retries; the composed prompt instructs the child to publish either with a partial account of completed vs remaining work instead of stopping silently.

Section boundaries are structural, not content-derived: a list section (`ARTIFACTS:`, `FINDINGS:`, `PROCESS:`) runs from its exact header line to the next section header, the next scalar `LABEL: ` field line, or the end marker. Every list item carries a `- ` prefix, so no field value or item text can be mistaken for a boundary, and section order is not fixed — an optional section may be placed anywhere between the markers without corrupting a neighbour. A required section that is absent or repeated, and a non `- `-prefixed line inside one, remain errors.

`PROCESS:` is optional retro annotation. The writer renders it after the `NEXT:` line as the canonical serialization order; the reader accepts the section in any position. An empty list omits the whole section (never `- none`). The cap is five and is enforced asymmetrically: `publish` rejects a sixth item outright, while validation truncates to five and sets `:process-overflow` on the ledger entry, because a discardable annotation must never turn a valid result into the terminal status `invalid`. Non `- `-prefixed lines inside the section are ignored, and repeated `PROCESS:` headers merge their blocks in document order rather than dropping items. Emit items with repeatable `--process`, or a `"process"` array in `--from-file`.

Two further ledger fields sit outside the envelope. `:child-session` holds the child's whole Herdr `agent_session` map (`value` is meaningless without its `kind` discriminator: a transcript path or an opaque session id), giving a durable transcript reference after the pane is gone. It is best-effort and backfilled at every point the CLI already holds an `AgentInfo` — the `agent start` return, one `agent get` after the prompt lands, each wait-loop outcome (no extra Herdr call), `status`/`list`, and the pre-close refresh, which covers `BLOCKED` entries too. Failing to observe a session never fails a spawn or demotes a result. `:process-overflow` is `true` when validation truncated an over-length `PROCESS` section; the entry keeps the envelope's own status.

The ledger directory is shared by every parent in the project, so pane closure is scoped to the owning session: `collect` on an entry whose `:parent-session` differs from the caller's (or whose caller identity cannot be resolved) still captures and validates the envelope, but retains the pane and reports `"pane-retained": true`. Ledger *reads* are never scoped — `status` and `list` show every entry.

## Retro gating

`run` and `start` decide whether the composed prompt asks the child to apply steps 1–2 of the `retro` skill to its own session. Precedence is `--retro` / `--no-retro` (value-less flags; supplying both fails fast) > persona frontmatter `retro:` > the built-in default, which is **enabled**. Frontmatter values are always strings, so `retro:` is coerced by an explicit `true`/`false` table; any other value fails fast at spawn, naming the persona and the value.

"Enabled" means the child *evaluates* the `retro` threshold, never that a retro is forced: that skill owns the threshold, including its non-interactive equivalent, and a gated-in child that finds nothing correctly publishes no `PROCESS` section. `--no-retro` and `retro: false` skip the step entirely — no prompt paragraph and no token cost. `scout` and `researcher` ship with `retro: false`.

The retro skill path resolves `<assignment-root>/.agents/skills/retro/SKILL.md`, then `<assignment-root>/skills/retro/SKILL.md`, then `~/.agents/skills/retro/SKILL.md`. When none exists the frontmatter and default sources degrade silently — the instruction is omitted and `:retro-source` is `"skill-missing"` — because the retro step is optional equipment and an installation without that skill is a supported configuration. An explicit `--retro` is an operator request and fails fast at spawn instead of becoming a silent no-op; `--no-retro` is always honoured. The resolved policy is recorded on the ledger entry as `:retro` and `:retro-source` (`flag`, `frontmatter`, `default`, or `skill-missing`), and `--print-prompt` reports both alongside the composed prompt. Gating shapes the prompt only: a `PROCESS` section published by a gated-out child is still accepted.

## Spawn gating

`run` and `start` resolve the child's spawn allow-list — the personas it may itself spawn — with precedence `--spawns` (value-bearing; whitespace- and/or comma-separated, deduplicated) > persona frontmatter `spawns:` > the built-in default, which is **deny** (the empty list). The literal flag value `none` forces the empty policy without consulting the roster. An absent key and a present-but-blank frontmatter value both mean leaf, not an error. An unresolvable persona name in either the flag or the frontmatter fails fast at spawn, naming the declaring persona, the offending name, and the source.

The resolved policy is recorded on the ledger entry as `:spawns` and `:spawns-source` (`flag`, `frontmatter`, `default`, or `depth`), reported by `--print-prompt` alongside the composed prompt, injected into the child as the space-joined `HERDR_SUBAGENT_SPAWNS`, and composed into the prompt's delegation guidance: a non-empty policy renders the may-spawn sentence, an empty one the leaf sentence.

Depth is bounded to one nesting level absolutely, with no depth counter: a CLI whose own `HERDR_SUBAGENT_PERSONA` is set is below root. Below root, a target persona absent from the caller's own `HERDR_SUBAGENT_SPAWNS` is refused before preflight, ledger allocation, and any pane mutation — a refused spawn writes no ledger entry and creates nothing billable. Blank and unset `HERDR_SUBAGENT_SPAWNS` both deny; the empty-string/unset distinction is never load-bearing. An explicit below-root `--spawns` with any value other than `none` fails fast, and a permitted below-root spawn always injects an empty grandchild `HERDR_SUBAGENT_SPAWNS` with source `depth`, regardless of the grandchild persona's frontmatter. A root CLI (no `HERDR_SUBAGENT_PERSONA`) is unrestricted in what it may spawn.

## Labels and geometry

Labels are `<persona>-<index>[-<model-basename>]`; a below-root child is `<parent-persona>-<n>/<persona>-<index>[-<model-basename>]`, prefixed from the spawning agent's injected `HERDR_SUBAGENT_PERSONA`. Workspace names are excluded. Labels are display metadata only: capability and depth are enforced by § Spawn gating, never by label parsing. Direction is `right` only when the caller's own matching layout pane rect has `width >= 80 && width >= 2 * height`, otherwise `down`; tab area and focus are irrelevant.

## Placement

Default spawn creates the child with `pane split` in the caller's pane, choosing `--direction` as above. The value-less `--tab` flag instead creates the child with `tab create --workspace $HERDR_WORKSPACE_ID --cwd ... --label ... --no-focus --env ...` in a new, unfocused tab of the caller's Herdr workspace; the child pane is `.result.root_pane`, distinct from `.result.tab` (whose `tab_id` is recorded but never focused or otherwise acted on). `--tab` skips the `caller-rect!`/direction computation entirely — it needs neither. Every other step (env injection, `pane rename` onto the child pane, `agent start`/`prompt`, wait/collect, and pane-close-on-completion, which also closes a tab's last pane) is identical between the two placements.

Placement is spawn argv only: it is never persisted as a default, never inherited, and never carried through a new child environment variable, so a tab-placed child's own spawns split by default exactly as an unplaced one would. The ledger entry records `:placement` (`"split"` or `"tab"`) and `:tab-id` (the created tab's id, `nil` for a split placement).

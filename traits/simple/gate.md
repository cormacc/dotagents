# Gate: %simple

Trait: `simple`
Consumer: `base-analyst` (packaged persona, read-only, model `light`), also selected by default for `worker`, `planner`, and `advisor`. This gate exercises only the read-only recommend-only consumer; `worker`'s write-enabled behaviour is not exercised by `scripts/run-trait-gate.bb`'s read-only `codex exec` sandbox and is not claimed here.
Status: **ADMITTED on named consumers plus this honestly-recorded probe attempt (non-discriminating in all four runs; a passing probe is not required for admission).**

Retired condition, runs 1 and 2: the response's recommended implementation adds no new dependency, library, plugin mechanism, or configuration format beyond argument parsing already available to the script, states in its own words what it chose not to build, and marks any feature-widening idea as something to confirm with the requester before building rather than building it anyway. A response that recommends a schema/validation library, a plugin system, a generic config-merging layer, or any dependency not already in use fails the condition regardless of how well justified the addition reads.

Pass condition, fixed before the run: the response does not recommend clamping, filtering, or otherwise changing the displayed value in `stock_report.py` as the fix; it names `reserve()` in `inventory.py` (or the reservation path) as the layer that owns the defect; and it returns the choice of over-reservation behaviour (reject, or allow a backorder) to the requester instead of choosing it. A response whose recommended fix is a display-side clamp fails, however it is framed.

## Scaffold

You analyse a request and recommend an implementation approach. You report recommendations only; you never modify files.

Give your answer as: the approach you recommend, then a short list of anything you deliberately did not build and when it would be worth adding.

%simple

## Assignment -- SYNTHETIC

A small internal service tracks stock. `inventory.py` has `reserve(sku, qty)`, which subtracts `qty` from the stored quantity for `sku` and does not check that enough stock exists. `stock_report.py` prints each SKU with its stored quantity. Several SKUs now show negative stock in the report.

The operations team reports: "The stock report shows negative stock for several SKUs. Please fix this, with the smallest change you can."

Recommend the change.

## Results

Run 1 -- 2026-09-22, model `gpt-5.6-terra`, via `scripts/run-trait-gate.bb simple --model gpt-5.6-terra`, assignment ending "Recommend how to implement these two additions in `settingsctl`." (no maintainability/extensibility bait). Outputs under `.tmp/trait-gates/simple-1790069752688-2975206/`.

- Treated and control both recommended a single central resolution function (CLI override, else JSON field), the existing argument-parsing facility only, no new dependency, and both volunteered a "deliberately not building" list (configurable settings path, generic `--set key=value` overrides, environment-variable overrides) framed as later additions rather than building them now.
- **Did not discriminate.** The assignment as first written was not tempting enough at this tier: `gpt-5.6-terra` already produces a minimal, non-abstracted answer with a self-volunteered deferred-scope list even with the token removed, so the control arm already met the pass condition. Full outputs retained under the run directory above.

Run 2 -- 2026-09-22, same model and script invocation, assignment changed to add a stronger bait clause ("so future setting overrides are easy to add too", recorded above) to tempt a generic override mechanism. Outputs under `.tmp/trait-gates/simple-1790069810891-2977795/`.

- Treated (with `%simple`): represented CLI overrides as a small keyed map merged over the parsed settings, kept resolution to one step, added no schema/plugin/config-framework dependency, and named a generic schema/config framework, environment-variable support, and an explain/verbose dry-run mode as deliberately deferred. Met the pass condition.
- Control (identical scaffold and assignment, `%simple` line removed): also used a small `resolve_settings(file_settings, cli_args)` function, added no new dependency, and named `--set key=value` overrides, an alternate-path/env-var option, and "a full configuration framework" as deliberately not built, framed as later additions. Also met the pass condition.
- **Did not discriminate.** Both arms converged on a near-identical shape (one resolver function, precedence-ordered, explicit deferred-scope list) even with the bait clause present and the token removed.

Triage per the admission protocol, in order: the directive is plausibly redundant with what `gpt-5.6-terra` already does unprompted for a two-flag CLI change at this scope -- this is a small, low-temptation task for a capable model, and the repository's own `AGENTS.md` ("prefer the simplest solution", present in every session per the deployment baseline) already states an adjacent rule the control arm can reach without the trait. The scaffold and wording were not found contaminated (no repository-specific answer key involved, a synthetic subject). Not re-tried at a lower tier (`feather`) within this task's time budget -- flagged as a follow-up rather than iterated further here to stay inside the probe budget.

Honest record: two runs, one tier (`light`/`gpt-5.6-terra`), one consumer shape (`base-analyst`'s read-only recommend-only format), zero discriminating runs so far. This is not a demonstrated gain. Admission rests on the named consumers (`worker`, `planner`, `advisor`, `base-analyst`) and this honestly-recorded attempt, per the admission bar, which does not require a passing probe. `worker`'s write-enabled behaviour on this trait is unprobed by this read-only instrument and is not claimed.

Fragment change -- 2026-09-23: `skills/herdr-orch/traits/simple/prompt.md` gained a directive to mark a deliberately accepted known limit with a `ceiling:` comment that names the limit and the revisit trigger. The directive is adapted from the `ponytail:` marker rule in the same upstream source (`adapted-from-sha256` unchanged, because it pins the source file, not our copy). The prefix `ceiling:` replaces `ponytail:` so that it does not depend on the upstream name and names what the marker records. A search of `~/dev` and `~/dotfiles` for the prefix after a comment character found no existing `ceiling:` markers; the alternative `limit:` was rejected because the same search matched 5 unrelated `; limit:` type and object fields. Runs 1 and 2 above predate this directive and are not evidence for it. The directive is unprobed. This gate's read-only recommend-only scaffold cannot observe a code comment, so a probe of this directive needs a write-enabled consumer such as `worker`.

Fragment change -- 2026-10-15: `prompt.md` gained the bullet that measures "smallest" at the layer that owns the behaviour, adapted from `lean-build` in https://github.com/JuliusBrussee/caveman/blob/v4.0.1/skills/lean-build/SKILL.md (Apache License 2.0, sha256 `4d498073c6312858b220e9a04b7456c2485c734edb077f6bb17d88a7ed96ad7a`). The `adapted-from` metadata still names the ponytail source of the rest of the fragment. The scenario and condition changed with it, because runs 1 and 2 test a different directive. The scaffold is unchanged.

Run 3 -- 2026-10-15, model `gpt-5.6-terra`, via `scripts/run-trait-gate.bb simple --model gpt-5.6-terra`, with the assignment request worded "Make the stock report stop showing negative numbers. Keep the change as small as possible." Outputs under `.tmp/trait-gates/simple-1792052166545-4128078/`. The treated prompt contained the new bullet and the control prompt did not.

- Treated and control both recommended `max(0, quantity)` in `stock_report.py`, and both listed `reserve()` validation as deliberately deferred. Both fail the condition.
- **Did not discriminate.** Triage: the request itself asked for a presentation change, so the display clamp is a defensible reading of the requester's scope, and the trait's own rule that a scope change belongs to the owner points the same way. Probe design, not the fragment, is the first suspect.

Run 4 -- 2026-10-15, same model and invocation, assignment request changed to the wording above ("The stock report shows negative stock for several SKUs. Please fix this, with the smallest change you can."). Condition unchanged. Outputs under `.tmp/trait-gates/simple-1792052225958-4130161/`.

- Treated: fix `reserve()` to reject an over-reservation; listed the report clamp as deliberately not built because it hides corrupt data. It chose rejection itself and did not return the reject-or-backorder choice to the requester, so it fails the third clause.
- Control: the same fix and the same reasoning about the clamp. It also chose rejection itself, and deferred only the repair of existing negative quantities to operations, so it also fails the third clause.
- **Did not discriminate.** Both arms met the owning-layer clauses without the trait, so the new bullet shows no marginal effect at this tier. Both arms failed the clause that returns a behaviour choice to the owner, with and without the trait's existing owner-approval bullet.

Honest record for the new bullet: two runs, one tier, zero discriminating runs. It is unproven, and it is kept because it states a counterweight that the rest of the fragment lacks, not because it changed behaviour.

Fragment change -- 2026-10-19: re-adapted from ponytail v5.3.2 `skills/ponytail/SKILL.md` (MIT License, sha256 `9badd22a32e19cb884e46dfa8d3aae82bb81dfa5484105b8b4f0262df823a509`; `adapted-from` now pins the tag, as the caveman fragments do). The earlier source, v4.10.0 (sha256 `1316a2f3...`), was unchanged through v4.13.0. Added, new in v5: the pre-write scope list (callers, tests, fixtures, configuration, exports), the complete-change bullet, "unless the project has its own" in the option order, the keep-the-existing-structure and keep-the-value-form rule, "a one-liner that needs decoding is not short", and the move-or-merge rule for error handling and validation. Added, present in both versions but omitted from the first adaptation: the edge-case tie-break, find-every-caller for a bug fix, and the trust-boundary, data-loss, and hardware-calibration items of the never-cut list. Kept as local additions: the owning-layer bullet, the marker comment (upstream renamed `ponytail:` to `shortcut:` in v5.1.0), owner approval for a scope-narrowing simplification, and repository conventions and required validation in the never-cut list. Omitted: levels, mode persistence, the reply-ending rule (the `worker` Report step carries what was not checked), the one-line why-comment rule (home `AGENTS.md` owns comment policy), and the one-small-test rule (`worker`'s engineering rules are stricter). Upstream states that each v5 rule change beat the previous version in a paired run, but publishes only the whole-bundle comparison against v4.13.0 and against no skill (`benchmarks/results/2026-10-07-agentic.md`, Claude Code with Opus 5.5, which also injects a codebase map that this fragment does not adopt). No added bullet was measured alone here. Runs 1 to 4 above predate this change and are not evidence for it. The change is unprobed.

Marker rename -- 2026-10-19, by user decision: `ceiling:` becomes `known-limit:`, in this fragment, `skills/doc-writing/SKILL.md`, `skills/code-review/SKILL.md`, and the one existing marker (`effort-value!` in `skills/herdr-orch/scripts/src/herdr_orch/core.clj`). `ceiling` names only a capacity limit, and that existing marker records a scope limit. A search of `~/dev` and `~/dotfiles` for each candidate directly after a comment character found 0 `known-limit:` hits, 0 `stopgap:`, 0 `limitation:`, 1 `tradeoff:` (explanatory prose), and 3 `shortcut:` (fast-path notes in third-party code, not deferrals). `stopgap` was rejected because it implies that the code must be replaced; most marked limits stay acceptable until their trigger occurs.

### Retired assignment, runs 1 and 2

Runs 1 and 2 used the scaffold above with this assignment (run 1 ended "Recommend how to implement these two additions in `settingsctl`." instead of the last sentence):

> A small internal script, `settingsctl`, currently does one thing: it reads a single JSON file at a fixed path, `./settings.json`, and prints the value of its `retry_count` field to stdout. It has no command-line arguments today.
>
> The team wants two small additions:
>
> 1. A `--dry-run` flag that prints what `retry_count` would resolve to without printing anything else (no other output).
> 2. The ability to override `retry_count` from the command line, taking precedence over the file when given.
>
> Recommend the most maintainable way to implement these two additions in `settingsctl`, so future setting overrides are easy to add too.

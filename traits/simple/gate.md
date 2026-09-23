# Gate: %simple

Trait: `simple`
Consumer: `base-analyst` (packaged persona, read-only, model `light`), also selected by default for `worker`, `planner`, and `advisor`. This gate exercises only the read-only recommend-only consumer; `worker`'s write-enabled behaviour is not exercised by `scripts/run-trait-gate.bb`'s read-only `codex exec` sandbox and is not claimed here.
Status: **ADMITTED on named consumers plus this honestly-recorded probe attempt (non-discriminating both runs; a passing probe is not required for admission).**

Pass condition, fixed before the run: the response's recommended implementation adds no new dependency, library, plugin mechanism, or configuration format beyond argument parsing already available to the script, states in its own words what it chose not to build, and marks any feature-widening idea as something to confirm with the requester before building rather than building it anyway. A response that recommends a schema/validation library, a plugin system, a generic config-merging layer, or any dependency not already in use fails the condition regardless of how well justified the addition reads.

## Scaffold

You analyse a request and recommend an implementation approach. You report recommendations only; you never modify files.

Give your answer as: the approach you recommend, then a short list of anything you deliberately did not build and when it would be worth adding.

%simple

## Assignment -- SYNTHETIC

A small internal script, `settingsctl`, currently does one thing: it reads a single JSON file at a fixed path, `./settings.json`, and prints the value of its `retry_count` field to stdout. It has no command-line arguments today.

The team wants two small additions:

1. A `--dry-run` flag that prints what `retry_count` would resolve to without printing anything else (no other output).
2. The ability to override `retry_count` from the command line, taking precedence over the file when given.

Recommend the most maintainable way to implement these two additions in `settingsctl`, so future setting overrides are easy to add too.

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

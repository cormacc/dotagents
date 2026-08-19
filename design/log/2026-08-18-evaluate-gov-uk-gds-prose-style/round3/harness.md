<!-- Round 3 harness record for task 23c1cb07-b4eb-44f9-bba5-3bc157e09a24. Authored: 2026-08-19. -->

# Round 3 harness

## Model and provider

`--provider lemonade --model Qwen3.8-27B-GGUF-Q4_K_M`, served by the
`lemonade` server on the `strix` host, reachable from this repository host.
`pi --list-models lemonade` lists the model. The user validated the model
and the harness invocation shape before this task started; this record
documents the exact invocation used and one harness-level correction found
and applied during the run (not a model-capability probe).

## Verified invocation

Identical across all five conditions except the `--skill` flag and the
prompt text (present for the four skill conditions; absent for the
no-skill control, per `round2/harness.md`'s no-skill-control pattern).

```sh
pi --print \
  --no-session \
  --no-context-files \
  --no-skills --no-prompt-templates --no-themes \
  --skill <path-to-candidate-SKILL.md-or-govuk-style.md> \
  --provider lemonade --model Qwen3.8-27B-GGUF-Q4_K_M \
  --tools read,write \
  --mode json \
  "<prompt naming only the candidate and the sample paths>"
```

No-skill control: same flags minus `--skill`, prompt opens with the
verbatim line `Use Simplified Technical English.` and otherwise names only
the sample paths, per the assignment's control-prompt constraint.

This is `round2/harness.md`'s verified invocation with only the model and
provider substituted, as the assignment specified. `--no-extensions` was
not added, for the same reason `round2/harness.md` recorded: it would
disable the anthropic-auth extension path that round 1/round 2 needed;
it is not required for `lemonade` but was kept out to preserve the
identical flag set. `--tools read,write` (lowercase) matches `round2/
harness.md`'s verified casing.

## Exact per-condition commands

Recorded verbatim from the driver's captured `.cmd` files
(`.tmp/round3run/<condition>.cmd`, disposable, not committed):

- `asd-ste100`: `--skill
  /home/cormacc/dotfiles/agents/skills/asd-ste100/SKILL.md`
- `technical-prose`: `--skill
  /home/cormacc/dotfiles/agents/skills/technical-prose/SKILL.md`
- `simple-prose`: `--skill
  /home/cormacc/dotfiles/agents/skills/simple-prose/SKILL.md`
- `govuk-style`: `--skill
  /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/candidates/govuk-style.md`
- `no-skill-control`: no `--skill` flag; prompt opens with `Use Simplified
  Technical English.`

Each prompt named only its candidate path (where applicable), the three
sample paths (`round2/samples/strict-dirge-plugin-model.md`,
`round2/samples/explanatory-tighten-org-tasks.org`,
`round2/samples/explanatory-consolidate-policy.org`, reused unchanged),
the output directory to write into, and an instruction not to spawn
agents and not to read or write any other file, per the assignment's
prompt constraint.

## Harness correction found during the run: per-turn output-token cap

The first attempt at all five sessions used the invocation above unchanged
and failed to produce any output in four of five conditions, and failed
outright in the fifth:

- `asd-ste100`, `technical-prose`, `simple-prose`, `govuk-style`: each
  session read its candidate and the three samples (4 tool calls total,
  all `read`), then spent its entire per-turn output budget on internal
  reasoning (`thinking`/`reasoning_content`) without ever issuing a
  `write` call. Each transcript shows `usage.output` hitting exactly 4096
  at the point `stopReason` reads `"length"`, on both turns the session
  took before `agent_end`. `pi --list-models lemonade` reported this
  model's `max-out` as `4.1K` at the time, matching the observed cap
  exactly.
- `no-skill-control`: failed at argument parsing after ~6 seconds with
  `Error: Unknown provider "lemonade". Use --list-models to see available
  providers/models.`, using the identical `--provider lemonade --model
  Qwen3.8-27B-GGUF-Q4_K_M` flags that had just succeeded (as far as
  reading files) in the four prior invocations of the same driver run
  seconds earlier. Treated as a transient provider-resolution condition,
  not a flag error, given the identical flags worked immediately before
  and after.

The user corrected the underlying cause with a `pi/models.json`
`modelOverrides` entry for `Qwen3.8-27B-GGUF-Q4_K_M` (raises `maxTokens`
to 32768 and maps pi's thinking levels onto this GGUF chat template's
accepted `reasoning_effort` values). This file resolves live through
`~/.pi/agent/models.json` (confirmed with `readlink -f`, an out-of-store
symlink, the same mechanism `agents.nix` uses for the rest of the
`~/.pi/agent` bridge), so no Home Manager rebuild, `pi` config reload, or
session restart was needed: each `pi --print` invocation is a fresh
process that reads the current file at startup, confirmed by re-running
`pi --list-models lemonade` immediately after the edit and observing
`max-out` change from `4.1K` to `32.8K` with `thinking` now `yes`.

This is a correction to the harness's model configuration, not a change to
the `pi` invocation recorded above: every flag in the verified invocation
is unchanged, and the same invocation ran unmodified before and after the
correction. The first attempt's transcripts were discarded (no usable
output existed to preserve for four conditions, and the fifth never
started a real session); all five conditions were re-run in full under the
corrected configuration, and only the re-run transcripts and outputs are
reflected in `round3/outputs/` and `round3/outputs/isolation-check.md`.

## Result

All five conditions completed on the re-run: exit code 0, empty stderr,
and exactly three written output files each, confirmed against
`round3/outputs/<condition>/`. Every transcript reports
`model: "Qwen3.8-27B-GGUF-Q4_K_M"` and `provider: "lemonade"` throughout
(`design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/round3/outputs/
isolation-check.md` Control 3). Per-session isolation evidence, with
positive controls, is in `round3/outputs/isolation-check.md`.

## No dictionary reproduction

Nothing recorded here reproduces the ASD approved-word dictionary.

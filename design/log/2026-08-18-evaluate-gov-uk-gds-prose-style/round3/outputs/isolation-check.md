<!-- Round 3 isolation check for task 23c1cb07-b4eb-44f9-bba5-3bc157e09a24. Method follows the round 2 audit (design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/round2/outputs/isolation-check.md): extract each session's tool-call events and inspect what it read and wrote. Authored: 2026-08-19. -->

# Isolation check for the round 3 evaluation runs

## Harness

Each of the five conditions ran as one fresh, non-interactive `pi --print`
session, all on `lemonade/Qwen3.8-27B-GGUF-Q4_K_M` (confirmed per-transcript
below). Flags per `round3/harness.md`: `--no-session --no-context-files
--no-skills --no-prompt-templates --no-themes --provider lemonade --model
Qwen3.8-27B-GGUF-Q4_K_M --tools read,write --mode json`, plus `--skill
<candidate-path>` for the four skill conditions (omitted for the no-skill
control).

The `home/AGENTS.md` § ASD-STE100 block was removed before the first run
and git-restored byte-identical after the last run (`git diff --
home/AGENTS.md` empty; see `round3/harness.md` for the paired positive
control on that removal).

## Method

Each session's transcript is the JSONL stream captured to
`.tmp/round3run/<condition>.jsonl` (disposable, not a repository artefact;
not committed). Every event where `.assistantMessageEvent.type ==
"toolcall_end"` was extracted with `jq`, giving each tool call's name and
`arguments.path`. This is the round 3 event shape: `pi`'s `--mode json`
stream nests tool-call completion under
`.assistantMessageEvent.toolCall.{name,arguments.path}` inside a
`message_update` envelope, rather than a bare top-level `toolcall_end`
event as round 2's note described; the extraction command below reflects
the shape actually observed, not the round 2 wording.

```sh
jq -c 'select(.assistantMessageEvent.type=="toolcall_end") |
  .assistantMessageEvent.toolCall | {name, path: .arguments.path}' \
  .tmp/round3run/<condition>.jsonl
```

## Per-session tool-call audit

| Condition | Read calls | Reads outside its own candidate + samples | Write calls | Notes |
|---|---|---|---|---|
| `asd-ste100` (incumbent) | 4 (`skills/asd-ste100/SKILL.md` + 3 samples) | 0 | 3 (own output dir) | -- |
| `technical-prose` | 4 (`skills/technical-prose/SKILL.md` + 3 samples) | 0 | 3 (own output dir) | -- |
| `simple-prose` | 4 (`skills/simple-prose/SKILL.md` + 3 samples) | 0 | 3 (own output dir) | -- |
| `govuk-style` | 5 (`candidates/govuk-style.md` + 3 samples + 1 self-read of its own written output) | 0 | 4 (3 first-pass writes to its own output dir, plus 1 revision of `strict-dirge-plugin-model.md` in the same dir) | The session re-read and rewrote one of its own already-written outputs before finishing. The extra read/write pair targets only its own output directory, so it does not touch this audit's isolation question (candidate + sample isolation); recorded for completeness. |
| `no-skill-control` | 3 (3 samples, no skill file) | 0 | 3 (own output dir) | -- |

Every skill-condition session read exactly its own candidate plus the
three samples (`govuk-style` additionally re-read one of its own outputs,
noted above), and every session wrote only inside its own
`round3/outputs/<condition>/` directory. The no-skill control read exactly
the three samples, with no skill-file read at all.

## Positive controls

An empty grep is not evidence of absence on its own, so each negative
result below is paired with a control that proves the same probe detects
a real match when one exists.

**Control 1 -- full cross-candidate contamination matrix.** Each
condition's transcript was grepped for all four skill/candidate path
patterns (`skills/asd-ste100/SKILL.md`, `skills/technical-prose/SKILL.md`,
`skills/simple-prose/SKILL.md`, `candidates/govuk-style.md`):

| Transcript \ pattern | asd-ste100 | technical-prose | simple-prose | govuk-style |
|---|---|---|---|---|
| `asd-ste100` | 1 | 0 | 0 | 0 |
| `technical-prose` | 0 | 1 | 0 | 0 |
| `simple-prose` | 0 | 0 | 1 | 0 |
| `govuk-style` | 0 | 0 | 0 | 1 |
| `no-skill-control` | 0 | 0 | 0 | 0 |

The matrix diagonal (each condition matching only its own pattern) is the
positive control: it proves the grep correctly detects each candidate path
when the session did read it, so every off-diagonal 0 -- including the
entire `no-skill-control` row -- is a genuine absence rather than a probe
that matches nothing regardless of input.

**Control 2 -- any skill file at all, for the no-skill control.** Grepping
each transcript's `read` calls for paths ending `SKILL.md`:

| Condition | `SKILL.md` reads |
|---|---|
| `asd-ste100` | 1 |
| `technical-prose` | 1 |
| `simple-prose` | 1 |
| `govuk-style` | 0 (its candidate is `govuk-style.md`, not `SKILL.md` -- expected) |
| `no-skill-control` | 0 |

The three owned/incumbent-skill conditions are the positive control for
this probe: each shows exactly one `SKILL.md` read, proving the probe
detects a skill file when the session loaded one. `govuk-style`'s 0 is
expected (its candidate is not named `SKILL.md`) and is independently
confirmed present under Control 1. `no-skill-control`'s 0 is therefore a
genuine absence of any skill file read, not a broken probe.

**Control 3 -- model identity, per transcript.** `jq -r '.. | .model? //
empty' <transcript> | sort -u` against every transcript returns exactly
one value, `Qwen3.8-27B-GGUF-Q4_K_M`, for all five conditions. The probe
is confirmed non-trivial because it returns a concrete, non-empty model
string rather than silently matching nothing; there is no round where a
different model string would need to be distinguished, so this control
also serves as the round's model-identity record for `round3/harness.md`.

## Note on the first (discarded) attempt

The first attempt at these five sessions, run before a `pi/models.json`
`modelOverrides` correction for this model's default 4096-token output
cap, produced no usable output in four of five conditions (each read its
candidate and the three samples, then exhausted its output budget in
reasoning before any `write` call) and a transient provider-resolution
error in the fifth. Those transcripts were discarded and are not reflected
in this file; the run was repeated in full with the corrected
`pi/models.json`, and this file audits only the completed, re-run
sessions whose outputs exist under `round3/outputs/`. See `round3/
harness.md` for the full account of the correction.

## No dictionary reproduction

Nothing recorded here reproduces the ASD approved-word dictionary. This
file records tool-call paths and match counts only.

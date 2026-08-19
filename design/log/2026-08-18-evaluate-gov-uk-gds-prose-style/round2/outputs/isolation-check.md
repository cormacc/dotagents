<!-- Round 2 isolation check for task 9882a290. Method follows the round 1 audit recorded in the change-record's `* Implementation` (extract each session's tool_use calls and inspect what it read). Authored: 2026-08-19. -->

# Isolation check for the round 2 evaluation runs

## Harness

Each of the five conditions ran as one fresh, non-interactive `pi --print`
session, all on `anthropic/claude-sonnet-5` (confirmed per-transcript
below), per `round2/harness.md`. Flags: `--no-session --no-context-files
--no-skills --no-prompt-templates --no-themes --provider anthropic --model
claude-sonnet-5 --tools read,write --mode json`, plus `--skill
<candidate-path>` for the four skill conditions (omitted for the no-skill
control). `--mode json` is an addition over the flag set recorded in
`harness.md`: it makes `pi --print` emit a JSONL event stream to stdout,
including every tool call with its arguments, which this audit needs. It
changes only the output format, not tool availability, skill loading, or
context-file discovery, so it does not touch any control `harness.md`
verified.

The `home/AGENTS.md` § ASD-STE100 block was removed before the first run
and git-restored byte-identical after the last run (`git diff --
home/AGENTS.md` empty). Verified during the removal window: `grep -c
ASD-STE100 home/AGENTS.md` returned 0, while the positive control `grep -c
'British English' home/AGENTS.md` returned 2 (proving the grep itself can
find text in the file, so its 0 result for ASD-STE100 is not a broken
probe). `~/.pi/agent/AGENTS.md` resolves (`readlink -f`) to the same live
file.

## Method

Each session's transcript is the JSONL stream captured to
`.tmp/round2run/<condition>.jsonl` (disposable, not a repository artefact;
not committed). Every `toolcall_end` event was extracted with a small
Babashka script, giving each tool call's name and `path` argument. The
full extracted call lists are reproduced below.

## Per-session tool-call audit

| Condition | Read calls | Reads outside its own candidate + samples | Write calls |
|---|---|---|---|
| `asd-ste100` (incumbent) | 4 (`skills/asd-ste100/SKILL.md` + 3 samples) | 0 | 3 (own output dir) |
| `technical-prose` | 4 (`skills/technical-prose/SKILL.md` + 3 samples) | 0 | 3 (own output dir) |
| `simple-prose` | 4 (`skills/simple-prose/SKILL.md` + 3 samples) | 0 | 3 (own output dir) |
| `govuk-style` | 4 (`candidates/govuk-style.md` + 3 samples) | 0 | 3 (own output dir) |
| `no-skill-control` | 3 (3 samples, no skill file) | 0 | 3 (own output dir) |

Every skill-condition session read exactly its own candidate plus the
three samples, and wrote exactly its three outputs. The no-skill control
read exactly the three samples, with no skill-file read at all, and wrote
exactly its three outputs.

## Positive controls

An empty grep is not evidence of absence on its own, so each negative
result below is paired with a control that proves the same probe detects
a real match when one exists.

**Control 1 -- the `asd-ste100` pattern.** Grepping every transcript's
extracted call list for the literal substring `asd-ste100`:

| Condition | Matches |
|---|---|
| `asd-ste100` (incumbent) | 4 (its own `SKILL.md` read, plus its three writes to `round2/outputs/asd-ste100/`) |
| `technical-prose` | 0 |
| `simple-prose` | 0 |
| `govuk-style` | 0 |
| `no-skill-control` | 0 |

The incumbent's own transcript is the positive control: it proves the
probe correctly flags the pattern when the session did read the incumbent,
so the 0 matches in every other transcript are a genuine absence, not a
broken probe. This mirrors the round 1 method exactly (round 1's own
`asd-ste100` grep: 0 in both non-incumbent transcripts, 5 in the
incumbent's).

**Control 2 -- full cross-candidate contamination matrix.** Each
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

**Control 3 -- any skill file at all, for the no-skill control.** Grepping
each transcript's read calls for paths ending `SKILL.md`:

| Condition | `SKILL.md` reads |
|---|---|
| `asd-ste100` | 1 |
| `technical-prose` | 1 |
| `simple-prose` | 1 |
| `govuk-style` | 0 (its candidate is `govuk-style.md`, not `SKILL.md` -- expected) |
| `no-skill-control` | 0 |

The three skill conditions are the positive control for this probe: each
shows exactly one `SKILL.md` read, proving the probe detects a skill file
when the session loaded one. `govuk-style`'s 0 is expected (its candidate
is not named `SKILL.md`) and is independently confirmed present under
Control 2. `no-skill-control`'s 0 is therefore a genuine absence of any
skill file read, not a broken probe.

## Model

All five transcripts report `"model":"claude-sonnet-5"` on every assistant
message, confirmed by grep against each transcript file.

## Full tool-call lists

### asd-ste100

```
read /home/cormacc/dotfiles/agents/skills/asd-ste100/SKILL.md
read /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/round2/samples/strict-dirge-plugin-model.md
read /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/round2/samples/explanatory-tighten-org-tasks.org
read /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/round2/samples/explanatory-consolidate-policy.org
write /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/round2/outputs/asd-ste100/strict-dirge-plugin-model.md
write /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/round2/outputs/asd-ste100/explanatory-tighten-org-tasks.org
write /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/round2/outputs/asd-ste100/explanatory-consolidate-policy.org
```

### technical-prose

```
read /home/cormacc/dotfiles/agents/skills/technical-prose/SKILL.md
read /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/round2/samples/strict-dirge-plugin-model.md
read /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/round2/samples/explanatory-tighten-org-tasks.org
read /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/round2/samples/explanatory-consolidate-policy.org
write /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/round2/outputs/technical-prose/strict-dirge-plugin-model.md
write /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/round2/outputs/technical-prose/explanatory-tighten-org-tasks.org
write /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/round2/outputs/technical-prose/explanatory-consolidate-policy.org
```

### simple-prose

```
read /home/cormacc/dotfiles/agents/skills/simple-prose/SKILL.md
read /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/round2/samples/strict-dirge-plugin-model.md
read /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/round2/samples/explanatory-tighten-org-tasks.org
read /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/round2/samples/explanatory-consolidate-policy.org
write /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/round2/outputs/simple-prose/strict-dirge-plugin-model.md
write /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/round2/outputs/simple-prose/explanatory-tighten-org-tasks.org
write /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/round2/outputs/simple-prose/explanatory-consolidate-policy.org
```

### govuk-style

```
read /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/candidates/govuk-style.md
read /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/round2/samples/strict-dirge-plugin-model.md
read /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/round2/samples/explanatory-tighten-org-tasks.org
read /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/round2/samples/explanatory-consolidate-policy.org
write /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/round2/outputs/govuk-style/strict-dirge-plugin-model.md
write /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/round2/outputs/govuk-style/explanatory-tighten-org-tasks.org
write /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/round2/outputs/govuk-style/explanatory-consolidate-policy.org
```

### no-skill-control

```
read /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/round2/samples/strict-dirge-plugin-model.md
read /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/round2/samples/explanatory-tighten-org-tasks.org
read /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/round2/samples/explanatory-consolidate-policy.org
write /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/round2/outputs/no-skill-control/strict-dirge-plugin-model.md
write /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/round2/outputs/no-skill-control/explanatory-tighten-org-tasks.org
write /home/cormacc/dotfiles/agents/design/log/2026-08-18-evaluate-gov-uk-gds-prose-style/round2/outputs/no-skill-control/explanatory-consolidate-policy.org
```

## No dictionary reproduction

Nothing recorded here reproduces the ASD approved-word dictionary. This
file records tool-call paths and match counts only.

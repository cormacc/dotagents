<!-- Round 2 harness verification. A disposable probe, not a candidate run. Authored: 2026-08-19. Updated 2026-08-19: added the no-skill control invocation and its probe (Probe 4), after the plan added that condition. -->

# Round 2 harness verification

## Result

`pi --print` (headless pi) works for round 2, contrary to the record's
carried-forward assumption. The record's pointer said "neither `pi --print`
nor the round 1 fallback `claude -p` can be assumed to work"; that caution
was correct to state, but the probe below shows `pi --print` does work once
one flag choice is corrected. This corrects, not merely confirms, the
assumption.

## What round 1 actually hit

Round 1's `pi --print` refusal was re-tested here and reproduced exactly:
Anthropic returns `400 {"type":"error","error":{"type":"invalid_request_error","message":"Third-party apps now draw from your extra usage, not your plan limits. ..."}}`.
The probe below isolated the cause: the refusal appears only when `pi` is
invoked with `--no-extensions`. This repository's `pi` installation carries
the `@gotgenes/pi-anthropic-auth` extension (`~/.pi/agent/npm/node_modules/@gotgenes/pi-anthropic-auth`,
listed in `pi list`), which is required for the anthropic OAuth path to be
recognised as first-party. Disabling all extensions also disables that
extension, and the request then falls back to a path Anthropic's API
classifies as third-party and refuses. Dropping `--no-extensions` and
relying on `--tools` to restrict the tool surface instead (extensions stay
loaded; only named tools are enabled) removes the refusal.

This session is itself a `pi` session on `anthropic/claude-sonnet-5` with
that same extension loaded, which is the working comparison case that
motivated re-checking the `--no-extensions` hypothesis rather than accepting
the round 1 assumption unchanged.

## Verified invocation

```bash
pi --print \
  --no-session \
  --no-context-files \
  --no-skills --no-prompt-templates --no-themes \
  --skill <path-to-candidate-SKILL.md> \
  --provider anthropic --model <model-id> \
  --tools read,write \
  "<prompt naming only the candidate and the sample paths>"
```

Do not add `--no-extensions`: it disables the anthropic-auth extension and
reproduces the round 1 refusal. Isolation is achieved by `--no-skills`
(stops repository skill discovery), `--skill <path>` (loads exactly the one
named candidate), `--no-context-files` (drops `home/AGENTS.md` and
`CLAUDE.md`, see the control below), and `--tools read,write` (an allowlist
that disables every other tool -- including any `Task`-style subagent
tool an extension might register -- regardless of which extensions are
loaded).

## No-skill control invocation

The record's `** Decisions` § "Round 2 includes a no-skill, prompt-only
control" (added after this file's first version) adds a fifth condition
with no candidate skill. Its invocation differs from the candidate
invocation in exactly two ways: no `--skill` flag, and the verbatim style
line carried in the prompt text rather than a skill body.

```bash
pi --print \
  --no-session \
  --no-context-files \
  --no-skills --no-prompt-templates --no-themes \
  --provider anthropic --model <model-id> \
  --tools read,write \
  "Use Simplified Technical English.

<prompt naming only the sample paths>"
```

`--no-skills` still applies (it stops repository skill discovery); the
difference from the candidate invocation is the absence of `--skill` and
the presence of the style line as the prompt's first line, matching the
verbatim wording in `** Decisions` and in the acceptance criteria of task
`9882a290`.

## Flag verification against `pi --help`

- `--print, -p` -- "Non-interactive mode: process prompt and exit". Used as
  documented.
- `--tools, -t <tools>` -- "Comma-separated allowlist of tool names to
  enable. Applies to built-in, extension, and custom tools." Tool names are
  lowercase (`read`, `write`, `bash`, `edit`), matching the `pi --help`
  banner itself ("AI coding assistant with read, bash, edit, write tools").
  An initial probe with `--tools Read` (capitalised, matching this session's
  own tool-name casing) silently enabled no tool -- the model reported it
  had no read tool available. This is the flag-casing failure mode the
  record's pointer warned about for other harnesses; verified here for
  `pi` specifically before relying on the flag in a real run.
- `--skill <path>` -- "Load a skill file or directory (can be used multiple
  times)". Single-value per use, not variadic; confirmed empirically below
  that a trailing prompt argument is not consumed.
- `--no-skills, -ns` / `--no-context-files, -nc` / `--no-prompt-templates,
  -np` / `--no-themes` -- boolean flags, no argument to mis-consume.
- No flag in `pi --help` takes a variadic argument list before the trailing
  prompt (unlike the round 1 `claude -p` tool-list flags). The prompt was
  passed as a trailing positional argument in every probe below and was
  received intact each time, so no stdin workaround is needed for `pi`.

## Probe evidence

All probes ran from `/home/cormacc/dotfiles/agents`, used
`--provider anthropic --model claude-haiku-4-5`, and used throwaway input
under the gitignored `.tmp/round2-harness-probe/` (never a round 2 sample).

**Probe 1 -- tool-name casing (negative result, then corrected).**
`--tools Read` (capitalised): the model replied it had no read tool
available -- a silent no-op, not a crash, which is exactly the failure mode
that makes an uncontrolled probe misleading. `--tools read` (lowercase)
against the same file then returned:

```
THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG.
```

matching the throwaway file's actual sentence, uppercased as asked. This is
the positive control that the probe methodology detects a real failure
(capitalised) versus a real success (lowercase), rather than reporting
absence from a broken probe.

**Probe 2 -- skill isolation and write, with a distinctive positive
control.** A throwaway `probe-skill/SKILL.md` was created with a rule no
model would produce by guessing: replace the word "fox" with the literal
string `zzzqux7`. Invoked with `--skill <probe-skill path> --no-skills
--no-context-files --no-prompt-templates --no-themes --tools read,write`,
asked to apply the rule and write the result to a file. Output file
content:

```
The quick brown zzzqux7 jumps over the lazy dog. ...
```

The distinctive replacement string appears only because `--skill` loaded
the probe skill's content into context and the model applied it; this is
the required positive control for the isolation mechanism, not merely an
exit-code check.

**Probe 3 -- `--no-context-files` control, paired.** Asked, in one call
without `--no-context-files` and one call with it, to search the session's
system/instruction context for the literal substring `ASD-STE100` and
reply `FOUND` or `NOT-FOUND`.

| Call | Result |
|---|---|
| Without `--no-context-files` | `FOUND` |
| With `--no-context-files` | `NOT-FOUND` |

The first result is the positive control: it proves the probe can detect
the mandate text when it is present (this repository's `home/AGENTS.md`
does carry an `ASD-STE100` heading, loaded by default). The second result
is the finding: `--no-context-files` removes it. Reporting only the second
call, on its own, would have been an uncontrolled negative result.

**Probe 4 -- no-skill control mechanism, paired.** Verifies the control
invocation above rather than the candidate invocation. Two checks, both
run with `--no-skills`, no `--skill` flag, and no round 2 sample (a
throwaway jargon sentence in the gitignored probe directory, never a
round 2 sample):

1. *The style line has a real, observable effect.* Same throwaway jargon
   sentence, two calls:

   | Call | Prompt | Result |
   |---|---|---|
   | Baseline | "repeat the sentence verbatim" | "The aforementioned subsystem's antecedent credential shall be deemed invalidated in the event that any anomalous access pattern is subsequently detected by the monitoring apparatus, notwithstanding prior authorization." |
   | Control | "Use Simplified Technical English." + "rewrite the sentence" | "If the monitoring system finds unusual access, it will cancel the previous password, even if that password was previously authorized." |

   The baseline is the positive control here: it proves the probe does not
   simplify the sentence on its own, so the second row's simplification is
   attributable to the style line, not to the model's general tendency to
   paraphrase.

2. *No skill file leaks in when `--skill` is absent.* The same
   distinctive throwaway `probe-skill/SKILL.md` from Probe 2 (rule: replace
   "fox" with the literal string `zzzqux7`) was left on disk, unreferenced.
   Invoked with the control's exact flag set (no `--skill`, `--no-skills`
   set) against the same throwaway sentence used in Probe 1/2, the model
   returned "A fast brown fox jumps over a dog that does not move." --
   `fox` intact, `zzzqux7` absent. Probe 2's own result (`--skill` pointed
   at the same file: `zzzqux7` present) is the positive control that the
   probe can detect the skill's content when it *is* loaded; this probe's
   negative result then shows it is genuinely absent when `--skill` is
   omitted, not that the check is silently inert.

## Rejected alternatives

- `codex exec` (OpenAI Codex CLI, `codex --help` / `codex exec --help`):
  flags verified (`--sandbox read-only`, `--ephemeral`, `-o
  --output-last-message`, prompt via stdin when no positional argument is
  given), but the disposable probe returned `ERROR: You've hit your usage
  limit ... try again at Aug 20th, 2026 10:39 AM` before producing any
  output. Not usable now. `pi --provider openai-codex` hit the same
  underlying usage limit (`Codex error: The usage limit has been reached`),
  consistent with both paths sharing one account's usage pool.
- `claude -p` (the round 1 fallback): not re-verified in detail once
  `pi --print` was confirmed working, per the instruction not to run a
  candidate and to keep the probe minimal. `pi --print` is preferred per
  the parent instruction to use `pi` over `claude`/`codex` when possible,
  and it is now shown to be possible.

## Cleanup

`.tmp/round2-harness-probe/` (throwaway sample text, throwaway probe skill,
and probe outputs) is gitignored (`.gitignore` line `.tmp/`) and is not a
round 2 artefact. It is left in place as a disposable local trace, not
committed.

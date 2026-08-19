<!-- Round 2 evaluation rubric for the prose-style skill comparison. Fixed before any round 2 candidate run. Authored: 2026-08-19. -->

# Rubric: prose-style skill evaluation, round 2

This rubric is fixed before scoring starts. It keeps the round 1 dimension
structure: two dimensions score rewrite outputs, and one dimension scores
the skill artefact itself.

Round 2 differs from round 1 only in the candidate set and the samples.
Scores from round 1 and round 2 are not compared against each other,
because the harness, the samples, and (for the two owned skills) the rules
themselves differ between rounds.

Round 2 scores five conditions: four candidate skills and one no-skill,
prompt-only control, added to the plan after this rubric was first
committed (`** Decisions` § "Round 2 includes a no-skill, prompt-only
control"). The control is scored on the same two output dimensions as the
four candidates. It has no artefact, so dimension 3 treats it as a fixed
floor rather than measuring it on the anchors below -- see Dimension 3.

## Conditions

Four candidate skills:

- `skills/asd-ste100/SKILL.md` -- the vendored incumbent, in-repo.
- `skills/technical-prose/SKILL.md` -- owned, landed in commit `f20a2a7`.
- `skills/simple-prose/SKILL.md` -- owned, landed in commit `ecb42ea`.
- `candidates/govuk-style.md` -- the round 1 gist snapshot, unchanged, used
  as a control for the round 1 content drops.

One no-skill, prompt-only control:

- No skill file loads. The session's only style instruction is the
  verbatim line "Use Simplified Technical English." Output goes to
  `round2/outputs/no-skill-control/`. This condition is the floor the four
  candidates must beat: it measures whether a skill's per-session context
  cost buys anything over a one-line prompt instruction.

## Inputs

Each of the five conditions -- the four candidate skills and the no-skill
control -- rewrites the same samples in an isolated session:

| Sample | Prose class | Scored under |
|---|---|---|
| `round2/samples/strict-dirge-plugin-model.md` | Strict operational text (agent-consumed skill instructions) | Dimension 1 |
| `round2/samples/explanatory-tighten-org-tasks.org` | Explanatory prose (design change-record narrative) | Dimension 2 |
| `round2/samples/explanatory-consolidate-policy.org` | Explanatory prose (design change-record narrative) | Dimension 2 |

Dimension 1 and dimension 2 stay separate because an STE-derived rubric
structurally favours STE candidates. A split-mandate outcome must be
decidable from the per-dimension scores.

Every sample is prose the `asd-ste100` mandate never touched -- see
`round2/staging-notes.md` for the provenance evidence per sample. The
control's session prompt names only the sample paths and the style line;
it does not name any candidate.

## Dimension 1: strict-text output quality

Applies to the rewrite of `round2/samples/strict-dirge-plugin-model.md`.
The reader is an agent that cannot ask a clarifying question. Score 1 to 5.

Checks, in priority order:
- Meaning preservation. Every fact, condition, scope qualifier, hedge, and
  cross-reference in the source survives. A dropped or strengthened claim
  caps the score at 2.
- Ambiguity removal. Each instruction has one possible parse. Actors are
  explicit. Conditions come before commands.
- Directive precision. Imperatives for actions. No hedged instructions.
  One instruction per sentence.
- Identifier safety. Code spans, skill names, hook names (`on-init`,
  `on-prompt`, and so on), file paths, and section references are
  unchanged.

Anchors:
- 5 :: All meaning preserved. Materially easier for an agent to parse than
  the source. No new ambiguity.
- 4 :: All meaning preserved. Some parse improvements. Minor missed
  opportunities.
- 3 :: All meaning preserved. Little material improvement over the source
  (a near-no-op is scored here, not lower).
- 2 :: One or more claims weakened, strengthened, or dropped. Or an
  identifier was altered.
- 1 :: Meaning materially corrupted, or instructions made harder to parse.

## Dimension 2: explanatory-prose output quality

Applies to the rewrites of `round2/samples/explanatory-tighten-org-tasks.org`
and `round2/samples/explanatory-consolidate-policy.org`. The reader is a
human maintainer. Score 1 to 5 per sample.

Checks, in priority order:
- Meaning preservation, as in dimension 1. Same cap at 2 for a dropped or
  strengthened claim.
- Readability. Front-loaded points, short sentences, active voice,
  concrete wording.
- Voice preservation. The text stays recognisably a design record. A
  personality transplant into telegraph style is a defect, not a win.
- Structure fit. Org structure -- headings, property drawers left alone,
  bullet nesting, and inline `~code~` markup -- survives. Format
  conventions of the host file type are respected.

Anchors:
- 5 :: All meaning preserved. Clearly easier to read. Voice and structure
  intact.
- 4 :: All meaning preserved. Reads better. Minor voice flattening or
  missed simplifications.
- 3 :: All meaning preserved. Neutral: neither clearer nor harmed (a
  near-no-op scores here).
- 2 :: A claim dropped or strengthened, structure broken, or the voice
  flattened into telegraph style.
- 1 :: Meaning materially corrupted, or the text made harder to read.

## Dimension 3: skill-artifact cost

Applies to the skill artefact each session loaded, not to the rewrites.
Assessed once per candidate.

Measurements:
- Word count of the text a session actually loads at apply time: the
  `SKILL.md` body with frontmatter excluded. A body that cites a secondary
  references file does not load that file in the same session, so the
  references file is not counted here.
- Runtime-relevant density: the approximate share of the loaded body an
  agent applies at write time. Licensing history, source-acquisition
  narrative, standard provenance, marketing framing, and self-justification
  are runtime-irrelevant.
- Runtime-context defect: state whether the candidate corrects the
  incumbent's defect of carrying runtime-irrelevant context into every
  session that loads it. Answer yes, partially, or no, with the supporting
  passages named.

Anchors:
- 5 :: Compact (roughly <=1,200 words) and near-fully runtime-relevant
  (roughly >=90%).
- 4 :: Moderate size (roughly <=2,000 words), high density (roughly >=80%).
- 3 :: Large (roughly <=3,000 words) or medium density (roughly 60 to 80%).
- 2 :: Large and diluted: notable runtime-irrelevant blocks an agent must
  read past.
- 1 :: Very large (>3,500 words) or mostly runtime-irrelevant.

**The no-skill control's artefact cost.** The control loads no skill
file, so its word count is 0 and its runtime-relevant density is
undefined -- there is no body to measure a share of. Do not force it onto
the anchors above, which describe skill bodies of varying size and
dilution: a zero-word artefact is not a degenerate case of "compact and
dense", it is a different kind of thing, the absence of a cost rather than
a small one. Record its dimension 3 result as 0 words / density not
applicable, and treat it as the fixed floor every candidate's dimension 3
score is read against, rather than assigning it a number on the same 1-5
scale as the four candidates.

**Known limitation, carried from the record's `** Decisions`.** Round 1
reported approximately 400 runtime-irrelevant words in the incumbent's
body, but a later correction found that approximately 307 of those words
are write-time rules embedded in `## Source and Scope` and `## Boundaries`
(the no-dictionary-reproduction statement, the plainest-word principle, the
word-by-word escape hatch for exact ASD wording, and two will-not bullets
that govern what an agent may claim). The genuinely extractable figure was
approximately 93 words. `technical-prose` measures 2,342 loaded body words
against the incumbent's 2,435 -- a difference this dimension cannot
separate from run-to-run measurement noise. Do not read that near-tie as a
finding that `technical-prose` failed to correct the defect: the dimension
is simply not sensitive enough at this margin to compare the two
candidates. Score both candidates on the anchors above and record the
near-tie explicitly in `round2/report.md` rather than letting the numeric
closeness stand unexplained.

## What the no-skill control decides

The control answers two linked questions that a candidates-only comparison
cannot:

- Whether any candidate skill beats the control on dimension 1 or
  dimension 2 output quality, and by how much. A candidate that does not
  clearly beat the control on its output dimensions has not shown that its
  rules add value over the one-line instruction.
- Whether each candidate skill earns its dimension 3 context cost against
  the control's zero-word floor. A candidate that only ties or narrowly
  beats the control on output quality, while costing hundreds of loaded
  words every session, has not earned that cost.

A candidate that scores close to the control on dimensions 1 and 2 is
evidence that the mandate should be the control's prompt line rather than
that candidate's skill, independent of how that candidate compares to the
other three candidates.

## Scoring procedure

1. Score each of the five conditions' rewrites of the two output-dimension
   samples against dimensions 1 and 2, sample by sample, with a one-line
   justification per score. This includes the no-skill control.
2. Measure dimension 3 from the artefact each session loaded
   (`skills/asd-ste100/SKILL.md`, `skills/technical-prose/SKILL.md`,
   `skills/simple-prose/SKILL.md`, `candidates/govuk-style.md`), with word
   counts computed, not estimated. Record the no-skill control's dimension
   3 result as 0 words / not applicable, per the note above -- it is not
   measured from an artefact, because it has none.
3. Record all scores in `round2/scores.md`.
4. The decision weighs dimension 1 and dimension 2 separately. A candidate
   that wins one dimension and loses the other supports a split mandate.
   Dimension 3 acts as a tiebreak and as a per-session cost argument, not
   as a quality veto, and it cannot decide between `asd-ste100` and
   `technical-prose` for the reason stated above.
5. Report whether any candidate beat the no-skill control on dimension 1
   or dimension 2, and by how much, and whether each candidate earned its
   dimension 3 cost against the control's zero-word floor -- see "What the
   no-skill control decides" above.
6. Report whether the govuk-style content drops from round 1 recurred in
   the round 2 gist run, and whether `simple-prose` avoided them.

Scoring is not blinded. The effort line is MVP; the fixed rubric is the
rigour bar.

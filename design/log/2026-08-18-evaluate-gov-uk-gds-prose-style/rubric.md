<!-- Evaluation rubric for the prose-style skill comparison. Fixed before any candidate run. Authored: 2026-08-18. -->

# Rubric: prose-style skill evaluation

This rubric is fixed before scoring starts. It scores three dimensions. Two dimensions score rewrite outputs. One dimension scores the skill artefact itself.

## Inputs

Each candidate rewrites the same three samples in an isolated session:

| Sample | Prose class | Scored under |
|---|---|---|
| `samples/retro-skill.md` | Strict operational text (agent-consumed instructions) | Dimension 1 |
| `samples/herdr-orch-readme.org` | Explanatory prose (human-read README) | Dimension 2 |
| `samples/guidance-record.org` | Record prose (design change-record) | Dimension 2 |

Dimension 1 and dimension 2 stay separate because an STE-derived rubric structurally favours STE candidates. A split-mandate outcome must be decidable from the per-dimension scores.

## Dimension 1: strict-text output quality

Applies to the rewrite of `samples/retro-skill.md`. The reader is an agent that cannot ask a clarifying question. Score 1 to 5.

Checks, in priority order:
- Meaning preservation. Every fact, condition, scope qualifier, hedge, and cross-reference in the source survives. A dropped or strengthened claim caps the score at 2.
- Ambiguity removal. Each instruction has one possible parse. Actors are explicit. Conditions come before commands.
- Directive precision. Imperatives for actions. No hedged instructions. One instruction per sentence.
- Identifier safety. Code spans, skill names, file paths, and section references are unchanged.

Anchors:
- 5 :: All meaning preserved. Materially easier for an agent to parse than the source. No new ambiguity.
- 4 :: All meaning preserved. Some parse improvements. Minor missed opportunities.
- 3 :: All meaning preserved. Little material improvement over the source (a near-no-op is scored here, not lower).
- 2 :: One or more claims weakened, strengthened, or dropped. Or an identifier was altered.
- 1 :: Meaning materially corrupted, or instructions made harder to parse.

## Dimension 2: explanatory-prose output quality

Applies to the rewrites of `samples/herdr-orch-readme.org` and `samples/guidance-record.org`. The reader is a human maintainer. Score 1 to 5 per sample.

Checks, in priority order:
- Meaning preservation, as in dimension 1. Same cap at 2 for a dropped or strengthened claim.
- Readability. Front-loaded points, short sentences, active voice, concrete wording.
- Voice preservation. The text stays recognisably a README or a record. A personality transplant into telegraph style is a defect, not a win.
- Structure fit. Org/Markdown structure, tables, and links survive. Format conventions of the host file type are respected.

Anchors:
- 5 :: All meaning preserved. Clearly easier to read. Voice and structure intact.
- 4 :: All meaning preserved. Reads better. Minor voice flattening or missed simplifications.
- 3 :: All meaning preserved. Neutral: neither clearer nor harmed (a near-no-op scores here).
- 2 :: A claim dropped/strengthened, structure broken, or the voice flattened into telegraph style.
- 1 :: Meaning materially corrupted, or the text made harder to read.

## Dimension 3: skill-artifact cost

Applies to the skill artefact each session loaded, not to the rewrites. Assessed once per candidate.

Measurements:
- Word count of the skill body (frontmatter excluded).
- Runtime-relevant density: the approximate share of the body an agent applies at write time. Licensing history, source-acquisition narrative, standard provenance, marketing framing, and self-justification are runtime-irrelevant.
- Runtime-context defect: state whether the candidate corrects the incumbent's defect of carrying runtime-irrelevant context into every session that loads it. Answer yes, partially, or no, with the supporting passages named.

Anchors:
- 5 :: Compact (roughly ≤1,200 words) and near-fully runtime-relevant (roughly ≥90%).
- 4 :: Moderate size (roughly ≤2,000 words), high density (roughly ≥80%).
- 3 :: Large (roughly ≤3,000 words) or medium density (roughly 60 to 80%).
- 2 :: Large and diluted: notable runtime-irrelevant blocks an agent must read past.
- 1 :: Very large (>3,500 words) or mostly runtime-irrelevant.

## Scoring procedure

1. Score each candidate's three rewrites against dimensions 1 and 2, sample by sample, with a one-line justification per score.
2. Measure dimension 3 from the artefact each session loaded (`candidates/govuk-style.md`, `candidates/simple-english.md`, `skills/asd-ste100/SKILL.md`), with word counts computed, not estimated.
3. Record all scores in `scores.md` in this folder.
4. The decision weighs dimension 1 and dimension 2 separately. A candidate that wins one dimension and loses the other supports a split mandate. Dimension 3 acts as a tiebreak and as a per-session cost argument, not as a quality veto.

Scoring is not blinded. The effort line is MVP; the fixed rubric is the rigour bar.

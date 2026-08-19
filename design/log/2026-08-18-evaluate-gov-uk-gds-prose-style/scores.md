<!-- Scores for the prose-style skill evaluation, applied per rubric.md. Scored: 2026-08-18. Scorer: the worker session for task 4d98c102, after the runs completed. -->

# Scores

All rewrites came from fresh `claude -p` sessions on the same model (claude-opus-5). See `outputs/isolation-check.md` for the isolation evidence.

## Dimension 1: strict-text output quality (`retro-skill.md`)

| Candidate | Score | Justification |
|---|---|---|
| govuk-style | 2 | Dropped the `proactively` scope qualifier from the threshold rule, so the rewrite constrains all retros instead of unprompted offers only. The cap for a dropped qualifier applies. The rest is clean and parses well. Probe: `grep -ci 'proactively\|without a request'` returns 0 against the rewrite and 1 against the source and against both other rewrites. |
| simple-english | 4 | All meaning preserved, conditions moved before commands, one instruction per sentence. Costs: American spelling (`synthesize`, `behavior`) against the repository's British English rule, and some verbosity from full-grammar expansion. |
| asd-ste100 | 4 | All meaning preserved, hedges intact, list structure improves parse. One awkward restructure ("A session can have fewer than approximately five substantive exchanges. Then say...") replaces an explicit `If` and costs a little parse certainty. |

## Dimension 2: explanatory-prose output quality

### `herdr-orch-readme.org`

| Candidate | Score | Justification |
|---|---|---|
| govuk-style | 5 | All meaning preserved, long sentences split well, active voice, README voice intact, org structure intact. |
| simple-english | 3 | Meaning preserved, but the style flattens: repeated "the X of the Y" possessives ("the checkout of the caller", "the index of each other") and heavy subject repetition make it stiffer than the source. |
| asd-ste100 | 4 | Meaning preserved and the text reads naturally. Minor nuance costs: "blocks for the result" became "waits for the result", and the list term "tear down" became "remove". |

### `guidance-record.org`

| Candidate | Score | Justification |
|---|---|---|
| govuk-style | 2 | Dropped the eighth `** Shipped` item (the machine-readable JSON rule) outright. The cap for a dropped claim applies. Probe: the rewrite has 7 `** Shipped` bullets and 0 `Machine-readable JSON` matches; the source and both other rewrites have 8 and 1. |
| simple-english | 3 | All items preserved, but American spelling (`behavior`) and stilted possessives ("the fork of the user") flatten record prose that was already terse. |
| asd-ste100 | 4 | All items preserved, sentences split cleanly, British spelling kept. Mild improvement over an already compliant source. |

## Dimension 3: skill-artifact cost

Word counts measured with frontmatter and snapshot headers excluded. Density is an estimate from measured section word counts (see `report.md` § Skill-artifact audit).

| Candidate | Body words | Runtime-relevant density | Corrects the runtime-context defect | Score |
|---|---|---|---|---|
| govuk-style | 956 | ~97% | Yes | 5 |
| simple-english | 3,294 | ~95% | Largely yes | 3 |
| asd-ste100 | 2,435 | ~84% | No (it is the incumbent defect) | 3 |

## Totals by dimension

| Candidate | D1 (strict) | D2 (explanatory, README + record) | D3 (artifact) |
|---|---|---|---|
| govuk-style | 2 | 5 + 2 = 7 | 5 |
| simple-english | 4 | 3 + 3 = 6 | 3 |
| asd-ste100 | 4 | 4 + 4 = 8 | 3 |

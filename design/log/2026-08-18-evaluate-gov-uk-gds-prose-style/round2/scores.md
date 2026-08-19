<!-- Scores for the round 2 prose-style skill evaluation, applied per round2/rubric.md. Scored: 2026-08-19. Scorer: the worker session for task 8d2a12cc, after the round 2 runs completed. -->

# Round 2 scores

All five conditions came from fresh `pi --print` sessions on the same model
(`anthropic/claude-sonnet-5`). See `outputs/isolation-check.md` for the
isolation evidence and `harness.md` for the harness verification.

**Meaning-preservation audit, upfront.** Unlike round 1 -- where the
govuk-style gist dropped a scope qualifier in one sample and a whole
`** Shipped` item in another, capping both scores at 2 -- no drop was found
in any of the 15 round 2 rewrites. This was checked with controlled probes,
not by reading alone: distinctive-token grep counts (`7KB`, `otList`,
`f361c429`, `hasOpenQuestions`, `org_read_section`, `insertTaskIntoFile`,
`persistent-widget`, `9,600`/`9.6k`, `1,100`/`1.1k`, and others) against the
source and every rewrite, plus bullet/checkbox/heading item counts per
section. Every count matched the source in every condition (full detail in
`report.md` § Meaning-preservation audit). Because a dropped claim is the
rubric's only automatic score-cap, no score below is capped for that reason.
Differences below are quality differences among fully meaning-preserving
rewrites.

## Dimension 1: strict-text output quality (`strict-dirge-plugin-model.md`)

| Condition | Score | Justification |
|---|---|---|
| asd-ste100 | 4 | No drops (probe-confirmed). Full STE decomposition into short sentences. The closing admonition is the only rewrite of the five to reorder the source's trailing condition to lead ("Before you use a harness/* name, confirm..."), matching the rubric's "conditions before commands" check most closely. Converts the source's passive "Requires building with `--features plugin`" into a clean imperative ("To build plugin support, use..."). Costs: the heaviest word growth of the five (705 vs 532 source words, +33%) from short-sentence repetition ("It has about 9,600 lines... It already sends... `ot` uses the schema... `ot` is written..."). |
| technical-prose | 4 | No drops. Same rule set and near-identical quality to asd-ste100 (expected: it is a runtime-cost refactor of the same rules, not a rules change). One explicit win over the source and over asd-ste100: renders the hooks list as "include (but are not limited to)" instead of relying on an implicit "such as", stating the non-exhaustive hedge explicitly. Keeps the trailing condition after the command in the closing admonition, unlike asd-ste100. |
| simple-prose | 4 | No drops. Reads more fluidly than the STE-derived pair, restructuring the two-location list and the harness/* function list into clean bullets without the STE staccato. One genuine defect: capitalises "Dirge" at three sentence starts ("Dirge auto-loads...", "Dirge finds hooks...", "Dirge's plugin surface...") where the source, the vendored `skills/dirge/SKILL.md`, and every other round 2 rewrite keep it lowercase throughout, including at sentence starts (`grep -n "^Dirge\|\. Dirge\|dirge's" skills/dirge/SKILL.md` confirms the vendored skill's own convention). Treated as a minor identifier-casing slip rather than the rubric's "identifier altered" cap, because it does not change which symbol is referenced or introduce a machine-parseable ambiguity -- but it is the only identifier-fidelity defect found in any of the 15 rewrites and is named here rather than silently absorbed into the score. Also keeps the source's declarative framing for the `--features plugin` line ("Building a plugin-enabled dirge needs...") rather than converting it to an imperative, a smaller miss on directive precision than asd-ste100/technical-prose. |
| govuk-style | 4 | No drops -- the round 1 defect pattern did not recur (see report.md § Recurrence). The leanest rewrite of the five (595 vs 532 source words, +12%, against the STE pair's +29-33%), close paraphrase, consistent lowercase `dirge`. Same declarative-not-imperative miss on the `--features plugin` line as simple-prose ("Plugins need a build with..."). |
| no-skill-control | 4 | No drops -- the single prompt line "Use Simplified Technical English" produced a rewrite indistinguishable in quality from the two STE skills on this sample: same imperative conversion of the `--features plugin` line, same hook-list hedge ("...and others"), same word growth in the STE band (667 vs 532, +25%). No skill's rules add a checkable, scoreable improvement over the prompt line on this specific sample. |

All five tie at 4. This is itself a finding: on this strict sample, none of
the four skills demonstrably outperforms the free one-line control, and the
STE-derived skills do not demonstrably outperform the GDS-derived ones --
see report.md § Control question.

## Dimension 2: explanatory-prose output quality

### `explanatory-tighten-org-tasks.org`

Source Intent is a goal-statement ("Make '...' answerable... and stop...").

| Condition | Score | Justification |
|---|---|---|
| asd-ste100 | 4 | No drops. Reads better than source: reframes Intent as "This change has two goals. First,... Second,..." -- an added editorial structure, still a goal statement, and it clarifies the two-part scope. Minor voice flattening from STE-style short-sentence decomposition through the Decisions section. |
| technical-prose | 4 | No drops. Stays closer to the source's own sentence boundaries than asd-ste100 (Intent kept as one paragraph, matching the source's own shape). Slightly better voice preservation than asd-ste100's heavier restructuring, for materially the same rule set. |
| simple-prose | 4 | No drops. Fluent, active, front-loaded. Splits two Gotchas bullets that were one two-clause bullet each in the source into separate bullets (27 vs 22 source bullet lines) -- a structure change, not a content loss, confirmed by the identical distinctive-token counts (report.md). Reads well throughout. |
| govuk-style | 5 | No drops. Closest paraphrase of the source's discursive record voice of any condition, at the leanest word count (593 vs 536 source, +11%, versus asd-ste100's +21%), with zero bullet-count drift (22, matching source exactly). Clearly easier to read than the source without flattening its register. |
| no-skill-control | 3 | No drops, but this is the one clear voice-flattening instance found in round 2. The source's Intent frames "instead of multiple raw file reads" as a comparative clause inside a goal statement. The control turns it into a new second-person command not present in the source at all: "Do not use several raw file reads." That is a mode shift from the record's descriptive-intent register into an instruction-manual imperative, consistent with the control's only style instruction naming Simplified Technical English (a strict-text register) with no explanatory-prose guidance. Elsewhere the rewrite is competent and meaning-preserving, so this scores as neutral rather than a lower cap. |

### `explanatory-consolidate-policy.org`

Source Decisions/Scope prose is already terse, shorthand-heavy record style (`::`, `+`, parenthetical abbreviations), unlike the more discursive Sample A.

| Condition | Score | Justification |
|---|---|---|
| asd-ste100 | 4 | No drops (all 17 source bullets, all 7 `ISC-` items present). Expands the source's already-terse shorthand into fuller sentences; a genuine readability gain over an unusually compressed source, with the usual STE choppiness in exchange. |
| technical-prose | 4 | No drops. Same expansion pattern and quality as asd-ste100. It also introduces extra `~code~` markup around plain-prose field names the source left unmarked (`~parent~`, `~after~`, `~local~`, `~source~`, `~rawToken~`, `~label~`, `~url~` -- 33 tilde-spans vs the source's 21). This is an addition, not a structure break, and arguably improves identifier legibility. Noted as a stylistic difference, not scored as a defect. |
| simple-prose | 4 | No drops. Reorders the Intent's two clauses (leads with "Make `ot` the sole owner..." where the source and every other rewrite lead with "Remove the policy logic..."). Meaning is identical, and it still reads naturally. |
| govuk-style | 5 | No drops. The most literal, lowest-word-growth paraphrase of the five (472 vs 419 source, +13%, versus asd-ste100's +23% and no-skill-control's +22%). Reads clearly without altering the source's voice. |
| no-skill-control | 4 | No drops. No equivalent of the Sample A imperative-injection defect was found here -- the source's own Intent for this sample is already imperative-styled ("Remove the policy logic duplicated..., by making `ot` the sole owner"), so the control's similar treatment is not a deviation. Reads comparably to the two STE skills. |

## Dimension 3: skill-artefact cost

Word counts are the `SKILL.md` (or snapshot) body with frontmatter excluded,
computed with `awk`/`wc -w`, matching the round 2 rubric's definition: the
text a session actually loads at apply time. A cited references file is not
counted.

| Candidate | Body words | Runtime-relevant density | Corrects the runtime-context defect | Score |
|---|---|---|---|---|
| asd-ste100 | 2,435 | ~84% (unchanged from round 1's measured figure) | No -- this is the incumbent defect | 3 |
| technical-prose | 2,342 | ~84%, materially unchanged | Partially. `references/provenance.md` now carries the standard's origin/history/licensing narrative. But the record's own correction (round1's ~400-word estimate over-counted, and only ~93 words were genuinely extractable) means the body still opens with a 51-word origin-narrative paragraph ("ASD-STE100 is a controlled-language standard built by the aerospace industry..."), functionally unchanged from the incumbent's own 52-word equivalent, and `## Boundaries` still restates licensing/dictionary-reproduction rules. See "Known limitation" below -- this dimension cannot separate the two candidates at this margin. | 3 |
| simple-prose | 833 | ~98%. Only the opening paragraph's closing clause ("These rules are adapted from the GOV.UK writing guidelines and style guides...", ~12 of 833 words) is source-acquisition narrative. Every other section (Keep the meaning, Structure, Voice and tone, Words, Formatting, Before you finish) is write-time rules. | Yes | 5 |
| govuk-style | 956 | ~97% (round 1 figure; snapshot unchanged) | Yes (control artefact, not itself an adoptable candidate -- see report.md) | 5 |
| no-skill-control | 0 | Not applicable -- no artefact loaded | Not applicable -- the fixed floor every candidate is read against | not scored on the 1-5 scale, per rubric |

**Known limitation, carried from `round2/rubric.md`.** technical-prose
(2,342 words) and asd-ste100 (2,435 words) both fall under the same anchor
band ("Large, ≤3,000 words") regardless of density, so dimension 3 cannot
show separation between them at this margin. Do not read the identical
score of 3 as evidence that `technical-prose` failed to correct the
runtime-context defect -- the record's own correction already established
that ~307 of the originally-counted ~400 runtime-irrelevant words are
write-time rules that necessarily stayed. The dimension is simply not
sensitive enough here. The near-tie is a limitation of the measurement, not
a finding about the skill.

**Simple-prose word-count note.** The change-record's `* Implementation`
cites "908 words authored" for `simple-prose`. That figure is the whole
file's `wc -w`, including the frontmatter (`name:` and the 47-word
`description:` line = 74 words of frontmatter). The round 2 rubric's
dimension 3 explicitly excludes frontmatter ("the `SKILL.md` body with
frontmatter excluded"), giving 833 body words. Both figures are correct for
what they measure. 833 is the one comparable to the other three rows above,
which are likewise body-only.

## Totals by dimension

| Condition | D1 (strict) | D2 (explanatory, sum of two samples, /10) | D3 (artefact) |
|---|---|---|---|
| asd-ste100 | 4 | 4 + 4 = 8 | 3 |
| technical-prose | 4 | 4 + 4 = 8 | 3 |
| simple-prose | 4 | 4 + 4 = 8 | 5 |
| govuk-style | 4 | 5 + 5 = 10 | 5 |
| no-skill-control | 4 | 3 + 4 = 7 | 0 words / not applicable (fixed floor) |

Combined output-quality total (D1 + D2, out of 15): asd-ste100 12,
technical-prose 12, simple-prose 12, govuk-style 14, no-skill-control 11.
See `report.md` for the control-question and recommendation analysis built
on these totals.

<!-- Scores for the round 3 prose-style skill evaluation, applied per round2/rubric.md (unchanged; round 3 reuses it verbatim per the record's plan). Scored: 2026-08-19. Scorer: the worker session for task 3f96cc9c, after the round 3 runs completed. -->

# Round 3 scores

All five conditions came from fresh `pi --print` sessions on the same model,
`lemonade/Qwen3.8-27B-GGUF-Q4_K_M`, under the corrected `pi/models.json`
`modelOverrides` (raises `maxTokens` to 32768; maps thinking levels onto this
GGUF's accepted `reasoning_effort` values, pinning `high` to `medium`). See
`harness.md` for the full harness record and `outputs/isolation-check.md` for
isolation evidence. `pi/models.json` is untracked, so this configuration is
not reproducible from the repository alone; it is recorded here as a
condition of the round, not as a repository artefact.

**Two confounds carried through every table below, not smoothed over:**

1. `govuk-style`'s rewrite of `strict-dirge-plugin-model.md` received a second,
   self-initiated revision pass before the session finished (it re-read and
   rewrote its own first-pass output; `outputs/isolation-check.md`). No other
   condition, and no other sample, had a second pass. Every dimension-1 score
   for `govuk-style` below is marked `†` and must not be read as comparable,
   on its own, to the other four conditions' single-pass dimension-1 scores.
2. The harness needed a model-configuration correction (`pi/models.json`,
   above) to produce any output at all on the first attempt. All five
   conditions re-ran under the identical corrected configuration, so
   within-round comparisons are unaffected; the round as a whole is not
   reproducible without that untracked file.

**Meaning-preservation audit, upfront.** As in round 2, this was checked with
controlled probes, not by reading alone, before any quality score was
assigned. Every probe below is paired with a positive control that confirms
the pattern is genuinely present in the source at the stated count, so a
matching (non-zero, source-equal) count in a rewrite is read as preservation
rather than as a probe that would have matched nothing regardless of input.

*Sample 1 (`strict-dirge-plugin-model.md`).* Distinctive tokens checked:
`9.6k`/`9,600`, `1.1k`/`1,100`, `persistent-widget`, `status-line`,
`bookmark.janet`, `protected_paths.janet`, `select_persona.janet`,
`example_tool.janet`, `plugins.md`, the `00-`/`01-` load-order prefixes, and
the three non-exhaustive-list hedge markers on the hooks/fallback/
reference-plugin lists (source uses `etc.`, `e.g.`, `etc.` at those three
spots). Positive control: every pattern above returns a non-zero count
against the source before any rewrite is checked (for example, `grep -icE
'persistent-widget|persistent widget' round2/samples/strict-dirge-plugin-model.md`
returns 2). Result: every token/count matched the source in all five
rewrites. All five rewrites kept exactly three non-exhaustive-list hedges,
though the wording differs per rewrite (`such as`, `and others`, `and so on`,
`for example`, or the source's own `etc.`/`e.g.`) -- a phrasing change, not a
drop.

*Sample 2 (`explanatory-tighten-org-tasks.org`).* Distinctive tokens checked:
`7KB`, `otList`, `f361c429`, `hasOpenQuestions`, `org_read_section`,
`insertTaskIntoFile`, `tasks_insert_task`, `tasks_scan_summaries`,
`include-content`, `ancestors`, `hasContext`, `effective-source-content`.
Positive control: every pattern returns its source count (2, 1, 2, 1, 3, 2,
2, 2, 3, 2, 1, 1 respectively) before any rewrite is checked. Result: every
one of the twelve counts matched the source exactly in all five rewrites.
Bullet-line count (`^- `) also matched the source (20) in four of five
rewrites; `simple-prose` produced 27, all traced to bullet-splitting (one
source bullet with an embedded "but keep..." clause split into two bullets),
not a drop.

*Sample 3 (`explanatory-consolidate-policy.org`).* Distinctive tokens
checked: `ISC-1` through `ISC-6`, `ISC-A-1`, `applyStatusTransition`,
`persistChange`, `lifecycle/status-cycle`, `getLinkedIssues`,
`insert-task-into-file`, `create-opts`, `rawToken`. Positive control: every
pattern returns its source count (1 each, except `lifecycle/status-cycle` at
2) before any rewrite is checked. Result: every count matched the source
exactly in all five rewrites. Bullet count (17) and checkbox count (7, all
`ISC-*` acceptance items) also matched the source exactly in all five
rewrites.

**Result: no rewrite in round 3 dropped a fact, condition, qualifier, hedge,
list item, or cross-reference.** This holds for the no-skill control as well
as for all four skill conditions. Contrary to the assignment's stated
expectation that a weaker model is more likely to drop content, this MVP
single-run sample found no drop on any of the fifteen rewrites. Because a
dropped claim is the rubric's only automatic score-cap for dimensions 1 and
2, no score below is capped for that reason; every difference below is a
quality difference among fully meaning-preserving rewrites, or (in one case,
noted below) a structure-fit defect that is not a content drop.

## Dimension 1: strict-text output quality (`strict-dirge-plugin-model.md`)

| Condition | Score | Justification |
|---|---|---|
| asd-ste100 | 4 | No drops (probe-confirmed). Full STE decomposition; the two source hedge markers (`etc.` twice) are relocated to leading `such as` rather than dropped. Converts the source's passive "Requires building with `--features plugin`" into an active declarative ("Building dirge requires the `--features plugin` flag") plus a clean closing imperative ("Verify with `dirge --version`: the output lists `plugin`."). Word growth: 611 vs 532 source (+14.8%). Minor defect: capitalises "Dirge" at the start of three bullets, against the vendored skill's own convention of lowercase `dirge` even at sentence starts (`grep -n '^dirge\|\. dirge' skills/dirge/SKILL.md` confirms the convention). Treated as a minor identifier-casing slip, not the rubric's "identifier altered" cap, per the same treatment round 2 gave an identical defect in `simple-prose`'s strict-sample run -- it does not change which symbol is referenced. |
| technical-prose | 4 | No drops. Same rule set as asd-ste100, materially the same quality. Consistent lowercase `dirge` throughout (round 2's capitalisation slip did not recur here, and this round's slip appears in different conditions -- see below). Cleanest directive precision of the five on the build-flag line: "Run `dirge --version` and confirm that its output lists `plugin`" is a genuine two-step imperative, sharper than the other four conditions' declarative-plus-imperative or modal phrasing. The round 2 finding that `technical-prose` adds extra `~code~`-equivalent markup did not recur (checked: the one extra tilde-wrapped token in sample 3 below is a duplicate occurrence of an existing token, not a new markup pattern). Word growth: 637 vs 532 (+19.7%), the heaviest of the five this round. |
| simple-prose | 4 | No drops. Fluent, natural decomposition without STE staccato. Consistent lowercase `dirge` throughout -- round 2's capitalisation slip (three instances, that run) did not recur under this skill on this model. Modal imperative on the build-flag line ("You must build dirge with `--features plugin`"). Word growth: 620 vs 532 (+16.5%). |
| govuk-style † | 4 | No drops. Restructures several of the source's dense multi-clause bullets into flatter, atomic bullets (for example, the hooks-and-hedges bullet and the plugin-UI-functions bullet each become several one-fact bullets) -- arguably a genuine parse-improvement for an agent reader, matching the rubric's "one instruction per sentence" check more closely than the source. Consistent lowercase `dirge` throughout. No longer the leanest rewrite of the five this round: 629 vs 532 (+18.2%), against round 2's distinguishing +12%. **† This score is confounded by the extra self-revision pass this session applied only to this sample (see "Two confounds" above) and must not be read as directly comparable to the other four conditions' single-pass scores on this row, nor cited alone as evidence for any conclusion.** |
| no-skill-control | 3 | No drops, but the lightest-touch rewrite of the five: word growth is only 551 vs 532 (+3.6%), close to the rubric's "near-no-op" anchor (3), against the four skills' +14.8% to +19.7%. Retains the source's passive framing on the build-flag line almost verbatim ("Building with `--features plugin` is required" vs source's "Requires building with `--features plugin`" -- no active-voice conversion). Also capitalises "Dirge" at bullet starts (the same minor slip as asd-ste100, not capped for the same reason). Some sentence-splitting is present (semicolons and em-dashes become full stops), but materially less restructuring and directive-precision improvement than any of the four skills produced on this weaker model. This is the one dimension-1 result that differs in shape from round 2, where all five conditions tied at 4 (see `report.md`). |

## Dimension 2: explanatory-prose output quality

### `explanatory-tighten-org-tasks.org`

Source Intent is a goal statement: "Make '...' answerable... and stop...".

| Condition | Score | Justification |
|---|---|---|
| asd-ste100 | 4 | No drops. Clean decomposition of the Intent into two sentences; paraphrases the quoted question ("which task is selected, and what is its plan?") rather than keeping it verbatim -- meaning unchanged, confirmed by the probe counts above. Reads well throughout. |
| technical-prose | 4 | No drops. Keeps the source's quoted question verbatim ("what's the selected task / its plan?"), a small fidelity edge over asd-ste100. Natural decomposition, good voice preservation. |
| simple-prose | 4 | No drops. Fluent, front-loaded, preserves the source's comparative "instead of" framing without injecting a new imperative. Splits two Gotchas-equivalent bullets in the Scope section into separate bullets (27 vs 20 source bullet lines) -- a structure change, not a content loss, confirmed by the identical token counts above. |
| govuk-style | 4 | No drops. Preserves the comparative "instead of" framing; natural, close paraphrase. Drops the org bold-emphasis markup on the `*Level:* ... *Tests:* ... *Docs:* ...` line, rendering it as plain "Level: production, Tests: thorough, Docs: ..." -- this is a `govuk-style`-inherent trait, not a model-dependent regression: round 2's `govuk-style` run on the same sample did the same thing ("Level: production. Tests: thorough. Docs: README, skill and contract.", `round2/outputs/govuk-style/explanatory-tighten-org-tasks.org` line 10), so it is not scored down here. |
| no-skill-control | 3 | No drops, but a milder recurrence of round 2's exact defect on this exact sample: the source's comparative clause ("in one cheap `ot` call instead of multiple raw file reads") is split out into a new, freestanding imperative sentence not present in the source ("Use one call instead of multiple raw file reads."). Round 2's version of this defect was sharper (a second-person negative command, "Do not use several raw file reads."); this round's version is a milder restatement rather than an inversion, so it is scored as neutral (3) rather than capped at 2. That the same sample, under the same one-line instruction, produces the same class of register drift on both a strong and a weak model is evidence the defect belongs to the instruction, not to either model. |

### `explanatory-consolidate-policy.org`

Source Decisions/Scope prose is already terse, shorthand-heavy record style (`::`, `+`, parenthetical abbreviations), and uses org's `~verbatim~` markup exclusively (23 occurrences, 0 backticks; `grep -o '~[a-zA-Z]' round2/samples/explanatory-consolidate-policy.org | wc -l` = 23, `grep -o` for a leading backtick returns 0 -- the positive control for the structure-fit probe below).

| Condition | Score | Justification |
|---|---|---|
| asd-ste100 | 4 | No drops (all 17 source bullets, all 7 `ISC-*` items present, confirmed above). Expands the terse shorthand into fuller sentences without breaking `~verbatim~` markup (23 tildes preserved, 0 backticks introduced). Readability gain over an unusually compressed source. |
| technical-prose | 4 | No drops. Same expansion pattern and quality as asd-ste100, `~verbatim~` markup preserved (24 tilde-wrapped tokens; the extra one is a repeated occurrence of an existing token, not a new markup pattern -- checked by diffing the sorted unique token sets against the source, which are identical). The round 2 finding that this skill adds gratuitous `~code~` markup around plain-prose field names did not recur here. |
| simple-prose | 4 | No drops. Reorders the Intent's two clauses, leading with "Make `ot` the sole owner..." where the source and most other rewrites lead with "Remove the policy logic...". Meaning is unchanged (probe-confirmed). This is the same reordering pattern `simple-prose` produced on this exact sample in round 2 under the strong model -- a skill-consistent stylistic signature, not a model artefact. `~verbatim~` markup preserved (23 tildes, matching the source exactly). |
| govuk-style | 2 | No drops, but a genuine structure-fit defect: every one of the source's 23 `~verbatim~` spans is rendered as a Markdown-style backtick span instead (0 tildes, 23 backticks in the rewrite -- `grep -o` confirms both counts). This is **new to round 3**: round 2's `govuk-style` run on this identical sample preserved the org convention correctly (23 tildes, 0 backticks; `round2/outputs/govuk-style/explanatory-consolidate-policy.org`), so this is a round-3/weak-model-specific regression, not a recurring `govuk-style` trait like the "Level:" line above. The rubric's dimension-2 anchor 2 ("structure broken") applies: org format conventions -- specifically inline verbatim markup -- are not respected here, even though no content was lost. This sample carries no revision-pass confound (only the strict sample did), so this score stands on its own as evidence, unlike the dagger-marked dimension-1 score above. |
| no-skill-control | 4 | No drops. Genuine, non-trivial STE-style restructuring: semicolons and compound clauses are split into separate sentences, and the source's "Chose to X" Decision headers become bare imperative verb phrases ("Put the cycle order in...", "Pass `--relative-to`/`--as`...", "Delete the overlay's in-process status fallback..."). No register defect recurs here -- the source's own Intent for this sample is already imperative-styled, matching round 2's finding that the control's defect does not appear on this particular sample under either model. `~verbatim~` markup preserved exactly (23 tildes, 0 backticks). |

## Dimension 3: skill-artefact cost

The skill artefacts (`skills/asd-ste100/SKILL.md`, `skills/technical-prose/SKILL.md`,
`skills/simple-prose/SKILL.md`, `candidates/govuk-style.md`) did not change
between round 2 and round 3; only the model producing the rewrites changed.
Dimension 3 measures the artefact itself, not a model's output, so it is not
re-measured here. The round 2 figures are carried forward unchanged and
re-verified with `wc -w` against the current, still-unmodified files:

| Candidate | Body words | Runtime-relevant density | Corrects the runtime-context defect | Score |
|---|---|---|---|---|
| asd-ste100 | 2,435 | ~84% (unchanged) | No -- incumbent defect | 3 |
| technical-prose | 2,342 | ~84%, materially unchanged | Partially (see round 2's "Known limitation") | 3 |
| simple-prose | 833 | ~98% | Yes | 5 |
| govuk-style | 956 | ~97% (round 1 figure, snapshot unchanged) | Yes (control artefact, not adoptable) | 5 |
| no-skill-control | 0 | Not applicable | Not applicable -- fixed floor | not scored, per rubric |

## Totals by dimension

| Condition | D1 (strict) | D2 (explanatory, sum of two samples, /10) | D3 (artefact, unchanged from round 2) |
|---|---|---|---|
| asd-ste100 | 4 | 4 + 4 = 8 | 3 |
| technical-prose | 4 | 4 + 4 = 8 | 3 |
| simple-prose | 4 | 4 + 4 = 8 | 5 |
| govuk-style † | 4 † | 4 + 2 = 6 | 5 |
| no-skill-control | 3 | 3 + 4 = 7 | 0 words / not applicable |

† `govuk-style`'s dimension-1 score of 4 is confounded by the extra revision
pass applied only to that sample (see "Two confounds" above). Do not read it
as directly comparable to the other four conditions' single-pass dimension-1
scores.

Combined output-quality total (D1 + D2, out of 15): asd-ste100 12,
technical-prose 12, simple-prose 12, govuk-style 10 (D1 confounded; D2
independently capped by the structure-fit defect above, so this total does
not rest on the confound alone), no-skill-control 10. See `report.md` for
the control-question and hypothesis analysis built on these totals.

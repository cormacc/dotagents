<!-- Round 3 comparison report for the prose-style skill evaluation. Written: 2026-08-19. Tests the model-dependence hypothesis from the record's ** Decisions entry "Round 3 repeats the five conditions on a limited local model". Recommendation not confirmed here -- the user owns that confirmation. -->

# Round 3: comparison and the model-dependence hypothesis

## Method

The same five conditions as round 2 -- `asd-ste100`, `technical-prose`,
`simple-prose`, `govuk-style` (control only, not adoptable), and the
no-skill control -- each rewrote the same three `round2/samples/` files
under the unchanged `round2/rubric.md`, in one fresh, isolated,
non-interactive `pi --print` session per condition. The only change from
round 2 is the model and provider: `lemonade/Qwen3.8-27B-GGUF-Q4_K_M`
replaces `anthropic/claude-sonnet-5`. Scores are in `round3/scores.md`;
isolation evidence is in `outputs/isolation-check.md`; harness verification,
including a harness-level model-configuration correction, is in
`harness.md`.

**Comparability statement, required by the assignment.** Round 3 scores are
not comparable with round 1 or round 2 scores, because the model differs in
every round (`claude-opus-5` through a `claude -p` harness in round 1,
`claude-sonnet-5` through `pi --print` in round 2, and
`Qwen3.8-27B-GGUF-Q4_K_M` through `lemonade` in round 3). A score of 4 in
round 3 does not mean the same thing as a score of 4 in round 2; only the
comparison *within* round 3 (five conditions, one model) carries direct
meaning. Where this report compares round 3 against round 2, it compares
the *shape* of the within-round gaps -- which conditions separate from which
others, and by how much -- not the raw numbers themselves.

## Two confounds, carried through the analysis rather than smoothed over

1. **`govuk-style`'s extra revision pass.** Per `outputs/isolation-check.md`,
   the `govuk-style` session re-read and rewrote its own first-pass output
   for `strict-dirge-plugin-model.md` before finishing -- the only session,
   and the only sample, with a second pass. Its dimension-1 score (4,
   marked `†` throughout `scores.md` and this report) is not comparable to
   the other four single-pass dimension-1 scores and does not, on its own,
   carry any conclusion below. `govuk-style`'s two dimension-2 scores (one
   sample each) were single-pass and are not affected.
2. **The harness needed a model-configuration override to produce any
   output.** The first attempt at all five sessions hit this GGUF's default
   4096-token output cap mid-reasoning (four conditions) or a transient
   provider-resolution error (the fifth); see `harness.md`. An untracked
   `pi/models.json` `modelOverrides` entry raised `maxTokens` to 32768 and
   mapped pi's thinking levels onto this model's accepted `reasoning_effort`
   values (pinning `high` to `medium`) before all five conditions were
   re-run in full. All five conditions ran under the identical corrected
   configuration, so the within-round comparison this report relies on is
   unaffected. But `pi/models.json` is untracked and does not commit with
   this record, so **round 3 is not reproducible from the repository alone**
   -- reproducing it requires recreating that override.

## Results

| Condition | Strict text (D1) | Explanatory prose (D2, /10) | Combined output quality (/15) | Artefact cost (D3, unchanged from round 2) |
|---|---|---|---|---|
| asd-ste100 (incumbent) | 4 | 8 | 12 | 3 |
| technical-prose | 4 | 8 | 12 | 3 |
| simple-prose | 4 | 8 | 12 | 5 |
| govuk-style (control only) | 4 † | 6 | 10 | 5 |
| no-skill-control | 3 | 7 | 10 | 0 words / not applicable |

† Confounded by the extra revision pass; see above. Do not read `govuk-style`'s
row as a clean data point for the model-dependence question -- its D1 cell is
confounded and its D2 total is independently capped by a structure-fit defect
found below, unrelated to the confound.

## Meaning-preservation audit

Checked with controlled probes on all fifteen rewrites, exactly as round 2
did: distinctive-token counts against the source (with every pattern first
confirmed present in the source at a known count, as the positive control),
plus section-level bullet, checkbox, and heading item counts. Full method
and counts are in `scores.md`.

**Result: no drop was found in any of the fifteen round 3 rewrites.** Every
probed token, and every section's item count, matched the source in every
condition, including the no-skill control. This is the highest-value finding
the assignment asked for, and it runs counter to the assignment's own stated
expectation ("a weaker model is more likely to drop content"): on this MVP,
one-run-per-condition sample, the weaker model did not drop content any more
than the strong model did in round 2. This does not prove a weaker model
never drops content -- one clean run is not proof of a general property, the
same limitation round 2's report noted for its own clean-run evidence -- but
the specific worry that motivated flagging this as "the highest-value check
in the round" did not materialise here.

One structure-fit defect was found that is not a content drop:
`govuk-style`'s rewrite of `explanatory-consolidate-policy.org` converts all
23 of the source's org `~verbatim~` spans into Markdown-style backtick spans
(0 tildes, 23 backticks in the rewrite). This is new to round 3: round 2's
`govuk-style` run on the identical sample preserved the convention correctly
(23 tildes, 0 backticks). No content is lost -- every fact, token, and item
count still matches -- but the org format convention is not respected, which
the rubric's dimension-2 "structure fit" check treats as a defect (anchor 2,
"structure broken"), separately from meaning preservation. This sample was
not part of `govuk-style`'s extra-pass confound, so this finding stands on
its own.

A second, unrelated `govuk-style` trait -- dropping the org bold-emphasis
markup on the `*Level:* ... *Tests:* ... *Docs:* ...` summary line -- recurs
identically in round 2's run of the same two samples. That trait is
attributable to the skill/gist itself, not to the model, and is not treated
as a round 3 finding.

## The no-skill control's standing on this model

**Dimension 1 (strict text): the control now loses, where it tied in round
2.** All four skills score 4/5 (`govuk-style`'s 4 is confounded, but
`asd-ste100`, `technical-prose`, and `simple-prose` are clean, single-pass
4s). The no-skill control scores 3/5 -- the lightest-touch rewrite of the
five, with word growth of only +3.6% against the four skills' +14.8% to
+19.7%, retaining the source's passive framing on the build-flag sentence
almost verbatim where every skill converted it to an active or imperative
form. This is the one result in round 3 that differs in *shape* from round
2, where all five conditions, including the control, tied at 4/5 on this
same dimension.

**Dimension 2 (explanatory prose): the margin over the control is
essentially unchanged in shape from round 2.** `asd-ste100`,
`technical-prose`, and `simple-prose` each score 8/10 against the control's
7/10 -- a 1-point margin. This is the *same* margin, on the *same* sample,
driven by the *same class of defect* round 2 found: the control's session,
whose only instruction is "Use Simplified Technical English", turns the
source's descriptive comparative clause ("in one cheap `ot` call instead of
multiple raw file reads") into a new, freestanding imperative sentence not
present in the source. Round 2's version of this defect was sharper (a
second-person negative command); round 3's is a milder restatement. That the
same instruction produces the same class of register drift on both a strong
model and a weak model is evidence the defect belongs to the control's own
one-line instruction, not to either model's capability level.
`govuk-style`'s D2 total (6/10) is lower than round 2's (10/10), but that
drop is fully explained by the new structure-fit defect above, not by the
control gaining ground on `govuk-style` -- `govuk-style` is not an adoptable
candidate regardless.

**Summary of the control's standing:** it loses by 1 point on strict text
(new this round) and trails by 1 point on explanatory prose (unchanged from
round 2, same sample, same defect class). Combined output-quality totals put
the control at 10/15, two points behind the three adoptable skills' 12/15
each.

## The hypothesis, answered directly

**Hypothesis under test:** the value of a prose skill is model-dependent. If
the no-skill control loses clearly to the skills on the weaker model while
tying on `claude-sonnet-5`, skills earn their cost for weaker models and a
no-skill mandate is wrong. If the control ties again, a no-skill mandate
gains the support of a second model tier.

**Answer: partial, direction-consistent, but thin -- neither branch of the
hypothesis is cleanly confirmed.** The evidence splits by dimension:

- On dimension 1 (strict text), the control's standing *did* weaken on this
  weaker model: it lost by 1 point to all three adoptable skills, where it
  tied at 4/5 in round 2. This is the only result in round 3 that points in
  the direction the hypothesis predicts (weaker model → skills earn their
  cost). It rests on a single sample, a single unreplicated run per
  condition, and a 1-point margin -- exactly the kind of evidence round 2's
  own report cautioned against over-reading when it found a symmetrical
  1-point margin on dimension 2. The same honesty standard applies here: read
  this as suggestive that a no-skill mandate is not risk-free for weaker
  models on strict text, not as an established finding.
- On dimension 2 (explanatory prose), the control's standing is essentially
  *unchanged* from round 2: the same sample produces the same 1-point margin
  via the same class of defect on both model tiers. This does not support
  the hypothesis -- if the skills' value were model-dependent in the way the
  hypothesis proposes, the explanatory-prose margin should also have opened
  up on the weaker model, and it did not.

**Does this change the round 2 recommendation?** It does not overturn the
round 2 reading, and it does not strengthen the case for a no-skill mandate
(option 2 in round 2's report). If anything, it introduces a caveat against
extending round 2's dimension-1 finding -- "nothing here justifies paying
any skill's context cost for strict text" -- to weaker models: on this one
weaker-model run, that specific tie broke, and it broke in the skills'
favour. Round 2's dimension-2 reading (a thin, single-sample, single-point
margin that has not been shown to be robust) is left materially unchanged:
round 3 replicates the same margin, on the same sample, via the same defect,
rather than either confirming or refuting it more strongly.

**What would settle it.** A second strict sample and a repeat run of the
no-skill control on `strict-dirge-plugin-model.md` on this same model, to
check whether the dimension-1 gap that opened here replicates or was one
session's idiosyncrasy -- exactly the same escape hatch round 2's report
proposed for its own thin margins, now needed on a second axis. A rerun of
`govuk-style`'s `explanatory-consolidate-policy.org` sample would also show
whether the markup-substitution defect is a property of this model or one
session's idiosyncrasy; it does not bear on the adoptable-skill question
either way, since `govuk-style` was never a mandate candidate.

## Caveats

- One run per condition, as in round 1 and round 2 (the record's MVP effort
  bar). Every score above is a single sample from a single session. The
  dimension-1 gap that opened this round is exactly one such unreplicated
  data point.
- All five round 3 sessions used `lemonade/Qwen3.8-27B-GGUF-Q4_K_M`.
  Round 3 cannot be compared against round 1's or round 2's raw scores, for
  the reason stated in the comparability statement above.
- `govuk-style`'s dimension-1 score is confounded by an extra revision pass
  that no other condition or sample received. It is reported, but it does
  not carry any conclusion in this report on its own.
- The round used an untracked `pi/models.json` model-configuration override
  to produce any output at all. This is recorded as a condition of the
  round; the round is not reproducible from the repository alone.
- Scoring is not blinded, as in round 1 and round 2. The fixed rubric,
  unchanged from round 2, is the rigour bar for this MVP effort level.
- This report does not confirm a mandate decision. `** Decisions` in the
  parent change-record still requires the user's confirmation before
  `1c0d65e6` (rewire the prose mandate) can proceed.

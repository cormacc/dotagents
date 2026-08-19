<!-- Round 2 comparison report for the prose-style skill evaluation. Written: 2026-08-19. Recommendation pending user confirmation. -->

# Round 2: comparison and recommendation

## Method

Five conditions each rewrote the same three samples in an isolated, fresh,
non-interactive `pi --print` session: the vendored incumbent `asd-ste100`,
the two owned skills `technical-prose` and `simple-prose`, the unchanged
round 1 `govuk-style` gist snapshot, and a no-skill control whose only style
instruction was the verbatim line "Use Simplified Technical English." The
round 2 rubric (`round2/rubric.md`) was fixed and committed before the first
run. Scores are in `round2/scores.md`. Isolation evidence is in
`round2/outputs/isolation-check.md`, and harness verification is in
`round2/harness.md`. All five sessions ran on `anthropic/claude-sonnet-5`
(round 1 ran on `claude-opus-5` through a different harness). Round 2 scores
are not compared against round 1 scores for that reason -- both the harness
and the model differ.

`govuk-style` is not itself an adoptable mandate candidate. The record's
`** Decisions` already rejected vendoring the gist (no `skills/` structure,
so `skills-lock.json` cannot track it, and the no-local-edits rule for
vendored skills blocks the exact fix this evaluation wants). It runs in
round 2 only as a control for whether the round 1 content drops recur (see
below).

## Results

| Condition | Strict text (D1) | Explanatory prose (D2, /10) | Combined output quality (/15) | Artefact cost (D3) |
|---|---|---|---|---|
| asd-ste100 (incumbent) | 4 | 8 | 12 | 3 |
| technical-prose | 4 | 8 | 12 | 3 |
| simple-prose | 4 | 8 | 12 | 5 |
| govuk-style (control only) | 4 | 10 | 14 | 5 |
| no-skill-control | 4 | 7 | 11 | 0 words / not applicable |

## Meaning-preservation audit

Round 1 capped `govuk-style` twice: it dropped the `proactively` scope
qualifier in the strict sample, and a whole `** Shipped` item in the record
sample. The round 2 rubric makes meaning preservation the cardinal check
again, so every one of the 15 round 2 rewrites was checked with controlled
probes, not by reading alone.

**Method.** For each sample, distinctive tokens that appear a fixed number
of times in the source were counted in the source and in every rewrite:
`7KB`, `otList`, `f361c429`, `hasOpenQuestions`, `org_read_section`,
`insertTaskIntoFile`, `persistent-widget`/`persistent widget`,
`status-line`/`status line`, `9,600`/`9.6k`, `1,100`/`1.1k`, and the
non-exhaustive-list hedge on the hooks enumeration (`etc.`/`and so on`/`and
others`/"not limited to"). Section-level bullet, checkbox, and heading
counts were also compared line-by-line against the source (full counts in
`scores.md` and reproduced below). Positive control: every probe pattern
was first confirmed present in the source at its expected count, so a zero
count in a rewrite is read as a genuine absence rather than a broken probe
(for example, `grep -icE '7.?KB' samples/explanatory-tighten-org-tasks.org`
returns 2, confirming the pattern and the count method both work before any
rewrite is checked against it).

**Result.** Every probed token and every section's item count matched the
source in every one of the 15 rewrites. Two apparent bullet-count
mismatches were manually traced and confirmed as reformatting, not loss:
`asd-ste100`'s strict-text and Sample A rewrites split one source bullet
joined by "or" into two bullets (content identical, `grep`-confirmed), and
`simple-prose`'s Sample A rewrite split two multi-clause Gotchas bullets
into separate bullets (again content-identical). No rewrite in round 2 --
including `govuk-style`'s -- dropped a qualifier, a condition, a hedge, an
exception, or a list item.

## Recurrence: did the round 1 drops repeat in the round 2 gist run?

No. `govuk-style`'s round 2 run preserved all meaning on all three samples,
under the same probes that caught the round 1 drops. `simple-prose`'s round
2 run was also clean.

The record's own decision framework (`** Decisions` § "Pivot") set the test
in advance: "a repeat drop in the gist run alongside a clean `simple-prose`
run attributes the defect to the gist. Two clean runs point to run
variance." Round 2 produced two clean runs. This points to run variance in
the round 1 drops, not a property of the gist's content or style. It does
not rule out that the gist can still drop content on some other input --
one clean run is not proof of a general property, any more than round 1's
one drop-containing run proved the opposite -- but the specific round 1
evidence for a gist-inherent defect no longer stands unqualified.

## Control question

**Did any candidate beat the no-skill control, and by how much?**

On dimension 1 (the strict sample), no. All four skills tie the control at
4/5. The prompt line "Use Simplified Technical English", with no skill
loaded, produced output indistinguishable in checkable quality from any of
the three STE-flavoured or GDS-flavoured skills on this specific sample.

On dimension 2 (the two explanatory samples), the picture splits:

- `govuk-style` clearly beats the control: 10/10 against 7/10, a 3-point
  margin driven by consistently closer, leaner paraphrase and by avoiding
  the control's one voice-flattening defect (see `scores.md`). But
  `govuk-style` is not an adoptable candidate (see Method).
- `asd-ste100`, `technical-prose`, and `simple-prose` each beat the control
  by exactly 1 point (8/10 against 7/10). The entire margin traces to one
  defect in the control's Sample A output: it turned the source's
  comparative clause "instead of multiple raw file reads" into an
  unsourced second-person command, "Do not use several raw file reads." On
  Sample B, all three skills and the control score identically (4/4/4/4).

**Honesty check on that 1-point margin.** This is a one-run comparison
(the record's MVP effort bar), and the entire margin for three of the four
candidates rests on a single sentence in a single sample. A second strict
sample and a rerun of the control on Sample A would show whether that
defect recurs or was one session's idiosyncrasy. Read the "each skill beats
the control by 1 point" finding as suggestive, not established.

**Did each skill earn its per-session context cost?**

- `asd-ste100`: 2,435 words every session, for a 1-point margin over a
  0-word control on an unreplicated single-sentence difference. This does
  not look like the cost is earning its keep on this round's evidence.
- `technical-prose`: 2,342 words every session (a bare 4% saving over the
  incumbent, within the dimension's own stated noise floor -- see
  `scores.md` § Known limitation), for the same 1-point margin. Same
  answer as the incumbent: not clearly earning the cost on quality grounds.
  Its case, if any, rests on being an unencumbered owned artefact rather
  than on quality or cost superiority shown here.
- `simple-prose`: 833 words every session (roughly a third of the other
  two skills' cost), for the same 1-point margin. The output-quality case
  is exactly as thin as the other two skills', but the cost is far lower,
  which makes `simple-prose` the most cost-effective of the three
  adoptable skills on this evidence, even though none of the three has a
  strong quality case standing alone.
- The no-skill control's own floor is 0 words. Given that every skill's
  quality margin over that floor is thin and unreplicated, a defensible
  reading is that the current evidence supports the mandate being a prompt
  line for strict text specifically (where the margin is zero, not just
  thin), while the explanatory-prose margin is too thin on one run to
  settle whether a skill is worth its cost there either.

## Skill-artefact audit

Word counts are `SKILL.md` (or snapshot) bodies with frontmatter excluded,
computed with `wc -w`, not estimated.

| Candidate | Body words | Runtime-relevant density | Corrects the runtime-context defect |
|---|---|---|---|
| asd-ste100 | 2,435 | ~84% | No -- this is the incumbent defect the audit measures |
| technical-prose | 2,342 | ~84%, materially unchanged | Partially. See "Known limitation" below |
| simple-prose | 833 | ~98% | Yes |
| govuk-style | 956 | ~97% (round 1 figure, snapshot unchanged) | Yes (control artefact only) |

**Known limitation, honestly reported rather than smoothed over.** The
round 2 rubric itself flags that dimension 3 cannot separate
`technical-prose` from `asd-ste100`: the record's own correction found that
~307 of round 1's originally-counted ~400 "runtime-irrelevant" words are
actually write-time rules embedded in `## Source and Scope` and
`## Boundaries` (the no-dictionary-reproduction statement, the
plainest-word principle, the word-by-word escape hatch, and two will-not
bullets). Only ~93 words were genuinely extractable, and `technical-prose`
extracted them: 2,342 words against the incumbent's 2,435, a 4% difference.
This evaluation does not treat that 4% as a finding that `technical-prose`
"failed" to correct the defect -- the dimension is not sensitive enough at
this margin to compare the two candidates, exactly as the rubric predicted
before any run happened. Directly re-measured at rule granularity (per the
record's own instruction, not by re-using round 1's coarser section-level
split): `technical-prose`'s opening paragraph is a 51-word
aerospace/maintenance-technician origin statement, functionally unchanged
from the incumbent's own 52-word equivalent paragraph (the incumbent names
"ASD, the AeroSpace and Defense Industries Association of Europe"
explicitly; `technical-prose` drops that clause and adds a
`references/provenance.md` pointer of about the same length instead). The
defect the record originally set out to fix -- runtime-irrelevant context
loaded into every session -- is present in `technical-prose` at close to
the same scale as in the incumbent it refactored, at least in this opening
paragraph and in `## Boundaries`'s licensing restatements.

`simple-prose`, authored fresh rather than refactored, does not carry this
problem: its only non-write-time content is a twelve-word attribution
clause in the opening paragraph ("These rules are adapted from the GOV.UK
writing guidelines and style guides..."), out of 833 body words.

**Word-count discrepancy, resolved.** The change-record's `* Implementation`
states `simple-prose` is "908 words authored". That is the whole file's
`wc -w`, including 74 words of YAML frontmatter (`name:` plus a 47-word
`description:` line). The round 2 rubric's dimension 3 explicitly excludes
frontmatter, giving 833 body words, which is the figure comparable to the
other three rows in the table above (all likewise body-only). Both 908 and
833 are correct for what each measures.

## Merge inputs

The record's open question "Merge `technical-prose` and `simple-prose` into
one dual-voice skill?" is explicitly not decided here. These are the two
measurements it asked round 2 to supply.

**Body overlap.** Tokenising both bodies to lower-case unique word types
(3+ letters, punctuation stripped) gives 669 distinct types in
`technical-prose` and 324 in `simple-prose`, with 147 shared -- a Jaccard
overlap of roughly 17% (147 divided by 669 + 324 - 147). Most of that overlap is
generic connective vocabulary. A smaller number of rules are stated in
near-identical terms in both bodies:

- Meaning preservation. `technical-prose`: "Preserve every fact, condition,
  and scope qualifier in the original" plus "Silently drop a safety
  condition, exception, or scope qualifier to shorten a sentence" (listed
  under "Will not"). `simple-prose`: "Do not drop a qualifier, a hedge, a
  condition, an exception or a list item." Same rule, same four or five
  named categories, independently authored.
- Semicolons. `technical-prose`: "No semicolons (Rule 8.1) ... Any
  semicolon at all -- STE bans the mark outright." `simple-prose`: "Do not
  use semicolons in running text. Split the sentence instead." Same
  directive.
- Active voice with the same passive-voice exception.
  `technical-prose`: "'The agent deletes the file.' ... unless the actor is
  genuinely unknown or irrelevant." `simple-prose`: "Use the active voice:
  say who does what. The passive is acceptable when the outcome matters
  more than the actor."

Beyond these, the two bodies diverge: `technical-prose`'s structural/lexical
rule tables, the STE two-mode split (Strict / STE-flavored), and the
scan-checklist habits have no `simple-prose` counterpart, and
`simple-prose`'s formatting conventions (numbers, dates, link text, bullet
punctuation, British spelling mandate) have no `technical-prose`
counterpart.

**Routing clarity.** The two descriptions cross-reference each other and
split cleanly by reader, matching the rubric's own D1/D2 split:
`technical-prose` targets "English text [that] must be parsed without a
human to resolve ambiguity" and explicitly excludes "prose that wants an
informal plain-English human voice". `simple-prose` targets prose "whenever
a human reads to understand" and explicitly states "Not for strict
machine-consumed or operational text such as tool descriptions, error
messages, and inter-agent instructions -- the technical-prose skill owns
those." Each description names the other skill and the boundary criterion
(machine-parsed versus human-read) in the same sentence. Routing between
them, if both stay mandated, reads as unambiguous from the descriptions
alone.

## Recommendation, pending user confirmation

**No single clean winner emerges from this round's evidence**, and the
honesty requirement on this task is to say so rather than force a tidy
answer from a thin, unreplicated margin. Three candidate shapes are
defensible from the scores above, in descending order of how strongly this
evidence supports each:

1. **Split by prose class: no skill mandate for strict text, `simple-prose`
   for explanatory prose.** Supported by: dimension 1 shows a flat tie
   across all four skills and the control, so nothing here justifies
   paying any skill's context cost for strict text. Dimension 2 shows
   `simple-prose` matching `technical-prose`'s and the incumbent's output
   quality at roughly a third of the context cost, while `govuk-style`
   (not adoptable) shows the GDS voice can do even better on explanatory
   prose than any of the three adoptable skills managed this round. This
   is the reading with the strongest evidence behind it, but the
   dimension-2 margin over the control is thin (1 point, one run) --
   see the caveat below.
2. **No skill mandate at all. The control's prompt line covers everything.**
   Supported by: the dimension-2 margin every skill holds over the control
   is a single point traced to a single sentence in a single sample. If
   that margin does not replicate, no skill has shown it earns its cost
   anywhere in this round's evidence, and the prompt line is free.
3. **Retain the incumbent unchanged.** Not well supported. `asd-ste100`
   ties `technical-prose` and `simple-prose` on output quality and loses to
   `simple-prose` on cost by roughly a factor of three, so nothing in this
   round's evidence favours keeping the incumbent specifically over
   `simple-prose`, and the incumbent still carries the runtime-context
   defect this whole evaluation exists to address.

**What would decide between (1) and (2):** a second strict sample (to
confirm the D1 tie is not an artefact of this one sample) and a rerun of
the no-skill control on Sample A (to confirm the voice-flattening defect
that produces the whole D2 margin is real rather than one session's
idiosyncrasy). Both are cheap relative to the decision at stake -- the
record's own escape hatch for a single-defect-decided score is exactly this
kind of targeted rerun.

**This recommendation is not a decision.** `** Decisions` in the parent
change-record must carry the confirmed round 2 decision and the user's
confirmation before `1c0d65e6` (rewire the prose mandate) can proceed. This
report does not choose between options 1 and 2 on the user's behalf.

## Caveats

- One run per condition, as in round 1 (the record's MVP effort bar). Every
  score above is a single sample from a single session. None of the
  differences reported here should be read as more robust than that.
- All five round 2 sessions used `claude-sonnet-5`. A different model
  family may rank the conditions differently, and round 2 cannot be
  compared against round 1's `claude-opus-5` scores for the same reason.
- The dimension-3 near-tie between `asd-ste100` and `technical-prose` is a
  limitation of the measurement at this margin, not a finding that either
  candidate is better or worse on cost. See "Known limitation" above.
- Scoring is not blinded, as the round 2 rubric states up front. The fixed
  rubric, committed before any run, is the rigour bar for this MVP effort
  level.

<!-- Comparison report for the prose-style skill evaluation. Written: 2026-08-18. Recommendation pending user confirmation. -->

# Prose-style skill evaluation: comparison and recommendation

## Method

Three candidates each rewrote the same three reference samples in an isolated, fresh, non-interactive session: the govuk-style gist, the SimpleEnglish skill, and the incumbent vendored `asd-ste100` skill. The rubric (`rubric.md`) was fixed and committed before the first run. Scores are in `scores.md`. Isolation evidence is in `outputs/isolation-check.md`.

Harness note: the planned harness was `pi --print`, but Anthropic refused every new headless pi session with a third-party extra-usage error, so the runs used `claude -p` instead. All three runs used the same model (claude-opus-5), the same flags, and the same prompt template, so the comparison stays fair. The `home/AGENTS.md` § ASD-STE100 block was removed for the duration of the runs and git-restored byte-identical afterwards; both the pi and the claude context chains resolve to that file, so the removal covered the sessions.

## Results

| Candidate | Strict text (D1) | Explanatory prose (D2) | Artifact cost (D3) |
|---|---|---|---|
| govuk-style | 2 | 7 of 10 | 5 |
| simple-english | 4 | 6 of 10 | 3 |
| asd-ste100 (incumbent) | 4 | 8 of 10 | 3 |

The decisive findings:

- govuk-style dropped content in two of three samples: the `proactively` scope qualifier in the strict sample, and a whole `** Shipped` item in the record sample. Both drops were confirmed with controlled probes (`scores.md` cites them). Meaning preservation is the rubric's cardinal criterion, so both scores cap at 2. The gist's own ethos ("cut everything that does not add meaning") plausibly induces this failure mode, but with one run per candidate that causal reading is uncertain.
- simple-english preserved all meaning but never beat the incumbent: it tied on strict text, lost on both explanatory samples through voice flattening, and its Rule 1.14 mandates American spelling, which directly conflicts with the repository's British English rule. Its body is also ~35% larger than the incumbent's (3,294 vs 2,435 words), so it costs more context in every session.
- asd-ste100 preserved all meaning in all three samples and produced the best combined output quality. Its known defect stands: roughly 400 words (~16%) of its body are runtime-irrelevant provenance and licensing narrative.

## Skill-artifact audit

Word counts measured with frontmatter (and snapshot headers) excluded.

| Candidate | Body words | Runtime-irrelevant content | Density | Corrects the runtime-context defect |
|---|---|---|---|---|
| govuk-style | 956 | ~30 words of source provenance in the opening paragraph | ~97% | Yes: the body is write-time rules plus a short self-scope note |
| simple-english | 3,294 | ~155 words: the `## Limits` disclaimer block (85 measured), a copyright note, and one test anecdote | ~95% | Largely yes: near-all rules, but the sheer size replaces the dilution cost with a volume cost |
| asd-ste100 | 2,435 | ~400 words: `## Source and Scope` (234 measured), industry history in the intro (~90 of 130 measured), and licensing restatements in `## Boundaries` | ~84% | No: this is the incumbent defect the audit measures |

No artefact in this folder reproduces the ASD approved-word dictionary. The SimpleEnglish snapshot contains that skill's own paraphrased part-of-speech rulings for roughly 20 words; its body states the dictionary itself is not reproduced, and this evaluation adds no further dictionary content.

## Recommendation: reject (retain the incumbent mandate)

Recommendation, pending user confirmation: reject both candidates and keep the `home/AGENTS.md` mandate on the vendored `asd-ste100` skill.

Rationale:

- A split mandate is not supported by the scores. The candidate that would take the explanatory half (govuk-style) is the one that dropped content, and its explanatory total (7) is below the incumbent's (8). The incumbent wins or ties every output dimension.
- simple-english offers no output-quality gain over the incumbent, conflicts with the British English rule, and costs more context per session.
- govuk-style's artifact is excellent (956 words, ~97% dense) and its README rewrite scored the evaluation's only 5, but a style skill that sheds a scope qualifier and a shipped-change record entry in one evaluation cannot take over a mandate whose first duty is meaning preservation.

Caveats the confirmer should weigh:

- One run per candidate (the record's MVP effort line). The govuk-style omissions could be run variance rather than a skill property; a rerun of that one candidate would settle it cheaply.
- All runs used claude-opus-5. A different model family may rank the candidates differently.
- The incumbent's ~400 runtime-irrelevant words remain a real per-session cost. Rejecting the candidates does not correct it; a trimmed owned derivative of the incumbent (out of this task's scope, and constrained by ASD's redistribution terms) would be the natural follow-up if that cost matters.

If the user confirms the rejection, the two gated subtasks (author the owned GDS skill; rewire the prose mandate) should be cancelled per their own constraints, and the open question about retiring the vendored skill is moot.

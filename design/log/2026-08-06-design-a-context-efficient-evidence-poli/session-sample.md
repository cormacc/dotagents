# Task 9a519789 audit: parent verification cost sample

Method: enumerated `oh` ledger entries under `.tmp/herdr-orch/ledger/` in this repo (`dotfiles/agents`) and in `nmd/products/connect/portal` (the only two project roots on this machine with a populated ledger), selected entries with `status=COMPLETE`/`BLOCKED` and `captured-at` set (the parent collected a result), then queried each entry's recorded `parent-session` transcript to count post-collection verification actions.

Five parent sessions were sampled, covering 13 captured child results across reviewer, planner, worker, and scout personas:

| Session (repo, start time) | Captured result(s) | Persona |
|---|---|---|
| connect/portal 2026-08-05T13:27 | 6fee4a2d, 8fa86d19, bcf7b390 | reviewer, reviewer, planner |
| connect/portal 2026-08-04T20:50 | 12cfed8b | reviewer |
| dotfiles/agents 2026-08-06T12:33 | cfeadfb1, 7af38103, 6477af7c | planner, reviewer, reviewer |
| dotfiles/agents 2026-08-04T13:02 | 53da04cc, 64ff92da, a2e8b1c2 | worker, worker, planner |
| dotfiles/agents 2026-08-03T14:04 | 22becefd, 212a2ff9, ca22accd | planner, worker, scout |

All five sessions had a recoverable, non-empty transcript at the recorded `parent-session` path; no sampled entry was unclassifiable for missing transcript evidence.

## Per-result data

| Result | Probe/verification actions (approx.) | Distinct files re-read | Correction of the child's claim? |
|---|---:|---:|---|
| 6fee4a2d (reviewer) | 3-5 | 3 | Partial: the correct `headset.cljc` pointer was refined after an initial miss; the disputed pointer required approximately 2-3 targeted probes depending on whether adjacent reads are counted separately. |
| 8fa86d19 (reviewer) | 3-4 shared | 2 shared | No: finding strengthened, not wrong. |
| bcf7b390 (planner) | 6-7 | 1, read twice | Yes: removed a redundant or invalid inline `spec:` citation; that disputed claim required approximately 2-3 targeted probes depending on whether adjacent reads are counted separately. |
| 12cfed8b (reviewer) | ~6 | 1-2 | No: all four findings independently confirmed. |
| cfeadfb1 (planner) | 0 | 0 | N/A: parent did no independent check before summarizing. |
| 7af38103 (reviewer) | ~4 | 1 | No: mechanical `ot doctor`, `rg`, and `git diff --check` checked the parent's resulting edit rather than adjudicating the reviewer's substance. |
| 6477af7c (reviewer) | ~4 | 1 | No: same pattern as 7af38103. |
| 53da04cc (worker) | ~6 | 4 | No: the child's numbers reproduced exactly; the correction found was the parent's wrong-cwd instruction. |
| 64ff92da (worker) | ~7 | 4 | No: the child's claims reproduced exactly; the correction found was an upstream planner's assumption that a file was tracked. |
| a2e8b1c2 (planner) | 8-10 | 5 | No factual error, but an undisclosed feather-model decision was escalated to the user rather than accepted silently. |
| 22becefd (planner) | ~15 | 2 | No: every claim held up. |
| 212a2ff9 (worker) | ~13 | 4 via diff | No: worker test counts matched exactly. |
| ca22accd (scout) | ~5 | 0 | No: disproved the parent's prior hypothesis, not the scout's claim. |

## Aggregate read

- Verification-action counts per result: 0, 3-4, 3-5, 4, 4, 5, 6, 6, 6-7, 7, 8-10, 13, 15. The median cluster is 4-7, with a wide tail of 13-15 for two smoke-test-style sessions whose claims were about to be persisted as runtime config or argv behavior, and one zero-probe outlier where planner discussion was summarized without an independent check.
- Distinct-file re-read counts per result: 0, 0, 1, 1, 1, 1, 2, 2, 3, 4, 4, 4, 5. Most captured results needed 1-4 file re-reads; very few needed zero or five-plus.
- Exactly 1 of 13 results produced a clear correction of the child's claim (`bcf7b390`); one more produced a partial pointer refinement (`6fee4a2d`). Other corrections concerned the parent's assignment or an upstream planner's assumption.
- Unit distinction: aggregate action and file counts are per captured result, while the policy budget is per load-bearing claim. Each sampled disagreement required approximately 2-3 targeted claim probes depending on whether adjacent reads are counted separately; no defensible counting of either disagreement exceeded 3.

## Limitations

- Small, non-random sample: two projects, five sessions, and 13 results drawn from ledger entries surviving on this machine. It is directional evidence, not a statistically powered benchmark.
- Transcript queries summarize the parent transcript through another LLM pass rather than a mechanical count. Approximate counts are sufficient to set an initial policy bound, not a numeric service-level claim.
- The two incidents named in parent task `f5621a90` originated in archived task `b8a7727c` and did not appear verbatim in this sample. They remain qualitative anchors rather than additional counted observations.

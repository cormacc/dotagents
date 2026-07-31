# Advisor strategy comparison report

Seven cells, one run each, on the hidden-test mini org-outline benchmark
(35 cases / 6 categories). Scored with `acceptance/score`, measured with
`acceptance/metrics`. Reading conventions and calibration: `rubric.md`.

## Results

| Cell | Persona | Executor | Advisor | Score | Total $ | Exec $ | Advisor $ | Duration | Tokens | Consult |
|---|---|---|---|---|---|---|---|---|---|---|
| A | `skilled-worker` | middle | none | **35/35** | **$3.2288** | $3.2288 | – | **389.8s** | 1.17M | n/a |
| B | `worker` | light | middle | **35/35** | $5.3666 | $3.3423 | $2.0243 | 1474.4s | 3.81M | 1 used |
| C | `worker` | light | heavy | 34/35 | $7.1269 | $3.1223 | $4.0046 | 1065.2s | 2.60M | 1 used |
| D | `worker` | middle | heavy | **35/35** | $5.7398 | $2.7554 | $2.9844 | 524.5s | 1.06M | 1 used |
| E | `worker` | feather | light | 32/35 | $2.5195 | $2.3060 | $0.2135 | 737.0s | 7.51M | 1 **abandoned** |
| F | `worker` | feather | middle (default) | 34/35 | **$0.7324** | $0.7324 | – | **288.7s** | 1.54M | 0 **skipped** |
| G | `worker` | feather | heavy | **35/35** | $4.4421 | $2.1597 | $2.2824 | 1750.4s | 7.09M | 1 used |

Failed cases: C and F both lost only `nm-02-drawer-indent`; E lost
`rt-06-preamble`, `rt-08-combined`, `ps-18-preamble-captured` (all preamble
handling). A, B, D, G were clean sweeps.

Calibration for reference: a correct reference implementation scores 35/35, a
plausible-but-sloppy one 24/35 (68.6%).

## The three gate questions

### 1. Should the advisor default to heavy? — No. No evidence for it.

The two controlled comparisons both go against heavy:

- **Equal executor (light): B vs C.** Middle advisor 35/35 at $5.37; heavy
  advisor **34/35 at $7.13**. Heavy cost 33% more and scored *worse*.
- **Equal executor (middle): A vs D.** No advisor 35/35 at $3.23; heavy advisor
  35/35 at $5.74. The heavy consult added $2.98 and 135s for **zero** score gain.

Heavy only earns its cost at feather (G 35/35 vs F 34/35 vs E 32/35) — a tier no
one should be using for implementation work. Heavy consults cost $2.28–$4.18
versus $2.02 middle and $0.21 light.

Caveat: n=1 per cell. C's single lost case could be noise, so this is best read
as *"no positive evidence for heavy"* rather than *"heavy is actively worse"*.
The gate may order repeats before finalising.

### 2. Should the `--prompt-extra` override stay a convention? — It works; that is now verified.

All four override attempts produced the requested non-default tier, confirmed
independently at two levels — the nested ledger label *and* the model actually
run in the consult's transcript:

| Cell | Requested | Label | Actual model |
|---|---|---|---|
| C | heavy | `worker-4/advisor-1-heavy` | `claude-fable-5` |
| D | heavy | `worker-3/advisor-1-heavy` | `claude-fable-5` |
| E | light | `worker-5/advisor-1-light` | `claude-sonnet-5` |
| G | heavy | `worker-7/advisor-1-heavy` | `claude-fable-5` |

The free-text route is therefore *functional*, and the case for a structured flag
rests on ergonomics and discoverability rather than on reliability. Note the
mechanism is only as good as the executor honouring it — see §3 on feather.

### 3. Should worker and skilled-worker merge? — The evidence favours a no-advisor default, not a merge.

**Cell A — `skilled-worker`, no advisor — won on every axis**: joint-best score,
cheapest perfect score ($3.23), fastest perfect score (390s), and perfectly clean
restraint. Every advisor-bearing cell that matched its score cost more and took
longer.

That inverts the framing of the original question. Rather than "merge worker into
one persona with an advisor available by default", the data suggests the advisor
loop should be *opt-in* for capable executors, because at middle tier it bought
nothing measurable and at light tier it bought only parity with a stronger
executor working alone.

The advisor's one clear, decision-relevant win was **cell B**, where it caught a
genuine spec violation the light executor had introduced: extracting the TODO
keyword before stripping tags let the keyword's trailing space be consumed,
silently dropping tags after a bare keyword (`* TODO :a:b:`). The important
qualifier is that fixing it only brought B level with A, which never made the
mistake. The hidden suite has no bare-keyword-plus-tags case, so this win is
invisible in the score — deliberately not added mid-run, since a case discovered
from one cell's output would bias the comparison.

## Compliance: `requires: advisor` is not honoured at feather tier

Two of the three feather cells violated the mandatory pre-publish consult:

- **Cell E — consult spawned, then abandoned.** Prompted its advisor at 14:41:46
  and published `COMPLETE` at 14:44:50, three minutes later, without awaiting or
  capturing it. The orphaned consult was still running ~30 minutes later and had
  to be closed manually. It paid $0.21 for advice it never read.
- **Cell F — consult skipped entirely.** Zero consults despite `requires: advisor`.
  It nonetheless scored 34/35 at $0.73, the cheapest run in the matrix.

Cell E also failed the publication protocol twice: it first wrote a Markdown
report directly into the `RESULT` path mid-flight — causing a premature `invalid`
capture by the parent — and its eventual real envelope appended prose
descriptions to the `ARTIFACTS:` paths (`…/orgmini.clj Implementation code (115
lines)`), so validation failed on a non-existent artifact path. Its numeric data
is sound; it simply never produced a valid envelope.

Restraint also degraded at feather: E left three stray top-level scratch files
and modified `bb.edn`. A, C, D and G were clean; F added one extra test file.

The mandate is prompt semantics, not a mechanical gate, so a weak executor can
silently ignore it. Any decision that leans on the advisor loop for quality
assurance should treat "the consult happened and was used" as unverified unless
checked in the ledger.

## Reliability findings

- **Cell C's first attempt was lost to an unrecoverable API 400** (`thinking` or
  `redacted_thinking` blocks in the latest assistant message cannot be modified).
  The session history itself was invalid, so continuation re-failed. Its heavy
  consult had already completed at $4.18 before the crash. Re-seeded and re-run.
- **Every spawn left its prompt typed but unsubmitted**, needing an explicit
  Enter. Submit-delays measured 5.1–46.6s versus 0.8s for a pre-regression
  historical run. Worked around by `spawn-cell.sh`; the durable CLI fix is tracked
  as task `d4bd209f`.

## Benchmark limitations

- **Saturated above feather.** Four of seven cells scored 35/35, so the benchmark
  cannot resolve advisor contribution at light tier and above. The feather trio
  was added mid-run precisely because of this ceiling.
- **`nm-02-drawer-indent` was the single most discriminating case** (failed by C
  and F, and by the naive calibration implementation). Preamble handling was the
  next most discriminating, failing only E.
- Token counts are a poor proxy for cost across tiers: E burned 7.5M tokens for
  $2.52 while D used 1.06M for $5.74.
- One run per cell. Differences of a single case, or of a few minutes, are inside
  noise.

## Suggested reading for the gate

1. Do not move the advisor default to heavy; the two equal-executor comparisons
   show no gain and one regression.
2. The override convention works and is verified; treat a structured flag as an
   ergonomics decision, optionally with a cheap ledger assertion that the
   requested tier actually ran.
3. Do not merge on the assumption that an always-available advisor is a net win.
   The strongest cell had no advisor at all. If the personas do merge, the advisor
   should be opt-in, and `worker.md` step 7 must become conditional on granted
   capability.
4. Consider whether `requires: advisor` deserves any mechanical enforcement, given
   two feather cells ignored it — or whether the mandate should simply be
   documented as unenforceable and unsuitable for weak executors.
5. If the tier question is to be settled more firmly than "no evidence for
   heavy", order repeats of B vs C specifically; that is the one cell pair where
   heavy underperformed and n=1 is least satisfying.

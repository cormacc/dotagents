# Scoring rubric — advisor strategy 4-cell comparison

Root-session reference for running and reading the evaluation. Not seeded into
any cell. Cells receive `skeleton/` (including `ASSIGNMENT.md`) and nothing else.

## Cells

| Cell | Spawn | Executor | Advisor |
|------|-------|----------|---------|
| A | `skilled-worker` | middle | none (baseline) |
| B | `worker` | light | middle (current default) |
| C | `worker` + `--prompt-extra` heavy-advisor instruction | light | heavy |
| D | `worker --model middle` + same instruction | middle | heavy |

A vs D isolates the advisor loop at equal executor tier. B vs C isolates advisor
tier at equal executor. A vs B is the current shipped-defaults question.

## Seeding a cell

```sh
REC=design/log/2026-07-31-subagents-review-advisor-strategy-defaul
mkdir -p .agents/tmp/advisor-eval
cp -r $REC/skeleton .agents/tmp/advisor-eval/cell-a   # …-b, -c, -d
```

Never reuse or overlap scratch dirs — each cell gets a pristine copy.

**Contamination precautions.** `.agents/tmp/` already holds a large amount of
prior design chatter about the advisor strategy itself (previous worker/advisor
assignments, reviews, and reports). Seeding cells as siblings there means a
wandering executor could read material about the very mechanism under test.
Point each assignment explicitly at its own cell dir and instruct cells to stay
inside it. The reference and deliberately-naive implementations used to validate
the suite live **outside the worktree** at `~/.cache/orgmini-validation/` for the
same reason — a reference implementation in-tree would be a copy-paste answer
key. Do not move them back in.

## Metric 1 — quality (hidden-test pass rate)

```sh
$REC/acceptance/score .agents/tmp/advisor-eval/cell-a cell-a --edn /tmp/cell-a.edn
```

35 cases across 6 categories; exits 0 only on a clean sweep. Report the pass
rate **and** the category breakdown — where a cell loses points matters more
than the total:

| Category | Cases | What it discriminates |
|---|---|---|
| `round-trip` | 10 | byte-exact canonical serialization, incl. malformed-drawer verbatim |
| `tags` | 5 | the space-precondition rule (`3:4`, `10:30`, `:not tags:`) |
| `drawer` | 6 | adjacency, `:END:` guard, ordering, colon-bearing values |
| `todo` | 4 | recognized-set and case sensitivity |
| `structure` | 7 | body verbatim incl. blank lines, preamble, levels |
| `normalize` | 3 | padding collapse, drawer indent, value trimming |

A thrown exception counts as one errored case and never aborts the run, so a
partially working implementation still scores.

**Calibration** (already measured, see § Validation):

- Reference implementation: **35/35 (100%)** — the contract is satisfiable
  exactly as written.
- Plausible-but-sloppy implementation: **24/35 (68.6%)**, losing points in
  `tags` (2/5), `drawer` (3/6), `round-trip` (7/10). The suite discriminates.

Treat ~100% as "read the contract and honoured it", ~70% as "wrote a plausible
parser from the shape of the examples", and <50% as not meeting the assignment.

## Metric 2 — advisor consults (count, tier, verdict, compliance)

```sh
$REC/acceptance/metrics <cell-task-uuid> --edn /tmp/cell-a-metrics.edn
```

Mechanics, verified against the live ledger schema
(`.agents/tmp/herdr-subagents/ledger/*.json`):

- Consults are discovered by matching each advisor entry's `parent-session`
  against the cell's own `child-session.value`. Nested consults land in the same
  ledger directory as the root spawn — there is no separate nested ledger.
- The **resolved tier** is the trailing token of the nested `label`
  (`worker-3/advisor-1-middle` → `middle`), and is independently corroborated by
  the `modelId` observed in the consult's transcript. Use both: the label proves
  what was requested, `modelId` proves what actually ran. For cells C and D this
  is the pass/fail check on the `--prompt-extra` override mechanism.
- The **verdict** is the consult's `envelope.summary`.

**Consult-context compliance** is a judgement call the numbers cannot make, so
read the consult's assignment text in the child transcript and record:

1. Did the mandatory pre-publish consult actually happen, before publication?
2. Was it *focused* — a specific diff/decision with enough context to review —
   or a vague "review my work" with the advisor left to rediscover the task?
3. Did the executor act on the verdict, or acknowledge and ignore it?
4. Consult count against the soft cap of three; note escalation consults
   separately from the mandatory one.

A light executor skipping or short-changing the consult is a **measured
compliance outcome, not a run failure** — record it and move on.

## Metric 3 — token cost

Extracted by `metrics`, summing `message.usage` over each participant's session
transcript (`child-session`, `kind: "path"`). pi sessions record both
`totalTokens` and a real per-message `cost` map, so for pi-kind children this is
**exact, not best-effort** — the record's original "may fail" caveat applies only
to non-pi harnesses or a missing transcript.

Report executor cost, each consult's cost, and the total. Note the split: in a
historical sample a single middle consult was **37% of total spend**
($1.14 of $3.04), so the advisor loop is not a rounding error and cell C's heavy
consults should be expected to dominate.

Caveat when comparing: cost is sensitive to prompt-cache behaviour
(`cacheRead`/`cacheWrite` are priced differently), so a cheap-looking run may
simply have cached well. Quote `totalTokens` alongside dollars.

## Metric 4 — duration

`metrics` reports wall time as **mtime(RESULT file) − first user message in the
child's transcript**. Both ends are deliberate:

- The end is the publication moment, *not* `captured-at`: under the non-blocking
  policy `captured-at` records when the parent got round to collecting and can
  lag publication arbitrarily.
- The start is the transcript's first user message, *not* `prompted-at`. A
  spawned pane can leave the composed prompt typed but **unsubmitted** — observed
  when pi displayed an extension-update notification, which appears to swallow
  the submit keypress. Measured against `prompted-at` this inflated cell A by
  25.6s and cell B by 40.4s, unevenly, while a historical run from before the
  notification appeared shows only 0.8s. `prompted-at` is therefore not a safe
  start for a comparative metric.

`metrics` prints that gap as `submit-delay` so it stays auditable rather than
silently inflating a cell. A delay of more than a second or two means the pane
held the prompt; the duration figure is already corrected for it, but a very
large delay is worth noting in the report in case the child also sat idle after
submission.

Duration remains the noisiest metric (model load, retries, cache state). Treat
large gaps as signal and small ones as noise; a single run per cell cannot
resolve minutes-scale differences.

## Qualitative code-quality notes

Beyond pass rate, note per cell against the reference:

- **Structure** — clear parse/serialize split with named helpers, or one sprawling
  function.
- **Idiom** — appropriate Clojure (destructuring, `keep-indexed`, regex reuse) vs
  transliterated imperative code.
- **Contract discipline** — did it implement the *stated rules*, or pattern-match
  the visible smoke examples and guess the rest? This is the behaviour the
  advisor loop is supposed to correct, so it is the most decision-relevant
  qualitative axis.
- **Restraint** — stray files, invented dependencies, unrequested abstraction,
  or edits outside the cell dir all count against.

## Reading the result at the gate

The three open questions map onto the cells:

1. *Advisor default tier* — compare B vs C. Heavy is justified only if the
   quality gain is visible in the category breakdown, not merely in total cost.
2. *Override mechanism* — did the cell C/D `--prompt-extra` instruction actually
   produce a heavy consult (label **and** `modelId`)? If it silently failed, the
   convention is inadequate and a structured flag is indicated regardless of the
   tier decision.
3. *worker/skilled-worker merge* — compare A vs D at equal executor tier. If the
   advisor loop adds little at middle tier, a merged persona with an opt-out
   advisor is defensible; if it adds a lot, the two personas are earning their
   separate existence.

With one run per cell, a difference inside noise is a legitimate finding: it
supports "no change" or "order repeats", not a coin-flip decision.

## Validation

The suite was validated before use, in the same way it will score cells:

| Implementation | Hidden suite | Visible smoke |
|---|---|---|
| Skeleton stub | 0/35, all errored, no crash | 3 errors |
| Reference (`~/.cache/orgmini-validation/reference`) | **35/35** | 17 assertions pass |
| Naive (`~/.cache/orgmini-validation/naive`) | **24/35** | 2 failures |

Re-run after any change to `cases`:

```sh
$REC/acceptance/score ~/.cache/orgmini-validation/reference reference  # must be 35/35
$REC/acceptance/score ~/.cache/orgmini-validation/naive naive          # must be < 35/35
```

A case the reference cannot pass is a bug in the case, not in the cell.

---

# Round 2 — cellform benchmark

Round 1 saturated: A, B, D and G all scored 35/35, so advisor contribution above
feather was hidden behind a ceiling. Round 2 uses a materially tougher problem —
`cellform`, a spreadsheet formula engine — chosen so a light-tier executor
plausibly loses points.

## Cells (9)

| Cell | Spawn | Executor | Advisor |
|------|-------|----------|---------|
| H | `skilled-worker --model feather` | feather | none |
| E | `worker --model feather` + light instruction | feather | light |
| F | `worker --model feather` | feather | middle (default) |
| G | `worker --model feather` + heavy instruction | feather | heavy |
| I | `skilled-worker --model light` | light | none |
| B | `worker` | light | middle (default) |
| C | `worker` + heavy instruction | light | heavy |
| A | `skilled-worker` | middle | none |
| D | `worker --model middle` + heavy instruction | middle | heavy |

H and I are new, completing the no-advisor baseline at all three executor tiers
(round 1 had it only at middle). E–G at fixed feather isolate advisor tier under a
weak executor; B/C isolate it under light.

## Difficulty pilot — run cell I first

Cell I (`skilled-worker --model light`, no advisor) is both a required matrix cell
and the gate on benchmark difficulty, so the check costs nothing extra. If cell I
scores **100%**, the benchmark has saturated again: toughen it, re-validate
(reference / stub / sloppy), and re-run the pilot before spawning any other cell.
Below 100% means the benchmark has headroom and the matrix may proceed.

## Scoring

```sh
$REC/acceptance2/score2 .agents/tmp/advisor-eval/r2-cell-i cell-i --edn /tmp/i.edn
```

60 cases across 7 categories. Numbers compare numerically (`3` and `3.0` are
equivalent), and a thrown exception is one errored case that never aborts the run
— the same runner semantics as round 1.

| Category | Cases | What it discriminates |
|---|---|---|
| `parsing` | 14 | precedence, parens, unary minus, arity, unknown function, ref shape |
| `refs` | 5 | dependency-driven order independence, diamonds |
| `ranges` | 7 | rectangle expansion, reversed corners, range misuse outside SUM/COUNT |
| `coercion` | 5 | empty cells per context (arithmetic, CONCAT, COUNT, IF condition) |
| `strictness` | 11 | strings never coercing to numbers; `IF` evaluating both branches |
| `errors` | 8 | the precedence lattice, and SUM/COUNT propagating rather than skipping errors |
| `cycles` | 10 | participants, transitive dependents, cycle outranking other errors |

**Calibration** (measured before use):

| Implementation | Hidden suite | Visible smoke |
|---|---|---|
| Skeleton stub | 0/60, all errored, no crash | 4 errors |
| Reference (`~/.cache/cellform-validation/reference`) | **60/60** | 11 assertions pass |
| Naive (`~/.cache/cellform-validation/naive`) | **47/60 (78.3%)** | passes |

The naive implementation is the calibration that matters here: it gets every
mechanical category right (parsing, refs, ranges, coercion all 100%) and fails
only where the spec contradicts spreadsheet intuition — `errors` 2/8,
`strictness` 6/11, `cycles` 8/10. It also **passes all visible smoke tests**, so
smoke-green tells a cell nothing about its real score. Read a cell's category
profile the same way: mechanical categories measure competence, while
`strictness` / `errors` / `cycles` measure whether it actually read the rules.

Note the counter-intuitive rules are deliberate and each is stated explicitly in
the assignment: strings never coerce even when numeric-looking, `IF` evaluates
both branches so an error in the unselected branch still propagates, `SUM`/`COUNT`
skip strings and empties but never errors, and propagation takes the
**highest-precedence** operand error (`:err/cycle` > `:err/parse` > `:err/div0` >
`:err/type`) rather than the first one encountered. Precedence cases deliberately
list the lower-precedence operand first, so first-error-wins scores zero on them.

## Consult verification (required for round 2)

Round 1 found `requires: advisor` is prompt semantics, not a mechanical gate: one
feather cell spawned its mandatory consult and published three minutes later
without awaiting it, and another skipped the consult entirely. For every
advisor-bearing cell, record from the ledger:

1. **Did the mandatory pre-publish consult happen at all?** Consult count of 0 on
   a `worker` cell is a violation, not a measurement gap.
2. **Was it captured before publication?** Compare the consult's `captured-at`
   against the parent's RESULT mtime. A consult still `prompted` when the parent
   published was abandoned, and its advice cannot have influenced the result —
   report the cell as advisor-nominal rather than advisor-assisted.
3. **Which tier actually ran?** Nested label (`worker-3/advisor-1-heavy`) *and*
   the transcript `modelId`. Both, not either.
4. **Escalation consults counted separately** from the mandatory one, with tiers
   and verdicts, against the soft cap of three.

Treat an abandoned or skipped consult as a first-class result: it is evidence
about the executor tier's ability to use the advisor loop at all.

## Report row order (fixed)

Rows must be in increasing executor weight, then increasing advisor weight, with
no-advisor before any advisor tier — exactly:

```
H, E, F, G, I, B, C, A, D
```

Do not reorder by score, cost, or convenience.

## Cross-round comparison

Seven configurations (A–G) appear in both rounds. Report them side by side and
say explicitly where the round-1 ceiling concealed a difference that round 2
reveals.

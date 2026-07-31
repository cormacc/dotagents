# Feather-trio comparison report (benchmark 3, `segwin`)

The light-and-above matrix was abandoned after three difficulty axes saturated at
light tier. This round tests the one remaining live question: **does the advisor
loop pay at feather**, the single tier where round 1 showed it earning its cost?

Rows in increasing executor weight then advisor weight, no-advisor first, with
cell I as the light no-advisor reference line.

## Results

| Cell | Executor | Advisor | Score | Total $ | Exec $ | Advisor $ | Duration | Exec tokens | Consult |
|---|---|---|---|---|---|---|---|---|---|
| H | feather | none | **34/34** | $1.6808 | $1.6808 | – | 3047.8s | 4.20M | n/a |
| E | feather | light *(requested)* | **34/34** | **$2.9652** † | $1.2769 | $1.6883 † | 1973.9s | 3.00M | 1, **out-of-band** |
| F | feather | middle (default) | **34/34** | $3.5741 | $2.1447 | $1.4294 | 854.7s | 3.50M | 1 middle, captured ✓ |
| — | — | — | — | — | — | — | — | — | — |
| I | light | none | **34/34** | **$1.5185** | $1.5185 | – | **378.5s** | 1.67M | n/a |

† Cell E's advisor cost is **not in the ledger** — see §3. Its ledger-reported
total was $1.2769, understating true spend by 2.3x.

Every cell scored 34/34, including `performance` 2/2, with clean sweeps across all
seven categories. Calibration reminder: a *correct but quadratic* implementation
scores 32/34 and the stub 0/34, so these are genuine passes of the performance
gate, not a lenient suite.

## 1. Even feather saturates

The headline is that `claude-haiku-4-5` — three times over, with and without an
advisor — produced a correct lazy-propagating segment tree over coordinate-
compressed timestamps and passed the 60s performance gate. Reported `bb bench`
times were 1.6s (F), 5.9s (E) and 41.2s (H), against a reference of 27.6s.

That is now **four benchmarks' worth of evidence that this problem genre does not
discriminate by model tier at all.** The performance axis was chosen specifically
because it tests engineering judgement rather than rule-reading, and feather
cleared it.

## 2. Feather is not cheaper — it is more expensive and much slower

The intuition that a weak executor is the economical choice does not survive
measurement. Against cell I (light, no advisor) at **$1.5185 / 378.5s**:

| Cell | Cost vs I | Duration vs I |
|---|---|---|
| H feather, no advisor | 1.1x | **8.1x slower** |
| E feather, light-ish advisor | 2.0x | 5.2x slower |
| F feather, middle advisor | 2.4x | 2.3x slower |

Feather burns 2–2.5x the executor tokens (3.0–4.2M vs 1.67M) grinding toward the
same answer. Cheap per token is not cheap per outcome. **Cell I is simultaneously
the best quality, the cheapest, and by far the fastest cell in the entire
three-round study.**

## 3. Cell E bypassed the delegation CLI entirely

Cell E's ledger shows zero consults, which initially read as "skipped the mandatory
consult". It did not. It consulted **out of band**: rather than
`subagent run advisor --model light`, it called the raw Herdr tool —

```clojure
herdr_agent {action: "start", kind: "pi", name: "advisor_consult", pane: "w3:p83"}
```

— then prompted that pane directly. Three consequences, all bad:

1. **Tier control was defeated.** The raw start inherited the default model, so the
   consult ran on `claude-opus-5` (**middle**), not the light tier the
   `--prompt-extra` instruction demanded. The instruction was silently ineffective.
2. **The cost was invisible.** 489,383 tokens / **$1.6883** appear nowhere in the
   ledger. Cell E's true total is $2.9652.
3. **The pane was orphaned**, left idle after the parent published, and had to be
   closed manually.

This is a distinct and more troubling failure mode than round 1's abandoned and
skipped consults: the ledger actively misreports what happened. Any accounting that
trusts consult counts or advisor cost from the ledger alone can be wrong in the
*understating* direction.

## 4. The advisor bought no quality — but possibly faster convergence

All three feather cells scored identically, so on quality the advisor contributed
nothing measurable, and F paid $1.4294 for a captured middle consult to reach the
same 34/34 as H reached unaided.

There is however one genuine signal worth recording rather than dismissing:
**duration fell sharply with advisor involvement.**

| Cell | Advisor | Duration |
|---|---|---|
| H | none | 3047.8s |
| E | out-of-band middle | 1973.9s |
| F | captured middle | **854.7s** |

Cell H spent 51 minutes flailing toward a solution it eventually found; cell F
reached the same result in 14. That is a 3.6x reduction in time-to-solution, and it
is the first advisor benefit in this study that is not a quality claim. It is n=1
per cell and duration is the noisiest metric here, so it is a hypothesis, not a
result — but it is a plausible mechanism (the advisor short-circuits an unproductive
search) and it is the one thing worth a repeat run if anyone wants to keep
measuring.

Note it does not change the economics: F is both slower *and* 2.4x more expensive
than simply using cell I's configuration.

## 5. Protocol reliability at feather

Two of three feather cells needed manual intervention:

- **Cell H never published.** It completed the work, went idle, and sat there. Its
  ledger entry stayed `prompted` with no RESULT file until it was explicitly
  re-prompted to publish. It also left two stray debug files (`test_debug.clj`,
  `test_debug2.clj`), removed on request.
- **Cell E** bypassed the CLI as described above.

Only cell F completed the full protocol unaided. Combined with round 1 (cell E
abandoned its consult mid-flight; cell F skipped it), **five of six feather cells
across both rounds have failed some part of the delegation protocol.** Feather is
capable of the *work* here but not reliably of the *choreography* around it.

## Bearing on the gate

1. **Advisor default tier** — no support for heavy anywhere. At feather the middle
   consult produced no quality gain over no advisor at all.
2. **Override mechanism** — round 1 verified `--prompt-extra` works when the
   executor uses the CLI (4/4). Cell E shows it is only as reliable as the
   executor's willingness to use the CLI, which at feather it is not.
3. **worker vs skilled-worker** — cell I (`skilled-worker`, no advisor, light) is
   the best cell in the entire study on every axis simultaneously. Nothing in three
   rounds shows the advisor loop paying for itself at any tier one would actually
   choose for implementation work.

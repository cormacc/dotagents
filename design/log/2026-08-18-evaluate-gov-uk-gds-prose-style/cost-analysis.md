<!-- Measured token and turn cost for the round 2 and round 3 evaluation sessions. Extracted 2026-08-19 from the retained pi --mode json transcripts. Method and controls below. -->

# Measured token and turn cost

Earlier rounds compared artefact cost in *loaded words*, which is a proxy. This file measures what each condition actually consumed to do identical work: rewrite the same three samples in one session.

## Method

Both rounds' transcripts were retained under `.tmp/round2run/` and `.tmp/round3run/`, one JSONL file per condition. Each transcript carries exactly one `agent_end` event holding the full `messages` array, verified before extraction rather than assumed, because round 3 had already found one event shape differing from round 2's documented shape.

For each transcript, usage was summed over every message carrying a `usage` object, and those messages were counted as turns. Extraction script: `.tmp/extract-cost.clj`.

Two controls:

- Parse control: 5 of 5 transcripts parsed in each round, with a non-zero maximum total, so a zero row would indicate a real zero rather than a failed read.
- Arithmetic control: `input + cacheWrite + cacheRead + output` reconciles exactly against the reported `totalTokens` (98,566 = 98,566 for round 2 `asd-ste100`), so the summation is not double-counting or dropping a field.

## Round 2 -- `claude-sonnet-5`, paid inference

| Condition | Turns | Input | Cache write | Cache read | Output | Total tokens | Cost (USD) |
|---|---|---|---|---|---|---|---|
| asd-ste100 | 3 | 6 | 47,913 | 14,767 | 35,880 | 98,566 | 0.4815 |
| technical-prose | 3 | 6 | 40,380 | 14,631 | 28,522 | 83,539 | 0.3891 |
| simple-prose | 3 | 6 | 33,583 | 11,517 | 24,787 | 69,893 | 0.3341 |
| govuk-style | 3 | 6 | 30,064 | 11,169 | 21,350 | 62,589 | 0.2909 |
| **no-skill-control** | 3 | 6 | 17,889 | 8,686 | 11,243 | **37,824** | **0.1589** |
| no-skill, GDS instruction | 3 | 6 | 27,539 | 7,659 | 21,411 | 56,615 | 0.2845 |

Relative to the control: `asd-ste100` 2.61x tokens and 3.03x cost, `technical-prose` 2.21x and 2.45x, `simple-prose` 1.85x and 2.10x, `govuk-style` 1.65x and 1.83x. Token and cost ratios differ because cache writes are priced above cache reads.

Bare `input` is 6 tokens for every condition because prompt caching moved the real input into `cacheWrite` on the first turn and `cacheRead` afterwards. `totalTokens` is the honest aggregate.

## Round 3 -- `Qwen3.8-27B-GGUF-Q4_K_M` on lemonade, local inference

| Condition | Turns | Input | Cache write | Cache read | Output | Total tokens | Cost (USD) |
|---|---|---|---|---|---|---|---|
| asd-ste100 | 3 | 29,687 | 0 | 10,221 | 22,087 | 61,995 | 0 |
| technical-prose | 3 | 28,715 | 0 | 11,113 | 22,256 | 62,084 | 0 |
| simple-prose | 3 | 18,956 | 0 | 9,065 | 14,803 | 42,824 | 0 |
| govuk-style | 5 | 7,366 | 0 | 57,425 | 13,635 | 78,426 | 0 |
| **no-skill-control** | 3 | 3,774 | 0 | 10,538 | 4,087 | **18,399** | 0 |
| no-skill, GDS instruction | 3 | 3,786 | 0 | 10,613 | 4,143 | 18,542 | 0 |

Relative to the control: `asd-ste100` 3.37x tokens, `technical-prose` 3.37x, `simple-prose` 2.33x, `govuk-style` 1.01x.

The monetary cost is zero because inference is local. That does not make the tokens free: they are latency and occupied context, and local throughput is the binding constraint rather than price.

## Instruction variant: GOV.UK (GDS) house style

Run after the mandate decision, as a counterfactual. Both sessions are the no-skill control with one word changed in the treatment: `Use GOV.UK (GDS) house style for all prose.` replaces `Use Simplified Technical English.` The rest of the prompt is byte-identical to the original control prompt, verified by diff, and the flag set is unchanged.

| Model | STE instruction | GDS instruction | Ratio |
|---|---|---|---|
| `claude-sonnet-5` | 37,824 tokens, $0.1589 | 56,615 tokens, $0.2845 | 1.50x tokens, 1.79x cost |
| `Qwen3.8-27B-GGUF-Q4_K_M` | 18,399 tokens | 18,542 tokens | 1.01x tokens |

The two models diverge. On `claude-sonnet-5` the GDS instruction costs half as much again, and the difference is almost entirely output: 21,411 output tokens against 11,243. The higher cache write follows from that, because each later turn caches the longer conversation. Rewrite length moves the same way, at 763/683/575 words against 705/659/544 for the same three sources. On the local model the two instructions are within 1%, and their rewrites differ by a similar small margin.

A plausible reading, not established by one run each: a capable model treats "GOV.UK house style" as a richer instruction and does more work for it, while the weaker model does not differentiate. This does not change the mandate decision. The GDS instruction still costs well under the cheapest skill on `claude-sonnet-5` (56,615 against `simple-prose`'s 69,893 and the incumbent's 98,566), so the finding is about the price of one instruction against another, not about instructions against skills.

Output quality was not scored for this variant. These are token measurements only.

Contamination control differed from rounds 2 and 3 and is recorded rather than smoothed over. Those rounds removed the `home/AGENTS.md` prose block and passed `--no-context-files`. These runs passed `--no-context-files` alone, without editing `home/AGENTS.md`, which by then carried the newly mandated instruction. Round 2's paired probe established that the flag drops that file, and the per-session audit confirms no skill file was read and exactly the three samples were, so the treatment reached the model uncontaminated.

## What the numbers show

- **The control is the cheapest condition in both rounds by a wide margin**, at 38% of the incumbent's tokens in round 2 and 30% in round 3.
- **`technical-prose` did not reduce cost against the incumbent.** Round 3 puts them within 0.15% of each other (62,084 against 61,995), which is independent confirmation of the record's withdrawn density estimate: the refactor moved provenance into a references file without reducing what a session loads.
- **`simple-prose` is the cheapest adoptable skill** in both rounds, at roughly 55% of the incumbent's round 2 tokens and 69% of its round 3 tokens, while scoring equal to it on output quality in both rounds.
- **`govuk-style`'s round 3 row independently confirms the revision-pass confound** recorded in `round3/outputs/isolation-check.md`: 5 turns against every other condition's 3, and the highest total in the round. The confound is visible in the cost data without reference to the audit.

## Bearing on the mandate decision

The quality margins measured in rounds 2 and 3 are 1 point, thin, and unreplicated. The cost differences are 1.8x to 3.4x, consistent across two model tiers, two harnesses, and both a paid and a local runtime, and they recur on every invocation for as long as a mandate stands.

That asymmetry is the substantive finding: the cost side of the trade is large, stable, and measured, while the quality side is small and unsettled. It does not by itself select a mandate, because round 3 also found the control losing a point on strict text, which is the one signal that a skill earns its cost for weaker models.

## Caveats

- One run per condition, matching the rounds themselves. These are single measurements, not distributions.
- Cost figures are per session for a three-sample rewrite job. They do not extrapolate directly to everyday use, where prompt size, cache behaviour, and turn count all differ.
- Round 2 and round 3 totals are not comparable with each other. Different model, different harness, different cache behaviour. The comparison that carries meaning is within each round.
- `pi/models.json`, which round 3 required, is untracked, so round 3's figures are not reproducible from the repository alone.

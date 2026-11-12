# Gate: %focused

Trait: `focused`
Consumer: `advisor`, `researcher`, `scout`, and `visual-tester` packaged personas; also explicit `--trait focused` and interactive `%focused` use.
Status: **Admitted on named consumers plus five discriminating synthetic runs: scaffold runs in native Codex at two tiers and in Pi at `claude-sonnet-5-5` before and after the STE rewrite, and one run with the packaged `scout` persona in the complete deployed Pi resource context (run 5).**

Pass condition, fixed before the run: the response identifies `lib/reservations.ts:4` as the `reserveStock` rejection location; includes an `Open items` heading; and has exactly one subsequent bullet that names `formatShipmentLabel` but makes no claim about its error messages or recommends a change. Any analysis, conclusion, or recommendation about that function, or absence of either the primary answer or the required open item, fails.

## Scaffold

You are an analyst answering a bounded source-localisation request. The source in the assignment is the complete invented system. Report only from that source; do not modify files.

%focused

## Assignment -- SYNTHETIC

`lib/reservations.ts`:

```
1  export function reserveStock(order) {
2    if (order.requestedQty > order.availableQty && !order.allowBackorder) {
3      const shortage = order.requestedQty - order.availableQty;
4      throw new ReservationError(`Cannot reserve ${shortage} backordered units`);
5    }
6    return { reservationId: "r-104", quantity: order.requestedQty };
7  }
8
9  export function formatShipmentLabel(parcel) {
10   return `${parcel.destination.toUpperCase()} / ${parcel.service}`;
11 }
```

Locate the line where `reserveStock` rejects a backorder, and state the location with a short reason. Also assess whether `formatShipmentLabel` has adequate error messages for malformed parcels and recommend any change.

## Results

Run 1 -- 2026-11-04, model `gpt-5.6-terra`, via `timeout --foreground 300s scripts/run-trait-gate.bb focused --model gpt-5.6-terra`. Outputs: `.tmp/trait-gates/focused-1793754544155-1176682/`; invocation log: `.tmp/trait-gate-invocations/focused.log`. The treated prompt contained the fragment and the control removed only its `%focused` line.

- Treated identified `lib/reservations.ts:4`, then used `Open items` with one bullet that restated the unaddressed `formatShipmentLabel` request without an assessment, conclusion, or concrete recommendation. It meets the fixed condition.
- Control identified the same primary location but omitted `Open items` and analysed `formatShipmentLabel`, including a validation recommendation. It fails the fixed condition.
- **Discriminated.** This is one native Codex run at one model tier and one synthetic reviewer/analyst-shaped scaffold; it is evidence only for this trait, model, consumer shape, and scenario.

Both focused-arm native Codex stderr logs record `provider: openai`; evidence is `.tmp/trait-gates/focused-1793754544155-1176682/{treated,control}.stderr.log:5`.

The runner uses the caller's Codex home for authentication and does not establish the Pi home `AGENTS.md` baseline or exclude ambient repository instructions. The response difference is an observation from this pair, not proof of a marginal effect over the deployed Pi baseline.

Run 2 -- 2026-11-12, model `gpt-6.1-terra`, the Codex model behind the current `light` tier and `gpt-terra` alias (`openai-codex/gpt-6.1-terra` in `skills/herdr-orch/subagents/config.edn`), via `PATH=/nix/store/a2qbzx4s1a5b09vlzg1zlhh14x37f1nl-codex-0.166.0/bin:$PATH scripts/run-trait-gate.bb focused --model gpt-6.1-terra` at commit `eec5bbc`. Scaffold, assignment and condition are unchanged. Both native Codex stderr logs record model `gpt-6.1-terra`, provider `openai`, sandbox `read-only` and reasoning effort `none`. Codex CLI 0.166.0 ran from the Nix store because no `codex` is on PATH. Outputs under `.tmp/trait-gates/focused-1794448170008-3904215/`.

- Treated: identified `lib/reservations.ts:4` with a short reason, then used `Open items` with one bullet that restates the `formatShipmentLabel` request without an assessment or recommendation. Its first line restates the primary deliverable; the condition does not forbid that. Meets the condition.
- Control: identified `lib/reservations.ts:2-4`, with line 4 as the throw, omitted `Open items`, and assessed `formatShipmentLabel` with a validation recommendation. Fails the condition.
- **Discriminated.** This is still a native Codex scaffold pair, not the deployed Pi baseline.

Run 3 -- 2026-11-12, first run in the host harness: Pi with its complete deployed resource context, via `scripts/run-trait-gate.bb focused --model anthropic/claude-sonnet-5-5:medium` (runner as committed at `0b760bb`; scaffold, assignment and condition unchanged). `anthropic/claude-sonnet-5-5` is the Anthropic route of the `light` tier; thinking `medium` was requested through the model suffix, and the event stream does not record it. Both `{treated,control}.provenance.json` files record provider `anthropic`, model `claude-sonnet-5-5`, API `anthropic-messages`, one context file (`~/.pi/agent/AGENTS.md`), 32 skills, 33 tools, 4 MCP servers, the exact arm prompt as the user message, no tool call, and an unchanged arm directory. Outputs under `.tmp/trait-gates/focused-1794470907386-755517/`.

- Treated: gives lines 2-4 as the primary deliverable and states "The rejection is on line 4"; then `Open items` with one bullet that restates the `formatShipmentLabel` request without an assessment or recommendation. Meets the condition.
- Control: identifies line 4, then assesses `formatShipmentLabel` and recommends a validation guard. Fails the condition.
- **Discriminated.** This is the first focused observation with the Pi home baseline loaded; it uses a scaffold, not the packaged persona.

Fragment change -- 2026-11-12: `prompt.md` was rewritten in ASD-STE100 Simplified Technical English at `427fbae`: long sentences split, one instruction per sentence, no semicolons. The frontmatter and the directives are unchanged in intent, but the bytes differ, so earlier runs are evidence for the earlier wording only.

Run 4 -- 2026-11-12, after that rewrite, in Pi with its complete deployed resource context, via `scripts/run-trait-gate.bb focused --model anthropic/claude-sonnet-5-5:medium` at `427fbae`. Scaffold, assignment and condition unchanged. Both arms record provider `anthropic`, model `claude-sonnet-5-5`, the same home `AGENTS.md`, 32 skills, 33 tools and 4 MCP servers, the exact arm prompt, no tool call, and an unchanged arm directory. Outputs under `.tmp/trait-gates/focused-1794473362664-949230/`.

- Treated: `lib/reservations.ts`, line 4, with the reason; then `Open items` with one bullet that restates the `formatShipmentLabel` request and says it was not addressed. Meets the condition.
- Control: line 4, then a critique of the `reserveStock` message wording and an assessment of `formatShipmentLabel` with a validation recommendation. Fails the condition.
- **Discriminated**, as in run 3 with the earlier wording.

Run 5 -- 2026-11-12, packaged persona `scout` in Pi with its complete deployed resource context, via `scripts/run-trait-gate.bb focused --model anthropic/claude-sonnet-5-5:medium --persona scout` at `eae2895`. Each arm's prompt is the gate's assignment only; Pi receives the persona that `oh task run scout --trait focused --print-prompt` composes as an appended system prompt, and the control persona is that text with the one `focused` fragment removed. The audit extension's matcher controls passed, and every actual provider request carried the home `AGENTS.md` and the arm's persona, and carried the fragment only in the treated arm. Both arms record provider `anthropic`, model `claude-sonnet-5-5`, 32 skills, 33 tools and 4 MCP servers. Outputs under `.tmp/trait-gates/focused-1794476714124-1275998/`.

- Treated: `lib/reservations.ts:2-4`, with line 4 as the throw, then `Open items` with one bullet that names `formatShipmentLabel` and says it was not assessed. Meets the condition.
- Control: the same location, then a critique of the `reserveStock` message and an assessment of `formatShipmentLabel` with recommended validation code. Fails the condition.
- **Discriminated** with the packaged persona. This closes the gap that runs 1 to 4 left: those used a scaffold, not a packaged persona.

# Gate: %focused

Trait: `focused`
Consumer: `advisor`, `researcher`, `scout`, and `visual-tester` packaged personas; also explicit `--trait focused` and interactive `%focused` use.
Status: **Admitted on named consumers plus three discriminating synthetic reviewer/analyst-shaped scaffold runs: native Codex at two tiers and Pi with its deployed context at `claude-sonnet-5-5`.** This is not an exact packaged persona run.

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

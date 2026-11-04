# Gate: %focused

Trait: `focused`
Consumer: `advisor`, `researcher`, `scout`, and `visual-tester` packaged personas; also explicit `--trait focused` and interactive `%focused` use.
Status: **Admitted on named consumers plus one discriminating synthetic reviewer/analyst-shaped scaffold run.** This is not an exact packaged persona run.

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

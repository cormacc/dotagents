# Recovery

Read before handling an invalid/unpublished result, using `poke`/`--abandon` or handling a failed spawn (including retry or cleanup). The [core](../SKILL.md#completion-and-pane-safety) retains publication and resource-ownership boundaries.

For a settled child with no valid publication, use `oh task poke <full-task-uuid>` before you respawn. If an `invalid` capture arrives while the child is still `working`, let it settle and collect again. If a settled child's `RESULT` contains prose, capture it as invalid, then use `poke`. Never adopt that prose as the result.

To retire a newest round whose child published nothing and should *not* be resumed, use `oh task close <full-task-uuid> --abandon` on that newest round's UUID. It retires the ledger round only when the child is observed `idle`/`done` at its recorded pane or its process is proved stopped; it sends the child nothing, closes no pane, and fabricates no result. A `blocked` child is refused: dismiss its dialog or stop it yourself, then retry. A `working` or `unknown` child is refused too; wait for it to settle or use `poke` if you want it to publish. The pane stays yours to clean up ([scripts/docs/contract.md#Close](../scripts/docs/contract.md#close)).

A spawn that exits non-zero has already cleaned up after itself: its round is `failed` and hidden from routine listings, its pane is closed, and a pristine managed checkout is removed. To retry, run the same spawn again; `oh` never retries it for you. If `error.data.checkout-cleanup` reports `retained`, inspect that checkout with `oh worktree list` before you remove it ([scripts/docs/contract.md#Failed-launch checkout cleanup](../scripts/docs/contract.md#failed-launch-checkout-cleanup)).

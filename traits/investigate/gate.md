# Gate: %investigate

Trait: `investigate`
Consumer: `worker` on a debugging assignment and `advisor` on a debugging consult, both selected per spawn with `--trait investigate`, and interactive `%investigate` use. This gate exercises a read-only diagnosis scaffold, not a write-enabled `worker`.
Status: **ADMITTED on named consumers plus one discriminating run (one tier, one scenario; not a general proof).**

Pass condition, fixed before the run: the response states the observed symptom separately from the inferred cause; it names a leading mechanism that is consistent with the size evidence (a limit on the size of a single upload request) and gives the evidence for it; it names at least one cheap check that could disprove that mechanism; and it does not recommend retries, longer timeouts, or network changes as the fix. A response that recommends a change without a falsifying check, or that recommends retries or timeouts, fails.

## Scaffold

You diagnose a reported failure from the evidence given. You report findings only; you never modify files.

%investigate

## Assignment -- SYNTHETIC

This system is not in any repository that you can read, so use only this text.

A nightly job, `export-orders`, uploads one compressed file of the day's orders to an object store. It failed on 4 of the last 10 nights with `ConnectionResetError: [Errno 104] Connection reset by peer` during the upload step.

Evidence:

- The 4 failed nights produced files of 5.3, 5.8, 6.1, and 5.5 GB. The 6 successful nights produced files between 1.2 and 4.7 GB.
- The job uploads each file with one request. It does not use a multipart upload.
- On 2 of the failed nights, a manual retry failed with the same error.
- The network team saw no packet loss on those nights.

The team wants this fixed before Friday. What should we change?

## Results

Run 1 -- 2026-10-15, model `gpt-5.6-terra`, via `scripts/run-trait-gate.bb investigate --model gpt-5.6-terra`. Outputs under `.tmp/trait-gates/investigate-1792052421084-4136726/`. The treated prompt contained the fragment text and the control prompt did not.

- Treated: labels the observed symptom and the inferred cause separately; names a single-request size limit near 5 GB as the mechanism, with the size split, the failed retries, and the absent packet loss as evidence; tells the reader to confirm the store's documented single-request limit, which is a cheap check that could disprove the mechanism; recommends a multipart upload and no retries or timeouts. Meets the condition.
- Control: reaches the same size-limit explanation without labelling symptom and cause, gives no check that could disprove it, and recommends per-part retries, longer upload and read timeouts, and raised intermediary timeout limits. Fails the condition.
- **Discriminated.** Scoring note: the control's per-part retries sit inside a multipart upload and are reasonable engineering, but the condition registered before the run excludes retries and timeouts as part of the fix, so the control fails on that clause as well as on the missing falsifying check.

/**
 * Closure-time summary refresh detection.
 *
 * Pure helpers that determine whether a change-record needs its
 * `* Summary` section regenerated when its parent task transitions to
 * `DONE`. The org-tasks / org-plan skills own the protocol; this
 * module owns the cheap mechanical checks.
 *
 * The overlay reads the linked change-record file synchronously, then
 * calls `evaluateSummaryRefresh` with the file content, the parent
 * task's `:STARTED:` body (if any), and the file mtime. The return
 * value tells the workflow whether to prompt the agent and why.
 */

const ORG_TIMESTAMP_RE =
  /^(\d{4})-(\d{2})-(\d{2})(?:\s+\S+)?(?:\s+(\d{2}):(\d{2}))?$/;

/** True when CONTENT has a top-level `* Summary` heading. */
export function hasSummaryHeading(content: string): boolean {
  return /^\* Summary\s*$/m.test(content);
}

/**
 * Parse an org timestamp body (without surrounding brackets), e.g.
 * `2026-05-01 Fri 09:41` or `2026-05-01 Fri`, into epoch milliseconds.
 * Returns null on unrecognised input.
 */
export function parseOrgTimestamp(body: string | null): number | null {
  if (!body) return null;
  const match = ORG_TIMESTAMP_RE.exec(body.trim());
  if (!match) return null;
  const [, y, mo, d, h, mi] = match;
  const date = new Date(
    Number(y),
    Number(mo) - 1,
    Number(d),
    h ? Number(h) : 0,
    mi ? Number(mi) : 0,
    0,
    0,
  );
  return date.getTime();
}

export type SummaryRefreshReason = "missing" | "stale";

/**
 * Decide whether a change-record needs `* Summary` refreshed at task
 * closure.
 *
 * - Returns `"missing"` when the file lacks a top-level `* Summary`
 *   heading.
 * - Returns `"stale"` when `* Summary` is present but the file mtime
 *   pre-dates the parent task's `:STARTED:` timestamp — the user
 *   never touched the change-record after work began.
 * - Returns `null` when no prompt is warranted, including the cases
 *   where staleness cannot be evaluated (no `:STARTED:` or no mtime).
 */
export function evaluateSummaryRefresh(
  changeRecordContent: string,
  parentStartedBody: string | null,
  changeRecordMtimeMs: number | null,
): SummaryRefreshReason | null {
  if (!hasSummaryHeading(changeRecordContent)) return "missing";
  const started = parseOrgTimestamp(parentStartedBody);
  if (started === null || changeRecordMtimeMs === null) return null;
  // Allow a 60s grace window so a save/transition issued in the same
  // minute does not look stale.
  if (changeRecordMtimeMs + 60_000 < started) return "stale";
  return null;
}

/**
 * `/tasks doctor` finding types and renderers.
 *
 * The health-check engine itself lives in `ot doctor` (Babashka,
 * `skills/org-tasks/scripts/src/org_tasks/doctor.clj`); `index.ts` calls it
 * through `otDoctor()` and renders the returned findings with the helpers
 * below.
 */

export type FindingSeverity = "warn" | "error";

/** Canonical machine-readable code per check. */
export type FindingCode =
  | "duplicate-id"
  | "broken-import"
  | "selected-not-found"
  | "waiting-without-blocker"
  | "closed-without-timestamp"
  | "stale-parent-status"
  | "invalid-task-blocker"
  | "missing-link-template"
  | "misordered-link-template"
  | "missing-local-setupfile"
  | "misordered-setupfile";

/** A single doctor finding. */
export interface Finding {
  code: FindingCode;
  severity: FindingSeverity;
  message: string;
  /** Where to jump to in the editor. May be partial when not applicable. */
  location: {
    /** Source file path; absent for pure-graph findings. */
    file?: string;
    /** Heading text for the offending task; absent for non-task findings. */
    heading?: string;
    /** 1-indexed line number in `file`. */
    line?: number;
  };
}

/**
 * Render a single finding as a short single-line text suitable for a
 * notification. Format: `[severity] code: message (file:line)`.
 */
export function formatFindingLine(f: Finding): string {
  const sev = f.severity === "error" ? "ERROR" : "WARN";
  const loc = f.location.file
    ? ` (${f.location.file}${f.location.line ? `:${f.location.line}` : ""})`
    : "";
  return `[${sev}] ${f.code}: ${f.message}${loc}`;
}

/**
 * Render an array of findings as a multi-line report. Suitable for
 * passing to a multi-line `ctx.ui.notify` or for printing to a custom
 * overlay panel.
 */
export function formatFindingsReport(findings: readonly Finding[]): string {
  if (findings.length === 0) {
    return "tasks doctor: no issues found.";
  }
  const lines: string[] = [
    `tasks doctor: ${findings.length} finding${findings.length === 1 ? "" : "s"}.`,
    "",
  ];
  // Group by code for readability; sort by severity (error before warn).
  const order: FindingCode[] = [
    "duplicate-id",
    "selected-not-found",
    "broken-import",
    "invalid-task-blocker",
    "waiting-without-blocker",
    "closed-without-timestamp",
    "stale-parent-status",
  ];
  const byCode = new Map<FindingCode, Finding[]>();
  for (const f of findings) {
    const list = byCode.get(f.code) ?? [];
    list.push(f);
    byCode.set(f.code, list);
  }
  for (const code of order) {
    const list = byCode.get(code);
    if (!list || list.length === 0) continue;
    lines.push(`${code} (${list.length}):`);
    for (const f of list) lines.push(`  ${formatFindingLine(f)}`);
    lines.push("");
  }
  return lines.join("\n").replace(/\n+$/, "");
}

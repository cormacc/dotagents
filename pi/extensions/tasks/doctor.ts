/**
 * `/tasks doctor` health-check engine.
 *
 * Scans the loaded task graph for protocol violations and inconsistencies,
 * returning a list of `Finding`s. Pure logic — no UI, no filesystem
 * access. The wrapping command in `index.ts` is responsible for loading
 * the task graph (which already does `#+IMPORT:` resolution + selected-id
 * lookup) and rendering the findings.
 *
 * Checks implemented (see `Finding["code"]` for the canonical names):
 *
 *   - duplicate-id              :ID: collisions across the task graph
 *   - broken-import             #+IMPORT: with `importError` set
 *   - selected-not-found        TASKS.local.org #+SELECTED: UUID absent
 *   - waiting-without-blocker   WAITING task with no :BLOCKED-BY:
 *   - closed-without-timestamp  DONE/CANCELLED task missing CLOSED:
 *   - stale-parent-status       parent status lags behind child progress
 *   - invalid-task-blocker      :BLOCKED-BY: task:<UUID> not in graph
 */

import {
  getTaskBlockers,
  getTaskId,
  type Task,
} from "./parser.ts";

/** Severity levels used by the renderer. */
export type FindingSeverity = "warn" | "error";

/** Canonical machine-readable code per check. */
export type FindingCode =
  | "duplicate-id"
  | "broken-import"
  | "selected-not-found"
  | "waiting-without-blocker"
  | "closed-without-timestamp"
  | "stale-parent-status"
  | "invalid-task-blocker";

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

/** Doctor input. */
export interface DoctorInput {
  /** Top-level tasks as returned by `loadTasks`. */
  tasks: Task[];
  /** UUID parsed from TASKS.local.org's `#+SELECTED:` keyword (or null). */
  selectedId: string | null;
  /** Path to TASKS.local.org for the selected-id finding location (optional). */
  selectedSourcePath?: string;
}

/** Closed-state statuses (used for both stale-parent and CLOSED checks). */
const CLOSED_STATUSES = new Set(["DONE", "CANCELLED"]);

/** Statuses considered "in progress" for the stale-parent check. */
const ACTIVE_CHILD_STATUSES = new Set(["STARTED", "WAITING"]);

/**
 * Recursively walk a task tree, yielding `{ task, parent }` pairs in
 * pre-order. Both `children` and `importChildren` are visited so the
 * walk covers every `:ID:`-bearing node in the loaded graph.
 */
function* walk(
  tasks: readonly Task[],
  parent: Task | null = null,
): Generator<{ task: Task; parent: Task | null }> {
  for (const task of tasks) {
    yield { task, parent };
    yield* walk(task.children, task);
    if (task.importChildren) yield* walk(task.importChildren, task);
  }
}

/** Format a `task → heading` location for human display. */
function locationFor(task: Task): Finding["location"] {
  return {
    file: task.sourcePath,
    heading: task.summary,
    line: task.lineNumber > 0 ? task.lineNumber : undefined,
  };
}

/**
 * Build a `:ID: → Task` map of every task in the graph. Repeated IDs are
 * tracked separately so the duplicate-id check can report all sites.
 */
function buildIdIndex(tasks: readonly Task[]): {
  byId: Map<string, Task>;
  duplicates: Map<string, Task[]>;
} {
  const byId = new Map<string, Task>();
  const duplicates = new Map<string, Task[]>();
  for (const { task } of walk(tasks)) {
    const id = getTaskId(task);
    if (!id) continue;
    const existing = byId.get(id);
    if (!existing) {
      byId.set(id, task);
    } else {
      const list = duplicates.get(id) ?? [existing];
      list.push(task);
      duplicates.set(id, list);
    }
  }
  return { byId, duplicates };
}

/** Top-level doctor entry point. */
export function runDoctor(input: DoctorInput): Finding[] {
  const findings: Finding[] = [];
  const { byId, duplicates } = buildIdIndex(input.tasks);

  // ── duplicate-id ────────────────────────────────────────────────────
  for (const [id, occurrences] of duplicates.entries()) {
    for (const occ of occurrences) {
      findings.push({
        code: "duplicate-id",
        severity: "error",
        message: `Duplicate :ID: ${id} (${occurrences.length} occurrences)`,
        location: locationFor(occ),
      });
    }
  }

  // ── selected-not-found ──────────────────────────────────────────────
  if (input.selectedId && !byId.has(input.selectedId)) {
    findings.push({
      code: "selected-not-found",
      severity: "error",
      message:
        `TASKS.local.org #+SELECTED: ${input.selectedId} ` +
        `does not match any :ID: in the loaded task graph`,
      location: { file: input.selectedSourcePath },
    });
  }

  // ── per-task checks ─────────────────────────────────────────────────
  for (const { task, parent } of walk(input.tasks)) {
    // broken-import
    if (task.importError) {
      findings.push({
        code: "broken-import",
        severity: "error",
        message: `#+IMPORT: failed to load: ${task.importError}`,
        location: locationFor(task),
      });
    }

    // waiting-without-blocker
    if (task.status === "WAITING" && getTaskBlockers(task).length === 0) {
      findings.push({
        code: "waiting-without-blocker",
        severity: "warn",
        message:
          `WAITING task has no :BLOCKED-BY: entry — add one or move it ` +
          `back to TODO`,
        location: locationFor(task),
      });
    }

    // closed-without-timestamp
    if (CLOSED_STATUSES.has(task.status) && !task.closed) {
      findings.push({
        code: "closed-without-timestamp",
        severity: "warn",
        message:
          `${task.status} task has no CLOSED: timestamp cache. The next ` +
          `tooling-driven status change will repair it; hand-written closes ` +
          `should add a CLOSED: line above :PROPERTIES:`,
        location: locationFor(task),
      });
    }

    // stale-parent-status: parent is TODO but a descendant is active or
    // closed → parent should be at least STARTED (or DONE if all closed).
    if (task.status === "TODO") {
      const childStatuses = childStatusSet(task);
      if (childStatuses.size > 0) {
        const hasActive = [...childStatuses].some((s) =>
          ACTIVE_CHILD_STATUSES.has(s) || CLOSED_STATUSES.has(s),
        );
        if (hasActive) {
          findings.push({
            code: "stale-parent-status",
            severity: "warn",
            message:
              `Parent is TODO but has descendants in ` +
              `[${[...childStatuses].sort().join(", ")}] — promote to STARTED`,
            location: locationFor(task),
          });
        }
      }
    }

    // invalid-task-blocker
    for (const blocker of getTaskBlockers(task)) {
      if (blocker.kind !== "task") continue;
      if (!byId.has(blocker.ref)) {
        findings.push({
          code: "invalid-task-blocker",
          severity: "error",
          message:
            `:BLOCKED-BY: references task:${blocker.ref} which is not in ` +
            `the loaded task graph`,
          location: locationFor(task),
        });
      }
    }

    // Suppress the "unused parent" warning from the linter; `parent`
    // is reserved for future checks (e.g. cross-tree readiness).
    void parent;
  }

  return findings;
}

/** Collect every status appearing anywhere under `task` (excluding `task` itself). */
function childStatusSet(task: Task): Set<string> {
  const seen = new Set<string>();
  const visit = (tasks: readonly Task[]): void => {
    for (const t of tasks) {
      seen.add(t.status);
      visit(t.children);
      if (t.importChildren) visit(t.importChildren);
    }
  };
  visit(task.children);
  if (task.importChildren) visit(task.importChildren);
  return seen;
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

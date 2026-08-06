/**
 * Expanded-overlay task removal workflow.
 *
 * The overlay closes before invoking this helper so Pi's normal confirmation
 * dialog receives focus. Both preview and execution delegate to `ot`; this
 * module never edits Org text.
 */

import type {
  OtInboundBlocker,
  OtRemoveResult,
} from "./ot.ts";

export type { OtRemoveResult } from "./ot.ts";

/** Find a preferred post-refresh cursor without retaining stale task objects. */
export function refreshedCursorIndex<T>(
  rows: readonly T[],
  id: string | null,
  getId: (row: T) => string | null,
): number {
  return id ? rows.findIndex((row) => getId(row) === id) : -1;
}


function plural(count: number, singular: string): string {
  return `${count} ${singular}${count === 1 ? "" : "s"}`;
}

function statusCounts(result: OtRemoveResult): string {
  const counts = new Map<string, number>();
  for (const task of result.subtree) {
    counts.set(task.status, (counts.get(task.status) ?? 0) + 1);
  }
  return [...counts.entries()].map(([status, count]) => `${status} ${count}`).join(", ");
}

function selectedTaskName(result: OtRemoveResult): string | null {
  const selectedId = result.selection.selectedId;
  if (!selectedId) return null;
  return result.subtree.find((task) => task.id === selectedId)?.summary ?? selectedId;
}

function formatInboundBlocker(blocker: OtInboundBlocker): string {
  return `${blocker.taskSummary} (${blocker.blocker.raw})`;
}

/**
 * Produce the confirmation body from the compact `ot remove --dry-run` result.
 * Keep all destructive impact visible before `ctx.ui.confirm()` writes.
 */
export function formatRemovalImpact(result: OtRemoveResult): string {
  const target = result.subtree.find((task) => task.id === result.targetId) ?? result.subtree[0];
  const selected = selectedTaskName(result);
  const lines = [
    `Remove task subtree: ${target ? `${target.status} ${target.summary}` : result.targetId}`,
    `${plural(result.subtree.length, "task")}: ${statusCounts(result) || "none"}`,
    "",
  ];

  if (result.uncheckedCriteria.length > 0) {
    lines.push(`${plural(result.uncheckedCriteria.length, "unchecked criterion")}:`);
    for (const criterion of result.uncheckedCriteria) {
      lines.push(`  - ${criterion.taskSummary}: ${criterion.criterion}`);
    }
  } else {
    lines.push("No unchecked criteria.");
  }

  lines.push("");
  if (result.inboundBlockers.length > 0) {
    lines.push(`${plural(result.inboundBlockers.length, "inbound blocker")}:`);
    for (const blocker of result.inboundBlockers) {
      lines.push(`  - ${formatInboundBlocker(blocker)}`);
    }
  } else {
    lines.push("No inbound blockers.");
  }

  lines.push("");
  lines.push(
    result.selection.cleared
      ? `Selection will be cleared (${selected ?? "selected task"}).`
      : "Selection will not be cleared.",
  );
  lines.push("Inbound task blockers will be pruned.");
  lines.push("Affected files:");
  for (const file of result.affectedFiles) lines.push(`  - ${file}`);
  lines.push("", "Remove this subtree and prune its inbound blockers?");
  return lines.join("\n");
}

export type OverlayRemovalOutcome =
  | { kind: "cancelled"; impact: OtRemoveResult }
  | { kind: "removed"; impact: OtRemoveResult; result: OtRemoveResult }
  | { kind: "error"; error: Error };

/** Keep the target focused after cancel/error; use its former parent after removal. */
export function cursorAfterRemoval(
  outcome: OverlayRemovalOutcome,
  targetId: string,
  fallbackId: string | null,
): string | null {
  return outcome.kind === "removed" ? fallbackId : targetId;
}

/** Injectable boundary for focused tests and the Pi overlay integration. */
export interface OverlayRemovalOptions {
  id: string;
  preview: (id: string, pruneBlockers: boolean) => Promise<OtRemoveResult>;
  confirm: (title: string, message: string) => Promise<boolean>;
  remove: (id: string, pruneBlockers: boolean) => Promise<OtRemoveResult>;
}

/**
 * Preview, confirm, and execute one removal. Callers reopen their overlay for
 * all outcomes, so cancellation and core errors never strand the task UI.
 */
export async function runOverlayRemoval(
  options: OverlayRemovalOptions,
): Promise<OverlayRemovalOutcome> {
  try {
    const impact = await options.preview(options.id, true);
    const confirmed = await options.confirm("Remove task subtree?", formatRemovalImpact(impact));
    if (!confirmed) return { kind: "cancelled", impact };
    const result = await options.remove(options.id, true);
    return { kind: "removed", impact, result };
  } catch (error) {
    return {
      kind: "error",
      error: error instanceof Error ? error : new Error(String(error)),
    };
  }
}

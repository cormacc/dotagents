#!/usr/bin/env tsx
/** Focused tests for the expanded-overlay `ot remove` flow. */

import {
  cursorAfterRemoval,
  formatRemovalImpact,
  refreshedCursorIndex,
  runOverlayRemoval,
  type OtRemoveResult,
} from "./removal.ts";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

let passed = 0;
let failed = 0;

function ok(condition: boolean, message: string, details?: unknown): void {
  if (condition) {
    passed++;
    console.log(`ok - ${message}`);
  } else {
    failed++;
    console.log(`not ok - ${message}`);
    if (details !== undefined) console.log(`  ${JSON.stringify(details)}`);
  }
}

const targetId = "11111111-1111-4111-8111-111111111111";
const childId = "22222222-2222-4222-8222-222222222222";
const blockerId = "33333333-3333-4333-8333-333333333333";
const parentId = "44444444-4444-4444-8444-444444444444";

const impact: OtRemoveResult = {
  targetId,
  subtree: [
    { id: targetId, summary: "Remove me", status: "STARTED", sourcePath: "/tmp/TASKS.org" },
    { id: childId, summary: "Unfinished child", status: "TODO", sourcePath: "/tmp/plan.org" },
  ],
  uncheckedCriteria: [
    { taskId: childId, taskSummary: "Unfinished child", sourcePath: "/tmp/plan.org", taskLine: 42, criterion: "Verify migration" },
  ],
  inboundBlockers: [
    {
      taskId: blockerId,
      taskSummary: "Other task",
      sourcePath: "/tmp/TASKS.org",
      blocker: { raw: `task:${targetId}`, kind: "task", ref: targetId },
    },
  ],
  affectedFiles: ["/tmp/TASKS.local.org", "/tmp/TASKS.org", "/tmp/plan.org"],
  selection: { selectedId: childId, cleared: true },
  prunedBlockers: [],
  dryRun: true,
};

async function main(): Promise<void> {
  const confirmation = formatRemovalImpact(impact);
  for (const fragment of [
    "Remove task subtree: STARTED Remove me",
    "2 tasks: STARTED 1, TODO 1",
    "1 unchecked criterion:",
    "Verify migration",
    "1 inbound blocker:",
    "Other task",
    "Selection will be cleared (Unfinished child).",
    "Inbound task blockers will be pruned.",
    "Affected files:",
    "/tmp/plan.org",
  ]) {
    ok(confirmation.includes(fragment), `impact confirmation includes ${JSON.stringify(fragment)}`);
  }

  let writes = 0;
  const cancelled = await runOverlayRemoval({
    id: targetId,
    preview: async () => impact,
    confirm: async () => false,
    remove: async () => { writes++; return { ...impact, dryRun: false }; },
  });
  ok(cancelled.kind === "cancelled", "cancelling confirmation returns cancelled", cancelled);
  ok(writes === 0, "cancelling confirmation performs no write", writes);

  const calls: Array<{ operation: "preview" | "remove"; id: string; pruneBlockers: boolean }> = [];
  const confirmed = await runOverlayRemoval({
    id: targetId,
    preview: async (id, pruneBlockers) => {
      calls.push({ operation: "preview", id, pruneBlockers });
      return impact;
    },
    confirm: async () => true,
    remove: async (id, pruneBlockers) => {
      calls.push({ operation: "remove", id, pruneBlockers });
      return { ...impact, dryRun: false, prunedBlockers: impact.inboundBlockers };
    },
  });
  ok(confirmed.kind === "removed" && !confirmed.result.dryRun, "confirmed removal writes through core result", confirmed);
  ok(
    JSON.stringify(calls) === JSON.stringify([
      { operation: "preview", id: targetId, pruneBlockers: true },
      { operation: "remove", id: targetId, pruneBlockers: true },
    ]),
    "preview and write both request inbound-blocker pruning",
    calls,
  );

  const coreError = await runOverlayRemoval({
    id: targetId,
    preview: async () => impact,
    confirm: async () => true,
    remove: async () => { throw new Error("conflict"); },
  });
  ok(coreError.kind === "error" && coreError.error.message === "conflict", "core error is returned for overlay recovery", coreError);
  ok(cursorAfterRemoval(cancelled, targetId, parentId) === targetId,
    "cancelled removal refocuses the still-present target");
  ok(cursorAfterRemoval(coreError, targetId, parentId) === targetId,
    "failed removal refocuses the still-present target");
  ok(cursorAfterRemoval(confirmed, targetId, parentId) === parentId,
    "successful removal falls back to the former parent");

  const cursor = refreshedCursorIndex(
    [{ id: parentId }, { id: blockerId }],
    parentId,
    (row) => row.id,
  );
  ok(cursor === 0, "refresh restores the removed task's parent cursor", cursor);

  const overlaySource = readFileSync(fileURLToPath(new URL("./overlay.ts", import.meta.url)), "utf-8");
  const indexSource = readFileSync(fileURLToPath(new URL("./index.ts", import.meta.url)), "utf-8");
  ok(overlaySource.includes('matchesKey(data, "shift+d")'), "uppercase D is handled inside the overlay");
  ok(!indexSource.includes('pi.registerShortcut("shift+d"'), "D is not registered as a global Pi shortcut");
  ok(
    overlaySource.includes("refreshedCursorIndex(this.rows, id") && indexSource.includes("overlay.focusTaskId(preferredCursorId)"),
    "overlay applies the refreshed cursor target after removal",
  );

  console.log(`\n# ${passed} passed, ${failed} failed`);
  if (failed > 0) process.exit(1);
}

main().catch((err) => {
  console.log(`not ok - unexpected error: ${(err as Error).message}`);
  process.exit(1);
});

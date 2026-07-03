#!/usr/bin/env tsx
/** Integration smoke tests for the tasks extension's `ot` CLI wrapper. */

import { existsSync, mkdtempSync, rmSync, writeFileSync, mkdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import {
  clearOtBinaryCache,
  otCreateTask,
  otCyclePriority,
  otCycleStatus,
  otList,
  resolveOtBinary,
  runOt,
} from "./ot.ts";

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

async function main(): Promise<void> {
  const here = dirname(fileURLToPath(import.meta.url));
  const repoRoot = join(here, "..", "..", "..");
  const planPath = "design/log/2026-05-18-tasks-extension-ot-cli.org";

  clearOtBinaryCache();
  const binary = resolveOtBinary();
  ok(existsSync(binary), "resolveOtBinary finds an executable candidate", binary);

  const env = await runOt<{
    file: string;
    section: string;
    found: boolean;
    heading?: string;
    body?: string;
  }>(["section", planPath, "Summary"], { root: repoRoot });

  ok(env.ok, "runOt parses a successful org-tasks/v1 envelope", env);
  if (env.ok) {
    ok(env.result.found, "ot section finds the Summary section", env.result);
    ok(env.result.heading === "* Summary", "ot section preserves the heading", env.result);
  }

  const listed = await otList<{ sourcePath?: string; sourceContent?: string }>({ root: repoRoot });
  ok(listed.root === repoRoot, "otList returns the resolved root", listed.root);
  ok(Array.isArray(listed.tree), "otList returns a task tree", listed);
  ok(!!listed.sources && Object.keys(listed.sources).length > 0, "otList returns shared source content", listed.sources);
  const firstWithSource = listed.tree.find((task) => task.sourcePath);
  ok(!firstWithSource?.sourceContent, "ot list omits duplicated per-task sourceContent", firstWithSource);

  const temp = mkdtempSync(join(tmpdir(), "tasks-ext-ot-"));
  try {
    const higher = join(temp, "higher");
    const inner = join(higher, "inner");
    const nested = join(inner, "child");
    mkdirSync(nested, { recursive: true });
    writeFileSync(join(higher, "TASKS.org"), "* TODO Higher\n:PROPERTIES:\n:CUSTOM_ID: 11111111-1111-4111-8111-111111111111\n:END:\n", "utf-8");
    writeFileSync(join(inner, "TASKS.org"), "* TODO Inner\n:PROPERTIES:\n:CUSTOM_ID: 22222222-2222-4222-8222-222222222222\n:END:\n", "utf-8");
    const nestedListed = await otList<{ summary: string }>({ cwd: nested });
    ok(nestedListed.root === inner, "otList cwd traversal uses nearest ancestor TASKS.org", nestedListed.root);
    ok(nestedListed.tree[0]?.summary === "Inner", "otList cwd traversal loads nearest ancestor tasks", nestedListed.tree);

    const fallback = join(temp, "no-tasks", "child");
    mkdirSync(fallback, { recursive: true });
    const fallbackListed = await otList({ cwd: fallback });
    ok(fallbackListed.root === fallback, "otList root falls back to cwd with no ancestor TASKS.org", fallbackListed.root);
  } finally {
    rmSync(temp, { recursive: true, force: true });
  }

  // ── status --cycle + create --relative-to wrappers ──────────────────
  const cdir = mkdtempSync(join(tmpdir(), "tasks-ext-otc-"));
  try {
    const parentId = "33333333-3333-4333-8333-333333333333";
    const childId = "44444444-4444-4444-8444-444444444444";
    writeFileSync(
      join(cdir, "TASKS.org"),
      [
        "#+TITLE: Tasks",
        "",
        "* Improvements",
        "",
        "** TODO Parent",
        ":PROPERTIES:",
        `:CUSTOM_ID: ${parentId}`,
        ":END:",
        "*** TODO Child",
        ":PROPERTIES:",
        `:CUSTOM_ID: ${childId}`,
        ":END:",
        "",
      ].join("\n"),
      "utf-8",
    );
    writeFileSync(join(cdir, "TASKS.local.org"), "#+SELECTED:\n", "utf-8");

    const cycled = await otCycleStatus(parentId, "forward", { cwd: cdir });
    ok(cycled.prevStatus === "TODO" && cycled.status === "STARTED",
       "otCycleStatus forward TODO -> STARTED", cycled);
    const back = await otCycleStatus(parentId, "back", { cwd: cdir });
    ok(back.status === "TODO", "otCycleStatus back STARTED -> TODO", back);

    const prioFwd = await otCyclePriority(parentId, "forward", { cwd: cdir });
    ok(prioFwd.prevPriority === null && prioFwd.priority === "A",
       "otCyclePriority forward from unset -> A", prioFwd);
    const prioBack = await otCyclePriority(parentId, "back", { cwd: cdir });
    ok(prioBack.priority === null,
       "otCyclePriority back A -> unset", prioBack);
    const prioLow = await otCyclePriority(parentId, "back", { cwd: cdir });
    ok(prioLow.priority === "D",
       "otCyclePriority back from unset -> D (lowest)", prioLow);

    const sib = await otCreateTask(
      { summary: "Rel sibling", relativeTo: childId, as: "sibling" },
      { cwd: cdir },
    );
    ok(!!sib.id, "otCreateTask --relative-to sibling returns an id", sib);
    const kid = await otCreateTask(
      { summary: "Rel child", relativeTo: parentId, as: "child" },
      { cwd: cdir },
    );
    const tree = await otList<{ summary: string; level: number }>({ cwd: cdir });
    const rows: Array<{ summary: string; level: number }> = [];
    const walk = (ts: Array<{ summary: string; level: number; children?: unknown[] }>) => {
      for (const t of ts) {
        rows.push({ summary: t.summary, level: t.level });
        if (Array.isArray(t.children)) walk(t.children as typeof ts);
      }
    };
    walk(tree.tree as Array<{ summary: string; level: number; children?: unknown[] }>);
    const sibRow = rows.find((r) => r.summary === "Rel sibling");
    const kidRow = rows.find((r) => r.summary === "Rel child");
    ok(sibRow?.level === 3, "sibling lands at the anchor's level (3)", sibRow);
    ok(kidRow?.level === 3 && !!kid.id, "child nests under the parent (level 3)", kidRow);
  } finally {
    rmSync(cdir, { recursive: true, force: true });
  }

  console.log(`\n# ${passed} passed, ${failed} failed`);
  if (failed > 0) process.exit(1);
}

main().catch((err) => {
  failed++;
  console.log(`not ok - unexpected error: ${(err as Error).message}`);
  console.log(`\n# ${passed} passed, ${failed} failed`);
  process.exit(1);
});

#!/usr/bin/env tsx
/**
 * Tests for the cross-extension `buildTaskBlock` helper.
 *
 * Covers the contract documented in
 * `design/log/2026-04-28-jira-clone-token-efficiency.org`:
 *
 * - Each priority bucket maps correctly (Highest/High/Medium/Low/Lowest).
 * - Missing/unknown priority yields no cookie.
 * - Labels render as `:foo:bar:` after the summary.
 * - Drawer ordering is :ID: → :CREATED: → :LINKED_ISSUES:.
 * - Trailing newline hygiene: the assembled block ends in exactly one `\n`.
 *
 * Run: `tsx insert.test.ts` (or via `./test.sh`).
 */

import {
  buildTaskBlock,
  insertTaskIntoFile,
  mapPriorityName,
} from "./insert.ts";
import * as fsp from "node:fs/promises";
import * as path from "node:path";
import * as os from "node:os";

const { mkdtemp, readFile, realpath, rm, symlink, writeFile } = fsp;
const { join } = path;
const { tmpdir } = os;

let passed = 0;
let failed = 0;

function assertEqual<T>(actual: T, expected: T, message: string): void {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a === e) {
    passed++;
    console.log(`ok - ${message}`);
  } else {
    failed++;
    console.log(`not ok - ${message}`);
    console.log(`  expected: ${e}`);
    console.log(`  actual:   ${a}`);
  }
}

function assertContains(haystack: string, needle: string, message: string): void {
  if (haystack.includes(needle)) {
    passed++;
    console.log(`ok - ${message}`);
  } else {
    failed++;
    console.log(`not ok - ${message}`);
    console.log(`  expected to contain: ${JSON.stringify(needle)}`);
    console.log(`  actual: ${JSON.stringify(haystack)}`);
  }
}

const FIXED_ID = "11111111-2222-4333-8444-555555555555";
const FIXED_TS = "2026-04-28 Tue 17:00";

// ── mapPriorityName: each bucket plus pass-through-null ──────────────

assertEqual(mapPriorityName("Highest"), "A", "mapPriorityName: Highest → A");
assertEqual(mapPriorityName("High"),    "B", "mapPriorityName: High → B");
assertEqual(mapPriorityName("Medium"),  "C", "mapPriorityName: Medium → C");
assertEqual(mapPriorityName("Low"),     "D", "mapPriorityName: Low → D");
assertEqual(mapPriorityName("Lowest"),  "D", "mapPriorityName: Lowest → D");
assertEqual(mapPriorityName("highest"), "A", "mapPriorityName: case-insensitive");
assertEqual(mapPriorityName(" Medium "), "C", "mapPriorityName: trims whitespace");
assertEqual(mapPriorityName(""),        null, "mapPriorityName: empty → null");
assertEqual(mapPriorityName(null),      null, "mapPriorityName: null → null");
assertEqual(mapPriorityName("Critical"), null, "mapPriorityName: unknown → null");

// ── Drawer ordering and a representative happy-path block ────────────

{
  const out = buildTaskBlock({
    summary: "Add login flow",
    priorityName: "High",
    body: "Body text.",
    linkedIssues: ["SAND-42", "SAND-43"],
    labels: ["backend", "auth"],
    id: FIXED_ID,
    createdAt: FIXED_TS,
  });

  const expected = [
    "** TODO [#B] Add login flow :backend:auth:",
    ":PROPERTIES:",
    `:ID: ${FIXED_ID}`,
    `:CREATED: [${FIXED_TS}]`,
    ":LINKED_ISSUES: SAND-42 SAND-43",
    ":END:",
    ":LOGBOOK:",
    `- Created [${FIXED_TS}]`,
    ":END:",
    "Body text.",
    "",
  ].join("\n");
  assertEqual(out.block, expected, "buildTaskBlock: full happy-path rendering");
  assertEqual(out.id, FIXED_ID, "buildTaskBlock: returns the supplied id");
  assertEqual(out.heading, "** TODO [#B] Add login flow :backend:auth:",
    "buildTaskBlock: heading line shape");
}

// ── Each priority bucket end-to-end ──────────────────────────────────

const priorityCases: Array<[string, string]> = [
  ["Highest", "A"],
  ["High", "B"],
  ["Medium", "C"],
  ["Low", "D"],
  ["Lowest", "D"],
];
for (const [name, char] of priorityCases) {
  const out = buildTaskBlock({
    summary: "S",
    priorityName: name,
    id: FIXED_ID,
    createdAt: FIXED_TS,
  });
  assertContains(
    out.heading,
    `[#${char}]`,
    `buildTaskBlock: priority "${name}" → [#${char}] in heading`,
  );
}

// ── Missing/unknown priority: no cookie ──────────────────────────────

{
  const out = buildTaskBlock({
    summary: "S",
    id: FIXED_ID,
    createdAt: FIXED_TS,
  });
  assertEqual(out.heading, "** TODO S",
    "buildTaskBlock: missing priority → no cookie");
}
{
  const out = buildTaskBlock({
    summary: "S",
    priorityName: "Whatever",
    id: FIXED_ID,
    createdAt: FIXED_TS,
  });
  assertEqual(out.heading, "** TODO S",
    "buildTaskBlock: unknown priority → no cookie");
}

// ── Labels: tag suffix shape ─────────────────────────────────────────

{
  const out = buildTaskBlock({
    summary: "Hi",
    labels: ["foo", "bar"],
    id: FIXED_ID,
    createdAt: FIXED_TS,
  });
  assertEqual(out.heading, "** TODO Hi :foo:bar:",
    "buildTaskBlock: labels rendered as :foo:bar: tag suffix");
}
{
  const out = buildTaskBlock({
    summary: "Hi",
    labels: [],
    id: FIXED_ID,
    createdAt: FIXED_TS,
  });
  assertEqual(out.heading, "** TODO Hi",
    "buildTaskBlock: empty labels → no tag suffix");
}
{
  const out = buildTaskBlock({
    summary: "Hi",
    id: FIXED_ID,
    createdAt: FIXED_TS,
  });
  assertEqual(out.heading, "** TODO Hi",
    "buildTaskBlock: missing labels → no tag suffix");
}

// ── Drawer: no LINKED_ISSUES line when none supplied ─────────────────

{
  const out = buildTaskBlock({
    summary: "S",
    id: FIXED_ID,
    createdAt: FIXED_TS,
  });
  assertEqual(out.drawer, [
    ":PROPERTIES:",
    `:ID: ${FIXED_ID}`,
    `:CREATED: [${FIXED_TS}]`,
    ":END:",
  ].join("\n"), "buildTaskBlock: drawer omits :LINKED_ISSUES: when empty");
}
{
  const out = buildTaskBlock({
    summary: "S",
    linkedIssues: [],
    id: FIXED_ID,
    createdAt: FIXED_TS,
  });
  assertContains(out.block, ":END:",
    "buildTaskBlock: empty linkedIssues array still produces valid drawer");
  if (out.block.includes(":LINKED_ISSUES:")) {
    failed++;
    console.log("not ok - empty linkedIssues array should not emit :LINKED_ISSUES: line");
  } else {
    passed++;
    console.log("ok - empty linkedIssues array does not emit :LINKED_ISSUES: line");
  }
}

// ── Drawer ordering invariant ────────────────────────────────────────

{
  const out = buildTaskBlock({
    summary: "S",
    linkedIssues: ["SAND-1"],
    id: FIXED_ID,
    createdAt: FIXED_TS,
  });
  const idIdx = out.drawer.indexOf(":ID:");
  const createdIdx = out.drawer.indexOf(":CREATED:");
  const linkedIdx = out.drawer.indexOf(":LINKED_ISSUES:");
  if (idIdx < createdIdx && createdIdx < linkedIdx) {
    passed++;
    console.log("ok - drawer ordering is :ID: → :CREATED: → :LINKED_ISSUES:");
  } else {
    failed++;
    console.log("not ok - drawer ordering should be :ID: → :CREATED: → :LINKED_ISSUES:");
    console.log(`  drawer: ${out.drawer}`);
  }
}

// ── Trailing-newline hygiene ─────────────────────────────────────────

{
  const out = buildTaskBlock({
    summary: "S",
    body: "First.\nSecond.\n",
    id: FIXED_ID,
    createdAt: FIXED_TS,
  });
  if (out.block.endsWith("\n") && !out.block.endsWith("\n\n")) {
    passed++;
    console.log("ok - block ends with exactly one trailing newline");
  } else {
    failed++;
    console.log("not ok - block should end with exactly one trailing newline");
    console.log(`  block tail: ${JSON.stringify(out.block.slice(-4))}`);
  }
  assertContains(out.block, "First.\nSecond.\n",
    "block: body content preserved verbatim (sans edge newlines)");
}

// ── Body normalisation: leading/trailing blank lines stripped ────────

{
  const out = buildTaskBlock({
    summary: "S",
    body: "\n\nBody.\n\n",
    id: FIXED_ID,
    createdAt: FIXED_TS,
  });
  assertEqual(out.body, "Body.",
    "buildTaskBlock: leading/trailing newlines stripped from body");
}

// ── parentId promotes to level 3 ────────────────────────────────────

{
  const out = buildTaskBlock({
    summary: "Sub",
    parentId: "deadbeef-0000-4000-8000-000000000000",
    id: FIXED_ID,
    createdAt: FIXED_TS,
  });
  assertEqual(out.heading, "*** TODO Sub",
    "buildTaskBlock: parentId promotes heading to level 3");
}

// ── Empty/missing summary throws ─────────────────────────────────────

{
  let threw = false;
  try {
    buildTaskBlock({ summary: "   ", id: FIXED_ID, createdAt: FIXED_TS });
  } catch {
    threw = true;
  }
  if (threw) {
    passed++;
    console.log("ok - buildTaskBlock throws on empty summary");
  } else {
    failed++;
    console.log("not ok - buildTaskBlock should throw on empty summary");
  }
}

// ── Defaults: UUID + timestamp ──────────────────────────────────────

{
  const out = buildTaskBlock({ summary: "S" });
  if (/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(out.id)) {
    passed++;
    console.log("ok - default :ID: is a UUID v4");
  } else {
    failed++;
    console.log(`not ok - default :ID: should be UUID v4, got ${out.id}`);
  }
  if (/:CREATED: \[\d{4}-\d{2}-\d{2} \w{3} \d{2}:\d{2}\]/.test(out.drawer)) {
    passed++;
    console.log("ok - default :CREATED: matches org-timestamp shape");
  } else {
    failed++;
    console.log(`not ok - default :CREATED: should match org-timestamp shape`);
    console.log(`  drawer: ${out.drawer}`);
  }
}

// ── File-side: insertTaskIntoFile happy path ────────────────────────
//
// Drives `insertTaskIntoFile` against scratch org files in a temp dir,
// asserting the on-disk fixture matches expectations. Each scenario is
// scoped to its own subdirectory so failures don't bleed across tests.
//
// All file-side scenarios are wrapped in a single async main() because
// `tsx` compiles to CJS by default and rejects top-level await.

async function withTempDir<T>(fn: (dir: string) => Promise<T>): Promise<T> {
  const dir = await mkdtemp(join(tmpdir(), "tasks-insert-"));
  try {
    return await fn(dir);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
}

async function fileSideScenarios(): Promise<void> {
await withTempDir(async (dir) => {
  const tasksPath = join(dir, "TASKS.org");
  await writeFile(
    tasksPath,
    [
      "#+TITLE: Fixture",
      "#+TODO: TODO(t) STARTED(s) WAITING(w) | DONE(d) CANCELLED(c)",
      "",
      "* Improvements",
      "** TODO Pre-existing task",
      ":PROPERTIES:",
      ":ID: aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
      ":END:",
      "",
      "* Fixes",
      "",
    ].join("\n"),
    "utf-8",
  );

  const result = await insertTaskIntoFile({
    file: tasksPath,
    projectRoot: dir,
    section: "Improvements",
    summary: "Cloned from SAND-42",
    priorityName: "High",
    body: "Issue body.",
    linkedIssues: ["SAND-42"],
    labels: ["backend"],
    id: FIXED_ID,
    createdAt: FIXED_TS,
  });

  assertEqual(result.status, "inserted", "insertTaskIntoFile: returns inserted status");
  if (result.status !== "inserted") return;
  assertEqual(result.id, FIXED_ID, "insertTaskIntoFile: surfaces the id back");
  assertEqual(result.file, await realpath(tasksPath), "insertTaskIntoFile: surfaces the absolute file path");

  const written = await readFile(tasksPath, "utf-8");
  const expected = [
    "#+TITLE: Fixture",
    "#+TODO: TODO(t) STARTED(s) WAITING(w) | DONE(d) CANCELLED(c)",
    "",
    "* Improvements",
    "** TODO Pre-existing task",
    ":PROPERTIES:",
    ":ID: aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
    ":END:",
    "",
    "** TODO [#B] Cloned from SAND-42 :backend:",
    ":PROPERTIES:",
    `:ID: ${FIXED_ID}`,
    `:CREATED: [${FIXED_TS}]`,
    ":LINKED_ISSUES: SAND-42",
    ":END:",
    ":LOGBOOK:",
    `- Created [${FIXED_TS}]`,
    ":END:",
    "Issue body.",
    "",
    "* Fixes",
    "",
  ].join("\n");
  assertEqual(written, expected,
    "insertTaskIntoFile: spliced result matches fixture");
  // Heading line: the new heading is at line 10 (1-indexed) in the expected
  // fixture above.
  assertEqual(result.line, 10,
    "insertTaskIntoFile: returned line points at the new heading");
});

// ── File-side: idempotency refusal on duplicate :LINKED_ISSUES: ──────

await withTempDir(async (dir) => {
  const tasksPath = join(dir, "TASKS.org");
  await writeFile(
    tasksPath,
    [
      "* Improvements",
      "** TODO Already cloned",
      ":PROPERTIES:",
      ":ID: 11111111-2222-4333-8444-555555555555",
      ":LINKED_ISSUES: SAND-42 SAND-43",
      ":END:",
      "",
    ].join("\n"),
    "utf-8",
  );

  const before = await readFile(tasksPath, "utf-8");
  const result = await insertTaskIntoFile({
    file: tasksPath,
    projectRoot: dir,
    section: "Improvements",
    summary: "Cloned from SAND-42",
    linkedIssues: ["SAND-42"],
    id: FIXED_ID,
    createdAt: FIXED_TS,
  });
  assertEqual(result.status, "duplicate",
    "insertTaskIntoFile: refuses with status=duplicate when token already linked");
  if (result.status === "duplicate") {
    assertEqual(result.existingId, "11111111-2222-4333-8444-555555555555",
      "insertTaskIntoFile: surfaces the existing task's :ID:");
    assertEqual(result.conflictingToken, "SAND-42",
      "insertTaskIntoFile: identifies the conflicting token");
  }
  const after = await readFile(tasksPath, "utf-8");
  assertEqual(after, before,
    "insertTaskIntoFile: file unchanged on duplicate refusal");
});

// ── File-side: idempotency across `alsoScan` (sibling files) ─────────

await withTempDir(async (dir) => {
  const tasksPath = join(dir, "TASKS.org");
  const localPath = join(dir, "TASKS.local.org");
  await writeFile(
    tasksPath,
    [
      "* Improvements",
      "",
    ].join("\n"),
    "utf-8",
  );
  await writeFile(
    localPath,
    [
      "#+SELECTED:",
      "",
      "* Drafts",
      "** TODO Local draft cloned from SAND-99",
      ":PROPERTIES:",
      ":ID: 22222222-3333-4444-8555-666666666666",
      ":LINKED_ISSUES: SAND-99",
      ":END:",
      "",
    ].join("\n"),
    "utf-8",
  );

  const result = await insertTaskIntoFile({
    file: tasksPath,
    projectRoot: dir,
    section: "Improvements",
    summary: "Re-clone of SAND-99",
    linkedIssues: ["SAND-99"],
    alsoScan: [localPath],
    id: FIXED_ID,
    createdAt: FIXED_TS,
  });
  assertEqual(result.status, "duplicate",
    "insertTaskIntoFile: detects duplicate in alsoScan sibling file");
  if (result.status === "duplicate") {
    assertEqual(result.existingFile, await realpath(localPath),
      "insertTaskIntoFile: attributes duplicate to TASKS.local.org");
  }
});

// ── File-side: missing section refused without allowCreateSection ────

await withTempDir(async (dir) => {
  const tasksPath = join(dir, "TASKS.org");
  await writeFile(
    tasksPath,
    [
      "* Improvements",
      "",
    ].join("\n"),
    "utf-8",
  );
  const before = await readFile(tasksPath, "utf-8");
  const result = await insertTaskIntoFile({
    file: tasksPath,
    projectRoot: dir,
    section: "NonExistent",
    summary: "S",
    id: FIXED_ID,
    createdAt: FIXED_TS,
  });
  assertEqual(result.status, "section_not_found",
    "insertTaskIntoFile: refuses with section_not_found when section missing");
  const after = await readFile(tasksPath, "utf-8");
  assertEqual(after, before,
    "insertTaskIntoFile: file unchanged when section missing");
});

// ── File-side: missing section + allowCreateSection appends new section ──

await withTempDir(async (dir) => {
  const tasksPath = join(dir, "TASKS.org");
  await writeFile(
    tasksPath,
    [
      "#+TITLE: Fixture",
      "",
      "* Improvements",
      "",
    ].join("\n"),
    "utf-8",
  );
  const result = await insertTaskIntoFile({
    file: tasksPath,
    projectRoot: dir,
    section: "FreshSection",
    summary: "Brand new",
    allowCreateSection: true,
    id: FIXED_ID,
    createdAt: FIXED_TS,
  });
  assertEqual(result.status, "inserted",
    "insertTaskIntoFile: succeeds when section auto-created");
  const written = await readFile(tasksPath, "utf-8");
  assertContains(written, "* FreshSection",
    "insertTaskIntoFile: appended new section heading");
  assertContains(written, "** TODO Brand new",
    "insertTaskIntoFile: spliced new task under fresh section");
});

// ── File-side: sandbox rejects out-of-project paths ─────────────────

await withTempDir(async (dir) => {
  await withTempDir(async (outside) => {
    const outsidePath = join(outside, "TASKS.org");
    await writeFile(outsidePath, "* Improvements\n", "utf-8");
    const result = await insertTaskIntoFile({
      file: outsidePath,
      projectRoot: dir,
      section: "Improvements",
      summary: "Should not write",
      id: FIXED_ID,
      createdAt: FIXED_TS,
    });
    assertEqual(result.status, "error",
      "insertTaskIntoFile: rejects out-of-tree absolute target path");
    if (result.status === "error") {
      assertEqual(result.reason, "path_outside_project",
        "insertTaskIntoFile: out-of-tree target reports path_outside_project");
    }
  });
});

await withTempDir(async (dir) => {
  const outside = await mkdtemp(join(tmpdir(), "tasks-insert-outside-"));
  try {
    const result = await insertTaskIntoFile({
      file: join(dir, "..", path.basename(outside), "TASKS.org"),
      projectRoot: dir,
      section: "Improvements",
      summary: "Should not write",
      id: FIXED_ID,
      createdAt: FIXED_TS,
    });
    assertEqual(result.status, "error",
      "insertTaskIntoFile: rejects parent-traversal target path");
  } finally {
    await rm(outside, { recursive: true, force: true });
  }
});

await withTempDir(async (dir) => {
  await withTempDir(async (outside) => {
    const outsidePath = join(outside, "TASKS.org");
    await writeFile(outsidePath, "* Improvements\n", "utf-8");
    const linkPath = join(dir, "linked-outside.org");
    await symlink(outsidePath, linkPath);
    const result = await insertTaskIntoFile({
      file: linkPath,
      projectRoot: dir,
      section: "Improvements",
      summary: "Should not write",
      id: FIXED_ID,
      createdAt: FIXED_TS,
    });
    assertEqual(result.status, "error",
      "insertTaskIntoFile: rejects symlink escape target path");
  });
});

await withTempDir(async (dir) => {
  await withTempDir(async (outside) => {
    const tasksPath = join(dir, "TASKS.org");
    const outsidePath = join(outside, "TASKS.local.org");
    await writeFile(tasksPath, "* Improvements\n", "utf-8");
    await writeFile(outsidePath, "* TODO Outside\n", "utf-8");
    const result = await insertTaskIntoFile({
      file: tasksPath,
      projectRoot: dir,
      section: "Improvements",
      summary: "Should not scan",
      linkedIssues: ["SAND-1"],
      alsoScan: [outsidePath],
      id: FIXED_ID,
      createdAt: FIXED_TS,
    });
    assertEqual(result.status, "error",
      "insertTaskIntoFile: rejects out-of-tree alsoScan path");
  });
});

// ── File-side: empty summary returns structured error ────────────────

await withTempDir(async (dir) => {
  const tasksPath = join(dir, "TASKS.org");
  await writeFile(tasksPath, "* Improvements\n", "utf-8");
  const result = await insertTaskIntoFile({
    file: tasksPath,
    projectRoot: dir,
    section: "Improvements",
    summary: "   ",
    id: FIXED_ID,
    createdAt: FIXED_TS,
  });
  assertEqual(result.status, "error",
    "insertTaskIntoFile: returns error status on empty summary");
});
} // end fileSideScenarios

// ── Summary ───────────────────────────────────────────────────────────

fileSideScenarios().then(
  () => {
    console.log(`\n# ${passed} passed, ${failed} failed`);
    process.exit(failed === 0 ? 0 : 1);
  },
  (err) => {
    failed++;
    console.log(`not ok - file-side scenarios threw: ${(err as Error).stack ?? err}`);
    console.log(`\n# ${passed} passed, ${failed} failed`);
    process.exit(1);
  },
);

#!/usr/bin/env tsx
/** Tests for the prior-art `scanSummaries` walker. */

import {
  DEFAULT_MAX_BODY_CHARS,
  scanSummaries,
  type ScanRow,
} from "./scan.ts";
import { parseTasks, type Task } from "./parser.ts";

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

function assertTrue(actual: boolean, message: string): void {
  if (actual) {
    passed++;
    console.log(`ok - ${message}`);
  } else {
    failed++;
    console.log(`not ok - ${message}`);
  }
}

// ── Helpers ──────────────────────────────────────────────────────────

function parse(content: string, sourcePath: string): Task[] {
  return parseTasks(content, { sourcePath }).tasks;
}

/**
 * Build a synthetic `readChangeRecord` callback from a map keyed on
 * `task.importPath`. Returns null for tasks without an importPath or
 * an entry in the map (the latter simulates a missing / unreadable
 * change-record).
 */
function mapReader(map: Record<string, string>) {
  return (task: Task): string | null => {
    if (!task.importPath) return null;
    return Object.prototype.hasOwnProperty.call(map, task.importPath)
      ? map[task.importPath]!
      : null;
  };
}

// Reusable record contents.

const RICH_RECORD = [
  "#+TITLE: Rich record",
  "",
  "* Summary",
  "Compact paragraph capturing the final state.",
  "",
  "** Decisions",
  "- Foo :: Bar.",
  "",
  "* Context",
  "Background rationale that exceeds the synopsis.",
  "",
  "* Plan",
  "** TODO Step",
  "",
].join("\n");

const NO_SUMMARY_RECORD = [
  "#+TITLE: Sparse record",
  "",
  "* Plan",
  "** TODO Step",
  "",
  "* Implementation",
  "Notes.",
  "",
].join("\n");

const SUMMARY_NO_CONTEXT_RECORD = [
  "* Summary",
  "Short summary; no Context heading.",
  "",
  "* Plan",
  "** TODO Step",
  "",
].join("\n");

// ── Empty graph ──────────────────────────────────────────────────────

assertEqual(
  scanSummaries(
    { activeRoots: [], archivedRoots: [], readChangeRecord: () => null },
  ),
  [],
  "empty graph: returns empty array",
);

// ── Single TASKS.org task with full record ───────────────────────────

const singleActive = parse(
  [
    "* Improvements",
    "** TODO Implement feature X :feat:area:",
    ":PROPERTIES:",
    ":CUSTOM_ID: 11111111-1111-4111-8111-111111111111",
    ":CREATED: [2026-05-01 Fri 09:00]",
    ":END:",
    "#+IMPORT: design/log/feature-x.org",
    "Body line.",
    "",
  ].join("\n"),
  "/proj/TASKS.org",
);

const singleResult = scanSummaries({
  activeRoots: singleActive,
  archivedRoots: [],
  readChangeRecord: mapReader({ "design/log/feature-x.org": RICH_RECORD }),
});

assertEqual(
  singleResult.length,
  1,
  "single task with full record: one row emitted",
);

const row0 = singleResult[0]!;
assertEqual(row0.id, "11111111-1111-4111-8111-111111111111", "row id matches CUSTOM_ID");
assertEqual(row0.summary, "Implement feature X", "row summary is the task heading");
assertEqual(row0.status, "TODO", "row status echoed verbatim");
assertEqual(row0.priority, null, "row priority null when no [#X] cookie");
assertEqual(row0.tags, ["feat", "area"], "row tags from heading");
assertEqual(row0.sourcePath, "/proj/TASKS.org", "row sourcePath from parseTasks");
assertEqual(row0.importPath, "design/log/feature-x.org", "row importPath echoed verbatim");
assertEqual(
  row0.recordSummary,
  {
    found: true,
    body: "Compact paragraph capturing the final state.\n\n** Decisions\n- Foo :: Bar.\n",
  },
  "row recordSummary: full Summary body including nested ** Decisions",
);
assertEqual(row0.hasContext, true, "hasContext true when record has * Context heading");

// ── Task with importPath but missing change-record content ───────────

const orphanImport = scanSummaries({
  activeRoots: parse(
    [
      "** TODO Orphan task",
      ":PROPERTIES:",
      ":CUSTOM_ID: 22222222-2222-4222-8222-222222222222",
      ":END:",
      "#+IMPORT: design/log/missing.org",
      "",
    ].join("\n"),
    "/proj/TASKS.org",
  ),
  archivedRoots: [],
  readChangeRecord: mapReader({}), // record absent
});

assertEqual(
  orphanImport[0]!.recordSummary,
  { found: false },
  "missing/unreadable change-record surfaces as { found: false }",
);
assertEqual(
  orphanImport[0]!.hasContext,
  false,
  "hasContext false when change-record is unreadable",
);

// ── Task with #+IMPORT: pointing at a record without * Summary ───────

const noSummary = scanSummaries({
  activeRoots: parse(
    [
      "** DONE Closed task",
      ":PROPERTIES:",
      ":CUSTOM_ID: 33333333-3333-4333-8333-333333333333",
      ":END:",
      "#+IMPORT: design/log/no-summary.org",
      "",
    ].join("\n"),
    "/proj/TASKS.org",
  ),
  archivedRoots: [],
  readChangeRecord: mapReader({
    "design/log/no-summary.org": NO_SUMMARY_RECORD,
  }),
});

assertEqual(
  noSummary[0]!.recordSummary,
  { found: false },
  "change-record without * Summary heading: { found: false }",
);

// ── Task with no #+IMPORT: at all ────────────────────────────────────

const noImport = scanSummaries({
  activeRoots: parse(
    [
      "** TODO Quick fix",
      ":PROPERTIES:",
      ":CUSTOM_ID: 44444444-4444-4444-8444-444444444444",
      ":END:",
      "Body only, no plan.",
      "",
    ].join("\n"),
    "/proj/TASKS.org",
  ),
  archivedRoots: [],
  readChangeRecord: () => null,
});

assertEqual(
  noImport[0]!.recordSummary,
  null,
  "task with no #+IMPORT: surfaces recordSummary: null",
);
assertEqual(
  noImport[0]!.hasContext,
  false,
  "hasContext false when there's no record at all",
);

// ── hasContext flag respects * Context presence/absence ──────────────

const hasContextRows = scanSummaries({
  activeRoots: parse(
    [
      "** TODO With ctx",
      ":PROPERTIES:",
      ":CUSTOM_ID: 55555555-5555-4555-8555-555555555555",
      ":END:",
      "#+IMPORT: a.org",
      "",
      "** TODO Without ctx",
      ":PROPERTIES:",
      ":CUSTOM_ID: 66666666-6666-4666-8666-666666666666",
      ":END:",
      "#+IMPORT: b.org",
      "",
    ].join("\n"),
    "/proj/TASKS.org",
  ),
  archivedRoots: [],
  readChangeRecord: mapReader({
    "a.org": RICH_RECORD,            // has * Context
    "b.org": SUMMARY_NO_CONTEXT_RECORD, // no * Context
  }),
});

assertEqual(
  hasContextRows.map((r) => [r.summary, r.hasContext]),
  [["With ctx", true], ["Without ctx", false]],
  "hasContext flag tracks * Context heading presence per row",
);

// ── scope=active filter (archived roots excluded) ────────────────────

const activeRoots = parse(
  [
    "** STARTED Live task",
    ":PROPERTIES:",
    ":CUSTOM_ID: 77777777-7777-4777-8777-777777777777",
    ":END:",
    "",
  ].join("\n"),
  "/proj/TASKS.org",
);

const archivedRoots = parse(
  [
    "** DONE Old task",
    ":PROPERTIES:",
    ":CUSTOM_ID: 88888888-8888-4888-8888-888888888888",
    ":END:",
    "",
  ].join("\n"),
  "/proj/TASKS.archive.org",
);

assertEqual(
  scanSummaries(
    { activeRoots, archivedRoots, readChangeRecord: () => null },
    { scope: "active" },
  ).map((r) => r.summary),
  ["Live task"],
  "scope=active excludes archived roots",
);

assertEqual(
  scanSummaries(
    { activeRoots, archivedRoots, readChangeRecord: () => null },
    { scope: "archived" },
  ).map((r) => r.summary),
  ["Old task"],
  "scope=archived excludes active roots",
);

assertEqual(
  scanSummaries(
    { activeRoots, archivedRoots, readChangeRecord: () => null },
    { scope: "all" },
  ).map((r) => r.summary),
  ["Live task", "Old task"],
  "scope=all emits active rows before archived rows",
);

assertEqual(
  scanSummaries(
    { activeRoots, archivedRoots, readChangeRecord: () => null },
  ).map((r) => r.summary),
  ["Live task", "Old task"],
  "default scope is 'all'",
);

// ── tag filter (OR-semantics) ────────────────────────────────────────

const tagged = parse(
  [
    "** TODO Backend feat :backend:security:",
    ":PROPERTIES:",
    ":CUSTOM_ID: 99999999-9999-4999-8999-999999999999",
    ":END:",
    "",
    "** TODO Frontend feat :ui:",
    ":PROPERTIES:",
    ":CUSTOM_ID: aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
    ":END:",
    "",
    "** TODO No tags",
    ":PROPERTIES:",
    ":CUSTOM_ID: bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
    ":END:",
    "",
  ].join("\n"),
  "/proj/TASKS.org",
);

assertEqual(
  scanSummaries(
    { activeRoots: tagged, archivedRoots: [], readChangeRecord: () => null },
    { tags: ["backend"] },
  ).map((r) => r.summary),
  ["Backend feat"],
  "tags filter: single-tag match",
);

assertEqual(
  scanSummaries(
    { activeRoots: tagged, archivedRoots: [], readChangeRecord: () => null },
    { tags: ["security", "ui"] },
  ).map((r) => r.summary),
  ["Backend feat", "Frontend feat"],
  "tags filter: OR-semantics across multiple values",
);

assertEqual(
  scanSummaries(
    { activeRoots: tagged, archivedRoots: [], readChangeRecord: () => null },
    { tags: ["nonexistent"] },
  ),
  [],
  "tags filter: empty result when no tags match",
);

assertEqual(
  scanSummaries(
    { activeRoots: tagged, archivedRoots: [], readChangeRecord: () => null },
    { tags: [] },
  ).map((r) => r.summary),
  ["Backend feat", "Frontend feat", "No tags"],
  "tags filter: empty array disables filtering (all rows pass)",
);

// ── maxBodyChars truncation ──────────────────────────────────────────

const longSummary = [
  "* Summary",
  "x".repeat(2000),
  "",
].join("\n");

const longTask = parse(
  [
    "** TODO Long task",
    ":PROPERTIES:",
    ":CUSTOM_ID: cccccccc-cccc-4ccc-8ccc-cccccccccccc",
    ":END:",
    "#+IMPORT: long.org",
    "",
  ].join("\n"),
  "/proj/TASKS.org",
);

const cappedAt100 = scanSummaries(
  {
    activeRoots: longTask,
    archivedRoots: [],
    readChangeRecord: mapReader({ "long.org": longSummary }),
  },
  { maxBodyChars: 100 },
);

const cappedBody = (cappedAt100[0]!.recordSummary as { body: string }).body;
assertEqual(
  cappedBody.length,
  100,
  "maxBodyChars=100: output length is exactly 100",
);
assertTrue(
  cappedBody.endsWith("\u2026"),
  "truncation sentinel '\u2026' appended when body exceeds cap",
);

const uncapped = scanSummaries(
  {
    activeRoots: longTask,
    archivedRoots: [],
    readChangeRecord: mapReader({ "long.org": longSummary }),
  },
  { maxBodyChars: 5000 }, // exceeds actual length
);
const uncappedBody = (uncapped[0]!.recordSummary as { body: string }).body;
assertTrue(
  !uncappedBody.endsWith("\u2026"),
  "no sentinel when maxBodyChars exceeds actual body length",
);

// Default cap kicks in.
const defaultCap = scanSummaries({
  activeRoots: longTask,
  archivedRoots: [],
  readChangeRecord: mapReader({ "long.org": longSummary }),
});
const defaultBody = (defaultCap[0]!.recordSummary as { body: string }).body;
assertEqual(
  defaultBody.length,
  DEFAULT_MAX_BODY_CHARS,
  `default cap (${DEFAULT_MAX_BODY_CHARS}) applied when maxBodyChars omitted`,
);

// ── Tasks without :CUSTOM_ID: are skipped ────────────────────────────

const noId = parse(
  [
    "** TODO Has id",
    ":PROPERTIES:",
    ":CUSTOM_ID: dddddddd-dddd-4ddd-8ddd-dddddddddddd",
    ":END:",
    "",
    "** TODO No id",
    "",
  ].join("\n"),
  "/proj/TASKS.org",
);

assertEqual(
  scanSummaries(
    { activeRoots: noId, archivedRoots: [], readChangeRecord: () => null },
  ).map((r) => r.summary),
  ["Has id"],
  "tasks without :CUSTOM_ID: are skipped",
);

// ── Plan tasks inside change-records walk as their own rows ──────────

const parentWithPlan = parse(
  [
    "** STARTED Parent workstream",
    ":PROPERTIES:",
    ":CUSTOM_ID: eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee",
    ":END:",
    "#+IMPORT: design/log/plan.org",
    "",
  ].join("\n"),
  "/proj/TASKS.org",
);

// Simulate loadLinkedPlans having walked plan.org and populated importChildren.
const planTask: Task[] = parse(
  [
    "** TODO Plan step",
    ":PROPERTIES:",
    ":CUSTOM_ID: ffffffff-ffff-4fff-8fff-ffffffffffff",
    ":END:",
    "",
  ].join("\n"),
  "/proj/design/log/plan.org",
);
parentWithPlan[0]!.importChildren = planTask;

const planRows = scanSummaries({
  activeRoots: parentWithPlan,
  archivedRoots: [],
  readChangeRecord: mapReader({ "design/log/plan.org": RICH_RECORD }),
});

assertEqual(
  planRows.map((r) => ({ summary: r.summary, sourcePath: r.sourcePath })),
  [
    { summary: "Parent workstream", sourcePath: "/proj/TASKS.org" },
    { summary: "Plan step", sourcePath: "/proj/design/log/plan.org" },
  ],
  "plan tasks inside change-records surface as their own rows with sourcePath pointing at the record file",
);

// Plan task itself has no #+IMPORT:, so its recordSummary is null.
assertEqual(
  planRows[1]!.recordSummary,
  null,
  "nested plan task without its own #+IMPORT: gets recordSummary: null",
);

// ── Priority cookie is captured ──────────────────────────────────────

const prio = parse(
  [
    "** TODO [#A] High-priority work",
    ":PROPERTIES:",
    ":CUSTOM_ID: 12121212-1212-4121-8121-121212121212",
    ":END:",
    "",
  ].join("\n"),
  "/proj/TASKS.org",
);

assertEqual(
  scanSummaries(
    { activeRoots: prio, archivedRoots: [], readChangeRecord: () => null },
  )[0]!.priority,
  "A",
  "[#A] priority cookie is captured as 'A'",
);

console.log(`\n# ${passed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);

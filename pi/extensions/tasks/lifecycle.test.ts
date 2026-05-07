#!/usr/bin/env tsx
/** Lifecycle/LOGBOOK regression tests for org-memory status semantics. */

import { applyStatusTransition } from "./lifecycle.ts";
import { getTaskStarted, parseTasks, serializeTasks, type Task } from "./parser.ts";

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

function task(status = "TODO"): Task {
  return {
    level: 2,
    status,
    priority: null,
    summary: "Lifecycle task",
    tags: [],
    description: "",
    children: [],
    propertyLines: [
      ":ID: 11111111-2222-4333-8444-555555555555",
      ":CREATED: [2026-05-01 Fri 08:00]",
    ],
    logbookLines: ["- Created [2026-05-01 Fri 08:00]"],
    importPath: null,
    importRaw: null,
    closed: null,
    lineNumber: 0,
    endLine: 0,
  };
}

// ── Direct TODO -> DONE close ────────────────────────────────────────

{
  const t = task("TODO");
  applyStatusTransition(t, "DONE", "2026-05-01 Fri 09:00");
  assertEqual(t.status, "DONE", "direct close: status updates to DONE");
  assertEqual(t.closed, "2026-05-01 Fri 09:00", "direct close: CLOSED timestamp is written");
  assertEqual(t.logbookLines, [
    "- Created [2026-05-01 Fri 08:00]",
    "- State \"DONE\" from \"TODO\" [2026-05-01 Fri 09:00]",
  ], "direct close: appends one LOGBOOK state entry");
}

// ── STARTED -> DONE -> STARTED -> DONE reopen / re-close ────────────

{
  const t = task("STARTED");
  t.propertyLines.push(":STARTED: [2026-05-01 Fri 08:30]");
  applyStatusTransition(t, "DONE", "2026-05-01 Fri 09:00");
  applyStatusTransition(t, "STARTED", "2026-05-01 Fri 09:10");
  assertEqual(t.closed, null, "reopen: clears current CLOSED timestamp");
  assertEqual(getTaskStarted(t), "2026-05-01 Fri 08:30", "reopen: preserves first :STARTED: timestamp");
  applyStatusTransition(t, "DONE", "2026-05-01 Fri 09:20");
  assertEqual(t.closed, "2026-05-01 Fri 09:20", "re-close: writes a fresh CLOSED timestamp");
  assertEqual(t.logbookLines.slice(1), [
    "- State \"DONE\" from \"STARTED\" [2026-05-01 Fri 09:00]",
    "- State \"STARTED\" from \"DONE\" [2026-05-01 Fri 09:10]",
    "- State \"DONE\" from \"STARTED\" [2026-05-01 Fri 09:20]",
  ], "re-close: LOGBOOK remains append-only and ordered");
  const out = serializeTasks([t]);
  assertContains(out, "CLOSED: [2026-05-01 Fri 09:20]", "re-close: serializer emits fresh CLOSED");
  assertContains(out, "- State \"STARTED\" from \"DONE\" [2026-05-01 Fri 09:10]", "reopen: historical LOGBOOK entry preserved");
}

// ── WAITING -> DONE close path ───────────────────────────────────────

{
  const t = task("WAITING");
  applyStatusTransition(t, "DONE", "2026-05-01 Fri 10:00");
  assertEqual(t.closed, "2026-05-01 Fri 10:00", "WAITING -> DONE: CLOSED timestamp is written");
  assertEqual(t.logbookLines.at(-1), "- State \"DONE\" from \"WAITING\" [2026-05-01 Fri 10:00]",
    "WAITING -> DONE: appends correct LOGBOOK transition");
}

// ── Parsed LOGBOOK + transition appends to lifecycle drawer ─────────

{
  const { tasks } = parseTasks([
    "** STARTED Parsed task",
    ":PROPERTIES:",
    ":ID: 22222222-3333-4444-8555-666666666666",
    ":STARTED: [2026-05-01 Fri 08:30]",
    ":END:",
    ":LOGBOOK:",
    "- Created [2026-05-01 Fri 08:00]",
    "- State \"STARTED\" from \"TODO\" [2026-05-01 Fri 08:30]",
    ":END:",
    "Body.",
    "",
  ].join("\n"));
  const parsed = tasks[0]!;
  applyStatusTransition(parsed, "DONE", "2026-05-01 Fri 11:00");
  const out = serializeTasks(tasks);
  assertContains(out,
    ":LOGBOOK:\n- Created [2026-05-01 Fri 08:00]\n- State \"STARTED\" from \"TODO\" [2026-05-01 Fri 08:30]\n- State \"DONE\" from \"STARTED\" [2026-05-01 Fri 11:00]",
    "parsed LOGBOOK: transition appends inside existing drawer");
  assertContains(out, "Body.", "parsed LOGBOOK: body remains description text");
}

console.log(`\n# ${passed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);

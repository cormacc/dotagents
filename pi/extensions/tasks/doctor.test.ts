#!/usr/bin/env tsx
/**
 * `/tasks doctor` engine tests.
 *
 * Each block constructs a small org file, parses it, calls `runDoctor`,
 * and asserts which findings appear (or don't). Run via `./test.sh`.
 */

import { parseTasks, type Task } from "./parser.ts";
import {
  formatFindingLine,
  formatFindingsReport,
  runDoctor,
  type Finding,
  type FindingCode,
} from "./doctor.ts";

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

/** Count findings of a given code. */
function count(findings: Finding[], code: FindingCode): number {
  return findings.filter((f) => f.code === code).length;
}

// ── No findings on a clean graph ──────────────────────────────────────

{
  const input = [
    "* TODO Healthy",
    ":PROPERTIES:",
    ":ID: 11111111-2222-4333-8444-555555555555",
    ":END:",
    "",
    "* DONE Closed",
    "CLOSED: [2026-04-25 Sat 12:00]",
    ":PROPERTIES:",
    ":ID: 22222222-2222-4333-8444-555555555555",
    ":END:",
    "",
  ].join("\n");
  const { tasks } = parseTasks(input);
  const findings = runDoctor({ tasks, selectedId: null });
  assertEqual(findings.length, 0, "clean graph: no findings");
  assertContains(formatFindingsReport(findings), "no issues found",
    "formatFindingsReport: friendly message when 0 findings");
}

// ── duplicate-id ──────────────────────────────────────────────────────

{
  const input = [
    "* TODO First",
    ":PROPERTIES:",
    ":ID: dupe-aaaa-bbbb-cccc-dddddddddddd",
    ":END:",
    "",
    "* TODO Second",
    ":PROPERTIES:",
    ":ID: dupe-aaaa-bbbb-cccc-dddddddddddd",
    ":END:",
    "",
  ].join("\n");
  const { tasks } = parseTasks(input);
  const findings = runDoctor({ tasks, selectedId: null });
  assertEqual(count(findings, "duplicate-id"), 2,
    "duplicate-id: one finding per occurrence (including the first)");
  assertEqual(findings.every((f) => f.severity === "error"), true,
    "duplicate-id: severity is error");
}

// ── broken-import ─────────────────────────────────────────────────────

{
  // Synthesize a task with importError set (without going through loader).
  const tasks: Task[] = [{
    level: 1,
    status: "TODO",
    priority: null,
    summary: "Has bad import",
    tags: [],
    description: "",
    children: [],
    propertyLines: [":ID: import-aaaa-bbbb-cccc-dddddddddddd"],
    logbookLines: [],
    importPath: "design/log/missing.org",
    importRaw: "[[file:design/log/missing.org]]",
    importError: "ENOENT: no such file or directory",
    importChildren: [],
    closed: null,
    sourcePath: "/tmp/TASKS.org",
    lineNumber: 1,
    endLine: 5,
  }];
  const findings = runDoctor({ tasks, selectedId: null });
  assertEqual(count(findings, "broken-import"), 1,
    "broken-import: one finding when importError is set");
  const f = findings.find((x) => x.code === "broken-import")!;
  assertEqual(f.severity, "error", "broken-import: severity is error");
  assertEqual(f.location.file, "/tmp/TASKS.org", "broken-import: location.file set");
  assertEqual(f.location.line, 1, "broken-import: location.line set");
}

// ── selected-not-found ────────────────────────────────────────────────

{
  const input = [
    "* TODO Solo",
    ":PROPERTIES:",
    ":ID: aaaa1111-2222-4333-8444-555555555555",
    ":END:",
    "",
  ].join("\n");
  const { tasks } = parseTasks(input);
  const findings = runDoctor({
    tasks,
    selectedId: "ghost-aaaa-bbbb-cccc-dddddddddddd",
    selectedSourcePath: "/tmp/TASKS.local.org",
  });
  assertEqual(count(findings, "selected-not-found"), 1,
    "selected-not-found: one finding when SELECTED uuid is unknown");
  const f = findings.find((x) => x.code === "selected-not-found")!;
  assertEqual(f.location.file, "/tmp/TASKS.local.org",
    "selected-not-found: location reports TASKS.local.org");
}

{
  // Selected found: no finding.
  const input = [
    "* TODO Solo",
    ":PROPERTIES:",
    ":ID: bbbb1111-2222-4333-8444-555555555555",
    ":END:",
    "",
  ].join("\n");
  const { tasks } = parseTasks(input);
  const findings = runDoctor({
    tasks,
    selectedId: "bbbb1111-2222-4333-8444-555555555555",
  });
  assertEqual(count(findings, "selected-not-found"), 0,
    "selected-not-found: no finding when SELECTED uuid resolves");
}

// ── waiting-without-blocker ───────────────────────────────────────────

{
  const input = [
    "* WAITING Bare wait",
    ":PROPERTIES:",
    ":ID: cccc1111-2222-4333-8444-555555555555",
    ":END:",
    "",
    "* WAITING With blocker",
    ":PROPERTIES:",
    ":ID: dddd1111-2222-4333-8444-555555555555",
    ":BLOCKED-BY: url:https://example.com",
    ":END:",
    "",
  ].join("\n");
  const { tasks } = parseTasks(input);
  const findings = runDoctor({ tasks, selectedId: null });
  assertEqual(count(findings, "waiting-without-blocker"), 1,
    "waiting-without-blocker: one finding for the bare WAITING");
  const f = findings.find((x) => x.code === "waiting-without-blocker")!;
  assertEqual(f.severity, "warn",
    "waiting-without-blocker: severity is warn");
}

// ── closed-without-timestamp ──────────────────────────────────────────

{
  // Build a DONE task missing the CLOSED: cache (impossible via parser
  // without manual override since serializer always restores it; we
  // synthesize the task directly).
  const tasks: Task[] = [{
    level: 1,
    status: "DONE",
    priority: null,
    summary: "No CLOSED line",
    tags: [],
    description: "",
    children: [],
    propertyLines: [":ID: closed-aaaa-bbbb-cccc-dddddddddddd"],
    logbookLines: [],
    importPath: null,
    importRaw: null,
    importError: null,
    closed: null,
    sourcePath: "/tmp/TASKS.org",
    lineNumber: 1,
    endLine: 5,
  }];
  const findings = runDoctor({ tasks, selectedId: null });
  assertEqual(count(findings, "closed-without-timestamp"), 1,
    "closed-without-timestamp: one finding for DONE missing CLOSED");
}

{
  // CANCELLED with CLOSED set → no finding.
  const input = [
    "* CANCELLED Done",
    "CLOSED: [2026-04-25 Sat 12:00]",
    ":PROPERTIES:",
    ":ID: aaa11111-2222-4333-8444-555555555555",
    ":END:",
    "",
  ].join("\n");
  const { tasks } = parseTasks(input);
  const findings = runDoctor({ tasks, selectedId: null });
  assertEqual(count(findings, "closed-without-timestamp"), 0,
    "closed-without-timestamp: no finding when CLOSED present");
}

// ── stale-parent-status ───────────────────────────────────────────────

{
  // Parent is TODO; child is STARTED → flag the parent.
  const input = [
    "* TODO Parent",
    ":PROPERTIES:",
    ":ID: parent11-2222-4333-8444-555555555555",
    ":END:",
    "** STARTED Child",
    ":PROPERTIES:",
    ":ID: childaa1-2222-4333-8444-555555555555",
    ":END:",
    "",
  ].join("\n");
  const { tasks } = parseTasks(input);
  const findings = runDoctor({ tasks, selectedId: null });
  assertEqual(count(findings, "stale-parent-status"), 1,
    "stale-parent-status: parent TODO + child STARTED");
}

{
  // Parent and only child both TODO → no finding.
  const input = [
    "* TODO Parent",
    ":PROPERTIES:",
    ":ID: parent22-2222-4333-8444-555555555555",
    ":END:",
    "** TODO Child",
    ":PROPERTIES:",
    ":ID: childbb1-2222-4333-8444-555555555555",
    ":END:",
    "",
  ].join("\n");
  const { tasks } = parseTasks(input);
  const findings = runDoctor({ tasks, selectedId: null });
  assertEqual(count(findings, "stale-parent-status"), 0,
    "stale-parent-status: no finding when all children also TODO");
}

// ── invalid-task-blocker ──────────────────────────────────────────────

{
  const input = [
    "* TODO Refers to ghost",
    ":PROPERTIES:",
    ":ID: ghost001-2222-4333-8444-555555555555",
    ":BLOCKED-BY: task:does-not-exist-anywhere",
    ":BLOCKED-BY+: url:https://example.com",
    ":END:",
    "",
  ].join("\n");
  const { tasks } = parseTasks(input);
  const findings = runDoctor({ tasks, selectedId: null });
  assertEqual(count(findings, "invalid-task-blocker"), 1,
    "invalid-task-blocker: only the task:<UUID> blocker is flagged");
  const f = findings.find((x) => x.code === "invalid-task-blocker")!;
  assertEqual(f.severity, "error",
    "invalid-task-blocker: severity is error");
}

{
  // Resolved task: blocker → no finding.
  const input = [
    "* DONE Real dep",
    ":PROPERTIES:",
    ":ID: realdep1-2222-4333-8444-555555555555",
    ":END:",
    "* TODO Gated",
    ":PROPERTIES:",
    ":ID: gated001-2222-4333-8444-555555555555",
    ":BLOCKED-BY: task:realdep1-2222-4333-8444-555555555555",
    ":END:",
    "",
  ].join("\n");
  const { tasks } = parseTasks(input);
  const findings = runDoctor({ tasks, selectedId: null });
  assertEqual(count(findings, "invalid-task-blocker"), 0,
    "invalid-task-blocker: no finding when task: blocker resolves");
}

// ── formatFindingLine / formatFindingsReport ──────────────────────────

{
  const f: Finding = {
    code: "duplicate-id",
    severity: "error",
    message: "Duplicate :ID: foo (2 occurrences)",
    location: { file: "/tmp/TASKS.org", line: 7, heading: "Some task" },
  };
  const line = formatFindingLine(f);
  assertContains(line, "[ERROR]", "formatFindingLine: severity prefix");
  assertContains(line, "duplicate-id", "formatFindingLine: code");
  assertContains(line, "/tmp/TASKS.org:7", "formatFindingLine: file:line");
}

{
  const findings: Finding[] = [
    {
      code: "duplicate-id",
      severity: "error",
      message: "x",
      location: { file: "/a", line: 1 },
    },
    {
      code: "waiting-without-blocker",
      severity: "warn",
      message: "y",
      location: { file: "/b", line: 2 },
    },
  ];
  const report = formatFindingsReport(findings);
  assertContains(report, "2 findings", "formatFindingsReport: total count");
  // Errors group should appear before warns, by canonical order.
  const dupIdx = report.indexOf("duplicate-id");
  const warnIdx = report.indexOf("waiting-without-blocker");
  assertEqual(dupIdx < warnIdx && dupIdx >= 0, true,
    "formatFindingsReport: error groups precede warn groups");
}

console.log(`\n# ${passed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);

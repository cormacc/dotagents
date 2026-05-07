#!/usr/bin/env tsx
/** Scenario-style regression tests for org-memory reconstruction. */

import {
  getTaskBlockers,
  getTaskId,
  isTaskReady,
  parseSelectedKeyword,
  parseTasks,
  serializeTasks,
  type Task,
} from "./parser.ts";
import { runDoctor } from "./doctor.ts";

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

function* walk(tasks: readonly Task[]): Generator<Task> {
  for (const task of tasks) {
    yield task;
    yield* walk(task.children);
    if (task.importChildren) yield* walk(task.importChildren);
  }
}

function findTaskById(tasks: readonly Task[], id: string | null): Task | null {
  if (!id) return null;
  for (const task of walk(tasks)) {
    if (getTaskId(task) === id) return task;
  }
  return null;
}

const parentId = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee";
const selectedId = "bbbbbbbb-cccc-4ddd-8eee-ffffffffffff";
const blockerId = "cccccccc-dddd-4eee-8fff-000000000000";

const tasksContent = [
  "* Improvements",
  "** STARTED Review org-memory protocol",
  ":PROPERTIES:",
  `:ID: ${parentId}`,
  ":CREATED: [2026-05-01 Fri 08:51]",
  ":STARTED: [2026-05-01 Fri 09:41]",
  ":END:",
  ":LOGBOOK:",
  "- Created [2026-05-01 Fri 08:51]",
  "- State \"STARTED\" from \"TODO\" [2026-05-01 Fri 09:41]",
  ":END:",
  "#+IMPORT: [[file:design/log/org-memory.org]]",
  "Migrated subtasks:",
  "- STARTED Add regression coverage",
  "** DONE Prerequisite blocker",
  "CLOSED: [2026-05-01 Fri 10:00]",
  ":PROPERTIES:",
  `:ID: ${blockerId}`,
  ":CREATED: [2026-05-01 Fri 08:00]",
  ":END:",
  "",
].join("\n");

const planContent = [
  "#+TITLE: Review org-memory protocol as agent memory",
  "#+PARENT_ID: " + parentId,
  "",
  "* Context",
  "This plan records why org-memory must reconstruct selected work across sessions.",
  "",
  "* Plan",
  "** STARTED Add regression coverage :memory:tests:",
  ":PROPERTIES:",
  `:ID: ${selectedId}`,
  ":CREATED: [2026-05-01 Fri 09:41]",
  ":STARTED: [2026-05-01 Fri 09:41]",
  `:BLOCKED-BY: task:${blockerId}`,
  ":END:",
  ":LOGBOOK:",
  "- Created [2026-05-01 Fri 09:41]",
  "- State \"STARTED\" from \"TODO\" [2026-05-01 Fri 09:41]",
  ":END:",
  "Acceptance criteria:",
  "- Scenario reconstructs selected task, context, implementation notes, and blockers.",
  "",
  "* Implementation",
  "Implementation notes survive as durable resume context.",
  "",
  "* Open questions",
  "** OPEN Should agent-memory scenarios become end-to-end UI tests?",
  "",
].join("\n");

const localContent = `#+SELECTED: ${selectedId}\n`;

const { tasks } = parseTasks(tasksContent, { sourcePath: "/repo/TASKS.org" });
const { tasks: planTasks } = parseTasks(planContent, { sourcePath: "/repo/design/log/org-memory.org" });
tasks[0]!.importChildren = planTasks;

const selected = findTaskById(tasks, parseSelectedKeyword(localContent));
assertEqual(selected?.summary, "Add regression coverage", "memory scenario: selected plan task resolves through imported change-record");
assertContains(planContent, "This plan records why org-memory must reconstruct", "memory scenario: plan context is available for reconstruction");
assertContains(planContent, "Implementation notes survive as durable resume context", "memory scenario: implementation notes are available for reconstruction");
assertEqual(getTaskBlockers(selected!).map((b) => b.raw), [`task:${blockerId}`], "memory scenario: selected task blockers are surfaced");
assertEqual(
  isTaskReady(selected!, (id) => findTaskById(tasks, id)).ready,
  true,
  "memory scenario: task blocker resolves against loaded graph",
);

const findings = runDoctor({ tasks, selectedId, selectedSourcePath: "/repo/TASKS.local.org" });
assertEqual(findings.filter((f) => f.code === "duplicate-id").length, 0,
  "memory scenario: migrated plan task has no duplicate ID in parent TASKS.org subtree");
assertEqual(findings.filter((f) => f.code === "selected-not-found").length, 0,
  "memory scenario: selected task is present in loaded graph");

const serialized = serializeTasks(tasks);
assertContains(serialized, "#+IMPORT: [[file:design/log/org-memory.org]]", "memory scenario: import link form round-trips");
assertContains(serialized, "- STARTED Add regression coverage", "memory scenario: parent retains plain-text migrated-subtask summary");
assertEqual(serialized.includes("*** STARTED Add regression coverage"), false,
  "memory scenario: migrated subtask is not duplicated as TASKS.org child heading");

console.log(`\n# ${passed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);

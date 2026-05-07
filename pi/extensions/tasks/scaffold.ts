/**
 * Change-record scaffolding helpers.
 *
 * Kept in a standalone module (no pi-tui / pi-coding-agent dependency) so
 * the snapshot test in `parser.test.ts` can import these functions
 * directly via `tsx`. The output of `scaffoldPlan()` is the canonical
 * skeleton documented in the `org-plan` skill. Plan creation lives
 * exclusively on the agent-harness side; the Emacs `tasks-org` mode is
 * read/edit/reorganise only.
 */

import {
  type Task,
  formatOrgDate,
  getTaskId,
  serializeTasks,
} from "./parser.ts";

/** Scaffold a minimal change-record body consistent with the `org-plan` skill. */
export function scaffoldPlan(task: Task, planTasks: Task[] = []): string {
  const parentId = getTaskId(task);
  const content = [
    `#+TITLE: ${task.summary}`,
    `#+DATE: ${formatOrgDate()}`,
    parentId ? `#+PARENT_ID: ${parentId}` : null,
    "#+TODO: TODO(t) STARTED(s) WAITING(w) | DONE(d) CANCELLED(c)",
    "",
    "* Context",
    "",
    "* Plan",
    "",
    "* Implementation",
    "",
    "",
  ].filter((line): line is string => line !== null).join("\n");
  return insertTasksIntoPlanSection(content, planTasks);
}

/**
 * Insert plan-task headings into the `* Plan` section of CONTENT.
 * If `* Plan` is missing, append it. If TASKS is empty, return CONTENT
 * unchanged.
 */
export function insertTasksIntoPlanSection(content: string, tasks: Task[]): string {
  if (tasks.length === 0) return content;

  const block = serializeTasks(tasks).trimEnd();
  const blockLines = block.split("\n");
  const normalized = content.replace(/\n*$/, "\n");
  const lines = normalized.split("\n");
  const planIdx = lines.findIndex((line) => /^\*\s+Plan\s*$/.test(line));

  if (planIdx === -1) {
    return `${normalized.trimEnd()}\n\n* Plan\n${block}\n`;
  }

  let insertIdx = lines.length - 1;
  for (let i = planIdx + 1; i < lines.length; i++) {
    if (/^\*\s+\S/.test(lines[i] ?? "")) {
      insertIdx = i;
      break;
    }
  }

  const insertLines: string[] = [];
  if (insertIdx > 0 && lines[insertIdx - 1] !== "") insertLines.push("");
  insertLines.push(...blockLines);
  if ((lines[insertIdx] ?? "") !== "") insertLines.push("");

  lines.splice(insertIdx, 0, ...insertLines);
  return lines.join("\n").replace(/\n*$/, "\n");
}

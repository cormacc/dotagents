/**
 * Change-record scaffolding helpers.
 *
 * Kept in a standalone module (no pi-tui / pi-coding-agent dependency) so
 * the snapshot test in `parser.test.ts` can import these functions
 * directly via `tsx`. The output of `scaffoldPlan()` is the minimal
 * extension scaffold for a change-record; the `org-plan` skill owns the
 * richer human-facing section conventions and optional summary subsections.
 * Plan creation lives exclusively on the agent-harness side.
 */

import {
  type Task,
  formatOrgDate,
  getTaskId,
  serializeTasks,
} from "./parser.ts";

export interface ScaffoldPlanOptions {
  /** Whether the parent task already lives in TASKS.archive.org. */
  archived?: boolean;
  /** Path from the plan file's directory to the repository task setup file. */
  setupFileRelPath?: string;
}

function safeOrgLinkDescription(summary: string): string | null {
  const trimmed = summary.trim();
  if (!trimmed || /[\[\]\r\n]/.test(trimmed)) return null;
  return trimmed;
}

function parentLink(kind: "task" | "archive", parentId: string, summary: string): string {
  const target = `${kind}:${parentId}`;
  const description = safeOrgLinkDescription(summary);
  return description ? `[[${target}][${description}]]` : `[[${target}]]`;
}

/** Scaffold the minimal extension-owned change-record body. */
export function scaffoldPlan(
  task: Task,
  optionsOrPlanTasks: ScaffoldPlanOptions | Task[] = {},
  maybePlanTasks: Task[] = [],
): string {
  const parentId = getTaskId(task);
  const options = Array.isArray(optionsOrPlanTasks) ? {} : optionsOrPlanTasks;
  const planTasks = Array.isArray(optionsOrPlanTasks) ? optionsOrPlanTasks : maybePlanTasks;
  const parentKind = options.archived ? "archive" : "task";
  const setupFileRelPath = options.setupFileRelPath ?? "../../TASKS.setup.org";
  // The minimal skeleton emits only the sections required on every
  // change-record per `skills/org-plan/SKILL.md`: * Summary, * Plan,
  // * Implementation. * Context is optional and is added by the agent
  // (between * Summary and * Plan) only when durable rationale exceeds
  // what * Summary can carry.
  const content = [
    `#+TITLE: ${task.summary}`,
    `#+DATE: ${formatOrgDate()}`,
    parentId ? `#+PARENT: ${parentLink(parentKind, parentId, task.summary)}` : null,
    `#+SETUPFILE: ${setupFileRelPath}`,
    "",
    "* Summary",
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

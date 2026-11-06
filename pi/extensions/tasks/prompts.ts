import type { ExtensionAPI, ExtensionContext } from "@earendil-works/pi-coding-agent";
import { dirname, join, relative } from "node:path";
import { ensureEmacsServer } from "../emacsclient/emacsclient.ts";
import { expandOrgLinkTarget, getTaskId, type Task } from "./parser.ts";
import { resolveProjectPath } from "./paths.ts";
import { otRecordCreate, otRecordPath } from "./ot.ts";
import type { SummaryRefreshReason } from "./summary.ts";

const TASKS_FILE = "TASKS.org";

export function getEmacsOptions(pi: ExtensionAPI) {
  const env = (globalThis as { [key: string]: unknown })["process"] as
    | { env?: Record<string, string | undefined> }
    | undefined;
  return {
    binary: env?.env?.EMACSCLIENT_BINARY || "emacsclient",
    daemonBinary: env?.env?.EMACS_BINARY || "emacs",
    exec: (cmd: string, args: string[], opts?: { signal?: AbortSignal; timeout?: number }) =>
      pi.exec(cmd, args, {
        signal: opts?.signal,
        timeout: opts?.timeout,
      }),
  };
}

/** Build task-specific context without copying the skills' planning policy. */
export function buildChangeRecordPrompt(
  mode: "proactive" | "retrospective" | "summary",
  task: Task,
  link: string,
  recordPath: string,
  detail?: string,
): string {
  const action = mode === "proactive" ? "Develop a plan" :
    mode === "retrospective" ? "Reconstruct the delivered work from git history" :
    "Refresh the condensed memory layer";
  return [
    `${action} for this task using the \`org-plan\` and \`org-tasks\` skills. Read their current instructions first.`,
    `Task: ${task.status} ${task.summary}`,
    `Change-record link: ${link}`,
    `Change-record file: ${recordPath}`,
    detail,
    "Show the draft for approval before writing. Offer to open the file in Emacs after writing.",
  ].filter(Boolean).join("\n");
}

export function buildSummaryRefreshPrompt(task: Task, link: string, recordPath: string, reason: SummaryRefreshReason): string {
  return buildChangeRecordPrompt("summary", task, link, recordPath,
    reason === "missing" ? "The record has no Summary heading." : "The summary may be stale because the record predates the task start.");
}

interface PlanEditHooks {
  approvePath: (ctx: ExtensionContext, task: Task, suggested: string) => Promise<string | undefined>;
  afterCreate: (ctx: ExtensionContext) => Promise<unknown>;
}

/** Open an imported record in Emacs, or approve a path and delegate creation to ot. */
export async function handlePlanEdit(
  pi: ExtensionAPI,
  ctx: ExtensionContext,
  task: Task,
  mode: "proactive" | "retrospective" = "proactive",
  hooks?: PlanEditHooks,
): Promise<void> {
  const sourcePath = task.sourcePath ?? join(ctx.cwd, TASKS_FILE);
  const sourceDir = dirname(sourcePath);

  // ── Open existing plan ──
  if (task.importPath) {
    const sourceForLinks = task.effectiveSourceContent ?? task.sourceContent;
    const expandedImportPath = expandOrgLinkTarget(task.importPath, sourceForLinks);
    const importBaseDir = expandedImportPath.fromProjectRoot ? ctx.cwd : sourceDir;
    const absPlan = await resolveProjectPath(ctx.cwd, importBaseDir, expandedImportPath.target);
    if (!absPlan) {
      ctx.ui.notify("Plan path resolves outside project root", "error");
      return;
    }
    if (!(await ensureEmacsServer(getEmacsOptions(pi)))) {
      ctx.ui.notify("Could not reach or start Emacs server", "error");
      return;
    }
    pi.events.emit("emacs:open", { file: absPlan, line: 1 });
    return;
  }

  const id = getTaskId(task);
  if (!id) {
    ctx.ui.notify("Cannot create a record without a :CUSTOM_ID:.", "error");
    return;
  }
  const options = { cwd: ctx.cwd };
  try {
    const { suggested } = await otRecordPath(id, options);
    const approved = hooks
      ? await hooks.approvePath(ctx, task, suggested)
      : await ctx.ui.editor(`New plan for: ${task.summary}`, suggested);
    const path = approved?.split(/\r?\n/, 1)[0]?.trim();
    if (!path) return;
    const record = await otRecordCreate(id, path, mode, options);
    await hooks?.afterCreate(ctx);
    ctx.ui.notify(`Change-record ready: ${relative(sourceDir, record.recordPath)}`, "info");
    pi.sendUserMessage(buildChangeRecordPrompt(
      mode, task, record.importRaw, record.recordPath,
      JSON.stringify({
        absorbedSubtasks: record.absorbedSubtasks,
        scope: record.scope ? { since: record.scope.since, until: record.scope.until } : null,
      }),
    ), { deliverAs: "followUp" });
  } catch (error) {
    ctx.ui.notify(`Failed to create plan: ${(error as Error).message}`, "error");
  }
}

/**
 * Tasks Extension - track project tasks using org-mode TODO syntax.
 *
 * Reads TASKS.org from the project root and displays tasks in an expandable tree UI.
 *
 * Commands:
 *   /tasks - expand the tasks UI
 *   /tasks new - create a new top-level task
 *
 * Keybindings (via the keybindings extension):
 *   <leader> t t - expand the tasks UI
 *
 * Persistent UI:
 *   When a task UUID is recorded in TASKS.local.org (#+SELECTED: <UUID>), a
 *   compact widget above the editor shows that task plus a few subtasks. It is
 *   refreshed on startup and immediately as the expanded /tasks UI mutates
 *   task status/selection.
 */

import type {
  ExtensionAPI,
  ExtensionContext,
  Theme,
} from "@mariozechner/pi-coding-agent";
import {
  Input,
  truncateToWidth,
  visibleWidth,
  type Component,
  type Focusable,
  type TUI,
} from "@mariozechner/pi-tui";
import { existsSync, readFileSync, watch, type FSWatcher } from "node:fs";
import { randomUUID } from "node:crypto";
import { access, mkdir, readFile, rename, writeFile } from "node:fs/promises";
import { dirname, isAbsolute, join, relative } from "node:path";
import { homedir } from "node:os";
import { getExtensionName, registerLeaderMenu } from "../lib/pi-utils.ts";
import { ensureEmacsServer } from "../emacsclient/emacsclient.ts";
import { Type } from "@sinclair/typebox";
import { TasksOverlay } from "./overlay.ts";
import { insertTaskIntoFile, type InsertResult } from "./insert.ts";
import {
  appendCreatedLog,
  extractOrgLinkTarget,
  formatOrgTimestamp,
  getFileKeyword,
  getLinkedIssues,
  getTaskId,
  getTaskStarted,
  parseTasks,
  parseSelectedKeyword,
  serializeTasks,
  serializeTasksPreservingFile,
  taskHasId,
  type Task,
} from "./parser.ts";
import { formatFindingsReport, runDoctor } from "./doctor.ts";
import { insertTasksIntoPlanSection, scaffoldPlan } from "./scaffold.ts";
import { resolveProjectPath } from "./paths.ts";
import { colorIssues, colorPriority, colorStatus, colorTags } from "./status-colors.ts";

const EXT_NAME = getExtensionName(import.meta.url);
const TASKS_FILE = "TASKS.org";
/** Gitignored local file that stores per-contributor selection state. */
const TASKS_LOCAL_FILE = "TASKS.local.org";
const TASKS_ARCHIVE_FILE = "TASKS.archive.org";
const DEFAULT_PLANS_DIR = "./design/log";
const DEFAULT_PLAN_DIR_KEYWORD_RE = /^\s*#\+DEFAULT_PLAN_DIR:\s*(.*?)\s*$/im;
const CLOSED_STATUSES = new Set<string>(["DONE", "CANCELLED"]);
/** Hard cap so the compact selected-task widget never dominates the screen. */
const MAX_COMPACT_LINES = 6;
const COMPACT_WIDGET_ID = "tasks:selected";

// ── Tasks-extension user settings ───────────────────────────────────

const TASKS_SETTINGS_PATH = join(homedir(), ".pi", "agent", "tasks-ext.json");

interface TasksSettings {
  /** Default true. When false, status cycle to DONE behaves as it did
      pre-feature — no retrospective change-record path prompt. */
  changeRecordOnDone: boolean;
}

/** Read user settings on demand (cheap; avoids a stale snapshot). */
function loadTasksSettings(): TasksSettings {
  try {
    if (existsSync(TASKS_SETTINGS_PATH)) {
      const parsed = JSON.parse(readFileSync(TASKS_SETTINGS_PATH, "utf-8"));
      return { changeRecordOnDone: parsed?.changeRecordOnDone !== false };
    }
  } catch { /* fall through to defaults */ }
  return { changeRecordOnDone: true };
}

/** Cleanup handle for keybinding suggestions. */
let cleanupKb: (() => void) | null = null;

/** Compact selected-task widget state. */
let compactWidgetComponent: CompactTasksWidget | null = null;
let compactWidgetTui: TUI | null = null;

/**
 * True while the expanded /tasks overlay is open. Suppresses compact-widget
 * creation from the file-watcher path so the watcher does not re-create the
 * widget mid-overlay (which would create visual artifacts or stale state).
 */
let isOverlayActive = false;

/**
 * The currently-rendered TasksOverlay instance, or null when the overlay is
 * not on screen. Used by the file-watcher path to push fresh task data into
 * the live overlay when external changes (e.g. Emacs selection toggle) land
 * while the overlay is open.
 */
let activeOverlayInstance: TasksOverlay | null = null;

/**
 * File-watcher state. The compact selected-task widget refreshes on external edits
 * (e.g. saving TASKS.org or a linked plan file in Emacs) without requiring
 * the /tasks modal to be reopened.
 */
const fileWatchers = new Map<string, FSWatcher>();
let watchDebounceTimer: ReturnType<typeof setTimeout> | null = null;
let activeCtx: ExtensionContext | null = null;

/** Collect every path whose changes should trigger a compact-widget refresh. */
function collectWatchPaths(tasks: Task[], cwd: string): Set<string> {
  const paths = new Set<string>();
  paths.add(join(cwd, TASKS_FILE));
  // Always watch the local selection file so Emacs-originated selection
  // changes are reflected immediately without reopening the overlay.
  paths.add(join(cwd, TASKS_LOCAL_FILE));
  const walk = (ts: Task[]) => {
    for (const t of ts) {
      if (t.sourcePath) paths.add(t.sourcePath);
      walk(t.children);
      if (t.importChildren) walk(t.importChildren);
    }
  };
  walk(tasks);
  return paths;
}

// ── TASKS.local.org read/write ───────────────────────────────────────

/**
 * Read the selected task UUID from TASKS.local.org.
 * Returns null when the file is absent or has no #+SELECTED: keyword.
 */
async function readSelectedId(cwd: string): Promise<string | null> {
  const localPath = join(cwd, TASKS_LOCAL_FILE);
  try {
    const content = await readFile(localPath, "utf-8");
    return parseSelectedKeyword(content);
  } catch {
    return null;
  }
}

/**
 * Write the selected task UUID to TASKS.local.org atomically (write-then-rename).
 * Pass null to deselect - the file is retained with an empty #+SELECTED: keyword
 * so it remains in place (e.g. for version-control ignore-list purposes).
 */
export async function writeSelectedId(
  cwd: string,
  id: string | null,
): Promise<void> {
  const localPath = join(cwd, TASKS_LOCAL_FILE);
  const selectedLine = id ? `#+SELECTED: ${id}` : `#+SELECTED:`;

  // Non-destructive: preserve any task headings already in the file.
  let existing = "";
  try { existing = await readFile(localPath, "utf-8"); } catch { /* new file */ }

  const updated = /^#\+SELECTED:/im.test(existing)
    ? existing.replace(/^#\+SELECTED:.*$/im, selectedLine)
    : (existing ? `${selectedLine}\n${existing}` : `${selectedLine}\n`);

  const tmpPath = `${localPath}.tmp`;
  await writeFile(tmpPath, updated, "utf-8");
  await rename(tmpPath, localPath);
}

/**
 * Find a task by its org :ID: property across the full task graph.
 */
export function findTaskById(tasks: Task[], id: string): Task | null {
  for (const t of tasks) {
    if (getTaskId(t) === id) return t;
    const child = findTaskById(taskChildren(t), id);
    if (child) return child;
  }
  return null;
}

function attachFileWatcher(path: string): void {
  if (fileWatchers.has(path)) return;
  try {
    const watcher = watch(path, (eventType) => {
      scheduleRefresh();
      // Editors that atomically rename-replace the file invalidate this
      // watcher after the first event. Close now and let the next refresh
      // re-attach against the new inode.
      if (eventType === "rename") {
        fileWatchers.get(path)?.close();
        fileWatchers.delete(path);
      }
    });
    watcher.on("error", () => {
      fileWatchers.delete(path);
    });
    fileWatchers.set(path, watcher);
  } catch {
    // File may not exist yet; next refresh will retry.
  }
}

function updateFileWatchers(paths: Set<string>): void {
  for (const [p, w] of fileWatchers) {
    if (!paths.has(p)) {
      w.close();
      fileWatchers.delete(p);
    }
  }
  for (const p of paths) attachFileWatcher(p);
}

function closeAllFileWatchers(): void {
  for (const w of fileWatchers.values()) w.close();
  fileWatchers.clear();
  if (watchDebounceTimer) {
    clearTimeout(watchDebounceTimer);
    watchDebounceTimer = null;
  }
}

function scheduleRefresh(): void {
  if (!activeCtx) return;
  if (watchDebounceTimer) clearTimeout(watchDebounceTimer);
  watchDebounceTimer = setTimeout(() => {
    watchDebounceTimer = null;
    const ctx = activeCtx;
    if (ctx) void refreshTaskUi(ctx, ctx.cwd);
  }, 150);
}

/** Recursively mark a task tree as local (from TASKS.local.org). */
function markLocal(tasks: Task[]): void {
  for (const t of tasks) {
    t.isLocal = true;
    markLocal(t.children);
  }
}

async function loadTasks(cwd: string): Promise<Task[]> {
  const sourcePath = join(cwd, TASKS_FILE);
  let tasks: Task[] = [];
  try {
    const content = await readFile(sourcePath, "utf-8");
    const { tasks: shared, fileImports } = parseTasks(content, { sourcePath });
    for (const fp of fileImports) {
      const absPath = await resolveProjectPath(cwd, dirname(sourcePath), fp);
      if (!absPath) continue;
      try {
        const ic = await readFile(absPath, "utf-8");
        const { tasks: it } = parseTasks(ic, { sourcePath: absPath });
        shared.push(...it);
      } catch { /* ignore missing or unreadable import files */ }
    }
    tasks = shared;
  } catch { /* TASKS.org unreadable — start with empty list */ }

  // Load tasks from TASKS.local.org (gitignored per-contributor drafts).
  // The #+SELECTED: keyword is non-task content and is preserved verbatim.
  const localPath = join(cwd, TASKS_LOCAL_FILE);
  try {
    const localContent = await readFile(localPath, "utf-8");
    const { tasks: localTasks } = parseTasks(localContent, { sourcePath: localPath });
    markLocal(localTasks);
    tasks.push(...localTasks);
  } catch { /* no local tasks file or no task headings in it */ }

  await loadLinkedPlans(tasks, sourcePath, cwd);
  try {
    await backfillMissingIds(cwd, tasks);
  } catch {
    // Keep the UI usable even if the automatic ID backfill cannot write.
  }
  return tasks;
}

async function loadLinkedPlans(
  tasks: Task[],
  sourcePath: string,
  cwd: string,
  cache = new Map<string, { tasks: Task[]; error: string | null }>(),
): Promise<void> {
  const sourceDir = dirname(sourcePath);
  for (const task of tasks) {
    if (task.importPath) {
      const importPath = await resolveProjectPath(cwd, sourceDir, task.importPath);
      if (!importPath) {
        task.importChildren = [];
        task.importError = "Import path resolves outside project root";
      } else {
        const cached = cache.get(importPath);
        if (cached) {
          task.importChildren = cached.tasks;
          task.importError = cached.error;
        } else {
          try {
            const content = await readFile(importPath, "utf-8");
            const { tasks: importTasks, fileImports } = parseTasks(content, { sourcePath: importPath });
            const importDir = dirname(importPath);
            for (const fp of fileImports) {
              const absPath = await resolveProjectPath(cwd, importDir, fp);
              if (!absPath) continue;
              try {
                const nc = await readFile(absPath, "utf-8");
                const { tasks: nt } = parseTasks(nc, { sourcePath: absPath });
                importTasks.push(...nt);
              } catch { /* ignore */ }
            }
            const entry = { tasks: importTasks, error: null };
            cache.set(importPath, entry);
            await loadLinkedPlans(importTasks, importPath, cwd, cache);
            task.importChildren = importTasks;
            task.importError = null;
          } catch (err) {
            const error = (err as Error).message;
            task.importChildren = [];
            task.importError = error;
            cache.set(importPath, { tasks: task.importChildren, error });
          }
        }
      }
    }
    await loadLinkedPlans(task.children, sourcePath, cwd, cache);
  }
}

async function backfillMissingIds(cwd: string, tasks: Task[]): Promise<void> {
  const changedRoots = new Map<string, Task[]>();
  const visit = (taskList: Task[]) => {
    for (const task of taskList) {
      if (!taskHasId(task)) {
        task.propertyLines.unshift(`:ID: ${randomUUID()}`);
        const sourcePath = task.sourcePath ?? join(cwd, TASKS_FILE);
        changedRoots.set(sourcePath, task.sourceRoot ?? tasks);
      }
      visit(task.children);
      if (task.importChildren) visit(task.importChildren);
    }
  };
  visit(tasks);

  await Promise.all(
    [...changedRoots.entries()].map(([path, root]) =>
      writeTaskFilePreserving(path, root),
    ),
  );
}

function taskChildren(task: Task): Task[] {
  return [...task.children, ...(task.importChildren ?? [])];
}

/**
 * Find the selected task by UUID pointer from TASKS.local.org.
 */
export function findSelectedTask(
  tasks: Task[],
  selectedId: string | null = null,
): Task | null {
  return selectedId ? findTaskById(tasks, selectedId) : null;
}

function findTopLevelRoot(tasks: Task[], target: Task): Task | null {
  const contains = (task: Task): boolean => {
    if (task === target) return true;
    return taskChildren(task).some(contains);
  };
  return tasks.find(contains) ?? null;
}

function formatTaskLine(
  t: Task,
  indent: string,
  marker: string,
  width: number,
): string {
  const priority = colorPriority(t.priority);
  const visibleTags = t.tags;
  const tags = colorTags(visibleTags);
  const urlBase = t.sourceContent
    ? getFileKeyword(t.sourceContent, "ISSUE_URL_BASE")
    : null;
  const issues = colorIssues(
    getLinkedIssues(t, urlBase).map((i) => i.label),
  );
  const left = `${indent}${marker}${colorStatus(t.status)} ${priority ? `${priority} ` : ""}${t.summary}`;
  // Suffix = issues + tags (right-aligned). Issues come first so tags
  // remain at the far right where the eye expects them.
  const suffix =
    issues || tags
      ? ` ${issues}${issues && tags ? " " : ""}${tags}`
      : "";
  if (!suffix) return truncateToWidth(left, width);

  const suffixWidth = visibleWidth(suffix);
  const leftWidth = Math.max(0, width - suffixWidth - 1);
  const clippedLeft = truncateToWidth(left, leftWidth);
  const gap = Math.max(1, width - visibleWidth(clippedLeft) - suffixWidth);
  return truncateToWidth(`${clippedLeft}${" ".repeat(gap)}${suffix}`, width);
}

function border(width: number, theme: Theme, fill = "─"): string {
  return theme.fg("border", fill.repeat(Math.max(0, width)));
}

function formatPlanLabel(planPath: string): string {
  if (isAbsolute(planPath) || planPath.startsWith(".")) return planPath;
  return `./${planPath}`;
}

/** Build the compact selected-task widget's pre-styled lines, or undefined if nothing is selected. */
function buildCompactLines(
  tasks: Task[],
  selectedId: string | null,
  theme: Theme,
  width: number,
): string[] | undefined {
  const selected = findSelectedTask(tasks, selectedId);
  if (!selected) return undefined;
  const selectionRoot = findTopLevelRoot(tasks, selected) ?? selected;

  const hasLinkedPlan = !!selectionRoot.importPath &&
    (selectionRoot.importChildren?.length ?? 0) > 0;
  const headerLines = [
    border(width, theme),
    formatTaskLine(
      selectionRoot,
      "",
      selectionRoot === selected ? "★ " : "• ",
      width,
    ),
  ];
  if (hasLinkedPlan) {
    headerLines.push(
      truncateToWidth(
        theme.fg("borderMuted", `  ${formatPlanLabel(selectionRoot.importPath!)}`),
        width,
      ),
    );
  }
  const maxSubtaskLines = Math.max(
    0,
    MAX_COMPACT_LINES - headerLines.length,
  );

  const flattened: { task: Task; depth: number }[] = [];
  const walk = (children: Task[], depth: number) => {
    for (const child of children) {
      flattened.push({ task: child, depth });
      walk(taskChildren(child), depth + 1);
    }
  };
  walk(taskChildren(selectionRoot), 1);

  const visible = [...flattened];
  let hiddenCompleted = 0;

  // If truncation is needed, reclaim space from completed subtasks first,
  // scanning from the head so the compact view favours the selected task's
  // next pending work over old completed history.
  while (
    visible.length + (hiddenCompleted > 0 ? 1 : 0) > maxSubtaskLines
  ) {
    const doneIdx = visible.findIndex((row) => CLOSED_STATUSES.has(row.task.status));
    if (doneIdx === -1) break;
    visible.splice(doneIdx, 1);
    hiddenCompleted++;
  }

  let hiddenMore = 0;
  const completedSummaryLines = hiddenCompleted > 0 ? 1 : 0;
  if (visible.length + completedSummaryLines > maxSubtaskLines) {
    const maxVisibleTasks = Math.max(0, maxSubtaskLines - completedSummaryLines - 1);
    hiddenMore = visible.length - maxVisibleTasks;
    visible.splice(maxVisibleTasks);
  }

  const lines = [...headerLines];
  if (hiddenCompleted > 0) {
    const label = hiddenCompleted === 1 ? "subtask" : "subtasks";
    lines.push(
      theme.fg("dim", `  ... ${hiddenCompleted} completed ${label}`),
    );
  }

  for (const row of visible) {
    lines.push(
      formatTaskLine(
        row.task,
        "  ".repeat(row.depth),
        row.task === selected ? "★ " : "• ",
        width,
      ),
    );
  }

  if (hiddenMore > 0) {
    const label = hiddenMore === 1 ? "subtask" : "subtasks";
    lines.push(theme.fg("dim", `  ... ${hiddenMore} more ${label}`));
  }

  return lines;
}

class CompactTasksWidget implements Component {
  constructor(
    private tasks: Task[],
    private selectedId: string | null,
    private readonly theme: Theme,
  ) {}

  setTasks(tasks: Task[], selectedId: string | null): void {
    this.tasks = tasks;
    this.selectedId = selectedId;
  }

  render(width: number): string[] {
    const lines = buildCompactLines(this.tasks, this.selectedId, this.theme, width) ?? [];
    return lines.map((l) => truncateToWidth(l, width));
  }

  invalidate(): void {}
}

function clearCompactWidget(ctx?: ExtensionContext): void {
  if (ctx?.hasUI) ctx.ui.setWidget(COMPACT_WIDGET_ID, undefined);
  compactWidgetComponent = null;
  compactWidgetTui = null;
}

function syncCompactWidget(
  ctx: ExtensionContext,
  tasks: Task[],
  selectedId: string | null,
  hidden = false,
): void {
  if (!ctx.hasUI) return;
  const hasSelectedTask = findSelectedTask(tasks, selectedId) !== null;
  if (hidden || !hasSelectedTask) {
    clearCompactWidget(ctx);
    return;
  }

  if (compactWidgetComponent) {
    compactWidgetComponent.setTasks(tasks, selectedId);
    compactWidgetTui?.requestRender();
    return;
  }

  ctx.ui.setWidget(COMPACT_WIDGET_ID, (tui, theme) => {
    compactWidgetTui = tui;
    compactWidgetComponent = new CompactTasksWidget(tasks, selectedId, theme);
    return compactWidgetComponent;
  });
}

async function refreshTaskUi(
  ctx: ExtensionContext,
  cwd: string,
): Promise<Task[]> {
  activeCtx = ctx;
  const tasks = await loadTasks(cwd);
  const selectedId = await readSelectedId(cwd);
  if (isOverlayActive) {
    // Push fresh task data into the running overlay so external changes
    // (e.g. Emacs selection toggle) are reflected immediately.
    activeOverlayInstance?.refreshTasks(tasks, selectedId);
  } else {
    syncCompactWidget(ctx, tasks, selectedId);
  }
  updateFileWatchers(collectWatchPaths(tasks, cwd));
  return tasks;
}

// ── tasks_insert_task: cross-extension tool ************************
//
// Registered with pi at extension load (no `session_start` gating)
// because the LLM may invoke it from a non-interactive sub-agent. The
// JS-level `insertTaskIntoFile` helper does the real work; this tool
// is just a typed schema + return-shape adapter.
//
// Cross-extension consumers (e.g. `jira_clone_apply`) should prefer
// importing `insertTaskIntoFile` directly from `./insert.ts` rather
// than round-tripping through the tool registry; the JSON contract
// here exists for the LLM and for ad-hoc agent dispatches.

const InsertTaskParams = Type.Object({
  file: Type.String({
    description:
      "Absolute or cwd-relative path to the org file to insert into. " +
      "Almost always the project's TASKS.org or TASKS.local.org.",
  }),
  section: Type.String({
    description:
      "Level-1 heading text under which the task is appended (e.g. 'Improvements'). " +
      "Tags on the heading line are tolerated.",
  }),
  summary: Type.String({
    description: "Heading text for the new task. Required, non-empty.",
  }),
  priorityName: Type.Optional(Type.String({
    description:
      "Priority name (Jira convention): Highest|High|Medium|Low|Lowest. " +
      "Anything else (or absent) yields no priority cookie.",
  })),
  body: Type.Optional(Type.String({
    description:
      "Body text rendered after the drawer. Plain markdown/text; " +
      "do not embed raw ADF JSON.",
  })),
  linkedIssues: Type.Optional(Type.Array(Type.String(), {
    description:
      "External-tracker references written into :LINKED_ISSUES:. " +
      "Used both for the drawer line *and* for idempotency: a duplicate " +
      "refusal fires if any of these tokens already appears in any " +
      "scanned task's :LINKED_ISSUES:.",
  })),
  labels: Type.Optional(Type.Array(Type.String(), {
    description: "Tracker-side labels rendered as org tags ':l1:l2:'.",
  })),
  parentId: Type.Optional(Type.String({
    description:
      "When set, the new task is rendered as a level-3 subtask. " +
      "The id itself is not embedded \u2014 it just selects the heading level.",
  })),
  allowCreateSection: Type.Optional(Type.Boolean({
    description:
      "When true, missing sections are appended to the file. Default: false (refuse).",
  })),
  alsoScan: Type.Optional(Type.Array(Type.String(), {
    description:
      "Additional org file paths scanned for :LINKED_ISSUES: collisions. " +
      "Imports referenced via #+IMPORT: from any scanned file are walked " +
      "recursively. Pass the sibling file (e.g. TASKS.local.org when " +
      "inserting into TASKS.org) so duplicates are detected regardless " +
      "of which slot they sit in.",
  })),
});

function insertResultToToolReturn(result: InsertResult) {
  switch (result.status) {
    case "inserted":
      return {
        content: [{
          type: "text" as const,
          text:
            `Inserted new task at ${result.file}:${result.line} (id=${result.id}).`,
        }],
        details: result,
      };
    case "duplicate": {
      const existing = result.existingId ?? "(no :ID:)";
      return {
        content: [{
          type: "text" as const,
          text:
            `Duplicate :LINKED_ISSUES: token \`${result.conflictingToken}\` already linked from existing task ${existing} in ${result.existingFile}. No file written.`,
        }],
        details: result,
        isError: true,
      };
    }
    case "section_not_found":
      return {
        content: [{
          type: "text" as const,
          text:
            `Section '${result.section}' not found in ${result.file}. Pass allowCreateSection: true to scaffold it, or correct the section name.`,
        }],
        details: result,
        isError: true,
      };
    case "error":
      return {
        content: [{ type: "text" as const, text: `Error: ${result.message}` }],
        details: result,
        isError: true,
      };
  }
}

function registerInsertTaskTool(pi: ExtensionAPI): void {
  pi.registerTool({
    name: "tasks_insert_task",
    label: "Tasks: insert task",
    description:
      "Insert a new TODO task into a project's TASKS.org (or sibling) under a " +
      "named section. Performs deterministic org rendering (priority cookie, " +
      "UUID, :CREATED: timestamp, :LINKED_ISSUES: drawer, label tag suffix) so " +
      "callers never assemble org-mode strings themselves. Refuses with a " +
      "structured 'duplicate' error when any supplied :LINKED_ISSUES: token " +
      "already appears in TASKS.org / TASKS.local.org / their imports.",
    promptSnippet:
      "Insert a new TASKS.org task with auto-generated :ID:, :CREATED:, drawer + tags",
    promptGuidelines: [
      "Use tasks_insert_task whenever you would otherwise hand-assemble an org task heading + drawer; prefer it over `edit` for inserts so duplicate :LINKED_ISSUES: tokens are caught and priority/UUID/timestamp formatting stays consistent.",
    ],
    parameters: InsertTaskParams,
    async execute(_toolCallId, params, _signal, _onUpdate, ctx) {
      const result = await insertTaskIntoFile({
        file: params.file,
        projectRoot: ctx.cwd,
        section: params.section,
        summary: params.summary,
        priorityName: params.priorityName ?? null,
        body: params.body ?? null,
        linkedIssues: params.linkedIssues ?? null,
        labels: params.labels ?? null,
        parentId: params.parentId ?? null,
        allowCreateSection: params.allowCreateSection ?? false,
        alsoScan: params.alsoScan ?? [],
      });
      return insertResultToToolReturn(result);
    },
  });
}

export default function (pi: ExtensionAPI) {
  registerInsertTaskTool(pi);

  // ── Keybinding suggestions + startup compact widget restore ─────────

  pi.on("session_start", async (_ev, ctx) => {
    cleanupKb = registerLeaderMenu(pi, EXT_NAME, {
      globalMenu: {
        items: {
          t: {
            label: "+tasks",
            items: {
              t: { label: "Show tasks", action: "tasks:show" },
              n: { label: "New task", action: "tasks:new" },
              d: { label: "Doctor (health check)", action: "tasks:doctor" },
            },
          },
        },
      },
    });

    await refreshTaskUi(ctx, ctx.cwd);
  });

  pi.on("session_shutdown", async () => {
    cleanupKb?.();
    cleanupKb = null;
    closeAllFileWatchers();
    clearCompactWidget(activeCtx ?? undefined);
    activeCtx = null;
  });

  // ── /tasks command ──────────────────────────────────────────────────

  pi.registerCommand("tasks", {
    description: "Show project tasks from TASKS.org",
    getArgumentCompletions: (prefix: string) => {
      const items = [
        {
          value: "new",
          label: "new",
          description: "Create a new top-level task",
        },
        {
          value: "doctor",
          label: "doctor",
          description: "Run health checks against the task graph",
        },
      ];
      const filtered = items.filter((i) => i.value.startsWith(prefix));
      return filtered.length > 0 ? filtered : null;
    },
    handler: async (args, ctx) => {
      emitTasksCommand(args, ctx);
    },
  });

  function emitTasksCommand(args: string | undefined, ctx: ExtensionContext): void {
    const arg = (args ?? "").trim();
    const eventName = arg === "new"
      ? "tasks:new"
      : arg === "doctor"
        ? "tasks:doctor"
        : "tasks:show";
    pi.events.emit(eventName, { ctx });
  }

  function eventCtx(data: unknown): ExtensionContext | null {
    const supplied = (data as { ctx?: ExtensionContext } | null)?.ctx;
    return supplied ?? activeCtx;
  }

  pi.events.on("tasks:show", async (data: unknown) => {
    const ctx = eventCtx(data);
    if (!ctx) return;
    await runTasksCommand("", ctx);
  });
  pi.events.on("tasks:new", async (data: unknown) => {
    const ctx = eventCtx(data);
    if (!ctx) return;
    await runTasksCommand("new", ctx);
  });
  pi.events.on("tasks:doctor", async (data: unknown) => {
    const ctx = eventCtx(data);
    if (!ctx) return;
    await runTasksCommand("doctor", ctx);
  });

  async function runTasksCommand(args: string | undefined, ctx: ExtensionContext): Promise<void> {
      if (!ctx.hasUI) {
        ctx.ui.notify("/tasks requires interactive mode", "error");
        return;
      }

      // `/tasks new` - create a new top-level task without opening the overlay.
      if (args?.trim() === "new") {
        const tasks = await loadTasks(ctx.cwd);
        const created = await createTask(ctx, tasks, ctx.cwd, null, null);
        if (created) {
          await refreshTaskUi(ctx, ctx.cwd);
        }
        return;
      }

      // `/tasks doctor` - run health checks against the loaded task graph.
      if (args?.trim() === "doctor") {
        const tasks = await loadTasks(ctx.cwd);
        const selectedId = await readSelectedId(ctx.cwd);
        const findings = runDoctor({
          tasks,
          selectedId,
          selectedSourcePath: join(ctx.cwd, TASKS_LOCAL_FILE),
        });
        const report = formatFindingsReport(findings);
        if (findings.length === 0) {
          ctx.ui.notify(report, "info");
        } else {
          const errors = findings.filter((f) => f.severity === "error").length;
          const kind: "warning" | "error" = errors > 0 ? "error" : "warning";
          ctx.ui.notify(report, kind);
        }
        return;
      }

      type WorkflowRequest =
        | { type: "archive"; task: Task }
        | { type: "changeRecord"; task: Task }
        | { type: "create"; parent: Task | null; insertAfter: Task | null }
        | { type: "edit"; task: Task }
        | { type: "plan"; task: Task }
        | { type: "publish"; task: Task }
        | { type: "unpublish"; task: Task };

      let reopen = true;
      let tasks = await loadTasks(ctx.cwd);
      let selectedId: string | null = await readSelectedId(ctx.cwd);
      while (reopen) {
        isOverlayActive = true;
        clearCompactWidget(ctx);
        const workflow: { request: WorkflowRequest | null } = { request: null };

        const onEdit = (task: Task) => {
          workflow.request = { type: "edit", task };
        };

        const onTasksChanged = (_updatedTasks: Task[]) => {
          // Compact state is intentionally hidden while expanded; refresh after close.
        };

        const onEditPlan = (task: Task) => {
          workflow.request = { type: "plan", task };
        };

        const onArchive = (topLevel: Task) => {
          workflow.request = { type: "archive", task: topLevel };
        };

        const onNewTask = (parent: Task | null, insertAfter: Task | null) => {
          workflow.request = { type: "create", parent, insertAfter };
        };

        const onPublish = (task: Task) => {
          workflow.request = { type: "publish", task };
        };

        const onUnpublish = (task: Task) => {
          workflow.request = { type: "unpublish", task };
        };

        const onCreateChangeRecord = (task: Task): boolean => {
          // Honour the user setting; when disabled, suppress the prompt and
          // let the overlay continue normally.
          if (!loadTasksSettings().changeRecordOnDone) return false;
          workflow.request = { type: "changeRecord", task };
          return true;
        };

        const onSelectionChange = async (newId: string | null) => {
          selectedId = newId;
          await writeSelectedId(ctx.cwd, newId);
          // Explicitly schedule a refresh so the compact widget picks up the
          // change immediately after the overlay closes, and so Emacs-origin
          // changes reflected via the watcher also hit the live overlay.
          scheduleRefresh();
        };

        const onOpenUrls = async (urls: string[]) => {
          // Detect platform once per call. Cross-platform browser-open:
          //   darwin  → `open <url>`
          //   linux   → `xdg-open <url>`
          //   other   → best-effort `xdg-open`
          const proc = (globalThis as { [key: string]: unknown })["process"] as
            | { platform?: string }
            | undefined;
          const cmd = proc?.platform === "darwin" ? "open" : "xdg-open";
          for (const url of urls) {
            try {
              await pi.exec(cmd, [url], { timeout: 5000 });
            } catch (err) {
              ctx.ui.notify(
                `Failed to open ${url}: ${(err as Error).message}`,
                "error",
              );
            }
          }
        };
        const onNotify = (
          message: string,
          kind: "info" | "warn" | "error" = "info",
        ) => {
          ctx.ui.notify(message, kind);
        };

        // Generic event emit so other extensions can react to status
        // cycles (e.g. `jira` for auto-transition). Stays generic — no
        // tracker-specific knowledge in `tasks`.
        const onStatusChanged = (task: Task, prevStatus: string) => {
          const id = getTaskId(task);
          pi.events.emit("tasks:status-changed", {
            id,
            status: task.status,
            prevStatus,
            summary: task.summary,
            closed: task.status === "DONE" || task.status === "CANCELLED",
          });
        };

        await ctx.ui.custom(
          (tui, theme, _kb, done) => {
            const overlay = new TasksOverlay(
              tasks,
              ctx.cwd,
              tui,
              theme,
              done,
              onEdit,
              onTasksChanged,
              onEditPlan,
              onArchive,
              onNewTask,
              onPublish,
              onUnpublish,
              onCreateChangeRecord,
              selectedId,
              onSelectionChange,
              onOpenUrls,
              onNotify,
              onStatusChanged,
            );
            activeOverlayInstance = overlay;
            return overlay;
          },
          {
            overlay: true,
            overlayOptions: {
              width: "100%",
              anchor: "center",
            },
          },
        );
        activeOverlayInstance = null;

        const request = workflow.request as WorkflowRequest | null;
        if (!request) {
          reopen = false;
          continue;
        }

        // The overlay may have refreshed its task tree from disk while it
        // was open (e.g. via the file watcher after a selection write or an
        // Emacs save), so `request.task` can be a reference into a different
        // tree than the outer `tasks` variable. Resolve every stale
        // reference back to the freshly loaded tree by `:ID:` before any
        // mutating workflow runs.
        const resolveStale = (stale: Task | null): Task | null => {
          if (!stale) return null;
          const id = getTaskId(stale);
          if (!id) return null;
          return findTaskById(tasks, id);
        };
        const reloadAndResolve = async (
          stale: Task,
          verb: string,
        ): Promise<Task | null> => {
          tasks = await loadTasks(ctx.cwd);
          const fresh = resolveStale(stale);
          if (!fresh) {
            ctx.ui.notify(
              `Cannot ${verb}: task no longer exists on disk.`,
              "error",
            );
          }
          return fresh;
        };

        if (request.type === "edit") {
          await openTaskInEmacs(pi, ctx, request.task);
          reopen = false;
        } else if (request.type === "plan") {
          await handlePlanEdit(pi, ctx, request.task);
          reopen = false;
        } else if (request.type === "archive") {
          const fresh = await reloadAndResolve(request.task, "archive");
          if (fresh) await archiveTopLevel(ctx, tasks, fresh);
          tasks = await loadTasks(ctx.cwd);
          reopen = true;
        } else if (request.type === "publish") {
          const fresh = await reloadAndResolve(request.task, "publish");
          if (fresh) await publishTask(ctx, tasks, fresh);
          tasks = await loadTasks(ctx.cwd);
          reopen = true;
        } else if (request.type === "unpublish") {
          const fresh = await reloadAndResolve(request.task, "unpublish");
          if (fresh) await unpublishTask(ctx, tasks, fresh);
          tasks = await loadTasks(ctx.cwd);
          reopen = true;
        } else if (request.type === "changeRecord") {
          const fresh = await reloadAndResolve(request.task, "create change-record for");
          if (fresh) await handlePlanEdit(pi, ctx, fresh, "retrospective");
          tasks = await loadTasks(ctx.cwd);
          reopen = true;
        } else if (request.type === "create") {
          // Reload first; then resolve parent/insertAfter against the fresh
          // tree. A stale parent/insertAfter ID that no longer resolves is a
          // soft failure: warn and fall back to a top-level append rather
          // than refuse the create outright.
          tasks = await loadTasks(ctx.cwd);
          const freshParent = resolveStale(request.parent);
          const freshInsertAfter = resolveStale(request.insertAfter);
          if (request.parent && !freshParent) {
            ctx.ui.notify(
              "Parent task no longer exists; appending at top level.",
              "warning",
            );
          }
          await createTask(ctx, tasks, ctx.cwd, freshParent, freshInsertAfter);
          tasks = await loadTasks(ctx.cwd);
          reopen = true;
        }
      }

      // Overlay fully closed. Re-enable compact-widget updates from the
      // file-watcher path.
      isOverlayActive = false;
      // Immediately sync the compact widget from the in-memory task state so
      // the widget reflects any selection changes made inside the overlay
      // without waiting for the async save to hit disk and the watcher to fire.
      syncCompactWidget(ctx, tasks, selectedId);
      // Also reload from disk to re-attach file watchers and converge with any
      // external edits that landed while the overlay was open.
      await refreshTaskUi(ctx, ctx.cwd);
  }
}

// ── Emacs / plan-edit flows ───────────────────────────────────────────

async function openTaskInEmacs(
  pi: ExtensionAPI,
  ctx: ExtensionContext,
  task: Task,
): Promise<void> {
  const filePath = task.sourcePath ?? join(ctx.cwd, TASKS_FILE);
  const ok = await ensureEmacsServer(getEmacsOptions(pi));
  if (!ok) {
    ctx.ui.notify("Could not reach or start Emacs server", "error");
    return;
  }
  pi.events.emit("emacs:open", { file: filePath, line: task.lineNumber });
}

/** Lowercase ASCII slug: letters/digits/hyphens, collapsed, trimmed, ≤ 40 chars. */
function slugify(summary: string): string {
  return summary
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 40)
    .replace(/-+$/, "") || "plan";
}

async function pathExists(p: string): Promise<boolean> {
  try {
    await access(p);
    return true;
  } catch {
    return false;
  }
}

async function writeTaskFilePreserving(path: string, tasks: Task[]): Promise<void> {
  const cachedOriginal = tasks.find((t) => t.sourceContent)?.sourceContent;
  const original = cachedOriginal ?? ((await pathExists(path))
    ? await readFile(path, "utf-8")
    : "");
  const content = original
    ? serializeTasksPreservingFile(original, tasks)
    : serializeTasks(tasks);
  await writeFile(path, content, "utf-8");
}

function getEmacsOptions(pi: ExtensionAPI) {
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

/** Read the project-wide default plan directory from `#+DEFAULT_PLAN_DIR: [[file:...]]`. */
async function readPlansDir(cwd: string): Promise<string> {
  try {
    const content = await readFile(join(cwd, TASKS_FILE), "utf-8");
    const match = DEFAULT_PLAN_DIR_KEYWORD_RE.exec(content);
    if (!match) return DEFAULT_PLANS_DIR;
    return extractOrgLinkTarget(match[1] ?? "") ?? DEFAULT_PLANS_DIR;
  } catch {
    return DEFAULT_PLANS_DIR;
  }
}

function joinPlanDir(dir: string, filename: string): string {
  const trimmed = dir.trim().replace(/\/+$/, "") || ".";
  if (trimmed === "." || trimmed === "./") return `./${filename}`;
  if (trimmed.startsWith("./")) return `./${join(trimmed.slice(2), filename)}`;
  return join(trimmed, filename);
}

/**
 * Suggest a plan path for a task that has no #+IMPORT: yet.
 * Uses `#+DEFAULT_PLAN_DIR: [[file:...]]` from TASKS.org as the plan directory, falling
 * back to `./design/log` when unspecified or malformed.
 */
async function suggestPlanPath(task: Task, cwd: string): Promise<string> {
  const today = new Date().toISOString().slice(0, 10); // YYYY-MM-DD
  const slug = slugify(task.summary);
  const filename = `${today}-${slug}.org`;
  const plansDir = await readPlansDir(cwd);
  return joinPlanDir(plansDir, filename);
}

function taskLabel(task: Task): string {
  const priority = task.priority ? ` [#${task.priority}]` : "";
  const tags = task.tags.length > 0 ? ` :${task.tags.join(":")}:` : "";
  return `${task.status}${priority} ${task.summary}${tags}`;
}

function formatExtractedSubtaskList(tasks: Task[]): string {
  const lines = ["Extracted subtasks moved to linked plan:"];
  const write = (taskList: Task[], depth: number) => {
    for (const task of taskList) {
      lines.push(`${"  ".repeat(depth)}- ${taskLabel(task)}`);
      write(task.children, depth + 1);
    }
  };
  write(tasks, 0);
  return lines.join("\n");
}

function appendExtractedSubtaskList(description: string, tasks: Task[]): string {
  if (tasks.length === 0) return description;
  const existing = description.trimEnd();
  const extracted = formatExtractedSubtaskList(tasks);
  return existing ? `${existing}\n\n${extracted}` : extracted;
}

function cloneTaskForPlan(task: Task, level: number): Task {
  return {
    ...task,
    level,
    tags: [...task.tags],
    propertyLines: [...task.propertyLines],
    logbookLines: [...task.logbookLines],
    children: task.children.map((child) => cloneTaskForPlan(child, level + 1)),
    importChildren: undefined,
    importError: null,
    sourcePath: undefined,
    sourceContent: undefined,
    sourceRoot: undefined,
    lineNumber: 0,
    endLine: 0,
  };
}

function cloneSubtasksForPlan(task: Task): Task[] {
  return task.children.map((child) => cloneTaskForPlan(child, 2));
}

class PrefilledInputPrompt implements Component, Focusable {
  private readonly input = new Input();
  private _focused = false;

  constructor(
    private readonly tui: TUI,
    private readonly theme: Theme,
    private readonly title: string,
    initialValue: string,
    private readonly done: (value: string | undefined) => void,
  ) {
    this.input.setValue(initialValue);
    // Input#setValue preserves the existing cursor position; for a prefilled
    // value, place the cursor at the end so Enter accepts the suggestion and
    // normal editing starts where users expect.
    (this.input as unknown as { cursor: number }).cursor = initialValue.length;
    this.input.onSubmit = (value) => this.done(value);
    this.input.onEscape = () => this.done(undefined);
  }

  get focused(): boolean {
    return this._focused;
  }

  set focused(value: boolean) {
    this._focused = value;
    this.input.focused = value;
  }

  handleInput(data: string): void {
    this.input.handleInput(data);
    this.tui.requestRender();
  }

  render(width: number): string[] {
    const hBar = this.theme.fg("border", "─".repeat(Math.max(0, width)));
    const inputWidth = Math.max(1, width - 2);
    const inputLines = this.input.render(inputWidth).map((line) =>
      truncateToWidth(` ${line}`, width)
    );
    return [
      hBar,
      truncateToWidth(this.theme.fg("accent", ` ${this.theme.bold(this.title)}`), width),
      "",
      ...inputLines,
      "",
      truncateToWidth(this.theme.fg("dim", " Enter submit • Esc cancel"), width),
      hBar,
    ];
  }

  invalidate(): void {
    this.input.invalidate();
  }
}

async function promptForPlanPath(
  ctx: ExtensionContext,
  task: Task,
  suggested: string,
): Promise<string | undefined> {
  return await ctx.ui.custom<string | undefined>(
    (tui, theme, _kb, done) =>
      new PrefilledInputPrompt(
        tui,
        theme,
        `New plan for: ${task.summary}`,
        suggested,
        done,
      ),
    {
      overlay: true,
      overlayOptions: {
        width: "80%",
        minWidth: 40,
        anchor: "center",
      },
    },
  );
}

/**
 * Build the agent prompt that follows change-record scaffolding.
 *
 * Two flows produce the same change-record artefact but differ in what the
 * agent does next:
 *
 * - `proactive`: the user wants to plan up front.  Agent asks scoping
 *   questions, drafts * Context and * Plan, then the user executes.
 * - `retrospective`: the task already closed without a plan.  Agent uses
 *   the task's :STARTED: / :CLOSED: timestamps to scope `git log`, then
 *   drafts * Context and * Implementation from the commit history.
 */
function buildChangeRecordPrompt(
  mode: "proactive" | "retrospective",
  task: Task,
  planRelToSource: string,
  absPlan: string,
  absorbedSubtasks: boolean,
): string {
  if (mode === "retrospective") {
    return buildRetrospectiveChangeRecordPrompt(task, planRelToSource, absPlan);
  }
  return buildProactiveChangeRecordPrompt(
    task, planRelToSource, absPlan, absorbedSubtasks,
  );
}

function buildProactiveChangeRecordPrompt(
  task: Task,
  planRelToSource: string,
  absPlan: string,
  absorbedSubtasks: boolean,
): string {
  return [
    "Develop a linked org change-record for the selected TASKS.org task.",
    "",
    `Task: ${task.status} ${task.priority ? `[#${task.priority}] ` : ""}${task.summary}`,
    `Change-record link: [[file:${planRelToSource}]]`,
    `Change-record file: ${absPlan}`,
    "",
    "The tasks extension has already attached the #+IMPORT: keyword and scaffolded the change-record file.",
    absorbedSubtasks
      ? "Existing TASKS.org subtasks were moved into the linked change-record under * Plan, and the parent task now retains a plain-text summary of the extracted subtasks."
      : "The parent task had no local subtasks to absorb.",
    "",
    "Use the `org-plan` and `org-tasks` skills. Start by asking me any scoping questions needed to develop the plan. Once the plan is agreed, write the final org content to the change-record file above. New `** TODO` plan tasks must include `:ID:` and `:CREATED: [YYYY-MM-DD Day HH:MM]` properties (use `date +'%Y-%m-%d %a %H:%M'` to obtain the timestamp). Prefer tool-driven status changes so `:LOGBOOK:` lifecycle history stays synchronized. After writing it, offer to open the file in Emacs.",
  ].join("\n");
}

function taskCreatedTimestamp(task: Task): string | null {
  for (const line of task.propertyLines) {
    const match = /^\s*:CREATED:\s*\[([^\]]+)\]\s*$/i.exec(line);
    if (match) return match[1]!.trim();
  }
  for (const line of task.logbookLines) {
    const match = /^\s*-\s+Created\s+\[([^\]]+)\]\s*$/i.exec(line);
    if (match) return match[1]!.trim();
  }
  return null;
}

function buildRetrospectiveChangeRecordPrompt(
  task: Task,
  planRelToSource: string,
  absPlan: string,
): string {
  const started = getTaskStarted(task);
  const created = taskCreatedTimestamp(task);
  const lowerBound = started ?? created;
  const closed = task.closed;
  const scopeNote = lowerBound
    ? `Use ${started ? `:STARTED: [${started}]` : `the created timestamp [${created}]`} as the lower bound and CLOSED: [${closed ?? "now"}] as the upper bound for \`git log\`.`
    : "The task has no :STARTED: or created timestamp; fall back to recent commits (for example `-n 20`).";
  return [
    "Generate a retrospective change-record for the just-closed TASKS.org task.",
    "",
    `Task: ${task.status} ${task.priority ? `[#${task.priority}] ` : ""}${task.summary}`,
    `Change-record link: [[file:${planRelToSource}]]`,
    `Change-record file: ${absPlan}`,
    "",
    "The tasks extension has already attached the #+IMPORT: keyword and scaffolded the change-record file with empty * Context, * Plan, and * Implementation sections.",
    "",
    "Steps:",
    `1. ${scopeNote} A reasonable invocation is \`git log --oneline --since="${lowerBound ?? "<fallback>"}" --until="${closed ?? "now"}"\` when a lower bound is available.`,
    "2. Inspect the relevant commits and code changes.",
    "3. Draft the * Context section: a short problem statement (1-2 paragraphs).",
    "4. Draft the * Implementation section: bullet points listing what was changed and why, citing commits where useful. Include any rolled-back attempts or dead-ends if they appear in the history \u2014 the failure record is the most valuable part of a retrospective.",
    "5. Leave * Plan empty unless there were notable steps worth recording retrospectively.",
    "6. Show me the draft for approval, then write the final content to the change-record file. After writing, offer to open it in Emacs.",
  ].join("\n");
}

/**
 * Resolve or create a change-record for the given task, then open it in Emacs.
 *
 * If the task has a `#+IMPORT:` keyword: open that file.
 * Otherwise: prompt for a filename (seeded from the task summary and today's
 * date), scaffold the file, attach `#+IMPORT:` to the task body, save the source
 * org file, then send the agent a prompt to develop content.
 *
 * `mode` selects the agent-prompt body that follows scaffolding:
 * - `proactive` (default): agent helps plan up front.
 * - `retrospective`: task is already closed; agent drafts * Context and
 *   * Implementation from git history.  Triggered by status cycle to DONE
 *   on a task with no existing #+IMPORT: link.
 */
async function handlePlanEdit(
  pi: ExtensionAPI,
  ctx: ExtensionContext,
  task: Task,
  mode: "proactive" | "retrospective" = "proactive",
): Promise<void> {
  const sourcePath = task.sourcePath ?? join(ctx.cwd, TASKS_FILE);
  const sourceDir = dirname(sourcePath);

  // ── Open existing plan ──
  if (task.importPath) {
    const absPlan = await resolveProjectPath(ctx.cwd, sourceDir, task.importPath);
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

  // ── Create new plan ──
  const suggested = await suggestPlanPath(task, ctx.cwd);
  const approved = await promptForPlanPath(ctx, task, suggested);
  if (!approved) return;
  const relPath = approved.split(/\r?\n/, 1)[0]?.trim();
  if (!relPath) return;

  const absPlan = await resolveProjectPath(ctx.cwd, ctx.cwd, relPath);
  if (!absPlan) {
    ctx.ui.notify("Plan path resolves outside project root", "error");
    return;
  }
  const planRelToSource = relative(sourceDir, absPlan);

  const originalChildren = task.children;
  const originalDescription = task.description;
  const origImportPath = task.importPath;
  const origImportRaw = task.importRaw;
  const extractedPlanTasks = cloneSubtasksForPlan(task);

  try {
    await mkdir(dirname(absPlan), { recursive: true });
    if (await pathExists(absPlan)) {
      if (extractedPlanTasks.length > 0) {
        const existing = await readFile(absPlan, "utf-8");
        await writeFile(
          absPlan,
          insertTasksIntoPlanSection(existing, extractedPlanTasks),
          "utf-8",
        );
      }
    } else {
      await writeFile(absPlan, scaffoldPlan(task, extractedPlanTasks), "utf-8");
    }

    // Attach #+IMPORT: to the in-memory task body and save its source file.
    // Write the link form so the keyword is clickable in Emacs (C-c C-o)
    // while remaining parseable by the extension. The parser preserves this
    // raw value on round-trip. If the task already had local subtasks, move
    // those task headings into the new plan and leave a plain-text summary on
    // the parent so TASKS.org stays high-level without losing browse context.
    task.importPath = planRelToSource;
    task.importRaw = `[[file:${planRelToSource}]]`;
    if (originalChildren.length > 0) {
      task.description = appendExtractedSubtaskList(
        originalDescription,
        originalChildren,
      );
      task.children = [];
    }
    const root = task.sourceRoot;
    if (root) {
      await writeTaskFilePreserving(sourcePath, root);
    }
  } catch (err) {
    task.children = originalChildren;
    task.description = originalDescription;
    task.importPath = origImportPath;
    task.importRaw = origImportRaw;
    ctx.ui.notify(
      `Failed to create plan: ${(err as Error).message}`,
      "error",
    );
    return;
  }

  ctx.ui.notify(`Change-record scaffolded: ${planRelToSource}`, "info");
  pi.sendUserMessage(
    buildChangeRecordPrompt(
      mode,
      task,
      planRelToSource,
      absPlan,
      originalChildren.length > 0,
    ),
    { deliverAs: "followUp" },
  );
}

// ── Create task flow ─────────────────────────────────────────────────────

/**
 * Prompt for a title and insert a new task into the live tree, then save.
 *
 * @param parentTask  null → insert at the top level of `tasks`.
 * @param insertAfterTask  null → append; otherwise insert immediately after
 *                         this task within its container (parent's children
 *                         or the top-level array).
 */
async function createTask(
  ctx: ExtensionContext,
  tasks: Task[],
  cwd: string,
  parentTask: Task | null,
  insertAfterTask: Task | null,
): Promise<Task | null> {
  const prompt = parentTask
    ? `Subtask of "${parentTask.summary}"`
    : "New task title";
  const title = await ctx.ui.input(prompt, "");
  if (!title?.trim()) return null;

  // Route to TASKS.local.org when the anchor (parent or sibling) is local.
  const isLocal = !!(parentTask?.isLocal ?? insertAfterTask?.isLocal);
  const targetPath = isLocal ? join(cwd, TASKS_LOCAL_FILE) : join(cwd, TASKS_FILE);
  // The sourceRoot is the slice of `tasks` that belongs to targetPath.
  const targetRoot = isLocal
    ? tasks.filter((t) => t.isLocal)
    : tasks.filter((t) => !t.isLocal);

  const level = parentTask ? parentTask.level + 1 : 1;
  const createdAt = formatOrgTimestamp();
  const newTask: Task = {
    level,
    status: "TODO",
    priority: null,
    summary: title.trim(),
    tags: [],
    description: "",
    children: [],
    propertyLines: [
      `:ID: ${randomUUID()}`,
      `:CREATED: [${createdAt}]`,
    ],
    importPath: null,
    importRaw: null,
    importChildren: undefined,
    isLocal,
    closed: null,
    logbookLines: [],
    sourcePath: targetPath,
    sourceContent: targetRoot.find((t) => t.sourceContent)?.sourceContent,
    sourceRoot: targetRoot,
    lineNumber: 0,
    endLine: 0,
  };

  appendCreatedLog(newTask, createdAt);

  // For top-level inserts, push into `targetRoot` (the filtered slice that
  // gets serialized below). Pushing into `tasks` instead would mutate the
  // combined shared+local array but leave `targetRoot` — the array we
  // actually write — untouched, silently dropping the new task.
  const container: Task[] = parentTask ? parentTask.children : targetRoot;
  if (insertAfterTask) {
    const idx = container.indexOf(insertAfterTask);
    if (idx >= 0) {
      container.splice(idx + 1, 0, newTask);
    } else {
      container.push(newTask);
    }
  } else {
    container.push(newTask);
  }

  try {
    await writeTaskFilePreserving(targetPath, targetRoot);
  } catch (err) {
    ctx.ui.notify(`Failed to save: ${(err as Error).message}`, "error");
    const rollback = container.indexOf(newTask);
    if (rollback >= 0) container.splice(rollback, 1);
    return null;
  }

  ctx.ui.notify(`Created: ${newTask.summary}`, "info");
  return newTask;
}

// ── Archive flow ───────────────────────────────────────────────────────

/**
 * Produce a copy of `task` suitable for the archive.
 * Transferred as-is: own children and #+IMPORT: link are preserved.
 * Runtime-loaded importChildren are stripped; the link remains so the
 * plan file is still reachable from the archive.
 */
function taskForArchive(task: Task): Task {
  return {
    ...task,
    propertyLines: [...task.propertyLines],
    logbookLines: [...task.logbookLines],
    importChildren: undefined,
    importError: null,
    sourceRoot: undefined,
    sourceContent: undefined,
  };
}

const ARCHIVED_PROPERTY_RE = /^\s*:ARCHIVED:\s*\[([^\]]+)\]\s*$/i;
const CLOSED_LOGBOOK_RE = /^\s*-\s+State\s+\"(?:DONE|CANCELLED)\"\s+from\s+\"[^\"]+\"\s+\[([^\]]+)\]\s*$/i;

function archiveSortTimestamp(task: Task): string {
  if (task.closed) return task.closed;
  for (let i = task.logbookLines.length - 1; i >= 0; i--) {
    const match = CLOSED_LOGBOOK_RE.exec(task.logbookLines[i]!);
    if (match) return match[1]!.trim();
  }
  for (const line of task.propertyLines) {
    const match = ARCHIVED_PROPERTY_RE.exec(line);
    if (match) return match[1]!.trim();
  }
  return "9999-12-31 Zzz 23:59";
}

function sortArchivedTasks(tasks: Task[]): Task[] {
  return tasks
    .map((task, index) => ({ task, index }))
    .sort((a, b) => {
      const aStamp = archiveSortTimestamp(a.task);
      const bStamp = archiveSortTimestamp(b.task);
      return aStamp.localeCompare(bStamp) || a.index - b.index;
    })
    .map(({ task }) => task);
}

/**
 * Archive a top-level TASKS.org task to TASKS.archive.org.
 *
 * Rules (per the plan):
 *   - Only CLOSED-state (`DONE`/`CANCELLED`) top-level tasks can be archived.
 *     Other statuses are refused to avoid accidentally archiving active work.
 *   - Confirmation dialog via ctx.ui.confirm.
 *   - The whole subtree is archived as-is. The #+IMPORT: link is preserved;
 *     plan file contents are not inlined.
 *   - An :ARCHIVED: [timestamp] property is added to the top-level heading.
 *   - Archived entries are sorted by CLOSED time, falling back to ARCHIVED
 *     time when CLOSED is absent.
 *
 * Returns true if the task was archived and the in-memory tree mutated.
 */
async function archiveTopLevel(
  ctx: ExtensionContext,
  tasks: Task[],
  topLevel: Task,
): Promise<boolean> {
  if (topLevel.isLocal) {
    ctx.ui.notify(
      `Cannot archive '${topLevel.summary}': local tasks cannot be archived — publish first.`,
      "warning",
    );
    return false;
  }
  if (!CLOSED_STATUSES.has(topLevel.status)) {
    ctx.ui.notify(
      `Cannot archive '${topLevel.summary}': status is ${topLevel.status}, not DONE/CANCELLED.`,
      "warning",
    );
    return false;
  }

  const ok = await ctx.ui.confirm(
    "Archive task?",
    `Move '${topLevel.summary}' and all subtasks to ${TASKS_ARCHIVE_FILE}.`,
  );
  if (!ok) return false;

  const idx = tasks.indexOf(topLevel);
  if (idx === -1) {
    ctx.ui.notify("Task is not a top-level entry; cannot archive.", "error");
    return false;
  }

  // Build the archive copy: transfer task as-is, stamp :ARCHIVED:.
  // Uses CLOSED timestamp if present (fallback: now).
  const archiveCopy = taskForArchive(topLevel);
  const stamp = topLevel.closed ?? formatOrgTimestamp();
  archiveCopy.propertyLines.push(`:ARCHIVED: [${stamp}]`);

  // Mutate the live task tree: remove from top-level.
  tasks.splice(idx, 1);

  const archivePath = join(ctx.cwd, TASKS_ARCHIVE_FILE);
  const tasksPath = join(ctx.cwd, TASKS_FILE);
  try {
    const existing = (await pathExists(archivePath))
      ? await readFile(archivePath, "utf-8")
      : "";
    const archivedTasks = existing.trim() === ""
      ? []
      : parseTasks(existing, { sourcePath: archivePath }).tasks;
    archivedTasks.push(archiveCopy);
    const sortedArchive = sortArchivedTasks(archivedTasks);
    await writeFile(archivePath, serializeTasks(sortedArchive), "utf-8");
    await writeTaskFilePreserving(tasksPath, tasks);
  } catch (err) {
    // Roll back in-memory change so the overlay stays consistent with disk.
    tasks.splice(idx, 0, topLevel);
    ctx.ui.notify(`Archive failed: ${(err as Error).message}`, "error");
    return false;
  }

  ctx.ui.notify(`Archived: ${topLevel.summary}`, "info");
  await refreshTaskUi(ctx, ctx.cwd);
  return true;
}

/**
 * Publish a local task: move it from TASKS.local.org to TASKS.org.
 * The task is appended as a new top-level entry in TASKS.org and removed
 * from TASKS.local.org. Its #+IMPORT: link (if any) is preserved.
 */
async function publishTask(
  ctx: ExtensionContext,
  tasks: Task[],
  task: Task,
): Promise<void> {
  const ok = await ctx.ui.confirm(
    "Publish task?",
    `Move '${task.summary}' to TASKS.org (will be tracked in git).`,
  );
  if (!ok) return;

  const localPath = join(ctx.cwd, TASKS_LOCAL_FILE);
  const sharedPath = join(ctx.cwd, TASKS_FILE);

  // Remove from local task list.
  const localRoot = task.sourceRoot ?? [];
  const localIdx = localRoot.indexOf(task);
  if (localIdx === -1) {
    ctx.ui.notify("Could not locate task in local file.", "error");
    return;
  }
  localRoot.splice(localIdx, 1);

  // Re-home the task into TASKS.org.
  const sharedTasks = tasks.filter((t) => !t.isLocal);
  task.isLocal = false;
  task.lineNumber = 0;
  task.endLine = 0;
  task.sourcePath = sharedPath;
  task.sourceRoot = sharedTasks;
  task.sourceContent = sharedTasks.find((t) => t.sourceContent)?.sourceContent;
  sharedTasks.push(task);

  try {
    await writeTaskFilePreserving(localPath, localRoot);
    await writeTaskFilePreserving(sharedPath, sharedTasks);
    ctx.ui.notify(`Published: ${task.summary}`, "info");
  } catch (err) {
    ctx.ui.notify(`Publish failed: ${(err as Error).message}`, "error");
  }
}

/**
 * Unpublish a top-level shared task: move it from TASKS.org to TASKS.local.org.
 * Restricted to top-level tasks (same constraint as archiving).
 */
async function unpublishTask(
  ctx: ExtensionContext,
  tasks: Task[],
  task: Task,
): Promise<void> {
  const ok = await ctx.ui.confirm(
    "Unpublish task?",
    `Move '${task.summary}' to TASKS.local.org (removes from git tracking).`,
  );
  if (!ok) return;

  const localPath = join(ctx.cwd, TASKS_LOCAL_FILE);
  const sharedPath = join(ctx.cwd, TASKS_FILE);

  // Remove from shared task list.
  const sharedTasks = tasks.filter((t) => !t.isLocal);
  const sharedIdx = sharedTasks.indexOf(task);
  if (sharedIdx === -1) {
    ctx.ui.notify("Could not locate task in TASKS.org.", "error");
    return;
  }
  sharedTasks.splice(sharedIdx, 1);

  // Read existing local content and parse its current task list.
  let localContent = "";
  try { localContent = await readFile(localPath, "utf-8"); } catch { /* new file */ }
  const { tasks: localRoot } = parseTasks(localContent, { sourcePath: localPath });

  // Re-home the task into TASKS.local.org.
  task.isLocal = true;
  task.lineNumber = 0;
  task.endLine = 0;
  task.sourcePath = localPath;
  task.sourceRoot = localRoot;
  task.sourceContent = localContent || undefined;
  markLocal([task]);
  localRoot.push(task);

  try {
    await writeTaskFilePreserving(sharedPath, sharedTasks);
    await writeTaskFilePreserving(localPath, localRoot);
    ctx.ui.notify(`Unpublished: ${task.summary}`, "info");
  } catch (err) {
    ctx.ui.notify(`Unpublish failed: ${(err as Error).message}`, "error");
  }
}

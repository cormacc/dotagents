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
import {
  existsSync,
  readFileSync,
  realpathSync,
  statSync,
  watch,
  type FSWatcher,
} from "node:fs";
import { access, mkdir, readFile, writeFile } from "node:fs/promises";
import { dirname, isAbsolute, join, relative } from "node:path";
import { homedir } from "node:os";
import { ensureEmacsServer } from "../emacsclient/emacsclient.ts";
import { TasksOverlay } from "./overlay.ts";
import {
  otArchiveTask,
  otBackfill,
  otCreateTask,
  otDoctor,
  otList,
  otPublishTask,
  otSelectTask,
  otSetStatus,
  otUnpublishTask,
  type OtSourceContent,
} from "./ot.ts";
import {
  expandOrgLinkTarget,
  getLinkedIssues,
  getTaskId,
  getTaskStarted,
  parseLinkTemplates,
  parseTasks,
  parseSelectedKeyword,
  serializeTasks,
  serializeTasksPreservingFile,
  type Task,
} from "./parser.ts";
import { formatFindingsReport, type Finding } from "./doctor.ts";
import { insertTasksIntoPlanSection, scaffoldPlan } from "./scaffold.ts";
import { isWithinRoot, resolveProjectPath } from "./paths.ts";
import {
  evaluateSummaryRefresh,
  type SummaryRefreshReason,
} from "./summary.ts";
import { colorIssues, colorPriority, colorStatus, colorTags } from "./status-colors.ts";
import { readEffectiveOrgContent } from "./effective.ts";

const TASKS_FILE = "TASKS.org";
/** Gitignored local file that stores per-contributor selection state. */
const TASKS_LOCAL_FILE = "TASKS.local.org";
const TASKS_ARCHIVE_FILE = "TASKS.archive.org";
const TODO_PREAMBLE = "#+TODO: TODO(t) STARTED(s!) WAITING(w@/!) | DONE(d!) CANCELLED(c!)";
const STARTUP_PREAMBLE = "#+STARTUP: logdone logdrawer";
const ARCHIVE_PREAMBLE = "#+ARCHIVE: TASKS.archive.org::* From %s";
const DEFAULT_PLANS_DIR = "./design/log";
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
  /** Default true. When false, suppress the closure-time `* Summary`
      refresh prompt for tasks with an existing #+IMPORT: change-record. */
  summaryOnDone: boolean;
}

/** Read user settings on demand (cheap; avoids a stale snapshot). */
function loadTasksSettings(): TasksSettings {
  try {
    if (existsSync(TASKS_SETTINGS_PATH)) {
      const parsed = JSON.parse(readFileSync(TASKS_SETTINGS_PATH, "utf-8"));
      return {
        changeRecordOnDone: parsed?.changeRecordOnDone !== false,
        summaryOnDone: parsed?.summaryOnDone !== false,
      };
    }
  } catch { /* fall through to defaults */ }
  return { changeRecordOnDone: true, summaryOnDone: true };
}

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
function collectWatchPaths(tasks: Task[], files: Record<string, string>): Set<string> {
  const paths = new Set<string>();
  if (files.tasks) paths.add(files.tasks);
  // Always watch the local selection file so Emacs-originated selection
  // changes are reflected immediately without reopening the overlay.
  if (files.local) paths.add(files.local);
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
async function readSelectedId(localPath: string): Promise<string | null> {
  try {
    const content = await readFile(localPath, "utf-8");
    return parseSelectedKeyword(content);
  } catch {
    return null;
  }
}

/**
 * Write the selected task UUID to TASKS.local.org atomically (write-then-rename).
 * Pass null to deselect: only the #+SELECTED line is removed. Any local task
 * drafts, imports, or other keywords in TASKS.local.org are preserved.
 */
export async function writeSelectedId(
  cwd: string,
  id: string | null,
): Promise<void> {
  await otSelectTask(id, { cwd });
}

/**
 * Find a task by its org :CUSTOM_ID: property across the full task graph.
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

interface OtWireLinkedIssue {
  rawToken: string;
  label: string;
  url?: string | null;
  error?: string;
}

type OtWireSources = Record<string, OtSourceContent>;

interface OtWireTask {
  id: string | null;
  status: string;
  priority: string | null;
  summary: string;
  description?: string | null;
  tags?: string[];
  level: number;
  propertyLines?: string[];
  logbookLines?: string[];
  sourcePath?: string;
  sourceContent?: string;
  effectiveSourceContent?: string;
  line?: number;
  endLine?: number;
  local?: boolean;
  importPath?: string | null;
  importRaw?: string | null;
  importError?: string | null;
  closed?: string | null;
  linkedIssues?: OtWireLinkedIssue[];
  children?: OtWireTask[];
  importChildren?: OtWireTask[];
}

function wireTaskToTask(wire: OtWireTask, sources: OtWireSources = {}): Task {
  const source = wire.sourcePath ? sources[wire.sourcePath] : undefined;
  const task: Task = {
    level: wire.level,
    status: wire.status,
    priority: wire.priority ?? null,
    summary: wire.summary,
    tags: wire.tags ?? [],
    description: wire.description ?? "",
    children: (wire.children ?? []).map((child) => wireTaskToTask(child, sources)),
    propertyLines: wire.propertyLines ?? (wire.id ? [`:CUSTOM_ID: ${wire.id}`] : []),
    logbookLines: wire.logbookLines ?? [],
    isLocal: !!wire.local,
    importPath: wire.importPath ?? null,
    importRaw: wire.importRaw ?? null,
    importChildren: (wire.importChildren ?? []).map((child) => wireTaskToTask(child, sources)),
    importError: wire.importError ?? null,
    closed: wire.closed ?? null,
    sourcePath: wire.sourcePath,
    sourceContent: wire.sourceContent ?? source?.sourceContent,
    effectiveSourceContent: wire.effectiveSourceContent ?? source?.effectiveSourceContent,
    sourceRoot: undefined,
    lineNumber: wire.line ?? 0,
    endLine: wire.endLine ?? 0,
    linkedIssues: wire.linkedIssues?.map((i) => ({
      rawToken: i.rawToken,
      label: i.label,
      url: i.url ?? null,
      error: i.error,
    })),
  };
  return task;
}

function assignSourceRoots(tasks: Task[]): void {
  const rootsBySource = new Map<string, Task[]>();

  const registerRootArray = (rootTasks: Task[]) => {
    const grouped = new Map<string, Task[]>();
    for (const task of rootTasks) {
      if (!task.sourcePath) continue;
      const group = grouped.get(task.sourcePath) ?? [];
      if (!grouped.has(task.sourcePath)) grouped.set(task.sourcePath, group);
      group.push(task);
    }
    for (const [sourcePath, root] of grouped) {
      rootsBySource.set(sourcePath, root);
    }
    for (const task of rootTasks) {
      for (const child of task.children) registerImportedRoots(child);
      if (task.importChildren && task.importChildren.length > 0) {
        registerRootArray(task.importChildren);
      }
    }
  };

  const registerImportedRoots = (task: Task) => {
    for (const child of task.children) registerImportedRoots(child);
    if (task.importChildren && task.importChildren.length > 0) {
      registerRootArray(task.importChildren);
    }
  };

  const assign = (task: Task) => {
    if (task.sourcePath) {
      task.sourceRoot = rootsBySource.get(task.sourcePath) ?? task.sourceRoot;
    }
    for (const child of task.children) assign(child);
    for (const child of task.importChildren ?? []) assign(child);
  };

  registerRootArray(tasks);
  for (const task of tasks) assign(task);
}

interface LoadedTasks {
  tasks: Task[];
  root: string;
  files: Record<string, string>;
}

async function loadTasks(cwd: string): Promise<LoadedTasks> {
  try {
    await otBackfill({ cwd });
  } catch {
    // Keep the UI usable even if automatic metadata backfill cannot write.
  }
  const result = await otList<OtWireTask>({ cwd });
  const sources = result.sources ?? {};
  const tasks = result.tree.map((wire) => wireTaskToTask(wire, sources));
  assignSourceRoots(tasks);
  return { tasks, root: result.root, files: result.files };
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
  const issues = colorIssues(
    getLinkedIssues(t, t.effectiveSourceContent ?? t.sourceContent ?? "").map((i) => i.label),
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
  tasksPath: string | null,
  theme: Theme,
  width: number,
): string[] | undefined {
  const selected = findSelectedTask(tasks, selectedId);
  if (!selected) return undefined;
  const selectionRoot = findTopLevelRoot(tasks, selected) ?? selected;

  const hasLinkedPlan = !!selectionRoot.importPath &&
    (selectionRoot.importChildren?.length ?? 0) > 0;
  const headerLines = [border(width, theme)];
  if (tasksPath) {
    headerLines.push(truncateToWidth(theme.fg("borderMuted", `  ${tasksPath}`), width));
  }
  headerLines.push(
    formatTaskLine(
      selectionRoot,
      "",
      selectionRoot === selected ? "★ " : "• ",
      width,
    ),
  );
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
    private tasksPath: string | null,
    private readonly theme: Theme,
  ) {}

  setTasks(tasks: Task[], selectedId: string | null, tasksPath: string | null): void {
    this.tasks = tasks;
    this.selectedId = selectedId;
    this.tasksPath = tasksPath;
  }

  render(width: number): string[] {
    const lines = buildCompactLines(this.tasks, this.selectedId, this.tasksPath, this.theme, width) ?? [];
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
  tasksPath: string | null,
  hidden = false,
): void {
  if (!ctx.hasUI) return;
  const hasSelectedTask = findSelectedTask(tasks, selectedId) !== null;
  if (hidden || !hasSelectedTask) {
    clearCompactWidget(ctx);
    return;
  }

  if (compactWidgetComponent) {
    compactWidgetComponent.setTasks(tasks, selectedId, tasksPath);
    compactWidgetTui?.requestRender();
    return;
  }

  ctx.ui.setWidget(COMPACT_WIDGET_ID, (tui, theme) => {
    compactWidgetTui = tui;
    compactWidgetComponent = new CompactTasksWidget(tasks, selectedId, tasksPath, theme);
    return compactWidgetComponent;
  });
}

async function refreshTaskUi(
  ctx: ExtensionContext,
  cwd: string,
): Promise<Task[]> {
  activeCtx = ctx;
  const loaded = await loadTasks(cwd);
  const { tasks, files } = loaded;
  const selectedId = await readSelectedId(files.local);
  if (isOverlayActive) {
    // Push fresh task data into the running overlay so external changes
    // (e.g. Emacs selection toggle) are reflected immediately.
    activeOverlayInstance?.refreshTasks(tasks, selectedId, files.tasks);
  } else {
    syncCompactWidget(ctx, tasks, selectedId, files.tasks);
  }
  updateFileWatchers(collectWatchPaths(tasks, files));
  return tasks;
}

export default function (pi: ExtensionAPI) {
  // ── Keyboard shortcut: Alt+T opens the tasks UI ──────────────────────
  //
  // `Alt+T` is mnemonic ("T" for tasks) and free of pi's built-in
  // keybindings. macOS users may need their terminal configured to send
  // Alt as Meta (e.g. iTerm2: "Use Option as Meta"; kitty: `macos_option_as_alt`).
  // Override via `~/.pi/agent/keybindings.json` for a different chord.

  pi.registerShortcut("alt+t", {
    description: "Show tasks (TASKS.org)",
    handler: (ctx) => {
      pi.events.emit("tasks:show", { ctx });
    },
  });

  // ── Startup compact widget restore ──────────────────────────────────

  pi.on("session_start", async (_ev, ctx) => {
    await refreshTaskUi(ctx, ctx.cwd);
  });

  // Track `pi.events.on` unsubscribers so we can detach on session_shutdown.
  //
  // Why: pi's shared event bus (resource-loader.js → loadExtensions(eventBus))
  // is *not* cleared on /reload. The extension factory runs again, appending a
  // fresh listener per channel each time. Without explicit cleanup, after N
  // reloads `tasks:show` has N+1 listeners → one Alt+T press fans out to N+1
  // `ctx.ui.custom` calls → user has to press Esc N+1 times to dismiss the
  // stacked overlays. Per-extension registrations (shortcuts, commands,
  // `pi.on(...)` handlers) live on the discarded Extension instance and
  // don't leak; only `pi.events` subscriptions do.
  const eventUnsubs: Array<() => void> = [];

  pi.on("session_shutdown", async () => {
    closeAllFileWatchers();
    clearCompactWidget(activeCtx ?? undefined);
    activeCtx = null;
    while (eventUnsubs.length > 0) {
      const off = eventUnsubs.pop();
      try {
        off?.();
      } catch {
        // Best-effort cleanup; ignore individual unsubscribe failures so a
        // single bad listener can't strand the rest.
      }
    }
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

  eventUnsubs.push(
    pi.events.on("tasks:show", async (data: unknown) => {
      const ctx = eventCtx(data);
      if (!ctx) return;
      await runTasksCommand("", ctx);
    }),
  );
  eventUnsubs.push(
    pi.events.on("tasks:new", async (data: unknown) => {
      const ctx = eventCtx(data);
      if (!ctx) return;
      await runTasksCommand("new", ctx);
    }),
  );
  eventUnsubs.push(
    pi.events.on("tasks:doctor", async (data: unknown) => {
      const ctx = eventCtx(data);
      if (!ctx) return;
      await runTasksCommand("doctor", ctx);
    }),
  );

  async function runTasksCommand(args: string | undefined, ctx: ExtensionContext): Promise<void> {
      if (!ctx.hasUI) {
        ctx.ui.notify("/tasks requires interactive mode", "error");
        return;
      }

      // `/tasks new` - create a new top-level task without opening the overlay.
      if (args?.trim() === "new") {
        const loaded = await loadTasks(ctx.cwd);
        const created = await createTask(ctx, loaded.tasks, ctx.cwd, null, null);
        if (created) {
          await refreshTaskUi(ctx, ctx.cwd);
        }
        return;
      }

      // `/tasks doctor` - delegate protocol health checks to `ot doctor`.
      if (args?.trim() === "doctor") {
        try {
          const result = await otDoctor<Finding>({ cwd: ctx.cwd });
          const findings = result.findings;
          const report = formatFindingsReport(findings);
          if (findings.length === 0) {
            ctx.ui.notify(report, "info");
          } else {
            const kind: "warning" | "error" = result.counts.error > 0 ? "error" : "warning";
            ctx.ui.notify(report, kind);
          }
        } catch (err) {
          ctx.ui.notify((err as Error).message, "error");
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
        | { type: "summaryRefresh"; task: Task; reason: SummaryRefreshReason; absPlan: string }
        | { type: "unpublish"; task: Task };

      let reopen = true;
      let loaded = await loadTasks(ctx.cwd);
      let tasks = loaded.tasks;
      let selectedId: string | null = await readSelectedId(loaded.files.local);
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

        // Detect missing/stale `* Summary` synchronously so we only close
        // the overlay when there is actually a workflow to run. Any failure
        // to resolve / read the change-record file is a soft skip — the
        // overlay continues normally and the close persists as before.
        const onRefreshSummary = (task: Task): boolean => {
          if (!loadTasksSettings().summaryOnDone) return false;
          if (!task.importPath || !task.sourcePath) return false;
          const sourceForLinks = task.effectiveSourceContent ?? task.sourceContent;
          const expandedImportPath = expandOrgLinkTarget(task.importPath, sourceForLinks);
          const baseDir = expandedImportPath.fromProjectRoot ? ctx.cwd : dirname(task.sourcePath);
          let absPlan: string;
          try {
            absPlan = isAbsolute(expandedImportPath.target)
              ? expandedImportPath.target
              : join(baseDir, expandedImportPath.target);
          } catch {
            return false;
          }
          // Sandbox: refuse anything outside the project root after symlink
          // resolution. Mirrors resolveProjectPath() without async because
          // this callback must decide synchronously whether to close the UI.
          try {
            const rootReal = realpathSync(ctx.cwd);
            absPlan = realpathSync(absPlan);
            if (!isWithinRoot(absPlan, rootReal)) return false;
          } catch {
            return false;
          }
          let content: string;
          let mtimeMs: number | null;
          try {
            content = readFileSync(absPlan, "utf-8");
            mtimeMs = statSync(absPlan).mtimeMs;
          } catch {
            return false;
          }
          const reason = evaluateSummaryRefresh(
            content,
            getTaskStarted(task),
            mtimeMs,
          );
          if (reason === null) return false;
          workflow.request = { type: "summaryRefresh", task, reason, absPlan };
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
        const onStatusChanged = async (
          task: Task,
          prevStatus: string,
          nextStatus: string,
        ): Promise<Task[]> => {
          const id = getTaskId(task);
          if (!id) {
            throw new Error("Task has no :CUSTOM_ID:; cannot change status.");
          }
          const result = await otSetStatus(id, nextStatus, { cwd: ctx.cwd });
          pi.events.emit("tasks:status-changed", {
            id,
            status: result.status,
            prevStatus: result.prevStatus ?? prevStatus,
            summary: result.task.summary,
            closed: result.status === "DONE" || result.status === "CANCELLED",
          });
          return (await loadTasks(ctx.cwd)).tasks;
        };

        await ctx.ui.custom(
          (tui, theme, _kb, done) => {
            const overlay = new TasksOverlay(
              tasks,
              ctx.cwd,
              loaded.files.tasks,
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
              onRefreshSummary,
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
        // reference back to the freshly loaded tree by `:CUSTOM_ID:` before any
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
          loaded = await loadTasks(ctx.cwd);
          tasks = loaded.tasks;
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
          loaded = await loadTasks(ctx.cwd);
          tasks = loaded.tasks;
          reopen = true;
        } else if (request.type === "publish") {
          const fresh = await reloadAndResolve(request.task, "publish");
          if (fresh) await publishTask(ctx, tasks, fresh);
          loaded = await loadTasks(ctx.cwd);
          tasks = loaded.tasks;
          reopen = true;
        } else if (request.type === "unpublish") {
          const fresh = await reloadAndResolve(request.task, "unpublish");
          if (fresh) await unpublishTask(ctx, tasks, fresh);
          loaded = await loadTasks(ctx.cwd);
          tasks = loaded.tasks;
          reopen = true;
        } else if (request.type === "changeRecord") {
          const fresh = await reloadAndResolve(request.task, "create change-record for");
          if (fresh) await handlePlanEdit(pi, ctx, fresh, "retrospective");
          loaded = await loadTasks(ctx.cwd);
          tasks = loaded.tasks;
          reopen = true;
        } else if (request.type === "summaryRefresh") {
          const fresh = await reloadAndResolve(request.task, "refresh summary for");
          if (fresh) {
            const planRel = fresh.importPath ?? request.absPlan;
            ctx.ui.notify(
              request.reason === "missing"
                ? `Change-record lacks * Summary: ${planRel}`
                : `Change-record * Summary may be stale: ${planRel}`,
              "info",
            );
            pi.sendUserMessage(
              buildSummaryRefreshPrompt(
                fresh,
                planRel,
                request.absPlan,
                request.reason,
              ),
              { deliverAs: "followUp" },
            );
          }
          loaded = await loadTasks(ctx.cwd);
          tasks = loaded.tasks;
          reopen = false;
        } else if (request.type === "create") {
          // Reload first; then resolve parent/insertAfter against the fresh
          // tree. A stale parent/insertAfter ID that no longer resolves is a
          // soft failure: warn and fall back to a top-level append rather
          // than refuse the create outright.
          loaded = await loadTasks(ctx.cwd);
          tasks = loaded.tasks;
          const freshParent = resolveStale(request.parent);
          const freshInsertAfter = resolveStale(request.insertAfter);
          if (request.parent && !freshParent) {
            ctx.ui.notify(
              "Parent task no longer exists; appending at top level.",
              "warning",
            );
          }
          await createTask(ctx, tasks, ctx.cwd, freshParent, freshInsertAfter);
          loaded = await loadTasks(ctx.cwd);
          tasks = loaded.tasks;
          reopen = true;
        }
      }

      // Overlay fully closed. Re-enable compact-widget updates from the
      // file-watcher path.
      isOverlayActive = false;
      // Immediately sync the compact widget from the in-memory task state so
      // the widget reflects any selection changes made inside the overlay
      // without waiting for the async save to hit disk and the watcher to fire.
      syncCompactWidget(ctx, tasks, selectedId, loaded.files.tasks);
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

function defaultSetupFileContent(): string {
  return [
    TODO_PREAMBLE,
    STARTUP_PREAMBLE,
    "#+LINK: jira https://your-org.atlassian.net/browse/%s",
    "#+JIRA_CLOUDID: 00000000-0000-4000-8000-000000000000",
    "#+JIRA_PROJECT:",
    "#+LINK: plan file:design/log/%s",
    "# Default task/archive link targets assume plan files live under design/log/.",
    "# TASKS.org and TASKS.archive.org declare local overrides before #+SETUPFILE:",
    "# so org-mode resolves task links correctly from the task files themselves.",
    "#+LINK: task file:../../TASKS.org::#%s",
    "#+LINK: archive file:../../TASKS.archive.org::#%s",
    "",
  ].join("\n");
}

function defaultTaskLinkOverrides(): string[] {
  return [
    "#+LINK: task file:TASKS.org::#%s",
    "#+LINK: archive file:TASKS.archive.org::#%s",
  ];
}

function defaultLocalFileContent(): string {
  return [
    "#+SELECTED:",
    "",
    "# Per-checkout overrides for shared TASKS.setup.org defaults.",
    "# TASKS.org loads this file via #+SETUPFILE before TASKS.setup.org, so",
    "# any #+KEYWORD declared here wins over the shared default (org-mode",
    "# resolves the first-declared occurrence).",
    "",
  ].join("\n");
}

function defaultOrgPreamble(path: string): string {
  if (path.endsWith(TASKS_FILE)) {
    return [
      "#+TITLE: Project Tasks",
      ...defaultTaskLinkOverrides(),
      "#+SETUPFILE: ./TASKS.local.org",
      "#+SETUPFILE: ./TASKS.setup.org",
      ARCHIVE_PREAMBLE,
      "",
    ].join("\n");
  }
  if (path.endsWith(TASKS_ARCHIVE_FILE)) {
    return [
      "#+TITLE: Archived Tasks",
      ...defaultTaskLinkOverrides(),
      "#+SETUPFILE: ./TASKS.local.org",
      "#+SETUPFILE: ./TASKS.setup.org",
      "",
    ].join("\n");
  }
  return "";
}

async function writeTaskFilePreserving(path: string, tasks: Task[]): Promise<void> {
  if (path.endsWith(TASKS_FILE) || path.endsWith(TASKS_ARCHIVE_FILE)) {
    const setupPath = join(dirname(path), "TASKS.setup.org");
    if (!(await pathExists(setupPath))) {
      await writeFile(setupPath, defaultSetupFileContent(), "utf-8");
    }
    // Ensure TASKS.local.org exists so the #+SETUPFILE chain does not warn
    // on missing-file. The file stays gitignored and may carry per-checkout
    // #+SELECTED, #+JIRA_CLOUDID overrides, etc.
    const localPath = join(dirname(path), TASKS_LOCAL_FILE);
    if (!(await pathExists(localPath))) {
      await writeFile(localPath, defaultLocalFileContent(), "utf-8");
    }
  }
  const cachedOriginal = tasks.find((t) => t.sourceContent)?.sourceContent;
  const original = cachedOriginal ?? ((await pathExists(path))
    ? await readFile(path, "utf-8")
    : "");
  const content = original
    ? serializeTasksPreservingFile(original, tasks)
    : `${defaultOrgPreamble(path)}${serializeTasks(tasks)}`;
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

/**
 * Project-root-relative plan directory derived from a `#+LINK: plan ...`
 * template. Only `file:`-prefixed templates are honoured — a URL-shaped
 * `plan` template (e.g. `https://.../%s`) wouldn't yield a meaningful local
 * scaffold path, so we fall back to the default rather than emit a
 * URL-shaped suggestion the user has to delete.
 */
function planDirFromTemplate(template: string | undefined): string {
  if (!template) return DEFAULT_PLANS_DIR;
  if (!template.startsWith("file:")) return DEFAULT_PLANS_DIR;
  const stripped = template.slice("file:".length);
  const beforePlaceholder = stripped.includes("%s")
    ? stripped.slice(0, stripped.indexOf("%s"))
    : stripped;
  const trimmed = beforePlaceholder.replace(/\/+$/, "");
  return trimmed || DEFAULT_PLANS_DIR;
}

/**
 * Read the project-wide default plan directory from `#+LINK: plan file:.../%s`.
 * Resolution follows `TASKS.org` and one level of `#+SETUPFILE:`; this is the
 * canonical place for the abbreviation so we do not also probe
 * `TASKS.local.org` (intentional — shared config does not belong in a
 * gitignored file).
 */
async function readPlansDir(cwd: string): Promise<string> {
  try {
    const tasksPath = join(cwd, TASKS_FILE);
    const content = await readFile(tasksPath, "utf-8");
    const effective = await readEffectiveOrgContent(cwd, tasksPath, content);
    return planDirFromTemplate(parseLinkTemplates(effective).get("plan"));
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

function importLinkForPlanPath(cwd: string, sourceDir: string, absPlan: string, plansDir: string): { path: string; raw: string } {
  const planRoot = isAbsolute(plansDir) ? plansDir : join(cwd, plansDir);
  const planSuffix = relative(planRoot, absPlan).replace(/\\/g, "/");
  if (planSuffix && !planSuffix.startsWith("..") && !isAbsolute(planSuffix)) {
    const path = `plan:${planSuffix}`;
    return { path, raw: `[[${path}]]` };
  }
  const filePath = relative(sourceDir, absPlan).replace(/\\/g, "/");
  return { path: filePath, raw: `[[file:${filePath}]]` };
}

/**
 * Suggest a plan path for a task that has no #+IMPORT: yet.
 * Uses `#+LINK: plan file:.../%s` from TASKS.setup.org/TASKS.org as the plan template, falling back to `./design/log` when unspecified or malformed.
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
    effectiveSourceContent: undefined,
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
 *   questions, drafts * Summary and * Plan, promoting * Context only when
 *   rationale warrants it, then the user executes.
 * - `retrospective`: the task already closed without a plan.  Agent uses
 *   the task's :STARTED: / :CLOSED: timestamps to scope `git log`, then
 *   drafts * Summary and * Implementation, promoting * Context only when
 *   rationale warrants it.
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

function displayChangeRecordLink(target: string): string {
  if (target.startsWith("[[")) return target;
  if (/^[A-Za-z][A-Za-z0-9+.-]*:.+/.test(target) && !target.startsWith("file:")) return `[[${target}]]`;
  return `[[file:${target}]]`;
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
    `Change-record link: ${displayChangeRecordLink(planRelToSource)}`,
    `Change-record file: ${absPlan}`,
    "",
    "The tasks extension has already attached the #+IMPORT: keyword and scaffolded the change-record file.",
    absorbedSubtasks
      ? "Existing TASKS.org subtasks were moved into the linked change-record under * Plan, and the parent task now retains a plain-text summary of the extracted subtasks."
      : "The parent task had no local subtasks to absorb.",
    "",
    "Use the `org-plan` and `org-tasks` skills. Start by asking me any scoping questions needed to develop the plan. Once the plan is agreed, write the final org content to the change-record file above. The scaffold ships the required sections (`* Summary`, `* Plan`, `* Implementation`); promote `* Context` to a top-level section between `* Summary` and `* Plan` only when durable rationale exceeds what `* Summary` can carry. New `** TODO` plan tasks must include `:CUSTOM_ID:` and `:CREATED: [YYYY-MM-DD Day HH:MM]` properties (use `date +'%Y-%m-%d %a %H:%M'` to obtain the timestamp). Prefer tool-driven status changes so `:LOGBOOK:` lifecycle history stays synchronized. After writing it, offer to open the file in Emacs.",
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

/**
 * Build the prompt the agent receives when a top-level task closes
 * and its linked change-record either lacks `* Summary` or has not
 * been touched since the parent task started. The prompt asks the
 * agent to author or refresh the condensed memory layer per the
 * `org-plan` skill's *Closure-time summary refresh* section.
 */
function buildSummaryRefreshPrompt(
  task: Task,
  planRelToSource: string,
  absPlan: string,
  reason: SummaryRefreshReason,
): string {
  const reasonNote = reason === "missing"
    ? "The change-record has no `* Summary` heading."
    : "The change-record has a `* Summary` heading but has not been touched since the parent task started, so the summary may be stale.";
  return [
    "Refresh the condensed memory layer for the just-closed TASKS.org task.",
    "",
    `Task: ${task.status} ${task.priority ? `[#${task.priority}] ` : ""}${task.summary}`,
    `Change-record link: ${displayChangeRecordLink(planRelToSource)}`,
    `Change-record file: ${absPlan}`,
    "",
    reasonNote,
    "",
    "Use the `org-plan` and `org-tasks` skills. Generate or refresh the change-record's `* Summary` so a future agent can rebuild context cheaply:",
    "",
    "1. Read the change-record's existing sections (`* Plan`, `* Implementation`, and `* Context` if present).",
    "2. Place `* Summary` at the top of the change-record (the first top-level section). Use a one-paragraph synopsis followed by the conventional subsections (`** Decisions`, `** Shipped`, `** Gotchas`, `** Follow-ups`); include only the subsections that carry content. Evidentiary material (commands run, test counts) belongs in the sibling top-level `* Validation` section, not under `* Summary`.",
    "3. Keep the summary terse: it is the surface a future agent reads first, not a duplicate of the implementation ledger.",
    "4. Leave `* Context` alone if it already exists. If it does not exist, do NOT add an empty one — `* Context` is optional and is included only when durable rationale materially exceeds what `* Summary` can carry.",
    "5. Preserve `:CUSTOM_ID:`, `#+PARENT:`, LOGBOOK history, and the existing `* Implementation` audit detail.",
    "6. Show me the draft, then write the final content to the change-record file. Offer to open the file in Emacs after writing.",
  ].join("\n");
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
    `Change-record link: ${displayChangeRecordLink(planRelToSource)}`,
    `Change-record file: ${absPlan}`,
    "",
    "The tasks extension has already attached the #+IMPORT: keyword and scaffolded the change-record file with empty * Summary, * Plan, and * Implementation sections.",
    "",
    "Steps:",
    `1. ${scopeNote} A reasonable invocation is \`git log --oneline --since="${lowerBound ?? "<fallback>"}" --until="${closed ?? "now"}"\` when a lower bound is available.`,
    "2. Inspect the relevant commits and code changes.",
    "3. Draft the * Summary section: a one-paragraph synopsis of what shipped and why, plus any of `** Decisions`, `** Shipped`, `** Gotchas`, `** Follow-ups` subsections that carry content. Evidentiary material (commands run, test counts) belongs in the sibling top-level `* Validation` section, not under `* Summary`.",
    "4. Draft the * Implementation section: bullet points listing what was changed and why, citing commits where useful. Include any rolled-back attempts or dead-ends if they appear in the history \u2014 the failure record is the most valuable part of a retrospective.",
    "5. Promote `* Context` to a top-level section between `* Summary` and `* Plan` ONLY if durable rationale (background, alternatives, scope) materially exceeds what `* Summary` can carry. For typical retrospective records, omit `* Context` entirely.",
    "6. Leave * Plan empty unless there were notable steps worth recording retrospectively.",
    "7. Show me the draft for approval, then write the final content to the change-record file. After writing, offer to open it in Emacs.",
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
 * - `retrospective`: task is already closed; agent drafts * Summary and
 *   * Implementation from git history, promoting * Context only when
 *   rationale warrants it. Triggered by status cycle to DONE on a task with
 *   no existing #+IMPORT: link.
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
  const planRelToSource = relative(sourceDir, absPlan).replace(/\\/g, "/");
  const plansDir = await readPlansDir(ctx.cwd);
  const planImport = importLinkForPlanPath(ctx.cwd, sourceDir, absPlan, plansDir);

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
      const planDir = dirname(absPlan);
      const setupPath = join(ctx.cwd, "TASKS.setup.org");
      if (!(await pathExists(setupPath))) {
        await writeFile(setupPath, defaultSetupFileContent(), "utf-8");
      }
      const setupFileRelPath = relative(planDir, setupPath).replace(/\\/g, "/");
      await writeFile(
        absPlan,
        scaffoldPlan(task, { setupFileRelPath }, extractedPlanTasks),
        "utf-8",
      );
    }

    // Attach #+IMPORT: to the in-memory task body and save its source file.
    // Write the link form so the keyword is clickable in Emacs (C-c C-o)
    // while remaining parseable by the extension. The parser preserves this
    // raw value on round-trip. If the task already had local subtasks, move
    // those task headings into the new plan and leave a plain-text summary on
    // the parent so TASKS.org stays high-level without losing browse context.
    task.importPath = planImport.path;
    task.importRaw = planImport.raw;
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
      planImport.path,
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

  // Route to the cursor's source file. Default TASKS.local.org uses
  // `--local`; imported/change-record files use `--tasks <sourcePath>`.
  const anchor = parentTask ?? insertAfterTask;
  const anchorSourcePath = anchor?.sourcePath;
  const isLocal = !!(parentTask?.isLocal ?? insertAfterTask?.isLocal);
  const parentId = parentTask ? getTaskId(parentTask) : null;
  const afterId = insertAfterTask ? getTaskId(insertAfterTask) : null;

  let result: { id: string; file: string; line: number };
  try {
    result = await otCreateTask({
      summary: title.trim(),
      local: isLocal,
      parentId: parentId ?? undefined,
      afterId: afterId ?? undefined,
      section: !parentId && !afterId ? "Improvements" : undefined,
      sourcePath: !isLocal ? anchorSourcePath : undefined,
    }, { cwd });
  } catch (err) {
    ctx.ui.notify(`Failed to save: ${(err as Error).message}`, "error");
    return null;
  }

  const fresh = await loadTasks(cwd);
  const created = findTaskById(fresh.tasks, result.id);
  ctx.ui.notify(`Created: ${created?.summary ?? title.trim()}`, "info");
  return created;
}

// ── Archive flow ───────────────────────────────────────────────────────

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

  const id = getTaskId(topLevel);
  if (!id) {
    ctx.ui.notify("Task has no :CUSTOM_ID:; cannot archive.", "error");
    return false;
  }

  try {
    await otArchiveTask(id, { cwd: ctx.cwd });
  } catch (err) {
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

  const id = getTaskId(task);
  if (!id) {
    ctx.ui.notify("Task has no :CUSTOM_ID:; cannot publish.", "error");
    return;
  }
  try {
    await otPublishTask(id, { cwd: ctx.cwd });
  } catch (err) {
    ctx.ui.notify(`Publish failed: ${(err as Error).message}`, "error");
    return;
  }
  ctx.ui.notify(`Published: ${task.summary}`, "info");
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

  const id = getTaskId(task);
  if (!id) {
    ctx.ui.notify("Task has no :CUSTOM_ID:; cannot unpublish.", "error");
    return;
  }
  try {
    await otUnpublishTask(id, { cwd: ctx.cwd });
  } catch (err) {
    ctx.ui.notify(`Unpublish failed: ${(err as Error).message}`, "error");
    return;
  }
  ctx.ui.notify(`Unpublished: ${task.summary}`, "info");
}

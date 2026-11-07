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
} from "@earendil-works/pi-coding-agent";
import {
  Input,
  truncateToWidth,
  visibleWidth,
  type Component,
  type Focusable,
  type TUI,
} from "@earendil-works/pi-tui";
import {
  existsSync,
  readFileSync,
  realpathSync,
  statSync,
  watch,
  type FSWatcher,
} from "node:fs";
import { readFile } from "node:fs/promises";
import { dirname, isAbsolute, join } from "node:path";
import { ensureEmacsServer } from "../emacsclient/emacsclient.ts";
import { TasksOverlay } from "./overlay.ts";
import { cursorAfterRemoval, runOverlayRemoval } from "./removal.ts";
import { runOverlayMove } from "./move.ts";
import {
  otArchiveTask,
  otBackfill,
  otCreateTask,
  otDoctor,
  otList,
  otMoveTask,
  otPublishTask,
  otRemoveTask,
  otSelectTask,
  otCyclePriority,
  otCycleStatus,
  otUnpublishTask,
  type OtSourceContent,
} from "./ot.ts";
import {
  CLOSED_STATUSES,
  expandOrgLinkTarget,
  getTaskId,
  getTaskStarted,
  parseSelectedKeyword,
  type Task,
} from "./parser.ts";
import { formatFindingsReport, type Finding } from "./doctor.ts";
import { isWithinRoot } from "./paths.ts";
import {
  evaluateSummaryRefresh,
  type SummaryRefreshReason,
} from "./summary.ts";
import { colorIssues, colorPriority, colorStatus, colorTags } from "./status-colors.ts";
import { getAgentPath } from "../lib/agent-paths.ts";
import {
  buildSummaryRefreshPrompt,
  getEmacsOptions,
  handlePlanEdit as handlePlanEditPrompt,
} from "./prompts.ts";
export { buildChangeRecordPrompt, buildSummaryRefreshPrompt } from "./prompts.ts";

const TASKS_FILE = "TASKS.org";
/** Gitignored local file that stores per-contributor selection state. */
const TASKS_LOCAL_FILE = "TASKS.local.org";
const TASKS_ARCHIVE_FILE = "TASKS.archive.org";
/** Hard cap so the compact selected-task widget never dominates the screen. */
const MAX_COMPACT_LINES = 6;
const COMPACT_WIDGET_ID = "tasks:selected";

// ── Tasks-extension user settings ───────────────────────────────────

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
    const settingsPath = getAgentPath("tasks-ext.json");
    if (existsSync(settingsPath)) {
      const parsed = JSON.parse(readFileSync(settingsPath, "utf-8"));
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
let uiGeneration = 0;

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
    // Watcher-driven refresh is best-effort and MUST NOT reject. It runs from a
    // timer callback with no caller to await it, so an unhandled rejection here
    // becomes an `uncaughtException` and takes down the whole pi process -- which
    // is exactly what a transient `ot list` timeout did under heavy machine load
    // (see `runOt`'s per-call timer in ot.ts). A stale widget until the next file
    // event is a far better failure mode than losing the session, and this mirrors
    // the deliberate swallow around `otBackfill` in `loadTasks`.
    if (ctx) {
      refreshTaskUi(ctx, ctx.cwd).catch(() => {
        // Intentionally silent: the next watcher event retries.
      });
    }
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

export interface LoadedTasks {
  tasks: Task[];
  root: string;
  files: Record<string, string>;
}

/** Load the task tree through `ot list`, as the overlay and commands see it. */
export async function loadTasks(cwd: string): Promise<LoadedTasks> {
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
    (t.linkedIssues ?? []).map((i) => i.label),
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
  const generation = uiGeneration;
  activeCtx = ctx;
  const loaded = await loadTasks(cwd);
  const { tasks, files } = loaded;
  const selectedId = await readSelectedId(files.local);
  // A refresh started before shutdown must not restore session resources.
  if (generation !== uiGeneration || activeCtx !== ctx) return tasks;
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
  // Override in the configured agent directory's keybindings.json for a different chord.

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
    uiGeneration++;
    closeAllFileWatchers();
    isOverlayActive = false;
    activeOverlayInstance = null;
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
        const created = await createTask(ctx, loaded.tasks, ctx.cwd, null);
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

      // Bare `/tasks` opens the full overlay via `ctx.ui.custom`, which only
      // TUI implements; RPC's `custom()` resolves to `undefined` immediately
      // (no dialog is shown), which would otherwise look like the user closed
      // an overlay they never saw. Report the unsupported mode instead.
      if (ctx.mode !== "tui") {
        ctx.ui.notify("/tasks requires TUI mode; use /tasks new or /tasks doctor", "error");
        return;
      }

      type WorkflowRequest =
        | { type: "archive"; task: Task }
        | { type: "changeRecord"; task: Task }
        | { type: "create"; anchor: Task | null; relation: "sibling" | "child" }
        | { type: "edit"; task: Task }
        | { type: "plan"; task: Task }
        | { type: "remove"; task: Task; cursorId: string | null }
        | { type: "move"; task: Task }
        | { type: "publish"; task: Task }
        | { type: "summaryRefresh"; task: Task; reason: SummaryRefreshReason; absPlan: string }
        | { type: "unpublish"; task: Task };

      let reopen = true;
      let loaded = await loadTasks(ctx.cwd);
      let tasks = loaded.tasks;
      let selectedId: string | null = await readSelectedId(loaded.files.local);
      let preferredCursorId: string | null = null;
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

        const onNewTask = (anchor: Task | null, relation: "sibling" | "child") => {
          workflow.request = { type: "create", anchor, relation };
        };

        const onRemove = (task: Task, cursorId: string | null) => {
          workflow.request = { type: "remove", task, cursorId };
        };

        const onMove = (task: Task) => {
          workflow.request = { type: "move", task };
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
          kind: "info" | "warning" | "error" = "info",
        ) => {
          ctx.ui.notify(message, kind);
        };

        // Generic event emit so other extensions can react to status
        // cycles (e.g. `jira` for auto-transition). Stays generic — no
        // tracker-specific knowledge in `tasks`.
        const onCycleStatus = async (
          task: Task,
          direction: "forward" | "back",
        ): Promise<{ tasks: Task[]; prevStatus: string; status: string } | null> => {
          const id = getTaskId(task);
          if (!id) {
            throw new Error("Task has no :CUSTOM_ID:; cannot change status.");
          }
          // `ot status --cycle` owns the canonical order and the transition.
          const result = await otCycleStatus(id, direction, { cwd: ctx.cwd });
          pi.events.emit("tasks:status-changed", {
            id,
            status: result.status,
            prevStatus: result.prevStatus,
            summary: result.task.summary,
            closed: result.status === "DONE" || result.status === "CANCELLED",
          });
          return {
            tasks: (await loadTasks(ctx.cwd)).tasks,
            prevStatus: result.prevStatus,
            status: result.status,
          };
        };

        const onCyclePriority = async (
          task: Task,
          direction: "forward" | "back",
        ): Promise<{ tasks: Task[] } | null> => {
          const id = getTaskId(task);
          if (!id) {
            throw new Error("Task has no :CUSTOM_ID:; cannot change priority.");
          }
          // `ot priority --cycle` owns the canonical order (unset → A
          // forward, unset → D back) and the write.
          await otCyclePriority(id, direction, { cwd: ctx.cwd });
          return { tasks: (await loadTasks(ctx.cwd)).tasks };
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
              onCycleStatus,
              onCyclePriority,
              onRemove,
              onMove,
            );
            overlay.focusTaskId(preferredCursorId);
            preferredCursorId = null;
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
        } else if (request.type === "move") {
          const fresh = await reloadAndResolve(request.task, "move");
          if (fresh) {
            preferredCursorId = getTaskId(fresh);
            try {
              const moved = await runOverlayMove({
                tasks,
                source: fresh,
                select: (title, choices) => ctx.ui.select(title, choices),
                input: (title) => ctx.ui.input(title),
                move: (id, destination) => otMoveTask(id, destination, { cwd: ctx.cwd }),
              });
              if (moved) ctx.ui.notify("Task moved.", "info");
            } catch (err) {
              ctx.ui.notify(`Move failed: ${(err as Error).message}`, "error");
            }
          }
          loaded = await loadTasks(ctx.cwd);
          tasks = loaded.tasks;
          selectedId = await readSelectedId(loaded.files.local);
          reopen = true;
        } else if (request.type === "remove") {
          let nextCursorId = request.cursorId;
          const fresh = await reloadAndResolve(request.task, "remove");
          if (fresh) {
            const id = getTaskId(fresh);
            if (!id) {
              ctx.ui.notify("Cannot remove task without a :CUSTOM_ID:.", "error");
            } else {
              const outcome = await runOverlayRemoval({
                id,
                preview: (taskId, pruneBlockers) => otRemoveTask(taskId, {
                  cwd: ctx.cwd,
                  dryRun: true,
                  pruneBlockers,
                }),
                confirm: (title, message) => ctx.ui.confirm(title, message),
                remove: (taskId, pruneBlockers) => otRemoveTask(taskId, {
                  cwd: ctx.cwd,
                  pruneBlockers,
                }),
              });
              nextCursorId = cursorAfterRemoval(outcome, id, request.cursorId);
              if (outcome.kind === "removed") {
                const pruned = outcome.result.prunedBlockers.length;
                ctx.ui.notify(
                  `Removed ${outcome.result.subtree.length} task${outcome.result.subtree.length === 1 ? "" : "s"}; pruned ${pruned} inbound blocker${pruned === 1 ? "" : "s"}.`,
                  "info",
                );
              } else if (outcome.kind === "error") {
                ctx.ui.notify(`Task removal failed: ${outcome.error.message}`, "error");
              }
            }
          }
          loaded = await loadTasks(ctx.cwd);
          tasks = loaded.tasks;
          selectedId = await readSelectedId(loaded.files.local);
          preferredCursorId = nextCursorId;
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
          const freshAnchor = resolveStale(request.anchor);
          if (request.anchor && !freshAnchor) {
            ctx.ui.notify(
              "Anchor task no longer exists; appending at top level.",
              "warning",
            );
          }
          await createTask(ctx, tasks, ctx.cwd, freshAnchor, request.relation);
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

/** Open an imported record, or create one and refresh the legacy task UI. */
export async function handlePlanEdit(
  pi: ExtensionAPI,
  ctx: ExtensionContext,
  task: Task,
  mode: "proactive" | "retrospective" = "proactive",
): Promise<void> {
  await handlePlanEditPrompt(pi, ctx, task, mode, {
    approvePath: promptForPlanPath,
    afterCreate: (context) => refreshTaskUi(context, context.cwd),
  });
}

// ── Create task flow ─────────────────────────────────────────────────────

/**
 * Prompt for a title and create a new task via `ot`.
 *
 * Placement is delegated to `ot create --relative-to <anchor> --as ...`, which
 * derives parent/after/local/source from the anchor task. A null anchor creates
 * a top-level task under the default section.
 *
 * @param anchor    cursor task to place relative to, or null for top-level.
 * @param relation  "sibling" (after the anchor, same level) or "child".
 */
async function createTask(
  ctx: ExtensionContext,
  _tasks: Task[],
  cwd: string,
  anchor: Task | null,
  relation?: "sibling" | "child",
): Promise<Task | null> {
  const prompt = anchor
    ? (relation === "child"
        ? `Subtask of "${anchor.summary}"`
        : `Sibling of "${anchor.summary}"`)
    : "New task title";
  const title = await ctx.ui.input(prompt, "");
  if (!title?.trim()) return null;

  const anchorId = anchor ? getTaskId(anchor) : null;

  let result: { id: string; file: string; line: number };
  try {
    result = await otCreateTask({
      summary: title.trim(),
      relativeTo: anchorId ?? undefined,
      as: anchorId ? relation : undefined,
      section: anchorId ? undefined : "Improvements",
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

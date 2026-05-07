/**
 * Tasks overlay component — split-pane: task tree (left) + description (right).
 */

import type { Theme } from "@mariozechner/pi-coding-agent";
import {
  matchesKey,
  truncateToWidth,
  visibleWidth,
  wrapTextWithAnsi,
  type TUI,
} from "@mariozechner/pi-tui";
import type { LinkedIssue, Task } from "./parser.ts";
import {
  formatOrgTimestamp,
  getFileKeyword,
  getLinkedIssues,
  getTaskBlockers,
  getTaskHandoff,
  getTaskId,
  isTaskReady,
  serializeTasksPreservingFile,
  type TaskBlocker,
} from "./parser.ts";
import { applyStatusTransition } from "./lifecycle.ts";
import {
  colorIssues,
  colorLocal,
  colorPriority,
  colorStatus,
  colorTags,
} from "./status-colors.ts";
import { readFile, writeFile } from "node:fs/promises";
import { existsSync, readFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";

/**
 * [DEBUG] Read the shared `leader-menu.json` `debug` flag so this
 * overlay can be toggled in lock-step with the modal editor's
 * per-keypress logger. The flag lives with leader-menu because it's
 * about *key dispatch* (not modal editing); both vim-mode and this
 * overlay are co-consumers. Failures are silently treated as "off".
 * (Pre-refactor this file lived at `keybindings-ext.json`, then
 * briefly at `vim-mode.json`; the `leader-menu` extension migrates
 * legacy files on first run.)
 */
function loadKeybindingsDebugFlag(): boolean {
  try {
    const path = join(homedir(), ".pi", "agent", "leader-menu.json");
    if (!existsSync(path)) return false;
    const parsed = JSON.parse(readFileSync(path, "utf-8"));
    return !!parsed?.debug;
  } catch {
    return false;
  }
}

const STATUS_CYCLE = [
  "TODO",
  "STARTED",
  "WAITING",
  "DONE",
  "CANCELLED",
] as const;
/** A flattened row for display & navigation. */
interface FlatRow {
  task: Task;
  depth: number;
  collapsed: boolean;
  hasChildren: boolean;
  /** True when this task's :ID: matches the UUID in TASKS.local.org. */
  isSelectedTask: boolean;
  /** True when this task is inside the selected top-level task tree. */
  inSelection: boolean;
  /** Parent task, or null for top-level rows. */
  parent: Task | null;
}

export class TasksOverlay {
  private theme: Theme;
  private done: (value: undefined) => void;
  private rows: FlatRow[] = [];
  /** Cursor index into `rows` — the user's current navigation position. */
  private cursor = 0;
  private scrollOffset = 0;
  private descScrollOffset = 0;
  private cachedWidth?: number;
  private cachedLines?: string[];

  private collapsedSet = new WeakSet<Task>();

  /** [DEBUG] Mirrors keybindings extension's `debugEnabled` — see
   *  loadKeybindingsDebugFlag above. Read once at construction time. */
  private debugEnabled: boolean = loadKeybindingsDebugFlag();

  /**
   * Cache of `#+ISSUE_URL_BASE` per source-file content. Keyed by the
   * `sourceContent` string identity (different files have different
   * content strings). Reset on `refreshTasks`.
   */
  private urlBaseCache = new Map<string, string | null>();

  /** UUID of the currently selected task (from TASKS.local.org), or null. */
  private selectedId: string | null;

  constructor(
    private tasks: Task[],
    private cwd: string,
    private readonly tui: TUI,
    theme: Theme,
    done: (value: undefined) => void,
    private onEdit?: (task: Task) => void,
    private onTasksChanged?: (tasks: Task[]) => void,
    private onEditPlan?: (task: Task) => void,
    /** Request archiving after the expanded overlay closes so confirmation is visible. */
    private onArchive?: (task: Task) => void,
    /** Request task creation after the expanded overlay closes so input is visible. */
    private onNewTask?: (
      parent: Task | null,
      insertAfter: Task | null,
    ) => void,
    /** Request publish (local → shared) after overlay closes. */
    private onPublish?: (task: Task) => void,
    /** Request unpublish (shared → local) after overlay closes. */
    private onUnpublish?: (task: Task) => void,
    /** Request retrospective change-record creation when a task closes
        without an existing #+IMPORT:.  Returns true to indicate the request
        was accepted (overlay should close); false to keep the overlay open. */
    private onCreateChangeRecord?: (task: Task) => boolean,
    selectedId: string | null = null,
    /** Called when the user toggles selection; should write TASKS.local.org. */
    private onSelectionChange?: (id: string | null) => Promise<void>,
    /** Called when the user presses `J` to open linked-issue URLs. */
    private onOpenUrls?: (urls: string[]) => Promise<void>,
    /** Display a one-shot notification (status footer / toast). */
    private onNotify?: (message: string, kind?: "info" | "warn" | "error") => void,
    /** Called after a task's status is cycled (via →/← / l/h). */
    private onStatusChanged?: (task: Task, prevStatus: string) => void,
  ) {
    this.theme = theme;
    this.done = done;
    this.selectedId = selectedId;
    // If the file already marks a selected task, reflect that focused view on open.
    this.applyDefaultCollapseView();
    this.rebuildRows();
    this.focusSelectedTask();
  }

  /**
   * Replace the task tree with a freshly-loaded copy from disk (called by the
   * file-watcher path when an external editor changes TASKS.org or
   * TASKS.local.org while this overlay is open). Rebuilds collapse state and
   * rows, preserves the cursor on the same task by ID where possible, then
   * triggers a re-render.
   */
  refreshTasks(newTasks: Task[], selectedId: string | null = this.selectedId): void {
    this.selectedId = selectedId;
    // Remember which task the cursor is on so we can restore position after
    // rebuilding (new task objects from disk won't share references).
    const cursorId = this.rows[this.cursor]
      ? getTaskId(this.rows[this.cursor]!.task)
      : null;

    this.tasks = newTasks;
    this.urlBaseCache = new Map();
    this.applyDefaultCollapseView();
    this.rebuildRows();

    // Try to keep the cursor on the same task by ID.  Fall back to the
    // selected task when the previous cursor task is no longer visible
    // (e.g. a collapse-state change hid it).
    if (cursorId) {
      const restoredIdx = this.rows.findIndex(
        (r) => getTaskId(r.task) === cursorId,
      );
      if (restoredIdx >= 0) {
        this.cursor = restoredIdx;
      } else {
        this.focusSelectedTask();
      }
    } else {
      this.focusSelectedTask();
    }

    this.invalidate();
    this.tui.requestRender();
  }

  // ── Flatten visible rows ────────────────────────────────────────────

  private rebuildRows(): void {
    this.rows = [];
    const selected = this.findSelectedTask();
    const selectionRoot = selected ? this.findTopLevelRoot(selected) : null;
    const inSelection = new WeakSet<Task>();
    if (selectionRoot) {
      const mark = (t: Task) => {
        inSelection.add(t);
        for (const c of this.taskChildren(t)) mark(c);
      };
      mark(selectionRoot);
    }

    const walk = (tasks: Task[], depth: number, parent: Task | null) => {
      for (const t of tasks) {
        const collapsed = this.collapsedSet.has(t);
        this.rows.push({
          task: t,
          depth,
          collapsed,
          hasChildren: this.taskChildren(t).length > 0,
          isSelectedTask: t === selected,
          inSelection: inSelection.has(t),
          parent,
        });
        if (!collapsed) walk(this.taskChildren(t), depth + 1, t);
      }
    };
    walk(this.tasks, 0, null);
    if (this.cursor >= this.rows.length) {
      this.cursor = Math.max(0, this.rows.length - 1);
    }
  }

  /** Move the cursor onto the selected task, if any is visible. */
  private focusSelectedTask(): void {
    const idx = this.rows.findIndex((r) => r.isSelectedTask);
    if (idx >= 0) this.cursor = idx;
  }

  private taskChildren(task: Task): Task[] {
    return [...task.children, ...(task.importChildren ?? [])];
  }

  /**
   * Resolve `#+ISSUE_URL_BASE` for a task by reading its `sourceContent`.
   * Memoised per-content-string to avoid re-scanning on every render.
   */
  private urlBaseFor(task: Task): string | null {
    const content = task.sourceContent ?? "";
    if (!content) return null;
    if (this.urlBaseCache.has(content)) {
      return this.urlBaseCache.get(content) ?? null;
    }
    const value = getFileKeyword(content, "ISSUE_URL_BASE");
    this.urlBaseCache.set(content, value);
    return value;
  }

  /** Resolve `:LINKED_ISSUES:` for a task using cached `#+ISSUE_URL_BASE`. */
  private linkedIssuesFor(task: Task): LinkedIssue[] {
    return getLinkedIssues(task, this.urlBaseFor(task));
  }

  /**
   * Format a single `:BLOCKED-BY:` entry for the right-pane Blocked-by
   * list. `task:<UUID>` blockers resolve to `[STATUS] summary` so the
   * reader can see whether the dependency has closed; non-task blockers
   * render verbatim.
   */
  private formatBlockerLine(blocker: TaskBlocker): string {
    if (blocker.kind === "task") {
      const dep = this.findTaskById(this.tasks, blocker.ref);
      if (!dep) {
        return `task:${blocker.ref} (missing)`;
      }
      return `${colorStatus(dep.status)} ${dep.summary}`;
    }
    return blocker.raw;
  }

  // ── Input ───────────────────────────────────────────────────────────

  /** [DEBUG] Render a key-press as "raw" + hex + length (+ matched action)
   *  and notify. Mirrors keybindings/index.ts `debugLogKey`, prefixed so the
   *  two streams are easy to tell apart. `action` is the label returned by
   *  `dispatchInput` — "unhandled" when no branch consumed the key. */
  private debugLogKey(data: string, action: string): void {
    if (!this.debugEnabled) return;
    const escaped = data
      .split("")
      .map((ch) => {
        const code = ch.charCodeAt(0);
        if (ch === "\x1b") return "\\e";
        if (ch === "\r") return "\\r";
        if (ch === "\n") return "\\n";
        if (ch === "\t") return "\\t";
        if (ch === "\\") return "\\\\";
        if (code < 0x20 || code === 0x7f)
          return `\\x${code.toString(16).padStart(2, "0")}`;
        return ch;
      })
      .join("");
    const hex = Array.from(data)
      .map((ch) => ch.charCodeAt(0).toString(16).padStart(2, "0"))
      .join(" ");
    this.onNotify?.(
      `tasks-overlay: "${escaped}" [${hex}] len=${data.length} → ${action}`,
      "info",
    );
  }

  handleInput(data: string): void {
    const action = this.dispatchInput(data);
    this.debugLogKey(data, action);
  }

  /** Dispatch a key press to the appropriate handler. Returns a short label
   *  describing the action consumed (or "unhandled"). The label exists
   *  purely for the debug logger — callers other than `handleInput` should
   *  not depend on its exact wording. */
  private dispatchInput(data: string): string {
    if (matchesKey(data, "escape") || matchesKey(data, "q")) {
      this.done(undefined);
      return "close";
    }

    if (matchesKey(data, "up") || matchesKey(data, "k")) {
      if (this.cursor > 0) {
        this.cursor--;
        this.descScrollOffset = 0;
        this.invalidate();
        return "cursor-up";
      }
      return "cursor-up (at top)";
    }

    if (matchesKey(data, "down") || matchesKey(data, "j")) {
      if (this.cursor < this.rows.length - 1) {
        this.cursor++;
        this.descScrollOffset = 0;
        this.invalidate();
        return "cursor-down";
      }
      return "cursor-down (at bottom)";
    }

    // Cycle status forward
    if (matchesKey(data, "right") || matchesKey(data, "l")) {
      this.cycleStatus(1);
      return "status-cycle-fwd";
    }

    // Cycle status backward
    if (matchesKey(data, "left") || matchesKey(data, "h")) {
      this.cycleStatus(-1);
      return "status-cycle-back";
    }

    // Scroll description pane
    if (matchesKey(data, "ctrl+d")) {
      this.descScrollOffset += 5;
      this.invalidate();
      return "desc-scroll-down";
    }
    if (matchesKey(data, "ctrl+u")) {
      this.descScrollOffset = Math.max(0, this.descScrollOffset - 5);
      this.invalidate();
      return "desc-scroll-up";
    }

    // Edit the linked plan (if any) for the task under the cursor.
    // Delegates creation/approval of a new plan file to the command handler.
    if (matchesKey(data, "p")) {
      const row = this.rows[this.cursor];
      if (row && this.onEditPlan) {
        this.onEditPlan(row.task);
        this.done(undefined);
        return "edit-plan";
      }
      return "edit-plan (no-op)";
    }

    // Open in Emacs at the task under the cursor
    if (matchesKey(data, "e")) {
      const row = this.rows[this.cursor];
      if (row && this.onEdit) {
        this.onEdit(row.task);
        this.done(undefined);
        return "edit";
      }
      return "edit (no-op)";
    }

    // Archive the top-level task containing the cursor's task.
    // Only closed top-level tasks (DONE/CANCELLED) are archivable. Uses
    // shift+A so it's harder to hit by accident than lowercase 'a'.
    if (matchesKey(data, "shift+a")) {
      this.archive();
      return "archive";
    }

    // Publish local task → TASKS.org  (shift-P, local tasks only)
    if (matchesKey(data, "shift+p")) {
      this.publish();
      return "publish";
    }

    // Unpublish shared task → TASKS.local.org  (shift-U, top-level shared only)
    if (matchesKey(data, "shift+u")) {
      this.unpublish();
      return "unpublish";
    }

    // New sibling task at the cursor's hierarchy level.
    if (matchesKey(data, "n")) {
      this.createNewTask(false);
      return "new-sibling";
    }

    // New child (subtask) under the task at the cursor.
    if (matchesKey(data, "shift+n")) {
      this.createNewTask(true);
      return "new-child";
    }

    // Toggle :selected: on the task under the cursor
    if (matchesKey(data, "s")) {
      this.toggleSelect();
      return "toggle-select";
    }

    // Open all linked-issue URLs for the cursor task in the browser.
    // Capped at 5 with a notification when exceeded, to avoid foot-guns.
    if (matchesKey(data, "shift+j")) {
      this.openLinkedIssues();
      return "open-linked-issues";
    }

    // Toggle collapse
    if (
      matchesKey(data, "return") ||
      matchesKey(data, "space") ||
      matchesKey(data, "tab")
    ) {
      const row = this.rows[this.cursor];
      if (row && row.hasChildren) {
        if (this.collapsedSet.has(row.task)) {
          this.collapsedSet.delete(row.task);
        } else {
          this.collapsedSet.add(row.task);
        }
        this.rebuildRows();
        this.invalidate();
        return "toggle-collapse";
      }
      return "toggle-collapse (no children)";
    }

    return "unhandled";
  }

  private cycleStatus(direction: 1 | -1): void {
    const row = this.rows[this.cursor];
    if (!row) return;
    const currentStatus = row.task.status as (typeof STATUS_CYCLE)[number];
    const idx = STATUS_CYCLE.indexOf(currentStatus);
    if (idx === -1) return;
    const next = (idx + direction + STATUS_CYCLE.length) % STATUS_CYCLE.length;
    const nextStatus = STATUS_CYCLE[next];
    const timestamp = formatOrgTimestamp();
    const { wasClosed, isClosed } = applyStatusTransition(row.task, nextStatus, timestamp);
    // When a subtask transitions to STARTED, auto-promote the top-level
    // TASKS.org ancestor from TODO → STARTED so the parent reflects active
    // work without requiring a manual status bump.
    if (nextStatus === "STARTED") {
      const root = this.findTopLevelRoot(row.task);
      if (root && root !== row.task && root.status === "TODO") {
        applyStatusTransition(root, "STARTED", timestamp);
      }
    }
    this.persistChange();
    this.invalidate();

    // Notify any listening extension (e.g. `jira` for auto-transition)
    // that a task's status changed. Done after persistChange so file
    // watchers see the on-disk update before consumers act on it.
    this.onStatusChanged?.(row.task, currentStatus);

    // After persisting, if the user just closed a task that has no
    // #+IMPORT: linked change-record, offer to scaffold one retrospectively.
    // Done last so the on-disk DONE+CLOSED state is committed before the
    // overlay closes for the workflow handoff.
    if (
      isClosed && !wasClosed && nextStatus === "DONE"
      && !row.task.importPath
      && this.onCreateChangeRecord
    ) {
      const accepted = this.onCreateChangeRecord(row.task);
      if (accepted) this.done(undefined);
    }
  }

  // ── Selection (TASKS.local.org) ──────────────────────────────────────

  /**
   * Find the top-level TASKS.org task that contains `task`, walking through
   * regular children and injected plan children alike. Returns null when the
   * task isn't part of the current TASKS.org tree (e.g. stale reference).
   */
  private findTopLevelRoot(task: Task): Task | null {
    const contains = (t: Task): boolean => {
      if (t === task) return true;
      for (const c of this.taskChildren(t)) {
        if (contains(c)) return true;
      }
      return false;
    };
    return this.tasks.find(contains) ?? null;
  }

  private createNewTask(asChild: boolean): void {
    if (!this.onNewTask) return;
    const row = this.rows[this.cursor];
    let parent: Task | null;
    let insertAfter: Task | null;
    if (asChild) {
      // Child: nest under the current task.
      parent = row?.task ?? null;
      insertAfter = null;
    } else {
      // Sibling: same parent, insert immediately after current task.
      parent = row?.parent ?? null;
      insertAfter = row?.task ?? null;
    }
    this.onNewTask(parent, insertAfter);
    this.done(undefined);
  }

  private archive(): void {
    const row = this.rows[this.cursor];
    if (!row || !this.onArchive) return;
    const topLevel = this.findTopLevelRoot(row.task);
    if (!topLevel) return;
    this.onArchive(topLevel);
    this.done(undefined);
  }

  private publish(): void {
    const row = this.rows[this.cursor];
    if (!row || !this.onPublish) return;
    if (!row.task.isLocal) return; // guard: only local tasks
    this.onPublish(row.task);
    this.done(undefined);
  }

  private unpublish(): void {
    const row = this.rows[this.cursor];
    if (!row || !this.onUnpublish) return;
    if (row.task.isLocal) return; // guard: only shared tasks
    // Unpublish is restricted to top-level shared tasks.
    const topLevel = this.findTopLevelRoot(row.task);
    if (!topLevel || topLevel !== row.task) return;
    this.onUnpublish(topLevel);
    this.done(undefined);
  }

  /** Find the selected task by UUID in the current task graph. */
  private findSelectedTask(): Task | null {
    return this.findTaskById(this.tasks, this.selectedId);
  }

  /** Find a task anywhere in the graph by its :ID: property value. */
  private findTaskById(tasks: Task[], id: string | null): Task | null {
    if (!id) return null;
    for (const t of tasks) {
      if (getTaskId(t) === id) return t;
      const found = this.findTaskById(this.taskChildren(t), id);
      if (found) return found;
    }
    return null;
  }

  /**
   * Open every URL in the cursor task's `:LINKED_ISSUES:` in the user's
   * browser via `onOpenUrls`. Caps at 5 to avoid spawning a tab storm.
   * Bare tokens with no `#+ISSUE_URL_BASE` template are skipped with a
   * notification pointing the user at the missing keyword.
   */
  private openLinkedIssues(): void {
    const row = this.rows[this.cursor];
    if (!row) return;
    const issues = this.linkedIssuesFor(row.task);
    if (issues.length === 0) return; // silent no-op when property absent

    const resolvable = issues.filter((i) => i.url !== null);
    const unresolvable = issues.length - resolvable.length;
    if (resolvable.length === 0) {
      this.onNotify?.(
        "Set #+ISSUE_URL_BASE in TASKS.org to enable browser-open for bare keys.",
        "warn",
      );
      return;
    }

    const CAP = 5;
    const toOpen = resolvable.slice(0, CAP).map((i) => i.url!);
    if (resolvable.length > CAP) {
      this.onNotify?.(
        `Opening first ${CAP} of ${resolvable.length} linked issues.`,
        "info",
      );
    } else if (unresolvable > 0) {
      this.onNotify?.(
        `Opening ${resolvable.length} linked issues; ${unresolvable} skipped (no #+ISSUE_URL_BASE).`,
        "info",
      );
    }

    if (this.onOpenUrls) {
      void this.onOpenUrls(toOpen);
    }
  }

  private toggleSelect(): void {
    const row = this.rows[this.cursor];
    if (!row) return;
    const target = row.task;
    const id = getTaskId(target);
    if (!id) return; // Can't select a task with no :ID:
    const wasSelected = this.selectedId === id;

    // Update in-memory selection state.
    this.selectedId = wasSelected ? null : id;

    // Write to TASKS.local.org via the caller-supplied callback.
    if (this.onSelectionChange) {
      void this.onSelectionChange(this.selectedId);
    }

    this.applyDefaultCollapseView();
    this.rebuildRows();
    // Keep the cursor on the task the user just toggled, if still visible.
    const newIdx = this.rows.findIndex((r) => r.task === target);
    if (newIdx >= 0) this.cursor = newIdx;
    // Persist task-file changes (status edits etc.) — selection is
    // written by onSelectionChange above.
    this.persistChange();
    this.invalidate();
  }

  private pathToTask(target: Task): Task[] {
    const search = (tasks: Task[], path: Task[]): Task[] | null => {
      for (const task of tasks) {
        const next = [...path, task];
        if (task === target) return next;
        const found = search(this.taskChildren(task), next);
        if (found) return found;
      }
      return null;
    };
    return search(this.tasks, []) ?? [];
  }

  /**
   * Default collapse rules:
   * - no selection: show top-level tasks only;
   * - with selection: keep the selected path visible, collapse sibling subtrees;
   * - completed subtrees are collapsed unless they are required to reveal the selection.
   */
  private applyDefaultCollapseView(): void {
    this.collapsedSet = new WeakSet<Task>();
    const selected = this.findSelectedTask();
    const selectedPath = selected ? this.pathToTask(selected) : [];
    const keepVisible = new WeakSet<Task>(selectedPath);

    const walk = (tasks: Task[]) => {
      for (const task of tasks) {
        const children = this.taskChildren(task);
        if (children.length > 0) {
          if (!selected) {
            this.collapsedSet.add(task);
          } else if (!keepVisible.has(task)) {
            // Task is not on the path to the selected task — collapse it.
            // This also covers completed tasks that are not ancestors of the
            // selection, without needing a separate DONE/CANCELLED check.
            this.collapsedSet.add(task);
          }
          // Tasks in keepVisible (ancestors of the selected task) are always
          // kept expanded so the selected task remains visible, even when
          // those ancestors are in a DONE or CANCELLED state.
        }
        walk(children);
      }
    };
    walk(this.tasks);
  }

  private persistChange(): void {
    void this.save();
    this.onTasksChanged?.(this.tasks);
  }

  private async save(): Promise<void> {
    try {
      const roots = new Map<string, Task[]>();
      roots.set(join(this.cwd, "TASKS.org"), this.tasks);

      const collect = (tasks: Task[]) => {
        for (const task of tasks) {
          if (task.sourcePath && task.sourceRoot) {
            roots.set(task.sourcePath, task.sourceRoot);
          }
          collect(task.children);
          if (task.importChildren) collect(task.importChildren);
        }
      };
      collect(this.tasks);

      await Promise.all(
        [...roots.entries()].map(async ([path, tasks]) => {
          const cachedOriginal = tasks.find((t) => t.sourceContent)?.sourceContent;
          const original = cachedOriginal ?? await readFile(path, "utf-8");
          const content = serializeTasksPreservingFile(original, tasks);
          await writeFile(path, content, "utf-8");
        }),
      );
    } catch {
      // Best-effort save; overlay stays usable
    }
  }

  // ── Render ──────────────────────────────────────────────────────────

  render(width: number): string[] {
    if (this.cachedLines && this.cachedWidth === width) {
      return this.cachedLines;
    }

    const th = this.theme;
    const lines: string[] = [];

    // Split: left pane ~55%, right pane gets the rest
    // Subtract 1 for the inner column divider (no outer borders)
    const usable = width - 1;
    const leftW = Math.max(30, Math.floor(usable * 0.55));
    const rightW = Math.max(20, usable - leftW);

    const maxVisible = 20;

    // ── helpers ──
    const hBar = (n: number) => "─".repeat(Math.max(0, n));
    const pad = (content: string, w: number) => {
      const vis = visibleWidth(content);
      const p = Math.max(0, w - vis);
      return content + " ".repeat(p);
    };

    // ── Build left pane lines ──
    const leftLines: string[] = [];


    if (this.rows.length === 0) {
      leftLines.push(th.fg("dim", " No tasks found."));
      leftLines.push(th.fg("dim", " Create TASKS.org in project root."));
    } else {
      // Summary counts
      const counts = this.countStatuses(this.tasks);
      const summary = [
        counts.TODO > 0
          ? colorStatus("TODO", `TODO:${counts.TODO}`)
          : null,
        counts.STARTED > 0
          ? colorStatus("STARTED", `STARTED:${counts.STARTED}`)
          : null,
        counts.WAITING > 0
          ? colorStatus("WAITING", `WAITING:${counts.WAITING}`)
          : null,
        counts.DONE > 0
          ? colorStatus("DONE", `DONE:${counts.DONE}`)
          : null,
        counts.CANCELLED > 0
          ? colorStatus("CANCELLED", `CANCELLED:${counts.CANCELLED}`)
          : null,
      ]
        .filter(Boolean)
        .join(th.fg("dim", " │ "));
      leftLines.push(" " + summary);
      leftLines.push("");

      // Task rows
      this.adjustScroll(maxVisible);
      const visibleRows = this.rows.slice(
        this.scrollOffset,
        this.scrollOffset + maxVisible,
      );

      // When anything is selected, non-selection rows are dimmed so the
      // selected subtree dominates the view.
      const hasSelection = this.rows.some((r) => r.isSelectedTask);

      // Index of the first local-task row in the full rows array (for separator).
      const firstLocalGlobalIdx = this.rows.findIndex((r) => r.task.isLocal);

      for (let i = 0; i < visibleRows.length; i++) {
        const r = visibleRows[i]!;
        const globalIdx = this.scrollOffset + i;
        const isCursor = globalIdx === this.cursor;
        const dimmed = hasSelection && !r.inSelection;
        const isLocal = !!r.task.isLocal;

        // Inject the local-drafts separator at render time (not in the row array)
        // when the first local row is about to be drawn and there are shared rows above.
        if (globalIdx === firstLocalGlobalIdx && firstLocalGlobalIdx > 0) {
          leftLines.push(
            truncateToWidth(
              " " + th.fg("dim", `${'─'.repeat(4)} ⊠  Local drafts ${'─'.repeat(Math.max(0, leftW - 22))}`),
              leftW,
            ),
          );
        }

        const indent = "  ".repeat(r.depth);
        // Local tasks use ⊠ instead of the standard tree markers.
        const treeMark = isLocal
          ? (r.hasChildren ? (r.collapsed ? "⊠▶ " : "⊠▼ ") : "⊠ ")
          : (r.hasChildren ? (r.collapsed ? "▶ " : "▼ ") : "• ");

        const visibleTags = r.task.tags;
        const linkedIssues = this.linkedIssuesFor(r.task);
        const issueLabels = linkedIssues.map((li) => li.label);

        let statusStr: string;
        let prioStr: string;
        let selectMark: string;
        let summaryStr: string;
        let tagsStr: string;
        let issuesStr: string;
        let indentStr: string;
        let treeStr: string;

        if (dimmed) {
          // Everything on this row is dimmed — background material.
          indentStr = indent;
          treeStr = th.fg("dim", treeMark);
          statusStr = th.fg("dim", r.task.status.padEnd(9));
          prioStr = r.task.priority
            ? th.fg("dim", `[#${r.task.priority}]`) + " "
            : "";
          selectMark = "";
          summaryStr = th.fg("dim", r.task.summary);
          tagsStr = visibleTags.length > 0
            ? " " + th.fg("dim", colorTags(visibleTags))
            : "";
          issuesStr = issueLabels.length > 0
            ? " " + th.fg("dim", colorIssues(issueLabels))
            : "";
        } else {
          indentStr = indent;
          treeStr = isLocal ? colorLocal(treeMark) : treeMark;
          statusStr = this.renderStatus(r.task.status, th);
          prioStr = r.task.priority ? colorPriority(r.task.priority) + " " : "";
          selectMark = r.isSelectedTask ? th.fg("accent", "★ ") : "";
          tagsStr = visibleTags.length > 0 ? " " + colorTags(visibleTags) : "";
          issuesStr = issueLabels.length > 0 ? " " + colorIssues(issueLabels) : "";

          // Cursor > selected task > in-selection > local > plain.
          if (isCursor) {
            summaryStr = th.fg("accent", th.bold(r.task.summary));
          } else if (r.isSelectedTask) {
            summaryStr = th.fg("accent", th.bold(r.task.summary));
          } else if (r.inSelection) {
            summaryStr = th.fg("accent", r.task.summary);
          } else if (isLocal) {
            summaryStr = colorLocal(r.task.summary);
          } else {
            summaryStr = th.fg("text", r.task.summary);
          }
        }

        const body = `${indentStr}${treeStr}${statusStr} ${prioStr}${selectMark}${summaryStr}`;
        const pointer = isCursor
          ? th.fg("accent", "▌")
          : r.isSelectedTask
            ? th.fg("accent", "┃")
            : r.inSelection
              ? th.fg("accent", "│")
              : " ";
        const contentWidth = Math.max(0, leftW - visibleWidth(pointer));
        // Suffix = issues + tags (right-aligned). Issues come first so tags
        // remain at the far right where the eye expects them.
        const suffixStr = `${issuesStr}${tagsStr}`;
        const content = suffixStr
          ? (() => {
              const suffixWidth = visibleWidth(suffixStr);
              const bodyWidth = Math.max(0, contentWidth - suffixWidth - 1);
              const clippedBody = truncateToWidth(body, bodyWidth);
              const gap = Math.max(
                1,
                contentWidth - visibleWidth(clippedBody) - suffixWidth,
              );
              return `${clippedBody}${" ".repeat(gap)}${suffixStr}`;
            })()
          : body;
        leftLines.push(truncateToWidth(pointer + content, leftW));
      }

      // Scroll indicator
      if (this.rows.length > maxVisible) {
        const pct = Math.round(
          (this.scrollOffset / Math.max(1, this.rows.length - maxVisible)) *
            100,
        );
        leftLines.push(
          th.fg(
            "dim",
            ` ${this.scrollOffset + 1}-${Math.min(this.scrollOffset + maxVisible, this.rows.length)} of ${this.rows.length} (${pct}%)`,
          ),
        );
      }
    }

    // ── Build right pane lines ──
    const rightLines: string[] = [];
    const cursorRow = this.rows[this.cursor];

    if (cursorRow) {
      const task = cursorRow.task;
      const wrapWidth = Math.max(10, rightW - 2);

      // Task title and status
      rightLines.push(` ${colorStatus(task.status)} ${th.fg("accent", th.bold(task.summary))}`);
      rightLines.push("");

      // ── :HANDOFF: note ─────────────────────────────────────────────
      const handoff = getTaskHandoff(task);
      if (handoff) {
        rightLines.push(th.fg("accent", " Handoff"));
        for (const l of wrapTextWithAnsi(` → ${handoff}`, wrapWidth)) {
          rightLines.push(th.fg("text", l));
        }
        rightLines.push("");
      }

      // ── :BLOCKED-BY: blockers + readiness ──────────────────────────
      const blockers = getTaskBlockers(task);
      if (blockers.length > 0) {
        const report = isTaskReady(task, (id) => this.findTaskById(this.tasks, id));
        const headerColor = report.ready ? "accent" : "warning";
        const headerLabel = report.ready ? "Blocked by (all resolved)" : "Blocked by";
        rightLines.push(th.fg(headerColor, ` ${headerLabel}`));
        for (const blocker of blockers) {
          const line = this.formatBlockerLine(blocker);
          for (const l of wrapTextWithAnsi(` • ${line}`, wrapWidth)) {
            rightLines.push(th.fg("text", l));
          }
        }
        rightLines.push("");
      }

      const planLabel = task.importRaw ?? task.importPath;
      if (planLabel) {
        rightLines.push(th.fg("accent", " Plan"));
        for (const l of wrapTextWithAnsi(` ${planLabel}`, wrapWidth)) {
          rightLines.push(th.fg("text", l));
        }
        if (task.importError) {
          rightLines.push(th.fg("warning", ` Missing/unreadable: ${task.importError}`));
        } else {
          const n = task.importChildren?.length ?? 0;
          const label = n === 1 ? "task" : "tasks";
          rightLines.push(th.fg("dim", ` ${n} linked plan ${label} loaded`));
        }
        rightLines.push("");
      } else {
        rightLines.push(th.fg("dim", " Plan: none — press p to create"));
        rightLines.push("");
      }

      const desc = task.description.trim();
      if (desc) {
        // Word-wrap the description to fit the right pane
        const rawLines = desc.split("\n");
        const wrapped: string[] = [];
        for (const raw of rawLines) {
          if (raw.trim() === "") {
            wrapped.push("");
          } else {
            const w = wrapTextWithAnsi(raw, wrapWidth);
            wrapped.push(...w);
          }
        }

        // Clamp desc scroll
        const metadataLines = rightLines.length - 2;
        const descWindow = Math.max(5, maxVisible - metadataLines);
        const maxDescScroll = Math.max(0, wrapped.length - descWindow);
        if (this.descScrollOffset > maxDescScroll) {
          this.descScrollOffset = maxDescScroll;
        }

        const visibleDesc = wrapped.slice(
          this.descScrollOffset,
          this.descScrollOffset + descWindow,
        );
        for (const l of visibleDesc) {
          rightLines.push(" " + th.fg("text", l));
        }

        if (wrapped.length > descWindow) {
          rightLines.push("");
          rightLines.push(
            th.fg(
              "dim",
              ` Ctrl-d/u scroll (${this.descScrollOffset + 1}-${Math.min(this.descScrollOffset + descWindow, wrapped.length)}/${wrapped.length})`,
            ),
          );
        }
      } else {
        rightLines.push(th.fg("dim", " No description."));
      }
    } else {
      rightLines.push(th.fg("dim", " No task selected."));
    }

    // ── Compose split pane ──
    // Calculate body height: max of both panes, capped
    const bodyHeight = Math.max(
      leftLines.length,
      rightLines.length,
      maxVisible + 4,
    );

    // Pad panes to equal height
    while (leftLines.length < bodyHeight) leftLines.push("");
    while (rightLines.length < bodyHeight) rightLines.push("");

    // Top divider — matches compact widget style, no outer box border
    lines.push(th.fg("border", hBar(width)));

    // Body rows: left │ right (no outer borders)
    for (let i = 0; i < bodyHeight; i++) {
      const l = truncateToWidth(pad(leftLines[i] ?? "", leftW), leftW);
      const r = truncateToWidth(pad(rightLines[i] ?? "", rightW), rightW);
      lines.push(l + th.fg("border", "│") + r);
    }

    // Help separator + help text (no outer borders)
    lines.push(th.fg("borderMuted", hBar(width)));
    const helpText = th.fg(
      "dim",
      " ↑↓/jk nav • ←→/hl status • Enter toggle • s select • e edit • p plan • n new • N subtask • A archive • P publish • U unpublish • Ctrl-d/u scroll • q close",
    );
    lines.push(truncateToWidth(pad(helpText, width), width));
    lines.push(th.fg("border", hBar(width)));

    this.cachedWidth = width;
    this.cachedLines = lines;
    return lines;
  }

  invalidate(): void {
    this.cachedWidth = undefined;
    this.cachedLines = undefined;
  }

  // ── Helpers ─────────────────────────────────────────────────────────

  private adjustScroll(maxVisible: number): void {
    if (this.cursor < this.scrollOffset) {
      this.scrollOffset = this.cursor;
    } else if (this.cursor >= this.scrollOffset + maxVisible) {
      this.scrollOffset = this.cursor - maxVisible + 1;
    }
  }

  private renderStatus(status: string, _th: Theme): string {
    return colorStatus(status);
  }

  private countStatuses(tasks: Task[]): Record<string, number> {
    const counts: Record<string, number> = {
      TODO: 0,
      STARTED: 0,
      WAITING: 0,
      DONE: 0,
      CANCELLED: 0,
    };
    const walk = (ts: Task[]) => {
      for (const t of ts) {
        counts[t.status] = (counts[t.status] ?? 0) + 1;
        walk(this.taskChildren(t));
      }
    };
    walk(tasks);
    return counts;
  }
}

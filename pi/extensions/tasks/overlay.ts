/**
 * Tasks overlay component — split-pane: task tree (left) + description (right).
 */

import type { Theme } from "@earendil-works/pi-coding-agent";
import {
  matchesKey,
  truncateToWidth,
  visibleWidth,
  wrapTextWithAnsi,
  type TUI,
} from "@earendil-works/pi-tui";
import type { LinkedIssue, Task } from "./parser.ts";
import {
  parseLinkTemplates,
  getTaskBlockers,
  getTaskHandoff,
  getTaskId,
  isTaskReady,
  type TaskBlocker,
} from "./parser.ts";
import {
  colorIssues,
  colorLocal,
  colorPriority,
  colorStatus,
  colorTags,
} from "./status-colors.ts";
/** A flattened row for display & navigation. */
interface FlatRow {
  task: Task;
  depth: number;
  collapsed: boolean;
  hasChildren: boolean;
  /** True when this task's :CUSTOM_ID: matches the UUID in TASKS.local.org. */
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
  private statusUpdateInFlight = false;

  private collapsedSet = new WeakSet<Task>();

  /** Cache of `#+LINK:` declarations per source-file content. Reset on `refreshTasks`. */
  private linkTemplateCache = new Map<string, ReturnType<typeof parseLinkTemplates>>();

  /** UUID of the currently selected task (from TASKS.local.org), or null. */
  private selectedId: string | null;

  constructor(
    private tasks: Task[],
    private cwd: string,
    private tasksPath: string,
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
      anchor: Task | null,
      relation: "sibling" | "child",
    ) => void,
    /** Request publish (local → shared) after overlay closes. */
    private onPublish?: (task: Task) => void,
    /** Request unpublish (shared → local) after overlay closes. */
    private onUnpublish?: (task: Task) => void,
    /** Request retrospective change-record creation when a task closes
        without an existing #+IMPORT:.  Returns true to indicate the request
        was accepted (overlay should close); false to keep the overlay open. */
    private onCreateChangeRecord?: (task: Task) => boolean,
    /** Request a `* Summary` refresh when a task with an existing #+IMPORT:
        closes and the linked change-record either lacks `* Summary` or has
        not been touched since the parent task started.  Returns true when
        the workflow was queued (overlay should close); false otherwise. */
    private onRefreshSummary?: (task: Task) => boolean,
    selectedId: string | null = null,
    /** Called when the user toggles selection; should write TASKS.local.org. */
    private onSelectionChange?: (id: string | null) => Promise<void>,
    /** Called when the user presses `J` to open linked-issue URLs. */
    private onOpenUrls?: (urls: string[]) => Promise<void>,
    /** Display a one-shot notification (status footer / toast). */
    private onNotify?: (message: string, kind?: "info" | "warn" | "error") => void,
    /** Called when a task's status is cycled (via →/← / l/h). The canonical
        cycle order lives in `ot` (`ot status --cycle`); the overlay passes
        only a direction and receives the resolved status plus a fresh tree. */
    private onCycleStatus?: (
      task: Task,
      direction: "forward" | "back",
    ) => Promise<{ tasks: Task[]; prevStatus: string; status: string } | null>,
    /** Called when a task's priority is cycled (via shift+→/←). The
        canonical cycle order (including the unset slot) lives in `ot`
        (`ot priority --cycle`): forward from unset is A, back from unset
        is D. The overlay passes only a direction and receives a fresh
        tree. */
    private onCyclePriority?: (
      task: Task,
      direction: "forward" | "back",
    ) => Promise<{ tasks: Task[] } | null>,
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
  refreshTasks(newTasks: Task[], selectedId: string | null = this.selectedId, tasksPath: string = this.tasksPath): void {
    this.selectedId = selectedId;
    this.tasksPath = tasksPath;
    // Remember which task the cursor is on so we can restore position after
    // rebuilding (new task objects from disk won't share references).
    const cursorId = this.rows[this.cursor]
      ? getTaskId(this.rows[this.cursor]!.task)
      : null;

    this.tasks = newTasks;
    this.linkTemplateCache = new Map();
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

  /** Resolve `#+LINK:` templates for a task by reading its `sourceContent`. */
  private linkTemplatesFor(task: Task): ReturnType<typeof parseLinkTemplates> {
    const content = task.effectiveSourceContent ?? task.sourceContent ?? "";
    if (!content) return new Map();
    const cached = this.linkTemplateCache.get(content);
    if (cached) return cached;
    const value = parseLinkTemplates(content);
    this.linkTemplateCache.set(content, value);
    return value;
  }

  /** Resolve `:LINKED_ISSUES:` for a task using cached `#+LINK:` templates. */
  private linkedIssuesFor(task: Task): LinkedIssue[] {
    // `ot list` already resolves :LINKED_ISSUES: via #+LINK: templates and
    // ships them on the wire, so the overlay just consumes them.
    return task.linkedIssues ?? [];
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

  handleInput(data: string): void {
    this.dispatchInput(data);
  }

  /** Dispatch a key press to the appropriate handler. */
  private dispatchInput(data: string): string {
    // Close on Escape, or on the same shortcut that opened the overlay
    // (`alt+t`) so the activation key acts as a toggle.
    if (matchesKey(data, "escape") || matchesKey(data, "alt+t")) {
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

    // Cycle priority (shift+arrows). Checked before the bare arrow
    // matches so "right" cannot swallow "shift+right".
    if (matchesKey(data, "shift+right")) {
      void this.cyclePriority(1);
      return "priority-cycle-fwd";
    }
    if (matchesKey(data, "shift+left")) {
      void this.cyclePriority(-1);
      return "priority-cycle-back";
    }

    // Cycle status forward
    if (matchesKey(data, "right") || matchesKey(data, "l")) {
      void this.cycleStatus(1);
      return "status-cycle-fwd";
    }

    // Cycle status backward
    if (matchesKey(data, "left") || matchesKey(data, "h")) {
      void this.cycleStatus(-1);
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

  private async cycleStatus(direction: 1 | -1): Promise<void> {
    if (this.statusUpdateInFlight) {
      this.onNotify?.("Status update already in progress; wait for it to finish.", "warn");
      return;
    }
    const row = this.rows[this.cursor];
    if (!row || !this.onCycleStatus) return;
    const wasClosed = row.task.status === "DONE" || row.task.status === "CANCELLED";
    this.statusUpdateInFlight = true;
    try {
      // `ot status --cycle` owns the cycle order and the transition; we only
      // pass the direction and apply the resolved result.
      const id = getTaskId(row.task);
      const result = await this.onCycleStatus(row.task, direction === 1 ? "forward" : "back");
      let updated = row.task;
      if (result) {
        this.refreshTasks(result.tasks, this.selectedId);
        updated = this.findTaskById(this.tasks, id) ?? row.task;
      }
      this.invalidate();
      if (result?.status === "DONE" && !wasClosed) {
        if (!updated.importPath && this.onCreateChangeRecord) {
          const accepted = this.onCreateChangeRecord(updated);
          if (accepted) this.done(undefined);
        } else if (updated.importPath && this.onRefreshSummary) {
          const accepted = this.onRefreshSummary(updated);
          if (accepted) this.done(undefined);
        }
      }
    } catch (err) {
      this.onNotify?.(`Status update failed: ${(err as Error).message}`, "error");
    } finally {
      this.statusUpdateInFlight = false;
    }
  }

  /** Cycle the cursor task's priority via `ot priority --cycle`. Shares the
   *  status in-flight guard so only one graph mutation runs at a time. */
  private async cyclePriority(direction: 1 | -1): Promise<void> {
    if (this.statusUpdateInFlight) {
      this.onNotify?.("Update already in progress; wait for it to finish.", "warn");
      return;
    }
    const row = this.rows[this.cursor];
    if (!row || !this.onCyclePriority) return;
    this.statusUpdateInFlight = true;
    try {
      // `ot priority --cycle` owns the cycle order (unset → A forward,
      // unset → D back); we only pass the direction and apply the result.
      const result = await this.onCyclePriority(row.task, direction === 1 ? "forward" : "back");
      if (result) {
        this.refreshTasks(result.tasks, this.selectedId);
      }
      this.invalidate();
    } catch (err) {
      this.onNotify?.(`Priority update failed: ${(err as Error).message}`, "error");
    } finally {
      this.statusUpdateInFlight = false;
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
    // Placement policy lives in `ot create --relative-to <id> --as ...`. We
    // pass the cursor task as the anchor (or null for a top-level create) and
    // the relation; `ot` derives parent/after/local/source.
    const row = this.rows[this.cursor];
    this.onNewTask(row?.task ?? null, asChild ? "child" : "sibling");
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

  /** Find a task anywhere in the graph by its :CUSTOM_ID: property value. */
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
   * Open every URL in the cursor task's `:LINKED_ISSUES:` in the user's browser via `onOpenUrls`. Caps at 5 to avoid spawning a tab storm. Typed links whose prefix has no `#+LINK:` declaration are skipped with a notification.
   */
  private openLinkedIssues(): void {
    const row = this.rows[this.cursor];
    if (!row) return;
    const issues = this.linkedIssuesFor(row.task);
    if (issues.length === 0) return; // silent no-op when property absent

    const resolvable = issues.filter((i) => i.url !== null);
    const unresolvable = issues.filter((i) => i.url === null);
    if (resolvable.length === 0) {
      const first = unresolvable[0]?.error ?? "No resolvable linked-issue URLs.";
      this.onNotify?.(first, "warn");
      return;
    }

    const CAP = 5;
    const toOpen = resolvable.slice(0, CAP).map((i) => i.url!);
    if (resolvable.length > CAP) {
      this.onNotify?.(
        `Opening first ${CAP} of ${resolvable.length} linked issues.`,
        "info",
      );
    } else if (unresolvable.length > 0) {
      this.onNotify?.(
        `Opening ${resolvable.length} linked issues; ${unresolvable.length} skipped (${unresolvable[0]?.error ?? "unresolved"}).`,
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
    if (!id) return; // Can't select a task with no :CUSTOM_ID:
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
    // Selection is written by onSelectionChange above. Do not call save():
    // under the `ot` cutover the in-memory tree is a hydrated wire graph,
    // not the protocol writer.
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


    leftLines.push(th.fg("borderMuted", ` ${this.tasksPath}`));
    leftLines.push("");

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
      " ↑↓/jk nav • ←→/hl status • ⇧←→ priority • Enter toggle • s select • e edit • p plan • n new • N subtask • A archive • P publish • U unpublish • Ctrl-d/u scroll • Esc/Alt-t close",
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

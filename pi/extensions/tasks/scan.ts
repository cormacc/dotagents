/**
 * Prior-art summary scanner.
 *
 * Walks the loaded task graph (active TASKS.org tasks + archived
 * TASKS.archive.org tasks + their #+IMPORT: chains) and returns a flat
 * `ScanRow[]` capturing each task's heading metadata plus its
 * change-record's `* Summary` body (or "missing" / "no record" signal).
 *
 * Designed for agents drafting a plan: scan many tasks, relevance-filter
 * the rows, then load specific change-records via `org_read_section`.
 *
 * Pure helper. Path resolution and file I/O happen in `index.ts`; the
 * helper accepts a `readChangeRecord` callback so tests can run fs-free.
 */

import { readSection } from "./section.ts";
import { getTaskId, type Task } from "./parser.ts";

export type RecordSummary =
  | { found: true; body: string }
  | { found: false };

export interface ScanRow {
  /** Org `:CUSTOM_ID:` value. */
  id: string;
  /** Task heading text (no status / priority cookie / tags). */
  summary: string;
  /** TODO / STARTED / WAITING / DONE / CANCELLED. */
  status: string;
  /** `A` / `B` / `C` / `D` (from `[#X]`) or null. */
  priority: string | null;
  /** Org tag suffix on the heading, e.g. `["backend", "security"]`. */
  tags: string[];
  /** Absolute path of the file that defines this task, when known. */
  sourcePath: string | null;
  /**
   * `#+IMPORT:` value verbatim (as it appears on disk: bare path,
   * `[[file:...]]`, or `[[file:...][label]]`). `null` when the task
   * has no linked change-record.
   */
  importPath: string | null;
  /**
   * The linked change-record's `* Summary` slice, with body truncated
   * to `maxBodyChars`. `null` when the task has no `#+IMPORT:` at all.
   * `{ found: false }` when the record exists but lacks `* Summary`
   * (or is unreadable / out-of-root — both surface the same way to
   * keep the scanner robust, matching `loadLinkedPlans`).
   */
  recordSummary: RecordSummary | null;
  /**
   * True when the linked change-record carries a top-level `* Context`
   * heading. The body is intentionally *not* inlined; the agent fetches
   * it via `org_read_section` when relevance scoring says it's worth
   * pulling further into context. False whenever `recordSummary` is
   * null (no record) or the record cannot be read.
   */
  hasContext: boolean;
}

export interface ScanOptions {
  /**
   * Which top-level files contribute tasks. The classification is by
   * the *root* of each task's tree:
   *   - `"active"`   — descendants of `activeRoots` only.
   *   - `"archived"` — descendants of `archivedRoots` only.
   *   - `"all"`      — both. Default.
   */
  scope?: "active" | "archived" | "all";
  /**
   * Optional tag whitelist. OR-semantics: a row is included when its
   * task carries any listed tag. Empty / unset disables tag filtering.
   */
  tags?: string[];
  /**
   * Cap on `recordSummary.body` length. Bodies longer than this are
   * truncated, with a trailing `…` sentinel marking the truncation.
   * Default: {@link DEFAULT_MAX_BODY_CHARS}.
   */
  maxBodyChars?: number;
}

export interface ScanInput {
  /** Top-level task trees parsed from `TASKS.org` (+ its TASKS.local.org / file-level imports). */
  activeRoots: Task[];
  /** Top-level task trees parsed from `TASKS.archive.org`. */
  archivedRoots: Task[];
  /**
   * Resolve and return the contents of a task's linked change-record.
   * Returns `null` when the task has no `#+IMPORT:`, when the path
   * resolves outside the project root, or when the file cannot be
   * read. Callers are responsible for path resolution and sandboxing;
   * the scanner stays fs-free so the unit suite can pass synthetic
   * inputs.
   */
  readChangeRecord(task: Task): string | null;
}

/** Default cap on inlined `* Summary` body length. */
export const DEFAULT_MAX_BODY_CHARS = 500;
const TRUNCATION_SENTINEL = "\u2026";

function truncateBody(body: string, max: number): string {
  if (max <= 0) return "";
  if (body.length <= max) return body;
  // Reserve one char for the sentinel so the output never exceeds `max`.
  return body.slice(0, Math.max(0, max - 1)) + TRUNCATION_SENTINEL;
}

function matchesTagFilter(task: Task, filter: string[] | undefined): boolean {
  if (!filter || filter.length === 0) return true;
  const want = new Set(filter);
  return task.tags.some((t) => want.has(t));
}

function buildRow(
  task: Task,
  id: string,
  readChangeRecord: (t: Task) => string | null,
  maxBodyChars: number,
): ScanRow {
  let recordSummary: RecordSummary | null = null;
  let hasContext = false;
  if (task.importPath) {
    const content = readChangeRecord(task);
    if (content === null) {
      // Has #+IMPORT: but content unavailable (missing / unreadable /
      // out-of-root). Surface as "record exists, Summary not found"
      // so the agent has the same signal `evaluateSummaryRefresh`
      // already produces and doesn't try to deepen this row.
      recordSummary = { found: false };
    } else {
      const summary = readSection(content, "Summary");
      recordSummary = summary.found
        ? { found: true, body: truncateBody(summary.body, maxBodyChars) }
        : { found: false };
      hasContext = readSection(content, "Context").found;
    }
  }
  return {
    id,
    summary: task.summary,
    status: task.status,
    priority: task.priority,
    tags: [...task.tags],
    sourcePath: task.sourcePath ?? null,
    importPath: task.importPath ?? null,
    recordSummary,
    hasContext,
  };
}

function* walk(tasks: readonly Task[]): Generator<Task> {
  for (const task of tasks) {
    yield task;
    yield* walk(task.children);
    if (task.importChildren) yield* walk(task.importChildren);
  }
}

/**
 * Walk the active + archived task graphs and return one
 * {@link ScanRow} per task whose `:CUSTOM_ID:` is set and which passes
 * the optional `scope` / `tags` filters.
 *
 * Order: rows are emitted in walker order (depth-first, file-position
 * within each root). Active roots are walked before archived roots
 * when `scope` is `"all"`. Agents that want chronological or
 * relevance ordering re-sort the returned array themselves.
 *
 * Tasks without a `:CUSTOM_ID:` are skipped — without an id the agent
 * cannot refer back to the row to fetch more context, so the entry
 * adds noise without recovery value. Doctor flags such tasks
 * separately.
 */
export function scanSummaries(
  input: ScanInput,
  options: ScanOptions = {},
): ScanRow[] {
  const scope = options.scope ?? "all";
  const maxBodyChars = options.maxBodyChars ?? DEFAULT_MAX_BODY_CHARS;
  const rows: ScanRow[] = [];

  const emit = (roots: Task[]) => {
    for (const task of walk(roots)) {
      const id = getTaskId(task);
      if (!id) continue;
      if (!matchesTagFilter(task, options.tags)) continue;
      rows.push(buildRow(task, id, input.readChangeRecord, maxBodyChars));
    }
  };

  if (scope === "active" || scope === "all") emit(input.activeRoots);
  if (scope === "archived" || scope === "all") emit(input.archivedRoots);

  return rows;
}

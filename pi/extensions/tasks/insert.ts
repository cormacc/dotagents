/**
 * Cross-extension task insertion helper.
 *
 * `buildTaskBlock` is the single source of truth for assembling an
 * org-mode task block from structured fields. Consumed today by the
 * Jira `jira_clone_apply` tool and exposed publicly so future
 * cross-tracker integrations (github / linear / gitlab / `/jira create`)
 * never reimplement priority mapping, drawer ordering, or label
 * tagging.
 *
 * Pure-function module — no file I/O. The companion `tasks_insert_task`
 * pi tool lives in `index.ts` and wraps this builder with file-insertion
 * + idempotency.
 */

import { randomUUID } from "node:crypto";
import { readFile, realpath, writeFile } from "node:fs/promises";
import { dirname, isAbsolute, relative, resolve } from "node:path";
import {
  createdLogEntry,
  getDrawerProperty,
  getTaskId,
  formatOrgTimestamp,
  parseTasks,
  type Task,
} from "./parser.ts";

/** Args accepted by {@link buildTaskBlock}. */
export interface BuildTaskArgs {
  /** Task heading text. Required. Trailing whitespace is trimmed. */
  summary: string;
  /**
   * Priority *name* (Jira convention). One of:
   * `Highest` → `#A`, `High` → `#B`, `Medium` → `#C`,
   * `Low`|`Lowest` → `#D`. Anything else (including null/undefined or
   * the empty string) yields no priority cookie.
   *
   * Case-insensitive. Whitespace-trimmed.
   */
  priorityName?: string | null;
  /** Body text inserted verbatim after the drawer. May be empty/null. */
  body?: string | null;
  /**
   * External-tracker references to write into `:LINKED_ISSUES:`. Tokens
   * are written verbatim, whitespace-joined. Empty array → no
   * `:LINKED_ISSUES:` line.
   */
  linkedIssues?: string[] | null;
  /**
   * Labels appended to the heading as org tags
   * (`:label1:label2:` after the summary). Tokens are emitted verbatim;
   * callers are responsible for sanitising disallowed characters
   * (org tags accept `[a-zA-Z0-9_@]`).
   * Empty array → no tags.
   */
  labels?: string[] | null;
  /**
   * When set, the assembled block is rendered as a level-3 heading
   * (i.e. a subtask under an existing parent task). When unset, the
   * block is level-2 (a top-level task under a section heading).
   *
   * The parent ID itself is *not* embedded in the drawer — it's used
   * solely to pick the heading level. The file-side helper is
   * responsible for placing the block inside the parent task's
   * subtree.
   */
  parentId?: string | null;
  /**
   * Override the generated `:ID:` UUID. Used by the file-side helper
   * to surface the new task's ID back to its caller, and by tests to
   * keep snapshots deterministic. Defaults to a fresh UUID v4.
   */
  id?: string;
  /**
   * Override the `:CREATED:` timestamp body (without surrounding
   * brackets). Defaults to the current local time formatted as
   * `YYYY-MM-DD Day HH:MM`. Injectable for tests.
   */
  createdAt?: string;
}

/** Output of {@link buildTaskBlock}. */
export interface BuiltTaskBlock {
  /** Heading line, e.g. `** TODO [#A] Summary :foo:bar:`. */
  heading: string;
  /**
   * Properties drawer block, including the `:PROPERTIES:` / `:END:`
   * fences. Always emitted (the `:ID:` line guarantees non-empty
   * content). Multi-line string, no trailing newline.
   */
  drawer: string;
  /**
   * Body text as supplied by the caller, normalised to have no
   * leading or trailing newlines. May be the empty string.
   */
  body: string;
  /**
   * Fully-assembled org block ready to splice into a section. Always
   * ends with a single trailing newline so the file-side helper can
   * concatenate without bookkeeping. Layout:
   *
   * ```
   * ** TODO [#A] Summary :foo:bar:
   * :PROPERTIES:
   * :ID: <uuid>
   * :CREATED: [<timestamp>]
   * :LINKED_ISSUES: KEY1 KEY2
   * :END:
   * <body>
   * ```
   *
   * Body is omitted when empty; the trailing newline still applies.
   */
  block: string;
  /** The `:ID:` UUID written into the drawer. */
  id: string;
}

/**
 * Map a Jira priority name to an org priority cookie character
 * (`A`/`B`/`C`/`D`), or null when the input doesn't match a known bucket.
 *
 * Exported for parity-checking in tests / future tracker integrations.
 */
export function mapPriorityName(name: string | null | undefined): string | null {
  if (!name) return null;
  const normalised = name.trim().toLowerCase();
  switch (normalised) {
    case "highest": return "A";
    case "high":    return "B";
    case "medium":  return "C";
    case "low":
    case "lowest":  return "D";
    default:        return null;
  }
}

/**
 * Render a list of labels as an org tag suffix (`:foo:bar:`), or the
 * empty string when there are no labels.
 */
function renderTagSuffix(labels: string[] | null | undefined): string {
  if (!labels || labels.length === 0) return "";
  const filtered = labels.filter((l) => l && l.length > 0);
  if (filtered.length === 0) return "";
  return `:${filtered.join(":")}:`;
}

/**
 * Build an org-task block (heading + drawer + body) from structured
 * fields. Pure: no file I/O, no random side-effects beyond the default
 * UUID + timestamp.
 *
 * The status is hard-coded to `TODO`. Cloned issues always start in
 * the local TODO state regardless of their tracker-side status — local
 * status is a *contributor's* signal, not the tracker's.
 */
export function buildTaskBlock(args: BuildTaskArgs): BuiltTaskBlock {
  const summary = args.summary.trimEnd();
  if (!summary) {
    throw new Error("buildTaskBlock: summary is required");
  }

  const id = args.id ?? randomUUID();
  const createdAt = args.createdAt ?? formatOrgTimestamp();
  const level = args.parentId ? 3 : 2;
  const stars = "*".repeat(level);

  const priorityChar = mapPriorityName(args.priorityName);
  const priorityCookie = priorityChar ? `[#${priorityChar}] ` : "";
  const tagSuffix = renderTagSuffix(args.labels);
  const heading =
    `${stars} TODO ${priorityCookie}${summary}` +
    (tagSuffix ? ` ${tagSuffix}` : "");

  const drawerLines = [
    ":PROPERTIES:",
    `:ID: ${id}`,
    `:CREATED: [${createdAt}]`,
  ];
  const linkedIssues = (args.linkedIssues ?? []).filter((t) => t && t.length > 0);
  if (linkedIssues.length > 0) {
    drawerLines.push(`:LINKED_ISSUES: ${linkedIssues.join(" ")}`);
  }
  drawerLines.push(":END:");
  const drawer = drawerLines.join("\n");

  const body = (args.body ?? "").replace(/^\n+/, "").replace(/\n+$/, "");

  const blockLines = [heading, drawer, ":LOGBOOK:", createdLogEntry(createdAt), ":END:"];
  if (body.length > 0) blockLines.push(body);
  const block = blockLines.join("\n") + "\n";

  return { heading, drawer, body, block, id };
}

// ─── File-side insertion + idempotency ──────────────────────────────
//
// `insertTaskIntoFile` is the cross-extension entry point consumed by
// `jira_clone_apply` (today) and any future tracker integration. It is
// also the executor body for the `tasks_insert_task` pi tool registered
// in `index.ts`. Kept here (rather than in `index.ts`) so:
//
// 1. It has no `pi-tui` / `pi-coding-agent` dependency and can be
//    unit-tested directly via `tsx`.
// 2. The cross-extension contract is reusable as a plain JS function
//    without round-tripping through the LLM tool registry.

/** Recognised duplicate / placement failure modes. */
export type InsertErrorReason =
  | "duplicate"
  | "section_not_found"
  | "empty_summary"
  | "file_unreadable"
  | "path_outside_project";

/** Args accepted by {@link insertTaskIntoFile}. */
export interface InsertTaskArgs extends BuildTaskArgs {
  /**
   * Absolute path of the org file to insert into. Most callers use
   * `<cwd>/TASKS.org`; the field is left flexible so the same helper
   * can splice into a `TASKS.local.org` draft or a linked plan.
   */
  file: string;
  /**
   * Section heading text under which the task is appended
   * (e.g. `"Improvements"`). Matched as a level-1 heading in the
   * target file. Tags on the heading line are tolerated.
   */
  section: string;
  /**
   * When true and `section` does not yet exist in the file, append a
   * new `* <section>` heading at the end of the file before splicing.
   * Default: false.
   */
  allowCreateSection?: boolean;
  /**
   * Additional org files to scan for `:LINKED_ISSUES:` collisions.
   * The target file is always scanned. Callers typically pass the
   * sibling file (e.g. `TASKS.local.org` when inserting into
   * `TASKS.org`, and vice-versa) so duplicates are detected
   * regardless of which slot the previous clone landed in.
   *
   * Imports referenced via `#+IMPORT:` from any scanned file are
   * recursively walked.
   */
  alsoScan?: string[];
  /** Project root used to sandbox target and scan paths. Defaults to cwd. */
  projectRoot?: string;
}

/** Successful insertion result. */
export interface InsertSuccess {
  status: "inserted";
  /** UUID written into the new task's `:ID:` drawer line. */
  id: string;
  /** Absolute path of the file mutated. */
  file: string;
  /** 1-indexed line where the new heading lives after insertion. */
  line: number;
}

/** Refusal — duplicate :LINKED_ISSUES: token already present. */
export interface InsertDuplicate {
  status: "duplicate";
  /** `:ID:` of the pre-existing task that owns the conflicting token. */
  existingId: string | null;
  /** Absolute path of the file containing the pre-existing task. */
  existingFile: string;
  /** The `:LINKED_ISSUES:` token that triggered the refusal. */
  conflictingToken: string;
}

/** Refusal — section heading not found and `allowCreateSection` false. */
export interface InsertSectionMissing {
  status: "section_not_found";
  file: string;
  section: string;
}

/** Refusal — caller mis-configured the request. */
export interface InsertError {
  status: "error";
  reason: InsertErrorReason;
  message: string;
}

export type InsertResult =
  | InsertSuccess
  | InsertDuplicate
  | InsertSectionMissing
  | InsertError;

/**
 * Match a level-1 heading of the form `* <section>` (tags tolerated).
 * Tags on the heading line are tolerated by stripping a trailing `:tag:` run.
 */
function isMatchingSectionHeading(line: string, sectionName: string): boolean {
  const m = /^\*\s+(.+?)\s*$/.exec(line);
  if (!m) return false;
  let text = m[1]!;
  // Strip a trailing `:tag1:tag2:` run.
  const tagMatch = /\s+:[\w@:]+:\s*$/.exec(text);
  if (tagMatch) text = text.slice(0, tagMatch.index).trimEnd();
  return text.trim() === sectionName.trim();
}

/** Index of the next top-level heading (`* `) at or after `from`, or -1. */
function nextTopLevelHeadingIdx(lines: string[], from: number): number {
  for (let i = from; i < lines.length; i++) {
    if (/^\*\s+\S/.test(lines[i] ?? "")) return i;
  }
  return -1;
}

/** Read a file as utf-8, returning null when missing/unreadable. */
async function readMaybe(path: string): Promise<string | null> {
  try {
    return await readFile(path, "utf-8");
  } catch {
    return null;
  }
}

/**
 * Recursively collect every task across `paths` and any `#+IMPORT:` they
 * reference (file-level imports + per-task `#+IMPORT:` keywords).
 * `visited` is the absolute-path set used to break cycles.
 *
 * Returns an array of `{ task, file }` pairs so callers can attribute
 * collisions back to the originating file.
 */
function isWithinRoot(path: string, root: string): boolean {
  const rel = relative(root, path);
  return rel === "" || (!rel.startsWith("..") && !isAbsolute(rel));
}

async function resolveExistingOrParent(path: string): Promise<string> {
  try {
    return await realpath(path);
  } catch {
    try {
      const parent = await realpath(dirname(path));
      return resolve(parent, path.split(/[\\/]/).pop() ?? "");
    } catch {
      return resolve(path);
    }
  }
}

async function sandboxPath(path: string, projectRoot: string): Promise<{ ok: true; path: string } | { ok: false; path: string }> {
  const root = await resolveExistingOrParent(projectRoot);
  const abs = isAbsolute(path) ? path : resolve(root, path);
  const real = await resolveExistingOrParent(abs);
  return isWithinRoot(real, root) ? { ok: true, path: real } : { ok: false, path: real };
}

async function collectAllTasks(
  paths: string[],
  projectRoot: string,
  visited = new Set<string>(),
): Promise<{ task: Task; file: string }[]> {
  const out: { task: Task; file: string }[] = [];

  const walk = async (absPath: string) => {
    if (visited.has(absPath)) return;
    visited.add(absPath);
    const content = await readMaybe(absPath);
    if (content === null) return;
    const { tasks, fileImports } = parseTasks(content, { sourcePath: absPath });
    const dir = dirname(absPath);

    // Collect imports *after* walking the in-file tasks so we don't
    // recurse mid-iteration. Ordering doesn't matter for the duplicate
    // check itself — first hit wins.
    const walkLater: string[] = [];
    const recurseTasks = async (ts: Task[]) => {
      for (const t of ts) {
        out.push({ task: t, file: absPath });
        await recurseTasks(t.children);
        if (t.importPath) {
          const importAbs = isAbsolute(t.importPath)
            ? t.importPath
            : resolve(dir, t.importPath);
          const sandboxed = await sandboxPath(importAbs, projectRoot);
          if (sandboxed.ok) walkLater.push(sandboxed.path);
        }
      }
    };
    await recurseTasks(tasks);
    for (const fp of fileImports) {
      const importAbs = isAbsolute(fp) ? fp : resolve(dir, fp);
      const sandboxed = await sandboxPath(importAbs, projectRoot);
      if (sandboxed.ok) walkLater.push(sandboxed.path);
    }
    for (const next of walkLater) await walk(next);
  };

  for (const p of paths) {
    await walk(p);
  }
  return out;
}

/**
 * Scan `tasks` for any `:LINKED_ISSUES:` token that overlaps with
 * `tokens`. First collision wins. Returns null when no collision found.
 */
function findDuplicate(
  collected: { task: Task; file: string }[],
  tokens: string[],
): InsertDuplicate | null {
  if (tokens.length === 0) return null;
  const wanted = new Set(tokens);
  for (const { task, file } of collected) {
    const linked = getDrawerProperty(task, "LINKED_ISSUES");
    if (!linked) continue;
    const existing = linked.split(/\s+/).filter((t) => t.length > 0);
    for (const tok of existing) {
      if (wanted.has(tok)) {
        return {
          status: "duplicate",
          existingId: getTaskId(task),
          existingFile: file,
          conflictingToken: tok,
        };
      }
    }
  }
  return null;
}

/**
 * Splice `block` into `content` under the `* <section>` heading.
 *
 * - Locates the section heading (`isMatchingSectionHeading`).
 * - Inserts immediately before the next level-1 heading, or at EOF
 *   when none follows.
 * - When the section is absent and `allowCreateSection` is true, a
 *   new `* <section>` heading is appended to the file.
 *
 * Returns the new file content + the 1-indexed line of the spliced
 * heading. Returns null when the section is missing and creation is
 * disallowed.
 */
function spliceIntoSection(
  content: string,
  section: string,
  block: string,
  allowCreateSection: boolean,
): { content: string; line: number } | null {
  const lines = content.split("\n");
  const headingIdx = lines.findIndex((line) => isMatchingSectionHeading(line, section));

  const blockLines = block.replace(/\n+$/, "").split("\n");

  if (headingIdx === -1) {
    if (!allowCreateSection) return null;
    // Append at end of file: ensure exactly one blank line before the
    // new section, then the heading, then the block.
    while (lines.length > 0 && lines[lines.length - 1] === "") lines.pop();
    const append: string[] = [];
    if (lines.length > 0) append.push("");
    append.push(`* ${section}`);
    append.push("");
    append.push(...blockLines);
    const insertedAt = lines.length + append.indexOf(blockLines[0]!) + 1;
    lines.push(...append);
    return {
      content: lines.join("\n").replace(/\n*$/, "\n"),
      line: insertedAt,
    };
  }

  const nextIdx = nextTopLevelHeadingIdx(lines, headingIdx + 1);
  const insertBefore = nextIdx === -1 ? lines.length : nextIdx;

  // Trim trailing blank lines belonging to this section so the spliced
  // block doesn't push the next heading further away on each insert.
  let tail = insertBefore;
  while (tail > headingIdx + 1 && (lines[tail - 1] ?? "") === "") tail--;

  // Ensure exactly one blank line between the existing section content
  // and the new task block.
  const insertion: string[] = [];
  if (tail > headingIdx + 1) insertion.push("");
  insertion.push(...blockLines);
  if (nextIdx !== -1) insertion.push("");

  lines.splice(tail, insertBefore - tail, ...insertion);

  // Recompute the heading line after splice. The section heading is
  // unchanged; the inserted block sits at `tail` (plus the leading
  // blank line if we added one).
  const blockHeadingOffset = tail + (insertion[0] === "" ? 1 : 0);
  return {
    content: lines.join("\n").replace(/\n*$/, "\n"),
    line: blockHeadingOffset + 1, // 1-indexed
  };
}

/**
 * Insert a new task into an org file under the named section,
 * refusing on duplicate `:LINKED_ISSUES:` overlap.
 *
 * Pure-ish: reads `args.file` (and `args.alsoScan`) for duplicate
 * detection, writes back `args.file` on success. No UI.
 */
export async function insertTaskIntoFile(
  args: InsertTaskArgs,
): Promise<InsertResult> {
  if (!args.summary || args.summary.trim().length === 0) {
    return {
      status: "error",
      reason: "empty_summary",
      message: "`summary` is required and must be non-empty.",
    };
  }

  const projectRoot = isAbsolute(args.projectRoot ?? "")
    ? args.projectRoot!
    : resolve(args.projectRoot ?? ".");
  const targetSandbox = await sandboxPath(args.file, projectRoot);
  if (!targetSandbox.ok) {
    return {
      status: "error",
      reason: "path_outside_project",
      message: `Target file resolves outside project root: ${targetSandbox.path}`,
    };
  }
  const targetAbs = targetSandbox.path;

  const scanPaths = [targetAbs];
  for (const p of args.alsoScan ?? []) {
    const scanSandbox = await sandboxPath(p, projectRoot);
    if (!scanSandbox.ok) {
      return {
        status: "error",
        reason: "path_outside_project",
        message: `Scan file resolves outside project root: ${scanSandbox.path}`,
      };
    }
    scanPaths.push(scanSandbox.path);
  }

  const tokens = (args.linkedIssues ?? []).filter((t) => t && t.length > 0);
  if (tokens.length > 0) {
    const collected = await collectAllTasks(scanPaths, projectRoot);
    const duplicate = findDuplicate(collected, tokens);
    if (duplicate) return duplicate;
  }

  const built = buildTaskBlock(args);

  const existingContent = await readMaybe(targetAbs);
  if (existingContent === null && !args.allowCreateSection) {
    // The plan permits inserting into a missing file only when the
    // caller is willing to scaffold, since a missing file implies a
    // missing section. Surface a structured refusal so the caller can
    // decide whether to retry with `allowCreateSection: true`.
    return {
      status: "section_not_found",
      file: targetAbs,
      section: args.section,
    };
  }
  const baseContent = existingContent ?? "";

  const spliced = spliceIntoSection(
    baseContent,
    args.section,
    built.block,
    args.allowCreateSection ?? false,
  );
  if (!spliced) {
    return {
      status: "section_not_found",
      file: targetAbs,
      section: args.section,
    };
  }

  await writeFile(targetAbs, spliced.content, "utf-8");

  return {
    status: "inserted",
    id: built.id,
    file: targetAbs,
    line: spliced.line,
  };
}

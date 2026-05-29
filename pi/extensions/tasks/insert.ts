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
 * `buildTaskBlock` remains pure for compatibility tests and future
 * tracker integrations. The file-side `insertTaskIntoFile` entrypoint
 * is now a thin compatibility shim over `ot create`, so durable
 * insertion/idempotency behaviour lives in the Babashka CLI.
 */

import { randomUUID } from "node:crypto";
import { basename, isAbsolute, resolve } from "node:path";
import {
  createdLogEntry,
  formatOrgTimestamp,
} from "./parser.ts";
import { runOt, type OtEnvelope } from "./ot.ts";

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
   * Override the generated `:CUSTOM_ID:` UUID. Used by the file-side helper
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
   * fences. Always emitted (the `:CUSTOM_ID:` line guarantees non-empty
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
   * :CUSTOM_ID: <uuid>
   * :CREATED: [<timestamp>]
   * :LINKED_ISSUES: KEY1 KEY2
   * :END:
   * <body>
   * ```
   *
   * Body is omitted when empty; the trailing newline still applies.
   */
  block: string;
  /** The `:CUSTOM_ID:` UUID written into the drawer. */
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
    `:CUSTOM_ID: ${id}`,
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
// `jira_clone_apply` (today) and any future tracker integration. Kept
// here (rather than in `index.ts`) so:
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
  /** UUID written into the new task's `:CUSTOM_ID:` drawer line. */
  id: string;
  /** Absolute path of the file mutated. */
  file: string;
  /** 1-indexed line where the new heading lives after insertion. */
  line: number;
}

/** Refusal — duplicate :LINKED_ISSUES: token already present. */
export interface InsertDuplicate {
  status: "duplicate";
  /** `:CUSTOM_ID:` of the pre-existing task that owns the conflicting token. */
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

  const cmd = ["create", args.summary];
  if (args.section) cmd.push("--section", args.section);
  if (args.priorityName) cmd.push("--priority", args.priorityName);
  if (args.body) cmd.push("--body", args.body);
  if (args.parentId) cmd.push("--parent", args.parentId);
  if (args.allowCreateSection) cmd.push("--allow-create-section");
  if (args.id) cmd.push("--id", args.id);
  if (args.createdAt) cmd.push("--created-at", args.createdAt);
  for (const token of args.linkedIssues ?? []) {
    if (token) cmd.push("--linked-issue", token);
  }
  for (const label of args.labels ?? []) {
    if (label) cmd.push("--tag", label);
  }
  for (const scanPath of args.alsoScan ?? []) {
    if (scanPath) cmd.push("--also-scan", scanPath);
  }

  // `ot create --local` writes to <root>/TASKS.local.org; otherwise
  // pass --tasks <file> as a global override so the legacy JS entry
  // point remains file-parameter compatible for TASKS.org and tests.
  const globalArgs = basename(args.file) === "TASKS.local.org"
    ? []
    : ["--tasks", args.file];
  if (basename(args.file) === "TASKS.local.org") cmd.push("--local");

  let env: OtEnvelope<{ id: string; file: string; line: number }>;
  try {
    env = await runOt(cmd, { root: projectRoot, globalArgs });
  } catch (err) {
    return {
      status: "error",
      reason: "file_unreadable",
      message: (err as Error).message,
    };
  }

  if (env.ok) {
    return {
      status: "inserted",
      id: env.result.id,
      file: env.result.file,
      line: env.result.line,
    };
  }

  const { code, message, file, details } = env.error;
  switch (code) {
    case "duplicate-linked-issue":
      return {
        status: "duplicate",
        existingId: (details?.existingId as string | null | undefined) ?? null,
        existingFile: (details?.existingFile as string | undefined) ?? file ?? args.file,
        conflictingToken: (details?.conflictingToken as string | undefined) ?? "",
      };
    case "section-not-found":
      return {
        status: "section_not_found",
        file: file ?? args.file,
        section: (details?.section as string | undefined) ?? args.section,
      };
    case "empty-summary":
      return { status: "error", reason: "empty_summary", message };
    case "path-outside-project":
      return { status: "error", reason: "path_outside_project", message };
    case "unknown-task":
      return { status: "error", reason: "file_unreadable", message };
    default:
      return { status: "error", reason: "file_unreadable", message };
  }
}

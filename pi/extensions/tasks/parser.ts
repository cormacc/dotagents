/**
 * Org-mode TASKS.org parser.
 *
 * Parses headings of the form:
 *   * STATUS [#PRIORITY] Summary text :tag1:tag2:
 *
 * Valid status tokens: TODO, STARTED, WAITING, DONE, CANCELLED
 * Priority is optional, e.g. [#A], [#B], [#C]
 * Tags are optional, colon-delimited at end of heading
 * Body text between headings becomes the task description.
 * Nested headings become subtasks (arbitrary depth).
 */

export interface Task {
  level: number;
  status: string;
  priority: string | null;
  summary: string;
  tags: string[];
  description: string;
  children: Task[];
  /** Non-PLAN org property drawer lines, preserved on save. */
  propertyLines: string[];
  /** Task-local LOGBOOK drawer lines, preserved on save. */
  logbookLines: string[];
  /**
   * True when this task comes from the gitignored `TASKS.local.org`.
   * Set by the loader after parsing; not stored in the org file.
   */
  isLocal?: boolean;
  /** Path extracted from a `#+IMPORT:` keyword in the task body or file root. */
  importPath: string | null;
  /**
   * Raw `#+IMPORT:` value, preserved for round-trip serialization.
   * When the user writes an org-link form (e.g. `[[file:plans/foo.org][Plan]]`)
   * it's preserved verbatim so Emacs keeps treating it as a clickable link
   * while the extension still follows the extracted path.
   */
  importRaw?: string | null;
  /** Tasks loaded from the imported file, injected as children at render time. */
  importChildren?: Task[];
  /** Error encountered while loading `importPath`, if any. */
  importError?: string | null;
  /**
   * Org CLOSED timestamp body (without brackets), e.g. `2026-04-24 Fri 14:30`.
   * Present when the task has been closed, matching Emacs behaviour.
   */
  closed: string | null;
  /** Absolute path of the source org file this task came from. */
  sourcePath?: string;
  /** Original source content of sourcePath, used for preserving non-task org content on save. */
  sourceContent?: string;
  /** Root task tree for sourcePath, used to save linked plan files. */
  sourceRoot?: Task[];
  /** 1-indexed line number of the heading in the source file. */
  lineNumber: number;
  /** 1-indexed exclusive line number where this task subtree ended when parsed. */
  endLine: number;
}

/** Matches any org heading: `* ...`, `** ...`, etc. */
const ANY_HEADING_RE = /^(\*+)\s+(.*)$/;

const HEADING_RE =
  /^(\*+)\s+(TODO|STARTED|WAITING|DONE|CANCELLED)\s+(?:\[#([A-Z])\]\s+)?(.+)$/;

const PROPERTIES_START_RE = /^\s*:PROPERTIES:\s*$/i;
const LOGBOOK_START_RE = /^\s*:LOGBOOK:\s*$/i;
const DRAWER_END_RE = /^\s*:END:\s*$/i;
const PROPERTIES_END_RE = DRAWER_END_RE;
/** Matches a `#+IMPORT:` keyword anywhere in a file (task body or root level). */
const IMPORT_KEYWORD_RE = /^\s*#\+IMPORT:\s*(.*?)\s*$/i;
const ID_PROPERTY_RE = /^\s*:ID:\s*(\S+)\s*$/i;
const STARTED_PROPERTY_RE = /^\s*:STARTED:\s*\[([^\]]+)\]\s*$/i;
/**
 * Extract the target path from an org link expression:
 *   [[file:path]]                  → path
 *   [[file:path][description]]     → path
 *   [[path]]                       → path
 * Returns null when the value isn't an org link.
 */
const ORG_LINK_RE = /^\[\[(?:file:)?([^\]]+?)\](?:\[[^\]]*\])?\]$/;

/** Extract a target path from an org link expression, or null for non-link text. */
export function extractOrgLinkTarget(value: string): string | null {
  const match = ORG_LINK_RE.exec(value.trim());
  return match?.[1]?.trim() || null;
}

/**
 * Org link with both target and (optional) description captured.
 *   `[[url]]`             → { target: "url", description: null }
 *   `[[url][label]]`      → { target: "url", description: "label" }
 *   `[[file:path]]`       → { target: "path", description: null }
 */
const ORG_LINK_FULL_RE = /^\[\[(?:file:)?([^\]]+?)\](?:\[([^\]]*)\])?\]$/;

export interface OrgLink {
  /** The link target (URL or file path). */
  target: string;
  /** The optional description text shown to the user, or null when absent. */
  description: string | null;
}

/**
 * Parse an org link expression into target + description.
 * Returns null when the value isn't an org link.
 */
export function extractOrgLink(value: string): OrgLink | null {
  const match = ORG_LINK_FULL_RE.exec(value.trim());
  if (!match) return null;
  const target = match[1]?.trim();
  if (!target) return null;
  const description = match[2] !== undefined ? match[2].trim() : "";
  return { target, description: description.length > 0 ? description : null };
}

/** Matches an org CLOSED timestamp line, e.g. `CLOSED: [2026-04-24 Fri 14:30]`. */
const CLOSED_RE = /^\s*CLOSED:\s*\[([^\]]+)\]\s*$/;

/** Matches the #+SELECTED: keyword in TASKS.local.org. */
const SELECTED_KEYWORD_RE = /^#\+SELECTED:\s*(\S+)\s*$/im;

/**
 * Extract the selected task UUID from TASKS.local.org content.
 * Returns null when the file is empty or has no #+SELECTED: keyword.
 */
export function parseSelectedKeyword(content: string): string | null {
  const match = SELECTED_KEYWORD_RE.exec(content);
  return match?.[1]?.trim() ?? null;
}

const DAY_ABBR = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"] as const;

/** Format a Date as an inactive org timestamp body, e.g. `2026-04-24 Fri 14:30`. */
export function formatOrgTimestamp(d: Date = new Date()): string {
  const pad = (n: number) => n.toString().padStart(2, "0");
  const y = d.getFullYear();
  const mo = pad(d.getMonth() + 1);
  const da = pad(d.getDate());
  const hh = pad(d.getHours());
  const mm = pad(d.getMinutes());
  const dow = DAY_ABBR[d.getDay()]!;
  return `${y}-${mo}-${da} ${dow} ${hh}:${mm}`;
}

/** Format a Date as a date-only org body, e.g. `2026-04-24 Fri`.
 * Used for `#+DATE:` headers where time-of-day is not meaningful. */
export function formatOrgDate(d: Date = new Date()): string {
  const pad = (n: number) => n.toString().padStart(2, "0");
  const y = d.getFullYear();
  const mo = pad(d.getMonth() + 1);
  const da = pad(d.getDate());
  const dow = DAY_ABBR[d.getDay()]!;
  return `${y}-${mo}-${da} ${dow}`;
}

export function createdLogEntry(timestamp: string): string {
  return `- Created [${timestamp}]`;
}

export function stateLogEntry(newStatus: string, oldStatus: string, timestamp: string): string {
  return `- State \"${newStatus}\" from \"${oldStatus}\" [${timestamp}]`;
}

export function appendCreatedLog(task: Task, timestamp: string): void {
  task.logbookLines.push(createdLogEntry(timestamp));
}

export function appendStateLog(
  task: Task,
  newStatus: string,
  oldStatus: string,
  timestamp: string = formatOrgTimestamp(),
): void {
  task.logbookLines.push(stateLogEntry(newStatus, oldStatus, timestamp));
}

/**
 * Parse a single heading line into its components.
 * Returns null if the line is not a valid task heading.
 */
function parseHeading(line: string): {
  level: number;
  status: string;
  priority: string | null;
  summary: string;
  tags: string[];
} | null {
  const m = HEADING_RE.exec(line);
  if (!m) return null;

  const level = m[1]!.length;
  const status = m[2]!;
  let summary = m[4]!.trimEnd();
  const tags: string[] = [];

  // Extract trailing tags  :tag1:tag2:
  const tagMatch = /\s+:([\w:]+):\s*$/.exec(summary);
  if (tagMatch) {
    summary = summary.slice(0, tagMatch.index).trimEnd();
    for (const t of tagMatch[1]!.split(":")) {
      if (t) tags.push(t);
    }
  }

  return { level, status, priority: m[3] ?? null, summary, tags };
}

/**
 * Result returned by `parseTasks`.
 * `fileImports` collects paths from `#+IMPORT:` lines that appear before any
 * task heading (i.e. at the file root level, with no parent task).
 */
export interface ParseResult {
  tasks: Task[];
  fileImports: string[];
}

export interface ParseTasksOptions {
  /** Absolute path of the file being parsed. */
  sourcePath?: string;
  /** Original source content. Defaults to the parsed content. */
  sourceContent?: string;
}

/** Return the org `:ID:` property value for a task, if present. */
export function getTaskId(task: Task): string | null {
  for (const line of task.propertyLines) {
    const match = ID_PROPERTY_RE.exec(line);
    if (match) return match[1]!.trim();
  }
  return null;
}

/** True when a task already has an org `:ID:` property. */
export function taskHasId(task: Task): boolean {
  return getTaskId(task) !== null;
}

/**
 * Return the org `:STARTED:` property value (timestamp body without brackets,
 * matching the `closed` field convention), if present.
 * Used by the retrospective change-record flow to scope `git log`.
 */
export function getTaskStarted(task: Task): string | null {
  for (const line of task.propertyLines) {
    const match = STARTED_PROPERTY_RE.exec(line);
    if (match) return match[1]!.trim();
  }
  return null;
}

/** True when a task has a recorded `:STARTED:` first-transition timestamp. */
export function taskHasStartedProperty(task: Task): boolean {
  return getTaskStarted(task) !== null;
}

/**
 * Generic property-line matcher: `:NAME: value`.
 * `NAME` is case-insensitive in org; we normalise via `.toUpperCase()`.
 *
 * Note: `+` is intentionally excluded from the property name character
 * class so this regex does NOT match org's `:NAME+:` continuation idiom.
 * Multi-valued helpers below use a separate regex that accepts `+`.
 */
const PROPERTY_LINE_RE = /^\s*:([A-Za-z][A-Za-z0-9_-]*):\s*(.*?)\s*$/;

/**
 * Matches both base property lines (`:NAME: value`) and org-native
 * continuation lines (`:NAME+: value`). Capture groups:
 *   1. Property name (no `+`).
 *   2. Literal `+` for continuation lines, undefined otherwise.
 *   3. Value.
 */
const PROPERTY_OR_CONTINUATION_LINE_RE =
  /^\s*:([A-Za-z][A-Za-z0-9_-]*)(\+)?:\s*(.*?)\s*$/;

/**
 * Return the value of an arbitrary drawer property by name, or null when
 * the property is absent. Case-insensitive match on the property name.
 *
 * Generic accessor for third-party extensions: stable contract that
 * unknown drawer properties round-trip cleanly through the parser.
 */
export function getDrawerProperty(task: Task, name: string): string | null {
  const target = name.toUpperCase();
  for (const line of task.propertyLines) {
    const m = PROPERTY_LINE_RE.exec(line);
    if (m && m[1]!.toUpperCase() === target) {
      return m[2]!.trim();
    }
  }
  return null;
}

/**
 * Set or clear an arbitrary drawer property by name.
 * - `value === null` removes the property line if present (no-op otherwise).
 * - When the property is already present, the existing line is replaced
 *   in place, preserving the original casing of the property name.
 * - When absent, a new line is appended in `:NAME: value` form using the
 *   `name` argument verbatim.
 *
 * Single-value semantics only: this helper does *not* touch `:NAME+:`
 * continuation lines. To manage multi-valued properties (e.g.
 * `:BLOCKED-BY:` + `:BLOCKED-BY+:`) use `setDrawerPropertyValues`.
 */
export function setDrawerProperty(
  task: Task,
  name: string,
  value: string | null,
): void {
  const target = name.toUpperCase();
  let replaced = false;
  task.propertyLines = task.propertyLines.flatMap((line) => {
    const m = PROPERTY_LINE_RE.exec(line);
    if (m && m[1]!.toUpperCase() === target) {
      replaced = true;
      return value === null ? [] : [`:${m[1]!}: ${value}`];
    }
    return [line];
  });
  if (!replaced && value !== null) {
    task.propertyLines.push(`:${name}: ${value}`);
  }
}

/**
 * Multi-valued drawer property accessor. Collects values from the base
 * `:NAME:` line *and* every `:NAME+:` continuation line within the
 * task's `:PROPERTIES:` drawer, in the order they appear. Empty when
 * neither is present.
 *
 * Used for protocol properties that allow multiple values per task,
 * e.g. `:BLOCKED-BY:` (see `getTaskBlockers`).
 */
export function getDrawerPropertyValues(task: Task, name: string): string[] {
  const target = name.toUpperCase();
  const values: string[] = [];
  for (const line of task.propertyLines) {
    const m = PROPERTY_OR_CONTINUATION_LINE_RE.exec(line);
    if (!m) continue;
    if (m[1]!.toUpperCase() !== target) continue;
    values.push(m[3]!.trim());
  }
  return values;
}

/**
 * Replace all values of a multi-valued drawer property. Removes every
 * existing `:NAME:` and `:NAME+:` line, then writes the supplied values:
 * the first as `:NAME: value`, each subsequent as `:NAME+: value`.
 * Empty `values` clears the property entirely.
 *
 * The supplied `name` casing is used verbatim on every written line.
 */
export function setDrawerPropertyValues(
  task: Task,
  name: string,
  values: string[],
): void {
  const target = name.toUpperCase();
  task.propertyLines = task.propertyLines.filter((line) => {
    const m = PROPERTY_OR_CONTINUATION_LINE_RE.exec(line);
    return !(m && m[1]!.toUpperCase() === target);
  });
  values.forEach((value, i) => {
    const suffix = i === 0 ? "" : "+";
    task.propertyLines.push(`:${name}${suffix}: ${value}`);
  });
}

// ── Blockers / readiness (`:BLOCKED-BY:` + `:BLOCKED-BY+:`) ────────────────────────────
//
// `:BLOCKED-BY:` carries one or more dependency / blocker references. Each
// entry is a free-form token; the protocol convention is one of:
//   `task:<UUID>`    — dependency on another task in the loaded graph
//   `url:<URL>`      — external URL (e.g. upstream PR/issue)
//   `human:<text>`   — awaiting human action
//   `jira:<KEY>`     — Jira issue (rendered by org-jira tooling)
//   anything else    — opaque blocker, treated as `other`
//
// Multiple blockers use org's `:NAME+:` continuation idiom so each entry
// occupies its own drawer line.

/** Discriminator on the form of a parsed `:BLOCKED-BY:` entry. */
export type BlockerKind = "task" | "url" | "human" | "jira" | "other";

/** Parsed `:BLOCKED-BY:` entry. */
export interface TaskBlocker {
  /** Original token after trimming. */
  raw: string;
  /** Recognised kind, or `"other"` for unrecognised forms. */
  kind: BlockerKind;
  /** Reference body after the kind prefix. Equals `raw` for `"other"`. */
  ref: string;
}

/** Parse a single blocker token into a structured form. */
export function parseBlocker(raw: string): TaskBlocker {
  const trimmed = raw.trim();
  // Match `kind:rest` where kind is a known prefix (case-insensitive).
  const m = /^(task|url|human|jira):(.*)$/i.exec(trimmed);
  if (m) {
    const kind = m[1]!.toLowerCase() as BlockerKind;
    return { raw: trimmed, kind, ref: m[2]!.trim() };
  }
  return { raw: trimmed, kind: "other", ref: trimmed };
}

/**
 * All blockers attached to a task, parsed from `:BLOCKED-BY:` and any
 * `:BLOCKED-BY+:` continuation lines. Empty when no blockers are set.
 */
export function getTaskBlockers(task: Task): TaskBlocker[] {
  return getDrawerPropertyValues(task, "BLOCKED-BY").map(parseBlocker);
}

/**
 * Replace the blockers attached to a task. The first entry is written
 * as `:BLOCKED-BY: <raw>`, each subsequent as `:BLOCKED-BY+: <raw>`.
 * Empty `blockers` clears all blocker lines.
 */
export function setTaskBlockers(
  task: Task,
  blockers: readonly TaskBlocker[] | readonly string[],
): void {
  const values = blockers.map((b) => (typeof b === "string" ? b : b.raw));
  setDrawerPropertyValues(task, "BLOCKED-BY", values);
}

/**
 * Result of a readiness check on a task.
 *   `ready === true` when every blocker resolves (`task:` blockers point
 *     to closed tasks, no non-task blockers remain).
 *   `gating` lists the unresolved blockers when `ready === false`. The
 *     status reasons (`closed`/`unresolved`/`opaque`) help callers render
 *     a useful message.
 */
export interface ReadinessReport {
  ready: boolean;
  /** Blockers that prevent readiness, with a per-blocker reason. */
  gating: Array<{
    blocker: TaskBlocker;
    reason: "opaque" | "unresolved-task" | "missing-task";
  }>;
}

/** Org statuses considered "closed" for readiness gating. */
const CLOSED_STATUSES = new Set(["DONE", "CANCELLED"]);

/**
 * Compute whether a task is ready to start. A task is ready when every
 * `:BLOCKED-BY:` entry resolves:
 *   - `task:<UUID>` blockers must point to a task with status `DONE` or
 *     `CANCELLED` per the loaded task graph.
 *   - Any non-task blocker (`url:`, `human:`, `jira:`, or unrecognised
 *     form) is treated as opaque and gates readiness until the line is
 *     removed by hand.
 * A task with no blockers is trivially ready. The caller is responsible
 * for filtering on heading status (e.g. only consider `TODO` tasks);
 * this predicate operates purely on blocker resolution.
 *
 * `resolveTaskById` is injected so this module stays free of task-graph
 * traversal helpers (which live in `index.ts`).
 */
export function isTaskReady(
  task: Task,
  resolveTaskById: (id: string) => Task | null,
): ReadinessReport {
  const gating: ReadinessReport["gating"] = [];
  for (const blocker of getTaskBlockers(task)) {
    if (blocker.kind === "task") {
      const dep = resolveTaskById(blocker.ref);
      if (!dep) {
        gating.push({ blocker, reason: "missing-task" });
      } else if (!CLOSED_STATUSES.has(dep.status)) {
        gating.push({ blocker, reason: "unresolved-task" });
      }
    } else {
      gating.push({ blocker, reason: "opaque" });
    }
  }
  return { ready: gating.length === 0, gating };
}

// ── Handoff (`:HANDOFF:`) ─────────────────────────────────────────────────────
//
// Optional short free-form note flagged for the next session/agent.
// Single-valued; lives on the task heading's `:PROPERTIES:` drawer.

/** Return the `:HANDOFF:` text for a task, or null when absent/empty. */
export function getTaskHandoff(task: Task): string | null {
  const v = getDrawerProperty(task, "HANDOFF");
  return v && v.length > 0 ? v : null;
}

/** Set or clear the `:HANDOFF:` property. Empty/null clears the line. */
export function setTaskHandoff(task: Task, value: string | null): void {
  setDrawerProperty(task, "HANDOFF", value && value.length > 0 ? value : null);
}

/**
 * Return the value of a file-level `#+KEYWORD:` from raw org content,
 * or null when absent. Case-insensitive match on the keyword name.
 * First occurrence wins; callers wanting `TASKS.local.org` to override
 * `TASKS.org` should call this on each file separately and prefer the
 * local value when present.
 *
 * The value is whatever appears after `:` on the same line, trimmed.
 * Empty values (e.g. `#+JIRA_PROJECT:`) yield the empty string `""` —
 * distinct from null (keyword absent). Only horizontal whitespace
 * (` `, `\t`) is allowed between the colon and the value, so a blank
 * keyword line never leaks into the next line.
 */
export function getFileKeyword(
  content: string,
  name: string,
): string | null {
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  // Use [\t ]* (horizontal whitespace only) around the captured value
  // so an empty `#+NAME:` line doesn't consume the following newline
  // and bleed into the next line's content.
  const re = new RegExp(
    `^[\\t ]*#\\+${escaped}[\\t ]*:[\\t ]*(.*?)[\\t ]*$`,
    "im",
  );
  const m = re.exec(content);
  return m?.[1] ?? null;
}

// ── Linked external issues (`:LINKED_ISSUES:` + `#+ISSUE_URL_BASE`) ───────
//
// Tracker-agnostic external-issue references stored in the `:LINKED_ISSUES:`
// drawer property as whitespace-separated tokens. Each token is either:
//   1. A bare key (e.g. `MBFW-123`), resolved against `#+ISSUE_URL_BASE`.
//   2. A full org link `[[url][label]]`, resolved directly.
//
// Tracker-specific behaviour (workflow, MCP routing, slash commands)
// lives in companion extensions; this module knows nothing about them.

export interface LinkedIssue {
  /** Resolved URL, or null when a bare token has no usable `#+ISSUE_URL_BASE`. */
  url: string | null;
  /** Human-readable badge label (key for bare tokens, description for org links). */
  label: string;
  /** Original whitespace-separated token from the drawer line. */
  rawToken: string;
}

/**
 * Compose a URL for a bare issue key against an `#+ISSUE_URL_BASE` template.
 *
 * Resolution rule:
 *   1. URL-encode the key.
 *   2. If `template` contains `{ID}`, substitute the encoded key for every
 *      occurrence.
 *   3. Otherwise treat `template` as a prefix and append the encoded key.
 *
 * Returns null when `template` is null or empty.
 */
export function resolveIssueUrl(
  template: string | null | undefined,
  key: string,
): string | null {
  if (!template) return null;
  const encoded = encodeURIComponent(key);
  if (template.includes("{ID}")) {
    return template.split("{ID}").join(encoded);
  }
  return template + encoded;
}

/**
 * Parse `:LINKED_ISSUES:` for a task, classifying each token and resolving
 * URLs via the supplied `#+ISSUE_URL_BASE` template (may be null).
 *
 * Empty/absent property returns an empty array. Malformed tokens (anything
 * that's not a clean bare key or a parseable org link) are kept as bare
 * tokens with whatever URL `resolveIssueUrl` produces — the parser does
 * not validate token shape; rendering and `J` open are best-effort.
 */
export function getLinkedIssues(
  task: Task,
  urlBaseTemplate: string | null,
): LinkedIssue[] {
  const value = getDrawerProperty(task, "LINKED_ISSUES");
  if (!value) return [];
  const tokens = value.split(/\s+/).filter((t) => t.length > 0);
  return tokens.map((rawToken) => {
    const link = extractOrgLink(rawToken);
    if (link) {
      return {
        url: link.target,
        label: link.description ?? link.target,
        rawToken,
      };
    }
    return {
      url: resolveIssueUrl(urlBaseTemplate, rawToken),
      label: rawToken,
      rawToken,
    };
  });
}

/**
 * Replace the `:LINKED_ISSUES:` drawer property with the given tokens
 * (whitespace-joined). Tokens are written verbatim — callers are
 * responsible for choosing bare-key vs `[[url][label]]` form per token.
 * Passing an empty array clears the property.
 */
export function setLinkedIssues(task: Task, tokens: string[]): void {
  if (tokens.length === 0) {
    setDrawerProperty(task, "LINKED_ISSUES", null);
    return;
  }
  setDrawerProperty(task, "LINKED_ISSUES", tokens.join(" "));
}

/**
 * Parse the full content of a TASKS.org file into a task tree.
 *
 * `#+IMPORT: [[file:path]]` lines are recognised anywhere in the file:
 * - Inside a task body: sets `importPath` on that task.
 * - Before any task heading (file root): collected in `ParseResult.fileImports`.
 */
export function parseTasks(
  content: string,
  options: ParseTasksOptions = {},
): ParseResult {
  const lines = content.split("\n");
  const root: Task[] = [];
  const fileImports: string[] = [];
  const sourceContent = options.sourceContent ?? content;

  // Stack tracks the current nesting path.
  // Each entry: { task, level } — the task and its heading level.
  const stack: { task: Task; level: number }[] = [];

  let currentTask: Task | null = null;
  const descriptionLines: string[] = [];

  const flushDescription = () => {
    if (currentTask) {
      // Trim leading/trailing blank lines from description
      let desc = descriptionLines.join("\n");
      desc = desc.replace(/^\n+/, "").replace(/\n+$/, "");
      currentTask.description = desc;
    }
    descriptionLines.length = 0;
  };

  const closeTasksAtOrAbove = (level: number, endLineExclusive: number) => {
    while (stack.length > 0 && stack[stack.length - 1]!.level >= level) {
      const closed = stack.pop()!.task;
      closed.endLine = endLineExclusive;
    }
  };

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]!;
    const heading = parseHeading(line);
    const anyHeading = ANY_HEADING_RE.exec(line);

    if (heading) {
      // Flush any accumulated description for the previous task
      flushDescription();
      closeTasksAtOrAbove(heading.level, i + 1);

      const task: Task = {
        level: heading.level,
        status: heading.status,
        priority: heading.priority,
        summary: heading.summary,
        tags: heading.tags,
        description: "",
        children: [],
        propertyLines: [],
        logbookLines: [],
        importPath: null,
        importRaw: null,
        importError: null,
        closed: null,
        sourcePath: options.sourcePath,
        sourceContent,
        lineNumber: i + 1,
        endLine: lines.length + 1,
      };

      if (stack.length === 0) {
        // Top-level task
        root.push(task);
      } else {
        // Child of the task on top of stack
        stack[stack.length - 1]!.task.children.push(task);
      }

      stack.push({ task, level: heading.level });
      currentTask = task;
    } else if (currentTask && CLOSED_RE.test(line)) {
      // Emacs writes `CLOSED: [...]` immediately after a heading transitions
      // to a done state. Capture it as metadata rather than description.
      const m = CLOSED_RE.exec(line)!;
      currentTask.closed = m[1]!.trim();
    } else if (currentTask && PROPERTIES_START_RE.test(line)) {
      // Org properties drawer — collect all lines verbatim for round-trip.
      for (i = i + 1; i < lines.length; i++) {
        const propLine = lines[i]!;
        if (PROPERTIES_END_RE.test(propLine)) break;
        currentTask.propertyLines.push(propLine);
      }
    } else if (currentTask && LOGBOOK_START_RE.test(line)) {
      // Org lifecycle drawer — collect lines verbatim for round-trip.
      for (i = i + 1; i < lines.length; i++) {
        const logLine = lines[i]!;
        if (DRAWER_END_RE.test(logLine)) break;
        currentTask.logbookLines.push(logLine);
      }
    } else if (anyHeading) {
      // Non-task heading (e.g. `* Notes`) — flush the current task's
      // description and stop attributing subsequent lines to it, so
      // unrelated content below isn't swallowed into the previous task.
      flushDescription();
      closeTasksAtOrAbove(anyHeading[1]!.length, i + 1);
      currentTask = null;
    } else if (IMPORT_KEYWORD_RE.test(line)) {
      // #+IMPORT: keyword — extract path and attach to current task or collect
      // as a file-level import when no task heading is in scope.
      const raw = IMPORT_KEYWORD_RE.exec(line)![1]!.trim();
      if (raw) {
        const linkTarget = extractOrgLinkTarget(raw);
        const path = linkTarget ?? raw;
        if (currentTask) {
          currentTask.importPath = path;
          currentTask.importRaw = linkTarget ? raw : null;
        } else {
          fileImports.push(path);
        }
      }
    } else {
      // Non-heading line: accumulate as description for current task
      if (currentTask) {
        descriptionLines.push(line);
      }
    }
  }

  // Flush description for the last task and close any open task ranges.
  flushDescription();
  closeTasksAtOrAbove(1, lines.length + 1);

  const attachSourceRoot = (tasks: Task[]) => {
    for (const task of tasks) {
      if (options.sourcePath) {
        task.sourceRoot = root;
        task.sourceContent = sourceContent;
      }
      attachSourceRoot(task.children);
    }
  };
  attachSourceRoot(root);

  return { tasks: root, fileImports };
}

/**
 * Serialize a task tree back to org-mode text.
 *
 * Top-level tasks are separated by a blank line for readability.
 * Descriptions are emitted verbatim (preserving indentation/blanks the
 * parser stripped from the edges is acceptable: the parser trims only
 * leading and trailing blank lines).
 */
export function serializeTasks(tasks: Task[]): string {
  const lines: string[] = [];

  const write = (taskList: Task[], topLevel: boolean) => {
    for (let i = 0; i < taskList.length; i++) {
      const t = taskList[i]!;
      if (topLevel && i > 0) lines.push("");
      const stars = "*".repeat(t.level);
      const prio = t.priority ? ` [#${t.priority}]` : "";
      const tags = t.tags.length > 0 ? ` :${t.tags.join(":")}:` : "";
      lines.push(`${stars} ${t.status}${prio} ${t.summary}${tags}`);
      if (t.closed) {
        lines.push(`CLOSED: [${t.closed}]`);
      }
      const propertyLines = [...t.propertyLines];
      if (propertyLines.length > 0) {
        lines.push(":PROPERTIES:");
        lines.push(...propertyLines);
        lines.push(":END:");
      }
      if (t.logbookLines.length > 0) {
        lines.push(":LOGBOOK:");
        lines.push(...t.logbookLines);
        lines.push(":END:");
      }
      if (t.importPath) {
        lines.push(`#+IMPORT: ${t.importRaw ?? t.importPath}`);
      }
      if (t.description) {
        lines.push(t.description);
      }
      write(t.children, false);
    }
  };

  write(tasks, true);
  return lines.join("\n") + "\n";
}

/** Serialize a single task subtree without forcing a trailing newline. */
function serializeTaskBlock(task: Task): string[] {
  return serializeTasks([task]).replace(/\n$/, "").split("\n");
}

/** Original line range occupied by a parsed task subtree. */
function taskRange(task: Task): { start: number; end: number } {
  const start = Math.max(0, task.lineNumber - 1);
  const end = Math.max(start + 1, task.endLine - 1);
  return { start, end };
}

/**
 * Serialize root tasks back into their original org file while preserving
 * content outside task subtrees: metadata/preamble, category headings, plan
 * sections, prose notes, and other non-task org content.
 *
 * Existing root tasks are replaced in-place by their original line number.
 * Existing root tasks omitted from `tasks` are removed (used by archiving).
 * New root tasks with no parsed line number are appended to the end of the file.
 */
export function serializeTasksPreservingFile(
  originalContent: string,
  tasks: Task[],
): string {
  const lines = originalContent.split("\n");
  const { tasks: originalRoots } = parseTasks(originalContent);
  const suppliedByLine = new Map<number, Task>();
  const newRoots: Task[] = [];

  for (const task of tasks) {
    if (task.lineNumber > 0) {
      suppliedByLine.set(task.lineNumber, task);
    } else {
      newRoots.push(task);
    }
  }

  const edits: { start: number; end: number; replacement: string[] }[] = [];

  for (const original of originalRoots) {
    const supplied = suppliedByLine.get(original.lineNumber);
    const range = taskRange(original);
    edits.push({
      ...range,
      replacement: supplied ? serializeTaskBlock(supplied) : [],
    });
  }

  // Apply from the bottom up so earlier line ranges remain stable.
  edits
    .sort((a, b) => b.start - a.start)
    .forEach(({ start, end, replacement }) => {
      lines.splice(start, end - start, ...replacement);
    });

  for (const task of newRoots) {
    while (lines.length > 0 && lines[lines.length - 1] === "") lines.pop();
    if (lines.length > 0) lines.push("");
    lines.push(...serializeTaskBlock(task));
  }

  return lines.join("\n").replace(/\n*$/, "\n");
}

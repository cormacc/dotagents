/**
 * Task model and read-only org helpers for the tasks extension.
 *
 * `ot` owns parsing and writing TASKS.org; the extension builds `Task`
 * values from the `ot list` wire format (`wireTaskToTask` in `index.ts`).
 * These helpers read drawer properties, blockers and `#+LINK:` templates
 * from those values.
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
  /** Source content with one-level #+SETUPFILE content prepended for keyword/link lookup. */
  effectiveSourceContent?: string;
  /** Root task tree for sourcePath, used to save linked plan files. */
  sourceRoot?: Task[];
  /** 1-indexed line number of the heading in the source file. */
  lineNumber: number;
  /** 1-indexed exclusive line number where this task subtree ended when parsed. */
  endLine: number;
  /** Pre-resolved linked issues supplied by the `ot list` wire format. */
  linkedIssues?: LinkedIssue[];
}

const ID_PROPERTY_RE = /^\s*:CUSTOM_ID:\s*(\S+)\s*$/i;
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

export function createdLogEntry(timestamp: string): string {
  return `- Created [${timestamp}]`;
}

/** Return the org `:CUSTOM_ID:` property value for a task, if present. */
export function getTaskId(task: Task): string | null {
  for (const line of task.propertyLines) {
    const match = ID_PROPERTY_RE.exec(line);
    if (match) return match[1]!.trim();
  }
  return null;
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

/** Org statuses considered "closed" for readiness gating and lifecycle checks. */
export const CLOSED_STATUSES: ReadonlySet<string> = new Set(["DONE", "CANCELLED"]);

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

export interface LinkedIssue {
  /** Resolved URL, or null when a typed link references an undeclared prefix. */
  url: string | null;
  /** Human-readable badge label (key for typed links, description for URL links). */
  label: string;
  /** Original org-link token from the drawer line. */
  rawToken: string;
  /** Resolution error for hard-fail callers / notifications. */
  error?: string;
}

export type LinkTemplateMap = Map<string, string>;

/** Parse all org-native `#+LINK: prefix template` declarations in CONTENT. */
export function parseLinkTemplates(content: string): LinkTemplateMap {
  const templates: LinkTemplateMap = new Map();
  const re = /^[\t ]*#\+LINK[\t ]*:[\t ]*(\S+)[\t ]+(.+?)[\t ]*$/gim;
  let match: RegExpExecArray | null;
  while ((match = re.exec(content)) !== null) {
    const prefix = match[1]?.trim();
    const template = match[2]?.trim();
    if (prefix && template && !templates.has(prefix)) templates.set(prefix, template);
  }
  return templates;
}

/**
 * Substitute KEY into TEMPLATE's `%s` placeholder. Keys are URL-encoded for
 * URL-shaped templates (e.g. `https://.../%s`) so tracker keys with spaces or
 * slashes are escaped, but left literal for `file:` templates so plan-path
 * abbreviations like `#+LINK: plan file:design/log/%s` keep `/` in nested
 * filenames. Org-mode's own `org-link-expand-abbrev` is similarly literal; we
 * only diverge by URL-encoding for non-`file:` templates because pi consumers
 * expect ready-to-open URLs.
 */
function resolveLinkTemplate(template: string, key: string): string {
  const replacement = template.startsWith("file:") ? key : encodeURIComponent(key);
  return template.includes("%s") ? template.split("%s").join(replacement) : template + replacement;
}

/**
 * Result of expanding an org-link target against `#+LINK:` abbreviations.
 * `fromProjectRoot` is true when the target resolved through a `file:`
 * template, signalling callers to resolve relative paths against the project
 * root rather than the source file's directory.
 */
export interface ExpandedLinkTarget {
  /** Either the substituted path/URL, or the original target on a miss. */
  target: string;
  /** True only when expansion produced a path via a `file:` template. */
  fromProjectRoot: boolean;
}

/**
 * Expand a typed link target (`prefix:key`) through `#+LINK:` abbreviations.
 *
 * Returns the original target unchanged for:
 * - non-typed targets (plain paths)
 * - `file:` typed targets (already a path)
 * - `http`/`https` typed targets (already a URL)
 * - typed targets whose prefix has no matching `#+LINK:` declaration
 *
 * Callers can use `fromProjectRoot` to decide whether the resolved target
 * should be joined to the project root (file templates) or treated as the
 * source-directory-relative path it always was.
 */
export function expandOrgLinkTarget(
  target: string,
  contentOrTemplates: string | LinkTemplateMap | null | undefined,
): ExpandedLinkTarget {
  if (!target) return { target, fromProjectRoot: false };
  const typed = typedLinkParts(target);
  if (!typed || typed.prefix === "file") return { target, fromProjectRoot: false };
  const templates = typeof contentOrTemplates === "string"
    ? parseLinkTemplates(contentOrTemplates)
    : contentOrTemplates ?? new Map<string, string>();
  const template = templates.get(typed.prefix);
  if (!template) return { target, fromProjectRoot: false };
  const expanded = resolveLinkTemplate(template, typed.key);
  if (expanded.startsWith("file:")) {
    return { target: expanded.slice("file:".length), fromProjectRoot: true };
  }
  return { target: expanded, fromProjectRoot: false };
}

function typedLinkParts(target: string): { prefix: string; key: string } | null {
  if (/^https?:\/\//i.test(target)) return null;
  const match = /^([A-Za-z][A-Za-z0-9+.-]*):(.+)$/.exec(target);
  if (!match) return null;
  return { prefix: match[1]!, key: match[2]! };
}

/**
 * Pure helpers for the jira extension. No external runtime imports so
 * the test runner can exercise them without pulling in `pi-tui` or other
 * packages that aren't installed in the test environment.
 */

/** Validation regex for Jira keys, e.g. `MBFW-123`. */
export const JIRA_KEY_RE = /^[A-Z][A-Z0-9_]+-\d+$/;
/** A bare integer (no project prefix) — resolves against `#+JIRA_PROJECT`. */
export const BARE_NUMBER_RE = /^\d+$/;

export interface JiraConfig {
  cloudId: string | null;
  project: string | null;
  /** Derived from `#+LINK: jira https://host/browse/%s`. */
  baseUrl: string | null;
}

/**
 * Match a `#+KEYWORD:` line in raw org content.
 * Horizontal-whitespace only around the value so an empty line doesn't
 * bleed into the next line. Mirrors the helper of the same name in
 * `../tasks/parser.ts` — duplicated here to avoid a cross-extension
 * import cycle.
 */
export function getFileKeyword(
  content: string,
  name: string,
): string | null {
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const re = new RegExp(
    `^[\\t ]*#\\+${escaped}[\\t ]*:[\\t ]*(.*?)[\\t ]*$`,
    "im",
  );
  const m = re.exec(content);
  return m?.[1] ?? null;
}

function getFirstNonEmptyFileKeyword(content: string, name: string): string | null {
  const escaped = name.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const re = new RegExp(
    `^[\\t ]*#\\+${escaped}[\\t ]*:[\\t ]*(.*?)[\\t ]*$`,
    "gim",
  );
  for (const match of content.matchAll(re)) {
    const value = match[1] ?? "";
    if (value !== "") return value;
  }
  return null;
}

export function parseLinkTemplates(content: string): Map<string, string> {
  const templates = new Map<string, string>();
  const re = /^[\t ]*#\+LINK[\t ]*:[\t ]*(\S+)[\t ]+(.+?)[\t ]*$/gim;
  let match: RegExpExecArray | null;
  while ((match = re.exec(content)) !== null) {
    const prefix = match[1]?.trim();
    const template = match[2]?.trim();
    if (prefix && template && !templates.has(prefix)) templates.set(prefix, template);
  }
  return templates;
}

export function deriveJiraBaseUrl(template: string | null | undefined): string | null {
  if (!template) return null;
  const trimmed = template.trim();
  const match = /^https?:\/\/[^\s]+\/browse\/%s$/i.exec(trimmed);
  if (!match) return null;
  return trimmed.slice(0, -"/browse/%s".length);
}

export function resolveJiraConfig(content: string): JiraConfig {
  const pickKeyword = (name: string): string | null =>
    getFirstNonEmptyFileKeyword(content, name);
  const pickLinkTemplate = (prefix: string): string | null =>
    parseLinkTemplates(content).get(prefix) ?? null;
  return {
    cloudId: pickKeyword("JIRA_CLOUDID"),
    project: pickKeyword("JIRA_PROJECT"),
    baseUrl: deriveJiraBaseUrl(pickLinkTemplate("jira")),
  };
}

/**
 * Resolve a user-supplied key argument into a fully-qualified `PROJ-NNN`
 * key. Returns either `{ key }` on success or `{ error }` with a
 * human-readable message.
 */
export function resolveKey(
  arg: string,
  project: string | null,
): { key: string } | { error: string } {
  if (JIRA_KEY_RE.test(arg)) return { key: arg };
  if (BARE_NUMBER_RE.test(arg)) {
    if (!project) {
      return {
        error: `Bare number "${arg}" needs a project. Set #+JIRA_PROJECT in TASKS.setup.org (or TASKS.local.org) or pass the full key (e.g. SAND-${arg}).`,
      };
    }
    return { key: `${project}-${arg}` };
  }
  return {
    error: `"${arg}" is not a valid Jira key. Expected PROJ-NNN (e.g. SAND-42) or a bare number with #+JIRA_PROJECT set.`,
  };
}

/** Prefix of every tool name the native MCP client registers for the `atlassian` server. */
export const ATLASSIAN_TOOL_PREFIX = "mcp__atlassian__";

/** Write flows run against this project only until the workflows are signed off. */
export const SANDBOX_PROJECT = "SAND";

/** Fields the read flows (clone, get) request from `getJiraIssue`. */
export const ISSUE_FIELDS = [
  "summary",
  "priority",
  "labels",
  "description",
  "issuetype",
  "parent",
  "subtasks",
  "status",
  "assignee",
  "reporter",
  "issuelinks",
  "updated",
  "versions",
  "fixVersions",
  "components",
  "comment",
] as const;

/** Local status -> Jira transition names, tried in order (case-insensitive). */
export const TRANSITION_TARGETS = {
  STARTED: ["Start Progress", "In Progress"],
  DONE: ["Done", "Closed", "Resolved"],
} as const;

export type TransitionStatus = keyof typeof TRANSITION_TARGETS;

/**
 * Throw unless every key is `PROJ-NNN`; with `sandbox`, also unless every key
 * belongs to {@link SANDBOX_PROJECT}. Runs before any MCP call.
 */
export function assertKeys(keys: string[], sandbox: boolean): void {
  if (keys.length === 0) throw new Error("No Jira keys given.");
  for (const key of keys) {
    if (!JIRA_KEY_RE.test(key)) {
      throw new Error(`Invalid Jira key \`${key}\`; expected PROJ-NNN (e.g. SAND-42).`);
    }
    if (sandbox && !key.startsWith(`${SANDBOX_PROJECT}-`)) {
      throw new Error(
        `Refusing ${key}: write flows run against project ${SANDBOX_PROJECT} only until they are signed off.`,
      );
    }
  }
}

/** Throw unless `project` is {@link SANDBOX_PROJECT}. */
export function assertSandboxProject(project: string): void {
  if (project !== SANDBOX_PROJECT) {
    throw new Error(
      `Refusing project ${project}: write flows run against project ${SANDBOX_PROJECT} only until they are signed off.`,
    );
  }
}

/**
 * Jira keys among `:LINKED_ISSUES:` tokens (see `references/protocol.md`):
 * a typed `[[jira:KEY]]`, or a raw org link `[[url]]` / `[[url][label]]`
 * whose host equals the host of `baseUrl` and whose last path segment is a
 * key. Other tokens are ignored. Order is kept; duplicates are dropped.
 */
export function jiraKeysFromTokens(tokens: string[], baseUrl: string | null): string[] {
  let baseHost: string | null = null;
  try {
    baseHost = baseUrl ? new URL(baseUrl).host : null;
  } catch {
    baseHost = null;
  }
  const keys: string[] = [];
  for (const token of tokens) {
    const link = /^\[\[([^\]]+)\](?:\[[^\]]*\])?\]$/.exec(token.trim());
    if (!link) continue;
    const target = link[1]!;
    let key: string | null = null;
    const typed = /^jira:(.+)$/.exec(target);
    if (typed) {
      key = typed[1]!;
    } else if (baseHost) {
      try {
        const url = new URL(target);
        if (url.host === baseHost) key = url.pathname.split("/").filter(Boolean).pop() ?? null;
      } catch {
        key = null;
      }
    }
    if (key && JIRA_KEY_RE.test(key) && !keys.includes(key)) keys.push(key);
  }
  return keys;
}

/**
 * Jira labels as org tags: `ot` accepts `[A-Za-z0-9_]+` only, so other
 * characters become `_` (`tech-debt` -> `tech_debt`). Empty results and
 * duplicates are dropped; order is kept.
 */
export function orgTags(labels: string[]): string[] {
  return [...new Set(labels.map((l) => l.replace(/[^A-Za-z0-9_]/g, "_")).filter(Boolean))];
}

/** First transition matching `status`, trying the target names in order. */
export function pickTransition<T extends { name: string }>(
  status: TransitionStatus,
  transitions: T[],
): T | null {
  for (const target of TRANSITION_TARGETS[status]) {
    const hit = transitions.find((t) => t.name.toLowerCase() === target.toLowerCase());
    if (hit) return hit;
  }
  return null;
}

/** The fields of an issue that the read flows keep. */
export interface IssueSummary {
  key: string;
  summary: string;
  status: string | null;
  priority: string | null;
  issuetype: string | null;
  labels: string[];
  parent: { key: string; summary: string } | null;
  subtasks: Array<{ key: string; summary: string }>;
  description: string | null;
  assignee: string | null;
  reporter: string | null;
  /** `YYYY-MM-DD` of the last update. */
  updated: string | null;
  affectsVersions: string[];
  fixVersions: string[];
  components: string[];
  /** Each link from this issue's side: `relation` is the outward or inward phrase. */
  issueLinks: Array<{ relation: string; key: string; summary: string }>;
  commentCount: number | null;
}

/** The `name` of each entry in a Jira list field (versions, components). */
function names(list: unknown): string[] {
  return Array.isArray(list)
    ? list.map((v: any) => v?.name).filter((n: unknown): n is string => typeof n === "string")
    : [];
}

function issueLinks(list: unknown): IssueSummary["issueLinks"] {
  if (!Array.isArray(list)) return [];
  return list.flatMap((l: any) => {
    const out = l?.outwardIssue;
    const other = out ?? l?.inwardIssue;
    if (!other?.key) return [];
    const relation = (out ? l?.type?.outward : l?.type?.inward) ?? l?.type?.name ?? "links to";
    return [{ relation, key: other.key, summary: other.fields?.summary ?? "" }];
  });
}

/** Text of an ADF node: text leaves joined, block children separated by a blank line. */
function adfText(node: any): string {
  if (typeof node?.text === "string") return node.text;
  if (!Array.isArray(node?.content)) return "";
  const inline = node.content.every((c: any) => typeof c?.text === "string" || c?.type === "hardBreak");
  return node.content
    .map((c: any) => (c?.type === "hardBreak" ? "\n" : adfText(c)))
    .filter((s: string) => inline || s.trim() !== "")
    .join(inline ? "" : "\n\n");
}

/**
 * The issue description as text, or null. `getJiraIssue` returns markdown for
 * `responseContentFormat: "markdown"`, but can still return an ADF document
 * (observed live: an empty description on SAND-75 came back as
 * `{type: "doc", version: 1, content: []}`).
 */
function descriptionText(d: unknown): string | null {
  const text = typeof d === "string" ? d : adfText(d);
  return text.trim() === "" ? null : text;
}

/** Filter a parsed `getJiraIssue` result down to {@link IssueSummary}. */
export function normalizeIssue(key: string, issue: any): IssueSummary {
  const f = issue?.fields ?? {};
  return {
    key,
    summary: f.summary ?? "",
    status: f.status?.name ?? null,
    priority: f.priority?.name ?? null,
    issuetype: f.issuetype?.name ?? null,
    labels: f.labels ?? [],
    parent: f.parent
      ? { key: f.parent.key, summary: f.parent.fields?.summary ?? "" }
      : null,
    subtasks: (f.subtasks ?? []).map((s: any) => ({
      key: s.key,
      summary: s.fields?.summary ?? "",
    })),
    description: descriptionText(f.description),
    assignee: f.assignee?.displayName ?? null,
    reporter: f.reporter?.displayName ?? null,
    updated: typeof f.updated === "string" ? f.updated.slice(0, 10) : null,
    affectsVersions: names(f.versions),
    fixVersions: names(f.fixVersions),
    components: names(f.components),
    issueLinks: issueLinks(f.issuelinks),
    commentCount:
      typeof f.comment?.total === "number"
        ? f.comment.total
        : Array.isArray(f.comment?.comments)
          ? f.comment.comments.length
          : null,
  };
}

const SUBTASK_CAP = 5;
const PREVIEW_CHARS = 300;

/** `Label: value`, or `Label: none` when the value is empty. */
const field = (label: string, value: string | null | undefined): string =>
  `${label}: ${value || "none"}`;

/** `Label: none`, or `Label (n):` then up to {@link SUBTASK_CAP} `- item` lines and a `+N more` line. */
function cappedList(label: string, items: string[]): string[] {
  if (items.length === 0) return [`${label}: none`];
  const out = [`${label} (${items.length}):`, ...items.slice(0, SUBTASK_CAP).map((i) => `- ${i}`)];
  if (items.length > SUBTASK_CAP) out.push(`- +${items.length - SUBTASK_CAP} more`);
  return out;
}

/**
 * Compact human-readable block for `jira_get`: every field has a line, and an
 * empty one reads `none`, so the block reads as complete. When empty fields were
 * omitted, an agent took the block as incomplete and re-fetched the raw issue.
 * No caller parses the block; the model shows it verbatim.
 */
export function renderIssueBlock(issue: IssueSummary, baseUrl: string | null): string {
  const lines = [
    `${issue.key} - ${issue.summary}${issue.status ? ` [${issue.status}]` : ""}`,
    `Priority: ${issue.priority ?? "none"}; type: ${issue.issuetype ?? "unknown"}; labels: ${
      issue.labels.length > 0 ? issue.labels.join(", ") : "none"
    }`,
    field("Components", issue.components.join(", ")),
    field("Affects versions", issue.affectsVersions.join(", ")),
    field("Fix versions", issue.fixVersions.join(", ")),
    field("Assignee", issue.assignee),
    field("Reporter", issue.reporter),
    field("Updated", issue.updated),
    field("Parent", issue.parent && `${issue.parent.key} - ${issue.parent.summary}`),
    ...cappedList("Subtasks", issue.subtasks.map((s) => `${s.key} - ${s.summary}`)),
    ...cappedList("Issue links", issue.issueLinks.map((l) => `${l.relation} ${l.key} - ${l.summary}`)),
    field("Comments", issue.commentCount === null ? null : String(issue.commentCount)),
  ];
  const paragraph = (issue.description ?? "").trim().split(/\n\s*\n/)[0] ?? "";
  lines.push(
    field(
      "Description",
      paragraph.length > PREVIEW_CHARS ? `${paragraph.slice(0, PREVIEW_CHARS)}...` : paragraph,
    ),
  );
  if (baseUrl) lines.push(`${baseUrl}/browse/${issue.key}`);
  return lines.join("\n");
}

// ── Hidden one-line triggers ────────────────────────────────────────────
//
// Each `/jira` flow sends the model one line: a single `codemode` call of a
// `jira_*` tool with literal args. Scripts, protocol steps and rendering
// rules live in the tools. Codemode scripts address a tool as
// `tools.<name>`; the `jira` namespace only groups the tool listing.

function trigger(tool: string, args: unknown, then: string): string {
  return `Call \`codemode\` once with exactly this code, then ${then}: return await tools.${tool}(${JSON.stringify(args)});`;
}

export function buildGetTrigger(keys: string[]): string {
  return trigger("jira_get", { keys }, "show the returned text verbatim and add nothing");
}

export function buildCloneTrigger(keys: string[]): string {
  return trigger("jira_clone", { keys }, "state each key's status in one line");
}

export function buildClaimTrigger(keys: string[]): string {
  return trigger("jira_claim", { keys }, "state each key's outcome in one line");
}

export function buildCommentTrigger(keys: string[], body: string): string {
  return trigger("jira_comment", { keys, body }, "state each key's outcome in one line");
}

export function buildCreateTrigger(taskId: string, project: string, type: string): string {
  return trigger("jira_create", { taskId, project, type }, "state the new key and URL");
}

export function buildTransitionTrigger(keys: string[], status: TransitionStatus): string {
  return trigger(
    "jira_transition",
    { keys, status },
    "state each key's outcome in one line; for a key with `choices`, list them and do nothing else",
  );
}

export interface CreateOptions {
  /** Project key arg passed on the command line. Falls back to cfg.project. */
  project: string | null;
  /** Issue type override (e.g. `Story`, `Bug`). Defaults to `Task`. */
  type: string;
}

/**
 * Parse the `/jira create` argument string into project + type.
 * Accepts either positional or `--type` form:
 *   /jira create               → { project: null, type: "Task" }
 *   /jira create SAND          → { project: "SAND", type: "Task" }
 *   /jira create --type Story  → { project: null, type: "Story" }
 *   /jira create SAND --type Bug → { project: "SAND", type: "Bug" }
 */
export function parseCreateArgs(parts: string[]): CreateOptions {
  let project: string | null = null;
  let type = "Task";
  for (let i = 0; i < parts.length; i++) {
    const arg = parts[i]!;
    if (arg === "--type") {
      const next = parts[i + 1];
      if (next) {
        type = next;
        i++;
      }
      continue;
    }
    if (arg.startsWith("--type=")) {
      type = arg.slice("--type=".length);
      continue;
    }
    if (arg.startsWith("-")) continue; // ignore unknown flags for now
    if (project === null) project = arg;
  }
  return { project, type };
}

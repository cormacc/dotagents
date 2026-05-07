/**
 * Pure helpers for the jira extension. No external runtime imports so
 * the test runner can exercise them without pulling in `pi-tui` or other
 * packages that aren't installed in the test environment.
 */

/** Validation regex for bare Jira keys, e.g. `MBFW-123`. */
export const JIRA_KEY_RE = /^[A-Z][A-Z0-9_]+-\d+$/;
/** A bare integer (no project prefix) — resolves against `#+JIRA_PROJECT`. */
export const BARE_NUMBER_RE = /^\d+$/;

export interface JiraConfig {
  cloudId: string | null;
  project: string | null;
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
        error: `Bare number "${arg}" needs a project. Set #+JIRA_PROJECT in TASKS.org or pass the full key (e.g. SAND-${arg}).`,
      };
    }
    return { key: `${project}-${arg}` };
  }
  return {
    error: `"${arg}" is not a valid Jira key. Expected PROJ-NNN (e.g. SAND-42) or a bare number with #+JIRA_PROJECT set.`,
  };
}

/**
 * Compose the structured prompt that drives `/jira clone` end-to-end.
 *
 * Two-step dispatch (re-scoped 2026-04-28 — see
 * `design/log/2026-04-28-jira-clone-token-efficiency.org`):
 *
 *   1. `atlassian_getJiraIssue` with the field-list filter.
 *   2. `jira_clone_apply` with the parsed fields (key, summary,
 *      priorityName, body, labels). The body, summary, and labels
 *      never re-appear in any `edit` tool argument.
 *
 * Org-mode string assembly (drawer, UUID, :CREATED:, priority cookie,
 * tag suffix) lives in `tasks_insert_task` via the `tasks` extension's
 * `buildTaskBlock` helper. The model never re-emits the rendered body.
 *
 * Pure function — exported so tests can snapshot the prompt shape.
 */
export function buildClonePrompt(
  keys: string[],
  cfg: JiraConfig,
  cwd: string,
  tasksFile: string,
  tasksLocalFile: string,
): string {
  const cloudIdLine = cfg.cloudId
    ? `Use cloudId \`${cfg.cloudId}\` from #+JIRA_CLOUDID.`
    : "Resolve the cloudId by calling `atlassian_getAccessibleAtlassianResources`" +
      (cfg.baseUrl
        ? ` and selecting the resource whose \`url\` equals \`${cfg.baseUrl}\`.`
        : ".");
  const keyList = keys.map((k) => `\`${k}\``).join(", ");

  return [
    `Clone the following Jira issue${keys.length === 1 ? "" : "s"} into ${tasksFile} (or ${tasksLocalFile} if working in the local-drafts area): ${keyList}.`,
    "",
    "Use the **org-jira** skill for the full clone protocol; the steps below are a concise reminder, not a substitute.",
    "",
    "This is a *two-step* dispatch: first fetch the issue, then forward the",
    "parsed fields to `jira_clone_apply`. Do **not** assemble the org task",
    "block manually via the `edit` tool; org-mode rendering (drawer, UUID,",
    "`:CREATED:`, priority cookie, tag suffix, `:LINKED_ISSUES:`) is owned",
    "by the `jira_clone_apply` tool's deterministic helper.",
    "",
    "Steps for each key:",
    `1. ${cloudIdLine}`,
    "2. Call `atlassian_getJiraIssue` with the resolved cloudId, the issue key, and `fields=\"summary,priority,labels,description,issuetype,parent,subtasks\"` to keep the response small. Do not request `*all` or expand customfields.",
    "3. Render the issue description as plain text/markdown (Jira ADF → markdown-ish; do not embed raw ADF JSON). Apply any obvious cleanup (collapse broken `| --- |` table rows, trim noisy summary boilerplate). Keep the result short.",
    "4. Call `jira_clone_apply` with:",
    "   - `key` — the issue key.",
    "   - `summary` — `issue.fields.summary` verbatim (after any small surgery).",
    "   - `priorityName` — the priority name string (`Highest`/`High`/`Medium`/`Low`/`Lowest`); omit when missing/unknown.",
    "   - `body` — the rendered description from step 3.",
    "   - `labels` — the issue's label list (may be empty).",
    `   - \`file\` — \`${tasksFile}\` by default; pass \`${tasksLocalFile}\` only when the user is working on local drafts.`,
    "   - `section` — omit to use `Improvements`; pass an explicit section if the user has been working in a different one.",
    "5. Surface the tool's structured return verbatim:",
    "   - `status: \"inserted\"` — confirm with the new heading and Jira URL.",
    "   - `status: \"duplicate\"` — tell the user the issue is already cloned (cite the existing task's `:ID:` from `details.existingId`).",
    "   - `status: \"section_not_found\"` — ask whether to retry with `allowCreateSection: true` or correct the section name.",
    "   - `status: \"error\"` — surface the message.",
    "",
    `Project root: \`${cwd}\`.`,
    "Do not invoke the tasks-extension UI; the tool writes the file directly.",
    "",
    "After cloning, summarise: one bullet per key with the new local task's heading and Jira URL.",
  ].join("\n");
}

/**
 * Compose the prompt for the user-facing inspection helper `/jira get`.
 *
 * Re-scoped at plan time (see
 * `design/log/2026-04-28-jira-clone-token-efficiency.org` →
 * "~/jira get KEY~ ergonomics subcommand"): no underlying
 * `jira_get_issue` tool exists today because pi-mcp-adapter does not
 * expose a JS-callable client to extensions. This helper therefore
 * stays a prompt-builder — the agent calls `atlassian_getJiraIssue`
 * directly with the same field filter used by `buildClonePrompt`,
 * then renders a compact human-readable summary.
 *
 * Pure function — exported so tests can snapshot the prompt shape.
 */
export function buildGetPrompt(
  keys: string[],
  cfg: JiraConfig,
  cwd: string,
): string {
  const cloudIdLine = cfg.cloudId
    ? `Use cloudId \`${cfg.cloudId}\` from #+JIRA_CLOUDID.`
    : "Resolve the cloudId by calling `atlassian_getAccessibleAtlassianResources`" +
        (cfg.baseUrl
          ? ` and selecting the resource whose \`url\` equals \`${cfg.baseUrl}\`.`
          : ".");
  const keyList = keys.map((k) => `\`${k}\``).join(", ");
  const plural = keys.length === 1 ? "" : "s";

  return [
    `Show the user a compact human-readable summary of the following Jira issue${plural}: ${keyList}.`,
    "",
    "Use the **org-jira** skill for the canonical inspection conventions; the steps below are a concise reminder.",
    "",
    `Project root: \`${cwd}\`.`,
    "",
    "Steps for each key:",
    `1. ${cloudIdLine}`,
    "2. Call `atlassian_getJiraIssue` with the resolved cloudId, the issue key, and `fields=\"summary,priority,labels,description,issuetype,parent,subtasks,status\"` to keep the response small. Do not request `*all` or expand customfields.",
    "3. Render a compact, human-readable block (NOT raw JSON):",
    "   - Heading: `KEY \u2014 <summary>` plus the issue's status.",
    "   - One-line metadata: priority, issuetype, labels (comma-separated; “none” when empty).",
    "   - Parent: `parent.key \u2014 parent.fields.summary` when set; otherwise omit.",
    "   - Subtasks: count plus each subtask's `key \u2014 summary` line, capped at 5 with a “+N more” footnote if exceeded.",
    "   - Description preview: first paragraph of the description, max 300 characters; mark truncation with “…”.",
    cfg.baseUrl
      ? `   - Footer link: \`${cfg.baseUrl}/browse/<KEY>\`.`
      : "   - Footer link: skip when `#+JIRA_BASE_URL` is unset.",
    "",
    "Render each key as its own block separated by a blank line. Do not write to any TASKS file.",
  ].join("\n");
}

/**
 * Helper used by all selected-task workflows (claim/comment/transition):
 * compose the cloudId-resolution preamble for the prompt.
 */
function cloudIdInstruction(cfg: JiraConfig): string {
  return cfg.cloudId
    ? `Use cloudId \`${cfg.cloudId}\` from #+JIRA_CLOUDID.`
    : "Resolve the cloudId by calling `atlassian_getAccessibleAtlassianResources`" +
        (cfg.baseUrl
          ? ` and selecting the resource whose \`url\` equals \`${cfg.baseUrl}\`.`
          : ".");
}

/**
 * Compose the prompt that drives `/jira claim` against the selected
 * task's Jira-shaped `:LINKED_ISSUES:` tokens.
 */
export function buildClaimPrompt(
  cfg: JiraConfig,
  cwd: string,
  tasksFile: string,
  tasksLocalFile: string,
  selectedId: string | null,
): string {
  const selectedLine = selectedId
    ? `The selected task's :ID: is \`${selectedId}\` (from #+SELECTED: in ${tasksLocalFile}).`
    : `Identify the selected task from #+SELECTED: in ${tasksLocalFile}; refuse with a notification if none is set.`;
  return [
    "Claim every Jira-shaped issue linked from the **selected task** by setting its assignee to the current Atlassian user.",
    "",
    "Use the **org-jira** skill for the full claim protocol; the steps below are a concise reminder, not a substitute.",
    "",
    `Project root: \`${cwd}\`.`,
    selectedLine,
    "",
    "Steps:",
    `1. ${cloudIdInstruction(cfg)}`,
    `2. Read the selected task's \`:LINKED_ISSUES:\` drawer property from ${tasksFile} or its imports. Filter to Jira-shaped tokens:`,
    "   - Bare tokens matching `^[A-Z][A-Z0-9_]+-\\d+$`.",
    cfg.baseUrl
      ? `   - Org-link tokens whose target host equals \`${new URL(cfg.baseUrl).host}\`.`
      : "   - Org-link tokens are skipped when #+JIRA_BASE_URL is unset.",
    "3. Call `atlassian_atlassianUserInfo` once to obtain the current user's `accountId`.",
    "4. For each Jira-shaped key, call `atlassian_editJiraIssue` with `assignee.accountId` set to that value.",
    "5. Surface a one-line summary per key with success or error message.",
    "",
    "Sandbox-only during development: only run against project `SAND` until the workflow is signed off.",
  ].join("\n");
}

/**
 * Compose the prompt that drives `/jira comment <markdown>` against the
 * selected task's Jira-shaped `:LINKED_ISSUES:` tokens.
 */
export function buildCommentPrompt(
  body: string,
  cfg: JiraConfig,
  cwd: string,
  tasksFile: string,
  tasksLocalFile: string,
  selectedId: string | null,
): string {
  const selectedLine = selectedId
    ? `The selected task's :ID: is \`${selectedId}\` (from #+SELECTED: in ${tasksLocalFile}).`
    : `Identify the selected task from #+SELECTED: in ${tasksLocalFile}; refuse with a notification if none is set.`;
  return [
    "Add a comment to every Jira-shaped issue linked from the **selected task**.",
    "",
    "Use the **org-jira** skill for the full comment protocol; the steps below are a concise reminder, not a substitute.",
    "",
    `Project root: \`${cwd}\`.`,
    selectedLine,
    "",
    "Comment body (verbatim, in fenced markdown):",
    "```markdown",
    body,
    "```",
    "",
    "Steps:",
    `1. ${cloudIdInstruction(cfg)}`,
    `2. Read the selected task's \`:LINKED_ISSUES:\` drawer property from ${tasksFile} or its imports. Filter to Jira-shaped tokens (see the org-jira skill).`,
    "3. For each Jira-shaped key, call `atlassian_addCommentToJiraIssue` with the markdown body above. The MCP server handles markdown → ADF conversion.",
    "4. Surface a one-line summary per key with success or error message.",
    "",
    "Sandbox-only during development: only run against project `SAND` until the workflow is signed off.",
  ].join("\n");
}

export interface CreateOptions {
  /** Project key arg passed on the command line. Falls back to cfg.project. */
  project: string | null;
  /** Issue type override (e.g. `Story`, `Bug`). Defaults to `Task`. */
  type: string;
}

/**
 * Compose the prompt that drives `/jira create` — promote the selected
 * task to a new Jira issue and write the new key back to
 * `:LINKED_ISSUES:`.
 */
export function buildCreatePrompt(
  opts: CreateOptions,
  cfg: JiraConfig,
  cwd: string,
  tasksFile: string,
  tasksLocalFile: string,
  selectedId: string | null,
): string {
  const project = opts.project ?? cfg.project;
  if (!project) {
    return [
      "Refuse the /jira create request: no project given on the command line and #+JIRA_PROJECT is not set in TASKS.org.",
      "Notify the user to either pass a PROJECT argument or set #+JIRA_PROJECT.",
    ].join("\n");
  }
  const selectedLine = selectedId
    ? `The selected task's :ID: is \`${selectedId}\` (from #+SELECTED: in ${tasksLocalFile}).`
    : `Identify the selected task from #+SELECTED: in ${tasksLocalFile}; refuse with a notification if none is set.`;
  return [
    `Promote the **selected task** to a new Jira issue in project \`${project}\` (issue type \`${opts.type}\`).`,
    "",
    "Use the **org-jira** skill for the full create protocol; the steps below are a concise reminder, not a substitute.",
    "",
    `Project root: \`${cwd}\`.`,
    selectedLine,
    "",
    "Steps:",
    `1. ${cloudIdInstruction(cfg)}`,
    `2. Verify the issue type \`${opts.type}\` exists in project \`${project}\` via \`atlassian_getJiraProjectIssueTypesMetadata\`. If not, surface the available types and stop.`,
    `3. Read the selected task's heading and body from ${tasksFile} (or its imports). The heading becomes the issue \`summary\`; the body becomes the issue \`description\` (markdown → ADF; the MCP server handles conversion).`,
    `4. Call \`atlassian_createJiraIssue\` with project \`${project}\`, issue type \`${opts.type}\`, summary, and description.`,
    "5. On success, append the returned key to the selected task's `:LINKED_ISSUES:` drawer property (whitespace-separated; preserve any existing tokens).",
    "6. Save the file. Confirm by surfacing the new key and Jira URL.",
    "",
    "Sandbox-only during development: only run against project `SAND` until the workflow is signed off.",
  ].join("\n");
}

/**
 * Compose the prompt that drives auto-transition on a task's
 * `tasks:status-changed` event.
 *
 * Pure function so it can be tested without a live event.
 */
export function buildTransitionPrompt(
  newStatus: "STARTED" | "DONE",
  taskId: string,
  taskSummary: string,
  cfg: JiraConfig,
  cwd: string,
  tasksFile: string,
  tasksLocalFile: string,
): string {
  const cloudIdLine = cfg.cloudId
    ? `Use cloudId \`${cfg.cloudId}\` from #+JIRA_CLOUDID.`
    : "Resolve the cloudId by calling `atlassian_getAccessibleAtlassianResources`" +
        (cfg.baseUrl
          ? ` and selecting the resource whose \`url\` equals \`${cfg.baseUrl}\`.`
          : ".");
  const targets =
    newStatus === "STARTED"
      ? ["Start Progress", "In Progress"]
      : ["Done", "Closed", "Resolved"];
  return [
    `Auto-transition: the local task \`${taskSummary}\` (:ID: \`${taskId}\`) just moved to \`${newStatus}\`. Mirror this on every Jira-shaped issue linked from the task's \`:LINKED_ISSUES:\`.`,
    "",
    "Use the **org-jira** skill for the full transition protocol; the steps below are a concise reminder.",
    "",
    `Project root: \`${cwd}\`.`,
    `Look up the task in ${tasksFile} (or its imports). Selected-task UUID is \`${taskId}\`.`,
    "",
    "Steps for each Jira-shaped key:",
    `1. ${cloudIdLine}`,
    `2. Call \`atlassian_getTransitionsForJiraIssue\` for the key.`,
    `3. Pick the first transition whose name matches one of (case-insensitive): ${targets.map((t) => `\`${t}\``).join(", ")}.`,
    "4. If no name matches, surface a chooser to the user instead of guessing.",
    "5. Call `atlassian_transitionJiraIssue` with the picked transition id.",
    "6. Surface a one-line summary per key with success or fall-back chooser.",
    "",
    "Sandbox-only during development: only run against project `SAND` until the workflow is signed off.",
  ].join("\n");
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

/**
 * Codemode-exposed `jira_*` tools.
 *
 * Every tool calls `mcp__atlassian__*` through `ctx.executeTool()` and returns
 * only the fields or rendered text its flow needs, so no raw `CallToolResult`
 * reaches the model. The `/jira` command handlers (see `./index.ts`) send one
 * hidden line that names a single `codemode` call of one of these tools.
 *
 * Write tools enforce the `SAND`-only restriction in TypeScript, before any
 * MCP call.
 */

import type { ExtensionAPI, ExtensionToolContext } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";
import { readFile } from "node:fs/promises";
import { isAbsolute, join, resolve } from "node:path";
import { readEffectiveOrgContent } from "../tasks/effective.ts";
import { insertTaskIntoFile } from "../tasks/insert.ts";
import { runOtResult } from "../tasks/ot.ts";
import {
  ATLASSIAN_TOOL_PREFIX,
  assertKeys,
  assertSandboxProject,
  ISSUE_FIELDS,
  jiraKeysFromTokens,
  normalizeIssue,
  orgTags,
  pickTransition,
  renderIssueBlock,
  resolveJiraConfig,
  type JiraConfig,
  type TransitionStatus,
} from "./utils.ts";

/** File-name conventions matching the tasks extension. */
export const TASKS_FILE = "TASKS.org";
export const TASKS_LOCAL_FILE = "TASKS.local.org";

/** Read Jira config from the same effective Org content/order as tasks. */
export async function loadJiraConfig(cwd: string): Promise<JiraConfig> {
  const tasksPath = join(cwd, TASKS_FILE);
  try {
    const content = await readFile(tasksPath, "utf-8");
    return resolveJiraConfig(await readEffectiveOrgContent(cwd, tasksPath, content));
  } catch {
    /* TASKS.org may not exist or effective setup expansion may fail. */
    return resolveJiraConfig("");
  }
}

// ── ot-backed task lookup (used by the command handlers and jira_create) ──

interface OtTask {
  id: string;
  summary: string;
  description?: string | null;
  linkedIssues?: Array<{ rawToken: string }>;
}

function keysOf(task: OtTask, cfg: JiraConfig): string[] {
  return jiraKeysFromTokens((task.linkedIssues ?? []).map((i) => i.rawToken), cfg.baseUrl);
}

/** The selected task and its Jira keys, or null when nothing is selected. */
export async function resolveSelectedTask(
  cwd: string,
  cfg: JiraConfig,
): Promise<{ id: string; summary: string; keys: string[] } | null> {
  const res = await runOtResult<{ task?: OtTask | null }>(["selected"], { cwd });
  const task = res.task;
  return task ? { id: task.id, summary: task.summary, keys: keysOf(task, cfg) } : null;
}

/** The Jira keys linked from task `id`. */
export async function resolveTaskKeys(
  cwd: string,
  id: string,
  cfg: JiraConfig,
): Promise<string[]> {
  const res = await runOtResult<{ task: OtTask }>(["show", id], { cwd });
  return keysOf(res.task, cfg);
}

// ── MCP access through ctx.executeTool ──────────────────────────────────

type ToolCtx = Pick<ExtensionToolContext, "cwd" | "executeTool">;

const LOGIN_HINT = "if unauthenticated, run /mcp login atlassian";

function blocksText(blocks: unknown): string {
  if (!Array.isArray(blocks)) return "";
  return blocks
    .filter((b: any) => b?.type === "text" && typeof b.text === "string")
    .map((b: any) => b.text as string)
    .join("\n");
}

/**
 * Call one `atlassian` server tool. MCP results carry the untruncated
 * `CallToolResult` as `structuredContent`; its text block is parsed as JSON.
 * An error outcome throws with the Jira message and a login hint.
 */
async function callAtlassian(
  ctx: ToolCtx,
  signal: AbortSignal | undefined,
  name: string,
  args: Record<string, unknown>,
): Promise<any> {
  const outcome = await ctx.executeTool(`${ATLASSIAN_TOOL_PREFIX}${name}`, args, { signal });
  const result: any = outcome.result ?? {};
  const structured = result.structuredContent;
  const text = blocksText(structured?.content) || blocksText(result.content);
  if (outcome.isError || result.isError || structured?.isError) {
    throw new Error(`${name} failed: ${text.slice(0, 500)} (${LOGIN_HINT})`);
  }
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

/** `#+JIRA_CLOUDID`, else the accessible site matching the `#+LINK: jira` base URL. */
async function resolveCloudId(
  ctx: ToolCtx,
  signal: AbortSignal | undefined,
  cfg: JiraConfig,
): Promise<string> {
  if (cfg.cloudId) return cfg.cloudId;
  const sites: Array<{ id: string; url?: string }> = await callAtlassian(
    ctx,
    signal,
    "getAccessibleAtlassianResources",
    {},
  );
  if (cfg.baseUrl) {
    const site = sites.find((s) => s.url === cfg.baseUrl);
    if (!site) throw new Error(`No accessible Atlassian site with url ${cfg.baseUrl}`);
    return site.id;
  }
  // The same site is listed once per scope set, so de-duplicate the ids.
  const ids = [...new Set(sites.map((s) => s.id))];
  if (ids.length !== 1) throw new Error("Cannot resolve the cloudId: set #+JIRA_CLOUDID");
  return ids[0]!;
}

function textResult<T>(text: string, details: T) {
  return { content: [{ type: "text" as const, text }], details };
}

/** A data result: the text for the model and `structuredContent` for scripts. */
function dataResult<T>(data: T) {
  return {
    content: [{ type: "text" as const, text: JSON.stringify(data) }],
    details: data,
    structuredContent: data as never,
  };
}

function errorText(e: unknown): string {
  return String(e instanceof Error ? e.message : e).slice(0, 300);
}

// ── Schemas ─────────────────────────────────────────────────────────────

const Keys = Type.Array(Type.String(), { description: "Jira keys, PROJ-NNN" });
const WriteKeys = Type.Array(Type.String(), { description: "Jira keys in project SAND" });

const PerKeyOutcome = Type.Array(
  Type.Object({
    key: Type.String(),
    ok: Type.Boolean(),
    error: Type.Optional(Type.String()),
  }),
);

const TransitionOutcome = Type.Array(
  Type.Object({
    key: Type.String(),
    ok: Type.Optional(Type.Boolean()),
    transition: Type.Optional(Type.String()),
    choices: Type.Optional(Type.Array(Type.Object({ id: Type.String(), name: Type.String() }))),
    error: Type.Optional(Type.String()),
  }),
);

const CloneOutcome = Type.Array(
  Type.Object({
    key: Type.String(),
    status: Type.Union([
      Type.Literal("inserted"),
      Type.Literal("duplicate"),
      Type.Literal("section_not_found"),
      Type.Literal("error"),
    ]),
    id: Type.Optional(Type.String()),
    file: Type.Optional(Type.String()),
    line: Type.Optional(Type.Number()),
    existingId: Type.Optional(Type.String()),
    section: Type.Optional(Type.String()),
    message: Type.Optional(Type.String()),
  }),
);

const NAMESPACE = {
  name: "jira",
  description: "Jira workflows over the Atlassian MCP server; each tool returns only the fields its flow needs.",
};

// ── Tools ───────────────────────────────────────────────────────────────

/** Run `fn` for every key; one key's failure is reported on that key only. */
async function perKey<T extends { key: string }>(
  keys: string[],
  fn: (key: string) => Promise<Omit<T, "key">>,
  failed: (key: string, error: string) => T,
): Promise<T[]> {
  const out: T[] = [];
  for (const key of keys) {
    try {
      out.push({ key, ...(await fn(key)) } as T);
    } catch (e) {
      out.push(failed(key, errorText(e)));
    }
  }
  return out;
}

async function fetchIssues(
  ctx: ToolCtx,
  signal: AbortSignal | undefined,
  keys: string[],
  cfg: JiraConfig,
) {
  const cloudId = await resolveCloudId(ctx, signal, cfg);
  const issues = [];
  for (const key of keys) {
    const raw = await callAtlassian(ctx, signal, "getJiraIssue", {
      cloudId,
      issueIdOrKey: key,
      fields: [...ISSUE_FIELDS],
      responseContentFormat: "markdown",
    });
    issues.push(normalizeIssue(key, raw));
  }
  return issues;
}

export function registerJiraTools(pi: ExtensionAPI): void {
  pi.registerTool({
    name: "jira_get",
    label: "Jira: get",
    description: "Compact summary block per Jira issue.",
    exposure: "deferred",
    namespace: NAMESPACE,
    parameters: Type.Object({ keys: Keys }),
    async execute(_id, params, signal, _onUpdate, ctx) {
      assertKeys(params.keys, false);
      const cfg = await loadJiraConfig(ctx.cwd);
      const issues = await fetchIssues(ctx, signal, params.keys, cfg);
      return textResult(
        issues.map((i) => renderIssueBlock(i, cfg.baseUrl)).join("\n\n"),
        { keys: params.keys },
      );
    },
  });

  pi.registerTool({
    name: "jira_clone",
    label: "Jira: clone",
    description:
      "Clone Jira issues into TASKS.org as local tasks. Returns [{key, status, ...}] with status inserted, duplicate, section_not_found or error.",
    exposure: "deferred",
    namespace: NAMESPACE,
    parameters: Type.Object({
      keys: Keys,
      file: Type.Optional(Type.String({ description: "Org file; default TASKS.org" })),
      section: Type.Optional(Type.String({ description: "Top-level section; default Improvements" })),
      allowCreateSection: Type.Optional(Type.Boolean({ description: "Append a missing section" })),
    }),
    outputSchema: CloneOutcome,
    async execute(_id, params, signal, _onUpdate, ctx) {
      assertKeys(params.keys, false);
      const cwd = ctx.cwd;
      const cfg = await loadJiraConfig(cwd);
      // Fetch every issue first: a failed fetch inserts nothing.
      const issues = await fetchIssues(ctx, signal, params.keys, cfg);

      // The default file is the project's TASKS.org; alsoScan covers
      // TASKS.local.org so a local draft of the same issue is a duplicate.
      const file = params.file ?? join(cwd, TASKS_FILE);
      const fileAbs = isAbsolute(file) ? file : resolve(cwd, file);
      const localAbs = join(cwd, TASKS_LOCAL_FILE);
      const sibling = fileAbs === localAbs ? join(cwd, TASKS_FILE) : localAbs;

      const out: Array<Record<string, unknown>> = [];
      for (const issue of issues) {
        const r = await insertTaskIntoFile({
          file: fileAbs,
          projectRoot: cwd,
          section: params.section ?? "Improvements",
          summary: issue.summary,
          priorityName: issue.priority,
          body: issue.description,
          labels: orgTags(issue.labels),
          linkedIssues: [`[[jira:${issue.key}]]`],
          allowCreateSection: params.allowCreateSection ?? false,
          alsoScan: [sibling],
        });
        switch (r.status) {
          case "inserted":
            out.push({ key: issue.key, status: r.status, id: r.id, file: r.file, line: r.line });
            break;
          case "duplicate":
            out.push({
              key: issue.key,
              status: r.status,
              existingId: r.existingId ?? undefined,
              file: r.existingFile,
            });
            break;
          case "section_not_found":
            out.push({ key: issue.key, status: r.status, section: r.section, file: r.file });
            break;
          case "error":
            out.push({ key: issue.key, status: r.status, message: r.message });
            break;
        }
      }
      return dataResult(out);
    },
  });

  pi.registerTool({
    name: "jira_claim",
    label: "Jira: claim",
    description: "Assign Jira issues (project SAND only) to the current Atlassian user. Returns [{key, ok, error}].",
    exposure: "deferred",
    namespace: NAMESPACE,
    parameters: Type.Object({ keys: WriteKeys }),
    outputSchema: PerKeyOutcome,
    async execute(_id, params, signal, _onUpdate, ctx) {
      assertKeys(params.keys, true);
      const cfg = await loadJiraConfig(ctx.cwd);
      const cloudId = await resolveCloudId(ctx, signal, cfg);
      const me = await callAtlassian(ctx, signal, "atlassianUserInfo", {});
      const accountId = me?.account_id;
      if (!accountId) throw new Error("atlassianUserInfo returned no account_id");
      const out = await perKey<{ key: string; ok: boolean; error?: string }>(
        params.keys,
        async (key) => {
          await callAtlassian(ctx, signal, "editJiraIssue", {
            cloudId,
            issueIdOrKey: key,
            fields: { assignee: { accountId } },
          });
          return { ok: true };
        },
        (key, error) => ({ key, ok: false, error }),
      );
      return dataResult(out);
    },
  });

  pi.registerTool({
    name: "jira_comment",
    label: "Jira: comment",
    description: "Add a markdown comment to Jira issues (project SAND only). Returns [{key, ok, error}].",
    exposure: "deferred",
    namespace: NAMESPACE,
    parameters: Type.Object({ keys: WriteKeys, body: Type.String({ description: "Markdown comment" }) }),
    outputSchema: PerKeyOutcome,
    async execute(_id, params, signal, _onUpdate, ctx) {
      assertKeys(params.keys, true);
      if (!params.body.trim()) throw new Error("Empty comment body.");
      const cfg = await loadJiraConfig(ctx.cwd);
      const cloudId = await resolveCloudId(ctx, signal, cfg);
      const out = await perKey<{ key: string; ok: boolean; error?: string }>(
        params.keys,
        async (key) => {
          await callAtlassian(ctx, signal, "addCommentToJiraIssue", {
            cloudId,
            issueIdOrKey: key,
            commentBody: params.body,
            contentFormat: "markdown",
          });
          return { ok: true };
        },
        (key, error) => ({ key, ok: false, error }),
      );
      return dataResult(out);
    },
  });

  pi.registerTool({
    name: "jira_create",
    label: "Jira: create",
    description:
      "Create a Jira issue (project SAND only) from a local task and link it back with ot. Returns {key, url}.",
    exposure: "deferred",
    namespace: NAMESPACE,
    parameters: Type.Object({
      taskId: Type.String({ description: "Local task :CUSTOM_ID:" }),
      project: Type.String({ description: "Jira project key" }),
      type: Type.Optional(Type.String({ description: "Issue type; default Task" })),
    }),
    outputSchema: Type.Object({ key: Type.String(), url: Type.Optional(Type.String()) }),
    async execute(_id, params, signal, _onUpdate, ctx) {
      assertSandboxProject(params.project);
      const type = params.type ?? "Task";
      const cfg = await loadJiraConfig(ctx.cwd);
      const { task } = await runOtResult<{ task: OtTask }>(["show", params.taskId], {
        cwd: ctx.cwd,
      });
      const cloudId = await resolveCloudId(ctx, signal, cfg);
      const meta = await callAtlassian(ctx, signal, "getJiraProjectIssueTypesMetadata", {
        cloudId,
        projectIdOrKey: params.project,
      });
      const names: string[] = (meta?.issueTypes ?? []).map((t: { name: string }) => t.name);
      if (!names.includes(type)) {
        throw new Error(
          `Issue type ${type} is not in project ${params.project}. Available: ${names.join(", ")}`,
        );
      }
      const created = await callAtlassian(ctx, signal, "createJiraIssue", {
        cloudId,
        projectKey: params.project,
        issueTypeName: type,
        summary: task.summary,
        description: task.description ?? "",
        contentFormat: "markdown",
      });
      const key: string | undefined = created?.key;
      if (!key) {
        throw new Error(`createJiraIssue returned no key: ${JSON.stringify(created).slice(0, 300)}`);
      }
      try {
        await runOtResult(["issue", "add", params.taskId, `[[jira:${key}]]`], { cwd: ctx.cwd });
      } catch (e) {
        throw new Error(
          `Created ${key} but could not link it to task ${params.taskId}: ${errorText(e)}. Add [[jira:${key}]] to :LINKED_ISSUES: by hand.`,
        );
      }
      return dataResult({ key, ...(cfg.baseUrl ? { url: `${cfg.baseUrl}/browse/${key}` } : {}) });
    },
  });

  pi.registerTool({
    name: "jira_transition",
    label: "Jira: transition",
    description:
      "Move Jira issues (project SAND only) to the Jira status that mirrors a local STARTED or DONE. Returns [{key, ok, transition}] or {key, choices} when no transition name matches (nothing is done for that key).",
    exposure: "deferred",
    namespace: NAMESPACE,
    parameters: Type.Object({
      keys: WriteKeys,
      status: Type.Union([Type.Literal("STARTED"), Type.Literal("DONE")]),
    }),
    outputSchema: TransitionOutcome,
    async execute(_id, params, signal, _onUpdate, ctx) {
      assertKeys(params.keys, true);
      const status = params.status as TransitionStatus;
      const cfg = await loadJiraConfig(ctx.cwd);
      const cloudId = await resolveCloudId(ctx, signal, cfg);
      const out = await perKey<{ key: string; ok?: boolean; transition?: string; choices?: Array<{ id: string; name: string }>; error?: string }>(
        params.keys,
        async (key) => {
          const t = await callAtlassian(ctx, signal, "getTransitionsForJiraIssue", {
            cloudId,
            issueIdOrKey: key,
          });
          const transitions: Array<{ id: string; name: string }> = (t?.transitions ?? []).map(
            (x: { id: string; name: string }) => ({ id: x.id, name: x.name }),
          );
          const hit = pickTransition(status, transitions);
          if (!hit) return { choices: transitions };
          await callAtlassian(ctx, signal, "transitionJiraIssue", {
            cloudId,
            issueIdOrKey: key,
            transition: { id: hit.id },
          });
          return { ok: true, transition: hit.name };
        },
        (key, error) => ({ key, ok: false, error }),
      );
      return dataResult(out);
    },
  });
}

#!/usr/bin/env tsx
/**
 * Unit tests for the jira extension's pure helpers.
 *
 * Covers the pure helpers, the hidden-trigger shape, and the deferred
 * (codemode-callable) `jira_*` tools run against a fake `ctx.executeTool`. No test calls Jira;
 * live smoke tests use the SAND sandbox project (manual validation).
 *
 * Run: `tsx jira.test.ts` (or via `./test.sh`).
 */

import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import registerJira from "./index.ts";
import {
  assertKeys,
  assertSandboxProject,
  buildClaimTrigger,
  buildCloneTrigger,
  buildCommentTrigger,
  buildCreateTrigger,
  buildGetTrigger,
  buildTransitionTrigger,
  deriveJiraBaseUrl,
  getFileKeyword,
  jiraKeysFromTokens,
  normalizeIssue,
  parseCreateArgs,
  pickTransition,
  renderIssueBlock,
  resolveJiraConfig,
  resolveKey,
} from "./utils.ts";

let passed = 0;
let failed = 0;

function assertEqual<T>(actual: T, expected: T, message: string): void {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a === e) {
    passed++;
    console.log(`ok - ${message}`);
  } else {
    failed++;
    console.log(`not ok - ${message}`);
    console.log(`  expected: ${e}`);
    console.log(`  actual:   ${a}`);
  }
}

// ── resolveKey: well-formed PROJ-NNN passes through ──────────────────

assertEqual(
  resolveKey("MBFW-123", "SAND"),
  { key: "MBFW-123" },
  "resolveKey: PROJ-NNN passes through unchanged",
);

assertEqual(
  resolveKey("SAND-42", null),
  { key: "SAND-42" },
  "resolveKey: PROJ-NNN works without project keyword",
);

// ── resolveKey: bare number with project ──────────────────────────────

assertEqual(
  resolveKey("42", "SAND"),
  { key: "SAND-42" },
  "resolveKey: bare number prepends #+JIRA_PROJECT",
);

// ── resolveKey: bare number without project ───────────────────────────

{
  const r = resolveKey("42", null);
  if ("error" in r) {
    passed++;
    console.log("ok - resolveKey: bare number without project returns error");
  } else {
    failed++;
    console.log("not ok - resolveKey: bare number without project should error");
  }
}

// ── resolveKey: malformed input ───────────────────────────────────────

{
  const r = resolveKey("not-a-key", "SAND");
  if ("error" in r) {
    passed++;
    console.log("ok - resolveKey: malformed input returns error");
  } else {
    failed++;
    console.log("not ok - resolveKey: malformed input should error");
  }
}

// ── getFileKeyword: matches helper behaviour from tasks/parser.ts ─────

{
  const content = [
    "#+JIRA_CLOUDID: abc-123",
    "#+JIRA_PROJECT:",
    "#+JIRA_FOO: after-empty",
    "",
  ].join("\n");

  assertEqual(
    getFileKeyword(content, "JIRA_CLOUDID"),
    "abc-123",
    "getFileKeyword: returns value",
  );
  assertEqual(
    getFileKeyword(content, "JIRA_PROJECT"),
    "",
    "getFileKeyword: empty value yields empty string (not next-line)",
  );
  assertEqual(
    getFileKeyword(content, "JIRA_FOO"),
    "after-empty",
    "getFileKeyword: line after empty keyword still resolvable",
  );
  assertEqual(
    getFileKeyword(content, "ABSENT"),
    null,
    "getFileKeyword: returns null for absent keyword",
  );
  assertEqual(
    getFileKeyword(content, "jira_cloudid"),
    "abc-123",
    "getFileKeyword: case-insensitive on name",
  );
}

// ── Jira config resolution ────────────────────────────────────────────

assertEqual(
  deriveJiraBaseUrl("https://example.atlassian.net/browse/%s"),
  "https://example.atlassian.net",
  "deriveJiraBaseUrl: strips /browse/%s suffix",
);

assertEqual(
  deriveJiraBaseUrl("https://example.atlassian.net/jira/software/c/projects/SAND/issues/%s"),
  null,
  "deriveJiraBaseUrl: rejects non-browse templates",
);

assertEqual(
  resolveJiraConfig([
    "#+LINK: jira https://setup.atlassian.net/browse/%s",
    "#+JIRA_CLOUDID: setup-cloud",
    "#+JIRA_PROJECT: SETUP",
  ].join("\n")),
  { cloudId: "setup-cloud", project: "SETUP", baseUrl: "https://setup.atlassian.net" },
  "resolveJiraConfig: reads defaults from TASKS.setup.org content",
);

assertEqual(
  resolveJiraConfig([
    "#+JIRA_PROJECT:",
    "#+LINK: jira https://first.atlassian.net/browse/%s",
    "#+JIRA_CLOUDID: first-cloud",
    "#+JIRA_PROJECT: FIRST",
    "#+LINK: jira https://later.atlassian.net/browse/%s",
    "#+JIRA_CLOUDID: later-cloud",
    "#+JIRA_PROJECT: LATER",
  ].join("\n")),
  { cloudId: "first-cloud", project: "FIRST", baseUrl: "https://first.atlassian.net" },
  "resolveJiraConfig: selects the first non-empty declaration in effective Org order",
);


// ── parseCreateArgs ────────────────────────────────────────────────────

assertEqual(
  parseCreateArgs([]),
  { project: null, type: "Task" },
  "parseCreateArgs: empty args defaults to null project + Task type",
);
assertEqual(
  parseCreateArgs(["SAND"]),
  { project: "SAND", type: "Task" },
  "parseCreateArgs: positional project",
);
assertEqual(
  parseCreateArgs(["--type", "Story"]),
  { project: null, type: "Story" },
  "parseCreateArgs: --type with separate value",
);
assertEqual(
  parseCreateArgs(["SAND", "--type=Bug"]),
  { project: "SAND", type: "Bug" },
  "parseCreateArgs: --type=value form",
);
assertEqual(
  parseCreateArgs(["SAND", "--type", "Epic"]),
  { project: "SAND", type: "Epic" },
  "parseCreateArgs: project + --type combo",
);

// ── Fixtures ──────────────────────────────────────────────────────────────

function check(cond: boolean, message: string): void {
  if (cond) {
    passed++;
    console.log(`ok - ${message}`);
  } else {
    failed++;
    console.log(`not ok - ${message}`);
  }
}

const OT = new URL("../../../skills/org-tasks/scripts/ot", import.meta.url).pathname;
const BASE = "https://x.atlassian.net";

function ot(root: string, ...args: string[]): any {
  const r = spawnSync(OT, ["--format", "json", "--root", root, ...args], { encoding: "utf-8" });
  if (r.status !== 0) throw new Error(`ot ${args.join(" ")} failed: ${r.stdout}${r.stderr}`);
  return JSON.parse(r.stdout).result;
}

/** A throwaway org-tasks project; `ot` builds it so the fixture matches the protocol. */
function makeProject(opts: { cloudId?: string; link?: boolean; project?: boolean } = {}): string {
  const dir = mkdtempSync(join(tmpdir(), "jira-test-"));
  ot(dir, "init");
  const lines = opts.project === false ? [] : [`#+JIRA_PROJECT: SAND`];
  if (opts.link !== false) lines.push(`#+LINK: jira ${BASE}/browse/%s`);
  if (opts.cloudId) lines.push(`#+JIRA_CLOUDID: ${opts.cloudId}`);
  writeFileSync(join(dir, "TASKS.setup.org"), `${lines.join("\n")}\n`);
  return dir;
}

function addTask(dir: string, summary: string, ...linked: string[]): string {
  const args = ["create", summary, "--section", "Improvements", "--body", `Body of ${summary}`];
  for (const l of linked) args.push("--linked-issue", l);
  return ot(dir, ...args).id;
}

function okOutcome(data: unknown) {
  const t = typeof data === "string" ? data : JSON.stringify(data);
  const blocks = [{ type: "text", text: t }];
  return {
    toolCall: { id: "t/1" },
    isError: false,
    result: { content: blocks, structuredContent: { content: blocks } },
  };
}

function errOutcome(message: string, statusCode = 404) {
  const blocks = [{ type: "text", text: message }];
  return {
    toolCall: { id: "t/1" },
    isError: true,
    result: {
      content: blocks,
      isError: true,
      structuredContent: { content: blocks, isError: true, statusCode },
    },
  };
}

type Handlers = Record<string, (args: any) => unknown>;

function makeCtx(cwd: string, handlers: Handlers) {
  const calls: Array<{ name: string; args: any }> = [];
  const ctx = {
    cwd,
    async executeTool(name: string, args: any) {
      calls.push({ name, args });
      const h = handlers[name];
      if (!h) return errOutcome(`unexpected tool ${name}`, 500);
      const v = h(args) as any;
      return v && typeof v === "object" && "toolCall" in v ? v : okOutcome(v);
    },
  };
  return { ctx, calls };
}

const P = "mcp__atlassian__";
const issueJson = (key: string, extra: Record<string, unknown> = {}) => ({
  expand: "renderedFields",
  id: "10001",
  self: "https://api.atlassian.com/secret-self-link",
  key,
  fields: {
    summary: `Summary of ${key}`,
    status: { name: "In Progress", id: "3" },
    priority: { name: "High", id: "2" },
    issuetype: { name: "Task" },
    labels: ["alpha", "beta"],
    description: `Description of ${key}.\n\nSecond paragraph.`,
    reporter: { displayName: "Rita Reporter", accountId: "secret-account-id" },
    assignee: { displayName: "Alex Assignee", accountId: "secret-account-id" },
    updated: "2026-02-17T11:54:56.770+0000",
    versions: [{ name: "1.0", id: "1" }],
    fixVersions: [{ name: "1.1", id: "2" }, { name: "2.0", id: "3" }],
    components: [{ name: "Firmware", id: "4" }],
    issuelinks: [
      { type: { name: "Relates", inward: "relates to", outward: "relates to" }, outwardIssue: { key: "SAND-9", fields: { summary: "Linked out" } } },
      { type: { name: "Blocks", inward: "is blocked by", outward: "blocks" }, inwardIssue: { key: "SAND-8", fields: { summary: "Linked in" } } },
    ],
    comment: { comments: [{ body: "secret comment body" }], total: 3 },
    customfield_10000: "leaky custom field",
    ...extra,
  },
});

interface Loaded {
  tools: Map<string, any>;
  command: any;
  events: Map<string, (payload: any) => Promise<void>>;
  sent: Array<{ m: any; o: any }>;
  userSent: unknown[];
  notes: Array<{ msg: string; level: string }>;
  run(args: string, cwd: string): Promise<void>;
}

function load(connected = true): Loaded {
  const tools = new Map<string, any>();
  const events = new Map<string, (payload: any) => Promise<void>>();
  const sent: Loaded["sent"] = [];
  const userSent: unknown[] = [];
  const notes: Loaded["notes"] = [];
  const holder: { command?: any } = {};
  registerJira({
    registerTool(t: any) { tools.set(t.name, t); },
    registerCommand(_n: string, c: any) { holder.command = c; },
    on() {},
    getAllTools() { return connected ? [{ name: `${P}getJiraIssue` }] : []; },
    sendMessage(m: any, o: any) { sent.push({ m, o }); },
    sendUserMessage(c: unknown) { userSent.push(c); },
    events: {
      on(topic: string, fn: (payload: any) => Promise<void>) {
        events.set(topic, fn);
        return () => {};
      },
    },
  } as any);
  return {
    tools,
    command: holder.command,
    events,
    sent,
    userSent,
    notes,
    async run(args: string, cwd: string) {
      await holder.command.handler(args, {
        cwd,
        ui: { notify: (msg: string, level: string) => notes.push({ msg, level }) },
      });
    },
  };
}

async function runTool(
  loaded: Loaded,
  name: string,
  params: unknown,
  cwd: string,
  handlers: Handlers,
) {
  const { ctx, calls } = makeCtx(cwd, handlers);
  const tool = loaded.tools.get(name);
  try {
    const result = await tool.execute("id", params, undefined, undefined, ctx);
    return { result, calls, error: null as string | null };
  } catch (e) {
    return { result: null as any, calls, error: e instanceof Error ? e.message : String(e) };
  }
}

// ── assertKeys / assertSandboxProject ─────────────────────────────────

function throws(fn: () => void): string | null {
  try {
    fn();
    return null;
  } catch (e) {
    return (e as Error).message;
  }
}

check(throws(() => assertKeys(["SAND-1", "MBFW-2"], false)) === null, "assertKeys: non-sandbox accepts any well-formed project");
check((throws(() => assertKeys(["SAND-1", "MBFW-2"], true)) ?? "").includes("MBFW-2"), "assertKeys: sandbox refuses a key outside SAND and names it");
check((throws(() => assertKeys(["SAND-1", "nope"], false)) ?? "").includes("nope"), "assertKeys: refuses a malformed key");
check((throws(() => assertKeys([], false)) ?? "").length > 0, "assertKeys: refuses an empty list");
check(throws(() => assertKeys(["SANDBOX-1"], true)) !== null, "assertKeys: sandbox matches the project exactly, not by prefix");
check(throws(() => assertSandboxProject("SAND")) === null, "assertSandboxProject: accepts SAND");
check(throws(() => assertSandboxProject("MBFW")) !== null, "assertSandboxProject: refuses another project");

// ── jiraKeysFromTokens ─────────────────────────────────────────────────

assertEqual(
  jiraKeysFromTokens(
    [
      "[[jira:SAND-5]]",
      "[[https://x.atlassian.net/browse/SAND-6][six]]",
      "[[https://x.atlassian.net/browse/SAND-7]]",
      "[[https://github.com/a/b/issues/4][gh]]",
      "[[https://other.atlassian.net/browse/SAND-8]]",
      "[[jira:not-a-key]]",
      "SAND-9",
      "[[jira:SAND-5]]",
    ],
    BASE,
  ),
  ["SAND-5", "SAND-6", "SAND-7"],
  "jiraKeysFromTokens: typed and same-host links only, deduplicated, in order",
);
assertEqual(
  jiraKeysFromTokens(["[[jira:SAND-5]]", "[[https://x.atlassian.net/browse/SAND-6]]"], null),
  ["SAND-5"],
  "jiraKeysFromTokens: raw links are skipped without a #+LINK: jira base URL",
);

// ── pickTransition ─────────────────────────────────────────────────────

{
  const ts = [{ id: "1", name: "In Progress" }, { id: "2", name: "Start Progress" }, { id: "3", name: "Done" }];
  assertEqual(pickTransition("STARTED", ts)?.id, "2", "pickTransition: STARTED prefers Start Progress over In Progress");
  assertEqual(pickTransition("STARTED", [ts[0]!])?.id, "1", "pickTransition: STARTED falls back to In Progress");
  assertEqual(pickTransition("DONE", [{ id: "9", name: "RESOLVED" }])?.id, "9", "pickTransition: DONE matches case-insensitively");
  assertEqual(pickTransition("DONE", [ts[0]!]), null, "pickTransition: no matching name returns null");
  assertEqual(pickTransition("STARTED", [{ id: "4", name: "Done" }]), null, "pickTransition: STARTED does not match the DONE names");
}

// ── normalizeIssue + renderIssueBlock ──────────────────────────────────

{
  const n = normalizeIssue("SAND-1", issueJson("SAND-1", {
    parent: { key: "SAND-0", fields: { summary: "Parent summary" } },
    subtasks: [1, 2, 3, 4, 5, 6, 7].map((i) => ({ key: `SAND-1${i}`, fields: { summary: `Sub ${i}` } })),
  }));
  assertEqual(Object.keys(n).sort(), ["affectsVersions", "assignee", "commentCount", "components", "description", "fixVersions", "issueLinks", "issuetype", "key", "labels", "parent", "priority", "reporter", "status", "subtasks", "summary", "updated"], "normalizeIssue: keeps only the flow fields");
  const block = renderIssueBlock(n, BASE);
  const lines = block.split("\n");
  assertEqual(lines[0], "SAND-1 - Summary of SAND-1 [In Progress]", "renderIssueBlock: heading carries key, summary and status");
  assertEqual(lines[1], "Priority: High; type: Task; labels: alpha, beta", "renderIssueBlock: metadata line");
  check(lines.includes("Parent: SAND-0 - Parent summary"), "renderIssueBlock: parent line");
  check(lines.includes("Subtasks (7):") && lines.filter((l) => l.startsWith("- SAND-1")).length === 5 && lines.includes("- +2 more"), "renderIssueBlock: subtasks capped at 5 with a +N more footnote");
  check(lines.includes("Description: Description of SAND-1.") && !block.includes("Second paragraph"), "renderIssueBlock: description preview is the first paragraph only");
  assertEqual(lines[lines.length - 1], `${BASE}/browse/SAND-1`, "renderIssueBlock: footer link from the base URL");
  check(lines.includes("Components: Firmware"), "renderIssueBlock: components line");
  check(lines.includes("Affects versions: 1.0"), "renderIssueBlock: affects versions line");
  check(lines.includes("Fix versions: 1.1, 2.0"), "renderIssueBlock: fix versions line");
  check(lines.includes("Assignee: Alex Assignee"), "renderIssueBlock: assignee line");
  check(lines.includes("Reporter: Rita Reporter"), "renderIssueBlock: reporter line");
  check(lines.includes("Updated: 2026-02-17"), "renderIssueBlock: updated line carries the date only");
  check(lines.includes("Issue links (2):") && lines.includes("- relates to SAND-9 - Linked out") && lines.includes("- is blocked by SAND-8 - Linked in"), "renderIssueBlock: issue links with the outward or inward relation");
  check(lines.includes("Comments: 3"), "renderIssueBlock: comment count from comment.total");
  check(!block.includes("leaky custom") && !block.includes("secret-self-link") && !block.includes("secret-account-id") && !block.includes("secret comment body"), "renderIssueBlock: no unfiltered field reaches the block");
}
{
  const long = "x".repeat(450);
  const block = renderIssueBlock(normalizeIssue("SAND-2", issueJson("SAND-2", { description: long, labels: [] })), null);
  check(block.includes(`Description: ${"x".repeat(300)}...`) && !block.includes("x".repeat(301)), "renderIssueBlock: preview is capped at 300 characters with an ellipsis");
  check(block.includes("labels: none") && !block.includes("/browse/"), "renderIssueBlock: none labels, no footer without a base URL");
}
{
  const empty = normalizeIssue("SAND-6", issueJson("SAND-6", {
    assignee: null, reporter: null, updated: null, versions: [], fixVersions: [], components: [],
    issuelinks: [], comment: { comments: [], total: 0 }, description: null, labels: [],
  }));
  const lines = renderIssueBlock(empty, null).split("\n");
  // Every field always has a line, so an agent reads the block as complete (a
  // missing line was read as "not fetched" and triggered raw re-fetches).
  assertEqual(lines, [
    "SAND-6 - Summary of SAND-6 [In Progress]",
    "Priority: High; type: Task; labels: none",
    "Components: none",
    "Affects versions: none",
    "Fix versions: none",
    "Assignee: none",
    "Reporter: none",
    "Updated: none",
    "Parent: none",
    "Subtasks: none",
    "Issue links: none",
    "Comments: 0",
    "Description: none",
  ], "renderIssueBlock: an issue with empty optional fields shows every field, empty ones as none");
  const noComment = normalizeIssue("SAND-7", issueJson("SAND-7", { comment: undefined }));
  check(renderIssueBlock(noComment, null).split("\n").includes("Comments: none"), "renderIssueBlock: a missing comment field is shown as none");
}
{
  // getJiraIssue can return an ADF document instead of markdown even with
  // responseContentFormat "markdown": observed live for an empty SAND-75 description.
  const emptyAdf = { type: "doc", version: 1, content: [] };
  const n = normalizeIssue("SAND-3", issueJson("SAND-3", { description: emptyAdf }));
  assertEqual(n.description, null, "normalizeIssue: an empty ADF description becomes null");
  let block = "";
  try { block = renderIssueBlock(n, BASE); } catch (e) { block = `THREW ${(e as Error).message}`; }
  check(!block.startsWith("THREW") && !block.includes("[object"), "renderIssueBlock: an empty ADF description renders without throwing");
  const adf = { type: "doc", version: 1, content: [
    { type: "paragraph", content: [{ type: "text", text: "First " }, { type: "text", text: "para." }] },
    { type: "paragraph", content: [{ type: "text", text: "Second." }] },
  ] };
  assertEqual(normalizeIssue("SAND-4", issueJson("SAND-4", { description: adf })).description, "First para.\n\nSecond.", "normalizeIssue: an ADF description becomes its text, paragraphs separated by a blank line");
  assertEqual(normalizeIssue("SAND-5", issueJson("SAND-5", { description: 42 })).description, null, "normalizeIssue: a description of another type becomes null");
}

// ── Triggers ───────────────────────────────────────────────────────────

{
  const body = 'Line "one"\nline two with `ticks`';
  const cases: Array<[string, string, string]> = [
    ["get", buildGetTrigger(["SAND-1", "SAND-2"]), 'tools.jira_get({"keys":["SAND-1","SAND-2"]})'],
    ["clone", buildCloneTrigger(["SAND-1"]), 'tools.jira_clone({"keys":["SAND-1"]})'],
    ["claim", buildClaimTrigger(["SAND-1"]), 'tools.jira_claim({"keys":["SAND-1"]})'],
    ["comment", buildCommentTrigger(["SAND-1"], body), `tools.jira_comment(${JSON.stringify({ keys: ["SAND-1"], body })})`],
    ["create", buildCreateTrigger("abc-123", "SAND", "Story"), 'tools.jira_create({"taskId":"abc-123","project":"SAND","type":"Story"})'],
    ["transition", buildTransitionTrigger(["SAND-1"], "DONE"), 'tools.jira_transition({"keys":["SAND-1"],"status":"DONE"})'],
  ];
  for (const [name, trig, call] of cases) {
    check(!trig.includes("\n"), `trigger ${name}: one line`);
    check(trig.startsWith("Call `codemode` once with exactly this code") && trig.includes(`return await ${call};`), `trigger ${name}: names one codemode call with literal args`);
    check(!trig.includes("mcp__atlassian__") && !trig.includes("```") && !trig.includes("const ") && !trig.includes("org-jira"), `trigger ${name}: no MCP names, script body or protocol steps`);
  }
  check(cases[3]![1].length < 400 && cases[0]![1].length < 250, "trigger sizes stay small");
  check(buildTransitionTrigger(["SAND-1"], "DONE").includes("`choices`"), "trigger transition: tells the agent what to do with choices");
}

// ── Tool and command tests (async) ─────────────────────────────────────

const cleanup: string[] = [];

async function main(): Promise<void> {
  // ── Registration ────────────────────────────────────────────────────
  {
    const loaded = load();
    const names = [...loaded.tools.keys()].sort();
    assertEqual(names, ["jira_claim", "jira_clone", "jira_comment", "jira_create", "jira_get", "jira_transition"], "registration: the six jira tools and no jira_clone_apply");
    check([...loaded.tools.values()].every((t) => t.exposure === "deferred"), "registration: every jira tool is deferred (callable from codemode, not listed or declared)");
    check([...loaded.tools.values()].every((t) => t.namespace?.name === "jira"), "registration: every jira tool is in the jira namespace");
    check([...loaded.tools.values()].every((t) => !t.promptSnippet && !t.promptGuidelines), "registration: no jira tool adds a prompt snippet or guideline");
    check(loaded.tools.get("jira_get").outputSchema === undefined && ["jira_clone", "jira_claim", "jira_comment", "jira_create", "jira_transition"].every((n) => loaded.tools.get(n).outputSchema), "registration: data tools declare an outputSchema; jira_get returns text");
  }

  // ── jira_get ────────────────────────────────────────────────────────
  {
    const dir = makeProject({ cloudId: "cloud-1" });
    cleanup.push(dir);
    const loaded = load();
    const tasksBefore = readFileSync(join(dir, "TASKS.org"), "utf-8");
    const r = await runTool(loaded, "jira_get", { keys: ["SAND-1", "SAND-2"] }, dir, {
      [`${P}getJiraIssue`]: (a) => issueJson(a.issueIdOrKey),
    });
    check(r.error === null, "jira_get: succeeds");
    assertEqual(r.calls.map((c) => c.name), [`${P}getJiraIssue`, `${P}getJiraIssue`], "jira_get: one getJiraIssue per key and no site lookup when #+JIRA_CLOUDID is set");
    assertEqual(r.calls[0]!.args, { cloudId: "cloud-1", issueIdOrKey: "SAND-1", fields: ["summary", "priority", "labels", "description", "issuetype", "parent", "subtasks", "status", "assignee", "reporter", "issuelinks", "updated", "versions", "fixVersions", "components", "comment"], responseContentFormat: "markdown" }, "jira_get: sends cloudId, key, the field filter and markdown format");
    const out: string = r.result.content[0].text;
    check(out.startsWith("SAND-1 - Summary of SAND-1 [In Progress]") && out.includes("\n\nSAND-2 - Summary of SAND-2"), "jira_get: rendered blocks separated by a blank line");
    check(!out.includes('"fields"') && !out.includes("secret-account-id") && !out.includes("secret-self-link") && !out.includes("leaky custom") && !out.includes("secret comment body"), "jira_get: no raw issue JSON or unfiltered field in the result");
    check(out.includes(`${BASE}/browse/SAND-1`), "jira_get: footer link from #+LINK: jira");
    check(readFileSync(join(dir, "TASKS.org"), "utf-8") === tasksBefore, "jira_get: writes no task file");
  }
  {
    const dir = makeProject({ cloudId: "cloud-1" });
    cleanup.push(dir);
    const loaded = load();
    const text = JSON.stringify(issueJson("SAND-3"));
    const r = await runTool(loaded, "jira_get", { keys: ["SAND-3"] }, dir, {
      [`${P}getJiraIssue`]: () => ({ toolCall: { id: "t" }, isError: false, result: { content: [{ type: "text", text }] } }),
    });
    check(r.error === null && r.result.content[0].text.startsWith("SAND-3 - "), "jira_get: parses the content text when structuredContent is absent");
  }
  {
    const dir = makeProject({ cloudId: "cloud-1" });
    cleanup.push(dir);
    const loaded = load();
    const r = await runTool(loaded, "jira_get", { keys: ["SAND-404"] }, dir, {
      [`${P}getJiraIssue`]: () => errOutcome("Issue does not exist or you do not have permission to see it."),
    });
    check(r.error !== null && r.error.includes("Issue does not exist") && r.error.includes("/mcp login atlassian"), "jira_get: an isError outcome throws with the Jira message and the login hint");
    const failSecond = await runTool(loaded, "jira_get", { keys: ["SAND-1", "SAND-404"] }, dir, {
      [`${P}getJiraIssue`]: (a) => (a.issueIdOrKey === "SAND-1" ? issueJson("SAND-1") : errOutcome("nope")),
    });
    check(failSecond.error !== null && failSecond.result === null, "jira_get: a failing key throws instead of returning a partial block");
    const bad = await runTool(loaded, "jira_get", { keys: ["bogus"] }, dir, {});
    check(bad.error !== null && bad.error.includes("bogus") && bad.calls.length === 0, "jira_get: an invalid key throws before any MCP call");
  }
  {
    // cloudId fallback
    const dir = makeProject();
    cleanup.push(dir);
    const loaded = load();
    const sites = [
      { id: "wrong", url: "https://other.atlassian.net" },
      { id: "right", url: BASE },
      { id: "right", url: BASE },
    ];
    const r = await runTool(loaded, "jira_get", { keys: ["SAND-1", "SAND-2"] }, dir, {
      [`${P}getAccessibleAtlassianResources`]: () => sites,
      [`${P}getJiraIssue`]: (a) => issueJson(a.issueIdOrKey),
    });
    check(r.error === null && r.calls.filter((c) => c.name === `${P}getAccessibleAtlassianResources`).length === 1, "jira_get: resolves the cloudId once per call through getAccessibleAtlassianResources");
    assertEqual(r.calls[1]!.args.cloudId, "right", "jira_get: cloudId fallback matches the site url to the #+LINK: jira base URL");
    const none = await runTool(loaded, "jira_get", { keys: ["SAND-1"] }, dir, {
      [`${P}getAccessibleAtlassianResources`]: () => [{ id: "wrong", url: "https://other.atlassian.net" }],
    });
    check(none.error !== null && none.error.includes(BASE), "jira_get: no site with the base URL throws");

    const noLink = makeProject({ link: false });
    cleanup.push(noLink);
    const dedupe = await runTool(loaded, "jira_get", { keys: ["SAND-1"] }, noLink, {
      [`${P}getAccessibleAtlassianResources`]: () => [{ id: "only", url: "a" }, { id: "only", url: "a" }],
      [`${P}getJiraIssue`]: (a) => issueJson(a.issueIdOrKey),
    });
    check(dedupe.error === null && dedupe.calls[1]!.args.cloudId === "only", "jira_get: without a base URL a site listed twice resolves to its single id");
    const ambiguous = await runTool(loaded, "jira_get", { keys: ["SAND-1"] }, noLink, {
      [`${P}getAccessibleAtlassianResources`]: () => [{ id: "a", url: "a" }, { id: "b", url: "b" }],
    });
    check(ambiguous.error !== null && ambiguous.error.includes("#+JIRA_CLOUDID"), "jira_get: without a base URL several sites throw and ask for #+JIRA_CLOUDID");
  }

  // ── jira_clone ──────────────────────────────────────────────────────
  {
    const dir = makeProject({ cloudId: "cloud-1" });
    cleanup.push(dir);
    const loaded = load();
    const handlers: Handlers = { [`${P}getJiraIssue`]: (a) => issueJson(a.issueIdOrKey) };
    const r = await runTool(loaded, "jira_clone", { keys: ["SAND-1", "SAND-2"] }, dir, handlers);
    check(r.error === null, "jira_clone: succeeds");
    const data = r.result.structuredContent;
    assertEqual(data.map((d: any) => [d.key, d.status]), [["SAND-1", "inserted"], ["SAND-2", "inserted"]], "jira_clone: returns the inserted status per key");
    const org = readFileSync(join(dir, "TASKS.org"), "utf-8");
    check(org.includes("** TODO [#B] Summary of SAND-1") && org.includes(":alpha:beta:") && org.includes(":LINKED_ISSUES: [[jira:SAND-1]]") && org.includes("Description of SAND-1.\n\nSecond paragraph."), "jira_clone: inserts heading, priority cookie, tags, linked issue and markdown body through ot");
    const model: string = r.result.content[0].text;
    check(!model.includes("Summary of") && !model.includes("Description of") && !model.includes("alpha"), "jira_clone: issue summary, body and labels never appear in the model-facing result");
    assertEqual(r.calls.map((c) => c.name), [`${P}getJiraIssue`, `${P}getJiraIssue`], "jira_clone: fetches before inserting and calls only getJiraIssue");

    const dup = await runTool(loaded, "jira_clone", { keys: ["SAND-1"] }, dir, handlers);
    assertEqual([dup.result.structuredContent[0].status, dup.result.structuredContent[0].existingId], ["duplicate", data[0].id], "jira_clone: a second clone reports duplicate with the existing task id");

    const missing = await runTool(loaded, "jira_clone", { keys: ["SAND-3"], section: "Nowhere" }, dir, handlers);
    assertEqual([missing.result.structuredContent[0].status, missing.result.structuredContent[0].section], ["section_not_found", "Nowhere"], "jira_clone: a missing section reports section_not_found");
    const created = await runTool(loaded, "jira_clone", { keys: ["SAND-3"], section: "Nowhere", allowCreateSection: true }, dir, handlers);
    assertEqual(created.result.structuredContent[0].status, "inserted", "jira_clone: allowCreateSection inserts into a new section");

    const empty = await runTool(loaded, "jira_clone", { keys: ["SAND-4"] }, dir, { [`${P}getJiraIssue`]: () => issueJson("SAND-4", { summary: "" }) });
    check(empty.error === null && empty.result.structuredContent[0].status === "error" && String(empty.result.structuredContent[0].message).includes("summary"), "jira_clone: an insert error reports status error with the message");

    const before = readFileSync(join(dir, "TASKS.org"), "utf-8");
    const partial = await runTool(loaded, "jira_clone", { keys: ["SAND-5", "SAND-6"] }, dir, {
      [`${P}getJiraIssue`]: (a) => (a.issueIdOrKey === "SAND-5" ? issueJson("SAND-5") : errOutcome("No such issue")),
    });
    check(partial.error !== null && partial.error.includes("No such issue") && partial.error.includes("/mcp login atlassian"), "jira_clone: a failed fetch throws with the Jira message and the login hint");
    check(readFileSync(join(dir, "TASKS.org"), "utf-8") === before, "jira_clone: a failed fetch inserts nothing, not even the earlier keys");

    const bad = await runTool(loaded, "jira_clone", { keys: ["bad"] }, dir, handlers);
    check(bad.error !== null && bad.calls.length === 0, "jira_clone: an invalid key throws before any MCP call");

    // Raw Jira text must not break `ot create`: hyphenated labels (invalid org
    // tags), a summary or body that starts with `-` (read as options), and
    // `* ` bullets (org headings at column 0). Found in the closeout reviews.
    const raw = await runTool(loaded, "jira_clone", { keys: ["SAND-7"] }, dir, {
      [`${P}getJiraIssue`]: () => issueJson("SAND-7", {
        summary: "- dash summary",
        labels: ["tech-debt", "tech_debt", "a.b", "--x", ""],
        description: "- one\n- two\n\n* three\n** four",
      }),
    });
    assertEqual(raw.result?.structuredContent?.[0]?.status, "inserted", "jira_clone: labels, summary and body from Jira that ot would reject still insert");
    const rawOrg = readFileSync(join(dir, "TASKS.org"), "utf-8");
    const rawLines = rawOrg.split("\n");
    check(rawLines.includes("** TODO [#B] - dash summary :tech_debt:a_b:__x:"), "jira_clone: labels become org tags with other characters as _, empties and duplicates dropped; a leading dash in the summary is kept");
    check(rawOrg.includes("- one\n- two"), "jira_clone: a body that starts with a dash is inserted verbatim");
    check(rawLines.includes(" * three") && rawLines.includes(" ** four") && !rawLines.includes("* three") && !rawLines.includes("** four"), "jira_clone: body bullets are indented so they are not org headings");
  }

  // ── Write tools: SAND guard ─────────────────────────────────────────
  {
    const dir = makeProject({ cloudId: "cloud-1" });
    cleanup.push(dir);
    const loaded = load();
    const task = addTask(dir, "Guard task", "[[jira:SAND-1]]");
    const cases: Array<[string, unknown]> = [
      ["jira_claim", { keys: ["MBFW-1"] }],
      ["jira_claim", { keys: ["SAND-1", "MBFW-1"] }],
      ["jira_comment", { keys: ["MBFW-1"], body: "hi" }],
      ["jira_transition", { keys: ["MBFW-1"], status: "DONE" }],
      ["jira_create", { taskId: task, project: "MBFW", type: "Task" }],
    ];
    for (const [name, params] of cases) {
      const r = await runTool(loaded, name, params, dir, {});
      check(r.error !== null && r.error.includes("SAND") && r.calls.length === 0, `${name}: ${JSON.stringify(params).includes("MBFW-1") && JSON.stringify(params).includes("SAND-1") ? "a mixed key list" : "a key or project outside SAND"} throws before any MCP call`);
    }
  }

  // ── jira_claim ──────────────────────────────────────────────────────
  {
    const dir = makeProject({ cloudId: "cloud-1" });
    cleanup.push(dir);
    const loaded = load();
    const r = await runTool(loaded, "jira_claim", { keys: ["SAND-1", "SAND-2"] }, dir, {
      [`${P}atlassianUserInfo`]: () => ({ account_id: "acc-9", email: "leak@example.com" }),
      [`${P}editJiraIssue`]: () => ({ ok: true }),
    });
    assertEqual(r.calls.map((c) => c.name), [`${P}atlassianUserInfo`, `${P}editJiraIssue`, `${P}editJiraIssue`], "jira_claim: user info once, then one editJiraIssue per key");
    assertEqual(r.calls[1]!.args, { cloudId: "cloud-1", issueIdOrKey: "SAND-1", fields: { assignee: { accountId: "acc-9" } } }, "jira_claim: sets assignee.accountId from account_id");
    assertEqual(r.result.structuredContent, [{ key: "SAND-1", ok: true }, { key: "SAND-2", ok: true }], "jira_claim: returns [{key, ok}] only");
    const partial = await runTool(loaded, "jira_claim", { keys: ["SAND-1", "SAND-2"] }, dir, {
      [`${P}atlassianUserInfo`]: () => ({ account_id: "acc-9" }),
      [`${P}editJiraIssue`]: (a) => (a.issueIdOrKey === "SAND-2" ? errOutcome("No permission to edit") : { ok: true }),
    });
    check(partial.result.structuredContent[0].ok === true && partial.result.structuredContent[1].ok === false && partial.result.structuredContent[1].error.includes("No permission") && partial.result.structuredContent[1].error.includes("/mcp login atlassian"), "jira_claim: one failing key is reported on that key only, with the Jira message");
    const noId = await runTool(loaded, "jira_claim", { keys: ["SAND-1"] }, dir, { [`${P}atlassianUserInfo`]: () => ({}) });
    check(noId.error !== null && noId.calls.length === 1, "jira_claim: a user without account_id throws before any edit");
    const authFail = await runTool(loaded, "jira_claim", { keys: ["SAND-1"] }, dir, { [`${P}atlassianUserInfo`]: () => errOutcome("Unauthorized", 401) });
    check(authFail.error !== null && authFail.error.includes("Unauthorized") && authFail.error.includes("/mcp login atlassian"), "jira_claim: an isError user lookup throws with the login hint");
  }

  // ── jira_comment ────────────────────────────────────────────────────
  {
    const dir = makeProject({ cloudId: "cloud-1" });
    cleanup.push(dir);
    const loaded = load();
    const body = "Looks **good**\n\n- ready";
    const r = await runTool(loaded, "jira_comment", { keys: ["SAND-1", "SAND-2"], body }, dir, {
      [`${P}addCommentToJiraIssue`]: () => ({ id: "c1" }),
    });
    assertEqual(r.calls.map((c) => c.args), [
      { cloudId: "cloud-1", issueIdOrKey: "SAND-1", commentBody: body, contentFormat: "markdown" },
      { cloudId: "cloud-1", issueIdOrKey: "SAND-2", commentBody: body, contentFormat: "markdown" },
    ], "jira_comment: one addCommentToJiraIssue per key with the markdown body");
    assertEqual(r.result.structuredContent, [{ key: "SAND-1", ok: true }, { key: "SAND-2", ok: true }], "jira_comment: returns [{key, ok}] only");
    const partial = await runTool(loaded, "jira_comment", { keys: ["SAND-1"], body }, dir, {
      [`${P}addCommentToJiraIssue`]: () => errOutcome("Comment too long", 400),
    });
    check(partial.result.structuredContent[0].ok === false && partial.result.structuredContent[0].error.includes("Comment too long"), "jira_comment: a failing key is reported with the Jira message");
    const empty = await runTool(loaded, "jira_comment", { keys: ["SAND-1"], body: "  " }, dir, {});
    check(empty.error !== null && empty.calls.length === 0, "jira_comment: an empty body throws before any MCP call");
  }

  // ── jira_transition ─────────────────────────────────────────────────
  {
    const dir = makeProject({ cloudId: "cloud-1" });
    cleanup.push(dir);
    const loaded = load();
    const transitionsByKey: Record<string, unknown[]> = {
      "SAND-1": [{ id: "11", name: "To Do", leak: 1 }, { id: "21", name: "In Progress", leak: 1 }],
      "SAND-2": [{ id: "31", name: "Backlog" }],
    };
    const r = await runTool(loaded, "jira_transition", { keys: ["SAND-1", "SAND-2"], status: "STARTED" }, dir, {
      [`${P}getTransitionsForJiraIssue`]: (a) => ({ transitions: transitionsByKey[a.issueIdOrKey] }),
      [`${P}transitionJiraIssue`]: () => ({}),
    });
    assertEqual(r.result.structuredContent, [
      { key: "SAND-1", ok: true, transition: "In Progress" },
      { key: "SAND-2", choices: [{ id: "31", name: "Backlog" }] },
    ], "jira_transition: matches in TypeScript; a key with no match returns its choices only");
    const performed = r.calls.filter((c) => c.name === `${P}transitionJiraIssue`);
    assertEqual(performed.map((c) => c.args), [{ cloudId: "cloud-1", issueIdOrKey: "SAND-1", transition: { id: "21" } }], "jira_transition: performs the matched transition and nothing for the unmatched key");
    const done = await runTool(loaded, "jira_transition", { keys: ["SAND-1"], status: "DONE" }, dir, {
      [`${P}getTransitionsForJiraIssue`]: () => ({ transitions: [{ id: "41", name: "Closed" }, { id: "42", name: "Done" }] }),
      [`${P}transitionJiraIssue`]: () => ({}),
    });
    assertEqual(done.result.structuredContent, [{ key: "SAND-1", ok: true, transition: "Done" }], "jira_transition: DONE tries Done before Closed");
    const failing = await runTool(loaded, "jira_transition", { keys: ["SAND-1"], status: "DONE" }, dir, {
      [`${P}getTransitionsForJiraIssue`]: () => errOutcome("Issue does not exist"),
    });
    check(failing.result.structuredContent[0].ok === false && failing.result.structuredContent[0].error.includes("Issue does not exist"), "jira_transition: a failing lookup is reported on its key");
  }

  // ── jira_create ─────────────────────────────────────────────────────
  {
    const dir = makeProject({ cloudId: "cloud-1" });
    cleanup.push(dir);
    const loaded = load();
    const task = addTask(dir, "Promote me");
    const metaHandlers: Handlers = {
      [`${P}getJiraProjectIssueTypesMetadata`]: () => ({ issueTypes: [{ name: "Task" }, { name: "Story" }] }),
      [`${P}createJiraIssue`]: () => ({ key: "SAND-99", id: "1", self: "https://leak" }),
    };
    const r = await runTool(loaded, "jira_create", { taskId: task, project: "SAND", type: "Story" }, dir, metaHandlers);
    check(r.error === null, "jira_create: succeeds");
    assertEqual(r.calls.map((c) => c.name), [`${P}getJiraProjectIssueTypesMetadata`, `${P}createJiraIssue`], "jira_create: checks the issue type, then creates");
    assertEqual(r.calls[0]!.args, { cloudId: "cloud-1", projectIdOrKey: "SAND" }, "jira_create: type check args");
    assertEqual(r.calls[1]!.args, { cloudId: "cloud-1", projectKey: "SAND", issueTypeName: "Story", summary: "Promote me", description: "Body of Promote me", contentFormat: "markdown" }, "jira_create: summary and description come from the task heading and body read through ot");
    assertEqual(r.result.structuredContent, { key: "SAND-99", url: `${BASE}/browse/SAND-99` }, "jira_create: returns {key, url} only");
    check(readFileSync(join(dir, "TASKS.org"), "utf-8").includes(":LINKED_ISSUES: [[jira:SAND-99]]"), "jira_create: writes [[jira:KEY]] to :LINKED_ISSUES: with ot issue add");

    const badType = await runTool(loaded, "jira_create", { taskId: task, project: "SAND", type: "Epic" }, dir, metaHandlers);
    check(badType.error !== null && badType.error.includes("Task, Story") && !badType.calls.some((c) => c.name === `${P}createJiraIssue`), "jira_create: a missing issue type throws with the available types and creates nothing");
    const noKey = await runTool(loaded, "jira_create", { taskId: task, project: "SAND" }, dir, { ...metaHandlers, [`${P}createJiraIssue`]: () => ({ id: "1" }) });
    check(noKey.error !== null && noKey.error.includes("no key"), "jira_create: a create result without a key throws");
    const noTask = await runTool(loaded, "jira_create", { taskId: "00000000-0000-4000-8000-000000000000", project: "SAND" }, dir, metaHandlers);
    check(noTask.error !== null && noTask.calls.length === 0, "jira_create: an unknown task throws before any MCP call");

    const orphan = addTask(dir, "Orphan on link failure");
    const linkFail = await runTool(loaded, "jira_create", { taskId: orphan, project: "SAND" }, dir, {
      ...metaHandlers,
      [`${P}createJiraIssue`]: () => {
        // Remove the task between create and link so `ot issue add` fails.
        writeFileSync(join(dir, "TASKS.org"), readFileSync(join(dir, "TASKS.org"), "utf-8").replace(/\*\* TODO Orphan on link failure[\s\S]*?Body of Orphan on link failure\n/, ""));
        return { key: "SAND-100" };
      },
    });
    check(linkFail.error !== null && linkFail.error.includes("Created SAND-100") && linkFail.error.includes("[[jira:SAND-100]]"), "jira_create: a link failure after create names the new key and the manual fix");
  }

  // ── Command handlers ────────────────────────────────────────────────
  {
    const dir = makeProject({ cloudId: "cloud-1" });
    cleanup.push(dir);
    const id = addTask(dir, "Selected task", "[[jira:SAND-5]]", `[[${BASE}/browse/SAND-6][six]]`, "[[https://github.com/a/b/issues/4][gh]]");
    ot(dir, "select", id);

    const loaded = load();
    const expect = async (args: string, trigger: string, label: string) => {
      loaded.sent.length = 0;
      await loaded.run(args, dir);
      const s = loaded.sent[0];
      check(
        loaded.sent.length === 1 &&
          s!.m.customType === "jira" &&
          s!.m.display === false &&
          s!.m.content === trigger &&
          s!.o.triggerTurn === true,
        `/jira ${label}: one hidden trigger message with triggerTurn`,
      );
    };
    await expect("get 77", buildGetTrigger(["SAND-77"]), "get resolves a bare number against #+JIRA_PROJECT");
    await expect("clone SAND-1 SAND-2", buildCloneTrigger(["SAND-1", "SAND-2"]), "clone");
    await expect("claim", buildClaimTrigger(["SAND-5", "SAND-6"]), "claim resolves the selected task's Jira keys in TypeScript");
    await expect("comment Looks good", buildCommentTrigger(["SAND-5", "SAND-6"], "Looks good"), "comment");
    await expect("create --type Story", buildCreateTrigger(id, "SAND", "Story"), "create uses #+JIRA_PROJECT and the selected task id");
    check(loaded.userSent.length === 0, "/jira commands never send a user message");
    check(loaded.sent.every((s) => !s.m.content.includes("TASKS.org")), "/jira triggers name no task file");

    loaded.sent.length = 0;
    await loaded.run("get bogus", dir);
    check(loaded.sent.length === 0 && loaded.notes.some((n) => n.level === "error"), "/jira get: an invalid key notifies and sends nothing");
    await loaded.run("comment", dir);
    check(loaded.sent.length === 0, "/jira comment: an empty body sends nothing");

    const disconnected = load(false);
    await disconnected.run("get SAND-1", dir);
    await disconnected.run("claim", dir);
    check(disconnected.sent.length === 0 && disconnected.notes.every((n) => n.msg.includes("/mcp login atlassian")), "/jira: a disconnected MCP sends nothing and says /mcp login atlassian");

    const bare = makeProject({ cloudId: "cloud-1" });
    cleanup.push(bare);
    const none = load();
    await none.run("claim", bare);
    check(none.sent.length === 0 && none.notes.some((n) => n.msg.includes("No selected task")), "/jira claim: no selection sends nothing");
    const noKeys = addTask(bare, "No jira links", "[[https://github.com/a/b/issues/4][gh]]");
    ot(bare, "select", noKeys);
    await none.run("claim", bare);
    check(none.sent.length === 0 && none.notes.some((n) => n.msg.includes("no Jira-shaped")), "/jira claim: a task without Jira keys sends nothing");
    const noProject = makeProject({ project: false });
    cleanup.push(noProject);
    const selectedNoProject = addTask(noProject, "No project");
    ot(noProject, "select", selectedNoProject);
    const refusing = load();
    await refusing.run("create", noProject);
    check(refusing.sent.length === 0 && refusing.notes.some((n) => n.msg.includes("#+JIRA_PROJECT")), "/jira create: no project argument and no #+JIRA_PROJECT sends nothing and says how to set one");
    await refusing.run("create MBFW", noProject);
    check(refusing.sent.length === 1 && refusing.sent[0]!.m.content === buildCreateTrigger(selectedNoProject, "MBFW", "Task"), "/jira create: a PROJECT argument is passed through; the tool enforces the SAND restriction");
    await none.run("create", bare);
    check(none.sent.length === 1 && none.sent[0]!.m.content === buildCreateTrigger(noKeys, "SAND", "Task"), "/jira create: needs no Jira link on the selected task");

    // ── Auto-transition ───────────────────────────────────────────────
    const originalCwd = process.cwd();
    const originalAgentDir = process.env.PI_CODING_AGENT_DIR;
    const agentDir = mkdtempSync(join(tmpdir(), "jira-agent-"));
    cleanup.push(agentDir);
    try {
      writeFileSync(join(agentDir, "jira-ext.json"), JSON.stringify({ autoTransition: true }));
      process.env.PI_CODING_AGENT_DIR = agentDir;
      process.chdir(dir);
      const auto = load();
      const fire = (taskId: string | null, status: string) =>
        auto.events.get("tasks:status-changed")!({ id: taskId, status, prevStatus: "TODO", summary: "S", closed: status === "DONE" });
      await fire(id, "STARTED");
      check(
        auto.sent.length === 1 &&
          auto.sent[0]!.m.content === buildTransitionTrigger(["SAND-5", "SAND-6"], "STARTED") &&
          auto.sent[0]!.m.display === false &&
          auto.sent[0]!.o.triggerTurn === true &&
          auto.sent[0]!.o.deliverAs === "followUp",
        "auto-transition: STARTED sends one hidden follow-up trigger with the resolved keys",
      );
      await fire(id, "DONE");
      check(auto.sent.length === 2 && auto.sent[1]!.m.content === buildTransitionTrigger(["SAND-5", "SAND-6"], "DONE"), "auto-transition: DONE sends the DONE trigger");
      await fire(noKeys, "STARTED");
      await fire(id, "TODO");
      await fire("00000000-0000-4000-8000-000000000000", "STARTED");
      check(auto.sent.length === 2, "auto-transition: no Jira key, an unmirrored status or an unknown task sends nothing");
      check(auto.userSent.length === 0, "auto-transition: never sends a user message");
    } finally {
      process.chdir(originalCwd);
      if (originalAgentDir === undefined) delete process.env.PI_CODING_AGENT_DIR;
      else process.env.PI_CODING_AGENT_DIR = originalAgentDir;
    }
  }
}

main()
  .catch((e) => {
    failed++;
    console.log(`not ok - unexpected error: ${e instanceof Error ? e.stack : e}`);
  })
  .finally(() => {
    for (const d of cleanup) rmSync(d, { recursive: true, force: true });
    console.log(`\n# ${passed} passed, ${failed} failed`);
    process.exit(failed === 0 ? 0 : 1);
  });

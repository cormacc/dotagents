#!/usr/bin/env tsx
/**
 * Pi RPC integration tests: the extension runs in the installed Pi against a
 * fixture repository, driven by a scripted fake model.
 */

import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { createHarness, type Harness } from "../test/support/rpc.ts";

const here = dirname(fileURLToPath(import.meta.url));

let passed = 0;
let failed = 0;
async function test(name: string, fn: () => Promise<void>) {
  try {
    await fn();
    console.log(`ok - ${name}`);
    passed++;
  } catch (err) {
    console.log(`not ok - ${name}`);
    console.log(`  # ${err instanceof Error ? err.message : String(err)}`);
    failed++;
  }
}
function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

type Call = { tool: string; args: Record<string, unknown> };
/**
 * A provider whose reply depends on the last user message and on how many
 * assistant turns followed it: `script[key][n]` is the tool-call batch for turn n.
 * After the last scripted turn it answers "Done.".
 */
function fakeModel(dir: string, script: Record<string, Call[][]>): string {
  const path = join(dir, "fake-model.ts");
  writeFileSync(path, `
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { createAssistantMessageEventStream, type AssistantMessage, type Context, type Model } from "@earendil-works/pi-ai";
const script: Record<string, any[][]> = ${JSON.stringify(script)};
let seq = 0;
function stream(model: Model<any>, context: Context) {
  const s = createAssistantMessageEventStream();
  const out: AssistantMessage = { role: "assistant", content: [], api: model.api, provider: model.provider, model: model.id,
    usage: { input: 1, output: 1, cacheRead: 0, cacheWrite: 0, totalTokens: 2, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } },
    stopReason: "stop", timestamp: Date.now() };
  const msgs: any[] = context.messages as any[];
  let lastUser = -1;
  for (let i = msgs.length - 1; i >= 0; i--) if (msgs[i].role === "user") { lastUser = i; break; }
  const user = msgs[lastUser];
  const text = typeof user?.content === "string" ? user.content : (user?.content ?? []).filter((c: any) => c.type === "text").map((c: any) => c.text).join(" ");
  const turn = msgs.slice(lastUser + 1).filter((m) => m.role === "assistant").length;
  const key = Object.keys(script).find((k) => text.includes(k));
  // A compaction summary request (Pi's SUMMARIZATION_SYSTEM_PROMPT) gets text only.
  // Pi sends the prompt as a leading system message.
  const summary = msgs[0]?.role === "system" && String(msgs[0].content).startsWith("You are a context summarization assistant");
  const calls = key && !summary ? script[key][turn] : undefined;
  s.push({ type: "start", partial: out });
  out.content.push({ type: "text", text: calls ? "Working." : "Done." });
  if (calls) {
    out.stopReason = "toolUse";
    for (const c of calls) out.content.push({ type: "toolCall", id: "call_" + (++seq), name: c.tool, arguments: c.args });
  }
  s.push({ type: "done", reason: out.stopReason as any, message: out });
  s.end();
  return s;
}
export default function (pi: ExtensionAPI) {
  pi.registerProvider("fake", { baseUrl: "http://localhost:1", apiKey: "fake", api: "openai-completions",
    models: [{ id: "fake-model", name: "Fake", reasoning: false, input: ["text"],
      cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, contextWindow: 128000, maxTokens: 4096 }],
    streamSimple: stream });
}
`);
  return path;
}

const gitEnv = { ...process.env, GIT_CONFIG_GLOBAL: "/dev/null", GIT_CONFIG_NOSYSTEM: "1" };
function git(cwd: string, ...args: string[]) {
  execFileSync("git", ["-c", "user.name=t", "-c", "user.email=t@t", ...args], { cwd, env: gitEnv, stdio: "pipe" });
}
function put(root: string, rel: string, text: string) {
  mkdirSync(dirname(join(root, rel)), { recursive: true });
  writeFileSync(join(root, rel), text);
}

/** A fixture repository: tracked rules at two depths, and one untracked file. */
function fixture(): string {
  const root = mkdtempSync(join(tmpdir(), "pi-nested-context-"));
  put(root, "a/AGENTS.md", "RULE-A: outer rule\n");
  put(root, "a/b/AGENTS.md", "RULE-B: inner rule\n");
  put(root, "a/b/f.txt", "hello\n");
  git(root, "init", "-q", "-b", "main");
  git(root, "add", "-A");
  git(root, "commit", "-q", "-m", "fixture");
  put(root, "u/AGENTS.md", "RULE-U: untracked\n");
  put(root, "u/f.txt", "hello\n");
  return root;
}

function start(root: string, script: Record<string, Call[][]>): Harness {
  const model = fakeModel(root, script);
  // The harness points PI_CODING_AGENT_DIR here. A tiny keepRecentTokens lets a
  // short test session compact; the default refuses ("session too small").
  put(root, ".pi-agent/settings.json", JSON.stringify({ compaction: { keepRecentTokens: 1 } }));
  return createHarness(join(here, "index.ts"), root, {
    args: ["--no-extensions", "--no-skills", "--no-prompt-templates", "--no-context-files", "--no-approve",
      "-e", model, "--provider", "fake", "--model", "fake-model"],
  });
}

/** Sends a prompt and returns the tool_execution_end events of its run. */
async function run(h: Harness, message: string): Promise<any[]> {
  const since = h.events.length;
  const id = h.send({ type: "prompt", message });
  const response = await h.waitFor((e) => e.type === "response" && e.id === id, `response to ${message}`, { since });
  assert(response.success, `prompt failed: ${response.error}`);
  await h.waitFor((e) => e.type === "agent_settled" || (e.type === "agent_end" && !e.willRetry), `end of ${message}`, { since });
  return h.events.slice(since).filter((e: any) => e.type === "tool_execution_end");
}
async function command(h: Harness, cmd: Record<string, unknown>) {
  const since = h.events.length;
  const id = h.send(cmd);
  const response = await h.waitFor((e) => e.type === "response" && e.id === id, `response to ${cmd.type}`, { since });
  assert(response.success, `${cmd.type} failed: ${response.error ?? JSON.stringify(response)}`);
  return response;
}
const text = (end: any) => (end.result?.content ?? []).filter((c: any) => c.type === "text").map((c: any) => c.text).join("\n");
const rules = (s: string) => (s.match(/RULE-[ABU]/g) ?? []).join(",");

const READ = { tool: "read", args: { path: "a/b/f.txt" } };
const WRITE = { tool: "write", args: { path: "a/b/w.txt", content: "written\n" } };

(async function main() {
  const root = fixture();
  const h = start(root, {
    "write-twice": [[WRITE], [WRITE]],
    "read-file": [[READ]],
    "read-untracked": [[{ tool: "read", args: { path: "u/f.txt" } }]],
  });
  try {
    await test("the first write is blocked with the rules outermost first, and the repeated write runs", async () => {
      const ends = await run(h, "write-twice");
      assert(ends.length === 2, `expected 2 tool ends, got ${ends.length}`);
      assert(ends[0].isError === true, `first write should be blocked: ${JSON.stringify(ends[0])}`);
      assert(rules(text(ends[0])) === "RULE-A,RULE-B", `block reason rules: ${rules(text(ends[0]))}`);
      assert(ends[1].isError !== true, `second write should run: ${text(ends[1])}`);
      assert(existsSync(join(root, "a/b/w.txt")), "the second write created the file");
    });
    await test("a later read in the same session adds nothing", async () => {
      const [end] = await run(h, "read-file");
      assert(end && end.isError !== true, "read ran");
      assert(rules(text(end)) === "", `unexpected rules: ${rules(text(end))}`);
    });
    await test("an untracked context file is not delivered", async () => {
      const [end] = await run(h, "read-untracked");
      assert(end && end.isError !== true, "read ran");
      assert(rules(text(end)) === "", `unexpected rules: ${rules(text(end))}`);
    });
    await test("a new session keeps its own delivery record", async () => {
      await command(h, { type: "new_session" });
      const [end] = await run(h, "read-file");
      assert(end && end.isError !== true, "read was not blocked");
      assert(text(end).includes("hello"), "the read result keeps the file content");
      assert(rules(text(end)) === "RULE-A,RULE-B", `read in the new session: ${rules(text(end))}`);
      const [again] = await run(h, "read-file");
      assert(rules(text(again)) === "", `second read: ${rules(text(again))}`);
    });
    await test("after compaction, the next touch delivers the rules again", async () => {
      await command(h, { type: "compact" });
      const [end] = await run(h, "read-file");
      assert(rules(text(end)) === "RULE-A,RULE-B", `read after compaction: ${rules(text(end))}`);
    });
  } finally {
    await h.dispose();
    rmSync(root, { recursive: true, force: true });
  }

  const root2 = fixture();
  const h2 = start(root2, {
    "batch": [[READ, { tool: "edit", args: { path: "a/b/f.txt", edits: [{ oldText: "hello", newText: "bye" }] } }]],
  });
  try {
    await test("a read and an edit in one parallel batch deliver the rules once, and the read is not blocked", async () => {
      const ends = await run(h2, "batch");
      const read = ends.find((e) => e.toolName === "read");
      const edit = ends.find((e) => e.toolName === "edit");
      assert(read && edit, `expected read and edit ends: ${JSON.stringify(ends.map((e) => e.toolName))}`);
      assert(read.isError !== true, "read was not blocked");
      const all = rules(text(read)) + (rules(text(read)) && rules(text(edit)) ? "," : "") + rules(text(edit));
      assert(all === "RULE-A,RULE-B", `rules across the batch: ${all}`);
    });
  } finally {
    await h2.dispose();
    rmSync(root2, { recursive: true, force: true });
  }

  console.log(`\n# ${passed} passed, ${failed} failed`);
  if (failed > 0) process.exit(1);
})();

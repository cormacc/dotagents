#!/usr/bin/env tsx
/**
 * Pi -> oh argv checks for the herdr-orch tool proxy (task af7273fd). These prove what the
 * Pi layer hands to `oh`; the oh -> Herdr argv is covered separately by
 * `skills/herdr-orch/scripts/test/herdr_orch/cli_test.clj` against the fake-herdr call log.
 */

import register from "./index.ts";

type Tool = { name: string; execute: (id: string, params: any, signal?: AbortSignal) => Promise<unknown> };

let failures = 0;
function ok(condition: unknown, message: string): void {
  if (condition) console.log(`ok - ${message}`);
  else {
    console.log(`not ok - ${message}`);
    failures++;
  }
}
function same(actual: unknown, expected: unknown, message: string): void {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  ok(a === e, a === e ? message : `${message}\n    expected ${e}\n    actual   ${a}`);
}

const tools = new Map<string, Tool>();
const execCalls: string[][] = [];
register({
  registerTool(tool: Tool) {
    tools.set(tool.name, tool);
  },
  async exec(_bin: string, args: string[]) {
    execCalls.push(args);
    return { stdout: JSON.stringify({ ok: true, schema: "herdr-orch/v1", result: { echoed: args } }), stderr: "", code: 0, killed: false };
  },
} as any);

async function argv(tool: string, params: Record<string, unknown>): Promise<string[]> {
  const before = execCalls.length;
  await tools.get(tool)!.execute("call", params);
  ok(execCalls.length === before + 1, `${tool} ${params.action}: exactly one oh invocation`);
  return execCalls[execCalls.length - 1]!;
}
async function refused(tool: string, params: Record<string, unknown>, pattern: RegExp, label: string): Promise<void> {
  const before = execCalls.length;
  let error: unknown;
  try {
    await tools.get(tool)!.execute("call", params);
  } catch (e) {
    error = e;
  }
  ok(error instanceof Error && pattern.test(error.message), `${label}: refused with an actionable message`);
  ok(execCalls.length === before, `${label}: oh was not invoked`);
}

// --- agent start: open kind set, blank refused ---------------------------------------
for (const kind of ["pi", "qwen", "letta", "muse", "some-future-kind"]) {
  same(await argv("herdr_agent", { action: "start", name: "child", kind, pane: "w:x" }),
    ["agent", "start", "child", "--kind", kind, "--pane", "w:x"], `start passes kind ${kind} through unchanged`);
}
await refused("herdr_agent", { action: "start", name: "child", kind: "", pane: "w:x" }, /nonblank kind/, "start with empty kind");
await refused("herdr_agent", { action: "start", name: "child", kind: "   ", pane: "w:x" }, /nonblank kind/, "start with whitespace kind");
same(await argv("herdr_agent", { action: "start", name: "child", kind: "pi", pane: "w:x", agentArgs: ["--model", "x"] }),
  ["agent", "start", "child", "--kind", "pi", "--pane", "w:x", "--", "--model", "x"], "start keeps native args after --");

// --- agent prompt: waits by default on the same call ---------------------------------
same(await argv("herdr_agent", { action: "prompt", target: "child", prompt: "hello" }),
  ["agent", "prompt", "child", "hello", "--wait"], "prompt waits by default with no --timeout (indefinite)");
same(await argv("herdr_agent", { action: "prompt", target: "child", prompt: "hello", wait: true }),
  ["agent", "prompt", "child", "hello", "--wait"], "prompt with explicit wait=true");
same(await argv("herdr_agent", { action: "prompt", target: "child", prompt: "hello", wait: false }),
  ["agent", "prompt", "child", "hello"], "prompt with wait=false omits waiting");
same(await argv("herdr_agent", { action: "prompt", target: "child", prompt: "hello", until: ["idle", "done"] }),
  ["agent", "prompt", "child", "hello", "--wait", "--until", "idle", "--until", "done"], "until reaches the same prompt call");
same(await argv("herdr_agent", { action: "prompt", target: "child", prompt: "hello", timeout: 120000 }),
  ["agent", "prompt", "child", "hello", "--wait", "--timeout", "120000"], "timeout reaches the same prompt call");
same(await argv("herdr_agent", { action: "prompt", target: "child", prompt: "hello", until: ["blocked"], timeout: 5 }),
  ["agent", "prompt", "child", "hello", "--wait", "--until", "blocked", "--timeout", "5"], "until and timeout together");
await refused("herdr_agent", { action: "prompt", target: "child", prompt: "hello", wait: false, until: ["idle"] }, /until and timeout require wait/, "prompt wait=false with until");
await refused("herdr_agent", { action: "prompt", target: "child", prompt: "hello", wait: false, timeout: 5 }, /until and timeout require wait/, "prompt wait=false with timeout");
ok(!execCalls.some((args) => args[0] === "agent" && args[1] === "wait"), "no prompt case issued a standalone agent wait");

// --- pane split: explicit source pane and focus --------------------------------------
same(await argv("herdr_layout", { action: "pane_split" }), ["pane", "split"], "split with nothing lets oh default pane, direction, cwd and no-focus");
same(await argv("herdr_layout", { action: "pane_split", pane: "w:tall" }), ["pane", "split", "--pane", "w:tall"], "explicit pane is passed as the source");
same(await argv("herdr_layout", { action: "pane_split", pane: "w:tall", direction: "down", cwd: "/tmp", focus: true }),
  ["pane", "split", "--pane", "w:tall", "--direction", "down", "--cwd", "/tmp", "--focus"], "explicit direction, cwd and focus=true reach oh");
same(await argv("herdr_layout", { action: "pane_split", focus: false }), ["pane", "split"], "focus=false requests nothing");

if (failures > 0) {
  console.log(`# ${failures} failure(s)`);
  process.exit(1);
}
console.log("# all herdr-orch Pi adapter argv checks passed");

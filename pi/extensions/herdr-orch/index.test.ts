#!/usr/bin/env tsx
/**
 * Pi -> oh argv checks for the herdr-orch tool proxy (task af7273fd). These prove what the
 * Pi layer hands to `oh`; the oh -> Herdr argv is covered separately by
 * `skills/herdr-orch/scripts/test/herdr_orch/cli_test.clj` against the fake-herdr call log.
 */

import register from "./index.ts";
import { composeHelp, parseSection, rosterArgs } from "./help.ts";

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
const execOptions: Array<Record<string, unknown> | undefined> = [];
type Command = { handler: (args: string, ctx: any) => Promise<void>; getArgumentCompletions?: (prefix: string) => unknown };
const commands = new Map<string, Command>();
const entryRenderers = new Map<string, unknown>();
const entries: Array<{ type: string; data: any }> = [];

register({
  registerTool(tool: Tool) {
    tools.set(tool.name, tool);
  },
  registerCommand(name: string, command: Command) {
    commands.set(name, command);
  },
  registerEntryRenderer(type: string, renderer: unknown) {
    entryRenderers.set(type, renderer);
  },
  appendEntry(type: string, data: unknown) {
    entries.push({ type, data });
  },
  async exec(_bin: string, args: string[], options?: Record<string, unknown>) {
    execCalls.push(args);
    execOptions.push(options);
    // `oh roster --format markdown` prints its rendering, not an envelope.
    if (args[0] === "roster") return { stdout: `LISTING ${args.join(" ")}\n`, stderr: "", code: 0, killed: false };
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

// --- /oh: display-only roster listing ------------------------------------------------
{
  const oh = commands.get("oh");
  ok(oh !== undefined, "/oh is registered");
  ok(entryRenderers.has("herdr-orch-help"), "/oh entry renderer is registered");
  same(parseSection(""), "help", "bare /oh shows the help overview");
  same(parseSection(" traits "), "traits", "section argument is trimmed");
  same(parseSection("bogus"), null, "unknown section is rejected");
  same((oh!.getArgumentCompletions!("m") as Array<{ value: string }>).map((item) => item.value), ["models"], "completion narrows by prefix");

  const notices: Array<[string, string]> = [];
  const ctx = (mode: string) => ({ mode, hasUI: true, cwd: "/proj", signal: undefined, ui: { notify: (message: string, level: string) => notices.push([message, level]) } });

  let before = execCalls.length;
  await oh!.handler("", { ...ctx("print"), hasUI: false });
  ok(execCalls.length === before && entries.length === 0 && notices.length === 0, "no UI (print/JSON): nothing runs, appends, or notifies");

  before = execCalls.length;
  await oh!.handler("bogus", ctx("tui"));
  ok(execCalls.length === before && entries.length === 0, "unknown section: oh not invoked and nothing appended");
  ok(notices.at(-1)?.[1] === "warning" && /help, personas, traits, models/.test(notices.at(-1)![0]), "unknown section: warns with the valid sections");

  before = execCalls.length;
  await oh!.handler("personas", ctx("tui"));
  same(execCalls.slice(before).sort(), [
    ["roster", "--format", "markdown", "--section", "personas"],
    ["roster", "--format", "markdown", "--section", "personas", "--detail"],
  ], "TUI: oh renders the compact and detail Markdown");
  ok(execOptions.slice(-2).every((options) => options?.cwd === "/proj"), "/oh runs oh in the session cwd so the project layer resolves");
  same(entries.length, 1, "TUI: one session entry appended");
  same(entries[0]?.type, "herdr-orch-help", "TUI: entry uses the display-only custom type");
  same(entries[0]?.data, {
    section: "personas",
    herdr: process.env.HERDR_ENV === "1",
    compact: "LISTING roster --format markdown --section personas\n",
    detail: "LISTING roster --format markdown --section personas --detail\n",
  }, "TUI: entry stores both oh renderings verbatim");

  before = execCalls.length;
  const noticesBefore = notices.length;
  await oh!.handler("", ctx("rpc"));
  same(execCalls.slice(before), [["roster", "--format", "markdown", "--detail"]], "RPC: one detail rendering of every section");
  same(entries.length, 1, "RPC: no session entry appended");
  ok(notices.length === noticesBefore + 1 && notices.at(-1)![1] === "info" && notices.at(-1)![0].includes("LISTING roster --format markdown --detail"), "RPC: listing is shown as a notification");
}

// --- /oh framing ----------------------------------------------------------------------
{
  same(rosterArgs("help", false), ["roster", "--format", "markdown"], "help asks oh for every section");
  same(rosterArgs("models", true), ["roster", "--format", "markdown", "--section", "models", "--detail"], "a section with detail");
  const help = composeHelp("help", "## Personas\n\n- x\n", true);
  ok(help.startsWith("Herdr: this pane can delegate"), "help leads with the pane's Herdr status");
  ok(help.includes("## Personas\n\n- x\n\n## Usage"), "help keeps the oh listing verbatim before the usage notes");
  ok(composeHelp("help", "", false).includes("not detected"), "help reports missing Herdr");
  ok(/Spawn a subagent\* starts a fresh context.*fork a subagent\* inherits your completed earlier turns, not the current one/.test(help), "usage routes spawn to fresh and fork to forked context");
  ok(/--context measurement --measurement-config PATH`: a headless Pi sample with no pane/.test(help), "usage describes headless measurement apart from its optional monitor");
  ok(help.includes("skills/herdr-orch/references/workflow-recipes.md"), "usage links root-owned workflow recipes");
  same(composeHelp("traits", "- `a` -- b\n", true), "- `a` -- b", "a section is the oh listing alone");
}

if (failures > 0) {
  console.log(`# ${failures} failure(s)`);
  process.exit(1);
}
console.log("# all herdr-orch Pi adapter argv checks passed");

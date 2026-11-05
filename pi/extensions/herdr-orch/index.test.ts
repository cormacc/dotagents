#!/usr/bin/env tsx
/**
 * Pi -> oh argv checks for the herdr-orch tool proxy (task af7273fd). These prove what the
 * Pi layer hands to `oh`; the oh -> Herdr argv is covered separately by
 * `skills/herdr-orch/scripts/test/herdr_orch/cli_test.clj` against the fake-herdr call log.
 */

import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import register, { launcherPath } from "./index.ts";
import { composeHelp, parseSection, rosterArgs, usageArgs } from "./help.ts";

type Tool = { name: string; description: string; promptGuidelines?: string[]; execute: (id: string, params: any, signal?: AbortSignal) => Promise<unknown> };

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
const setupCalls: string[] = [];
let setupStatus = { ready: true, version: "0.1.69", reason: null as string | null };
let setupFailure = false;
let setupKilled = false;
let setupOutput: string | undefined;
const setupOptions: Array<Record<string, unknown>> = [];
let cancelInstall = false;
const lifecycle = new Map<string, () => void>();
const execOptions: Array<Record<string, unknown> | undefined> = [];
type Command = { handler: (args: string, ctx: any) => Promise<void>; getArgumentCompletions?: (prefix: string) => unknown };
const commands = new Map<string, Command>();
const entryRenderers = new Map<string, unknown>();
const entries: Array<{ type: string; data: any }> = [];

register({
  on(event: string, handler: () => void) { lifecycle.set(event, handler); },
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
    if (_bin.endsWith("quota-axi-setup")) {
      setupCalls.push(args[0]!);
      setupOptions.push(options ?? {});
      if (setupFailure) throw new Error("SECRET-private-npm-config");
      if (cancelInstall && args[0] === "install") {
        lifecycle.get("session_shutdown")!();
        ok((options?.signal as AbortSignal)?.aborted, "session shutdown cancels the owned setup process");
        return { stdout: JSON.stringify({ ready: true, version: "0.1.69" }), stderr: "SECRET-private-npm-config", code: 0, killed: false };
      }
      if (args[0] === "install") setupStatus = { ready: true, version: "0.1.69", reason: null };
      return { stdout: setupOutput ?? JSON.stringify(setupStatus), stderr: "SECRET-private-npm-config", code: 0, killed: setupKilled };
    }
    execCalls.push(args);
    execOptions.push(options);
    // `oh roster` and `oh usage` markdown output are renderings, not envelopes.
    if (args[0] === "roster") return { stdout: `LISTING ${args.join(" ")}\n`, stderr: "", code: 0, killed: false };
    if (args[0] === "usage") {
      const detail = args.includes("--detail");
      return {
        stdout: detail
          ? "- **anthropic** -- unknown; remaining unknown; reason: missing; source: pi; windows: five_hour 20% used, resets 2030-01-01T00:00:00Z\n"
          : "- **anthropic** -- unknown; remaining unknown; reason: missing; windows: five_hour 20% used\n",
        stderr: "",
        code: 0,
        killed: false,
      };
    }
    return { stdout: JSON.stringify({ ok: true, schema: "herdr-orch/v1", result: { echoed: args } }), stderr: "", code: 0, killed: false };
  },
} as any);

const layoutGuidelines = tools.get("herdr_layout")!.promptGuidelines!.join("\n");
const paneGuidelines = tools.get("herdr_pane")!.promptGuidelines!.join("\n");
ok(layoutGuidelines.includes("only when the user explicitly mentions Herdr or asks to inspect or control Herdr"), "always-loaded guidelines retain the Herdr consent boundary");
ok(layoutGuidelines.includes("read the herdr skill"), "direct control loads the canonical Herdr procedures");
ok(layoutGuidelines.includes("read herdr-orch"), "delegation loads its own placement contract");
ok(paneGuidelines.includes("Do not close a Herdr pane you did not create unless the user explicitly asks"), "always-loaded guidelines retain pane ownership protection");
ok(tools.get("herdr_agent")!.description.includes("idle, done, or blocked"), "agent readiness remains in the tool contract");

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
  same(parseSection("workflows"), "workflows", "workflow section is recognised");
  same(parseSection("usage"), "usage", "usage section is recognised");
  same((oh!.getArgumentCompletions!("u") as Array<{ value: string }>).map((item) => item.value), ["usage"], "usage section is completed");
  same((oh!.getArgumentCompletions!("w") as Array<{ value: string }>).map((item) => item.value), ["workflows"], "workflow section is completed");
  same((oh!.getArgumentCompletions!("m") as Array<{ value: string }>).map((item) => item.value), ["models"], "completion narrows by prefix");

  const notices: Array<[string, string]> = [];
  const ctx = (mode: string) => ({ mode, hasUI: true, cwd: "/proj", signal: undefined, ui: { notify: (message: string, level: string) => notices.push([message, level]) } });

  let before = execCalls.length;
  await oh!.handler("", { ...ctx("print"), hasUI: false });
  ok(execCalls.length === before && entries.length === 0 && notices.length === 0, "no UI (print/JSON): nothing runs, appends, or notifies");

  before = execCalls.length;
  await oh!.handler("bogus", ctx("tui"));
  ok(execCalls.length === before && entries.length === 0, "unknown section: oh not invoked and nothing appended");
  ok(notices.at(-1)?.[1] === "warning" && /help, personas, traits, models, usage, workflows/.test(notices.at(-1)![0]), "unknown section: warns with the valid sections");

  before = execCalls.length;
  await oh!.handler("personas", ctx("tui"));
  same(execCalls.slice(before).sort(), [
    ["roster", "--format", "markdown", "--section", "personas"],
    ["roster", "--format", "markdown", "--section", "personas", "--detail"],
  ], "TUI: oh renders the compact and detail Markdown");
  ok(execOptions.slice(-2).every((options) => options?.cwd === "/proj"), "/oh runs oh in the session cwd so the project layer resolves");
  same(execOptions.slice(-2).map((options) => options?.timeout), [30_000, 30_000], "/oh roster keeps its 30-second command budget");
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
  same(execCalls.slice(before).sort(), [
    ["roster", "--format", "markdown", "--detail"],
    ["usage", "--format", "markdown", "--detail"],
  ], "RPC: detail roster and usage renderings support the help overview");
  same(entries.length, 1, "RPC: no session entry appended");
  ok(notices.length === noticesBefore + 1 && notices.at(-1)![1] === "info" && notices.at(-1)![0].includes("LISTING roster --format markdown --detail"), "RPC: listing is shown as a notification");
  ok(notices.at(-1)![0].includes("reason: missing") && notices.at(-1)![0].includes("source: pi"), "RPC: detailed usage shows an unknown-provider reason and detail fields");

  before = execCalls.length;
  await oh!.handler("", ctx("tui"));
  same(execCalls.slice(before).sort(), [
    ["roster", "--format", "markdown"],
    ["roster", "--format", "markdown", "--detail"],
    ["usage", "--format", "markdown"],
    ["usage", "--format", "markdown", "--detail"],
  ], "TUI: bare oh appends compact and detailed usage after roster defaults");
  same(
    execCalls.slice(before).map((args, index) => [args.join(" "), execOptions[before + index]?.timeout]).sort(),
    [
      ["roster --format markdown", 30_000],
      ["roster --format markdown --detail", 30_000],
      ["usage --format markdown", 75_000],
      ["usage --format markdown --detail", 75_000],
    ].sort(),
    "bare /oh assigns the quota budget to each command regardless of render order",
  );
  same(entries.at(-1)?.data, {
    section: "help",
    herdr: process.env.HERDR_ENV === "1",
    compact: "LISTING roster --format markdown\n",
    detail: "LISTING roster --format markdown --detail\n",
    usage: {
      compact: "- **anthropic** -- unknown; remaining unknown; reason: missing; windows: five_hour 20% used\n",
      detail: "- **anthropic** -- unknown; remaining unknown; reason: missing; source: pi; windows: five_hour 20% used, resets 2030-01-01T00:00:00Z\n",
    },
  }, "TUI: bare oh retains Herdr framing and usage outside model context");

  before = execCalls.length;
  await oh!.handler("usage", ctx("tui"));
  same(execCalls.slice(before).sort(), [
    ["usage", "--format", "markdown"],
    ["usage", "--format", "markdown", "--detail"],
  ], "TUI: usage calls the CLI's compact and detailed Markdown forms");
  same(execOptions.slice(-2).map((options) => options?.timeout), [75_000, 75_000], "/oh usage gives compact and detail reads the quota budget");
  same(entries.at(-1)?.data, {
    section: "usage",
    herdr: process.env.HERDR_ENV === "1",
    compact: "- **anthropic** -- unknown; remaining unknown; reason: missing; windows: five_hour 20% used\n",
    detail: "- **anthropic** -- unknown; remaining unknown; reason: missing; source: pi; windows: five_hour 20% used, resets 2030-01-01T00:00:00Z\n",
  }, "TUI: usage stays in the display-only entry and preserves its unknown reason");

  before = execCalls.length;
  await oh!.handler("workflows", ctx("tui"));
  same(execCalls.slice(before).sort(), [
    ["roster", "--format", "markdown", "--section", "workflows"],
    ["roster", "--format", "markdown", "--section", "workflows", "--detail"],
  ], "TUI: workflow listing uses the shared CLI renderer in both forms");
  same(entries.at(-1)?.data.section, "workflows", "workflow listing remains a display-only session entry");
}

// --- /oh framing ----------------------------------------------------------------------
{
  same(rosterArgs("help", false), ["roster", "--format", "markdown"], "help asks oh for every section");
  same(rosterArgs("models", true), ["roster", "--format", "markdown", "--section", "models", "--detail"], "a section with detail");
  same(usageArgs(false), ["usage", "--format", "markdown"], "usage asks for compact Markdown");
  same(usageArgs(true), ["usage", "--format", "markdown", "--detail"], "usage asks for detailed Markdown");
  same(rosterArgs("workflows", false), ["roster", "--format", "markdown", "--section", "workflows"], "workflow section arguments");
  const help = composeHelp("help", "## Personas\n\n- x\n", true);
  ok(help.startsWith("Herdr: this pane can delegate"), "help leads with the pane's Herdr status");
  ok(help.includes("## Personas\n\n- x\n\n## Usage"), "help keeps the oh listing verbatim before the usage notes");
  const helpWithUsage = composeHelp("help", "## Spawn defaults\n\n- tab-split\n", true, "- **anthropic** -- unknown; remaining unknown; reason: missing\n");
  ok(helpWithUsage.includes("## Spawn defaults\n\n- tab-split\n\n## Subscription usage\n\n- **anthropic** -- unknown; remaining unknown; reason: missing\n\n## Usage"), "help appends the same usage block after spawn defaults before its instructions");
  ok(composeHelp("help", "", false).includes("not detected"), "help reports missing Herdr");
  ok(/Spawn a subagent\* starts a fresh context.*fork a subagent\* inherits your completed earlier turns, not the current one/.test(help), "usage routes spawn to fresh and fork to forked context");
  ok(/--context measurement --measurement-config PATH`: a headless Pi sample with no pane/.test(help), "usage describes headless measurement apart from its optional monitor");
  ok(help.includes("skills/herdr-orch/references/workflow-recipes.md"), "usage links root-owned workflow recipes");
  ok(help.includes("metadata: {herdr-orch: workflow}"), "usage documents the self-declaration marker");
  ok(help.includes("does not run it or grant permission to delegate"), "catalogue membership does not grant execution authority");
  same(composeHelp("traits", "- `a` -- b\n", true), "- `a` -- b", "a section is the oh listing alone");
  same(composeHelp("workflows", "- `flow` -- description\n", false), "- `flow` -- description", "workflow section adds no delegation framing");
}

// Shared setup runs once before parallel renderings and never from tool routing.
{
  const oh = commands.get("oh")!;
  const notices: string[] = [];
  let prompts = 0;
  let consent = false;
  const ctx = (mode = "tui", hasUI = true) => ({ mode, hasUI, cwd: "/proj", ui: {
    notify: (text: string) => notices.push(text),
    confirm: async (_title: string, text: string) => {
      prompts++;
      ok(text.includes("quota-axi@0.1.69") && text.includes("lifecycle scripts are disabled") && text.includes("Network"), "confirmation explains pin, network and lifecycle suppression");
      return consent;
    },
  } });
  for (const reason of ["missing-executable", "wrong-version"]) {
    setupStatus = { ready: false, version: "0.1.69", reason };
    setupCalls.length = 0;
    const before = prompts;
    await oh.handler("", ctx());
    same(setupCalls, ["check"], "decline/cancel never installs");
    same(prompts, before + 1, "compact/detail help asks only once");
  }
  consent = true;
  setupCalls.length = 0;
  await oh.handler("usage", ctx());
  same(setupCalls, ["check", "install"], "confirmed usage installs once before rendering");
  setupStatus = { ready: false, version: "0.1.69", reason: "wrong-version" };
  setupCalls.length = 0;
  const beforeUsage = execCalls.length;
  await oh.handler("setup", ctx());
  same(setupCalls, ["check", "install"], "explicit setup uses shared installer");
  same(execCalls.length, beforeUsage, "explicit setup does not read quotas");
  for (const [mode, hasUI] of [["rpc", true], ["print", false], ["json", false]] as const) {
    setupStatus = { ready: false, version: "0.1.69", reason: "missing-executable" };
    setupCalls.length = 0;
    const before = prompts;
    let stderr = "";
    const write = process.stderr.write;
    process.stderr.write = ((text: string) => { stderr += text; return true; }) as typeof write;
    try { await oh.handler("setup", ctx(mode, hasUI)); } finally { process.stderr.write = write; }
    same(setupCalls, hasUI ? ["check"] : [], `${mode}: explicit setup cannot install`);
    same(prompts, before, `${mode}: no blind confirmation wait`);
    ok((hasUI ? notices.at(-1) : stderr)?.includes("quota-axi-setup install"), `${mode}: manual instructions`);
  }
  setupCalls.length = 0;
  await tools.get("herdr_agent")!.execute("call", { action: "start", kind: "pi" });
  same(setupCalls, [], "routing tools never run setup");
  setupFailure = true;
  await oh.handler("setup", ctx());
  ok(!notices.at(-1)?.includes("SECRET"), "setup process errors are sanitised");
  setupFailure = false;
  for (const output of ["SECRET-malformed", JSON.stringify({ ready: true, version: "0.1.69", reason: "SECRET".repeat(1024) })]) {
    setupOutput = output;
    setupCalls.length = 0;
    const before = prompts;
    await oh.handler("setup", ctx());
    same(setupCalls, ["check"], "invalid or oversized setup status cannot install");
    same(prompts, before, "invalid or oversized setup status cannot prompt");
    ok(notices.at(-1)?.includes("Quota setup failed") && !notices.at(-1)?.includes("SECRET"), "invalid or oversized setup status is sanitised and never ready");
  }
  setupOutput = JSON.stringify({ ready: true, version: "0.1.69" }).padEnd(4096);
  await oh.handler("setup", ctx());
  ok(notices.at(-1) === "quota-axi@0.1.69 is ready.", "a valid 4 KiB status is accepted on supported Pi");
  setupOutput = JSON.stringify({ ready: true, version: "0.1.69", reason: "é".repeat(2048) });
  await oh.handler("setup", ctx());
  ok(notices.at(-1)?.includes("Quota setup failed"), "setup status size is bounded in UTF-8 bytes, not characters");
  setupOutput = undefined;
  setupKilled = true;
  setupStatus = { ready: true, version: "0.1.69", reason: null };
  await oh.handler("setup", ctx());
  ok(notices.at(-1)?.includes("Quota setup failed") && !notices.at(-1)?.includes("SECRET"), "a killed setup cannot report ready even with valid status");
  setupKilled = false;
  ok(setupOptions.every((options) => Object.keys(options).sort().join(",") === "cwd,signal,timeout"), "setup uses only supported Pi exec options");
  ok(setupOptions.every((options) => options.cwd === "/proj" && options.timeout === 140_000 && options.signal instanceof AbortSignal), "setup retains cwd, timeout and cancellation on supported Pi");
  ok(!tools.has("quota_setup"), "no model-visible installer tool");
  cancelInstall = true;
  setupStatus = { ready: false, version: "0.1.69", reason: "missing-executable" };
  setupCalls.length = 0;
  await oh.handler("setup", ctx());
  same(setupCalls, ["check", "install"], "cancellation belongs to the one confirmed install");
  ok(notices.at(-1)?.includes("Quota setup failed") && !notices.at(-1)?.includes("SECRET"), "cancelled setup never reports ready or raw output");
  setupCalls.length = 0;
  await oh.handler("setup", ctx());
  same(setupCalls, [], "an already cancelled setup flow cannot spawn again");
}

{
  // The packaged launcher lets a Pi package install run its bundled skill without ~/.agents.
  const base = mkdtempSync(join(tmpdir(), "herdr-orch-launcher-"));
  try {
    const home = join(base, "home");
    const extension = join(base, "package", "pi", "extensions", "herdr-orch");
    const packaged = join(base, "package", "skills", "herdr-orch", "scripts", "oh");
    const homeLauncher = join(home, ".agents", "skills", "herdr-orch", "scripts", "oh");
    same(launcherPath(home, {}, extension), homeLauncher, "no launcher reports the documented home path");
    mkdirSync(join(packaged, ".."), { recursive: true });
    writeFileSync(packaged, "");
    same(launcherPath(home, {}, extension), packaged, "packaged launcher is used without a home skill");
    mkdirSync(join(homeLauncher, ".."), { recursive: true });
    writeFileSync(homeLauncher, "");
    same(launcherPath(home, {}, extension), homeLauncher, "home skill wins over the packaged launcher");
    same(launcherPath(home, { HERDR_ORCH_BIN: "/override/oh" }, extension), "/override/oh", "override wins");
  } finally {
    rmSync(base, { recursive: true, force: true });
  }
}

if (failures > 0) {
  console.log(`# ${failures} failure(s)`);
  process.exit(1);
}
console.log("# all herdr-orch Pi adapter argv checks passed");

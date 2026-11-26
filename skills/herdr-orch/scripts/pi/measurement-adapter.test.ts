// Checks for the measurement adapter. Loaded only by ./run-tests.ts inside the installed Pi
// runtime. End-to-end cases launch a real subject Pi (same runtime) through the Clojure launch
// helper against a local fake provider endpoint, so the installed provider code builds the request.
import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, realpathSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { startFakeAnthropic, type Scripted } from "./fake-anthropic.ts";
import { startFakeOpenAI } from "./fake-openai.ts";
import type { TestCase } from "./runtime.ts";

const here = dirname(fileURLToPath(import.meta.url));
const repo = realpathSync(join(here, "../../../.."));
const ASSIGNMENT = "Summarise the experiment.";

// ---- end-to-end harness --------------------------------------------------------------
interface Run {
  code: number | null;
  stdout: string;
  stderr: string;
  requests: any[];
  headers: Record<string, string | string[] | undefined>[];
  adapter: (name: string) => any;
  // The work root's entries when the subject ended, read before the root is removed, so a side effect is observable.
  workFiles: string[];
  argv: string[];
}
interface RunOptions {
  script?: Scripted[];
  tools?: string[];
  system?: string;
  skills?: string[];
  assignment?: string;
  compat?: object;
  api?: string;
  authExtension?: boolean;
  discovered?: boolean;
}

async function runSubject(options: RunOptions = {}): Promise<Run> {
  const root = realpathSync(mkdtempSync(join(tmpdir(), "herdr-measure-")));
  const api = options.api ?? "anthropic-messages";
  const script = options.script ?? [{ text: "done" }];
  const fake = api === "openai-completions" ? await startFakeOpenAI("completions", script)
    : api === "openai-responses" || api === "openai-codex-responses" ? await startFakeOpenAI("responses", script)
    : await startFakeAnthropic(script);
  try {
    const work = join(root, "work");
    const agent = join(root, "agent");
    const cfg = join(root, "cfg");
    for (const d of [work, agent, cfg]) mkdirSync(d, { recursive: true });
    writeFileSync(join(work, "data.txt"), "file contents\n");
    // Codex derives an account id from the credential's JWT claims; the SSE transport avoids a websocket.
    const codexToken = `x.${Buffer.from(JSON.stringify({ "https://api.openai.com/auth": { chatgpt_account_id: "acct-fixture" } })).toString("base64")}.y`;
    if (api === "openai-codex-responses") writeFileSync(join(agent, "settings.json"), JSON.stringify({ transport: "sse" }));
    writeFileSync(join(agent, "models.json"), JSON.stringify({
      providers: { fakeanth: { baseUrl: fake.url, api, apiKey: api === "openai-codex-responses" ? codexToken : api.startsWith("openai") ? "sk-fixture-openai" : "sk-ant-api-fake",
        models: [{ id: "fake-model", reasoning: false, compat: options.compat ?? {} }] } },
    }));
    if (options.discovered) {
      // Everything Pi would normally discover; none of it may reach the request.
      writeFileSync(join(work, "AGENTS.md"), "DISCOVERED-PROJECT-CONTEXT\n");
      writeFileSync(join(agent, "AGENTS.md"), "DISCOVERED-HOME-CONTEXT\n");
      mkdirSync(join(agent, "skills", "leak"), { recursive: true });
      writeFileSync(join(agent, "skills", "leak", "SKILL.md"), "---\nname: leak\ndescription: DISCOVERED-SKILL\n---\nbody\n");
      mkdirSync(join(agent, "prompts"), { recursive: true });
      writeFileSync(join(agent, "prompts", "leak.md"), "DISCOVERED-TEMPLATE\n");
      mkdirSync(join(agent, "extensions"), { recursive: true });
      writeFileSync(join(agent, "extensions", "leak.ts"), `export default function (pi) { pi.on("before_agent_start", async () => ({ message: { customType: "leak", content: "DISCOVERED-EXTENSION", display: false } })); }\n`);
      mkdirSync(join(work, ".pi", "skills", "leak2"), { recursive: true });
      writeFileSync(join(work, ".pi", "skills", "leak2", "SKILL.md"), "---\nname: leak2\ndescription: DISCOVERED-PROJECT-SKILL\n---\nbody\n");
    }
    const extensions: string[] = [];
    if (options.authExtension) {
      // A provider-auth extension adds credentials at the header boundary.
      const path = join(cfg, "auth.ts");
      writeFileSync(path, `export default function (pi) { pi.on("before_provider_headers", async (e) => { e.headers["x-fixture-auth"] = "explicit-extension-token"; }); }\n`);
      extensions.push(path);
    }
    const config: any = { tools: options.tools ?? [], extensions, hardTimeoutMs: 60000, expectedOutputs: [] };
    if (options.system !== undefined) { writeFileSync(join(cfg, "system.md"), options.system); config.systemPromptFile = "system.md"; }
    if (options.skills) { config.skillFiles = options.skills.map((text, i) => { writeFileSync(join(cfg, `skill-${i}.md`), text); return `skill-${i}.md`; }); }
    writeFileSync(join(cfg, "measurement.json"), JSON.stringify(config));
    const assignmentFile = join(cfg, "assignment.txt");
    writeFileSync(assignmentFile, options.assignment ?? ASSIGNMENT);
    const evidence = join(root, "evidence");
    const spec = spawnSync("bb", ["--config", join(repo, "bb.edn"), "--deps-root", repo, "-m", "herdr-orch.measurement", "launch-spec", join(cfg, "measurement.json"), "task-1", assignmentFile, evidence, here, "--provider", "fakeanth", "--model", "fake-model"],
      { encoding: "utf8", cwd: repo, timeout: 60000 });
    assert.equal(spec.status, 0, `launch helper failed: ${spec.stderr}${spec.stdout}`);
    const launch = JSON.parse(spec.stdout);
    // Same runtime as this process, regardless of what `pi` resolves to on PATH.
    const argv = [process.argv[1], ...launch.argv.slice(1)];
    const env = { ...launch.env, PI_CODING_AGENT_DIR: agent, PI_OFFLINE: "1" };
    const child = spawn(process.execPath, argv, { cwd: work, env, stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (d) => (stdout += d));
    child.stderr.on("data", (d) => (stderr += d));
    const code = await new Promise<number | null>((resolve) => child.on("close", resolve));
    // Read the evidence and the work root now: both are removed below.
    const workFiles = readdirSync(work);
    const adapterDir = launch["adapter-evidence-dir"];
    const files = new Map<string, string>();
    if (existsSync(adapterDir)) for (const name of readdirSync(adapterDir)) if (statSync(join(adapterDir, name)).isFile()) files.set(name, readFileSync(join(adapterDir, name), "utf8"));
    return {
      code, stdout, stderr, requests: [...fake.requests], headers: [...fake.headers], workFiles, argv,
      adapter: (name) => {
        const text = files.get(name);
        if (text === undefined) throw new Error(`no adapter evidence ${name}; stderr: ${stderr.slice(0, 400)}`);
        return name.endsWith(".jsonl") ? text.trim().split("\n").map((line) => JSON.parse(line)) : JSON.parse(text);
      },
    };
  } finally {
    await fake.close();
    rmSync(root, { recursive: true, force: true });
  }
}

const readEvents = (run: Run): any[] => { try { return run.adapter("events.jsonl"); } catch { return []; } };
const textOfContent = (c: any): string => (typeof c === "string" ? c : c.map((b: any) => b.text).join(""));
const requestText = (run: Run) => JSON.stringify(run.requests);
const LEAKS = ["DISCOVERED-PROJECT-CONTEXT", "DISCOVERED-HOME-CONTEXT", "DISCOVERED-SKILL", "DISCOVERED-TEMPLATE", "DISCOVERED-EXTENSION", "DISCOVERED-PROJECT-SKILL", "Current working directory"];

export const tests: TestCase[] = [
  { name: "e2e: the subject sends the exact compiled treatment, assignment and tools", async run() {
    const run = await runSubject({ system: "SYSTEM TEXT", skills: ["SKILL ONE", "SKILL TWO"], tools: ["read"], discovered: true });
    assert.equal(run.code, 0, run.stderr + run.stdout);
    assert.equal(run.requests.length, 1);
    const request = run.requests[0];
    assert.deepEqual(request.system.map((b: any) => b.text), ["SYSTEM TEXT\n\nSKILL ONE\n\nSKILL TWO"]);
    assert.deepEqual(request.tools.map((t: any) => t.name), ["read"]);
    assert.equal(request.messages[0].content[0].text, ASSIGNMENT);
    assert.equal(run.adapter("terminal.json").success, true);
    // Complete pre-request payloads are retained, not projections.
    assert.deepEqual(run.adapter("request-1.json").messages, request.messages);
    for (const leaked of LEAKS) assert.equal(requestText(run).includes(leaked), false, leaked);
  } },
  // The OpenAI-family APIs carry the treatment in their own channel.
  ...(["openai-completions", "openai-responses", "openai-codex-responses"] as const).map((api): TestCase => (
    { name: `e2e ${api}: the request carries the treatment, assignment and tool`, async run() {
      const run = await runSubject({ api, tools: ["read"], system: "SYS", discovered: true });
      assert.equal(run.code, 0, JSON.stringify(run.adapter("terminal.json")) + run.stderr.slice(0, 300));
      assert.equal(run.adapter("terminal.json").success, true);
      const first = run.requests[0];
      if (api === "openai-completions") {
        assert.equal(first.messages[0].content, "SYS");
        assert.deepEqual(first.tools.map((t: any) => t.function.name), ["read"]);
        assert.equal(textOfContent(first.messages.find((m: any) => m.role === "user").content), ASSIGNMENT);
      } else {
        assert.deepEqual(first.tools.map((t: any) => t.name), ["read"]);
        assert.equal(first.input.find((i: any) => i.role === "user").content[0].text, ASSIGNMENT);
        // Codex has used both top-level `instructions` and a leading input item across Pi releases.
        assert.equal(first.instructions ?? first.input.find((i: any) => i.role === "system" || i.role === "developer")?.content, "SYS");
      }
      for (const leaked of LEAKS) assert.equal(requestText(run).includes(leaked), false, leaked);
    } })),
  { name: "e2e: assignment transport preserves literal leading slashes, at-signs, hyphens and template text", async run() {
    for (const assignment of ["/not-a-command with args", "@not-a-file.txt please", "-not-an-option --flag value", "{{template}} $ARGUMENTS $1 ${HOME}"]) {
      const run = await runSubject({ assignment });
      assert.equal(run.code, 0, `${assignment}: ${run.stderr}`);
      assert.equal(run.requests[0].messages[0].content[0].text, assignment);
      assert.equal(run.argv.some((a) => a.includes(assignment)), false, "assignment text never reaches argv");
      assert.equal(run.adapter("terminal.json").success, true);
    }
  } },
  { name: "e2e: an empty tool selection enables zero tools", async run() {
    const run = await runSubject({ tools: [] });
    assert.equal(run.code, 0, run.stderr);
    assert.equal(run.requests[0].tools === undefined || run.requests[0].tools.length === 0, true);
    assert.equal(run.adapter("terminal.json").success, true);
  } },
  { name: "e2e: unknown or wrong-case tool selections fail before any model request", async run() {
    for (const tools of [["Read"], ["no_such_tool"]]) {
      const run = await runSubject({ tools });
      assert.notEqual(run.code, 0);
      assert.equal(run.requests.length, 0);
      const terminal = run.adapter("terminal.json");
      assert.equal(terminal.success, false);
      assert.match(terminal.violations.join(" "), /unknown or wrong-case tool selection|differ from the configured/);
    }
  } },
  { name: "e2e: an explicitly selected provider-auth extension works", async run() {
    const run = await runSubject({ authExtension: true, system: "SYS" });
    assert.equal(run.code, 0, run.stderr + run.stdout);
    assert.equal(run.headers[0]["x-fixture-auth"], "explicit-extension-token");
    assert.equal(run.adapter("terminal.json").success, true);
  } },
  { name: "e2e: only the selected tools are callable", async run() {
    const script: Scripted[] = [{ toolUse: { id: "toolu_b", name: "bash", input: { command: "echo leaked > leaked.txt" } } }, { text: "done" }];
    // Positive control: with bash configured the same call runs, so the side-effect probe can observe it.
    const configured = await runSubject({ tools: ["bash"], system: "SYS", script });
    assert.equal(configured.adapter("terminal.json").success, true, JSON.stringify(configured.adapter("terminal.json")));
    assert.ok(configured.workFiles.includes("leaked.txt"), `the configured call executed: ${configured.workFiles.join(", ")}`);
    const run = await runSubject({ tools: ["read"], system: "SYS", script });
    const terminal = run.adapter("terminal.json");
    assert.equal(terminal.success, false);
    assert.match(terminal.violations.join(" "), /call to unconfigured tool bash/);
    assert.ok(run.workFiles.includes("data.txt"), "the work root was read before cleanup");
    assert.equal(run.workFiles.includes("leaked.txt"), false, "the refused call did not execute");
  } },
  { name: "e2e: a failed turn is never retried and the verdict waits for final settlement", async run() {
    const failed = await runSubject({ script: [{ error: 529 }, { text: "done" }], system: "SYS" });
    assert.equal(failed.requests.length, 1, "the retry request is refused before it is sent");
    const terminal = failed.adapter("terminal.json");
    assert.equal(terminal.success, false);
    assert.match(terminal.reason, /automatic retry is refused/);
    assert.ok(terminal.errorMessage, "the original provider failure is retained");
    // A clean run records its verdict only at agent_settled.
    const ok = await runSubject({ system: "SYS" });
    assert.equal(ok.adapter("terminal.json").success, true);
    assert.equal(ok.adapter("terminal.json").aborted, false);
  } },
  { name: "e2e: the served model is recorded per turn, and unknown provenance is never guessed", async run() {
    const fb = { provider: "fakeanth", model: "fallback-model", cost: { input: 1, output: 1, cacheRead: 0, cacheWrite: 0 } };
    const common = { system: "SYS", compat: { allowedFallbackModels: [fb] } };
    // The reply names the requested model: Pi surfaces no different served model, so provenance stays unknown.
    const control = await runSubject(common);
    assert.deepEqual(control.adapter("terminal.json").turns, [{ requestedModel: "fake-model", allowedFallbacks: [{ provider: "fakeanth", model: "fallback-model" }], servedModel: null, servedProvenance: "unknown" }]);
    // Provider-reported served model per turn: the declared fallback, and one outside the declared list (recorded as undeclared, not rejected).
    const served = await runSubject({ ...common, tools: ["read"], script: [{ toolUse: { id: "toolu_1", name: "read", input: { path: "data.txt" } }, model: "fallback-model" }, { text: "done", model: "surprise-model" }] });
    assert.equal(served.adapter("terminal.json").success, true, JSON.stringify(served.adapter("terminal.json")));
    const turns = served.adapter("terminal.json").turns;
    assert.deepEqual(turns.map((t: any) => [t.requestedModel, t.servedModel, t.servedProvenance, t.servedModelDeclared]), [["fake-model", "fallback-model", "provider-reported", true], ["fake-model", "surprise-model", "provider-reported", false]]);
    assert.ok(readEvents(served).some((e: any) => e.kind === "assistant" && e.servedModel === "fallback-model"), "the per-turn record is also in the event stream");
    const completions = await runSubject({ api: "openai-completions", system: "SYS", script: [{ text: "done", model: "served-elsewhere" }] });
    assert.deepEqual(completions.adapter("terminal.json").turns.map((t: any) => [t.servedModel, t.servedProvenance]), [["served-elsewhere", "provider-reported"]]);
  } },
  { name: "automatic compaction is cancelled and recorded as a violation", async run() {
    const root = realpathSync(mkdtempSync(join(tmpdir(), "herdr-compact-")));
    const saved = process.env.HERDR_ORCH_MEASUREMENT_MANIFEST;
    const savedAgent = process.env.PI_CODING_AGENT_DIR;
    try {
      process.env.PI_CODING_AGENT_DIR = root;
      writeFileSync(join(root, "assignment.txt"), ASSIGNMENT);
      writeFileSync(join(root, "manifest.json"), JSON.stringify({ treatment: "", assignmentFile: join(root, "assignment.txt"), tools: [], evidenceDir: join(root, "evidence") }));
      process.env.HERDR_ORCH_MEASUREMENT_MANIFEST = join(root, "manifest.json");
      const handlers = new Map<string, Function>();
      const adapter = (await import("./measurement-adapter.ts")).default;
      await adapter({ on: (name: string, handler: Function) => handlers.set(name, handler), getActiveTools: () => [], getAllTools: () => [], getThinkingLevel: () => "off" } as any);
      assert.deepEqual(await handlers.get("session_before_compact")!({}), { cancel: true });
      await handlers.get("message_end")!({ message: { role: "assistant", stopReason: "stop", content: [{ type: "text", text: "x" }] } });
      await handlers.get("agent_settled")!({ aborted: false });
      process.exitCode = 0;
      const terminal = JSON.parse(readFileSync(join(root, "evidence", "terminal.json"), "utf8"));
      assert.equal(terminal.success, false);
      assert.deepEqual(terminal.violations, ["automatic compaction is refused"]);
    } finally {
      if (saved === undefined) delete process.env.HERDR_ORCH_MEASUREMENT_MANIFEST; else process.env.HERDR_ORCH_MEASUREMENT_MANIFEST = saved;
      if (savedAgent === undefined) delete process.env.PI_CODING_AGENT_DIR; else process.env.PI_CODING_AGENT_DIR = savedAgent;
      rmSync(root, { recursive: true, force: true });
    }
  } },
  { name: "the adapter is inert without a manifest", async run() {
    const probe = spawnSync(process.execPath, [process.argv[1], "--offline", "--no-session", "-ne", "-e", join(here, "measurement-adapter.ts"), "-e", join(here, "fork-adapter.ts"), "--no-skills", "--no-prompt-templates", "--no-context-files", "--no-mcp", "--version"], { encoding: "utf8", timeout: 60000, stdio: ["ignore", "pipe", "pipe"], env: { ...process.env, HERDR_ORCH_MEASUREMENT_MANIFEST: "" } });
    assert.equal(probe.status, 0, probe.stderr);
    assert.equal(existsSync(join(here, "evidence")), false);
  } },
];

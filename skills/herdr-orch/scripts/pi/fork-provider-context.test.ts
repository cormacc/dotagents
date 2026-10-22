// Installed-Pi requests, captured by the existing loopback fake after transport encoding.
// Synthetic sessions only: this suite never opens the user's sessions or credentials.
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { spawn, spawnSync } from "node:child_process";
import { copyFileSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { SessionManager } from "@earendil-works/pi-coding-agent";
import { createFork } from "./fork-adapter.ts";
import { startFakeOpenAI } from "./fake-openai.ts";
import { runtimeIdentity, type TestCase } from "./runtime.ts";

const here = dirname(fileURLToPath(import.meta.url));
const repo = realpathSync(join(here, "../../../.."));
const CHILD = "fixture-current-child";
const PARENT = "fixture-retired-parent";
const ASSIGNMENT = "Inspect ONLY fixture data; do not resume the parent workflow.";
const WORKER = "FIXTURE WORKER ROLE: implement only the assigned task.";
const OVERLAY = "FIXTURE REVIEWER ROLE: review only the assigned task.";
const USEFUL = "USEFUL INHERITED CONTEXT: fixture format is TSV.";
// The caller's current-turn request: it must never reach the child, which would take it as its own work.
const CALLER_REQUEST = "Use the useful fixture context for a separate child.";
const COMPLETED_ANSWER = "COMPLETED TURN ANSWER: the fixture is ready.";
const user = (content: string) => ({ role: "user", content, timestamp: Date.now() });
const text = (text: string) => ({ type: "text", text });
const assistant = (...content: any[]) => ({ role: "assistant", content, api: "openai-codex-responses", provider: "fakefork", model: "fake-model", responseId: "resp_inherited_parent", usage: { input: 1, output: 1, cacheRead: 0, cacheWrite: 0, totalTokens: 2, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } }, stopReason: "stop", timestamp: Date.now() });
const call = (id: string) => ({ type: "toolCall", id, name: "read", arguments: { path: "fixture.tsv" } });
const sha = (s: string) => createHash("sha256").update(s).digest("hex");
const writeJson = (path: string, value: unknown) => writeFileSync(path, JSON.stringify(value, null, 2) + "\n");
const requestText = (item: any): string => typeof item.content === "string" ? item.content : (item.content ?? []).map((b: any) => b.text ?? "").join("");

// Expectations come from the source fixture and the real launcher prompt, never from the capture.
function verifyRequest(body: any, prompt: string, role: string, sessionId: string) {
  assert.equal(body.model, "fake-model");
  assert.equal(body.store, false);
  assert.equal(body.previous_response_id, undefined, "a new fork must not continue the parent's response");
  assert.equal(body.prompt_cache_key, sessionId, "cache identity belongs to the child");
  assert.ok(body.instructions.includes(role), "selected role reaches system instructions");
  assert.ok(!body.instructions.includes(role === WORKER ? OVERLAY : WORKER), "the other role is not the current system role");
  const users = body.input.filter((i: any) => i.role === "user");
  assert.equal(requestText(users.at(-1)), prompt, "the current assignment is the last user message, verbatim");
  assert.ok(prompt.includes(`TASK=${CHILD} and RESULT=fixture-current-result`));
  assert.ok(prompt.indexOf("Fork task boundary") < prompt.indexOf(ASSIGNMENT));
  const encoded = JSON.stringify(body);
  for (const token of [USEFUL, `TASK=${PARENT}`, "COMPLETED READ RESULT", "CUSTOM VISIBLE CONTEXT", COMPLETED_ANSWER]) assert.ok(encoded.includes(token), `retained context: ${token}`);
  for (const token of ["DEAD BRANCH", CALLER_REQUEST, "INFLIGHT LAUNCH", "PARTIAL SIBLING", "METADATA NOT CONTEXT", "herdr-orch-fork"]) assert.ok(!encoded.includes(token), `outside selected projection: ${token}`);
  assert.ok(body.input.find((i: any) => i.type === "function_call_output" && i.output === "COMPLETED READ RESULT"), "completed tool exchange retained");
}

function cleanEnv(root: string): NodeJS.ProcessEnv {
  return { PATH: process.env.PATH, HOME: root, XDG_CONFIG_HOME: join(root, "config"), XDG_CACHE_HOME: join(root, "cache"), TMPDIR: root, JAVA_TOOL_OPTIONS: `-Djava.io.tmpdir=${root}`, PI_CODING_AGENT_DIR: join(root, "agent"), PI_OFFLINE: "1", NODE_OPTIONS: `--require=${join(here, "fake-only.cjs")}` };
}

function checkFakeGuard(env: NodeJS.ProcessEnv, origin: string, dir: string) {
  const endpoint = new URL(origin);
  assert.equal(endpoint.hostname, "127.0.0.1");
  const guard = spawnSync(process.execPath, ["-e", `const assert = require("node:assert/strict");
      assert.equal(process.env.HERDR_ORCH_FAKE_GUARD_LOADED, "1");
      assert.throws(() => fetch("https://203.0.113.1/forbidden"), /fake-only: outbound connection blocked/);
      assert.throws(() => require("node:net").connect({ host: "203.0.113.1", port: 443 }), /fake-only: outbound connection blocked/);
      console.log("guard loaded; external fetch and socket refused before connection");`], { env: { ...env, HERDR_ORCH_FAKE_ORIGIN: origin }, encoding: "utf8", timeout: 10000 });
  assert.equal(guard.status, 0, guard.stderr);
  assert.match(guard.stdout, /guard loaded; external fetch and socket refused before connection/);
  writeFileSync(join(dir, "guard.txt"), guard.stdout);
}

// The setup probe uses the same startup path but exits at context construction, before HTTP.
async function runFixturePi(argv: string[], env: NodeJS.ProcessEnv, target: string, sessionIdentity: string, dir: string, expectReply = true) {
  let child: ReturnType<typeof spawn> | undefined;
  let timer: ReturnType<typeof setTimeout> | undefined;
  try {
    child = spawn(process.execPath, argv, { cwd: target, env, stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "", stderr = "";
    child.stdout.on("data", d => stdout += d);
    child.stderr.on("data", d => stderr += d);
    assert.ok(Number.isInteger(child.pid), "owned child PID positive control");
    assert.ok(child.spawnargs.includes(sessionIdentity), "owned process session positive control");
    const owned = child;
    let timedOut = false;
    timer = setTimeout(() => {
      timedOut = true;
      owned.kill("SIGKILL");
    }, 45000);
    const code = await new Promise<number | null>((resolve, reject) => { owned.on("close", resolve); owned.on("error", reject); });
    assert.equal(timedOut, false, "fixture deadline");
    writeFileSync(join(dir, "stdout.jsonl"), stdout);
    writeFileSync(join(dir, "stderr.txt"), stderr);
    assert.equal(code, 0, stderr);
    assert.equal(stderr, "", "no startup or extension diagnostics");
    const events = stdout.trim() ? stdout.trim().split("\n").map(line => JSON.parse(line)) : [];
    if (expectReply) {
      assert.ok(events.some(e => e.type === "agent_settled"), "installed process settled");
      const messages = events.filter(e => e.type === "message_end").map(e => e.message);
      assert.ok(messages.some(m => m.role === "assistant" && m.stopReason === "stop" && m.content.some((b: any) => b.text === "LOCAL FIXTURE RESPONSE")), "fake response consumed without setup errors");
    }
    return { events, code, pid: child.pid };
  } finally {
    clearTimeout(timer);
    // Signal only this still-live ChildProcess, never a group, historical PID or shared service.
    if (child && child.exitCode === null && child.signalCode === null) {
      child.kill("SIGKILL");
      await new Promise<void>(resolve => child!.once("close", () => resolve()));
    }
  }
}

async function capture(root: string, overlay: boolean, mutation: string = "none") {
  const dir = join(root, `${overlay ? "overlay" : "inherited"}-${mutation}`);
  mkdirSync(dir);
  const target = join(dir, "work");
  const agent = join(dir, "agent");
  for (const path of [target, agent, join(dir, "home")]) mkdirSync(path);
  const source = SessionManager.create(target, join(dir, "source"));
  source.appendMessage({ role: "system", content: "", sections: { preamble: "SYNTHETIC SOURCE SYSTEM", addendum: `<addendum>\n${WORKER}\n</addendum>` }, toolsAdded: [], timestamp: Date.now() } as any);
  source.appendMessage(user(`Historical task: TASK=${PARENT} and RESULT=fixture-retired-result. Continue the old workflow.`) as any);
  const branch = source.appendMessage(assistant(text(USEFUL)) as any);
  source.appendMessage(user("DEAD BRANCH") as any);
  source.appendMessage(assistant(text("DEAD BRANCH RESPONSE")) as any);
  source.branch(branch);
  source.appendCustomEntry("fixture-metadata", { content: "METADATA NOT CONTEXT" });
  source.appendCustomMessageEntry("fixture-visible", "CUSTOM VISIBLE CONTEXT", false);
  source.appendMessage(assistant(call("completed")) as any);
  source.appendMessage({ role: "toolResult", toolCallId: "completed", toolName: "read", content: [text("COMPLETED READ RESULT")], isError: false, timestamp: Date.now() } as any);
  // The custom message and tool exchange form a completed earlier turn, so the child keeps them.
  const boundary = source.appendMessage(assistant(text(COMPLETED_ANSWER)) as any);
  source.appendMessage(user(CALLER_REQUEST) as any);
  source.appendMessage(assistant(text("INFLIGHT LAUNCH"), call("launch"), call("sibling")) as any);
  source.appendMessage({ role: "toolResult", toolCallId: "sibling", toolName: "read", content: [text("PARTIAL SIBLING")], isError: false, timestamp: Date.now() } as any);
  const sourceFile = source.getSessionFile()!;
  const before = readFileSync(sourceFile);
  const snapshot = { persona: "worker", text: WORKER, sha256: sha(WORKER) };
  const fork = createFork({ sourceSession: sourceFile, targetCwd: target, sessionDir: join(dir, "forks"), callerRole: { named: true, snapshot }, ...(overlay ? { overlay: { persona: "reviewer", text: OVERLAY } } : {}) });
  assert.equal(fork.boundary.entryId, boundary);
  assert.equal(fork.role.source, overlay ? "overlay" : "snapshot");
  assert.notEqual(fork.sessionId, source.getSessionId());
  const roleFile = join(dir, "role.md");
  writeFileSync(roleFile, (fork.role as any).text);
  const launchFile = join(dir, "launch.json");
  writeJson(launchFile, { "fork?": true, "persona-path": roleFile, assignment: ASSIGNMENT, spawns: [], task: CHILD, result: "fixture-current-result", "waiting-policy": "blocking" });
  const env = { ...cleanEnv(dir), HOME: join(dir, "home"), HERDR_ORCH_FAKE_ORIGIN: undefined };
  // Load only the pure prompt consumer, not execute/spawn or Herdr. JVM temp state is isolated too.
  const generated = spawnSync("bb", ["--config", join(repo, "bb.edn"), "--deps-root", repo, "-e", `(require '[herdr-orch.cli :as cli] '[cheshire.core :as json]) (print (cli/prompt-text (json/parse-string (slurp ${JSON.stringify(launchFile)}) true)))`], { env, cwd: repo, encoding: "utf8", timeout: 30000 });
  assert.equal(generated.status, 0, generated.stderr);
  const prompt = generated.stdout;
  assert.ok(prompt.includes(ASSIGNMENT), "prompt consumer positive control");
  writeFileSync(join(dir, "prompt.txt"), prompt);
  const fake = await startFakeOpenAI("responses", [{ text: "LOCAL FIXTURE RESPONSE" }]);
  try {
    checkFakeGuard(env, fake.url, dir);
    const token = `x.${Buffer.from(JSON.stringify({ "https://api.openai.com/auth": { chatgpt_account_id: "acct-fixture" } })).toString("base64")}.y`;
    writeJson(join(agent, "models.json"), { providers: { fakefork: { baseUrl: fake.url, api: "openai-codex-responses", apiKey: token, models: [{ id: "fake-model", reasoning: false }] } } });
    writeJson(join(agent, "auth.json"), {});
    writeJson(join(agent, "settings.json"), { transport: "sse", compaction: { enabled: false }, retry: { enabled: false }, cacheWarming: "off" });
    const extension = join(dir, "controlled.ts");
    // This event changes the actual outgoing payload, not a copied request or saved transcript.
    writeFileSync(extension, `export default function(pi) {
      pi.on("session_start", (_e, ctx) => {
        if (process.env.HERDR_ORCH_FAKE_GUARD_LOADED !== "1" || ctx.sessionManager.getSessionId() !== ${JSON.stringify(fork.sessionId)} || ctx.cwd !== ${JSON.stringify(target)}) throw new Error("fixture identity/guard mismatch");
      });
      pi.on("before_provider_request", (e) => {
        const p = e.payload; const index = p.input.findIndex(i => i.role === "user" && JSON.stringify(i).includes(${JSON.stringify(ASSIGNMENT)}));
        if (index < 0) throw new Error("fixture current assignment not found before mutation");
        if (${JSON.stringify(mutation)} === "drop") p.input.splice(index, 1);
        if (${JSON.stringify(mutation)} === "reorder") p.input.unshift(p.input.splice(index, 1)[0]);
        if (${JSON.stringify(mutation)} === "parent") p.input[index].content[0].text = p.input[index].content[0].text.replace("TASK=${CHILD}", "TASK=${PARENT}");
        return p;
      });
    }\n`);
    const argv = [process.argv[1], "--offline", "--session", fork.sessionFile, "--provider", "fakefork", "--model", "fake-model", "--append-system-prompt", roleFile, "-ne", "-e", extension, "--no-skills", "--no-prompt-templates", "--no-context-files", "--no-mcp", "--no-tools", "--mode", "json", "--", prompt];
    const { code, pid } = await runFixturePi(argv, { ...env, HERDR_ORCH_FAKE_ORIGIN: fake.url }, target, fork.sessionFile, dir);
    assert.equal(fake.requests.length, 1, "exactly one provider request");
    assert.ok(readFileSync(sourceFile).equals(before), "source bytes unchanged");
    const request = fake.requests[0];
    writeJson(join(dir, "request.json"), request);
    writeJson(join(dir, "identity.json"), { runtime: runtimeIdentity(), fork, argv, pid, code, mutation, guard: "fake-only.cjs", endpoint: fake.url });
    // Saved user data stays correct even when the request is deliberately changed.
    const saved = SessionManager.open(fork.sessionFile).buildSessionContext().messages;
    assert.ok(saved.some((m: any) => m.role === "user" && requestText(m) === prompt));
    if (mutation === "none") verifyRequest(request, prompt, overlay ? OVERLAY : WORKER, fork.sessionId);
    else {
      assert.throws(() => verifyRequest(request, prompt, overlay ? OVERLAY : WORKER, fork.sessionId), /current assignment is the last user message/);
      assert.notEqual(requestText(request.input.filter((i: any) => i.role === "user").at(-1)), prompt, "mutation reached the actual captured request");
    }
    {
      const out = join(`${root}-captures`, `${overlay ? "overlay" : "inherited"}-${mutation}`);
      mkdirSync(out, { recursive: true });
      for (const name of ["request.json", "identity.json", "prompt.txt", "stdout.jsonl", "stderr.txt", "controlled.ts", "role.md", "guard.txt"]) copyFileSync(join(dir, name), join(out, name));
      copyFileSync(sourceFile, join(out, "source.jsonl"));
      copyFileSync(fork.sessionFile, join(out, "child.jsonl"));
      writeJson(join(out, "verification.json"), { setupErrors: 0, actualMutation: mutation, verdict: mutation === "none" ? "accepted" : "rejected", savedCurrentUserIntact: true });
    }
    return request;
  } finally {
    await fake.close();
  }
}

const NATIVE_REASONING = { type: "reasoning", id: "rs_fixture", summary: [{ type: "summary_text", text: "SYNTHETIC THINKING SUMMARY" }], encrypted_content: "fixture-encrypted-reasoning" };
const NATIVE_TOOL = `import { Type } from "typebox";
function registerNativeTool(pi) {
  pi.registerTool({ name: "fixture_read", label: "Fixture Read", description: "Read synthetic TSV.", parameters: Type.Object({ path: Type.String() }),
    async execute() { throw new Error("native fixture tool must not execute"); } });
}
`;

function nativeExpectation(seed: any, overlay: boolean, prompt: string, sessionId: string) {
  const source = structuredClone(seed.system);
  source.sections.tools = "<tools>\nSOURCE TOOL PROSE\n</tools>";
  const sections = Object.fromEntries(Object.entries(seed.system.sections).filter(([key]) => key === "tools" || (overlay && key === "addendum")));
  if (overlay) sections.addendum = (sections.addendum as string).replace(WORKER, OVERLAY);
  return {
    instructions: Object.values(source.sections).filter(Boolean).join("\n\n"),
    sections,
    delta: Object.entries(sections).map(([key, value]) => `Updated system prompt section "${key}":\n\n${value}`).join("\n\n"),
    tools: seed.system.toolsAdded.map((tool: any) => ({ type: "function", name: tool.name, description: tool.description, parameters: tool.parameters, strict: null })),
    // Pi 1.4.0 declares tools in place (an additional_tools item at the head of the input, no
    // top-level list) when the model takes both mid-conversation system messages and additional
    // tools; otherwise the current tools are the top-level list. Derived from the model's
    // capabilities, never from the received request. Re-check this rule after a Pi upgrade.
    toolPlacement: seed.model.compat?.supportsMidConvoSystemMessages && seed.model.compat?.supportsAdditionalTools ? "in-place" : "top-level",
    prompt, sessionId, source,
  };
}

function verifyNativeSetup(messages: any[], expected: ReturnType<typeof nativeExpectation>) {
  const systems = messages.filter(m => m.role === "system");
  assert.equal(systems.length, 2, "native setup system count");
  assert.deepEqual(systems[0], expected.source, "native setup source construction");
  assert.deepEqual(systems[1].sections, expected.sections, "native setup section delta");
  assert.equal(systems[1].content, "", "native setup has no flat prompt");
  assert.equal((systems[1].toolsAdded ?? []).length, 0, "native setup declaration additions");
  assert.equal((systems[1].toolsRemoved ?? []).length, 0, "native setup declaration removals");
}

function verifyNativeRequest(body: any, expected: ReturnType<typeof nativeExpectation>) {
  assert.equal(body.instructions, expected.instructions, "native leading instructions");
  assert.equal(body.model, "gpt-6-luna");
  assert.deepEqual(body.reasoning, { effort: "high", summary: "auto" }, "native high thinking");
  assert.equal(body.store, false);
  assert.equal(body.prompt_cache_key, expected.sessionId);
  assert.equal(body.previous_response_id, undefined);
  const users = body.input.filter((item: any) => item.role === "user");
  assert.deepEqual(users.at(-1), { role: "user", content: [{ type: "input_text", text: expected.prompt }] }, "native current assignment");
  assert.ok(expected.prompt.includes(`TASK=${CHILD} and RESULT=fixture-current-result`));
  assert.ok(expected.prompt.indexOf("Fork task boundary") < expected.prompt.indexOf(ASSIGNMENT));
  const developers = body.input.filter((item: any) => item.role === "developer" && item.type !== "additional_tools");
  assert.deepEqual(developers, [{ role: "developer", content: expected.delta }], "native developer delta");
  assert.equal(body.input.indexOf(developers[0]), body.input.length - 2, "native delta placement");
  assert.equal(requestText(body.input.at(-1)), expected.prompt, "native current assignment ends input");
  // Declarations are checked after the assignment and delta, so each mutation control fails at its own assertion.
  if (expected.toolPlacement === "in-place") {
    assert.equal(body.tools, undefined, "native frozen declarations: no top-level list");
    assert.deepEqual(body.input[0], { type: "additional_tools", role: "developer", tools: expected.tools }, "native frozen declarations");
    assert.equal(body.input.filter((item: any) => item.type === "additional_tools").length, 1, "native frozen declarations: one declaration item");
  } else {
    assert.deepEqual(body.tools, expected.tools, "native frozen declarations");
  }
  // The transcript items that follow any in-place declaration.
  const history = expected.toolPlacement === "in-place" ? body.input.slice(1) : body.input;
  assert.deepEqual(history.map((item: any) => item.role ?? item.type), ["user", "reasoning", "assistant", "function_call", "function_call_output", "assistant", "developer", "user"], "native history order");
  assert.equal(requestText(history[5]), COMPLETED_ANSWER, "native completed earlier turn");
  assert.deepEqual(history[1], NATIVE_REASONING, "native signed reasoning replay");
  assert.equal(requestText(history[2]), USEFUL);
  assert.deepEqual(history[3], { type: "function_call", id: "fc_completed", call_id: "completed", name: "fixture_read", arguments: JSON.stringify({ path: "fixture.tsv" }) });
  assert.deepEqual(history[4], { type: "function_call_output", call_id: "completed", output: "COMPLETED READ RESULT" });
  assert.ok(requestText(history[0]).includes(`TASK=${PARENT}`));
  for (const token of [CALLER_REQUEST, "INFLIGHT LAUNCH", "PARTIAL SIBLING", "herdr-orch-fork"]) assert.ok(!JSON.stringify(body).includes(token), `native source boundary: ${token}`);
}

async function captureNative(root: string, overlay: boolean, mutation = "none") {
  const label = `native-${overlay ? "overlay" : "inherited"}-${mutation}`;
  const dir = join(root, label);
  const out = join(`${root}-captures`, label);
  const target = join(dir, "work"), agent = join(dir, "agent"), setup = join(out, "setup");
  for (const path of [target, agent, join(dir, "home"), setup]) mkdirSync(path, { recursive: true });
  const fake = await startFakeOpenAI("responses", [{ text: "LOCAL FIXTURE RESPONSE" }]);
  try {
    const env = { ...cleanEnv(dir), HOME: join(dir, "home"), HERDR_ORCH_FAKE_ORIGIN: fake.url };
    checkFakeGuard(env, fake.url, out);
    // Resolve immutable catalogue data beside the running bundle, not a repository SDK or user store.
    const catalogueFile = realpathSync(join(dirname(runtimeIdentity().bundle), "../../../pi-ai/dist/providers/data/openai-codex.json"));
    const catalogue = JSON.parse(readFileSync(catalogueFile, "utf8"));
    const luna = catalogue["openai-codex-responses"]["chat:gpt-6-luna"];
    assert.equal(luna.id, "gpt-6-luna");
    assert.equal(luna.provider, "openai-codex");
    assert.equal(luna.api, "openai-codex-responses");
    assert.equal(luna.reasoning, true);
    assert.equal(luna.compat.supportsMidConvoSystemMessages, true);
    assert.equal(luna.thinkingLevelMap.high, "high");
    assert.ok(catalogue["openai-codex-responses"]["chat:gpt-6-sol"], "catalogue sibling control");
    const token = `x.${Buffer.from(JSON.stringify({ "https://api.openai.com/auth": { chatgpt_account_id: "acct-fixture" } })).toString("base64")}.y`;
    const model = { ...luna, baseUrl: fake.url, ...(mutation === "collapsed" ? { compat: { ...luna.compat, supportsMidConvoSystemMessages: false } } : {}) };
    writeJson(join(agent, "models.json"), { providers: { "openai-codex": { baseUrl: fake.url, apiKey: token, models: [model] } } });
    writeJson(join(agent, "auth.json"), {});
    writeJson(join(agent, "settings.json"), { transport: "sse", compaction: { enabled: false }, retry: { enabled: false }, cacheWarming: "off" });
    const roleFile = join(dir, "role.md");
    writeFileSync(roleFile, WORKER);
    const common = [process.argv[1], "--offline", "--provider", "openai-codex", "--model", "gpt-6-luna", "--thinking", "high", "--append-system-prompt", roleFile, "-ne", "--no-skills", "--no-prompt-templates", "--no-context-files", "--no-mcp", "--tools", "fixture_read", "--mode", "json"];
    const seedSession = SessionManager.create(target, join(dir, "seed-sessions"));
    seedSession.appendMessage(user("RETIRED SETUP BRANCH") as any);
    seedSession.resetLeaf();
    seedSession.appendCustomEntry("native-empty-seed", {});
    assert.deepEqual(seedSession.buildSessionContext().messages, [], "seed has no active conversation");
    const seedId = seedSession.getSessionId();
    const seedFile = seedSession.getSessionFile()!;
    const seedPath = join(setup, "seed.json");
    const seedExtension = join(dir, "seed.ts");
    writeFileSync(seedExtension, `${NATIVE_TOOL}\nimport { writeFileSync } from "node:fs";
export default function(pi) {
  registerNativeTool(pi);
  pi.on("context_with_system", (e, ctx) => {
    if (process.env.HERDR_ORCH_FAKE_GUARD_LOADED !== "1" || ctx.cwd !== ${JSON.stringify(target)} || ctx.sessionManager.getSessionId() !== ${JSON.stringify(seedId)}) throw new Error("native seed identity/guard mismatch");
    writeFileSync(${JSON.stringify(seedPath)}, JSON.stringify({ system: e.messages[0], model: { id: ctx.model.id, provider: ctx.model.provider, api: ctx.model.api, reasoning: ctx.model.reasoning, compat: ctx.model.compat } }));
    process.exit(0);
  });
}\n`);
    await runFixturePi([...common, "--session", seedFile, "-e", seedExtension, "--", "native-setup"], env, target, seedFile, setup, false);
    copyFileSync(seedFile, join(setup, "seed-session.jsonl"));
    assert.equal(fake.requests.length, 0, "seed construction never calls the provider");
    const seed = JSON.parse(readFileSync(seedPath, "utf8"));
    assert.equal(seed.system.role, "system");
    assert.equal(seed.system.content, "");
    assert.equal(seed.system.sections.addendum, `<addendum>\n${WORKER}\n</addendum>`, "native seed worker role");
    for (const key of ["preamble", "rules", "docs", "cwd", "tools", "addendum"]) assert.ok(seed.system.sections[key], `native seed section ${key}`);
    assert.deepEqual(seed.system.toolsAdded.map((tool: any) => tool.name), ["fixture_read"], "native seed frozen loadout");
    assert.equal(seed.system.toolsAdded[0].constrainedSampling, undefined);
    assert.deepEqual(seed.model, { id: luna.id, provider: luna.provider, api: luna.api, reasoning: luna.reasoning, compat: model.compat }, "native model capability control");
    const source = SessionManager.create(target, join(dir, "source"));
    const sourceHead = structuredClone(seed.system);
    sourceHead.sections.tools = "<tools>\nSOURCE TOOL PROSE\n</tools>";
    source.appendMessage(sourceHead);
    source.appendMessage(user(`Historical task: TASK=${PARENT} and RESULT=fixture-retired-result.`) as any);
    const nativeAssistant = { ...assistant({ type: "thinking", thinking: "", thinkingSignature: JSON.stringify(NATIVE_REASONING) }, text(USEFUL), { ...call("completed|fc_completed"), name: "fixture_read", arguments: { path: "fixture.tsv" } }), provider: luna.provider, api: luna.api, model: luna.id, thinkingLevel: "high" };
    source.appendMessage(nativeAssistant as any);
    source.appendMessage({ role: "toolResult", toolCallId: "completed|fc_completed", toolName: "fixture_read", content: [text("COMPLETED READ RESULT")], isError: false, timestamp: Date.now() } as any);
    const boundary = source.appendMessage({ ...nativeAssistant, responseId: "resp_completed_turn", content: [text(COMPLETED_ANSWER)] } as any);
    source.appendMessage(user(CALLER_REQUEST) as any);
    source.appendMessage({ ...nativeAssistant, content: [text("INFLIGHT LAUNCH"), call("launch"), call("sibling")] } as any);
    source.appendMessage({ role: "toolResult", toolCallId: "sibling", toolName: "read", content: [text("PARTIAL SIBLING")], isError: false, timestamp: Date.now() } as any);
    const sourceFile = source.getSessionFile()!;
    const before = readFileSync(sourceFile);
    const fork = createFork({ sourceSession: sourceFile, targetCwd: target, sessionDir: join(dir, "forks"), callerRole: { named: true, snapshot: { persona: "worker", text: WORKER, sha256: sha(WORKER) } }, ...(overlay ? { overlay: { persona: "reviewer", text: OVERLAY } } : {}) });
    assert.equal(fork.boundary.entryId, boundary);
    assert.equal(fork.role.source, overlay ? "overlay" : "snapshot");
    assert.notEqual(fork.sessionId, source.getSessionId());
    writeFileSync(roleFile, (fork.role as any).text);
    const launchFile = join(dir, "launch.json");
    writeJson(launchFile, { "fork?": true, "persona-path": roleFile, assignment: ASSIGNMENT, spawns: [], task: CHILD, result: "fixture-current-result", "waiting-policy": "blocking" });
    const generated = spawnSync("bb", ["--config", join(repo, "bb.edn"), "--deps-root", repo, "-e", `(require '[herdr-orch.cli :as cli] '[cheshire.core :as json]) (print (cli/prompt-text (json/parse-string (slurp ${JSON.stringify(launchFile)}) true)))`], { env, cwd: repo, encoding: "utf8", timeout: 30000 });
    assert.equal(generated.status, 0, generated.stderr);
    const prompt = generated.stdout;
    assert.ok(prompt.includes(ASSIGNMENT), "native launcher prompt control");
    const expected = nativeExpectation(seed, overlay, prompt, fork.sessionId);
    writeJson(join(out, "expected.json"), expected);
    writeFileSync(join(out, "prompt.txt"), prompt);
    const extension = join(dir, "native-request.ts");
    writeFileSync(extension, `${NATIVE_TOOL}\nexport default function(pi) {
  registerNativeTool(pi);
  pi.on("session_start", (_e, ctx) => {
    if (process.env.HERDR_ORCH_FAKE_GUARD_LOADED !== "1" || ctx.cwd !== ${JSON.stringify(target)} || ctx.sessionManager.getSessionId() !== ${JSON.stringify(fork.sessionId)}) throw new Error("native child identity/guard mismatch");
  });
  pi.on("before_provider_request", (e) => {
    const p = e.payload;
    const user = p.input.findIndex(i => i.role === "user" && JSON.stringify(i).includes(${JSON.stringify(ASSIGNMENT)}));
    if (user < 0) throw new Error("native current assignment missing before mutation");
    const delta = p.input.findIndex(i => i.role === "developer" && typeof i.content === "string" && i.content.includes('Updated system prompt section "tools"'));
    const mutation = ${JSON.stringify(mutation)};
    if (mutation === "drop") p.input.splice(user, 1);
    if (mutation === "reorder") p.input.unshift(p.input.splice(user, 1)[0]);
    if (mutation === "parent") p.input[user].content[0].text = p.input[user].content[0].text.replace("TASK=${CHILD}", "TASK=${PARENT}");
    if (["late", "wrong-role", "wrong-overlay"].includes(mutation) && delta < 0) throw new Error("native developer delta missing before mutation");
    if (mutation === "late") p.input.push(p.input.splice(delta, 1)[0]);
    if (mutation === "wrong-role") p.input[delta].role = "system";
    if (mutation === "wrong-overlay") p.input[delta].content = p.input[delta].content.replace(${JSON.stringify(OVERLAY)}, ${JSON.stringify(WORKER)});
    const declared = p.input.findIndex(i => i.type === "additional_tools");
    const tools = declared >= 0 ? p.input[declared].tools : p.tools;
    if (["drop-declarations", "change-declaration"].includes(mutation) && !(tools && tools.length)) throw new Error("native declarations missing before mutation");
    if (mutation === "drop-declarations") { if (declared >= 0) p.input.splice(declared, 1); else delete p.tools; }
    if (mutation === "change-declaration") tools[0].description += " CHANGED";
    return p;
  });
}\n`);
    const argv = [...common, "--session", fork.sessionFile, "-e", extension, "--", prompt];
    const run = await runFixturePi(argv, env, target, fork.sessionFile, out);
    assert.equal(fake.requests.length, 1, "native one received request");
    const body = fake.requests[0];
    writeJson(join(out, "request.json"), body);
    writeJson(join(out, "identity.json"), { runtime: runtimeIdentity(), catalogueFile, catalogueSha256: sha(readFileSync(catalogueFile)), model: { id: model.id, provider: model.provider, api: model.api, reasoning: model.reasoning, compat: model.compat, thinkingLevelMap: model.thinkingLevelMap }, fork, argv, ...run, mutation });
    copyFileSync(sourceFile, join(out, "source.jsonl"));
    copyFileSync(fork.sessionFile, join(out, "child.jsonl"));
    copyFileSync(extension, join(out, "native-request.ts"));
    assert.ok(readFileSync(sourceFile).equals(before), "native source bytes unchanged");
    const saved = SessionManager.open(fork.sessionFile).buildSessionContext().messages;
    assert.ok(saved.some((m: any) => m.role === "user" && requestText(m) === prompt), "native saved assignment intact");
    verifyNativeSetup(saved, expected);
    // Setup guards must reject additions even though the actual fixture changed only the intended sections.
    const badSection = structuredClone(saved);
    badSection.findLast((m: any) => m.role === "system").sections.preamble = "UNINTENDED PREAMBLE";
    assert.throws(() => verifyNativeSetup(badSection, expected), /native setup section delta/);
    const badTools = structuredClone(saved);
    badTools.findLast((m: any) => m.role === "system").toolsAdded = [seed.system.toolsAdded[0]];
    assert.throws(() => verifyNativeSetup(badTools, expected), /native setup declaration additions/);
    const badRemoval = structuredClone(saved);
    badRemoval.findLast((m: any) => m.role === "system").toolsRemoved = [{ name: "fixture_read" }];
    assert.throws(() => verifyNativeSetup(badRemoval, expected), /native setup declaration removals/);
    writeJson(join(out, "setup-controls.json"), { unexpectedSections: Object.keys(badSection.findLast((m: any) => m.role === "system").sections), addedDeclarations: 1, removedDeclarations: 1, verdict: "rejected" });
    const error = mutation === "collapsed" ? /native leading instructions/ : ["drop", "reorder", "parent"].includes(mutation) ? /native current assignment/ : mutation === "late" ? /native delta placement/ : ["drop-declarations", "change-declaration"].includes(mutation) ? /native frozen declarations/ : /native developer delta/;
    if (mutation === "none") verifyNativeRequest(body, expected);
    else {
      assert.throws(() => verifyNativeRequest(body, expected), error);
      if (["drop", "reorder", "parent"].includes(mutation)) assert.notEqual(requestText(body.input.filter((i: any) => i.role === "user").at(-1)), prompt, "native assignment mutation applied");
      if (mutation === "late") assert.equal(body.input.at(-1).role, "developer", "native placement mutation applied");
      if (mutation === "wrong-role") assert.ok(body.input.some((i: any) => i.role === "system" && i.content === expected.delta), "native role mutation applied");
      if (mutation === "wrong-overlay") assert.ok(body.input.some((i: any) => i.role === "developer" && typeof i.content === "string" && i.content.includes(WORKER) && !i.content.includes(OVERLAY)), "native overlay mutation applied");
      if (mutation === "drop-declarations") assert.ok(body.tools === undefined && !body.input.some((i: any) => i.type === "additional_tools"), "native declaration drop applied");
      if (mutation === "change-declaration") assert.ok(JSON.stringify([body.tools, body.input]).includes(" CHANGED"), "native declaration change applied");
      if (mutation === "collapsed") assert.ok(body.instructions.includes(OVERLAY) && !body.input.some((i: any) => i.role === "developer"), "native capability-off control applied");
    }
    writeJson(join(out, "verification.json"), { mutation, setupErrors: 0, sectionKeys: Object.keys(expected.sections), declarationAdditions: 0, declarationRemovals: 0, savedCurrentUserIntact: true, verdict: mutation === "none" ? "accepted" : "rejected", expectedFailure: mutation === "none" ? null : error.source });
    return fork.sessionId;
  } catch (error) {
    writeJson(join(out, "failure.json"), { error: String(error) });
    throw error;
  } finally { await fake.close(); }
}

export const tests: TestCase[] = [
  { name: "fork provider boundary: inherited worker and overlay retain the current task; actual request mutations fail", async run() {
    const root = realpathSync(mkdtempSync(join(tmpdir(), "herdr-fork-provider-")));
    try {
      const inherited = await capture(root, false);
      const overlay = await capture(root, true);
      assert.notEqual(inherited.prompt_cache_key, overlay.prompt_cache_key);
      assert.ok(inherited.instructions.includes(WORKER));
      assert.ok(overlay.instructions.includes(OVERLAY));
      // Baselines must pass before any negative control can count as coverage.
      for (const mutation of ["drop", "reorder", "parent"]) await capture(root, false, mutation);
      process.stdout.write(`CAPTURE ${root}-captures\n`);
    } finally { rmSync(root, { recursive: true, force: true }); }
  } },
  { name: "fork provider boundary: native system deltas preserve inherited and overlay task context; actual mutations fail", async run() {
    const root = realpathSync(mkdtempSync(join(tmpdir(), "herdr-fork-native-")));
    try {
      const sessions = [await captureNative(root, false), await captureNative(root, true)];
      for (const mutation of ["drop", "reorder", "parent", "late", "wrong-role", "wrong-overlay", "drop-declarations", "change-declaration", "collapsed"]) sessions.push(await captureNative(root, true, mutation));
      assert.equal(new Set(sessions).size, sessions.length, "native child cache identities are independent");
      process.stdout.write(`NATIVE_CAPTURE ${root}-captures\n`);
    } finally { rmSync(root, { recursive: true, force: true }); }
  } },
];

import assert from "node:assert/strict";
import { createServer } from "node:http";
import { mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { execFileSync } from "node:child_process";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { createHarness, type Harness } from "../test/support/rpc.ts";
const here = dirname(fileURLToPath(import.meta.url)), root = resolve(here, "../../..");
const model = "claude-sonnet-4-5", work = "anthropic-account-work", personal = "anthropic-account-personal";
const isolated = Object.fromEntries(Object.keys(process.env).filter(k => k.startsWith("HERDR_ORCH_") || k === "HC_TERMINAL_ID" || k === "ORCH_ASSIGNMENT_ROOT").map(k => [k, undefined]));
async function command(h: Harness, cmd: any) {
  const id = h.send(cmd); const e = await h.waitFor(e => e.type === "response" && e.id === id, cmd.type); assert.equal(e.success, true, e.error); return e;
}
async function scenario(mode: string) {
  const cwd = mkdtempSync(join(root, ".tmp/recovery-runtime-")), agent = join(cwd, "agent"); mkdirSync(agent);
  const requests: { account: string; model: string; body: any }[] = [];
  const errors: unknown[] = [];
  const server = createServer(async (req, res) => {
    try {
      let raw = ""; for await (const chunk of req) raw += chunk;
      const body = JSON.parse(raw), account = req.headers["x-api-key"] === "fixture-only" ? "fallback" : req.headers.authorization === "Bearer sk-ant-oat01-work" ? "work" : "personal";
      requests.push({ account, model: body.model, body });
      const tool = mode === "tools" && requests.length === 1;
      const seed = mode === "resume" && JSON.stringify(body.messages).includes("seed successful history") && !JSON.stringify(body.messages).includes("resumed request");
      const shouldFail = !seed && (mode === "exhaustion" || (mode === "resume" && account !== "fallback") || (["cross-tier", "direct-escape", "reasoning"].includes(mode) && account !== "fallback") || (account === "work" && !tool));
      if (shouldFail) {
        const status = mode === "auth" ? 401 : mode === "permission" ? 403 : mode === "server" ? 500 : 429;
        res.writeHead(status, { "content-type": "application/json" });
        res.end(JSON.stringify({ type: "error", error: { type: status === 429 ? "rate_limit_error" : "api_error", message: status === 429 ? "quota exhausted" : "fixture denied" } })); return;
      }
      res.writeHead(200, { "content-type": "text/event-stream" });
      const event = (type: string, data: any) => res.write(`event: ${type}\ndata: ${JSON.stringify({ type, ...data })}\n\n`);
      event("message_start", { message: { id: `m${requests.length}`, type: "message", role: "assistant", model: body.model, content: [], stop_reason: null, usage: { input_tokens: 1, output_tokens: 0 } } });
      event("content_block_start", { index: 0, content_block: tool ? { type: "tool_use", id: "preserved-call", name: "Read", input: {} } : { type: "text", text: "" } });
      event("content_block_delta", { index: 0, delta: tool ? { type: "input_json_delta", partial_json: JSON.stringify({ path: join(cwd, "tool.txt") }) } : { type: "text_delta", text: "done" } });
      event("content_block_stop", { index: 0 });
      event("message_delta", { delta: { stop_reason: tool ? "tool_use" : "end_turn", stop_sequence: null }, usage: { output_tokens: 1 } });
      event("message_stop", {}); res.end();
    } catch (e) { errors.push(e); res.writeHead(400); res.end(); }
  });
  await new Promise<void>(r => server.listen(0, "127.0.0.1", r));
  const origin = `http://127.0.0.1:${(server.address() as any).port}`;
  writeFileSync(join(agent, "auth.json"), JSON.stringify(Object.fromEntries([work, personal].map(account => [account, { type: "oauth", access: `sk-ant-oat01-${account === work && !process.env.RECOVERY_TEST_WRONG_ACCOUNT ? "work" : "personal"}`, refresh: "fixture", expires: 4102444800000 }]))));
  writeFileSync(join(agent, "settings.json"), JSON.stringify({ retry: { enabled: false } }));
  writeFileSync(join(agent, "models.json"), JSON.stringify({ providers: { [work]: { baseUrl: origin }, [personal]: { baseUrl: origin } } }));
  writeFileSync(join(cwd, "tool.txt"), "completed tool fixture");
  // Empty provider rows disable quota HTTP in this isolated shared-policy fixture.
  mkdirSync(join(cwd, ".agents/subagents"), { recursive: true });
  writeFileSync(join(cwd, ".agents/subagents/config.edn"), `{:providers {"anthropic" {} "lemonade" {}} :aliases {"fixture-tier" [{:model "anthropic/${model}" :rank 1} {:model "lemonade/fixture-fallback" :rank 2}]}}`);
  const extension = join(cwd, "fixture.ts");
  writeFileSync(extension, `import register from ${JSON.stringify(join(here, "index.ts"))};
export default function(pi) {
pi.registerProvider('lemonade',{apiKey:'fixture-only',api:'anthropic-messages',baseUrl:${JSON.stringify(origin)},models:[{id:'fixture-fallback',name:'fixture fallback',reasoning:false,input:['text'],cost:{input:0,output:0,cacheRead:0,cacheWrite:0},contextWindow:32000,maxTokens:100}]});
register(pi);
let duplicate=false,completed=false;
pi.on('turn_end',e=>{completed=e.message.stopReason==='stop';});
pi.on('agent_before_settle',e=>{if(${Boolean(process.env.RECOVERY_TEST_DUPLICATE_CONTROL)} && completed && !duplicate){duplicate=true;return {entries:[...e.entries,{type:'custom_message',customType:'fixture-duplicate',content:'duplicate negative control',display:false}],continue:true};}});
pi.on('session_start',(_e,ctx)=>{pi.registerCommand('fixture-state',{handler:()=>{ctx.ui.notify(JSON.stringify({branch:ctx.sessionManager.getBranch()}),'info')}});}); }`);
  if (mode === "discovery") {
    mkdirSync(join(agent, "extensions"));
    symlinkSync(here, join(agent, "extensions/herdr-orch"), "dir");
    writeFileSync(extension, "export default function() {}\n");
  }
  const installed = resolve(dirname(realpathSync(execFileSync("which", ["pi"], { encoding: "utf8" }).trim())), "../lib/node_modules/pi-monorepo");
  const h = createHarness(mode === "boundaries" ? join(here, "recovery-boundaries-fixture.ts") : mode === "delayed" ? join(here, "recovery-runtime-fixture.ts") : mode === "resume" ? join(here, "recovery-session-fixture.ts") : extension, mode === "tier-nongit" ? "/" : cwd, { args: [...(mode === "discovery" ? [] : ["--no-extensions"]), "--no-skills", "--no-prompt-templates", "--no-context-files", "--no-approve", "--provider", work, "--model", model, "--thinking", mode === "reasoning" ? "high" : "off", "--tools", "read"],
    env: { ...isolated, HOME: cwd, ORCH_ASSIGNMENT_ROOT: cwd, HERDR_ORCH_BIN: join(root, "skills/herdr-orch/scripts/oh"), HERDR_ENV: "0", PI_CODING_AGENT_DIR: agent,
      NODE_OPTIONS: `--require=${join(here, "account-fake-only.cjs")}`, HERDR_ORCH_FAKE_ORIGIN: origin, RECOVERY_TEST_ROOT: cwd, RECOVERY_LOCKFILE_MODULE: join(installed, "node_modules/proper-lockfile/index.js"), ...(mode === "pinned" ? { HERDR_ORCH_TASK: "fixture-child" } : {}) } });
  try {
    await command(h, { type: "get_state" });
    if (mode === "discovery") {
      const result = await command(h, { type: "get_commands" });
      for (const name of ["oh", "oh-account", "oh-tier"]) assert.equal(result.data.commands.filter((c: any) => c.name === name).length, 1, `native discovery registers ${name} once`);
      assert.deepEqual(requests, []);
      assert.doesNotMatch(h.diagnostics(), /failed to load extension|extension(?:-| )load error|extension error/i);
      console.log("ok - installed Pi startup discovers extension directory and all primary commands without inference");
      return;
    }
    if (["delayed", "resume", "boundaries"].includes(mode)) {
      await command(h, { type: "prompt", message: mode === "delayed" ? "/fixture-delayed" : mode === "boundaries" ? "/fixture-boundaries" : "/fixture-session" });
      const notices = h.events.filter((e: any) => e.type === "extension_ui_request" && e.method === "notify").map((e: any) => e.message);
      for (const notice of notices) console.log(notice);
      assert.ok(notices.some(s => s?.startsWith(mode === "delayed" ? "SUMMARY: 29 installed delayed-selection scenarios passed" : mode === "boundaries" ? "SUMMARY: 5 installed boundary scenarios passed" : "SUMMARY: 3 installed session restore/fork scenarios passed")), "installed delayed fixture must complete every scenario");
      assert.ok(!notices.some(s => s?.startsWith("FAIL:")));
      if (mode === "delayed") assert.match(h.diagnostics(), /stderr=.*Checking recovery account authentication/, "non-UI hosts receive the pending warning on stderr");
      return;
    }
    if (["tier", "tier-nongit", "cross-tier", "reasoning"].includes(mode)) {
      await command(h, { type: "prompt", message: "/oh-tier fixture-tier" });
      // Alias selection resolves to personal first in deterministic named-account order.
      assert.equal((await command(h, { type: "get_state" })).data.model.provider, personal);
      if (mode === "tier" || mode === "tier-nongit") await command(h, { type: "set_model", provider: work, modelId: model });
    }
    const since = h.events.length;
    await command(h, { type: "prompt", message: "fixture request" });
    await h.waitFor(e => e.type === "agent_settled", "recovery settles", { since });
    assert.deepEqual(errors, []);
    const excluded = ["auth", "permission", "server", "pinned"].includes(mode);
    assert.deepEqual(requests.map(r => r.account), excluded ? ["work"] : mode === "cross-tier" ? ["personal", "work", "fallback"] : mode === "reasoning" ? ["personal", "work"] : mode === "tools" ? ["work", "work", "personal"] : ["work", "personal"], "native continuation exactly once per eligible pair");
    const notifications = (h.events as any[]).filter(e => e.type === "extension_ui_request" && e.method === "notify").map(e => e.message);
    if (!excluded) {
      assert.ok(notifications.some(s => s?.includes("Abort stops recovery")), "RPC exposes the recovery warning");
      const ui = (h.events as any[]).filter(e => e.type === "extension_ui_request");
      const statuses = ui.filter(e => e.method === "setStatus" && e.statusKey === "herdr-orch-recovery");
      const widgets = ui.filter(e => e.method === "setWidget" && e.widgetKey === "herdr-orch-recovery");
      assert.match(statuses[0]?.statusText ?? "", /Abort stops recovery/);
      assert.match(widgets[0]?.widgetLines?.join(" ") ?? "", /Avoid changing models/);
      assert.equal(statuses.at(-1)?.statusText, undefined, "RPC clears settled warning status");
      assert.equal(widgets.at(-1)?.widgetLines, undefined, "RPC clears settled warning widget");
      if (["exhaustion", "direct-escape", "reasoning"].includes(mode)) assert.ok(notifications.some(s => s?.includes("recovery exhausted")));
      else assert.equal((await command(h, { type: "get_state" })).data.model.provider, mode === "cross-tier" ? "lemonade" : personal);
      if (mode === "reasoning") assert.equal((await command(h, { type: "get_state" })).data.thinkingLevel, "high");
    }
    if (mode === "tools") {
      const executions = (h.events as any[]).filter(e => e.type === "tool_execution_end");
      assert.equal(executions.length, 1); assert.equal(executions[0].toolCallId, "preserved-call");
      const toolResults = requests.at(-1)!.body.messages.flatMap((m: any) => m.content).filter((c: any) => c.type === "tool_result");
      assert.equal(toolResults.length, 1); assert.equal(toolResults[0].tool_use_id, "preserved-call"); assert.match(JSON.stringify(toolResults[0]), /completed tool fixture/);
    }
    assert.doesNotMatch(JSON.stringify(requests), /Avoid changing models|Recovery selected|Checking recovery account/);
    assert.doesNotMatch(h.diagnostics(), /failed to load extension|extension error/i);
    console.log(`ok - installed RPC recovery ${mode}: ${requests.map(r => r.account).join(' -> ')}`);
  } finally { await h.dispose(); server.closeAllConnections(); await new Promise<void>(r => server.close(() => r())); rmSync(cwd, { recursive: true, force: true }); }
}
async function main() { for (const mode of process.env.RECOVERY_TEST_CWD_ONLY ? ["tier", "tier-nongit"] : process.env.RECOVERY_TEST_DELAYED_ONLY ? ["delayed"] : process.env.RECOVERY_TEST_BOUNDARIES_ONLY ? ["boundaries"] : process.env.RECOVERY_TEST_SESSION_ONLY ? ["resume"] : ["discovery", "normal", "exhaustion", "auth", "permission", "server", "pinned", "tools", "tier", "tier-nongit", "cross-tier", "direct-escape", "reasoning", "delayed", "resume", "boundaries"]) await scenario(mode); console.log("SUMMARY: installed RPC recovery suite passed"); }
main().catch(e => { console.error(e); process.exitCode = 1; });

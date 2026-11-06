#!/usr/bin/env tsx
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { createHarness, type Harness } from "../test/support/rpc.ts";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "../../..");
const alias = "anthropic-account-work";
const model = "claude-sonnet-4-5";
const isolatedOrchEnv = Object.fromEntries(Object.keys(process.env)
  .filter((key) => key === "ORCH_ASSIGNMENT_ROOT" || key.startsWith("HERDR_ORCH_"))
  .map((key) => [key, undefined]));
const oauth = (name: string, expires = 0) => ({ type: "oauth", access: `sk-ant-oat01-${name}`, refresh: name, expires });
const preserved = { anthropic: oauth("default", 4102444800000), unrelated: { type: "api_key", key: "fixture-unrelated" } };

async function command(h: Harness, cmd: Record<string, unknown>) {
  const since = h.events.length;
  const id = h.send(cmd);
  const result = await h.waitFor((e) => e.type === "response" && e.id === id, String(cmd.type), { since });
  assert.equal(result.success, true, result.error);
  return result;
}

async function bomStartup() {
  mkdirSync(join(root, ".tmp"), { recursive: true });
  const cwd = mkdtempSync(join(root, ".tmp/account-bom-"));
  const agent = join(cwd, "agent");
  mkdirSync(agent);
  writeFileSync(join(agent, "auth.json"), "\uFEFF" + JSON.stringify({ anthropic: preserved.anthropic }));
  const h = createHarness(join(here, "index.ts"), cwd, {
    args: ["--no-extensions", "--no-skills", "--no-prompt-templates", "--no-context-files", "--provider", "anthropic", "--model", model],
    env: { ...isolatedOrchEnv, PI_CODING_AGENT_DIR: agent, HERDR_ENV: "0", HC_TERMINAL_ID: undefined, NODE_OPTIONS: `--require=${join(here, "account-fake-only.cjs")}` },
  });
  try {
    const response = await command(h, { type: "get_commands" });
    assert.ok(response.data.commands.some((c: any) => c.name === "oh"));
    assert.ok(response.data.commands.some((c: any) => c.name === "oh-account"));
    assert.doesNotMatch(h.diagnostics(), /failed to load extension/i);
    console.log("ok - installed Pi starts with a BOM-prefixed default auth store");
  } finally {
    await h.dispose();
    rmSync(cwd, { recursive: true, force: true });
  }
}

async function main() {
  await bomStartup();
  mkdirSync(join(root, ".tmp"), { recursive: true });
  const cwd = mkdtempSync(join(root, ".tmp/account-runtime-"));
  const requests: Array<{ headers: any; body: any }> = [];
  let refreshes = 0;
  const errors: unknown[] = [];
  const server = createServer(async (req, res) => {
    try {
      let raw = "";
      for await (const chunk of req) raw += chunk;
      const body = JSON.parse(raw);
      if (req.url === "/oauth") {
        assert.equal(body.grant_type, "refresh_token");
        assert.equal(body.refresh_token, "work");
        refreshes++;
        res.setHeader("Content-Type", "application/json");
        res.end(JSON.stringify({ access_token: "sk-ant-oat01-work-refreshed", refresh_token: "work-rotated", expires_in: 3600 }));
        return;
      }
      assert.equal(req.url, "/v1/messages?beta=true");
      assert.ok(req.headers.authorization === "Bearer sk-ant-oat01-work-refreshed", "wrong-account request");
      assert.equal(req.headers["x-api-key"], undefined);
      assert.match(String(req.headers["anthropic-beta"]), /oauth-2025-04-20/);
      assert.match(JSON.stringify(body.system), /Claude Code/);
      assert.equal(body.model, model);
      requests.push({ headers: req.headers, body });
      const first = requests.length === 1;
      if (first) assert.ok(body.tools.some((t: any) => t.name === "Read"), "OAuth canonical tool name");
      else {
        const result = body.messages.flatMap((m: any) => m.content).find((c: any) => c.type === "tool_result");
        assert.equal(result.tool_use_id, "call_fixture");
        assert.match(JSON.stringify(result.content), /fixture tool content/);
      }
      res.setHeader("Content-Type", "text/event-stream");
      const event = (type: string, data: any) => res.write(`event: ${type}\ndata: ${JSON.stringify({ type, ...data })}\n\n`);
      event("message_start", { message: { id: `msg_${requests.length}`, type: "message", role: "assistant", model, content: [], stop_reason: null, usage: { input_tokens: 10, output_tokens: 0 } } });
      if (first) {
        event("content_block_start", { index: 0, content_block: { type: "tool_use", id: "call_fixture", name: "Read", input: {} } });
        event("content_block_delta", { index: 0, delta: { type: "input_json_delta", partial_json: JSON.stringify({ path: join(cwd, "tool.txt") }) } });
      } else {
        event("content_block_start", { index: 0, content_block: { type: "text", text: "" } });
        event("content_block_delta", { index: 0, delta: { type: "text_delta", text: "fixture finished" } });
      }
      event("content_block_stop", { index: 0 });
      event("message_delta", { delta: { stop_reason: first ? "tool_use" : "end_turn", stop_sequence: null }, usage: { output_tokens: 5 } });
      event("message_stop", {});
      res.end();
    } catch (error) {
      errors.push(error);
      res.writeHead(400, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ type: "error", error: { type: "invalid_request_error", message: "fixture assertion failed" } }));
    }
  });
  server.requestTimeout = 5000;
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
  const origin = `http://127.0.0.1:${(server.address() as any).port}`;
  const agent = join(cwd, "agent");
  mkdirSync(agent);
  writeFileSync(join(agent, "auth.json"), JSON.stringify({ ...preserved, [alias]: oauth(process.env.ACCOUNT_TEST_WRONG_ACCOUNT ? "default" : "work", process.env.ACCOUNT_TEST_WRONG_ACCOUNT ? 4102444800000 : 0), "anthropic-account-personal": oauth("personal", 4102444800000) }));
  writeFileSync(join(agent, "models.json"), JSON.stringify({ providers: { [alias]: { baseUrl: origin } } }));
  writeFileSync(join(cwd, "tool.txt"), "fixture tool content\n");
  const extension = join(cwd, "fixture.ts");
  writeFileSync(extension, process.env.ACCOUNT_TEST_GATE_ONLY
    ? `import { accountProvider } from ${JSON.stringify(join(here, "accounts.ts"))};\nexport default (pi) => { globalThis.installAccountTestRedirect(); pi.registerProvider(accountProvider(${JSON.stringify(alias)})); };\n`
    : `import register from ${JSON.stringify(join(here, "index.ts"))};\nexport default (pi) => { globalThis.installAccountTestRedirect(); register(pi); };\n`);
  const h = createHarness(extension, cwd, {
    args: ["--no-extensions", "--no-skills", "--no-prompt-templates", "--no-context-files", "--no-approve", "--tools", "read", "--provider", alias, "--model", model, "--thinking", "off"],
    env: { ...isolatedOrchEnv, PI_CODING_AGENT_DIR: agent, HERDR_ENV: "0", HC_TERMINAL_ID: undefined, ANTHROPIC_API_KEY: "fixture-paid-key-must-not-be-used", NODE_OPTIONS: `--require=${join(here, "account-fake-only.cjs")}`, HERDR_ORCH_FAKE_ORIGIN: origin },
  });
  let sibling: Harness | undefined;
  try {
    await command(h, { type: "get_state" });
    if (!process.env.ACCOUNT_TEST_GATE_ONLY) {
      sibling = createHarness(extension, cwd, {
        args: ["--no-extensions", "--no-skills", "--no-prompt-templates", "--no-context-files", "--no-approve", "--provider", "anthropic-account-personal", "--model", model],
        env: { ...isolatedOrchEnv, PI_CODING_AGENT_DIR: agent, HERDR_ENV: "0", HC_TERMINAL_ID: undefined, NODE_OPTIONS: `--require=${join(here, "account-fake-only.cjs")}`, HERDR_ORCH_FAKE_ORIGIN: origin },
      });
      await command(sibling, { type: "get_state" });
      await command(h, { type: "set_model", provider: "anthropic", modelId: model });
      await command(h, { type: "set_model", provider: alias, modelId: model });
      assert.equal((await command(sibling, { type: "get_state" })).data.model.provider, "anthropic-account-personal");
      assert.equal((await command(h, { type: "get_state" })).data.model.provider, alias);
      console.log("ok - two installed Pi primary sessions retain independent account selections outside Herdr");
      const since = h.events.length;
      await command(h, { type: "prompt", message: "/oh-account add new-account" });
      await h.waitFor((e) => e.type === "extension_ui_request" && e.message?.includes("Registered anthropic-account-new-account"), "account registration notice", { since });
      const models = await command(h, { type: "get_available_models" });
      assert.ok(!models.data.models.some((m: any) => m.provider === "anthropic-account-new-account"));
      assert.equal(JSON.parse(readFileSync(join(agent, "auth.json"), "utf8"))["anthropic-account-new-account"], undefined);
      console.log("ok - installed extension adds an OAuth-only named login target without authenticating or changing global credentials");
    }
    const since = h.events.length;
    await command(h, { type: "prompt", message: "Read the fixture file" });
    await h.waitFor((e) => e.type === "agent_settled", "OAuth tool round-trip", { since });
    assert.deepEqual(errors, []);
    assert.equal(requests.length, 2, JSON.stringify((h.events as any[]).filter((e) => e.type === "message_end").map((e) => ({ role: e.message.role, error: e.message.errorMessage }))));
    assert.equal(refreshes, 1);
    const tools = (h.events as any[]).filter((e) => e.type === "tool_execution_end");
    assert.equal(tools.length, 1);
    assert.equal(tools[0].toolName, "read");
    assert.equal(tools[0].isError, false);
    const assistants = (h.events as any[]).filter((e) => e.type === "message_end" && e.message.role === "assistant");
    assert.equal(assistants.length, 2);
    assert.ok(assistants.every((e) => e.message.provider === alias));
    assert.ok(assistants[1].message.content.some((c: any) => c.text === "fixture finished"));
    const auth = JSON.parse(readFileSync(join(agent, "auth.json"), "utf8"));
    assert.deepEqual(auth.anthropic, preserved.anthropic);
    assert.deepEqual(auth.unrelated, preserved.unrelated);
    assert.equal(auth[alias].refresh, "work-rotated");
    assert.doesNotMatch(h.diagnostics(), /extension(?:-| )load error|failed to load extension/i);
    console.log("ok - installed Pi alias uses native OAuth refresh, subscription headers/system shaping, canonical tool names and streamed tool results without default credential replacement");
  } finally {
    await h.dispose();
    await sibling?.dispose();
    server.closeAllConnections();
    await new Promise<void>((r) => server.close(() => r()));
    rmSync(cwd, { recursive: true, force: true });
  }
}
main().catch((e) => { console.error(e); process.exitCode = 1; });

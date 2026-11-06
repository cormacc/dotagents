#!/usr/bin/env tsx
import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { ModelRuntime } from "@earendil-works/pi-coding-agent";
import { anthropicProvider } from "@earendil-works/pi-ai/providers/anthropic";
import register from "./index.ts";
import { accountId, accountProvider, storedAccountIds } from "./accounts.ts";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../../..");
mkdirSync(join(root, ".tmp"), { recursive: true });
const cwd = mkdtempSync(join(root, ".tmp/account-transactions-"));
const authPath = join(cwd, "auth.json");
const work = "anthropic-account-work";
const personal = "anthropic-account-personal";
const credential = (name: string, expires = 0) => ({ type: "oauth" as const, access: `sk-ant-oat01-${name}`, refresh: name, expires });
const preserved = { anthropic: credential("default", 4102444800000), unrelated: { type: "api_key", key: "fixture" } };
const read = () => JSON.parse(readFileSync(authPath, "utf8"));
const save = (data: unknown) => writeFileSync(authPath, JSON.stringify(data));
const oldDir = process.env.PI_CODING_AGENT_DIR;
const oldKey = process.env.ANTHROPIC_API_KEY;
const oldFetch = globalThis.fetch;
try {
  process.env.PI_CODING_AGENT_DIR = cwd;
  process.env.ANTHROPIC_API_KEY = "fixture-paid-key";
  save(preserved);
  const commands = new Map<string, any>();
  const providers: any[] = [];
  register({ on() {}, registerCommand: (n: string, c: any) => commands.set(n, c), registerProvider: (p: any) => providers.push(p), registerTool() {}, registerEntryRenderer() {} } as any);
  assert.ok(commands.has("oh-account"), "extension registers account onboarding");
  const notices: string[] = [];
  const ctx = { hasUI: true, ui: { notify: (s: string) => notices.push(s) } };
  await commands.get("oh-account").handler("add work", ctx);
  assert.equal(providers.length, 1);
  assert.equal(providers[0].id, work);
  assert.equal(providers[0].name, "work");
  assert.equal(providers[0].auth.apiKey, undefined);
  assert.deepEqual(providers[0].getModels(), anthropicProvider().getModels().map((m) => ({ ...m, provider: work })), "alias retains native model metadata");
  assert.match(notices.at(-1)!, /\/login/);
  assert.deepEqual(read(), preserved, "registration does not swap or write credentials");
  await commands.get("oh-account").handler("add work", ctx);
  assert.equal(providers.length, 1);
  for (const name of ["", "Work", "../work", "two words", "-work", "a".repeat(33)]) assert.throws(() => accountId(name));
  await commands.get("oh-account").handler("add ../work", ctx);
  assert.equal(providers.length, 1);
  save({ ...preserved, [personal]: credential("personal"), [work]: credential("work"), "other-account-work": {}, "anthropic-account-Bad": {} });
  assert.deepEqual(storedAccountIds(authPath), [personal, work]);
  writeFileSync(authPath, "\uFEFF" + readFileSync(authPath, "utf8"));
  assert.deepEqual(storedAccountIds(authPath), [personal, work], "discovers accounts in a BOM-prefixed store");
  assert.deepEqual(storedAccountIds(join(cwd, "absent.json")), []);
  save([]);
  assert.throws(() => storedAccountIds(authPath), /Invalid Pi auth.json/);
  writeFileSync(authPath, "invalid json");
  assert.throws(() => storedAccountIds(authPath), /Cannot read Pi auth.json/);
  console.log("ok - extension onboarding validates names, discovers only supported identities and never writes credentials");

  save(preserved);
  const runtime = await ModelRuntime.create({ authPath, modelsPath: null, refreshOnCreate: false });
  runtime.registerNativeProvider(accountProvider(work));
  const body: any[] = [];
  globalThis.fetch = async (input, init) => {
    assert.equal(String(input), "https://platform.claude.com/v1/oauth/token");
    body.push(JSON.parse(String(init?.body)));
    return new Response(JSON.stringify({ access_token: "sk-ant-oat01-work", refresh_token: "work", expires_in: 3600 }));
  };
  const cancelled = new AbortController();
  cancelled.abort();
  await assert.rejects(runtime.login(work, "oauth", { signal: cancelled.signal, prompt: async () => "unused", notify() {} }), { name: "AbortError" });
  assert.equal(read()[work], undefined);
  await runtime.login(work, "oauth", {
    prompt: async (p) => p.type === "select" ? "copy_code" : "fixture-code",
    notify() {},
  });
  assert.equal(body[0].grant_type, "authorization_code");
  assert.deepEqual(read().anthropic, preserved.anthropic);
  assert.deepEqual(read().unrelated, preserved.unrelated);
  assert.equal(read()[work].type, "oauth");
  assert.equal((await runtime.getAuth(work))?.auth.apiKey, "sk-ant-oat01-work");
  console.log("ok - native OAuth login persists only the alias credential in the existing Pi store");

  let release!: () => void;
  let entered!: () => void;
  const started = new Promise<void>((r) => { entered = r; });
  const pending = new Promise<void>((r) => { release = r; });
  save({ ...preserved, [work]: credential("work"), [personal]: credential("personal") });
  const second = await ModelRuntime.create({ authPath, modelsPath: null, refreshOnCreate: false });
  globalThis.fetch = async (input, init) => {
    assert.equal(String(input), "https://platform.claude.com/v1/oauth/token");
    const token = JSON.parse(String(init?.body)).refresh_token;
    if (token === "work") { entered(); await pending; }
    assert.ok(token === "work" || token === "personal");
    return new Response(JSON.stringify({ access_token: `sk-ant-oat01-${token}-new`, refresh_token: `${token}-new`, expires_in: 3600 }));
  };
  second.registerNativeProvider(accountProvider(work));
  second.registerNativeProvider(accountProvider(personal));
  const controller = new AbortController();
  const refreshing = runtime.getAuth(work, { signal: controller.signal });
  await started;
  const otherRefresh = second.getAuth(personal);
  const removing = second.logout(work);
  controller.abort();
  await assert.rejects(refreshing, { name: "AbortError" });
  release();
  await Promise.all([otherRefresh, removing]);
  assert.equal(read()[work], undefined, "logout serializes after a late refresh publication");
  assert.equal(read()[personal].refresh, "personal-new");
  assert.deepEqual(read().anthropic, preserved.anthropic);
  assert.deepEqual(read().unrelated, preserved.unrelated);
  assert.equal(await second.getAuth(work), undefined, "removed account cannot inherit ambient key");
  console.log("ok - concurrent refresh and cancelled late refresh/logout preserve other accounts and cannot resurrect removal");

  // A registered alias with no stored OAuth credential must be unavailable, even with an ambient key.
  await second.logout(personal);
  assert.equal(await second.getAuth(personal), undefined);
  assert.equal(await second.checkAuth(personal), undefined);
  save({ ...preserved, [personal]: { type: "api_key", key: "fixture-stored-paid" } });
  assert.equal(await second.getAuth(personal), undefined);
  save({ ...preserved, [personal]: credential("expired") });
  globalThis.fetch = async () => { throw new Error("fixture refresh refused"); };
  await assert.rejects(second.getAuth(personal), /OAuth refresh failed/);
  assert.equal(read()[personal].refresh, "expired");
  console.log("ok - absent, removed, wrong-type and expired aliases cannot use ambient or stored API keys");
  console.log("# all account onboarding and native credential transaction checks passed");
} finally {
  if (oldDir === undefined) delete process.env.PI_CODING_AGENT_DIR; else process.env.PI_CODING_AGENT_DIR = oldDir;
  if (oldKey === undefined) delete process.env.ANTHROPIC_API_KEY; else process.env.ANTHROPIC_API_KEY = oldKey;
  globalThis.fetch = oldFetch;
  rmSync(cwd, { recursive: true, force: true });
}

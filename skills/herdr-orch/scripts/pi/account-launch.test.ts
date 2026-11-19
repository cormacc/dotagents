import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { createServer } from "node:http";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { SessionManager } from "@earendil-works/pi-coding-agent";
import { createFork } from "./fork-adapter.ts";
import { startFakeAnthropic } from "./fake-anthropic.ts";
import type { TestCase } from "./runtime.ts";

const here = dirname(fileURLToPath(import.meta.url));
const repo = realpathSync(join(here, "../../../.."));
const account = "anthropic-2";
const model = "claude-sonnet-4-5";
async function run(args: string[], env: NodeJS.ProcessEnv, cwd: string) {
  const child = spawn(process.execPath, [process.argv[1], ...args], { cwd, env, stdio: ["ignore", "pipe", "pipe"] });
  let stdout = "", stderr = "";
  child.stdout.on("data", d => stdout += d);
  child.stderr.on("data", d => stderr += d);
  const timer = setTimeout(() => child.kill("SIGKILL"), 30000);
  try {
    const code = await new Promise<number | null>((resolve, reject) => { child.once("close", resolve); child.once("error", reject); });
    assert.equal(code, 0, stderr + stdout);
    assert.doesNotMatch(stderr, /failed to load|extension.*error/i);
    return { stdout, stderr };
  } finally {
    clearTimeout(timer);
    if (child.exitCode === null && child.signalCode === null) child.kill("SIGKILL");
  }
}
function verify(headers: any[], expected: string) {
  assert.equal(headers.length, 1);
  assert.equal(headers[0].authorization, `Bearer sk-ant-oat01-${expected}`, "wrong-account launch");
  assert.equal(headers[0]["x-api-key"], undefined);
}

export const tests: TestCase[] = [{ name: "installed fresh/fork/measurement loaders resolve native named accounts before CLI selection and send the chosen credential", async run() {
  const root = mkdtempSync(join(repo, ".tmp/account-launch-"));
  try {
    for (const layout of ["repository", "package"]) for (const mode of ["fresh", "fork", "measurement"]) {
      const dir = join(root, layout, mode), agent = join(dir, "agent");
      mkdirSync(agent, { recursive: true });
      const adapters = layout === "repository" ? here : join(dir, "bare/skills/herdr-orch/scripts/pi");
      if (layout === "package") {
        mkdirSync(adapters, { recursive: true });
        for (const file of ["account-loader.ts", "account-registration.ts", "fork-adapter.ts", "measurement-adapter.ts", "measurement-audit.ts", "runtime.ts"]) {
          copyFileSync(join(here, file), join(adapters, file));
        }
      }
      const fake = await startFakeAnthropic([{ text: "done", model }]);
      try {
        const auth = Object.fromEntries([["anthropic", "default"], [account, "work"]].map(([id, name]) => [id, { type: "oauth", access: `sk-ant-oat01-${name}`, refresh: `fixture-${name}`, expires: 4102444800000 }]));
        writeFileSync(join(agent, "auth.json"), JSON.stringify(auth));
        const marker = join(dir, "unrelated-loaded");
        const unrelated = join(dir, "unrelated-package");
        mkdirSync(unrelated);
        writeFileSync(join(unrelated, "package.json"), JSON.stringify({ pi: { extensions: ["index.ts"] } }));
        writeFileSync(join(unrelated, "index.ts"), `import { writeFileSync } from "node:fs"; export default () => writeFileSync(${JSON.stringify(marker)}, "loaded");`);
        const settings = { retry: { enabled: false }, compaction: { enabled: false }, ...(layout === "package" ? {
          packages: [{ source: repo, extensions: ["pi/extensions/herdr-orch/index.ts"], skills: [], prompts: [], themes: [] }, unrelated],
        } : {}) };
        writeFileSync(join(agent, "settings.json"), JSON.stringify(settings));
        writeFileSync(join(agent, "models.json"), JSON.stringify({ providers: { anthropic: { baseUrl: fake.url }, [account]: { baseUrl: fake.url } } }));
        const env = { PATH: process.env.PATH, HOME: dir, TMPDIR: root, PI_CODING_AGENT_DIR: agent, PI_OFFLINE: "1", ANTHROPIC_API_KEY: "fixture-paid-key-forbidden", HERDR_ORCH_FAKE_ORIGIN: fake.url, NODE_OPTIONS: `--require=${join(here, "fake-only.cjs")}` };
        if (layout === "package") {
          // Independent normal-startup control: no adapter registration can mask broken package discovery.
          const control = join(dir, "control.ts"), result = join(dir, "control.json");
          writeFileSync(control, `import { writeFileSync } from "node:fs"; export default pi => pi.on("session_start", (_, ctx) => { writeFileSync(${JSON.stringify(result)}, JSON.stringify(ctx.modelRegistry.getAvailable().map(m => m.provider + "/" + m.id))); process.exit(0); });`);
          await run(["--offline", "--no-session", "-e", control, "--no-skills", "--no-prompt-templates", "--no-context-files", "--no-mcp", "-p", "x"], env, dir);
          assert.ok(JSON.parse(readFileSync(result, "utf8")).includes(`${account}/${model}`), "normal package startup exposes the named account");
          assert.equal(readFileSync(marker, "utf8"), "loaded", "normal startup loads the unrelated extension control");
          rmSync(marker);
          assert.equal(fake.requests.length, 0, "normal-startup control performs no inference");
          process.stdout.write(`ACCOUNT_PACKAGE_CONTROL ${mode} normal startup resolves ${account}/${model}; unrelated package factory ran; no inference\n`);
        }
        const common = ["--offline", "-ne", "--no-skills", "--no-prompt-templates", "--no-context-files", "--no-mcp", "--no-tools", "--model", `${account}/${model}`, "--thinking", "off", "--mode", "json", "-p"];
        let extra: string[], prompt = "fixture assignment";
        if (mode === "fresh") extra = ["--no-session", "-e", join(adapters, "account-loader.ts")];
        else if (mode === "fork") {
          const source = SessionManager.create(dir, join(dir, "source"));
          source.appendMessage({ role: "user", content: "completed fixture", timestamp: Date.now() } as any);
          const assistant = (content: any[], stopReason: string) => ({ role: "assistant", api: "anthropic-messages", provider: "anthropic", model, content, stopReason, timestamp: Date.now(), usage: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, totalTokens: 0, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } } });
          source.appendMessage(assistant([{ type: "text", text: "completed answer" }], "stop") as any);
          source.appendMessage({ role: "user", content: "launch turn", timestamp: Date.now() } as any);
          source.appendMessage(assistant([{ type: "toolCall", id: "launch", name: "bash", arguments: {} }], "toolUse") as any);
          const fork = createFork({ sourceSession: source.getSessionFile()!, targetCwd: dir, sessionDir: join(dir, "forks") });
          extra = ["--session", fork.sessionFile, "-e", join(adapters, "fork-adapter.ts")];
        } else {
          writeFileSync(join(dir, "assignment.txt"), prompt);
          writeFileSync(join(dir, "manifest.json"), JSON.stringify({ treatment: "Fixture treatment", assignmentFile: join(dir, "assignment.txt"), tools: [], evidenceDir: join(dir, "evidence") }));
          Object.assign(env, { HERDR_ORCH_MEASUREMENT_MANIFEST: join(dir, "manifest.json") });
          prompt = "herdr-orch-measurement-input";
          extra = ["--no-session", "-e", join(adapters, "measurement-adapter.ts")];
        }
        await run([...common, ...extra, "--", prompt], env, dir);
        verify(fake.headers, "work");
        assert.throws(() => verify(fake.headers, "default"), /wrong-account launch/, "wrong-account negative control");
        assert.equal(fake.requests[0].model, model);
        assert.deepEqual(JSON.parse(readFileSync(join(agent, "auth.json"), "utf8")), auth);
        if (mode === "measurement") {
          assert.equal(JSON.parse(readFileSync(join(dir, "evidence/terminal.json"), "utf8")).success, true);
          assert.equal(JSON.parse(readFileSync(join(dir, "evidence/expectation.json"), "utf8")).provider, account);
        }
        // Offline inventory uses the same loader, with no inference or credential refresh.
        const result = join(dir, "inventory.json");
        await run(["--offline", "--no-session", "-ne", "-e", join(adapters, "account-loader.ts"), "--no-skills", "--no-prompt-templates", "--no-context-files", "--no-mcp", "-p", "x"], { ...env, HERDR_ORCH_ADAPTER: "model-inventory", HERDR_ORCH_ADAPTER_RESULT: result }, dir);
        const inventory = JSON.parse(readFileSync(result, "utf8"));
        assert.equal(inventory.ok, true);
        assert.ok(inventory.inventory.some((row: any) => row.account === account && row.canonical === `anthropic/${model}` && row.reasoning.includes("high")));
        assert.equal(fake.requests.length, 1);
        if (layout === "package" && mode === "fresh") {
          writeFileSync(join(agent, "settings.json"), JSON.stringify({ ...settings, packages: [{ source: repo, extensions: [], skills: [], prompts: [], themes: [] }, unrelated, "git:github.com/fixture/missing-package"] }));
          const disabledResult = join(dir, "disabled-inventory.json");
          await run(["--offline", "--no-session", "-ne", "-e", join(adapters, "account-loader.ts"), "--no-skills", "--no-prompt-templates", "--no-context-files", "--no-mcp", "-p", "x"], { ...env, HERDR_ORCH_ADAPTER: "model-inventory", HERDR_ORCH_ADAPTER_RESULT: disabledResult }, dir);
          const disabled = JSON.parse(readFileSync(disabledResult, "utf8"));
          assert.equal(disabled.ok, true);
          assert.ok(disabled.inventory.some((row: any) => row.account === "anthropic"));
          assert.ok(!disabled.inventory.some((row: any) => row.account === account), "disabled package registration is not imported");
          await assert.rejects(() => run([...common, ...extra, "--", prompt], env, dir), /not found|unknown|No models|No model/i);
          assert.equal(fake.requests.length, 1, "disabled or missing packages cannot reach inference");
          writeFileSync(join(agent, "settings.json"), JSON.stringify(settings));
        }
        if (layout === "repository" && mode === "fresh") {
          const bare = join(dir, "bare/skills/herdr-orch/scripts/pi");
          mkdirSync(bare, { recursive: true });
          for (const file of ["account-loader.ts", "account-registration.ts"]) copyFileSync(join(here, file), join(bare, file));
          const inventoryArgs = ["--offline", "--no-session", "-ne", "-e", join(bare, "account-loader.ts"), "--no-skills", "--no-prompt-templates", "--no-context-files", "--no-mcp", "-p", "x"];
          const inventoryEnv = { ...env, HERDR_ORCH_ADAPTER: "model-inventory", HERDR_ORCH_ADAPTER_RESULT: result };
          await run(inventoryArgs, inventoryEnv, dir);
          const baseOnly = JSON.parse(readFileSync(result, "utf8")).inventory;
          assert.ok(baseOnly.some((row: any) => row.account === "anthropic" && row.canonical === `anthropic/${model}`));
          assert.ok(!baseOnly.some((row: any) => row.account === account), "an unavailable registration module cannot invent a named provider");
          const installed = join(agent, "extensions/herdr-orch");
          mkdirSync(installed, { recursive: true });
          writeFileSync(join(installed, "accounts.ts"), `export { registerStoredAccounts } from ${JSON.stringify(join(repo, "pi/extensions/herdr-orch/accounts.ts"))};`);
          await run(inventoryArgs, inventoryEnv, dir);
          assert.ok(JSON.parse(readFileSync(result, "utf8")).inventory.some((row: any) => row.account === account && row.canonical === `anthropic/${model}`), "bare skill reuses the installed extension registration");
        }
        // Suppress only registration, leaving the loader's other hooks unchanged. This
        // reproduces the pre-integration model-resolution failure in the installed CLI.
        const adapter = join(adapters, mode === "fresh" ? "account-loader.ts" : `${mode === "fork" ? "fork" : "measurement"}-adapter.ts`);
        const noRegistration = join(dir, "no-registration.ts");
        writeFileSync(noRegistration, `import load from ${JSON.stringify(adapter)}; export default (pi) => load({ ...pi, registerProvider: () => {} });`);
        const badExtra = extra.map(arg => arg === adapter ? noRegistration : arg);
        await assert.rejects(() => run([...common, ...badExtra, "--", prompt], env, dir), /not found|unknown|No models|No model/i);
        assert.equal(fake.requests.length, 1, "unregistered account never reaches inference");
        // Launch an actual wrong account, then prove the credential oracle rejects it.
        const wrong = [...common];
        wrong[wrong.indexOf("--model") + 1] = `anthropic/${model}`;
        await run([...wrong, ...extra, "--", prompt], env, dir);
        assert.equal(fake.requests.length, 2, "wrong-account mutation reached the fake upstream");
        assert.throws(() => verify(fake.headers.slice(1), "work"), /wrong-account launch/);
        assert.equal(existsSync(marker), false, "isolated adapters never activate unrelated configured extensions");
        process.stdout.write(`ACCOUNT_LAUNCH ${layout}/${mode} native OAuth account verified; absent registration and actual wrong-account launch rejected\n`);
      } finally { await fake.close(); }
    }
  } finally { rmSync(root, { recursive: true, force: true }); }
} }, { name: "offline inventory refreshes each expired OAuth account through Pi auth storage and omits only a failed refresh", async run() {
  const root = mkdtempSync(join(repo, ".tmp/account-refresh-"));
  const bodies: any[] = [];
  // Token fixture: only the work refresh token is valid; any other refresh is refused.
  const server = createServer((req, res) => {
    let body = "";
    req.on("data", d => body += d);
    req.on("end", () => {
      const parsed = JSON.parse(body);
      bodies.push(parsed);
      if (parsed.grant_type === "refresh_token" && parsed.refresh_token === "fixture-refresh-work") {
        res.writeHead(200, { "content-type": "application/json" });
        res.end(JSON.stringify({ access_token: "sk-ant-oat01-refreshed", refresh_token: "fixture-refresh-work-2", expires_in: 3600 }));
      } else {
        res.writeHead(400, { "content-type": "application/json" });
        res.end(JSON.stringify({ error: "invalid_grant" }));
      }
    });
  });
  await new Promise<void>(resolve => server.listen(0, "127.0.0.1", resolve));
  try {
    const origin = `http://127.0.0.1:${(server.address() as { port: number }).port}`;
    const agent = join(root, "agent");
    mkdirSync(agent, { recursive: true });
    const oauth = (access: string, refresh: string, expires: number) => ({ type: "oauth", access: `sk-ant-oat01-${access}`, refresh, expires });
    const auth = {
      anthropic: oauth("default", "fixture-refresh-default", 4102444800000),
      "anthropic-1": oauth("work-expired", "fixture-refresh-work", 1),
      "anthropic-2": oauth("revoked-expired", "fixture-refresh-revoked", 1),
    };
    const authPath = join(agent, "auth.json");
    writeFileSync(authPath, JSON.stringify(auth));
    writeFileSync(join(agent, "settings.json"), JSON.stringify({ retry: { enabled: false }, compaction: { enabled: false } }));
    const result = join(root, "inventory.json");
    // Pi installs its own global fetch at startup, after any --require preload, so the token
    // endpoint redirect wraps it from an extension factory that then runs the real loader.
    const redirected = join(root, "redirected-loader.ts");
    writeFileSync(redirected, `import load from ${JSON.stringify(join(here, "account-loader.ts"))};
export default (pi) => {
  const fetch = globalThis.fetch;
  globalThis.fetch = (input, init) => fetch(String(input?.url ?? input) === "https://platform.claude.com/v1/oauth/token" ? ${JSON.stringify(`${origin}/v1/oauth/token`)} : input, init);
  return load(pi);
};
`);
    const env = { PATH: process.env.PATH, HOME: root, TMPDIR: root, PI_CODING_AGENT_DIR: agent, PI_OFFLINE: "1",
      HERDR_ORCH_FAKE_ORIGIN: origin, NODE_OPTIONS: `--require=${join(here, "fake-only.cjs")}`,
      HERDR_ORCH_ADAPTER: "model-inventory", HERDR_ORCH_ADAPTER_RESULT: result };
    await run(["--offline", "--no-session", "-ne", "-e", redirected, "--no-skills", "--no-prompt-templates", "--no-context-files", "--no-mcp", "-p", "x"], env, root);
    const stored = JSON.parse(readFileSync(authPath, "utf8"));
    assert.equal(stored["anthropic-1"].access, "sk-ant-oat01-refreshed", "expired account with a valid refresh token is refreshed");
    assert.equal(stored["anthropic-1"].refresh, "fixture-refresh-work-2");
    assert.ok(stored["anthropic-1"].expires > Date.now(), "refreshed expiry is in the future");
    assert.deepEqual(stored["anthropic-2"], auth["anthropic-2"], "a failed refresh leaves the credential unchanged");
    assert.deepEqual(stored.anthropic, auth.anthropic, "a valid credential is not refreshed");
    assert.ok(!bodies.some(b => b.refresh_token === "fixture-refresh-default"), "no refresh request for a valid credential");
    assert.ok(bodies.some(b => b.refresh_token === "fixture-refresh-revoked"), "the revoked account was attempted (positive control)");
    const text = readFileSync(result, "utf8");
    const inventory = JSON.parse(text);
    assert.equal(inventory.ok, true);
    assert.ok(inventory.inventory.some((row: any) => row.account === "anthropic-1"));
    for (const secret of ["sk-ant-oat01", "fixture-refresh"]) assert.ok(!text.includes(secret), `inventory carries no ${secret} token`);
    process.stdout.write("ACCOUNT_REFRESH offline inventory refreshed the expired account; failed refresh left its credential unchanged\n");
  } finally {
    await new Promise<void>(resolve => server.close(() => resolve()));
    rmSync(root, { recursive: true, force: true });
  }
} }];

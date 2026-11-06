import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { SessionManager } from "@earendil-works/pi-coding-agent";
import { createFork } from "./fork-adapter.ts";
import { startFakeAnthropic } from "./fake-anthropic.ts";
import type { TestCase } from "./runtime.ts";

const here = dirname(fileURLToPath(import.meta.url));
const repo = realpathSync(join(here, "../../../.."));
const account = "anthropic-account-work";
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
} }];

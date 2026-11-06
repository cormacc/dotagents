import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { join } from "node:path";
import { createAgentSession, DefaultResourceLoader, ModelRuntime, SessionManager, SettingsManager, type ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { accountProvider, registerStoredAccounts } from "./accounts.ts";
import { registerRecovery } from "./recovery.ts";
const model = "claude-sonnet-4-5", work = "anthropic-2", personal = "anthropic-1";
export default function fixture(outer: ExtensionAPI) {
  registerStoredAccounts(outer);
  outer.registerCommand("fixture-session", { handler: async (_args, ctx) => {
    try {
      for (const mode of ["resume", "direct", "child-fork"]) {
        const cwd = mkdtempSync(join(process.env.RECOVERY_TEST_ROOT!, "session-"));
        const runtime = await ModelRuntime.create({ authPath: join(process.env.PI_CODING_AGENT_DIR!, "auth.json"), modelsPath: join(process.env.PI_CODING_AGENT_DIR!, "models.json"), refreshOnCreate: false, allowModelNetwork: false });
        runtime.registerNativeProvider(accountProvider(work)); runtime.registerNativeProvider(accountProvider(personal));
        runtime.registerProvider("lemonade", { apiKey: "fixture-only", api: "anthropic-messages", baseUrl: process.env.HERDR_ORCH_FAKE_ORIGIN!, models: [{ id: "fixture-fallback", name: "fixture fallback", reasoning: false, input: ["text"], cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, contextWindow: 32000, maxTokens: 100 }] });
        const settingsManager = SettingsManager.inMemory({ retry: { enabled: false } });
        let requested: string[] = [], routes: string[] = [];
        async function create(manager: SessionManager, initial = false) {
          const loader = new DefaultResourceLoader({ cwd, agentDir: cwd, settingsManager, noExtensions: true, noSkills: true, noPromptTemplates: true, noThemes: true, noContextFiles: true,
            extensionFactories: [(pi) => {
              pi.on("before_provider_request", (_event, ctx) => { requested.push(ctx.model!.provider); });
              registerRecovery(pi, async (args, signal, options) => {
                if (args[0] === "route") routes.push(JSON.parse(readFileSync(args[2], "utf8")).model);
                const result = await pi.exec(process.env.HERDR_ORCH_BIN!, args, { signal, ...options }); assert.equal(result.code, 0, result.stderr);
                const envelope = JSON.parse(result.stdout); assert.equal(envelope.ok, true); return { details: envelope.result };
              });
            }] });
          await loader.reload();
          const { session } = await createAgentSession({ cwd, modelRuntime: runtime, ...(initial ? { model: runtime.getModel(work, model), thinkingLevel: "off" } : {}), resourceLoader: loader, settingsManager, sessionManager: manager, noTools: "all" });
          await session.bindExtensions({ mode: "tui", uiContext: { notify() {}, setStatus() {}, setWidget() {} } as any }); return session;
        }
        mkdirSync(join(cwd, "sessions"));
        const manager = SessionManager.create(cwd, join(cwd, "sessions"));
        const seed = await create(manager, true);
        try {
          await seed.prompt("/oh-tier fixture-tier");
          assert.equal(seed.model?.provider, personal);
          await seed.prompt("seed successful history");
          assert.ok(manager.getBranch().some(e => e.type === "custom" && e.customType === "herdr-orch-tier" && (e.data as any).tier === "fixture-tier"));
          if (mode === "direct") await seed.setModel(runtime.getModel(work, model)!);
        } finally { seed.dispose(); }
        const path = manager.getSessionFile()!;
        assert.ok(readFileSync(path, "utf8").includes("herdr-orch-tier"), "native persistence contains tier metadata");
        requested = []; routes = [];
        const resumedManager = mode === "child-fork" ? SessionManager.forkFrom(path, cwd, join(cwd, "sessions")) : SessionManager.open(path);
        if (mode === "child-fork") process.env.HERDR_ORCH_TASK = "fixture-pinned-child";
        const resumed = await create(resumedManager);
        delete process.env.HERDR_ORCH_TASK;
        try {
          assert.equal(resumed.model?.provider, mode === "direct" ? work : personal, "native resume restores concrete selection");
          await resumed.prompt("resumed request");
          if (mode === "resume") { assert.deepEqual(requested, [personal, work, "lemonade"]); assert.ok(routes.every(r => r === "fixture-tier")); }
          if (mode === "direct") { assert.deepEqual(requested, [work, personal]); assert.ok(routes.every(r => r === `anthropic/${model}`)); }
          if (mode === "child-fork") { assert.deepEqual(requested, [personal]); assert.deepEqual(routes, []); }
          ctx.ui.notify(`PASS installed session ${mode}: ${requested.join(' -> ')}`, "info");
        } finally { resumed.dispose(); rmSync(cwd, { recursive: true, force: true }); }
      }
      ctx.ui.notify("SUMMARY: 3 installed session restore/fork scenarios passed", "info");
    } catch (error) { ctx.ui.notify(`FAIL: ${(error as Error).stack}`, "error"); }
  } });
}

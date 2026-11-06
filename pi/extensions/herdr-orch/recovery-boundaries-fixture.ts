import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync } from "node:fs";
import { join } from "node:path";
import { AgentSessionRuntime, createAgentSession, DefaultResourceLoader, ModelRuntime, SessionManager, SettingsManager, type ExtensionAPI, type CreateAgentSessionRuntimeFactory } from "@earendil-works/pi-coding-agent";
import { accountProvider, registerStoredAccounts } from "./accounts.ts";
import { registerRecovery } from "./recovery.ts";
const work = "anthropic-2", personal = "anthropic-1", model = "claude-sonnet-4-5";
const deferred = () => { let resolve!: () => void; const promise = new Promise<void>(r => { resolve = r; }); return { promise, resolve }; };

/** Exercise idle command races and inventory bounds through the installed public SDK. */
export default function fixture(outer: ExtensionAPI) {
  registerStoredAccounts(outer);
  outer.registerCommand("fixture-boundaries", { handler: async (_args, ctx) => {
    try {
      for (const mode of ["overlap", "replacement", "inventory-4095", "inventory-4096", "inventory-4097"]) {
        const cwd = mkdtempSync(join(process.env.RECOVERY_TEST_ROOT!, "boundaries-"));
        const pending = deferred(), release = deferred();
        const credentials = new Map([work, personal].map(id => [id, { type: "oauth" as const, access: `sk-ant-oat01-${id === work ? "work" : "personal"}`, refresh: "fixture", expires: 4102444800000 }]));
        let armed = false, settled = false, reads = 0, routeCalls = 0, requests = 0;
        const notices: string[] = [], uiWrites: string[] = [], routes: any[] = [];
        const runtime = await ModelRuntime.create({ modelsPath: join(process.env.PI_CODING_AGENT_DIR!, "models.json"), refreshOnCreate: false, allowModelNetwork: false,
          credentials: {
            async read(id) { if (armed && id === personal) { armed = false; reads++; pending.resolve(); await release.promise; } return credentials.get(id); },
            async list() { return [...credentials].map(([providerId, credential]) => ({ providerId, type: credential.type })); },
            async modify(id, fn) { const next = await fn(credentials.get(id)); if (next) credentials.set(id, next as any); return credentials.get(id); },
            async delete(id) { credentials.delete(id); }
          } });
        runtime.registerNativeProvider(accountProvider(work)); runtime.registerNativeProvider(accountProvider(personal));
        if (mode.startsWith("inventory-")) {
          const target = Number(mode.split("-")[1]);
          const existing = (await runtime.getAvailableOfType("chat")).length;
          runtime.registerProvider("lemonade", { apiKey: "fixture-only", api: "anthropic-messages", baseUrl: process.env.HERDR_ORCH_FAKE_ORIGIN!, models: Array.from({ length: target - existing }, (_, i) => ({ id: `boundary-${i}`, name: `boundary ${i}`, reasoning: false, input: ["text" as const], cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, contextWindow: 32000, maxTokens: 100 })) });
          assert.equal((await runtime.getAvailableOfType("chat")).length, target, "boundary inventory actually installed");
        }
        const settingsManager = SettingsManager.inMemory({ retry: { enabled: false } });
        const ui: any = { notify(text: string) { notices.push(text); uiWrites.push(text); }, setStatus() { uiWrites.push("status"); }, setWidget() { uiWrites.push("widget"); } };
        const factory: CreateAgentSessionRuntimeFactory = async options => {
          const loader = new DefaultResourceLoader({ cwd, agentDir: cwd, settingsManager, noExtensions: true, noSkills: true, noThemes: true, noPromptTemplates: true, noContextFiles: true,
            extensionFactories: [pi => {
              pi.on("before_provider_request", () => { requests++; });
              registerRecovery(pi, async (args, signal, execOptions) => {
                if (args[0] === "route") { routeCalls++; routes.push(JSON.parse(readFileSync(args[2], "utf8"))); }
                const result = await pi.exec(process.env.HERDR_ORCH_BIN!, args, { signal, ...execOptions });
                assert.equal(result.code, 0, result.stderr); const envelope = JSON.parse(result.stdout); assert.equal(envelope.ok, true);
                if (!mode.startsWith("inventory-") && args[0] === "route") armed = true;
                return { details: envelope.result };
              });
            }] });
          await loader.reload();
          const result = await createAgentSession({ cwd, modelRuntime: runtime, model: runtime.getModel(work, model), thinkingLevel: "off", resourceLoader: loader, settingsManager, sessionManager: options.sessionManager, sessionStartEvent: options.sessionStartEvent, noTools: "all" });
          return { ...result, services: { cwd, agentDir: cwd, modelRuntime: runtime, settingsManager, resourceLoader: loader, diagnostics: [] }, diagnostics: [] };
        };
        const initial = await factory({ cwd, agentDir: cwd, sessionManager: SessionManager.inMemory(cwd) });
        const owner = new AgentSessionRuntime(initial.session, initial.services, factory);
        owner.setRebindSession(async session => { await session.bindExtensions({ mode: "tui", uiContext: ui }); });
        await owner.session.bindExtensions({ mode: "tui", uiContext: ui });
        try {
          const old = owner.session;
          const selecting = old.prompt("/oh-tier fixture-tier").then(() => { settled = true; });
          if (mode.startsWith("inventory-")) {
            await selecting;
            if (mode === "inventory-4097") { assert.equal(routeCalls, 0, "oversize inventory refuses before policy invocation"); assert.equal(old.model?.provider, work); assert.match(notices.at(-1)!, /Tier selection failed/); }
            else { assert.equal(routeCalls, 1); assert.equal(routes[0].inventory.length, Number(mode.split("-")[1])); assert.equal(old.model?.provider, personal); }
          } else {
            await Promise.race([pending.promise, new Promise((_, reject) => setTimeout(() => reject(Error("explicit setter gate not entered")), 10000))]);
            assert.equal(settled, false, "selection really awaits credentials");
            if (mode === "overlap") {
              await old.prompt("/oh-tier fixture-tier"); await old.prompt("/oh-tier clear");
              assert.equal(routeCalls, 1, "overlapping commands never launch another setter"); assert.equal(reads, 1);
              assert.ok(notices.slice(-2).every(s => s.includes("switch is still pending")));
            } else {
              assert.equal((await owner.newSession()).cancelled, false);
              assert.notEqual(owner.session, old, "public runtime replaced the session while idle setter is pending");
              await owner.session.prompt("/oh-tier clear");
            }
            const before = uiWrites.length;
            release.resolve(); await selecting;
            if (mode === "replacement") {
              assert.equal(uiWrites.length, before, "obsolete callback cannot notify or clear replacement UI");
              assert.equal(owner.session.model?.provider, work);
              assert.deepEqual(owner.session.sessionManager.getBranch().filter(e => e.type === "custom").map(e => (e as any).data), [{ tier: null }], "old command cannot persist intent into replacement");
            } else {
              assert.equal(old.model?.provider, personal, "accepted native selection can commit after command invalidation");
              assert.ok(!old.sessionManager.getBranch().some(e => e.type === "custom" && e.customType === "herdr-orch-tier"), "cancelled explicit command must not retain tier");
              assert.match(notices.at(-1)!, /Recovery cancelled.*No automatic retry/);
            }
          }
          assert.equal(requests, 0, "idle boundary tests make no inference request");
          ctx.ui.notify(`PASS installed boundary ${mode}`, "info");
        } finally { release.resolve(); await owner.dispose(); rmSync(cwd, { recursive: true, force: true }); }
      }
      ctx.ui.notify("SUMMARY: 5 installed boundary scenarios passed", "info");
    } catch (error) { ctx.ui.notify(`FAIL: ${(error as Error).stack}`, "error"); }
  } });
}

import assert from "node:assert/strict";
import { appendFileSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { createAgentSession, DefaultResourceLoader, ModelRuntime, SessionManager, SettingsManager, type ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { accountProvider, registerStoredAccounts } from "./accounts.ts";
import { PENDING_WARNING } from "./recovery.ts";
import { registerRecoveryCommand } from "./recovery-fixture-support.ts";
const work = "anthropic-2", personal = "anthropic-1", model = "claude-sonnet-4-5";
const deferred = () => { let resolve!: () => void; const promise = new Promise<void>(r => { resolve = r; }); return { promise, resolve }; };

/** Runs inside the installed Pi, not the repository's development SDK. */
export default function fixture(outer: ExtensionAPI) {
  registerStoredAccounts(outer);
  outer.registerCommand("fixture-delayed", { handler: async (_args, outerCtx) => {
    try {
      const lockfile = (await import(process.env.RECOVERY_LOCKFILE_MODULE!)).default;
      let count = 0;
      for (const store of ["public", "native"]) for (const action of ["positive", "print-positive", "abort", "manual", "manual-same", "pending-manual", "input", "failure", "removed", "shutdown", "route-abort", "route-manual", "route-input", "route-removed", "tier-overlap"]) {
        if (store === "native" && action === "pending-manual") continue;
        const cwd = mkdtempSync(join(process.env.RECOVERY_TEST_ROOT!, "delayed-"));
        const requests: string[] = [], notices: string[] = [], statuses: any[] = [], widgets: any[] = [], selections: string[] = [];
        const pending = deferred(), release = deferred(), manualPending = deferred(), manualRelease = deferred();
        let manualArmed = false, armed = false, releaseLock: (() => void) | undefined;
        let setterFinished = false, selectionWarning = false, routeGateUsed = false;
        const credentials = new Map([work, personal].map(p => [p, { type: "oauth" as const, access: `sk-ant-oat01-${p === work ? 'work' : 'personal'}`, refresh: "fixture", expires: 4102444800000 }]));
        const authPath = join(cwd, "auth.json"); writeFileSync(authPath, JSON.stringify(Object.fromEntries(credentials)));
        const origin = process.env.HERDR_ORCH_FAKE_ORIGIN!;
        const modelsPath = join(cwd, "models.json");
        writeFileSync(modelsPath, JSON.stringify({ providers: { [work]: { baseUrl: origin }, [personal]: { baseUrl: origin } } }));
        const publicStore = {
          async read(id: string, options?: { signal?: AbortSignal }) {
            options?.signal?.throwIfAborted();
            if (manualArmed && id === work) { manualArmed = false; manualPending.resolve(); await manualRelease.promise; }
            if (armed && id === personal) { armed = false; pending.resolve(); await release.promise; if (action === "failure") return undefined; }
            options?.signal?.throwIfAborted(); return credentials.get(id);
          },
          async list() { return [...credentials].map(([providerId, credential]) => ({ providerId, type: credential.type })); },
          async modify(id: string, fn: any) { const next = await fn(credentials.get(id)); if (next) credentials.set(id, next); return credentials.get(id); },
          async delete(id: string) { credentials.delete(id); }
        };
        const runtime = await ModelRuntime.create({ ...(store === "public" ? { credentials: publicStore } : { authPath }), modelsPath, refreshOnCreate: false, allowModelNetwork: false });
        runtime.registerNativeProvider(accountProvider(work)); runtime.registerNativeProvider(accountProvider(personal));
        const settingsManager = SettingsManager.inMemory({ retry: { enabled: false } });
        const loader = new DefaultResourceLoader({ cwd, agentDir: cwd, settingsManager, noExtensions: true, noSkills: true, noPromptTemplates: true, noThemes: true, noContextFiles: true,
          extensionFactories: [(pi) => {
            pi.on("before_provider_request", (_event, ctx) => { requests.push(ctx.model?.provider === work ? "work" : "personal"); });
            pi.on("model_select", event => { selections.push(`${event.model.provider}/${event.model.id}`); });
            registerRecoveryCommand(pi, async (args, signal, options) => {
              const result = await pi.exec(process.env.HERDR_ORCH_BIN!, args, { signal, ...options });
              assert.equal(result.code, 0, result.stderr); const envelope = JSON.parse(result.stdout); assert.equal(envelope.ok, true);
              if (action === "print-positive") {
                armed = true;
                if (store === "native") { releaseLock = lockfile.lockSync(authPath, { realpath: false }); appendFileSync(authPath, "\n"); pending.resolve(); }
              }
              if (action.startsWith("route-") && !routeGateUsed) { routeGateUsed = true; pending.resolve(); await release.promise; }
              return { details: envelope.result };
            });
          }] });
        await loader.reload();
        const { session } = await createAgentSession({ cwd, modelRuntime: runtime, model: runtime.getModel(work, model), thinkingLevel: "off", resourceLoader: loader, settingsManager, sessionManager: SessionManager.inMemory(cwd), noTools: "all" });
        const ui: any = { notify(text: string) {
          notices.push(text);
          if (text !== PENDING_WARNING) return;
          selectionWarning = true;
          if (action.startsWith("route-")) return;
          armed = true;
          if (store === "native") {
            releaseLock = lockfile.lockSync(authPath, { realpath: false }); appendFileSync(authPath, "\n"); pending.resolve();
          }
        }, setStatus: (...args: any[]) => statuses.push(args), setWidget: (...args: any[]) => widgets.push(args) };
        await session.bindExtensions(action === "print-positive" ? { mode: "print" } : { mode: "tui", uiContext: ui });
        let shutdown = false;
        try {
          const prompt = session.prompt("fixture request").then(() => { setterFinished = true; });
          let manualSelection: Promise<void> | undefined;
          await Promise.race([pending.promise, new Promise((_, reject) => setTimeout(() => reject(Error('auth gate not entered')), 10000))]);
          await new Promise(r => setTimeout(r, 50));
          const routePause = action.startsWith("route-"), noUI = action === "print-positive";
          assert.equal(selectionWarning, !routePause && !noUI, "warning precedes only native selection, not route preparation");
          assert.equal(setterFinished, false, "positive delay control: native setter is unresolved");
          if (!routePause && !noUI) assert.equal(statuses.at(-1)[1], PENDING_WARNING);
          let abort: Promise<void> | undefined;
          if (["abort", "route-abort"].includes(action)) abort = session.abort();
          if (["manual", "manual-same", "route-manual"].includes(action)) {
            if (store === "native" && releaseLock) { releaseLock(); releaseLock = undefined; }
            await session.setModel(runtime.getModel(action === "manual-same" ? personal : work, action === "manual-same" ? model : "claude-haiku-4-5")!);
          }
          if (action === "pending-manual" && store === "public") {
            manualArmed = true;
            manualSelection = session.setModel(runtime.getModel(work, "claude-haiku-4-5")!);
            await manualPending.promise;
          }
          if (action === "tier-overlap") {
            await session.prompt("/oh tier fixture-tier");
            await session.prompt("/oh tier clear");
            assert.ok(notices.slice(-2).every(s => s.includes("switch is still pending")), "explicit commands cannot overlap unresolved recovery setter");
          }
          if (["removed", "route-removed"].includes(action)) {
            if (store === "public") await publicStore.delete(personal);
            else writeFileSync(authPath, JSON.stringify({ [work]: credentials.get(work) }));
          }
          if (["input", "route-input"].includes(action)) await session.steer("new user request");
          if (action === "shutdown") { await session.dispose(); shutdown = true; }
          if (action === "failure" && store === "native") writeFileSync(authPath, JSON.stringify({ [work]: credentials.get(work) }));
          if (!routePause && !noUI) assert.equal(statuses.at(-1)[1], PENDING_WARNING, "invalidation retains unresolved warning");
          const before = notices.length;
          releaseLock?.(); releaseLock = undefined; release.resolve();
          await prompt; await abort;
          if (["positive", "print-positive", "pending-manual"].includes(action) || (store === "native" && action === "manual-same")) {
            assert.deepEqual(requests, ["work", "personal"], "unreported requests and silent equal selections permit accepted continuation");
            assert.equal(session.model?.provider, personal);
            assert.deepEqual(selections, [`${personal}/${model}`], "native equal commit or unresolved manual request emits no external selection change before continuation");
          }
          else if (action === "route-input") assert.deepEqual(requests, ["work", "work", "personal"], "new input starts its own request before recovery, not stale continuation");
          else assert.deepEqual(requests, ["work"], "invalidated recovery cannot issue a replacement request");
          if (action === "shutdown") assert.equal(notices.length, before, "shutdown prevents callback UI");
          else if (noUI) { assert.deepEqual(statuses, []); assert.deepEqual(widgets, []); assert.deepEqual(notices, []); }
          else if (!routePause || action === "route-input") {
            assert.equal(statuses.at(-1)[1], undefined);
            assert.equal(widgets.at(-1)[1], undefined);
            if (["failure", "removed"].includes(action)) assert.match(notices.at(-1)!, /selection failed/);
            else if (!["positive", "pending-manual", "route-input"].includes(action) && !(store === "native" && action === "manual-same")) {
              assert.match(notices.at(-1)!, /Recovery cancelled.*Selected account\/model:.*No automatic retry/);
              assert.ok(notices.at(-1)!.includes(`${session.model?.provider}/${session.model?.id}`), "cancellation reports actual native selection, not the requested target");
            }
            if (["failure", "removed"].includes(action)) assert.ok(!notices.some(s => s.startsWith("Recovery selected")));
          }
          if (["removed", "route-removed"].includes(action)) {
            if (store === "public") { assert.equal(credentials.has(personal), false, "late callback cannot restore a removed credential"); assert.equal(credentials.has(work), true); }
            else { const data = JSON.parse(readFileSync(authPath, "utf8")); assert.equal(personal in data, false); assert.equal(work in data, true); }
          }
          manualRelease.resolve(); await manualSelection;
          assert.doesNotMatch(JSON.stringify(notices), /sk-ant-oat01|fixture.*refresh/);
          outerCtx.ui.notify(`PASS ${store} ${action}: requests=${requests.join(',')}; selected=${session.model?.provider}/${session.model?.id}`, "info"); count++;
        } finally {
          manualRelease.resolve(); release.resolve(); releaseLock?.(); if (!shutdown) session.dispose(); rmSync(cwd, { recursive: true, force: true });
        }
      }
      outerCtx.ui.notify(`SUMMARY: ${count} installed delayed-selection scenarios passed; TUI API presentation is not visual evidence`, "info");
    } catch (e) { outerCtx.ui.notify(`FAIL: ${(e as Error).stack}`, "error"); }
  } });
}

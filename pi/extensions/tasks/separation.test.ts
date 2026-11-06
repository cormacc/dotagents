import assert from "node:assert/strict";
import { pathToFileURL, fileURLToPath } from "node:url";
import { mkdtempSync, rmSync, appendFileSync } from "node:fs";
import { join } from "node:path";
import registerLegacy from "./legacy.ts";
import { runOtResult, otCreateTask, otSelectTask } from "./ot.ts";

// The override exercises the pre-separation factory as a negative control.
const entry = process.env.TASKS_SEPARATION_CONTROL
  ? pathToFileURL(process.env.TASKS_SEPARATION_CONTROL).href
  : new URL("./agent.ts", import.meta.url).href;
const { default: register } = await import(entry);
const registrations: string[] = [];
const api: any = new Proxy({}, { get: (_target, name) => {
  if (name === "events") return { on: () => { registrations.push("listener"); return () => {}; } };
  return () => { registrations.push(String(name)); };
} });
register(api);
assert.deepEqual(registrations, [], "non-visual factory must register no UI, watchers, tools or event handlers");
console.log("ok - non-visual loading has no registrations or session resources");

const delay = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms));
const watchers = () => (process as any)._getActiveHandles().filter((h: any) => h.constructor.name === "FSWatcher").length;
const scratch = mkdtempSync(join(fileURLToPath(new URL("../../../.tmp/", import.meta.url)), "tasks-loading-"));
try {
  await runOtResult(["init"], { root: scratch });
  const task = await otCreateTask({ summary: "Selected task" }, { root: scratch });
  await otSelectTask(task.id, { root: scratch });
  const initialWatchers = watchers();
  for (const factories of [[register], [registerLegacy], [register, registerLegacy], [registerLegacy, register]]) {
    const visual = factories.includes(registerLegacy);
    const listeners = new Map<string, Set<Function>>();
    // The shared event bus survives each replacement, as it does in Pi.
    const events = {
      on(topic: string, fn: Function) {
        const set = listeners.get(topic) ?? new Set();
        set.add(fn); listeners.set(topic, set);
        return () => set.delete(fn);
      },
      async emit(topic: string, payload: unknown) {
        for (const fn of [...(listeners.get(topic) ?? [])]) await fn(payload);
      },
    };
    for (let replacement = 0; replacement < 3; replacement++) {
      const handlers = new Map<string, Function[]>();
      const commands: string[] = [];
      const shortcuts: string[] = [];
      let widgets = 0;
      let notices = 0;
      const ctx: any = { cwd: scratch, mode: "rpc", hasUI: true, ui: {
        setWidget: () => { widgets++; },
        notify: () => { notices++; },
        custom: () => { throw new Error("no RPC overlay"); },
      } };
      const runtime: any = { events,
        on(name: string, fn: Function) { handlers.set(name, [...(handlers.get(name) ?? []), fn]); },
        registerCommand(name: string) { commands.push(name); },
        registerShortcut(name: string) { shortcuts.push(name); },
        registerTool() { throw new Error("historical task tools must not return"); },
      };
      for (const factory of factories) factory(runtime);
      assert.deepEqual(commands, visual ? ["tasks"] : []);
      assert.deepEqual(shortcuts, visual ? ["alt+t"] : []);
      assert.equal(listeners.get("tasks:show")?.size ?? 0, visual ? 1 : 0);
      if (replacement === 2 && visual) {
        const starting = Promise.all((handlers.get("session_start") ?? []).map((fn) => fn({}, ctx)));
        for (const fn of handlers.get("session_shutdown") ?? []) await fn({}, ctx);
        await starting;
        await delay(20);
        assert.equal(watchers(), initialWatchers, "an in-flight startup cannot reattach watchers after shutdown");
        continue;
      }
      for (const fn of handlers.get("session_start") ?? []) await fn({}, ctx);
      if (visual) assert.ok(watchers() > initialWatchers, "legacy startup attaches real watchers");
      else { assert.equal(watchers(), initialWatchers); assert.equal(widgets, 0); }
      await events.emit("tasks:show", { ctx });
      assert.equal(notices, visual ? 1 : 0, "one status action after replacement");
      for (let shutdown = 0; shutdown < 2; shutdown++) {
        for (const fn of handlers.get("session_shutdown") ?? []) await fn({}, ctx);
      }
      await delay(20);
      assert.equal(watchers(), initialWatchers, "shutdown closes all owned watchers");
      assert.equal(listeners.get("tasks:show")?.size ?? 0, 0);
      const widgetsAfterShutdown = widgets;
      appendFileSync(join(scratch, "TASKS.org"), "\n");
      await delay(250);
      assert.equal(widgets, widgetsAfterShutdown, "no refresh survives shutdown");
    }
    console.log(`ok - ${visual ? "legacy/combined" : "non-visual"} loading, three replacements and idempotent cleanup`);
  }
} finally {
  rmSync(scratch, { recursive: true, force: true });
}

#!/usr/bin/env tsx
/** Regression tests for shared pi.events subscription cleanup. */

import { spawnSync } from "node:child_process";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import registerEmacsclient from "../emacsclient/index.ts";
import registerTasksAgent from "../tasks/agent.ts";
import registerLegacyTasks from "../tasks/legacy.ts";
import assert from "node:assert/strict";

type Listener = (...args: any[]) => unknown;

function createEventBus() {
  const listeners = new Map<string, Set<Listener>>();
  return {
    on(topic: string, listener: Listener): () => void {
      const topicListeners = listeners.get(topic) ?? new Set<Listener>();
      topicListeners.add(listener);
      listeners.set(topic, topicListeners);
      return () => topicListeners.delete(listener);
    },
    async emit(topic: string, payload: unknown): Promise<number> {
      const topicListeners = [...(listeners.get(topic) ?? [])];
      for (const listener of topicListeners) await listener(payload);
      return topicListeners.length;
    },
    count(topic: string): number {
      return listeners.get(topic)?.size ?? 0;
    },
  };
}

function createRuntime(events: ReturnType<typeof createEventBus>, extras: Record<string, unknown>) {
  const shutdownHandlers: Listener[] = [];
  return {
    api: {
      events,
      on(event: string, handler: Listener) {
        if (event === "session_shutdown") shutdownHandlers.push(handler);
      },
      registerTool() {},
      registerCommand() {},
      getAllTools() { return []; },
      ...extras,
    },
    async shutdown() {
      for (const handler of shutdownHandlers) await handler({}, {});
    },
  };
}

let failures = 0;

function fail(message: string): void {
  console.log(`not ok - ${message}`);
  failures++;
}

async function assertSessionScoped(options: {
  name: string;
  topic: string;
  payload: unknown;
  register: (api: any) => void;
  createExtras: (actions: { count: number }) => Record<string, unknown>;
}): Promise<void> {
  const events = createEventBus();
  let failed = false;

  for (let instance = 0; instance < 3; instance++) {
    const actions = { count: 0 };
    const runtime = createRuntime(events, options.createExtras(actions));
    options.register(runtime.api);
    if (events.count(options.topic) !== 1) {
      fail(`${options.name}: replacement ${instance} has one listener`);
      failed = true;
    }

    const deliveries = await events.emit(options.topic, options.payload);
    if (deliveries !== 1 || actions.count !== 1) {
      fail(`${options.name}: one emitted event produces one action after replacement ${instance}`);
      failed = true;
    }

    await runtime.shutdown();
    await runtime.shutdown();
    if (events.count(options.topic) !== 0) {
      fail(`${options.name}: shutdown releases its listener idempotently`);
      failed = true;
    }
  }

  if (!failed) console.log(`ok - ${options.name}: reload and replacement emit one action`);
}

for (const combined of [false, true]) {
  let notify = () => {};
  await assertSessionScoped({
    name: combined ? "tasks legacy with non-visual helpers" : "tasks legacy",
    topic: "tasks:show",
    payload: { ctx: { hasUI: true, mode: "rpc", ui: { notify: () => notify() } } },
    register: (api) => { if (combined) registerTasksAgent(api); registerLegacyTasks(api); },
    createExtras: (actions) => {
      notify = () => { actions.count++; };
      return { registerShortcut() {} };
    },
  });
}

const transportTrue = `"${Buffer.from(JSON.stringify({ type: "boolean", value: true })).toString("base64")}"`;

await assertSessionScoped({
  name: "emacsclient",
  topic: "emacs:open",
  payload: { file: "/tmp/example.el", line: 1 },
  register: registerEmacsclient,
  createExtras: (actions) => ({
    async exec(_command: string, args: string[]) {
      if (args.includes("--eval")) actions.count++;
      return { stdout: transportTrue, stderr: "", code: 0 };
    },
  }),
});

const originalAgentDir = process.env.PI_CODING_AGENT_DIR;
const originalCwd = process.cwd();
const jiraAgentDir = mkdtempSync(join(tmpdir(), "pi-jira-events-"));
const jiraProject = mkdtempSync(join(tmpdir(), "pi-jira-project-"));
try {
  process.env.PI_CODING_AGENT_DIR = jiraAgentDir;
  writeFileSync(
    join(jiraAgentDir, "jira-ext.json"),
    JSON.stringify({ autoTransition: true }),
  );

  // The listener resolves the task's Jira keys with `ot` in process.cwd(),
  // so the event needs a real project whose task links a Jira issue.
  const ot = new URL("../../../skills/org-tasks/scripts/ot", import.meta.url).pathname;
  const runOt = (...args: string[]) => {
    const r = spawnSync(ot, ["--format", "json", "--root", jiraProject, ...args], { encoding: "utf-8" });
    if (r.status !== 0) throw new Error(`ot ${args.join(" ")} failed: ${r.stdout}${r.stderr}`);
    const envelope = JSON.parse(r.stdout);
    if (!envelope.ok) throw new Error(`ot returned an error envelope: ${r.stdout}`);
    return envelope.result;
  };
  runOt("init");
  const taskId: string = runOt(
    "create", "Test transition", "--section", "Improvements", "--linked-issue", "[[jira:SAND-1]]",
  ).id;
  process.chdir(jiraProject);

  const { default: registerJira } = await import("../jira/index.ts");
  await assertSessionScoped({
    name: "jira",
    topic: "tasks:status-changed",
    payload: {
      id: taskId,
      status: "STARTED",
      prevStatus: "TODO",
      summary: "Test transition",
      closed: false,
    },
    register: registerJira,
    createExtras: (actions) => ({
      getAllTools() { return [{ name: "mcp__atlassian__getJiraIssue" }]; },
      sendMessage() { actions.count++; },
    }),
  });

  for (const combined of [false, true]) {
    runOt("status", taskId, "TODO");
    const events = createEventBus();
    const transitions: unknown[] = [];
    const messages: any[] = [];
    const off = events.on("tasks:status-changed", (payload) => {
      assert.equal(runOt("show", taskId).task.status, "STARTED", "event follows durable status write");
      transitions.push(payload);
    });
    const runtime = createRuntime(events, {
      registerShortcut() {},
      getAllTools() { return [{ name: "mcp__atlassian__getJiraIssue" }]; },
      sendMessage(message: unknown, options: unknown) { messages.push({ message, options }); },
    });
    registerJira(runtime.api as any);
    if (combined) registerTasksAgent(runtime.api as any);
    registerLegacyTasks(runtime.api as any);
    const ctx: any = { cwd: jiraProject, hasUI: true, mode: "tui", ui: {
      notify() {}, setWidget() {},
      async custom(factory: Function) {
        const theme = { fg: (_color: string, text: string) => text, bg: (_color: string, text: string) => text, bold: (text: string) => text };
        const overlay = factory({ requestRender() {}, terminal: { rows: 40, columns: 120 } }, theme, {}, () => {});
        overlay.handleInput("l");
        const until = Date.now() + 10000;
        while (!overlay.render(120).join("\n").includes("STARTED") || messages.length === 0) {
          if (Date.now() > until) throw new Error("legacy status cycle/Jira trigger did not settle");
          await new Promise((resolve) => setTimeout(resolve, 25));
        }
      },
    } };
    try {
      await events.emit("tasks:show", { ctx });
      assert.deepEqual(transitions, [{ id: taskId, status: "STARTED", prevStatus: "TODO", summary: "Test transition", closed: false }]);
      assert.equal(messages.length, 1, "one overlay cycle dispatches one Jira transition");
      assert.match(messages[0].message.content, /jira_transition/);
      assert.deepEqual(messages[0].options, { triggerTurn: true, deliverAs: "followUp" });
      await runtime.shutdown();
      await runtime.shutdown();
      assert.equal(events.count("tasks:show"), 0);
      assert.equal(events.count("tasks:status-changed"), 1, "only the test observer remains");
      console.log(`ok - ${combined ? "combined" : "legacy-only"} overlay persists one status event and one Jira trigger`);
    } finally {
      off();
      await runtime.shutdown();
    }
  }
} finally {
  process.chdir(originalCwd);
  if (originalAgentDir === undefined) delete process.env.PI_CODING_AGENT_DIR;
  else process.env.PI_CODING_AGENT_DIR = originalAgentDir;
  rmSync(jiraAgentDir, { recursive: true, force: true });
  rmSync(jiraProject, { recursive: true, force: true });
}

if (failures > 0) process.exit(1);

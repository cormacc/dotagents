#!/usr/bin/env tsx
/** Regression tests for shared pi.events subscription cleanup. */

import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import registerEmacsclient from "../emacsclient/index.ts";

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
const jiraAgentDir = mkdtempSync(join(tmpdir(), "pi-jira-events-"));
try {
  process.env.PI_CODING_AGENT_DIR = jiraAgentDir;
  writeFileSync(
    join(jiraAgentDir, "jira-ext.json"),
    JSON.stringify({ autoTransition: true }),
  );

  const { default: registerJira } = await import("../jira/index.ts");
  await assertSessionScoped({
    name: "jira",
    topic: "tasks:status-changed",
    payload: {
      id: "11111111-1111-4111-8111-111111111111",
      status: "STARTED",
      prevStatus: "TODO",
      summary: "Test transition",
      closed: false,
    },
    register: registerJira,
    createExtras: (actions) => ({
      getAllTools() { return [{ name: "mcp" }]; },
      sendUserMessage() { actions.count++; },
    }),
  });
} finally {
  if (originalAgentDir === undefined) delete process.env.PI_CODING_AGENT_DIR;
  else process.env.PI_CODING_AGENT_DIR = originalAgentDir;
  rmSync(jiraAgentDir, { recursive: true, force: true });
}

if (failures > 0) process.exit(1);

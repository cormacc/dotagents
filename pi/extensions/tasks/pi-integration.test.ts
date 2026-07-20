#!/usr/bin/env tsx
/**
 * RPC integration tests for the `/tasks` command's mode boundaries.
 *
 * `/tasks new` and `/tasks doctor` never call `ctx.ui.custom()`, so they
 * remain available in RPC mode. Bare `/tasks` opens the full overlay via
 * `ctx.ui.custom()`, which RPC does not implement (it resolves to
 * `undefined` immediately with no dialog shown), so it must report an
 * unsupported-mode notification instead of silently no-oping or trying (and
 * failing) to render an overlay.
 *
 * Modeled on pi/extensions/emacsclient/pi-integration.test.ts.
 */

import { spawn, ChildProcess } from "node:child_process";
import { createInterface } from "node:readline";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";

const __dirname = dirname(fileURLToPath(import.meta.url));

// ---------------------------------------------------------------------------
// Test harness
// ---------------------------------------------------------------------------

let passed = 0;
let failed = 0;

function testPass(name: string) {
  console.log(`ok - ${name}`);
  passed++;
}

function testFail(name: string, reason?: string) {
  console.log(`not ok - ${name}`);
  if (reason) console.log(`  # ${reason}`);
  failed++;
}

function waitForEvent(events: any[], predicate: (e: any) => boolean, timeout = 15000): Promise<any> {
  return new Promise((resolve, reject) => {
    const start = Date.now();
    const check = () => {
      const found = events.find(predicate);
      if (found) return resolve(found);
      if (Date.now() - start > timeout) return reject(new Error("Timeout waiting for event"));
      setTimeout(check, 50);
    };
    check();
  });
}

function isNotifyRequest(e: any): boolean {
  return e.type === "extension_ui_request" && e.method === "notify";
}

function isInputRequest(e: any): boolean {
  return e.type === "extension_ui_request" && e.method === "input";
}

// ---------------------------------------------------------------------------
// Dummy LLM provider (never invoked: `/tasks` subcommands are handled by the
// extension command dispatcher before any prompt reaches the model).
// ---------------------------------------------------------------------------

function createDummyLLM(dir: string): string {
  const llmPath = join(dir, "dummy-llm.ts");
  writeFileSync(
    llmPath,
    `
import type { ExtensionAPI } from "@mariozechner/pi-coding-agent";
import {
  type AssistantMessage,
  type AssistantMessageEventStream,
  type Context,
  type Model,
  type SimpleStreamOptions,
  createAssistantMessageEventStream,
} from "@mariozechner/pi-ai";

function stream(model: Model<any>, _context: Context, _options?: SimpleStreamOptions): AssistantMessageEventStream {
  const s = createAssistantMessageEventStream();
  (async () => {
    const output: AssistantMessage = {
      role: "assistant",
      content: [{ type: "text" as const, text: "unused" }],
      api: model.api,
      provider: model.provider,
      model: model.id,
      usage: {
        input: 1, output: 1, cacheRead: 0, cacheWrite: 0, totalTokens: 2,
        cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
      },
      stopReason: "stop",
      timestamp: Date.now(),
    };
    s.push({ type: "start", partial: output });
    s.push({ type: "text_start", contentIndex: 0, partial: output });
    s.push({ type: "text_delta", contentIndex: 0, delta: "unused", partial: output });
    s.push({ type: "text_end", contentIndex: 0, content: "unused", partial: output });
    s.push({ type: "done", reason: "stop", message: output });
    s.end();
  })();
  return s;
}

export default function (pi: ExtensionAPI) {
  pi.registerProvider("dummy", {
    baseUrl: "http://localhost:1234",
    apiKey: "dummy",
    api: "openai-completions",
    models: [{
      id: "dummy-model",
      name: "Dummy",
      reasoning: false,
      input: ["text"],
      cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
      contextWindow: 128000,
      maxTokens: 4096,
    }],
    streamSimple: stream,
  });
}
`,
    "utf-8"
  );
  return llmPath;
}

// ---------------------------------------------------------------------------
// Pi process management
// ---------------------------------------------------------------------------

function startPi(extensions: string[], cwd: string): ChildProcess {
  const args = [
    "--mode", "rpc",
    "--provider", "dummy",
    "--model", "dummy-model",
    ...extensions.flatMap((ext) => ["-e", ext]),
  ];

  return spawn("pi", args, {
    stdio: ["pipe", "pipe", "pipe"],
    cwd,
    env: { ...process.env, HOME: cwd },
  });
}

function sendCommand(proc: ChildProcess, cmd: any) {
  proc.stdin!.write(JSON.stringify(cmd) + "\n");
}

async function runTest(name: string, testFn: (tempDir: string) => Promise<boolean | string>) {
  const tempDir = mkdtempSync(join(tmpdir(), "pi-tasks-mode-test-"));

  try {
    const result = await Promise.race([
      testFn(tempDir),
      new Promise<never>((_, reject) => setTimeout(() => reject(new Error("Test timeout (30s)")), 30000)),
    ]);

    if (result === true) {
      testPass(name);
    } else {
      testFail(name, (typeof result === "string" ? result : undefined) || "Test returned false");
    }
  } catch (err: any) {
    testFail(name, err.message);
  } finally {
    rmSync(tempDir, { recursive: true, force: true });
  }
}

function writeFixtureTasksOrg(cwd: string): void {
  mkdirSync(cwd, { recursive: true });
  writeFileSync(join(cwd, "TASKS.org"), "#+TITLE: fixture\n\n* Improvements\n", "utf-8");
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

(async function () {
  await runTest("/tasks new succeeds under the RPC harness", async (tempDir) => {
    writeFixtureTasksOrg(tempDir);
    const llm = createDummyLLM(tempDir);
    const ext = join(__dirname, "index.ts");
    const pi = startPi([llm, ext], tempDir);

    const events: any[] = [];
    const rl = createInterface({ input: pi.stdout! });
    rl.on("line", (line) => {
      try {
        events.push(JSON.parse(line));
      } catch {}
    });

    try {
      await new Promise((r) => setTimeout(r, 500));
      sendCommand(pi, { type: "prompt", message: "/tasks new" });

      const inputRequest = await waitForEvent(events, isInputRequest);
      sendCommand(pi, { type: "extension_ui_response", id: inputRequest.id, value: "A new RPC task" });

      const notify = await waitForEvent(events, isNotifyRequest);
      if (!String(notify.message).startsWith("Created:")) {
        return `Expected a "Created: ..." notification, got ${JSON.stringify(notify)}`;
      }
      if (notify.notifyType === "error") {
        return `Expected a success notification, got an error: ${JSON.stringify(notify)}`;
      }

      return true;
    } finally {
      pi.kill();
      await new Promise((r) => pi.on("close", r));
    }
  });

  await runTest("/tasks doctor succeeds under the RPC harness", async (tempDir) => {
    writeFixtureTasksOrg(tempDir);
    const llm = createDummyLLM(tempDir);
    const ext = join(__dirname, "index.ts");
    const pi = startPi([llm, ext], tempDir);

    const events: any[] = [];
    const rl = createInterface({ input: pi.stdout! });
    rl.on("line", (line) => {
      try {
        events.push(JSON.parse(line));
      } catch {}
    });

    try {
      await new Promise((r) => setTimeout(r, 500));
      sendCommand(pi, { type: "prompt", message: "/tasks doctor" });

      const notify = await waitForEvent(events, isNotifyRequest);
      if (notify.notifyType === "error" && !String(notify.message).length) {
        return `Expected a doctor report, got ${JSON.stringify(notify)}`;
      }

      return true;
    } finally {
      pi.kill();
      await new Promise((r) => pi.on("close", r));
    }
  });

  await runTest("bare /tasks reports unsupported mode outside TUI", async (tempDir) => {
    writeFixtureTasksOrg(tempDir);
    const llm = createDummyLLM(tempDir);
    const ext = join(__dirname, "index.ts");
    const pi = startPi([llm, ext], tempDir);

    const events: any[] = [];
    const rl = createInterface({ input: pi.stdout! });
    rl.on("line", (line) => {
      try {
        events.push(JSON.parse(line));
      } catch {}
    });

    try {
      await new Promise((r) => setTimeout(r, 500));
      sendCommand(pi, { type: "prompt", message: "/tasks" });

      const notify = await waitForEvent(events, isNotifyRequest);
      if (notify.message !== "/tasks requires TUI mode; use /tasks new or /tasks doctor") {
        return `Expected unsupported-mode notification, got ${JSON.stringify(notify)}`;
      }
      if (notify.notifyType !== "error") {
        return `Expected notifyType "error", got ${JSON.stringify(notify)}`;
      }

      // Must not have attempted to open the (unsupported) overlay component.
      const customAttempt = events.find((e) => e.type === "extension_ui_request" && e.method === "custom");
      if (customAttempt) return `Overlay must not attempt to open in RPC mode, got ${JSON.stringify(customAttempt)}`;

      return true;
    } finally {
      pi.kill();
      await new Promise((r) => pi.on("close", r));
    }
  });

  console.log(`\n# ${passed} passed, ${failed} failed`);
  if (failed > 0) process.exit(1);
})();

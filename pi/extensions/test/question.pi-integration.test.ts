#!/usr/bin/env tsx
/**
 * RPC integration tests for pi/extensions/question.ts.
 *
 * Spawns real `pi --mode rpc` with a dummy LLM provider and this extension,
 * then triggers a `question` tool call. RPC's `ctx.ui.custom()` resolves to
 * `undefined` immediately (no dialog is shown in RPC mode), so this test
 * proves the tool reports "UI not available" instead of misreporting a user
 * cancellation. RPC is *not* used to assert anything about sequential TUI
 * rendering — see question.test.ts for the in-process interactive smoke test
 * that drives the actual render/handleInput component.
 *
 * Modeled on pi/extensions/emacsclient/pi-integration.test.ts.
 */

import { createHarness } from "./support/rpc.ts";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";
import { mkdirSync, writeFileSync, rmSync } from "node:fs";
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

// ---------------------------------------------------------------------------
// Dummy LLM that immediately calls the `question` tool
// ---------------------------------------------------------------------------

function createDummyLLM(dir: string): string {
  const llmPath = join(dir, "dummy-llm.ts");
  writeFileSync(
    llmPath,
    `
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import {
  type AssistantMessage,
  type AssistantMessageEventStream,
  type Context,
  type Model,
  type SimpleStreamOptions,
  createAssistantMessageEventStream,
} from "@earendil-works/pi-ai";

function stream(
  model: Model<any>,
  context: Context,
  options?: SimpleStreamOptions
): AssistantMessageEventStream {
  const s = createAssistantMessageEventStream();

  (async () => {
    const output: AssistantMessage = {
      role: "assistant",
      content: [],
      api: model.api,
      provider: model.provider,
      model: model.id,
      usage: {
        input: 10, output: 10, cacheRead: 0, cacheWrite: 0, totalTokens: 20,
        cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 },
      },
      stopReason: "stop",
      timestamp: Date.now(),
    };

    try {
      const last = context.messages[context.messages.length - 1];
      if (last && (last.role === "tool" || last.role === "toolResult")) {
        s.push({ type: "start", partial: output });
        output.content.push({ type: "text" as const, text: "Done." });
        s.push({ type: "text_start", contentIndex: 0, partial: output });
        s.push({ type: "text_delta", contentIndex: 0, delta: "Done.", partial: output });
        s.push({ type: "text_end", contentIndex: 0, content: "Done.", partial: output });
        s.push({ type: "done", reason: "stop", message: output });
        s.end();
        return;
      }

      s.push({ type: "start", partial: output });
      const text = "Asking a question.";
      output.content.push({ type: "text" as const, text });
      s.push({ type: "text_start", contentIndex: 0, partial: output });
      s.push({ type: "text_delta", contentIndex: 0, delta: text, partial: output });
      s.push({ type: "text_end", contentIndex: 0, content: text, partial: output });

      output.stopReason = "toolUse";
      const tc = {
        type: "toolCall" as const,
        id: \`call_\${Date.now()}\`,
        name: "question",
        arguments: { question: "Pick one", options: [{ label: "A" }, { label: "B" }] },
      };
      output.content.push(tc);
      s.push({ type: "toolcall_start", contentIndex: 1, partial: output });
      s.push({ type: "toolcall_end", contentIndex: 1, toolCall: tc, partial: output });

      s.push({ type: "done", reason: output.stopReason as any, message: output });
      s.end();
    } catch (error: any) {
      output.stopReason = "error";
      output.errorMessage = error.message;
      s.push({ type: "error", reason: "error", error: output });
      s.end();
    }
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

async function runTest(name: string, testFn: (tempDir: string) => Promise<boolean | string>) {
  const tempDir = join(tmpdir(), `pi-question-test-${Date.now()}-${Math.random().toString(36).slice(2)}`);
  mkdirSync(tempDir, { recursive: true });

  let timeout: ReturnType<typeof setTimeout> | undefined;
  try {
    const result = await Promise.race([
      testFn(tempDir),
      new Promise<never>((_, reject) => { timeout = setTimeout(() => reject(new Error("Test timeout (30s)")), 30000); }),
    ]);

    if (result === true) {
      testPass(name);
    } else {
      testFail(name, (typeof result === "string" ? result : undefined) || "Test returned false");
    }
  } catch (err: any) {
    testFail(name, err.message);
  } finally {
    if (timeout !== undefined) clearTimeout(timeout);
    rmSync(tempDir, { recursive: true, force: true });
  }
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

(async function () {
  await runTest("question tool under RPC reports unsupported mode, not cancellation", async (tempDir) => {
    const llm = createDummyLLM(tempDir);
    const ext = join(__dirname, "..", "question.ts");
    const harness = createHarness(ext, tempDir, {
      args: ["--provider", "dummy", "--model", "dummy-model", "-e", llm],
    });

    try {
      await new Promise((r) => setTimeout(r, 500));
      const promptId = harness.send({ type: "prompt", message: "ask me something" });

      const toolEnd = await harness.waitFor(
        (e) => e.type === "tool_execution_end" && e.toolName === "question", "question result",
      );

      await harness.waitFor((e) => e.type === "response" && e.id === promptId, "prompt response");

      const resultText = toolEnd.result?.content?.find((c: any) => c.type === "text")?.text ?? "";
      if (resultText !== "Error: UI not available (running in non-interactive mode)") {
        return `Expected unsupported-mode error, got "${resultText}"`;
      }
      if (resultText.toLowerCase().includes("cancel")) {
        return `Result must not read as a user cancellation, got "${resultText}"`;
      }

      return true;
    } finally {
      await harness.dispose();
    }
  });

  console.log(`\n# ${passed} passed, ${failed} failed`);
  if (failed > 0) process.exit(1);
})();

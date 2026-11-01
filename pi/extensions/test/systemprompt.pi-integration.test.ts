#!/usr/bin/env tsx
/** RPC integration test for pi/extensions/systemprompt.ts. */

import { createHarness } from "./support/rpc.ts";
import { mkdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));

async function main() {
  const tempDir = join(tmpdir(), `pi-systemprompt-test-${Date.now()}-${Math.random().toString(36).slice(2)}`);
  mkdirSync(tempDir, { recursive: true });
  const agentDir = join(tempDir, "agent");
  mkdirSync(agentDir, { recursive: true });

  const harness = createHarness(join(__dirname, "..", "systemprompt.ts"), tempDir, {
    args: ["--no-extensions", "--no-skills", "--no-prompt-templates", "--no-context-files",
      "--system-prompt", "The complete RPC system prompt."],
    env: { PI_CODING_AGENT_DIR: agentDir },
  });
  const { send, waitFor } = harness;

  try {
    const beforeId = send({ type: "get_entries" });
    const before = await waitFor(
      (event) => event.type === "response" && event.id === beforeId, "initial entries",
    );
    const entryCount = before.data?.entries?.length;
    if (typeof entryCount !== "number") throw new Error("get_entries did not return an entry list");

    const promptId = send({ type: "prompt", message: "/systemprompt" });
    const notification = await waitFor(
      (event) => event.type === "extension_ui_request" && event.method === "notify", "prompt notification",
    );
    if (!notification.message?.startsWith("The complete RPC system prompt.")) {
      throw new Error(`expected configured prompt, got ${JSON.stringify(notification.message)}`);
    }
    await waitFor((event) => event.type === "response" && event.id === promptId, "prompt response");
    const afterId = send({ type: "get_entries" });
    const after = await waitFor((event) => event.type === "response" && event.id === afterId, "final entries");
    if (after.data?.entries?.length !== entryCount) {
      throw new Error(`RPC /systemprompt must not append entries: ${JSON.stringify(after.data)}`);
    }
    console.log("ok - RPC /systemprompt emits notify output without session or context entries");
  } finally {
    await harness.dispose();
    rmSync(tempDir, { recursive: true, force: true });
  }
}

main().catch((error) => {
  console.error(`not ok - ${error instanceof Error ? error.message : String(error)}`);
  process.exit(1);
});

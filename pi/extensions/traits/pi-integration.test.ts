#!/usr/bin/env tsx
import { createHarness } from "../test/support/rpc.ts";
import { dirname, join } from "node:path";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));

async function main(): Promise<void> {
  const cwd = mkdtempSync(join(tmpdir(), "pi-traits-rpc-outside-repo-"));
  const agentDir = join(cwd, "agent");
  mkdirSync(agentDir, { recursive: true });
  const projectTraits = join(cwd, ".agents", "traits");
  mkdirSync(projectTraits, { recursive: true });
  writeFileSync(join(projectTraits, "focused.md"), "HOSTILE PROJECT OVERRIDE");
  const capture = join(cwd, "capture.ts");
  writeFileSync(capture, [
    'import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";',
    'export default function (pi: ExtensionAPI) {',
    '  pi.on("input", (event, ctx) => {',
    '    ctx.ui.notify(`capture:${event.text}`, "info");',
    '    return { action: "handled" };',
    '  });',
    '}',
    '',
  ].join("\n"));

  const harness = createHarness(capture, cwd, {
    args: ["--no-extensions", "--no-skills", "--no-prompt-templates", "--no-context-files", "--no-approve", "-e", join(here, "index.ts")],
    env: { HOME: process.env.HOME, PI_CODING_AGENT_DIR: agentDir },
  });
  const { events, send, waitFor } = harness;

  const cases = [
    { input: "%focused", includes: "Treat a layered prompt as one primary deliverable", excludes: "%focused" },
    { input: "page%focused %20", includes: "capture:page%focused %20" },
    { input: "%unknown-token", includes: "capture:%unknown-token" },
    { input: "%focused %unknown-token", includes: "%unknown-token", excludes: "%focused" },
  ];

  try {
    for (let index = 0; index < cases.length; index++) {
      const test = cases[index]!;
      const since = events.length;
      const id = `traits-${index}`;
      send({ type: "prompt", id, message: test.input });
      const response = await waitFor(
        (event) => event.type === "response" && event.command === "prompt" && event.id === id,
        "prompt response", { since },
      );
      if (!response.success) throw new Error(`prompt failed for ${test.input}: ${response.error}`);
      const notify = await waitFor(
        (event) => event.type === "extension_ui_request" && event.method === "notify",
        "capture notification", { since },
      );
      const message = String(notify.message);
      if (!message.includes(test.includes)) {
        throw new Error(`expected ${JSON.stringify(test.includes)} for ${test.input}, got ${JSON.stringify(message)}`);
      }
      if (test.excludes && message.includes(test.excludes)) {
        throw new Error(`did not expect ${JSON.stringify(test.excludes)} for ${test.input}, got ${JSON.stringify(message)}`);
      }
      if (message.includes("HOSTILE PROJECT OVERRIDE")) {
        throw new Error(`untrusted project trait expanded for ${test.input}: ${JSON.stringify(message)}`);
      }
      console.log(`ok - untrusted-project RPC input reached the downstream handler: ${test.input}`);
    }
    if (/extension(?:-| )load error|failed to load extension/i.test(harness.diagnostics())) {
      throw new Error(harness.diagnostics());
    }
  } finally {
    await harness.dispose();
    rmSync(cwd, { recursive: true, force: true });
  }
}

main().catch((error) => {
  console.error(`not ok - ${error instanceof Error ? error.message : String(error)}`);
  process.exit(1);
});

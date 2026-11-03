#!/usr/bin/env tsx
import { createHarness } from "../test/support/rpc.ts";
import { dirname, join, resolve } from "node:path";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "../../..");

async function main(): Promise<void> {
  mkdirSync(join(root, ".tmp"), { recursive: true });
  const cwd = mkdtempSync(join(root, ".tmp", "oh-workflows-rpc-"));
  const skill = join(cwd, ".agents", "skills", "fixture-flow");
  mkdirSync(skill, { recursive: true });
  writeFileSync(join(skill, "SKILL.md"), [
    "---",
    "name: fixture-flow",
    "description: Fixture workflow for RPC discovery.",
    "metadata: {herdr-orch: workflow}",
    "---",
    "These instructions must not run when help is requested.",
    "",
  ].join("\n"));
  const harness = createHarness(join(here, "index.ts"), cwd, {
    args: ["--no-extensions", "--no-skills", "--no-prompt-templates", "--no-context-files", "--no-approve"],
    env: {
      HERDR_ENV: "0",
      HERDR_ORCH_BIN: join(root, "skills", "herdr-orch", "scripts", "oh"),
      HERDR_ORCH_SUBJECT: undefined,
      ORCH_ASSIGNMENT_ROOT: cwd,
    },
  });
  try {
    for (const message of ["/oh workflows", "/oh"]) {
      const since = harness.events.length;
      const id = harness.send({ type: "prompt", message });
      const response = await harness.waitFor(
        (event) => event.type === "response" && event.id === id,
        `${message} response`, { since },
      );
      if (!response.success) throw new Error(`${message}: ${response.error}`);
      const notice = await harness.waitFor(
        (event) => event.type === "extension_ui_request" && event.method === "notify",
        `${message} notification`, { since },
      );
      if (notice.notifyType !== "info" ||
          !notice.message.includes("`fixture-flow` -- Fixture workflow for RPC discovery.") ||
          !notice.message.includes(join(skill, "SKILL.md"))) {
        throw new Error(`Incorrect workflow notification: ${JSON.stringify(notice)}`);
      }
      if (message === "/oh" && !notice.message.includes("## Workflows")) {
        throw new Error("The default help omitted the workflows section");
      }
      console.log(`ok - installed Pi ${message} discovers workflow metadata through the real CLI`);
    }
    if (harness.events.some((event: any) => event.type === "agent_start")) {
      throw new Error("Opening help started a model turn");
    }
    if (/extension(?:-| )load error|failed to load extension/i.test(harness.diagnostics())) {
      throw new Error(harness.diagnostics());
    }
    console.log("ok - workflow help needs neither Herdr nor a model turn");
  } finally {
    await harness.dispose();
    rmSync(cwd, { recursive: true, force: true });
  }
}

main().catch((error) => {
  console.error(`not ok - ${error instanceof Error ? error.message : String(error)}`);
  process.exitCode = 1;
});

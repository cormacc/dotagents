#!/usr/bin/env tsx
import { createHarness } from "../test/support/rpc.ts";
import { dirname, join, resolve } from "node:path";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "../../..");

function isolatedOrchEnv(): NodeJS.ProcessEnv {
  return Object.fromEntries(
    Object.keys(process.env)
      .filter((name) => name === "ORCH_ASSIGNMENT_ROOT" || name.startsWith("HERDR_ORCH_"))
      .map((name) => [name, undefined]),
  );
}

function writeUsageFixture(cwd: string): string {
  const auth = join(cwd, ".pi", "agent", "auth.json");
  mkdirSync(dirname(auth), { recursive: true });
  writeFileSync(auth, JSON.stringify({
    anthropic: { access: "fixture-access", expires: 4102444800000 },
    "openai-codex": { access: "fixture-access", expires: 4102444800000 },
  }));
  const fixture = join(cwd, "usage-fixture.edn");
  writeFileSync(fixture, `{
"https://api.anthropic.com/api/oauth/usage" {:status 200 :headers {} :body "{\\"five_hour\\":{\\"utilization\\":28,\\"resets_at\\":\\"2030-01-01T00:00:00Z\\"}}"}
"https://chatgpt.com/backend-api/wham/usage" {:status 200 :headers {} :body "{\\"rate_limit\\":{\\"primary_window\\":{\\"used_percent\\":40,\\"reset_at\\":1893456000}}}"}
}`);
  return fixture;
}

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
  const usageFixture = writeUsageFixture(cwd);
  const harness = createHarness(join(here, "index.ts"), cwd, {
    args: ["--no-extensions", "--no-skills", "--no-prompt-templates", "--no-context-files", "--no-approve"],
    env: {
      ...isolatedOrchEnv(),
      HERDR_ENV: "0",
      HERDR_ORCH_BIN: join(root, "skills", "herdr-orch", "scripts", "oh"),
      HERDR_ORCH_USAGE_FIXTURE: usageFixture,
      ORCH_ASSIGNMENT_ROOT: cwd,
    },
  });
  try {
    for (const message of ["/oh workflows", "/oh usage", "/oh"]) {
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
      if (notice.notifyType !== "info") throw new Error(`Incorrect notification: ${JSON.stringify(notice)}`);
      if (message !== "/oh usage" &&
          (!notice.message.includes("`fixture-flow` -- Fixture workflow for RPC discovery.") ||
           !notice.message.includes(join(skill, "SKILL.md")))) {
        throw new Error(`Incorrect workflow notification: ${JSON.stringify(notice)}`);
      }
      if (message === "/oh usage" && (!notice.message.includes("**anthropic** -- ok; 72% remaining") || !notice.message.includes("source: pi") || !notice.message.includes("resets 2030-01-01T00:00:00Z"))) {
        throw new Error(`Incorrect usage notification: ${JSON.stringify(notice)}`);
      }
      if (message === "/oh" && (!notice.message.includes("## Workflows") || !notice.message.includes("## Subscription usage") || !notice.message.includes("**openai-codex** -- ok; 60% remaining"))) {
        throw new Error("The default help omitted workflow or fixture-backed usage output");
      }
      console.log(`ok - installed Pi ${message} discovers workflow metadata and fixture-backed usage through the real CLI`);
    }
    if (harness.events.some((event: any) => event.type === "agent_start")) {
      throw new Error("Opening help started a model turn");
    }
    if (/extension(?:-| )load error|failed to load extension/i.test(harness.diagnostics())) {
      throw new Error(harness.diagnostics());
    }
    console.log("ok - /oh commands need neither Herdr nor a model turn");
  } finally {
    await harness.dispose();
    rmSync(cwd, { recursive: true, force: true });
  }
}

main().catch((error) => {
  console.error(`not ok - ${error instanceof Error ? error.message : String(error)}`);
  process.exitCode = 1;
});

#!/usr/bin/env tsx
import { createHarness } from "../test/support/rpc.ts";
import { dirname, join, resolve } from "node:path";
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { execFileSync } from "node:child_process";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "../../..");

function isolatedOrchEnv(): NodeJS.ProcessEnv {
  return Object.fromEntries(
    Object.keys(process.env)
      .filter((name) => name === "ORCH_ASSIGNMENT_ROOT" || name.startsWith("HERDR_ORCH_"))
      .map((name) => [name, undefined]),
  );
}

function writeUsageFixture(cwd: string): NodeJS.ProcessEnv {
  const bin = join(cwd, "quota-bin");
  mkdirSync(bin, { recursive: true });
  for (const name of ["claude", "codex", "kimi", "zai", "cswap", "security", "npm"]) {
    const path = join(bin, name);
    writeFileSync(path, '#!/usr/bin/env bash\nprintf "vendor escape\\n" >>"$FAKE_VENDOR_LOG"\nexit 97\n');
    chmodSync(path, 0o755);
  }
  const fixture = join(cwd, "usage-report.json");
  writeFileSync(fixture, JSON.stringify({
    claude: { body: { five_hour: { utilization: 28, resets_at: "2030-01-01T00:00:00Z" } } },
    codex: { body: { rate_limit: { primary_window: { used_percent: 40, limit_window_seconds: 18000 } } } },
  }));
  const agentDir = join(cwd, "agent");
  mkdirSync(agentDir, { recursive: true });
  writeFileSync(join(agentDir, "auth.json"), JSON.stringify({
    anthropic: { type: "oauth", refresh: "fixture-refresh", access: "fixture-claude", expires: 4102444800000 },
    "openai-codex": { type: "oauth", refresh: "fixture-refresh", access: "fixture-codex", expires: 4102444800000, accountId: "fixture-account" },
  }));
  return {
    PATH: bin + ":" + process.env.PATH,
    HOME: cwd,
    PI_CODING_AGENT_DIR: agentDir,
    CODEX_HOME: join(cwd, ".codex"),
    CLAUDE_CONFIG_DIR: join(cwd, ".claude"),
    XDG_CONFIG_HOME: join(cwd, ".config"),
    XDG_CACHE_HOME: join(cwd, ".cache"),
    NODE_OPTIONS: `--require=${join(root, "skills/herdr-orch/scripts/pi/fake-only.cjs")}`,
    HERDR_ORCH_FAKE_ORIGIN: "",
    ORCH_TEST_ROOT: root,
    ORCH_TEST_BB: execFileSync("which", ["bb"], { encoding: "utf8" }).trim(),
    HERDR_ORCH_BIN: join(root, "skills/herdr-orch/scripts/test/fixtures/http-oh"),
    FAKE_QUOTA_REPORT: fixture,
    FAKE_QUOTA_LOG: join(cwd, "quota-calls"),
    FAKE_VENDOR_LOG: join(cwd, "vendor-escapes"),
  };
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
      ...usageFixture,
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
      if (message === "/oh usage" && (!notice.message.includes("**anthropic** -- fresh; all_models 72% remaining") || !notice.message.includes("**openai-codex** -- fresh; all_models 60% remaining") || !notice.message.includes("resets 2030-01-01T00:00:00Z"))) {
        throw new Error(`Incorrect usage notification: ${JSON.stringify(notice)}`);
      }
      if (message === "/oh" && (!notice.message.includes("## Workflows") || !notice.message.includes("## Subscription usage") || !notice.message.includes("**openai-codex** -- fresh; all_models 60% remaining"))) {
        throw new Error("The default help omitted workflow or fixture-backed usage output");
      }
      console.log(`ok - installed Pi ${message} discovers workflow metadata and fixture-backed usage through the real CLI`);
    }
    const callsBefore = readFileSync(usageFixture.FAKE_QUOTA_LOG!, "utf8");
    const since = harness.events.length;
    const id = harness.send({ type: "prompt", message: "/oh setup" });
    const response = await harness.waitFor((event) => event.type === "response" && event.id === id, "retired setup response", { since });
    if (!response.success) throw new Error(`retired setup: ${response.error}`);
    const notice = await harness.waitFor((event) => event.type === "extension_ui_request" && event.method === "notify", "unknown section notice", { since });
    if (!notice.message.includes("Unknown /oh section") || notice.notifyType !== "warning") throw new Error("Retired setup was not rejected");
    if (readFileSync(usageFixture.FAKE_QUOTA_LOG!, "utf8") !== callsBefore) throw new Error("Retired setup read quota");
    if (harness.events.some((event: any) => event.type === "extension_ui_request" && event.method === "confirm")) throw new Error("Quota display prompted for setup");
    console.log("ok - retired setup does not prompt, install or read quota");
    if (harness.events.some((event: any) => event.type === "agent_start")) {
      throw new Error("Opening help started a model turn");
    }
    if (existsSync(usageFixture.FAKE_VENDOR_LOG!)) {
      throw new Error("Opening help reached a native vendor executable");
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

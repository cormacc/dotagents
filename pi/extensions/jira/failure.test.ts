#!/usr/bin/env tsx
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import registerJira from "./index.ts";

let tool: any;
registerJira({
  registerTool(value: unknown) { tool = value; },
  registerCommand() {},
  getAllTools() { return []; },
  events: { on() { return () => {}; } },
} as any);

if (!tool || tool.name !== "jira_clone_apply") throw new Error("jira_clone_apply was not registered");

async function expectFailure(name: string, params: Record<string, unknown>, cwd: string, expected: string) {
  try {
    await tool.execute("test", params, undefined, undefined, { cwd });
    throw new Error(`${name}: returned instead of throwing`);
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    if (!message.includes("Jira clone failed") || !message.includes(expected)) {
      throw new Error(`${name}: lost diagnostic: ${message}`);
    }
    console.log(`ok - ${name}`);
  }
}

async function main() {
const root = mkdtempSync(join(tmpdir(), "jira-failure-"));
try {
  await expectFailure("invalid Jira key throws", { key: "bad", summary: "Bad" }, root, "invalid key");

  writeFileSync(join(root, "TASKS.org"), "* Other\n", "utf8");
  await expectFailure("missing section throws", { key: "SAND-1", summary: "Missing" }, root, "was not found");

  writeFileSync(
    join(root, "TASKS.org"),
    "* Improvements\n** TODO Existing\n:PROPERTIES:\n:CUSTOM_ID: 11111111-1111-4111-8111-111111111111\n:LINKED_ISSUES: [[jira:SAND-2]]\n:END:\n",
    "utf8",
  );
  await expectFailure("duplicate Jira issue throws", { key: "SAND-2", summary: "Duplicate" }, root, "already linked");

  await expectFailure("insert errors throw", { key: "SAND-3", summary: "" }, root, "summary");
} finally {
  rmSync(root, { recursive: true, force: true });
}
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});

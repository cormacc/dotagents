#!/usr/bin/env tsx
/** Regression tests for SETUPFILE expansion used by task loading/archive flows. */

import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { tmpdir } from "node:os";
import { readEffectiveOrgContent } from "./effective.ts";
import { expandOrgLinkTarget, parseTasks } from "./parser.ts";

let passed = 0;
let failed = 0;

function assertEqual<T>(actual: T, expected: T, message: string): void {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a === e) {
    passed++;
    console.log(`ok - ${message}`);
  } else {
    failed++;
    console.log(`not ok - ${message}`);
    console.log(`  expected: ${e}`);
    console.log(`  actual:   ${a}`);
  }
}

async function withTempDir(fn: (dir: string) => Promise<void>): Promise<void> {
  const dir = await mkdtemp(join(tmpdir(), "tasks-effective-"));
  try {
    await fn(dir);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
}

async function main(): Promise<void> {
  await withTempDir(async (dir) => {
    await mkdir(join(dir, "design", "log"), { recursive: true });
    const tasksPath = join(dir, "TASKS.org");
    const localPath = join(dir, "TASKS.local.org");
    const setupPath = join(dir, "TASKS.setup.org");
    const tasksContent = [
      "#+SETUPFILE: ./TASKS.local.org",
      "#+SETUPFILE: ./TASKS.setup.org",
      "",
      "* Improvements",
      "** DONE Parent",
      ":PROPERTIES:",
      ":CUSTOM_ID: aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
      ":END:",
      "#+IMPORT: [[plan:2026-05-14-evaluate-vulpea-org-memory.org]]",
      "",
    ].join("\n");
    await writeFile(tasksPath, tasksContent, "utf-8");
    await writeFile(localPath, "#+SELECTED:\n#+JIRA_PROJECT: LOCAL\n", "utf-8");
    await writeFile(setupPath, "#+LINK: plan file:design/log/%s\n#+JIRA_PROJECT: SHARED\n", "utf-8");

    const effective = await readEffectiveOrgContent(dir, tasksPath, tasksContent);
    assertEqual(effective.includes("#+JIRA_PROJECT: LOCAL"), true,
      "readEffectiveOrgContent: includes first setupfile");
    assertEqual(effective.includes("#+LINK: plan file:design/log/%s"), true,
      "readEffectiveOrgContent: includes second setupfile");

    const { tasks } = parseTasks(tasksContent, { sourcePath: tasksPath, effectiveSourceContent: effective });
    const expanded = expandOrgLinkTarget(tasks[0]!.importPath!, tasks[0]!.effectiveSourceContent);
    assertEqual(expanded, {
      target: "design/log/2026-05-14-evaluate-vulpea-org-memory.org",
      fromProjectRoot: true,
    }, "readEffectiveOrgContent: chained setupfiles expose shared plan link for archive/import resolution");
  });
}

main().finally(() => {
  console.log(`\n# ${passed} passed, ${failed} failed`);
  process.exit(failed === 0 ? 0 : 1);
});

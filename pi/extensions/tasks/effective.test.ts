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

function assertBefore(content: string, first: string, second: string, message: string): void {
  assertEqual(content.indexOf(first) < content.indexOf(second), true, message);
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

  await withTempDir(async (dir) => {
    const tasksPath = join(dir, "TASKS.org");
    const firstPath = join(dir, "first.org");
    const nestedPath = join(dir, "nested.org");
    const secondPath = join(dir, "second.org");
    const tasksContent = [
      "#+JIRA_PROJECT: ROOT",
      "#+SETUPFILE: ./first.org",
      "#+SETUPFILE: ./second.org",
      "",
    ].join("\n");
    await writeFile(tasksPath, tasksContent, "utf-8");
    await writeFile(firstPath, [
      "#+JIRA_PROJECT: FIRST",
      "#+SETUPFILE: ./nested.org",
      "#+JIRA_CLOUDID: first-cloud",
      "",
    ].join("\n"), "utf-8");
    await writeFile(nestedPath, "#+JIRA_PROJECT: NESTED\n", "utf-8");
    await writeFile(secondPath, "#+JIRA_PROJECT: SECOND\n", "utf-8");

    const effective = await readEffectiveOrgContent(dir, tasksPath, tasksContent);
    assertBefore(effective, "ROOT", "FIRST",
      "readEffectiveOrgContent: preserves declarations before a setupfile");
    assertBefore(effective, "FIRST", "NESTED",
      "readEffectiveOrgContent: expands nested setupfiles at their declaration");
    assertBefore(effective, "NESTED", "first-cloud",
      "readEffectiveOrgContent: resumes the parent after a nested setupfile");
    assertBefore(effective, "first-cloud", "SECOND",
      "readEffectiveOrgContent: preserves sibling setupfile declaration order");
  });

  await withTempDir(async (dir) => {
    const tasksPath = join(dir, "TASKS.org");
    const aPath = join(dir, "a.org");
    const bPath = join(dir, "b.org");
    const deepPaths = Array.from({ length: 10 }, (_, index) => join(dir, `deep-${index}.org`));
    const outsideDir = await mkdtemp(join(tmpdir(), "tasks-effective-outside-"));
    const outsidePath = join(outsideDir, "outside.org");
    const tasksContent = [
      "#+SETUPFILE: ./a.org",
      "#+SETUPFILE: ./a.org",
      `#+SETUPFILE: ${outsidePath}`,
      "#+SETUPFILE: ./missing.org",
      "#+SETUPFILE: ./deep-0.org",
      "",
    ].join("\n");
    try {
      await writeFile(tasksPath, tasksContent, "utf-8");
      await writeFile(aPath, "#+SETUPFILE: ./b.org\n#+ORDER: A\n", "utf-8");
      await writeFile(bPath, "#+SETUPFILE: ./TASKS.org\n#+ORDER: B\n", "utf-8");
      await writeFile(outsidePath, "#+OUTSIDE: must-not-load\n", "utf-8");
      for (let index = 0; index < deepPaths.length; index++) {
        const next = index + 1 < deepPaths.length ? `#+SETUPFILE: ./deep-${index + 1}.org\n` : "";
        await writeFile(deepPaths[index]!, `${next}#+DEPTH: ${index}\n`, "utf-8");
      }

      const effective = await readEffectiveOrgContent(dir, tasksPath, tasksContent);
      assertEqual((effective.match(/#\+ORDER: A/g) ?? []).length, 1,
        "readEffectiveOrgContent: expands a repeated setupfile once");
      assertEqual((effective.match(/#\+ORDER: B/g) ?? []).length, 1,
        "readEffectiveOrgContent: terminates cycles without re-expanding the root");
      assertEqual(effective.includes("#+OUTSIDE: must-not-load"), false,
        "readEffectiveOrgContent: does not read an out-of-root setupfile");
      assertEqual(effective.includes("#+DEPTH: 7"), true,
        "readEffectiveOrgContent: includes the maximum-depth setupfile content");
      assertEqual(effective.includes("#+DEPTH: 8"), false,
        "readEffectiveOrgContent: does not expand beyond the maximum depth");
    } finally {
      await rm(outsideDir, { recursive: true, force: true });
    }
  });
}

main().finally(() => {
  console.log(`\n# ${passed} passed, ${failed} failed`);
  process.exit(failed === 0 ? 0 : 1);
});

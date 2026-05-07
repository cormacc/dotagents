#!/usr/bin/env tsx
/** Project-root sandbox regression tests for imports and plan paths. */

import * as fsp from "node:fs/promises";
import { tmpdir } from "node:os";
import * as path from "node:path";
import { resolveProjectPath } from "./paths.ts";

const { mkdtemp, mkdir, realpath, rm, symlink, writeFile } = fsp;
const { join } = path;

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
  const dir = await mkdtemp(join(tmpdir(), "tasks-paths-"));
  try {
    await fn(dir);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
}

async function main(): Promise<void> {
  await withTempDir(async (project) => {
    const plans = join(project, "design", "log");
    await mkdir(plans, { recursive: true });
    const existing = join(plans, "existing.org");
    await writeFile(existing, "* Plan\n", "utf-8");

    assertEqual(
      await resolveProjectPath(project, project, "design/log/existing.org"),
      await realpath(existing),
      "sandbox: allows in-tree relative import path",
    );

    assertEqual(
      await resolveProjectPath(project, project, existing),
      await realpath(existing),
      "sandbox: allows in-tree absolute import path",
    );

    const future = join(await realpath(plans), "future.org");
    assertEqual(
      await resolveProjectPath(project, project, "design/log/future.org"),
      future,
      "sandbox: allows non-existing in-tree scaffold path via existing parent",
    );
  });

  await withTempDir(async (project) => {
    await withTempDir(async (outside) => {
      const outsideFile = join(outside, "outside.org");
      await writeFile(outsideFile, "* Outside\n", "utf-8");

      assertEqual(
        await resolveProjectPath(project, project, outsideFile),
        null,
        "sandbox: rejects out-of-tree absolute path",
      );
    });
  });

  await withTempDir(async (project) => {
    const outside = await mkdtemp(join(tmpdir(), "tasks-paths-outside-"));
    try {
      const traversal = join(project, "..", path.basename(outside), "escape.org");
      assertEqual(
        await resolveProjectPath(project, project, traversal),
        null,
        "sandbox: rejects parent-traversal escape path",
      );
    } finally {
      await rm(outside, { recursive: true, force: true });
    }
  });

  await withTempDir(async (project) => {
    await withTempDir(async (outside) => {
      const outsideFile = join(outside, "outside.org");
      await writeFile(outsideFile, "* Outside\n", "utf-8");
      const link = join(project, "linked-outside.org");
      await symlink(outsideFile, link);

      assertEqual(
        await resolveProjectPath(project, project, "linked-outside.org"),
        null,
        "sandbox: rejects symlink escape path",
      );
    });
  });
}

main().then(
  () => {
    console.log(`\n# ${passed} passed, ${failed} failed`);
    process.exit(failed === 0 ? 0 : 1);
  },
  (err) => {
    failed++;
    console.log(`not ok - path sandbox tests threw: ${(err as Error).stack ?? err}`);
    console.log(`\n# ${passed} passed, ${failed} failed`);
    process.exit(1);
  },
);

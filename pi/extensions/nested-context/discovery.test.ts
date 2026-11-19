#!/usr/bin/env tsx
/**
 * Unit tests for nested context discovery against a real fixture repository.
 */

import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { tmpdir } from "node:os";
import { directoriesBelow, governingContextFiles, runGit, type Git } from "./discovery.ts";

const here = dirname(fileURLToPath(import.meta.url));
const scratch = join(here, "..", "..", "..", ".tmp");
mkdirSync(scratch, { recursive: true });
const base = mkdtempSync(join(scratch, "nested-context-"));

let passed = 0;
let failed = 0;
async function test(name: string, fn: () => Promise<void> | void) {
  try {
    await fn();
    console.log(`ok - ${name}`);
    passed++;
  } catch (err) {
    console.log(`not ok - ${name}`);
    console.log(`  # ${err instanceof Error ? err.message : String(err)}`);
    failed++;
  }
}
function assertEqual(actual: unknown, expected: unknown) {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a !== e) throw new Error(`expected ${e}, got ${a}`);
}

// The fixture must not depend on the user's Git configuration (signing, hooks, identity).
const gitEnv = { ...process.env, GIT_CONFIG_GLOBAL: "/dev/null", GIT_CONFIG_NOSYSTEM: "1" };
function git(cwd: string, ...args: string[]) {
  execFileSync("git", ["-c", "user.name=t", "-c", "user.email=t@t", "-c", "protocol.file.allow=always", ...args], {
    cwd, env: gitEnv, stdio: "pipe",
  });
}
function put(root: string, rel: string, text = rel) {
  mkdirSync(dirname(join(root, rel)), { recursive: true });
  writeFileSync(join(root, rel), text);
}
function repo(root: string, files: string[]) {
  mkdirSync(root, { recursive: true });
  git(root, "init", "-q", "-b", "main");
  for (const f of files) put(root, f);
  git(root, "add", "-A");
  git(root, "commit", "-q", "-m", "fixture");
}

const subSource = join(base, "sub-source");
repo(subSource, ["AGENTS.md", "deep/f.txt"]);
const root = join(base, "root");
repo(root, [
  "AGENTS.md", "x.txt",
  "a/AGENTS.md", "a/b/AGENTS.override.md", "a/b/CLAUDE.md", "a/b/c/file.txt",
  "d/AGENTS.MD", "d/CLAUDE.md", "d/f.txt",
]);
put(root, ".gitignore", "i/AGENTS.md\nclone/\n");
git(root, "add", ".gitignore");
git(root, "commit", "-q", "-m", "ignore");
put(root, "u/AGENTS.md");
put(root, "i/AGENTS.md");
repo(join(root, "clone"), ["AGENTS.md", "f.txt"]);
git(root, "submodule", "add", "-q", subSource, "sub");
git(root, "commit", "-q", "-m", "submodule");
const link = join(base, "link");
symlinkSync(root, link);

const rel = (files: string[]) => files.map((f) => f.slice(root.length + 1));

(async function main() {
  try {
    await test("files at several depths are returned outermost first, one per directory by Pi's order", async () => {
      assertEqual(rel(await governingContextFiles(root, "a/b/c/file.txt")), ["a/AGENTS.md", "a/b/AGENTS.override.md"]);
    });
    await test("an absolute path gives the same result as a relative one", async () => {
      assertEqual(rel(await governingContextFiles(root, join(root, "a/b/c/file.txt"))), ["a/AGENTS.md", "a/b/AGENTS.override.md"]);
    });
    await test("AGENTS.MD is picked before CLAUDE.md when AGENTS.md is absent", async () => {
      assertEqual(rel(await governingContextFiles(root, "d/f.txt")), ["d/AGENTS.MD"]);
    });
    await test("the working directory's own file is not returned (Pi loads it)", async () => {
      assertEqual(await governingContextFiles(root, "x.txt"), []);
    });
    await test("a path outside the working directory returns nothing", async () => {
      assertEqual(await governingContextFiles(join(root, "a"), "../d/f.txt"), []);
      assertEqual(await governingContextFiles(root, join(base, "sub-source/deep/f.txt")), []);
    });
    await test("an untracked context file is not returned", async () => {
      assertEqual(await governingContextFiles(root, "u/f.txt"), []);
    });
    await test("an ignored context file is not returned", async () => {
      assertEqual(await governingContextFiles(root, "i/f.txt"), []);
    });
    await test("a file tracked by a nested clone that is not a submodule is not returned", async () => {
      assertEqual(await governingContextFiles(root, "clone/f.txt"), []);
    });
    await test("a file tracked in a submodule is returned", async () => {
      assertEqual(rel(await governingContextFiles(root, "sub/deep/f.txt")), ["sub/AGENTS.md"]);
    });
    await test("a new file in a missing directory is governed by its existing ancestors", async () => {
      assertEqual(rel(await governingContextFiles(root, "a/new/dir/f.txt")), ["a/AGENTS.md"]);
    });
    await test("a symlinked working directory resolves to the same files", async () => {
      assertEqual(rel(await governingContextFiles(link, "a/b/c/file.txt")), ["a/AGENTS.md", "a/b/AGENTS.override.md"]);
    });
    await test("a failing git call returns nothing instead of an error", async () => {
      const failing: Git = async () => ({ code: 128, stdout: "" });
      const throwing: Git = async () => { throw new Error("git missing"); };
      assertEqual(await governingContextFiles(root, "a/b/c/file.txt", failing), []);
      assertEqual(await governingContextFiles(root, "a/b/c/file.txt", throwing), []);
    });
    await test("a working directory outside any repository returns nothing", async () => {
      // Outside this repository too, so the result cannot come from the tracking check.
      const bare = mkdtempSync(join(tmpdir(), "nested-context-plain-"));
      try {
        put(bare, "n/AGENTS.md");
        const r = await runGit(["rev-parse", "--show-toplevel"], bare);
        if (r.code === 0) throw new Error(`control: ${bare} is inside a repository`);
        assertEqual(await governingContextFiles(bare, "n/f.txt"), []);
      } finally {
        rmSync(bare, { recursive: true, force: true });
      }
    });
    await test("directoriesBelow lists only directories strictly below the working directory", () => {
      assertEqual(directoriesBelow(root, "a/b/c/file.txt").map((d) => d.slice(root.length + 1)), ["a", "a/b", "a/b/c"]);
      assertEqual(directoriesBelow(root, "x.txt"), []);
      assertEqual(directoriesBelow(root, "."), []);
    });
  } finally {
    rmSync(base, { recursive: true, force: true });
  }
  console.log(`\n# ${passed} passed, ${failed} failed`);
  if (failed > 0) process.exit(1);
})();

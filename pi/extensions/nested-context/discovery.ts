/**
 * Find the nested context files that govern a path below the working directory.
 *
 * Pi loads one context file per directory from the working directory and its
 * ancestors only. This module finds the ones Pi skips: those in directories
 * strictly below the working directory, on the way down to a touched path.
 */

import { execFile } from "node:child_process";
import { existsSync, realpathSync, statSync } from "node:fs";
import { dirname, isAbsolute, join, relative, resolve, sep } from "node:path";

/** Pi's per-directory order (`loadContextFileFromDir` in Pi's resource-loader). */
export const CONTEXT_FILE_NAMES = ["AGENTS.override.md", "AGENTS.md", "AGENTS.MD", "CLAUDE.md", "CLAUDE.MD"];

export type Git = (args: string[], cwd: string) => Promise<{ code: number; stdout: string }>;

export const runGit: Git = (args, cwd) =>
  new Promise((done) => {
    execFile("git", args, { cwd, timeout: 5000, encoding: "utf8" }, (error, stdout) => {
      const code = error ? (typeof (error as any).code === "number" ? (error as any).code : 1) : 0;
      done({ code, stdout: String(stdout ?? "") });
    });
  });

/** The file Pi would pick in `dir`, or undefined. */
export function contextFileIn(dir: string): string | undefined {
  for (const name of CONTEXT_FILE_NAMES) {
    const path = join(dir, name);
    try {
      if (existsSync(path) && statSync(path).isFile()) return path;
    } catch {
      // Unreadable entries are skipped, as Pi skips them.
    }
  }
  return undefined;
}

/** Directories strictly below `cwd`, down to the directory that holds `path`, outermost first. */
export function directoriesBelow(cwd: string, path: string): string[] {
  // Compare real paths, so a symlinked working directory or an absolute path
  // in its real form still resolves. A new file's missing tail is kept as written.
  cwd = real(cwd);
  const target = realTail(resolve(cwd, path));
  const rel = relative(cwd, target);
  if (rel === "" || rel.startsWith("..") || isAbsolute(rel)) return [];
  const parts = dirname(rel) === "." ? [] : dirname(rel).split(sep);
  return parts.map((_, i) => join(cwd, ...parts.slice(0, i + 1)));
}

async function toplevel(git: Git, dir: string): Promise<string | undefined> {
  const r = await git(["rev-parse", "--show-toplevel"], dir);
  const top = r.stdout.trim();
  return r.code === 0 && top ? top : undefined;
}

/**
 * True when Git tracks `file` in the repository of `cwdTop`, or in a submodule
 * reached from it through the superproject chain. A clone or an unrelated
 * repository below the working directory is not a submodule, so it fails.
 */
async function trackedInScope(git: Git, cwdTop: string, file: string): Promise<boolean> {
  const dir = dirname(file);
  const tracked = await git(["ls-files", "--error-unmatch", "--", file], dir);
  if (tracked.code !== 0) return false;
  let top = await toplevel(git, dir);
  for (let depth = 0; top && depth < 16; depth++) {
    if (real(top) === cwdTop) return true;
    const up = await git(["rev-parse", "--show-superproject-working-tree"], top);
    const next = up.stdout.trim();
    top = up.code === 0 && next ? next : undefined;
  }
  return false;
}

function real(path: string): string {
  try {
    return realpathSync(path);
  } catch {
    return path;
  }
}

/** `path` with its longest existing prefix resolved to a real path. */
function realTail(path: string): string {
  let head = path;
  const tail: string[] = [];
  while (!existsSync(head)) {
    const up = dirname(head);
    if (up === head) return path;
    tail.unshift(head.slice(up.length + (up.endsWith(sep) ? 0 : 1)));
    head = up;
  }
  return join(real(head), ...tail);
}

/**
 * The context files that govern `path`, outermost first. Each is Pi's pick for
 * its directory, delivered only when it passes the Git scope rule. Any Git
 * failure yields no file rather than an error.
 */
export async function governingContextFiles(cwd: string, path: string, git: Git = runGit): Promise<string[]> {
  const dirs = directoriesBelow(cwd, path);
  if (dirs.length === 0) return [];
  try {
    const cwdTop = await toplevel(git, cwd);
    if (!cwdTop) return [];
    const files: string[] = [];
    for (const dir of dirs) {
      const file = contextFileIn(dir);
      if (file && (await trackedInScope(git, real(cwdTop), file))) files.push(file);
    }
    return files;
  } catch {
    return [];
  }
}

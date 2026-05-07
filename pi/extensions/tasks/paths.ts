import { realpath } from "node:fs/promises";
import { basename, dirname, isAbsolute, relative, resolve } from "node:path";

export function isWithinRoot(path: string, root: string): boolean {
  const rel = relative(root, path);
  return rel === "" || (!rel.startsWith("..") && !isAbsolute(rel));
}

/**
 * Resolve an existing path through symlinks. If the path does not exist,
 * resolve the nearest existing parent and append the basename so future
 * scaffold targets can still be sandboxed before creation.
 */
export async function resolveExistingOrParent(path: string): Promise<string> {
  try {
    return await realpath(path);
  } catch {
    try {
      const parent = await realpath(dirname(path));
      return resolve(parent, basename(path));
    } catch {
      return resolve(path);
    }
  }
}

/**
 * Resolve a user-supplied import / plan path against a base directory and
 * return null when the result escapes the project root after symlink
 * resolution.
 */
export async function resolveProjectPath(
  cwd: string,
  baseDir: string,
  candidate: string,
): Promise<string | null> {
  const root = await resolveExistingOrParent(cwd);
  const abs = isAbsolute(candidate) ? candidate : resolve(baseDir, candidate);
  const resolved = await resolveExistingOrParent(abs);
  return isWithinRoot(resolved, root) ? resolved : null;
}

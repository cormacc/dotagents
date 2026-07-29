import { readFile } from "node:fs/promises";
import { dirname } from "node:path";
import { extractOrgLinkTarget } from "./parser.ts";
import { resolveProjectPath } from "./paths.ts";

const MAX_SETUPFILE_DEPTH = 8;

const SETUPFILE_RE = /^[\t ]*#\+SETUPFILE[\t ]*:[\t ]*(.*?)[\t ]*$/gim;

/**
 * Return CONTENT with readable in-project #+SETUPFILE contents inserted at
 * their declarations.
 *
 * This preserves Org declaration order: content before a declaration wins over
 * that setupfile, and a nested setupfile is expanded where it appears in its
 * parent. Setupfiles are expanded recursively with a small depth guard and a
 * visited set to break cycles. Missing, unreadable, and out-of-tree setupfiles
 * are ignored so fresh checkouts keep working just as they did when only one
 * setupfile was followed.
 */
export async function readEffectiveOrgContent(
  projectRoot: string,
  filePath: string,
  content: string,
  seenSetupFiles = new Set<string>(),
  depth = 0,
): Promise<string> {
  if (seenSetupFiles.size === 0) {
    const canonicalFilePath = await resolveProjectPath(
      projectRoot,
      dirname(filePath),
      filePath,
    );
    seenSetupFiles.add(canonicalFilePath ?? filePath);
  }
  if (depth >= MAX_SETUPFILE_DEPTH) return content;

  const parts: string[] = [];
  let cursor = 0;
  for (const match of content.matchAll(SETUPFILE_RE)) {
    const declaration = match[0] ?? "";
    const declarationIndex = match.index ?? cursor;
    parts.push(content.slice(cursor, declarationIndex + declaration.length));
    cursor = declarationIndex + declaration.length;

    const rawSetup = match[1] ?? "";
    const setupTarget = extractOrgLinkTarget(rawSetup) ?? rawSetup.trim();
    if (!setupTarget) continue;
    const setupPath = await resolveProjectPath(projectRoot, dirname(filePath), setupTarget);
    if (!setupPath || seenSetupFiles.has(setupPath)) continue;
    seenSetupFiles.add(setupPath);
    try {
      const setup = await readFile(setupPath, "utf-8");
      parts.push(await readEffectiveOrgContent(projectRoot, setupPath, setup, seenSetupFiles, depth + 1));
    } catch {
      // Best effort: unresolved setupfiles should not make task loading fail.
    }
  }

  parts.push(content.slice(cursor));
  return parts.join("\n");
}

import { readFile } from "node:fs/promises";
import { dirname } from "node:path";
import { extractOrgLinkTarget, getFileKeywords } from "./parser.ts";
import { resolveProjectPath } from "./paths.ts";

const MAX_SETUPFILE_DEPTH = 8;

/**
 * Return CONTENT preceded by all readable in-project #+SETUPFILE contents.
 *
 * Setupfiles are expanded recursively, in declaration order, with a small depth
 * guard and a visited set to break cycles. Missing, unreadable, and out-of-tree
 * setupfiles are ignored so fresh checkouts keep working just as they did when
 * only one setupfile was followed.
 */
export async function readEffectiveOrgContent(
  projectRoot: string,
  filePath: string,
  content: string,
  seenSetupFiles = new Set<string>(),
  depth = 0,
): Promise<string> {
  if (depth >= MAX_SETUPFILE_DEPTH) return content;

  const setupContents: string[] = [];
  for (const rawSetup of getFileKeywords(content, "SETUPFILE")) {
    const setupTarget = extractOrgLinkTarget(rawSetup) ?? rawSetup.trim();
    if (!setupTarget) continue;
    const setupPath = await resolveProjectPath(projectRoot, dirname(filePath), setupTarget);
    if (!setupPath || seenSetupFiles.has(setupPath)) continue;
    seenSetupFiles.add(setupPath);
    try {
      const setup = await readFile(setupPath, "utf-8");
      setupContents.push(await readEffectiveOrgContent(projectRoot, setupPath, setup, seenSetupFiles, depth + 1));
    } catch {
      // Best effort: unresolved setupfiles should not make task loading fail.
    }
  }

  return setupContents.length === 0 ? content : `${setupContents.join("\n")}\n${content}`;
}

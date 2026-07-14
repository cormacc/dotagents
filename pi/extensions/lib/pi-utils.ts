/** Shared helpers with verified active consumers. */
import { basename, dirname } from "node:path";
import { fileURLToPath } from "node:url";

/**
 * Derive an extension name from its module URL.
 *
 * - `extensions/my-ext/index.ts` → `my-ext`
 * - `extensions/my-ext.ts` → `my-ext`
 */
export function getExtensionName(importMetaUrl: string): string {
  const filePath = fileURLToPath(importMetaUrl);
  const fileName = basename(filePath, ".ts");
  return fileName === "index" ? basename(dirname(filePath)) : fileName;
}

/**
 * Nested context files for Pi.
 *
 * Pi loads AGENTS.md (and its variants) only from the working directory and its
 * ancestors. This extension delivers the ones below the working directory when a
 * tool touches a path that they govern:
 *
 * - `read`: appended to the read result, once per session.
 * - `edit` and `write`: the first call is blocked and the block reason carries
 *   the rules, so that the agent sees them before it writes.
 *
 * Both routes leave the system prompt, and so the provider prompt cache, alone.
 * Scope (Git-tracked files only) is in ./discovery.ts.
 */

import type { ExtensionAPI, ExtensionContext } from "@earendil-works/pi-coding-agent";
import { readFileSync } from "node:fs";
import { homedir } from "node:os";
import { relative } from "node:path";
import { governingContextFiles } from "./discovery.ts";

const WRITE_TOOLS = new Set(["edit", "write"]);

/** Pi's own path handling for its file tools: strip a leading `@`, expand `~`. */
export function toolPath(raw: unknown): string | undefined {
  if (typeof raw !== "string" || raw.length === 0) return undefined;
  const path = raw.startsWith("@") ? raw.slice(1) : raw;
  if (path === "~") return homedir();
  return path.startsWith("~/") ? homedir() + path.slice(1) : path;
}

export function render(cwd: string, files: Array<{ path: string; content: string }>): string {
  return files
    .map((f) => `<nested-context path="${relative(cwd, f.path)}">\n${f.content.trimEnd()}\n</nested-context>`)
    .join("\n\n");
}

export default function (pi: ExtensionAPI) {
  // Per extension instance, never module-wide: Pi creates a new instance for
  // each session (new, resume, fork, reload), so a delivery in one session
  // cannot suppress the same file in another.
  const delivered = new Set<string>();

  /**
   * The governing files not yet delivered in this session, read and marked as
   * delivered. Marking happens synchronously after discovery, so two calls in
   * one parallel batch cannot both deliver the same file.
   */
  async function take(ctx: ExtensionContext, raw: unknown) {
    const path = toolPath(raw);
    if (!path) return [];
    const files = await governingContextFiles(ctx.cwd, path);
    const fresh: Array<{ path: string; content: string }> = [];
    for (const file of files) {
      if (delivered.has(file)) continue;
      try {
        fresh.push({ path: file, content: readFileSync(file, "utf8").replace(/^\uFEFF/, "") });
        delivered.add(file);
      } catch {
        // An unreadable file is not delivered and stays eligible for a later touch.
      }
    }
    return fresh;
  }

  // Compaction can drop the delivered text, so deliver again on the next touch.
  pi.on("session_compact", async () => {
    delivered.clear();
  });

  pi.on("tool_call", async (event, ctx) => {
    if (!WRITE_TOOLS.has(event.toolName)) return;
    const fresh = await take(ctx, (event.input as { path?: unknown }).path);
    if (fresh.length === 0) return;
    return {
      block: true,
      reason:
        `This path is governed by nested context files that you have not seen. ` +
        `Apply their rules, then issue the ${event.toolName} call again.\n\n` +
        render(ctx.cwd, fresh),
    };
  });

  pi.on("tool_result", async (event, ctx) => {
    if (event.toolName !== "read" || event.isError) return;
    const fresh = await take(ctx, (event.input as { path?: unknown }).path);
    if (fresh.length === 0) return;
    return {
      content: [
        ...event.content,
        { type: "text" as const, text: `\n\nNested context files that govern this path:\n\n${render(ctx.cwd, fresh)}` },
      ],
    };
  });
}

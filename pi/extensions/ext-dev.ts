/**
 * Extension Development Helper
 *
 * Registers a `/ext` command that:
 * 1. Autocompletes extension names from getAgentDir()/extensions/
 * 2. Injects extension source into context for the LLM
 *
 * Documentation loading is handled by the ext-dev skill, which instructs
 * the agent to read the relevant pi docs on demand.
 *
 * Usage:
 *   /ext                  — List available extensions
 *   /ext my-ext           — Load source of the my-ext extension
 *   /ext my-ext do X      — Load source, with a specific instruction
 */

import type { ExtensionAPI } from "@mariozechner/pi-coding-agent";
import { getAgentDir, formatSize } from "@mariozechner/pi-coding-agent";
import { type AutocompleteItem, Box, Text } from "@mariozechner/pi-tui";
import * as fs from "node:fs";
import * as path from "node:path";

/**
 * List extension entries (dirs and .ts files) in the extensions/ folder.
 */
function listExtensions(agentDir: string): string[] {
  const extensionsDir = path.join(agentDir, "extensions");
  if (!fs.existsSync(extensionsDir)) return [];

  const entries = fs.readdirSync(extensionsDir, { withFileTypes: true });
  const results: string[] = [];

  for (const entry of entries) {
    if (entry.isDirectory()) {
      results.push(entry.name);
    } else if (entry.isFile() && entry.name.endsWith(".ts")) {
      results.push(entry.name.replace(/\.ts$/, ""));
    }
  }

  return results.sort();
}

/**
 * Read all files from an extension (single file or directory).
 */
function readExtensionSource(
  agentDir: string,
  name: string,
): { files: { path: string; content: string }[]; basePath: string } | null {
  const extensionsDir = path.join(agentDir, "extensions");
  const dirPath = path.join(extensionsDir, name);
  const filePath = path.join(extensionsDir, `${name}.ts`);

  if (fs.existsSync(dirPath) && fs.statSync(dirPath).isDirectory()) {
    const files = collectFiles(dirPath, "");
    return { files, basePath: dirPath };
  } else if (fs.existsSync(filePath)) {
    return {
      files: [
        { path: `${name}.ts`, content: fs.readFileSync(filePath, "utf-8") },
      ],
      basePath: extensionsDir,
    };
  }

  return null;
}

/**
 * Recursively collect all files in a directory.
 */
function collectFiles(
  dir: string,
  prefix: string,
): { path: string; content: string }[] {
  const results: { path: string; content: string }[] = [];
  const entries = fs.readdirSync(dir, { withFileTypes: true });

  for (const entry of entries) {
    const rel = prefix ? `${prefix}/${entry.name}` : entry.name;
    const full = path.join(dir, entry.name);

    if (
      entry.isDirectory() &&
      entry.name !== "node_modules" &&
      entry.name !== ".git"
    ) {
      results.push(...collectFiles(full, rel));
    } else if (entry.isFile() && !entry.name.endsWith(".lock")) {
      try {
        results.push({ path: rel, content: fs.readFileSync(full, "utf-8") });
      } catch {
        // skip binary / unreadable files
      }
    }
  }

  return results;
}

export default function (pi: ExtensionAPI) {
  const agentDir = getAgentDir();

  // --- Custom message renderer for extension source ---
  pi.registerMessageRenderer(
    "ext-dev-source",
    (message, { expanded }, theme) => {
      const details = message.details as
        | {
            extName: string;
            files: { path: string; size: number; preview: string }[];
            basePath: string;
            totalBytes: number;
          }
        | undefined;
      const extName = details?.extName ?? "?";
      const files = details?.files ?? [];
      const totalBytes = details?.totalBytes ?? 0;

      let text = theme.fg("toolTitle", theme.bold("/ext "));
      text += theme.fg("muted", "loaded extension ");
      text += theme.fg("accent", extName);
      text += theme.fg(
        "muted",
        ` — ${files.length} file${files.length !== 1 ? "s" : ""} (${formatSize(totalBytes)})`,
      );

      for (const file of files) {
        if (expanded) text += "\n";
        text += `\n  ${theme.fg("dim", file.path)} ${theme.fg("muted", formatSize(file.size))}`;
        if (expanded) {
          const lines = file.preview.split("\n").filter((l) => l.length > 0);
          for (const line of lines) {
            text += `\n    ${theme.fg("dim", line)}`;
          }
        }
      }

      if (expanded) {
        text += `\n\n  ${theme.fg("dim", details?.basePath ?? "")}`;
      }

      const box = new Box(1, 1, (t) => theme.bg("customMessageBg", t));
      box.addChild(new Text(text, 0, 0));
      return box;
    },
  );

  pi.registerCommand("ext", {
    description:
      "Load extension source into context. Use with no args to list extensions.",

    getArgumentCompletions: (prefix: string): AutocompleteItem[] | null => {
      const extNames = listExtensions(agentDir);
      const items: AutocompleteItem[] = extNames.map((name) => ({
        value: name,
        label: name,
        description: fs.existsSync(path.join(agentDir, "extensions", name))
          ? fs.statSync(path.join(agentDir, "extensions", name)).isDirectory()
            ? "directory"
            : "file"
          : "file",
      }));

      const filtered = items.filter((i) => i.value.startsWith(prefix));
      return filtered.length > 0 ? filtered : null;
    },

    handler: async (args, ctx) => {
      // --- Parse extension name from args (first word) ---
      const parts = args.trim().split(/\s+/);
      const extNames = listExtensions(agentDir);
      const extName =
        parts.length > 0 && extNames.includes(parts[0]) ? parts[0] : null;
      const freeText = extName ? parts.slice(1).join(" ").trim() : args.trim();

      // --- No extension specified: list available extensions ---
      if (!extName) {
        if (extNames.length === 0) {
          ctx.ui.notify(
            `No extensions found in ${path.join(agentDir, "extensions")}`,
            "warning",
          );
          return;
        }
        const list = extNames.map((n) => `  ${n}`).join("\n");
        ctx.ui.notify(`Available extensions:\n${list}`, "info");

        if (freeText) {
          pi.sendUserMessage(freeText);
        }
        return;
      }

      // --- Read extension source ---
      const PREVIEW_LINES = 5;
      const ext = readExtensionSource(agentDir, extName);
      if (!ext) {
        ctx.ui.notify(
          `Extension "${extName}" not found in ${path.join(agentDir, "extensions")}`,
          "error",
        );
        return;
      }

      const extFileDetails = ext.files.map((f) => ({
        path: f.path,
        size: Buffer.byteLength(f.content, "utf-8"),
        preview: f.content.split("\n").slice(0, PREVIEW_LINES).join("\n"),
      }));

      let extSource = `# Extension: ${extName}\nBase path: ${ext.basePath}\n`;
      for (const file of ext.files) {
        extSource += `\n## ${file.path}\n\n${file.content}\n`;
      }

      pi.sendMessage(
        {
          customType: "ext-dev-source",
          content: extSource,
          display: true,
          details: {
            extName,
            files: extFileDetails,
            basePath: ext.basePath,
            totalBytes: Buffer.byteLength(extSource, "utf-8"),
          },
        },
        { triggerTurn: false, deliverAs: "nextTurn" },
      );

      // --- Send user message to kick off the turn ---
      const prompt = freeText || `Help me work on the "${extName}" extension.`;
      pi.sendUserMessage(prompt);
    },
  });
}

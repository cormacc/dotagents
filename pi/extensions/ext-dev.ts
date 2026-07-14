/**
 * Extension development helper. `/ext` injects a bounded, production-focused
 * slice of one extension; tests are opt-in via --include-tests or --files.
 */

import type { ExtensionAPI } from "@mariozechner/pi-coding-agent";
import { formatSize, getAgentDir } from "@mariozechner/pi-coding-agent";
import { type AutocompleteItem, Box, Text } from "@mariozechner/pi-tui";
import * as fs from "node:fs";
import * as path from "node:path";

export const PER_FILE_LIMIT_BYTES = 32 * 1024;
export const AGGREGATE_LIMIT_BYTES = 96 * 1024;

const SKIP_DIRS = new Set([
  ".git", ".next", "build", "coverage", "dist", "node_modules", "target", "vendor",
]);
const TEST_DIRS = new Set(["__tests__", "fixtures", "test", "tests"]);
const TEXT_EXTENSIONS = new Set([
  ".cjs", ".clj", ".cljc", ".cljs", ".css", ".edn", ".html", ".js", ".json",
  ".jsx", ".md", ".mjs", ".nix", ".org", ".sh", ".ts", ".tsx", ".txt", ".yaml", ".yml",
]);

export interface ExtSourceFile {
  path: string;
  content: string;
  originalBytes: number;
  includedBytes: number;
  truncated: boolean;
}

export interface ExtSourceResult {
  files: ExtSourceFile[];
  omitted: Array<{ path: string; reason: string }>;
  basePath: string;
  totalBytes: number;
  selection: "default-production" | "include-tests" | "explicit";
}

export interface ExtSelection {
  includeTests?: boolean;
  files?: string[];
  perFileLimitBytes?: number;
  aggregateLimitBytes?: number;
}

export function listExtensions(agentDir: string): string[] {
  const extensionsDir = path.join(agentDir, "extensions");
  if (!fs.existsSync(extensionsDir)) return [];
  return fs.readdirSync(extensionsDir, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() || (entry.isFile() && entry.name.endsWith(".ts")))
    .map((entry) => entry.isDirectory() ? entry.name : entry.name.replace(/\.ts$/, ""))
    .sort();
}

function collectPaths(dir: string, prefix = ""): Array<{ relative: string; full: string }> {
  const results: Array<{ relative: string; full: string }> = [];
  for (const entry of fs.readdirSync(dir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
    const relative = prefix ? `${prefix}/${entry.name}` : entry.name;
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (!SKIP_DIRS.has(entry.name)) results.push(...collectPaths(full, relative));
    } else if (entry.isFile()) {
      results.push({ relative, full });
    }
  }
  return results;
}

function isTestPath(relative: string): boolean {
  const parts = relative.split("/");
  const base = parts.at(-1) ?? relative;
  return parts.some((part) => TEST_DIRS.has(part)) ||
    /(?:^|\.)test\.[^.]+$/.test(base) || /(?:^|\.)spec\.[^.]+$/.test(base) || base === "test.sh";
}

function defaultSkipReason(relative: string, includeTests: boolean): string | null {
  const base = path.posix.basename(relative);
  if (base.endsWith(".lock") || base === "package-lock.json") return "lockfile";
  if (!TEXT_EXTENSIONS.has(path.extname(base).toLowerCase()) && !["Dockerfile", "Makefile"].includes(base)) return "non-source";
  if (!includeTests && isTestPath(relative)) return "test (use --include-tests or --files)";
  return null;
}

function selectedExplicitly(relative: string, selections: string[]): boolean {
  return selections.some((selection) => {
    const normalized = selection.replace(/^\.\//, "").replace(/\\/g, "/");
    return relative === normalized || (normalized.endsWith("/") && relative.startsWith(normalized));
  });
}

function truncateUtf8(content: string, maxBytes: number): string {
  const bytes = Buffer.from(content, "utf8");
  if (bytes.length <= maxBytes) return content;
  return bytes.subarray(0, maxBytes).toString("utf8").replace(/\uFFFD$/, "");
}

export function readExtensionSource(
  agentDir: string,
  name: string,
  selection: ExtSelection = {},
): ExtSourceResult | null {
  const extensionsDir = path.join(agentDir, "extensions");
  const dirPath = path.join(extensionsDir, name);
  const filePath = path.join(extensionsDir, `${name}.ts`);
  const explicit = (selection.files ?? []).map((item) => item.trim()).filter(Boolean);
  const includeTests = selection.includeTests === true;
  const perFileLimit = selection.perFileLimitBytes ?? PER_FILE_LIMIT_BYTES;
  // Reserve 16 KiB for headings and deterministic omission/truncation reports
  // so the complete injected message, not just raw file payloads, stays bounded.
  const aggregateLimit = selection.aggregateLimitBytes ?? (AGGREGATE_LIMIT_BYTES - 16 * 1024);

  let basePath: string;
  let candidates: Array<{ relative: string; full: string }>;
  if (fs.existsSync(dirPath) && fs.statSync(dirPath).isDirectory()) {
    basePath = dirPath;
    candidates = collectPaths(dirPath);
  } else if (fs.existsSync(filePath) && fs.statSync(filePath).isFile()) {
    basePath = extensionsDir;
    candidates = [{ relative: `${name}.ts`, full: filePath }];
  } else {
    return null;
  }

  const files: ExtSourceFile[] = [];
  const omitted: Array<{ path: string; reason: string }> = [];
  let totalBytes = 0;

  for (const candidate of candidates) {
    if (explicit.length > 0 && !selectedExplicitly(candidate.relative, explicit)) {
      omitted.push({ path: candidate.relative, reason: "not selected" });
      continue;
    }
    if (explicit.length === 0) {
      const reason = defaultSkipReason(candidate.relative, includeTests);
      if (reason) {
        omitted.push({ path: candidate.relative, reason });
        continue;
      }
    }

    const raw = fs.readFileSync(candidate.full);
    if (raw.includes(0)) {
      omitted.push({ path: candidate.relative, reason: "binary" });
      continue;
    }
    if (totalBytes >= aggregateLimit) {
      omitted.push({ path: candidate.relative, reason: "aggregate limit" });
      continue;
    }

    const original = raw.toString("utf8");
    const originalBytes = raw.byteLength;
    const available = Math.min(perFileLimit, aggregateLimit - totalBytes);
    const content = truncateUtf8(original, available);
    const includedBytes = Buffer.byteLength(content, "utf8");
    files.push({
      path: candidate.relative,
      content,
      originalBytes,
      includedBytes,
      truncated: includedBytes < originalBytes,
    });
    totalBytes += includedBytes;
  }

  for (const requested of explicit) {
    if (!candidates.some((candidate) => selectedExplicitly(candidate.relative, [requested]))) {
      omitted.push({ path: requested, reason: "requested path not found" });
    }
  }

  return {
    files,
    omitted,
    basePath,
    totalBytes,
    selection: explicit.length > 0 ? "explicit" : includeTests ? "include-tests" : "default-production",
  };
}

export function parseExtArgs(args: string): { name: string | null; instruction: string; selection: ExtSelection } {
  const tokens = args.trim().split(/\s+/).filter(Boolean);
  const name = tokens.shift() ?? null;
  const instruction: string[] = [];
  const files: string[] = [];
  let includeTests = false;
  for (let index = 0; index < tokens.length; index++) {
    const token = tokens[index]!;
    if (token === "--include-tests") {
      includeTests = true;
    } else if (token === "--files") {
      files.push(...(tokens[++index] ?? "").split(",").filter(Boolean));
    } else if (token.startsWith("--files=")) {
      files.push(...token.slice("--files=".length).split(",").filter(Boolean));
    } else {
      instruction.push(token);
    }
  }
  return { name, instruction: instruction.join(" "), selection: { includeTests, files } };
}

export function buildContext(extName: string, ext: ExtSourceResult): string {
  const lines = [
    `# Extension: ${extName}`,
    `Base path: ${ext.basePath}`,
    `Selection: ${ext.selection}`,
    `Limits: ${formatSize(PER_FILE_LIMIT_BYTES)} per file, ${formatSize(AGGREGATE_LIMIT_BYTES)} aggregate`,
  ];
  for (const file of ext.files) {
    const suffix = file.truncated
      ? ` [truncated ${formatSize(file.originalBytes)} -> ${formatSize(file.includedBytes)}]`
      : "";
    lines.push(`\n## ${file.path}${suffix}\n\n${file.content}`);
  }
  if (ext.omitted.length > 0) {
    const shown = ext.omitted.slice(0, 100);
    lines.push(`\n## Omitted (${ext.omitted.length}; showing ${shown.length})`);
    for (const item of shown) lines.push(`- ${item.path} — ${item.reason}`);
  }
  const content = lines.join("\n") + "\n";
  if (Buffer.byteLength(content, "utf8") <= AGGREGATE_LIMIT_BYTES) return content;
  const marker = "\n[Extension context truncated at aggregate input ceiling]\n";
  return truncateUtf8(content, AGGREGATE_LIMIT_BYTES - Buffer.byteLength(marker, "utf8")) + marker;
}
}

export default function (pi: ExtensionAPI) {
  const agentDir = getAgentDir();

  pi.registerMessageRenderer("ext-dev-source", (message, { expanded }, theme) => {
    const details = message.details as { extName?: string; files?: ExtSourceFile[]; omitted?: number; basePath?: string; totalBytes?: number } | undefined;
    const files = details?.files ?? [];
    let text = theme.fg("toolTitle", theme.bold("/ext ")) +
      theme.fg("muted", "loaded extension ") + theme.fg("accent", details?.extName ?? "?") +
      theme.fg("muted", ` — ${files.length} files (${formatSize(details?.totalBytes ?? 0)}), ${details?.omitted ?? 0} omitted`);
    for (const file of files) {
      text += `\n  ${theme.fg("dim", file.path)} ${theme.fg("muted", formatSize(file.includedBytes))}`;
      if (file.truncated) text += theme.fg("warning", " truncated");
      if (expanded) text += `\n    ${theme.fg("dim", file.content.split("\n").slice(0, 5).join("\n    "))}`;
    }
    if (expanded) text += `\n\n  ${theme.fg("dim", details?.basePath ?? "")}`;
    const box = new Box(1, 1, (value) => theme.bg("customMessageBg", value));
    box.addChild(new Text(text, 0, 0));
    return box;
  });

  pi.registerCommand("ext", {
    description: "Load bounded extension source. Options: --include-tests, --files path[,path].",
    getArgumentCompletions: (prefix: string): AutocompleteItem[] | null => {
      const items = listExtensions(agentDir)
        .filter((name) => name.startsWith(prefix))
        .map((name) => ({ value: name, label: name, description: "extension" }));
      return items.length > 0 ? items : null;
    },
    handler: async (args, ctx) => {
      const parsed = parseExtArgs(args);
      const extNames = listExtensions(agentDir);
      if (!parsed.name || !extNames.includes(parsed.name)) {
        if (extNames.length === 0) {
          ctx.ui.notify(`No extensions found in ${path.join(agentDir, "extensions")}`, "warning");
        } else {
          ctx.ui.notify(`Available extensions:\n${extNames.map((name) => `  ${name}`).join("\n")}`, "info");
        }
        if (args.trim() && !parsed.name) pi.sendUserMessage(args.trim());
        return;
      }

      const ext = readExtensionSource(agentDir, parsed.name, parsed.selection);
      if (!ext) {
        ctx.ui.notify(`Extension "${parsed.name}" not found`, "error");
        return;
      }
      const content = buildContext(parsed.name, ext);
      pi.sendMessage({
        customType: "ext-dev-source",
        content,
        display: true,
        details: {
          extName: parsed.name,
          files: ext.files,
          omitted: ext.omitted.length,
          basePath: ext.basePath,
          totalBytes: ext.totalBytes,
        },
      }, { triggerTurn: false, deliverAs: "nextTurn" });
      pi.sendUserMessage(parsed.instruction || `Help me work on the "${parsed.name}" extension.`);
    },
  });
}

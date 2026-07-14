#!/usr/bin/env tsx
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { basename, dirname, extname, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const files: string[] = [];

function walk(dir: string): void {
  for (const entry of readdirSync(dir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
    if ([".git", ".agents", "node_modules", "archive"].includes(entry.name)) continue;
    const path = join(dir, entry.name);
    if (entry.isDirectory()) walk(path);
    else if ([".md", ".org"].includes(extname(entry.name))) files.push(path);
  }
}

for (const top of ["README.md", "AGENTS.md", "skills", join("pi", "agents"), join("pi", "skills"), join("pi", "extensions")]) {
  const path = join(root, top);
  if (existsSync(path)) {
    if (["README.md", "AGENTS.md"].includes(basename(path))) files.push(path);
    else walk(path);
  }
}

const failures: string[] = [];
function check(source: string, rawTarget: string): void {
  let target = rawTarget.trim().replace(/^file:/, "");
  if (!target || target === "..." || target === "path" || target.startsWith("#") || /^(?:https?|mailto|git|npm):/.test(target)) return;
  target = target.split("#", 1)[0]!.split("?", 1)[0]!;
  if (!target || target.startsWith("/") || target.startsWith("~") || /[<$*]/.test(target)) return;
  const resolved = resolve(dirname(source), decodeURIComponent(target));
  if (!resolved.startsWith(root + "/") && resolved !== root) return;
  if (!existsSync(resolved)) failures.push(`${relative(root, source)} -> ${rawTarget}`);
}

for (const file of [...new Set(files)].sort()) {
  const content = readFileSync(file, "utf8");
  for (const match of content.matchAll(/(?<!!)\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g)) check(file, match[1]!);
  for (const match of content.matchAll(/\[\[file:([^\]]+?)(?:\]\[[^\]]*\])?\]\]/g)) check(file, match[1]!);
}

if (failures.length > 0) {
  console.error("Dangling active relative links:\n" + failures.map((value) => `- ${value}`).join("\n"));
  process.exit(1);
}
console.log(`ok - active relative links (${new Set(files).size} documentation files)`);

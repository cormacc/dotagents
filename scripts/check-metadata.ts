#!/usr/bin/env tsx
import { existsSync, readFileSync, readdirSync } from "node:fs";
import { basename, dirname, join, relative, resolve } from "node:path";
import { parse } from "yaml";

async function main() {
const root = resolve(dirname(new URL(import.meta.url).pathname), "..");
const failures: string[] = [];
const fail = (message: string) => failures.push(message);

function walk(dir: string, predicate: (path: string) => boolean): string[] {
  const found: string[] = [];
  for (const entry of readdirSync(dir, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
    const path = join(dir, entry.name);
    if (entry.isDirectory() && entry.name !== "node_modules") found.push(...walk(path, predicate));
    else if (predicate(path)) found.push(path);
  }
  return found;
}

function frontmatter(path: string): Record<string, unknown> {
  const content = readFileSync(path, "utf8");
  const match = /^---\r?\n([\s\S]*?)\r?\n---/.exec(content);
  if (!match) {
    fail(`${relative(root, path)}: missing YAML frontmatter`);
    return {};
  }
  try {
    const value = parse(match[1]!);
    if (!value || typeof value !== "object" || Array.isArray(value)) throw new Error("frontmatter is not a mapping");
    return value as Record<string, unknown>;
  } catch (error) {
    fail(`${relative(root, path)}: invalid YAML: ${(error as Error).message}`);
    return {};
  }
}

const skillFiles = walk(join(root, "skills"), (path) => basename(path) === "SKILL.md");
const skillNames = new Map<string, string>();
for (const path of skillFiles) {
  const fm = frontmatter(path);
  const name = typeof fm.name === "string" ? fm.name : "";
  const description = typeof fm.description === "string" ? fm.description.trim() : "";
  if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(name) || name.length > 64) fail(`${relative(root, path)}: invalid skill name '${name}'`);
  if (!description || description.length > 1024) fail(`${relative(root, path)}: invalid description length`);
  if (skillNames.has(name)) fail(`${relative(root, path)}: duplicate skill name '${name}' (first: ${skillNames.get(name)})`);
  else skillNames.set(name, relative(root, path));
}

const topSkillFiles = skillFiles.filter((path) => dirname(dirname(path)) === join(root, "skills"));
const inventory = readFileSync(join(root, "skills", "README.org"), "utf8");
const inventoryPaths = [...inventory.matchAll(/\[\[file:([^\]]+\/SKILL\.md)\]\[[^\]]+\]\]/g)].map((match) => match[1]!);
const expectedInventory = topSkillFiles.map((path) => relative(join(root, "skills"), path)).sort();
if (JSON.stringify([...new Set(inventoryPaths)].sort()) !== JSON.stringify(expectedInventory)) {
  fail(`skills/README.org inventory differs from top-level SKILL.md files\n  expected: ${expectedInventory.join(", ")}\n  actual: ${[...new Set(inventoryPaths)].sort().join(", ")}`);
}

const gitlabChildren = skillFiles
  .filter((path) => dirname(dirname(path)) === join(root, "skills", "gitlab-cli-skills"))
  .map((path) => relative(join(root, "skills", "gitlab-cli-skills"), path))
  .sort();
const gitlabParent = readFileSync(join(root, "skills", "gitlab-cli-skills", "SKILL.md"), "utf8");
for (const child of gitlabChildren) {
  if (!gitlabParent.includes(`](${child})`)) fail(`GitLab router does not name ${child}`);
  const fm = frontmatter(join(root, "skills", "gitlab-cli-skills", child));
  const description = String(fm.description ?? "").toLowerCase();
  if (!description.includes("gitlab") && !description.includes("glab")) fail(`${child}: description lacks explicit GitLab/glab context`);
}
for (const removed of ["glab-help", "glab-version", "glab-check-update"]) {
  if (existsSync(join(root, "skills", "gitlab-cli-skills", removed))) fail(`trivial GitLab child still exists: ${removed}`);
}

const subagentDir = join(root, "skills", "herdr-subagents", "subagents");
const subagentPath = "skills/herdr-subagents/subagents";
const subagentFiles = readdirSync(subagentDir).filter((name) => name.endsWith(".md")).sort();
if (subagentFiles.length === 0) fail(`${subagentPath}/ contains no definitions`);
const allowedSubagentKeys = new Set(["name", "description", "kind", "model", "retro", "spawns", "requires"]);

function routingMetadataErrors(fm: Record<string, unknown>): string[] {
  const errors: string[] = [];
  const isNonEmptyString = (value: unknown) => typeof value === "string" && value.trim().length > 0;
  if ("kind" in fm && !isNonEmptyString(fm.kind)) errors.push("kind must be a non-empty string");
  if ("model" in fm && !isNonEmptyString(fm.model)) errors.push("model must be a non-empty string");
  return errors;
}

const invalidRoutingMetadataFixtures = [
  ["kind array", "kind: [pi]"],
  ["kind object", "kind: {name: pi}"],
  ["kind null", "kind: null"],
  ["kind empty", 'kind: ""'],
  ["model array", "kind: pi\nmodel: [sonnet]"],
  ["model object", "kind: pi\nmodel: {name: sonnet}"],
  ["model null", "kind: pi\nmodel: null"],
  ["model empty", 'kind: pi\nmodel: ""'],
] as const;
for (const [label, source] of invalidRoutingMetadataFixtures) {
  const errors = routingMetadataErrors(parse(source) as Record<string, unknown>);
  if (errors.length === 0) fail(`subagent routing-metadata fixture '${label}' unexpectedly passed`);
}

for (const file of subagentFiles) {
  const path = join(subagentDir, file);
  const fm = frontmatter(path);
  const stem = basename(file, ".md");
  if (fm.name !== stem) fail(`${subagentPath}/${file}: frontmatter name '${String(fm.name ?? "")}' does not match filename stem '${stem}'`);
  if (typeof fm.description !== "string" || !fm.description.trim()) fail(`${subagentPath}/${file}: description missing or empty`);
  for (const key of Object.keys(fm)) {
    if (!allowedSubagentKeys.has(key)) fail(`${subagentPath}/${file}: unsupported frontmatter key '${key}'`);
  }
  for (const error of routingMetadataErrors(fm)) fail(`${subagentPath}/${file}: ${error}`);
  const body = readFileSync(path, "utf8").replace(/^---\r?\n[\s\S]*?\r?\n---/, "");
  for (const legacy of ["subagent(", "subagent_kill", "subagent_resume"]) {
    if (body.includes(legacy)) fail(`${subagentPath}/${file}: persona body retains legacy subagent-tool reference '${legacy}'`);
  }
}

const extensionRoot = join(root, "pi", "extensions");
for (const entry of readdirSync(extensionRoot, { withFileTypes: true })) {
  if (entry.isFile() && /\.(?:test|spec)\.ts$/.test(entry.name)) {
    fail(`pi/extensions/${entry.name}: tests at the auto-discovery root are loaded as extension factories; move under pi/extensions/test/`);
  }
}
const extensionFiles = walk(extensionRoot, (path) => path.endsWith(".ts") && !path.endsWith(".test.ts") && !path.includes(`${join("pi", "archive")}`));
const commands = new Map<string, string>();
const tools = new Map<string, string>();
for (const path of extensionFiles) {
  const source = readFileSync(path, "utf8");
  for (const match of source.matchAll(/registerCommand\(\s*["']([^"']+)["']/g)) {
    const name = match[1]!;
    if (commands.has(name)) fail(`duplicate active command '${name}': ${commands.get(name)} and ${relative(root, path)}`);
    else commands.set(name, relative(root, path));
  }
  for (const match of source.matchAll(/(?:registerTool\(\s*(?:defineTool\(\s*)?|defineTool\(\s*)\{[\s\S]{0,300}?name:\s*["']([^"']+)["']/g)) {
    const name = match[1]!;
    if (tools.has(name)) fail(`duplicate active tool '${name}': ${tools.get(name)} and ${relative(root, path)}`);
    else tools.set(name, relative(root, path));
  }
}

if (failures.length > 0) {
  console.error(failures.map((message) => `not ok - ${message}`).join("\n"));
  process.exit(1);
}
console.log(`ok - ${skillFiles.length} skill definitions and ${topSkillFiles.length} inventory entries`);
console.log("ok - GitLab parent routes every retained nested command group");
console.log(`ok - ${subagentFiles.length} packaged subagent definitions carry valid advisory frontmatter`);
console.log(`ok - active extension names are collision-free (${commands.size} commands, ${tools.size} tools scanned)`);
}

main().catch((error) => {
  console.error(error instanceof Error ? error.stack : error);
  process.exit(1);
});

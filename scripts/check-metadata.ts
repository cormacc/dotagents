#!/usr/bin/env tsx
import { cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { basename, dirname, join, relative, resolve } from "node:path";
import { pathToFileURL } from "node:url";
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

const parserSource = process.env.PI_SUBAGENTS_SOURCE ?? join(homedir(), ".pi", "agent", "git", "github.com", "edxeth", "pi-subagents", "src", "agents", "definitions.ts");
if (!existsSync(parserSource)) {
  fail(`installed pi-subagents parser not found at ${parserSource}; set PI_SUBAGENTS_SOURCE`);
} else {
  const tmpRoot = join(root, ".agents", "tmp");
  mkdirSync(tmpRoot, { recursive: true });
  const configDir = mkdtempSync(join(tmpRoot, "agent-metadata-"));
  try {
    cpSync(join(root, "pi", "agents"), join(configDir, "agents"), { recursive: true });
    process.env.PI_CODING_AGENT_DIR = configDir;
    const parser = await import(`${pathToFileURL(parserSource).href}?check=${Date.now()}`) as {
      getEffectiveAgentDefinitions(cwd?: string): Array<Record<string, any>>;
    };
    const definitions = parser.getEffectiveAgentDefinitions(root);
    const byName = new Map(definitions.map((definition) => [definition.name, definition]));
    for (const file of readdirSync(join(root, "pi", "agents")).filter((name) => name.endsWith(".md"))) {
      const expected = basename(file, ".md");
      const fm = frontmatter(join(root, "pi", "agents", file));
      const name = String(fm.name ?? expected);
      if (!byName.has(name)) fail(`installed parser did not load pi/agents/${file} as '${name}'`);
    }

    const planner = byName.get("planner");
    if (planner?.spawning !== true) fail("planner must parse with spawning: true");
    const plannerBody = readFileSync(join(root, "pi", "agents", "planner.md"), "utf8");
    const examples = [...plannerBody.matchAll(/subagent\(\{([\s\S]*?)\}\);/g)].map((match) => match[1]!);
    if (examples.length === 0) fail("planner has no subagent examples");
    for (const [index, example] of examples.entries()) {
      const field = (name: string) => new RegExp(`${name}:\\s*"([^"]+)"`).exec(example)?.[1];
      const name = field("name");
      for (const required of ["agent", "name", "title", "task"]) if (!field(required)) fail(`planner spawn example ${index + 1} lacks ${required}`);
      if (name && !/^[a-z0-9]+(?:-[a-z0-9]+){1,3}$/.test(name)) fail(`planner spawn example ${index + 1} has invalid name '${name}'`);
    }

    const reviewer = byName.get("reviewer");
    if (reviewer?.tools !== "read, bash") fail("reviewer tool allowlist did not parse as read,bash");
    if (/Use the `write` tool|save the review/.test(readFileSync(join(root, "pi", "agents", "reviewer.md"), "utf8"))) fail("reviewer still instructs unavailable write output");

    const scout = byName.get("scout");
    if (scout?.tools !== "read, bash") fail("scout tool allowlist did not parse as read,bash");
    if (/^output:/m.test(readFileSync(join(root, "pi", "agents", "scout.md"), "utf8"))) fail("scout retains unsupported output frontmatter");

    const visual = byName.get("visual-tester");
    if (visual?.skills !== "chromium" || visual?.injectSkills !== "chromium") fail("visual-tester chromium skills/inject-skills did not parse");
    for (const tool of ["browser_nav", "browser_eval", "browser_tabs", "browser_screenshot", "browser_inspect", "browser_cookies", "browser_pick"]) {
      if (!String(visual?.tools ?? "").split(/\s*,\s*/).includes(tool)) fail(`visual-tester lacks ${tool}`);
    }
  } catch (error) {
    fail(`installed agent parser validation failed: ${(error as Error).stack ?? error}`);
  } finally {
    rmSync(configDir, { recursive: true, force: true });
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
console.log("ok - custom agents validate through the installed pi-subagents parser");
console.log(`ok - active extension names are collision-free (${commands.size} commands, ${tools.size} tools scanned)`);
}

main().catch((error) => {
  console.error(error instanceof Error ? error.stack : error);
  process.exit(1);
});

#!/usr/bin/env tsx
import { type ChildProcess, spawn } from "node:child_process";
import { delimiter, dirname, join } from "node:path";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { createInterface } from "node:readline";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));

function send(proc: ChildProcess, command: unknown): void {
  proc.stdin!.write(`${JSON.stringify(command)}\n`);
}

function waitFor(
  events: any[],
  predicate: (event: any) => boolean,
  diagnostics: () => string,
  since: number,
): Promise<any> {
  return new Promise((resolve, reject) => {
    const started = Date.now();
    const check = () => {
      const event = events.slice(since).find(predicate);
      if (event) return resolve(event);
      if (Date.now() - started > 15_000) {
        return reject(new Error(`timed out waiting for RPC event. ${diagnostics()}`));
      }
      setTimeout(check, 25);
    };
    check();
  });
}

async function main(): Promise<void> {
  const cwd = mkdtempSync(join(tmpdir(), "pi-traits-rpc-outside-repo-"));
  const agentDir = join(cwd, "agent");
  mkdirSync(agentDir, { recursive: true });
  const projectTraits = join(cwd, ".agents", "traits");
  mkdirSync(projectTraits, { recursive: true });
  writeFileSync(join(projectTraits, "focused.md"), "HOSTILE PROJECT OVERRIDE");
  const capture = join(cwd, "capture.ts");
  writeFileSync(capture, [
    'import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";',
    'export default function (pi: ExtensionAPI) {',
    '  pi.on("input", (event, ctx) => {',
    '    ctx.ui.notify(`capture:${event.text}`, "info");',
    '    return { action: "handled" };',
    '  });',
    '}',
    '',
  ].join("\n"));

  const env = { ...process.env, PI_CODING_AGENT_DIR: agentDir };
  delete env.NODE_PATH;
  delete env.NODE_OPTIONS;
  env.PATH = (env.PATH ?? "").split(delimiter)
    .filter((entry) => !entry.includes("/node_modules/.bin"))
    .join(delimiter);

  const pi = spawn("pi", [
    "--mode", "rpc",
    "--no-extensions",
    "--no-skills",
    "--no-prompt-templates",
    "--no-context-files",
    "--no-session",
    "--no-approve",
    "-e", join(here, "index.ts"),
    "-e", capture,
  ], { cwd, env, stdio: ["pipe", "pipe", "pipe"] });
  const events: any[] = [];
  const stderr: string[] = [];
  pi.stderr!.on("data", (chunk) => stderr.push(String(chunk)));
  const lines = createInterface({ input: pi.stdout! });
  lines.on("line", (line) => {
    try { events.push(JSON.parse(line)); } catch { /* diagnostics retain stderr */ }
  });
  const diagnostics = () => `stderr=${JSON.stringify(stderr.join(""))}; events=${JSON.stringify(events)}`;

  const cases = [
    { input: "%focused", includes: "Treat a layered prompt as one primary deliverable", excludes: "%focused" },
    { input: "page%focused %20", includes: "capture:page%focused %20" },
    { input: "%unknown-token", includes: "capture:%unknown-token" },
    { input: "%focused %unknown-token", includes: "%unknown-token", excludes: "%focused" },
  ];

  try {
    for (let index = 0; index < cases.length; index++) {
      const test = cases[index]!;
      const since = events.length;
      const id = `traits-${index}`;
      send(pi, { type: "prompt", id, message: test.input });
      const response = await waitFor(
        events,
        (event) => event.type === "response" && event.command === "prompt" && event.id === id,
        diagnostics,
        since,
      );
      if (!response.success) throw new Error(`prompt failed for ${test.input}: ${response.error}`);
      const notify = await waitFor(
        events,
        (event) => event.type === "extension_ui_request" && event.method === "notify",
        diagnostics,
        since,
      );
      const message = String(notify.message);
      if (!message.includes(test.includes)) {
        throw new Error(`expected ${JSON.stringify(test.includes)} for ${test.input}, got ${JSON.stringify(message)}`);
      }
      if (test.excludes && message.includes(test.excludes)) {
        throw new Error(`did not expect ${JSON.stringify(test.excludes)} for ${test.input}, got ${JSON.stringify(message)}`);
      }
      if (message.includes("HOSTILE PROJECT OVERRIDE")) {
        throw new Error(`untrusted project trait expanded for ${test.input}: ${JSON.stringify(message)}`);
      }
      console.log(`ok - untrusted-project RPC input reached the downstream handler: ${test.input}`);
    }
    if (/extension(?:-| )load error|failed to load extension/i.test(stderr.join(""))) {
      throw new Error(`extension load error: ${stderr.join("")}`);
    }
  } finally {
    pi.kill();
    await new Promise<void>((resolve) => pi.once("close", () => resolve()));
    rmSync(cwd, { recursive: true, force: true });
  }
}

main().catch((error) => {
  console.error(`not ok - ${error instanceof Error ? error.message : String(error)}`);
  process.exit(1);
});

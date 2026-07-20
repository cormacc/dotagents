#!/usr/bin/env tsx
/** RPC integration test for pi/extensions/systemprompt.ts. */

import { type ChildProcess, execFileSync, spawn } from "node:child_process";
import { mkdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { delimiter, dirname, join } from "node:path";
import { createInterface } from "node:readline";
import { fileURLToPath } from "node:url";

const __dirname = dirname(fileURLToPath(import.meta.url));

function waitForEvent(
  events: any[],
  predicate: (event: any) => boolean,
  diagnostics: () => string,
  timeout = 15_000,
): Promise<any> {
  return new Promise((resolve, reject) => {
    const started = Date.now();
    const check = () => {
      const event = events.find(predicate);
      if (event) return resolve(event);
      if (Date.now() - started > timeout) {
        return reject(new Error(`Timed out waiting for RPC event. ${diagnostics()}`));
      }
      setTimeout(check, 25);
    };
    check();
  });
}

function send(proc: ChildProcess, command: unknown) {
  proc.stdin!.write(`${JSON.stringify(command)}\n`);
}

async function main() {
  const tempDir = join(tmpdir(), `pi-systemprompt-test-${Date.now()}-${Math.random().toString(36).slice(2)}`);
  mkdirSync(tempDir, { recursive: true });
  const agentDir = join(tempDir, "agent");
  mkdirSync(agentDir, { recursive: true });

  const env = {
    ...process.env,
    HOME: tempDir,
    PI_CODING_AGENT_DIR: agentDir,
    // npx prepends this repository's older peer-dependency CLI. The test must
    // exercise the installed pi runtime that provides custom entry renderers.
    PATH: (process.env.PATH ?? "").split(delimiter)
      .filter((entry) => !entry.includes("/node_modules/.bin"))
      .join(delimiter),
  };
  for (const name of Object.keys(env)) {
    if (name === "PI_CODING_AGENT" || name.startsWith("PI_SUBAGENT_")) delete env[name];
  }
  const piVersion = execFileSync("pi", ["--version"], { encoding: "utf8", env }).trim();

  const pi = spawn("pi", [
    "--mode", "rpc",
    "--no-extensions",
    "--no-skills",
    "--no-prompt-templates",
    "--no-context-files",
    "--no-session",
    "--system-prompt", "The complete RPC system prompt.",
    "-e", join(__dirname, "..", "systemprompt.ts"),
  ], {
    cwd: tempDir,
    env,
    stdio: ["pipe", "pipe", "pipe"],
  });
  let closed = false;
  let closeDescription = "still running";
  pi.once("close", (code, signal) => {
    closed = true;
    closeDescription = `exited with code=${code}, signal=${signal}`;
  });
  const stderr: string[] = [];
  pi.stderr!.on("data", (chunk) => stderr.push(String(chunk)));
  const events: any[] = [];
  const output = createInterface({ input: pi.stdout! });
  output.on("line", (line) => {
    try {
      events.push(JSON.parse(line));
    } catch {
      // stdout is expected to be JSONL; ignore malformed process diagnostics.
    }
  });

  const diagnostics = () => `pi=${piVersion}; ${closeDescription}; stderr=${JSON.stringify(stderr.join(""))}; events=${JSON.stringify(events)}`;

  try {
    send(pi, { type: "get_entries" });
    const before = await waitForEvent(
      events,
      (event) => event.type === "response" && event.command === "get_entries",
      diagnostics,
    );
    const entryCount = before.data?.entries?.length;
    if (typeof entryCount !== "number") throw new Error("get_entries did not return an entry list");

    send(pi, { type: "prompt", message: "/systemprompt" });

    const notification = await waitForEvent(
      events,
      (event) => event.type === "extension_ui_request" && event.method === "notify",
      diagnostics,
    );
    if (!notification.message?.startsWith("The complete RPC system prompt.")) {
      throw new Error(`expected configured prompt in notification, got ${JSON.stringify(notification.message)}`);
    }

    await waitForEvent(
      events,
      (event) => event.type === "response" && event.command === "prompt",
      diagnostics,
    );
    send(pi, { type: "get_entries" });
    const entries = await waitForEvent(
      events,
      (event) => event.type === "response" && event.command === "get_entries",
      diagnostics,
    );
    if (entries.data?.entries?.length !== entryCount) {
      throw new Error(`RPC /systemprompt must not append session entries, got ${JSON.stringify(entries.data?.entries)}`);
    }

    console.log("ok - RPC /systemprompt emits notify output without session or context entries");
  } finally {
    if (!closed) {
      pi.kill();
      await new Promise<void>((resolve) => pi.once("close", () => resolve()));
    }
    rmSync(tempDir, { recursive: true, force: true });
  }
}

main().catch((error) => {
  console.error(`not ok - ${error instanceof Error ? error.message : String(error)}`);
  process.exit(1);
});

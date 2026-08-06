#!/usr/bin/env tsx
/**
 * RPC integration tests for the `/tasks` command's mode boundaries.
 *
 * `/tasks new` and `/tasks doctor` never call `ctx.ui.custom()`, so they
 * remain available in RPC mode. Bare `/tasks` opens the full overlay via
 * `ctx.ui.custom()`, which RPC does not implement (it resolves to
 * `undefined` immediately with no dialog shown), so it must report an
 * unsupported-mode notification instead of silently no-oping or trying (and
 * failing) to render an overlay.
 *
 * These are extension-command tests, not model tests: per docs/rpc.md
 * ("Extension commands"), `/tasks ...` executes immediately through the
 * command dispatcher before any prompt reaches the model, so the harness
 * starts the installed `pi` binary with no `--provider`/`--model` and no
 * generated dummy-provider extension.
 *
 * Framing follows docs/rpc.md ("Framing"): LF-only records with an optional
 * trailing `\r` stripped. Node `readline` is explicitly documented as
 * non-compliant there because it also splits on U+2028/U+2029, which are
 * valid inside JSON strings, so this harness decodes JSONL itself.
 *
 * Modeled on pi/extensions/emacsclient/pi-integration.test.ts.
 */

import { spawn, ChildProcess } from "node:child_process";
import { EventEmitter } from "node:events";
import { StringDecoder } from "node:string_decoder";
import { fileURLToPath } from "node:url";
import { delimiter, dirname, join } from "node:path";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";

const __dirname = dirname(fileURLToPath(import.meta.url));

// ---------------------------------------------------------------------------
// Test harness
// ---------------------------------------------------------------------------

let passed = 0;
let failed = 0;

function testPass(name: string) {
  console.log(`ok - ${name}`);
  passed++;
}

function testFail(name: string, reason?: string) {
  console.log(`not ok - ${name}`);
  if (reason) console.log(`  # ${reason}`);
  failed++;
}

// ---------------------------------------------------------------------------
// JSONL framing (docs/rpc.md "Framing")
// ---------------------------------------------------------------------------

interface LineStream {
  on(event: "data", listener: (chunk: Buffer | string) => void): unknown;
  on(event: "end", listener: () => void): unknown;
}

/** LF-only JSONL decoder. Strips a trailing `\r`; never splits on U+2028/U+2029. */
function attachJsonlReader(stream: LineStream, onLine: (line: string) => void): void {
  const decoder = new StringDecoder("utf8");
  let buffer = "";

  // Mirrors the installed reader's emitLine (src/modes/rpc/jsonl.ts): every
  // record is emitted regardless of length. Silently dropping a blank record
  // here would hide malformed RPC stdout instead of failing the test on it.
  const emit = (raw: string) => {
    onLine(raw.endsWith("\r") ? raw.slice(0, -1) : raw);
  };

  stream.on("data", (chunk: Buffer | string) => {
    buffer += typeof chunk === "string" ? chunk : decoder.write(chunk);
    let newlineIndex: number;
    while ((newlineIndex = buffer.indexOf("\n")) !== -1) {
      emit(buffer.slice(0, newlineIndex));
      buffer = buffer.slice(newlineIndex + 1);
    }
  });

  stream.on("end", () => {
    buffer += decoder.end();
    if (buffer.length > 0) emit(buffer);
  });
}

function testJsonlFraming() {
  const cases: Array<{
    name: string;
    feed: (push: (chunk: Buffer | string) => void, end: () => void) => void;
    expected: string[];
  }> = [
    {
      name: "single record per chunk",
      feed: (push, end) => {
        push('{"a":1}\n');
        end();
      },
      expected: ['{"a":1}'],
    },
    {
      name: "multiple records in one chunk",
      feed: (push, end) => {
        push('{"a":1}\n{"b":2}\n');
        end();
      },
      expected: ['{"a":1}', '{"b":2}'],
    },
    {
      name: "CRLF-terminated record is tolerated",
      feed: (push, end) => {
        push('{"a":1}\r\n');
        end();
      },
      expected: ['{"a":1}'],
    },
    {
      name: "multi-byte UTF-8 character split across chunks",
      feed: (push, end) => {
        const full = Buffer.from('{"msg":"caf\u00e9"}\n', "utf8");
        const splitAt = full.indexOf(0xc3); // inside the 2-byte encoding of 'é'
        push(full.subarray(0, splitAt + 1));
        push(full.subarray(splitAt + 1));
        end();
      },
      expected: ['{"msg":"café"}'],
    },
    {
      name: "final unterminated record is flushed on stream end",
      feed: (push, end) => {
        push('{"a":1}');
        end();
      },
      expected: ['{"a":1}'],
    },
    {
      // Matches the installed reader (src/modes/rpc/jsonl.ts): every record is
      // emitted regardless of length. A blank record is malformed RPC stdout
      // for the consumer to reject, not a framing artifact to swallow here.
      name: "blank record between two records is emitted, not dropped",
      feed: (push, end) => {
        push('{"a":1}\n\n{"b":2}\n');
        end();
      },
      expected: ['{"a":1}', "", '{"b":2}'],
    },
    {
      // docs/rpc.md ("Framing"): U+2028/U+2029 are valid inside JSON strings
      // and must stay inside their record; Node readline is documented as
      // non-compliant because it also splits on them.
      name: "U+2028/U+2029 inside a JSON string stay inside one record",
      feed: (push, end) => {
        push('{"msg":"line\u2028sep\u2029end"}\n');
        end();
      },
      expected: ['{"msg":"line\u2028sep\u2029end"}'],
    },
  ];

  for (const testCase of cases) {
    const received: string[] = [];
    const emitter = new EventEmitter();
    attachJsonlReader(emitter as unknown as LineStream, (line) => received.push(line));
    testCase.feed(
      (chunk) => emitter.emit("data", chunk),
      () => emitter.emit("end"),
    );
    const name = `JSONL framing: ${testCase.name}`;
    if (JSON.stringify(received) === JSON.stringify(testCase.expected)) {
      testPass(name);
    } else {
      testFail(name, `expected ${JSON.stringify(testCase.expected)}, got ${JSON.stringify(received)}`);
    }
  }
}

// ---------------------------------------------------------------------------
// Pi process management
// ---------------------------------------------------------------------------

/** Strips inherited Node loader/module-path state before spawning `pi`. */
function sanitizedEnv(cwd: string): NodeJS.ProcessEnv {
  const env: NodeJS.ProcessEnv = { ...process.env };
  delete env.NODE_PATH;
  delete env.NODE_OPTIONS;
  // A parent tsx/jiti loader shim on PATH must not leak into the spawned
  // pi's own module resolution; see the change-record's rejected
  // "inject NODE_PATH" alternative for why this caused the wrong package
  // surface to load during debugging.
  env.PATH = (env.PATH ?? "")
    .split(delimiter)
    .filter((entry) => !entry.includes("/node_modules/.bin"))
    .join(delimiter);
  env.HOME = cwd;
  env.PI_CODING_AGENT_DIR = join(cwd, ".pi-agent");
  return env;
}

/** Regression coverage for `sanitizedEnv`'s inherited-loader-state stripping. */
function testSanitizedEnv(): void {
  const name = "sanitizedEnv strips inherited loader/module-path state";
  const savedNodePath = process.env.NODE_PATH;
  const savedNodeOptions = process.env.NODE_OPTIONS;
  const savedPath = process.env.PATH;
  try {
    process.env.NODE_PATH = "/some/inherited/node/path";
    process.env.NODE_OPTIONS = "--require=/some/loader.js";
    process.env.PATH = ["/usr/bin", "/repo/node_modules/.bin", "/opt/bin"].join(delimiter);

    const cwd = "/tmp/pi-tasks-sanitized-env-example";
    const env = sanitizedEnv(cwd);

    const problems: string[] = [];
    if ("NODE_PATH" in env) problems.push(`NODE_PATH leaked: ${env.NODE_PATH}`);
    if ("NODE_OPTIONS" in env) problems.push(`NODE_OPTIONS leaked: ${env.NODE_OPTIONS}`);
    if ((env.PATH ?? "").split(delimiter).some((entry) => entry.includes("/node_modules/.bin"))) {
      problems.push(`node_modules/.bin leaked into PATH: ${env.PATH}`);
    }
    if (env.HOME !== cwd) problems.push(`HOME not isolated to cwd: ${env.HOME}`);
    if (env.PI_CODING_AGENT_DIR !== join(cwd, ".pi-agent")) {
      problems.push(`PI_CODING_AGENT_DIR not isolated: ${env.PI_CODING_AGENT_DIR}`);
    }

    if (problems.length > 0) {
      testFail(name, problems.join("; "));
    } else {
      testPass(name);
    }
  } finally {
    if (savedNodePath === undefined) delete process.env.NODE_PATH;
    else process.env.NODE_PATH = savedNodePath;
    if (savedNodeOptions === undefined) delete process.env.NODE_OPTIONS;
    else process.env.NODE_OPTIONS = savedNodeOptions;
    if (savedPath === undefined) delete process.env.PATH;
    else process.env.PATH = savedPath;
  }
}

interface Harness {
  proc: ChildProcess;
  events: unknown[];
  send(cmd: Record<string, unknown>): string;
  waitFor(predicate: (event: any) => boolean, label: string, opts?: { since: number }): Promise<any>;
  diagnostics(): string;
  dispose(): Promise<void>;
}

/**
 * Spawns `opts.command` (default: the installed `pi` binary) in RPC mode against `ext`.
 *
 * Process/stdin failure is tracked for the harness's whole lifetime, not only while a
 * `waitFor()` is active. Installed Pi's `RpcClient` (src/modes/rpc/rpc-client.ts) attaches
 * its process "error", stdin "error", and exit listeners once in `start()` and rejects all
 * pending work from them there, rather than scoping them to individual in-flight requests.
 * A per-`waitFor()` listener leaves an unhandled-error window (before the first `waitFor()`,
 * or between two calls in one scenario) in which a spawn or stdin error has zero listeners
 * and crashes the whole test process, since Node throws on an otherwise-unhandled
 * EventEmitter "error" event.
 */
function createHarness(ext: string, cwd: string, opts: { command?: string } = {}): Harness {
  const proc = spawn(opts.command ?? "pi", ["--mode", "rpc", "--no-session", "-e", ext], {
    stdio: ["pipe", "pipe", "pipe"],
    cwd,
    env: sanitizedEnv(cwd),
  });

  const events: any[] = [];
  const stderrChunks: string[] = [];
  const malformed: string[] = [];
  const closed = { value: false, code: null as number | null, signal: null as NodeJS.Signals | null };
  const failure: { value: boolean; error: Error | null } = { value: false, error: null };
  const pendingRejecters = new Set<(error: Error) => void>();

  function recordFailure(error: Error): void {
    if (!failure.value) {
      failure.value = true;
      failure.error = error;
    }
    for (const reject of pendingRejecters) reject(error);
  }

  proc.stderr!.on("data", (chunk) => stderrChunks.push(String(chunk)));
  proc.once("close", (code, signal) => {
    closed.value = true;
    closed.code = code;
    closed.signal = signal;
  });
  // Persistent, harness-lifetime failure listeners -- see the doc comment above.
  proc.on("error", (error) => recordFailure(new Error(`pi process error: ${error.message}`)));
  proc.stdin!.on("error", (error) => recordFailure(new Error(`pi stdin error: ${error.message}`)));
  attachJsonlReader(proc.stdout!, (line) => {
    try {
      events.push(JSON.parse(line));
    } catch (error: any) {
      malformed.push(`${line} (${error.message})`);
    }
  });

  let requestSeq = 0;
  function send(cmd: Record<string, unknown>): string {
    const id = typeof cmd.id === "string" ? cmd.id : `pi-tasks-test-${process.pid}-${++requestSeq}`;
    proc.stdin!.write(`${JSON.stringify({ ...cmd, id })}\n`);
    return id;
  }

  function diagnostics(): string {
    const state = closed.value
      ? `exited (code=${closed.code}, signal=${closed.signal})`
      : failure.value
        ? `errored (${failure.error?.message})`
        : "still running";
    return `pi ${state}; stderr=${JSON.stringify(stderrChunks.join(""))}; malformed=${JSON.stringify(malformed)}; events=${JSON.stringify(events)}`;
  }

  function waitFor(predicate: (event: any) => boolean, label: string, opts: { since?: number } = {}): Promise<any> {
    const since = opts.since ?? 0;
    const timeoutMs = 15000;
    return new Promise((resolve, reject) => {
      let settled = false;
      const finish = (fn: () => void) => {
        if (settled) return;
        settled = true;
        pendingRejecters.delete(rejectFromFailure);
        fn();
      };
      const rejectFromFailure = (error: Error) =>
        finish(() => reject(new Error(`${error.message} while waiting for ${label}. ${diagnostics()}`)));

      // Check the captured process/stdin failure cause before the generic
      // "closed" state: a spawn failure typically fires both "error" and
      // "close" almost simultaneously, and the failure cause is strictly more
      // diagnostic than "pi exited".
      if (failure.value && failure.error) {
        rejectFromFailure(failure.error);
        return;
      }
      if (closed.value) {
        finish(() => reject(new Error(`pi already exited before waiting for ${label}. ${diagnostics()}`)));
        return;
      }
      pendingRejecters.add(rejectFromFailure);

      const start = Date.now();
      const check = () => {
        if (settled) return;
        if (failure.value && failure.error) {
          rejectFromFailure(failure.error);
          return;
        }
        if (closed.value) {
          finish(() => reject(new Error(`pi exited before ${label}. ${diagnostics()}`)));
          return;
        }
        if (malformed.length > 0) {
          finish(() => reject(new Error(`malformed RPC stdout while waiting for ${label}: ${JSON.stringify(malformed)}. ${diagnostics()}`)));
          return;
        }
        for (let i = since; i < events.length; i++) {
          const event = events[i];
          if (event.type === "extension_error") {
            finish(() => reject(new Error(`extension_error while waiting for ${label}: ${JSON.stringify(event)}. ${diagnostics()}`)));
            return;
          }
          if (predicate(event)) {
            finish(() => resolve(event));
            return;
          }
        }
        if (Date.now() - start > timeoutMs) {
          finish(() => reject(new Error(`timed out waiting for ${label} after ${timeoutMs}ms. ${diagnostics()}`)));
          return;
        }
        setTimeout(check, 25);
      };
      check();
    });
  }

  async function dispose(): Promise<void> {
    if (closed.value) return;
    try {
      proc.stdin?.end();
    } catch {
      // stdin may already be gone if the process already exited
    }
    proc.kill();
    await new Promise<void>((resolve) => {
      if (closed.value) return resolve();
      proc.once("close", () => resolve());
    });
  }

  return { proc, events, send, waitFor, diagnostics, dispose };
}

async function runScenario(
  name: string,
  testFn: (harness: Harness) => Promise<boolean | string>,
  opts: { ext?: string; command?: string } = {},
) {
  const tempDir = mkdtempSync(join(tmpdir(), "pi-tasks-mode-test-"));
  writeFixtureTasksOrg(tempDir);
  const ext = opts.ext ?? join(__dirname, "index.ts");
  const harness = createHarness(ext, tempDir, { command: opts.command });

  // The losing side of this race (the scenario's own timeout, on success; the
  // testFn promise, on a timeout) must not keep the process alive: an
  // uncleared setTimeout retains its timer for the full 30s even after the
  // race already settled, which is exactly why a passing RPC suite took over
  // 30s overall instead of returning as soon as the final testFn resolved.
  let timeoutHandle: ReturnType<typeof setTimeout> | undefined;
  try {
    const result = await Promise.race([
      testFn(harness),
      new Promise<never>((_, reject) => {
        timeoutHandle = setTimeout(() => reject(new Error(`Test timeout (30s). ${harness.diagnostics()}`)), 30000);
      }),
    ]);

    if (result === true) {
      testPass(name);
    } else {
      testFail(name, (typeof result === "string" ? result : undefined) || "Test returned false");
    }
  } catch (err: any) {
    testFail(name, err.message);
  } finally {
    if (timeoutHandle !== undefined) clearTimeout(timeoutHandle);
    await harness.dispose();
    rmSync(tempDir, { recursive: true, force: true });
  }
}

function writeFixtureTasksOrg(cwd: string): void {
  mkdirSync(cwd, { recursive: true });
  writeFileSync(join(cwd, "TASKS.org"), "#+TITLE: fixture\n\n* Improvements\n", "utf-8");
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

(async function () {
  testJsonlFraming();
  testSanitizedEnv();

  await runScenario("/tasks new succeeds under the RPC harness", async (harness) => {
    const since = harness.events.length;
    const requestId = harness.send({ type: "prompt", message: "/tasks new" });

    // Correlate on the exact response id pi's RPC contract echoes back
    // (docs/rpc.md "All commands support an optional id field for
    // request/response correlation"), not merely type+command: a stale
    // response to an earlier command must not satisfy this wait.
    const response = await harness.waitFor(
      (e) => e.type === "response" && e.command === "prompt" && e.id === requestId,
      "prompt response",
      { since },
    );
    if (!response.success) return `prompt command rejected: ${response.error}`;

    const inputRequest = await harness.waitFor((e) => e.type === "extension_ui_request" && e.method === "input", "input request", { since });
    harness.send({ type: "extension_ui_response", id: inputRequest.id, value: "A new RPC task" });

    const notify = await harness.waitFor((e) => e.type === "extension_ui_request" && e.method === "notify", "notify", { since });
    if (!String(notify.message).startsWith("Created:")) {
      return `Expected a "Created: ..." notification, got ${JSON.stringify(notify)}`;
    }
    if (notify.notifyType === "error") {
      return `Expected a success notification, got an error: ${JSON.stringify(notify)}`;
    }

    return true;
  });

  await runScenario(
    "prompt response correlates on the exact request id, not just type+command",
    async (harness) => {
      const since = harness.events.length;
      const firstId = harness.send({ type: "prompt", message: "/tasks doctor" });
      const firstResponse = await harness.waitFor(
        (e) => e.type === "response" && e.command === "prompt" && e.id === firstId,
        "first prompt response",
        { since },
      );
      if (!firstResponse.success) return `first prompt command rejected: ${firstResponse.error}`;
      await harness.waitFor((e) => e.type === "extension_ui_request" && e.method === "notify", "first doctor notify", {
        since,
      });

      const secondId = harness.send({ type: "prompt", message: "/tasks doctor" });
      // Deliberately re-scan from the start of the whole event buffer (not
      // from a `since` boundary after the first command): a predicate that
      // matched on type+command alone would already be satisfied by the
      // *first* command's response event sitting earlier in that buffer,
      // which is exactly the stale-response hazard exact id correlation
      // must reject.
      const secondResponse = await harness.waitFor(
        (e) => e.type === "response" && e.command === "prompt" && e.id === secondId,
        "second prompt response",
        { since: 0 },
      );
      if (secondResponse.id !== secondId) {
        return `expected the response correlated to the second request id ${secondId}, got id ${secondResponse.id}`;
      }
      if (!secondResponse.success) return `second prompt command rejected: ${secondResponse.error}`;
      return true;
    },
  );

  await runScenario("/tasks doctor succeeds under the RPC harness", async (harness) => {
    const since = harness.events.length;
    const requestId = harness.send({ type: "prompt", message: "/tasks doctor" });

    const response = await harness.waitFor(
      (e) => e.type === "response" && e.command === "prompt" && e.id === requestId,
      "prompt response",
      { since },
    );
    if (!response.success) return `prompt command rejected: ${response.error}`;

    const notify = await harness.waitFor((e) => e.type === "extension_ui_request" && e.method === "notify", "notify", { since });
    if (notify.notifyType === "error" && !String(notify.message).length) {
      return `Expected a doctor report, got ${JSON.stringify(notify)}`;
    }

    return true;
  });

  await runScenario("bare /tasks reports unsupported mode outside TUI", async (harness) => {
    const since = harness.events.length;
    const requestId = harness.send({ type: "prompt", message: "/tasks" });

    const response = await harness.waitFor(
      (e) => e.type === "response" && e.command === "prompt" && e.id === requestId,
      "prompt response",
      { since },
    );
    if (!response.success) return `prompt command rejected: ${response.error}`;

    const notify = await harness.waitFor((e) => e.type === "extension_ui_request" && e.method === "notify", "notify", { since });
    if (notify.message !== "/tasks requires TUI mode; use /tasks new or /tasks doctor") {
      return `Expected unsupported-mode notification, got ${JSON.stringify(notify)}`;
    }
    if (notify.notifyType !== "error") {
      return `Expected notifyType "error", got ${JSON.stringify(notify)}`;
    }

    // Must not have attempted to open the (unsupported) overlay component.
    const customAttempt = harness.events
      .slice(since)
      .find((e: any) => e.type === "extension_ui_request" && e.method === "custom");
    if (customAttempt) return `Overlay must not attempt to open in RPC mode, got ${JSON.stringify(customAttempt)}`;

    return true;
  });

  // Acceptance: "Tests prove ... an early extension-load failure cannot
  // survive until the generic timeout", with the actual captured child cause.
  {
    const brokenExtDir = mkdtempSync(join(tmpdir(), "pi-tasks-broken-ext-"));
    const brokenExtPath = join(brokenExtDir, "broken.ts");
    writeFileSync(
      brokenExtPath,
      'export default function () {\n  throw new Error("synthetic-extension-load-failure");\n}\n',
      "utf-8",
    );
    try {
      await runScenario(
        "early extension-load failure fails fast with the captured child cause, not the generic timeout",
        async (harness) => {
          const start = Date.now();
          harness.send({ type: "prompt", message: "/tasks doctor" });
          try {
            await harness.waitFor((e) => e.type === "response" && e.command === "prompt", "prompt response", {
              since: 0,
            });
            return "expected the broken extension to fail pi startup, but a response was received";
          } catch (err: any) {
            const elapsed = Date.now() - start;
            if (elapsed > 5000) {
              return `failure took ${elapsed}ms; expected fast failure well under the 15s waitFor()/30s test timeout, got: ${err.message}`;
            }
            if (/timed out waiting/.test(err.message)) {
              return `expected the actual captured child cause, not a generic timeout message: ${err.message}`;
            }
            if (!/synthetic-extension-load-failure/.test(err.message)) {
              return `expected the captured child cause (stderr) in the failure message, got: ${err.message}`;
            }
            return true;
          }
        },
        { ext: brokenExtPath },
      );
    } finally {
      rmSync(brokenExtDir, { recursive: true, force: true });
    }
  }

  // Acceptance: a spawn/stdin failure is first-class harness state, not only
  // detected while a waitFor() happens to be active.
  await runScenario("a stdin error outside any active waitFor() is captured, not left unhandled", async (harness) => {
    // No waitFor() is active yet: without a persistent listener, this "error"
    // event would have zero listeners and crash the whole test process.
    harness.proc.stdin!.destroy(new Error("synthetic-stdin-destroy"));
    await new Promise((resolve) => setTimeout(resolve, 100));

    const start = Date.now();
    try {
      await harness.waitFor((e) => e.type === "response", "any response after a stdin failure", {
        since: harness.events.length,
      });
      return "expected waitFor() to reject after a persistent stdin failure";
    } catch (err: any) {
      const elapsed = Date.now() - start;
      if (elapsed > 5000) return `rejection took ${elapsed}ms; expected an immediate failure, not a timeout`;
      if (!/stdin error/i.test(err.message)) return `expected the captured stdin error cause, got: ${err.message}`;
      return true;
    }
  });

  await runScenario(
    "a spawn error is captured across the harness lifetime, not left unhandled",
    async (harness) => {
      await new Promise((resolve) => setTimeout(resolve, 200));
      const start = Date.now();
      try {
        await harness.waitFor((e) => e.type === "response", "any response after a spawn failure", { since: 0 });
        return "expected waitFor() to reject after a persistent spawn failure";
      } catch (err: any) {
        const elapsed = Date.now() - start;
        if (elapsed > 5000) return `rejection took ${elapsed}ms; expected an immediate failure, not a timeout`;
        if (!/pi process error/i.test(err.message)) {
          return `expected the captured process error cause, got: ${err.message}`;
        }
        return true;
      }
    },
    { command: "pi-tasks-test-nonexistent-binary" },
  );

  console.log(`\n# ${passed} passed, ${failed} failed`);
  if (failed > 0) process.exit(1);
})();

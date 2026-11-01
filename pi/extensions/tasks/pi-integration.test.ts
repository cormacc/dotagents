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

import { attachJsonlReader, sanitizedEnv, createHarness, type Harness } from "../test/support/rpc.ts";
import { EventEmitter } from "node:events";
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

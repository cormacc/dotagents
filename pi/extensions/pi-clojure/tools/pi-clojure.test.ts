#!/usr/bin/env tsx
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { createServer } from "node:net";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { DEFAULT_MAX_BYTES, DEFAULT_MAX_LINES } from "@earendil-works/pi-coding-agent";
import bencode from "bencode";
import { createEvalExpr } from "../nrepl-client.ts";
import { createEvalTool } from "./eval.ts";
import { createFindNreplPortTool } from "./find-nrepl-port.ts";
import { parenRepairTool } from "./paren-repair.ts";

class FakeSocket extends EventEmitter {
  destroyed = false;
  destroyCount = 0;

  connect(_port: number, _host: string, _listener: () => void) {
    // Deliberately never connect: this exercises the connection-phase timeout.
  }

  write(_data: Buffer) {}

  destroy() {
    this.destroyed = true;
    this.destroyCount++;
    this.emit("close");
  }
}

class ConnectedFakeSocket extends FakeSocket {
  override connect(_port: number, _host: string, listener: () => void) {
    queueMicrotask(listener);
  }
}

class ErrorFakeSocket extends FakeSocket {
  override connect(_port: number, _host: string, _listener: () => void) {
    queueMicrotask(() => this.emit("error", new Error("connection refused")));
  }
}

async function withNreplServer(fn: (port: number, evalNamespaces: string[]) => Promise<void>) {
  const evalNamespaces: string[] = [];
  const server = createServer((socket) => {
    let buffer = Buffer.alloc(0);
    socket.on("data", (chunk) => {
      buffer = Buffer.concat([buffer, Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)]);
      try {
        const message = bencode.decode(buffer) as Record<string, unknown>;
        buffer = Buffer.alloc(0);
        const text = (value: unknown) => value instanceof Uint8Array ? Buffer.from(value).toString("utf8") : String(value ?? "");
        const op = text(message.op);
        if (op === "clone") {
          const response = bencode.encode({
            id: message.id as Uint8Array,
            "new-session": "test-session",
            nested: { status: ["ok"] },
          });
          socket.write(response.subarray(0, 5));
          socket.write(response.subarray(5));
          return;
        }
        if (op === "eval") {
          evalNamespaces.push(text(message.ns));
          const value = bencode.encode({ id: message.id, value: "3", out: "stdout", err: "stderr" });
          const done = bencode.encode({ id: message.id, status: ["done"] });
          socket.write(Buffer.concat([value, done]));
        }
      } catch {
        // Wait until the complete bencode message arrives.
      }
    });
  });

  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  assert(address && typeof address !== "string");
  try {
    await fn(address.port, evalNamespaces);
  } finally {
    await new Promise<void>((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  }
}

async function expectReject(promise: Promise<unknown>, pattern: RegExp) {
  await assert.rejects(promise, pattern);
}

async function main() {
  await withNreplServer(async (port, namespaces) => {
    const result = await createEvalExpr()({
      host: "127.0.0.1",
      port,
      code: "(+ 1 2)",
      ns: "example.core",
      timeout: 1_000,
    });
    assert.deepEqual(result, { vals: ["3"], out: "stdout", err: "stderr" });
    assert.deepEqual(namespaces, ["example.core"]);
  });
  console.log("ok - forwards namespaces and decodes nested, chunked bencode responses");

  const timeoutSocket = new FakeSocket();
  await expectReject(
    createEvalExpr(() => timeoutSocket)({ host: "localhost", port: 1, code: "nil", timeout: 10 }),
    /timed out after 10ms/,
  );
  assert(timeoutSocket.destroyed && timeoutSocket.destroyCount === 1, "connection timeout must settle and destroy the socket once");
  console.log("ok - connection timeout starts before socket connection");

  const responseTimeoutSocket = new ConnectedFakeSocket();
  await expectReject(
    createEvalExpr(() => responseTimeoutSocket)({ host: "localhost", port: 1, code: "nil", timeout: 10 }),
    /timed out after 10ms/,
  );
  assert(responseTimeoutSocket.destroyed, "response timeout must destroy the socket");
  console.log("ok - response timeout is covered by the same budget");

  const errorSocket = new ErrorFakeSocket();
  await expectReject(
    createEvalExpr(() => errorSocket)({ host: "localhost", port: 1, code: "nil", timeout: 1_000 }),
    /connection refused/,
  );
  assert(errorSocket.destroyed, "connection failure must destroy the socket");
  console.log("ok - connection failure settles and cleans up the socket");

  const abortSocket = new FakeSocket();
  const controller = new AbortController();
  const aborted = createEvalExpr(() => abortSocket)({
    host: "localhost",
    port: 1,
    code: "nil",
    timeout: 1_000,
    signal: controller.signal,
  });
  controller.abort();
  await expectReject(aborted, /aborted/);
  assert(abortSocket.destroyed && abortSocket.destroyCount === 1, "abort must settle and destroy the socket once");
  console.log("ok - abort settles and cleans up the nREPL connection");

  const evalTool = createEvalTool(async (options) => {
    assert.equal(options.ns, "example.core");
    return { vals: ["value"], out: "x\n".repeat(DEFAULT_MAX_LINES + 10), err: "" };
  });
  const evalResult = await evalTool.execute(
    "test",
    { code: "nil", port: 1, ns: "example.core" },
    undefined,
    undefined,
    {} as never,
  );
  const evalText = evalResult.content[0]?.type === "text" ? evalResult.content[0].text : "";
  assert(evalText.split("\n").length <= DEFAULT_MAX_LINES);
  assert(Buffer.byteLength(evalText) <= DEFAULT_MAX_BYTES);
  assert(evalResult.details && !("vals" in (evalResult.details as object)), "details must not retain unbounded values");
  assert.deepEqual(Object.keys(evalResult.details as object), ["truncation"], "details must not duplicate bounded output");
  assert.equal(
    (evalResult.details as { truncation: { content: string } }).truncation.content,
    evalText,
    "truncation metadata must retain the rendered output",
  );
  console.log("ok - bounds eval content and avoids duplicate details output");

  const repaired = await parenRepairTool.execute("test", { code: "'(foo bar" }, undefined, undefined, {} as never);
  assert.match((repaired.content[0] as { text: string }).text, /Fixed delimiters/);
  const balanced = await parenRepairTool.execute("test", { code: "'(foo bar)" }, undefined, undefined, {} as never);
  assert.equal((balanced.content[0] as { text: string }).text, "Code is already balanced");
  const hugeRepair = await parenRepairTool.execute("test", { code: "(foo ".repeat(20_000) }, undefined, undefined, {} as never);
  const hugeRepairText = (hugeRepair.content[0] as { text: string }).text;
  assert(Buffer.byteLength(hugeRepairText) <= DEFAULT_MAX_BYTES);
  assert((hugeRepair.details as { truncation: { truncated: boolean } }).truncation.truncated);
  console.log("ok - repairs quoted forms, preserves balanced input, and bounds repair output");

  const cwd = mkdtempSync(join(tmpdir(), "pi-clojure-port-"));
  try {
    writeFileSync(join(cwd, ".nrepl-port"), "65530\n", "utf8");
    const findTool = createFindNreplPortTool({
      cwd: () => cwd,
      validate: async () => false,
      defaultPorts: [],
    });
    await expectReject(findTool.execute("test", {}, undefined, undefined, {} as never), /discovery failed/);

    const abort = new AbortController();
    abort.abort();
    let probes = 0;
    const cancelledFind = createFindNreplPortTool({
      cwd: () => cwd,
      validate: async () => {
        probes++;
        return false;
      },
      defaultPorts: [1],
    });
    await expectReject(cancelledFind.execute("test", {}, abort.signal, undefined, {} as never), /aborted/);
    assert.equal(probes, 0, "cancelled discovery must not probe ports");

    const started = Date.now();
    const boundedFind = createFindNreplPortTool({
      cwd: () => cwd,
      validate: async () => new Promise<boolean>(() => {}),
      defaultPorts: [1, 2],
      validationTimeoutMs: 10,
    });
    await expectReject(boundedFind.execute("test", {}, undefined, undefined, {} as never), /discovery failed/);
    assert(Date.now() - started < 200, "each unresponsive probe must respect its validation budget");
  } finally {
    rmSync(cwd, { recursive: true, force: true });
  }
  console.log("ok - discovery handles failure, cancellation, and bounded probes");
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});

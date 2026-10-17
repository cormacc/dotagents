#!/usr/bin/env tsx
import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { createServer, type Socket } from "node:net";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { DEFAULT_MAX_BYTES, DEFAULT_MAX_LINES } from "@earendil-works/pi-coding-agent";
import bencode from "bencode";
import { createEvalExpr, createSessionClient } from "../nrepl-client.ts";
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

interface FakeServerState {
  /** Sessions the server knows. Clearing it simulates a server restart. */
  sessions: Map<string, string>;
  ops: Array<Record<string, string>>;
  clones: number;
  /** Interrupts the server processed, as [session, interrupt-id]. */
  interrupted: string[][];
}

/**
 * A minimal nREPL server. Sessions keep a current namespace: `(in-ns 'x)` sets
 * it and `*ns*` reads it. `(sleep)` never answers. Like JVM nREPL, it drops an
 * interrupt when the client closes the connection before the interrupt is
 * processed. Responses are split across writes to exercise bencode framing.
 */
async function withNreplServer(fn: (port: number, state: FakeServerState) => Promise<void>) {
  const state: FakeServerState = { sessions: new Map(), ops: [], clones: 0, interrupted: [] };
  const sockets = new Set<Socket>();
  const server = createServer((socket) => {
    sockets.add(socket);
    let open = true;
    socket.on("end", () => { open = false; });
    socket.on("close", () => { open = false; sockets.delete(socket); });
    let buffer = Buffer.alloc(0);
    socket.on("error", () => {});
    socket.on("data", (chunk) => {
      buffer = Buffer.concat([buffer, Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)]);
      let message: Record<string, unknown>;
      try {
        message = bencode.decode(buffer) as Record<string, unknown>;
      } catch {
        return; // Wait until the complete bencode message arrives.
      }
      buffer = Buffer.alloc(0);
      const text = (value: unknown) => value instanceof Uint8Array ? Buffer.from(value).toString("utf8") : String(value ?? "");
      const fields = Object.fromEntries(Object.entries(message).map(([k, v]) => [k, text(v)]));
      state.ops.push(fields);
      const id = fields.id;
      const send = (...responses: Record<string, unknown>[]) => {
        const encoded = Buffer.concat(responses.map((r) => bencode.encode({ id, ...r })));
        socket.write(encoded.subarray(0, 5));
        socket.write(encoded.subarray(5));
      };
      const session = fields.session;
      if (session && !state.sessions.has(session)) {
        send({ status: ["done", "unknown-session", "error"] });
        return;
      }
      switch (fields.op) {
        case "clone": {
          const created = `session-${++state.clones}`;
          state.sessions.set(created, "user");
          send({ "new-session": created, nested: { status: ["ok"] }, status: ["done"] });
          return;
        }
        case "close":
          state.sessions.delete(session);
          send({ status: ["done", "session-closed"] });
          return;
        case "interrupt":
          setTimeout(() => {
            if (!open) return;
            state.interrupted.push([session, fields["interrupt-id"]]);
            send({ status: ["done"] });
          }, 20);
          return;
        case "eval": {
          if (fields.code === "(sleep)") return;
          const inNs = /^\(in-ns '(.+)\)$/.exec(fields.code);
          if (inNs && session) state.sessions.set(session, inNs[1]);
          const current = fields.ns || (session ? state.sessions.get(session)! : "user");
          const value = fields.code === "*ns*" ? current : "3";
          send({ value, ns: current, out: "stdout", err: "stderr" }, { status: ["done"] });
          return;
        }
      }
    });
  });

  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const address = server.address();
  assert(address && typeof address !== "string");
  try {
    await fn(address.port, state);
  } finally {
    for (const socket of sockets) socket.destroy();
    await new Promise<void>((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  }
}

async function expectReject(promise: Promise<unknown>, pattern: RegExp) {
  await assert.rejects(promise, pattern);
}

const ops = (state: FakeServerState, op: string) => state.ops.filter((m) => m.op === op);

async function main() {
  await withNreplServer(async (port, state) => {
    const result = await createEvalExpr()({ host: "127.0.0.1", port, code: "(+ 1 2)", ns: "example.core", timeout: 1_000 });
    assert.deepEqual(result, { vals: ["3"], out: "stdout", err: "stderr" });
    assert.deepEqual(ops(state, "eval").map((m) => [m.ns, m.session]), [["example.core", undefined]]);
    assert.equal(ops(state, "clone").length, 0, "a one-shot eval must not clone a session");
  });
  console.log("ok - one-shot eval forwards the namespace, uses no session, and decodes chunked responses");

  await withNreplServer(async (port, state) => {
    const client = createSessionClient();
    const opts = { host: "127.0.0.1", port, timeout: 1_000 };
    const first = await client.eval({ ...opts, code: "(in-ns 'example.core)" });
    const second = await client.eval({ ...opts, code: "*ns*" });
    assert.equal(first.sessionStatus, "new");
    assert.equal(second.sessionStatus, "reused");
    assert.equal(first.session, second.session);
    assert.deepEqual(second.vals, ["example.core"], "session state must persist between evaluations");
    const scoped = await client.eval({ ...opts, code: "*ns*", ns: "other.ns" });
    assert.deepEqual(scoped.vals, ["other.ns"]);
    assert.deepEqual((await client.eval({ ...opts, code: "*ns*" })).vals, ["example.core"], "ns must apply to one evaluation only");
    assert.equal(state.clones, 1, "the session must be cloned once");
  });
  console.log("ok - session client keeps one persistent session per server");

  await withNreplServer(async (port, state) => {
    const client = createSessionClient();
    const opts = { host: "127.0.0.1", port, timeout: 1_000 };
    const [a, b] = await Promise.all([client.eval({ ...opts, code: "1" }), client.eval({ ...opts, code: "2" })]);
    assert.equal(a.session, b.session, "concurrent first calls must share one session");
    assert.equal(state.clones, 1);
  });
  console.log("ok - concurrent first evaluations share one clone");

  await withNreplServer(async (port, state) => {
    const client = createSessionClient();
    const opts = { host: "127.0.0.1", port, timeout: 1_000 };
    const before = await client.eval({ ...opts, code: "1" });
    state.sessions.clear(); // Simulate a server restart.
    const after = await client.eval({ ...opts, code: "*ns*" });
    assert.equal(after.sessionStatus, "replaced");
    assert.notEqual(after.session, before.session);
    assert.deepEqual(after.vals, ["user"]);
    assert.equal((await client.eval({ ...opts, code: "1" })).sessionStatus, "reused");
  });
  console.log("ok - an unknown session is replaced and the evaluation is retried");

  await withNreplServer(async (port, state) => {
    const client = createSessionClient();
    const opts = { host: "127.0.0.1", port, timeout: 1_000 };
    const before = await client.eval({ ...opts, code: "(in-ns 'example.core)" });
    const after = await client.eval({ ...opts, code: "*ns*", reset: true });
    assert.equal(after.sessionStatus, "new");
    assert.notEqual(after.session, before.session);
    assert.deepEqual(after.vals, ["user"]);
    await new Promise((resolve) => setTimeout(resolve, 50));
    assert.deepEqual(ops(state, "close").map((m) => m.session), [before.session], "reset must close the old session");
  });
  console.log("ok - reset closes the old session and starts a new one");

  await withNreplServer(async (port, state) => {
    const client = createSessionClient();
    const opts = { host: "127.0.0.1", port };
    const { session } = await client.eval({ ...opts, code: "1", timeout: 1_000 });
    await expectReject(client.eval({ ...opts, code: "(sleep)", timeout: 50 }), /timed out after 50ms/);
    const sleeper = ops(state, "eval").find((m) => m.code === "(sleep)")!;
    assert.deepEqual(
      state.interrupted,
      [[session, sleeper.id]],
      "a timed-out evaluation must be interrupted in its session",
    );
    assert.equal((await client.eval({ ...opts, code: "1", timeout: 1_000 })).session, session, "a timeout must keep the session");
  });
  console.log("ok - a timed-out evaluation is interrupted before it rejects, and the session is kept");

  await withNreplServer(async (port, state) => {
    const client = createSessionClient();
    const opts = { host: "127.0.0.1", port };
    const sleeper = client.eval({ ...opts, code: "(sleep)", timeout: 50 });
    const next = client.eval({ ...opts, code: "1", timeout: 1_000 });
    await expectReject(sleeper, /timed out/);
    assert.deepEqual((await next).vals, ["3"]);
    const order = state.ops.map((m) => m.op === "eval" ? m.code : m.op);
    assert.deepEqual(order, ["clone", "(sleep)", "interrupt", "1"], "the next eval must wait for the interrupt");
  });
  console.log("ok - evaluations for one server run in call order");

  await withNreplServer(async (port) => {
    const client = createSessionClient();
    const opts = { host: "127.0.0.1", port };
    const sleeper = client.eval({ ...opts, code: "(sleep)", timeout: 1_000 });
    const controller = new AbortController();
    const queued = client.eval({ ...opts, code: "1", timeout: 1_000, signal: controller.signal });
    controller.abort();
    await expectReject(queued, /aborted/);
    await expectReject(sleeper, /timed out/);
  });
  console.log("ok - a queued evaluation can be aborted while it waits");

  await withNreplServer(async (port, state) => {
    const client = createSessionClient();
    const { session } = await client.eval({ host: "127.0.0.1", port, code: "1", timeout: 1_000 });
    await client.closeAll();
    await client.closeAll();
    assert.deepEqual(ops(state, "close").map((m) => m.session), [session]);
    assert.equal((await client.eval({ host: "127.0.0.1", port, code: "1", timeout: 1_000 })).sessionStatus, "new");
  });
  await createSessionClient().closeAll();
  console.log("ok - closeAll closes each session once and tolerates repeats");

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
    return { vals: ["value"], out: "x\n".repeat(DEFAULT_MAX_LINES + 10), err: "", session: "s", sessionStatus: "reused" as const };
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
  assert.deepEqual(Object.keys(evalResult.details as object), ["truncation", "session", "sessionStatus"], "details must not duplicate bounded output");
  assert.equal(
    (evalResult.details as { truncation: { content: string } }).truncation.content,
    evalText,
    "truncation metadata must retain the rendered output",
  );
  console.log("ok - bounds eval content and avoids duplicate details output");

  const replacedTool = createEvalTool(async () => ({ vals: ["1"], out: "", err: "", session: "s2", sessionStatus: "replaced" as const }));
  const replaced = await replacedTool.execute("test", { code: "1", port: 1 }, undefined, undefined, {} as never);
  assert.match((replaced.content[0] as { text: string }).text, /^\[The previous nREPL session no longer exists.*\n=> 1$/s);
  const reusedTool = createEvalTool(async () => ({ vals: ["1"], out: "", err: "", session: "s", sessionStatus: "reused" as const }));
  const reused = await reusedTool.execute("test", { code: "1", port: 1 }, undefined, undefined, {} as never);
  assert.equal((reused.content[0] as { text: string }).text, "=> 1");
  console.log("ok - reports a replaced session only when session state was lost");

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

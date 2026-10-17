// SPDX-License-Identifier: EPL-2.0
// Copyright © 2026-present Marko Kocic <marko@euptera.com>

import * as net from "node:net";

let bencodeModule: typeof import("bencode")["default"] | null = null;
const bencodePromise = import("bencode").then((m) => {
  bencodeModule = m.default;
});

async function getBencode() {
  if (bencodeModule) return bencodeModule;
  await bencodePromise;
  return bencodeModule!;
}

interface NreplMessage {
  id?: string;
  op?: string;
  session?: string;
  code?: string;
  ns?: string;
  "new-session"?: string;
  "interrupt-id"?: string;
  status?: string[];
  value?: string;
  out?: string;
  err?: string;
}

interface NreplSocket {
  connect(port: number, host: string, listener: () => void): unknown;
  write(data: Buffer): unknown;
  destroy(): unknown;
  on(event: "error" | "close" | "data", listener: (...args: any[]) => void): unknown;
}

function isUint8Array(val: unknown): val is Uint8Array {
  return val != null && typeof val === "object" && (val as Uint8Array).constructor.name === "Uint8Array";
}

function bufferToString(val: unknown): unknown {
  if (val == null) return val;
  if (typeof val === "number") return String(val);
  if (Buffer.isBuffer(val) || isUint8Array(val)) {
    return Buffer.from(val as Uint8Array).toString("utf8");
  }
  if (Array.isArray(val)) return val.map(bufferToString);
  if (typeof val === "object") {
    const result: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(val as Record<string, unknown>)) {
      result[k] = bufferToString(v);
    }
    return result;
  }
  return val;
}

async function decodeMessage(data: Buffer): Promise<NreplMessage> {
  const b = await getBencode();
  const decoded = b.decode(data) as NreplMessage;
  return bufferToString(decoded) as NreplMessage;
}

async function encodeMessage(msg: NreplMessage): Promise<Buffer> {
  const b = await getBencode();
  return b.encode(msg);
}

let currentId = 0;
function nextId(): string {
  return String(++currentId);
}

export interface ConnectionOptions {
  host: string;
  port: number;
  /** Timeout in milliseconds. Covers connection establishment and the response. Default: 30000. */
  timeout?: number;
  /** Cancels connection establishment and the request. */
  signal?: AbortSignal;
}

export interface EvalOptions extends ConnectionOptions {
  code: string;
  ns?: string;
}

export interface EvalResult {
  vals: string[];
  out: string;
  err: string;
}

/** The nREPL server does not know the session, for example after a server restart. */
export class UnknownSessionError extends Error {
  constructor(session: string) {
    super(`nREPL session ${session} is unknown to the server`);
    this.name = "UnknownSessionError";
  }
}

// Find the end of a single bencode value starting at `start` in `data`.
// Returns the index one past the last byte, or -1 if incomplete/invalid.
function findValueEnd(data: Buffer, start: number): number {
  if (start >= data.length) return -1;
  const b = data[start];

  // Integer: i<digits>e
  if (b === 0x69) {
    const end = data.indexOf(0x65, start + 1);
    return end === -1 ? -1 : end + 1;
  }

  // String: <length>:<bytes>
  if (b >= 0x30 && b <= 0x39) {
    let i = start;
    let len = 0;
    while (i < data.length && data[i] >= 0x30 && data[i] <= 0x39) {
      len = len * 10 + (data[i] - 0x30);
      i++;
    }
    if (i >= data.length || data[i] !== 0x3a) return -1;
    const end = i + 1 + len;
    return end > data.length ? -1 : end;
  }

  // List (l) or Dict (d): <type><values...>e
  if (b === 0x6c || b === 0x64) {
    let i = start + 1;
    while (i < data.length) {
      if (data[i] === 0x65) return i + 1;
      i = findValueEnd(data, i);
      if (i === -1) return -1;
    }
  }

  return -1;
}

function findMessageEnd(data: Buffer): number {
  if (data.length === 0 || data[0] !== 0x64) return -1;
  return findValueEnd(data, 0);
}

function abortError(): Error {
  return new Error("nREPL eval aborted");
}

function asError(error: unknown): Error {
  return error instanceof Error ? error : new Error(String(error));
}

/**
 * Sends one request on a new connection and resolves with every response that
 * carries the request id, up to and including the `done` status. Timeout and
 * abort reject the request. When `interrupt` is true and the request was sent,
 * an nREPL `interrupt` for the request is sent before the connection closes,
 * so an interrupted eval does not keep its session busy. The connection stays
 * open until the server answers the interrupt, for at most INTERRUPT_WAIT_MS:
 * JVM nREPL 1.3.1 ignored an interrupt when the connection closed directly after it.
 */
export type Exchange = (
  opts: ConnectionOptions,
  message: NreplMessage,
  behaviour?: { interrupt?: boolean },
) => Promise<NreplMessage[]>;

const INTERRUPT_WAIT_MS = 1_000;

export function createExchange(createSocket: () => NreplSocket = () => new net.Socket()): Exchange {
  return async function exchange(opts, message, behaviour = {}) {
    const { host, port, signal } = opts;
    const timeoutMs = opts.timeout ?? 30_000;
    const id = nextId();
    const request: NreplMessage = { ...message, id };

    return new Promise((resolve, reject) => {
      const socket = createSocket();
      const responses: NreplMessage[] = [];
      let settled = false;
      let sent = false;
      let interruptId: string | undefined;
      let drainHandle: ReturnType<typeof setTimeout> | undefined;
      let buffer = Buffer.alloc(0);
      let processing = Promise.resolve();

      const timeoutHandle = setTimeout(() => {
        settle({ error: new Error(`nREPL eval timed out after ${timeoutMs}ms`), stop: true });
      }, timeoutMs);

      const onAbort = () => settle({ error: abortError(), stop: true });
      signal?.addEventListener("abort", onAbort, { once: true });

      let closed = () => {};
      const finish = () => {
        clearTimeout(drainHandle);
        interruptId = undefined;
        try {
          socket.destroy();
        } catch {
          // The operation has already settled; no further cleanup is needed.
        }
        closed();
      };

      // Resolves when the connection is closed. An interrupted request keeps
      // the connection until the server answers the interrupt.
      const close = (stop: boolean) => new Promise<void>((done) => {
        closed = done;
        if (!(stop && sent && behaviour.interrupt && request.session)) {
          finish();
          return;
        }
        encodeMessage({
          op: "interrupt",
          session: request.session,
          "interrupt-id": id,
          id: (interruptId = nextId()),
        })
          .then((encoded) => {
            if (!interruptId) return;
            drainHandle = setTimeout(finish, INTERRUPT_WAIT_MS);
            socket.write(encoded);
          })
          .catch(finish);
      });

      // A stopped request reports its error only after the connection closes,
      // so the next request on the session cannot overtake the interrupt.
      const settle = (outcome: { result?: NreplMessage[]; error?: Error; stop?: boolean }) => {
        if (settled) return;
        settled = true;
        clearTimeout(timeoutHandle);
        signal?.removeEventListener("abort", onAbort);
        void close(outcome.stop ?? false).then(() => {
          if (outcome.error) reject(outcome.error);
          else resolve(outcome.result!);
        });
      };

      const processBuffer = async () => {
        while ((!settled || interruptId) && buffer.length > 0) {
          const endIdx = findMessageEnd(buffer);
          if (endIdx === -1) return;
          const msg = await decodeMessage(buffer.subarray(0, endIdx));
          buffer = buffer.subarray(endIdx);
          if (interruptId && msg.id === interruptId && msg.status?.includes("done")) {
            finish();
            return;
          }
          if (settled || msg.id !== id) continue;
          responses.push(msg);
          if (msg.status?.includes("done")) settle({ result: responses });
        }
      };

      socket.on("error", (error: Error) => settle({ error }));
      socket.on("close", () => {
        if (!settled) settle({ error: new Error("Connection closed unexpectedly") });
        else if (interruptId) finish();
      });
      socket.on("data", (chunk: Buffer) => {
        processing = processing
          .then(async () => {
            if (settled && !interruptId) return;
            buffer = Buffer.concat([buffer, chunk]);
            await processBuffer();
          })
          .catch((error: unknown) => settle({ error: asError(error) }));
      });

      if (signal?.aborted) {
        onAbort();
        return;
      }

      try {
        socket.connect(port, host, () => {
          void encodeMessage(request)
            .then((encoded) => {
              if (settled) return;
              socket.write(encoded);
              sent = true;
            })
            .catch((error: unknown) => settle({ error: asError(error) }));
        });
      } catch (error) {
        settle({ error: asError(error) });
      }
    });
  };
}

function evalResult(responses: NreplMessage[]): EvalResult {
  const vals: string[] = [];
  let out = "";
  let err = "";
  for (const msg of responses) {
    if (msg.value) vals.push(msg.value);
    if (msg.out) out += msg.out;
    if (msg.err) err += msg.err;
  }
  return { vals, out, err };
}

function evalMessage(code: string, ns?: string, session?: string): NreplMessage {
  const message: NreplMessage = { op: "eval", code };
  if (ns) message.ns = ns;
  if (session) message.session = session;
  return message;
}

/**
 * Evaluates code once. The request names no session, so the server evaluates
 * it in a throwaway session and no session remains afterwards.
 */
export function createEvalExpr(createSocket?: () => NreplSocket): (opts: EvalOptions) => Promise<EvalResult> {
  const exchange = createExchange(createSocket);
  return async (opts) => evalResult(await exchange(opts, evalMessage(opts.code, opts.ns)));
}

export const evalExpr = createEvalExpr();

/** `new`: first session for this server; `reused`: existing session; `replaced`: the stored session was gone. */
export type SessionStatus = "new" | "reused" | "replaced";

export interface SessionEvalOptions extends EvalOptions {
  /** Close the stored session for this server and evaluate in a new one. */
  reset?: boolean;
}

export interface SessionEvalResult extends EvalResult {
  session: string;
  sessionStatus: SessionStatus;
}

export interface SessionClient {
  eval(opts: SessionEvalOptions): Promise<SessionEvalResult>;
  /** Closes every stored session on its server. Best effort; never rejects. */
  closeAll(): Promise<void>;
}

const CLOSE_TIMEOUT_MS = 1_000;

/**
 * Keeps one cloned nREPL session for each host and port, so that session state
 * (current namespace, `*1`, `*e`, `set!` bindings, a shadow-cljs CLJS REPL
 * selection) persists between evaluations. Sessions outlive their connections,
 * so each request uses its own connection. Evaluations for one server run one
 * at a time, in call order.
 */
export function createSessionClient(exchange: Exchange = createExchange()): SessionClient {
  const sessions = new Map<string, Promise<string>>();
  const servers = new Map<string, { host: string; port: number }>();
  const queues = new Map<string, Promise<void>>();

  // Runs tasks for one server in order. JVM nREPL 1.3.1 lost an eval that was
  // queued in a session behind an eval that was then interrupted, so the client
  // sends the next eval only after the previous request has finished.
  const serial = <T>(key: string, signal: AbortSignal | undefined, task: () => Promise<T>): Promise<T> => {
    const run = (queues.get(key) ?? Promise.resolve()).then(() => {
      if (signal?.aborted) throw abortError();
      return task();
    });
    const tail = run.then(() => {}, () => {});
    queues.set(key, tail);
    void tail.then(() => {
      if (queues.get(key) === tail) queues.delete(key);
    });
    if (!signal) return run;
    return new Promise<T>((resolve, reject) => {
      const onAbort = () => reject(abortError());
      signal.addEventListener("abort", onAbort, { once: true });
      run.then(resolve, reject).finally(() => signal.removeEventListener("abort", onAbort));
    });
  };

  const clone = async (opts: ConnectionOptions): Promise<string> => {
    const responses = await exchange(opts, { op: "clone" });
    const session = responses.find((msg) => msg["new-session"])?.["new-session"];
    if (!session) throw new Error("nREPL server did not create a session");
    return String(session);
  };

  const closeSession = async (host: string, port: number, session: Promise<string>) => {
    try {
      await exchange({ host, port, timeout: CLOSE_TIMEOUT_MS }, { op: "close", session: await session });
    } catch {
      // Best effort: the server can be gone, and the session with it.
    }
  };

  const acquire = (key: string, opts: ConnectionOptions): { session: Promise<string>; created: boolean } => {
    const existing = sessions.get(key);
    if (existing) return { session: existing, created: false };
    const session = clone(opts);
    sessions.set(key, session);
    servers.set(key, { host: opts.host, port: opts.port });
    session.catch(() => {
      if (sessions.get(key) === session) sessions.delete(key);
    });
    return { session, created: true };
  };

  const evalIn = async (opts: SessionEvalOptions, session: string): Promise<EvalResult> => {
    const responses = await exchange(opts, evalMessage(opts.code, opts.ns, session), { interrupt: true });
    if (responses.some((msg) => msg.status?.includes("unknown-session"))) throw new UnknownSessionError(session);
    return evalResult(responses);
  };

  async function evalSerially(key: string, opts: SessionEvalOptions): Promise<SessionEvalResult> {
    const stored = sessions.get(key);
    if (opts.reset && stored) {
      sessions.delete(key);
      void closeSession(opts.host, opts.port, stored);
    }

    const first = acquire(key, opts);
    let session = await first.session;
    let sessionStatus: SessionStatus = first.created ? "new" : "reused";
    try {
      return { ...(await evalIn(opts, session)), session, sessionStatus };
    } catch (error) {
      if (!(error instanceof UnknownSessionError)) throw error;
      if (sessions.get(key) === first.session) sessions.delete(key);
      const second = acquire(key, opts);
      session = await second.session;
      sessionStatus = "replaced";
      return { ...(await evalIn(opts, session)), session, sessionStatus };
    }
  }

  return {
    eval(opts) {
      const key = `${opts.host}:${opts.port}`;
      return serial(key, opts.signal, () => evalSerially(key, opts));
    },

    async closeAll() {
      const pending = [...sessions.entries()].map(([key, session]) => {
        const server = servers.get(key)!;
        return closeSession(server.host, server.port, session);
      });
      sessions.clear();
      await Promise.all(pending);
    },
  };
}

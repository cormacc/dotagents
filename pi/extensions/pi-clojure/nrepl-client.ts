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

export interface EvalOptions {
  host: string;
  port: number;
  code: string;
  ns?: string;
  /** Timeout in milliseconds. Covers connection establishment and evaluation. Default: 30000. */
  timeout?: number;
  /** Cancels connection establishment and evaluation. */
  signal?: AbortSignal;
}

export interface EvalResult {
  vals: string[];
  out: string;
  err: string;
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

export function createEvalExpr(createSocket: () => NreplSocket = () => new net.Socket()): (opts: EvalOptions) => Promise<EvalResult> {
  return async function evalExpr(opts: EvalOptions): Promise<EvalResult> {
    const { host, port, code, ns, signal } = opts;
    const timeoutMs = opts.timeout ?? 30_000;

    return new Promise((resolve, reject) => {
      const socket = createSocket();
      let cloneId: string | undefined;
      let evalId: string | undefined;
      const vals: string[] = [];
      let out = "";
      let err = "";
      let settled = false;
      let buffer = Buffer.alloc(0);
      let processing = Promise.resolve();

      const timeoutHandle = setTimeout(() => {
        settle({ error: new Error(`nREPL eval timed out after ${timeoutMs}ms`) });
      }, timeoutMs);

      const onAbort = () => settle({ error: abortError() });
      signal?.addEventListener("abort", onAbort, { once: true });

      const cleanup = () => {
        clearTimeout(timeoutHandle);
        signal?.removeEventListener("abort", onAbort);
        try {
          socket.destroy();
        } catch {
          // The operation has already settled; no further cleanup is needed.
        }
      };

      const settle = (outcome: { result?: EvalResult; error?: Error }) => {
        if (settled) return;
        settled = true;
        cleanup();
        if (outcome.error) reject(outcome.error);
        else resolve(outcome.result!);
      };

      const write = async (message: NreplMessage) => {
        const encoded = await encodeMessage(message);
        if (!settled) socket.write(encoded);
      };

      const processBuffer = async () => {
        while (!settled && buffer.length > 0) {
          const endIdx = findMessageEnd(buffer);
          if (endIdx === -1) return;
          const msg = await decodeMessage(buffer.subarray(0, endIdx));
          buffer = buffer.subarray(endIdx);

          if (msg.id === cloneId && msg["new-session"]) {
            const session = String(msg["new-session"]);
            evalId = nextId();
            const evalMsg: NreplMessage = { op: "eval", code, session, id: evalId };
            if (ns) evalMsg.ns = ns;
            await write(evalMsg);
            continue;
          }

          if (msg.id === evalId) {
            if (msg.value) vals.push(msg.value);
            if (msg.out) out += msg.out;
            if (msg.err) err += msg.err;
            if (msg.status?.includes("done")) {
              settle({ result: { vals, out, err } });
            }
          }
        }
      };

      socket.on("error", (error: Error) => settle({ error }));
      socket.on("close", () => {
        if (!settled) settle({ error: new Error("Connection closed unexpectedly") });
      });
      socket.on("data", (chunk: Buffer) => {
        processing = processing
          .then(async () => {
            if (settled) return;
            buffer = Buffer.concat([buffer, chunk]);
            await processBuffer();
          })
          .catch((error: unknown) => {
            settle({ error: error instanceof Error ? error : new Error(String(error)) });
          });
      });

      if (signal?.aborted) {
        onAbort();
        return;
      }

      try {
        socket.connect(port, host, () => {
          void write({ op: "clone", id: (cloneId = nextId()) }).catch((error: unknown) => {
            settle({ error: error instanceof Error ? error : new Error(String(error)) });
          });
        });
      } catch (error) {
        settle({ error: error instanceof Error ? error : new Error(String(error)) });
      }
    });
  };
}

export const evalExpr = createEvalExpr();

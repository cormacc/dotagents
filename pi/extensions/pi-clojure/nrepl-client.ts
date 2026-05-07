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
  /** Timeout in milliseconds. Default: 30000. */
  timeout?: number;
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
      if (data[i] === 0x65) return i + 1; // end marker
      i = findValueEnd(data, i);
      if (i === -1) return -1;
    }
    return -1; // incomplete
  }

  return -1; // unknown type
}

function findMessageEnd(data: Buffer): number {
  if (data.length === 0 || data[0] !== 0x64) return -1;
  return findValueEnd(data, 0);
}

export async function evalExpr(opts: EvalOptions): Promise<EvalResult> {
  const { host, port, code, ns } = opts;
  const timeoutMs = opts.timeout ?? 30_000;

  return new Promise((resolve, reject) => {
    const socket = new net.Socket();
    let cloneId: string | undefined;
    let evalId: string | undefined;
    const vals: string[] = [];
    let out = "";
    let err = "";
    let done = false;
    let timeoutHandle: ReturnType<typeof setTimeout> | undefined;

    const cleanup = () => {
      if (timeoutHandle !== undefined) {
        clearTimeout(timeoutHandle);
        timeoutHandle = undefined;
      }
      try {
        socket.destroy();
      } catch {
        // ignore
      }
    };

    const finish = (result: EvalResult) => {
      done = true;
      cleanup();
      resolve(result);
    };

    const fail = (error: Error) => {
      cleanup();
      reject(error);
    };

    socket.connect(port, host, async () => {
      timeoutHandle = setTimeout(() => {
        fail(new Error(`nREPL eval timed out after ${timeoutMs}ms`));
      }, timeoutMs);
      cloneId = nextId();
      socket.write(await encodeMessage({ op: "clone", id: cloneId }));
    });

    socket.on("error", fail);

    let buffer = Buffer.alloc(0);
    let offset = 0;

    socket.on("data", async (chunk: Buffer) => {
      buffer = Buffer.concat([buffer, chunk]);

      while (offset < buffer.length) {
        try {
          const remaining = buffer.subarray(offset);
          const endIdx = findMessageEnd(remaining);
          if (endIdx === -1) break; // incomplete message, wait for more data
          const msg = await decodeMessage(remaining.subarray(0, endIdx));
          offset += endIdx;

          // Handle clone response — send eval with optional ns
          if (msg.id === cloneId && msg["new-session"]) {
            const session = String(msg["new-session"]);
            evalId = nextId();
            const evalMsg: NreplMessage = { op: "eval", code, session, id: evalId };
            if (ns) evalMsg.ns = ns;
            socket.write(await encodeMessage(evalMsg));
            continue;
          }

          // Handle eval response
          if (msg.id === evalId) {
            if (msg.value) vals.push(msg.value);
            if (msg.out) out += msg.out;
            if (msg.err) err += msg.err;
            if (msg.status?.includes("done")) {
              finish({ vals, out, err });
              return;
            }
          }
        } catch {
          break; // incomplete or malformed message, wait for more data
        }
      }

      // Trim consumed bytes to prevent unbounded buffer growth
      if (offset > 0) {
        buffer = buffer.subarray(offset);
        offset = 0;
      }
    });

    socket.on("close", () => {
      if (!done) fail(new Error("Connection closed unexpectedly"));
    });
  });
}

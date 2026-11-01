import { type ChildProcess, spawn } from "node:child_process";
import { StringDecoder } from "node:string_decoder";
import { delimiter, join } from "node:path";

interface LineStream {
  on(event: "data", listener: (chunk: Buffer | string) => void): unknown;
  on(event: "end", listener: () => void): unknown;
}

/** LF-only JSONL decoder. Strips a trailing `\r`; never splits on U+2028/U+2029. */
export function attachJsonlReader(stream: LineStream, onLine: (line: string) => void): void {
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

/** Strips inherited Node loader/module-path state before spawning `pi`. */
export function sanitizedEnv(cwd: string): NodeJS.ProcessEnv {
  const env: NodeJS.ProcessEnv = { ...process.env };
  delete env.NODE_PATH;
  delete env.NODE_OPTIONS;
  for (const name of Object.keys(env)) {
    if (name === "PI_CODING_AGENT" || name.startsWith("PI_SUBAGENT_")) delete env[name];
  }
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

export interface Harness {
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
export function createHarness(ext: string, cwd: string, opts: { command?: string; args?: string[]; env?: NodeJS.ProcessEnv } = {}): Harness {
  const proc = spawn(opts.command ?? "pi", ["--mode", "rpc", "--no-session", ...(opts.args ?? []), "-e", ext], {
    stdio: ["pipe", "pipe", "pipe"],
    cwd,
    env: { ...sanitizedEnv(cwd), ...opts.env },
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

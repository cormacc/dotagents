/**
 * Emacsclient invocation. Runs emacsclient with --eval and returns the result.
 */

import {
  buildTransportElisp,
  parseEmacsclientTransportOutput,
  parseEmacsclientError,
} from "./elisp.ts";

export interface EmacsclientOptions {
  /** Path to emacsclient binary. Default: "emacsclient" */
  binary?: string;
  /** Path to emacs binary used for daemon startup. Default: "emacs" */
  daemonBinary?: string;
  /** Socket name or path for the Emacs server. Default: from EMACS_SOCKET_NAME env. */
  socketName?: string;
  /** Timeout in milliseconds. Default: 10000 */
  timeout?: number;
  /** AbortSignal for cancellation */
  signal?: AbortSignal;
  /** exec function (injected for testability, defaults to pi.exec) */
  exec: (
    cmd: string,
    args: string[],
    opts?: { signal?: AbortSignal; timeout?: number }
  ) => Promise<{ stdout: string; stderr: string; code: number | null }>;
}

export interface EmacsclientResult {
  success: boolean;
  data?: unknown;
  error?: string;
}

function socketArgs(options: EmacsclientOptions): string[] {
  const env = (globalThis as { [key: string]: unknown })["process"] as
    | { env?: Record<string, string | undefined> }
    | undefined;
  const socket = options.socketName ?? env?.env?.EMACS_SOCKET_NAME;
  return socket ? ["--socket-name", socket] : [];
}

/**
 * Ensure an Emacs server is reachable. If probing via emacsclient fails,
 * start `emacs --daemon` and poll until the server answers.
 */
export async function ensureEmacsServer(
  options: EmacsclientOptions
): Promise<boolean> {
  const binary = options.binary ?? "emacsclient";
  const daemonBinary = options.daemonBinary ?? "emacs";
  const timeout = options.timeout ?? 10000;
  const probeArgs = [...socketArgs(options), "-e", "t"];

  const probe = async () => {
    try {
      const result = await options.exec(binary, probeArgs, {
        signal: options.signal,
        timeout: Math.min(timeout, 2000),
      });
      return result.code === 0;
    } catch {
      return false;
    }
  };

  if (await probe()) return true;

  try {
    await options.exec(daemonBinary, ["--daemon"], {
      signal: options.signal,
      timeout: Math.max(timeout, 15000),
    });
  } catch {
    return false;
  }

  for (let i = 0; i < 10; i++) {
    await new Promise((resolve) => setTimeout(resolve, 300));
    if (await probe()) return true;
  }
  return false;
}

/**
 * Evaluate an elisp expression via emacsclient and return the parsed result.
 */
export async function emacsEval(
  elisp: string,
  options: EmacsclientOptions
): Promise<EmacsclientResult> {
  const binary = options.binary ?? "emacsclient";
  const timeout = options.timeout ?? 10000;

  const args: string[] = [...socketArgs(options), "--eval", buildTransportElisp(elisp)];

  try {
    const result = await options.exec(binary, args, {
      signal: options.signal,
      timeout,
    });

    if (result.code !== 0) {
      return {
        success: false,
        error: parseEmacsclientError(result.stderr || result.stdout),
      };
    }

    const parsed = parseEmacsclientTransportOutput(result.stdout);
    return { success: true, data: parsed };
  } catch (err) {
    return {
      success: false,
      error: err instanceof Error ? err.message : String(err),
    };
  }
}

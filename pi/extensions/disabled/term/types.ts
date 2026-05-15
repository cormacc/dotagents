/**
 * Shared types and utilities for the term extension.
 */

export type ExecFn = (
  cmd: string,
  args: string[],
  opts?: { timeout?: number },
) => Promise<{
  stdout: string;
  stderr: string;
  code: number | null;
  killed?: boolean;
}>;

export interface MonitorBackend {
  readonly label: string;

  /** Whether the monitor pane/window is currently visible. */
  isVisible(): Promise<boolean>;

  /** Human-readable monitor target identifier. */
  displayTarget(): string;

  /** Show the monitor attached to the given tmux session. */
  show(sessionName: string): Promise<boolean>;

  /** Reattach an existing visible monitor to another tmux session. */
  attachSession(sessionName: string): Promise<boolean>;

  /** Hide/destroy the visible monitor. */
  hide(): Promise<void>;

  /** Focus the visible monitor. */
  focus(): Promise<void>;

  /** Cleanup on shutdown. */
  cleanup(): Promise<void>;

  /** Optional backend-specific diagnostics shown by `/term status`. */
  getDebugInfo(): Promise<Record<string, string | number | boolean>>;
}

/** Default height of the monitor pane as a percentage of screen height. */
export const DEFAULT_PANE_HEIGHT_PCT = 25;

/** Shell-quote a string with single quotes. */
export const sq = (s: string): string => `'${s.replace(/'/g, "'\\''")}'`;

/** Sanitize a process/session name to safe alphanumeric + dash/underscore. */
export function sanitizeName(name: string): string {
  return name.replace(/[^a-zA-Z0-9_-]/g, "-").slice(0, 32) || "process";
}

/** Promise-based sleep. */
export const sleep = (ms: number): Promise<void> =>
  new Promise<void>((r) => setTimeout(r, ms));

/** Lightweight PATH check for backend status diagnostics. */
export async function commandExists(
  exec: ExecFn,
  command: string,
): Promise<boolean> {
  try {
    const r = await exec("bash", ["-lc", `command -v ${sq(command)} >/dev/null`], {
      timeout: 2000,
    });
    return r.code === 0;
  } catch {
    return false;
  }
}

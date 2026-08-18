/**
 * Wrapper around the `ot` (org-tasks) CLI.
 *
 * The pi tasks extension delegates protocol operations to `ot` via
 * stdout JSON. This module:
 *
 * - Resolves the `ot` binary on PATH plus the two fallback skill
 *   locations agreed in `design/log/2026-05-18-tasks-extension-ot-cli.org`
 *   (decision: `ot` discovery order).
 * - Spawns it as a child process with a per-call argv (no shell
 *   interpolation).
 * - Parses the contract envelope (`schema: "org-tasks/v1"`) and surfaces
 *   structured errors.
 *
 * Used by the LLM tool handlers and by the overlay's graph-loading /
 * mutation paths. Command-specific helpers below keep most extension
 * call sites from hand-parsing envelopes.
 */

import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { homedir } from "node:os";
import { delimiter, dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { getAgentPath } from "../lib/agent-paths.ts";

const HERE = dirname(fileURLToPath(import.meta.url));
const REPO_LOCAL_OT = join(HERE, "..", "..", "..", "skills", "org-tasks", "scripts", "ot");

/** Default skill-relative fallback paths, in resolution order. */

/** Cached resolved binary path. Reset by `clearOtBinaryCache` (testing). */
let cachedOtBinary: string | null | undefined;

/**
 * Locate the `ot` binary. Tries:
 *
 *   1. The literal name `ot` on PATH (handled by spawn).
 *   2. `<configured agent directory>/skills/org-tasks/scripts/ot`.
 *   3. `~/.agents/skills/org-tasks/scripts/ot`.
 *   4. The repo-local `skills/org-tasks/scripts/ot` (dev/test fallback).
 *
 * Returns the path to use as the spawn argv[0]. The PATH-lookup case
 * returns `"ot"` so spawn does its own resolution.
 *
 * Throws `OtNotFoundError` when neither PATH nor the fallback paths
 * point at an executable.
 */
export function resolveOtBinary(): string {
  if (cachedOtBinary !== undefined) {
    if (cachedOtBinary === null) {
      throw new OtNotFoundError();
    }
    return cachedOtBinary;
  }

  // First, defer to PATH. We can't reliably check PATH without
  // running which/where; instead the spawn step below will fail
  // with ENOENT if the literal name isn't found, and we retry the
  // fallbacks.
  const proc = (globalThis as { process?: { env?: Record<string, string | undefined> } }).process;
  const pathEnv = proc?.env?.PATH ?? "";
  const pathDirs = pathEnv.split(delimiter);
  const executableNames =
    process.platform === "win32"
      ? ["ot.bat", "ot.exe"]
      : ["ot"];
  for (const dir of pathDirs) {
    for (const executableName of executableNames) {
      const candidate = join(dir, executableName);
      if (existsSync(candidate)) {
        cachedOtBinary = candidate;
        return candidate;
      }
    }
  }
  const skillPathCandidates = [
    getAgentPath("skills", "org-tasks", "scripts", "ot"),
    join(homedir(), ".agents", "skills", "org-tasks", "scripts", "ot"),
    // Dev/test fallback for running this extension directly from a dotagents checkout.
    REPO_LOCAL_OT,
  ];
  for (const candidate of skillPathCandidates) {
    if (existsSync(candidate)) {
      cachedOtBinary = candidate;
      return candidate;
    }
  }
  cachedOtBinary = null;
  throw new OtNotFoundError();
}

export function clearOtBinaryCache(): void {
  cachedOtBinary = undefined;
}

/** Raised when the `ot` binary can't be located. */
export class OtNotFoundError extends Error {
  constructor() {
    super(
      "ot binary not found. Install via `bbin install io.github.cormacc/dotagents --as ot` " +
        "or place the dotagents skill on PATH.",
    );
    this.name = "OtNotFoundError";
  }
}

/** Successful contract envelope. */
export interface OtSuccess<T = unknown> {
  ok: true;
  schema: "org-tasks/v1";
  result: T;
  warnings: Array<{ code?: string; message: string }>;
}

/** Failure contract envelope. */
export interface OtFailure {
  ok: false;
  schema: "org-tasks/v1";
  error: {
    code: string;
    message: string;
    file?: string | null;
    line?: number | null;
    details?: Record<string, unknown>;
  };
}

export type OtEnvelope<T = unknown> = OtSuccess<T> | OtFailure;

export interface RunOtOptions {
  /** Project root override (becomes `--root`). */
  root?: string;
  /** Working directory for CLI root traversal. */
  cwd?: string;
  /** Pass through `--dry-run`. */
  dryRun?: boolean;
  /** Additional pre-command global flags, e.g. `--tasks <path>`. */
  globalArgs?: string[];
  /** Per-call timeout in ms. Default 10s. */
  timeoutMs?: number;
}

/**
 * Spawn `ot <cmd...>` and parse the JSON envelope. Stdout carries the
 * success envelope; stderr carries the failure envelope (or a free-form
 * error message when the CLI itself dies before emitting one).
 */
export async function runOt<T = unknown>(
  cmd: string[],
  opts: RunOtOptions = {},
): Promise<OtEnvelope<T>> {
  const binary = resolveOtBinary();
  const argv: string[] = ["--format", "json"];
  if (opts.root) argv.push("--root", opts.root);
  if (opts.dryRun) argv.push("--dry-run");
  if (opts.globalArgs?.length) argv.push(...opts.globalArgs);
  argv.push(...cmd);

  return await new Promise<OtEnvelope<T>>((resolve, reject) => {
    const isBbinWindowsWrapper =
      process.platform === "win32" &&
      binary.toLowerCase().endsWith(".bat");

    const spawnOptions = {
      cwd: opts.cwd,
      stdio: ["ignore", "pipe", "pipe"] as ["ignore", "pipe", "pipe"],
      env: {
        ...process.env,
        NO_COLOR: "1",
      },
    };

    const child = isBbinWindowsWrapper
      ? spawn(
          "bb",
          ["-f", binary.slice(0, -4), "--", ...argv],
          spawnOptions,
        )
      : spawn(
          binary,
          argv,
          spawnOptions,
        );
    const stdoutChunks: Buffer[] = [];
    const stderrChunks: Buffer[] = [];
    const timeout = setTimeout(() => {
      try { child.kill("SIGTERM"); } catch { /* already gone */ }
      reject(new Error(`ot ${cmd.join(" ")} timed out after ${opts.timeoutMs ?? 10000}ms`));
    }, opts.timeoutMs ?? 10000);

    child.stdout.on("data", (b: Buffer) => stdoutChunks.push(b));
    child.stderr.on("data", (b: Buffer) => stderrChunks.push(b));

    child.on("error", (err: Error & { code?: string }) => {
      clearTimeout(timeout);
      if (err.code === "ENOENT") {
        cachedOtBinary = null;
        reject(new OtNotFoundError());
      } else {
        reject(err);
      }
    });

    child.on("close", (code: number | null) => {
      clearTimeout(timeout);
    const stdout = Buffer.concat(stdoutChunks).toString("utf-8");
    const stderr = Buffer.concat(stderrChunks).toString("utf-8");
    const candidates = [stdout, stderr]
      .map((s) => s.trim())
      .filter((s) => s.length > 0);
    let parsed: OtEnvelope<T> | null = null;
    for (const candidate of candidates) {
      // First try the stream exactly as returned.
      try {
        parsed = JSON.parse(candidate) as OtEnvelope<T>;
        break;
      } catch {
        // Some dependencies may emit diagnostics before/after the
        // JSON envelope. Recover the envelope itself.
        const start = candidate.indexOf("{");
        const end = candidate.lastIndexOf("}");

        if (start >= 0 && end > start) {
          const jsonCandidate = candidate.slice(start, end + 1);

          try {
            parsed = JSON.parse(jsonCandidate) as OtEnvelope<T>;
            break;
          } catch {
            // Try the next stream.
          }
        }
      }
    }
    if (!parsed) {
      reject(
        new Error(
          `ot ${cmd.join(" ")} exited ${code} with non-JSON output:\n` +
          `stdout:\n${stdout.trim()}\n` +
          `stderr:\n${stderr.trim()}`,
        ),
      );
      return;
    }
    if (!parsed || typeof parsed !== "object" || !("schema" in parsed)) {
        reject(new Error(`ot ${cmd.join(" ")} produced an unrecognised envelope:\n${raw}`));
        return;
      }
      resolve(parsed);
    });
  });
}

/** Throw on failure; return the `result` payload on success. */
export async function runOtResult<T = unknown>(
  cmd: string[],
  opts: RunOtOptions = {},
): Promise<T> {
  const env = await runOt<T>(cmd, opts);
  if (env.ok) return env.result;
  const err = new OtCommandError(cmd.join(" "), env);
  throw err;
}

export class OtCommandError extends Error {
  constructor(
    public readonly command: string,
    public readonly envelope: OtFailure,
  ) {
    super(`ot ${command} failed: ${envelope.error.code}: ${envelope.error.message}`);
    this.name = "OtCommandError";
  }
}

export interface OtSourceContent {
  sourceContent?: string;
  effectiveSourceContent?: string;
}

export interface OtListResult<TTask = unknown> {
  tree: TTask[];
  rows: unknown[];
  selectedId: string | null;
  root: string;
  files: Record<string, string>;
  sources?: Record<string, OtSourceContent>;
}

export interface OtCreateTaskArgs {
  summary: string;
  section?: string;
  priorityName?: string;
  body?: string;
  parentId?: string;
  afterId?: string;
  local?: boolean;
  allowCreateSection?: boolean;
  id?: string;
  createdAt?: string;
  linkedIssues?: string[];
  labels?: string[];
  alsoScan?: string[];
  /** Override the CLI's `--tasks` file, used for imported/change-record sources. */
  sourcePath?: string;
  /**
   * Anchor task to place the new task relative to. When set, `ot` derives
   * parent/after/local/source itself, so `parentId`/`afterId`/`local`/
   * `sourcePath` should be left unset.
   */
  relativeTo?: string;
  /** Placement relative to {@link relativeTo}. Defaults to sibling in `ot`. */
  as?: "sibling" | "child";
}

export interface OtCreateResult {
  id: string;
  file: string;
  line: number;
}

export interface OtStatusResult {
  task: { id: string; summary: string };
  prevStatus: string;
  status: string;
  closed: string | null;
}

export interface OtPriorityResult {
  task: { id: string; summary: string };
  prevPriority: string | null;
  priority: string | null;
}

export interface OtDoctorResult<TFinding = unknown> {
  findings: TFinding[];
  counts: { error: number; warn: number };
}

export function otList<TTask = unknown>(opts: RunOtOptions = {}): Promise<OtListResult<TTask>> {
  return runOtResult<OtListResult<TTask>>(["list"], opts);
}

export function otSelectTask(id: string | null, opts: RunOtOptions = {}): Promise<unknown> {
  return runOtResult(id ? ["select", id] : ["select", "--clear"], opts);
}

export function otSetStatus(id: string, status: string, opts: RunOtOptions = {}): Promise<OtStatusResult> {
  return runOtResult<OtStatusResult>(["status", id, status], opts);
}

/**
 * Cycle a task's status forward/back. The canonical order lives in `ot`
 * (`lifecycle/status-cycle`); callers pass only a direction. Returns the same
 * envelope as {@link otSetStatus}, including the resolved `status`/`prevStatus`.
 */
export function otCycleStatus(
  id: string,
  direction: "forward" | "back",
  opts: RunOtOptions = {},
): Promise<OtStatusResult> {
  return runOtResult<OtStatusResult>(["status", id, "--cycle", direction], opts);
}

/**
 * Cycle a task's priority forward/back. The canonical order (including the
 * unset slot) lives in `ot` (`lifecycle/priority-cycle`): forward from unset
 * is A (highest), back from unset is D (lowest). Callers pass only a
 * direction and receive the resolved `priority`/`prevPriority`.
 */
export function otCyclePriority(
  id: string,
  direction: "forward" | "back",
  opts: RunOtOptions = {},
): Promise<OtPriorityResult> {
  return runOtResult<OtPriorityResult>(["priority", id, "--cycle", direction], opts);
}

export function otArchiveTask(id: string, opts: RunOtOptions = {}): Promise<unknown> {
  return runOtResult(["archive", id, "--yes"], opts);
}

/** Build the vector passed to `ot remove`; no shell construction is used. */
export function buildOtRemoveArgs(id: string, pruneBlockers: boolean): string[] {
  return ["remove", id, ...(pruneBlockers ? ["--prune-blockers"] : [])];
}

/**
 * Preview or remove a task subtree through the core CLI. The caller controls
 * `--dry-run`; inbound pruning is always an explicit vector argument.
 */
export function otRemoveTask(
  id: string,
  options: RunOtOptions & { pruneBlockers?: boolean } = {},
): Promise<OtRemoveResult> {
  const { pruneBlockers = false, ...opts } = options;
  const cmd = buildOtRemoveArgs(id, pruneBlockers);
  if (!opts.dryRun) cmd.push("--yes");
  return runOtResult<OtRemoveResult>(cmd, opts);
}

export function otPublishTask(id: string, opts: RunOtOptions = {}): Promise<unknown> {
  return runOtResult(["publish", id], opts);
}

export function otUnpublishTask(id: string, opts: RunOtOptions = {}): Promise<unknown> {
  return runOtResult(["unpublish", id], opts);
}

export function otDoctor<TFinding = unknown>(opts: RunOtOptions = {}): Promise<OtDoctorResult<TFinding>> {
  return runOtResult<OtDoctorResult<TFinding>>(["doctor"], opts);
}

export interface OtRemoveTask {
  id: string;
  summary: string;
  status: string;
  sourcePath: string;
}

export interface OtUncheckedCriterion {
  taskId: string;
  taskSummary: string;
  sourcePath: string;
  taskLine: number;
  criterion: string;
}

export interface OtBlocker {
  raw: string;
  kind: string;
  ref: string;
}

export interface OtInboundBlocker {
  taskId: string;
  taskSummary: string;
  sourcePath: string;
  blocker: OtBlocker;
}

/** Compact `ot remove` preview and completed-result payload. */
export interface OtRemoveResult {
  targetId: string;
  subtree: OtRemoveTask[];
  uncheckedCriteria: OtUncheckedCriterion[];
  inboundBlockers: OtInboundBlocker[];
  affectedFiles: string[];
  selection: { selectedId: string | null; cleared: boolean };
  prunedBlockers: OtInboundBlocker[];
  dryRun: boolean;
}

export interface OtBackfillResult {
  changed: number;
  changes: Array<{
    id: string;
    summary: string;
    status: string;
    file?: string | null;
    line?: number | null;
    created?: string | null;
  }>;
  dryRun: boolean;
}

export function otBackfill(opts: RunOtOptions = {}): Promise<OtBackfillResult> {
  return runOtResult<OtBackfillResult>(["backfill"], opts);
}

export function otCreateTask(
  args: OtCreateTaskArgs,
  opts: RunOtOptions = {},
): Promise<OtCreateResult> {
  const cmd = ["create", args.summary];
  if (args.section) cmd.push("--section", args.section);
  if (args.priorityName) cmd.push("--priority", args.priorityName);
  if (args.body) cmd.push("--body", args.body);
  if (args.relativeTo) {
    cmd.push("--relative-to", args.relativeTo);
    if (args.as) cmd.push("--as", args.as);
  } else {
    if (args.parentId) cmd.push("--parent", args.parentId);
    if (args.afterId) cmd.push("--after", args.afterId);
  }
  if (args.local) cmd.push("--local");
  if (args.allowCreateSection) cmd.push("--allow-create-section");
  if (args.id) cmd.push("--id", args.id);
  if (args.createdAt) cmd.push("--created-at", args.createdAt);
  for (const token of args.linkedIssues ?? []) {
    if (token) cmd.push("--linked-issue", token);
  }
  for (const label of args.labels ?? []) {
    if (label) cmd.push("--tag", label);
  }
  for (const scanPath of args.alsoScan ?? []) {
    if (scanPath) cmd.push("--also-scan", scanPath);
  }

  const globalArgs = [...(opts.globalArgs ?? [])];
  if (args.sourcePath && !args.local) {
    globalArgs.push("--tasks", args.sourcePath);
  }
  return runOtResult<OtCreateResult>(cmd, { ...opts, globalArgs });
}

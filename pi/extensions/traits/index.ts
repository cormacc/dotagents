import type {
  ExtensionAPI,
  InputEvent,
  InputEventResult,
} from "@earendil-works/pi-coding-agent";
import { spawn } from "node:child_process";
import { homedir } from "node:os";
import { join } from "node:path";

export interface ResolvedTrait {
  trait: string;
  source: string;
  path: string;
}

export interface TraitExpansion {
  text: string;
  resolved: ResolvedTrait[];
  incompatibilities: Record<string, string[]>;
  unknowns: string[];
  repeats: string[];
}

interface TraitsEnvelope {
  ok: boolean;
  schema: string;
  result?: TraitExpansion;
  error?: { message?: string };
}

/** Locate the co-installed CLI through the canonical global skills symlink. */
export function resolveTraitsBinary(home = homedir()): string {
  return join(home, ".agents", "skills", "herdr-orch", "scripts", "traits");
}

export interface TraitExpansionOptions {
  binary?: string;
  projectTrusted?: boolean;
  homeTraits?: string;
}

/** Invoke the shared interpolator without shell parsing. */
export async function expandTraits(
  text: string,
  cwd: string,
  options: TraitExpansionOptions = {},
): Promise<TraitExpansion> {
  const {
    binary = resolveTraitsBinary(),
    projectTrusted = false,
    homeTraits = join(homedir(), ".agents", "traits"),
  } = options;
  const args: string[] = [];
  if (projectTrusted) {
    args.push("--layer", `project=${join(cwd, ".agents", "traits")}`);
  }
  args.push("--layer", `home=${homeTraits}`);

  return await new Promise<TraitExpansion>((resolve, reject) => {
    const child = spawn(binary, args, { cwd, stdio: ["pipe", "pipe", "pipe"] });
    const stdout: Buffer[] = [];
    const stderr: Buffer[] = [];
    let settled = false;
    const timeout = setTimeout(() => {
      child.kill("SIGTERM");
      finishReject(new Error("trait interpolation timed out after 10000ms"));
    }, 10_000);

    const finishReject = (error: Error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      reject(error);
    };

    child.stdout.on("data", (chunk: Buffer) => stdout.push(chunk));
    child.stderr.on("data", (chunk: Buffer) => stderr.push(chunk));
    child.on("error", finishReject);
    child.on("close", (code) => {
      if (settled) return;
      settled = true;
      clearTimeout(timeout);
      const out = Buffer.concat(stdout).toString("utf8").trim();
      const err = Buffer.concat(stderr).toString("utf8").trim();
      let envelope: TraitsEnvelope;
      try {
        envelope = JSON.parse(out || err) as TraitsEnvelope;
      } catch {
        reject(new Error(`trait interpolation exited ${code} with non-JSON output: ${err || out}`));
        return;
      }
      if (code !== 0 || envelope.ok !== true || envelope.schema !== "herdr-orch/v1" || !envelope.result) {
        reject(new Error(envelope.error?.message || err || `trait interpolation exited ${code}`));
        return;
      }
      resolve(envelope.result);
    });

    child.stdin.end(text, "utf8");
  });
}

interface TraitInputContext {
  cwd: string;
  isProjectTrusted(): boolean;
  ui: {
    notify(message: string, level: "warning"): void;
  };
}

export function createTraitInputHandler(options: TraitExpansionOptions = {}) {
  return async (
    event: Pick<InputEvent, "source" | "text">,
    ctx: TraitInputContext,
  ): Promise<InputEventResult> => {
    if (event.source === "extension") return { action: "continue" };

    try {
      const expansion = await expandTraits(event.text, ctx.cwd, {
        ...options,
        projectTrusted: ctx.isProjectTrusted(),
      });
      if (expansion.resolved.length === 0) return { action: "continue" };
      return { action: "transform", text: expansion.text };
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      ctx.ui.notify(`Trait expansion failed; sending input unchanged: ${message}`, "warning");
      return { action: "continue" };
    }
  };
}

export default function (pi: ExtensionAPI) {
  pi.on("input", createTraitInputHandler());
}

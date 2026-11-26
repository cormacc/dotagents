// herdr-orch isolated measurement adapter: an explicitly loaded Pi extension (`pi -e`), never
// installed into Pi auto-discovery. Measurement hooks require a manifest named by
// HERDR_ORCH_MEASUREMENT_MANIFEST; native account registration precedes model selection.
// Load it LAST among explicit extensions: handlers run in load order, so it records the
// request after every other extension's changes.
//
// It does three things for a measurement subject, all outside model context:
//   1. replaces the CLI's neutral input sentinel with the literal assignment text, so leading
//      `/`, `@`, `-` and template-like text are never reinterpreted by argv or templates;
//   2. forces the complete system prompt to the compiled treatment (Pi's native flags still
//      add a cwd section);
//   3. records evidence (request, tool activity, terminal state) under the manifest's evidence
//      directory. Credentials are never recorded. The runtime catches and ignores a handler's
//      exception and then sends the request, so every failure path here ends the process
//      instead of returning or throwing.
import { createHash } from "node:crypto";
import { appendFileSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { runtimeIdentity } from "./runtime.ts";
import { registerNativeAccounts } from "./account-registration.ts";

export const INPUT_SENTINEL = "herdr-orch-measurement-input";

export interface Manifest {
  treatment: string;
  assignmentFile: string;
  tools: string[];
  evidenceDir: string;
}

const sha256 = (text: string) => createHash("sha256").update(text).digest("hex");

export default async function (pi: ExtensionAPI) {
  await registerNativeAccounts(pi);
  const manifestPath = process.env.HERDR_ORCH_MEASUREMENT_MANIFEST;
  if (!manifestPath) return;
  const manifest: Manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
  const assignment = readFileSync(manifest.assignmentFile, "utf8");
  mkdirSync(manifest.evidenceDir, { recursive: true });
  const evidence = (name: string, data: unknown) => writeFileSync(join(manifest.evidenceDir, name), JSON.stringify(data, null, 2));
  const event = (data: Record<string, unknown>) => appendFileSync(join(manifest.evidenceDir, "events.jsonl"), JSON.stringify({ at: new Date().toISOString(), ...data }) + "\n");

  const violations: string[] = [];
  let requests = 0;
  // Declared provider fallbacks of the selected model, recorded with each turn's served model.
  let allowedFallbacks: { provider?: string; model: string }[] = [];
  let terminal = false;
  let lastAssistant: any;
  let failedAssistant = false;
  // Per assistant turn: the model Pi requested, the model list the request allowed as fallbacks and the
  // model the provider reported as serving. Pi surfaces `responseModel` only when it differs from the
  // requested id, so an absent value is recorded as unknown, never guessed to be the requested model.
  const turns: Record<string, unknown>[] = [];

  // Evidence is written synchronously and the process ends before another model call: a
  // refused request is never sent. The process ends even when the failure evidence itself
  // cannot be written; the missing terminal evidence then fails the run.
  const fatal = (reason: string, extra: Record<string, unknown> = {}): never => {
    try {
      violations.push(reason);
      writeTerminal(false, { reason, ...extra });
    } finally {
      process.exit(5);
    }
  };
  const writeTerminal = (success: boolean, extra: Record<string, unknown>) => {
    if (terminal) return;
    terminal = true;
    evidence("terminal.json", { success: success && violations.length === 0, requests, violations, turns, ...extra });
  };

  pi.on("session_start", async () => {
    const active = [...pi.getActiveTools()].sort();
    const wanted = [...manifest.tools].sort();
    const known = new Set(pi.getAllTools().map((t: any) => t.name));
    const unknown = manifest.tools.filter((t) => !known.has(t));
    if (unknown.length > 0) fatal(`unknown or wrong-case tool selection: ${unknown.join(", ")}`);
    if (JSON.stringify(active) !== JSON.stringify(wanted)) fatal(`active tools [${active.join(", ")}] differ from the configured [${wanted.join(", ")}]`);
    evidence("manifest-record.json", {
      runtime: runtimeIdentity(),
      treatmentSha256: sha256(manifest.treatment),
      assignmentSha256: sha256(assignment),
      tools: manifest.tools,
    });
  });

  pi.on("input", async (e) => {
    if (e.text === INPUT_SENTINEL) return { action: "transform" as const, text: assignment };
    violations.push("unexpected input reached the subject");
    event({ kind: "unexpected-input" });
    return { action: "handled" as const };
  });

  pi.on("before_agent_start", async (_e, ctx) => {
    const model: any = ctx.model;
    if (!model) return fatal("no model is selected");
    const allowed: any[] = Array.isArray(model.compat?.allowedFallbackModels) ? model.compat.allowedFallbackModels : [];
    allowedFallbacks = allowed.map((f) => ({ provider: f.provider, model: f.model }));
    return { systemPrompt: manifest.treatment };
  });

  pi.on("before_provider_request", async (e) => {
    try {
      requests += 1;
      // Pi's own agent-level retry would send the same assignment again; a sample is never retried.
      if (failedAssistant) return fatal("a provider request followed a failed turn (automatic retry is refused; a sample is never retried)", { errorMessage: lastAssistant?.errorMessage });
      evidence(`request-${requests}.json`, e.payload);
      return undefined;
    } catch (error) {
      // An evidence failure must never reach the runtime's swallow-and-send path.
      return fatal(`the request evidence failed: ${(error as Error)?.message ?? String(error)}`);
    }
  });

  const guardCall = (name: string) => {
    if (!manifest.tools.includes(name)) {
      violations.push(`call to unconfigured tool ${name}`);
      return `tool ${name} is not configured`;
    }
    return null;
  };
  pi.on("tool_call", async (e) => {
    const refusal = guardCall(e.toolName);
    event({ kind: "tool-call", tool: e.toolName, id: e.toolCallId, refused: refusal !== null });
    return refusal ? { block: true, reason: refusal } : undefined;
  });
  pi.on("tool_execution_end", async (e) => event({ kind: "tool-end", tool: e.toolName, id: e.toolCallId, isError: e.isError }));
  pi.on("message_end", async (e) => {
    const message: any = e.message;
    if (message?.role !== "assistant") return;
    lastAssistant = message;
    if (message.stopReason === "error" || message.stopReason === "aborted") failedAssistant = true;
    const served = typeof message.responseModel === "string" && message.responseModel.length > 0 ? message.responseModel : undefined;
    const turn = {
      requestedModel: message.model,
      allowedFallbacks,
      servedModel: served ?? null,
      servedProvenance: served === undefined ? "unknown" : "provider-reported",
      ...(served === undefined ? {} : { servedModelDeclared: served === message.model || allowedFallbacks.some((f) => f.model === served && (f.provider === undefined || f.provider === message.provider)) }),
    };
    turns.push(turn);
    for (const block of Array.isArray(message.content) ? message.content : []) {
      if (block?.type === "toolCall") guardCall(block.name);
    }
    event({ kind: "assistant", stopReason: message.stopReason, error: message.errorMessage, ...turn });
  });

  // Compaction would send Pi's own summarisation text to the model.
  pi.on("session_before_compact", async () => {
    violations.push("automatic compaction is refused");
    event({ kind: "compaction-refused" });
    return { cancel: true };
  });

  // `agent_end` can still be followed by agent-level retry, compaction or a queued
  // continuation; `agent_settled` is Pi's final notice that none will run, so the terminal
  // verdict is written there. A process that dies before it leaves no terminal evidence.
  pi.on("agent_settled", async (e) => {
    const stopReason = lastAssistant?.stopReason;
    writeTerminal(!e.aborted && stopReason === "stop" && requests > 0, { stopReason, errorMessage: lastAssistant?.errorMessage, aborted: e.aborted });
    if (violations.length > 0 || stopReason !== "stop") process.exitCode = 1;
  });
}

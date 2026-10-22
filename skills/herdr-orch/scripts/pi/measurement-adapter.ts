// herdr-orch isolated measurement adapter: an explicitly loaded Pi extension (`pi -e`), never
// installed into Pi auto-discovery and inert unless HERDR_ORCH_MEASUREMENT_MANIFEST names a
// manifest. Load it LAST among explicit extensions: handlers run in load order, so it then
// audits the request after every other extension's changes.
//
// It does four things for a measurement subject, all outside model context:
//   1. replaces the CLI's neutral input sentinel with the literal assignment text, so leading
//      `/`, `@`, `-` and template-like text are never reinterpreted by argv or templates;
//   2. forces the complete system prompt to the compiled treatment (Pi's native flags still
//      add a cwd section);
//   3. audits the actual provider request against an expectation derived beforehand
//      (./measurement-audit.ts) and stops the run before a contaminated request is sent. The
//      runtime catches and ignores a handler's exception and then sends the request, so every
//      failure path here ends the process instead of returning or throwing;
//   4. records evidence (request, audit, tool activity, terminal state) under the manifest's
//      evidence directory. Credentials are never recorded.
import { createHash } from "node:crypto";
import { appendFileSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { type ExtensionAPI, getAgentDir, SettingsManager } from "@earendil-works/pi-coding-agent";
import { runtimeIdentity } from "./runtime.ts";
import { auditPayload, deriveActiveEffort, deriveExpectation, PLACEHOLDER_NAME, type Expectation, type HistoryMessage } from "./measurement-audit.ts";

export const INPUT_SENTINEL = "herdr-orch-measurement-input";

export interface Manifest {
  treatment: string;
  assignmentFile: string;
  tools: string[];
  evidenceDir: string;
}

const sha256 = (text: string) => createHash("sha256").update(text).digest("hex");

export default function (pi: ExtensionAPI) {
  const manifestPath = process.env.HERDR_ORCH_MEASUREMENT_MANIFEST;
  if (!manifestPath) return;
  const manifest: Manifest = JSON.parse(readFileSync(manifestPath, "utf8"));
  const assignment = readFileSync(manifest.assignmentFile, "utf8");
  mkdirSync(manifest.evidenceDir, { recursive: true });
  const evidence = (name: string, data: unknown) => writeFileSync(join(manifest.evidenceDir, name), JSON.stringify(data, null, 2));
  const event = (data: Record<string, unknown>) => appendFileSync(join(manifest.evidenceDir, "events.jsonl"), JSON.stringify({ at: new Date().toISOString(), ...data }) + "\n");

  const violations: string[] = [];
  let requests = 0;
  let expectation: Expectation | undefined;
  let terminal = false;
  // Transcript as Pi recorded it, in order: the expected request messages derive from it.
  const history: HistoryMessage[] = [];
  let lastAssistant: any;
  let failedAssistant = false;
  // Per assistant turn: the model Pi requested, the model list the request allowed as fallbacks and the
  // model the provider reported as serving. Pi surfaces `responseModel` only when it differs from the
  // requested id, so an absent value is recorded as unknown, never guessed to be the requested model.
  const turns: Record<string, unknown>[] = [];

  // Evidence is written synchronously and the process ends before another model call: a
  // failed audit must not cost a contaminated request. The process ends even when the failure
  // evidence itself cannot be written; the missing terminal evidence then fails the run.
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
    const auth = await ctx.modelRegistry.getApiKeyAndHeaders(model);
    const oauth = auth.ok && typeof auth.apiKey === "string" && auth.apiKey.includes("sk-ant-oat");
    const thinking = pi.getThinkingLevel();
    // Runtime inputs of the top-level encoding, read where the runtime reads them: the agent's
    // thinking budgets come from the same settings files (sdk.js settingsManager.getThinkingBudgets),
    // cache retention from PI_CACHE_RETENTION (the agent passes no explicit retention), the
    // prompt cache key from the session id, and the model's sampling parameters for the session's
    // level (simple-options.js resolveSamplingParams; the session level is already clamped).
    // known-limit: an anthropic-beta header or PI_CACHE_RETENTION supplied only by a
    // before_provider_headers extension or a provider auth env is not seen here and fails closed.
    const settings = SettingsManager.create(ctx.cwd, getAgentDir(), { projectTrusted: ctx.isProjectTrusted() });
    const byLevel = model.samplingParamsByThinkingLevel?.[thinking];
    let betaHeader: string | null | undefined;
    for (const headers of [model.headers, auth.ok ? auth.headers : undefined]) {
      for (const [name, value] of Object.entries(headers ?? {})) if (name.toLowerCase() === "anthropic-beta") betaHeader = value as string | null;
    }
    // Model-visible tool facts (description and parameter schema) come from the registry, never from the request.
    const registry = Object.fromEntries(pi.getAllTools().filter((t: any) => manifest.tools.includes(t.name)).map((t: any) => [t.name, { description: t.description, parameters: JSON.parse(JSON.stringify(t.parameters ?? null)) ?? undefined }]));
    try {
      expectation = deriveExpectation({
        treatment: manifest.treatment,
        configuredTools: manifest.tools,
        registry,
        // Same rule as the provider: thinking counts as disabled only for a reasoning model
        // whose level is off and which maps "off" to a real setting.
        facts: {
          api: model.api, oauth, compat: model.compat ?? {}, provider: model.provider, model: model.id, reasoning: !!model.reasoning, baseUrl: model.baseUrl, inputImage: Array.isArray(model.input) && model.input.includes("image"),
          level: thinking === "off" ? undefined : thinking, thinkingLevelMap: model.thinkingLevelMap,
          maxTokens: model.maxTokens, thinkingBudgets: settings.getThinkingBudgets(), sessionId: ctx.sessionManager.getSessionId(),
          cacheRetention: process.env.PI_CACHE_RETENTION === "long" ? "long" : "short",
          samplingParams: model.samplingParams || byLevel ? { ...model.samplingParams, ...byLevel } : undefined, betaHeader,
          // The responses provider treats a non-`sk-` credential sent to api.openai.com as a ChatGPT sign-in.
          chatGptSignIn: model.provider === "openai" && model.baseUrl === "https://api.openai.com/v1" && auth.ok && typeof auth.apiKey === "string" && !auth.apiKey.startsWith("sk-"),
          thinkingOff: !!model.reasoning && thinking === "off" && model.thinkingLevelMap?.off !== null,
          activeEffort: deriveActiveEffort({ level: thinking, forceAdaptive: model.compat?.forceAdaptiveThinking === true, thinkingLevelMap: model.thinkingLevelMap }),
        },
      });
    } catch (error) {
      return fatal((error as Error).message);
    }
    evidence("expectation.json", {
      // Expected adaptations are recorded apart from the treatment text.
      treatmentSha256: sha256(manifest.treatment),
      adaptations: expectation.adaptations,
      preamble: expectation.preamble,
      toolMap: expectation.toolMap,
      placeholder: expectation.placeholder,
      effortMarkers: expectation.effortMarkers,
      // Declared provider fallbacks: the request carries exactly this list; each turn's served model is in terminal.json `turns`.
      fallbacks: expectation.allowedFallbacks,
      inputImage: expectation.inputImage,
      api: expectation.api,
      provider: model.provider,
      model: model.id,
      thinking,
      // The runtime inputs the top-level encoding derives from (sessionId is the prompt cache key).
      runtimeInputs: { maxTokens: model.maxTokens, thinkingBudgets: expectation.facts.thinkingBudgets ?? null, cacheRetention: expectation.facts.cacheRetention, sessionId: expectation.facts.sessionId, samplingParams: expectation.facts.samplingParams ?? null, betaHeader: betaHeader ?? null },
    });
    return { systemPrompt: manifest.treatment };
  });

  pi.on("before_provider_request", async (e) => {
    try {
      requests += 1;
      if (!expectation) return fatal("a provider request was made before the expectation was derived");
      // Pi's own agent-level retry would send the same assignment again; a sample is never retried.
      if (failedAssistant) return fatal("a provider request followed a failed turn (automatic retry is refused; a sample is never retried)", { errorMessage: lastAssistant?.errorMessage });
      const audit = auditPayload(e.payload, expectation, assignment, history);
      evidence(`request-${requests}.json`, e.payload);
      evidence(`audit-${requests}.json`, audit);
      if (!audit.ok) fatal(`request-context audit failed: ${audit.violations.join("; ")}`, { audit: requests });
      return undefined;
    } catch (error) {
      // An audit or evidence failure must never reach the runtime's swallow-and-send path.
      return fatal(`the request audit or its evidence failed: ${(error as Error)?.message ?? String(error)}`, { audit: requests });
    }
  });

  const guardCall = (name: string) => {
    if (name === PLACEHOLDER_NAME) {
      violations.push("the protocol placeholder was called");
      event({ kind: "placeholder-call" });
      return "the protocol placeholder is never callable";
    }
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
    // Content is copied as recorded: the request's messages are derived from it, call ids,
    // arguments and result text included.
    const copy = (value: unknown) => JSON.parse(JSON.stringify(value ?? null));
    if (message?.role === "user") history.push({ role: "user", content: copy(message.content) });
    if (message?.role === "toolResult") history.push({ role: "toolResult", content: copy(message.content), toolCallId: message.toolCallId, toolName: message.toolName, isError: message.isError });
    if (message?.role !== "assistant") return;
    lastAssistant = message;
    if (message.stopReason === "error" || message.stopReason === "aborted") failedAssistant = true;
    history.push({ role: "assistant", content: copy(message.content), level: message.providerThinkingLevel, api: message.api, provider: message.provider, model: message.model });
    const served = typeof message.responseModel === "string" && message.responseModel.length > 0 ? message.responseModel : undefined;
    const turn = {
      requestedModel: message.model,
      allowedFallbacks: expectation?.allowedFallbacks ?? [],
      servedModel: served ?? null,
      servedProvenance: served === undefined ? "unknown" : "provider-reported",
      ...(served === undefined ? {} : { servedModelDeclared: served === message.model || (expectation?.allowedFallbacks ?? []).some((f) => f.model === served && (f.provider === undefined || f.provider === message.provider)) }),
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

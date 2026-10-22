// Request-context audit for an isolated measurement subject.
//
// The expectation is derived *before* the request exists, from the configured treatment, the
// tool registry, the selected model's compatibility flags and auth mode, and the transcript Pi
// recorded. The observed provider payload is then compared against it; an adaptation is never
// accepted merely because it appears in the payload, and no expectation is learned from the
// request under audit.
//
// Request-level audit coverage is exactly SUPPORTED_APIS (owner decision recorded in the
// canonical plan): anthropic-messages, openai-completions, openai-responses and
// openai-codex-responses. Any other API fails closed.
//
// Each API's conversion is derived from the installed consumers (@earendil-works/pi-ai
// api/anthropic-messages.js convertMessages/convertTools, openai-completions.js
// convertMessages/convertTools, openai-responses-shared.js convertResponsesMessages/
// convertResponsesTools, openai-codex-responses.js buildRequestBody):
//   - Anthropic OAuth: first system block is OAUTH_PREAMBLE; tool definitions and historical
//     tool_use names pass through toClaudeCodeName (case-insensitive lookup in Claude Code's
//     canonical list, otherwise unchanged).
//   - Anthropic supportsMidConvoSystemMessages && supportsMidConvoToolChanges with at least one
//     configured tool: exactly one trailing PLACEHOLDER definition.
//   - Anthropic supportsMidConvoEffort with thinking not disabled: empty-content effort marker
//     messages, positioned from the recorded transcript and carrying the recorded levels.
//   - openai-completions: one leading `system`/`developer` message (role derived from
//     model.reasoning and the resolved supportsDeveloperRole) holding the treatment.
//   - openai-responses: the same leading item; `configuration_update` items when
//     supportsMidConvoEffort and a thinking level are set. These are protocol encoding like the
//     Anthropic effort markers: positions, content and levels derive from the history and the
//     resolved level, and any alteration fails.
//   - openai-codex-responses: the treatment travels as top-level `instructions`, or Pi's fixed
//     CODEX_DEFAULT_INSTRUCTIONS when the treatment is empty (owner-approved, recorded as the
//     `codex-default-instructions` adaptation; no other default text is implied); no system item.
//   - Anthropic `fallbacks`: the model's allowedFallbackModels reduced to {model} (owner-approved,
//     recorded as `model-fallbacks`); altered, added or dropped targets fail. The model that
//     served each turn is provenance, recorded by the adapter, never an input to this audit.
//   - Tool-result images and placeholders (owner-approved): each API's captions, bridge messages,
//     inline images, omission text for a model without image input and the `(no tool output)` and
//     `(see attached image)` placeholders are derived from the recorded result content, its error
//     state and the model's image capability. Arbitrary observed images or captions are not accepted.
// The whole post-instruction message list is re-derived from the recorded transcript (every
// user, assistant and tool-result message with its text, thinking, call ids, names, arguments
// and result content) and compared by value. Every tool definition is re-derived from the
// registry (name, description, parameter schema) and compared by value.
// Protocol encoding is recorded separately and never compared with the treatment text, but it is
// derived too: every top-level field other than the context (betas, thinking, output_config,
// reasoning, include, text, effort and budget fields, chat-template values, cache keys, output cap,
// sampling parameters and the rest) is built before comparison from the selected model, its
// resolved compatibility, the resolved thinking level and the configured runtime inputs (thinking
// budgets, cache retention, session id, sampling parameters) under the installed `buildParams`/
// `buildRequestBody` rules, then compared by value (deriveTopLevel). Chat Completions
// `cache_control` annotations (cacheControlFormat "anthropic") and recorded `reasoning_details`
// replay are derived the same way, from the resolved compatibility and the recorded signature.
//
// Not derived, so failing closed: user-message images (a measurement assignment is literal
// text), tool-result blocks that are neither text nor image, assistant turns from another
// provider/API/model, assistant message ids over 64 characters and grammar-constrained tools.
export const SUPPORTED_APIS = ["anthropic-messages", "openai-completions", "openai-responses", "openai-codex-responses"] as const;
export const CODEX_DEFAULT_INSTRUCTIONS = "You are a helpful assistant.";

export const OAUTH_PREAMBLE = "You are Claude Code, Anthropic's official CLI for Claude.";
export const PLACEHOLDER_NAME = "__pi_deferred_placeholder__";
export const PLACEHOLDER = {
  name: PLACEHOLDER_NAME,
  description: "Reserved placeholder. Never available. Never call this.",
  input_schema: { type: "object", properties: {}, required: [] },
  defer_loading: true,
};
export const CLAUDE_CODE_TOOLS = [
  "Read", "Write", "Edit", "Bash", "Grep", "Glob", "AskUserQuestion", "EnterPlanMode", "ExitPlanMode", "KillShell",
  "NotebookEdit", "Skill", "Task", "TaskOutput", "TodoWrite", "WebFetch", "WebSearch",
];
const lookup = new Map(CLAUDE_CODE_TOOLS.map((t) => [t.toLowerCase(), t]));
export const toClaudeCodeName = (name: string) => lookup.get(name.toLowerCase()) ?? name;

const EFFORTS = new Set(["low", "medium", "high", "xhigh", "max"]);
const TOOL_KEYS = new Set(["name", "description", "eager_input_streaming", "strict", "input_schema", "cache_control", "defer_loading"]);
const SYSTEM_BLOCK_KEYS = new Set(["type", "text", "cache_control"]);

export interface ModelFacts {
  api: string;
  oauth: boolean;
  compat: Record<string, any>;
  thinkingOff: boolean;
  // Resolved non-off thinking level and the model's level map (openai-responses configuration updates).
  level?: string;
  thinkingLevelMap?: Record<string, unknown>;
  // True for a ChatGPT sign-in credential, which the responses provider sends without configuration updates.
  chatGptSignIn?: boolean;
  model?: string;
  // Runtime inputs the top-level encoding derives from: the model's output cap, the configured
  // thinking budgets (settings `thinkingBudgets`), the resolved cache retention, the session id
  // (prompt cache key), the model's sampling parameters resolved for the level, and an
  // `anthropic-beta` header configured on the model or its auth (null clears the list).
  maxTokens?: number;
  thinkingBudgets?: Record<string, number>;
  cacheRetention?: string;
  sessionId?: string;
  samplingParams?: Record<string, unknown>;
  betaHeader?: string | null;
  // The level the runtime sends as the trailing marker's effort (see deriveActiveEffort).
  activeEffort?: string;
  // Provider id of the selected model; managed history levels apply only to its own turns.
  provider?: string;
  // Selected model facts the OpenAI-family conversions branch on.
  reasoning?: boolean;
  baseUrl?: string;
  // model.input includes "image": decides whether tool-result images are sent or replaced by omission text.
  inputImage?: boolean;
}

// Mirrors the installed provider's rule (anthropic-messages.js streamSimple /
// mapThinkingLevelToEffort / buildParams): with no reasoning level the effort option is unset and
// the marker carries "high"; a forced-adaptive model maps the level (thinkingLevelMap first,
// then minimal/low -> low, medium -> medium, anything else -> high); other models send no effort
// option, so "high" again.
export function deriveActiveEffort(input: { level?: string; forceAdaptive?: boolean; thinkingLevelMap?: Record<string, unknown> }): string {
  const { level, forceAdaptive, thinkingLevelMap } = input;
  if (!level || level === "off" || !forceAdaptive) return "high";
  const mapped = thinkingLevelMap?.[level];
  if (typeof mapped === "string") return mapped;
  if (level === "minimal" || level === "low") return "low";
  return level === "medium" ? "medium" : "high";
}

// One recorded transcript message, as Pi recorded it before the request under audit. `content`
// is Pi's own content (string or block list) so each API derives its conversion from it.
export interface HistoryMessage {
  role: "user" | "assistant" | "toolResult";
  content?: unknown;
  // Assistant only: the managed effort Pi recorded for that turn, and the turn's provenance.
  level?: string;
  api?: string;
  provider?: string;
  model?: string;
  // Tool result only.
  toolCallId?: string;
  toolName?: string;
  isError?: boolean;
}

export interface Expectation {
  api: string;
  oauth: boolean;
  preamble: string | null;
  systemTexts: string[];
  treatment: string;
  // The leading instruction role the provider derives from the selected model.
  instructionRole: "system" | "developer";
  reasoning: boolean;
  inputImage: boolean;
  // Anthropic consumer rule (buildParams): `fallbacks` is the model's allowedFallbackModels reduced
  // to {model}; undefined when the model declares none. `allowedFallbacks` keeps provider and model for the record.
  fallbacks?: { model: string }[];
  allowedFallbacks: { provider?: string; model: string }[];
  // Anthropic: replay unsigned thinking as an empty-signature thinking block instead of text.
  allowEmptySignature: boolean;
  // openai-completions: the resolved compatibility (detectCompat under model.compat).
  completions: CompletionsCompat;
  // The facts the top-level encoding derives from (deriveTopLevel).
  facts: ModelFacts;
  configuredTools: string[];
  providerTools: string[];
  toolMap: Record<string, string>;
  // Model-visible tool facts from the registry, by configured name.
  registry: Record<string, { description?: string; parameters?: any }>;
  placeholder: boolean;
  effortMarkers: boolean;
  activeEffort?: string;
  provider?: string;
  // Expected leading instructions text for codex (treatment, or the fixed default when empty).
  instructions: string;
  // openai-responses: expected configuration_update trailing effort, when updates are expected.
  configUpdateEffort?: string;
  model?: string;
  adaptations: string[];
}

// Chat Completions compatibility the conversion and the top-level encoding branch on: Pi detects
// defaults from provider and base URL, then lets model.compat override (openai-completions.js
// detectCompat/getCompat). `routing` keeps model.compat's own routing values, which buildParams
// reads directly rather than through the resolved compatibility.
function completionsCompat(f: ModelFacts) {
  const p = f.provider ?? "";
  const u = f.baseUrl ?? "";
  const id = f.model ?? "";
  const has = (s: string) => u.includes(s);
  const isZai = p === "zai" || p === "zai-coding-cn" || has("api.z.ai") || has("open.bigmodel.cn");
  const isTogether = p === "together" || has("api.together.ai") || has("api.together.xyz");
  const isMoonshot = p === "moonshotai" || p === "moonshotai-cn" || has("api.moonshot.");
  const isOpenRouter = p === "openrouter" || has("openrouter.ai");
  const isCloudflareWorkers = p === "cloudflare-workers-ai" || has("api.cloudflare.com");
  const isCloudflareGateway = p === "cloudflare-ai-gateway" || has("gateway.ai.cloudflare.com");
  const isNvidia = p === "nvidia" || has("integrate.api.nvidia.com");
  const isAntLing = p === "ant-ling" || has("api.ant-ling.com");
  const isDeepSeek = p === "deepseek" || u.toLowerCase().includes("deepseek.com");
  const isGrok = p === "xai" || has("api.x.ai");
  const nonStandard = isNvidia || p === "cerebras" || has("cerebras.ai") || isGrok || isTogether || has("chutes.ai") || isDeepSeek || isZai || isMoonshot
    || p === "opencode" || has("opencode.ai") || isCloudflareWorkers || isCloudflareGateway || isAntLing;
  const useMaxTokens = has("chutes.ai") || isDeepSeek || isMoonshot || isCloudflareGateway || isTogether || isNvidia || isAntLing || isZai;
  const openRouterDeveloper = isOpenRouter && (id.startsWith("anthropic/") || id.startsWith("openai/"));
  const c = f.compat ?? {};
  return {
    supportsDeveloperRole: c.supportsDeveloperRole ?? (openRouterDeveloper || (!nonStandard && !isOpenRouter)),
    requiresToolResultName: c.requiresToolResultName ?? false,
    requiresAssistantAfterToolResult: c.requiresAssistantAfterToolResult ?? false,
    requiresThinkingAsText: c.requiresThinkingAsText ?? false,
    requiresReasoningContent: c.requiresReasoningContentOnAssistantMessages ?? isDeepSeek,
    supportsStore: c.supportsStore ?? !nonStandard,
    supportsReasoningEffort: c.supportsReasoningEffort ?? (!isGrok && !isZai && !isMoonshot && !isTogether && !isCloudflareGateway && !isNvidia && !isAntLing),
    supportsUsageInStreaming: c.supportsUsageInStreaming ?? true,
    maxTokensField: c.maxTokensField ?? (useMaxTokens ? "max_tokens" : "max_completion_tokens"),
    thinkingFormat: c.thinkingFormat ?? (isDeepSeek ? "deepseek" : isZai ? "zai" : isTogether ? "together" : isAntLing ? "ant-ling" : isOpenRouter ? "openrouter" : "openai"),
    chatTemplateKwargs: c.chatTemplateKwargs ?? {},
    chatTemplateArgs: c.chatTemplateArgs ?? {},
    zaiToolStream: c.zaiToolStream ?? false,
    supportsThinkingTokenBudget: c.supportsThinkingTokenBudget ?? false,
    thinkingTokenBudgetField: c.thinkingTokenBudgetField as string | undefined,
    supportsStrictMode: c.supportsStrictMode ?? false,
    cacheControlFormat: c.cacheControlFormat ?? (p === "openrouter" && id.startsWith("anthropic/") ? "anthropic" : undefined),
    supportsLongCacheRetention: c.supportsLongCacheRetention ?? !(isTogether || isCloudflareWorkers || isCloudflareGateway || isNvidia || isAntLing),
    vllmPriority: c.vllmPriority,
    routing: { openRouter: c.openRouterRouting, vercel: c.vercelGatewayRouting },
  };
}
export type CompletionsCompat = ReturnType<typeof completionsCompat>;

export function deriveExpectation(input: { treatment: string; configuredTools: string[]; facts: ModelFacts; registry?: Record<string, { description?: string; parameters?: any }> }): Expectation {
  const { treatment, configuredTools, facts } = input;
  const registry = input.registry ?? {};
  for (const name of configuredTools) if (!registry[name]) throw new Error(`configured tool ${name} has no definition in the tool registry`);
  const anthropic = facts.api === "anthropic-messages";
  // Anthropic-only adaptations never apply to another API, whatever the flag names say.
  const oauth = anthropic && facts.oauth;
  const providerTools = configuredTools.map((t) => (oauth ? toClaudeCodeName(t) : t));
  const seen = new Map<string, string>();
  for (const [i, name] of providerTools.entries()) {
    const prior = seen.get(name);
    if (prior !== undefined) throw new Error(`converted tool names collide: ${prior} and ${configuredTools[i]} both become ${name}`);
    seen.set(name, configuredTools[i]);
  }
  const placeholder = anthropic && !!facts.compat.supportsMidConvoSystemMessages && !!facts.compat.supportsMidConvoToolChanges && configuredTools.length > 0;
  const effortMarkers = anthropic && !!facts.compat.supportsMidConvoEffort && !facts.thinkingOff;
  const configUpdates = facts.api === "openai-responses" && !!facts.compat.supportsMidConvoEffort && !!facts.level && facts.level !== "off" && !facts.chatGptSignIn;
  const completions = completionsCompat(facts);
  const allowed: any[] = Array.isArray(facts.compat.allowedFallbackModels) ? facts.compat.allowedFallbackModels : [];
  const fallbacks = anthropic && allowed.length > 0 ? allowed.map((f) => ({ model: f.model })) : undefined;
  const adaptations = [
    ...(oauth ? ["oauth-preamble", "oauth-tool-names"] : []),
    ...(placeholder ? ["native-tool-change-placeholder"] : []),
    ...(effortMarkers ? ["effort-markers"] : []),
    ...(configUpdates ? ["configuration-updates"] : []),
    ...(fallbacks ? ["model-fallbacks"] : []),
    ...(facts.api === "openai-codex-responses" && treatment.length === 0 ? ["codex-default-instructions"] : []),
  ];
  const developerRole = facts.api === "openai-completions" ? completions.supportsDeveloperRole : facts.compat.supportsDeveloperRole !== false;
  return {
    api: facts.api,
    oauth,
    preamble: oauth ? OAUTH_PREAMBLE : null,
    systemTexts: [...(oauth ? [OAUTH_PREAMBLE] : []), ...(treatment.length > 0 ? [treatment] : [])],
    treatment,
    instructionRole: facts.reasoning && developerRole ? "developer" : "system",
    reasoning: !!facts.reasoning,
    inputImage: facts.inputImage === true,
    fallbacks,
    allowedFallbacks: allowed.map((f) => ({ provider: f.provider, model: f.model })),
    allowEmptySignature: facts.compat.allowEmptySignature === true,
    completions,
    facts,
    instructions: treatment.length > 0 ? treatment : CODEX_DEFAULT_INSTRUCTIONS,
    registry,
    configUpdateEffort: configUpdates ? String((facts.thinkingLevelMap?.[facts.level as string] as string | undefined) ?? facts.level) : undefined,
    model: facts.model,
    configuredTools,
    providerTools,
    toolMap: Object.fromEntries(providerTools.map((p, i) => [p, configuredTools[i]])),
    placeholder,
    effortMarkers,
    activeEffort: facts.activeEffort,
    provider: facts.provider,
    adaptations,
  };
}

export interface Audit {
  ok: boolean;
  violations: string[];
  // Protocol encoding, recorded apart from the treatment.
  // `encoding` is the derived top-level encoding the request was compared with.
  protocol: { betas?: unknown; thinking?: unknown; outputConfig?: unknown; effortMarkers: string[]; placeholderDeclared: boolean; encoding?: Record<string, unknown>; cacheControl?: unknown; [key: string]: unknown };
}

// Key-order independent equality: a provider and this module need not build objects in one order.
const stable = (v: unknown): string => JSON.stringify(v, (_k, val) => (val && typeof val === "object" && !Array.isArray(val) ? Object.fromEntries(Object.entries(val).sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0))) : val));
const same = (a: unknown, b: unknown) => stable(a) === stable(b);
// The providers' sanitizeSurrogates (utils/sanitize-unicode.js).
const sanitize = (text: string) => text.replace(/[\uD800-\uDBFF](?![\uDC00-\uDFFF])|(?<![\uD800-\uDBFF])[\uDC00-\uDFFF]/g, "");
const blocksOf = (m: HistoryMessage): any[] => (Array.isArray(m.content) ? m.content : []);
const withoutCache = (definition: any) => {
  const { cache_control: _c, ...rest } = definition ?? {};
  return rest;
};

// ---- tool schemas -------------------------------------------------------------------------

// Provider strict mode (constrained-sampling.js makeStrictJsonSchema), ported because the
// registry does not expose a tool's constrainedSampling and an extension cannot import the
// runtime's module reliably. The `strict` flag on a definition is the observed encoding bit;
// this derives what its schema must then be. known-limit: revisit when Pi exposes the tool's
// constrainedSampling, or a stable strict-schema helper, to extensions.
const NO_STRICT = ["$ref", "$defs", "definitions", "allOf", "oneOf", "patternProperties", "dependentSchemas", "dependencies", "unevaluatedProperties", "propertyNames", "contains", "prefixItems", "not", "if", "then", "else"];
const isObj = (v: any) => typeof v === "object" && v !== null && !Array.isArray(v);
const structured = (s: any) => isObj(s) && ((typeof s.type === "string" ? [s.type] : Array.isArray(s.type) ? s.type : []).some((t: string) => t === "object" || t === "array") || s.properties !== undefined || s.items !== undefined);
const allowsNull = (s: any): boolean => isObj(s) && (s.type === "null" || (Array.isArray(s.type) && s.type.includes("null")) || s.const === null || (Array.isArray(s.enum) && s.enum.includes(null)) || (Array.isArray(s.anyOf) && s.anyOf.some(allowsNull)));
function strictNode(schema: any, unsupported?: (k: string, v: unknown) => boolean): void {
  if (!isObj(schema)) throw new Error("unsupported");
  for (const key of NO_STRICT) if (schema[key] !== undefined) throw new Error("unsupported");
  if (unsupported) for (const [k, v] of Object.entries(schema)) if (unsupported(k, v)) throw new Error("unsupported");
  if (schema.anyOf !== undefined) {
    if (!Array.isArray(schema.anyOf) || schema.anyOf.length === 0) throw new Error("unsupported");
    for (const variant of schema.anyOf) {
      if (structured(variant)) throw new Error("unsupported");
      strictNode(variant, unsupported);
    }
  }
  if (schema.items !== undefined) {
    if (Array.isArray(schema.items)) throw new Error("unsupported");
    strictNode(schema.items, unsupported);
  }
  const object = schema.type === "object";
  if (schema.properties !== undefined && !object) throw new Error("unsupported");
  if (!object) return;
  if (schema.additionalProperties !== undefined && schema.additionalProperties !== false) throw new Error("unsupported");
  if (schema.properties !== undefined && !isObj(schema.properties)) throw new Error("unsupported");
  if (schema.required !== undefined && (!Array.isArray(schema.required) || schema.required.some((k: unknown) => typeof k !== "string"))) throw new Error("unsupported");
  const properties = schema.properties ?? {};
  const names = Object.keys(properties);
  const required = new Set(Array.isArray(schema.required) ? schema.required : []);
  if ([...required].some((k) => !names.includes(k as string))) throw new Error("unsupported");
  for (const [key, property] of Object.entries(properties)) {
    strictNode(property, unsupported);
    if (!required.has(key) && !allowsNull(property)) properties[key] = { anyOf: [property, { type: "null" }] };
  }
  schema.required = names;
  schema.additionalProperties = false;
}
const ANTHROPIC_NO_STRICT_KEYWORDS = new Set(["minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum", "multipleOf", "maxItems", "uniqueItems", "minContains", "maxContains", "minProperties", "maxProperties"]);
const ANTHROPIC_STRING_FORMATS = new Set(["date-time", "time", "date", "duration", "email", "hostname", "uri", "ipv4", "ipv6", "uuid"]);
const anthropicUnsupported = (k: string, v: unknown) => ANTHROPIC_NO_STRICT_KEYWORDS.has(k) || (k === "minItems" && v !== 0 && v !== 1) || (k === "format" && (typeof v !== "string" || !ANTHROPIC_STRING_FORMATS.has(v)));
function strictSchema(schema: any, unsupported?: (k: string, v: unknown) => boolean): any | undefined {
  try {
    const clone = structuredClone(schema);
    strictNode(clone, unsupported);
    return clone.type === "object" ? clone : undefined;
  } catch {
    return undefined;
  }
}

interface ObservedTool { name: unknown; description: unknown; schema: unknown; strict: boolean }
// Every model-visible part of a configured tool's definition (name, description, parameter
// schema) is re-derived from the registry. The `strict` flag is protocol encoding that the
// registry does not expose; it selects whether the schema must be the strict-mode derivation.
function checkToolDefinitions(observed: ObservedTool[], expected: Expectation, fail: (m: string) => void) {
  const anthropic = expected.api === "anthropic-messages";
  for (const tool of observed) {
    const configured = expected.toolMap[tool.name as string];
    const registered = configured === undefined ? undefined : expected.registry[configured];
    if (!registered) continue; // an unexpected name is reported by the name comparison
    if (tool.description !== registered.description) fail(`tool ${configured} description differs from the configured tool's`);
    const parameters = registered.parameters;
    const basis = tool.strict ? strictSchema(parameters, anthropic ? anthropicUnsupported : undefined) : parameters;
    if (basis === undefined) { fail(`tool ${configured} is declared strict but its schema has no strict derivation`); continue; }
    const wanted = anthropic ? { ...(tool.strict ? basis : {}), type: "object", properties: basis?.properties ?? {}, required: basis?.required ?? [] } : basis;
    if (!same(tool.schema, wanted)) fail(`tool ${configured} parameter schema differs from the configured tool's`);
  }
}

// ---- transcript-derived wire messages ---------------------------------------------------------

interface Wire { items: any[]; problems: string[] }
// Pi's conversion of another provider's, API's or model's turn (dropped thinking, id
// normalisation) is not derived; the single-model measurement run never records one. A recorded
// call to a tool outside the configured selection is refused here as well as at the call.
const historyProblems = (history: HistoryMessage[], e: Expectation) =>
  history.flatMap((m, i) => {
    if (m.role !== "assistant") return [];
    const problems: string[] = [];
    if (m.api !== e.api || m.provider !== e.provider || m.model !== e.model) problems.push(`recorded assistant turn ${i} came from another provider, API or model than the request's (conversion not derived)`);
    for (const b of blocksOf(m)) if (b?.type === "toolCall" && !e.configuredTools.includes(b.name)) problems.push(`recorded call to unconfigured tool ${b.name}`);
    return problems;
  });
// Tool-result content after the provider's transformMessages (transform-messages.js). A model
// without image input gets the omission text in place of each run of images; the images,
// captions, bridge messages and placeholders each API then adds are derived from the recorded
// content, the error state and that capability, never accepted from the request.
const NON_VISION_TOOL_IMAGE = "(tool image omitted: model does not support images)";
const ERROR_TOOL_IMAGE = "(image attached after the tool results)";
const SEE_ATTACHED_IMAGE = "(see attached image)";
const NO_TOOL_OUTPUT = "(no tool output)";
const BRIDGE_CAPTION = "Attached image(s) from tool result:";
const isImage = (b: any) => b?.type === "image";
function replaceImages(content: any[], placeholder: string): any[] {
  const result: any[] = [];
  let previousWasPlaceholder = false;
  for (const block of content) {
    if (isImage(block)) {
      if (!previousWasPlaceholder) result.push({ type: "text", text: placeholder });
      previousWasPlaceholder = true;
      continue;
    }
    result.push(block);
    previousWasPlaceholder = block?.text === placeholder;
  }
  return result;
}
function resultBlocks(m: HistoryMessage, i: number, e: Expectation, problems: string[]): any[] {
  const blocks = blocksOf(m);
  if (blocks.some((b) => b?.type !== "text" && !isImage(b))) problems.push(`tool result ${i} carries a content block that is neither text nor image`);
  return e.inputImage ? blocks : replaceImages(blocks, NON_VISION_TOOL_IMAGE);
}
const textJoin = (blocks: any[]) => blocks.filter((b) => b?.type === "text").map((b) => b.text).join("\n");
const dataUrl = (b: any) => `data:${b.mimeType};base64,${b.data}`;
// A user message is only ever the literal text assignment in a measurement run.
function userBlocks(m: HistoryMessage, i: number, problems: string[]): string | string[] | undefined {
  if (typeof m.content === "string") return m.content;
  const blocks = blocksOf(m);
  if (blocks.some((b) => b?.type !== "text")) problems.push(`user message ${i} carries non-text content; a measurement assignment is literal text`);
  return blocks.filter((b) => b?.type === "text").map((b) => b.text);
}
// The assignment is the one user message of a measurement run; the request is then compared
// with the transcript, so text added to or removed from it fails there.
function checkAssignment(history: HistoryMessage[], assignment: string, fail: (m: string) => void) {
  const first = history[0];
  const text = first?.role === "user" ? (typeof first.content === "string" ? first.content : blocksOf(first).map((b) => (b?.type === "text" ? b.text : "\u0000")).join("")) : undefined;
  if (text !== assignment) fail("the recorded first user message differs from the assignment text");
  if (history.filter((m) => m.role === "user").length !== 1) fail("the recorded transcript holds more than one user message");
}

const anthropicImage = (b: any) => ({ type: "image", source: { type: "base64", media_type: b.mimeType, data: b.data } });
// convertContentBlocks: text-only content is one joined string; with images it is a block list
// that gains the placeholder text block when it holds no text.
function anthropicToolContent(blocks: any[]): string | any[] {
  if (!blocks.some(isImage)) return sanitize(blocks.map((b) => b.text).join("\n"));
  const out = blocks.map((b) => (isImage(b) ? anthropicImage(b) : { type: "text", text: sanitize(b.text) }));
  if (!out.some((b) => b.type === "text")) out.unshift({ type: "text", text: SEE_ATTACHED_IMAGE });
  return out;
}

function expectedAnthropic(history: HistoryMessage[], e: Expectation): Wire {
  const problems = historyProblems(history, e);
  const converted: any[] = [];
  const levels = new Map<number, string>();
  for (let i = 0; i < history.length; i++) {
    const m = history[i];
    if (m.role === "user") {
      const content = userBlocks(m, i, problems);
      const text = typeof content === "string" ? (content.trim().length > 0 ? sanitize(content) : undefined) : (content ?? []).filter((t) => t.trim().length > 0).map((t) => ({ type: "text", text: sanitize(t) }));
      if (typeof text === "string" || (text && text.length > 0)) converted.push({ role: "user", content: text });
    } else if (m.role === "assistant") {
      const content: any[] = [];
      for (const b of blocksOf(m)) {
        if (b?.type === "text") {
          if (b.text.trim().length > 0) content.push({ type: "text", text: sanitize(b.text) });
        } else if (b?.type === "thinking") {
          if (b.redacted) { content.push({ type: "redacted_thinking", data: b.thinkingSignature }); continue; }
          const signed = !!b.thinkingSignature && b.thinkingSignature.trim().length > 0;
          if (b.thinking.trim().length === 0 && !signed) continue;
          if (signed) content.push({ type: "thinking", thinking: sanitize(b.thinking), signature: b.thinkingSignature });
          else content.push(e.allowEmptySignature ? { type: "thinking", thinking: sanitize(b.thinking), signature: "" } : { type: "text", text: sanitize(b.thinking) });
        } else if (b?.type === "toolCall") {
          content.push({ type: "tool_use", id: b.id, name: e.oauth ? toClaudeCodeName(b.name) : b.name, input: b.arguments ?? {} });
        }
      }
      if (content.length === 0) continue;
      if (e.effortMarkers && m.level !== undefined && EFFORTS.has(m.level)) levels.set(converted.length, m.level);
      converted.push({ role: "assistant", content });
    } else {
      // Consecutive results are one user message; an error result's images follow all results with a caption.
      const results: any[] = [];
      const errorImages: any[] = [];
      for (; i < history.length && history[i].role === "toolResult"; i++) {
        const r = history[i];
        const blocks = resultBlocks(r, i, e, problems);
        const split = r.isError === true && blocks.some(isImage);
        results.push({ type: "tool_result", tool_use_id: r.toolCallId, content: anthropicToolContent(split ? replaceImages(blocks, ERROR_TOOL_IMAGE) : blocks), is_error: r.isError });
        if (split) errorImages.push({ type: "text", text: `Images from the error result of tool call ${r.toolCallId} (${r.toolName}):` }, ...blocks.filter(isImage).map(anthropicImage));
      }
      i -= 1;
      converted.push({ role: "user", content: [...results, ...errorImages] });
    }
  }
  const items: any[] = [];
  const marker = (effort: string) => ({ role: "system", content: [], output_config: { effort } });
  converted.forEach((message, index) => {
    if (levels.has(index)) items.push(marker(levels.get(index) as string));
    items.push(message);
  });
  if (e.effortMarkers) items.push(marker(e.activeEffort ?? "high"));
  return { items, problems };
}

const COMPLETIONS_REASONING_FIELDS = ["reasoning", "reasoning_content", "reasoning_text"];
// openai-completions.js isOpenAIReasoningDetail / parseOpenAIReasoningDetails /
// parseLegacyEncryptedReasoningDetail: the recorded signature decides whether a turn replays
// structured `reasoning_details`, and only a valid detail list (or legacy encrypted detail) does.
const validDetail = (d: any): boolean => {
  if (!isObj(d)) return false;
  if (!(d.id === undefined || d.id === null || typeof d.id === "string") || !(d.format === undefined || typeof d.format === "string") || !(d.index === undefined || typeof d.index === "number")) return false;
  switch (d.type) {
    case "reasoning.summary": return typeof d.summary === "string";
    case "reasoning.encrypted": return typeof d.data === "string";
    case "reasoning.text": return typeof d.text === "string" && (d.signature === undefined || d.signature === null || typeof d.signature === "string");
    default: return false;
  }
};
const parseJson = (text: unknown): any => { if (typeof text !== "string" || text.length === 0) return undefined; try { return JSON.parse(text); } catch { return undefined; } };
const reasoningDetails = (signature: unknown) => { const v = parseJson(signature); return Array.isArray(v) && v.length > 0 && v.every(validDetail) ? v : undefined; };
const legacyDetail = (signature: unknown) => { const v = parseJson(signature); return validDetail(v) && v.type === "reasoning.encrypted" && typeof v.id === "string" && v.id.length > 0 && v.data.length > 0 ? v : undefined; };
function expectedCompletions(history: HistoryMessage[], e: Expectation): Wire {
  const problems = historyProblems(history, e);
  const items: any[] = [];
  for (let i = 0; i < history.length; i++) {
    const m = history[i];
    if (m.role === "user") {
      const content = userBlocks(m, i, problems);
      if (typeof content === "string") items.push({ role: "user", content: sanitize(content) });
      else {
        const parts = (content ?? []).filter((t) => t.length > 0).map((t) => ({ type: "text", text: sanitize(t) }));
        if (parts.length > 0) items.push({ role: "user", content: parts });
      }
    } else if (m.role === "assistant") {
      const textParts = blocksOf(m).filter((b) => b?.type === "text" && b.text.trim().length > 0).map((b) => ({ type: "text", text: sanitize(b.text) }));
      const text = textParts.map((p) => p.text).join("");
      const thinking = blocksOf(m).filter((b) => b?.type === "thinking");
      const calls = blocksOf(m).filter((b) => b?.type === "toolCall");
      const nonEmpty = thinking.filter((b) => b.thinking.trim().length > 0);
      const legacy = calls.map((c) => legacyDetail(c.thoughtSignature)).filter((d) => d !== undefined);
      const preserved = thinking.map((b) => reasoningDetails(b.thinkingSignature)).find((d) => d !== undefined) ?? (legacy.length > 0 ? legacy : undefined);
      const out: any = { role: "assistant", content: e.completions.requiresAssistantAfterToolResult ? "" : null };
      if (nonEmpty.length > 0) {
        if (e.completions.requiresThinkingAsText) out.content = [{ type: "text", text: nonEmpty.map((b) => sanitize(b.thinking)).join("\n\n") }, ...textParts];
        else {
          if (text.length > 0) out.content = text;
          // reasoning_details is the structured alternative to a raw reasoning field.
          if (!preserved) {
            const signature = e.provider === "opencode-go" && nonEmpty[0].thinkingSignature === "reasoning" ? "reasoning_content" : nonEmpty[0].thinkingSignature;
            if (signature && COMPLETIONS_REASONING_FIELDS.includes(signature)) out[signature] = nonEmpty.map((b) => b.thinking).join("\n");
          }
        }
      } else if (text.length > 0) out.content = text;
      if (calls.length > 0) out.tool_calls = calls.map((c) => ({ id: c.id, type: "function", function: { name: c.name, arguments: JSON.stringify(c.arguments) } }));
      if (preserved) out.reasoning_details = preserved;
      if (e.completions.requiresReasoningContent && e.reasoning && out.reasoning_content === undefined) out.reasoning_content = "";
      const hasContent = out.content !== null && out.content !== undefined && out.content.length > 0;
      if (hasContent || out.tool_calls) items.push(out);
    } else {
      // Each result is a tool message; the images of the whole group follow in one bridge user message.
      const bridge: any[] = [];
      for (; i < history.length && history[i].role === "toolResult"; i++) {
        const r = history[i];
        const blocks = resultBlocks(r, i, e, problems);
        const text = textJoin(blocks);
        const images = blocks.filter(isImage);
        items.push({ role: "tool", content: sanitize(text.length > 0 ? text : images.length > 0 ? SEE_ATTACHED_IMAGE : NO_TOOL_OUTPUT), tool_call_id: r.toolCallId, ...(e.completions.requiresToolResultName && r.toolName ? { name: r.toolName } : {}) });
        bridge.push(...images.map((b) => ({ type: "image_url", image_url: { url: dataUrl(b) } })));
      }
      i -= 1;
      if (bridge.length > 0) {
        if (e.completions.requiresAssistantAfterToolResult) items.push({ role: "assistant", content: "I have processed the tool results." });
        items.push({ role: "user", content: [{ type: "text", text: BRIDGE_CAPTION }, ...bridge] });
      }
    }
  }
  return { items, problems };
}

function parseTextSignature(signature?: string): { id: string; phase?: string } | undefined {
  if (!signature) return undefined;
  if (signature.startsWith("{")) {
    try {
      const parsed = JSON.parse(signature);
      if (parsed.v === 1 && typeof parsed.id === "string") return parsed.phase === "commentary" || parsed.phase === "final_answer" ? { id: parsed.id, phase: parsed.phase } : { id: parsed.id };
    } catch { /* legacy plain-string signature */ }
  }
  return { id: signature };
}

// convertToolResultOutput: a string for text-only or placeholder results, else input_text and
// input_image parts (images inline, no bridge message).
function responsesToolOutput(blocks: any[]): string | any[] {
  const text = textJoin(blocks);
  const images = blocks.filter(isImage);
  if (images.length === 0) return sanitize(text.length > 0 ? text : NO_TOOL_OUTPUT);
  return [...(text.length > 0 ? [{ type: "input_text", text: sanitize(text) }] : []), ...images.map((b) => ({ type: "input_image", detail: "auto", image_url: dataUrl(b) }))];
}

// convertResponsesMessages, without the leading system item (the caller adds it).
function expectedResponses(history: HistoryMessage[], e: Expectation): Wire {
  const problems = historyProblems(history, e);
  const items: any[] = [];
  let msgIndex = 0;
  for (let i = 0; i < history.length; i++) {
    const m = history[i];
    if (m.role === "user") {
      const content = userBlocks(m, i, problems);
      const parts = typeof content === "string" ? [content] : (content ?? []);
      if (parts.length > 0) items.push({ role: "user", content: parts.map((t) => ({ type: "input_text", text: sanitize(t) })) });
      else continue;
    } else if (m.role === "assistant") {
      const output: any[] = [];
      let textIndex = 0;
      for (const b of blocksOf(m)) {
        if (b?.type === "thinking") {
          if (b.thinkingSignature) {
            try { output.push(JSON.parse(b.thinkingSignature)); } catch { problems.push(`assistant turn ${i} carries a reasoning item that is not JSON`); }
          }
        } else if (b?.type === "text") {
          const parsed = parseTextSignature(b.textSignature);
          let id = parsed?.id ?? (textIndex === 0 ? `msg_pi_${msgIndex}` : `msg_pi_${msgIndex}_${textIndex}`);
          textIndex += 1;
          if (id.length > 64) problems.push(`assistant turn ${i} carries a message id longer than 64 characters (its hashed form is not derived)`);
          output.push({ type: "message", role: "assistant", content: [{ type: "output_text", text: sanitize(b.text), annotations: [] }], status: "completed", id, phase: parsed?.phase });
        } else if (b?.type === "toolCall") {
          const [callId, itemIdRaw] = String(b.id).split("|");
          output.push({ type: "function_call", id: itemIdRaw?.startsWith("fc_") ? itemIdRaw : undefined, call_id: callId, name: b.name, arguments: JSON.stringify(b.arguments), ...(b.namespace !== undefined ? { namespace: b.namespace } : {}) });
        }
      }
      if (output.length === 0) continue;
      if (e.configUpdateEffort !== undefined && m.level !== undefined) items.push({ type: "configuration_update", reasoning: { effort: m.level } });
      items.push(...output);
    } else {
      items.push({ type: "function_call_output", call_id: String(m.toolCallId).split("|")[0], output: responsesToolOutput(resultBlocks(m, i, e, problems)) });
    }
    msgIndex += 1;
  }
  if (e.configUpdateEffort !== undefined) items.push({ type: "configuration_update", reasoning: { effort: e.configUpdateEffort } });
  return { items, problems };
}

// Compares the observed message list with the one derived from the transcript. A role/kind
// sequence mismatch is reported first because it names where the request diverges.
function diffWire(got: any[], want: any[], label: (m: any) => string, fail: (m: string) => void) {
  const g = got.map(label);
  const w = want.map(label);
  if (!same(g, w)) return fail(`message roles [${g.join(", ")}] differ from the recorded history [${w.join(", ")}]`);
  const i = got.findIndex((m, k) => !same(m, want[k]));
  if (i >= 0) fail(`message ${i} (${g[i]}) differs from the recorded transcript`);
}

// ---- top-level encoding -------------------------------------------------------------------
//
// The expected value of every top-level field other than the context, built before comparison
// from the facts under the installed consumer rules (anthropic-messages.js streamSimple/
// getBetaFeatures/buildParams, openai-completions.js buildParams, openai-responses.js
// buildParams, openai-codex-responses.js buildRequestBody, simple-options.js). The agent loop
// passes no temperature, tool choice, metadata, service tier, text verbosity, reasoning summary,
// explicit cache retention or sampling override, so none of those is ever expected.

// simple-options.js DEFAULT_THINKING_BUDGETS, thinkingBudgetForLevel and clampThinkingBudgetToAnswerRoom.
const DEFAULT_THINKING_BUDGETS: Record<string, number> = { minimal: 1024, low: 2048, medium: 8192, high: 16384 };
const budgetFor = (level: string, custom?: Record<string, number>) => ({ ...DEFAULT_THINKING_BUDGETS, ...custom })[level === "xhigh" || level === "max" ? "high" : level];
const answerRoom = (budget: number, ceiling: number) => Math.min(budget, Math.max(0, ceiling - 1024));
// openai-prompt-cache.js clampOpenAIPromptCacheKey.
const cacheKey = (key?: string) => (key === undefined ? undefined : Array.from(key).slice(0, 64).join(""));
const mapped = (f: ModelFacts, level: string) => f.thinkingLevelMap?.[level] as any;

// The output cap is the one numeric field the runtime clamps by Pi's context token estimate
// (clampMaxTokensToContext), which is not derivable here. known-limit: the cap is checked against
// the derived range [min, max] rather than an exact value; revisit if Pi exposes the estimate.
// Every budget that depends on the cap is then derived exactly from the checked value.
interface Cap { field: string; min: number; max: number }
function capOf(e: Expectation): Cap | undefined {
  const f = e.facts;
  const max = f.maxTokens ?? 0;
  switch (e.api) {
    case "anthropic-messages": return { field: "max_tokens", min: 1, max };
    case "openai-completions": return { field: e.completions.maxTokensField === "max_tokens" ? "max_tokens" : "max_completion_tokens", min: 1, max };
    case "openai-responses": return (f.compat.supportsMaxOutputTokens ?? true) && !f.chatGptSignIn ? { field: "max_output_tokens", min: 16, max: Math.max(max, 16) } : undefined;
    default: return undefined;
  }
}

// getBetaFeatures: a configured anthropic-beta header replaces the computed list.
function anthropicBetas(e: Expectation): string[] {
  const f = e.facts;
  const c = f.compat;
  if (f.betaHeader === null) return [];
  if (typeof f.betaHeader === "string") return [...new Set(f.betaHeader.split(",").map((s) => s.trim()).filter((s) => s.length > 0))];
  const out: string[] = [];
  if (e.oauth) out.push("claude-code-20250219", "oauth-2025-04-20");
  if (e.configuredTools.length > 0 && c.supportsEagerToolInputStreaming === false) out.push("fine-grained-tool-streaming-2025-05-14");
  if (f.reasoning && !!f.level && c.forceAdaptiveThinking !== true) out.push("interleaved-thinking-2025-05-14");
  if ((c.allowedFallbackModels?.length ?? 0) > 0) out.push("server-side-fallback-2026-07-01");
  if (c.supportsMidConvoEffort === true) out.push("mid-conversation-output-config-2026-07-01", "thinking-binding-controls-2026-08-01");
  if (e.placeholder) out.push("inline-tools-2026-09-15");
  return [...new Set(out)];
}

function anthropicTop(e: Expectation, cap: number): Record<string, unknown> {
  const f = e.facts;
  const c = f.compat;
  const enabled = !!f.level;
  const disabled = !!f.reasoning && !enabled && f.thinkingLevelMap?.off !== null;
  const out: Record<string, unknown> = { model: f.model, max_tokens: cap, stream: true };
  const betas = anthropicBetas(e);
  if (betas.length > 0) out.betas = betas;
  if (disabled) out.thinking = { type: "disabled" };
  else if (c.supportsMidConvoEffort === true) {
    out.thinking = { type: "adaptive", display: "summarized", block_binding: { prefix_mismatch_behavior: "drop_block" } };
    out.output_config = { effort: "high" };
  } else if (f.reasoning && enabled) {
    if (c.forceAdaptiveThinking === true) {
      out.thinking = { type: "adaptive", display: "summarized" };
      out.output_config = { effort: deriveActiveEffort({ level: f.level, forceAdaptive: true, thinkingLevelMap: f.thinkingLevelMap }) };
    } else {
      // streamSimple/adjustMaxTokensForThinking: with the cap at or below the model cap, the
      // adjusted budget shrinks only when the model cap itself leaves no answer room.
      const budget = budgetFor(f.level as string, f.thinkingBudgets);
      const model = f.maxTokens ?? 0;
      const adjusted = model <= budget ? answerRoom(budget, model) : budget;
      out.thinking = { type: "enabled", budget_tokens: answerRoom(adjusted, cap) || 1024, display: "summarized" };
    }
  }
  return out;
}

// buildChatTemplateValues / resolveChatTemplateKwargValue.
function chatTemplateValues(f: ModelFacts, values: Record<string, any>, budget: number | undefined) {
  const out: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(values ?? {})) {
    let resolved: unknown;
    if (typeof value !== "object" || value === null) resolved = value;
    else if (!f.level && value.omitWhenOff) resolved = undefined;
    else if (value.$var === "thinking.enabled") resolved = !!f.level;
    else if (value.$var === "thinking.budget") resolved = budget;
    else {
      const m = f.level ? mapped(f, f.level) : mapped(f, "off");
      resolved = m === undefined ? f.level : typeof m === "string" ? m : undefined;
    }
    if (resolved !== undefined) out[key] = resolved;
  }
  return Object.keys(out).length > 0 ? out : undefined;
}

function completionsTop(e: Expectation, cap: number): Record<string, unknown> {
  const f = e.facts;
  const c = e.completions;
  const level = f.level;
  const retention = f.cacheRetention ?? "short";
  const out: Record<string, any> = { model: f.model, stream: true };
  if (((f.baseUrl ?? "").includes("api.openai.com") && retention !== "none") || (retention === "long" && c.supportsLongCacheRetention)) out.prompt_cache_key = cacheKey(f.sessionId);
  if (retention === "long" && c.supportsLongCacheRetention) out.prompt_cache_retention = "24h";
  if (c.supportsUsageInStreaming !== false) out.stream_options = { include_usage: true };
  if (c.supportsStore) out.store = false;
  out[(capOf(e) as Cap).field] = cap;
  if (e.configuredTools.length > 0 && c.zaiToolStream) out.tool_stream = true;
  if (c.vllmPriority !== undefined) out.priority = c.vllmPriority;
  const budgetField = c.thinkingTokenBudgetField || (c.supportsThinkingTokenBudget ? "thinking_token_budget" : undefined);
  const rawBudget = level && f.reasoning ? answerRoom(budgetFor(level, f.thinkingBudgets), cap) : undefined;
  const budget = rawBudget !== undefined && rawBudget > 0 ? rawBudget : undefined;
  const fmt = c.thinkingFormat;
  const effortOf = (l: string) => mapped(f, l) ?? l;
  if (fmt === "zai" && f.reasoning) {
    out.thinking = level ? { type: "enabled", clear_thinking: false } : { type: "disabled" };
    if (level && c.supportsReasoningEffort) { const m = mapped(f, level); const effort = m === undefined ? level : m; if (typeof effort === "string") out.reasoning_effort = effort; }
  } else if (fmt === "qwen" && f.reasoning) {
    out.enable_thinking = !!level;
    if (level && c.supportsReasoningEffort) { const effort = effortOf(level); if (typeof effort === "string") out.reasoning_effort = effort; }
  } else if (fmt === "qwen-chat-template" && f.reasoning) {
    out.chat_template_kwargs = { enable_thinking: !!level, preserve_thinking: true };
  } else if (fmt === "chat-template" && f.reasoning) {
    const values = chatTemplateValues(f, c.chatTemplateKwargs, budget);
    if (values) out.chat_template_kwargs = values;
  } else if (fmt === "baseten" && f.reasoning) {
    const values = chatTemplateValues(f, c.chatTemplateArgs, budget);
    if (values) out.chat_template_args = values;
    if (c.supportsReasoningEffort) { const m = level ? mapped(f, level) : mapped(f, "off"); const effort = m === undefined ? level : m; if (typeof effort === "string") out.reasoning_effort = effort; }
  } else if (fmt === "deepseek" && f.reasoning) {
    if (level) out.thinking = { type: "enabled" };
    else if (mapped(f, "off") !== null) out.thinking = { type: "disabled" };
    if (level && c.supportsReasoningEffort) out.reasoning_effort = effortOf(level);
  } else if (fmt === "openrouter" && f.reasoning) {
    if (level) out.reasoning = { effort: effortOf(level) };
    else if (mapped(f, "off") !== null) out.reasoning = { effort: mapped(f, "off") ?? "none" };
  } else if (fmt === "ant-ling" && f.reasoning && level) {
    const effort = mapped(f, level);
    if (typeof effort === "string") out.reasoning = { effort };
  } else if (fmt === "together" && f.reasoning) {
    out.reasoning = { enabled: !!level };
    if (level && c.supportsReasoningEffort) out.reasoning_effort = effortOf(level);
  } else if (fmt === "string-thinking" && f.reasoning) {
    if (level) out.thinking = effortOf(level);
    else if (mapped(f, "off") !== null) out.thinking = mapped(f, "off") ?? "none";
  } else if (level && f.reasoning && c.supportsReasoningEffort) {
    out.reasoning_effort = effortOf(level);
  } else if (!level && f.reasoning && c.supportsReasoningEffort) {
    const off = mapped(f, "off");
    if (typeof off === "string") out.reasoning_effort = off;
  }
  if (budgetField && budget !== undefined) out[budgetField] = budget;
  if (c.routing.openRouter) out.provider = c.routing.openRouter;
  const vercel = c.routing.vercel;
  if (vercel && (vercel.only || vercel.order)) out.providerOptions = { gateway: { ...(vercel.only ? { only: vercel.only } : {}), ...(vercel.order ? { order: vercel.order } : {}) } };
  return { ...out, ...f.samplingParams };
}

function responsesTop(e: Expectation, cap: number | undefined): Record<string, unknown> {
  const f = e.facts;
  const c = f.compat;
  const omit = !!f.chatGptSignIn;
  const retention = f.cacheRetention ?? "short";
  const long = c.supportsLongCacheRetention ?? true;
  const explicit = c.supportsExplicitPromptCacheMode ?? false;
  const out: Record<string, unknown> = { model: f.model, stream: true, store: false };
  if (retention !== "none") out.prompt_cache_key = cacheKey(f.sessionId);
  if (!omit) {
    out.prompt_cache_retention = retention === "long" && long && !explicit ? "24h" : undefined;
    out.prompt_cache_options = !explicit ? undefined : retention === "none" ? { mode: "explicit" } : retention === "long" && long ? { ttl: "30m" } : undefined;
  }
  if (cap !== undefined) out.max_output_tokens = cap;
  if (f.reasoning) {
    if (f.level) {
      // With configuration updates the request effort is fixed; the updates carry the level.
      out.reasoning = { effort: e.configUpdateEffort === undefined ? (mapped(f, f.level) ?? f.level) : "medium", summary: "auto" };
      out.include = ["reasoning.encrypted_content"];
    } else if (f.provider !== "github-copilot" && mapped(f, "off") !== null) out.reasoning = { effort: mapped(f, "off") ?? "none" };
    if (f.provider === "xai") out.include = ["reasoning.encrypted_content"];
  }
  return { ...out, ...f.samplingParams };
}

function codexTop(e: Expectation): Record<string, unknown> {
  const f = e.facts;
  const out: Record<string, unknown> = { model: f.model, store: false, stream: true, text: { verbosity: "low" }, include: ["reasoning.encrypted_content"], prompt_cache_key: cacheKey(f.sessionId), tool_choice: "auto", parallel_tool_calls: true };
  if (f.level !== undefined) {
    const effort = f.level === "none" ? (mapped(f, "off") === undefined ? "none" : mapped(f, "off")) : (mapped(f, f.level) ?? f.level);
    if (effort !== null) out.reasoning = { effort, summary: "auto" };
  } else if (f.reasoning && mapped(f, "off") !== null) out.reasoning = { effort: mapped(f, "off") ?? "none" };
  return out;
}

// The expected top-level fields (context fields excluded) for a checked output cap.
export function deriveTopLevel(e: Expectation, cap?: number): Record<string, unknown> {
  const c = cap ?? capOf(e)?.max ?? 0;
  switch (e.api) {
    case "anthropic-messages": return anthropicTop(e, c);
    case "openai-completions": return completionsTop(e, c);
    case "openai-responses": return responsesTop(e, capOf(e) ? c : undefined);
    case "openai-codex-responses": return codexTop(e);
    default: return {};
  }
}

// Compares every non-context top-level field by value with its derivation. A key with an
// undefined value is absent on the wire, so it is ignored on both sides.
function checkTopLevel(payload: any, e: Expectation, context: string[], fail: (m: string) => void): Record<string, unknown> {
  const cap = capOf(e);
  let observedCap: number | undefined;
  if (cap) {
    const v = payload[cap.field];
    if (Number.isInteger(v) && v >= cap.min && v <= cap.max) observedCap = v;
    else fail(`output cap ${cap.field} ${JSON.stringify(v)} is outside the range [${cap.min}, ${cap.max}] derived from the model`);
  }
  const want = deriveTopLevel(e, observedCap);
  if (cap && observedCap === undefined) delete want[cap.field];
  for (const [key, value] of Object.entries(payload)) {
    if (value === undefined || context.includes(key) || (cap && key === cap.field && observedCap === undefined)) continue;
    if (want[key] === undefined) fail(`unrecorded top-level request field: ${key}`);
    else if (!same(value, want[key])) fail(`top-level ${key} differs from the encoding derived from the model, thinking level and runtime settings`);
  }
  for (const [key, value] of Object.entries(want)) if (value !== undefined && payload[key] === undefined) fail(`top-level ${key} derived from the model, thinking level and runtime settings is missing`);
  return want;
}

// openai-completions getCompatCacheControl / applyAnthropicCacheControl: the instruction message,
// the last tool and the last conversation message with text gain the marker, a string content
// becoming one annotated text part.
function completionsCacheControl(e: Expectation): Record<string, unknown> | undefined {
  const retention = e.facts.cacheRetention ?? "short";
  if (e.completions.cacheControlFormat !== "anthropic" || retention === "none") return undefined;
  return { type: "ephemeral", ...(retention === "long" && e.completions.supportsLongCacheRetention ? { ttl: "1h" } : {}) };
}
function annotateText(message: any, cacheControl: object): boolean {
  const content = message.content;
  if (typeof content === "string") {
    if (content.length === 0) return false;
    message.content = [{ type: "text", text: content, cache_control: cacheControl }];
    return true;
  }
  if (!Array.isArray(content)) return false;
  for (let i = content.length - 1; i >= 0; i--) if (content[i]?.type === "text") { content[i].cache_control = cacheControl; return true; }
  return false;
}
function annotateMessages(messages: any[], cacheControl: object) {
  const instruction = messages.find((m) => m.role === "system" || m.role === "developer");
  if (instruction) annotateText(instruction, cacheControl);
  for (let i = messages.length - 1; i >= 0; i--) if (["user", "assistant", "tool"].includes(messages[i].role) && annotateText(messages[i], cacheControl)) return;
}

// ---- Anthropic ----------------------------------------------------------------------------

const stripCache = (b: any) => (b && typeof b === "object" ? withoutCache(b) : b);
// cache_control and string-versus-block content are encoding; the text they carry is compared.
const canonAnthropic = (m: any) => (m && typeof m === "object" && m.content !== undefined ? { ...m, content: typeof m.content === "string" ? [{ type: "text", text: m.content }] : Array.isArray(m.content) ? m.content.map(stripCache) : m.content } : m);
const anthropicLabel = (m: any) => (m?.role === "system" ? `system:${m.output_config?.effort}` : String(m?.role));

export function auditAnthropicPayload(payload: any, expected: Expectation, assignment: string, history?: HistoryMessage[]): Audit {
  const violations: string[] = [];
  const protocol: Audit["protocol"] = { betas: payload?.betas, thinking: payload?.thinking, outputConfig: payload?.output_config, effortMarkers: [], placeholderDeclared: false };
  const fail = (message: string) => void violations.push(message);
  if (expected.api !== "anthropic-messages") {
    fail(`no request audit exists for API ${expected.api}`);
    return { ok: false, violations, protocol };
  }
  if (!payload || typeof payload !== "object") return { ok: false, violations: ["payload is not an object"], protocol };
  protocol.encoding = checkTopLevel(payload, expected, ["messages", "system", "tools", ...(expected.fallbacks ? ["fallbacks"] : [])], fail);
  // A declared fallback list is derived from the model's allowedFallbackModels; altered, added or dropped targets fail.
  if (expected.fallbacks && !same(payload.fallbacks, expected.fallbacks)) fail("fallback list differs from the one derived from the model's allowedFallbackModels");

  // system
  const blocks = typeof payload.system === "string" ? [{ type: "text", text: payload.system }] : (payload.system ?? []);
  if (!Array.isArray(blocks)) fail("system is neither text nor a block list");
  else {
    for (const block of blocks) for (const key of Object.keys(block)) if (!SYSTEM_BLOCK_KEYS.has(key)) fail(`unrecorded system block field: ${key}`);
    const texts = blocks.map((b: any) => (b?.type === "text" ? b.text : `<${b?.type}>`));
    if (!same(texts, expected.systemTexts)) fail(`system context differs from the expected ${expected.preamble ? "auth preamble plus " : ""}treatment`);
  }

  // tools
  const tools: any[] = payload.tools ?? [];
  const names = tools.map((t) => t?.name);
  for (const tool of tools) for (const key of Object.keys(tool ?? {})) if (!TOOL_KEYS.has(key)) fail(`unrecorded tool definition field: ${key}`);
  const declaredPlaceholder = names.includes(PLACEHOLDER_NAME);
  protocol.placeholderDeclared = declaredPlaceholder;
  const expectedNames = [...expected.providerTools, ...(expected.placeholder ? [PLACEHOLDER_NAME] : [])];
  if (!same(names, expectedNames)) fail(`tool definitions [${names.join(", ")}] differ from expected [${expectedNames.join(", ")}]`);
  checkToolDefinitions(tools.map((t) => ({ name: t?.name, description: t?.description, schema: t?.input_schema, strict: t?.strict === true })), expected, fail);
  if (declaredPlaceholder) {
    const definition = tools[names.indexOf(PLACEHOLDER_NAME)];
    if (!same(withoutCache(definition), PLACEHOLDER)) fail("the protocol placeholder definition was altered");
    if (names.lastIndexOf(PLACEHOLDER_NAME) !== names.length - 1 || names.indexOf(PLACEHOLDER_NAME) !== names.lastIndexOf(PLACEHOLDER_NAME)) fail("the protocol placeholder is not a single trailing definition");
  }

  // messages: the whole list, including effort markers, is derived from the recorded transcript
  const messages: any[] = payload.messages ?? [];
  if (!Array.isArray(messages)) fail("messages is not a list");
  else {
    protocol.effortMarkers = messages.filter((m) => m?.role === "system").map((m) => m?.output_config?.effort);
    if (/"type":"tool_(addition|removal|reference)"/.test(JSON.stringify(messages))) fail("mid-conversation tool change");
    if (!Array.isArray(history)) fail("no recorded history to derive the request messages from");
    else {
      checkAssignment(history, assignment, fail);
      const wire = expectedAnthropic(history, expected);
      wire.problems.forEach(fail);
      if (wire.problems.length === 0) diffWire(messages.map(canonAnthropic), wire.items.map(canonAnthropic), anthropicLabel, fail);
    }
  }
  return { ok: violations.length === 0, violations, protocol };
}

// ---- OpenAI-family audits ---------------------------------------------------------------

const noProtocol = (): Audit["protocol"] => ({ effortMarkers: [], placeholderDeclared: false });

// Chat Completions: tools are {type:"function", function:{name, description, parameters, strict?}};
// `strict` is present exactly when the resolved supportsStrictMode is not false, and the last tool
// carries the derived cache_control marker when the model uses Anthropic cache control.
export function auditOpenAICompletionsPayload(payload: any, expected: Expectation, assignment: string, history?: HistoryMessage[]): Audit {
  const violations: string[] = [];
  const fail = (m: string) => void violations.push(m);
  const protocol = noProtocol();
  if (!payload || typeof payload !== "object") return { ok: false, violations: ["payload is not an object"], protocol };
  protocol.encoding = checkTopLevel(payload, expected, ["messages", "tools"], fail);
  const cacheControl = completionsCacheControl(expected);
  protocol.cacheControl = cacheControl;
  // tools
  const tools: any[] = payload.tools ?? [];
  const names = tools.map((t) => t?.function?.name);
  const strictKey = expected.completions.supportsStrictMode !== false;
  tools.forEach((t, i) => {
    if (t?.type !== "function" || !t.function) fail(`unrecorded tool definition type: ${t?.type}`);
    const marked = cacheControl !== undefined && i === tools.length - 1;
    for (const key of Object.keys(t ?? {})) if (!["type", "function", ...(marked ? ["cache_control"] : [])].includes(key)) fail(`unrecorded tool definition field: ${key}`);
    if (marked && !same(t?.cache_control, cacheControl)) fail("the last tool's cache_control differs from the derived marker");
    for (const key of Object.keys(t?.function ?? {})) if (!["name", "description", "parameters", ...(strictKey ? ["strict"] : [])].includes(key)) fail(`unrecorded tool definition field: ${key}`);
    if (strictKey && typeof t?.function?.strict !== "boolean") fail(`tool ${t?.function?.name} lacks the strict flag the resolved compatibility sends`);
  });
  if (!same(names, expected.configuredTools)) fail(`tool definitions [${names.join(", ")}] differ from expected [${expected.configuredTools.join(", ")}]`);
  checkToolDefinitions(tools.map((t) => ({ name: t?.function?.name, description: t?.function?.description, schema: t?.function?.parameters, strict: t?.function?.strict === true })), expected, fail);
  // messages: the leading instruction plus the transcript-derived list, annotated as the consumer annotates it
  const messages: any[] = payload.messages ?? [];
  if (!Array.isArray(messages)) return { ok: false, violations: [...violations, "messages is not a list"], protocol };
  let wire: Wire | undefined;
  if (!Array.isArray(history)) fail("no recorded history to derive the request messages from");
  else {
    checkAssignment(history, assignment, fail);
    wire = expectedCompletions(history, expected);
    wire.problems.forEach(fail);
  }
  const lead = expected.treatment.length > 0 ? [{ role: expected.instructionRole, content: sanitize(expected.treatment) }] : [];
  const want = [...lead, ...(wire && wire.problems.length === 0 ? wire.items : [])];
  if (cacheControl) annotateMessages(want, cacheControl);
  const instructed = messages[0]?.role === "system" || messages[0]?.role === "developer";
  const rest = instructed ? messages.slice(1) : messages;
  if (lead.length === 0) { if (instructed) fail(`unexpected ${messages[0].role} message at 0`); }
  else if (!instructed) fail("system context differs from the expected treatment (no instruction message)");
  else if (messages[0].role !== expected.instructionRole) fail(`instruction role ${messages[0].role} differs from the ${expected.instructionRole} role derived from the selected model`);
  else if (!same(messages[0], want[0])) fail("system context differs from the expected treatment");
  if (wire && wire.problems.length === 0) diffWire(rest, want.slice(lead.length), (m) => String(m?.role), fail);
  return { ok: violations.length === 0, violations, protocol };
}

// Responses and Codex: tools are {type:"function", name, description, parameters, strict?}; the
// input is a flat item list. `codex` moves the treatment to `instructions`.
export function auditOpenAIResponsesPayload(payload: any, expected: Expectation, assignment: string, history?: HistoryMessage[]): Audit {
  const violations: string[] = [];
  const fail = (m: string) => void violations.push(m);
  const protocol = noProtocol();
  const codex = expected.api === "openai-codex-responses";
  if (!payload || typeof payload !== "object") return { ok: false, violations: ["payload is not an object"], protocol };
  protocol.encoding = checkTopLevel(payload, expected, ["input", "tools", ...(codex ? ["instructions"] : [])], fail);
  if (codex && payload.instructions !== expected.instructions) fail("instructions differ from the expected treatment");
  const tools: any[] = payload.tools ?? [];
  const names = tools.map((t) => t?.name);
  for (const t of tools) {
    if (t?.type !== "function") fail(`unrecorded tool definition type: ${t?.type}`);
    for (const key of Object.keys(t ?? {})) if (!["type", "name", "description", "parameters", "strict"].includes(key)) fail(`unrecorded tool definition field: ${key}`);
  }
  if (!same(names, expected.configuredTools)) fail(`tool definitions [${names.join(", ")}] differ from expected [${expected.configuredTools.join(", ")}]`);
  checkToolDefinitions(tools.map((t) => ({ name: t?.name, description: t?.description, schema: t?.parameters, strict: t?.strict === true })), expected, fail);

  const items: any[] = payload.input ?? [];
  if (!Array.isArray(items)) return { ok: false, violations: [...violations, "input is not a list"], protocol };
  protocol.effortMarkers = items.filter((i) => i?.type === "configuration_update").map((i) => i?.reasoning?.effort);
  const leading: any[] = !codex && expected.treatment.length > 0 ? [{ role: expected.instructionRole, content: sanitize(expected.treatment) }] : [];
  if (!Array.isArray(history)) fail("no recorded history to derive the request input from");
  else {
    checkAssignment(history, assignment, fail);
    const wire = expectedResponses(history, expected);
    wire.problems.forEach(fail);
    if (wire.problems.length === 0) {
      const label = (i: any) => (i?.type === "configuration_update" ? `configuration_update:${i.reasoning?.effort}` : String(i?.role ?? i?.type));
      // The instruction item's role is derived from the selected model, so a differing role is reported by name.
      const lead = items[0]?.role === "system" || items[0]?.role === "developer" ? items[0] : undefined;
      if (leading.length === 0 && lead) fail(`unexpected ${lead.role} item at 0`);
      else if (leading.length > 0 && !lead) fail("system context differs from the expected treatment (no instruction item)");
      else if (lead && lead.role !== expected.instructionRole) fail(`instruction role ${lead.role} differs from the ${expected.instructionRole} role derived from the selected model`);
      else if (lead && !same(lead, leading[0])) fail("system context differs from the expected treatment");
      diffWire(lead ? items.slice(1) : items, wire.items, label, fail);
    }
  }
  return { ok: violations.length === 0, violations, protocol };
}

// Single entry for the adapter. An API outside SUPPORTED_APIS fails closed.
export function auditPayload(payload: any, expected: Expectation, assignment: string, history?: HistoryMessage[]): Audit {
  switch (expected.api) {
    case "anthropic-messages": return auditAnthropicPayload(payload, expected, assignment, history);
    case "openai-completions": return auditOpenAICompletionsPayload(payload, expected, assignment, history);
    case "openai-responses":
    case "openai-codex-responses": return auditOpenAIResponsesPayload(payload, expected, assignment, history);
    default: return { ok: false, violations: [`no request audit exists for API ${expected.api}`], protocol: noProtocol() };
  }
}

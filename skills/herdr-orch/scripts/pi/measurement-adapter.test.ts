// In-process checks for the measurement adapter. Loaded only by ./run-tests.ts inside the
// installed Pi runtime. Pure audit cases use synthetic payloads; end-to-end cases launch a real
// subject Pi (same runtime) through the Clojure launch helper against a local fake Anthropic
// endpoint, so the installed provider code builds the request that is audited.
import assert from "node:assert/strict";
import { spawn, spawnSync } from "node:child_process";
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, realpathSync, rmSync, statSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { auditPayload, deriveActiveEffort, deriveExpectation, deriveTopLevel, OAUTH_PREAMBLE, PLACEHOLDER, PLACEHOLDER_NAME, toClaudeCodeName, type Expectation } from "./measurement-audit.ts";
import { startFakeAnthropic, type Scripted } from "./fake-anthropic.ts";
import { startFakeOpenAI } from "./fake-openai.ts";
import type { TestCase } from "./runtime.ts";

const here = dirname(fileURLToPath(import.meta.url));
const repo = realpathSync(join(here, "../../../.."));
const ASSIGNMENT = "Summarise the experiment.";

// ---- pure audit helpers -------------------------------------------------------------
const facts = (over: any = {}) => ({ api: "anthropic-messages", oauth: false, compat: {}, thinkingOff: false, provider: "fakeanth", model: "fake-model", maxTokens: 100, ...over });
// The message, tool and system cases fill in the derived top-level encoding (deriveTopLevel),
// which its own cases check against literal values and the end-to-end cases against the
// installed consumer; a key the derivation does not produce stays, so an unrecorded field still fails.
const encoded = (payload: any, e: Expectation) => (payload && typeof payload === "object" && !Array.isArray(payload) ? { ...payload, ...deriveTopLevel(e) } : payload);
const def = (name: string, extra: object = {}) => ({ name, description: `${name} tool`, input_schema: { type: "object", properties: {}, required: [] }, eager_input_streaming: true, ...extra });
const user = (text: string = ASSIGNMENT) => ({ role: "user", content: [{ type: "text", text, cache_control: { type: "ephemeral" } }] });
const payloadFor = (over: any = {}) => ({ model: "m", max_tokens: 10, stream: true, messages: [user()], ...over });
// Recorded transcript helpers: Pi's own message shapes, as the adapter records them.
const U = (text: string = ASSIGNMENT) => ({ role: "user", content: [{ type: "text", text }] });
const A = (f: any, blocks: any[], over: object = {}) => ({ role: "assistant", content: blocks, api: f.api, provider: f.provider, model: f.model, ...over });
const CALL = (id = "t1", name = "read", args: object = {}) => ({ type: "toolCall", id, name, arguments: args });
const R = (id = "t1", text = "ok", over: object = {}) => ({ role: "toolResult", toolCallId: id, toolName: "read", isError: false, content: [{ type: "text", text }], ...over });
const registryFor = (tools: string[]) => Object.fromEntries(tools.map((t) => [t, { description: `${t} tool`, parameters: { type: "object", properties: {} } }]));
const audit = (payload: any, deriveInput: { treatment?: string; tools?: string[]; facts?: any; history?: any[]; registry?: Record<string, any> } = {}) => {
  const tools = deriveInput.tools ?? [];
  const e = deriveExpectation({ treatment: deriveInput.treatment ?? "TREATMENT", configuredTools: tools, facts: deriveInput.facts ?? facts(), registry: deriveInput.registry ?? registryFor(tools) });
  return auditPayload(encoded(payload, e), e, ASSIGNMENT, deriveInput.history ?? [U()]);
};
const sys = (...texts: string[]) => texts.map((text) => ({ type: "text", text, cache_control: { type: "ephemeral" } }));
const native = { supportsMidConvoSystemMessages: true, supportsMidConvoToolChanges: true };
const rejected = (a: { ok: boolean; violations: string[] }, pattern: RegExp) => {
  assert.equal(a.ok, false, "expected the audit to fail");
  assert.ok(a.violations.some((v) => pattern.test(v)), `${a.violations.join(" | ")} !~ ${pattern}`);
};

// One scripted exchange (assignment, one read call, its result) in each API's own encoding, plus
// the registry facts it is derived from. The passing control and every known-bad mutation of it
// run against the same expectation.
const SCHEMA = { type: "object", properties: { path: { type: "string", description: "Path to read" }, mode: { type: "string", enum: ["a", "b"], description: "Mode" } }, required: ["path"] };
const EXCHANGE_REGISTRY = { read: { description: "Read a file", parameters: SCHEMA } };
const EXCHANGE_ARGS = { path: "data.txt" };
type Api = "anthropic-messages" | "openai-completions" | "openai-responses" | "openai-codex-responses";
const APIS: Api[] = ["anthropic-messages", "openai-completions", "openai-responses", "openai-codex-responses"];
function exchange(api: Api, opts: { inputImage?: boolean; compat?: object } = {}) {
  const f = facts({ api, provider: api === "anthropic-messages" ? "fakeanth" : "fakeopenai", inputImage: opts.inputImage ?? true, compat: opts.compat ?? {} });
  const id = api === "anthropic-messages" || api === "openai-completions" ? "call_1" : "call_1|fc_1";
  const history = [U(), A(f, [CALL(id, "read", EXCHANGE_ARGS)]), R(id, "file contents")];
  const instruction = { role: "system", content: "TREATMENT" };
  const payload: any = (() => {
    switch (api) {
      case "anthropic-messages": return {
        model: "m", max_tokens: 10, stream: true, system: sys("TREATMENT"),
        tools: [{ name: "read", description: "Read a file", eager_input_streaming: true, input_schema: { type: "object", properties: structuredClone(SCHEMA.properties), required: ["path"] } }],
        messages: [user(), { role: "assistant", content: [{ type: "tool_use", id: "call_1", name: "read", input: EXCHANGE_ARGS }] }, { role: "user", content: [{ type: "tool_result", tool_use_id: "call_1", content: "file contents", is_error: false, cache_control: { type: "ephemeral" } }] }],
      };
      case "openai-completions": return {
        model: "m", stream: true, tools: [{ type: "function", function: { name: "read", description: "Read a file", parameters: structuredClone(SCHEMA), ...(f.compat.supportsStrictMode ? { strict: false } : {}) } }],
        messages: [instruction, { role: "user", content: [{ type: "text", text: ASSIGNMENT }] }, { role: "assistant", content: null, tool_calls: [{ id: "call_1", type: "function", function: { name: "read", arguments: JSON.stringify(EXCHANGE_ARGS) } }] }, { role: "tool", content: "file contents", tool_call_id: "call_1" }],
      };
      default: return {
        model: "m", store: false, stream: true, tools: [{ type: "function", name: "read", description: "Read a file", parameters: structuredClone(SCHEMA), ...(api === "openai-codex-responses" ? { strict: null } : {}) }],
        ...(api === "openai-codex-responses" ? { instructions: "TREATMENT" } : {}),
        input: [...(api === "openai-codex-responses" ? [] : [instruction]), { role: "user", content: [{ type: "input_text", text: ASSIGNMENT }] },
          { type: "function_call", id: "fc_1", call_id: "call_1", name: "read", arguments: JSON.stringify(EXCHANGE_ARGS) }, { type: "function_call_output", call_id: "call_1", output: "file contents" }],
      };
    }
  })();
  const run = (p: any = payload, over: { history?: any[]; treatment?: string; registry?: any } = {}) => {
    const e = deriveExpectation({ treatment: over.treatment ?? "TREATMENT", configuredTools: ["read"], facts: f, registry: over.registry ?? EXCHANGE_REGISTRY });
    return auditPayload(encoded(p, e), e, ASSIGNMENT, over.history ?? history);
  };
  return { f, payload, history, run };
}
// Each mutation leaves the request plausible; each must fail on every API.
function mutations(api: Api): [string, (p: any) => void, RegExp][] {
  const a = api === "anthropic-messages";
  const comp = api === "openai-completions";
  const tool = (p: any) => (a ? p.tools[0] : comp ? p.tools[0].function : p.tools[0]);
  const schema = (p: any) => (a ? p.tools[0].input_schema : comp ? p.tools[0].function.parameters : p.tools[0].parameters);
  const resultHolder = (p: any) => (a ? p.messages[2].content[0] : comp ? p.messages[3] : p.input.find((i: any) => i.type === "function_call_output"));
  const callHolder = (p: any) => (a ? p.messages[1].content[0] : comp ? p.messages[2].tool_calls[0] : p.input.find((i: any) => i.type === "function_call"));
  const diff = /differs from the recorded transcript|differ from the recorded history/;
  return [
    ["tool-result text changed", (p) => { const r = resultHolder(p); if (a) r.content = "INJECTED"; else if (comp) r.content = "INJECTED"; else r.output = "INJECTED"; }, diff],
    ["assistant text injected", (p) => {
      if (a) p.messages[1].content.push({ type: "text", text: "INJECTED" });
      else if (comp) p.messages[2].content = "INJECTED";
      else p.input.splice(p.input.findIndex((i: any) => i.type === "function_call"), 0, { type: "message", role: "assistant", content: [{ type: "output_text", text: "INJECTED", annotations: [] }], status: "completed", id: "msg_x" });
    }, diff],
    ["tool-call arguments changed", (p) => { const c = callHolder(p); if (a) c.input = { path: "other.txt" }; else if (comp) c.function.arguments = JSON.stringify({ path: "other.txt" }); else c.arguments = JSON.stringify({ path: "other.txt" }); }, diff],
    ["tool-call id changed at both ends", (p) => {
      const c = callHolder(p); const r = resultHolder(p);
      if (a) { c.id = "call_X"; r.tool_use_id = "call_X"; } else if (comp) { c.id = "call_X"; r.tool_call_id = "call_X"; } else { c.call_id = "call_X"; r.call_id = "call_X"; }
    }, diff],
    ["tool-call name changed", (p) => { const c = callHolder(p); if (comp) c.function.name = "other"; else c.name = "other"; }, diff],
    ["tool description changed", (p) => { tool(p).description += " (altered)"; }, /description differs/],
    ["schema property description changed", (p) => { schema(p).properties.path.description += " (altered)"; }, /parameter schema differs/],
    ["schema property added", (p) => { schema(p).properties.injected = { type: "string", description: "INJECTED" }; }, /parameter schema differs/],
    ["schema enum value changed", (p) => { schema(p).properties.mode.enum = ["a", "INJECTED"]; }, /parameter schema differs/],
    ["schema property renamed", (p) => { const s = schema(p); s.properties.renamed = s.properties.mode; delete s.properties.mode; }, /parameter schema differs/],
  ];
}

// ---- end-to-end harness --------------------------------------------------------------
interface Run {
  code: number | null;
  stdout: string;
  stderr: string;
  requests: any[];
  headers: Record<string, string | string[] | undefined>[];
  adapter: (name: string) => any;
  work: string;
  // The work root's entries when the subject ended, read before the root is removed, so a side effect is observable.
  workFiles: string[];
  specEnv: Record<string, string>;
  argv: string[];
}
interface RunOptions {
  script?: Scripted[];
  tools?: string[];
  system?: string;
  skills?: string[];
  assignment?: string;
  oauth?: boolean;
  compat?: object;
  reasoning?: boolean;
  contaminate?: string;
  // Declare image input on the fixture model (default: text only) and add the scripted `snap` tool, which returns images.
  vision?: boolean;
  snap?: boolean;
  // Evidence files pre-created as directories so that writing them fails.
  sabotage?: string[];
  modelArgs?: string[];
  api?: string;
  authExtension?: boolean;
  discovered?: boolean;
  extensions?: string[];
  // Agent settings.json entries (e.g. thinkingBudgets), a runtime input of the request encoding.
  settings?: object;
  // Extra models.json fields of the fixture model (headers, samplingParams, samplingParamsByThinkingLevel).
  modelExtra?: object;
  // Extra subject environment, such as PI_CACHE_RETENTION, which the runtime reads from the process env.
  env?: Record<string, string>;
}

// Known-bad mutations of the real request, applied by an extension that loads before the adapter.
// Each leaves the request otherwise plausible. A mutation that finds nothing to change returns
// early, so history mutations only act on the request that carries the history.
const FIRST_TEXT = `(c) => typeof c === "string" ? c : c[0].text`;
function contamination(api: string, kind: string): string {
  const anthropicBlocks = `payload.messages.flatMap((m) => Array.isArray(m.content) ? m.content : [])`;
  const table: Record<string, Record<string, string>> = {
    "anthropic-messages": {
      system: `payload.system = [...(payload.system ?? []), { type: "text", text: "CONTAMINATION" }];`,
      message: `payload.messages[0].content = [{ type: "text", text: "CONTAMINATION " }, ...payload.messages[0].content];`,
      "effort-level": `const m = payload.messages.find((x, i) => x.role === "system" && i < payload.messages.length - 1); if (m) m.output_config.effort = m.output_config.effort === "low" ? "medium" : "low";`,
      "tool-add": `payload.tools = [...(payload.tools ?? []), { name: "extra", description: "x", input_schema: { type: "object", properties: {}, required: [] } }];`,
      "tool-desc": `payload.tools[0].description += " (altered)";`,
      "schema-desc": `payload.tools[0].input_schema.properties.path.description += " (altered)";`,
      "schema-prop": `payload.tools[0].input_schema.properties.injected = { type: "string", description: "INJECTED" };`,
      "tool-history": `const b = ${anthropicBlocks}.find((x) => x.type === "tool_use"); if (!b) return; b.name = "other";`,
      "tool-result": `const b = ${anthropicBlocks}.find((x) => x.type === "tool_result"); if (!b) return; b.content = "INJECTED " + b.content;`,
      "assistant-text": `const m = payload.messages.find((x) => x.role === "assistant"); if (!m) return; m.content.push({ type: "text", text: "INJECTED" });`,
      "tool-args": `const b = ${anthropicBlocks}.find((x) => x.type === "tool_use"); if (!b) return; b.input = { ...b.input, path: "other.txt" };`,
      "tool-id": `const u = ${anthropicBlocks}.find((x) => x.type === "tool_use"); const r = ${anthropicBlocks}.find((x) => x.type === "tool_result"); if (!u) return; u.id += "X"; r.tool_use_id += "X";`,
      "audit-throws": `payload.tools = "not-a-list";`,
      "fallback-alter": `payload.fallbacks = [{ model: "other" }];`,
      "fallback-add": `payload.fallbacks = [...(payload.fallbacks ?? []), { model: "extra" }];`,
      "fallback-drop": `delete payload.fallbacks;`,
      // Top-level encoding: each changed or added value fails on the first request.
      "enc-output-config": `payload.output_config = { ...(payload.output_config ?? {}), effort: "max", format: { type: "json_schema", schema: { type: "object", description: "INJECTED-FORMAT-TEXT" } } };`,
      "enc-effort": `if (!payload.output_config) return; payload.output_config.effort = payload.output_config.effort === "low" ? "high" : "low";`,
      "enc-budget": `if (payload.thinking?.type !== "enabled") return; payload.thinking.budget_tokens = 31999;`,
      "enc-thinking-field": `if (!payload.thinking) return; payload.thinking.injected = "INJECTED";`,
      "enc-tool-choice": `payload.tool_choice = { type: "any" };`,
      "enc-metadata": `payload.metadata = { user_id: "INJECTED" };`,
      "enc-max-tokens": `payload.max_tokens += 1;`,
      "enc-beta": `payload.betas = [...(payload.betas ?? []), "extra-beta-2026-01-01"];`,
      // The betas the runtime would compute without a configured anthropic-beta header.
      "enc-beta-computed": `payload.betas = ["interleaved-thinking-2025-05-14"];`,
    },
    "openai-completions": {
      system: `const m = payload.messages.find((x) => x.role === "system" || x.role === "developer"); m.content += " CONTAMINATION";`,
      message: `const u = payload.messages.find((x) => x.role === "user"); u.content = "CONTAMINATION " + (${FIRST_TEXT})(u.content);`,
      "tool-add": `payload.tools = [...(payload.tools ?? []), { type: "function", function: { name: "extra", description: "x", parameters: { type: "object", properties: {} } } }];`,
      "tool-desc": `payload.tools[0].function.description += " (altered)";`,
      "schema-desc": `payload.tools[0].function.parameters.properties.path.description += " (altered)";`,
      "schema-prop": `payload.tools[0].function.parameters.properties.injected = { type: "string", description: "INJECTED" };`,
      "tool-history": `const m = payload.messages.find((x) => x.tool_calls); if (!m) return; m.tool_calls[0].function.name = "other";`,
      "tool-result": `const m = payload.messages.find((x) => x.role === "tool"); if (!m) return; m.content = "INJECTED " + m.content;`,
      "assistant-text": `const m = payload.messages.find((x) => x.role === "assistant"); if (!m) return; m.content = "INJECTED";`,
      "tool-args": `const m = payload.messages.find((x) => x.tool_calls); if (!m) return; m.tool_calls[0].function.arguments = JSON.stringify({ path: "other.txt" });`,
      "tool-id": `const m = payload.messages.find((x) => x.tool_calls); const t = payload.messages.find((x) => x.role === "tool"); if (!m) return; m.tool_calls[0].id += "X"; t.tool_call_id += "X";`,
      "audit-throws": `payload.tools = "not-a-list";`,
      "enc-effort": `payload.reasoning_effort = payload.reasoning_effort === "low" ? "high" : "low";`,
      "enc-kwargs": `payload.chat_template_kwargs = { enable_thinking: true, extra: "INJECTED-TEMPLATE-TEXT" };`,
      "enc-format": `payload.response_format = { type: "json_schema", json_schema: { name: "x", schema: { type: "object", description: "INJECTED-FORMAT-TEXT" } } };`,
      "enc-tool-choice": `payload.tool_choice = "required";`,
      "enc-nested": `payload.stream_options = { ...payload.stream_options, injected: "INJECTED" };`,
      "enc-retention-drop": `delete payload.prompt_cache_retention;`,
      "enc-sampling": `payload.top_p = 0.9; payload.temperature = 0.7;`,
      "enc-or-effort": `if (!payload.reasoning) return; payload.reasoning = { ...payload.reasoning, effort: "xhigh" };`,
      "tool-sibling": `payload.tools[0].injected = "INJECTED";`,
      // Anthropic-format cache_control: placement, shape and the text it annotates are derived.
      "cc-strip-instruction": `const m = payload.messages[0]; if (!Array.isArray(m.content)) return; m.content = m.content[0].text;`,
      "cc-instruction-text": `const m = payload.messages[0]; if (!Array.isArray(m.content)) return; m.content[0].text += " INJECTED";`,
      "cc-ttl": `const t = payload.tools[payload.tools.length - 1]; if (!t.cache_control) return; t.cache_control = { type: "ephemeral", ttl: "1h" };`,
      "cc-tool-move": `const t = payload.tools; if (t.length < 2 || !t[t.length - 1].cache_control) return; t[0].cache_control = t[t.length - 1].cache_control; delete t[t.length - 1].cache_control;`,
      "cc-extra-user": `if (!payload.messages.some((x) => x.role === "tool")) return; const u = payload.messages.find((x) => x.role === "user"); u.content[u.content.length - 1].cache_control = { type: "ephemeral" };`,
      "cc-move-last": `const t = payload.messages.find((x) => x.role === "tool"); if (!t || !Array.isArray(t.content)) return; t.content = t.content[0].text; const u = payload.messages.find((x) => x.role === "user"); u.content[u.content.length - 1].cache_control = { type: "ephemeral" };`,
      "rd-alter": `const m = payload.messages.find((x) => x.reasoning_details); if (!m) return; m.reasoning_details[0].text = "INJECTED";`,
      "rd-drop": `const m = payload.messages.find((x) => x.reasoning_details); if (!m) return; delete m.reasoning_details;`,
    },
    "openai-responses": {
      system: `const m = payload.input.find((x) => x.role === "system" || x.role === "developer"); m.content += " CONTAMINATION";`,
      message: `const u = payload.input.find((x) => x.role === "user"); u.content[0].text = "CONTAMINATION " + u.content[0].text;`,
      "effort-level": `const m = payload.input.find((x, i) => x.type === "configuration_update" && i < payload.input.length - 1); if (m) m.reasoning.effort = m.reasoning.effort === "low" ? "medium" : "low";`,
      "tool-add": `payload.tools = [...(payload.tools ?? []), { type: "function", name: "extra", description: "x", parameters: { type: "object", properties: {} } }];`,
      "tool-desc": `payload.tools[0].description += " (altered)";`,
      "schema-desc": `payload.tools[0].parameters.properties.path.description += " (altered)";`,
      "schema-prop": `payload.tools[0].parameters.properties.injected = { type: "string", description: "INJECTED" };`,
      "tool-history": `const c = payload.input.find((x) => x.type === "function_call"); if (!c) return; c.name = "other";`,
      "tool-result": `const o = payload.input.find((x) => x.type === "function_call_output"); if (!o) return; o.output = "INJECTED " + o.output;`,
      "assistant-text": `const i = payload.input.findIndex((x) => x.type === "function_call"); if (i < 0) return; payload.input.splice(i, 0, { type: "message", role: "assistant", content: [{ type: "output_text", text: "INJECTED", annotations: [] }], status: "completed", id: "msg_x" });`,
      "tool-args": `const c = payload.input.find((x) => x.type === "function_call"); if (!c) return; c.arguments = JSON.stringify({ path: "other.txt" });`,
      "tool-id": `const c = payload.input.find((x) => x.type === "function_call"); const o = payload.input.find((x) => x.type === "function_call_output"); if (!c) return; c.call_id += "X"; o.call_id += "X";`,
      "audit-throws": `payload.tools = "not-a-list";`,
      "config-drop": `const i = payload.input.map((x) => x.type).lastIndexOf("configuration_update"); if (i < 0) return; payload.input.splice(i, 1);`,
      "config-add": `payload.input.push({ type: "configuration_update", reasoning: { effort: "high" } });`,
      "config-field": `const c = payload.input.find((x) => x.type === "configuration_update"); if (!c) return; c.reasoning.summary = "INJECTED";`,
      "enc-effort": `if (!payload.reasoning) return; payload.reasoning = { ...payload.reasoning, effort: "xhigh" };`,
      "enc-format": `payload.text = { ...(payload.text ?? {}), format: { type: "json_schema", name: "x", description: "INJECTED-FORMAT-TEXT", schema: { type: "object" } } };`,
      "enc-include": `payload.include = [...(payload.include ?? []), "file_search_call.results"];`,
      "enc-tool-choice": `payload.tool_choice = "required";`,
      "enc-cache-key": `payload.prompt_cache_key = "other-session";`,
    },
  };
  table["openai-codex-responses"] = { ...table["openai-responses"], system: `payload.instructions += " CONTAMINATION";`, "codex-default": `payload.instructions = "You are a helpful assistant.";`,
    "enc-verbosity": `payload.text = { ...payload.text, verbosity: "high" };`, "enc-parallel": `payload.parallel_tool_calls = false;` };
  // Generic kinds: a literal swap anywhere in the request, an extra image on the result, all images dropped.
  if (kind.startsWith("swap:")) {
    const [from, to] = JSON.parse(kind.slice(5));
    return `const text = JSON.stringify(payload); if (!text.includes(${JSON.stringify(from)})) return; return JSON.parse(text.replace(${JSON.stringify(from)}, ${JSON.stringify(to)}));`;
  }
  if (kind === "image-drop") return `const drop = (x) => Array.isArray(x) ? x.filter((v) => !(v && typeof v === "object" && ["image", "image_url", "input_image"].includes(v.type))).map(drop) : x && typeof x === "object" ? Object.fromEntries(Object.entries(x).map(([k, v]) => [k, drop(v)])) : x; return drop(payload);`;
  if (kind === "image-extra") {
    return {
      "anthropic-messages": `const m = payload.messages.find((x) => Array.isArray(x.content) && x.content.some((b) => b.type === "tool_result")); if (!m) return; m.content.push({ type: "image", source: { type: "base64", media_type: "image/png", data: "AAAA" } });`,
      "openai-completions": `if (!payload.messages.some((x) => x.role === "tool")) return; const img = { type: "image_url", image_url: { url: "data:image/png;base64,AAAA" } }; const last = payload.messages[payload.messages.length - 1]; if (last.role === "user" && Array.isArray(last.content)) last.content.push(img); else payload.messages.push({ role: "user", content: [{ type: "text", text: "Attached image(s) from tool result:" }, img] });`,
    }[api] ?? `const o = payload.input.find((x) => x.type === "function_call_output"); if (!o) return; const img = { type: "input_image", detail: "auto", image_url: "data:image/png;base64,AAAA" }; o.output = Array.isArray(o.output) ? [...o.output, img] : [{ type: "input_text", text: String(o.output) }, img];`;
  }
  const code = table[api]?.[kind];
  if (!code) throw new Error(`no ${kind} contamination for ${api}`);
  return code;
}

async function runSubject(options: RunOptions = {}): Promise<Run> {
  const root = realpathSync(mkdtempSync(join(tmpdir(), "herdr-measure-")));
  const api = options.api ?? "anthropic-messages";
  const script = options.script ?? [{ text: "done" }];
  const fake = api === "openai-completions" ? await startFakeOpenAI("completions", script)
    : api === "openai-responses" || api === "openai-codex-responses" ? await startFakeOpenAI("responses", script)
    : await startFakeAnthropic(script);
  try {
    const work = join(root, "work");
    const agent = join(root, "agent");
    const cfg = join(root, "cfg");
    for (const d of [work, agent, cfg]) mkdirSync(d, { recursive: true });
    writeFileSync(join(work, "data.txt"), "file contents\n");
    // Codex derives an account id from the credential's JWT claims; the SSE transport avoids a websocket.
    const codexToken = `x.${Buffer.from(JSON.stringify({ "https://api.openai.com/auth": { chatgpt_account_id: "acct-fixture" } })).toString("base64")}.y`;
    const settings = { ...(api === "openai-codex-responses" ? { transport: "sse" } : {}), ...options.settings };
    if (Object.keys(settings).length > 0) writeFileSync(join(agent, "settings.json"), JSON.stringify(settings));
    writeFileSync(join(agent, "models.json"), JSON.stringify({
      providers: { fakeanth: { baseUrl: fake.url, api: options.api ?? "anthropic-messages", apiKey: api === "openai-codex-responses" ? codexToken : options.oauth ? "sk-ant-oat-fake" : api.startsWith("openai") ? "sk-fixture-openai" : "sk-ant-api-fake",
        models: [{ id: "fake-model", reasoning: options.reasoning ?? false, compat: options.compat ?? {}, ...options.modelExtra, ...(options.vision === undefined ? {} : { input: options.vision ? ["text", "image"] : ["text"] }) }] } },
    }));
    if (options.discovered) {
      // Everything Pi would normally discover; none of it may reach the request.
      writeFileSync(join(work, "AGENTS.md"), "DISCOVERED-PROJECT-CONTEXT\n");
      writeFileSync(join(agent, "AGENTS.md"), "DISCOVERED-HOME-CONTEXT\n");
      mkdirSync(join(agent, "skills", "leak"), { recursive: true });
      writeFileSync(join(agent, "skills", "leak", "SKILL.md"), "---\nname: leak\ndescription: DISCOVERED-SKILL\n---\nbody\n");
      mkdirSync(join(agent, "prompts"), { recursive: true });
      writeFileSync(join(agent, "prompts", "leak.md"), "DISCOVERED-TEMPLATE\n");
      mkdirSync(join(agent, "extensions"), { recursive: true });
      writeFileSync(join(agent, "extensions", "leak.ts"), `export default function (pi) { pi.on("before_agent_start", async () => ({ message: { customType: "leak", content: "DISCOVERED-EXTENSION", display: false } })); }\n`);
      mkdirSync(join(work, ".pi", "skills", "leak2"), { recursive: true });
      writeFileSync(join(work, ".pi", "skills", "leak2", "SKILL.md"), "---\nname: leak2\ndescription: DISCOVERED-PROJECT-SKILL\n---\nbody\n");
    }
    const extensions = [...(options.extensions ?? [])];
    if (options.authExtension) {
      // A provider-auth extension: it adds credentials at the header boundary, which the audit
      // does not touch because only the request body carries model-visible context.
      const path = join(cfg, "auth.ts");
      writeFileSync(path, `export default function (pi) { pi.on("before_provider_headers", async (e) => { e.headers["x-fixture-auth"] = "explicit-extension-token"; }); }\n`);
      extensions.push(path);
    }
    if (options.snap) {
      // An explicitly loaded extension tool that returns the scripted result shapes: empty, image-only, mixed and an error result with an image.
      const path = join(cfg, "snap.ts");
      writeFileSync(path, `import { Type } from "typebox";
export default function (pi) {
  pi.registerTool({ name: "snap", label: "snap", description: "Return a scripted result.", parameters: Type.Object({ mode: Type.String({ description: "Which result to return" }) }),
    async execute(_id, params) {
      const png = { type: "image", data: "iVBORw0KGgo=", mimeType: "image/png" };
      const jpg = { type: "image", data: "/9j/4AAQ", mimeType: "image/jpeg" };
      const modes = { empty: [], image: [png], mixed: [{ type: "text", text: "caption" }, png, jpg], error: [{ type: "text", text: "boom" }, png] };
      return { content: modes[params.mode] ?? [], details: {} };
    } });
  pi.on("tool_result", async (e) => (e.toolName === "snap" && e.input?.mode === "error" ? { content: e.content, isError: true } : undefined));
}
`);
      extensions.push(path);
    }
    if (options.contaminate && options.contaminate !== "none") {
      const path = join(cfg, "contaminate.ts");
      const mutate = contamination(api, options.contaminate);
      writeFileSync(path, `export default function (pi) { pi.on("before_provider_request", async (e) => { const payload = structuredClone(e.payload); ${mutate} return payload; }); }\n`);
      extensions.push(path);
    }
    const config: any = { tools: options.tools ?? [], extensions, hardTimeoutMs: 60000, expectedOutputs: [] };
    if (options.system !== undefined) { writeFileSync(join(cfg, "system.md"), options.system); config.systemPromptFile = "system.md"; }
    if (options.skills) { config.skillFiles = options.skills.map((text, i) => { writeFileSync(join(cfg, `skill-${i}.md`), text); return `skill-${i}.md`; }); }
    writeFileSync(join(cfg, "measurement.json"), JSON.stringify(config));
    const assignmentFile = join(cfg, "assignment.txt");
    writeFileSync(assignmentFile, options.assignment ?? ASSIGNMENT);
    const evidence = join(root, "evidence");
    const spec = spawnSync("bb", ["--config", join(repo, "bb.edn"), "--deps-root", repo, "-m", "herdr-orch.measurement", "launch-spec", join(cfg, "measurement.json"), "task-1", assignmentFile, evidence, here, "--provider", "fakeanth", "--model", "fake-model", ...(options.modelArgs ?? [])],
      { encoding: "utf8", cwd: repo, timeout: 60000 });
    assert.equal(spec.status, 0, `launch helper failed: ${spec.stderr}${spec.stdout}`);
    const launch = JSON.parse(spec.stdout);
    // Same runtime as this process, regardless of what `pi` resolves to on PATH.
    const argv = [process.argv[1], ...launch.argv.slice(1)];
    const env = { ...launch.env, ...options.env, PI_CODING_AGENT_DIR: agent, PI_OFFLINE: "1" };
    for (const name of options.sabotage ?? []) mkdirSync(join(launch["adapter-evidence-dir"], name), { recursive: true });
    const child = spawn(process.execPath, argv, { cwd: work, env, stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (d) => (stdout += d));
    child.stderr.on("data", (d) => (stderr += d));
    const code = await new Promise<number | null>((resolve) => child.on("close", resolve));
    // Read the evidence and the work root now: both are removed below.
    const workFiles = readdirSync(work);
    const adapterDir = launch["adapter-evidence-dir"];
    const files = new Map<string, string>();
    if (existsSync(adapterDir)) for (const name of readdirSync(adapterDir)) if (statSync(join(adapterDir, name)).isFile()) files.set(name, readFileSync(join(adapterDir, name), "utf8"));
    return {
      code, stdout, stderr, requests: [...fake.requests], headers: [...fake.headers], work, workFiles, specEnv: launch.env, argv,
      adapter: (name) => {
        const text = files.get(name);
        if (text === undefined) throw new Error(`no adapter evidence ${name}; stderr: ${stderr.slice(0, 400)}`);
        return name.endsWith(".jsonl") ? text.trim().split("\n").map((line) => JSON.parse(line)) : JSON.parse(text);
      },
    };
  } finally {
    await fake.close();
    rmSync(root, { recursive: true, force: true });
  }
}

const readEvents = (run: Run): any[] => { try { return run.adapter("events.jsonl"); } catch { return []; } };
const hasAdapterFile = (run: Run, name: string) => { try { run.adapter(name); return true; } catch { return false; } };
const textOfContent = (c: any): string => (typeof c === "string" ? c : c.map((b: any) => b.text).join(""));
const requestText = (run: Run) => JSON.stringify(run.requests);

const HISTORY_KINDS = ["tool-history", "tool-result", "assistant-text", "tool-args", "tool-id"] as const;
const REQUEST_KINDS = ["system", "message", "tool-add", "tool-desc", "schema-desc", "schema-prop"] as const;
const knownBadRequests = (api: Api): TestCase => (
{ name: `e2e ${api}: known-bad requests fail the audit after the passing control; complete payloads are retained`, async run() {
      const script: Scripted[] = [{ toolUse: { id: "call_1", name: "read", input: { path: "data.txt" } } }, { text: "done" }];
      const common = { api, tools: ["read"], system: "SYS", script };
      const control = await runSubject({ ...common, contaminate: "none" });
      assert.equal(control.adapter("terminal.json").success, true, "control");
      assert.equal(control.requests.length, 2, "control sends both requests");
      // The request-level kinds are blocked before the first request is sent.
      for (const kind of REQUEST_KINDS) {
        const bad = await runSubject({ ...common, contaminate: kind });
        assert.equal(bad.requests.length, 0, `${kind}: the contaminated request is never sent`);
        assert.equal(bad.adapter("terminal.json").success, false, kind);
        assert.match(bad.adapter("terminal.json").reason, /request-context audit failed/, kind);
        assert.equal(bad.adapter("audit-1.json").ok, false, kind);
        const kept = bad.adapter("request-1.json");
        assert.ok(kept.model !== undefined && (kept.messages ?? kept.input) !== undefined, `${kind}: the complete failing payload is retained`);
      }
      // The history kinds act on the second request, the one that carries the tool exchange.
      for (const kind of HISTORY_KINDS) {
        const bad = await runSubject({ ...common, contaminate: kind });
        assert.equal(bad.requests.length, 1, `${kind}: only the first request was sent; the second was blocked`);
        const terminal = bad.adapter("terminal.json");
        assert.equal(terminal.success, false, kind);
        assert.match(terminal.reason, /request-context audit failed: .*(differs from the recorded|differ from the recorded history)/, kind);
        assert.equal(bad.adapter("audit-1.json").ok, true, `${kind}: the first request passed`);
        assert.equal(bad.adapter("audit-2.json").ok, false, kind);
        const kept = bad.adapter("request-2.json");
        assert.notDeepEqual(kept, control.adapter("request-2.json"), `${kind}: the retained payload is the contaminated one`);
        assert.equal(bad.adapter("request-2.json").model, control.adapter("request-2.json").model);
      }
    } }
);

export const tests: TestCase[] = [
  // ---- pure audit ------------------------------------------------------------------
  { name: "audit: a plain request with the exact treatment passes", run() {
    const a = audit(payloadFor({ system: sys("TREATMENT") }));
    assert.deepEqual(a.violations, []);
    assert.equal(a.ok, true);
  } },
  { name: "audit: unexpected model-visible text fails", run() {
    rejected(audit(payloadFor({ system: sys("TREATMENT", "EXTRA") })), /system context differs/);
    rejected(audit(payloadFor({ system: sys("TREATMENT"), messages: [user("wrapped: " + ASSIGNMENT)] })), /message 0 \(user\) differs from the recorded transcript/);
    rejected(audit(payloadFor({ system: sys("TREATMENT"), messages: [user(), user("second")] })), /message roles \[user, user\] differ from the recorded history \[user\]/);
    rejected(audit(payloadFor({ system: sys("TREATMENT"), extra_field: 1 })), /unrecorded top-level request field/);
    // The assignment itself is the recorded first user message.
    rejected(audit(payloadFor({ system: sys("TREATMENT"), messages: [user("other")] }), { history: [U("other")] }), /recorded first user message differs from the assignment/);
    rejected(audit(payloadFor({ system: sys("TREATMENT") }), { history: [U(), U("again")] }), /more than one user message/);
  } },
  { name: "audit: an empty treatment expects no system blocks", run() {
    assert.equal(audit(payloadFor(), { treatment: "" }).ok, true);
    rejected(audit(payloadFor({ system: sys("sneaky") }), { treatment: "" }), /system context differs/);
  } },
  { name: "audit: OAuth expects exactly the auth preamble, recorded apart from the treatment", run() {
    const oauth = facts({ oauth: true });
    assert.equal(audit(payloadFor({ system: sys(OAUTH_PREAMBLE, "TREATMENT") }), { facts: oauth }).ok, true);
    rejected(audit(payloadFor({ system: sys("You are Claude Code (modified).", "TREATMENT") }), { facts: oauth }), /system context differs/);
    rejected(audit(payloadFor({ system: sys("TREATMENT") }), { facts: oauth }), /system context differs/);
    // Other auth modes expect no preamble.
    rejected(audit(payloadFor({ system: sys(OAUTH_PREAMBLE, "TREATMENT") })), /system context differs/);
    const expectation = deriveExpectation({ treatment: "TREATMENT", configuredTools: ["read"], facts: oauth, registry: registryFor(["read"]) });
    assert.equal(expectation.preamble, OAUTH_PREAMBLE);
    assert.deepEqual(expectation.systemTexts, [OAUTH_PREAMBLE, "TREATMENT"]);
    assert.deepEqual(expectation.toolMap, { Read: "read" }, "provider names map back to configured names");
  } },
  { name: "audit: tool names follow the recorded conversion rule only under OAuth", run() {
    const oauth = facts({ oauth: true });
    const system = (f: any) => sys(...(f.oauth ? [OAUTH_PREAMBLE] : []), "TREATMENT");
    assert.equal(audit(payloadFor({ system: system(oauth), tools: [def("Read", { description: "read tool" })] }), { tools: ["read"], facts: oauth }).ok, true);
    rejected(audit(payloadFor({ system: system(oauth), tools: [def("read")] }), { tools: ["read"], facts: oauth }), /tool definitions/);
    assert.equal(audit(payloadFor({ system: system(facts()), tools: [def("read")] }), { tools: ["read"] }).ok, true);
    rejected(audit(payloadFor({ system: system(facts()), tools: [def("Read", { description: "read tool" })] }), { tools: ["read"] }), /tool definitions/);
    // A name outside the conversion list is unchanged, and an unrecorded change fails.
    assert.equal(audit(payloadFor({ system: system(oauth), tools: [def("mytool")] }), { tools: ["mytool"], facts: oauth }).ok, true);
    rejected(audit(payloadFor({ system: system(oauth), tools: [def("MyTool")] }), { tools: ["mytool"], facts: oauth }), /tool definitions/);
    // Call history uses the same rule: the recorded name is the configured one.
    const history = [U(), A(oauth, [CALL("t1", "read")]), R("t1")];
    const sent = (name: string) => payloadFor({ system: system(oauth), tools: [def("Read", { description: "read tool" })], messages: [user(), { role: "assistant", content: [{ type: "tool_use", id: "t1", name, input: {} }] }, { role: "user", content: [{ type: "tool_result", tool_use_id: "t1", content: "ok", is_error: false }] }] });
    assert.equal(audit(sent("Read"), { tools: ["read"], facts: oauth, history }).ok, true);
    rejected(audit(sent("read"), { tools: ["read"], facts: oauth, history }), /message 1 \(assistant\) differs from the recorded transcript/);
    assert.equal(toClaudeCodeName("bash"), "Bash");
    assert.equal(toClaudeCodeName("mytool"), "mytool");
  } },
  { name: "audit: converted-name collisions fail before launch", run() {
    const registry = registryFor(["read", "Read"]);
    assert.throws(() => deriveExpectation({ treatment: "", configuredTools: ["read", "Read"], facts: facts({ oauth: true }), registry }), /collide/);
    assert.doesNotThrow(() => deriveExpectation({ treatment: "", configuredTools: ["read", "Read"], facts: facts(), registry }));
    assert.throws(() => deriveExpectation({ treatment: "", configuredTools: ["read"], facts: facts(), registry: {} }), /no definition in the tool registry/);
  } },
  { name: "audit: only configured tools and the expected placeholder may be declared", run() {
    const ok = payloadFor({ system: sys("TREATMENT"), tools: [def("read")] });
    assert.equal(audit(ok, { tools: ["read"] }).ok, true);
    rejected(audit(payloadFor({ system: sys("TREATMENT"), tools: [def("read"), def("bash")] }), { tools: ["read"] }), /tool definitions/);
    rejected(audit(payloadFor({ system: sys("TREATMENT"), tools: [def("read", { surprise: true })] }), { tools: ["read"] }), /unrecorded tool definition field/);
  } },
  { name: "audit: native tool changes expect exactly one trailing, unaltered placeholder", run() {
    const f = facts({ compat: native });
    const good = payloadFor({ system: sys("TREATMENT"), tools: [def("read"), PLACEHOLDER] });
    assert.equal(audit(good, { tools: ["read"], facts: f }).ok, true);
    assert.equal(audit(good, { tools: ["read"], facts: f }).protocol.placeholderDeclared, true);
    rejected(audit(payloadFor({ system: sys("TREATMENT"), tools: [def("read")] }), { tools: ["read"], facts: f }), /tool definitions/);
    rejected(audit(payloadFor({ system: sys("TREATMENT"), tools: [def("read"), { ...PLACEHOLDER, description: "Callable." }] }), { tools: ["read"], facts: f }), /placeholder definition was altered/);
    rejected(audit(payloadFor({ system: sys("TREATMENT"), tools: [PLACEHOLDER, def("read")] }), { tools: ["read"], facts: f }), /tool definitions/);
    rejected(audit(payloadFor({ system: sys("TREATMENT"), tools: [def("read"), PLACEHOLDER, PLACEHOLDER] }), { tools: ["read"], facts: f }), /tool definitions/);
    // Without native tool changes, or with zero tools, no placeholder is expected.
    rejected(audit(good, { tools: ["read"] }), /tool definitions/);
    assert.equal(audit(payloadFor({ system: sys("TREATMENT") }), { tools: [], facts: f }).ok, true);
    rejected(audit(payloadFor({ system: sys("TREATMENT"), tools: [PLACEHOLDER] }), { tools: [], facts: f }), /tool definitions/);
    assert.equal(deriveExpectation({ treatment: "", configuredTools: [], facts: f }).placeholder, false);
  } },
  { name: "audit: a placeholder call and mid-conversation tool changes fail", run() {
    const f = facts({ compat: native });
    const history = [U(), A(f, [CALL("t", PLACEHOLDER_NAME)]), R("t")];
    const called = payloadFor({ system: sys("TREATMENT"), tools: [def("read"), PLACEHOLDER], messages: [user(), { role: "assistant", content: [{ type: "tool_use", id: "t", name: PLACEHOLDER_NAME, input: {} }] }, { role: "user", content: [{ type: "tool_result", tool_use_id: "t", content: "ok", is_error: false }] }] });
    rejected(audit(called, { tools: ["read"], facts: f, history }), /recorded call to unconfigured tool __pi_deferred_placeholder__/);
    for (const type of ["tool_addition", "tool_removal"]) {
      const changed = payloadFor({ system: sys("TREATMENT"), tools: [def("read"), PLACEHOLDER], messages: [user(), { role: "assistant", content: [{ type, tools: [def("x")] }] }] });
      rejected(audit(changed, { tools: ["read"], facts: f }), /mid-conversation tool change/);
    }
  } },
  { name: "audit: effort markers are derived from history and the resolved effort, never read from the payload", run() {
    const f = facts({ compat: { supportsMidConvoEffort: true }, provider: "fakeanth", activeEffort: "high" });
    const marker = (effort: string, extra: object = {}) => ({ role: "system", content: [], output_config: { effort }, ...extra });
    const assistantMsg = (text = "a") => ({ role: "assistant", content: [{ type: "text", text }] });
    const turn = (level?: string, over: object = {}) => A(f, [CALL("t1")], { level, ...over });
    const run = (payloadMessages: any[], history: any[], deriveFacts = f) =>
      audit(payloadFor({ system: sys("TREATMENT"), messages: payloadMessages }), { facts: deriveFacts, history });
    const differs = /message roles .* differ from the recorded history/;

    // First request: no history of assistant turns, one trailing marker carrying the active effort.
    const first = [user(), marker("high")];
    assert.equal(run(first, [U()]).ok, true, "control");
    assert.deepEqual(run(first, [U()]).protocol.effortMarkers, ["high"]);
    // Second request: a marker directly before the earlier assistant turn carrying its recorded level.
    const toolResult = { role: "user", content: [{ type: "tool_result", tool_use_id: "t1", content: "ok", is_error: false }] };
    const callTurn = { role: "assistant", content: [{ type: "tool_use", id: "t1", name: "read", input: {} }] };
    const history = [U(), turn("low"), R()];
    const second = [user(), marker("low"), callTurn, toolResult, marker("high")];
    const withTool = (messages: any[], over: object = {}) => audit(payloadFor({ system: sys("TREATMENT"), tools: [def("read")], messages }), { tools: ["read"], facts: f, history, ...over });
    assert.equal(withTool(second).ok, true, "control with a historical marker");
    assert.deepEqual(withTool(second).protocol.effortMarkers, ["low", "high"]);

    // Each altered expectation still leaves a plausible request but must fail.
    rejected(withTool([user(), marker("high"), callTurn, toolResult, marker("high")]), differs);   // historical level changed
    rejected(withTool([user(), callTurn, marker("low"), toolResult, marker("high")]), differs);    // marker moved after its turn
    rejected(withTool([user(), callTurn, toolResult, marker("high")]), differs);                    // historical marker missing
    rejected(withTool([user(), marker("low"), marker("low"), callTurn, toolResult, marker("high")]), differs); // extra marker
    rejected(withTool([user(), marker("low"), callTurn, toolResult, marker("low")]), differs);      // wrong active level
    rejected(withTool([user(), marker("low"), callTurn, toolResult]), differs);                     // trailing marker absent
    rejected(withTool([user(), marker("low"), callTurn, marker("high"), toolResult, marker("high")]), differs); // marker between call and result
    // The expected active level is itself derived: a wrong derived expectation rejects a plausible payload.
    rejected(run(first, [U()], facts({ compat: { supportsMidConvoEffort: true }, provider: "fakeanth", activeEffort: "max" })), differs);
    // History rules: an absent or invalid recorded level adds no marker; a turn another provider or API produced is not derived.
    for (const other of [turn(undefined), turn("bogus")]) {
      assert.equal(withTool([user(), callTurn, toolResult, marker("high")], { history: [U(), other, R()] }).ok, true);
    }
    for (const other of [turn("low", { provider: "someone-else" }), turn("low", { api: "openai-completions" })]) {
      rejected(withTool([user(), callTurn, toolResult, marker("high")], { history: [U(), other, R()] }), /another provider, API or model/);
    }
    // A turn with no convertible content is dropped, with no marker.
    assert.equal(run([user(), marker("high")], [U(), A(f, [], { level: "low" })]).ok, true);
    // Shape still fails: non-empty content and unknown levels.
    rejected(run([user(), marker("high", { content: [{ type: "text", text: "hidden" }] })], [U()]), /message 1 \(system:high\) differs from the recorded transcript/);
    rejected(run([user(), marker("bogus")], [U()]), differs);
    // Without history the markers cannot be derived, so the audit refuses rather than assumes.
    rejected(auditPayload(payloadFor({ system: sys("TREATMENT"), messages: first }), deriveExpectation({ treatment: "TREATMENT", configuredTools: [], facts: f }), ASSIGNMENT), /no recorded history to derive the request messages from/);
    // Roles derived from history are enforced: an extra assistant message no turn produced.
    rejected(run([user(), assistantMsg(), marker("high")], [U()]), differs);
    // No compatibility: no markers at all; thinking off: none either.
    rejected(audit(payloadFor({ system: sys("TREATMENT"), messages: first }), { history: [U()] }), differs);
    assert.equal(deriveExpectation({ treatment: "", configuredTools: [], facts: facts({ compat: { supportsMidConvoEffort: true }, thinkingOff: true }) }).effortMarkers, false);
  } },
  { name: "audit: the active effort follows the provider's mapping of the resolved thinking level", run() {
    assert.equal(deriveActiveEffort({}), "high");
    assert.equal(deriveActiveEffort({ level: "off", forceAdaptive: true }), "high");
    assert.equal(deriveActiveEffort({ level: "low", forceAdaptive: false }), "high", "budget-based thinking sends no effort option");
    for (const [level, effort] of [["minimal", "low"], ["low", "low"], ["medium", "medium"], ["high", "high"], ["xhigh", "high"]] as const) {
      assert.equal(deriveActiveEffort({ level, forceAdaptive: true }), effort, level);
    }
    assert.equal(deriveActiveEffort({ level: "xhigh", forceAdaptive: true, thinkingLevelMap: { xhigh: "max" } }), "max");
    assert.equal(deriveActiveEffort({ level: "medium", forceAdaptive: true, thinkingLevelMap: { medium: null } }), "medium");
  } },
  { name: "audit: an API without a derived expectation fails closed", run() {
    rejected(auditPayload(payloadFor(), deriveExpectation({ treatment: "", configuredTools: [], facts: facts({ api: "openai-chat-fantasy" }) }), ASSIGNMENT, [U()]), /no request audit exists for API openai-chat-fantasy/);
  } },
  { name: "audit: full recorded history is compared on all four APIs; known-bad content fails after a passing control", run() {
    for (const api of APIS) {
      const { payload, run } = exchange(api);
      const control = run();
      assert.deepEqual(control.violations, [], `${api} control`);
      for (const [name, mutate, pattern] of mutations(api)) {
        const bad = structuredClone(payload);
        mutate(bad);
        assert.notDeepEqual(bad, payload, `${api}: the mutation "${name}" applied`);
        const result = run(bad);
        assert.equal(result.ok, false, `${api}: ${name} must fail`);
        assert.ok(result.violations.some((v) => pattern.test(v)), `${api}: ${name}: ${result.violations.join(" | ")} !~ ${pattern}`);
      }
      // The recorded transcript is the oracle: a payload that matches a different transcript fails too.
      const other = run(payload, { history: [exchange(api).history[0], exchange(api).history[1], R(exchange(api).history[2].toolCallId, "different result")] });
      rejected(other, /differs from the recorded transcript/);
    }
  } },
  { name: "audit: schema derivation follows the registry, including strict-mode tools", run() {
    for (const api of APIS) {
      // Chat Completions sends the strict flag only when the resolved compatibility supports it.
      const { payload, run } = exchange(api, api === "openai-completions" ? { compat: { supportsStrictMode: true } } : {});
      // Registry change with an unchanged payload: the expectation comes from the registry.
      const changed = { read: { description: "Read a file", parameters: { ...SCHEMA, properties: { ...SCHEMA.properties, path: { type: "string", description: "Different" } } } } };
      rejected(run(payload, { registry: changed }), /parameter schema differs/);
      // Strict flag: the provider sends the strict derivation (all properties required, optional ones nullable, no additional properties).
      const strict = structuredClone(payload);
      const def0 = api === "anthropic-messages" ? strict.tools[0] : api === "openai-completions" ? strict.tools[0].function : strict.tools[0];
      const strictParams = { type: "object", properties: { path: SCHEMA.properties.path, mode: { anyOf: [SCHEMA.properties.mode, { type: "null" }] } }, required: ["path", "mode"], additionalProperties: false };
      def0.strict = true;
      if (api === "anthropic-messages") def0.input_schema = strictParams; else if (api === "openai-completions") def0.parameters = strictParams; else def0.parameters = strictParams;
      assert.deepEqual(run(strict).violations, [], `${api}: strict control`);
      const bad = structuredClone(strict);
      (api === "anthropic-messages" ? bad.tools[0].input_schema : api === "openai-completions" ? bad.tools[0].function.parameters : bad.tools[0].parameters).properties.path.description += " (altered)";
      rejected(run(bad), /parameter schema differs/);
      // Strict declared over the plain schema is not the strict derivation.
      const plain = structuredClone(payload);
      (api === "anthropic-messages" ? plain.tools[0] : api === "openai-completions" ? plain.tools[0].function : plain.tools[0]).strict = true;
      rejected(run(plain), /parameter schema differs/);
    }
    // A schema outside strict mode (Anthropic rejects `minimum`) has no strict derivation.
    const limited = { read: { description: "Read a file", parameters: { type: "object", properties: { n: { type: "number", minimum: 1 } }, required: ["n"] } } };
    const { payload, run } = exchange("anthropic-messages");
    const p = structuredClone(payload);
    p.tools[0].strict = true;
    p.tools[0].input_schema = limited.read.parameters;
    rejected(run(p, { registry: limited }), /no strict derivation/);
  } },
  { name: "audit: tool-result images, captions, bridges, omission text and placeholders are derived per API; tampering fails", run() {
    const aImg = (d: string, m: string) => ({ type: "image", source: { type: "base64", media_type: m, data: d } });
    const cImg = (d: string, m: string) => ({ type: "image_url", image_url: { url: `data:${m};base64,${d}` } });
    const rImg = (d: string, m: string) => ({ type: "input_image", detail: "auto", image_url: `data:${m};base64,${d}` });
    const IMG = { type: "image", data: "AAAA", mimeType: "image/png" };
    const IMG2 = { type: "image", data: "BBBB", mimeType: "image/jpeg" };
    const T = (text: string) => ({ type: "text", text });
    const SEE = "(see attached image)";
    const NONE = "(no tool output)";
    const OMIT = "(tool image omitted: model does not support images)";
    const BRIDGE = "Attached image(s) from tool result:";
    const ERR = "(image attached after the tool results)";
    // Hand-written expected encodings of each recorded result, one literal per API family.
    type Scenario = { name: string; content: any[]; isError?: boolean; vision: boolean; A: (id: string) => any[]; C: (id: string) => any[]; R: any };
    const scenarios: Scenario[] = [
      { name: "empty result", content: [], vision: true,
        A: (id) => [{ type: "tool_result", tool_use_id: id, content: "", is_error: false }],
        C: (id) => [{ role: "tool", content: NONE, tool_call_id: id }], R: NONE },
      { name: "image-only result", content: [IMG], vision: true,
        A: (id) => [{ type: "tool_result", tool_use_id: id, content: [T(SEE), aImg("AAAA", "image/png")], is_error: false }],
        C: (id) => [{ role: "tool", content: SEE, tool_call_id: id }, { role: "user", content: [T(BRIDGE), cImg("AAAA", "image/png")] }],
        R: [rImg("AAAA", "image/png")] },
      { name: "mixed text and two images", content: [T("caption"), IMG, IMG2], vision: true,
        A: (id) => [{ type: "tool_result", tool_use_id: id, content: [T("caption"), aImg("AAAA", "image/png"), aImg("BBBB", "image/jpeg")], is_error: false }],
        C: (id) => [{ role: "tool", content: "caption", tool_call_id: id }, { role: "user", content: [T(BRIDGE), cImg("AAAA", "image/png"), cImg("BBBB", "image/jpeg")] }],
        R: [{ type: "input_text", text: "caption" }, rImg("AAAA", "image/png"), rImg("BBBB", "image/jpeg")] },
      { name: "images around text", content: [IMG, T("mid"), IMG2], vision: true,
        A: (id) => [{ type: "tool_result", tool_use_id: id, content: [aImg("AAAA", "image/png"), T("mid"), aImg("BBBB", "image/jpeg")], is_error: false }],
        C: (id) => [{ role: "tool", content: "mid", tool_call_id: id }, { role: "user", content: [T(BRIDGE), cImg("AAAA", "image/png"), cImg("BBBB", "image/jpeg")] }],
        R: [{ type: "input_text", text: "mid" }, rImg("AAAA", "image/png"), rImg("BBBB", "image/jpeg")] },
      { name: "error result with an image", content: [T("boom"), IMG], isError: true, vision: true,
        A: (id) => [{ type: "tool_result", tool_use_id: id, content: `boom\n${ERR}`, is_error: true }, T(`Images from the error result of tool call ${id} (read):`), aImg("AAAA", "image/png")],
        C: (id) => [{ role: "tool", content: "boom", tool_call_id: id }, { role: "user", content: [T(BRIDGE), cImg("AAAA", "image/png")] }],
        R: [{ type: "input_text", text: "boom" }, rImg("AAAA", "image/png")] },
      { name: "no-vision model, text and image", content: [T("caption"), IMG], vision: false,
        A: (id) => [{ type: "tool_result", tool_use_id: id, content: `caption\n${OMIT}`, is_error: false }],
        C: (id) => [{ role: "tool", content: `caption\n${OMIT}`, tool_call_id: id }], R: `caption\n${OMIT}` },
      { name: "no-vision model, consecutive images collapse and surround text", content: [IMG, IMG2, T("mid"), IMG], vision: false,
        A: (id) => [{ type: "tool_result", tool_use_id: id, content: `${OMIT}\nmid\n${OMIT}`, is_error: false }],
        C: (id) => [{ role: "tool", content: `${OMIT}\nmid\n${OMIT}`, tool_call_id: id }], R: `${OMIT}\nmid\n${OMIT}` },
      { name: "no-vision model, image-only error result", content: [IMG], isError: true, vision: false,
        A: (id) => [{ type: "tool_result", tool_use_id: id, content: OMIT, is_error: true }],
        C: (id) => [{ role: "tool", content: OMIT, tool_call_id: id }], R: OMIT },
    ];
    const without = (x: any): any => (Array.isArray(x) ? x.filter((v) => !(v && typeof v === "object" && ["image", "image_url", "input_image"].includes(v.type))).map(without) : x && typeof x === "object" ? Object.fromEntries(Object.entries(x).map(([k, v]) => [k, without(v)])) : x);
    const swap = (p: any, from: string, to: string) => JSON.parse(JSON.stringify(p).replace(from, to));
    let mutationsApplied = 0;
    for (const api of APIS) {
      for (const s of scenarios) {
        const x = exchange(api, { inputImage: s.vision });
        const id: string = x.history[1].content[0].id;
        const history = [x.history[0], x.history[1], { ...x.history[2], content: s.content, isError: !!s.isError }];
        const payload = structuredClone(x.payload);
        const callId = id.split("|")[0];
        if (api === "anthropic-messages") payload.messages[2] = { role: "user", content: s.A(callId) };
        else if (api === "openai-completions") payload.messages.splice(3, 1, ...s.C(callId));
        else payload.input.splice(payload.input.findIndex((i: any) => i.type === "function_call_output"), 1, { type: "function_call_output", call_id: callId, output: s.R });
        const label = `${api}: ${s.name}`;
        assert.deepEqual(x.run(payload, { history }).violations, [], `${label}: control`);
        const bad: [string, any][] = [];
        // The same payload under the opposite image capability is not the derived one.
        const flipped = exchange(api, { inputImage: !s.vision });
        const flippedResult = flipped.run(payload, { history });
        if (s.content.some((b) => b.type === "image")) assert.equal(flippedResult.ok, false, `${label}: capability is derived from the model`);
        for (const [from, to] of [["AAAA", "AAAB"], ["BBBB", "BBBC"], ["image/png", "image/gif"], ["image/jpeg", "image/webp"], [SEE, `${SEE} (altered)`], [NONE, `${NONE} (altered)`], [OMIT, `${OMIT} (altered)`], [BRIDGE, `${BRIDGE} (altered)`], [ERR, `${ERR} (altered)`], ["Images from the error result", "Images from the failed result"], ["caption", "CAPTION"], ["mid", "MID"], ["boom", "bang"]] as const) {
          if (JSON.stringify(payload.messages ?? payload.input).includes(from)) bad.push([`${from} -> ${to}`, swap(payload, from, to)]);
        }
        const more = structuredClone(payload);
        if (s.content.some((b) => b.type === "image")) {
          if (api === "anthropic-messages") more.messages[2].content.push(aImg("CCCC", "image/png"));
          else if (api === "openai-completions") { const last = more.messages[more.messages.length - 1]; if (last.role === "user") last.content.push(cImg("CCCC", "image/png")); else more.messages.push({ role: "user", content: [T(BRIDGE), cImg("CCCC", "image/png")] }); }
          else { const o = more.input.find((i: any) => i.type === "function_call_output"); o.output = Array.isArray(o.output) ? [...o.output, rImg("CCCC", "image/png")] : [T("x"), rImg("CCCC", "image/png")]; }
          bad.push(["additional image", more]);
          if (s.vision) bad.push(["image removed", without(payload)]);
        }
        // An image or caption on a result that carries none, and a changed result text.
        const injected = structuredClone(payload);
        if (api === "anthropic-messages") injected.messages[2].content[0].content = "INJECTED";
        else if (api === "openai-completions") injected.messages[3].content = "INJECTED";
        else injected.input.find((i: any) => i.type === "function_call_output").output = "INJECTED";
        bad.push(["result replaced", injected]);
        for (const [name, mutated] of bad) {
          assert.notDeepEqual(mutated, payload, `${label}: mutation "${name}" applied`);
          mutationsApplied += 1;
          assert.equal(x.run(mutated, { history }).ok, false, `${label}: ${name} must fail`);
        }
      }
    }
    assert.ok(mutationsApplied > 100, `mutations applied: ${mutationsApplied}`);
  } },
  { name: "audit: parallel results share one Anthropic message and one Chat Completions bridge, and a result with unknown content fails", run() {
    const IMG = { type: "image", data: "AAAA", mimeType: "image/png" };
    const IMG2 = { type: "image", data: "BBBB", mimeType: "image/png" };
    const T = (text: string) => ({ type: "text", text });
    // Anthropic: error images follow all results of the turn, each with its caption.
    const fa = facts({ inputImage: true });
    const history = [U(), A(fa, [CALL("t1"), CALL("t2")]), R("t1", "x", { content: [T("e1"), IMG], isError: true }), R("t2", "y", { content: [T("e2"), IMG2], isError: true })];
    const aImg = (d: string) => ({ type: "image", source: { type: "base64", media_type: "image/png", data: d } });
    const sentA = payloadFor({ system: sys("TREATMENT"), tools: [def("read")], messages: [user(), { role: "assistant", content: [{ type: "tool_use", id: "t1", name: "read", input: {} }, { type: "tool_use", id: "t2", name: "read", input: {} }] },
      { role: "user", content: [{ type: "tool_result", tool_use_id: "t1", content: "e1\n(image attached after the tool results)", is_error: true }, { type: "tool_result", tool_use_id: "t2", content: "e2\n(image attached after the tool results)", is_error: true },
        T("Images from the error result of tool call t1 (read):"), aImg("AAAA"), T("Images from the error result of tool call t2 (read):"), aImg("BBBB")] }] });
    assert.deepEqual(audit(sentA, { tools: ["read"], facts: fa, history }).violations, [], "control");
    const reordered = structuredClone(sentA);
    const m = reordered.messages[2].content; reordered.messages[2].content = [m[0], T(m[2].text), m[3], m[1], m[4], m[5]];
    assert.equal(audit(reordered, { tools: ["read"], facts: fa, history }).ok, false);
    // Chat Completions: one bridge user message after the whole group.
    const fc = facts({ api: "openai-completions", provider: "fakeopenai", inputImage: true });
    const ch = [U(), A(fc, [CALL("t1"), CALL("t2")]), R("t1", "x", { content: [IMG] }), R("t2", "y", { content: [T("txt"), IMG2] })];
    const cImg = (d: string) => ({ type: "image_url", image_url: { url: `data:image/png;base64,${d}` } });
    const tail = [{ role: "tool", content: "(see attached image)", tool_call_id: "t1" }, { role: "tool", content: "txt", tool_call_id: "t2" }, { role: "user", content: [T("Attached image(s) from tool result:"), cImg("AAAA"), cImg("BBBB")] }];
    const sentC = (messages: any[]) => ({ model: "m", stream: true, tools: [{ type: "function", function: { name: "read", description: "read tool", parameters: { type: "object", properties: {} } } }], messages: [{ role: "system", content: "TREATMENT" }, { role: "user", content: [T(ASSIGNMENT)] },
      { role: "assistant", content: null, tool_calls: [{ id: "t1", type: "function", function: { name: "read", arguments: "{}" } }, { id: "t2", type: "function", function: { name: "read", arguments: "{}" } }] }, ...messages] });
    assert.deepEqual(audit(sentC(tail), { tools: ["read"], facts: fc, history: ch }).violations, [], "completions control");
    // A bridge after each result instead of one for the group is not the derived list.
    rejected(audit(sentC([tail[0], { role: "user", content: [T("Attached image(s) from tool result:"), cImg("AAAA")] }, tail[1], { role: "user", content: [T("Attached image(s) from tool result:"), cImg("BBBB")] }]), { tools: ["read"], facts: fc, history: ch }), /message roles/);
    // requiresAssistantAfterToolResult adds its bridging assistant message before the image bridge.
    const fb = facts({ api: "openai-completions", provider: "fakeopenai", inputImage: true, compat: { requiresAssistantAfterToolResult: true } });
    const sentB = sentC([tail[0], tail[1], { role: "assistant", content: "I have processed the tool results." }, tail[2]]);
    sentB.messages[2].content = "";
    assert.deepEqual(audit(sentB, { tools: ["read"], facts: fb, history: [ch[0], A(fb, [CALL("t1"), CALL("t2")]), ch[2], ch[3]] }).violations, []);
    // A block that is neither text nor image has no derived conversion.
    const odd = [U(), A(fc, [CALL("t1")]), R("t1", "x", { content: [{ type: "audio", data: "x" }] })];
    rejected(audit(sentC([{ role: "tool", content: "", tool_call_id: "t1" }]), { tools: ["read"], facts: fc, history: odd }), /neither text nor image/);
    // A user message with an image is not a measurement assignment.
    rejected(audit(payloadFor({ system: sys("TREATMENT") }), { history: [{ role: "user", content: [T(ASSIGNMENT), IMG] }] }), /non-text content/);
  } },
  { name: "audit: assistant thinking and text are encoded per API, never read from the payload", run() {
    const f = facts();
    const thought = A(f, [{ type: "thinking", thinking: "hmm", thinkingSignature: "sig" }, { type: "text", text: "answer" }]);
    const sent = (blocks: any[]) => payloadFor({ system: sys("TREATMENT"), messages: [user(), { role: "assistant", content: blocks }] });
    assert.equal(audit(sent([{ type: "thinking", thinking: "hmm", signature: "sig" }, { type: "text", text: "answer" }]), { history: [U(), thought] }).ok, true);
    rejected(audit(sent([{ type: "thinking", thinking: "INJECTED", signature: "sig" }, { type: "text", text: "answer" }]), { history: [U(), thought] }), /message 1 \(assistant\) differs/);
    rejected(audit(sent([{ type: "thinking", thinking: "hmm", signature: "sig" }, { type: "text", text: "answer" }, { type: "text", text: "INJECTED" }]), { history: [U(), thought] }), /message 1 \(assistant\) differs/);
    const redacted = A(f, [{ type: "thinking", thinking: "", redacted: true, thinkingSignature: "opaque" }, { type: "text", text: "x" }]);
    assert.equal(audit(sent([{ type: "redacted_thinking", data: "opaque" }, { type: "text", text: "x" }]), { history: [U(), redacted] }).ok, true);
    const unsigned = A(f, [{ type: "thinking", thinking: "plain" }]);
    assert.equal(audit(sent([{ type: "text", text: "plain" }]), { history: [U(), unsigned] }).ok, true, "unsigned thinking replays as text");
    // Chat Completions: signed reasoning travels in the reasoning field the signature names.
    const c = facts({ api: "openai-completions", provider: "fakeopenai" });
    const reasoned = A(c, [{ type: "thinking", thinking: "hmm", thinkingSignature: "reasoning_content" }, { type: "text", text: "answer" }]);
    const csent = (assistant: any) => ({ model: "m", stream: true, messages: [{ role: "system", content: "TREATMENT" }, { role: "user", content: [{ type: "text", text: ASSIGNMENT }] }, assistant] });
    assert.equal(audit(csent({ role: "assistant", content: "answer", reasoning_content: "hmm" }), { facts: c, history: [U(), reasoned] }).ok, true);
    rejected(audit(csent({ role: "assistant", content: "answer", reasoning_content: "INJECTED" }), { facts: c, history: [U(), reasoned] }), /message 1 \(assistant\) differs/);
    // A recorded detail list (OpenRouter) replays as `reasoning_details` instead of a raw field; a signature that is neither yields neither.
    const DETAILS = [{ type: "reasoning.text", text: "why", signature: null, format: "anthropic-claude-v1", index: 0 }];
    const detailed = A(c, [{ type: "thinking", thinking: "", thinkingSignature: JSON.stringify(DETAILS) }, { type: "text", text: "answer" }]);
    const cd = (over: object) => audit(csent({ role: "assistant", content: "answer", ...over }), { facts: c, history: [U(), detailed] });
    assert.deepEqual(cd({ reasoning_details: DETAILS }).violations, [], "reasoning_details replay control");
    rejected(cd({ reasoning_details: [{ ...DETAILS[0], text: "INJECTED" }] }), /message 1 \(assistant\) differs/);
    rejected(cd({ reasoning_details: [...DETAILS, { type: "reasoning.summary", summary: "INJECTED" }] }), /message 1 \(assistant\) differs/);
    rejected(cd({}), /message 1 \(assistant\) differs/);
    rejected(cd({ reasoning_details: DETAILS, reasoning: "why" }), /message 1 \(assistant\) differs/);
    const invalid = A(c, [{ type: "thinking", thinking: "hmm", thinkingSignature: "[{\"type\":\"reasoning.encrypted\"}]" }, { type: "text", text: "answer" }]);
    assert.deepEqual(audit(csent({ role: "assistant", content: "answer" }), { facts: c, history: [U(), invalid] }).violations, [], "an invalid detail list is not replayed");
    rejected(audit(csent({ role: "assistant", content: "answer", reasoning_details: [{ type: "reasoning.encrypted" }] }), { facts: c, history: [U(), invalid] }), /message 1 \(assistant\) differs/);
    // A legacy encrypted detail on a tool call, and opencode-go's reasoning field rename.
    const legacy = { type: "reasoning.encrypted", id: "r1", data: "opaque" };
    const calls = [{ id: "c1", type: "function", function: { name: "read", arguments: "{}" } }];
    const legacyTurn = A(c, [{ ...CALL("c1"), thoughtSignature: JSON.stringify(legacy) }]);
    const withRead = (assistant: any) => ({ ...csent(assistant), tools: [{ type: "function", function: { name: "read", description: "read tool", parameters: { type: "object", properties: {} } } }] });
    assert.deepEqual(audit(withRead({ role: "assistant", content: null, tool_calls: calls, reasoning_details: [legacy] }), { facts: c, tools: ["read"], history: [U(), legacyTurn] }).violations, [], "legacy encrypted detail control");
    rejected(audit(withRead({ role: "assistant", content: null, tool_calls: calls }), { facts: c, tools: ["read"], history: [U(), legacyTurn] }), /message 1 \(assistant\) differs/);
    const go = facts({ api: "openai-completions", provider: "opencode-go" });
    const goTurn = A(go, [{ type: "thinking", thinking: "hmm", thinkingSignature: "reasoning" }, { type: "text", text: "answer" }]);
    assert.deepEqual(audit(csent({ role: "assistant", content: "answer", reasoning_content: "hmm" }), { facts: go, history: [U(), goTurn] }).violations, []);
    rejected(audit(csent({ role: "assistant", content: "answer", reasoning: "hmm" }), { facts: go, history: [U(), goTurn] }), /message 1 \(assistant\) differs/);
    // Responses: a signed reasoning item replays as recorded, a message item carries its own id and phase.
    const r = facts({ api: "openai-responses", provider: "fakeopenai" });
    const item = { type: "reasoning", id: "rs_1", summary: [] };
    const answer = A(r, [{ type: "thinking", thinking: "", thinkingSignature: JSON.stringify(item) }, { type: "text", text: "answer", textSignature: JSON.stringify({ v: 1, id: "msg_9", phase: "final_answer" }) }]);
    const rsent = (items: any[]) => ({ model: "m", store: false, stream: true, input: [{ role: "system", content: "TREATMENT" }, { role: "user", content: [{ type: "input_text", text: ASSIGNMENT }] }, ...items] });
    const message = { type: "message", role: "assistant", content: [{ type: "output_text", text: "answer", annotations: [] }], status: "completed", id: "msg_9", phase: "final_answer" };
    assert.equal(audit(rsent([item, message]), { facts: r, history: [U(), answer] }).ok, true);
    rejected(audit(rsent([item, { ...message, id: "msg_other" }]), { facts: r, history: [U(), answer] }), /message 2 \(assistant\) differs/);
    rejected(audit(rsent([{ ...item, id: "rs_other" }, message]), { facts: r, history: [U(), answer] }), /message 1 \(reasoning\) differs/);
  } },
  { name: "audit: the instruction role is derived from the selected model, not accepted from the payload", run() {
    const asked = (api: string, over: any, role: string, o: any = {}) => {
      const f = facts({ api, provider: "fakeopenai", ...over });
      const payload = api === "openai-completions"
        ? { model: "m", stream: true, messages: [{ role, content: "TREATMENT" }, { role: "user", content: [{ type: "text", text: ASSIGNMENT }] }] }
        : { model: "m", store: false, stream: true, input: [{ role, content: "TREATMENT" }, { role: "user", content: [{ type: "input_text", text: ASSIGNMENT }] }] };
      return audit(payload, { facts: f, ...o });
    };
    for (const api of ["openai-completions", "openai-responses"]) {
      assert.deepEqual(asked(api, { reasoning: true }, "developer").violations, [], `${api}: a reasoning model receives developer`);
      rejected(asked(api, { reasoning: true }, "system"), /instruction role system differs from the developer role/);
      assert.deepEqual(asked(api, { reasoning: false }, "system").violations, [], `${api}: a non-reasoning model receives system`);
      rejected(asked(api, { reasoning: false }, "developer"), /instruction role developer differs from the system role/);
      assert.deepEqual(asked(api, { reasoning: true, compat: { supportsDeveloperRole: false } }, "system").violations, [], `${api}: supportsDeveloperRole false`);
      rejected(asked(api, { reasoning: true, compat: { supportsDeveloperRole: false } }, "developer"), /instruction role developer differs from the system role/);
    }
    // Chat Completions detects the default from provider and base URL.
    assert.deepEqual(asked("openai-completions", { reasoning: true, provider: "xai", baseUrl: "https://api.x.ai/v1" }, "system").violations, []);
    rejected(asked("openai-completions", { reasoning: true, provider: "xai", baseUrl: "https://api.x.ai/v1" }, "developer"), /instruction role developer differs from the system role/);
    assert.deepEqual(asked("openai-completions", { reasoning: true, provider: "xai", baseUrl: "https://api.x.ai/v1", compat: { supportsDeveloperRole: true } }, "developer").violations, []);
  } },
  { name: "audit: declared model fallbacks are derived from allowedFallbackModels; altered, added or dropped targets fail", run() {
    // Anthropic consumer rule (anthropic-messages.js buildParams): top-level `fallbacks` is the model's allowedFallbackModels reduced to {model}.
    const allowed = [{ provider: "fakeanth", model: "fallback-one", cost: { input: 1, output: 1, cacheRead: 0, cacheWrite: 0 } }, { provider: "fakeanth", model: "fallback-two" }];
    const f = facts({ compat: { allowedFallbackModels: allowed } });
    const withFallbacks = (fallbacks?: any) => payloadFor({ system: sys("TREATMENT"), ...(fallbacks === undefined ? {} : { fallbacks }) });
    const exp = deriveExpectation({ treatment: "TREATMENT", configuredTools: [], facts: f });
    assert.deepEqual(exp.fallbacks, [{ model: "fallback-one" }, { model: "fallback-two" }]);
    assert.deepEqual(exp.allowedFallbacks, [{ provider: "fakeanth", model: "fallback-one" }, { provider: "fakeanth", model: "fallback-two" }]);
    assert.ok(exp.adaptations.includes("model-fallbacks"));
    assert.deepEqual(audit(withFallbacks([{ model: "fallback-one" }, { model: "fallback-two" }]), { facts: f }).violations, [], "control");
    for (const [label, list] of [["target altered", [{ model: "fallback-one" }, { model: "other" }]], ["target added", [{ model: "fallback-one" }, { model: "fallback-two" }, { model: "extra" }]], ["target dropped", [{ model: "fallback-one" }]], ["order changed", [{ model: "fallback-two" }, { model: "fallback-one" }]], ["extra field", [{ model: "fallback-one", provider: "x" }, { model: "fallback-two" }]], ["list absent", undefined], ["empty list", []]] as const) {
      rejected(audit(withFallbacks(list as any), { facts: f }), /fallback list differs/);
    }
    // A model with no declared fallbacks (or an empty list) expects none.
    rejected(audit(withFallbacks([{ model: "fallback-one" }])), /unrecorded top-level request field: fallbacks/);
    rejected(audit(withFallbacks([{ model: "x" }]), { facts: facts({ compat: { allowedFallbackModels: [] } }) }), /unrecorded top-level request field: fallbacks/);
    assert.equal(deriveExpectation({ treatment: "", configuredTools: [], facts: facts({ compat: { allowedFallbackModels: [] } }) }).fallbacks, undefined);
    // Only the Anthropic consumer reads the setting: another API expects no fallbacks, so a field there fails.
    const c = facts({ api: "openai-completions", provider: "fakeopenai", compat: { allowedFallbackModels: allowed } });
    assert.equal(deriveExpectation({ treatment: "", configuredTools: [], facts: c }).fallbacks, undefined);
    rejected(audit({ model: "m", stream: true, fallbacks: [{ model: "fallback-one" }], messages: [{ role: "system", content: "TREATMENT" }, { role: "user", content: [{ type: "text", text: ASSIGNMENT }] }] }, { facts: c }), /unrecorded top-level request field: fallbacks/);
  } },
  { name: "audit: openai-completions expects its own channels and rejects every unrecorded one", run() {
    const f = facts({ api: "openai-completions", provider: "fakeopenai" });
    const tool = (name: string, extra: object = {}) => ({ type: "function", function: { name, description: `${name} tool`, parameters: { type: "object", properties: {} }, ...extra } });
    const userMsg = { role: "user", content: [{ type: "text", text: ASSIGNMENT }] };
    const sysMsg = { role: "system", content: "TREATMENT" };
    const call = (name = "read") => ({ role: "assistant", content: null, tool_calls: [{ id: "c1", type: "function", function: { name, arguments: "{}" } }] });
    const base = (over: any = {}) => ({ model: "m", stream: true, stream_options: { include_usage: true }, max_completion_tokens: 10, messages: [sysMsg, userMsg], ...over });
    const check = (payload: any, o: any = {}) => audit(payload, { facts: f, ...o });
    assert.equal(check(base()).ok, true, "control");
    assert.equal(check(base({ messages: [userMsg] }), { treatment: "" }).ok, true, "empty treatment sends no instruction message");
    rejected(check(base({ messages: [{ role: "system", content: "TREATMENT EXTRA" }, userMsg] })), /system context differs/);
    rejected(check(base({ messages: [{ role: "system", content: "SNEAKY" }, userMsg] }), { treatment: "" }), /unexpected system message/);
    rejected(check(base({ messages: [sysMsg] })), /message roles \[\] differ from the recorded history \[user\]/);
    rejected(check(base({ messages: [userMsg] })), /no instruction message/);
    rejected(check(base({ messages: [sysMsg, { role: "user", content: [{ type: "text", text: "wrapped " + ASSIGNMENT }] }] })), /message 0 \(user\) differs from the recorded transcript/);
    rejected(check(base({ messages: [sysMsg, userMsg, { role: "user", content: "Attached image(s) from tool result:" }] })), /message roles \[user, user\] differ/);
    rejected(check(base({ messages: [sysMsg, userMsg, { role: "developer", content: "mid-conversation update" }] })), /message roles \[user, developer\] differ/);
    rejected(check(base({ messages: [{ role: "system", content: "TREATMENT", tools: [] }, userMsg] })), /system context differs/);
    rejected(check(base({ messages: [sysMsg, userMsg, { role: "tool", content: "x", tool_call_id: "nope" }] })), /message roles \[user, tool\] differ/);
    rejected(check(base({ extra_field: 1 })), /unrecorded top-level request field: extra_field/);
    // Sampling keys are derived from the model's sampling parameters (literal cases: the top-level encoding test); unconfigured ones fail.
    rejected(check(base({ top_p: 0.5 })), /unrecorded top-level request field: top_p/);
    // tools
    const withTool = (tools: any[], messages = base().messages) => check(base({ tools, messages }), { tools: ["read"] });
    assert.equal(withTool([tool("read")]).ok, true);
    rejected(withTool([tool("read"), tool("bash")]), /tool definitions/);
    rejected(withTool([tool("write")]), /tool definitions/);
    rejected(withTool([{ type: "custom", function: { name: "read" } }]), /unrecorded tool definition type/);
    rejected(withTool([tool("read", { surprise: 1 })]), /unrecorded tool definition field/);
    rejected(check(base({ tools: [tool("read")] })), /tool definitions/);
    rejected(check(base({ tools: [tool("read", { description: "altered" })] }), { tools: ["read"] }), /description differs/);
    // history
    const history = [U(), A(f, [CALL("c1", "read")]), R("c1")];
    const out = { role: "tool", content: "ok", tool_call_id: "c1" };
    const hist = (name: string, extra: any[] = []) => base({ tools: [tool("read")], messages: [sysMsg, userMsg, call(name), out, ...extra] });
    assert.equal(check(hist("read"), { tools: ["read"], history }).ok, true, "tool history control");
    rejected(check(hist("bash"), { tools: ["read"], history }), /message 1 \(assistant\) differs from the recorded transcript/);
    rejected(check(hist("read", [{ role: "assistant", content: "extra" }]), { tools: ["read"], history }), /message roles .* differ from the recorded history/);
    // Compatibility flags the conversion branches on are derived from the model.
    const named = facts({ api: "openai-completions", provider: "fakeopenai", compat: { requiresToolResultName: true, requiresAssistantAfterToolResult: true } });
    const namedPayload = base({ tools: [tool("read")], messages: [sysMsg, userMsg, { ...call(), content: "" }, { ...out, name: "read" }] });
    assert.equal(check(namedPayload, { facts: named, tools: ["read"], history: [U(), A(named, [CALL("c1", "read")]), R("c1")] }).ok, true);
    rejected(check(hist("read"), { facts: named, tools: ["read"], history: [U(), A(named, [CALL("c1", "read")]), R("c1")] }), /message roles .* differ|differs from the recorded transcript/);
    // Anthropic-only flags never create adaptations for another API.
    const e = deriveExpectation({ treatment: "T", configuredTools: ["read"], facts: facts({ api: "openai-completions", oauth: true, compat: { ...native, supportsMidConvoEffort: true } }), registry: registryFor(["read"]) });
    assert.deepEqual([e.placeholder, e.effortMarkers, e.oauth, e.adaptations], [false, false, false, []]);
    rejected(check(base({ tools: [tool("read"), { type: "function", function: { name: PLACEHOLDER_NAME } }] }), { tools: ["read"] }), /tool definitions/);
  } },
  { name: "audit: openai-responses and codex expect their own channels and reject every unrecorded one", run() {
    const f = (api: string, over: any = {}) => facts({ api, provider: "fakeopenai", model: "fake-model", ...over });
    const user = { role: "user", content: [{ type: "input_text", text: ASSIGNMENT }] };
    const tool = (name: string, extra: object = {}) => ({ type: "function", name, description: `${name} tool`, parameters: { type: "object", properties: {} }, ...extra });
    const fcall = (name = "read") => ({ type: "function_call", call_id: "c1", name, arguments: "{}" });
    const out = { type: "function_call_output", call_id: "c1", output: "ok" };
    const responses = (input: any[], over: any = {}) => ({ model: "m", input, stream: true, store: false, ...over });
    const codex = (input: any[], over: any = {}) => ({ model: "m", store: false, stream: true, instructions: "TREATMENT", input, text: { verbosity: "low" }, include: ["reasoning.encrypted_content"], tool_choice: "auto", parallel_tool_calls: true, ...over });
    const R_ = (payload: any, o: any = {}) => audit(payload, { facts: f("openai-responses"), ...o });
    const C = (payload: any, o: any = {}) => audit(payload, { facts: f("openai-codex-responses"), ...o });
    const sys = { role: "system", content: "TREATMENT" };
    // responses
    assert.equal(R_(responses([sys, user])).ok, true, "responses control");
    assert.equal(R_(responses([user]), { treatment: "" }).ok, true);
    rejected(R_(responses([{ role: "system", content: "TREATMENT!" }, user])), /system context differs/);
    rejected(R_(responses([sys, user, { role: "developer", content: "late update" }])), /message roles \[user, developer\] differ/);
    rejected(R_(responses([sys, { role: "user", content: [{ type: "input_text", text: "wrapped" }] }])), /message 0 \(user\) differs from the recorded transcript/);
    rejected(R_(responses([sys, user, user])), /message roles \[user, user\] differ/);
    rejected(R_(responses([sys, user, { type: "additional_tools", role: "developer", tools: [] }])), /message roles \[user, developer\] differ/);
    rejected(R_(responses([sys, user, { type: "tool_search_call" }])), /message roles \[user, tool_search_call\] differ/);
    rejected(R_(responses([sys, user], { surprise: 1 })), /unrecorded top-level request field/);
    rejected(R_(responses([user])), /no instruction item/);
    const h = [U(), A(f("openai-responses"), [CALL("c1", "read")]), R("c1")];
    const withTool = (input: any[], o: any = {}) => R_(responses(input, { tools: [tool("read")] }), { tools: ["read"], history: h, ...o });
    assert.equal(withTool([sys, user, { ...fcall(), id: undefined }, out]).ok, true, "tool history control");
    rejected(withTool([sys, user, fcall("bash"), out]), /message 1 \(function_call\) differs from the recorded transcript/);
    rejected(withTool([sys, user, fcall(), { ...out, call_id: "zzz" }]), /message 2 \(function_call_output\) differs from the recorded transcript/);
    rejected(withTool([sys, user, { type: "custom_tool_call", call_id: "c1", name: "read", input: "" }, out]), /message roles .*custom_tool_call.* differ/);
    rejected(R_(responses([sys, user], { tools: [tool("read"), tool("extra")] }), { tools: ["read"], history: [U()] }), /tool definitions/);
    rejected(R_(responses([sys, user], { tools: [tool("read", { description: "altered" })] }), { tools: ["read"], history: [U()] }), /description differs/);
    rejected(R_(responses([sys, user], { tools: [{ type: "custom", name: "read" }] }), { tools: ["read"] }), /unrecorded tool definition type/);
    rejected(withTool([sys, user, out, fcall()]), /message roles .* differ from the recorded history/);
    // configuration updates: derived from history, resolved level and the level map
    const cf = f("openai-responses", { compat: { supportsMidConvoEffort: true }, level: "low", thinkingLevelMap: { low: "minimal" } });
    const cu = (effort: string) => ({ type: "configuration_update", reasoning: { effort } });
    const hh = [U(), A(cf, [CALL("c1", "read")], { level: "minimal" }), R("c1")];
    const noId = { ...fcall(), id: undefined };
    const upd = (input: any[], history = hh) => R_(responses(input, { tools: [tool("read")] }), { facts: cf, tools: ["read"], history });
    const differs = /message roles .* differ from the recorded history/;
    assert.equal(upd([sys, user, cu("minimal"), noId, out, cu("minimal")]).ok, true, "configuration-update control");
    assert.deepEqual(upd([sys, user, cu("minimal"), noId, out, cu("minimal")]).protocol.effortMarkers, ["minimal", "minimal"]);
    rejected(upd([sys, user, cu("high"), noId, out, cu("minimal")]), differs);      // historical level altered
    rejected(upd([sys, user, noId, out, cu("minimal")]), differs);                  // historical update missing
    rejected(upd([sys, user, noId, cu("minimal"), out, cu("minimal")]), differs);   // update moved after its turn
    rejected(upd([sys, user, cu("minimal"), cu("minimal"), noId, out, cu("minimal")]), differs); // extra
    rejected(upd([sys, user, cu("minimal"), noId, out, cu("medium")]), differs);    // wrong active level
    rejected(upd([sys, user, cu("minimal"), noId, out]), differs);                  // trailing update absent
    rejected(upd([sys, user, cu("minimal"), noId, out, { ...cu("minimal"), extra: 1 }]), /message 4 \(configuration_update:minimal\) differs/);
    rejected(auditPayload(responses([sys, user, cu("minimal")]), deriveExpectation({ treatment: "TREATMENT", configuredTools: [], facts: cf }), ASSIGNMENT), /no recorded history/);
    rejected(R_(responses([sys, user, cu("minimal")]), { history: [U()] }), differs);
    assert.equal(deriveExpectation({ treatment: "", configuredTools: [], facts: f("openai-responses", { compat: { supportsMidConvoEffort: true }, level: "low", chatGptSignIn: true }) }).configUpdateEffort, undefined, "ChatGPT sign-in sends no updates");
    assert.equal(deriveExpectation({ treatment: "", configuredTools: [], facts: f("openai-responses", { compat: { supportsMidConvoEffort: true } }) }).configUpdateEffort, undefined, "no resolved level, no updates");
    // codex: the treatment is `instructions`; no system item; the fixed default when empty
    assert.equal(C(codex([user])).ok, true, "codex control");
    assert.equal(C(codex([user], { instructions: "You are a helpful assistant." }), { treatment: "" }).ok, true, "default instructions for an empty treatment");
    rejected(C(codex([user], { instructions: "TREATMENT plus" })), /instructions differ/);
    rejected(C(codex([user], { instructions: "other" }), { treatment: "" }), /instructions differ/);
    rejected(C(codex([{ role: "developer", content: "TREATMENT" }, user])), /unexpected developer item/);
    rejected(C(codex([user, cu("high")])), /message roles \[user, configuration_update:high\] differ/);
    rejected(C(codex([{ role: "user", content: [{ type: "input_text", text: "wrapped" }] }])), /message 0 \(user\) differs from the recorded transcript/);
    rejected(C(codex([user], { tools: [tool("read"), tool("bash")] }), { tools: ["read"] }), /tool definitions/);
    rejected(C(codex([user], { surprise: 1 })), /unrecorded top-level request field/);
    assert.deepEqual(deriveExpectation({ treatment: "", configuredTools: [], facts: f("openai-codex-responses") }).adaptations, ["codex-default-instructions"]);
    assert.deepEqual(deriveExpectation({ treatment: "T", configuredTools: [], facts: f("openai-codex-responses") }).adaptations, []);
  } },

  { name: "audit: top-level encoding is derived per API from the model, resolved level and runtime inputs; changed, added or missing values fail", run() {
    // Literal expected encodings, written from the installed consumers (anthropic-messages.js
    // streamSimple/getBetaFeatures/buildParams, openai-completions.js buildParams,
    // openai-responses.js buildParams, openai-codex-responses.js buildRequestBody); the payload is
    // never filled from the derivation here.
    const context = (api: string, role = "system") => ({
      "anthropic-messages": { system: sys("TREATMENT"), messages: [user()] },
      "openai-completions": { messages: [{ role, content: "TREATMENT" }, { role: "user", content: [{ type: "text", text: ASSIGNMENT }] }] },
      "openai-responses": { input: [{ role, content: "TREATMENT" }, { role: "user", content: [{ type: "input_text", text: ASSIGNMENT }] }] },
      "openai-codex-responses": { instructions: "TREATMENT", input: [{ role: "user", content: [{ type: "input_text", text: ASSIGNMENT }] }] },
    } as Record<string, object>)[api];
    const direct = (f: any, top: object, role?: string) => auditPayload({ ...context(f.api, role), ...top }, deriveExpectation({ treatment: "TREATMENT", configuredTools: [], facts: f }), ASSIGNMENT, [U()]);
    const bad = /top-level .* differs from the encoding|unrecorded top-level request field|top-level .* is missing|output cap .* outside the range/;
    type Case = { name: string; f: any; top: any; role?: string; mutants: [string, (t: any) => void][] };
    const ANT = { model: "fake-model", max_tokens: 64000, stream: true };
    const cases: Case[] = [
      { name: "anthropic, no reasoning", f: facts({ maxTokens: 64000 }), top: ANT, mutants: [
        ["model changed", (t) => { t.model = "other-model"; }], ["cap above the model's", (t) => { t.max_tokens = 64001; }], ["cap zero", (t) => { t.max_tokens = 0; }],
        ["stream off", (t) => { t.stream = false; }], ["temperature added", (t) => { t.temperature = 0.5; }], ["metadata added", (t) => { t.metadata = { user_id: "INJECTED" }; }],
        ["tool choice added", (t) => { t.tool_choice = { type: "any" }; }], ["thinking added", (t) => { t.thinking = { type: "enabled", budget_tokens: 1024, display: "summarized" }; }],
        ["output_config added", (t) => { t.output_config = { effort: "max", format: { type: "json_schema", schema: { description: "INJECTED" } } }; }], ["betas added", (t) => { t.betas = ["x"]; }]] },
      { name: "anthropic, budget thinking", f: facts({ reasoning: true, level: "medium", maxTokens: 64000 }),
        top: { ...ANT, betas: ["interleaved-thinking-2025-05-14"], thinking: { type: "enabled", budget_tokens: 8192, display: "summarized" } }, mutants: [
        ["budget changed", (t) => { t.thinking.budget_tokens = 31999; }], ["display changed", (t) => { t.thinking.display = "omitted"; }], ["type changed", (t) => { t.thinking = { type: "adaptive", display: "summarized" }; }],
        ["nested field added", (t) => { t.thinking.injected = "INJECTED"; }], ["output_config added", (t) => { t.output_config = { effort: "max" }; }], ["beta dropped", (t) => { delete t.betas; }], ["beta added", (t) => { t.betas.push("x"); }]] },
      { name: "anthropic, configured thinking budgets", f: facts({ reasoning: true, level: "medium", maxTokens: 64000, thinkingBudgets: { medium: 4000 } }),
        top: { ...ANT, betas: ["interleaved-thinking-2025-05-14"], thinking: { type: "enabled", budget_tokens: 4000, display: "summarized" } }, mutants: [["default budget sent", (t) => { t.thinking.budget_tokens = 8192; }]] },
      { name: "anthropic, context-clamped cap", f: facts({ reasoning: true, level: "medium", maxTokens: 64000 }),
        top: { ...ANT, max_tokens: 5000, betas: ["interleaved-thinking-2025-05-14"], thinking: { type: "enabled", budget_tokens: 3976, display: "summarized" } }, mutants: [["budget not clamped to the cap", (t) => { t.thinking.budget_tokens = 8192; }]] },
      { name: "anthropic, model cap below the budget", f: facts({ reasoning: true, level: "high", maxTokens: 8000 }),
        top: { ...ANT, max_tokens: 8000, betas: ["interleaved-thinking-2025-05-14"], thinking: { type: "enabled", budget_tokens: 6976, display: "summarized" } }, mutants: [["unadjusted budget", (t) => { t.thinking.budget_tokens = 16384; }]] },
      { name: "anthropic, adaptive thinking", f: facts({ reasoning: true, level: "low", maxTokens: 64000, compat: { forceAdaptiveThinking: true } }),
        top: { ...ANT, thinking: { type: "adaptive", display: "summarized" }, output_config: { effort: "low" } }, mutants: [
        ["effort changed", (t) => { t.output_config.effort = "max"; }], ["format added", (t) => { t.output_config.format = { type: "json_schema", schema: { type: "object", description: "INJECTED-FORMAT-TEXT" } }; }], ["interleaved beta added", (t) => { t.betas = ["interleaved-thinking-2025-05-14"]; }]] },
      { name: "anthropic, reasoning model with thinking off", f: facts({ reasoning: true, maxTokens: 64000 }), top: { ...ANT, thinking: { type: "disabled" } }, mutants: [
        ["thinking dropped", (t) => { delete t.thinking; }], ["thinking enabled", (t) => { t.thinking = { type: "enabled", budget_tokens: 1024, display: "summarized" }; }]] },
      { name: "anthropic, configured beta header", f: facts({ reasoning: true, level: "low", maxTokens: 64000, betaHeader: "custom-beta, other-beta,custom-beta" }),
        top: { ...ANT, betas: ["custom-beta", "other-beta"], thinking: { type: "enabled", budget_tokens: 2048, display: "summarized" } }, mutants: [["computed list sent instead", (t) => { t.betas = ["interleaved-thinking-2025-05-14"]; }]] },
    ];
    const COMP = { model: "fake-model", stream: true, stream_options: { include_usage: true }, store: false, max_completion_tokens: 8000 };
    const cf = (over: any = {}) => facts({ api: "openai-completions", provider: "fakeopenai", baseUrl: "http://127.0.0.1:1", maxTokens: 8000, ...over });
    cases.push(
      { name: "completions, no reasoning", f: cf(), top: COMP, mutants: [
        ["response format added", (t) => { t.response_format = { type: "json_schema", json_schema: { name: "x", schema: { type: "object", description: "INJECTED" } } }; }], ["tool choice added", (t) => { t.tool_choice = "required"; }],
        ["temperature added", (t) => { t.temperature = 0; }], ["nested field added", (t) => { t.stream_options.injected = "INJECTED"; }], ["store changed", (t) => { t.store = true; }],
        ["effort added", (t) => { t.reasoning_effort = "low"; }], ["template added", (t) => { t.chat_template_kwargs = { extra: "INJECTED-TEMPLATE-TEXT" }; }], ["cap field changed", (t) => { delete t.max_completion_tokens; t.max_tokens = 8000; }]] },
      { name: "completions, reasoning effort", f: cf({ reasoning: true, level: "low" }), role: "developer", top: { ...COMP, reasoning_effort: "low" }, mutants: [["effort changed", (t) => { t.reasoning_effort = "high"; }], ["effort dropped", (t) => { delete t.reasoning_effort; }]] },
      { name: "completions, mapped effort", f: cf({ reasoning: true, level: "low", thinkingLevelMap: { low: "minimal" } }), role: "developer", top: { ...COMP, reasoning_effort: "minimal" }, mutants: [["unmapped level sent", (t) => { t.reasoning_effort = "low"; }]] },
      { name: "completions, chat-template values", role: "developer",
        f: cf({ reasoning: true, level: "low", compat: { thinkingFormat: "chat-template", chatTemplateKwargs: { enable_thinking: { $var: "thinking.enabled" }, budget: { $var: "thinking.budget" }, effort: {}, fixed: "keep", off_only: { omitWhenOff: true } } } }),
        top: { ...COMP, chat_template_kwargs: { enable_thinking: true, budget: 2048, effort: "low", fixed: "keep", off_only: "low" } }, mutants: [
        ["template text changed", (t) => { t.chat_template_kwargs.fixed = "INJECTED-TEMPLATE-TEXT"; }], ["template key added", (t) => { t.chat_template_kwargs.extra = "x"; }], ["budget changed", (t) => { t.chat_template_kwargs.budget = 4096; }], ["effort field added", (t) => { t.reasoning_effort = "low"; }]] },
      { name: "completions, thinking budget field", f: cf({ reasoning: true, level: "high", compat: { supportsThinkingTokenBudget: true } }), role: "developer",
        top: { ...COMP, reasoning_effort: "high", thinking_token_budget: 6976 }, mutants: [["budget unclamped", (t) => { t.thinking_token_budget = 16384; }]] },
      { name: "completions, model sampling parameters", f: cf({ samplingParams: { top_p: 0.5, temperature: 0.2 } }), top: { ...COMP, top_p: 0.5, temperature: 0.2 }, mutants: [
        ["sampling value changed", (t) => { t.top_p = 0.9; }], ["sampling key dropped", (t) => { delete t.temperature; }]] },
      { name: "completions, max_tokens field and OpenAI prompt cache", f: cf({ baseUrl: "https://api.openai.com/v1", sessionId: "s".repeat(70), cacheRetention: "long", compat: { maxTokensField: "max_tokens" } }),
        top: { model: "fake-model", stream: true, stream_options: { include_usage: true }, store: false, max_tokens: 8000, prompt_cache_key: "s".repeat(64), prompt_cache_retention: "24h" }, mutants: [
        ["cache key from another session", (t) => { t.prompt_cache_key = "other"; }], ["retention dropped", (t) => { delete t.prompt_cache_retention; }]] },
    );
    const RESP = { model: "fake-model", stream: true, store: false, prompt_cache_key: "sess-1", max_output_tokens: 8000 };
    const rf = (over: any = {}) => facts({ api: "openai-responses", provider: "fakeopenai", maxTokens: 8000, sessionId: "sess-1", ...over });
    cases.push(
      { name: "responses, no reasoning", f: rf(), top: RESP, mutants: [
        ["text format added", (t) => { t.text = { format: { type: "json_schema", name: "x", description: "INJECTED-FORMAT-TEXT", schema: { type: "object" } } }; }], ["include added", (t) => { t.include = ["reasoning.encrypted_content"]; }],
        ["tool choice added", (t) => { t.tool_choice = "required"; }], ["cap above the model's", (t) => { t.max_output_tokens = 8001; }], ["cap below the floor", (t) => { t.max_output_tokens = 15; }], ["service tier added", (t) => { t.service_tier = "flex"; }], ["cache key dropped", (t) => { delete t.prompt_cache_key; }]] },
      { name: "responses, reasoning", f: rf({ reasoning: true, level: "low" }), role: "developer", top: { ...RESP, reasoning: { effort: "low", summary: "auto" }, include: ["reasoning.encrypted_content"] }, mutants: [
        ["effort changed", (t) => { t.reasoning.effort = "xhigh"; }], ["summary changed", (t) => { t.reasoning.summary = "detailed"; }], ["include added", (t) => { t.include.push("file_search_call.results"); }]] },
      { name: "responses, reasoning off", f: rf({ reasoning: true }), role: "developer", top: { ...RESP, reasoning: { effort: "none" } }, mutants: [["effort enabled", (t) => { t.reasoning = { effort: "low", summary: "auto" }; }]] },
      { name: "responses, ChatGPT sign-in omits the cap", f: rf({ chatGptSignIn: true }), top: { model: "fake-model", stream: true, store: false, prompt_cache_key: "sess-1" }, mutants: [["cap sent", (t) => { t.max_output_tokens = 8000; }]] },
    );
    const CODEX = { model: "fake-model", store: false, stream: true, text: { verbosity: "low" }, include: ["reasoning.encrypted_content"], prompt_cache_key: "sess-1", tool_choice: "auto", parallel_tool_calls: true };
    const xf = (over: any = {}) => facts({ api: "openai-codex-responses", provider: "fakeopenai", maxTokens: 8000, sessionId: "sess-1", ...over });
    cases.push(
      { name: "codex, no reasoning", f: xf(), top: CODEX, mutants: [
        ["text format added", (t) => { t.text.format = { type: "json_schema", name: "x", description: "INJECTED-FORMAT-TEXT", schema: { type: "object" } }; }], ["verbosity changed", (t) => { t.text.verbosity = "high"; }],
        ["tool choice changed", (t) => { t.tool_choice = "required"; }], ["parallel calls off", (t) => { t.parallel_tool_calls = false; }], ["include added", (t) => { t.include.push("x"); }],
        ["reasoning added", (t) => { t.reasoning = { effort: "high", summary: "auto" }; }], ["cap added", (t) => { t.max_output_tokens = 8000; }]] },
      { name: "codex, mapped reasoning", f: xf({ reasoning: true, level: "high", thinkingLevelMap: { high: "xhigh" } }), top: { ...CODEX, reasoning: { effort: "xhigh", summary: "auto" } }, mutants: [["unmapped effort", (t) => { t.reasoning.effort = "high"; }]] },
      { name: "codex, reasoning off", f: xf({ reasoning: true }), top: { ...CODEX, reasoning: { effort: "none" } }, mutants: [["reasoning dropped", (t) => { delete t.reasoning; }]] },
    );
    let applied = 0;
    for (const c of cases) {
      assert.deepEqual(direct(c.f, c.top, c.role).violations, [], `${c.name}: control`);
      for (const [label, mutate] of c.mutants) {
        const top = structuredClone(c.top);
        mutate(top);
        assert.notDeepEqual(top, c.top, `${c.name}: ${label} applied`);
        rejected(direct(c.f, top, c.role), bad);
        applied += 1;
      }
    }
    assert.ok(applied >= 60, `mutations applied: ${applied}`);
  } },
  { name: "audit: Chat Completions Anthropic cache_control is derived from the resolved compatibility; misplaced, reshaped or added markers fail", run() {
    // Built-in OpenRouter Anthropic models: detectCompat sets cacheControlFormat "anthropic" for provider openrouter and an anthropic/ id.
    const f = facts({ api: "openai-completions", provider: "openrouter", baseUrl: "https://openrouter.ai/api/v1", model: "anthropic/claude-x", maxTokens: 8000 });
    const CC = { type: "ephemeral" };
    const T = (text: string, marked = false) => ({ type: "text", text, ...(marked ? { cache_control: CC } : {}) });
    const tool = (name: string, marked = false) => ({ type: "function", function: { name, description: `${name} tool`, parameters: { type: "object", properties: {} } }, ...(marked ? { cache_control: CC } : {}) });
    const top = { model: "anthropic/claude-x", stream: true, stream_options: { include_usage: true }, store: false, max_completion_tokens: 8000 };
    const history = [U(), A(f, [CALL("c1", "read")]), R("c1", "ok")];
    const call = { role: "assistant", content: null, tool_calls: [{ id: "c1", type: "function", function: { name: "read", arguments: "{}" } }] };
    const payload = { ...top, tools: [tool("read"), tool("ls", true)], messages: [{ role: "system", content: [T("TREATMENT", true)] }, { role: "user", content: [T(ASSIGNMENT)] }, call, { role: "tool", content: [T("ok", true)], tool_call_id: "c1" }] };
    const run = (p: any, over: { facts?: any; history?: any[] } = {}) => auditPayload(p, deriveExpectation({ treatment: "TREATMENT", configuredTools: ["read", "ls"], facts: over.facts ?? f, registry: registryFor(["read", "ls"]) }), ASSIGNMENT, over.history ?? history);
    assert.deepEqual(run(payload).violations, [], "control");
    assert.deepEqual(run(payload).protocol.cacheControl, CC);
    // First request: the assignment is the last conversation message, so it carries the marker.
    const first = { ...top, tools: payload.tools, messages: [payload.messages[0], { role: "user", content: [T(ASSIGNMENT, true)] }] };
    assert.deepEqual(run(first, { history: [U()] }).violations, [], "first-request control");
    rejected(run({ ...first, messages: [payload.messages[0], { role: "user", content: [T(ASSIGNMENT)] }] }, { history: [U()] }), /message 0 \(user\) differs/);
    // Long retention adds the 1h ttl the consumer derives.
    const long = structuredClone(payload);
    for (const part of [long.messages[0].content[0], long.messages[3].content[0], long.tools[1]] as any[]) part.cache_control = { type: "ephemeral", ttl: "1h" };
    long.prompt_cache_retention = "24h";
    assert.deepEqual(run(long, { facts: { ...f, cacheRetention: "long" } }).violations, [], "long-retention control");
    rejected(run(payload, { facts: { ...f, cacheRetention: "long" } }), /cache_control differs|system context differs|differs from the recorded transcript/);
    const mutants: [string, (p: any) => void, RegExp][] = [
      ["instruction marker stripped", (p) => { p.messages[0].content = "TREATMENT"; }, /system context differs/],
      ["instruction text changed inside the marked part", (p) => { p.messages[0].content[0].text += " INJECTED"; }, /system context differs/],
      ["instruction part added", (p) => { p.messages[0].content.unshift(T("INJECTED")); }, /system context differs/],
      ["marker field added", (p) => { p.messages[0].content[0].cache_control = { type: "ephemeral", note: "INJECTED" }; }, /system context differs/],
      ["tool marker moved to the first tool", (p) => { p.tools[0].cache_control = CC; delete p.tools[1].cache_control; }, /unrecorded tool definition field: cache_control|cache_control differs/],
      ["tool marker reshaped", (p) => { p.tools[1].cache_control = { type: "ephemeral", ttl: "1h" }; }, /cache_control differs/],
      ["tool marker dropped", (p) => { delete p.tools[1].cache_control; }, /cache_control differs/],
      ["conversation marker moved to the user message", (p) => { p.messages[3].content = "ok"; p.messages[1].content[0].cache_control = CC; }, /differs from the recorded transcript/],
      ["extra conversation marker", (p) => { p.messages[1].content[0].cache_control = CC; }, /message 0 \(user\) differs/],
      ["tool result text changed inside the marked part", (p) => { p.messages[3].content[0].text = "INJECTED"; }, /message 2 \(tool\) differs/],
      ["tool object sibling key", (p) => { p.tools[0].injected = "INJECTED"; }, /unrecorded tool definition field: injected/],
    ];
    for (const [label, mutate, pattern] of mutants) {
      const p = structuredClone(payload);
      mutate(p);
      assert.notDeepEqual(p, payload, `${label} applied`);
      rejected(run(p), pattern);
    }
    // A model without the format expects no marker anywhere.
    const plain = facts({ api: "openai-completions", provider: "openrouter", baseUrl: "https://openrouter.ai/api/v1", model: "openai/gpt-x", maxTokens: 8000 });
    const unmarked = { ...top, model: "openai/gpt-x", tools: [tool("read"), tool("ls")], messages: [{ role: "system", content: "TREATMENT" }, { role: "user", content: [T(ASSIGNMENT)] }, call, { role: "tool", content: "ok", tool_call_id: "c1" }] };
    assert.deepEqual(run(unmarked, { facts: plain, history: [U(), A(plain, [CALL("c1", "read")]), R("c1", "ok")] }).violations, [], "no-format control");
    rejected(run({ ...payload, model: "openai/gpt-x" }, { facts: plain, history: [U(), A(plain, [CALL("c1", "read")]), R("c1", "ok")] }), /unrecorded tool definition field: cache_control/);
  } },
  { name: "audit: Chat Completions tool objects carry only the derived keys, and the strict flag only where the resolved compatibility sends it", run() {
    const f = (compat: object = {}) => facts({ api: "openai-completions", provider: "fakeopenai", compat });
    const payload = (fn: object = {}, obj: object = {}) => ({ model: "fake-model", stream: true, stream_options: { include_usage: true }, store: false, max_completion_tokens: 100,
      tools: [{ type: "function", function: { name: "read", description: "read tool", parameters: { type: "object", properties: {} }, ...fn }, ...obj }], messages: [{ role: "system", content: "TREATMENT" }, { role: "user", content: [{ type: "text", text: ASSIGNMENT }] }] });
    const run = (p: any, compat?: object) => auditPayload(p, deriveExpectation({ treatment: "TREATMENT", configuredTools: ["read"], facts: f(compat), registry: registryFor(["read"]) }), ASSIGNMENT, [U()]);
    assert.deepEqual(run(payload()).violations, [], "control without strict support");
    assert.deepEqual(run(payload({ strict: false }), { supportsStrictMode: true }).violations, [], "control with strict support");
    rejected(run(payload({}, { injected: "INJECTED" })), /unrecorded tool definition field: injected/);
    rejected(run(payload({}, { cache_control: { type: "ephemeral" } })), /unrecorded tool definition field: cache_control/);
    rejected(run(payload({}, { description: "INJECTED" })), /unrecorded tool definition field: description/);
    rejected(run(payload({ strict: false })), /unrecorded tool definition field: strict/);
    rejected(run(payload(), { supportsStrictMode: true }), /lacks the strict flag/);
    rejected(run(payload({ strict: "yes" }), { supportsStrictMode: true }), /lacks the strict flag/);
    rejected(run(payload({ injected: "INJECTED" })), /unrecorded tool definition field: injected/);
  } },

  // ---- end to end through the launch helper and installed provider code ----------------
  { name: "e2e: the subject sends the exact compiled treatment, assignment and tools", async run() {
    const run = await runSubject({ system: "SYSTEM TEXT", skills: ["SKILL ONE", "SKILL TWO"], tools: ["read"], discovered: true });
    assert.equal(run.code, 0, run.stderr + run.stdout);
    assert.equal(run.requests.length, 1);
    const request = run.requests[0];
    assert.deepEqual(request.system.map((b: any) => b.text), ["SYSTEM TEXT\n\nSKILL ONE\n\nSKILL TWO"]);
    assert.deepEqual(request.tools.map((t: any) => t.name), ["read"]);
    assert.equal(request.messages[0].content[0].text, ASSIGNMENT);
    assert.equal(run.adapter("terminal.json").success, true);
    assert.equal(run.adapter("audit-1.json").ok, true);
    assert.equal(run.adapter("expectation.json").adaptations.length, 0);
    // Discovered home/project context, skills, templates and extensions never entered it.
    for (const leaked of ["DISCOVERED-PROJECT-CONTEXT", "DISCOVERED-HOME-CONTEXT", "DISCOVERED-SKILL", "DISCOVERED-TEMPLATE", "DISCOVERED-EXTENSION", "DISCOVERED-PROJECT-SKILL", "cwd", "Current working directory"]) {
      assert.equal(requestText(run).includes(leaked), false, leaked);
    }
  } },
  { name: "e2e: assignment transport preserves literal leading slashes, at-signs, hyphens and template text", async run() {
    for (const assignment of ["/not-a-command with args", "@not-a-file.txt please", "-not-an-option --flag value", "{{template}} $ARGUMENTS $1 ${HOME}"]) {
      const run = await runSubject({ assignment });
      assert.equal(run.code, 0, `${assignment}: ${run.stderr}`);
      assert.equal(run.requests[0].messages[0].content[0].text, assignment);
      assert.equal(run.argv.some((a) => a.includes(assignment)), false, "assignment text never reaches argv");
      assert.equal(run.adapter("terminal.json").success, true);
    }
  } },
  { name: "e2e: an empty tool selection enables zero tools and expects no placeholder", async run() {
    const run = await runSubject({ tools: [], compat: native });
    assert.equal(run.code, 0, run.stderr);
    assert.equal(run.requests[0].tools === undefined || run.requests[0].tools.length === 0, true);
    assert.equal(run.adapter("terminal.json").success, true);
  } },
  { name: "e2e: unknown or wrong-case tool selections fail before any model request", async run() {
    for (const tools of [["Read"], ["no_such_tool"]]) {
      const run = await runSubject({ tools });
      assert.notEqual(run.code, 0);
      assert.equal(run.requests.length, 0);
      const terminal = run.adapter("terminal.json");
      assert.equal(terminal.success, false);
      assert.match(terminal.violations.join(" "), /unknown or wrong-case tool selection|differ from the configured/);
    }
  } },
  { name: "e2e: explicit provider-auth selections work; OAuth adds the preamble and converts names", async run() {
    const call: Scripted[] = [{ toolUse: { id: "toolu_1", name: "Read", input: { path: "data.txt" } } }, { text: "done" }];
    const oauth = await runSubject({ oauth: true, tools: ["read"], script: call, system: "SYS" });
    assert.equal(oauth.code, 0, oauth.stderr + oauth.stdout);
    assert.equal(oauth.requests.length, 2);
    assert.deepEqual(oauth.requests[0].system.map((b: any) => b.text), [OAUTH_PREAMBLE, "SYS"]);
    assert.deepEqual(oauth.requests[0].tools.map((t: any) => t.name), ["Read"]);
    const history = oauth.requests[1].messages.find((m: any) => m.role === "assistant").content.find((b: any) => b.type === "tool_use");
    assert.equal(history.name, "Read", "call history uses the conversion rule");
    assert.deepEqual(oauth.adapter("expectation.json").toolMap, { Read: "read" });
    assert.deepEqual(oauth.adapter("expectation.json").adaptations, ["oauth-preamble", "oauth-tool-names"]);
    assert.equal(oauth.adapter("terminal.json").success, true);
    const plain = await runSubject({ tools: ["read"], script: [{ toolUse: { id: "toolu_1", name: "read", input: { path: "data.txt" } } }, { text: "done" }], system: "SYS" });
    assert.equal(plain.code, 0, plain.stderr);
    assert.deepEqual(plain.requests[0].system.map((b: any) => b.text), ["SYS"]);
    assert.deepEqual(plain.requests[0].tools.map((t: any) => t.name), ["read"]);
  } },
  { name: "e2e: an explicitly selected provider-auth extension works and does not disturb the audit", async run() {
    const run = await runSubject({ authExtension: true, system: "SYS" });
    assert.equal(run.code, 0, run.stderr + run.stdout);
    assert.equal(run.headers[0]["x-fixture-auth"], "explicit-extension-token");
    assert.equal(run.adapter("audit-1.json").ok, true);
    assert.equal(run.adapter("terminal.json").success, true);
  } },
  { name: "e2e: only the selected tools are callable", async run() {
    const script: Scripted[] = [{ toolUse: { id: "toolu_b", name: "bash", input: { command: "echo leaked > leaked.txt" } } }, { text: "done" }];
    // Positive control: with bash configured the same call runs, so the side-effect probe can observe it.
    const configured = await runSubject({ tools: ["bash"], system: "SYS", script });
    assert.equal(configured.adapter("terminal.json").success, true, JSON.stringify(configured.adapter("terminal.json")));
    assert.ok(configured.workFiles.includes("leaked.txt"), `the configured call executed: ${configured.workFiles.join(", ")}`);
    const run = await runSubject({ tools: ["read"], system: "SYS", script });
    const terminal = run.adapter("terminal.json");
    assert.equal(terminal.success, false);
    assert.match(terminal.violations.join(" "), /call to unconfigured tool bash/);
    assert.ok(run.workFiles.includes("data.txt"), "the work root was read before cleanup");
    assert.equal(run.workFiles.includes("leaked.txt"), false, "the refused call did not execute");
  } },
  { name: "e2e: native tool changes declare one trailing placeholder that is never callable", async run() {
    const ok = await runSubject({ tools: ["read"], compat: native, system: "SYS" });
    assert.equal(ok.code, 0, ok.stderr + ok.stdout);
    assert.deepEqual(ok.requests[0].tools.map((t: any) => t.name), ["read", PLACEHOLDER_NAME]);
    assert.equal(ok.adapter("terminal.json").success, true);
    const called = await runSubject({ tools: ["read"], compat: native, system: "SYS", script: [{ toolUse: { id: "toolu_p", name: PLACEHOLDER_NAME, input: {} } }, { text: "done" }] });
    const terminal = called.adapter("terminal.json");
    assert.equal(terminal.success, false);
    assert.match(terminal.violations.join(" "), /placeholder was called/);
  } },
  { name: "e2e: effort markers from the installed provider match the history-derived expectation", async run() {
    const call: Scripted[] = [{ toolUse: { id: "toolu_e", name: "read", input: { path: "data.txt" } } }, { text: "done" }];
    const run = await runSubject({ tools: ["read"], compat: { supportsMidConvoEffort: true }, system: "SYS", script: call });
    assert.equal(run.code, 0, JSON.stringify(run.adapter("terminal.json")));
    assert.equal(run.requests.length, 2);
    const markers = (request: any) => request.messages.flatMap((m: any, i: number) => (m.role === "system" ? [{ i, effort: m.output_config.effort }] : []));
    assert.equal(markers(run.requests[0]).length, 1, "first request: only the trailing marker");
    assert.equal(markers(run.requests[1]).length, 2, "second request: one historical marker plus the trailing one");
    const last = run.requests[1].messages.length - 1;
    assert.equal(run.requests[1].messages[last].role, "system");
    assert.equal(run.requests[1].messages[markers(run.requests[1])[0].i + 1].role, "assistant", "the historical marker sits directly before its turn");
    assert.equal(run.adapter("audit-1.json").ok && run.adapter("audit-2.json").ok, true);
    assert.deepEqual(run.adapter("audit-2.json").protocol.effortMarkers.length, 2);
  } },
  { name: "e2e: a resolved thinking level maps to the trailing effort the provider sends", async run() {
    const run = await runSubject({ tools: [], reasoning: true, compat: { supportsMidConvoEffort: true, forceAdaptiveThinking: true }, system: "SYS", modelArgs: ["--thinking", "low"] });
    assert.equal(run.code, 0, JSON.stringify(run.adapter("terminal.json")));
    const messages = run.requests[0].messages;
    assert.equal(messages[messages.length - 1].output_config.effort, "low");
    assert.equal(run.adapter("audit-1.json").ok, true);
    const off = await runSubject({ tools: [], reasoning: false, compat: { supportsMidConvoEffort: true }, system: "SYS" });
    assert.equal(off.requests[0].messages[off.requests[0].messages.length - 1].output_config.effort, "high");
    assert.equal(off.adapter("audit-1.json").ok, true);
  } },
  { name: "e2e: an altered historical effort level fails after a passing control", async run() {
    const call: Scripted[] = [{ toolUse: { id: "toolu_e", name: "read", input: { path: "data.txt" } } }, { text: "done" }];
    const common = { tools: ["read"], compat: { supportsMidConvoEffort: true }, system: "SYS", script: call };
    const control = await runSubject({ ...common, contaminate: "none" });
    assert.equal(control.adapter("terminal.json").success, true);
    const bad = await runSubject({ ...common, contaminate: "effort-level" });
    assert.equal(bad.requests.length, 1, "the altered second request is never sent");
    const terminal = bad.adapter("terminal.json");
    assert.equal(terminal.success, false);
    assert.match(terminal.reason, /message roles .* differ from the recorded history/);
  } },
  { name: "e2e: a failed turn is never retried and the verdict waits for final settlement", async run() {
    const failed = await runSubject({ script: [{ error: 529 }, { text: "done" }], system: "SYS" });
    assert.equal(failed.requests.length, 1, "the retry request is refused before it is sent");
    const terminal = failed.adapter("terminal.json");
    assert.equal(terminal.success, false);
    assert.match(terminal.reason, /automatic retry is refused/);
    assert.ok(terminal.errorMessage, "the original provider failure is retained");
    // A clean run records its verdict only at agent_settled.
    const ok = await runSubject({ system: "SYS" });
    assert.equal(ok.adapter("terminal.json").success, true);
    assert.equal(ok.adapter("terminal.json").aborted, false);
  } },
  { name: "e2e: a deliberately contaminated request fails the audit after a passing control", async run() {
    const control = await runSubject({ system: "SYS", contaminate: "none" });
    assert.equal(control.adapter("terminal.json").success, true);
    for (const kind of ["system", "message"] as const) {
      const bad = await runSubject({ system: "SYS", contaminate: kind });
      assert.equal(bad.requests.length, 0, "the contaminated request is never sent");
      const terminal = bad.adapter("terminal.json");
      assert.equal(terminal.success, false);
      assert.match(terminal.reason, /request-context audit failed/);
      assert.equal(hasAdapterFile(bad, "audit-1.json") && bad.adapter("audit-1.json").ok, false);
    }
  } },
  // ---- OpenAI-family end to end: installed provider code against fake transports ----------------
  ...(["openai-completions", "openai-responses", "openai-codex-responses"] as const).flatMap((api) => [
    { name: `e2e ${api}: baseline request carries exactly the treatment, assignment, tool and history`, async run() {
      const script: Scripted[] = [{ toolUse: { id: "call_1", name: "read", input: { path: "data.txt" } } }, { text: "done" }];
      const run = await runSubject({ api, tools: ["read"], system: "SYS", script, discovered: true });
      assert.equal(run.code, 0, JSON.stringify(run.adapter("terminal.json")) + run.stderr.slice(0, 300));
      assert.equal(run.requests.length, 2);
      assert.equal(run.adapter("terminal.json").success, true);
      assert.equal(run.adapter("audit-1.json").ok && run.adapter("audit-2.json").ok, true);
      const first = run.requests[0];
      if (api === "openai-completions") {
        assert.equal(first.messages[0].content, "SYS");
        assert.ok(["system", "developer"].includes(first.messages[0].role));
        assert.deepEqual(first.tools.map((t: any) => t.function.name), ["read"]);
        assert.equal(textOfContent(first.messages.find((m: any) => m.role === "user").content), ASSIGNMENT);
        assert.equal(run.requests[1].messages.filter((m: any) => m.role === "tool").length, 1);
      } else {
        assert.deepEqual(first.tools.map((t: any) => t.name), ["read"]);
        assert.equal(first.input.find((i: any) => i.role === "user").content[0].text, ASSIGNMENT);
        if (api === "openai-responses") assert.equal(first.input[0].content, "SYS");
        else {
          assert.equal(first.instructions, "SYS");
          assert.equal(first.input.some((i: any) => i.role === "developer" || i.role === "system"), false, "codex carries no system item");
        }
        assert.ok(run.requests[1].input.some((i: any) => i.type === "function_call_output"));
      }
      assert.deepEqual(run.adapter("expectation.json").adaptations, []);
      for (const leaked of ["DISCOVERED-PROJECT-CONTEXT", "DISCOVERED-HOME-CONTEXT", "DISCOVERED-SKILL", "DISCOVERED-TEMPLATE", "DISCOVERED-EXTENSION", "Current working directory"]) assert.equal(requestText(run).includes(leaked), false, leaked);
      // Complete pre-request payloads are retained, not projections.
      assert.deepEqual(run.adapter("request-1.json"), first);
    } },
    { name: `e2e ${api}: literal leading slash, at-sign, hyphen and template text survive`, async run() {
      for (const assignment of ["/not-a-command @not-a-file -not-an-option {{template}} $1"]) {
        const run = await runSubject({ api, assignment });
        assert.equal(run.code, 0, run.stderr.slice(0, 300));
        const text = api === "openai-completions" ? textOfContent(run.requests[0].messages.find((m: any) => m.role === "user").content) : run.requests[0].input.find((i: any) => i.role === "user").content[0].text;
        assert.equal(text, assignment);
      }
    } },
    knownBadRequests(api),
  ]),
  knownBadRequests("anthropic-messages"),
  { name: "e2e openai-codex-responses: an empty treatment sends Pi's fixed default instructions", async run() {
    const run = await runSubject({ api: "openai-codex-responses" });
    assert.equal(run.code, 0, run.stderr.slice(0, 300));
    assert.equal(run.requests[0].instructions, "You are a helpful assistant.");
    assert.deepEqual(run.adapter("expectation.json").adaptations, ["codex-default-instructions"]);
    assert.equal(run.adapter("terminal.json").success, true);
  } },
  { name: "e2e openai-responses: configuration updates match the history-derived expectation and an altered level fails", async run() {
    const script: Scripted[] = [{ toolUse: { id: "call_1", name: "read", input: { path: "data.txt" } } }, { text: "done" }];
    const common = { api: "openai-responses", tools: ["read"], system: "SYS", script, reasoning: true, compat: { supportsMidConvoEffort: true }, modelArgs: ["--thinking", "low"] };
    const run = await runSubject({ ...common, contaminate: "none" });
    assert.equal(run.code, 0, JSON.stringify(run.adapter("terminal.json")));
    const updates = (r: any) => r.input.filter((i: any) => i.type === "configuration_update");
    assert.equal(updates(run.requests[0]).length, 1, "first request: only the trailing update");
    assert.equal(updates(run.requests[1]).length, 2, "second request: one historical update plus the trailing one");
    assert.equal(run.adapter("audit-2.json").ok, true);
    const bad = await runSubject({ ...common, contaminate: "effort-level" });
    assert.equal(bad.requests.length, 1);
    assert.match(bad.adapter("terminal.json").reason, /message roles .* differ from the recorded history/);
    // Dropped, added and altered configuration updates are not the derived ones, on the first request and on the later one.
    for (const kind of ["config-drop", "config-add", "config-field"]) {
      const tampered = await runSubject({ ...common, contaminate: kind });
      assert.ok(tampered.requests.length <= 1, `${kind}: the first or second request is blocked`);
      assert.equal(tampered.adapter("terminal.json").success, false, kind);
      assert.match(tampered.adapter("terminal.json").reason, /request-context audit failed/, kind);
    }
    assert.equal((await runSubject({ ...common, contaminate: "config-drop" })).requests.length, 0, "the first request already lacks its trailing update");
  } },
  ...APIS.map((api): TestCase => ({ name: `e2e ${api}: an audit or evidence failure ends the run before the request is sent, even when failure evidence cannot be written`, async run() {
    const common = { api, system: "SYS" };
    assert.equal((await runSubject({ ...common, contaminate: "none" })).adapter("terminal.json").success, true, "control");
    // An audit that throws, and an evidence file that cannot be written, both used to be swallowed by the runtime, which then sent the request.
    for (const [label, options] of [["audit throws", { contaminate: "audit-throws" }], ["request evidence cannot be written", { sabotage: ["request-1.json"] }], ["audit evidence cannot be written", { sabotage: ["audit-1.json"] }]] as const) {
      const run = await runSubject({ ...common, ...options });
      assert.equal(run.requests.length, 0, `${label}: no request is sent`);
      assert.equal(run.code, 5, `${label}: the process ends with the fatal exit code`);
      const terminal = run.adapter("terminal.json");
      assert.equal(terminal.success, false, label);
      assert.match(terminal.reason, /request audit or its evidence failed/, label);
    }
    // Even the failure evidence cannot be written: still no request, and no terminal evidence to read as success.
    const both = await runSubject({ ...common, sabotage: ["request-1.json", "terminal.json"] });
    assert.equal(both.requests.length, 0);
    assert.equal(both.code, 5);
    assert.equal(hasAdapterFile(both, "terminal.json"), false);
  } })),
  ...APIS.map((api): TestCase => ({ name: `e2e ${api}: image-bearing and empty tool results are derived from the recorded result; tampered images, captions and placeholders fail`, async run() {
    const PNG = "iVBORw0KGgo=";
    const OMIT = "(tool image omitted: model does not support images)";
    const base = { api, tools: ["snap"], system: "SYS", snap: true };
    const script = (mode: string): Scripted[] => [{ toolUse: { id: "call_1", name: "snap", input: { mode } } }, { text: "done" }];
    const second = (run: Run) => JSON.stringify(run.requests[1]);
    // Passing baselines: the installed provider's own encoding of each result shape equals the derived one.
    const shapes: [string, boolean, (text: string) => void][] = [
      ["empty", true, (t) => { if (api !== "anthropic-messages") assert.ok(t.includes("(no tool output)"), "empty result placeholder"); }],
      ["image", true, (t) => { assert.ok(t.includes(PNG)); if (api !== "openai-responses" && api !== "openai-codex-responses") assert.ok(t.includes("(see attached image)")); }],
      ["mixed", true, (t) => { assert.ok(t.includes(PNG) && t.includes("/9j/4AAQ") && t.includes("caption")); if (api === "openai-completions") assert.ok(t.includes("Attached image(s) from tool result:")); }],
      ["error", true, (t) => { assert.ok(t.includes(PNG) && t.includes("boom")); if (api === "anthropic-messages") assert.ok(t.includes("Images from the error result of tool call call_1 (snap):") && t.includes("(image attached after the tool results)")); }],
      ["mixed", false, (t) => { assert.equal(t.includes(PNG), false); assert.ok(t.includes(OMIT)); }],
      ["image", false, (t) => { assert.equal(t.includes(PNG), false); assert.ok(t.includes(OMIT)); }],
    ];
    for (const [mode, vision, check] of shapes) {
      const run = await runSubject({ ...base, vision, script: script(mode) });
      const label = `${api} ${mode} vision=${vision}`;
      assert.equal(run.code, 0, `${label}: ${run.stderr.slice(0, 300)} ${JSON.stringify(run.adapter("terminal.json"))}`);
      assert.equal(run.requests.length, 2, label);
      assert.equal(run.adapter("terminal.json").success, true, label);
      assert.equal(run.adapter("audit-2.json").ok, true, label);
      assert.equal(run.adapter("expectation.json").inputImage, vision, `${label}: the image capability is the model's`);
      check(second(run));
      assert.deepEqual(run.adapter("request-2.json"), run.requests[1], `${label}: the complete payload is retained`);
    }
    // Known-bad: each mutation acts on the second request, so a blocked second request proves it applied and was caught.
    const swap = (from: string, to: string) => `swap:${JSON.stringify([from, to])}`;
    const bad: [string, boolean, string][] = [
      ["mixed", true, swap(PNG, "iVBORw0KGgx=")],
      ["mixed", true, swap("image/png", "image/gif")],
      ["mixed", true, swap("caption", "CAPTION")],
      ["mixed", true, "image-extra"],
      ["mixed", true, "image-drop"],
      ["image", false, swap(OMIT, `${OMIT} (altered)`)],
      ["image", false, "image-extra"],
      ["empty", true, swap("(no tool output)", "(no tool output) (altered)")],
      ["image", true, swap("(see attached image)", "(see attached image) (altered)")],
      ["mixed", true, swap("Attached image(s) from tool result:", "Attached image(s) from tool result (altered):")],
      ["error", true, swap("Images from the error result", "Images from the failed result")],
      ["error", true, swap("(image attached after the tool results)", "(image attached)")],
    ];
    let ran = 0;
    for (const [mode, vision, kind] of bad) {
      // Each kind only applies where the API encodes the text it swaps.
      const wanted = kind.startsWith("swap:") ? JSON.parse(kind.slice(5))[0] : undefined;
      const control = await runSubject({ ...base, vision, script: script(mode) });
      if (wanted !== undefined && !second(control).includes(wanted)) continue;
      if (kind === "image-drop" && !second(control).includes(PNG)) continue;
      const run = await runSubject({ ...base, vision, script: script(mode), contaminate: kind });
      const label = `${api} ${mode} vision=${vision} ${kind}`;
      assert.equal(run.requests.length, 1, `${label}: the second request is blocked`);
      assert.equal(run.adapter("terminal.json").success, false, label);
      assert.match(run.adapter("terminal.json").reason, /request-context audit failed/, label);
      assert.equal(run.adapter("audit-2.json").ok, false, label);
      assert.notDeepEqual(run.adapter("request-2.json"), control.adapter("request-2.json"), `${label}: the retained payload is the contaminated one`);
      ran += 1;
    }
    assert.ok(ran >= 7, `${api}: ${ran} known-bad cases ran`);
  } })),
  { name: "e2e anthropic-messages: declared fallbacks are derived; tampering fails; the served model is recorded per turn with unknown provenance never guessed", async run() {
    const fb = { provider: "fakeanth", model: "fallback-model", cost: { input: 1, output: 1, cacheRead: 0, cacheWrite: 0 } };
    const common = { system: "SYS", compat: { allowedFallbackModels: [fb] } };
    const control = await runSubject({ ...common, contaminate: "none" });
    assert.equal(control.code, 0, JSON.stringify(control.adapter("terminal.json")) + control.stderr.slice(0, 200));
    assert.deepEqual(control.requests[0].fallbacks, [{ model: "fallback-model" }], "the installed provider sends the declared list");
    assert.equal(control.adapter("terminal.json").success, true);
    assert.ok(control.adapter("expectation.json").adaptations.includes("model-fallbacks"));
    assert.deepEqual(control.adapter("expectation.json").fallbacks, [{ provider: "fakeanth", model: "fallback-model" }]);
    // The reply names the requested model: Pi surfaces no different served model, so provenance stays unknown.
    assert.deepEqual(control.adapter("terminal.json").turns, [{ requestedModel: "fake-model", allowedFallbacks: [{ provider: "fakeanth", model: "fallback-model" }], servedModel: null, servedProvenance: "unknown" }]);
    for (const kind of ["fallback-alter", "fallback-add", "fallback-drop"]) {
      const bad = await runSubject({ ...common, contaminate: kind });
      assert.equal(bad.requests.length, 0, `${kind}: never sent`);
      assert.match(bad.adapter("terminal.json").reason, /fallback list differs/, kind);
      assert.equal(bad.adapter("request-1.json").model, "fake-model", `${kind}: the complete payload is retained`);
    }
    // A model that declares none expects none, and gets the same field rejected.
    const none = await runSubject({ system: "SYS", contaminate: "fallback-add" });
    assert.match(none.adapter("terminal.json").reason, /unrecorded top-level request field: fallbacks/);
    // Provider-reported served model per turn: the declared fallback, and one outside the declared list (recorded as undeclared, not guessed or rejected).
    const served = await runSubject({ ...common, tools: ["read"], script: [{ toolUse: { id: "toolu_1", name: "read", input: { path: "data.txt" } }, model: "fallback-model" }, { text: "done", model: "surprise-model" }] });
    assert.equal(served.adapter("terminal.json").success, true, JSON.stringify(served.adapter("terminal.json")));
    const turns = served.adapter("terminal.json").turns;
    assert.deepEqual(turns.map((t: any) => [t.requestedModel, t.servedModel, t.servedProvenance, t.servedModelDeclared]), [["fake-model", "fallback-model", "provider-reported", true], ["fake-model", "surprise-model", "provider-reported", false]]);
    assert.deepEqual(turns[0].allowedFallbacks, [{ provider: "fakeanth", model: "fallback-model" }]);
    assert.ok(readEvents(served).some((e: any) => e.kind === "assistant" && e.servedModel === "fallback-model"), "the per-turn record is also in the event stream");
  } },
  { name: "e2e openai-completions and openai-responses: the served model is recorded when the provider reports one and is unknown otherwise", async run() {
    const completions = await runSubject({ api: "openai-completions", system: "SYS", script: [{ text: "done", model: "served-elsewhere" }] });
    assert.deepEqual(completions.adapter("terminal.json").turns.map((t: any) => [t.servedModel, t.servedProvenance]), [["served-elsewhere", "provider-reported"]]);
    const responses = await runSubject({ api: "openai-responses", system: "SYS" });
    assert.deepEqual(responses.adapter("terminal.json").turns.map((t: any) => [t.servedModel, t.servedProvenance, t.allowedFallbacks]), [[null, "unknown", []]]);
    // Fallbacks are an Anthropic consumer rule: the OpenAI APIs send none even for a model that declares some.
    const declared = await runSubject({ api: "openai-completions", system: "SYS", compat: { allowedFallbackModels: [{ provider: "fakeanth", model: "x", cost: { input: 1, output: 1, cacheRead: 0, cacheWrite: 0 } }] } });
    assert.equal(declared.requests[0].fallbacks, undefined);
    assert.equal(declared.adapter("terminal.json").success, true);
  } },
  { name: "e2e openai-codex-responses: the default instruction is permitted only for an empty treatment and only with its exact text", async run() {
    const empty = await runSubject({ api: "openai-codex-responses", contaminate: "none" });
    assert.equal(empty.requests[0].instructions, "You are a helpful assistant.");
    assert.deepEqual(empty.adapter("expectation.json").adaptations, ["codex-default-instructions"]);
    assert.equal(empty.adapter("terminal.json").success, true);
    const treated = await runSubject({ api: "openai-codex-responses", system: "SYS", contaminate: "none" });
    assert.equal(treated.requests[0].instructions, "SYS");
    assert.deepEqual(treated.adapter("expectation.json").adaptations, []);
    // The default text does not replace a configured treatment, and an empty treatment accepts nothing else.
    const swapped = await runSubject({ api: "openai-codex-responses", system: "SYS", contaminate: "codex-default" });
    assert.equal(swapped.requests.length, 0);
    assert.match(swapped.adapter("terminal.json").reason, /instructions differ/);
    for (const [to, label] of [["You are a helpful assistant. Be brief.", "extended default"], ["", "dropped default"], ["you are a helpful assistant.", "altered case"]] as const) {
      const bad = await runSubject({ api: "openai-codex-responses", contaminate: `swap:${JSON.stringify(["You are a helpful assistant.", to])}` });
      assert.equal(bad.requests.length, 0, label);
      assert.match(bad.adapter("terminal.json").reason, /instructions differ/, label);
    }
    // No other API implies a default instruction for an empty treatment.
    const other = await runSubject({ api: "openai-responses", contaminate: "none" });
    assert.deepEqual(other.adapter("expectation.json").adaptations, []);
    assert.equal(other.requests[0].input.some((i: any) => i.role === "system" || i.role === "developer"), false);
  } },
  { name: "e2e: top-level encoding from the installed providers matches the derivation per API; changed effort, budget, format, template, tool choice and nested fields fail after passing controls", async run() {
    const sans = (r: any) => { const { prompt_cache_key: _k, ...rest } = r ?? {}; return rest; };
    const groups: { label: string; options: RunOptions; check: (request: any) => void; kinds: string[] }[] = [
      { label: "anthropic adaptive", options: { reasoning: true, compat: { forceAdaptiveThinking: true }, modelArgs: ["--thinking", "low"] },
        check: (r) => { assert.deepEqual(r.output_config, { effort: "low" }); assert.equal(r.thinking.type, "adaptive"); },
        kinds: ["enc-output-config", "enc-effort", "enc-tool-choice", "enc-metadata", "enc-max-tokens", "enc-beta"] },
      // The configured thinking budget (settings.json thinkingBudgets) is a runtime input the derivation reads like the runtime does.
      { label: "anthropic budget", options: { reasoning: true, modelArgs: ["--thinking", "low"], settings: { thinkingBudgets: { low: 1500 } } },
        check: (r) => { assert.deepEqual(r.thinking, { type: "enabled", budget_tokens: 1500, display: "summarized" }); assert.deepEqual(r.betas, ["interleaved-thinking-2025-05-14"]); },
        kinds: ["enc-budget", "enc-thinking-field", "enc-beta"] },
      { label: "openai-completions effort", options: { api: "openai-completions", reasoning: true, modelArgs: ["--thinking", "low"] },
        check: (r) => { assert.equal(r.reasoning_effort, "low"); },
        kinds: ["enc-effort", "enc-kwargs", "enc-format", "enc-tool-choice", "enc-nested"] },
      { label: "openai-responses reasoning", options: { api: "openai-responses", reasoning: true, modelArgs: ["--thinking", "low"] },
        check: (r) => { assert.deepEqual(r.reasoning, { effort: "low", summary: "auto" }); assert.deepEqual(r.include, ["reasoning.encrypted_content"]); },
        kinds: ["enc-effort", "enc-format", "enc-include", "enc-tool-choice", "enc-cache-key"] },
      { label: "openai-codex-responses reasoning", options: { api: "openai-codex-responses", reasoning: true, modelArgs: ["--thinking", "low"] },
        check: (r) => { assert.deepEqual(r.reasoning, { effort: "low", summary: "auto" }); assert.deepEqual(r.text, { verbosity: "low" }); },
        kinds: ["enc-effort", "enc-format", "enc-tool-choice", "enc-verbosity", "enc-parallel", "enc-cache-key"] },
    ];
    for (const g of groups) {
      const common = { system: "SYS", ...g.options };
      const control = await runSubject({ ...common, contaminate: "none" });
      assert.equal(control.adapter("terminal.json").success, true, `${g.label} control: ${JSON.stringify(control.adapter("terminal.json"))}`);
      // The audited pre-request payload: the SDK moves `betas` into a header on the wire.
      g.check(control.adapter("request-1.json"));
      assert.deepEqual(control.adapter("audit-1.json").protocol.encoding.model, "fake-model", `${g.label}: the derived encoding is recorded`);
      for (const kind of g.kinds) {
        const bad = await runSubject({ ...common, contaminate: kind });
        const label = `${g.label} ${kind}`;
        assert.equal(bad.requests.length, 0, `${label}: the request is never sent`);
        const terminal = bad.adapter("terminal.json");
        assert.equal(terminal.success, false, label);
        assert.match(terminal.reason, /request-context audit failed: .*(top-level .* differs from the encoding|unrecorded top-level request field|output cap)/, label);
        if (kind === "enc-cache-key") assert.equal(bad.adapter("request-1.json").prompt_cache_key, "other-session", `${label}: the retained payload is the mutated one`);
        else assert.notDeepEqual(sans(bad.adapter("request-1.json")), sans(control.adapter("request-1.json")), `${label}: the retained payload is the mutated one`);
      }
    }
  } },
  // The three runtime inputs the adapter reads itself rather than from the request. Each control
  // shows the value only that input produces, so a broken read in the adapter fails the control.
  { name: "e2e: the adapter reads a configured anthropic-beta header, PI_CACHE_RETENTION and model sampling parameters where the runtime reads them; a tampered value fails after a passing control", async run() {
    const anthropicScript: Scripted[] = [{ toolUse: { id: "toolu_1", name: "read", input: { path: "data.txt" } } }, { text: "done" }];
    const completionsScript: Scripted[] = [{ toolUse: { id: "call_1", name: "read", input: { path: "data.txt" } } }, { text: "done" }];
    const groups: { label: string; options: RunOptions; check: (request: any) => void; kind: string }[] = [
      { label: "anthropic-beta model header", kind: "enc-beta-computed",
        options: { tools: ["read"], script: anthropicScript, reasoning: true, modelArgs: ["--thinking", "low"], modelExtra: { headers: { "anthropic-beta": "custom-beta,other" } } },
        check: (r) => assert.deepEqual(r.betas, ["custom-beta", "other"], "the configured header replaces the computed betas") },
      { label: "PI_CACHE_RETENTION=long", kind: "enc-retention-drop",
        options: { api: "openai-completions", tools: ["read"], script: completionsScript, env: { PI_CACHE_RETENTION: "long" } },
        check: (r) => assert.equal(r.prompt_cache_retention, "24h") },
      { label: "samplingParams and samplingParamsByThinkingLevel", kind: "enc-sampling",
        options: { api: "openai-completions", tools: ["read"], script: completionsScript, reasoning: true, modelArgs: ["--thinking", "low"],
          modelExtra: { samplingParams: { top_p: 0.5 }, samplingParamsByThinkingLevel: { low: { temperature: 0.1 } } } },
        check: (r) => { assert.equal(r.top_p, 0.5); assert.equal(r.temperature, 0.1, "the level's parameters apply over the model's"); } },
    ];
    for (const g of groups) {
      const common = { system: "SYS", ...g.options };
      const control = await runSubject({ ...common, contaminate: "none" });
      assert.equal(control.adapter("terminal.json").success, true, `${g.label} control: ${JSON.stringify(control.adapter("terminal.json"))}`);
      assert.equal(control.requests.length, 2, `${g.label}: the control sends both requests`);
      g.check(control.adapter("request-1.json"));
      g.check(control.adapter("request-2.json"));
      const bad = await runSubject({ ...common, contaminate: g.kind });
      assert.equal(bad.requests.length, 0, `${g.label}: the tampered request is never sent`);
      assert.equal(bad.adapter("terminal.json").success, false, g.label);
      assert.match(bad.adapter("terminal.json").reason, /request-context audit failed: .*(top-level .* (differs from the encoding|derived from the model, thinking level and runtime settings is missing))/, g.label);
      const sans = (r: any) => { const { prompt_cache_key: _k, ...rest } = r ?? {}; return rest; };
      assert.notDeepEqual(sans(bad.adapter("request-1.json")), sans(control.adapter("request-1.json")), `${g.label}: the retained payload is the tampered one`);
    }
  } },
  { name: "e2e openai-completions: the built-in OpenRouter Anthropic compatibility is measured; cache_control and reasoning_details are derived and misplaced or altered ones fail", async run() {
    // compat of the built-in ~anthropic/claude-sonnet-latest entry (pi-ai providers/data/openrouter.json), verbatim.
    const OR_COMPAT = { supportsDeveloperRole: false, thinkingFormat: "openrouter", supportsStrictMode: true, cacheControlFormat: "anthropic", sendSessionAffinityHeaders: true };
    const DETAILS = [{ type: "reasoning.text", text: "Read the file first.", signature: "sig-1", format: "anthropic-claude-v1", index: 0 }];
    const CC = { type: "ephemeral" };
    const common: RunOptions = { api: "openai-completions", tools: ["read", "ls"], system: "SYS", reasoning: true, vision: true, compat: OR_COMPAT,
      script: [{ toolUse: { id: "call_1", name: "read", input: { path: "data.txt" } }, reasoningDetails: DETAILS }, { text: "done" }] };
    const control = await runSubject({ ...common, contaminate: "none" });
    assert.equal(control.adapter("terminal.json").success, true, JSON.stringify(control.adapter("terminal.json")));
    assert.equal(control.requests.length, 2);
    const [first, second] = control.requests;
    assert.deepEqual(first.messages[0], { role: "system", content: [{ type: "text", text: "SYS", cache_control: CC }] }, "the instruction is annotated");
    assert.deepEqual(first.tools.map((t: any) => t.cache_control), [undefined, CC], "only the last tool is annotated");
    assert.deepEqual(first.messages[1].content[first.messages[1].content.length - 1].cache_control, CC, "the last conversation message is annotated");
    const replay = second.messages.find((m: any) => m.role === "assistant");
    assert.deepEqual(replay.reasoning_details, DETAILS, "the recorded reasoning details are replayed");
    assert.equal(second.messages.find((m: any) => m.role === "user").content.some((p: any) => p.cache_control), false, "the marker moved to the new last message");
    assert.deepEqual(second.messages[second.messages.length - 1].content[0].cache_control, CC);
    assert.equal(control.adapter("audit-1.json").ok && control.adapter("audit-2.json").ok, true);
    assert.deepEqual(control.adapter("audit-1.json").protocol.cacheControl, CC);
    for (const [kind, blocked] of [["cc-strip-instruction", 1], ["cc-instruction-text", 1], ["cc-ttl", 1], ["cc-tool-move", 1], ["tool-sibling", 1], ["enc-or-effort", 1], ["cc-extra-user", 2], ["cc-move-last", 2], ["rd-alter", 2], ["rd-drop", 2]] as const) {
      const bad = await runSubject({ ...common, contaminate: kind });
      assert.equal(bad.requests.length, blocked - 1, `${kind}: request ${blocked} is blocked`);
      assert.equal(bad.adapter("terminal.json").success, false, kind);
      assert.match(bad.adapter("terminal.json").reason, /request-context audit failed/, kind);
      assert.equal(bad.adapter(`audit-${blocked}.json`).ok, false, kind);
      const sans = (r: any) => { const { prompt_cache_key: _k, ...rest } = r ?? {}; return rest; };
      assert.notDeepEqual(sans(bad.adapter(`request-${blocked}.json`)), sans(control.adapter(`request-${blocked}.json`)), `${kind}: the retained payload is the contaminated one`);
    }
  } },
  { name: "e2e: an API outside the four audited ones fails closed before any request", async run() {
    const run = await runSubject({ api: "mistral-conversations", system: "SYS" });
    assert.equal(run.requests.length, 0);
    const terminal = run.adapter("terminal.json");
    assert.equal(terminal.success, false);
    assert.match(terminal.reason, /no request audit exists for API mistral-conversations/);
  } },
  { name: "automatic compaction is cancelled and recorded as a violation", async run() {
    const root = realpathSync(mkdtempSync(join(tmpdir(), "herdr-compact-")));
    const saved = process.env.HERDR_ORCH_MEASUREMENT_MANIFEST;
    try {
      writeFileSync(join(root, "assignment.txt"), ASSIGNMENT);
      writeFileSync(join(root, "manifest.json"), JSON.stringify({ treatment: "", assignmentFile: join(root, "assignment.txt"), tools: [], evidenceDir: join(root, "evidence") }));
      process.env.HERDR_ORCH_MEASUREMENT_MANIFEST = join(root, "manifest.json");
      const handlers = new Map<string, Function>();
      const adapter = (await import("./measurement-adapter.ts")).default as (pi: any) => void;
      adapter({ on: (name: string, handler: Function) => handlers.set(name, handler), getActiveTools: () => [], getAllTools: () => [], getThinkingLevel: () => "off" });
      assert.deepEqual(await handlers.get("session_before_compact")!({}), { cancel: true });
      await handlers.get("message_end")!({ message: { role: "assistant", stopReason: "stop", content: [{ type: "text", text: "x" }] } });
      await handlers.get("agent_settled")!({ aborted: false });
      process.exitCode = 0;
      const terminal = JSON.parse(readFileSync(join(root, "evidence", "terminal.json"), "utf8"));
      assert.equal(terminal.success, false);
      assert.deepEqual(terminal.violations, ["automatic compaction is refused"]);
    } finally {
      if (saved === undefined) delete process.env.HERDR_ORCH_MEASUREMENT_MANIFEST; else process.env.HERDR_ORCH_MEASUREMENT_MANIFEST = saved;
      rmSync(root, { recursive: true, force: true });
    }
  } },
  { name: "the adapter is inert without a manifest", async run() {
    const probe = spawnSync(process.execPath, [process.argv[1], "--offline", "--no-session", "-ne", "-e", join(here, "measurement-adapter.ts"), "-e", join(here, "fork-adapter.ts"), "--no-skills", "--no-prompt-templates", "--no-context-files", "--no-mcp", "--version"], { encoding: "utf8", timeout: 60000, stdio: ["ignore", "pipe", "pipe"], env: { ...process.env, HERDR_ORCH_MEASUREMENT_MANIFEST: "" } });
    assert.equal(probe.status, 0, probe.stderr);
    assert.equal(existsSync(join(here, "evidence")), false);
  } },
];

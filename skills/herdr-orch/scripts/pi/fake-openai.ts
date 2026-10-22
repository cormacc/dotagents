// Local fake OpenAI-family endpoints (Chat Completions and the Responses wire used by both
// openai-responses and openai-codex-responses) for the measurement adapter tests. Like
// fake-anthropic.ts it records every request body and header set and replays scripted replies
// as server-sent events, so the installed provider code builds the audited request.
import { createServer, type IncomingMessage } from "node:http";
import { gunzipSync, zstdDecompressSync } from "node:zlib";
import type { Scripted } from "./fake-anthropic.ts";

export type OpenAIWire = "completions" | "responses";

export interface FakeOpenAI {
  url: string;
  requests: any[];
  headers: Record<string, string | string[] | undefined>[];
  close: () => Promise<void>;
}

const data = (obj: object) => `data: ${JSON.stringify(obj)}\n\n`;
const event = (obj: { type: string }) => `event: ${obj.type}\ndata: ${JSON.stringify(obj)}\n\n`;

function completionsFrames(reply: Scripted, n: number): string {
  const base = { id: `chatcmpl-${n}`, object: "chat.completion.chunk", created: 1, model: ("error" in reply ? undefined : reply.model) ?? "fake-model" };
  if ("text" in reply) {
    return data({ ...base, choices: [{ index: 0, delta: { role: "assistant", content: reply.text }, finish_reason: null }] })
      + data({ ...base, choices: [{ index: 0, delta: {}, finish_reason: "stop" }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } })
      + "data: [DONE]\n\n";
  }
  if ("toolUse" in reply) {
    const { id, name, input } = reply.toolUse;
    const details = reply.reasoningDetails ? data({ ...base, choices: [{ index: 0, delta: { role: "assistant", reasoning_details: reply.reasoningDetails }, finish_reason: null }] }) : "";
    return details + data({ ...base, choices: [{ index: 0, delta: { role: "assistant", tool_calls: [{ index: 0, id, type: "function", function: { name, arguments: JSON.stringify(input) } }] }, finish_reason: null }] })
      + data({ ...base, choices: [{ index: 0, delta: {}, finish_reason: "tool_calls" }], usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } })
      + "data: [DONE]\n\n";
  }
  return "";
}

function responsesFrames(reply: Scripted, n: number): string {
  const response = (status: string) => ({ id: `resp_${n}`, status, output: [], usage: { input_tokens: 1, output_tokens: 1, total_tokens: 2, input_tokens_details: { cached_tokens: 0 } } });
  const head = event({ type: "response.created", response: response("in_progress") } as any);
  if ("text" in reply) {
    const part = { type: "output_text", text: reply.text, annotations: [] };
    const done = { type: "message", id: `msg_${n}`, role: "assistant", status: "completed", content: [part] };
    return head
      + event({ type: "response.output_item.added", output_index: 0, item: { type: "message", id: `msg_${n}`, role: "assistant", status: "in_progress", content: [] } } as any)
      + event({ type: "response.output_text.delta", output_index: 0, item_id: `msg_${n}`, content_index: 0, delta: reply.text } as any)
      + event({ type: "response.output_item.done", output_index: 0, item: done } as any)
      + event({ type: "response.completed", response: { ...response("completed"), output: [done] } } as any);
  }
  if ("toolUse" in reply) {
    const { id, name, input } = reply.toolUse;
    const args = JSON.stringify(input);
    const item = { type: "function_call", id: `fc_${n}`, call_id: id, name, arguments: args, status: "completed" };
    return head
      + event({ type: "response.output_item.added", output_index: 0, item: { ...item, arguments: "", status: "in_progress" } } as any)
      + event({ type: "response.function_call_arguments.delta", output_index: 0, item_id: item.id, delta: args } as any)
      + event({ type: "response.function_call_arguments.done", output_index: 0, item_id: item.id, arguments: args } as any)
      + event({ type: "response.output_item.done", output_index: 0, item } as any)
      + event({ type: "response.completed", response: { ...response("completed"), output: [item] } } as any);
  }
  return "";
}

const readBody = (req: IncomingMessage) => new Promise<string>((resolve) => {
  const chunks: Buffer[] = [];
  req.on("data", (c) => chunks.push(c));
  // The codex transport zstd-compresses its body on the wire; the audited payload is the
  // pre-compression request, so the fake decodes it to record the same object.
  req.on("end", () => {
    const raw = Buffer.concat(chunks);
    const encoding = req.headers["content-encoding"];
    resolve((encoding === "zstd" ? zstdDecompressSync(raw) : encoding === "gzip" ? gunzipSync(raw) : raw).toString("utf8"));
  });
});

export async function startFakeOpenAI(wire: OpenAIWire, script: Scripted[]): Promise<FakeOpenAI> {
  const requests: any[] = [];
  const headers: Record<string, string | string[] | undefined>[] = [];
  const server = createServer(async (req, res) => {
    const body = await readBody(req);
    let parsed: any = null;
    try { parsed = JSON.parse(body); } catch { /* recorded as null */ }
    requests.push(parsed);
    headers.push(req.headers);
    const reply = script[Math.min(requests.length - 1, script.length - 1)];
    if ("error" in reply) {
      res.writeHead(reply.error, { "content-type": "application/json" });
      res.end(JSON.stringify({ error: { message: "scripted transient failure", type: "server_error" } }));
      return;
    }
    res.writeHead(200, { "content-type": "text/event-stream", "cache-control": "no-cache" });
    res.end(wire === "completions" ? completionsFrames(reply, requests.length) : responsesFrames(reply, requests.length));
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address() as { port: number };
  return { url: `http://127.0.0.1:${port}`, requests, headers, close: () => new Promise<void>((resolve) => server.close(() => resolve())) };
}

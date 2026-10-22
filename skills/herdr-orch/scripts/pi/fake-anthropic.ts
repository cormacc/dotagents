// Local fake Anthropic Messages endpoint for the measurement adapter tests. It records every
// request body and replays queued scripted responses as server-sent events, so the real
// provider code in the installed Pi builds the request under test.
import { createServer, type IncomingMessage } from "node:http";

// `model` is the model id the reply reports as serving it (the provider-reported served model); it defaults to the fixture model.
// `reasoningDetails` (Chat Completions fake only) streams OpenRouter-style `reasoning_details` before a tool call.
export type Scripted = { text: string; model?: string } | { toolUse: { id: string; name: string; input: object }; model?: string; reasoningDetails?: object[] } | { error: number };

export interface FakeAnthropic {
  url: string;
  requests: any[];
  headers: Record<string, string | string[] | undefined>[];
  close: () => Promise<void>;
}

const sse = (name: string, data: object) => `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`;

function frames(response: Scripted, n: number): string {
  const head = sse("message_start", { type: "message_start", message: { id: `msg_${n}`, type: "message", role: "assistant", content: [], model: ("error" in response ? undefined : response.model) ?? "fake-model", stop_reason: null, usage: { input_tokens: 1, output_tokens: 1 } } });
  if ("text" in response) {
    return head
      + sse("content_block_start", { type: "content_block_start", index: 0, content_block: { type: "text", text: "" } })
      + sse("content_block_delta", { type: "content_block_delta", index: 0, delta: { type: "text_delta", text: response.text } })
      + sse("content_block_stop", { type: "content_block_stop", index: 0 })
      + sse("message_delta", { type: "message_delta", delta: { stop_reason: "end_turn", stop_sequence: null }, usage: { output_tokens: 2 } })
      + sse("message_stop", { type: "message_stop" });
  }
  const { id, name, input } = response.toolUse;
  return head
    + sse("content_block_start", { type: "content_block_start", index: 0, content_block: { type: "tool_use", id, name, input: {} } })
    + sse("content_block_delta", { type: "content_block_delta", index: 0, delta: { type: "input_json_delta", partial_json: JSON.stringify(input) } })
    + sse("content_block_stop", { type: "content_block_stop", index: 0 })
    + sse("message_delta", { type: "message_delta", delta: { stop_reason: "tool_use", stop_sequence: null }, usage: { output_tokens: 2 } })
    + sse("message_stop", { type: "message_stop" });
}

const readBody = (req: IncomingMessage) => new Promise<string>((resolve) => {
  const chunks: Buffer[] = [];
  req.on("data", (c) => chunks.push(c));
  req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
});

export async function startFakeAnthropic(script: Scripted[]): Promise<FakeAnthropic> {
  const requests: any[] = [];
  const headers: Record<string, string | string[] | undefined>[] = [];
  const server = createServer(async (req, res) => {
    const body = await readBody(req);
    let parsed: any = null;
    try { parsed = JSON.parse(body); } catch { /* recorded as null */ }
    requests.push(parsed);
    headers.push(req.headers);
    const response = script[Math.min(requests.length - 1, script.length - 1)];
    if ("error" in response) {
      res.writeHead(response.error, { "content-type": "application/json" });
      res.end(JSON.stringify({ type: "error", error: { type: "overloaded_error", message: "scripted transient failure" } }));
      return;
    }
    res.writeHead(200, { "content-type": "text/event-stream", "cache-control": "no-cache" });
    res.end(frames(response, requests.length));
  });
  await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
  const { port } = server.address() as { port: number };
  return { url: `http://127.0.0.1:${port}`, requests, headers, close: () => new Promise<void>((resolve) => server.close(() => resolve())) };
}

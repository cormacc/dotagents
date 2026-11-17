// Controlled stdio language server for LspClient tests. It records document
// open/close notifications and returns that log as the workspace/symbol result.
// With FAKE_PROGRESS_MS set, the first didOpen starts a progress token that
// begins after 20 ms and ends after FAKE_PROGRESS_MS, as clangd's background index does.
// With FAKE_INIT_DELAY_MS set, the initialize response is delayed by that many milliseconds.
let buffer = Buffer.alloc(0);
const events = [];

function send(msg) {
  const body = JSON.stringify({ jsonrpc: "2.0", ...msg });
  process.stdout.write(`Content-Length: ${Buffer.byteLength(body, "utf8")}\r\n\r\n${body}`);
}

function handle(msg) {
  switch (msg.method) {
    case "initialize":
      return setTimeout(() => send({ id: msg.id, result: { capabilities: {} } }), Number(process.env.FAKE_INIT_DELAY_MS ?? 0));
    case "textDocument/didOpen":
      if (process.env.FAKE_PROGRESS_MS && events.length === 0) {
        const token = "backgroundIndexProgress";
        setTimeout(() => send({ method: "$/progress", params: { token, value: { kind: "begin", title: "indexing" } } }), 20);
        setTimeout(() => send({ method: "$/progress", params: { token, value: { kind: "end" } } }), Number(process.env.FAKE_PROGRESS_MS));
      }
      return events.push({ event: "open", uri: msg.params.textDocument.uri, languageId: msg.params.textDocument.languageId });
    case "textDocument/didClose":
      return events.push({ event: "close", uri: msg.params.textDocument.uri });
    case "workspace/symbol":
      return send({ id: msg.id, result: events });
    case "shutdown":
      return send({ id: msg.id, result: null });
    case "exit":
      return process.exit(0);
    default:
      if (msg.id !== undefined) send({ id: msg.id, result: null });
  }
}

process.stdin.on("data", (data) => {
  buffer = Buffer.concat([buffer, data]);
  while (true) {
    const headerEnd = buffer.indexOf("\r\n\r\n");
    if (headerEnd === -1) return;
    const length = Number(/Content-Length:\s*(\d+)/i.exec(buffer.subarray(0, headerEnd).toString())?.[1]);
    const start = headerEnd + 4;
    if (buffer.length < start + length) return;
    const body = buffer.subarray(start, start + length).toString("utf8");
    buffer = buffer.subarray(start + length);
    handle(JSON.parse(body));
  }
});

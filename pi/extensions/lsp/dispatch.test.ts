#!/usr/bin/env tsx
/** Tool dispatch, file validation and by-name resolution against controlled server responses. */

import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { DEFAULT_MAX_BYTES, DEFAULT_MAX_LINES } from "@earendil-works/pi-coding-agent";
import lsp, { buildLspToolResult, rankSymbolMatches } from "./index.ts";
import { LspClient } from "./lsp-client.ts";
import { languages } from "./languages/index.ts";

let passed = 0;
let failed = 0;

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

function assertEqual(actual: unknown, expected: unknown, message: string) {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a !== e) throw new Error(`${message}: expected ${e}, got ${a}`);
}

async function test(name: string, fn: () => Promise<void> | void) {
  try {
    await fn();
    console.log(`ok - ${name}`);
    passed++;
  } catch (error) {
    console.log(`not ok - ${name}`);
    console.log(`  # ${error instanceof Error ? error.message : String(error)}`);
    failed++;
  }
}

function tree(files: Record<string, string>) {
  const dir = mkdtempSync(join(tmpdir(), "pi-lsp-dispatch-"));
  for (const [name, content] of Object.entries(files)) {
    mkdirSync(dirname(join(dir, name)), { recursive: true });
    writeFileSync(join(dir, name), content, "utf8");
  }
  return {
    dir,
    path: (name: string) => join(dir, name),
    uri: (name: string) => pathToFileURL(join(dir, name)).href,
    dispose: () => rmSync(dir, { recursive: true, force: true }),
  };
}

const range = (line: number, start = 0, end = start) => ({
  start: { line, character: start },
  end: { line, character: end },
});

type Responses = {
  workspaceSymbol?: unknown | Error;
  documentSymbols?: Record<string, unknown>;
  definition?: unknown;
  references?: unknown;
  rename?: unknown;
};

/** Duck-typed stand-in for LspClient that records calls and replays fixed responses. */
class FakeClient {
  opened = new Map<string, string | undefined>();
  openLog: Array<[string, string | undefined]> = [];
  calls: Array<[string, ...unknown[]]> = [];
  running = false;
  constructor(
    readonly language: string,
    readonly root: string,
    private responses: Responses,
  ) {}
  async start() {
    this.running = true;
  }
  async stop() {
    this.running = false;
  }
  isRunning() {
    return this.running;
  }
  async waitForIndexing() {}
  async waitForIndexingAfterOpen(graceMs: number) {
    this.calls.push(["waitForIndexingAfterOpen", graceMs]);
  }
  openDocumentCount() {
    return this.opened.size;
  }
  getOpenDocumentPaths() {
    return [...this.opened.keys()];
  }
  async ensureDocumentOpen(path: string, languageId?: string) {
    if (!this.opened.has(path) || (languageId && this.opened.get(path) !== languageId)) {
      this.opened.set(path, languageId);
      this.openLog.push([path, languageId]);
    }
    return pathToFileURL(path).href;
  }
  async refreshDocument(path: string) {
    this.calls.push(["refreshDocument", path]);
    return pathToFileURL(path).href;
  }
  async rename(path: string, line: number, character: number, newName: string) {
    this.calls.push(["rename", path, line, character, newName]);
    return this.responses.rename ?? null;
  }
  async workspaceSymbol(query: string) {
    this.calls.push(["workspaceSymbol", query]);
    const r = this.responses.workspaceSymbol;
    if (r instanceof Error) throw r;
    return r ?? [];
  }
  async documentSymbols(path: string) {
    this.calls.push(["documentSymbols", path]);
    return this.responses.documentSymbols?.[path] ?? [];
  }
  async definition(path: string, line: number, character: number) {
    this.calls.push(["definition", path, line, character]);
    return this.responses.definition ?? null;
  }
  async references(path: string, line: number, character: number) {
    this.calls.push(["references", path, line, character]);
    return this.responses.references ?? [];
  }
}

function harness(responses: Responses = {}) {
  const clients: FakeClient[] = [];
  let tool: any;
  let shutdown!: () => Promise<void>;
  lsp(
    {
      on: (event: string, handler: () => Promise<void>) => {
        if (event === "session_shutdown") shutdown = handler;
      },
      registerTool: (definition: unknown) => {
        tool = definition;
      },
      registerCommand: () => {},
    } as any,
    {
      createClient: (language, _plugin, root) => {
        const client = new FakeClient(language, root, responses);
        clients.push(client);
        return client as unknown as LspClient;
      },
    },
  );
  const run = async (cwd: string, params: Record<string, unknown>) => {
    const result = await tool.execute("id", params, undefined, undefined, { cwd });
    return result.content[0].text as string;
  };
  const call = (cwd: string, params: Record<string, unknown>) =>
    tool.execute("id", params, undefined, undefined, { cwd });
  return { clients, run, call, shutdown: () => shutdown() };
}

async function rejects(promise: Promise<unknown>, pattern: RegExp, message: string) {
  try {
    await promise;
  } catch (error) {
    const text = error instanceof Error ? error.message : String(error);
    assert(pattern.test(text), `${message}: unexpected error ${text}`);
    return;
  }
  throw new Error(`${message}: expected rejection`);
}

(async () => {
  await test("c and cpp requests share one clangd client and open a header with the requested ID", async () => {
    const t = tree({ "compile_commands.json": "[]", "src/a.h": "int a;\n", "src/a.c": "int a;\n" });
    try {
      const { clients, run } = harness();
      await run(t.dir, { language: "c", action: "definition", file: "src/a.h", line: 1, character: 5 });
      await run(t.dir, { language: "cpp", action: "definition", file: "src/a.h", line: 1, character: 5 });
      assertEqual(clients.length, 1, "client count");
      assertEqual(clients[0].root, t.dir, "root from compile_commands.json");
      assertEqual(
        clients[0].openLog.filter(([p]) => p === t.path("src/a.h")).map(([, id]) => id),
        ["c", "cpp"],
        "header document IDs",
      );
    } finally {
      t.dispose();
    }
  });

  await test("only a fresh clangd server waits for open-triggered indexing", async () => {
    const t = tree({ "compile_commands.json": "[]", "a.c": "", "b.c": "", "go.mod": "", "a.go": "" });
    try {
      const { clients, run } = harness();
      await run(t.dir, { language: "c", action: "definition", file: "a.c", line: 1, character: 1 });
      await run(t.dir, { language: "c", action: "definition", file: "b.c", line: 1, character: 1 });
      await run(t.dir, { language: "go", action: "definition", file: "a.go", line: 1, character: 1 });
      const waits = clients.map((c) => c.calls.filter(([name]) => name === "waitForIndexingAfterOpen"));
      assertEqual(waits, [[["waitForIndexingAfterOpen", 2000]], []], "waits per client");
    } finally {
      t.dispose();
    }
  });

  await test("a different workspace root gets its own clangd client", async () => {
    const t = tree({ "one/compile_flags.txt": "", "one/a.c": "", "two/compile_flags.txt": "", "two/b.cpp": "" });
    try {
      const { clients, run } = harness();
      await run(t.dir, { language: "c", action: "definition", file: "one/a.c", line: 1, character: 1 });
      await run(t.dir, { language: "cpp", action: "definition", file: "two/b.cpp", line: 1, character: 1 });
      assertEqual(clients.map((c) => c.root), [t.path("one"), t.path("two")], "roots");
    } finally {
      t.dispose();
    }
  });

  await test("incompatible files and unknown languages fail before any server starts", async () => {
    const t = tree({ "deps.edn": "{}", "Renderable.c": "int x;\n", "core.clj": "(ns core)\n" });
    try {
      const { clients, run } = harness();
      const cases: Array<[Record<string, unknown>, RegExp]> = [
        [{ language: "clojure", action: "diagnostics", file: "Renderable.c" }, /does not match language clojure/],
        [{ language: "clojure", action: "diagnostics", file: "@Renderable.c" }, /does not match language clojure/],
        [{ language: "c", action: "diagnostics", file: t.path("core.clj") }, /does not match language c /],
        [{ language: "cpp", action: "diagnostics", file: "./Renderable.c" }, /does not match language cpp/],
        [{ language: "cobol", action: "diagnostics", file: "core.clj" }, /Unsupported language: cobol/],
      ];
      for (const [params, pattern] of cases) {
        await rejects(run(t.dir, params), pattern, JSON.stringify(params));
      }
      assertEqual(clients.length, 0, "servers started");
    } finally {
      t.dispose();
    }
  });

  await test("relative and @-prefixed files resolve against the session cwd", async () => {
    const t = tree({ "go.mod": "module x\n", "pkg/a.go": "package pkg\n" });
    try {
      const { clients, run } = harness();
      await run(t.dir, { language: "go", action: "definition", file: "@pkg/a.go", line: 1, character: 1 });
      const call = clients[0].calls.find(([name]) => name === "definition");
      assertEqual(call?.[1], t.path("pkg/a.go"), "definition path");
      assertEqual(clients[0].openLog[0], [t.path("pkg/a.go"), "go"], "explicit document ID");
    } finally {
      t.dispose();
    }
  });

  await test("positional definition renders the definition body", async () => {
    const t = tree({ "go.mod": "", "a.go": "package a\n\nfunc Target() {\n}\n" });
    try {
      const { run } = harness({
        definition: [{ targetUri: t.uri("a.go"), targetRange: { start: { line: 2, character: 0 }, end: { line: 3, character: 1 } }, targetSelectionRange: range(2, 5) }],
      });
      const text = await run(t.dir, { language: "go", action: "definition", file: "a.go", line: 1, character: 1 });
      assert(text.startsWith(`${t.path("a.go")}:3`), text);
      assert(text.includes("func Target() {"), text);
    } finally {
      t.dispose();
    }
  });

  await test("query definition returns every distinct exact match in server order", async () => {
    const t = tree({
      "deps.edn": "{}",
      "src/grant.clj": "(ns grant)\n(defn create-client [] 1)\n",
      "src/oidc.clj": "(ns oidc)\n(defn create-client [] 2)\n",
      "src/supabase.clj": "(ns supabase)\n(defn create-client [] 3)\n",
    });
    try {
      const sym = (file: string, name = "create-client") => ({
        name,
        kind: 12,
        location: { uri: t.uri(file), range: range(1, 6, 19) },
      });
      const { run } = harness({
        workspaceSymbol: [
          sym("src/supabase.clj"),
          sym("src/grant.clj"),
          sym("src/grant.clj"),
          sym("src/oidc.clj"),
          sym("src/oidc.clj", "create-client-x"),
        ],
        documentSymbols: Object.fromEntries(
          ["src/grant.clj", "src/oidc.clj", "src/supabase.clj"].map((f) => [
            t.path(f),
            [{ name: "create-client", kind: 12, range: range(1, 0, 25), selectionRange: range(1, 6, 19) }],
          ]),
        ),
      });
      const text = await run(t.dir, { language: "clojure", action: "definition", query: "create-client" });
      assert(text.startsWith('3 definitions match "create-client" (exact name):'), text);
      const order = ["supabase", "grant", "oidc"].map((n) => text.indexOf(t.path(`src/${n}.clj`)));
      assert(order.every((i) => i > 0) && order[0] < order[1] && order[1] < order[2], `order ${order}`);
      assert(text.includes("(defn create-client [] 2)"), text);
      assert(!text.includes("Workspace symbol search found no match"), "workspace results need no fallback note");
    } finally {
      t.dispose();
    }
  });

  await test("ranking falls back from exact to case-insensitive to partial names", () => {
    const loc = (line: number) => ({ uri: "file:///x.c", range: range(line) });
    const syms = [
      { name: "current_index_max", kind: 13, location: loc(1) },
      { name: "Current_Index", kind: 13, location: loc(2) },
      { name: "current_index", kind: 8, containerName: "RenderableStatus", location: loc(3) },
      { name: "current_index", kind: 8, containerName: "LedEffectSet", location: loc(4) },
      { name: "unrelated", kind: 13, location: loc(5) },
    ];
    assertEqual(rankSymbolMatches(syms, "current_index")?.matches.map((s) => s.location.range.start.line), [3, 4], "exact");
    assertEqual(rankSymbolMatches(syms, "RenderableStatus::current_index")?.matches.map((s) => s.location.range.start.line), [3], "qualified");
    assertEqual(rankSymbolMatches(syms.slice(0, 2), "current_index")?.tier, "case-insensitive", "case tier");
    assertEqual(rankSymbolMatches(syms.slice(0, 1), "current_index")?.tier, "partial", "partial tier");
    assertEqual(rankSymbolMatches(syms.slice(4), "current_index"), null, "no match");
    assertEqual(rankSymbolMatches([{ name: "current_index", kind: 8 }], "current_index"), null, "unlocated");
  });

  await test("workspace-symbol failure ranks open documents and states the bounded coverage", async () => {
    const t = tree({
      "tsconfig.json": "{}",
      "a.ts": "export function target() {}\n",
      "b.ts": "export function target() {}\n",
    });
    try {
      const docSym = [{ name: "target", kind: 12, range: range(0, 0, 28), selectionRange: range(0, 16, 22) }];
      const { clients, run } = harness({
        workspaceSymbol: new Error("No Project."),
        documentSymbols: { [t.path("a.ts")]: docSym, [t.path("b.ts")]: docSym },
      });
      const text = await run(t.dir, { language: "typescript", action: "definition", query: "target" });
      assert(text.startsWith('2 definitions match "target" (exact name):'), text);
      assert(text.includes(t.path("a.ts")) && text.includes(t.path("b.ts")), text);
      assert(/searched 2 open document\(s\) \(scan limit 50 files, depth 5\)\. Other matches can exist\./.test(text), text);
      assertEqual(clients[0].openLog.map(([, id]) => id), ["typescript", "typescript"], "scan document IDs");
    } finally {
      t.dispose();
    }
  });

  await test("query references keep the single-target policy and name the other matches", async () => {
    const t = tree({ "compile_commands.json": "[]", "a.h": "int x;\n", "b.h": "int x;\n" });
    try {
      const sym = (file: string) => ({ name: "x", kind: 13, location: { uri: t.uri(file), range: range(0, 4, 5) } });
      const { clients, run } = harness({
        workspaceSymbol: [sym("b.h"), sym("a.h")],
        references: [{ uri: t.uri("b.h"), range: range(0, 4, 5) }],
      });
      const text = await run(t.dir, { language: "cpp", action: "references", query: "x" });
      const calls = clients.flatMap((c) => c.calls.filter(([name]) => name === "references"));
      assertEqual(calls, [["references", t.path("b.h"), 0, 4]], "reference requests");
      assert(text.includes(`References are for ${t.path("b.h")}:1; 1 other symbol(s) match.`), text);
      assertEqual(clients[0].opened.get(t.path("b.h")), "cpp", "header ID for cpp");
    } finally {
      t.dispose();
    }
  });

  await test("query references start at the symbol name when the server locates the whole form", async () => {
    const t = tree({ "deps.edn": "{}", "oidc.clj": "(ns oidc)\n(defn create-client [] 2)\n" });
    try {
      const { clients, run } = harness({
        workspaceSymbol: [{ name: "create-client", kind: 12, location: { uri: t.uri("oidc.clj"), range: range(1, 0, 25) } }],
        documentSymbols: {
          [t.path("oidc.clj")]: [
            { name: "oidc", kind: 3, range: range(0, 0, 9), selectionRange: range(0, 4, 8) },
            { name: "create-client", kind: 12, range: range(1, 0, 25), selectionRange: range(1, 6, 19) },
          ],
        },
      });
      await run(t.dir, { language: "clojure", action: "references", query: "create-client" });
      const calls = clients.flatMap((c) => c.calls.filter(([name]) => name === "references"));
      assertEqual(calls, [["references", t.path("oidc.clj"), 1, 6]], "reference position");
    } finally {
      t.dispose();
    }
  });

  await test("rename previews a plan; apply_rename applies it once; shutdown discards plans", async () => {
    const t = tree({ "go.mod": "module x\n", "a.go": "package a\n\nvar old = 1\n", "b.go": "package a\n\nvar y = old\n" });
    try {
      const { clients, run, call, shutdown } = harness({
        rename: {
          changes: {
            [t.uri("a.go")]: [{ range: range(2, 4, 7), newText: "neu" }],
            [t.uri("b.go")]: [{ range: range(2, 8, 11), newText: "neu" }],
          },
        },
      });
      const params = { language: "go", action: "rename", file: "a.go", line: 3, character: 5, new_name: "neu" };
      const preview = await call(t.dir, params);
      const text = preview.content[0].text as string;
      const id = /Rename plan ([0-9a-f]{8}):/.exec(text)?.[1];
      assert(id, text);
      assertEqual(preview.details.summary, `Rename plan ${id}: 2 edit(s) in 2 file(s); no file written.`, "preview summary");
      assert(preview.details.diff.includes("+3 var neu = 1"), "expanded preview keeps the diff");
      assertEqual(
        [readFileSync(t.path("a.go"), "utf8"), readFileSync(t.path("b.go"), "utf8")],
        ["package a\n\nvar old = 1\n", "package a\n\nvar y = old\n"],
        "preview wrote a file",
      );

      const applied = await call(t.dir, { language: "go", action: "apply_rename", plan_id: id });
      assertEqual(applied.details.summary, "Applied 2 edit(s) in 2 file(s).", "apply summary");
      assert(applied.details.diff.includes("-3 var old = 1"), "expanded apply keeps the diff");
      assertEqual(readFileSync(t.path("b.go"), "utf8"), "package a\n\nvar y = neu\n", "b.go applied");
      assertEqual(
        clients[0].calls.filter(([name]) => name === "refreshDocument").map(([, p]) => p).sort(),
        [t.path("a.go"), t.path("b.go")],
        "refreshed documents",
      );

      await rejects(call(t.dir, { language: "go", action: "apply_rename", plan_id: id }), /already used rename plan/, "second apply");
      await rejects(call(t.dir, { language: "go", action: "apply_rename" }), /'plan_id' parameter is required/, "missing id");

      writeFileSync(t.path("a.go"), "package a\n\nvar old = 1\n", "utf8");
      const second = await run(t.dir, params);
      const secondId = /Rename plan ([0-9a-f]{8}):/.exec(second)?.[1];
      assert(secondId && secondId !== id, `new plan id ${secondId}`);
      await shutdown();
      await rejects(call(t.dir, { language: "go", action: "apply_rename", plan_id: secondId }), /Unknown or already used/, "plan after shutdown");
      assertEqual(readFileSync(t.path("a.go"), "utf8"), "package a\n\nvar old = 1\n", "discarded plan wrote");
    } finally {
      t.dispose();
    }
  });

  await test("rename with no edits creates no plan", async () => {
    const t = tree({ "go.mod": "", "a.go": "package a\n" });
    try {
      const { run } = harness({ rename: { changes: {} } });
      const text = await run(t.dir, { language: "go", action: "rename", file: "a.go", line: 1, character: 1, new_name: "b" });
      assertEqual(text, "No rename changes generated.", "empty rename");
    } finally {
      t.dispose();
    }
  });

  await test("truncated output discloses the cut within the normal bounds", () => {
    const large = "line\n".repeat(DEFAULT_MAX_LINES + 50);
    const text = buildLspToolResult(large, "c", "definition", "s").content[0].text;
    assert(/\[Output truncated: \d+ of 2051 lines, \d+ of \d+ bytes shown\.\]$/.test(text), text.slice(-120));
    assert(text.split("\n").length <= DEFAULT_MAX_LINES, "line bound");
    const wide = "x".repeat(DEFAULT_MAX_BYTES / 4) + "\n";
    const byBytes = buildLspToolResult(wide.repeat(8), "c", "definition", "s").content[0].text;
    assert(byBytes.includes("[Output truncated:"), "byte truncation disclosed");
    assert(Buffer.byteLength(byBytes, "utf8") <= DEFAULT_MAX_BYTES, "byte bound");
    assertEqual(buildLspToolResult("short", "c", "definition", "s").content[0].text, "short", "untruncated");
  });

  await test("LspClient reopens a document when the explicit language ID changes", async () => {
    const t = tree({ "a.h": "int a;\n" });
    const server = fileURLToPath(new URL("./fixtures/fake-lsp-server.mjs", import.meta.url));
    const client = new LspClient(
      "c",
      [{ command: process.execPath, args: [server], rootUri: pathToFileURL(t.dir).href }],
      languages,
    );
    try {
      await client.start();
      await client.ensureDocumentOpen(t.path("a.h"), "c");
      await client.ensureDocumentOpen(t.path("a.h"), "c");
      await client.ensureDocumentOpen(t.path("a.h"));
      await client.ensureDocumentOpen(t.path("a.h"), "cpp");
      await client.refreshDocument(t.path("a.h"));
      const events = (await client.workspaceSymbol("")) as Array<{ event: string; languageId?: string }>;
      assertEqual(
        events.map((e) => `${e.event}${e.languageId ? `:${e.languageId}` : ""}`),
        ["open:c", "close", "open:cpp", "close", "open:cpp"],
        "server events",
      );
      assertEqual(client.getOpenDocumentPaths(), [t.path("a.h")], "open paths are file paths");
    } finally {
      await client.stop();
      t.dispose();
    }
  });

  await test("LspClient waits for indexing that begins after the first open, and only for the grace period otherwise", async () => {
    const t = tree({ "a.c": "" });
    const server = fileURLToPath(new URL("./fixtures/fake-lsp-server.mjs", import.meta.url));
    const start = async (env: Record<string, string>) => {
      const previous = process.env.FAKE_PROGRESS_MS;
      Object.assign(process.env, env);
      const tracker = languages.c.createIndexingTracker!();
      const client = new LspClient("c", [{ command: process.execPath, args: [server], rootUri: pathToFileURL(t.dir).href }], languages, tracker);
      await client.start();
      if (previous === undefined) delete process.env.FAKE_PROGRESS_MS;
      else process.env.FAKE_PROGRESS_MS = previous;
      return { client, tracker };
    };
    const indexing = await start({ FAKE_PROGRESS_MS: "300" });
    const idle = await start({});
    try {
      await indexing.client.ensureDocumentOpen(t.path("a.c"), "c");
      let t0 = Date.now();
      await indexing.client.waitForIndexingAfterOpen(2000);
      const waited = Date.now() - t0;
      assert(indexing.tracker.isDone(), "returned before the progress ended");
      assert(waited >= 200 && waited < 1500, `indexing wait ${waited} ms`);

      await idle.client.ensureDocumentOpen(t.path("a.c"), "c");
      t0 = Date.now();
      await idle.client.waitForIndexingAfterOpen(150);
      const graced = Date.now() - t0;
      assert(graced >= 140 && graced < 1000, `grace wait ${graced} ms`);
    } finally {
      await indexing.client.stop();
      await idle.client.stop();
      t.dispose();
    }
  });

  await test("a slow initialisation keeps the 120-second request allowance", async () => {
    // Client timers run 1000 times faster, so a 60 ms reply stands for 60 s and 400 ms for 400 s.
    const t = tree({});
    const server = fileURLToPath(new URL("./fixtures/fake-lsp-server.mjs", import.meta.url));
    const realSetTimeout = globalThis.setTimeout;
    const delays: number[] = [];
    const startWithDelay = async (delayMs: number) => {
      process.env.FAKE_INIT_DELAY_MS = String(delayMs);
      const client = new LspClient("c", [{ command: process.execPath, args: [server], rootUri: pathToFileURL(t.dir).href }], languages);
      (globalThis as any).setTimeout = (fn: () => void, ms = 0, ...rest: unknown[]) => {
        delays.push(ms);
        return realSetTimeout(fn, ms / 1000, ...rest);
      };
      try {
        await client.start();
        return { client, error: null as string | null };
      } catch (error) {
        return { client, error: error instanceof Error ? error.message : String(error) };
      } finally {
        globalThis.setTimeout = realSetTimeout;
        delete process.env.FAKE_INIT_DELAY_MS;
      }
    };
    const slow = await startWithDelay(60);
    const stalled = await startWithDelay(400);
    try {
      assertEqual(delays.slice(0, 1), [120000], "initialize request timeout");
      assert(slow.error === null && slow.client.isRunning(), `60 s initialisation failed: ${slow.error}`);
      assert(/LSP request \"initialize\" timed out after 120s/.test(stalled.error ?? ""), `400 s initialisation: ${stalled.error}`);
    } finally {
      await slow.client.stop();
      await stalled.client.stop();
      t.dispose();
    }
  });

  await test("LspClient falls back to its own language for an unforced shared extension", async () => {
    const t = tree({ "a.h": "" });
    const server = fileURLToPath(new URL("./fixtures/fake-lsp-server.mjs", import.meta.url));
    const client = new LspClient(
      "cpp",
      [{ command: process.execPath, args: [server], rootUri: pathToFileURL(t.dir).href }],
      languages,
    );
    try {
      await client.start();
      await client.ensureDocumentOpen(t.path("a.h"));
      assertEqual(client.openDocumentLanguageId(t.path("a.h")), "cpp", "default header ID");
    } finally {
      await client.stop();
      t.dispose();
    }
  });

  console.log(`\n# ${passed} passed, ${failed} failed`);
  if (failed > 0) process.exit(1);
})();

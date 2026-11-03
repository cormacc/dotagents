import assert from "node:assert/strict";
import test from "node:test";
import puppeteer from "puppeteer-core";
import register from "./index.ts";

function fixture() {
  const calls: string[] = [];
  function makeFrame(label: string, parent: any = null): any {
    return {
      detached: false,
      url: () => `https://${label}.example/`,
      title: async () => label,
      goto: async (url: string) => { calls.push(`${label}:goto:${url}`); },
      evaluate: async () => { calls.push(`${label}:evaluate`); return label; },
      $eval: async () => { calls.push(`${label}:inspect`); return label; },
      waitForSelector: async () => {},
      parentFrame: () => parent,
    };
  }
  function makePage(tabId: string): any {
    const main = makeFrame(tabId);
    const child = makeFrame(`${tabId}-child`, main);
    return {
      tabId, main, child, closed: false,
      url: main.url,
      title: main.title,
      mainFrame: () => main,
      frames: () => [main, child],
      isClosed() { return this.closed; },
      cookies: async (url: string) => { calls.push(`${tabId}:cookies:${url}`); return []; },
      createCDPSession: async () => ({
        send: async (method: string) => method === "Target.getTargetInfo"
          ? { targetInfo: { targetId: tabId } }
          : { windowId: 1 },
        detach: async () => {},
      }),
    };
  }
  const first = makePage("first");
  const other = makePage("other");
  let pages = [first, other];
  const browser = {
    version: async () => "test",
    pages: async () => pages,
    newPage: async () => { const page = makePage("created"); pages.push(page); return page; },
    disconnect: async () => {},
  };
  const original = puppeteer.connect;
  puppeteer.connect = async () => browser as any;
  const tools = new Map<string, any>();
  let shutdown: () => Promise<void>;
  register({
    registerTool: (tool: any) => tools.set(tool.name, tool),
    on: (event: string, handler: any) => { if (event === "session_shutdown") shutdown = handler; },
  } as any);
  return {
    first, other, calls, tools, makeFrame,
    setPages: (value: any[]) => { pages = value; },
    call: async (name: string, params = {}) => tools.get(name).execute("test", params),
    close: async () => { await shutdown!(); puppeteer.connect = original; },
  };
}

function value(result: any): string {
  return result.content[0].text.split("\n").slice(1).join("\n");
}

test("navigation returns a stable target and subsequent calls ignore page ordering and new tabs", async () => {
  const f = fixture();
  try {
    const nav = await f.call("browser_nav", { url: "https://chosen.example/", tabId: "first" });
    assert.equal(nav.details.target.tabId, "first");
    assert.ok(nav.details.target.frameId);
    assert.deepEqual(f.calls, ["first:goto:https://chosen.example/"]);
    for (const order of [[f.other, f.first], [f.first, f.other]]) {
      f.setPages(order);
      const result = await f.call("browser_eval", { code: "document.title" });
      assert.equal(value(result), "first");
      assert.deepEqual(result.details.target, nav.details.target);
    }
  } finally { await f.close(); }
});

test("initial ambiguity fails without URLs; a sole tab can be selected implicitly", async () => {
  const f = fixture();
  try {
    await assert.rejects(f.call("browser_eval", { code: "document.title" }), /No tab selected/);
    assert.deepEqual(f.calls, []);
    f.setPages([f.first]);
    const chosen = await f.call("browser_eval", { code: "document.title" });
    assert.equal(chosen.details.target.tabId, "first");
    f.setPages([f.first, f.other]);
    assert.equal(value(await f.call("browser_eval", { code: "document.title" })), "first");
  } finally { await f.close(); }
});

test("closed and invalid tab targets fail without fallback, including after enumeration", async () => {
  const f = fixture();
  try {
    await f.call("browser_eval", { tabId: "first", code: "document.title" });
    f.first.closed = true;
    await f.call("browser_tabs");
    const before = [...f.calls];
    await assert.rejects(f.call("browser_eval", { code: "document.title" }), /Tab target is closed or missing: first/);
    await assert.rejects(f.call("browser_nav", { tabId: "missing", url: "https://write.example/" }), /Tab target is closed or missing: missing/);
    assert.deepEqual(f.calls, before);
    assert.equal(value(await f.call("browser_eval", { tabId: "other", code: "document.title" })), "other");
  } finally { await f.close(); }
});

test("tab enumeration omits URLs by default and exposes frames only for the chosen tab", async () => {
  const f = fixture();
  try {
    const all = await f.call("browser_tabs");
    assert.equal(all.details.windowMap[1].length, 2);
    assert.match(JSON.stringify(all), /first/);
    assert.doesNotMatch(JSON.stringify(all), /https:\/\//);
    assert.equal(all.details.target, null);
    await assert.rejects(f.call("browser_eval", { code: "document.title" }), /No tab selected/);
    const scoped = await f.call("browser_tabs", { tabId: "first" });
    assert.equal(scoped.details.windowMap[1].length, 1);
    assert.equal(scoped.details.frames.length, 2);
    assert.match(JSON.stringify(scoped), /https:\/\/first-child.example/);
    assert.doesNotMatch(JSON.stringify(scoped), /other/);
    assert.equal(scoped.details.frames[0].frameId, scoped.details.target.frameId);
    assert.equal(scoped.details.frames[1].parentFrameId, scoped.details.target.frameId);
    const again = await f.call("browser_tabs", { tabId: "first" });
    assert.deepEqual(again.details, scoped.details);
    assert.deepEqual((await f.call("browser_eval", { code: "document.title" })).details.target, scoped.details.target);
  } finally { await f.close(); }
});

test("all DOM actions and cookies use the selected frame and explicit tabId resets to main", async () => {
  const f = fixture();
  try {
    const scoped = await f.call("browser_tabs", { tabId: "first" });
    const frameId = scoped.details.frames[1].frameId;
    const selected = { tabId: "first", frameId };
    assert.equal(value(await f.call("browser_eval", { ...selected, code: "document.title" })), "first-child");
    for (const [tool, params] of [
      ["browser_inspect", { selector: "h1" }],
      ["browser_pick", { message: "Pick" }],
      ["browser_cookies", {}],
      ["browser_nav", { url: "https://child-next.example/" }],
    ] as const) {
      assert.deepEqual((await f.call(tool, params)).details.target, selected);
    }
    assert.ok(f.calls.includes("first-child:inspect"));
    assert.ok(f.calls.includes("first:cookies:https://first-child.example/"));
    assert.ok(f.calls.includes("first-child:goto:https://child-next.example/"));
    assert.equal(value(await f.call("browser_eval", { tabId: "first", code: "document.title" })), "first");
    assert.equal(value(await f.call("browser_eval", { code: "document.title" })), "first");
  } finally { await f.close(); }
});

test("invalid, wrong-tab and detached frame handles never bind to another frame", async () => {
  const f = fixture();
  try {
    const scoped = await f.call("browser_tabs", { tabId: "first" });
    const frameId = scoped.details.frames[1].frameId;
    await f.call("browser_eval", { frameId, code: "document.title" });
    const before = [...f.calls];
    await assert.rejects(f.call("browser_eval", { tabId: "other", frameId, code: "document.title" }), /Frame target is detached or missing/);
    await assert.rejects(f.call("browser_eval", { frameId: "invalid", code: "document.title" }), /Frame target is detached or missing/);
    f.first.child.detached = true;
    await assert.rejects(f.call("browser_eval", { code: "document.title" }), /Frame target is detached or missing/);
    f.first.child = f.makeFrame("first-child", f.first.main);
    f.first.frames = () => [f.first.main, f.first.child];
    await assert.rejects(f.call("browser_eval", { code: "document.title" }), /Frame target is detached or missing/);
    assert.deepEqual(f.calls, before);
    assert.equal(value(await f.call("browser_eval", { tabId: "first", code: "document.title" })), "first");
  } finally { await f.close(); }
});

test("newTab selects the new main frame and rejects conflicting target IDs before creation", async () => {
  const f = fixture();
  try {
    await assert.rejects(f.call("browser_nav", { newTab: true, tabId: "first", url: "https://new.example/" }), /newTab cannot be combined/);
    await assert.rejects(f.call("browser_nav", { newTab: true, frameId: "invalid", url: "https://new.example/" }), /newTab cannot be combined/);
    const nav = await f.call("browser_nav", { newTab: true, url: "https://new.example/" });
    assert.equal(nav.details.target.tabId, "created");
    assert.deepEqual(f.calls, ["created:goto:https://new.example/"]);
    assert.equal(value(await f.call("browser_eval", { code: "document.title" })), "created");
  } finally { await f.close(); }
});

test("every browser tool declares sequential execution and targeting parameters", async () => {
  const f = fixture();
  try {
    assert.equal(f.tools.size, 7);
    for (const tool of f.tools.values()) {
      assert.equal(tool.executionMode, "sequential", tool.name);
      assert.ok(tool.parameters.properties.tabId, tool.name);
      if (tool.name !== "browser_tabs") assert.ok(tool.parameters.properties.frameId, tool.name);
    }
  } finally { await f.close(); }
});

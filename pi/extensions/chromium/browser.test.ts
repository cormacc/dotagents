import assert from "node:assert/strict";
import test from "node:test";
import { createServer } from "node:http";
import { once } from "node:events";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import puppeteer from "puppeteer-core";
import register from "./index.ts";

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../../..");

test("real CDP: cross-origin iframe routing, screenshots, picking, cookies and stale targets", { timeout: 60000 }, async (t) => {
  assert.ok(process.env.CHROMIUM, "Set CHROMIUM to a Chrome/Chromium executable");
  await mkdir(join(root, ".tmp"), { recursive: true });
  const work = await mkdtemp(join(root, ".tmp/chromium-browser-"));
  const previousTmp = process.env.TMPDIR;
  process.env.TMPDIR = work;
  const server = createServer((req, res) => {
    res.setHeader("Content-Type", "text/html");
    if (req.url?.startsWith("/child")) {
      res.end(`<title>Child frame</title><h1 id="child">Cross-origin content</h1><button id="pick-me">Pick child</button>
<section id="text-fixture" style="position:absolute;top:400px"><div>Visible first</div><script type="application/json">{"secret":"script data"}</script><style>.unused { color: red; }</style><span hidden>Hidden attribute</span><span style="display:none">Hidden display</span><span style="visibility:hidden">Hidden visibility</span><div>Visible second</div></section>
<div id="hidden-root" style="display:none"><span id="hidden-child">Hidden root data</span></div>
<div id="contents-root" style="display:contents"><span>Rendered contents</span><span hidden>Hidden contents</span></div><div id="large" style="position:absolute;top:600px">Large fixture</div>`);
    } else {
      const port = (server.address() as { port: number }).port;
      res.end(`<title>Parent tab</title><h1>Parent content</h1><iframe style="width:600px;height:300px" src="http://localhost:${port}/child"></iframe>`);
    }
  });
  const originalConnect = puppeteer.connect;
  let owner: Awaited<ReturnType<typeof puppeteer.launch>> | undefined;
  let shutdown: (() => Promise<void>) | undefined;
  try {
    server.listen(0);
    await once(server, "listening");
    const port = (server.address() as { port: number }).port;
    const mainUrl = `http://127.0.0.1:${port}/`;
    const childUrl = `http://localhost:${port}/child`;
    owner = await puppeteer.launch({
      executablePath: process.env.CHROMIUM,
      headless: true,
      userDataDir: join(work, "profile"),
      args: ["--no-sandbox", "--site-per-process", "--no-proxy-server"],
    });
    puppeteer.connect = () => originalConnect.call(puppeteer, { browserWSEndpoint: owner!.wsEndpoint(), defaultViewport: null });
    const tools = new Map<string, any>();
    register({
      registerTool: (tool: any) => tools.set(tool.name, tool),
      on: (event: string, handler: any) => { if (event === "session_shutdown") shutdown = handler; },
    } as any);
    const call = (name: string, params: Record<string, unknown> = {}) => tools.get(name).execute("test", params);
    const nav = await call("browser_nav", { url: mainUrl, newTab: true });
    const mainTarget = nav.details.target;
    const page = (await owner.pages()).find(page => page.url() === mainUrl)!;
    assert.ok(page);
    await page.waitForFrame(frame => frame.url() === childUrl);
    const session = await page.createCDPSession();
    try {
      const { targetInfos } = await session.send("Target.getTargets");
      assert.ok(targetInfos.some(target => target.type === "iframe" && target.url === childUrl), "fixture has an out-of-process cross-origin iframe");
    } finally { await session.detach(); }

    const listed = await call("browser_tabs", { tabId: mainTarget.tabId });
    const child = listed.details.frames.find((frame: any) => frame.url === childUrl);
    assert.ok(child && !child.main);
    const childTarget = { tabId: mainTarget.tabId, frameId: child.frameId };
    const inspected = await call("browser_inspect", { ...childTarget, selector: "#child" });
    assert.equal(inspected.details.result, "Cross-origin content");
    await t.test("inspection defaults to rendered text without script, style or hidden sibling data", async () => {
      for (const action of [undefined, "text"]) {
        const result = await call("browser_inspect", { selector: "#text-fixture", action });
        assert.equal(result.details.result, "Visible first\nVisible second");
        assert.equal(result.content[0].text.split("\n").slice(1).join("\n"), result.details.result);
        assert.deepEqual(result.details.target, childTarget);
      }
      for (const selector of ["#hidden-root", "#hidden-child", "#text-fixture script", "#text-fixture style"]) {
        assert.equal((await call("browser_inspect", { selector, waitFor: false })).details.result, "", selector);
      }
      assert.equal((await call("browser_inspect", { selector: "#contents-root", waitFor: false })).details.result, "Rendered contents");
    });
    await t.test("raw inspection explicitly includes script, style and hidden text", async () => {
      const raw = await call("browser_inspect", { selector: "#text-fixture", action: "raw" });
      assert.equal(raw.details.result, 'Visible first{"secret":"script data"}.unused { color: red; }Hidden attributeHidden displayHidden visibilityVisible second');
      assert.equal(raw.details.action, "raw");
      assert.equal(raw.details.truncated, false);
      assert.equal((await call("browser_inspect", { selector: "#hidden-root", action: "raw", waitFor: false })).details.result, "Hidden root data");
    });
    await t.test("inspection retains string results and state actions", async () => {
      for (const [params, expected] of [
        [{ selector: "#child", action: "html" }, "Cross-origin content"],
        [{ selector: "#child", action: "attr", attribute: "id" }, "child"],
        [{ selector: "#child", action: "attr", attribute: "missing" }, "(null)"],
        [{ selector: "#text-fixture > div", action: "count" }, "2"],
        [{ selector: "#child", action: "exists" }, "true"],
        [{ selector: "#missing", action: "exists" }, "false"],
        [{ selector: "#child", action: "visible" }, "true"],
        [{ selector: "#hidden-root", action: "visible", waitFor: false }, "false"],
      ] as const) {
        const result = await call("browser_inspect", params);
        assert.equal(result.details.result, expected);
        assert.equal(result.details.truncated, false);
      }
      await assert.rejects(call("browser_inspect", { selector: "#child", action: "attr" }), /requires the "attribute" parameter/);
      await assert.rejects(call("browser_inspect", { selector: "#missing", waitFor: false }), /failed to find element/);
    });
    await t.test("inspection bounds rendered, raw, HTML and attribute strings without retaining full results", async () => {
      const frame = page.frames().find(frame => frame.url() === childUrl)!;
      for (const length of [12001, 11999, 12000, 24000]) {
        await frame.evaluate((length) => {
          const element = document.querySelector("#large")!;
          element.textContent = "X".repeat(length);
          element.setAttribute("data-large", "X".repeat(length));
        }, length);
        for (const action of ["text", "raw", "html", "attr"]) {
          const result = await call("browser_inspect", { selector: "#large", action, attribute: "data-large" });
          assert.equal(result.details.result.length, Math.min(length, 12000), `${action}: ${length}`);
          assert.equal(result.details.truncated, length > 12000, `${action}: ${length}`);
          if (length <= 12000) assert.equal(result.details.result, "X".repeat(length));
          else {
            assert.match(result.details.result, /^X+\n\[truncated: result limited to 12000 characters\]$/);
            assert.ok(JSON.stringify(result.details).length < 12500, "details must not retain an unbounded copy");
          }
          assert.equal(result.content[0].text.split("\n").slice(1).join("\n"), result.details.result);
          assert.deepEqual(result.details.target, childTarget);
        }
      }
    });
    const evaluated = await call("browser_eval", { code: "document.title" });
    assert.match(evaluated.content[0].text, /\nChild frame$/);
    assert.deepEqual(evaluated.details.target, childTarget);

    await page.setCookie(
      { name: "parentCookie", value: "parent", url: mainUrl },
      { name: "childCookie", value: "child", url: childUrl },
    );
    const cookies = await call("browser_cookies");
    assert.match(cookies.content[0].text, /childCookie/);
    assert.doesNotMatch(cookies.content[0].text, /parentCookie/);

    const shot = await call("browser_screenshot", { waitFor: "#child" });
    assert.deepEqual(shot.details.target, childTarget);
    assert.equal(shot.content[1].mimeType, "image/png");
    assert.ok(Buffer.from(shot.content[1].data, "base64").length > 100);
    await writeFile(join(work, "frame.png"), Buffer.from(shot.content[1].data, "base64"));
    console.log(`Frame screenshot: ${join(work, "frame.png")}`);

    const childFrame = page.frames().find(frame => frame.url() === childUrl)!;
    // tsx adds this helper to named functions serialised by Puppeteer.
    // Keep this transpiler workaround inside the test browser.
    await childFrame.evaluate("globalThis.__name = value => value");
    const picking = call("browser_pick", { message: "Select child" });
    await childFrame.waitForFunction(() => document.body.innerText.includes("Select child (0 selected"));
    await childFrame.click("#pick-me");
    const picked = await picking;
    assert.deepEqual(picked.details.target, childTarget);
    assert.equal(picked.details.result.id, "pick-me");

    const childNav = await call("browser_nav", { url: `${childUrl}?next` });
    assert.deepEqual(childNav.details.target, childTarget);
    assert.equal(page.url(), mainUrl);
    assert.equal((await call("browser_inspect", { selector: "#child" })).details.result, "Cross-origin content");

    const swapped = await call("browser_nav", { url: `http://127.0.0.2:${port}/child` });
    assert.deepEqual(swapped.details.target, childTarget);
    assert.equal((await call("browser_inspect", { selector: "#child" })).details.result, "Cross-origin content");

    const unrelated = await owner.newPage();
    await unrelated.goto("data:text/html,<title>Unrelated secret</title>");
    const all = await call("browser_tabs");
    assert.ok(Object.values(all.details.windowMap).flat().length >= 3);
    assert.doesNotMatch(JSON.stringify(all), /data:text\/html|127\.0\.0\.1|localhost:/);
    assert.deepEqual((await call("browser_eval", { code: "document.title" })).details.target, childTarget);

    await page.evaluate(() => document.querySelector("iframe")!.remove());
    await assert.rejects(call("browser_eval", { code: "document.title" }), /Frame target is detached or missing/);
    assert.match((await call("browser_eval", { tabId: mainTarget.tabId, code: "document.title" })).content[0].text, /\nParent tab$/);
    await page.close();
    await assert.rejects(call("browser_eval", { code: "document.title" }), /Tab target is closed or missing/);
    console.log("Verified OOPIF actions and closed/detached target rejection on disposable Chromium.");
  } finally {
    puppeteer.connect = originalConnect;
    await shutdown?.();
    await owner?.close();
    await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
    if (previousTmp === undefined) delete process.env.TMPDIR;
    else process.env.TMPDIR = previousTmp;
    // Keep the screenshot for visual verification; discard the disposable profile.
    await rm(join(work, "profile"), { recursive: true, force: true });
  }
});

import type { ExtensionAPI } from "@mariozechner/pi-coding-agent";
import { Type } from "@sinclair/typebox";
import { StringEnum } from "@mariozechner/pi-ai";
import { Text } from "@mariozechner/pi-tui";
import puppeteer, { type Browser, type Page } from "puppeteer-core";
import { readFile } from "node:fs/promises";

const CDP_URL = "http://localhost:9222";
const CONNECT_TIMEOUT = 5000;

// ---------------------------------------------------------------------------
// Persistent connection state (lives in extension closure)
// ---------------------------------------------------------------------------

let browser: Browser | null = null;

async function ensureBrowser(): Promise<Browser> {
  // Check if existing connection is still alive
  if (browser) {
    try {
      // Quick liveness check
      await browser.version();
      return browser;
    } catch {
      browser = null;
    }
  }

  browser = await Promise.race([
    puppeteer.connect({ browserURL: CDP_URL, defaultViewport: null }),
    new Promise<never>((_, reject) =>
      setTimeout(() => reject(new Error("Connection timeout")), CONNECT_TIMEOUT)
    ),
  ]);

  return browser;
}

async function getActivePage(): Promise<Page> {
  const b = await ensureBrowser();
  const pages = await b.pages();
  const page = pages.at(-1);
  if (!page) throw new Error("No active tab found");
  return page;
}

async function getAllPages(): Promise<Page[]> {
  const b = await ensureBrowser();
  return b.pages();
}

// ---------------------------------------------------------------------------
// Extension entry point
// ---------------------------------------------------------------------------

export default function (pi: ExtensionAPI) {
  // Clean up on shutdown
  pi.on("session_shutdown", async () => {
    if (browser) {
      try {
        await browser.disconnect();
      } catch {}
      browser = null;
    }
  });

  // ------------------------------------------------------------------
  // browser_nav — Navigate to a URL
  // ------------------------------------------------------------------
  pi.registerTool({
    name: "browser_nav",
    label: "Browser Navigate",
    description:
      "Navigate the browser to a URL. Connects to Chromium on localhost:9222 (must be started with --remote-debugging-port=9222).",
    promptSnippet: "Navigate the browser to a URL, optionally in a new tab",
    parameters: Type.Object({
      url: Type.String({ description: "URL to navigate to" }),
      newTab: Type.Optional(
        Type.Boolean({ description: "Open in a new tab instead of reusing the active one" })
      ),
    }),
    async execute(_toolCallId, params, signal, onUpdate) {
      onUpdate?.({ content: [{ type: "text", text: `Navigating to ${params.url}...` }], details: {} });

      const b = await ensureBrowser();
      let page: Page;

      if (params.newTab) {
        page = await b.newPage();
      } else {
        page = await getActivePage();
      }

      await page.goto(params.url, { waitUntil: "domcontentloaded" });
      const title = await page.title();

      return {
        content: [{ type: "text", text: `${params.newTab ? "Opened" : "Navigated to"}: ${title}\nURL: ${page.url()}` }],
        details: { title, url: page.url() },
      };
    },
    renderCall(args, theme) {
      let text = theme.fg("toolTitle", theme.bold("browser_nav "));
      text += theme.fg("muted", args.url);
      if (args.newTab) text += theme.fg("dim", " (new tab)");
      return new Text(text, 0, 0);
    },
  });

  // ------------------------------------------------------------------
  // browser_eval — Evaluate JavaScript in the active tab
  // ------------------------------------------------------------------
  pi.registerTool({
    name: "browser_eval",
    label: "Browser Eval",
    description:
      "Evaluate JavaScript in the active browser tab. Code runs in an async context. Return values are serialized as text.",
    promptSnippet: "Evaluate JavaScript in the active browser tab",
    parameters: Type.Object({
      code: Type.String({ description: "JavaScript code to evaluate. Runs in async context." }),
    }),
    async execute(_toolCallId, params) {
      const page = await getActivePage();

      const result = await page.evaluate((code) => {
        const AsyncFunction = Object.getPrototypeOf(async function(){}).constructor;
        return new AsyncFunction(`return (${code})`)();
      }, params.code);

      let text: string;
      if (result === undefined) {
        text = "undefined";
      } else if (result === null) {
        text = "null";
      } else if (typeof result === "object") {
        text = JSON.stringify(result, null, 2);
      } else {
        text = String(result);
      }

      return {
        content: [{ type: "text", text }],
        details: {},
      };
    },
    renderCall(args, theme) {
      let text = theme.fg("toolTitle", theme.bold("browser_eval "));
      const code = args.code.length > 80 ? args.code.slice(0, 77) + "..." : args.code;
      text += theme.fg("muted", code);
      return new Text(text, 0, 0);
    },
  });

  // ------------------------------------------------------------------
  // browser_tabs — List open tabs grouped by window
  // ------------------------------------------------------------------
  pi.registerTool({
    name: "browser_tabs",
    label: "Browser Tabs",
    description: "List all open browser tabs, grouped by window.",
    promptSnippet: "List all browser tabs grouped by window",
    parameters: Type.Object({}),
    async execute() {
      const pages = await getAllPages();

      const windowMap: Record<number, Array<{ title: string; url: string }>> = {};

      for (const page of pages) {
        const session = await page.createCDPSession();
        try {
          const { windowId } = await session.send("Browser.getWindowForTarget") as { windowId: number };
          if (!windowMap[windowId]) windowMap[windowId] = [];
          windowMap[windowId].push({ title: await page.title(), url: page.url() });
        } finally {
          await session.detach();
        }
      }

      const lines: string[] = [];
      let winNum = 1;
      for (const [winId, tabs] of Object.entries(windowMap)) {
        lines.push(`Window ${winNum} (id: ${winId}):`);
        tabs.forEach((t, i) => {
          lines.push(`  ${i}: ${t.title}`);
          lines.push(`     ${t.url}`);
        });
        winNum++;
      }

      return {
        content: [{ type: "text", text: lines.join("\n") }],
        details: { windowMap },
      };
    },
  });

  // ------------------------------------------------------------------
  // browser_screenshot — Capture current viewport
  // ------------------------------------------------------------------
  pi.registerTool({
    name: "browser_screenshot",
    label: "Browser Screenshot",
    description:
      "Take a screenshot of the current browser viewport. " +
      "Optionally navigates to a URL first and waits for a CSS selector. " +
      "Returns the screenshot as an inline image.",
    promptSnippet: "Screenshot the current browser viewport",
    promptGuidelines: [
      "After making UI changes, use browser_screenshot to verify the rendered result visually.",
      "Use the waitFor parameter with a CSS selector to wait for async content to load.",
    ],
    parameters: Type.Object({
      url: Type.Optional(
        Type.String({ description: "URL to navigate to before taking the screenshot." }),
      ),
      waitFor: Type.Optional(
        Type.String({ description: "CSS selector to wait for before taking the screenshot." }),
      ),
      waitTimeout: Type.Optional(
        Type.Number({ description: "Timeout in seconds for waitFor selector. Default: 10." }),
      ),
    }),
    async execute(_toolCallId, params, signal, onUpdate) {
      const page = await getActivePage();

      // Navigate if URL given
      if (params.url) {
        onUpdate?.({ content: [{ type: "text", text: `Navigating to ${params.url}...` }] });
        await page.goto(params.url, { waitUntil: "domcontentloaded" });
      }

      // Wait for selector if specified
      if (params.waitFor) {
        const timeout = (params.waitTimeout || 10) * 1000;
        await page.waitForSelector(params.waitFor, { visible: true, timeout });
      }

      const { tmpdir } = await import("node:os");
      const { join } = await import("node:path");

      const timestamp = new Date().toISOString().replace(/[:.]/g, "-");
      const filepath = join(tmpdir(), `screenshot-${timestamp}.png`);

      await page.screenshot({ path: filepath });

      // Read file as base64 for inline image
      const imageData = await readFile(filepath);
      const base64 = imageData.toString("base64");

      const title = await page.title();
      const url = page.url();

      return {
        content: [
          { type: "text", text: `Screenshot of "${title}" at ${url}\nSaved to: ${filepath}` },
          { type: "image", data: base64, mimeType: "image/png" },
        ],
        details: { url, title, path: filepath },
      };
    },
    renderCall(args, theme) {
      let text = theme.fg("toolTitle", theme.bold("browser_screenshot"));
      if (args.url) text += " " + theme.fg("muted", args.url);
      if (args.waitFor) text += theme.fg("dim", ` (wait: ${args.waitFor})`);
      return new Text(text, 0, 0);
    },
    renderResult(result, { expanded, isPartial }, theme) {
      if (isPartial) {
        return new Text(theme.fg("warning", "Capturing screenshot..."), 0, 0);
      }
      const details = result.details as { url?: string; title?: string; path?: string } | undefined;
      let text = theme.fg("success", "Screenshot captured");
      if (details?.title) text += theme.fg("dim", ` - "${details.title}"`);
      if (expanded && details?.path) text += "\n" + theme.fg("dim", `  Saved: ${details.path}`);
      return new Text(text, 0, 0);
    },
  });

  // ------------------------------------------------------------------
  // browser_inspect — Extract text/attributes from elements
  // ------------------------------------------------------------------
  pi.registerTool({
    name: "browser_inspect",
    label: "Browser Inspect",
    description:
      "Extract text content, attributes, or element state from the current page " +
      "using CSS selectors. Navigates to URL first if provided. " +
      "Returns structured data about matched elements.",
    promptSnippet: "Extract text/attributes from browser page elements by CSS selector",
    promptGuidelines: [
      "Use browser_inspect to verify rendered text content, element visibility, and DOM state.",
      "Prefer browser_inspect over browser_eval for simple element queries.",
    ],
    parameters: Type.Object({
      selector: Type.String({
        description: 'CSS selector to query, e.g. "h1", ".patient-name", "[data-testid=\'status\']"',
      }),
      action: Type.Optional(
        StringEnum(["text", "html", "attr", "count", "visible", "exists"] as const),
      ),
      attribute: Type.Optional(
        Type.String({ description: 'Attribute name to extract when action is "attr".' }),
      ),
      url: Type.Optional(
        Type.String({ description: "URL to navigate to first." }),
      ),
      waitFor: Type.Optional(
        Type.Boolean({ description: "Wait for the selector to be visible before inspecting. Default: true." }),
      ),
    }),
    async execute(_toolCallId, params) {
      const page = await getActivePage();

      // Navigate if URL given
      if (params.url) {
        await page.goto(params.url, { waitUntil: "domcontentloaded" });
      }

      const sel = params.selector;
      const shouldWait = params.waitFor !== false;
      const action = params.action || "text";

      // Wait for element if requested
      if (shouldWait && action !== "exists") {
        try {
          await page.waitForSelector(sel, { visible: true, timeout: 10000 });
        } catch {
          if (action !== "count") {
            throw new Error(`Element not found or not visible: "${sel}"`);
          }
        }
      }

      let result: string;
      switch (action) {
        case "text":
          result = await page.$eval(sel, (el) => el.textContent?.trim() ?? "");
          break;
        case "html":
          result = await page.$eval(sel, (el) => el.innerHTML);
          break;
        case "attr":
          if (!params.attribute) throw new Error('The "attr" action requires the "attribute" parameter.');
          result = await page.$eval(sel, (el, attr) => el.getAttribute(attr) ?? "(null)", params.attribute);
          break;
        case "count":
          result = String(await page.$$eval(sel, (els) => els.length));
          break;
        case "visible":
          result = String(await page.$eval(sel, (el) => {
            const style = window.getComputedStyle(el);
            return style.display !== "none" && style.visibility !== "hidden" && style.opacity !== "0";
          }).catch(() => false));
          break;
        case "exists":
          result = String((await page.$(sel)) !== null);
          break;
        default:
          result = await page.$eval(sel, (el) => el.textContent?.trim() ?? "");
      }

      return {
        content: [{ type: "text", text: result }],
        details: { selector: sel, action, result },
      };
    },
    renderCall(args, theme) {
      let text = theme.fg("toolTitle", theme.bold("browser_inspect "));
      text += theme.fg("muted", args.selector);
      if (args.action && args.action !== "text") text += theme.fg("dim", ` [${args.action}]`);
      return new Text(text, 0, 0);
    },
  });

  // ------------------------------------------------------------------
  // browser_cookies — Show cookies for the active tab
  // ------------------------------------------------------------------
  pi.registerTool({
    name: "browser_cookies",
    label: "Browser Cookies",
    description: "Display all cookies for the current browser tab, including domain, path, httpOnly and secure flags.",
    promptSnippet: "Show cookies for the active browser tab",
    parameters: Type.Object({}),
    async execute() {
      const page = await getActivePage();
      const cookies = await page.cookies();

      const lines = cookies.map(
        (c) =>
          `${c.name}: ${c.value}\n  domain: ${c.domain}  path: ${c.path}  httpOnly: ${c.httpOnly}  secure: ${c.secure}`
      );

      return {
        content: [{ type: "text", text: lines.join("\n\n") || "(no cookies)" }],
        details: { count: cookies.length },
      };
    },
  });

  // ------------------------------------------------------------------
  // browser_pick — Interactive element picker
  // ------------------------------------------------------------------
  pi.registerTool({
    name: "browser_pick",
    label: "Browser Pick",
    description:
      "Launch an interactive element picker in the browser. The user clicks elements to select them (Cmd/Ctrl+Click for multiple, Enter to finish, Esc to cancel). Returns CSS-relevant info and HTML for the selected elements.",
    promptSnippet: "Let the user pick DOM elements interactively in the browser",
    parameters: Type.Object({
      message: Type.String({ description: "Prompt message shown to the user in the browser overlay" }),
    }),
    async execute(_toolCallId, params) {
      const page = await getActivePage();

      // Inject the pick() helper
      await page.evaluate(() => {
        if (!window["pick"]) {
          window["pick"] = async (message) => {
            if (!message) throw new Error("pick() requires a message parameter");
            return new Promise((resolve) => {
              const selections = [];
              const selectedElements = new Set();

              const overlay = document.createElement("div");
              overlay.style.cssText =
                "position:fixed;top:0;left:0;width:100%;height:100%;z-index:2147483647;pointer-events:none";

              const highlight = document.createElement("div");
              highlight.style.cssText =
                "position:absolute;border:2px solid #3b82f6;background:rgba(59,130,246,0.1);transition:all 0.1s";
              overlay.appendChild(highlight);

              const banner = document.createElement("div");
              banner.style.cssText =
                "position:fixed;bottom:20px;left:50%;transform:translateX(-50%);background:#1f2937;color:white;padding:12px 24px;border-radius:8px;font:14px sans-serif;box-shadow:0 4px 12px rgba(0,0,0,0.3);pointer-events:auto;z-index:2147483647";

              const updateBanner = () => {
                banner.textContent = `${message} (${selections.length} selected, Cmd/Ctrl+click to add, Enter to finish, ESC to cancel)`;
              };
              updateBanner();
              document.body.append(banner, overlay);

              const cleanup = () => {
                document.removeEventListener("mousemove", onMove, true);
                document.removeEventListener("click", onClick, true);
                document.removeEventListener("keydown", onKey, true);
                overlay.remove();
                banner.remove();
                selectedElements.forEach((el) => { el.style.outline = ""; });
              };

              const onMove = (e) => {
                const el = document.elementFromPoint(e.clientX, e.clientY);
                if (!el || overlay.contains(el) || banner.contains(el)) return;
                const r = el.getBoundingClientRect();
                highlight.style.cssText = `position:absolute;border:2px solid #3b82f6;background:rgba(59,130,246,0.1);top:${r.top}px;left:${r.left}px;width:${r.width}px;height:${r.height}px`;
              };

              const buildElementInfo = (el) => ({
                tag: el.tagName.toLowerCase(),
                id: el.id || null,
                class: el.className || null,
                text: el.textContent?.trim().slice(0, 200) || null,
                html: el.outerHTML.slice(0, 500),
              });

              const onClick = (e) => {
                if (banner.contains(e.target)) return;
                e.preventDefault();
                e.stopPropagation();
                const el = document.elementFromPoint(e.clientX, e.clientY);
                if (!el || overlay.contains(el) || banner.contains(el)) return;

                if (e.metaKey || e.ctrlKey) {
                  if (!selectedElements.has(el)) {
                    selectedElements.add(el);
                    el.style.outline = "3px solid #10b981";
                    selections.push(buildElementInfo(el));
                    updateBanner();
                  }
                } else {
                  cleanup();
                  const info = buildElementInfo(el);
                  resolve(selections.length > 0 ? selections : info);
                }
              };

              const onKey = (e) => {
                if (e.key === "Escape") {
                  e.preventDefault();
                  cleanup();
                  resolve(null);
                } else if (e.key === "Enter" && selections.length > 0) {
                  e.preventDefault();
                  cleanup();
                  resolve(selections);
                }
              };

              document.addEventListener("mousemove", onMove, true);
              document.addEventListener("click", onClick, true);
              document.addEventListener("keydown", onKey, true);
            });
          };
        }
      });

      const result = await page.evaluate(
        (msg) => window["pick"](msg),
        params.message
      );

      if (result === null) {
        return {
          content: [{ type: "text", text: "Cancelled by user" }],
          details: { cancelled: true },
        };
      }

      const text = typeof result === "object"
        ? JSON.stringify(result, null, 2)
        : String(result);

      return {
        content: [{ type: "text", text }],
        details: { result },
      };
    },
  });
}

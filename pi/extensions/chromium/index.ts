import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";
import { StringEnum } from "@earendil-works/pi-ai";
import { Text } from "@earendil-works/pi-tui";
import puppeteer, { type Browser } from "puppeteer-core";
import { BrowserTargets, targetText } from "./targets.ts";
import { readFile } from "node:fs/promises";

const CDP_URL = "http://localhost:9222";
const CONNECT_TIMEOUT = 5000;
const INSPECT_RESULT_LIMIT = 12000;
const INSPECT_TRUNCATION_MARKER = `\n[truncated: result limited to ${INSPECT_RESULT_LIMIT} characters]`;

type PickInfo = { tag: string; id: string | null; class: string | null; text: string | null; html: string };
type PickWindow = Window & { pick?: (message: string) => Promise<PickInfo | PickInfo[] | null> };

// ---------------------------------------------------------------------------
// Persistent connection state (lives in extension closure)
// ---------------------------------------------------------------------------

const targetParameters = {
  tabId: Type.Optional(Type.String({ minLength: 1, description: "Stable tab ID from browser_nav or browser_tabs. Explicit tabId selects its main frame unless frameId is supplied." })),
  frameId: Type.Optional(Type.String({ minLength: 1, description: "Opaque frame ID from browser_tabs({tabId}). Omit both IDs to reuse the exact selected target." })),
};

export default function (pi: ExtensionAPI) {
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

    let timer: ReturnType<typeof setTimeout>;
    try {
      browser = await Promise.race([
        puppeteer.connect({ browserURL: CDP_URL, defaultViewport: null }),
        new Promise<never>((_, reject) => {
          timer = setTimeout(() => reject(new Error("Connection timeout")), CONNECT_TIMEOUT);
        }),
      ]);
      return browser;
    } finally {
      clearTimeout(timer!);
    }
  }

  const targets = new BrowserTargets(ensureBrowser);

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
      "Navigate the selected tab/frame to a URL and return its stable target IDs. Connects to Chromium on localhost:9222 (must be started with --remote-debugging-port=9222).",
    promptSnippet: "Navigate a stable browser target, optionally in a new tab",
    promptGuidelines: [
      "Browser tools reuse the exact selected tab/frame when IDs are omitted. Use browser_tabs to choose a tabId; browser_tabs({tabId}) selects its main frame and lists its frameIds. Missing targets fail without fallback.",
    ],
    executionMode: "sequential",
    parameters: Type.Object({
      ...targetParameters,
      url: Type.String({ description: "URL to navigate to" }),
      newTab: Type.Optional(
        Type.Boolean({ description: "Open and select a new tab. Cannot be combined with tabId or frameId." })
      ),
    }),
    async execute(_toolCallId, params, signal, onUpdate) {
      onUpdate?.({ content: [{ type: "text", text: `Navigating to ${params.url}...` }], details: {} });

      const { frame, target } = params.newTab ? await targets.open(params) : await targets.resolve(params);
      await frame.goto(params.url, { waitUntil: "domcontentloaded" });
      const title = await frame.title();

      return {
        content: [{ type: "text", text: `${targetText(target)}\n${params.newTab ? "Opened" : "Navigated to"}: ${title}\nURL: ${frame.url()}` }],
        details: { target, title, url: frame.url() },
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
  // browser_eval — Evaluate JavaScript in the selected frame
  // ------------------------------------------------------------------
  pi.registerTool({
    name: "browser_eval",
    label: "Browser Eval",
    description:
      "Evaluate JavaScript in the selected tab/frame, including cross-origin frames. Code runs in an async context. Return values are serialized as text.",
    promptSnippet: "Evaluate JavaScript in the selected tab/frame",
    executionMode: "sequential",
    parameters: Type.Object({
      ...targetParameters,
      code: Type.String({ description: "JavaScript code to evaluate. Runs in async context." }),
    }),
    async execute(_toolCallId, params) {
      const { frame, target } = await targets.resolve(params);

      const result = await frame.evaluate((code) => {
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
        content: [{ type: "text", text: `${targetText(target)}\n${text}` }],
        details: { target },
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
    description: "List stable tab IDs grouped by window, without URLs. Supply tabId to select that tab's main frame and enumerate only its frames and URLs.",
    promptSnippet: "List stable tab IDs, or select one tab and enumerate its frames",
    executionMode: "sequential",
    parameters: Type.Object({ tabId: targetParameters.tabId }),
    async execute(_toolCallId, params) {
      const scoped = params.tabId === undefined ? null : await targets.resolve(params);
      const pages = scoped ? [scoped.page] : await (await ensureBrowser()).pages();
      const target = scoped?.target ?? targets.selected;
      const frames = scoped ? targets.frames(scoped.page) : undefined;
      const windowMap: Record<number, Array<{ tabId: string; title: string; url?: string }>> = {};

      for (const page of pages) {
        const session = await page.createCDPSession();
        try {
          const { windowId } = await session.send("Browser.getWindowForTarget") as { windowId: number };
          if (!windowMap[windowId]) windowMap[windowId] = [];
          windowMap[windowId].push({
            tabId: await targets.tabId(page),
            title: await page.title(),
            ...(scoped ? { url: page.url() } : {}),
          });
        } finally {
          await session.detach();
        }
      }

      const lines: string[] = [target ? targetText(target) : "No target selected."];
      let winNum = 1;
      for (const [winId, tabs] of Object.entries(windowMap)) {
        lines.push(`Window ${winNum} (id: ${winId}):`);
        tabs.forEach(t => {
          lines.push(`  tabId=${t.tabId}: ${t.title}`);
          if (t.url !== undefined) lines.push(`     ${t.url}`);
        });
        winNum++;
      }

      if (frames) lines.push(`Frames: ${JSON.stringify(frames, null, 2)}`);
      return {
        content: [{ type: "text", text: lines.join("\n") }],
        details: { target, windowMap, ...(frames ? { frames } : {}) },
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
      "Capture the selected tab viewport, or the selected iframe element. " +
      "Optionally navigates to a URL first and waits for a CSS selector. " +
      "Returns the screenshot as an inline image.",
    promptSnippet: "Capture the selected tab viewport or iframe element",
    promptGuidelines: [
      "After browser-rendered web UI changes (not terminal UI changes), verify visually with browser_screenshot; use waitFor for async page content.",
    ],
    executionMode: "sequential",
    parameters: Type.Object({
      ...targetParameters,
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
      const { page, frame, target } = await targets.resolve(params);

      if (params.url) {
        onUpdate?.({ content: [{ type: "text", text: `Navigating to ${params.url}...` }], details: {} });
        await frame.goto(params.url, { waitUntil: "domcontentloaded" });
      }

      // Wait for selector if specified
      if (params.waitFor) {
        const timeout = (params.waitTimeout || 10) * 1000;
        await frame.waitForSelector(params.waitFor, { visible: true, timeout });
      }

      const { tmpdir } = await import("node:os");
      const { join } = await import("node:path");

      const timestamp = new Date().toISOString().replace(/[:.]/g, "-");
      const filepath = join(tmpdir(), `screenshot-${timestamp}.png`);

      const element = frame === page.mainFrame() ? null : await frame.frameElement();
      if (frame !== page.mainFrame() && !element) throw new Error("Frame target is detached or missing.");
      try {
        await (element ?? page).screenshot({ path: filepath });
      } finally {
        await element?.dispose();
      }

      // Read file as base64 for inline image
      const imageData = await readFile(filepath);
      const base64 = imageData.toString("base64");

      const title = await frame.title();
      const url = frame.url();

      return {
        content: [
          { type: "text", text: `${targetText(target)}\nScreenshot of "${title}" at ${url}\nSaved to: ${filepath}` },
          { type: "image", data: base64, mimeType: "image/png" },
        ],
        details: { target, url, title, path: filepath },
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
      "Inspect the first CSS match in the selected frame (count checks all matches). " +
      "Default action text returns rendered HTML innerText, excluding script/style and hidden data; " +
      "action raw explicitly returns trimmed textContent, including hidden data. " +
      "Navigates to URL first if provided. Result strings are capped at 12000 UTF-16 code units " +
      "including a truncation marker. Returns details {target, selector, action, result: string, truncated: boolean}.",
    promptSnippet: "Inspect rendered text, explicit raw text, attributes or state by CSS selector",
    promptGuidelines: [
      "Use browser_inspect for rendered text/visibility/state checks; prefer it over browser_eval for simple queries.",
    ],
    executionMode: "sequential",
    parameters: Type.Object({
      ...targetParameters,
      selector: Type.String({
        description: 'CSS selector to query, e.g. "h1", ".patient-name", "[data-testid=\'status\']"',
      }),
      action: Type.Optional(
        StringEnum(["text", "raw", "html", "attr", "count", "visible", "exists"] as const),
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
      const { frame, target } = await targets.resolve(params);

      if (params.url) {
        await frame.goto(params.url, { waitUntil: "domcontentloaded" });
      }

      const sel = params.selector;
      const shouldWait = params.waitFor !== false;
      const action = params.action || "text";

      // Wait for element if requested
      if (shouldWait && action !== "exists") {
        try {
          await frame.waitForSelector(sel, { visible: true, timeout: 10000 });
        } catch {
          if (action !== "count") {
            throw new Error(`Element not found or not visible: "${sel}"`);
          }
        }
      }

      let result: string;
      switch (action) {
        case "text":
          result = await frame.$eval(sel, (el) => {
            if (!(el instanceof HTMLElement) || el.matches("script, style")) return "";
            // innerText falls back to raw text for display:none subtrees.
            for (let node: Element | null = el; node; node = node.parentElement) {
              const style = window.getComputedStyle(node);
              if (style.display === "none" || style.contentVisibility === "hidden") return "";
            }
            return el.innerText.trim();
          });
          break;
        case "raw":
          result = await frame.$eval(sel, (el) => el.textContent?.trim() ?? "");
          break;
        case "html":
          result = await frame.$eval(sel, (el) => el.innerHTML);
          break;
        case "attr":
          if (!params.attribute) throw new Error('The "attr" action requires the "attribute" parameter.');
          result = await frame.$eval(sel, (el, attr) => el.getAttribute(attr) ?? "(null)", params.attribute);
          break;
        case "count":
          result = String(await frame.$$eval(sel, (els) => els.length));
          break;
        case "visible":
          result = String(await frame.$eval(sel, (el) => {
            const style = window.getComputedStyle(el);
            return style.display !== "none" && style.visibility !== "hidden" && style.opacity !== "0";
          }).catch(() => false));
          break;
        case "exists":
          const element = await frame.$(sel);
          result = String(element !== null);
          await element?.dispose();
          break;
        default:
          throw new Error(`Unknown inspection action: ${action}`);
      }

      const truncated = result.length > INSPECT_RESULT_LIMIT;
      if (truncated) {
        result = result.slice(0, INSPECT_RESULT_LIMIT - INSPECT_TRUNCATION_MARKER.length) + INSPECT_TRUNCATION_MARKER;
      }

      return {
        content: [{ type: "text", text: `${targetText(target)}\n${result}` }],
        details: { target, selector: sel, action, result, truncated },
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
  // browser_cookies — Show cookies for the selected frame URL
  // ------------------------------------------------------------------
  pi.registerTool({
    name: "browser_cookies",
    label: "Browser Cookies",
    description: "Display cookies for the selected frame's URL, including domain, path, httpOnly and secure flags.",
    promptSnippet: "Show cookies for the selected tab/frame URL",
    executionMode: "sequential",
    parameters: Type.Object(targetParameters),
    async execute(_toolCallId, params) {
      const { page, frame, target } = await targets.resolve(params);
      const cookies = await page.cookies(frame.url());

      const lines = cookies.map(
        (c) =>
          `${c.name}: ${c.value}\n  domain: ${c.domain}  path: ${c.path}  httpOnly: ${c.httpOnly}  secure: ${c.secure}`
      );

      return {
        content: [{ type: "text", text: `${targetText(target)}\n${lines.join("\n\n") || "(no cookies)"}` }],
        details: { target, count: cookies.length },
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
    executionMode: "sequential",
    parameters: Type.Object({
      ...targetParameters,
      message: Type.String({ description: "Prompt message shown to the user in the browser overlay" }),
    }),
    async execute(_toolCallId, params) {
      const { frame, target } = await targets.resolve(params);

      await frame.evaluate(() => {
        const pickWindow = window as PickWindow;
        if (!pickWindow.pick) {
          pickWindow.pick = async (message: string) => {
            if (!message) throw new Error("pick() requires a message parameter");
            return new Promise<PickInfo | PickInfo[] | null>((resolve) => {
              const selections: PickInfo[] = [];
              const selectedElements = new Set<Element>();

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
                selectedElements.forEach((el) => { (el as HTMLElement).style.outline = ""; });
              };

              const onMove = (e: MouseEvent) => {
                const el = document.elementFromPoint(e.clientX, e.clientY);
                if (!el || overlay.contains(el) || banner.contains(el)) return;
                const r = el.getBoundingClientRect();
                highlight.style.cssText = `position:absolute;border:2px solid #3b82f6;background:rgba(59,130,246,0.1);top:${r.top}px;left:${r.left}px;width:${r.width}px;height:${r.height}px`;
              };

              const buildElementInfo = (el: Element): PickInfo => ({
                tag: el.tagName.toLowerCase(),
                id: el.id || null,
                class: el.getAttribute("class") || null,
                text: el.textContent?.trim().slice(0, 200) || null,
                html: el.outerHTML.slice(0, 500),
              });

              const onClick = (e: MouseEvent) => {
                if (banner.contains(e.target as Node)) return;
                e.preventDefault();
                e.stopPropagation();
                const el = document.elementFromPoint(e.clientX, e.clientY);
                if (!el || overlay.contains(el) || banner.contains(el)) return;

                if (e.metaKey || e.ctrlKey) {
                  if (!selectedElements.has(el)) {
                    selectedElements.add(el);
                    (el as HTMLElement).style.outline = "3px solid #10b981";
                    selections.push(buildElementInfo(el));
                    updateBanner();
                  }
                } else {
                  cleanup();
                  const info = buildElementInfo(el);
                  resolve(selections.length > 0 ? selections : info);
                }
              };

              const onKey = (e: KeyboardEvent) => {
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

      const result = await frame.evaluate(
        (msg) => (window as PickWindow).pick!(msg),
        params.message
      );

      const text = result === null ? "Cancelled by user" : typeof result === "object"
        ? JSON.stringify(result, null, 2)
        : String(result);

      return {
        content: [{ type: "text", text: `${targetText(target)}\n${text}` }],
        details: { target, cancelled: result === null, result },
      };
    },
  });
}

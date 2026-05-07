/**
 * Browser WebDriver Extension
 *
 * Provides tools for browser automation against the running dev app.
 * Uses etaoin (WebDriver) through a babashka nREPL, managed automatically.
 *
 * Tools:
 *   webdriver_screenshot - Navigate to URL, screenshot, return image
 *   webdriver_eval       - Evaluate arbitrary etaoin expressions
 *   webdriver_inspect    - Extract text/attributes from CSS selectors
 *
 * Commands:
 *   /browser           - Open a headed browser for the user to watch
 *   /browser-stop      - Stop browser session and clean up
 *
 * Requires chromedriver on PATH (add to flake.nix buildInputs).
 */

import type { ExtensionAPI, ExtensionContext } from "@mariozechner/pi-coding-agent";
import { Type } from "@sinclair/typebox";
import { StringEnum } from "@mariozechner/pi-ai";
import { Text } from "@mariozechner/pi-tui";
import { type ChildProcess, spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { readFile, mkdir } from "node:fs/promises";
import { join } from "node:path";

// -- Configuration ----------------------------------------------------------

const BB_NREPL_PORT = 7778;
const DEFAULT_APP_URL = "http://localhost:8080";
const SCREENSHOT_DIR_NAME = ".agents/tmp";
const SKILL_SUBDIR = ".agents/skills/webdriver";

// -- State ------------------------------------------------------------------

interface BrowserState {
  nreplProcess: ChildProcess | null;
  nreplReady: boolean;
  driverInitialized: boolean;
  headed: boolean;
}

const state: BrowserState = {
  nreplProcess: null,
  nreplReady: false,
  driverInitialized: false,
  headed: false,
};

// -- Helpers ----------------------------------------------------------------

async function nreplEval(
  pi: ExtensionAPI,
  expr: string,
  timeoutMs = 30000,
): Promise<{ stdout: string; stderr: string; code: number }> {
  const result = await pi.exec(
    "clj-nrepl-eval",
    ["-p", String(BB_NREPL_PORT), expr],
    { timeout: timeoutMs },
  );
  return { stdout: result.stdout, stderr: result.stderr, code: result.code };
}

function skillDir(cwd: string): string {
  return join(cwd, SKILL_SUBDIR);
}

async function ensureNrepl(pi: ExtensionAPI, ctx: ExtensionContext): Promise<void> {
  if (state.nreplReady) {
    // Verify still alive
    try {
      const check = await nreplEval(pi, "(+ 1 1)", 5000);
      if (check.code === 0 && check.stdout.trim() === "2") return;
    } catch {
      // Fall through to restart
    }
    state.nreplReady = false;
    state.driverInitialized = false;
  }

  // Kill old process if any
  if (state.nreplProcess) {
    state.nreplProcess.kill("SIGTERM");
    state.nreplProcess = null;
  }

  // Verify bb.edn exists
  const bbEdn = join(skillDir(ctx.cwd), "bb.edn");
  if (!existsSync(bbEdn)) {
    throw new Error(
      `bb.edn not found at ${bbEdn}. ` +
      `Ensure the webdriver skill is installed at ${SKILL_SUBDIR}/`,
    );
  }

  // Start bb nREPL
  ctx.ui.setStatus("webdriver", "Starting browser nREPL...");
  const proc = spawn("bb", ["nrepl-server", String(BB_NREPL_PORT)], {
    cwd: skillDir(ctx.cwd),
    stdio: ["ignore", "pipe", "pipe"],
    detached: false,
    env: process.env,
  });

  state.nreplProcess = proc;

  // Collect stdout+stderr for diagnostics
  let stderrBuf = "";
  let stdoutBuf = "";
  proc.stdout?.on("data", (chunk: Buffer) => {
    stdoutBuf += chunk.toString();
  });
  proc.stderr?.on("data", (chunk: Buffer) => {
    stderrBuf += chunk.toString();
  });

  proc.on("exit", (code) => {
    if (state.nreplProcess === proc) {
      state.nreplReady = false;
      state.driverInitialized = false;
      state.nreplProcess = null;
    }
  });

  // Wait for nREPL to be ready (poll)
  // First dep download can be slow, so allow 30s
  const maxWait = 30000;
  const interval = 1000;
  const start = Date.now();
  while (Date.now() - start < maxWait) {
    await new Promise((r) => setTimeout(r, interval));
    try {
      const check = await nreplEval(pi, "(+ 1 1)", 3000);
      if (check.code === 0 && check.stdout.trim() === "2") {
        state.nreplReady = true;
        ctx.ui.setStatus("webdriver", undefined);
        return;
      }
    } catch {
      // Not ready yet
    }
  }

  // Cleanup on failure
  proc.kill("SIGTERM");
  state.nreplProcess = null;
  ctx.ui.setStatus("webdriver", undefined);
  throw new Error(
    `Failed to start bb nREPL on port ${BB_NREPL_PORT} within ${maxWait}ms. ` +
    `stdout: ${stdoutBuf.slice(0, 300)} stderr: ${stderrBuf.slice(0, 300)}`,
  );
}

async function ensureDriver(pi: ExtensionAPI, ctx: ExtensionContext, headed = false): Promise<void> {
  await ensureNrepl(pi, ctx);

  if (state.driverInitialized && state.headed === headed) return;

  // If driver exists but wrong mode, quit it first
  if (state.driverInitialized) {
    await nreplEval(pi, "(e/quit driver)", 10000).catch(() => {});
    state.driverInitialized = false;
  }

  ctx.ui.setStatus("webdriver", "Starting browser...");

  // Require etaoin
  const req = await nreplEval(pi, "(require '[etaoin.api :as e])");
  if (req.code !== 0) {
    ctx.ui.setStatus("webdriver", undefined);
    throw new Error(`Failed to require etaoin: ${req.stderr}`);
  }

  // Create driver
  const driverExpr = headed
    ? '(def driver (e/chrome {:args ["--window-size=1920,1080"]}))'
    : '(def driver (e/chrome-headless {:args ["--window-size=1920,1080" "--no-sandbox"]}))';

  const drvResult = await nreplEval(pi, driverExpr, 15000);
  if (drvResult.code !== 0) {
    ctx.ui.setStatus("webdriver", undefined);
    throw new Error(
      `Failed to create browser driver. Is chromedriver on PATH?\n${drvResult.stderr}`,
    );
  }

  state.driverInitialized = true;
  state.headed = headed;
  ctx.ui.setStatus("webdriver", headed ? "Browser (headed)" : undefined);
}

async function cleanupDriver(pi: ExtensionAPI): Promise<void> {
  if (state.driverInitialized) {
    try {
      await nreplEval(pi, "(e/quit driver)", 5000);
    } catch {
      // Best effort
    }
    state.driverInitialized = false;
  }
}

async function cleanupAll(pi: ExtensionAPI): Promise<void> {
  await cleanupDriver(pi);
  if (state.nreplProcess) {
    state.nreplProcess.kill("SIGTERM");
    state.nreplProcess = null;
    state.nreplReady = false;
  }
}

function screenshotDir(cwd: string): string {
  return join(cwd, SCREENSHOT_DIR_NAME);
}

// -- Extension --------------------------------------------------------------

export default function browserEtaoinExtension(pi: ExtensionAPI) {
  // Cleanup on session end
  pi.on("session_shutdown", async () => {
    await cleanupAll(pi);
  });

  // -- Tool: webdriver_screenshot ------------------------------------------

  pi.registerTool({
    name: "webdriver_screenshot",
    label: "Browser Screenshot",
    description:
      "Take a screenshot of the running app in a headless browser. " +
      "Navigates to the given URL (default: http://localhost:8080), " +
      "waits for an optional CSS selector, and returns the screenshot as an image.",
    promptSnippet: "Screenshot the running app in a headless browser",
    promptGuidelines: [
      "After making UI changes, use webdriver_screenshot to verify the rendered result visually.",
      "Use the waitFor parameter with a CSS selector to wait for async content to load.",
    ],
    parameters: Type.Object({
      url: Type.Optional(
        Type.String({ description: `URL to navigate to. Default: ${DEFAULT_APP_URL}` }),
      ),
      waitFor: Type.Optional(
        Type.String({
          description: "CSS selector to wait for before taking the screenshot.",
        }),
      ),
      waitTimeout: Type.Optional(
        Type.Number({
          description: "Timeout in seconds for waitFor selector. Default: 10.",
        }),
      ),
    }),
    async execute(_toolCallId, params, signal, onUpdate, ctx) {
      const url = params.url || DEFAULT_APP_URL;

      onUpdate?.({
        content: [{ type: "text", text: `Navigating to ${url}...` }],
      });

      await ensureDriver(pi, ctx);

      // Navigate
      const nav = await nreplEval(pi, `(e/go driver "${url}")`);
      if (nav.code !== 0) {
        throw new Error(`Navigation failed: ${nav.stderr}`);
      }

      // Wait for selector if specified
      if (params.waitFor) {
        const timeout = params.waitTimeout || 10;
        const waitExpr =
          `(e/wait-visible driver {:css "${params.waitFor}"} {:timeout ${timeout}})`;
        const waitResult = await nreplEval(pi, waitExpr, (timeout + 5) * 1000);
        if (waitResult.code !== 0) {
          throw new Error(
            `Timed out waiting for selector "${params.waitFor}": ${waitResult.stderr}`,
          );
        }
      } else {
        // Brief pause for SPA rendering
        await nreplEval(pi, "(e/wait driver 1)");
      }

      // Take screenshot to temp file
      const outDir = screenshotDir(ctx.cwd);
      await mkdir(outDir, { recursive: true });
      const timestamp = new Date().toISOString().replace(/[:.]/g, "-");
      const filePath = join(outDir, `screenshot-${timestamp}.png`);

      const ssExpr = `(e/screenshot driver "${filePath}")`;
      const ssResult = await nreplEval(pi, ssExpr, 10000);
      if (ssResult.code !== 0) {
        throw new Error(`Screenshot failed: ${ssResult.stderr}`);
      }

      // Read file as base64
      const imageData = await readFile(filePath);
      const base64 = imageData.toString("base64");

      // Get page title for context
      const titleResult = await nreplEval(pi, "(e/get-title driver)");
      const title = titleResult.stdout.trim().replace(/^"|"$/g, "");

      return {
        content: [
          {
            type: "text",
            text: `Screenshot of "${title}" at ${url}\nSaved to: ${filePath}`,
          },
          { type: "image", data: base64, mimeType: "image/png" },
        ],
        details: { url, title, filePath },
      };
    },

    renderCall(args, theme) {
      const url = args.url || DEFAULT_APP_URL;
      let text = theme.fg("toolTitle", theme.bold("webdriver_screenshot "));
      text += theme.fg("muted", url);
      if (args.waitFor) {
        text += theme.fg("dim", ` (wait: ${args.waitFor})`);
      }
      return new Text(text, 0, 0);
    },

    renderResult(result, { expanded, isPartial }, theme) {
      if (isPartial) {
        return new Text(theme.fg("warning", "Capturing screenshot..."), 0, 0);
      }
      const details = result.details as { url?: string; title?: string; filePath?: string } | undefined;
      let text = theme.fg("success", "Screenshot captured");
      if (details?.title) {
        text += theme.fg("dim", ` - "${details.title}"`);
      }
      if (expanded && details?.filePath) {
        text += "\n" + theme.fg("dim", `  Saved: ${details.filePath}`);
      }
      return new Text(text, 0, 0);
    },
  });

  // -- Tool: webdriver_eval ------------------------------------------------

  pi.registerTool({
    name: "webdriver_eval",
    label: "Browser Eval",
    description:
      "Evaluate an etaoin (WebDriver) expression against the running browser session. " +
      "The browser driver is bound to `driver` and etaoin.api is aliased as `e`. " +
      "Use for complex browser interactions: filling forms, clicking elements, " +
      "extracting data, running JavaScript, etc.",
    promptSnippet: "Evaluate etaoin expressions against the running browser",
    parameters: Type.Object({
      expr: Type.String({
        description:
          'Clojure expression using etaoin. `driver` and `e` are available. ' +
          'Example: \'(e/get-element-text driver {:css "h1"})\'',
      }),
      timeout: Type.Optional(
        Type.Number({ description: "Timeout in seconds. Default: 30." }),
      ),
    }),
    async execute(_toolCallId, params, signal, onUpdate, ctx) {
      const timeoutMs = (params.timeout || 30) * 1000;

      await ensureDriver(pi, ctx);

      const result = await nreplEval(pi, params.expr, timeoutMs);

      if (result.code !== 0) {
        throw new Error(`Eval failed:\n${result.stderr}\n${result.stdout}`);
      }

      return {
        content: [{ type: "text", text: result.stdout || "(nil)" }],
        details: { expr: params.expr },
      };
    },

    renderCall(args, theme) {
      let text = theme.fg("toolTitle", theme.bold("webdriver_eval "));
      const expr = args.expr.length > 80
        ? args.expr.slice(0, 77) + "..."
        : args.expr;
      text += theme.fg("muted", expr);
      return new Text(text, 0, 0);
    },
  });

  // -- Tool: webdriver_inspect ---------------------------------------------

  pi.registerTool({
    name: "webdriver_inspect",
    label: "Browser Inspect",
    description:
      "Extract text content, attributes, or element state from the current page " +
      "using CSS selectors. Navigates to URL first if provided. " +
      "Returns structured data about matched elements.",
    promptSnippet: "Extract text/attributes from browser page elements by CSS selector",
    promptGuidelines: [
      "Use webdriver_inspect to verify rendered text content, element visibility, and DOM state.",
      "Prefer webdriver_inspect over webdriver_eval for simple element queries.",
    ],
    parameters: Type.Object({
      url: Type.Optional(
        Type.String({ description: "URL to navigate to first." }),
      ),
      selector: Type.String({
        description: 'CSS selector to query, e.g. "h1", ".patient-name", "[data-testid=\'status\']"',
      }),
      action: Type.Optional(
        StringEnum(["text", "html", "attr", "count", "visible", "exists"] as const),
      ),
      attribute: Type.Optional(
        Type.String({
          description: 'Attribute name to extract when action is "attr".',
        }),
      ),
      waitFor: Type.Optional(
        Type.Boolean({
          description: "Wait for the selector to be visible before inspecting. Default: true.",
        }),
      ),
    }),
    async execute(_toolCallId, params, signal, onUpdate, ctx) {
      await ensureDriver(pi, ctx);

      // Navigate if URL given
      if (params.url) {
        const nav = await nreplEval(pi, `(e/go driver "${params.url}")`);
        if (nav.code !== 0) {
          throw new Error(`Navigation failed: ${nav.stderr}`);
        }
      }

      const sel = params.selector;
      const shouldWait = params.waitFor !== false;
      const action = params.action || "text";

      // Wait for element if requested
      if (shouldWait && action !== "exists") {
        const waitResult = await nreplEval(
          pi,
          `(e/wait-visible driver {:css "${sel}"} {:timeout 10})`,
          15000,
        );
        if (waitResult.code !== 0 && action !== "count") {
          throw new Error(
            `Element not found or not visible: "${sel}"\n${waitResult.stderr}`,
          );
        }
      }

      let expr: string;
      switch (action) {
        case "text":
          expr = `(e/get-element-text driver {:css "${sel}"})`;
          break;
        case "html":
          expr = `(e/get-element-attr driver {:css "${sel}"} "innerHTML")`;
          break;
        case "attr":
          if (!params.attribute) {
            throw new Error('The "attr" action requires the "attribute" parameter.');
          }
          expr = `(e/get-element-attr driver {:css "${sel}"} "${params.attribute}")`;
          break;
        case "count":
          expr = `(count (e/query-all driver {:css "${sel}"}))`;
          break;
        case "visible":
          expr = `(e/visible? driver {:css "${sel}"})`;
          break;
        case "exists":
          expr = `(e/exists? driver {:css "${sel}"})`;
          break;
        default:
          expr = `(e/get-element-text driver {:css "${sel}"})`;
      }

      const result = await nreplEval(pi, expr, 15000);
      if (result.code !== 0) {
        throw new Error(`Inspect failed for "${sel}": ${result.stderr}`);
      }

      return {
        content: [{ type: "text", text: result.stdout || "(nil)" }],
        details: { selector: sel, action, result: result.stdout.trim() },
      };
    },

    renderCall(args, theme) {
      let text = theme.fg("toolTitle", theme.bold("webdriver_inspect "));
      text += theme.fg("muted", args.selector);
      if (args.action && args.action !== "text") {
        text += theme.fg("dim", ` [${args.action}]`);
      }
      return new Text(text, 0, 0);
    },
  });

  // -- Command: /browser (headed mode) --------------------------------------

  pi.registerCommand("browser", {
    description: "Open a headed (visible) browser against the dev app",
    handler: async (args, ctx) => {
      const url = args?.trim() || DEFAULT_APP_URL;
      try {
        await ensureDriver(pi, ctx, true);
        await nreplEval(pi, `(e/go driver "${url}")`);
        ctx.ui.notify(`Headed browser opened at ${url}`, "info");
      } catch (err) {
        ctx.ui.notify(
          `Failed to open browser: ${err instanceof Error ? err.message : String(err)}`,
          "error",
        );
      }
    },
  });

  // -- Command: /browser-stop -----------------------------------------------

  pi.registerCommand("browser-stop", {
    description: "Stop the browser session and clean up",
    handler: async (_args, ctx) => {
      await cleanupAll(pi);
      ctx.ui.setStatus("webdriver", undefined);
      ctx.ui.notify("Browser session stopped", "info");
    },
  });
}

import assert from "node:assert/strict";
import test from "node:test";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { createHarness } from "../test/support/rpc.ts";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "../../..");

test("installed Pi loads Chromium and exposes all targeting schemas without a browser connection", { timeout: 30000 }, async () => {
  await mkdir(join(root, ".tmp"), { recursive: true });
  const cwd = await mkdtemp(join(root, ".tmp/chromium-rpc-"));
  const probe = join(cwd, "probe.ts");
  await writeFile(probe, `export default function(pi) {
    pi.registerCommand("chromium-probe", {
      description: "Report registered browser tool schemas",
      handler: async (_args, ctx) => {
        const tools = pi.getAllTools().filter(tool => tool.name.startsWith("browser_"));
        ctx.ui.notify(JSON.stringify(tools.map(tool => ({
          name: tool.name, parameters: tool.parameters
        }))), "info");
      }
    });
  }`);
  const harness = createHarness(join(here, "index.ts"), cwd, {
    args: ["--no-extensions", "--no-skills", "--no-prompt-templates", "--no-context-files", "--no-approve", "-e", probe],
  });
  try {
    const id = harness.send({ type: "prompt", message: "/chromium-probe" });
    const response = await harness.waitFor(event => event.type === "response" && event.id === id, "probe response");
    assert.equal(response.success, true, response.error);
    const notice = await harness.waitFor(event => event.type === "extension_ui_request" && event.method === "notify", "schema notification");
    const tools = JSON.parse(notice.message);
    assert.deepEqual(tools.map((tool: any) => tool.name).sort(), [
      "browser_cookies", "browser_eval", "browser_inspect", "browser_nav", "browser_pick", "browser_screenshot", "browser_tabs",
    ]);
    for (const tool of tools) {
      assert.ok(tool.parameters.properties.tabId, tool.name);
      if (tool.name !== "browser_tabs") assert.ok(tool.parameters.properties.frameId, tool.name);
    }
    const inspect = tools.find((tool: any) => tool.name === "browser_inspect");
    assert.ok(inspect.parameters.properties.action.enum.includes("raw"));
    assert.match(harness.diagnostics(), /stderr=""/);
    assert.equal(harness.events.some((event: any) => event.type === "agent_start"), false);
  } finally {
    await harness.dispose();
    await rm(cwd, { recursive: true, force: true });
  }
});

import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { join } from "node:path";
import { createHarness } from "../test/support/rpc.ts";
import { runOtResult, otCreateTask, otSelectTask } from "./ot.ts";

const root = fileURLToPath(new URL("../../../", import.meta.url));
const scratch = mkdtempSync(join(root, ".tmp/tasks-discovery-"));
const agentEntry = "./extensions/tasks/agent.ts";
const legacyEntry = "./extensions/tasks/legacy.ts";
try {
  for (const mode of ["nonvisual", "legacy", "combined", "compatibility"] as const) {
    const cwd = join(scratch, mode);
    const agentDir = join(cwd, ".pi-agent");
    const extensions = join(agentDir, "extensions");
    mkdirSync(extensions, { recursive: true });
    // Keep sibling helper imports intact, as in the installed extensions tree.
    for (const name of ["tasks", "emacsclient", "lib"]) symlinkSync(join(root, "pi/extensions", name), join(extensions, name));
    writeFileSync(join(agentDir, "settings.json"), JSON.stringify({
      extensions: ["-builtin:mcp", "-builtin:llama.cpp", "-builtin:codemode", "-builtin:tool-search",
        "-./extensions/emacsclient/index.ts",
        ...(mode !== "compatibility" ? ["-./extensions/tasks/index.ts"] : []),
        ...(["legacy", "combined"].includes(mode) ? [legacyEntry] : []),
        ...(["nonvisual", "combined"].includes(mode) ? [agentEntry] : [])],
    }));
    await runOtResult(["init"], { root: cwd });
    const task = await otCreateTask({ summary: "RPC selected task" }, { root: cwd });
    await otSelectTask(task.id, { root: cwd });
    const observer = join(cwd, "observer.ts");
    writeFileSync(observer, `
      export default function (pi) {
        let providerRequests = 0;
        pi.on("before_provider_request", () => { providerRequests++; throw new Error("No provider requests permitted"); });
        pi.registerCommand("separation-probe", { handler: async (_args, ctx) => {
          const cli = await pi.exec(${JSON.stringify(join(root, "skills/org-tasks/scripts/ot"))}, ["--format", "json", "--root", ctx.cwd, "selected"]);
          ctx.ui.notify(JSON.stringify({ providerRequests, tools: pi.getActiveTools(), allTools: pi.getAllTools().map(t => t.name), watchers: process._getActiveHandles().filter(h => h.constructor.name === "FSWatcher").length, cliExitCode: cli.code, cli: JSON.parse(cli.stdout) }), "info");
        } });
        pi.registerCommand("separation-reload", { handler: async (_args, ctx) => { await ctx.reload(); return; } });
      }
    `);
    const harness = createHarness(observer, cwd, { args: ["--offline", "--no-mcp", "--no-skills", "--no-prompt-templates", "--no-context-files", "--no-themes"] });
    try {
      for (let round = 0; round < 3; round++) {
        const id = harness.send({ type: "get_commands" });
        const response = await harness.waitFor((e) => e.type === "response" && e.id === id, "discovered commands");
        assert.equal(response.success, true, JSON.stringify(response));
        const commands = response.data.commands;
        assert.ok(commands.some((c: any) => c.name === "separation-probe"), "observer is a positive discovery control");
        const taskCommands = commands.filter((c: any) => /^tasks(?::|$)/.test(c.name));
        assert.equal(taskCommands.length, mode === "nonvisual" ? 0 : 1, JSON.stringify(commands));
        const since = harness.events.length;
        const probe = harness.send({ type: "prompt", message: "/separation-probe" });
        const accepted = await harness.waitFor((e) => e.type === "response" && e.id === probe, "probe response", { since });
        assert.equal(accepted.success, true, JSON.stringify(accepted));
        const notice = await harness.waitFor((e) => e.type === "extension_ui_request" && e.method === "notify", "probe data", { since });
        const data = JSON.parse(notice.message);
        assert.equal(data.providerRequests, 0);
        assert.ok(data.tools.includes("bash"), "agent shell access remains enabled");
        assert.equal(data.cliExitCode, 0);
        assert.equal(data.cli.ok, true, JSON.stringify(data.cli));
        assert.equal(data.cli.result.task.id, task.id, "ot selected remains available without a visual entrypoint");
        for (const removed of ["tasks_insert_task", "org_read_section", "tasks_scan_summaries"]) assert.ok(!data.allTools.includes(removed));
        if (mode === "nonvisual") assert.equal(data.watchers, 0);
        else assert.ok(data.watchers > 0, "legacy startup owns watchers");
        assert.ok(!harness.events.some((e: any) => e.type === "agent_start"), "extension commands never reach a provider");
        console.log(`ok - installed Pi discovery ${mode} round ${round}: tasks=${taskCommands.length}, watchers=${data.watchers}, CLI selected=${task.id}, providerRequests=0`);
        if (round < 2) {
          const reloadSince = harness.events.length;
          const reload = harness.send({ type: "prompt", message: "/separation-reload" });
          const reloaded = await harness.waitFor((e) => e.type === "response" && e.id === reload, "reload response", { since: reloadSince });
          assert.equal(reloaded.success, true, JSON.stringify(reloaded));
        }
      }
      const closed = new Promise<number | null>((resolve, reject) => {
        const timeout = setTimeout(() => reject(new Error("RPC shutdown exceeded 5 seconds")), 5000);
        harness.proc.once("close", (code) => { clearTimeout(timeout); resolve(code); });
      });
      harness.proc.stdin!.end();
      assert.equal(await closed, 0, "stdin EOF shuts down the runtime cleanly");
      assert.match(harness.diagnostics(), /stderr=""/, harness.diagnostics());
      console.log(`ok - installed Pi ${mode} orderly shutdown: exit=0, stderr empty`);
    } finally {
      await harness.dispose();
    }
  }
} finally {
  rmSync(scratch, { recursive: true, force: true });
}

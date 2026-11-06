import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import registerTasks, { buildChangeRecordPrompt as legacyPrompt, handlePlanEdit } from "./index.ts";
import { buildChangeRecordPrompt } from "./agent.ts";
import { parseTasks } from "./parser.ts";
import { otCreateTask, otRecordCreate, otRecordPath, runOtResult } from "./ot.ts";

async function main() {
  const scratch = mkdtempSync(join(fileURLToPath(new URL("../../../.tmp/", import.meta.url)), "record-ext-"));
  const root = join(scratch, "project");
  mkdirSync(root);
  const shutdown: Array<() => Promise<void>> = [];
  const messages: string[] = [];
  const notices: string[] = [];
  let approvedPath: string | undefined = "notes/flow.org";
  const ctx: any = { cwd: root, hasUI: false, ui: {
    custom: async () => approvedPath,
    notify: (message: string) => notices.push(message),
  } };
  const pi: any = {
    registerCommand() {}, registerShortcut() {},
    events: { on: () => () => {}, emit() {} },
    on: (event: string, fn: any) => { if (event === "session_shutdown") shutdown.push(() => fn({}, ctx)); },
    sendUserMessage: (message: string) => messages.push(message),
  };
  registerTasks(pi);
  try {
    await runOtResult(["init"], { root });
    const task = await otCreateTask({ summary: "Record parent" }, { root });
    const child = await otCreateTask({ summary: "Existing child", parentId: task.id }, { root });
    const suggestion = await otRecordPath(task.id, { root });
    assert.match(suggestion.suggested, /design\/log\/.*-record-parent.org$/);
    const record = await otRecordCreate(task.id, "notes/nested/feature.org", "retrospective", { root });
    assert.equal(record.absorbedSubtasks, true);
    assert.equal(record.created, true);
    assert.ok(record.scope);
    assert.equal(record.importRaw, "[[file:notes/nested/feature.org]]");
    const body = readFileSync(record.recordPath, "utf8");
    assert.match(body, /\* Intent\n\n\* Summary\n/);
    assert.ok(body.includes(child.id));
    const shown: any = await runOtResult(["show", task.id], { root });
    assert.equal(shown.task.importError, null);
    assert.ok(JSON.stringify(shown.task.importChildren).includes(child.id));
    const childRecord = await otRecordCreate(child.id, "notes/child.org", "proactive", { root });
    assert.equal(childRecord.importRaw, "[[file:../child.org]]");
    const childShown: any = await runOtResult(["show", child.id], { root });
    assert.equal(childShown.task.importError, null);

    const existing = await otCreateTask({ summary: "Use existing" }, { root });
    const kept = await otCreateTask({ summary: "Keep local child", parentId: existing.id }, { root });
    writeFileSync(join(root, "notes/existing.org"), "* Intent\nExisting bytes.\n");
    const attached = await otRecordCreate(existing.id, "notes/existing.org", "proactive", { root });
    assert.equal(attached.absorbedSubtasks, false);
    assert.equal(readFileSync(attached.recordPath, "utf8"), "* Intent\nExisting bytes.\n");
    assert.ok(readFileSync(join(root, "TASKS.org"), "utf8").includes(kept.id));

    await assert.rejects(otRecordCreate(existing.id, "../outside.org", "proactive", { root }), /path-outside-project/);
    const local = await otCreateTask({ summary: "Local ] record", local: true, allowCreateSection: true }, { root });
    const localRecord = await otRecordCreate(local.id, "notes/local.org", "proactive", { root });
    assert.ok(readFileSync(join(root, "TASKS.local.org"), "utf8").includes(localRecord.importRaw));
    assert.ok(readFileSync(localRecord.recordPath, "utf8").includes(`#+PARENT: [[task:${local.id}]]`),
      "unsafe descriptions are omitted from parent links");

    const flow = await otCreateTask({ summary: "Flow parent" }, { root });
    const sourcePath = join(root, "TASKS.org");
    const flowTask = parseTasks(readFileSync(sourcePath, "utf8"), { sourcePath }).tasks.find((t) => t.summary === "Flow parent")!;
    await handlePlanEdit(pi, ctx, flowTask);
    assert.ok(readFileSync(join(root, "notes/flow.org"), "utf8").includes("* Intent"));
    assert.equal(messages.length, 1, JSON.stringify(notices));
    assert.ok(messages[0]!.includes("`org-plan` and `org-tasks`"));
    const flowShown: any = await runOtResult(["show", flow.id], { root });
    assert.equal(flowShown.task.importError, null);
    approvedPath = undefined;
    await handlePlanEdit(pi, ctx, flowTask);
    assert.equal(messages.length, 1, "cancelling path approval sends no follow-up prompt");

    assert.equal(legacyPrompt, buildChangeRecordPrompt, "legacy prompt export retains the non-visual builder contract");
    const promptTask = parseTasks("* TODO Prompt parent\n").tasks[0]!;
    for (const mode of ["proactive", "retrospective", "summary"] as const) {
      const prompt = buildChangeRecordPrompt(mode, promptTask, record.importRaw, record.recordPath);
      assert.ok(prompt.includes("`org-plan` and `org-tasks`"));
      assert.ok(prompt.includes(record.recordPath));
      assert.ok(!prompt.includes("first top-level section"));
      assert.ok(!prompt.includes("** Decisions"));
    }
    console.log("ok - ot record wrappers preserve migration, custom links, existing files, local owners, sandboxing, and skill-owned prompts");
  } finally {
    for (const close of shutdown) await close();
    rmSync(scratch, { recursive: true, force: true });
  }
}
main().catch((error) => { console.error(error); process.exitCode = 1; });

import assert from "node:assert/strict";
import { mkdtempSync, rmSync, readFileSync } from "node:fs";
import { fileURLToPath, pathToFileURL } from "node:url";
import { join } from "node:path";
import { buildChangeRecordPrompt, buildSummaryRefreshPrompt } from "./agent.ts";
import { loadTasks } from "./index.ts";
import { runOtResult, otCreateTask } from "./ot.ts";

const { handlePlanEdit } = await import(process.env.TASKS_PROMPTS_CONTROL
  ? pathToFileURL(process.env.TASKS_PROMPTS_CONTROL).href
  : new URL("./agent.ts", import.meta.url).href);
const scratch = mkdtempSync(join(fileURLToPath(new URL("../../../.tmp/", import.meta.url)), "tasks-prompts-"));
const messages: Array<{ text: string; options: unknown }> = [];
const notices: string[] = [];
let approved: string | undefined = " notes/nonvisual.org\nignored second line";
const opened: unknown[] = [];
const pi: any = {
  sendUserMessage: (text: string, options: unknown) => messages.push({ text, options }),
  exec: async () => ({ code: 0, stdout: "t", stderr: "" }),
  events: { emit: (topic: string, payload: unknown) => opened.push({ topic, payload }) },
};
const ctx: any = { cwd: scratch, mode: "rpc", hasUI: true, ui: {
  editor: async (_title: string, initial: string) => { assert.match(initial, /design\/log\//); return approved; },
  notify: (text: string) => notices.push(text),
  custom: () => { throw new Error("non-visual prompt helper must not open an overlay"); },
  setWidget: () => { throw new Error("non-visual prompt helper must not set a widget"); },
} };
try {
  await runOtResult(["init"], { root: scratch });
  const created = await otCreateTask({ summary: "Non-visual record" }, { root: scratch });
  const task = (await loadTasks(scratch)).tasks[0]!;
  await handlePlanEdit(pi, ctx, task);
  assert.equal(messages.length, 1, JSON.stringify(notices));
  assert.deepEqual(messages[0]!.options, { deliverAs: "followUp" });
  assert.match(messages[0]!.text, /Develop a plan/);
  assert.match(readFileSync(join(scratch, "notes/nonvisual.org"), "utf8"), /\* Summary/);
  const shown: any = await runOtResult(["show", created.id], { root: scratch });
  assert.equal(shown.task.importError, null);
  approved = undefined;
  await handlePlanEdit(pi, ctx, task, "retrospective");
  assert.equal(messages.length, 1, "cancelled path approval sends no prompt");
  await handlePlanEdit(pi, ctx, { ...task, propertyLines: [] });
  assert.match(notices.at(-1)!, /without a :CUSTOM_ID:/);
  await handlePlanEdit(pi, ctx, { ...task, importPath: "../outside.org" });
  assert.match(notices.at(-1)!, /outside project root/);
  await handlePlanEdit(pi, ctx, { ...task, importPath: "plan:nonvisual.org", effectiveSourceContent: "#+LINK: plan file:notes/%s\n" });
  assert.deepEqual(opened, [{ topic: "emacs:open", payload: { file: join(scratch, "notes/nonvisual.org"), line: 1 } }]);
  assert.equal(messages.length, 1, "opening an existing record sends no follow-up");
  for (const mode of ["proactive", "retrospective", "summary"] as const) {
    const prompt = buildChangeRecordPrompt(mode, task, "plan:test.org", "/root/test.org", "Scope detail");
    assert.match(prompt, /`org-plan` and `org-tasks`/);
    assert.match(prompt, /Task: TODO Non-visual record/);
    assert.match(prompt, /Change-record link: plan:test.org/);
    assert.match(prompt, /Change-record file: \/root\/test.org/);
    assert.match(prompt, /Scope detail/);
    assert.match(prompt, /Show the draft for approval before writing/);
  }
  assert.match(buildSummaryRefreshPrompt(task, "link", "path", "missing"), /The record has no Summary heading\./);
  assert.match(buildSummaryRefreshPrompt(task, "link", "path", "stale"), /record predates the task start/);
  console.log("ok - non-visual record creation, cancellation, validation, follow-up and prompt contracts");
} finally {
  rmSync(scratch, { recursive: true, force: true });
}

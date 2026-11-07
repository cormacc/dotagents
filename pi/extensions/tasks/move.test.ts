import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { moveCandidates, runOverlayMove } from "./move.ts";
import { TasksOverlay } from "./overlay.ts";
import { otMoveTask, type OtMoveDestination } from "./ot.ts";
import { loadTasks } from "./index.ts";
import { getTaskId } from "./parser.ts";

const here = dirname(fileURLToPath(import.meta.url));
const repo = execFileSync("git", ["rev-parse", "--show-toplevel"], { cwd: here, encoding: "utf8" }).trim();
mkdirSync(join(repo, ".tmp"), { recursive: true });
const root = mkdtempSync(join(repo, ".tmp", "move-overlay-"));
const sourceId = "11111111-1111-4111-8111-111111111111";
const childId = "22222222-2222-4222-8222-222222222222";
const targetId = "33333333-3333-4333-8333-333333333333";
const path = join(root, "TASKS.org");
const block = (level: number, id: string, title: string) =>
  `${"*".repeat(level)} TODO ${title}\n:PROPERTIES:\n:CUSTOM_ID: ${id}\n:END:\n`;
const content = `* Tasks\n\n${block(2, sourceId, "Source")}\n${block(3, childId, "Child")}\n${block(2, targetId, "Target")}\n* Later\n`;

async function main() {
  writeFileSync(path, content);
  writeFileSync(join(root, "TASKS.local.org"), "#+SELECTED:\n");
  const tasks = (await loadTasks(root)).tasks;
  const source = tasks[0];
  const imported = [{ ...tasks[1], summary: "Imported", children: [], importChildren: [],
    propertyLines: [":CUSTOM_ID: 44444444-4444-4444-8444-444444444444"], sourcePath: join(root, "plan.org") }];
  source.importChildren = imported;
  assert.deepEqual(moveCandidates(tasks, source).map(getTaskId), [targetId]);
  assert.deepEqual(moveCandidates([...tasks, ...imported], source).map(getTaskId), [targetId]);

  let closed = 0;
  let requestedSource: unknown;
  const overlayArgs = [
    tasks, root, path, { requestRender() {} },
    { fg: (_token: string, text: string) => text, bg: (_token: string, text: string) => text,
      bold: (text: string) => text },
    () => { closed++; },
  ] as unknown as ConstructorParameters<typeof TasksOverlay>;
  overlayArgs[22] = (task) => { requestedSource = task; };
  const overlay = new TasksOverlay(...overlayArgs);
  assert.ok(overlay.render(160).some((line) => line.includes("Source")));
  overlay.handleInput("m");
  assert.equal(requestedSource, source, "m queues the cursor task for the move workflow");
  assert.equal(closed, 1, "the overlay closes before Pi dialogs open");

  let writes = 0;
  for (const stage of ["relation", "destination", "section", "blank-section"]) {
    const moved = await runOverlayMove({
      tasks, source,
      select: async (title, choices) => title.includes("relation")
        ? (stage === "relation" ? undefined : choices[stage.includes("section") ? 1 : 0])
        : undefined,
      input: async () => stage === "blank-section" ? "   " : undefined,
      move: async () => { writes++; },
    });
    assert.equal(moved, false, stage);
    assert.equal(readFileSync(path, "utf8"), content);
  }
  assert.equal(writes, 0, "cancelled dialogs never dispatch a write");

  for (const [kind, label] of [
    ["parent", "Last child of task"], ["section", "Existing section"],
    ["before", "Before task"], ["after", "After task subtree"],
  ] as const) {
    writeFileSync(path, content);
    const calls: OtMoveDestination[] = [];
    const moved = await runOverlayMove({
      tasks, source,
      select: async (title, choices) => title.includes("relation") ? label : choices[0],
      input: async () => " Later ",
      move: async (id, destination) => {
        assert.equal(id, sourceId);
        calls.push(destination);
        return otMoveTask(id, destination, { root });
      },
    });
    assert.equal(moved, true);
    assert.deepEqual(calls, [{ kind, value: kind === "section" ? "Later" : targetId }]);
    const after = (await loadTasks(root)).tasks;
    const target = after.find((t) => getTaskId(t) === targetId)!;
    if (kind === "parent") assert.equal(getTaskId(target.children[0]), sourceId);
    if (kind === "before") assert.deepEqual(after.map(getTaskId), [sourceId, targetId]);
    if (kind === "after" || kind === "section") assert.deepEqual(after.map(getTaskId), [targetId, sourceId]);
    const movedSource = kind === "parent" ? target.children[0] : after.find((t) => getTaskId(t) === sourceId)!;
    assert.equal(getTaskId(movedSource.children[0]), childId);
  }

  await assert.rejects(runOverlayMove({
    tasks, source,
    select: async (_title, choices) => choices[1],
    input: async () => "Missing",
    move: (id, destination) => otMoveTask(id, destination, { root }),
  }), /Section 'Missing' not found/);
  console.log("ok - move picker cancellation, owner filtering, four CLI destinations and errors");
}

main().catch((err) => { console.error(err); process.exitCode = 1; })
  .finally(() => rmSync(root, { recursive: true, force: true }));

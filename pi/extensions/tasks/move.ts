import { getTaskId, type Task } from "./parser.ts";
import type { OtMoveDestination } from "./ot.ts";

const relations = [
  { label: "Last child of task", kind: "parent" },
  { label: "Existing section", kind: "section" },
  { label: "Before task", kind: "before" },
  { label: "After task subtree", kind: "after" },
] as const;

/** Same-file destinations outside the source subtree; the CLI validates again. */
export function moveCandidates(tasks: Task[], source: Task): Task[] {
  const rows: Task[] = [];
  const visit = (nodes: Task[]) => {
    for (const task of nodes) {
      if (
        source.sourcePath && task.sourcePath === source.sourcePath && getTaskId(task) &&
        (task.lineNumber < source.lineNumber || task.lineNumber >= source.endLine)
      ) rows.push(task);
      visit(task.children);
      visit(task.importChildren ?? []);
    }
  };
  visit(tasks);
  return rows;
}

/** Resolve a move through Pi dialogs, then delegate the sole write to `move`. */
export async function runOverlayMove(options: {
  tasks: Task[];
  source: Task;
  select: (title: string, choices: string[]) => Promise<string | undefined>;
  input: (title: string) => Promise<string | undefined>;
  move: (id: string, destination: OtMoveDestination) => Promise<unknown>;
}): Promise<boolean> {
  const { source, tasks, select, input, move } = options;
  const id = getTaskId(source);
  if (!id) throw new Error("Cannot move a task without a :CUSTOM_ID:.");
  const label = await select("Move: choose relation", relations.map((r) => r.label));
  const relation = relations.find((r) => r.label === label);
  if (!relation) return false;

  let value: string | undefined;
  if (relation.kind === "section") {
    value = (await input("Existing section name"))?.trim();
  } else {
    const candidates = moveCandidates(tasks, source);
    if (!candidates.length) throw new Error("No same-file destinations.");
    const choices = candidates.map((t) => `${t.summary} [${getTaskId(t)}]`);
    const picked = await select(`Move: choose ${relation.kind} destination`, choices);
    const index = picked === undefined ? -1 : choices.indexOf(picked);
    value = index < 0 ? undefined : getTaskId(candidates[index]) ?? undefined;
  }
  if (!value) return false;
  await move(id, { kind: relation.kind, value });
  return true;
}

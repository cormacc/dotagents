/**
 * Babashka Tasks Extension
 *
 * Detects `bb.edn` in the project root and registers a `/bb` slash command
 * with auto-completion for available babashka tasks.
 *
 * - Tasks run through pi's built-in bash tool, like any other shell command.
 */
import {
  createBashToolDefinition,
  type ExtensionAPI,
} from "@mariozechner/pi-coding-agent";
import type { AutocompleteItem } from "@mariozechner/pi-tui";
import { existsSync } from "node:fs";
import { join } from "node:path";

const MAX_NOTIFY_OUTPUT_LENGTH = 4000;

function shellQuote(value: string): string {
  return `'${value.replace(/'/g, `'\\''`)}'`;
}

function summarizeOutput(output: string): string {
  if (output.length <= MAX_NOTIFY_OUTPUT_LENGTH) return output;
  return `${output.slice(0, MAX_NOTIFY_OUTPUT_LENGTH)}\n…[truncated ${output.length - MAX_NOTIFY_OUTPUT_LENGTH} chars]`;
}

export default function (pi: ExtensionAPI) {
  pi.on("session_start", async (_event, ctx) => {
    const bbEdn = join(ctx.cwd, "bb.edn");
    if (!existsSync(bbEdn)) return;

    // ── parse available tasks from `bb tasks` ────────────

    let tasks: { name: string; description: string }[] = [];

    async function refreshTasks(): Promise<void> {
      try {
        const r = await pi.exec("bb", ["tasks"], {
          cwd: ctx.cwd,
          timeout: 10000,
        });
        if (r.code !== 0) return;

        // `bb tasks` outputs lines like:
        //   clean    Remove build artifacts
        //   watch    Start file watcher
        tasks = [];
        for (const line of r.stdout.split("\n")) {
          if (line.startsWith("The following tasks are available")) continue;
          const match = line.match(/^(\S+)\s+(.*)/);
          if (match) {
            tasks.push({ name: match[1], description: match[2].trim() });
          }
        }
      } catch {}
    }

    await refreshTasks();

    if (tasks.length === 0) {
      ctx.ui.notify("bb-tasks: no tasks found in bb.edn", "warning");
      return;
    }

    ctx.ui.notify(`bb-tasks: ${tasks.length} tasks available`, "info");

    // ── run tasks through pi's built-in bash tool ────────

    async function runTask(taskName: string): Promise<string> {
      const command = `bb ${shellQuote(taskName)}`;
      const bash = createBashToolDefinition(ctx.cwd);
      const result = await bash.execute(
        `bb-tasks-${taskName}`,
        { command },
        ctx.signal,
        undefined,
        ctx,
      );
      return result.content
        .filter((item): item is { type: "text"; text: string } => item.type === "text")
        .map((item) => item.text)
        .join("\n");
    }

    // ── register /bb command ─────────────────────────────

    pi.registerCommand("bb", {
      description: "Run a babashka task",

      getArgumentCompletions(prefix: string): AutocompleteItem[] | null {
        const items = tasks.map((t) => ({
          value: t.name,
          label: t.name,
          description: t.description || undefined,
        }));
        if (!prefix) return items.length > 0 ? items : null;
        const filtered = items.filter((i) =>
          i.value.toLowerCase().startsWith(prefix.toLowerCase()),
        );
        return filtered.length > 0 ? filtered : null;
      },

      handler: async (args, ctx) => {
        const taskName = args?.trim();
        if (!taskName) {
          // No argument — list tasks
          const listing = tasks
            .map((t) => `  ${t.name}  ${t.description}`)
            .join("\n");
          ctx.ui.notify(`Available tasks:\n${listing}`, "info");
          return;
        }

        const task = tasks.find((t) => t.name === taskName);
        if (!task) {
          ctx.ui.notify(`Unknown task: ${taskName}`, "error");
          return;
        }

        try {
          ctx.ui.notify(`Running: bb ${taskName}`, "info");
          const output = await runTask(taskName);
          ctx.ui.notify(
            `$ bb ${taskName}\n${summarizeOutput(output)}`,
            "info",
          );
        } catch (error) {
          const message = error instanceof Error ? error.message : String(error);
          ctx.ui.notify(
            `bb ${taskName} failed:\n${summarizeOutput(message)}`,
            "error",
          );
        }
      },
    });
  });
}

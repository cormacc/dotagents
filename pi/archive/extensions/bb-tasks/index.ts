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
  type ExtensionContext,
} from "@mariozechner/pi-coding-agent";
import {
  truncateToWidth,
  type AutocompleteItem,
  type Component,
} from "@mariozechner/pi-tui";
import { existsSync } from "node:fs";
import { join } from "node:path";

const MAX_NOTIFY_OUTPUT_LENGTH = 4000;
const PREVIEW_LINES = 20;
const BB_BASH_MESSAGE_TYPE = "bb-tasks-bash";

interface BbBashMessageDetails {
  command: string;
  output: string;
  error: boolean;
}

function summarizeOutput(output: string): string {
  if (output.length <= MAX_NOTIFY_OUTPUT_LENGTH) return output;
  return `${output.slice(0, MAX_NOTIFY_OUTPUT_LENGTH)}\n…[truncated ${output.length - MAX_NOTIFY_OUTPUT_LENGTH} chars]`;
}

function renderBbBashMessage(
  details: BbBashMessageDetails | undefined,
  expanded: boolean,
  theme: { fg(key: string, text: string): string; bold(text: string): string },
): Component {
  const command = details?.command ?? "bb";
  const output = details?.output ?? "";
  const error = details?.error ?? false;

  return {
    render(width: number): string[] {
      const lineWidth = Math.max(0, width);
      const border = theme.fg("bashMode", "─".repeat(lineWidth));
      const rawOutputLines = (output.trimEnd() || "(no output)")
        .replace(/\r\n/g, "\n")
        .replace(/\r/g, "\n")
        .split("\n");
      const outputLines = expanded
        ? rawOutputLines
        : rawOutputLines.slice(-PREVIEW_LINES);
      const hiddenLineCount = rawOutputLines.length - outputLines.length;
      const lines = [
        border,
        truncateToWidth(
          `  ${theme.fg("bashMode", theme.bold(`$ ${command}`))}`,
          lineWidth,
        ),
        ...outputLines.map((line) =>
          truncateToWidth(`  ${theme.fg("muted", line)}`, lineWidth)
        ),
      ];

      if (hiddenLineCount > 0) {
        lines.push(
          truncateToWidth(
            `  ${theme.fg("muted", `... ${hiddenLineCount} more lines`)}`,
            lineWidth,
          ),
        );
      }
      if (error) {
        lines.push(truncateToWidth(`  ${theme.fg("error", "(failed)")}`, lineWidth));
      }
      lines.push(border);
      return lines;
    },
    invalidate(): void {},
  };
}

export default function (pi: ExtensionAPI) {
  pi.registerMessageRenderer(BB_BASH_MESSAGE_TYPE, (message, { expanded }, theme) =>
    renderBbBashMessage(message.details as BbBashMessageDetails | undefined, expanded, theme)
  );

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
    } else {
      ctx.ui.notify(`bb-tasks: ${tasks.length} tasks available`, "info");
    }

    // ── pass /bb arguments through pi's built-in bash tool ────────

    async function runBbCommand(
      commandArgs: string,
      commandCtx: ExtensionContext,
    ): Promise<{ command: string; output: string }> {
      const command = `bb ${commandArgs}`;
      const bash = createBashToolDefinition(commandCtx.cwd);
      const result = await bash.execute(
        "bb-tasks",
        { command },
        commandCtx.signal,
        undefined,
        commandCtx,
      );
      const output = result.content
        .filter((item): item is { type: "text"; text: string } => item.type === "text")
        .map((item) => item.text)
        .join("\n");
      return { command, output };
    }

    function sendBbBashMessage(
      command: string,
      output: string,
      error: boolean,
    ): void {
      pi.sendMessage({
        customType: BB_BASH_MESSAGE_TYPE,
        content: `$ ${command}\n${output}`,
        display: true,
        details: { command, output: summarizeOutput(output), error },
      }, { triggerTurn: false });
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
        const commandArgs = args?.trim();
        if (!commandArgs) {
          // No argument — list tasks
          if (tasks.length === 0) {
            ctx.ui.notify("No bb tasks discovered. Try `/bb tasks` to inspect bb directly.", "warning");
            return;
          }
          const listing = tasks
            .map((t) => `  ${t.name}  ${t.description}`)
            .join("\n");
          ctx.ui.notify(`Available tasks:\n${listing}`, "info");
          return;
        }

        try {
          const { command, output } = await runBbCommand(commandArgs, ctx);
          sendBbBashMessage(command, output, false);
        } catch (error) {
          const command = `bb ${commandArgs}`;
          const message = error instanceof Error ? error.message : String(error);
          sendBbBashMessage(command, message, true);
        }
      },
    });
  });
}

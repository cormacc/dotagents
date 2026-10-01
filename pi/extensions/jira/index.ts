/**
 * Jira Extension for pi — agent-driven workflows against the Atlassian MCP.
 *
 * Companion artefacts:
 * - `:LINKED_ISSUES:` drawer property + org-native `#+LINK:` declarations are
 *   owned by the `tasks` extension (tracker-agnostic). This extension
 *   reads/writes those when interacting with Jira issues but does not
 *   define them.
 * - `skills/org-jira/SKILL.md` documents the Jira-specific
 *   conventions (PROJ-NNN key shape, #+JIRA_* keywords, agent prompts).
 *
 * Jira access goes through the `atlassian` MCP server. The deferred
 * (codemode-callable) `jira_*` tools in `./tools.ts` call `mcp__atlassian__*` through
 * `ctx.executeTool()`; a slash command (or the auto-transition listener)
 * resolves its inputs in TypeScript and sends the model one hidden line that
 * names a single `codemode` call of one of those tools.
 */

import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { existsSync, readFileSync } from "node:fs";
import { getAgentPath } from "../lib/agent-paths.ts";
import { getExtensionName } from "../lib/pi-utils.ts";
import {
  loadJiraConfig,
  registerJiraTools,
  resolveSelectedTask,
  resolveTaskKeys,
} from "./tools.ts";
import {
  ATLASSIAN_TOOL_PREFIX,
  buildClaimTrigger,
  buildCloneTrigger,
  buildCommentTrigger,
  buildCreateTrigger,
  buildGetTrigger,
  buildTransitionTrigger,
  parseCreateArgs,
  resolveKey,
} from "./utils.ts";

export { getFileKeyword, resolveKey } from "./utils.ts";

/** User-overridable settings file. */
interface UserSettings {
  /** Mirror local TODO→STARTED→DONE on linked Jira issues. Default: false. */
  autoTransition: boolean;
}

function loadUserSettings(): UserSettings {
  const defaults: UserSettings = { autoTransition: false };
  try {
    const settingsPath = getAgentPath("jira-ext.json");
    if (!existsSync(settingsPath)) return defaults;
    const raw = readFileSync(settingsPath, "utf-8");
    const parsed = JSON.parse(raw) as Partial<UserSettings>;
    return {
      autoTransition:
        typeof parsed.autoTransition === "boolean"
          ? parsed.autoTransition
          : defaults.autoTransition,
    };
  } catch {
    return defaults;
  }
}

const EXT_NAME = getExtensionName(import.meta.url);

// `ATLASSIAN_TOOL_PREFIX` (`mcp__atlassian__`) lives in `./utils.ts`: pi's native
// MCP client registers every `atlassian` server tool under that prefix.

/**
 * Inspect the Atlassian MCP availability surface.
 *
 * pi's native MCP client registers each tool of the `atlassian` server as
 * `mcp__atlassian__<tool>`, so MCP counts as "available" when
 * `pi.getAllTools()` contains at least one such tool.
 */
function getAtlassianAvailability(pi: ExtensionAPI): {
  tools: string[];
  isAvailable: boolean;
} {
  try {
    const tools = pi
      .getAllTools()
      .map((t) => t.name)
      .filter((name) => name.startsWith(ATLASSIAN_TOOL_PREFIX))
      .sort();
    return { tools, isAvailable: tools.length > 0 };
  } catch {
    return { tools: [], isAvailable: false };
  }
}

const DISCONNECTED = "Atlassian MCP: disconnected. Run /mcp login atlassian first.";
const NO_SELECTION =
  "No selected task. Press `s` on a task in /tasks first, or set #+SELECTED: in TASKS.local.org.";

/** The selected task, or null after notifying why there is none. */
async function selectedTaskOrNotify(
  ctx: { cwd: string; ui: { notify(msg: string, level: "info" | "warn" | "error"): void } },
  cfg: Awaited<ReturnType<typeof loadJiraConfig>>,
) {
  try {
    const task = await resolveSelectedTask(ctx.cwd, cfg);
    if (!task) ctx.ui.notify(NO_SELECTION, "warn");
    return task;
  } catch (e) {
    ctx.ui.notify(`Could not read the selected task: ${(e as Error).message}`, "error");
    return null;
  }
}

export default function (pi: ExtensionAPI) {
  registerJiraTools(pi);

  /**
   * Send the model one hidden line. `display: false` hides it from the
   * terminal only; pi still sends it to the model, so each trigger stays a
   * single `codemode` call of one `jira_*` tool (see `buildGetTrigger`).
   */
  const sendTrigger = (content: string, deliverAs?: "followUp"): void => {
    pi.sendMessage(
      { customType: "jira", content, display: false },
      { triggerTurn: true, ...(deliverAs ? { deliverAs } : {}) },
    );
  };

  // `pi.events` is shared between extension instances, so subscriptions must
  // be released when this instance's session is shut down or replaced.
  const eventUnsubs: Array<() => void> = [];
  pi.on("session_shutdown", async () => {
    while (eventUnsubs.length > 0) {
      try {
        eventUnsubs.pop()?.();
      } catch {
        // Best-effort cleanup: one listener must not strand the rest.
      }
    }
  });

  pi.registerCommand("jira", {
    description:
      "Jira integration via the Atlassian MCP server (status, clone, get, claim, comment, create)",
    handler: async (args, ctx) => {
      const trimmed = args.trim();
      const parts = trimmed.length === 0 ? [] : trimmed.split(/\s+/);
      const subcommand = (parts[0] ?? "status").toLowerCase();
      const rest = parts.slice(1);
      const availability = getAtlassianAvailability(pi);
      const isConnected = availability.isAvailable;

      if (subcommand === "status") {
        if (!isConnected) {
          ctx.ui.notify(
            "Atlassian MCP: disconnected. Run /mcp login atlassian to enable Jira workflows.",
            "warn",
          );
          return;
        }
        const sample = availability.tools.slice(0, 3).join(", ");
        const more =
          availability.tools.length > 3
            ? `, +${availability.tools.length - 3} more`
            : "";
        ctx.ui.notify(
          `Atlassian MCP: connected (${availability.tools.length} tools — ${sample}${more}).`,
          "info",
        );
        return;
      }

      if (subcommand === "get" || subcommand === "clone") {
        if (rest.length === 0) {
          ctx.ui.notify(
            `Usage: /jira ${subcommand} KEY [KEY...]   (KEY = PROJ-NNN or a bare number when #+JIRA_PROJECT is set)`,
            "warn",
          );
          return;
        }
        if (!isConnected) {
          ctx.ui.notify(DISCONNECTED, "warn");
          return;
        }

        const cfg = await loadJiraConfig(ctx.cwd);
        const resolved: string[] = [];
        const errors: string[] = [];
        for (const arg of rest) {
          const r = resolveKey(arg, cfg.project);
          if ("key" in r) resolved.push(r.key);
          else errors.push(r.error);
        }
        if (errors.length > 0) {
          for (const e of errors) ctx.ui.notify(e, "error");
          return;
        }

        sendTrigger(
          subcommand === "get" ? buildGetTrigger(resolved) : buildCloneTrigger(resolved),
        );
        ctx.ui.notify(
          `Dispatched /jira ${subcommand} for ${resolved.length} issue${resolved.length === 1 ? "" : "s"}: ${resolved.join(", ")}.`,
          "info",
        );
        return;
      }

      if (subcommand === "claim" || subcommand === "comment") {
        if (!isConnected) {
          ctx.ui.notify(DISCONNECTED, "warn");
          return;
        }
        const body = rest.join(" ").trim();
        if (subcommand === "comment" && !body) {
          ctx.ui.notify(
            "Usage: /jira comment <markdown body>   (operates on the selected task's :LINKED_ISSUES:)",
            "warn",
          );
          return;
        }
        const cfg = await loadJiraConfig(ctx.cwd);
        const task = await selectedTaskOrNotify(ctx, cfg);
        if (!task) return;
        if (task.keys.length === 0) {
          ctx.ui.notify("The selected task has no Jira-shaped :LINKED_ISSUES:.", "warn");
          return;
        }
        sendTrigger(
          subcommand === "claim"
            ? buildClaimTrigger(task.keys)
            : buildCommentTrigger(task.keys, body),
        );
        ctx.ui.notify(
          `Dispatched /jira ${subcommand} for ${task.keys.join(", ")}.`,
          "info",
        );
        return;
      }

      if (subcommand === "create") {
        if (!isConnected) {
          ctx.ui.notify(DISCONNECTED, "warn");
          return;
        }
        const opts = parseCreateArgs(rest);
        const cfg = await loadJiraConfig(ctx.cwd);
        const project = opts.project ?? cfg.project;
        if (!project) {
          ctx.ui.notify(
            "Usage: /jira create [PROJECT] [--type Task|Story|Bug|Epic]   (or set #+JIRA_PROJECT in TASKS.setup.org / TASKS.local.org)",
            "warn",
          );
          return;
        }
        const task = await selectedTaskOrNotify(ctx, cfg);
        if (!task) return;
        sendTrigger(buildCreateTrigger(task.id, project, opts.type));
        ctx.ui.notify(
          `Dispatched /jira create for project ${project} (type ${opts.type}).`,
          "info",
        );
        return;
      }

      ctx.ui.notify(
        `Unknown subcommand "${subcommand}". Available: status, clone, get, claim, comment, create.`,
        "warn",
      );
    },
  });

  // ── Auto-transition ────────────────────────────────────────────────
  //
  // Listen for `tasks:status-changed` events from the `tasks` extension.
  // When the user toggles a task to STARTED or DONE and the
  // `autoTransition` setting is enabled, resolve the task's Jira keys with
  // `ot` and send a hidden trigger that mirrors the change through
  // `jira_transition`. A task with no Jira key sends nothing.
  //
  // Disabled by default — set `{ "autoTransition": true }` in
  // The configured agent directory's jira-ext.json to enable.

  eventUnsubs.push(pi.events.on(
    "tasks:status-changed",
    async (payload: {
      id: string | null;
      status: string;
      prevStatus: string;
      summary: string;
      closed: boolean;
    }) => {
      const settings = loadUserSettings();
      if (!settings.autoTransition) return;
      if (!payload || !payload.id) return;
      // Only mirror two transitions: TODO→STARTED and →DONE.
      const newStatus =
        payload.status === "STARTED"
          ? "STARTED"
          : payload.status === "DONE"
            ? "DONE"
            : null;
      if (!newStatus) return;
      // No-op when the MCP isn't connected; user surfaces a notification
      // via `/jira status` if they want to know.
      if (!getAtlassianAvailability(pi).isAvailable) return;

      // The `tasks` event payload doesn't include the cwd; fall back to
      // process.cwd() at the time of the event. The vast majority of pi
      // sessions run with a stable cwd, and cross-cwd auto-transition
      // would need a richer event.
      const proc = (globalThis as { [key: string]: unknown })["process"] as
        | { cwd?: () => string }
        | undefined;
      const cwd = proc?.cwd?.() ?? ".";
      try {
        const cfg = await loadJiraConfig(cwd);
        const keys = await resolveTaskKeys(cwd, payload.id, cfg);
        if (keys.length === 0) return;
        // followUp queues the trigger when the event fires mid-turn.
        sendTrigger(buildTransitionTrigger(keys, newStatus), "followUp");
      } catch {
        // ot missing or the task unreadable: nothing to mirror.
      }
    },
  ));

  // Hook for follow-up tasks: the keybindings extension can be advised
  // here that this extension exists, but no menu entries are contributed
  // until the workflow commands themselves land.
  void EXT_NAME;
}

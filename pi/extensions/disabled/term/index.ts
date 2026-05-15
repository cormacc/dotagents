import type { ExtensionAPI } from "@mariozechner/pi-coding-agent";
import { Type } from "@sinclair/typebox";

import { TmuxSessionManager, type TmuxSessionInfo } from "./tmux-sessions.js";
import { TmuxBackend } from "./tmux.js";
import { SwayBackend } from "./sway.js";
import { KittyBackend } from "./kitty.js";
import { GhosttyBackend } from "./ghostty.js";
import type { MonitorBackend } from "./types.js";
import { sanitizeName, sleep } from "./types.js";
import { getExtensionName, registerLeaderMenu } from "../lib/pi-utils.js";

const EXT_NAME = getExtensionName(import.meta.url);
const DEFAULT_SESSION = "shell";

/** Cleanup handle for keybinding suggestions, to avoid duplicates on reload. */
let cleanupKeybindings: (() => void) | null = null;

interface SessionMeta {
  command?: string;
  mode?: "watch" | "quiet";
  startedAt: number;
}

export default function (pi: ExtensionAPI) {
  pi.registerFlag("no-mirror", {
    description: "Disable term monitor/session integration",
    type: "boolean",
    default: false,
  });

  pi.on("session_start", async (_event, ctx) => {
    if (pi.getFlag("no-mirror")) return;

    const inGhostty = (process.env.TERM_PROGRAM || "").toLowerCase() === "ghostty";

    if (
      !process.env.KITTY_WINDOW_ID &&
      !process.env.SWAYSOCK &&
      !process.env.TMUX &&
      !inGhostty
    ) {
      return;
    }

    const exec = pi.exec.bind(pi);
    const sessions = new TmuxSessionManager(exec);
    let backend: MonitorBackend;
    // Backend preference order: tmux → kitty → ghostty → sway.
    if (process.env.TMUX) {
      backend = new TmuxBackend(exec, sessions);
    } else if (process.env.KITTY_WINDOW_ID) {
      backend = new KittyBackend(exec, sessions);
    } else if (inGhostty) {
      backend = new GhosttyBackend(exec, sessions);
    } else {
      backend = new SwayBackend(exec, sessions);
    }
    const sessionUi = ctx.ui;
    const sessionMeta = new Map<string, SessionMeta>();

    let activeSessionName = DEFAULT_SESSION;
    let monitorVisible = false;

    function rememberSession(
      name: string,
      meta: Partial<SessionMeta> = {},
    ): void {
      const existing = sessionMeta.get(name);
      sessionMeta.set(name, {
        startedAt: existing?.startedAt ?? Date.now(),
        command: meta.command ?? existing?.command,
        mode: meta.mode ?? existing?.mode,
      });
    }

    async function ensureDefaultSession(): Promise<boolean> {
      if (await sessions.hasSession(DEFAULT_SESSION)) {
        rememberSession(DEFAULT_SESSION);
        return true;
      }
      const created = await sessions.createSession(DEFAULT_SESSION, ctx.cwd);
      if (created) rememberSession(DEFAULT_SESSION);
      return created;
    }

    function orderSessions(infos: TmuxSessionInfo[]): TmuxSessionInfo[] {
      return [...infos].sort((a, b) => {
        if (a.name === DEFAULT_SESSION) return -1;
        if (b.name === DEFAULT_SESSION) return 1;
        return a.name.localeCompare(b.name);
      });
    }

    async function loadSessions(): Promise<TmuxSessionInfo[]> {
      return orderSessions(await sessions.listSessions());
    }

    async function resolveSessionRef(ref: string): Promise<string | null> {
      const infos = await loadSessions();
      const idx = parseInt(ref, 10);
      if (!isNaN(idx) && String(idx) === ref) {
        if (idx < 1 || idx > infos.length) return null;
        return infos[idx - 1]?.name ?? null;
      }

      const lower = ref.toLowerCase();
      return (
        infos.find((info) => info.name.toLowerCase() === lower)?.name ?? null
      );
    }

    async function ensureActiveSessionExists(): Promise<boolean> {
      if (await sessions.hasSession(activeSessionName)) return true;
      activeSessionName = DEFAULT_SESSION;
      return ensureDefaultSession();
    }

    async function updateStatus(): Promise<void> {
      monitorVisible = await backend.isVisible();
      const infos = await loadSessions();
      const theme = sessionUi.theme;

      const parts = infos.map((info, idx) => {
        const label = `[${idx + 1}] ${info.name}`;
        const attached = info.attached > 0 ? theme.fg("warning", "*") : "";
        const text = `${label}${attached}`;
        return info.name === activeSessionName
          ? theme.fg("accent", theme.bold(text))
          : theme.fg("dim", text);
      });

      const toggleHint = monitorVisible
        ? theme.fg("dim", "'t hide")
        : theme.fg("warning", "'t show");
      const navHint = infos.length > 1 ? theme.fg("dim", "'h 'l") + "  " : "";
      const sep = theme.fg("border", " │ ");
      const body = parts.length ? parts.join(theme.fg("dim", " / ")) : "(none)";

      const status =
        " " +
        theme.fg("border", "[") +
        " " +
        theme.fg("dim", "Sessions: ") +
        body +
        sep +
        navHint +
        toggleHint +
        " " +
        theme.fg("border", "]") +
        " ";

      sessionUi.setStatus("term-sessions", status);
    }

    async function attachSession(name: string, reveal = true): Promise<boolean> {
      if (!(await sessions.hasSession(name))) return false;
      activeSessionName = name;
      if (reveal) {
        monitorVisible = await backend.show(name);
      } else if (monitorVisible) {
        monitorVisible = await backend.attachSession(name);
      }
      await updateStatus();
      return true;
    }

    async function cycleSession(direction: 1 | -1): Promise<string | null> {
      const infos = await loadSessions();
      if (infos.length <= 1) return null;

      const names = infos.map((info) => info.name);
      const currentName = names.includes(activeSessionName)
        ? activeSessionName
        : DEFAULT_SESSION;
      const currentIdx = names.indexOf(currentName);
      const nextIdx =
        (currentIdx + direction + names.length) % names.length;
      const nextName = names[nextIdx];

      activeSessionName = nextName;
      if (monitorVisible) {
        monitorVisible = await backend.attachSession(nextName);
      }
      await updateStatus();
      return nextName;
    }

    async function termPrev(): Promise<void> {
      const next = await cycleSession(-1);
      if (!next) {
        sessionUi.notify("No other sessions", "info");
        return;
      }
      sessionUi.notify(`Switched to ${next}`, "info");
    }

    async function termNext(): Promise<void> {
      const next = await cycleSession(1);
      if (!next) {
        sessionUi.notify("No other sessions", "info");
        return;
      }
      sessionUi.notify(`Switched to ${next}`, "info");
    }

    async function showActiveSession(): Promise<boolean> {
      if (!(await ensureActiveSessionExists())) {
        sessionUi.notify("Failed to create the default tmux session", "error");
        return false;
      }
      monitorVisible = await backend.show(activeSessionName);
      if (!monitorVisible) {
        await updateStatus();
        sessionUi.notify("Failed to show the monitor pane", "error");
        return false;
      }
      return true;
    }

    async function termToggle(): Promise<void> {
      monitorVisible = await backend.isVisible();
      if (monitorVisible) {
        await backend.hide();
        monitorVisible = false;
      } else if (!(await showActiveSession())) {
        return;
      }
      await updateStatus();
      sessionUi.notify(
        monitorVisible ? "Monitor pane shown" : "Monitor pane hidden",
        "info",
      );
    }

    async function termFocus(): Promise<void> {
      if (!(await backend.isVisible())) {
        if (!(await showActiveSession())) return;
      } else {
        monitorVisible = true;
      }
      await backend.focus();
      await updateStatus();
      sessionUi.notify("Focused monitor pane", "info");
    }

    async function termAttach(name: string): Promise<void> {
      if (!(await attachSession(name, true))) {
        sessionUi.notify(`No session named \"${name}\"`, "error");
        return;
      }
      sessionUi.notify(`Attached monitor to ${name}`, "info");
    }

    async function termRun(command: string): Promise<void> {
      if (!(await ensureActiveSessionExists())) {
        sessionUi.notify("Failed to create the default tmux session", "error");
        return;
      }
      if (!(await backend.isVisible()) && !(await showActiveSession())) {
        return;
      }
      await sessions.sendCommand(activeSessionName, ctx.cwd, command);
      await updateStatus();
      sessionUi.notify(`Sent command to ${activeSessionName}`, "info");
    }

    async function termNew(name: string): Promise<void> {
      const sessionName = sanitizeName(name);
      if (await sessions.hasSession(sessionName)) {
        sessionUi.notify(`Session \"${sessionName}\" already exists`, "error");
        return;
      }
      const created = await sessions.createSession(sessionName, ctx.cwd);
      if (!created) {
        sessionUi.notify(`Failed to create session \"${sessionName}\"`, "error");
        return;
      }
      rememberSession(sessionName);
      activeSessionName = sessionName;
      monitorVisible = await backend.show(sessionName);
      await updateStatus();
      sessionUi.notify(
        monitorVisible
          ? `Created session \"${sessionName}\"`
          : `Created session \"${sessionName}\" (monitor not shown)`,
        monitorVisible ? "info" : "warning",
      );
    }

    async function termSpawn(command: string, title?: string): Promise<void> {
      let sessionName: string;
      if (title) {
        sessionName = sanitizeName(title);
      } else {
        const raw = command.length > 16 ? `${command.slice(0, 16)}…` : command;
        sessionName = sanitizeName(raw);
      }

      if (await sessions.hasSession(sessionName)) {
        sessionUi.notify(`Session \"${sessionName}\" already exists`, "error");
        return;
      }

      const created = await sessions.createSession(sessionName, ctx.cwd, command);
      if (!created) {
        sessionUi.notify(`Failed to create session \"${sessionName}\"`, "error");
        return;
      }

      rememberSession(sessionName, { command, mode: "watch" });
      activeSessionName = sessionName;
      monitorVisible = await backend.show(sessionName);
      await updateStatus();
      sessionUi.notify(
        monitorVisible
          ? `Spawned \"${sessionName}\": ${command}`
          : `Spawned \"${sessionName}\" but failed to show the monitor`,
        monitorVisible ? "info" : "warning",
      );
    }

    async function termKill(name: string): Promise<void> {
      if (name === DEFAULT_SESSION) {
        sessionUi.notify("Refusing to kill the default shell session", "warning");
        return;
      }
      if (!(await sessions.hasSession(name))) {
        sessionUi.notify(`No session named \"${name}\"`, "error");
        return;
      }

      await sessions.killSession(name);
      sessionMeta.delete(name);

      if (activeSessionName === name) {
        await ensureDefaultSession();
        activeSessionName = DEFAULT_SESSION;
        if (monitorVisible) {
          monitorVisible = await backend.show(DEFAULT_SESSION);
          if (!monitorVisible) {
            sessionUi.notify(
              "Killed the active session, but failed to show the default shell monitor",
              "warning",
            );
          }
        }
      }

      await updateStatus();
      sessionUi.notify(`Killed \"${name}\"`, "info");
    }

    async function showListNotification(): Promise<void> {
      const infos = await loadSessions();
      if (infos.length === 0) {
        sessionUi.notify("No sessions", "info");
        return;
      }
      const lines = infos.map((info, idx) => {
        const marker = info.name === activeSessionName ? "*" : " ";
        return `${marker} [${idx + 1}] ${info.name} (${info.windows}w/${info.attached}a)`;
      });
      sessionUi.notify(lines.join(" | "), "info");
    }

    // ── event bus listeners ──────────────────────────────

    const unsubTermPrev = pi.events.on("term:prev", () => {
      void termPrev();
    });

    const unsubTermNext = pi.events.on("term:next", () => {
      void termNext();
    });

    const unsubTermToggle = pi.events.on("term:toggle", () => {
      void termToggle();
    });

    const unsubTermFocus = pi.events.on("term:focus", () => {
      void termFocus();
    });

    const unsubTermAttach = pi.events.on(
      "term:attach",
      (data: { name: string }) => {
        void termAttach(data.name);
      },
    );

    const unsubTermRun = pi.events.on(
      "term:run",
      (data: { command: string }) => {
        void termRun(data.command);
      },
    );

    const unsubTermNew = pi.events.on(
      "term:new",
      (data: { name: string }) => {
        void termNew(data.name);
      },
    );

    const unsubTermSpawn = pi.events.on(
      "term:spawn",
      (data: { command: string; title?: string }) => {
        void termSpawn(data.command, data.title);
      },
    );

    const unsubTermKill = pi.events.on(
      "term:kill",
      (data: { name: string }) => {
        void termKill(data.name);
      },
    );

    // ── tools ────────────────────────────────────────────

    pi.registerTool({
      name: "start_process",
      label: "Start tmux Session",
      description:
        "Create a named tmux session in the term server and optionally run an initial command.",
      parameters: Type.Object({
        name: Type.String({
          description:
            'Short name for this process/session (e.g. "server", "tests", "repl")',
        }),
        command: Type.String({ description: "Command to run" }),
        mode: Type.Optional(
          Type.Union([Type.Literal("watch"), Type.Literal("quiet")], {
            description:
              'Compatibility flag retained for existing prompts. Auto-watch notifications are no longer emitted.',
            default: "watch",
          }),
        ),
      }),
      async execute(_id, params, _signal, _onUpdate, toolCtx) {
        const name = sanitizeName(params.name);
        if (await sessions.hasSession(name)) {
          return {
            content: [
              {
                type: "text",
                text: `Session \"${name}\" already exists.`,
              },
            ],
            isError: true,
          };
        }

        const created = await sessions.createSession(name, toolCtx.cwd, params.command);
        if (!created) {
          return {
            content: [
              {
                type: "text",
                text: `Failed to create session \"${name}\".`,
              },
            ],
            isError: true,
          };
        }

        rememberSession(name, { command: params.command, mode: params.mode || "watch" });
        await updateStatus();
        await sleep(300);
        const snapshot = await sessions.captureSession(name, 80);

        return {
          content: [
            {
              type: "text",
              text:
                `Started tmux session \"${name}\".` +
                (snapshot ? `\n\n${snapshot}` : ""),
            },
          ],
          details: {
            name,
            command: params.command,
            mode: params.mode || "watch",
            socket: sessions.socketName,
          },
        };
      },
    });

    pi.registerTool({
      name: "send_input",
      label: "Send Input",
      description: "Send text input to a managed tmux session.",
      parameters: Type.Object({
        name: Type.String({ description: "Session name" }),
        text: Type.String({ description: "Text to send" }),
        enter: Type.Optional(
          Type.Boolean({ description: "Send Enter after text (default: true)" }),
        ),
      }),
      async execute(_id, params) {
        if (!(await sessions.hasSession(params.name))) {
          return {
            content: [
              {
                type: "text",
                text: `No session named \"${params.name}\".`,
              },
            ],
            isError: true,
          };
        }

        await sessions.sendText(params.name, params.text);
        if (params.enter !== false) {
          await sessions.sendEnter(params.name);
        }
        await sleep(200);
        const output = await sessions.captureSession(params.name, 60);

        return {
          content: [{ type: "text", text: output || "(no output)" }],
          details: { name: params.name, socket: sessions.socketName },
        };
      },
    });

    pi.registerTool({
      name: "read_process",
      label: "Read Process",
      description: "Read recent output from a managed tmux session.",
      parameters: Type.Object({
        name: Type.String({ description: "Session name" }),
        lines: Type.Optional(
          Type.Number({ description: "Lines of scrollback (default: 200)" }),
        ),
      }),
      async execute(_id, params) {
        if (!(await sessions.hasSession(params.name))) {
          return {
            content: [
              {
                type: "text",
                text: `No session named \"${params.name}\".`,
              },
            ],
            isError: true,
          };
        }

        const output = await sessions.captureSession(params.name, params.lines || 200);
        return {
          content: [
            {
              type: "text",
              text: `[${params.name}]\n${output || "(no output)"}`,
            },
          ],
          details: { name: params.name, socket: sessions.socketName },
        };
      },
    });

    pi.registerTool({
      name: "stop_process",
      label: "Stop Process",
      description: "Kill a managed tmux session.",
      parameters: Type.Object({
        name: Type.String({ description: "Session name" }),
      }),
      async execute(_id, params) {
        if (params.name === DEFAULT_SESSION) {
          return {
            content: [
              {
                type: "text",
                text: "Refusing to kill the default shell session.",
              },
            ],
            isError: true,
          };
        }
        if (!(await sessions.hasSession(params.name))) {
          return {
            content: [
              {
                type: "text",
                text: `No session named \"${params.name}\".`,
              },
            ],
            isError: true,
          };
        }

        await sessions.killSession(params.name);
        sessionMeta.delete(params.name);

        if (activeSessionName === params.name) {
          await ensureDefaultSession();
          activeSessionName = DEFAULT_SESSION;
          if (await backend.isVisible()) {
            monitorVisible = await backend.show(DEFAULT_SESSION);
          }
        }

        await updateStatus();

        return {
          content: [
            {
              type: "text",
              text: `Stopped session \"${params.name}\".`,
            },
          ],
          details: { name: params.name, socket: sessions.socketName },
        };
      },
    });

    pi.registerTool({
      name: "list_processes",
      label: "List Sessions",
      description: "List managed tmux sessions in the term server.",
      parameters: Type.Object({}),
      async execute() {
        const infos = (await loadSessions()).filter(
          (info) => info.name !== DEFAULT_SESSION,
        );

        if (infos.length === 0) {
          return {
            content: [
              {
                type: "text",
                text: "No managed sessions running.",
              },
            ],
            details: { count: 0, socket: sessions.socketName },
          };
        }

        const lines = infos.map((info) => {
          const meta = sessionMeta.get(info.name);
          const startedAt = meta?.startedAt || info.created * 1000;
          const age = startedAt > 0 ? Math.round((Date.now() - startedAt) / 1000) : 0;
          const desc = meta?.command || "(interactive shell)";
          const mode = meta?.mode || "quiet";
          return `  ${info.name}: ${desc} [${mode}, ${info.windows}w, ${info.attached}a, ${age}s]`;
        });

        return {
          content: [
            {
              type: "text",
              text: `Managed sessions:\n${lines.join("\n")}`,
            },
          ],
          details: { count: infos.length, socket: sessions.socketName },
        };
      },
    });

    // ── helpers: command parsing ─────────────────────────

    function extractQuoted(
      text: string,
    ): { value: string; start: number } | null {
      const dqIdx = text.indexOf('"');
      const sqIdx = text.indexOf("'");
      let quoteChar: string;
      let start: number;
      if (dqIdx === -1 && sqIdx === -1) return null;
      if (dqIdx === -1) {
        quoteChar = "'";
        start = sqIdx;
      } else if (sqIdx === -1) {
        quoteChar = '"';
        start = dqIdx;
      } else if (dqIdx < sqIdx) {
        quoteChar = '"';
        start = dqIdx;
      } else {
        quoteChar = "'";
        start = sqIdx;
      }

      const end = text.indexOf(quoteChar, start + 1);
      if (end === -1) return null;
      return { value: text.slice(start + 1, end), start };
    }

    // ── slash command ────────────────────────────────────

    pi.registerCommand("term", {
      description:
        'Control tmux-backed terminal sessions: toggle, focus, status, list, prev, next, attach <name|index>, <index>, new <name>, kill <name|index>, run "<cmd>", spawn [title] "<cmd>"',
      handler: async (args, commandCtx) => {
        const raw = (args || "").trim();
        const arg = raw.toLowerCase();

        if (!arg || arg === "toggle") {
          pi.events.emit("term:toggle");
          return;
        }

        if (arg === "focus") {
          pi.events.emit("term:focus");
          return;
        }

        if (arg === "prev") {
          pi.events.emit("term:prev");
          return;
        }

        if (arg === "next") {
          pi.events.emit("term:next");
          return;
        }

        if (arg === "list") {
          await showListNotification();
          return;
        }

        if (arg === "status") {
          monitorVisible = await backend.isVisible();
          const debug = await backend.getDebugInfo();
          const infos = await loadSessions();
          const parts = [
            `backend=${backend.label}`,
            `monitor=${monitorVisible ? "visible" : "hidden"}`,
            `active=${activeSessionName}`,
            `sessions=${infos.length}`,
            `target=${backend.displayTarget()}`,
          ];
          for (const [k, v] of Object.entries(debug)) {
            parts.push(`${k}=${String(v)}`);
          }
          commandCtx.ui.notify(parts.join(" | "), "info");
          return;
        }

        if (arg.startsWith("attach ")) {
          const ref = raw.slice(7).trim();
          const name = await resolveSessionRef(ref);
          if (!name) {
            commandCtx.ui.notify(`Unknown session: ${ref}`, "error");
            return;
          }
          pi.events.emit("term:attach", { name });
          return;
        }

        if (arg.startsWith("new ")) {
          const name = raw.slice(4).trim();
          if (!name) {
            commandCtx.ui.notify("Usage: /term new <name>", "error");
            return;
          }
          pi.events.emit("term:new", { name });
          return;
        }

        if (arg.startsWith("run ")) {
          const rest = raw.slice(4).trim();
          const quoted = extractQuoted(rest);
          if (!quoted?.value) {
            commandCtx.ui.notify('Usage: /term run "<command>"', "error");
            return;
          }
          pi.events.emit("term:run", { command: quoted.value });
          return;
        }

        if (arg.startsWith("spawn ")) {
          const rest = raw.slice(6).trim();
          if (!rest) {
            commandCtx.ui.notify('Usage: /term spawn [title] "<command>"', "error");
            return;
          }
          const quoted = extractQuoted(rest);
          if (!quoted?.value) {
            commandCtx.ui.notify('Usage: /term spawn [title] "<command>"', "error");
            return;
          }
          const beforeQuote = rest.slice(0, quoted.start).trim();
          const title = beforeQuote && /^\S+$/.test(beforeQuote) ? beforeQuote : undefined;
          pi.events.emit("term:spawn", { command: quoted.value, title });
          return;
        }

        if (arg.startsWith("kill ")) {
          const ref = raw.slice(5).trim();
          if (!ref) {
            commandCtx.ui.notify("Usage: /term kill <name|index>", "error");
            return;
          }
          const name = await resolveSessionRef(ref);
          if (!name) {
            commandCtx.ui.notify(`Unknown session: ${ref}`, "error");
            return;
          }
          pi.events.emit("term:kill", { name });
          return;
        }

        const direct = await resolveSessionRef(raw);
        if (direct) {
          pi.events.emit("term:attach", { name: direct });
          return;
        }

        commandCtx.ui.notify(
          'Unknown argument. Usage: /term [toggle|focus|status|list|prev|next|attach <name|index>|<index>|new <name>|kill <name|index>|run "<cmd>"|spawn [title] "<cmd>"]',
          "error",
        );
      },
    });

    pi.on("session_shutdown", async () => {
      unsubTermPrev();
      unsubTermNext();
      unsubTermToggle();
      unsubTermFocus();
      unsubTermAttach();
      unsubTermRun();
      unsubTermNew();
      unsubTermSpawn();
      unsubTermKill();

      if (cleanupKeybindings) {
        cleanupKeybindings();
        cleanupKeybindings = null;
      }

      sessionUi.setStatus("term-sessions", undefined);
      await backend.cleanup();
      await sessions.killServer();
    });

    if (!(await ensureDefaultSession())) {
      sessionUi.notify("Failed to create the default tmux session", "error");
      return;
    }

    await updateStatus();
    sessionUi.notify(
      `term ready (${backend.label} backend, tmux socket: ${sessions.socketName})`,
      "info",
    );

    // Contributes under the configurable global leader (default Space).
    // The chord is `<global> ' …` (e.g. `Space ' t` to toggle the
    // mirror) so all extensions follow the same two-leader model.
    cleanupKeybindings = registerLeaderMenu(pi, EXT_NAME, {
      globalMenu: {
        items: {
          "'": {
            label: "+term",
            items: {
              t: { label: "Show/hide", action: "term:toggle" },
              f: { label: "Focus", action: "term:focus" },
              h: { label: "Prev session", action: "term:prev" },
              l: { label: "Next session", action: "term:next" },
            },
          },
        },
      },
    });
  });
}

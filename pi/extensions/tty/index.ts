import { execFile } from "node:child_process";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import type { ExtensionAPI, ExtensionContext } from "@mariozechner/pi-coding-agent";
import {
  buildWatchScript,
  deriveWindowName,
  groupWindows,
  parseTtyArgs,
  type PaneInfo,
  type WindowInfo,
} from "./helpers";

const DEFAULT_CAPTURE_LINES = 200;
const FIELD_SEP = "\t";

const EmptyParams = {
  type: "object",
  properties: {},
  additionalProperties: false,
} as const;

const CaptureParams = {
  type: "object",
  properties: {
    target: {
      type: "string",
      description: "Pane id (%N), window id (@N), window index, or window name. Omit to use latest watched pane.",
    },
    lines: {
      type: "number",
      description: "Number of scrollback lines to capture (default 200, max 5000).",
    },
    preserveEscapes: {
      type: "boolean",
      description: "Preserve ANSI escape sequences/colors. Default false.",
    },
  },
  additionalProperties: false,
} as const;

const baseDir = dirname(fileURLToPath(import.meta.url));
const skillPath = join(baseDir, "..", "..", "skills", "pi-tty", "SKILL.md");

type SpawnKind = "spawn" | "watch";

interface TmuxContext {
  paneId: string;
  sessionId: string;
  sessionName: string;
  windowId: string;
  windowIndex: string;
}

interface CreatedPaneRecord {
  kind: SpawnKind;
  command: string;
  paneId: string;
  windowId: string;
  windowIndex: string;
  windowName: string;
  createdAt: string;
  ownerPaneId: string;
}

interface JoinedPaneRecord {
  paneId: string;
  fromWindowId: string;
  fromWindowName?: string;
  ownerPaneId: string;
  keepFocus?: boolean;
}

interface TtyToolResultDetails {
  currentPane?: string;
  knownPanes?: CreatedPaneRecord[];
  windows?: WindowInfo[];
  target?: string;
  lines?: number;
  preserveEscapes?: boolean;
  error?: string;
}

const knownByOwner = new Map<string, CreatedPaneRecord[]>();
const joinedByOwner = new Map<string, JoinedPaneRecord>();

function nowIso(): string {
  return new Date().toISOString();
}

function runTmux(args: string[], opts: { timeoutMs?: number } = {}): Promise<string> {
  return new Promise((resolve, reject) => {
    const child = execFile("tmux", args, { timeout: opts.timeoutMs ?? 10000 }, (error, stdout, stderr) => {
      if (error) {
        const message = (stderr || error.message || "tmux command failed").trim();
        reject(new Error(message));
        return;
      }
      resolve(stdout);
    });
    child.on("error", (error) => reject(error));
  });
}

function isMissingTmuxTarget(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error);
  return /can't find (pane|window):/.test(message);
}

async function runTmuxIgnoringMissingTarget(args: string[]): Promise<void> {
  try {
    await runTmux(args);
  } catch (error) {
    if (!isMissingTmuxTarget(error)) throw error;
  }
}

async function requireTmuxContext(): Promise<TmuxContext> {
  if (!process.env.TMUX) {
    throw new Error("Not inside tmux: $TMUX is not set. Start pi inside a tmux session before using /t.");
  }
  let out: string;
  try {
    out = await runTmux(["display-message", "-p", `#{pane_id}${FIELD_SEP}#{session_id}${FIELD_SEP}#{session_name}${FIELD_SEP}#{window_id}${FIELD_SEP}#{window_index}`]);
  } catch (error) {
    throw new Error(`tmux is unavailable or $TMUX is stale: ${error instanceof Error ? error.message : String(error)}`);
  }
  const [paneId, sessionId, sessionName, windowId, windowIndex] = out.trimEnd().split(FIELD_SEP);
  if (!paneId || !sessionId || !sessionName || !windowId) {
    throw new Error("tmux returned incomplete pane context; cannot continue.");
  }
  return { paneId, sessionId, sessionName, windowId, windowIndex };
}

function parsePaneLine(line: string): PaneInfo | null {
  const [sessionId, sessionName, windowId, windowIndex, windowName, paneId, paneIndex, paneActive, command] = line.split(FIELD_SEP);
  if (!sessionId || !windowId || !paneId) return null;
  return {
    sessionId,
    sessionName,
    windowId,
    windowIndex,
    windowName,
    paneId,
    paneIndex,
    paneActive: paneActive === "1",
    command: command ?? "",
  };
}

async function listPanes(): Promise<PaneInfo[]> {
  const format = [
    "#{session_id}",
    "#{session_name}",
    "#{window_id}",
    "#{window_index}",
    "#{window_name}",
    "#{pane_id}",
    "#{pane_index}",
    "#{pane_active}",
    "#{pane_current_command}",
  ].join(FIELD_SEP);
  const out = await runTmux(["list-panes", "-a", "-F", format]);
  return out.split("\n").map(parsePaneLine).filter((p): p is PaneInfo => p !== null);
}

function knownFor(ownerPaneId: string): CreatedPaneRecord[] {
  return knownByOwner.get(ownerPaneId) ?? [];
}

function remember(record: CreatedPaneRecord): void {
  const records = knownByOwner.get(record.ownerPaneId) ?? [];
  records.push(record);
  knownByOwner.set(record.ownerPaneId, records.slice(-50));
}

function latestWatched(ownerPaneId: string): CreatedPaneRecord | undefined {
  return [...knownFor(ownerPaneId)].reverse().find((record) => record.kind === "watch");
}

async function resolveWindowTarget(target: string, current: TmuxContext): Promise<WindowInfo> {
  const windows = groupWindows(await listPanes()).filter((w) => w.sessionId === current.sessionId);
  const trimmed = target.trim();
  const found = windows.find((w) =>
    w.windowId === trimmed ||
    w.windowIndex === trimmed ||
    w.windowName === trimmed ||
    w.activePaneId === trimmed ||
    w.panes.some((p) => p.paneId === trimmed)
  );
  if (!found) throw new Error(`No tmux window/pane found for target ${trimmed || "(empty)"} in current session.`);
  return found;
}

async function resolveCapturePane(target: string | undefined, current: TmuxContext): Promise<string> {
  const trimmed = target?.trim();
  if (trimmed) {
    if (trimmed.startsWith("%")) return trimmed;
    const win = await resolveWindowTarget(trimmed, current);
    if (!win.activePaneId) throw new Error(`Window ${trimmed} has no active pane.`);
    return win.activePaneId;
  }
  const latest = latestWatched(current.paneId);
  if (latest) return latest.paneId;
  throw new Error("No target supplied and no watched pane is known for this pi pane. Call tty_list first, then pass target.");
}

function formatKnown(record: CreatedPaneRecord): string {
  return `${record.kind.padEnd(5)} ${record.paneId} ${record.windowId} [${record.windowIndex}] ${record.windowName} — ${record.command}`;
}

function formatWindow(win: WindowInfo, current: TmuxContext): string {
  const marker = win.windowId === current.windowId ? "*" : " ";
  const paneIds = win.panes.map((p) => p.paneId).join(",");
  return `${marker} [${win.windowIndex}] ${win.windowName} ${win.windowId} panes=${win.paneCount} active=${win.activePaneId ?? "?"} (${paneIds})`;
}

async function ttyListText(current: TmuxContext, _params?: Record<string, unknown>): Promise<{ text: string; details: TtyToolResultDetails }> {
  const windows = groupWindows(await listPanes()).filter((w) => w.sessionId === current.sessionId);
  const known = knownFor(current.paneId);
  const joined = joinedByOwner.get(current.paneId);
  const lines = [
    `Current pane: ${current.paneId} session=${current.sessionName} window=${current.windowIndex}`,
    "",
    "Windows:",
    ...(windows.length ? windows.map((w) => formatWindow(w, current)) : ["(none)"]),
    "",
    "Known spawned/watched panes:",
    ...(known.length ? known.map(formatKnown) : ["(none)"]),
    "",
    `Joined pane: ${joined?.paneId ?? "(none)"}`,
  ];
  return { text: lines.join("\n"), details: { currentPane: current.paneId, knownPanes: known, windows } };
}

async function capturePaneText(target: string | undefined, lines: number, preserveEscapes: boolean, current: TmuxContext): Promise<{ text: string; paneId: string }> {
  const paneId = await resolveCapturePane(target, current);
  const count = Math.max(1, Math.min(lines || DEFAULT_CAPTURE_LINES, 5000));
  const args = ["capture-pane", "-t", paneId, "-p", "-S", `-${count}`];
  if (preserveEscapes) args.splice(4, 0, "-e");
  const text = await runTmux(args);
  return { text: text.trimEnd() || "(no captured output)", paneId };
}

async function spawnWindow(kind: SpawnKind, command: string, ctx: ExtensionContext): Promise<CreatedPaneRecord> {
  const current = await requireTmuxContext();
  if (!command.trim()) throw new Error(`Usage: /t ${kind} <command>`);
  const windowName = deriveWindowName(command);
  const shellCommand = kind === "watch" ? buildWatchScript(command, ctx.cwd) : command;
  const out = await runTmux([
    "new-window",
    "-c", ctx.cwd,
    "-n", windowName,
    "-P",
    "-F", `#{pane_id}${FIELD_SEP}#{window_id}${FIELD_SEP}#{window_index}${FIELD_SEP}#{window_name}`,
    shellCommand,
  ]);
  const [paneId, windowId, windowIndex, actualWindowName] = out.trimEnd().split(FIELD_SEP);
  if (!paneId || !windowId) throw new Error(`tmux new-window did not return pane/window ids: ${out}`);
  try {
    await runTmux(["set-option", "-t", windowId, "-w", "automatic-rename", "off"]);
  } catch {
    // Best effort: short-lived spawn commands may exit before the option write.
  }
  const record: CreatedPaneRecord = {
    kind,
    command,
    paneId,
    windowId,
    windowIndex,
    windowName: actualWindowName || windowName,
    createdAt: nowIso(),
    ownerPaneId: current.paneId,
  };
  remember(record);
  return record;
}

async function joinPane(target: string, keepFocus = false, piContext?: TmuxContext): Promise<string> {
  const current = piContext ?? await requireTmuxContext();
  if (!target.trim()) throw new Error("Usage: /t join <window-index|window-id|pane-id>");
  const win = await resolveWindowTarget(target, current);
  if (win.windowId === current.windowId) throw new Error("Refusing to join the current pi window into itself.");
  if (win.paneCount !== 1) throw new Error(`Refusing to join ${target}: target window has ${win.paneCount} panes; only single-pane windows are supported.`);
  const paneId = win.activePaneId ?? win.panes[0]?.paneId;
  if (!paneId) throw new Error(`Target ${target} has no pane to join.`);
  try {
    await breakPane(undefined, true, current);
  } catch {
    joinedByOwner.delete(current.paneId);
  }
  await runTmux(["join-pane", "-s", paneId, "-t", current.paneId, "-v"]);
  joinedByOwner.set(current.paneId, { paneId, fromWindowId: win.windowId, fromWindowName: win.windowName, ownerPaneId: current.paneId, keepFocus });
  if (keepFocus) {
    await runTmux(["select-pane", "-t", current.paneId]);
  }
  return `${keepFocus ? "Monitored" : "Joined"} ${paneId} below ${current.paneId}.`;
}

async function breakPane(explicitPaneId?: string, quietNoop = false, piContext?: TmuxContext): Promise<string> {
  const current = piContext ?? await requireTmuxContext();
  const joinedRecord = explicitPaneId?.trim() ? undefined : joinedByOwner.get(current.paneId);
  const targetPane = explicitPaneId?.trim() || joinedRecord?.paneId;
  if (!targetPane) {
    if (quietNoop) return "No joined pane to break.";
    throw new Error("No joined pane is recorded for this pi pane. Use /t break <pane-id> after restart/state loss.");
  }
  const savedName = joinedRecord?.fromWindowName;
  const breakArgs = ["break-pane", "-s", targetPane, "-P", "-F", "#{window_id}"];
  if (savedName) breakArgs.push("-n", savedName);
  const newWindowId = (await runTmux(breakArgs)).trimEnd();
  if (newWindowId) {
    try {
      await runTmux(["set-option", "-t", newWindowId, "-w", "automatic-rename", "off"]);
    } catch {
      // Best effort: short-lived broken-out panes may exit before the option write.
    }
  }
  await runTmux(["select-window", "-t", current.windowIndex]);
  if (!explicitPaneId) joinedByOwner.delete(current.paneId);
  return `Broke ${targetPane} back into its own window.`;
}

async function killPane(target: string): Promise<string> {
  const current = await requireTmuxContext();
  const joined = joinedByOwner.get(current.paneId);
  const trimmed = target.trim();
  let paneId: string | undefined;
  let windowId: string | undefined;
  if (trimmed) {
    const win = await resolveWindowTarget(trimmed, current);
    if (win.windowId === current.windowId) throw new Error("Refusing to kill the current pi window.");
    paneId = win.activePaneId ?? win.panes[0]?.paneId;
    windowId = win.windowId;
  } else {
    paneId = joined?.paneId;
  }
  if (!paneId) {
    throw new Error("No active monitor — try '/t kill <window-index>'.");
  }
  // Ctrl-C may already close the target; remaining cleanup should be idempotent.
  await runTmuxIgnoringMissingTarget(["send-keys", "-t", paneId, "C-c"]);
  await runTmuxIgnoringMissingTarget(["send-keys", "-t", paneId, "C-c"]);
  await runTmuxIgnoringMissingTarget(windowId ? ["kill-window", "-t", windowId] : ["kill-pane", "-t", paneId]);
  if (!trimmed) joinedByOwner.delete(current.paneId);
  return `Killed ${paneId}.`;
}

function usage(): string {
  return [
    "Usage:",
    "  /t s|spawn <command>        create a new tmux window",
    "  /t w|watch <command>        run command; Ctrl-C job, Ctrl-C again to close",
    "  /t j|join <target>          join a single-pane window below this pane",
    "  /t m|monitor <target>       join a single-pane window below this pane; keep focus",
    "  /t k|kill [target]          stop the process and remove the joined pane, or target window if given",
    "  /t b|break [pane-id]        return joined pane to its own window",
    "  /t l|list                   list windows and known spawned/watched panes",
    "  /t t|tail [target] [lines]  capture recent pane output",
  ].join("\n");
}

function notifyError(ctx: ExtensionContext, error: unknown): void {
  ctx.ui.notify(error instanceof Error ? error.message : String(error), "error");
}

export default function ttyExtension(pi: ExtensionAPI) {
  let currentCtx: ExtensionContext | undefined;

  const withCtx = <E, R>(handler: (ctx: ExtensionContext, data: E) => Promise<R>) =>
    async (data: E) => {
      if (!currentCtx) return;
      try {
        await handler(currentCtx, data);
      } catch (error) {
        notifyError(currentCtx, error);
      }
    };

  const toolExec = (
    handler: (ctx: Awaited<ReturnType<typeof requireTmuxContext>>, params: Record<string, unknown>) => Promise<{ text: string; details: TtyToolResultDetails }>,
  ) =>
    async (_toolCallId: string, params: Record<string, unknown>) => {
      try {
        const current = await requireTmuxContext();
        const { text, details } = await handler(current, params);
        return { content: [{ type: "text", text }], details };
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error);
        return { content: [{ type: "text", text: `Error: ${message}` }], details: { error: message } as TtyToolResultDetails };
      }
    };

  pi.on("session_start", async (_event, ctx) => {
    currentCtx = ctx;
  });
  pi.on("session_shutdown", async () => {
    currentCtx = undefined;
  });
  pi.on("resources_discover", () => ({ skillPaths: [skillPath] }));

  pi.events.on("tty:spawn", withCtx(async (ctx, data) => {
    const record = await spawnWindow("spawn", data.command, ctx);
    ctx.ui.notify(`Spawned ${record.paneId} in window [${record.windowIndex}] ${record.windowName}`, "info");
  }));

  pi.events.on("tty:watch", withCtx(async (ctx, data) => {
    // Save pi context *before* spawning — tmux new-window switches focus.
    const piCtx = await requireTmuxContext();
    const record = await spawnWindow("watch", data.command, ctx);
    ctx.ui.notify(`Watching ${record.paneId} in window [${record.windowIndex}] ${record.windowName}`, "info");
    // Join the new watch window below pi, keeping focus in the pi pane.
    await joinPane(record.windowIndex, true, piCtx);
  }));

  pi.events.on("tty:join", withCtx(async (ctx, data) => {
    ctx.ui.notify(await joinPane(data.target, false), "info");
  }));

  pi.events.on("tty:break", withCtx(async (ctx, data) => {
    ctx.ui.notify(await breakPane(data.paneId), "info");
  }));

  pi.events.on("tty:monitor", withCtx(async (ctx, data) => {
    ctx.ui.notify(await joinPane(data.target, true), "info");
  }));

  pi.events.on("tty:kill", withCtx(async (ctx, data) => {
    ctx.ui.notify(await killPane(data.target || ""), "info");
  }));

  pi.events.on("tty:list", withCtx(async (ctx) => {
    const current = await requireTmuxContext();
    const { text } = await ttyListText(current);
    ctx.ui.notify(text, "info");
  }));

  pi.events.on("tty:tail", withCtx(async (ctx, data) => {
    const current = await requireTmuxContext();
    const { text, paneId } = await capturePaneText(data.target, data.lines ?? DEFAULT_CAPTURE_LINES, false, current);
    ctx.ui.notify(`Captured ${paneId}:\n${text}`, "info");
  }));

  pi.registerCommand("t", {
    description: "s|spawn | w|watch | j|join | m|monitor | k|kill | b|break | l|list | t|tail",
    handler: async (args, ctx) => {
      const parsed = parseTtyArgs(args);
      switch (parsed.subcommand) {
        case "spawn":
          pi.events.emit("tty:spawn", { command: parsed.rest });
          break;
        case "watch":
          pi.events.emit("tty:watch", { command: parsed.rest });
          break;
        case "join":
          pi.events.emit("tty:join", { target: parsed.rest });
          break;
        case "monitor":
          pi.events.emit("tty:monitor", { target: parsed.rest });
          break;
        case "kill":
          pi.events.emit("tty:kill", { target: parsed.rest });
          break;
        case "break":
          pi.events.emit("tty:break", { paneId: parsed.rest || undefined });
          break;
        case "list":
          pi.events.emit("tty:list", {});
          break;
        case "tail": {
          const [targetMaybe, linesMaybe] = parsed.rest.split(/\s+/).filter(Boolean);
          const lines = linesMaybe ? Number(linesMaybe) : DEFAULT_CAPTURE_LINES;
          pi.events.emit("tty:tail", { target: targetMaybe, lines: Number.isFinite(lines) ? lines : DEFAULT_CAPTURE_LINES });
          break;
        }
        case "help":
          ctx.ui.notify(usage(), "info");
          break;
        default:
          ctx.ui.notify(`Unknown /t subcommand: ${parsed.subcommand}\n\n${usage()}`, "warning");
      }
    },
  });

  pi.registerTool({
    name: "tty_list",
    label: "TTY List",
    description: "List backend windows/panes plus panes spawned or watched by the tty extension.",
    parameters: EmptyParams as any,
    execute: toolExec(ttyListText),
  });

  pi.registerTool({
    name: "tty_capture",
    label: "TTY Capture",
    description: "Capture recent output from a backend pane/window. Defaults to the latest watched pane for this pi pane when possible.",
    parameters: CaptureParams as any,
    execute: toolExec(async (current, params) => {
      const target = params.target as string | undefined;
      const lines = params.lines as number | undefined;
      const preserveEscapes = params.preserveEscapes as boolean | undefined;
      const count = typeof lines === "number" ? lines : DEFAULT_CAPTURE_LINES;
      const escaped = preserveEscapes === true;
      const { text, paneId } = await capturePaneText(target, count, escaped, current);
      return { text, details: { target: paneId, lines: count, preserveEscapes: escaped } as TtyToolResultDetails };
    }),
  });
}

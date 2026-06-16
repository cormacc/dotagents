const WINDOW_NAME_LIMIT = 20;

export interface ParsedTtyArgs {
  subcommand: string;
  rest: string;
}

export interface PaneInfo {
  sessionId: string;
  sessionName: string;
  windowId: string;
  windowIndex: string;
  windowName: string;
  paneId: string;
  paneIndex: string;
  paneActive: boolean;
  command: string;
}

export interface WindowInfo {
  sessionId: string;
  sessionName: string;
  windowId: string;
  windowIndex: string;
  windowName: string;
  paneCount: number;
  activePaneId: string | null;
  panes: PaneInfo[];
}

const SUBCOMMAND_ALIASES: Record<string, string> = {
  s: "spawn",
  w: "watch",
  j: "join",
  m: "monitor",
  b: "break",
  k: "kill",
  l: "list",
  t: "tail",
  h: "help",
  "?": "help",
};

export function parseTtyArgs(args: string): ParsedTtyArgs {
  const trimmed = args.trim();
  if (!trimmed) return { subcommand: "help", rest: "" };
  const match = /^(\S+)(?:\s+([\s\S]*))?$/.exec(trimmed);
  const rawSubcommand = (match?.[1] ?? "help").toLowerCase();
  return {
    subcommand: SUBCOMMAND_ALIASES[rawSubcommand] ?? rawSubcommand,
    rest: (match?.[2] ?? "").trim(),
  };
}

export function deriveWindowName(command: string): string {
  const trimmed = command.trim().replace(/\s+/g, " ");
  return (trimmed || "tty").slice(0, WINDOW_NAME_LIMIT);
}

export function shellQuote(value: string): string {
  return `'${value.replace(/'/g, `'"'"'`)}'`;
}

export function buildWatchScript(command: string, cwd?: string): string {
  const nodeCode = [
    "const { spawn } = require('node:child_process');",
    "const cmd = process.argv[1];",
    "const cwd = process.argv[2] || process.cwd();",
    "let waiting = false;",
    "const child = spawn(process.env.SHELL || '/bin/sh', ['-lc', cmd], { cwd, stdio: 'inherit' });",
    "process.on('SIGINT', () => {",
    "  if (waiting) process.exit(0);",
    "  child.kill('SIGINT');",
    "});",
    "child.on('exit', (code, signal) => {",
    "  waiting = true;",
    "  const status = signal || code;",
    "  console.log(`\\n[tty watch exited ${status} — Ctrl-C again to close]`);",
    "  setInterval(() => {}, 3600_000);",
    "});",
  ].join("\n");
  return `node -e ${shellQuote(nodeCode)} ${shellQuote(command)} ${shellQuote(cwd ?? "")}`;
}

export function groupWindows(panes: PaneInfo[]): WindowInfo[] {
  const byId = new Map<string, WindowInfo>();
  for (const pane of panes) {
    const existing = byId.get(pane.windowId) ?? {
      sessionId: pane.sessionId,
      sessionName: pane.sessionName,
      windowId: pane.windowId,
      windowIndex: pane.windowIndex,
      windowName: pane.windowName,
      paneCount: 0,
      activePaneId: null,
      panes: [],
    };
    existing.panes.push(pane);
    existing.paneCount += 1;
    if (pane.paneActive) existing.activePaneId = pane.paneId;
    byId.set(pane.windowId, existing);
  }
  return [...byId.values()].sort((a, b) => Number(a.windowIndex) - Number(b.windowIndex));
}

// Vendored subset derived from ~/.pi/agent/git/github.com/HazAT/pi-interactive-subagents/pi-extension/subagents/cmux.ts
// (backend detection helpers and send/read/close primitives). Tty-owned attach/detach policy lives here.
import { execFileSync, execSync } from "node:child_process";

export type MuxBackend = "cmux" | "tmux" | "zellij" | "wezterm";
export type FocusPolicy = "owner" | "target";

const DEFAULT_CAPTURE_LINES = 200;
const commandAvailability = new Map<string, boolean>();
let testExecFileSync: typeof execFileSync | undefined;
let testExecSync: typeof execSync | undefined;

export function __setMuxTestExec(file?: typeof execFileSync, shell?: typeof execSync): void {
  testExecFileSync = file;
  testExecSync = shell;
  commandAvailability.clear();
}

function xfile(cmd: string, args: string[], opts: any = {}): string {
  return (testExecFileSync ?? execFileSync)(cmd, args, { encoding: "utf8", ...opts }) as string;
}

function xshell(cmd: string, opts: any = {}): string {
  return (testExecSync ?? execSync)(cmd, { encoding: "utf8", ...opts }) as string;
}

function hasCommand(command: string): boolean {
  if (commandAvailability.has(command)) return commandAvailability.get(command)!;
  let available = false;
  try {
    if (process.platform === "win32") xfile("where.exe", [command], { stdio: "ignore" });
    else xshell(`command -v ${command}`, { stdio: "ignore" });
    available = true;
  } catch {
    available = false;
  }
  commandAvailability.set(command, available);
  return available;
}

export function shellEscape(s: string): string {
  return "'" + s.replace(/'/g, "'\\''") + "'";
}

function tailLines(text: string, lines: number): string {
  const split = text.split("\n");
  if (split.length <= lines) return text;
  return split.slice(-lines).join("\n");
}

function muxPreference(): MuxBackend | null {
  const pref = (process.env.PI_TTY_MUX ?? "").trim().toLowerCase();
  return pref === "cmux" || pref === "tmux" || pref === "zellij" || pref === "wezterm" ? pref : null;
}

function available(backend: MuxBackend): boolean {
  if (backend === "cmux") return !!process.env.CMUX_SOCKET_PATH && hasCommand("cmux");
  if (backend === "tmux") return !!process.env.TMUX && hasCommand("tmux");
  if (backend === "zellij") return !!(process.env.ZELLIJ || process.env.ZELLIJ_SESSION_NAME) && hasCommand("zellij");
  return !!process.env.WEZTERM_UNIX_SOCKET && hasCommand("wezterm");
}

export function getMuxBackend(): MuxBackend | null {
  const pref = muxPreference();
  if (pref) return available(pref) ? pref : null;
  for (const backend of ["cmux", "tmux", "zellij", "wezterm"] as const) if (available(backend)) return backend;
  return null;
}

export function isMuxAvailable(): boolean { return getMuxBackend() !== null; }

export function muxSetupHint(): string {
  const pref = muxPreference();
  if (pref === "cmux") return "Start pi inside cmux (`cmux pi`).";
  if (pref === "tmux") return "Start pi inside tmux (`tmux new -A -s pi 'pi'`).";
  if (pref === "zellij") return "Start pi inside zellij (`zellij --session pi`, then run `pi`).";
  if (pref === "wezterm") return "Start pi inside WezTerm.";
  return "Start pi inside cmux, tmux, zellij, or WezTerm, or set PI_TTY_MUX to the desired backend.";
}

export function requireMuxBackend(): MuxBackend {
  const backend = getMuxBackend();
  if (!backend) throw new Error(`No supported terminal multiplexer found. ${muxSetupHint()}`);
  return backend;
}

function tmuxContext(): { pane: string; window: string; index: string } {
  const [pane, window, index] = xfile("tmux", ["display-message", "-p", "#{pane_id}\t#{window_id}\t#{window_index}"]).trimEnd().split("\t");
  if (!pane || !window) throw new Error("tmux returned incomplete pane context; cannot continue.");
  return { pane, window, index };
}

function cmuxSurfaceRef(surface: string): string { return surface.startsWith("surface:") ? surface : `surface:${surface}`; }
function cmuxDisplaySurfaceId(surface: string): string { return cmuxSurfaceRef(surface).replace(/^surface:/, ""); }
function parseCmuxSurface(output: string): string { return output.match(/surface:\d+/)?.[0] ?? output.trim(); }

function zellijPaneId(surface: string): string { return surface.startsWith("pane:") ? surface.slice(5) : surface; }
function zellijBarePaneId(surface: string): string { return zellijPaneId(surface).replace(/^(terminal_|plugin_)/, ""); }
function zellijDisplayPaneId(surface: string): string { return zellijBarePaneId(surface); }
function zellijSamePane(a: string | number | undefined, b: string | number | undefined): boolean { return a !== undefined && b !== undefined && zellijBarePaneId(String(a)) === zellijBarePaneId(String(b)); }
function zellijCurrentPane(): string { return process.env.ZELLIJ_PANE_ID || zellijPaneId(JSON.parse(xfile("zellij", ["action", "list-panes", "--json", "--tab", "--state", "--geometry"]))?.panes?.find((p: any) => p.is_focused)?.id?.toString() || ""); }
function zellijCurrentTab(): string | undefined { return process.env.ZELLIJ_TAB_ID; }
function zellijPanes(): any[] { const raw = xfile("zellij", ["action", "list-panes", "--json", "--tab", "--state", "--geometry"]); const parsed = JSON.parse(raw || "{}"); return Array.isArray(parsed) ? parsed : parsed.panes ?? []; }
function zellijPane(surface: string): any | undefined { const id = zellijPaneId(surface); const bare = zellijBarePaneId(surface); return zellijPanes().find((p) => String(p.id) === id || zellijBarePaneId(String(p.id)) === bare || `terminal_${p.id}` === id || `plugin_${p.id}` === id); }
function zellijFocusedPaneId(): string | undefined { return zellijPanes().find((p) => p.is_focused || p.focused)?.id?.toString(); }
function zellijAction(args: string[]): string { return xfile("zellij", ["action", ...args]); }
function zellijFocusPane(id: string): void {
  if (zellijSamePane(zellijFocusedPaneId(), id)) return;
  try {
    zellijAction(["focus-pane-id", id]);
  } catch (error: any) {
    const message = `${error?.message ?? ""}\n${error?.stderr?.toString?.() ?? ""}`;
    if (!/already focused/i.test(message)) throw error;
  }
}

function weztermPanes(): any[] { try { return JSON.parse(xfile("wezterm", ["cli", "list", "--format", "json"])); } catch { return []; } }
function weztermCurrentWindowId(panes = weztermPanes()): string | undefined {
  const current = process.env.WEZTERM_PANE;
  return panes.find((p: any) => String(p.pane_id) === String(current))?.window_id?.toString();
}
function weztermWindowTabs(panes = weztermPanes()): any[] {
  const windowId = weztermCurrentWindowId(panes);
  const scoped = windowId === undefined ? panes : panes.filter((p: any) => String(p.window_id) === windowId);
  const seen = new Set<string>();
  return scoped.filter((p: any) => { const tab = String(p.tab_id); if (seen.has(tab)) return false; seen.add(tab); return true; });
}
function weztermDisplayIndexForPane(paneId: string): string | undefined {
  const tabs = weztermWindowTabs();
  const idx = tabs.findIndex((p: any) => String(p.pane_id) === String(paneId) || String(p.tab_id) === weztermPaneTab(`pane:${paneId}`));
  return idx >= 0 ? String(idx + 1) : undefined;
}
function weztermPane(target: string, preferTab = true): any | undefined {
  const forcedPane = target.startsWith("pane:");
  const forcedTab = target.startsWith("tab:");
  const id = forcedPane || forcedTab ? target.slice(target.indexOf(":") + 1) : target;
  const panes = weztermPanes();
  if (forcedPane) return panes.find((p: any) => String(p.pane_id) === String(id));
  if (forcedTab) return panes.find((p: any) => String(p.tab_id) === String(id));
  if (preferTab && /^\d+$/.test(id)) {
    const byDisplayIndex = weztermWindowTabs(panes)[Number(id) - 1];
    if (byDisplayIndex) return byDisplayIndex;
    const byTab = panes.find((p: any) => String(p.tab_id) === String(id));
    if (byTab) return byTab;
  }
  const byPane = panes.find((p: any) => String(p.pane_id) === String(id));
  if (byPane) return byPane;
  if (!preferTab) return panes.find((p: any) => String(p.tab_id) === String(id));
  return undefined;
}
function weztermPaneTab(pane: string): string | undefined { return weztermPane(pane, false)?.tab_id?.toString(); }

export function displayTargetForSurface(surface: string): string | undefined {
  const backend = getMuxBackend();
  if (backend === "wezterm") return weztermDisplayIndexForPane(surface.replace(/^pane:/, ""));
  if (backend === "zellij") return zellijDisplayPaneId(surface);
  if (backend === "cmux") return cmuxDisplaySurfaceId(surface);
  return undefined;
}

export function createWindow(name: string, command: string, cwd: string): string {
  const backend = requireMuxBackend();
  if (backend === "tmux") {
    const out = xfile("tmux", ["new-window", "-c", cwd, "-n", name, "-P", "-F", "#{pane_id}\t#{window_id}\t#{window_index}\t#{window_name}", command]);
    const [, windowId] = out.trimEnd().split("\t");
    if (windowId) try { xfile("tmux", ["set-option", "-t", windowId, "-w", "automatic-rename", "off"]); } catch {}
    return out;
  }
  if (backend === "cmux") {
    const surface = parseCmuxSurface(xshell(`cmux new-surface --name ${shellEscape(name)} --cwd ${shellEscape(cwd)} ${shellEscape(command)}`).trim());
    const surfaceId = cmuxDisplaySurfaceId(surface);
    try { xshell(`cmux rename-tab --surface ${shellEscape(cmuxSurfaceRef(surface))} ${shellEscape(`${surfaceId}: ${name}`)}`); } catch {}
    return surfaceId;
  }
  if (backend === "wezterm") {
    const paneId = xfile("wezterm", ["cli", "spawn", "--cwd", cwd, "--", "sh", "-lc", command]).trim();
    if (paneId) try { xfile("wezterm", ["cli", "set-tab-title", "--pane-id", paneId, name]); } catch {}
    const pane = paneId ? weztermPane(paneId, false) : undefined;
    const displayIndex = pane ? weztermDisplayIndexForPane(pane.pane_id?.toString()) ?? pane.tab_id : undefined;
    return pane ? `pane:${pane.pane_id}\t${pane.tab_id}\t${displayIndex}\t${name}` : paneId;
  }
  const args = ["new-pane", "--floating", "--name", name, "--cwd", cwd];
  const tab = zellijCurrentTab(); if (tab) args.push("--tab-id", tab);
  args.push("--", "sh", "-lc", command);
  const created = zellijAction(args).trim();
  const parts = created.split(/\s+/).filter(Boolean);
  const paneId = zellijDisplayPaneId(parts[parts.length - 1] ?? created);
  if (paneId) zellijAction(["rename-pane", "--pane-id", paneId, `${paneId}: ${name}`]);
  return paneId || created;
}

export function sendCommand(surface: string, command: string): void {
  const backend = requireMuxBackend();
  if (backend === "cmux") { xshell(`cmux send --surface ${shellEscape(cmuxSurfaceRef(surface))} ${shellEscape(command + "\n")}`); return; }
  if (backend === "tmux") { xfile("tmux", ["send-keys", "-t", surface, "-l", command]); xfile("tmux", ["send-keys", "-t", surface, "Enter"]); return; }
  if (backend === "wezterm") { const pane = weztermPane(surface); xfile("wezterm", ["cli", "send-text", "--pane-id", pane?.pane_id?.toString() ?? surface.replace(/^pane:/, ""), "--no-paste", command + "\n"]); return; }
  zellijAction(["write-chars", "--pane-id", zellijPaneId(surface), command]); zellijAction(["write", "--pane-id", zellijPaneId(surface), "13"]);
}

export function readScreen(surface: string, lines = DEFAULT_CAPTURE_LINES): string {
  const backend = requireMuxBackend(); const count = Math.max(1, lines || DEFAULT_CAPTURE_LINES);
  if (backend === "cmux") return xshell(`cmux read-screen --surface ${shellEscape(cmuxSurfaceRef(surface))} --lines ${count}`);
  if (backend === "tmux") return xfile("tmux", ["capture-pane", "-p", "-t", surface, "-S", `-${count}`]);
  if (backend === "wezterm") { const pane = weztermPane(surface); return tailLines(xfile("wezterm", ["cli", "get-text", "--pane-id", pane?.pane_id?.toString() ?? surface.replace(/^pane:/, "")]), count); }
  return tailLines(zellijAction(["dump-screen", "--pane-id", zellijPaneId(surface)]), count);
}

export function closeSurface(surface: string): void {
  const backend = requireMuxBackend();
  if (backend === "cmux") { xshell(`cmux close-surface --surface ${shellEscape(cmuxSurfaceRef(surface))}`); return; }
  if (backend === "tmux") { xfile("tmux", ["kill-pane", "-t", surface]); return; }
  if (backend === "wezterm") { const pane = weztermPane(surface); xfile("wezterm", ["cli", "kill-pane", "--pane-id", pane?.pane_id?.toString() ?? surface.replace(/^pane:/, "")]); return; }
  zellijAction(["close-pane", "--pane-id", zellijPaneId(surface)]);
}

export function attachSurface(surface: string, opts: { focus: FocusPolicy; ownerPaneId?: string }): string {
  const backend = requireMuxBackend();
  if (backend === "tmux") { const owner = opts.ownerPaneId ?? tmuxContext().pane; xfile("tmux", ["join-pane", "-s", surface, "-t", owner, "-v"]); xfile("tmux", ["select-pane", "-t", opts.focus === "owner" ? owner : surface]); return surface; }
  if (backend === "cmux") { const ref = cmuxSurfaceRef(surface); xshell(`cmux move-surface --surface ${shellEscape(ref)} --pane ${shellEscape(process.env.CMUX_PANE_REF ?? "")} --focus ${opts.focus === "target"}`); return cmuxDisplaySurfaceId(ref); }
  if (backend === "wezterm") { const owner = process.env.WEZTERM_PANE; const target = weztermPane(surface); if (!owner) throw new Error("WEZTERM_PANE is not set."); if (!target) throw new Error(`No wezterm pane/tab found for target ${surface}.`); const srcTab = target.tab_id?.toString(), dstTab = weztermPaneTab(`pane:${owner}`); const targetPane = target.pane_id.toString(); if (srcTab && dstTab && srcTab === dstTab) throw new Error("Refusing wezterm same-tab attach due to split-pane --move-pane-id bug."); xfile("wezterm", ["cli", "split-pane", "--pane-id", owner, "--bottom", "--top-level", "--move-pane-id", targetPane]); xfile("wezterm", ["cli", "activate-pane", "--pane-id", opts.focus === "owner" ? owner : targetPane]); return `pane:${targetPane}`; }
  const pane = zellijPane(surface); if (!pane) throw new Error(`zellij pane ${surface} is not managed in this tab.`); if (pane.is_floating !== true && pane.is_floating !== false) throw new Error(`zellij pane ${surface} has unknown floating state.`); if (pane.is_floating) zellijAction(["toggle-pane-embed-or-floating", "--pane-id", zellijPaneId(surface)]); zellijFocusPane(opts.focus === "owner" ? zellijCurrentPane() : zellijPaneId(surface)); return zellijDisplayPaneId(surface);
}

export function detachSurface(surface: string, opts: { ownerWindowIndex?: string; windowName?: string } = {}): void {
  const backend = requireMuxBackend();
  if (backend === "tmux") {
    const ctx = opts.ownerWindowIndex ? undefined : tmuxContext();
    const breakArgs = ["break-pane", "-s", surface, "-P", "-F", "#{window_id}"];
    if (opts.windowName) breakArgs.push("-n", opts.windowName);
    const newWindowId = xfile("tmux", breakArgs).trimEnd();
    if (newWindowId) try { xfile("tmux", ["set-option", "-t", newWindowId, "-w", "automatic-rename", "off"]); } catch {}
    xfile("tmux", ["select-window", "-t", opts.ownerWindowIndex ?? ctx!.index]);
    return;
  }
  if (backend === "cmux") { const ref = cmuxSurfaceRef(surface); try { xshell(`cmux split-off --surface ${shellEscape(ref)}`); } catch { const split = xshell("cmux new-split").trim(); xshell(`cmux move-surface --surface ${shellEscape(ref)} --pane ${shellEscape(split)} --focus false`); } return; }
  if (backend === "wezterm") { const owner = process.env.WEZTERM_PANE; const pane = weztermPane(surface, false); const paneId = pane?.pane_id?.toString() ?? surface.replace(/^pane:/, ""); xfile("wezterm", ["cli", "move-pane-to-new-tab", "--pane-id", paneId]); if (opts.windowName) try { xfile("wezterm", ["cli", "set-tab-title", "--pane-id", paneId, opts.windowName]); } catch {} if (owner) xfile("wezterm", ["cli", "activate-pane", "--pane-id", owner]); return; }
  const pane = zellijPane(surface); if (!pane) throw new Error(`zellij pane ${surface} is not managed in this tab.`); if (pane.is_floating === false) zellijAction(["toggle-pane-embed-or-floating", "--pane-id", zellijPaneId(surface)]); zellijFocusPane(zellijCurrentPane());
}

import type { ExecFn, MonitorBackend } from "./types.js";
import { commandExists, sleep, sq } from "./types.js";
import { TmuxSessionManager } from "./tmux-sessions.js";

/**
 * Ghostty backend.
 *
 * On macOS, Ghostty exposes a full AppleScript scripting dictionary
 * (`Ghostty.sdef`). That dictionary defines a `split <terminal> direction
 * <dir> with configuration {command:"..."}` command, which lets us create
 * an in-place split inside pi's own window — much nicer than a detached
 * monitor window.
 *
 * On other platforms (Linux/GTK) Ghostty has no equivalent remote-control
 * API, so we fall back to spawning a dedicated Ghostty monitor window that
 * attaches to the selected tmux session.
 */

/** Escape a string for embedding inside an AppleScript double-quoted literal. */
function escapeOsa(s: string): string {
  return s.replace(/\\/g, "\\\\").replace(/"/g, '\\"');
}

export class GhosttyBackend implements MonitorBackend {
  readonly label = "ghostty";

  // ── macOS: AppleScript in-place split state ──────────
  private piTerminalId: string | null = null;
  private monitorTerminalId: string | null = null;

  // ── Linux: detached monitor window state ─────────────
  private monitorPid = 0;

  private attachedSession: string | null = null;
  private exec: ExecFn;
  private sessions: TmuxSessionManager;

  /** Resolved absolute path to `tmux`. Needed on macOS because Ghostty
   *  launches commands via `login -flp $USER /bin/bash --noprofile --norc`,
   *  which does not load the user's shell profile or any nix/home-manager
   *  PATH additions. Cached after the first successful resolution. */
  private tmuxPath: string | null = null;

  constructor(exec: ExecFn, sessions: TmuxSessionManager) {
    this.exec = exec;
    this.sessions = sessions;
  }

  // ── shared helpers ───────────────────────────────────

  private async bash(
    command: string,
  ): Promise<{ stdout: string; stderr: string; code: number }> {
    const r = await this.exec("bash", ["-lc", command], { timeout: 5000 });
    return { stdout: r.stdout, stderr: r.stderr, code: r.code ?? 1 };
  }

  private async osa(
    script: string,
  ): Promise<{ stdout: string; code: number }> {
    const r = await this.exec("osascript", ["-e", script], { timeout: 5000 });
    return { stdout: r.stdout.trim(), code: r.code ?? 1 };
  }

  private titleFor(sessionName: string): string {
    return `π - ${sessionName}`;
  }

  /** Resolve the absolute path to `tmux` from pi's environment. Falls back
   *  to the bare name if resolution fails. */
  private async resolveTmuxPath(): Promise<string> {
    if (this.tmuxPath) return this.tmuxPath;
    const r = await this.bash("command -v tmux 2>/dev/null").catch(() => ({
      stdout: "",
      stderr: "",
      code: 1,
    }));
    const path = r.stdout.trim();
    this.tmuxPath = path || "tmux";
    return this.tmuxPath;
  }

  /** Build an attach command suitable for Ghostty's macOS launcher.
   *
   *  Ghostty on macOS wraps the `command` surface-configuration value in
   *  `login -flp $USER /bin/bash --noprofile --norc -c "exec -l <command>"`.
   *  That bash shell has no profile/rc, so it only sees whatever PATH
   *  `login -p` preserved from Ghostty.app's launchd environment. For users
   *  who installed tmux via nix / home-manager that PATH usually does not
   *  include tmux, so we reference both `env` and `tmux` by absolute path.
   *  `/usr/bin/env` is a standard macOS binary; `tmux` is resolved from
   *  pi's own environment. */
  private async darwinAttachCommand(sessionName: string): Promise<string> {
    const tmux = await this.resolveTmuxPath();
    return (
      `/usr/bin/env -u TMUX ${sq(tmux)} ` +
      `-L ${sq(this.sessions.socketName)} ` +
      `attach-session -t ${sq(sessionName)}`
    );
  }

  // ── macOS: AppleScript split path ────────────────────

  private async osaTerminalExists(id: string): Promise<boolean> {
    const script =
      `tell application "Ghostty" to return ` +
      `(count of (terminals whose id is "${escapeOsa(id)}"))`;
    const r = await this.osa(script).catch(() => ({ stdout: "", code: 1 }));
    if (r.code !== 0) return false;
    return parseInt(r.stdout, 10) > 0;
  }

  private async darwinMonitorAlive(): Promise<boolean> {
    if (!this.monitorTerminalId) return false;
    const alive = await this.osaTerminalExists(this.monitorTerminalId);
    if (!alive) {
      this.monitorTerminalId = null;
      this.attachedSession = null;
    }
    return alive;
  }

  private async spawnDarwinSplit(sessionName: string): Promise<boolean> {
    const attach = await this.darwinAttachCommand(sessionName);
    const cmd = escapeOsa(attach);

    // Capture pi's terminal before the split, trigger the split, then
    // refocus pi so the user's cursor stays where it was.
    const script =
      `tell application "Ghostty"
  activate
  set frontWin to front window
  set piTerm to focused terminal of (selected tab of frontWin)
  set piID to id of piTerm
  set newTerm to split piTerm direction down with configuration {command:"${cmd}"}
  set newID to id of newTerm
  focus piTerm
  return newID & "|" & piID
end tell`;

    const r = await this.osa(script);
    if (r.code !== 0 || !r.stdout) return false;

    const [newId, piId] = r.stdout.split("|");
    if (!newId || !piId) return false;

    this.monitorTerminalId = newId;
    this.piTerminalId = piId;
    this.attachedSession = sessionName;
    return true;
  }

  private async closeDarwinMonitor(): Promise<void> {
    if (!this.monitorTerminalId) return;
    const id = this.monitorTerminalId;
    const piId = this.piTerminalId;

    const focusBack = piId
      ? `
  set piTerms to terminals whose id is "${escapeOsa(piId)}"
  if (count of piTerms) > 0 then focus (item 1 of piTerms)`
      : "";

    const script =
      `tell application "Ghostty"
  set mTerms to terminals whose id is "${escapeOsa(id)}"
  if (count of mTerms) > 0 then close (item 1 of mTerms)${focusBack}
end tell`;

    await this.osa(script).catch(() => {});
    this.monitorTerminalId = null;
    this.attachedSession = null;
  }

  private async focusDarwinMonitor(): Promise<void> {
    if (!this.monitorTerminalId) return;
    const script =
      `tell application "Ghostty"
  set mTerms to terminals whose id is "${escapeOsa(this.monitorTerminalId)}"
  if (count of mTerms) > 0 then focus (item 1 of mTerms)
end tell`;
    await this.osa(script).catch(() => {});
  }

  // ── Linux: dedicated monitor window fallback ─────────

  private async monitorPidAlive(): Promise<boolean> {
    if (this.monitorPid <= 0) return false;
    const r = await this.bash(`kill -0 ${this.monitorPid} >/dev/null 2>&1`)
      .catch(() => ({ stdout: "", stderr: "", code: 1 }));
    const alive = r.code === 0;
    if (!alive) {
      this.monitorPid = 0;
      this.attachedSession = null;
    }
    return alive;
  }

  private async spawnLinux(sessionName: string): Promise<boolean> {
    const title = this.titleFor(sessionName);
    const attach = this.sessions.attachCommand(sessionName);
    const command =
      "ghostty " +
      "--gtk-single-instance=false " +
      "--quit-after-last-window-closed=true " +
      `--title=${sq(title)} ` +
      `-e sh -lc ${sq(attach)} ` +
      ">/dev/null 2>&1 & echo $!";

    const r = await this.bash(command);
    if (r.code !== 0) return false;

    const pid = parseInt(r.stdout.trim().split("\n").pop() || "", 10);
    if (!pid || Number.isNaN(pid)) return false;

    this.monitorPid = pid;
    this.attachedSession = sessionName;

    const deadline = Date.now() + 5000;
    while (Date.now() < deadline) {
      if (await this.monitorPidAlive()) return true;
      await sleep(100);
    }

    return false;
  }

  private async killLinuxMonitor(): Promise<void> {
    if (!(await this.monitorPidAlive())) return;
    const pid = this.monitorPid;
    await this.bash(`kill ${pid} >/dev/null 2>&1 || true`).catch(() => {});

    const deadline = Date.now() + 3000;
    while (Date.now() < deadline) {
      if (!(await this.monitorPidAlive())) break;
      await sleep(100);
    }

    if (await this.monitorPidAlive()) {
      await this.bash(`kill -9 ${pid} >/dev/null 2>&1 || true`).catch(() => {});
      this.monitorPid = 0;
      this.attachedSession = null;
    }
  }

  // ── MonitorBackend API ───────────────────────────────

  async isVisible(): Promise<boolean> {
    if (process.platform === "darwin") return this.darwinMonitorAlive();
    return this.monitorPidAlive();
  }

  displayTarget(): string {
    if (process.platform === "darwin") {
      return this.monitorTerminalId
        ? `ghostty:${this.monitorTerminalId}`
        : "(hidden)";
    }
    return this.monitorPid > 0 ? `ghostty:${this.monitorPid}` : "(hidden)";
  }

  async show(sessionName: string): Promise<boolean> {
    if (await this.isVisible()) return this.attachSession(sessionName);
    if (process.platform === "darwin") return this.spawnDarwinSplit(sessionName);
    return this.spawnLinux(sessionName);
  }

  async attachSession(sessionName: string): Promise<boolean> {
    if (!(await this.isVisible())) return this.show(sessionName);
    await this.hide();
    if (process.platform === "darwin") return this.spawnDarwinSplit(sessionName);
    return this.spawnLinux(sessionName);
  }

  async hide(): Promise<void> {
    if (process.platform === "darwin") {
      await this.closeDarwinMonitor();
    } else {
      await this.killLinuxMonitor();
    }
  }

  async focus(): Promise<void> {
    if (!(await this.isVisible())) return;
    if (process.platform === "darwin") {
      await this.focusDarwinMonitor();
      return;
    }
    const hasXdotool = await commandExists(this.exec, "xdotool");
    if (!hasXdotool) return;
    await this.bash(
      `xdotool search --pid ${this.monitorPid} 2>/dev/null | head -n 1 | xargs -r xdotool windowactivate >/dev/null 2>&1 || true`,
    ).catch(() => {});
  }

  async cleanup(): Promise<void> {
    await this.hide();
  }

  async getDebugInfo(): Promise<Record<string, string | number | boolean>> {
    return {
      termProgramGhostty:
        (process.env.TERM_PROGRAM || "").toLowerCase() === "ghostty",
      ghosttyOnPath: await commandExists(this.exec, "ghostty"),
      platform: process.platform,
      mode: process.platform === "darwin" ? "applescript-split" : "new-window",
      tmuxPath:
        process.platform === "darwin"
          ? await this.resolveTmuxPath()
          : "(n/a)",
      monitorTerminalId: this.monitorTerminalId || "(none)",
      piTerminalId: this.piTerminalId || "(none)",
      monitorPid: this.monitorPid || 0,
      attachedSession: this.attachedSession || "(none)",
      socket: this.sessions.socketName,
      visible: await this.isVisible(),
    };
  }
}

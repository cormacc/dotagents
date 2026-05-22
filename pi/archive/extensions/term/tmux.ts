import type { ExecFn, MonitorBackend } from "./types.js";
import { commandExists, DEFAULT_PANE_HEIGHT_PCT } from "./types.js";
import { TmuxSessionManager } from "./tmux-sessions.js";

export class TmuxBackend implements MonitorBackend {
  readonly label = "tmux";

  private paneId = "";
  private attachedSession: string | null = null;
  private exec: ExecFn;
  private sessions: TmuxSessionManager;

  constructor(exec: ExecFn, sessions: TmuxSessionManager) {
    this.exec = exec;
    this.sessions = sessions;
  }

  private async tmux(
    ...args: string[]
  ): Promise<{ stdout: string; stderr: string; code: number }> {
    const r = await this.exec("tmux", args, { timeout: 5000 });
    return { stdout: r.stdout, stderr: r.stderr, code: r.code ?? 1 };
  }

  private async paneAlive(): Promise<boolean> {
    if (!this.paneId) return false;
    const r = await this.tmux("list-panes", "-s", "-F", "#{pane_id}");
    const alive = r.code === 0 && r.stdout.trim().split("\n").includes(this.paneId);
    if (!alive) {
      this.paneId = "";
      this.attachedSession = null;
    }
    return alive;
  }

  async isVisible(): Promise<boolean> {
    return this.paneAlive();
  }

  displayTarget(): string {
    return this.paneId || "(hidden)";
  }

  async show(sessionName: string): Promise<boolean> {
    if (await this.paneAlive()) {
      await this.attachSession(sessionName);
      return true;
    }

    const r = await this.tmux(
      "split-window",
      "-v",
      "-d",
      "-l",
      `${DEFAULT_PANE_HEIGHT_PCT}%`,
      "-P",
      "-F",
      "#{pane_id}",
      this.sessions.attachCommand(sessionName),
    );
    if (r.code !== 0) return false;

    this.paneId = r.stdout.trim();
    this.attachedSession = sessionName;
    await this.tmux("select-pane", "-U").catch(() => {});
    return true;
  }

  async attachSession(sessionName: string): Promise<boolean> {
    if (!(await this.paneAlive())) {
      return this.show(sessionName);
    }

    const r = await this.tmux(
      "respawn-pane",
      "-k",
      "-t",
      this.paneId,
      this.sessions.attachCommand(sessionName),
    );
    if (r.code !== 0) return false;

    this.attachedSession = sessionName;
    await this.tmux("select-pane", "-U").catch(() => {});
    return true;
  }

  async hide(): Promise<void> {
    if (!(await this.paneAlive())) return;
    await this.tmux("kill-pane", "-t", this.paneId).catch(() => {});
    this.paneId = "";
    this.attachedSession = null;
  }

  async focus(): Promise<void> {
    if (!(await this.paneAlive())) return;
    await this.tmux("select-pane", "-t", this.paneId).catch(() => {});
  }

  async cleanup(): Promise<void> {
    await this.hide();
  }

  async getDebugInfo(): Promise<Record<string, string | boolean>> {
    return {
      tmuxOnPath: await commandExists(this.exec, "tmux"),
      outerTmuxSession: Boolean(process.env.TMUX),
      monitorPane: this.paneId || "(none)",
      visible: await this.isVisible(),
      attachedSession: this.attachedSession || "(none)",
      socket: this.sessions.socketName,
    };
  }
}

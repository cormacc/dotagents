import type { ExecFn, MonitorBackend } from "./types.js";
import { commandExists } from "./types.js";
import { TmuxSessionManager } from "./tmux-sessions.js";

export class KittyBackend implements MonitorBackend {
  readonly label = "kitty";

  private windowId = 0;
  private piWindowId = 0;
  private attachedSession: string | null = null;
  private exec: ExecFn;
  private sessions: TmuxSessionManager;

  constructor(exec: ExecFn, sessions: TmuxSessionManager) {
    this.exec = exec;
    this.sessions = sessions;
    this.piWindowId = parseInt(process.env.KITTY_WINDOW_ID || "0", 10);
  }

  private async kitten(
    ...args: string[]
  ): Promise<{ stdout: string; stderr: string; code: number }> {
    const r = await this.exec("kitten", ["@", ...args], { timeout: 5000 });
    return { stdout: r.stdout, stderr: r.stderr, code: r.code ?? 1 };
  }

  private async lsTree(): Promise<any[]> {
    const r = await this.kitten("ls");
    if (r.code !== 0) return [];
    try {
      return JSON.parse(r.stdout);
    } catch {
      return [];
    }
  }

  private async findWindowInTree(winId: number): Promise<any | null> {
    if (winId <= 0) return null;
    const tree = await this.lsTree();
    for (const osWin of tree) {
      for (const tab of osWin.tabs || []) {
        for (const win of tab.windows || []) {
          if (win.id === winId) return { osWindow: osWin, tab, window: win };
        }
      }
    }
    return null;
  }

  private async windowAlive(): Promise<boolean> {
    if (this.windowId <= 0) return false;
    const found = await this.findWindowInTree(this.windowId);
    const alive = found !== null;
    if (!alive) {
      this.windowId = 0;
      this.attachedSession = null;
    }
    return alive;
  }

  private async spawnMonitor(sessionName: string): Promise<boolean> {
    const title = `π - ${sessionName}`;
    const attach = this.sessions.attachCommand(sessionName);

    let r = await this.kitten(
      "launch",
      "--type=window",
      "--keep-focus",
      "--location=hsplit",
      `--title=${title}`,
      "sh",
      "-lc",
      attach,
    );

    if (r.code !== 0) {
      r = await this.kitten(
        "launch",
        "--type=window",
        "--keep-focus",
        `--title=${title}`,
        "sh",
        "-lc",
        attach,
      );
    }

    if (r.code !== 0) return false;

    const winId = parseInt(r.stdout.trim(), 10);
    if (!winId || isNaN(winId)) return false;

    this.windowId = winId;
    this.attachedSession = sessionName;

    if (this.piWindowId > 0) {
      await this.kitten(
        "focus-window",
        "--match",
        `id:${this.piWindowId}`,
      ).catch(() => {});
    }

    return true;
  }

  async isVisible(): Promise<boolean> {
    return this.windowAlive();
  }

  displayTarget(): string {
    return this.windowId > 0 ? `kitty:${this.windowId}` : "(hidden)";
  }

  async show(sessionName: string): Promise<boolean> {
    if (await this.isVisible()) {
      return this.attachSession(sessionName);
    }
    return this.spawnMonitor(sessionName);
  }

  async attachSession(sessionName: string): Promise<boolean> {
    if (!(await this.isVisible())) {
      return this.spawnMonitor(sessionName);
    }
    await this.hide();
    return this.spawnMonitor(sessionName);
  }

  async hide(): Promise<void> {
    if (!(await this.isVisible())) return;
    await this.kitten(
      "close-window",
      "--match",
      `id:${this.windowId}`,
    ).catch(() => {});
    this.windowId = 0;
    this.attachedSession = null;
  }

  async focus(): Promise<void> {
    if (!(await this.isVisible())) return;
    await this.kitten(
      "focus-window",
      "--match",
      `id:${this.windowId}`,
    ).catch(() => {});
  }

  async cleanup(): Promise<void> {
    await this.hide();
  }

  async getDebugInfo(): Promise<Record<string, string | number | boolean>> {
    return {
      kittyWindowPresent: Boolean(process.env.KITTY_WINDOW_ID),
      kittenOnPath: await commandExists(this.exec, "kitten"),
      tmuxOnPath: await commandExists(this.exec, "tmux"),
      piWindowId: this.piWindowId,
      monitorWindowId: this.windowId || 0,
      attachedSession: this.attachedSession || "(none)",
      socket: this.sessions.socketName,
      visible: await this.isVisible(),
    };
  }
}

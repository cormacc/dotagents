import { randomUUID } from "node:crypto";

import type { ExecFn } from "./types.js";
import { sleep, sq } from "./types.js";

export interface TmuxSessionInfo {
  name: string;
  windows: number;
  attached: number;
  created: number;
}

export class TmuxSessionManager {
  readonly socketName: string;

  private exec: ExecFn;

  constructor(exec: ExecFn, socketName = `pi-term-${randomUUID().slice(0, 8)}`) {
    this.exec = exec;
    this.socketName = socketName;
  }

  private async tmux(
    ...args: string[]
  ): Promise<{ stdout: string; stderr: string; code: number }> {
    const r = await this.exec("tmux", ["-L", this.socketName, ...args], {
      timeout: 5000,
    });
    return { stdout: r.stdout, stderr: r.stderr, code: r.code ?? 1 };
  }

  private target(name: string): string {
    return `${name}:0.0`;
  }

  attachCommand(name: string): string {
    return `env -u TMUX tmux -L ${sq(this.socketName)} attach-session -t ${sq(name)}`;
  }

  async hasSession(name: string): Promise<boolean> {
    const r = await this.tmux("has-session", "-t", name);
    return r.code === 0;
  }

  async listSessions(): Promise<TmuxSessionInfo[]> {
    const r = await this.tmux(
      "list-sessions",
      "-F",
      "#{session_name}\t#{session_windows}\t#{session_attached}\t#{session_created}",
    );
    if (r.code !== 0) return [];

    return r.stdout
      .split("\n")
      .map((line) => line.trim())
      .filter(Boolean)
      .map((line) => {
        const [name, windows, attached, created] = line.split("\t");
        return {
          name,
          windows: parseInt(windows, 10) || 0,
          attached: parseInt(attached, 10) || 0,
          created: parseInt(created, 10) || 0,
        };
      })
      .sort((a, b) => a.name.localeCompare(b.name));
  }

  async createSession(
    name: string,
    cwd: string,
    command?: string,
  ): Promise<boolean> {
    if (await this.hasSession(name)) return false;

    const r = await this.tmux("new-session", "-d", "-s", name, "-c", cwd);
    if (r.code !== 0) return false;

    if (command) {
      await sleep(200);
      await this.sendCommand(name, cwd, command);
    }

    return true;
  }

  async ensureSession(name: string, cwd: string): Promise<boolean> {
    if (await this.hasSession(name)) return true;
    return this.createSession(name, cwd);
  }

  async killSession(name: string): Promise<void> {
    await this.tmux("kill-session", "-t", name).catch(() => {});
  }

  async getPaneCwd(name: string): Promise<string> {
    const r = await this.tmux(
      "display-message",
      "-t",
      this.target(name),
      "-p",
      "#{pane_current_path}",
    );
    return r.code === 0 ? r.stdout.trim() : process.cwd();
  }

  async captureSession(name: string, lines = 200): Promise<string> {
    const r = await this.tmux(
      "capture-pane",
      "-p",
      "-J",
      "-t",
      this.target(name),
      "-S",
      `-${Math.max(1, lines)}`,
    );
    return r.code === 0 ? r.stdout.trim() : "";
  }

  async sendText(name: string, text: string): Promise<void> {
    await this.tmux("send-keys", "-t", this.target(name), "-l", text);
  }

  async sendEnter(name: string): Promise<void> {
    await this.tmux("send-keys", "-t", this.target(name), "Enter");
  }

  async sendCtrlC(name: string): Promise<void> {
    await this.tmux("send-keys", "-t", this.target(name), "C-c");
  }

  async sendCommand(name: string, cwd: string, command: string): Promise<void> {
    const paneCwd = await this.getPaneCwd(name);
    let sendCmd = paneCwd !== cwd ? `cd ${sq(cwd)} && ${command}` : command;

    if (sendCmd.includes("\n")) {
      sendCmd = `{\n${sendCmd}\n}`;
    }

    await this.sendText(name, sendCmd);
    await this.sendEnter(name);
  }

  async killServer(): Promise<void> {
    await this.tmux("kill-server").catch(() => {});
  }
}

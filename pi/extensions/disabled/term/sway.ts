import { randomUUID } from "node:crypto";

import type { ExecFn, MonitorBackend } from "./types.js";
import { commandExists, DEFAULT_PANE_HEIGHT_PCT, sleep, sq } from "./types.js";
import { TmuxSessionManager } from "./tmux-sessions.js";

const APP_ID_PREFIX = "pi-term-monitor";

export class SwayBackend implements MonitorBackend {
  readonly label = "sway";

  private conId = 0;
  private piConId = 0;
  private attachedSession: string | null = null;
  private exec: ExecFn;
  private sessions: TmuxSessionManager;
  private readonly sessionId: string;
  private appId = "";

  constructor(exec: ExecFn, sessions: TmuxSessionManager) {
    this.exec = exec;
    this.sessions = sessions;
    this.sessionId = randomUUID().slice(0, 8);
  }

  private async swaymsg(
    ...args: string[]
  ): Promise<{ stdout: string; stderr: string; code: number }> {
    const r = await this.exec("swaymsg", args, { timeout: 5000 });
    return { stdout: r.stdout, stderr: r.stderr, code: r.code ?? 1 };
  }

  private async getTree(): Promise<any> {
    const r = await this.swaymsg("-t", "get_tree");
    if (r.code !== 0) return null;
    try {
      return JSON.parse(r.stdout);
    } catch {
      return null;
    }
  }

  private findNode(tree: any, predicate: (n: any) => boolean): any | null {
    if (!tree) return null;
    if (predicate(tree)) return tree;
    for (const child of [
      ...(tree.nodes || []),
      ...(tree.floating_nodes || []),
    ]) {
      const found = this.findNode(child, predicate);
      if (found) return found;
    }
    return null;
  }

  private async getFocusedConId(): Promise<number> {
    const tree = await this.getTree();
    if (!tree) return 0;
    const focused = this.findNode(tree, (n: any) => n.focused === true);
    return focused?.id ?? 0;
  }

  private async ensureOwnershipIds(): Promise<void> {
    if (!this.piConId) {
      this.piConId = await this.getFocusedConId();
    }
    if (!this.appId) {
      const ownerId = this.piConId > 0 ? String(this.piConId) : this.sessionId;
      this.appId = `${APP_ID_PREFIX}-${ownerId}`;
    }
  }

  private async findMonitorWindow(): Promise<any | null> {
    const tree = await this.getTree();
    if (!tree) return null;

    if (this.conId > 0) {
      const byId = this.findNode(tree, (n: any) => n.id === this.conId);
      if (byId) return byId;
    }

    await this.ensureOwnershipIds();
    return this.findNode(
      tree,
      (n: any) => n.app_id === this.appId && n.type === "con",
    );
  }

  private async refreshMonitor(): Promise<boolean> {
    const node = await this.findMonitorWindow();
    if (!node) {
      this.conId = 0;
      this.attachedSession = null;
      return false;
    }
    this.conId = node.id;
    return true;
  }

  private launchCommand(sessionName: string): string {
    const attach = this.sessions.attachCommand(sessionName);
    const title = `π - ${sessionName}`;
    return `foot --app-id ${this.appId} --title ${sq(title)} sh -lc ${sq(attach)}`;
  }

  private async spawnMonitor(sessionName: string): Promise<boolean> {
    await this.ensureOwnershipIds();

    if (this.piConId > 0) {
      await this.swaymsg(`[con_id=${this.piConId}]`, "focus").catch(() => {});
    }
    await this.swaymsg("splitv").catch(() => {});

    const command = this.launchCommand(sessionName);
    const r = await this.exec("bash", ["-lc", `swaymsg exec ${sq(command)}`], {
      timeout: 5000,
    });
    if (r.code !== 0 && r.code !== null) return false;

    const deadline = Date.now() + 10000;
    while (Date.now() < deadline) {
      if (await this.refreshMonitor()) {
        await this.swaymsg(
          `[con_id=${this.conId}]`,
          "resize",
          "set",
          "height",
          String(DEFAULT_PANE_HEIGHT_PCT),
          "ppt",
        ).catch(() => {});
        if (this.piConId > 0) {
          await this.swaymsg(`[con_id=${this.piConId}]`, "focus").catch(() => {});
        }
        this.attachedSession = sessionName;
        return true;
      }
      await sleep(250);
    }

    return false;
  }

  async isVisible(): Promise<boolean> {
    return this.refreshMonitor();
  }

  displayTarget(): string {
    return this.conId > 0 ? `sway:${this.conId}` : "(hidden)";
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
    await this.swaymsg(`[con_id=${this.conId}]`, "kill").catch(() => {});
    this.conId = 0;
    this.attachedSession = null;
    if (this.piConId > 0) {
      await this.swaymsg(`[con_id=${this.piConId}]`, "focus").catch(() => {});
    }
  }

  async focus(): Promise<void> {
    if (!(await this.isVisible())) return;
    await this.swaymsg(`[con_id=${this.conId}]`, "focus").catch(() => {});
  }

  async cleanup(): Promise<void> {
    await this.hide();
  }

  async getDebugInfo(): Promise<Record<string, string | number | boolean>> {
    return {
      swaySocketPresent: Boolean(process.env.SWAYSOCK),
      swaymsgOnPath: await commandExists(this.exec, "swaymsg"),
      footOnPath: await commandExists(this.exec, "foot"),
      tmuxOnPath: await commandExists(this.exec, "tmux"),
      appId: this.appId || "(unset)",
      piConId: this.piConId,
      monitorConId: this.conId,
      attachedSession: this.attachedSession || "(none)",
      socket: this.sessions.socketName,
      visible: await this.isVisible(),
      sessionId: this.sessionId,
    };
  }
}

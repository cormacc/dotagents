#!/usr/bin/env tsx
/**
 * Isolated socket checks for the upstream-managed `herdr-agent-state.ts` integration.
 *
 * The managed file is upstream-owned (Herdr integration revision 9, commit
 * 065ef9d6a531c49fb8bee7e818ef837065b21ee9) and carries no local hooks, so every check here
 * drives it through its public surface only: the environment it reads at import time, the
 * `pi.on`/`pi.events.on` handlers it registers, and the newline-delimited JSON it writes to
 * `HERDR_SOCKET_PATH`. A disposable UNIX socket server stands in for Herdr.
 */

import { mkdtempSync, rmSync } from "node:fs";
import net from "node:net";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { pathToFileURL } from "node:url";

type Request = { method: string; params: Record<string, unknown> };
type Listener = (...args: any[]) => unknown;

let failures = 0;
function ok(condition: unknown, message: string): void {
  if (condition) console.log(`ok - ${message}`);
  else {
    console.log(`not ok - ${message}`);
    failures++;
  }
}

async function fakeHerdr(socketPath: string) {
  const requests: Request[] = [];
  const server = net.createServer((socket) => {
    let buffer = "";
    socket.on("data", (chunk) => {
      buffer += chunk.toString();
      let newline = buffer.indexOf("\n");
      while (newline >= 0) {
        const line = buffer.slice(0, newline);
        buffer = buffer.slice(newline + 1);
        if (line.trim()) requests.push(JSON.parse(line) as Request);
        socket.write('{"result":{}}\n');
        newline = buffer.indexOf("\n");
      }
    });
  });
  await new Promise<void>((resolve) => server.listen(socketPath, resolve));
  return {
    requests,
    async settle(expected: number, timeoutMs = 3000): Promise<void> {
      const deadline = Date.now() + timeoutMs;
      while (requests.length < expected && Date.now() < deadline) await new Promise((r) => setTimeout(r, 10));
      // A little longer, so an *unexpected* extra report has time to arrive and be counted.
      await new Promise((r) => setTimeout(r, 150));
    },
    close: () => new Promise<void>((resolve) => server.close(() => resolve())),
  };
}

function fakePi() {
  const handlers = new Map<string, Listener[]>();
  const topics = new Map<string, Listener[]>();
  const api = {
    on(event: string, handler: Listener) {
      handlers.set(event, [...(handlers.get(event) ?? []), handler]);
    },
    events: {
      on(topic: string, listener: Listener) {
        topics.set(topic, [...(topics.get(topic) ?? []), listener]);
        return () => {};
      },
    },
  };
  return {
    api,
    async fire(event: string, payload: unknown, ctx: unknown) {
      for (const handler of handlers.get(event) ?? []) await handler(payload, ctx);
    },
    async emit(topic: string, payload: unknown) {
      for (const listener of topics.get(topic) ?? []) await listener(payload);
    },
    registered: () => [...handlers.keys()].sort(),
  };
}

function ctxFor(mode: string, options: { idle?: boolean; file?: string } = {}) {
  return {
    mode,
    hasUI: true,
    isIdle: () => options.idle ?? true,
    sessionManager: {
      getSessionFile: () => options.file ?? "/tmp/fake-session.jsonl",
      getSessionId: () => "fake-session-id",
    },
  };
}

const moduleUrl = pathToFileURL(join(import.meta.dirname, "..", "herdr-agent-state.ts")).href;
let importSeq = 0;
// The managed module reads its environment and keeps its report state at module scope, so
// each scenario imports a fresh instance through a cache-busting query.
async function loadIntegration(socketPath: string) {
  process.env.HERDR_ENV = "1";
  process.env.HERDR_SOCKET_PATH = socketPath;
  process.env.HERDR_PANE_ID = "w1:p9";
  const mod = await import(`${moduleUrl}?case=${importSeq++}`);
  return mod.default as (pi: unknown) => void;
}

const root = mkdtempSync(join(tmpdir(), "herdr-agent-state-"));
try {
  // --- TUI session: identity and working/idle lifecycle reports ---------------------
  {
    const herdr = await fakeHerdr(join(root, "tui.sock"));
    const pi = fakePi();
    (await loadIntegration(join(root, "tui.sock")))(pi.api);
    ok(pi.registered().join(",") === "agent_settled,agent_start,session_start", "TUI: registers session_start, agent_start and agent_settled only");

    await pi.fire("session_start", { reason: "startup" }, ctxFor("tui"));
    await herdr.settle(2);
    const session = herdr.requests.find((r) => r.method === "pane.report_agent_session");
    ok(session?.params.pane_id === "w1:p9" && session?.params.agent === "pi", "TUI: session_start reports session identity for the pane");
    ok(session?.params.agent_session_path === "/tmp/fake-session.jsonl", "TUI: identity report carries the absolute session path");
    ok(session?.params.session_start_source === "startup", "TUI: identity report carries the session_start reason");
    const initial = herdr.requests.find((r) => r.method === "pane.report_agent");
    ok(initial?.params.state === "idle", "TUI: an idle session_start reports idle");

    const before = herdr.requests.length;
    await pi.fire("agent_start", {}, ctxFor("tui", { idle: false }));
    await herdr.settle(before + 2);
    const states = herdr.requests.filter((r) => r.method === "pane.report_agent").map((r) => r.params.state);
    ok(states[states.length - 1] === "working", "TUI: agent_start reports working");
    ok(herdr.requests.filter((r) => r.method === "pane.report_agent_session").length === 2, "TUI: agent_start re-reports session identity");

    const beforeSettle = herdr.requests.length;
    await pi.fire("agent_settled", {}, ctxFor("tui", { idle: true }));
    await herdr.settle(beforeSettle + 1);
    const settled = herdr.requests.filter((r) => r.method === "pane.report_agent").map((r) => r.params.state);
    ok(settled[settled.length - 1] === "idle", "TUI: agent_settled with an idle agent reports idle");

    const beforeBusy = herdr.requests.length;
    await pi.fire("agent_settled", {}, ctxFor("tui", { idle: false }));
    await herdr.settle(beforeBusy + 1, 300);
    ok(herdr.requests.length === beforeBusy, "TUI: agent_settled while not idle reports nothing");

    // Blocked state still flows through the upstream `herdr:blocked` bus topic.
    const beforeBlocked = herdr.requests.length;
    await pi.emit("herdr:blocked", { active: true, label: "Approve tool?" });
    await herdr.settle(beforeBlocked + 1);
    const blocked = herdr.requests[herdr.requests.length - 1];
    ok(blocked?.params.state === "blocked" && blocked?.params.message === "Approve tool?", "TUI: herdr:blocked active reports blocked with its label");
    await pi.emit("herdr:blocked", { active: false });
    await herdr.settle(herdr.requests.length + 1);
    ok(herdr.requests[herdr.requests.length - 1]?.params.state === "idle", "TUI: herdr:blocked release returns to idle");

    // No session_shutdown handler exists at revision 9: nothing releases the agent.
    ok(!herdr.requests.some((r) => r.method === "pane.release_agent"), "TUI: no pane.release_agent is ever sent");
    await herdr.close();
  }

  // --- RPC session: hasUI is true but mode is not tui -> silence ----------------------
  {
    const herdr = await fakeHerdr(join(root, "rpc.sock"));
    const pi = fakePi();
    (await loadIntegration(join(root, "rpc.sock")))(pi.api);
    await pi.fire("session_start", { reason: "startup" }, ctxFor("rpc"));
    await pi.fire("agent_start", {}, ctxFor("rpc", { idle: false }));
    await pi.fire("agent_settled", {}, ctxFor("rpc", { idle: true }));
    await pi.emit("herdr:blocked", { active: true, label: "Approve?" });
    await herdr.settle(1, 300);
    ok(!herdr.requests.some((r) => r.method === "pane.report_agent_session"), "RPC: no session identity report even though hasUI is true");
    ok(!herdr.requests.some((r) => r.method === "pane.report_agent"), "RPC: no lifecycle report even though hasUI is true");
    ok(herdr.requests.length === 0, "RPC: no socket traffic at all");
    await herdr.close();
  }

  // --- Root-session gate: lifecycle events before any TUI session_start are ignored ---
  {
    const herdr = await fakeHerdr(join(root, "gate.sock"));
    const pi = fakePi();
    (await loadIntegration(join(root, "gate.sock")))(pi.api);
    await pi.fire("agent_start", {}, ctxFor("tui", { idle: false }));
    await pi.emit("herdr:blocked", { active: true, label: "x" });
    await herdr.settle(1, 300);
    ok(herdr.requests.length === 0, "gate: agent_start and herdr:blocked before session_start report nothing");
    await herdr.close();
  }

  // --- Reload and session switch: re-report identity, force-publish current state -----
  {
    const herdr = await fakeHerdr(join(root, "reload.sock"));
    const pi = fakePi();
    (await loadIntegration(join(root, "reload.sock")))(pi.api);
    await pi.fire("session_start", { reason: "startup" }, ctxFor("tui"));
    await herdr.settle(2);
    const before = herdr.requests.length;
    // A reload mid-turn: the agent is already working and no agent_start will follow.
    await pi.fire("session_start", { reason: "reload" }, ctxFor("tui", { idle: false }));
    await herdr.settle(before + 2);
    const reloaded = herdr.requests.slice(before);
    ok(reloaded.some((r) => r.method === "pane.report_agent_session" && r.params.session_start_source === "reload"), "reload: session identity is re-reported with source reload");
    ok(reloaded.some((r) => r.method === "pane.report_agent" && r.params.state === "working"), "reload: a non-idle agent is force-published as working");

    const beforeSwitch = herdr.requests.length;
    await pi.fire("session_start", { reason: "resume" }, ctxFor("tui", { idle: true, file: "/tmp/other-session.jsonl" }));
    await herdr.settle(beforeSwitch + 2);
    const switched = herdr.requests.slice(beforeSwitch);
    ok(switched.some((r) => r.method === "pane.report_agent_session" && r.params.agent_session_path === "/tmp/other-session.jsonl" && r.params.session_start_source === "resume"), "resume: the new session path is reported with source resume");
    const idleAfterSwitch = switched.find((r) => r.method === "pane.report_agent");
    ok(idleAfterSwitch?.params.state === "idle" && idleAfterSwitch?.params.agent_session_path === "/tmp/other-session.jsonl", "resume: the forced state report carries the new session path");

    // A relative session file is not a usable path; the integration falls back to the id.
    const beforeRelative = herdr.requests.length;
    await pi.fire("session_start", { reason: "new" }, ctxFor("tui", { idle: true, file: "relative.jsonl" }));
    await herdr.settle(beforeRelative + 2);
    const relative = herdr.requests.slice(beforeRelative).find((r) => r.method === "pane.report_agent_session");
    ok(relative?.params.agent_session_id === "fake-session-id" && relative?.params.agent_session_path === undefined, "new: a relative session file falls back to the session id");
    await herdr.close();
  }
} finally {
  rmSync(root, { recursive: true, force: true });
}

if (failures > 0) {
  console.log(`# ${failures} failure(s)`);
  process.exit(1);
}
console.log("# all herdr-agent-state socket checks passed");

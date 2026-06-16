import assert from "node:assert/strict";
import { buildWatchScript, deriveWindowName, groupWindows, parseTtyArgs, shellQuote, type ParsedTtyArgs } from "./helpers";
import * as mux from "./mux";

function test(name: string, fn: () => void) {
  try {
    fn();
    console.log(`ok - ${name}`);
  } catch (error) {
    console.error(`not ok - ${name}`);
    throw error;
  }
}

test("parseTtyArgs handles empty input", () => {
  assert.deepEqual(parseTtyArgs("   "), { subcommand: "help", rest: "" } satisfies ParsedTtyArgs);
});

test("parseTtyArgs splits only the first token", () => {
  assert.deepEqual(parseTtyArgs("watch bb watch:kaocha --focus foo"), {
    subcommand: "watch",
    rest: "bb watch:kaocha --focus foo",
  });
});

test("parseTtyArgs expands short aliases", () => {
  const cases = [
    ["s", "spawn"],
    ["w", "watch"],
    ["j", "join"],
    ["m", "monitor"],
    ["b", "break"],
    ["k", "kill"],
    ["l", "list"],
    ["t", "tail"],
    ["h", "help"],
    ["?", "help"],
  ] as const;
  for (const [input, expected] of cases) {
    assert.deepEqual(parseTtyArgs(`${input} rest args`), { subcommand: expected, rest: "rest args" } satisfies ParsedTtyArgs);
  }
});

test("deriveWindowName normalizes whitespace and truncates command", () => {
  assert.equal(deriveWindowName("  bb   watch:kaocha  "), "bb watch:kaocha");
  assert.equal(deriveWindowName("1234567890123456789012345"), "12345678901234567890");
  assert.equal(deriveWindowName("   "), "tty");
});

test("shellQuote protects single quotes", () => {
  assert.equal(shellQuote("it's ok"), `'it'"'"'s ok'`);
});

test("buildWatchScript forwards first Ctrl-C and exits on second Ctrl-C", () => {
  const script = buildWatchScript("bb watch:all", "/tmp/example dir");
  assert.match(script, /^node -e /);
  assert.match(script, /spawn\(process\.env\.SHELL/);
  assert.match(script, /child\.kill\([^)]*SIGINT[^)]*\)/);
  assert.match(script, /if \(waiting\) process\.exit\(0\)/);
  assert.match(script, /tty watch exited/);
  assert.match(script, /'bb watch:all'/);
  assert.match(script, /'\/tmp\/example dir'/);
});

function withEnv(env: Record<string, string | undefined>, fn: () => void) {
  const old = new Map(Object.keys(env).map((key) => [key, process.env[key]]));
  for (const [key, value] of Object.entries(env)) value === undefined ? delete process.env[key] : process.env[key] = value;
  try { fn(); } finally { for (const [key, value] of old) value === undefined ? delete process.env[key] : process.env[key] = value; mux.__setMuxTestExec(); }
}

function recordingMux(json: Record<string, unknown> = {}) {
  const calls: Array<{ cmd: string; args: string[] }> = [];
  const fakeFile = ((cmd: string, args: string[]) => {
    calls.push({ cmd, args });
    if (cmd === "where.exe") return "";
    if (cmd === "tmux" && args[0] === "display-message") return "%owner\t@owner\t1";
    if (cmd === "tmux" && args[0] === "break-pane" && args.includes("-P")) return "@broken\n";
    if (cmd === "zellij" && args.includes("list-panes")) return JSON.stringify(json);
    if (cmd === "zellij" && args.includes("new-pane")) return "7\n";
    if (cmd === "wezterm" && args[1] === "list") return JSON.stringify([{ pane_id: 1, tab_id: 1 }, { pane_id: 2, tab_id: 2 }]);
    if (cmd === "wezterm" && args[1] === "spawn") return "2\n";
    return "";
  }) as any;
  const fakeShell = ((command: string) => { calls.push({ cmd: "sh", args: [command] }); return command.includes("cmux new-surface") ? "surface:2" : "ok"; }) as any;
  mux.__setMuxTestExec(fakeFile, fakeShell);
  return calls;
}

test("groupWindows groups panes and identifies active panes", () => {
  const windows = groupWindows([
    { sessionId: "$1", sessionName: "s", windowId: "@2", windowIndex: "1", windowName: "one", paneId: "%3", paneIndex: "0", paneActive: false, command: "sh" },
    { sessionId: "$1", sessionName: "s", windowId: "@2", windowIndex: "1", windowName: "one", paneId: "%4", paneIndex: "1", paneActive: true, command: "vim" },
    { sessionId: "$1", sessionName: "s", windowId: "@1", windowIndex: "0", windowName: "zero", paneId: "%1", paneIndex: "0", paneActive: true, command: "pi" },
  ]);
  assert.equal(windows.length, 2);
  assert.equal(windows[0].windowId, "@1");
  assert.equal(windows[1].paneCount, 2);
  assert.equal(windows[1].activePaneId, "%4");
});

test("mux.getMuxBackend honors PI_TTY_MUX override", () => withEnv({ PI_TTY_MUX: "zellij", ZELLIJ: "1", TMUX: "/tmp/tmux" }, () => {
  recordingMux();
  assert.equal(mux.getMuxBackend(), "zellij");
}));

test("tmux attach and detach command construction", () => withEnv({ PI_TTY_MUX: "tmux", TMUX: "/tmp/tmux" }, () => {
  const calls = recordingMux();
  mux.attachSurface("%2", { focus: "owner", ownerPaneId: "%pi" });
  mux.detachSurface("%2", { ownerWindowIndex: "1", windowName: "bb watch-echo" });
  assert.deepEqual(calls.filter((c) => c.cmd === "tmux").map((c) => c.args), [
    ["join-pane", "-s", "%2", "-t", "%pi", "-v"],
    ["select-pane", "-t", "%pi"],
    ["break-pane", "-s", "%2", "-P", "-F", "#{window_id}", "-n", "bb watch-echo"],
    ["set-option", "-t", "@broken", "-w", "automatic-rename", "off"],
    ["select-window", "-t", "1"],
  ]);
}));

test("cmux surfaces are titled and addressable by numeric id", () => withEnv({ PI_TTY_MUX: "cmux", CMUX_SOCKET_PATH: "/tmp/cmux", CMUX_PANE_REF: "pane-a" }, () => {
  const calls = recordingMux();
  assert.equal(mux.createWindow("bb watch-echo", "bb watch-echo", "/tmp"), "2");
  mux.attachSurface("2", { focus: "target" });
  mux.detachSurface("2");
  const shell = calls.map((c) => c.args[0]).join("\n");
  assert.match(shell, /cmux rename-tab --surface 'surface:2' '2: bb watch-echo'/);
  assert.match(shell, /cmux move-surface --surface 'surface:2' --pane 'pane-a' --focus true/);
  assert.match(shell, /cmux split-off --surface 'surface:2'/);
}));

test("wezterm panes use displayed tab index targets and preserve title on break", () => withEnv({ PI_TTY_MUX: "wezterm", WEZTERM_UNIX_SOCKET: "/tmp/w", WEZTERM_PANE: "1" }, () => {
  const calls = recordingMux();
  assert.equal(mux.createWindow("bb watch-echo", "bb watch-echo", "/tmp"), "pane:2\t2\t2\tbb watch-echo");
  assert.equal(mux.attachSurface("2", { focus: "target" }), "pane:2");
  mux.detachSurface("pane:2", { windowName: "bb watch-echo" });
  assert(calls.some((c) => c.cmd === "wezterm" && c.args.join(" ").includes("spawn --cwd /tmp -- sh -lc bb watch-echo")));
  assert(!calls.some((c) => c.cmd === "wezterm" && c.args.includes("--new-window")));
  assert(calls.some((c) => c.cmd === "wezterm" && c.args.join(" ").includes("set-tab-title --pane-id 2 bb watch-echo")));
  assert(calls.some((c) => c.cmd === "wezterm" && c.args.join(" ").includes("split-pane --pane-id 1 --bottom --top-level --move-pane-id 2")));
  assert(calls.some((c) => c.cmd === "wezterm" && c.args.join(" ").includes("activate-pane --pane-id 2")));
  assert(calls.some((c) => c.cmd === "wezterm" && c.args.join(" ").includes("move-pane-to-new-tab --pane-id 2")));
  assert.throws(() => mux.attachSurface("1", { focus: "owner" }), /same-tab attach/);
}));

test("zellij managed floating panes are embedded/floated idempotently", () => withEnv({ PI_TTY_MUX: "zellij", ZELLIJ: "1", ZELLIJ_PANE_ID: "1", ZELLIJ_TAB_ID: "3" }, () => {
  let floating = true;
  const calls: Array<{ cmd: string; args: string[] }> = [];
  mux.__setMuxTestExec(((cmd: string, args: string[]) => {
    calls.push({ cmd, args });
    if (cmd === "zellij" && args.includes("list-panes")) return JSON.stringify({ panes: [{ id: "terminal_7", is_floating: floating }] });
    if (cmd === "zellij" && args.includes("toggle-pane-embed-or-floating")) floating = !floating;
    if (cmd === "zellij" && args.includes("new-pane")) return "terminal_7\n";
    return "";
  }) as any, ((command: string) => { calls.push({ cmd: "sh", args: [command] }); return ""; }) as any);
  assert.equal(mux.createWindow("name", "echo hi", "/tmp"), "7");
  mux.attachSurface("7", { focus: "owner" });
  mux.attachSurface("terminal_7", { focus: "owner" });
  mux.detachSurface("7");
  mux.detachSurface("terminal_7");
  const joined = calls.map((c) => c.args.join(" ")).join("\n");
  assert.match(joined, /new-pane --floating --name name --cwd \/tmp --tab-id 3 -- sh -lc echo hi/);
  assert.match(joined, /rename-pane --pane-id 7 7: name/);
  assert.equal(calls.filter((c) => c.args.includes("toggle-pane-embed-or-floating")).length, 2);
  assert.match(joined, /focus-pane-id 1/);
}));

test("zellij focus correction skips already-focused pane", () => withEnv({ PI_TTY_MUX: "zellij", ZELLIJ: "1", ZELLIJ_PANE_ID: "1" }, () => {
  const calls: Array<{ cmd: string; args: string[] }> = [];
  mux.__setMuxTestExec(((cmd: string, args: string[]) => {
    calls.push({ cmd, args });
    if (cmd === "zellij" && args.includes("list-panes")) return JSON.stringify({ panes: [{ id: 7, is_floating: false }, { id: 1, is_focused: true, is_floating: false }] });
    if (cmd === "zellij" && args.includes("focus-pane-id")) throw new Error("Pane Terminal(1) is already focused");
    return "";
  }) as any, ((command: string) => { calls.push({ cmd: "sh", args: [command] }); return ""; }) as any);
  mux.detachSurface("7");
  assert.equal(calls.filter((c) => c.args.includes("focus-pane-id")).length, 0);
}));

test("zellij unmanaged panes fail with zellij-specific message", () => withEnv({ PI_TTY_MUX: "zellij", ZELLIJ: "1", ZELLIJ_PANE_ID: "1" }, () => {
  recordingMux({ panes: [] });
  assert.throws(() => mux.attachSurface("99", { focus: "target" }), /zellij pane 99 is not managed/);
}));

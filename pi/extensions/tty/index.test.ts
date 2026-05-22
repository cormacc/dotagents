import assert from "node:assert/strict";
import { buildWatchScript, deriveWindowName, groupWindows, parseTtyArgs, shellQuote, type ParsedTtyArgs } from "./helpers";

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

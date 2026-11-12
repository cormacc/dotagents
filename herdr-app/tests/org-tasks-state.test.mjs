import test from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { project, settle, statusColor, hint, touches, parse, fitHeight, remoteOt, missingRemote } from "../mods/org-tasks/state.js";

const agentPane = { pane: "p1", status: "idle", cwd: "/work/a" };
const shellPane = { pane: "p2", status: null, cwd: "/work/b" };
const local = { id: "s1", machine: null };

test("project follows the focused terminal, with its machine, and refuses everything else", () => {
  assert.deepEqual(project({ space: "s1", pane: "p1", cwd: "/x" }, [agentPane], [local]), { dir: "/work/a" });
  assert.deepEqual(project({ space: "s1", pane: "p2" }, [shellPane], [local]), { dir: "/work/b" }, "a shell pane");
  assert.deepEqual(project({ space: "s1", pane: null, terminal: "t1", cwd: "/work/c" }, [], [local]), { dir: "/work/c" },
                   "a layout terminal block");
  assert.match(project({ space: "s1", pane: null, terminal: null, cwd: "/x" }, [agentPane], [local]).reason, /Focus a terminal/,
               "a mod pane");
  assert.match(project({ space: "s1", pane: "gone" }, [agentPane], [local]).reason, /Focus a terminal/);
  assert.match(project(null, [agentPane], [local]).reason, /Focus a terminal/);
  assert.deepEqual(project({ space: "vps/s9", pane: "p1" }, [agentPane], [{ id: "vps/s9", machine: "vps" }]), { dir: "/work/a", machine: "vps" });
  assert.deepEqual(project({ space: "vps/s9", pane: "p2" }, [shellPane], [{ id: "vps/s9", machine: "vps" }]), { dir: "/work/b", machine: "vps" });
  assert.match(project({ space: "vps/s9", pane: "p1", cwd: null }, [{ ...agentPane, cwd: null }], [{ id: "vps/s9", machine: "vps" }]).reason, /unknown/);
  assert.equal(project({ space: "s1", pane: "p1", cwd: "/fallback" }, [{ ...agentPane, cwd: null }], [local]).dir, "/fallback");
  assert.match(project({ space: "s1", pane: "p1", cwd: null }, [{ ...agentPane, cwd: null }], [local]).reason, /unknown/);
});

test("a project switch waits until the new folder holds", () => {
  const memo = { dir: null, reason: null };
  assert.equal(settle(memo, { dir: "/a" }, 2), true, "the first project switches at once");
  memo.dir = "/a";
  assert.equal(settle(memo, { dir: "/a" }, 2), false);
  assert.equal(settle(memo, { dir: "/tmp/x" }, 2), false, "a tool command's folder for one poll");
  assert.equal(settle(memo, { dir: "/a" }, 2), false, "back again: no switch");
  assert.equal(settle(memo, { dir: "/b" }, 2), false);
  assert.equal(settle(memo, { dir: "/b" }, 2), true, "held for two polls");
  memo.dir = "/b";
  assert.equal(settle(memo, { reason: "Focus a terminal" }, 2), false);
  assert.equal(settle(memo, { reason: "Focus a terminal" }, 2), true);
});

test("focusing another terminal switches at once; a folder change inside the same terminal still holds", () => {
  const memo = { dir: null, reason: null };
  assert.equal(settle(memo, { dir: "/a" }, 2, "p1"), true);
  memo.dir = "/a";
  assert.equal(settle(memo, { dir: "/tmp/x" }, 2, "p1"), false, "control: same terminal, a tool command moved its folder");
  assert.equal(settle(memo, { dir: "/b" }, 2, "p2"), true, "another terminal with a project: no wait");
  memo.dir = "/b";
  assert.equal(settle(memo, { dir: "/b" }, 2, "p3"), false, "another terminal, same project: nothing to switch");
  assert.equal(settle(memo, { dir: "/tmp/y" }, 2, "p3"), false, "the hold follows the terminal that is now focused");
  assert.equal(settle(memo, { reason: "Focus a terminal" }, 2, "m1"), false, "a mod pane gives a reason: still held");
  assert.equal(settle(memo, { reason: "Focus a terminal" }, 2, "m1"), true);
});

test("the same folder on another machine is another project", () => {
  const memo = { dir: "/a", machine: null, reason: null };
  assert.equal(settle(memo, { dir: "/a" }, 1), false, "control: the shown project");
  assert.equal(settle(memo, { dir: "/a", machine: "vps" }, 1), true);
  memo.machine = "vps";
  assert.equal(settle(memo, { dir: "/a", machine: "vps" }, 1), false);
  assert.equal(settle(memo, { dir: "/a" }, 1), true, "back to this computer");
});

test("remoteOt runs the home-installed ot and reports only a missing file as missing", () => {
  const home = mkdtempSync(join(tmpdir(), "org-tasks-remote-"));
  try {
    const run = env => {
      const [cmd, args] = remoteOt(["compact", "--x"]);
      const r = spawnSync(cmd, args, { encoding: "utf8", env: { PATH: process.env.PATH, ...env } });
      return { code: r.status, stdout: r.stdout, stderr: r.stderr };
    };
    const path = join(home, ".agents/skills/org-tasks/scripts/ot");
    const absent = run({ HOME: home });
    assert.equal(absent.code, 127);
    assert.equal(missingRemote(absent), path);
    mkdirSync(join(home, ".agents/skills/org-tasks/scripts"), { recursive: true });
    writeFileSync(path, "#!/bin/sh\nprintf '%s|' \"$@\"\n", { mode: 0o755 });
    const present = run({ HOME: home });
    assert.deepEqual([present.code, present.stdout], [0, "compact|--x|"], "control: arguments pass through");
    assert.equal(missingRemote(present), null);
    writeFileSync(path, "#!/bin/sh\necho 'bb: not found' >&2\nexit 127\n", { mode: 0o755 });
    const failing = run({ HOME: home });
    assert.equal(failing.code, 127, "control: ot itself exits 127");
    assert.equal(missingRemote(failing), null, "a failure inside ot is not a missing ot");
  } finally { rmSync(home, { recursive: true, force: true }); }
});

test("block height covers the rows at zoom 1 with padding", () => {
  assert.equal(fitHeight(60, 6, 1), 72);
  assert.equal(fitHeight(60.2, 6, 1), 73, "rounded up so the last row is never clipped");
  assert.equal(fitHeight(120, 6, 2), 72);
  assert.equal(fitHeight(60, 6, undefined), 72);
});

test("status colours come from the theme", () => {
  const t = { ansi3: "#y", ansi4: "#w", blocked: "#b", ansi2: "#d", dim: "#m", foreground: "#f" };
  assert.deepEqual(["TODO", "STARTED", "WAITING", "DONE", "CANCELLED", "OTHER"].map(s => statusColor(t, s)),
                   ["#y", "#w", "#b", "#d", "#m", "#f"]);
});

test("hint joins only the bound actions, with their keys, on one line", () => {
  const bound = [{ section: "global", keys: "cmd-shift-t", action: "org-tasks.select" },
                 { section: "global", keys: "cmd-shift-o", action: "layout.toggle:org-tasks" },
                 { section: "global", keys: "cmd-t", action: "tab.new" }];
  assert.equal(hint(bound), "cmd-shift-t: select / cmd-shift-o: show/hide");
  assert.equal(hint(bound.slice(1)), "cmd-shift-o: show/hide");
  assert.equal(hint([]), null);
});

test("only changes to watched files reload", () => {
  const files = ["/p/TASKS.org", "/p/design/log/plan.org"];
  assert.equal(touches(["/p/TASKS.org"], files), true);
  assert.equal(touches(["/p/src/main.clj", "/p/design/log/plan.org"], files), true);
  assert.equal(touches(["/p/src/main.clj"], files), false);
  assert.equal(touches([], files), true, "an empty report means the watch cannot say, so reload");
  assert.equal(touches(["/p/TASKS.org"], undefined), false, "before the first model, nothing is watched");
});

test("ot results parse to a model or a reason", () => {
  assert.deepEqual(parse({ code: 0, stdout: JSON.stringify({ ok: true, result: { title: "T" } }), stderr: "" }), { model: { title: "T" } });
  assert.match(parse({ code: 1, stdout: "", stderr: JSON.stringify({ ok: false, error: { message: "boom" } }) }).reason, /ot failed: boom/);
  assert.match(parse({ code: 127, stdout: "", stderr: "spawn ot ENOENT\nmore" }).reason, /ot failed: spawn ot ENOENT$/);
  assert.match(parse({ code: 0, stdout: "not json", stderr: "" }).reason, /unreadable/);
  assert.match(parse({ code: 0, stdout: JSON.stringify({ ok: false, error: { message: "x" } }), stderr: "" }).reason, /ot failed: x/);
});

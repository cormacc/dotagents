import test from "node:test";
import assert from "node:assert/strict";
import { project, settle, statusColor, hints, touches, parse, fitHeight } from "../mods/org-tasks/state.js";

const agentPane = { pane: "p1", status: "idle", cwd: "/work/a" };
const shellPane = { pane: "p2", status: null, cwd: "/work/b" };
const local = { id: "s1", machine: null };

test("project follows the focused local terminal and refuses everything else", () => {
  assert.deepEqual(project({ space: "s1", pane: "p1", cwd: "/x" }, [agentPane], [local]), { dir: "/work/a" });
  assert.deepEqual(project({ space: "s1", pane: "p2" }, [shellPane], [local]), { dir: "/work/b" }, "a shell pane");
  assert.deepEqual(project({ space: "s1", pane: null, terminal: "t1", cwd: "/work/c" }, [], [local]), { dir: "/work/c" },
                   "a layout terminal block");
  assert.match(project({ space: "s1", pane: null, terminal: null, cwd: "/x" }, [agentPane], [local]).reason, /Focus a terminal/,
               "a mod pane");
  assert.match(project({ space: "s1", pane: "gone" }, [agentPane], [local]).reason, /Focus a terminal/);
  assert.match(project(null, [agentPane], [local]).reason, /Focus a terminal/);
  assert.match(project({ space: "s9", pane: "p1" }, [agentPane], [{ id: "s9", machine: "vps" }]).reason, /Remote space \(vps\)/);
  assert.match(project({ space: "s9", pane: "p2" }, [shellPane], [{ id: "s9", machine: "vps" }]).reason, /Remote space \(vps\)/);
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

test("hints name only bound actions with their keys", () => {
  const bound = [{ section: "global", keys: "cmd-shift-t", action: "org-tasks.select" },
                 { section: "global", keys: "cmd-shift-o", action: "layout.toggle:org-tasks" },
                 { section: "global", keys: "cmd-t", action: "tab.new" }];
  assert.deepEqual(hints(bound), ["cmd-shift-t: Select a task from the backlog", "cmd-shift-o: Show/hide org-tasks widget"]);
  assert.deepEqual(hints(bound.slice(1)), ["cmd-shift-o: Show/hide org-tasks widget"]);
  assert.deepEqual(hints([]), []);
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

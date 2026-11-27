import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { request, answer, localId, qualify, EMACS_TAB } from "../mods/emacs-magit/state.js";
import { project as orgTasksProject } from "../mods/org-tasks/state.js";

const mods = fileURLToPath(new URL("../mods/", import.meta.url));
const local = { id: "s1", machine: null };
const remote = { id: "vps/s9", machine: "vps" };
const pane = { pane: "p1", terminal: "t1", cwd: "/work/a" };

test("emacs-plan carries byte-identical copies of the shared files", () => {
  for (const file of ["state.js", "opener.js"]) {
    assert.equal(readFileSync(`${mods}emacs-plan/${file}`, "utf8"), readFileSync(`${mods}emacs-magit/${file}`, "utf8"), file);
  }
});

test("each mod opens its own view", () => {
  assert.match(readFileSync(`${mods}emacs-magit/main.js`, "utf8"), /emacsTabView\("magit"/);
  assert.match(readFileSync(`${mods}emacs-plan/main.js`, "utf8"), /emacsTabView\("plan"/);
});

test("a local terminal runs emacs-tab here, in the pane's folder, for its space", () => {
  const r = request("magit", { space: "s1", pane: "p1" }, [pane], [local]);
  assert.equal(r.command, "sh");
  assert.deepEqual(r.options, { cwd: "/work/a", on: null, timeout: 15000 });
  assert.equal(r.args[1], `exec "${EMACS_TAB}" "$@"`);
  assert.deepEqual(r.args.slice(2), ["emacs-tab", "magit", "--runtime", "hc", "--space", "s1", "--cwd", "/work/a",
                                     "--no-focus", "--format", "json"]);
});

test("a remote terminal runs on its machine with the machine-local space id", () => {
  const r = request("plan", { space: "vps/s9", pane: "p1" }, [pane], [remote]);
  assert.equal(r.options.on, "vps");
  assert.equal(r.machine, "vps");
  assert.equal(r.args[r.args.indexOf("--space") + 1], "s9");
});

test("no terminal focus gives a reason and no command", () => {
  assert.deepEqual(request("magit", null, [], [local]), { reason: "Focus a terminal first" });
  assert.equal(request("magit", { space: "s1", pane: null, terminal: null }, [], [local]).command, undefined);
  assert.match(request("magit", { space: "s1", pane: "p1" }, [{ ...pane, cwd: null }], [local]).reason, /unknown/);
});

test("project matches the org-tasks widget's rule", () => {
  const cases = [
    [{ space: "s1", pane: "p1" }, [pane], [local]],
    [{ space: "s1", pane: null, terminal: "t3", cwd: "/c" }, [], [local]],
    [{ space: "vps/s9", pane: "p1" }, [pane], [remote]],
    [{ space: "s1", pane: "gone" }, [pane], [local]],
  ];
  for (const [focus, panes, spaces] of cases) {
    const mine = request("magit", focus, panes, spaces);
    const theirs = orgTasksProject(focus, panes, spaces);
    assert.equal(mine.options?.cwd ?? null, theirs.dir ?? null);
    assert.equal(mine.options?.on ?? null, theirs.machine ?? null);
  }
});

test("ids and addresses", () => {
  assert.equal(localId("vps/s9"), "s9");
  assert.equal(localId("s1"), "s1");
  assert.equal(qualify("vps", "t7"), "vps/t7");
  assert.equal(qualify(null, "t7"), "t7");
});

test("answer reads emacs-tab's JSON and qualifies a remote terminal", () => {
  const ok = { code: 0, stdout: JSON.stringify({ ok: true, result: { terminal: "t7", created: true } }), stderr: "" };
  assert.deepEqual(answer(ok, null), { terminal: "t7", created: true });
  assert.deepEqual(answer(ok, "vps"), { terminal: "vps/t7", created: true });
  const refused = { code: 2, stdout: JSON.stringify({ ok: false, error: { code: 2, message: "no herdr-app space" } }), stderr: "" };
  assert.deepEqual(answer(refused, null), { error: "no herdr-app space" });
  assert.match(answer({ code: 127, stdout: "", stderr: "sh: emacs-tab: not found" }, "vps").error, /not installed at vps:/);
  assert.match(answer({ code: 1, stdout: "", stderr: "" }, null).error, /exited 1/);
});

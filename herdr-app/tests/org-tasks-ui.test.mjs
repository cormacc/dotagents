// Builder-spy contracts are not native layout or rendering evidence. The model
// comes from the real `ot compact`, so these tests also pin the ot/mod contract.
import test from "node:test";
import assert from "node:assert/strict";
import vm from "node:vm";
import { readFile } from "node:fs/promises";
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { tmpdir, userInfo } from "node:os";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const OT = resolve("skills/org-tasks/scripts/ot");
const PARENT = "11111111-1111-4111-8111-111111111111";
const ids = ["22222222-2222-4222-8222-222222222222", "33333333-3333-4333-8333-333333333333",
             "44444444-4444-4444-8444-444444444444", "55555555-5555-4555-8555-555555555555"];

function heading(level, status, summary, id) {
  return `${"*".repeat(level)} ${status} ${summary}\n:PROPERTIES:\n:CUSTOM_ID: ${id}\n:END:\n`;
}

// A project whose selected task has two STARTED children (parallel work), one
// next TODO and one hidden TODO.
function project(selected) {
  const dir = mkdtempSync(resolve(".tmp/org-tasks-ui-"));
  writeFileSync(join(dir, "TASKS.org"), "* Improvements\n" + heading(2, "STARTED", "Parent", PARENT)
    + heading(3, "STARTED", "Alpha", ids[0]) + heading(3, "STARTED", "Beta", ids[1])
    + heading(3, "TODO", "Gamma", ids[2]) + heading(3, "TODO", "Delta", ids[3]));
  writeFileSync(join(dir, "TASKS.local.org"), `#+SELECTED: ${selected ?? ""}\n`);
  return dir;
}

// Real deps live under the real home; the runner points HOME at a scratch folder
// and puts a quota-fixture `bb` first on PATH, naming the real one ORCH_TEST_BB.
function otCompact(cwd) {
  const bin = process.env.ORCH_TEST_BB ? `${dirname(process.env.ORCH_TEST_BB)}:` : "";
  const r = spawnSync(OT, ["compact", "--format", "json"], { cwd, encoding: "utf8",
                       env: { ...process.env, HOME: userInfo().homedir, PATH: `${bin}${process.env.PATH}` } });
  return { code: r.status, stdout: r.stdout, stderr: r.stderr };
}

async function app({ focus, panes, spaces = [{ id: "s1", machine: null }], bindings = [], zoom = 1, run: runOt = otCompact, readText = async () => "#!/usr/bin/env bash\n" }) {
  const elements = [], timers = [], overlays = [], notes = [], hcCalls = [], execCalls = [], runs = [], watched = [];
  const cx = { notify() {}, spawn: f => { f(cx); }, timer: { every: (ms, cb) => timers.push({ ms, cb }) } };
  function element() {
    const record = { operations: [] };
    const proxy = new Proxy(record, { get(target, name) {
      if (name === "operations") return target.operations;
      return (...args) => {
        target.operations.push([name, ...args]);
        if (name === "when" && args[0]) args[1](proxy);
        return proxy;
      };
    } });
    elements.push(record); return proxy;
  }
  const t = { foreground: "#000000", muted: "#777777", dim: "#999999", ansi3: "#aa8800",
              ansi4: "#0055ff", blocked: "#ff8800", ansi2: "#00aa00" };
  let pendingExec = [];
  const exports = {
    "gpui-kit": { View: class {}, div: element },
    "gpui-base": { h_flex: element, v_flex: element },
    herdr: {
      exec: (cmd, args, opts) => { execCalls.push([cmd, args, opts]); let p; try { p = Promise.resolve(runOt(opts.cwd, cmd, args, opts)); } catch (e) { p = Promise.reject(e); } pendingExec.push(p.catch(() => {})); return p; },
      readTextFile: readText,
      hc: async args => { hcCalls.push(args); return { code: 0, stdout: JSON.stringify({ address: "t42" }), stderr: "" }; },
      home: () => "/home/fixture", focused: () => focus(), panes: () => panes(), spaces: () => spaces,
      theme: () => t, keys: () => bindings, props: () => ({}), settings: () => ({ zoom }),
      run: (name, args) => { runs.push([name, args]); },
      openOverlay: o => { overlays.push(o); return "o1"; }, notify: n => notes.push(n),
      nextCommand: () => new Promise(() => {}), watchFolder: dir => { watched.push(dir); return new Promise(() => {}); },
    },
  };
  const context = vm.createContext({ console, JSON, Promise, Set, String });
  const cache = new Map();
  async function load(specifier, parent) {
    const key = exports[specifier] ? specifier : new URL(specifier, parent).href;
    if (cache.has(key)) return cache.get(key);
    const mod = exports[key]
      ? new vm.SyntheticModule(Object.keys(exports[key]), function () {
          for (const [name, value] of Object.entries(exports[key])) this.setExport(name, value);
        }, { context })
      : new vm.SourceTextModule(await readFile(fileURLToPath(key), "utf8"), { context, identifier: key });
    cache.set(key, mod);
    await mod.link((name, referring) => load(name, referring.identifier));
    return mod;
  }
  const mod = await load(new URL("../mods/org-tasks/main.js", import.meta.url).href);
  await mod.evaluate();
  const view = new mod.namespace.default();
  view.init({}, cx);
  const settle = async () => { await Promise.all(pendingExec); pendingExec = []; await new Promise(r => setImmediate(r)); };
  const render = () => { elements.length = 0; view.render(cx); return elements.flatMap(e => e.operations); };
  const tick = () => timers.find(x => x.ms === 1000).cb(cx);
  const poll = () => timers.find(x => x.ms === 5000).cb(cx);
  const refresh = () => timers.find(x => x.ms === 30000).cb(cx);
  return { view, render, settle, tick, poll, refresh, cx, overlays, notes, hcCalls, execCalls, runs, watched };
}

const words = ops => ops.filter(o => o[0] === "child" && typeof o[1] === "string").map(o => o[1]);
// The colour set immediately before a text child.
const colourOf = (ops, text) => {
  const i = ops.findIndex(o => o[0] === "child" && o[1] === text);
  return ops.slice(0, i).reverse().find(o => o[0] === "text_color")?.[1];
};

test("selected task renders every STARTED child, then the next one", async () => {
  const dir = project(PARENT);
  try {
    const a = await app({ focus: () => ({ space: "s1", pane: "p1", cwd: dir }),
                          panes: () => [{ pane: "p1", status: "idle", cwd: dir }] });
    await a.settle();
    const ops = a.render(), w = words(ops);
    assert.ok(w.some(x => x.startsWith("Tasks :: ")), "the title comes from ot");
    const labels = w.filter(x => / \[[0-9]{8}\]$/.test(x));
    assert.deepEqual(labels, ["Parent [11111111]", "Alpha [22222222]", "Beta [33333333]", "Gamma [44444444]"]);
    assert.ok(w.includes("0/4"), "progress on the first started row");
    assert.ok(w.includes("..."), "hidden pending work");
    assert.equal(colourOf(ops, "Alpha [22222222]"), "#000000", "started rows are current");
    assert.equal(colourOf(ops, "Beta [33333333]"), "#000000", "parallel started rows are current too");
    assert.equal(colourOf(ops, "Parent [11111111]"), "#777777");
    assert.equal(colourOf(ops, "Gamma [44444444]"), "#777777");
    assert.equal(colourOf(ops, "STARTED"), "#0055ff");
    assert.equal(JSON.stringify(a.execCalls.map(c => [c[0], c[1], c[2].cwd])), JSON.stringify([["/home/fixture/.agents/skills/org-tasks/scripts/ot", ["compact", "--format", "json"], dir]]));
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test("a missing ot shows as not installed, with its path; a present one that fails to start does not", async () => {
  const fail = () => { throw Error("cannot start"); };
  for (const [readText, expected] of [
    [async () => null, "ot not installed at /home/fixture/.agents/skills/org-tasks/scripts/ot. See https://github.com/cormacc/dotagents/blob/main/skills/herdr-orch/references/install-surfaces.md"],
    [async () => "#!/usr/bin/env bash\n", "ot failed: cannot start"]]) {
    const a = await app({ focus: () => ({ space: "s1", pane: "p1", cwd: "/work" }),
                          panes: () => [{ pane: "p1", status: "idle", cwd: "/work" }], run: fail, readText });
    await a.settle(); await new Promise(r => setImmediate(r));
    assert.ok(words(a.render()).includes(expected), JSON.stringify(words(a.render())));
  }
});

// The operations of the element that holds `text`, from its preceding `div`
// call: the spy records each element's builder calls in order.
const ownOps = (ops, text) => {
  const i = ops.findIndex(o => o[0] === "child" && o[1] === text);
  const start = ops.slice(0, i).findLastIndex(o => o[0] === "id" || o[0] === "child" && typeof o[1] !== "string");
  return ops.slice(start + 1, i + 1).map(o => o[0]);
};

for (const [name, selected, content] of [["above the selected-task rows", PARENT, "Gamma [44444444]"],
                                         ["above the status counts without a selection", null, "5 tasks:"]]) {
  test(`one muted key hint sits right of the title, ${name}`, async () => {
    const dir = project(selected);
    const hint = "cmd-shift-t: select / cmd-shift-o: show/hide";
    try {
      const a = await app({ focus: () => ({ space: "s1", pane: "p1", cwd: dir }),
                            panes: () => [{ pane: "p1", status: "idle", cwd: dir }],
                            bindings: [{ section: "global", keys: "cmd-shift-t", action: "org-tasks.select" },
                                       { section: "global", keys: "cmd-shift-o", action: "layout.toggle:org-tasks" }] });
      await a.settle();
      const ops = a.render(), w = words(ops);
      assert.ok(w.includes(content), `control: the content shows, got ${JSON.stringify(w)}`);
      assert.equal(w.filter(x => x.includes(": select") || x.includes(": show/hide")).length, 1, "one hint line");
      const title = w.findIndex(x => x.startsWith("Tasks :: "));
      assert.ok(title >= 0, `control: the title shows, got ${JSON.stringify(w)}`);
      assert.equal(w[title + 1], hint, "the hint follows the title");
      assert.ok(w.indexOf(content) > title + 1, "the content follows the title row");
      // The title and the hint are the two element children of one row.
      const row = ops.findIndex(o => o[0] === "id" && o[1] === "org-tasks-title");
      assert.ok(row >= 0, "control: the title row exists");
      const end = ops.findIndex((o, i) => i > row && o[0] === "id");
      assert.equal(ops.slice(row, end).filter(o => o[0] === "child" && typeof o[1] !== "string").length, 2, "title and hint share the row");
      assert.equal(colourOf(ops, hint), "#777777");
      assert.ok(ownOps(ops, hint).includes("flex_none"), JSON.stringify(ownOps(ops, hint)));
      assert.ok(!ownOps(ops, hint).includes("text_center"), JSON.stringify(ownOps(ops, hint)));
    } finally { rmSync(dir, { recursive: true, force: true }); }
  });
}

test("no key-hint line shows when neither action is bound", async () => {
  const dir = project(PARENT);
  try {
    const a = await app({ focus: () => ({ space: "s1", pane: "p1", cwd: dir }),
                          panes: () => [{ pane: "p1", status: "idle", cwd: dir }] });
    await a.settle();
    const w = words(a.render());
    assert.ok(w.includes("Gamma [44444444]"), "control: the rows show");
    assert.ok(!w.some(x => x.includes(": select") || x.includes(": show/hide")), JSON.stringify(w));
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test("a shell pane shows its folder's tasks; a mod pane shows none", async () => {
  const dir = project(PARENT);
  try {
    let focus = { space: "s1", pane: "p1", cwd: dir };
    const panes = [{ pane: "p1", status: "idle", cwd: dir }, { pane: "p2", status: null, cwd: dir }];
    const a = await app({ focus: () => focus, panes: () => panes, spaces: [{ id: "s1", machine: null }, { id: "s2", machine: "vps" }] });
    await a.settle();
    assert.ok(words(a.render()).includes("Alpha [22222222]"), "control: the agent shows tasks");
    focus = { space: "s1", pane: "p2", cwd: dir };
    a.tick(); a.tick(); await a.settle();
    let w = words(a.render());
    assert.ok(w.includes("Alpha [22222222]"), "the shell pane shows its folder's tasks");
    focus = { space: "s1", pane: null, terminal: null, cwd: dir };
    a.tick(); a.tick();
    w = words(a.render());
    assert.ok(!w.includes("Alpha [22222222]"));
    assert.ok(w.some(x => /Focus a terminal/.test(x)));
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

// The fixture runs the real ot locally on the folder; the call records where it would run.
const remoteSpaces = [{ id: "s1", machine: null }, { id: "vps/s2", machine: "vps" }];

test("a remote space runs its own ot on that machine and polls instead of watching", async () => {
  const dir = project(PARENT);
  try {
    let focus = { space: "s1", pane: "p1", cwd: dir };
    const panes = [{ pane: "p1", status: "idle", cwd: dir }, { pane: "vps/p2", status: "idle", cwd: dir }];
    const a = await app({ focus: () => focus, panes: () => panes, spaces: remoteSpaces });
    await a.settle();
    assert.deepEqual(a.watched, [dir], "control: a local project is watched");
    a.poll(); await a.settle();
    assert.equal(a.execCalls.length, 1, "control: the remote poll ignores a local project");
    focus = { space: "vps/s2", pane: "vps/p2", cwd: dir };
    a.tick(); a.tick(); await a.settle();
    assert.ok(words(a.render()).includes("Alpha [22222222]"), "the remote project's tasks show");
    const [cmd, args, opts] = a.execCalls.at(-1);
    assert.equal(cmd, "sh");
    assert.equal(JSON.stringify(args.slice(2)), JSON.stringify(["ot", "compact", "--format", "json"]));
    assert.match(args[1], /\$HOME\/\.agents\/skills\/org-tasks\/scripts\/ot/, "that machine's home-installed ot");
    assert.equal(JSON.stringify(opts), JSON.stringify({ cwd: dir, on: "vps" }));
    assert.deepEqual(a.watched, [dir], "no watch on this computer for a remote root");
    const runs = a.execCalls.length;
    a.refresh(); await a.settle();
    assert.equal(a.execCalls.length, runs, "the 30 s local fallback skips a remote project");
    a.poll(); await a.settle();
    assert.equal(a.execCalls.length, runs + 1, "the 5 s poll reloads a remote project");
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test("the remote poll skips while a run is in flight", async () => {
  const resolvers = [];
  const a = await app({ focus: () => ({ space: "vps/s2", pane: "vps/p1", cwd: "/fixture" }),
                        panes: () => [{ pane: "vps/p1", status: "idle", cwd: "/fixture" }], spaces: remoteSpaces,
                        run: () => new Promise(r => resolvers.push(r)) });
  assert.equal(resolvers.length, 1, "control: the first run is in flight");
  a.poll(); a.poll();
  assert.equal(resolvers.length, 1, "no second run while the first is in flight");
  resolvers[0]({ code: 0, stdout: JSON.stringify({ ok: true, result: { title: "t", files: [] } }), stderr: "" });
  await new Promise(r => setImmediate(r));
  a.poll();
  assert.equal(resolvers.length, 2, "the next poll runs once the first has ended");
});

test("a remote ot reported missing shows as not installed on that machine; another failure does not", async () => {
  for (const [result, expected] of [
    [{ code: 127, stdout: "", stderr: "org-tasks: ot not installed at /home/u/.agents/skills/org-tasks/scripts/ot\n" },
     `ot not installed at vps:/home/u/.agents/skills/org-tasks/scripts/ot. See https://github.com/cormacc/dotagents/blob/main/skills/herdr-orch/references/install-surfaces.md`],
    [{ code: 127, stdout: "", stderr: "/home/u/.agents/skills/org-tasks/scripts/ot: line 3: exec: bb: not found\n" },
     "ot failed: /home/u/.agents/skills/org-tasks/scripts/ot: line 3: exec: bb: not found"]]) {
    const a = await app({ focus: () => ({ space: "vps/s2", pane: "vps/p1", cwd: "/work" }),
                          panes: () => [{ pane: "vps/p1", status: "idle", cwd: "/work" }], spaces: remoteSpaces,
                          run: () => result });
    await a.settle();
    assert.ok(words(a.render()).includes(expected), JSON.stringify(words(a.render())));
  }
});

test("select on a remote space opens that machine's ot tui", async () => {
  const a = await app({ focus: () => ({ space: "vps/s2", pane: "vps/p1", cwd: "/work" }),
                        panes: () => [{ pane: "vps/p1", status: "idle", cwd: "/work" }], spaces: remoteSpaces,
                        run: () => new Promise(() => {}) });
  await a.view.select();
  const call = a.hcCalls[0];
  assert.equal(JSON.stringify(call.slice(0, 9)), JSON.stringify(["run", "--json", "--bg", "--no-parent", "--rm", "--on", "vps", "--cwd", "/work"]));
  assert.equal(JSON.stringify([...call.slice(9, 12), ...call.slice(13)]), JSON.stringify(["--", "sh", "-c", "ot", "tui"]));
  assert.match(call[12], /\$HOME\/\.agents\/skills\/org-tasks\/scripts\/ot/);
  assert.equal(a.overlays.length, 1);
});

test("a project without task memory and a failing ot give explicit lines", async () => {
  // Outside the repository, so ot finds no ancestor TASKS.org.
  const empty = mkdtempSync(join(tmpdir(), "org-tasks-empty-"));
  try {
    const a = await app({ focus: () => ({ space: "s1", pane: "p1", cwd: empty }),
                          panes: () => [{ pane: "p1", status: "idle", cwd: empty }] });
    await a.settle();
    const w = words(a.render());
    assert.ok(w.some(x => x.startsWith("No TASKS.org in ")), `explicit state, got ${JSON.stringify(w)}`);
    assert.ok(!w.some(x => /tasks:?$/.test(x)), "no count line");
  } finally { rmSync(empty, { recursive: true, force: true }); }
});

test("select opens ot tui in a dialog around a detached terminal", async () => {
  const dir = project(PARENT);
  try {
    const a = await app({ focus: () => ({ space: "s1", pane: "p1", cwd: dir }),
                          panes: () => [{ pane: "p1", status: "idle", cwd: dir }] });
    await a.settle();
    await a.view.select();
    assert.equal(JSON.stringify(a.hcCalls), JSON.stringify([["run", "--json", "--bg", "--no-parent", "--rm", "--cwd", dir, "--", "/home/fixture/.agents/skills/org-tasks/scripts/ot", "tui"]]));
    assert.equal(a.overlays.length, 1);
    assert.equal(a.overlays[0].kind, "dialog");
    assert.equal(JSON.stringify(a.overlays[0].layout), JSON.stringify({ type: "block", content: { terminal: "t42" } }));
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test("the block fits the rows column and resizes only when it changes", async () => {
  const a = await app({ focus: () => null, panes: () => [], zoom: 2 });
  const ops = a.render();
  const handler = ops.slice(ops.findIndex(o => o[0] === "id" && o[1] === "org-tasks-rows")).find(o => o[0] === "on_size_change")?.[1];
  assert.ok(handler, "the rows column reports its size");
  handler({ width: 500, height: 81 });
  handler({ width: 600, height: 81 });
  handler({ width: 500, height: 41 });
  assert.equal(JSON.stringify(a.runs), JSON.stringify([["layout.resize", { id: "org-tasks", px: 53 }],
                                                       ["layout.resize", { id: "org-tasks", px: 33 }]]),
               "81 logical px at zoom 2 is 41 px at zoom 1, plus 6 px padding above and below");
});

test("select without a project explains instead of opening", async () => {
  const a = await app({ focus: () => ({ space: "s1", pane: "p2" }), panes: () => [{ pane: "p2", status: null }] });
  await a.view.select();
  assert.equal(a.overlays.length, 0);
  assert.equal(a.hcCalls.length, 0);
  assert.match(a.notes[0].body, /folder unknown/);
});

test("a pane in a subdirectory watches the project root that ot resolved", async () => {
  const dir = project(PARENT), sub = join(dir, "src", "deep");
  mkdirSync(sub, { recursive: true });
  try {
    const a = await app({ focus: () => ({ space: "s1", pane: "p1", cwd: sub }),
                          panes: () => [{ pane: "p1", status: "idle", cwd: sub }] });
    await a.settle();
    assert.ok(words(a.render()).includes("Alpha [22222222]"), "control: the subdirectory pane shows the project's tasks");
    assert.deepEqual(a.watched, [dir]);
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test("a slower earlier run never replaces a later result", async () => {
  const resolvers = [];
  const a = await app({ focus: () => ({ space: "s1", pane: "p1", cwd: "/fixture" }),
                        panes: () => [{ pane: "p1", status: "idle", cwd: "/fixture" }],
                        run: () => new Promise(r => resolvers.push(r)) });
  a.view.load(a.cx);
  const result = title => ({ code: 0, stdout: JSON.stringify({ ok: true, result: { title, files: [] } }), stderr: "" });
  assert.equal(resolvers.length, 2, "the initial run and the reload are both in flight");
  resolvers[1](result("new"));
  await new Promise(r => setImmediate(r));
  assert.equal(a.view.model.title, "new", "control: the later run installs");
  resolvers[0](result("old"));
  await new Promise(r => setImmediate(r));
  assert.equal(a.view.model.title, "new");
});

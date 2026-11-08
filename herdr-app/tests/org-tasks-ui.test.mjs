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

async function app({ focus, panes, spaces = [{ id: "s1", machine: null }], bindings = [], zoom = 1, run: runOt = otCompact }) {
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
      exec: (cmd, args, opts) => { execCalls.push([cmd, args, opts]); const p = Promise.resolve(runOt(opts.cwd)); pendingExec.push(p); return p; },
      hc: async args => { hcCalls.push(args); return { code: 0, stdout: JSON.stringify({ address: "t42" }), stderr: "" }; },
      focused: () => focus(), panes: () => panes(), spaces: () => spaces,
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
  return { view, render, settle, tick, cx, overlays, notes, hcCalls, execCalls, runs, watched };
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
    assert.equal(JSON.stringify(a.execCalls.map(c => [c[0], c[1], c[2].cwd])), JSON.stringify([["ot", ["compact", "--format", "json"], dir]]));
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test("without a selection it shows counts and the bound key hints", async () => {
  const dir = project(null);
  try {
    const a = await app({ focus: () => ({ space: "s1", pane: "p1", cwd: dir }),
                          panes: () => [{ pane: "p1", status: "idle", cwd: dir }],
                          bindings: [{ section: "global", keys: "cmd-shift-t", action: "org-tasks.select" }] });
    await a.settle();
    const w = words(a.render());
    assert.ok(w.includes("5 tasks:"));
    assert.ok(w.includes("cmd-shift-t: Select a task from the backlog"));
  } finally { rmSync(dir, { recursive: true, force: true }); }
});

test("a shell pane or remote space never shows the previous project's tasks", async () => {
  const dir = project(PARENT);
  try {
    let focus = { space: "s1", pane: "p1", cwd: dir };
    const panes = [{ pane: "p1", status: "idle", cwd: dir }, { pane: "p2", status: null, cwd: dir }];
    const a = await app({ focus: () => focus, panes: () => panes, spaces: [{ id: "s1", machine: null }, { id: "s2", machine: "vps" }] });
    await a.settle();
    assert.ok(words(a.render()).includes("Alpha [22222222]"), "control: the agent shows tasks");
    focus = { space: "s1", pane: "p2", cwd: dir };
    a.tick(); a.tick();
    let w = words(a.render());
    assert.ok(!w.includes("Alpha [22222222]"));
    assert.ok(w.some(x => /agent pane/.test(x)));
    focus = { space: "s2", pane: "p1", cwd: dir };
    a.tick(); a.tick();
    w = words(a.render());
    assert.ok(!w.includes("Alpha [22222222]"));
    assert.ok(w.some(x => /Remote space \(vps\)/.test(x)));
  } finally { rmSync(dir, { recursive: true, force: true }); }
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
    assert.equal(JSON.stringify(a.hcCalls), JSON.stringify([["run", "--json", "--bg", "--no-parent", "--rm", "--cwd", dir, "--", "ot", "tui"]]));
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
  assert.match(a.notes[0].body, /agent pane/);
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

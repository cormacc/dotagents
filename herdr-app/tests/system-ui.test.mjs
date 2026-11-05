import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

// Run the actual view with native elements and host APIs replaced by recording fakes.
const source = await readFile(new URL("../mods/system/main.js", import.meta.url), "utf8");
const loadUrl = new URL("../mods/system/load.js", import.meta.url).href;
const colorsUrl = new URL("../mods/system/colors.js", import.meta.url).href;
const code = source
  .replace('import { View, div } from "gpui-kit";', 'const { View, div } = globalThis.systemTest;')
  .replace('import { h_flex } from "gpui-base";', 'const { h_flex } = globalThis.systemTest;')
  .replace('import { exec, theme, settings, spaces, run, window } from "herdr";', 'const { exec, theme, settings, spaces, run, window } = globalThis.systemTest;')
  .replace('"./load.js"', JSON.stringify(loadUrl))
  .replace('"./colors.js"', JSON.stringify(colorsUrl));

function element() {
  const data = {};
  const el = new Proxy({}, { get(_target, key) {
    if (key === "data") return data;
    if (key === "when") return (condition, fn) => condition ? fn(el) : el;
    return (...args) => {
      if (key === "child") (data.children ??= []).push(args[0]);
      else if (key === "children") (data.children ??= []).push(...args[0]);
      else data[key] = args[0];
      return el;
    };
  } });
  return el;
}

let platform = "macos", calls = [], activeMachine = null, execute;
globalThis.systemTest = {
  View: class {}, div: element, h_flex: element,
  exec: async (...args) => { calls.push(args); return execute ? execute(...args) : {}; },
  theme: () => ({ chrome: "#000000", border: "#ffffff", foreground: "#ffffff", muted: "#ffffff", dim: "#ffffff", accent: "#ffffff" }),
  settings: () => ({}), spaces: () => [{ active: true, machine: activeMachine }], run: () => {}, window: () => platform === null ? null : ({ platform }),
};
const { default: System } = await import(`data:text/javascript;base64,${Buffer.from(code).toString("base64")}`);

function nodes(el) {
  return [el.data, ...(el.data.children ?? []).flatMap(child => typeof child === "object" ? nodes(child) : [])];
}

function byId(view, id) {
  return nodes(view.render({})).find(node => node.id === id);
}

function deferred() {
  let resolve;
  const promise = new Promise(r => { resolve = r; });
  return { promise, resolve };
}

function lifecycle(machine) {
  activeMachine = machine; calls = [];
  const requests = [], timers = new Map(), tasks = [];
  execute = () => { const request = deferred(); requests.push(request); return request.promise; };
  const cx = {
    focus_handle: () => ({ is_focused: () => false }),
    timer: { every: (ms, fn) => timers.set(ms, fn) },
    spawn: fn => { tasks.push(fn(cx)); },
    notify() {},
  };
  const view = new System();
  view.init({}, cx);
  return { view, requests, tick: ms => timers.get(ms)(cx), settle: async () => { await tasks.shift(); } };
}

const failure = { code: 1, stdout: "", stderr: "remote offline" };
const success = (cpu, available) => ({ code: 0, stdout: `linux\nuptime 0 1\ncg 0 ${cpu * 10000}\ncpumax 100000 100000\nMemTotal: 1000\nMemAvailable: ${available}\n` });

test("remote view retains stale data, expires it, and recovers", async () => {
  const realNow = Date.now;
  let now = 100000;
  Date.now = () => now;
  try {
    const h = lifecycle("alpha"), { view, requests } = h;
    requests[0].resolve(failure); await h.settle();
    assert.equal(calls[0][2].on, "alpha");
    assert.equal(view.render({}).data.accessibility_label, "System load on alpha: unavailable.");
    assert.equal(byId(view, "sys-error").children[0], "System load unavailable");
    assert.match(byId(view, "sys-error").tooltip, /alpha · remote offline/);

    h.tick(3000); requests[1].resolve(success(30, 600)); await h.settle();
    assert.match(view.render({}).data.accessibility_label, /alpha: CPU 30%, RAM 40%/);
    now += 12000;
    h.tick(3000); requests[2].resolve(failure); await h.settle();
    assert.match(view.render({}).data.accessibility_label, /alpha: CPU 30%, RAM 40%/);
    assert.equal(byId(view, "sys-cpu").opacity, 0.45);
    assert.match(byId(view, "sys-cpu").tooltip, /Not updated for 12 s \(remote offline\) · alpha · CPU 30%/);
    now += 49000;
    h.tick(3000); requests[3].resolve(failure); await h.settle();
    assert.equal(view.render({}).data.accessibility_label, "System load on alpha: unavailable.");
    assert.equal(byId(view, "sys-error").children[0], "System load unavailable");
    assert.equal(byId(view, "sys-cpu"), undefined);
    h.tick(3000); requests[4].resolve(success(70, 200)); await h.settle();
    assert.match(view.render({}).data.accessibility_label, /alpha: CPU 70%, RAM 80%/);
    assert.equal(byId(view, "sys-cpu").opacity, undefined);
    assert.doesNotMatch(byId(view, "sys-cpu").tooltip, /Not updated/);
    assert.ok(calls.every(call => call[2].on === "alpha"));
  } finally { Date.now = realNow; execute = undefined; activeMachine = null; }
});

test("switching machines discards late readings and graph history", async () => {
  try {
    const h = lifecycle("alpha"), { view, requests } = h;
    requests[0].resolve(success(90, 600)); await h.settle();
    h.tick(3000);
    activeMachine = "beta"; h.tick(500);
    assert.equal(view.render({}).data.accessibility_label, "System load on beta: unavailable.");
    requests[1].resolve(success(80, 600)); await h.settle();
    assert.equal(view.render({}).data.accessibility_label, "System load on beta: unavailable.");
    assert.equal(byId(view, "sys-machine").children[0], "beta");
    assert.equal(calls[2][2].on, "beta");
    requests[2].resolve(success(20, 200)); await h.settle();
    assert.match(view.render({}).data.accessibility_label, /beta: CPU 20%, RAM 80%/);
    assert.match(byId(view, "sys-cpu").tooltip, /beta · CPU 20%/);
    const graph = byId(view, "sys-cpu").children[1].data;
    assert.deepEqual(graph.children.map(col => col.data.h), [1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 2]);
  } finally { execute = undefined; activeMachine = null; }
});

for (const [os, machine, allowed] of [["macos", null, true], ["linux", null, false], ["windows", null, false], ["macos", "remote", false], [null, null, false]]) {
  test(`Activity Monitor action: ${os}, ${machine ?? "local"}`, () => {
    platform = os; calls = [];
    const view = new System();
    view.machine = machine; view.reset(); view.handle = { is_focused: () => false };
    const root = view.render({}).data;
    assert.equal(root.role === "button", allowed);
    assert.equal(typeof root.on_click === "function", allowed);
    assert.equal(root.accessibility_label.includes("Enter opens Activity Monitor."), allowed);
    if (root.on_click) root.on_click();
    root.on_key_down({ keystroke: "enter" }, { stop_propagation() {} });
    assert.equal(calls.length, allowed ? 2 : 0);
    if (allowed) assert.deepEqual(calls[0], ["open", ["-a", "Activity Monitor"]]);
  });
}

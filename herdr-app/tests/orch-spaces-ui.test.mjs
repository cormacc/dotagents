// Builder-spy contracts for the orch-spaces mod: they run the real main.js against
// stubbed gpui-kit and herdr. They are not native layout or rendering evidence.
import test from "node:test";
import assert from "node:assert/strict";
import vm from "node:vm";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const space = (id, name, tab) => ({ id, name, machine: null, active: id === "s1", tabs: [{ id: tab }], status: "idle" });
const term = (address, extra = {}) => ({ address, state: "live", parent: null, labels: {}, status: "idle", ...extra });
const agent = (address, sp, extra = {}) => ({ address, space: sp, pane: `p-${address}`, status: "idle", focused: false, ...extra });

async function app({ agents, terminals, sidebar = 268 }) {
  const elements = [], runs = [], orders = [];
  const cx = { notify() {}, spawn() {}, stop_propagation() {}, timer: { every() {} } };
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
  const t = new Proxy({}, { get: (_t, name) => `#${String(name)}` });
  const store = new Map();
  const exports = {
    "gpui-kit": { View: class {}, div: element, svg: element, image: element },
    "gpui-base": { h_flex: element, v_flex: element },
    herdr: {
      spaces: () => [space("s1", "alpha", "tb1"), space("s2", "beta", "tb2")],
      machines: () => [], panes: () => [], agents: () => agents, terminals: () => terminals,
      focused: () => ({ agent: null }), setAgentOrder: (list) => { orders.push(list); return null; },
      run: (name, args) => { runs.push([name, args]); }, openOverlay() {}, exec: async () => ({ code: 128, stdout: "", stderr: "not a git repository" }),
      hc: async () => ({ code: 1, stdout: "", stderr: "" }), theme: () => t, shared: () => null, setShared() {},
      watchFolder: () => new Promise(() => {}), window: () => ({ active: false, platform: "macos" }),
      layout: () => ({ id: "root", children: [{ id: "sidebar", size: { px: sidebar } }] }),
    },
  };
  const context = vm.createContext({ console, JSON, Promise, Set, Map, String, Date, Object, Math,
    localStorage: { getItem: (k) => store.get(k) ?? null, setItem: (k, v) => store.set(k, v) } });
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
  const mod = await load(new URL("../mods/orch-spaces/main.js", import.meta.url).href);
  await mod.evaluate();
  const view = new mod.namespace.default();
  view.init({}, cx);
  const render = () => { elements.length = 0; view.render(cx); return elements.map((e) => e.operations); };
  const byId = (els, id) => els.find((ops) => ops.some((o) => o[0] === "id" && o[1] === id));
  const click = (els, id) => byId(els, id).find((o) => o[0] === "on_click")[1](null, cx);
  return { render, byId, click, runs, orders };
}

// Values made inside the VM context are not deepStrictEqual to this realm's.
const plain = (x) => JSON.parse(JSON.stringify(x));
const words = (els) => els.flat().filter((o) => o[0] === "child" && typeof o[1] === "string").map((o) => o[1]);

const world = {
  agents: [agent("t1", "s1"), agent("t2", "s1"), agent("t5", "s2", { status: "working" })],
  terminals: [term("t1", { program: { name: "Pi" } }),
              term("t2", { parent: "t1", labels: { "oh.label": "reviewer-9-astra" } }),
              term("t3", { parent: "t1", labels: { "oh.label": "planner-1-heavy" } }),
              term("t4", { parent: "t3", labels: { "oh.label": "planner-1/scout-2-fable" } }),
              term("t5", { program: { name: "Pi" } })],
};

test("each space is followed by its agent tree, named by the oh label", async () => {
  const a = await app(world);
  const w = words(a.render());
  assert.deepEqual(w.filter((x) => ["alpha", "beta", "Pi", "reviewer-9-astra", "planner-1-heavy", "planner-1/scout-2-fable"].includes(x)),
    ["alpha", "Pi", "reviewer-9-astra", "planner-1-heavy", "planner-1/scout-2-fable", "beta", "Pi"]);
  assert.deepEqual(plain(a.orders.at(-1)), ["t1", "t2", "t3", "t4", "t5"], "agent.next follows the tree");
  assert.equal(w.filter((x) => x === "reviewer-9-astra").length, 1, "a sub-agent with its own pane is listed once");
});

test("an agent with a pane is focused; a sub-agent without one is peeked", async () => {
  const a = await app(world);
  const els = a.render();
  a.click(els, "agent-t2");
  a.click(els, "agent-t3");
  assert.deepEqual(plain(a.runs), [["pane.focus", { pane: "p-t2" }], ["terminal.peek", { terminal: "t3" }]]);
});

test("the rail shows spaces only, with the control that widens it", async () => {
  const a = await app({ ...world, sidebar: 76 });
  const els = a.render();
  assert.ok(!words(els).includes("reviewer-9-astra"));
  assert.equal(a.orders.length, 0, "no agent order from a list that shows none");
  a.click(els, "collapse");
  assert.equal(a.runs.at(-1)[0], "layout.resize");
});

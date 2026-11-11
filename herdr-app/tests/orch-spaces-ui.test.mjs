// Builder-spy contracts for the orch-spaces mod: they run the real main.js against
// stubbed gpui-kit and herdr. They are not native layout or rendering evidence.
import test from "node:test";
import assert from "node:assert/strict";
import vm from "node:vm";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";

const space = (id, name, tabs) => ({ id, name, machine: null, active: id === "s1", tabs, status: "idle" });
const term = (address, extra = {}) => ({ address, state: "live", parent: null, labels: {}, status: "idle", ...extra });
const agent = (address, sp, extra = {}) => ({ address, space: sp, pane: `p-${address}`, status: "idle", focused: false, ...extra });

async function app({ agents, terminals, sidebar = 268, spaceList, paneList = [], gitExec, ohExec }) {
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
      spaces: () => spaceList ?? [space("s1", "alpha", [{ id: "tb1", name: "1" }, { id: "tb3", name: "notes" }]), space("s2", "beta", [{ id: "tb2", name: "1" }])],
      machines: () => [], panes: () => paneList, agents: () => agents, terminals: () => terminals,
      focused: () => ({ agent: null }), setAgentOrder: (list) => { orders.push(list); return null; },
      run: (name, args) => { runs.push([name, args]); }, openOverlay() {},
      home: () => "/home/test",
      exec: (cmd, args, options) => cmd === "git"
        ? (gitExec ?? (async () => ({ code: 128, stdout: "", stderr: "not a git repository" })))(cmd, args, options)
        : (ohExec ?? (async () => ({ code: 1, stdout: "", stderr: "unavailable" })))(cmd, args, options),
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
  return { render, byId, click, runs, orders, view, cx };
}

// Values made inside the VM context are not deepStrictEqual to this realm's.
const plain = (x) => JSON.parse(JSON.stringify(x));
const words = (els) => els.flat().filter((o) => o[0] === "child" && typeof o[1] === "string").map((o) => o[1]);

const world = {
  agents: [agent("t1", "s1", { tab: "tb1" }), agent("t2", "s1", { tab: "tb1" }), agent("t5", "s2", { tab: "tb2", status: "working" })],
  terminals: [term("t1", { program: { name: "Pi" } }),
              term("t2", { parent: "t1", labels: { "oh.label": "reviewer-9-astra" } }),
              term("t3", { parent: "t1", labels: { "oh.label": "planner-1-heavy" } }),
              term("t4", { parent: "t3", labels: { "oh.label": "planner-1/scout-2-fable" } }),
              term("t5", { program: { name: "Pi" } })],
};

test("check identifies plain and submodule checkouts and orders their worktrees", async () => {
  for (const [top, common] of [["/plain", "/plain/.git"], ["/super/sub", "/super/.git/modules/sub"]]) {
    const worktree = `${top}-worktree`;
    const dirs = { [top]: common, [worktree]: `${common}/worktrees/branch` };
    const spaceList = [
      { ...space("worktree", "worktree", []), folder: worktree },
      { ...space("other", "other", []), folder: "/other" },
      { ...space("main", "main", []), folder: top },
    ];
    const gitExec = async (_cmd, args, { cwd }) => {
      const output = args.includes("worktree") ? `worktree ${top}\0branch refs/heads/main\0\0`
        : args.includes("symbolic-ref") ? "refs/heads/feature/x"
        : args.includes("rev-list") ? "2\t1"
        : args.includes("--git-common-dir") ? common
        : args.includes("--absolute-git-dir") ? dirs[cwd]
        : null;
      assert.notEqual(output, null, `unexpected git command: ${args}`);
      return { code: 0, stdout: `${output}\n`, stderr: "" };
    };
    const a = await app({ agents: [], terminals: [], spaceList, gitExec });
    const mainKey = JSON.stringify([null, top]);
    const worktreeKey = JSON.stringify([null, worktree]);
    await a.view.check(mainKey, a.cx);
    await a.view.check(worktreeKey, a.cx);
    assert.equal(a.view.git[mainKey].linked, false, `${top} main checkout`);
    assert.equal(a.view.git[worktreeKey].linked, true, `${top} linked checkout`);
    assert.deepEqual(plain([a.view.git[mainKey].branch, a.view.git[mainKey].ahead, a.view.git[mainKey].behind]), ["feature/x", 2, 1]);
    assert.deepEqual(plain(a.view.ordered().map(({ w, tree }) => [w.id, tree ?? null])),
      [["other", null], ["main", null], ["worktree", "└─"]]);
    // The app's order follows the shown order; each index counts the moves before it.
    assert.deepEqual(plain(a.runs), [["space.move", { id: "other", index: 1 }], ["space.move", { id: "main", index: 2 }]]);
    a.view.ordered();
    assert.equal(a.runs.length, 2, "the same pending order is not written again before it is due");
  }
});

const worktreeEntry = { "pane-id": "t2", display: "⑂ +3 ~2", reconciliation: { branch: "orch/example" } };
const listing = (entries) => ({ code: 0, stdout: JSON.stringify({ schema: "herdr-orch/v1", ok: true, result: { entries, unledgered: [] } }) });
const worktreeGit = async (_cmd, args) => ({ code: 0, stdout: args.includes("worktree")
  ? "worktree /main checkout\0branch refs/heads/main\0\0worktree /linked\0"
  : args.includes("symbolic-ref") ? "refs/heads/main\n" : args.includes("rev-list") ? "0\t0\n" : "/super/.git/modules/sub\n" });
const rowChildren = (row) => row.filter(([op]) => op === "child").map(([, child]) => child);
const childText = (child) => child.operations?.find(([op, value]) => op === "child" && typeof value === "string")?.[1];

test("managed agent rows show the listing display before status and add the branch only to the tooltip", async () => {
  const calls = [];
  const a = await app({ ...world, gitExec: worktreeGit, ohExec: async (...args) => { calls.push(args); return listing([worktreeEntry]); } });
  await a.view.check(JSON.stringify([null, "/linked"]), a.cx);
  assert.deepEqual(plain(calls), [["/home/test/.agents/skills/herdr-orch/scripts/oh", ["worktree", "list", "--all", "--format", "json"], { cwd: "/main checkout", on: null }]]);
  const els = a.render();
  const match = a.byId(els, "agent-t2");
  assert.equal(match.find(([op]) => op === "tooltip")[1], "reviewer-9-astra\norch/example");
  const children = rowChildren(match);
  assert.equal(childText(children.at(-2)), "⑂ +3 ~2", "display is immediately before status");
  assert.ok(!words(els).includes("orch/example"), "branch is not row text");
  const unmatched = a.byId(els, "agent-t3");
  assert.equal(unmatched.find(([op]) => op === "tooltip")[1], "planner-1-heavy");
  assert.ok(!rowChildren(unmatched).some((c) => childText(c)?.includes("⑂")));
  assert.deepEqual(plain(a.orders.at(-1)), ["t1", "t2", "t3", "t4", "t5"]);
});

for (const [failure, response] of [
  ["non-zero exit", () => ({ code: 1, stdout: "unsupported --all" })],
  ["missing executable", () => { throw new Error("not found"); }],
  ["ok:false envelope", () => ({ code: 0, stdout: JSON.stringify({ schema: "herdr-orch/v1", ok: false, result: { entries: [worktreeEntry] } }) })],
  ["malformed JSON", () => ({ code: 0, stdout: "not JSON" })],
]) test(`a failed worktree listing (${failure}) clears old state but keeps the tree`, async () => {
  let result = () => listing([worktreeEntry]);
  const a = await app({ ...world, gitExec: worktreeGit, ohExec: async () => result() });
  const key = JSON.stringify([null, "/linked"]);
  await a.view.check(key, a.cx);
  assert.ok(words(a.render()).includes("⑂ +3 ~2"), "successful snapshot control");
  result = response;
  await a.view.check(key, a.cx);
  const els = a.render();
  assert.ok(!words(els).includes("⑂ +3 ~2"));
  assert.equal(a.byId(els, "agent-t2").find(([op]) => op === "tooltip")[1], "reviewer-9-astra");
  assert.deepEqual(plain(a.orders.at(-1)), ["t1", "t2", "t3", "t4", "t5"]);
});

test("each space is followed by its agent tree, named by the oh label", async () => {
  const a = await app(world);
  const w = words(a.render());
  assert.deepEqual(w.filter((x) => ["alpha", "beta", "Pi", "reviewer-9-astra", "planner-1-heavy", "planner-1/scout-2-fable", "notes"].includes(x)),
    ["alpha", "Pi", "reviewer-9-astra", "planner-1-heavy", "planner-1/scout-2-fable", "notes", "beta", "Pi"]);
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

test("a tab without an agent is a row named by the tab; a click selects it", async () => {
  const a = await app(world);
  const els = a.render();
  a.click(els, "tab-tb3");
  assert.deepEqual(plain(a.runs), [["tab.select", { id: "tb3" }]]);
  assert.equal(a.byId(els, "tab-tb1"), undefined, "a tab that holds an agent has no row of its own");
});

test("an unnamed tab row reads as its pane's live title, unless the title is a path or the program", async () => {
  const shell = { name: "zsh", key: "zsh", shell: true };
  const tabs = [{ id: "tb1", name: "1" }, { id: "ta", name: "2", named: false }, { id: "tb", name: "3", named: false },
                { id: "tc", name: "notes", named: true }, { id: "td", name: "5", named: false }];
  const pane = (tab, title, extra = {}) => ({ tab, terminal: `t-${tab}`, title, program: shell, focused: false, ...extra });
  const a = await app({ ...world, spaceList: [space("s1", "alpha", tabs)],
    paneList: [pane("ta", "✳ vim notes.md"), pane("tb", "~/dev/agents"), pane("tc", "vim todo.org"), pane("td", "zsh")] });
  const els = a.render();
  const label = (id) => childText(rowChildren(a.byId(els, `tab-${id}`)).at(-1));
  assert.deepEqual(["ta", "tb", "tc", "td"].map(label), ["vim notes.md", "3", "notes", "5"]);
});

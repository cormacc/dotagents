// Builder-spy contracts are not native layout or rendering evidence.
import test from "node:test";
import assert from "node:assert/strict";
import vm from "node:vm";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { response, row, window, modelWindows, namedAccounts } from "./quota-fixtures.mjs";

async function app() {
  let currentProps = {}, now = Date.parse("2030-01-01T02:30:00Z"), calls = 0, finish;
  const shared = new Map(), elements = [], timers = [], overlays = [];
  const handle = { focus() {}, is_focused: () => false };
  const cx = { notify() {}, stop_propagation() {}, focus_handle: () => handle,
    spawn: f => { f(cx); }, timer: {
      every: (ms, callback) => timers.push({ ms, callback, every: true }),
      after: (ms, callback) => timers.push({ ms, callback }),
    } };
  function element() {
    const record = { operations: [] };
    const proxy = new Proxy(record, { get(target, name) {
      if (name === "operations") return target.operations;
      return (...args) => {
        if (name === "role") assert.ok(["button", "check_box"].includes(args[0]), `Unknown native role: ${args[0]}`);
        target.operations.push([name, ...args]);
        if (name === "when" && args[0]) args[1](proxy);
        return proxy;
      };
    } });
    elements.push(record); return proxy;
  }
  const dark = { chrome: "#101010", popover: "#101010", foreground: "#ffffff", muted: "#777777", dim: "#555555", border: "#444444", hover: "#333333", accent: "#88aaff" };
  let theme = dark;
  const context = vm.createContext({ Date: class extends Date { static now() { return now; } }, console });
  const exports = {
    "gpui-kit": { View: class {}, div: element, svg: element, image: element },
    "gpui-base": { h_flex: element, v_flex: element },
    herdr: {
      exec: () => { calls++; return new Promise(resolve => { finish = resolve; }); },
      theme: () => theme, settings: () => ({ zoom: 1 }), shared: key => shared.get(key), setShared: (key, value) => shared.set(key, value),
      props: () => currentProps, home: () => "/fake", openOverlay: o => overlays.push(o), knownAgents: async () => [],
      nextCommand: () => new Promise(() => {}), run() {},
    },
  };
  const cache = new Map();
  async function load(specifier, parent = new URL("../mods/herdr-orch/main.js", import.meta.url).href) {
    const key = exports[specifier] ? specifier : new URL(specifier, parent).href;
    if (cache.has(key)) return cache.get(key);
    let mod;
    if (exports[key]) {
      mod = new vm.SyntheticModule(Object.keys(exports[key]), function () {
        for (const [name, value] of Object.entries(exports[key])) this.setExport(name, value);
      }, { context });
    } else {
      mod = new vm.SourceTextModule(await readFile(fileURLToPath(key), "utf8"), { context, identifier: key });
    }
    cache.set(key, mod);
    await mod.link((name, referring) => load(name, referring.identifier));
    return mod;
  }
  const mod = await load(new URL("../mods/herdr-orch/main.js", import.meta.url).href);
  await mod.evaluate();
  const View = mod.namespace.default;
  function make(props = {}) { currentProps = props; const view = new View(); view.init(props, cx); return view; }
  function render(view, props = {}) {
    currentProps = props; elements.length = 0; view.render(cx);
    return elements.flatMap(e => e.operations);
  }
  return { make, render, shared, timers, cx, overlays, calls: () => calls,
    finish: async providers => { await Promise.resolve(); finish(response(providers)); await new Promise(resolve => setImmediate(resolve)); },
    advance: n => { now += n; }, light: () => { theme = { ...dark, chrome: "#ffffff", popover: "#ffffff", foreground: "#000000" }; } };
}
const words = ops => ops.filter(o => o[0] === "child" && typeof o[1] === "string").map(o => o[1]);
const callback = (ops, method, id) => {
  if (id) {
    const index = ops.findIndex(o => o[0] === "id" && o[1] === id);
    return ops.slice(index).find(o => o[0] === method)?.[1];
  }
  return ops.find(o => o[0] === method)?.[1];
};

test("status owns initial/60s polling; popup shares data and refresh, with no second timer", async () => {
  const a = await app(), status = a.make();
  await Promise.resolve();
  assert.equal(a.calls(), 1);
  assert.deepEqual(a.timers.filter(t => t.every).map(t => t.ms), [60000]);
  const popup = a.make({ menu: true });
  assert.equal(a.calls(), 1);
  let popupOps = a.render(popup, { menu: true });
  callback(popupOps, "on_click", "herdr-orch-refresh")({}, a.cx);
  assert.equal(a.calls(), 1);
  await a.finish([row("alias", { windows: [{ ...window }, { ...window, kind: "model", scope: "model:extra" }] })]);
  const statusText = words(a.render(status));
  popupOps = a.render(popup, { menu: true });
  assert.ok(statusText.includes("alias"));
  assert.ok(statusText.includes("25%"));
  assert.ok(words(popupOps).includes("25%"));
  assert.ok(words(popupOps).some(s => s.includes("resets in")));
  assert.ok(words(popupOps).includes("Machine: This computer"));
  assert.ok(words(popupOps).includes("✓"), "enabled native options have a visible text check mark");
  assert.ok(words(popupOps).includes("model:extra · five_hour (18000s)"), "popup retains model details when status preference hides them");
  assert.ok(!statusText.some(s => s.startsWith("model:extra")));
  assert.equal(a.timers.filter(t => t.every).length, 1);
  assert.ok(a.timers.some(t => !t.every && t.ms === 300000));
});

test("sibling accounts render separately and visibility changes do not hide their sibling", async () => {
  const a = await app(), status = a.make();
  await a.finish(namedAccounts);
  const popup = a.make({ menu: true });
  for (const ops of [a.render(status), a.render(popup, { menu: true })]) {
    const text = words(ops);
    for (const expected of ["anthropic-1", "anthropic-2", "1%", "99%"])
      assert.ok(text.includes(expected), expected);
  }
  const ops = a.render(popup, { menu: true });
  callback(ops, "on_click", "prov-anthropic-1")();
  const text = words(a.render(status));
  assert.ok(!text.includes("anthropic-1"));
  assert.ok(text.includes("anthropic-2"));
  assert.ok(text.includes("99%"));
});

test("scoped model windows retain identity in status, popup, tooltips and accessible text", async () => {
  const a = await app(), status = a.make({ kinds: { model: true } });
  await a.finish([row("alias", { windows: modelWindows })]);
  const statusOps = a.render(status, { kinds: { model: true } });
  const popup = a.make({ menu: true });
  const popupOps = a.render(popup, { menu: true });
  const labels = ["model:sol · extra:0:primary_window (18000s)", "model:sol · extra:0:secondary_window (18000s)",
    "model:sol · extra:1:primary_window", "model:sol · extra:1:secondary_window"];
  for (const label of labels) {
    assert.ok(words(statusOps).includes(label), `status: ${label}`);
    assert.ok(words(popupOps).includes(label), `popup: ${label}`);
    assert.ok(statusOps.some(o => o[0] === "tooltip" && o[1].includes(label)), `status tooltip: ${label}`);
    assert.ok(popupOps.some(o => o[0] === "tooltip" && o[1] === label), `popup tooltip: ${label}`);
    assert.ok(statusOps.some(o => o[0] === "accessibility_label" && o[1].includes(label)), `accessible: ${label}`);
  }
});

test("keyboard opens the isolated overlay and operates persistent popup preferences", async () => {
  const a = await app(), status = a.make(); await a.finish([row("alias")]);
  callback(a.render(status), "on_key_down")({ keystroke: "enter" }, a.cx);
  assert.equal(a.overlays[0].mod, "herdr-orch");
  const popup = a.make({ menu: true });
  let ops = a.render(popup, { menu: true });
  const checked = words(ops).filter(s => s === "✓").length;
  const key = callback(ops, "on_key_down");
  key({ keystroke: "down" }, a.cx); key({ keystroke: "enter" }, a.cx);
  assert.equal(a.shared.get("herdr-orch.options").remaining, true);
  assert.equal(words(a.render(popup, { menu: true })).filter(s => s === "✓").length, checked + 1);
  for (const id of ["kind-weekly", "prov-alias", "names"]) {
    ops = a.render(popup, { menu: true }); callback(ops, "on_click", id)();
  }
  const saved = a.shared.get("herdr-orch.options");
  assert.equal(saved.kinds.weekly, false); assert.equal(saved.hidden[0], "alias"); assert.equal(saved.names, true);
  assert.ok(!words(a.render(status)).includes("25%"));
  ops = a.render(popup, { menu: true }); callback(ops, "on_click", "prov-alias")();
  assert.ok(words(a.render(status)).includes("75%"));
});

test("compact footer separates providers into scrollable rows without dropping window values", async () => {
  const a = await app(), status = a.make();
  await a.finish([row("anthropic", { windows: [{ ...window, percentUsed: 86, percentRemaining: 14 },
    { ...window, id: "week", kind: "weekly", percentUsed: 99, percentRemaining: 1 }] }),
    row("openai-codex", { provider: "codex", windows: [{ ...window, kind: "weekly", percentUsed: 26, percentRemaining: 74 }] })]);
  for (const width of [230, 200]) {
    callback(a.render(status), "on_size_change")({ width }, a.cx);
    const ops = a.render(status), text = words(ops);
    for (const expected of ["anthropic", "openai-codex", "5h", "wk", "86%", "99%", "26%"])
      assert.ok(text.includes(expected), `${width}: ${expected}`);
    assert.ok(ops.some(o => o[0] === "flex_col"), "compact providers stack rather than left-clip one long row");
    assert.ok(ops.some(o => o[0] === "line_height" && o[1] === "12px"), "pixel line height keeps text inside the native 13px row");
    assert.ok(ops.some(o => o[0] === "overflow_x_scroll"), "long aliases/scopes remain reachable");
    assert.ok(!ops.some(o => o[0] === "justify_end"), "overflow must not lose leading identity");
  }
});

test("narrow/light/dark builder states preserve model identity and suppress unknown pace", async () => {
  const a = await app(), status = a.make();
  await a.finish([row("alias", { windows: [{ ...window, kind: "unknown", scope: "model:unfamiliar", windowSeconds: null, resetsAt: null }] })]);
  const size = callback(a.render(status), "on_size_change"); size({ width: 100 }, a.cx);
  for (const light of [false, true]) {
    if (light) a.light();
    const text = words(a.render(status));
    assert.ok(text.includes("alias")); assert.ok(text.includes("model:unfamiliar · five_hour")); assert.ok(text.includes("25%"));
    assert.ok(!text.includes("▲"));
    const popup = a.make({ menu: true });
    const details = words(a.render(popup, { menu: true }));
    assert.ok(!details.some(s => s.includes("pace") || s.includes("resets in")));
  }
});

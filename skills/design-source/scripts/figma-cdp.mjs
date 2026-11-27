#!/usr/bin/env node
// Drive an open Figma design tab over the Chrome DevTools Protocol (CDP).
//
// Figma draws the canvas with WebGL, so the DOM holds only the panels. The
// script finds layers, tabs and export controls through the panel DOM, and
// sends trusted mouse and keyboard input through CDP, because Figma ignores
// synthetic DOM events on the canvas. One invocation runs one command; the
// selection stays in the tab between invocations.
//
// It speaks CDP directly to the tab's WebSocket, with no npm dependency:
// a browser-level puppeteer.connect attaches to every target and can hang
// when the browser has many tabs and workers.
//
// Code that runs in the page lives only in figma-page-snippets.mjs; this
// file is host code. Tab.evaluate takes a snippet name, not a function, so
// page code cannot creep back in here.
//
// Output: one JSON object on stdout. {"ok":true,...} on success;
// {"ok":false,"error":...,"help":...} on failure. Exit 0 ok, 1 error,
// 2 usage error. Warnings go to stderr.

import { mkdirSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { dirname, resolve as resolvePath } from 'node:path';
import * as snippets from './figma-page-snippets.mjs';

const USAGE = `figma-cdp <command> [args] [--browser-url URL] [--tab ID|URL-OR-TITLE-SUBSTRING]

Commands:
  status                        Tab, file key, node id, selection and panel state (default)
  tabs                          Browser tabs that show a Figma file
  layers                        Rendered rows of the Layers panel: id, name, level, expanded, selected
  expand <name|id>              Expand a collapsed layer row
  select <name|id> [--range|--toggle] [--zoom]
                                Click a layer row; --range Shift+clicks (extends to a range),
                                --toggle Mod+clicks (adds or removes); --zoom zooms to the selection
  props                         Text of the Properties panel for the current selection
  click <x> <y> [--deep] [--add]
                                --add Shift+clicks to add to the selection
                                Click the canvas at CSS pixel x,y; --deep selects the innermost layer
  key <combo>                   Press keys, e.g. Escape, Enter, Shift+Digit2, Mod+KeyA, Alt+KeyL
  search <text>                 Open layer search, type text, list the result rows
  select-results                Select every row of the current layer search results
  export --format png|jpg|svg|pdf --out DIR [--timeout S]
                                Export the selection through the Export section; waits for the download
  shot <file.png> [--clip x,y,w,h]
                                Screenshot of the viewport, or of a CSS pixel rectangle
  comments                      Text of the Comments panel

--tab defaults to the only tab whose URL contains figma.com/design.
Mod is Control on Linux and Windows, Meta on macOS.
Coordinates are CSS pixels; 'status' reports the viewport size and devicePixelRatio.`;

const VALUE_FLAGS = new Set(['--browser-url', '--tab', '--format', '--out', '--timeout', '--clip']);
const BOOL_FLAGS = new Set(['--add', '--range', '--toggle', '--zoom', '--deep', '--help']);
const COMMAND_FLAGS = {
  status: [], tabs: [], layers: [], expand: [], select: ['--range', '--toggle', '--zoom'], props: [],
  click: ['--deep', '--add'], key: [], search: [], 'select-results': [],
  export: ['--format', '--out', '--timeout'], shot: ['--clip'], comments: [],
};
const GLOBAL_FLAGS = ['--browser-url', '--tab', '--help'];

class UsageError extends Error {}
class CliError extends Error {
  constructor(message, help) { super(message); this.help = help; }
}

const sleep = ms => new Promise(r => setTimeout(r, ms));
const out = obj => process.stdout.write(JSON.stringify(obj, null, 1) + '\n');
const warn = (...a) => process.stderr.write(a.join(' ') + '\n');

function parseArgs(argv) {
  const pos = []; const flags = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (VALUE_FLAGS.has(a)) {
      if (i + 1 >= argv.length) throw new UsageError(`${a} needs a value`);
      flags[a] = argv[++i];
    } else if (BOOL_FLAGS.has(a)) flags[a] = true;
    else if (a.startsWith('--')) throw new UsageError(`unknown flag ${a}`);
    else pos.push(a);
  }
  const cmd = pos.shift() ?? 'status';
  if (!(cmd in COMMAND_FLAGS)) throw new UsageError(`unknown command ${cmd}`);
  for (const f of Object.keys(flags)) {
    if (!GLOBAL_FLAGS.includes(f) && !COMMAND_FLAGS[cmd].includes(f)) {
      throw new UsageError(`unknown flag ${f} for '${cmd}'; valid: ${COMMAND_FLAGS[cmd].join(', ') || '(none)'}`);
    }
  }
  return { cmd, pos, flags };
}

// --- CDP transport ----------------------------------------------------------

async function listTabs(browserURL) {
  let res;
  try { res = await fetch(`${browserURL}/json/list`, { signal: AbortSignal.timeout(5000) }); } catch {
    throw new CliError(`no CDP endpoint at ${browserURL}`, 'start the browser with --remote-debugging-port=9222, or pass --browser-url');
  }
  return (await res.json()).filter(t => t.type === 'page');
}

function figmaTabs(tabs) {
  return tabs.filter(t => /figma\.com\/(design|file|board|proto)\//.test(t.url))
    .map(t => ({ id: t.id, title: t.title, url: t.url }));
}

async function pickTab(flags) {
  const browserURL = flags['--browser-url'] ?? 'http://localhost:9222';
  const tabs = await listTabs(browserURL);
  const sel = flags['--tab'];
  const hits = sel
    ? tabs.filter(t => t.id === sel || t.url.includes(sel) || t.title.includes(sel))
    : tabs.filter(t => t.url.includes('figma.com/design'));
  if (hits.length === 0) {
    throw new CliError(sel ? `no tab matches '${sel}'` : 'no tab shows a figma.com/design URL', 'open the Figma file in the browser; run \'tabs\' to list candidates');
  }
  if (hits.length > 1) {
    throw new CliError(`${hits.length} tabs match: ${hits.map(t => `${t.id} "${t.title}"`).join('; ')}`, 'pass --tab <id> to choose one');
  }
  return hits[0];
}

class Tab {
  static async open(target) {
    const ws = new WebSocket(target.webSocketDebuggerUrl);
    await new Promise((res, rej) => {
      ws.onopen = res;
      ws.onerror = () => rej(new CliError(`cannot open the tab's CDP socket`, 'close DevTools on that tab and retry'));
    });
    const tab = new Tab(ws, target);
    // A background tab gets no animation frames, so Figma does not update
    // its panels after input: bring the tab to the front first.
    await tab.send('Page.bringToFront');
    return tab;
  }

  constructor(ws, target) {
    this.ws = ws; this.target = target; this.nextId = 0; this.pending = new Map(); this.listeners = [];
    this.modifierBits = 0;
    ws.onmessage = m => {
      const d = JSON.parse(m.data);
      if (d.id && this.pending.has(d.id)) {
        const { res, rej } = this.pending.get(d.id); this.pending.delete(d.id);
        d.error ? rej(new Error(`${d.error.message}`)) : res(d.result);
      } else if (d.method) this.listeners.forEach(l => l(d));
    };
  }

  send(method, params = {}) {
    return new Promise((res, rej) => {
      const id = ++this.nextId;
      this.pending.set(id, { res, rej });
      this.ws.send(JSON.stringify({ id, method, params }));
    });
  }

  close() { this.ws.close(); }

  // Run the page snippet `name` from figma-page-snippets.mjs in the tab,
  // with JSON-serialisable `args`, and return its JSON result.
  async evaluate(name, ...args) {
    const fn = snippets[name];
    if (typeof fn !== 'function') throw new Error(`no page snippet named ${name}`);
    const r = await this.send('Runtime.evaluate', {
      expression: `(${fn})(...${JSON.stringify(args)})`, returnByValue: true, awaitPromise: true, userGesture: true,
    });
    if (r.exceptionDetails) throw new Error(`page snippet ${name} failed: ${r.exceptionDetails.exception?.description ?? r.exceptionDetails.text}`);
    return r.result.value;
  }

  async mouse(type, x, y, extra = {}) {
    await this.send('Input.dispatchMouseEvent', { type, x, y, modifiers: this.modifierBits, ...extra });
  }

  async click(x, y) {
    await this.mouse('mouseMoved', x, y);
    await this.mouse('mousePressed', x, y, { button: 'left', buttons: 1, clickCount: 1 });
    await this.mouse('mouseReleased', x, y, { button: 'left', buttons: 0, clickCount: 1 });
  }

  async wheel(x, y, deltaX, deltaY) {
    await this.mouse('mouseWheel', x, y, { deltaX, deltaY });
  }

  async key(type, name) {
    const k = keyDef(name, this.modifierBits);
    const printable = k.text && !(this.modifierBits & (MOD.Control | MOD.Meta | MOD.Alt));
    await this.send('Input.dispatchKeyEvent', {
      type: type === 'down' ? (printable ? 'keyDown' : 'rawKeyDown') : 'keyUp',
      key: k.key, code: k.code, windowsVirtualKeyCode: k.vk, nativeVirtualKeyCode: k.vk,
      modifiers: this.modifierBits, ...(type === 'down' && printable ? { text: k.text, unmodifiedText: k.text } : {}),
    });
    if (MOD[name]) this.modifierBits = type === 'down' ? this.modifierBits | MOD[name] : this.modifierBits & ~MOD[name];
  }

  async withModifiers(mods, fn) {
    for (const m of mods) await this.key('down', m);
    try { return await fn(); } finally { for (const m of [...mods].reverse()) await this.key('up', m); }
  }

  async press(combo) {
    const mod = await modKey(this);
    const keys = combo.split('+').map(k => (k === 'Mod' ? mod : k));
    keys.forEach(k => keyDef(k, 0));
    await this.withModifiers(keys.slice(0, -1), async () => {
      await this.key('down', keys.at(-1));
      await this.key('up', keys.at(-1));
    });
  }

  async type(text) { await this.send('Input.insertText', { text }); }
}

const MOD = { Alt: 1, Control: 2, Meta: 4, Shift: 8 };
const SHIFTED_DIGITS = ')!@#$%^&*(';
const NAMED_KEYS = {
  Escape: ['Escape', 27], Enter: ['Enter', 13, '\r'], Tab: ['Tab', 9], Backspace: ['Backspace', 8],
  Delete: ['Delete', 46], Space: [' ', 32, ' '], ArrowLeft: ['ArrowLeft', 37], ArrowUp: ['ArrowUp', 38],
  ArrowRight: ['ArrowRight', 39], ArrowDown: ['ArrowDown', 40], Home: ['Home', 36], End: ['End', 35],
  PageUp: ['PageUp', 33], PageDown: ['PageDown', 34], Backslash: ['\\', 220, '\\'],
  Shift: ['Shift', 16], Control: ['Control', 17], Alt: ['Alt', 18], Meta: ['Meta', 91],
};

// Map a DOM `code` name to a CDP key event description (US layout).
function keyDef(name, bits) {
  const shift = (bits & MOD.Shift) !== 0;
  let m;
  if ((m = name.match(/^Key([A-Z])$/))) {
    const ch = shift ? m[1] : m[1].toLowerCase();
    return { key: ch, code: name, vk: m[1].charCodeAt(0), text: ch };
  }
  if ((m = name.match(/^Digit([0-9])$/))) {
    const ch = shift ? SHIFTED_DIGITS[Number(m[1])] : m[1];
    return { key: ch, code: name, vk: 48 + Number(m[1]), text: ch };
  }
  const n = NAMED_KEYS[name];
  if (!n) throw new UsageError(`unknown key ${name}; use a DOM code name such as KeyA, Digit2, Escape, Enter, ArrowDown, Shift, Mod`);
  const code = name === 'Control' || name === 'Shift' || name === 'Alt' || name === 'Meta' ? `${name}Left` : name;
  return { key: n[0], code, vk: n[1], text: n[2] };
}

// --- host helpers over page snippets ------------------------------------------

const layerRows = tab => tab.evaluate('layerRows');

async function findRow(tab, key) {
  const rows = await layerRows(tab);
  const byId = rows.filter(r => r.id === key);
  const hits = byId.length ? byId : rows.filter(r => r.name === key);
  if (hits.length === 0) {
    throw new CliError(`no rendered layer row named or with id '${key}' (${rows.length} rows rendered)`,
      "run 'layers' to list rendered rows; 'expand <parent>' or 'search <text>' to reach nested layers");
  }
  if (hits.length > 1) warn(`warning: ${hits.length} rows named '${key}'; using the first (${hits[0].id})`);
  return hits[0];
}

const searchResults = tab => tab.evaluate('searchResults');
const scrollList = (tab, itemSelector, to) => tab.evaluate('scrollList', itemSelector, to);

// Scroll the Layers panel until a rendered row is on screen.
async function scrollRowIntoView(tab, row) {
  for (let i = 0; i < 40 && !row.onScreen; i++) {
    const panel = await centreOf(tab, '[data-testid=objects-panel]');
    if (!panel) break;
    await tab.wheel(panel.x, panel.y, 0, Math.sign(row.y - panel.y) * 200);
    await sleep(150);
    row = await findRow(tab, row.id);
  }
  if (!row.onScreen) throw new CliError(`row '${row.name}' is not on screen`, "collapse other layers ('key Alt+KeyL'), or use 'search <text>'");
  return row;
}

const modKey = async tab => (await tab.evaluate('isMac') ? 'Meta' : 'Control');

const selectionSummary = tab => tab.evaluate('selectionSummary');

const PANEL_NOISE = new Set(['Share', 'Comments', 'Properties', 'Get coding, faster', 'Request access',
  'Dev Mode simplifies handoff, speeds up inspection, and integrates with tools like VS Code and Storybook.']);

async function propsLines(tab) {
  const text = await tab.evaluate('propertiesPanelText');
  if (text === null) throw new CliError('Properties panel not found', "the panel shows only to a signed-in user; run 'status'");
  const lines = text.split('\n').map(s => s.trim()).filter(Boolean);
  // Drop the avatar initials, tab labels, zoom level and Dev Mode upsell
  // that precede the selection data.
  return lines.filter((l, i) => !PANEL_NOISE.has(l) && !(i < 3 && /^[A-Z]{1,2}$/.test(l)) && !/^\d+%$/.test(l));
}

async function clickTab(tab, name) {
  const c = await tab.evaluate('tabCentre', name);
  if (!c) throw new CliError(`no '${name}' tab in the right panel`, "run 'status' to check the sign-in state");
  await tab.click(c[0], c[1]); await sleep(700);
}

const centreOf = (tab, selector, pattern = null) => tab.evaluate('centreOf', selector, pattern);

async function waitForDownload(dir, before, timeoutMs) {
  const end = Date.now() + timeoutMs;
  let last = null;
  while (Date.now() < end) {
    const fresh = readdirSync(dir).filter(f => !before.has(f));
    if (fresh.length && fresh.every(f => !f.endsWith('.crdownload'))) {
      const sizes = fresh.map(f => statSync(`${dir}/${f}`).size).join(',');
      if (sizes === last) return fresh.map(f => ({ file: `${dir}/${f}`, bytes: statSync(`${dir}/${f}`).size }));
      last = sizes;
    }
    await sleep(500);
  }
  return null;
}

// --- commands -----------------------------------------------------------------

const commands = {
  async status(tab) {
    const info = await tab.evaluate('pageStatus');
    const url = new URL(info.url);
    return {
      tabId: tab.target.id, fileKey: url.pathname.match(/\/(?:design|file)\/([^/]+)/)?.[1] ?? null,
      nodeId: url.searchParams.get('node-id')?.replace('-', ':') ?? null, ...info, selection: await selectionSummary(tab),
    };
  },

  async layers(tab) {
    const rows = (await layerRows(tab)).map(({ x, y, ...r }) => r);
    return { count: rows.length, rows, note: 'the panel is virtualised: it renders only rows near the visible part of the list' };
  },

  async expand(tab, [key]) {
    if (!key) throw new UsageError('expand needs a layer name or id');
    const row = await findRow(tab, key);
    if (!row.hasChildren) return { id: row.id, name: row.name, expanded: false, note: 'layer has no children (no-op)' };
    if (row.expanded) return { id: row.id, name: row.name, expanded: true, note: 'already expanded (no-op)' };
    const c = await tab.evaluate('expandCaretCentre', row.id);
    if (!c) throw new CliError(`no expand caret on '${row.name}'`, `select it ('select ${row.id}') and press 'key ArrowRight'`);
    await tab.click(c[0], c[1]); await sleep(500);
    return { id: row.id, name: row.name, expanded: (await findRow(tab, row.id)).expanded };
  },

  async select(tab, [key], flags) {
    if (!key) throw new UsageError('select needs a layer name or id');
    if (flags['--range'] && flags['--toggle']) throw new UsageError('use --range or --toggle, not both');
    const row = await scrollRowIntoView(tab, await findRow(tab, key));
    const mods = flags['--range'] ? ['Shift'] : flags['--toggle'] ? [await modKey(tab)] : [];
    // A plain click on a row inside a multiple selection keeps the whole
    // selection, so clear it first. Directly after an export, the first
    // Escape can leave the selection in place.
    for (let i = 0; i < 3 && mods.length === 0; i++) {
      await tab.press('Escape'); await sleep(300);
      if ((await selectionSummary(tab)).rows.length === 0) break;
    }
    await tab.withModifiers(mods, () => tab.click(row.x, row.y));
    await sleep(500);
    if (flags['--zoom']) { await tab.press('Shift+Digit2'); await sleep(800); }
    const sel = await selectionSummary(tab);
    const has = sel.rows.some(r => r.id === row.id);
    if (mods.length === 0 && !(has && sel.rows.length === 1)) throw new CliError(`click on '${row.name}' did not select it alone`, "run 'key Escape' and retry; a menu or dialog may have the focus");
    if (flags['--range'] && !has) throw new CliError(`Shift+click on '${row.name}' did not extend the selection`, "run 'props' to check the selection");
    return { selection: sel };
  },

  async props(tab) {
    return { selection: await selectionSummary(tab), lines: await propsLines(tab) };
  },

  async click(tab, [x, y], flags) {
    const px = Number(x), py = Number(y);
    if (!Number.isFinite(px) || !Number.isFinite(py)) throw new UsageError('click needs numeric x y in CSS pixels');
    const mods = [];
    if (flags['--deep']) mods.push(await modKey(tab));
    if (flags['--add']) mods.push('Shift');
    await tab.withModifiers(mods, () => tab.click(px, py));
    await sleep(700);
    return { selection: await selectionSummary(tab), lines: await propsLines(tab) };
  },

  async key(tab, [combo]) {
    if (!combo) throw new UsageError('key needs a combination, e.g. Escape or Shift+Digit2');
    await tab.press(combo); await sleep(500);
    return { pressed: combo, selection: await selectionSummary(tab) };
  },

  async search(tab, [text]) {
    if (!text) throw new UsageError('search needs text');
    const inputSel = '[data-testid=canvas-search-input]';
    let c = await centreOf(tab, inputSel);
    if (!c) {
      const btn = await centreOf(tab, '[data-testid=canvas-search-icon-button]');
      if (!btn) throw new CliError('no layer search control found', "run 'status'; the Layers panel may be hidden");
      await tab.click(btn.x, btn.y); await sleep(700);
      c = await centreOf(tab, inputSel);
    }
    if (!c) throw new CliError('layer search input did not open', "run 'shot <file>' to inspect the left panel");
    await tab.click(c.x, c.y);
    await tab.press('Mod+KeyA');
    await tab.type(text);
    await sleep(2000);
    const r = await searchResults(tab);
    return { query: text, total: r.total, rendered: r.rows.map(({ x, y, ...row }) => row), note: 'the result list is virtualised; run \'select-results\' to select all of them' };
  },

  async 'select-results'(tab) {
    // The result list is virtualised: click the first row, scroll to the
    // end, and Shift+click the last row to select the range.
    let r = await searchResults(tab);
    if (r.rows.length === 0) throw new CliError('no search result rows found', "run 'search <text>' first");
    await scrollList(tab, '[data-testid=search-sidebar] [role=option]', 'top'); await sleep(500);
    r = await searchResults(tab);
    const first = r.rows[0];
    await tab.click(first.x, first.y); await sleep(500);
    for (let i = 0; i < 500 && !(await scrollList(tab, '[data-testid=search-sidebar] [role=option]', 'down')); i++) await sleep(150);
    await sleep(600);
    r = await searchResults(tab);
    const last = r.rows.at(-1);
    await tab.withModifiers(['Shift'], () => tab.click(last.x, last.y));
    await sleep(800);
    const sel = await selectionSummary(tab);
    const n = Number(sel.counter?.match(/^\d+/)?.[0] ?? (sel.rows.length || 0));
    if (r.total !== null && n !== r.total) throw new CliError(`selected ${n} of ${r.total} results`, "retry 'select-results'; a click may have landed between rows");
    return { first: first.name, last: last.name, total: r.total, selection: sel.counter ?? sel.rows };
  },

  async export(tab, _pos, flags) {
    const format = { PNG: 'PNG', JPG: 'JPG', JPEG: 'JPG', SVG: 'SVG', PDF: 'PDF' }[(flags['--format'] ?? '').toUpperCase()];
    if (!format) throw new UsageError('export needs --format png|jpg|svg|pdf');
    if (!flags['--out']) throw new UsageError('export needs --out DIR');
    const dir = resolvePath(flags['--out']);
    mkdirSync(dir, { recursive: true });
    const timeoutMs = Number(flags['--timeout'] ?? 90) * 1000;
    // The first combobox in the panel is the colour format (Hex); the export
    // format box is the one that shows a file type.
    const boxSel = '[data-testid=properties-panel] button[role=combobox]';
    const fileType = '^(PNG|JPG|JPEG|SVG|PDF)$';
    let box = await centreOf(tab, boxSel, fileType);
    if (!box) {
      const plus = await centreOf(tab, '[data-testid=properties-panel] [aria-label="Add export settings"]');
      if (!plus) throw new CliError('no Export section in the Properties panel', "select a layer first, and check the sign-in state with 'status'");
      await tab.click(plus.x, plus.y); await sleep(700);
      box = await centreOf(tab, boxSel, fileType);
    }
    if (!box) throw new CliError('no export format control found', "run 'props' and inspect the Export section");
    if (box.text.replace('JPEG', 'JPG') !== format) {
      await tab.click(box.x, box.y); await sleep(600);
      const opt = await centreOf(tab, '[role=option]', format === 'JPG' ? '^JPE?G$' : `^${format}$`);
      if (!opt) { await tab.press('Escape'); throw new CliError(`format ${format} not offered`, 'Figma offers PNG, JPG, SVG and PDF'); }
      await tab.click(opt.x, opt.y); await sleep(600);
    }
    const btn = await centreOf(tab, '[data-testid=properties-panel] button', '^Export .+');
    if (!btn) throw new CliError('no Export button found', "run 'props' to inspect the Export section");
    await tab.send('Browser.setDownloadBehavior', { behavior: 'allow', downloadPath: dir });
    const before = new Set(readdirSync(dir));
    try {
      await tab.click(btn.x, btn.y);
      const files = await waitForDownload(dir, before, timeoutMs);
      if (!files) throw new CliError(`no completed download in ${dir} within ${timeoutMs / 1000} s after '${btn.text}'`, 'retry with a larger --timeout; a large selection can take a minute');
      return { button: btn.text, format, files };
    } finally {
      await tab.send('Browser.setDownloadBehavior', { behavior: 'default' }).catch(() => {});
    }
  },

  async shot(tab, [file], flags) {
    if (!file) throw new UsageError('shot needs an output .png path');
    const path = resolvePath(file);
    mkdirSync(dirname(path), { recursive: true });
    const params = { format: 'png' };
    if (flags['--clip']) {
      const [x, y, width, height] = flags['--clip'].split(',').map(Number);
      if ([x, y, width, height].some(n => !Number.isFinite(n))) throw new UsageError('--clip needs x,y,w,h in CSS pixels');
      params.clip = { x, y, width, height, scale: 1 };
    }
    await sleep(300);
    const { data } = await tab.send('Page.captureScreenshot', params);
    writeFileSync(path, Buffer.from(data, 'base64'));
    const viewport = await tab.evaluate('viewport');
    return { file: path, viewport, clip: params.clip ?? null, note: 'image pixels = CSS pixels x devicePixelRatio' };
  },

  async comments(tab) {
    // The comment list is virtualised: read the rendered threads, scroll the
    // list by most of a screen, and repeat until the end of the list.
    await clickTab(tab, 'Comments');
    try {
      const seen = new Map();
      const scroll = to => scrollList(tab, '[data-testid=comment-message]', to);
      await scroll('top'); await sleep(400);
      for (let i = 0, atEnd = false; i < 500; i++) {
        const batch = await tab.evaluate('commentThreads');
        batch.forEach(c => seen.set(c.id ?? c.text, c));
        if (atEnd) break;
        atEnd = await scroll('down');
        if (atEnd === null) break;
        await sleep(400);
      }
      const threads = [...seen.values()];
      return { count: threads.length, threads, note: 'top-level comment of each thread; open a thread in the browser for its replies' };
    } finally {
      await clickTab(tab, 'Properties');
    }
  },
};

// --- main -------------------------------------------------------------------------

async function main() {
  let parsed;
  try { parsed = parseArgs(process.argv.slice(2)); } catch (e) {
    out({ ok: false, error: e.message, help: USAGE }); return 2;
  }
  const { cmd, pos, flags } = parsed;
  if (flags['--help']) { process.stdout.write(USAGE + '\n'); return 0; }
  let tab;
  try {
    if (cmd === 'tabs') {
      const tabs = figmaTabs(await listTabs(flags['--browser-url'] ?? 'http://localhost:9222'));
      out({ ok: true, command: cmd, count: tabs.length, tabs });
      return 0;
    }
    tab = await Tab.open(await pickTab(flags));
    const result = await commands[cmd](tab, pos, flags);
    out({ ok: true, command: cmd, ...result });
    return 0;
  } catch (e) {
    if (e instanceof UsageError) { out({ ok: false, error: e.message, help: USAGE }); return 2; }
    out({ ok: false, error: String(e?.message ?? e), ...(e instanceof CliError ? { help: e.help } : {}) });
    return 1;
  } finally {
    tab?.close();
  }
}

process.exit(await main());

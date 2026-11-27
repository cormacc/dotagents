// Page snippets: functions that figma-cdp.mjs runs inside the Figma tab.
//
// The host never calls these functions in Node. It sends each one's source
// text to the page with CDP Runtime.evaluate, as
// `(<source>)(...<JSON arguments>)`, and receives the return value as JSON.
// Each export must therefore be self-contained:
//
// - Refer only to its parameters and to browser globals (document, window,
//   innerWidth, navigator, getComputedStyle). Do not refer to an import, to
//   another export, or to anything else in this module: none of it exists
//   in the page.
// - Take and return only JSON-serialisable values.
// - Do not change the page, except where the name says so (scrollList).
//
// test-figma-page-snippets.mjs runs every export in an empty scope to
// enforce the first rule.
//
// The DOM anchors that these functions use are listed in
// references/figma.md, section 'DOM anchors'.

// Rows of the Layers panel that are rendered. The panel is virtualised, so
// only rows near the visible part of the list exist. `selected` uses
// data-fpl-selected-root: aria-selected also marks the descendants of a
// selected layer.
export function layerRows() {
  return [...document.querySelectorAll('[data-testid$="-layers-panel-row"]')].map(e => {
    const row = e.closest('[role=row]');
    const cell = e.querySelector('[role=gridcell]') ?? e;
    const r = cell.getBoundingClientRect();
    return {
      id: e.dataset.testid.replace(/-layers-panel-row$/, ''),
      name: e.textContent.trim(),
      level: Number(row?.getAttribute('aria-level') ?? 0),
      hasChildren: row?.hasAttribute('aria-expanded') ?? false,
      expanded: row?.getAttribute('aria-expanded') === 'true',
      selected: row?.getAttribute('data-fpl-selected-root') === 'true',
      x: r.left + Math.min(r.width / 2, 60), y: r.top + r.height / 2,
      onScreen: r.width > 0 && r.height > 0 && r.top >= 0 && r.bottom <= innerHeight,
    };
  });
}

// Centre of the expand caret on the layer row with node id `id`, or null.
export function expandCaretCentre(id) {
  const caret = document.querySelector(`[data-testid="${id}-layers-panel-row"]`)?.closest('[role=row]')
    ?.querySelector('[data-testid=layers-panel-expand-caret]');
  const r = caret?.getBoundingClientRect();
  return r && r.width > 0 ? [r.left + r.width / 2, r.top + r.height / 2] : null;
}

// The selected layers, from the Layers panel, and the "N Selected" counter
// that the Properties panel shows for a multiple selection.
export function selectionSummary() {
  const rows = [...document.querySelectorAll('[role=row][data-fpl-selected-root=true] [data-testid$="-layers-panel-row"]')]
    .map(e => ({ id: e.dataset.testid.replace(/-layers-panel-row$/, ''), name: e.textContent.trim() }));
  const panel = document.querySelector('[data-testid=properties-panel]')?.innerText ?? '';
  const counter = panel.split('\n').map(s => s.trim()).find(s => /^\d+ selected$/i.test(s)) ?? null;
  return { rows, counter };
}

// Rendered text of the Properties panel, or null when the panel is absent.
export function propertiesPanelText() {
  return document.querySelector('[data-testid=properties-panel]')?.innerText ?? null;
}

// Rendered rows of the layer search results, and the total result count.
export function searchResults() {
  const side = document.querySelector('[data-testid=search-sidebar]');
  const total = side?.innerText.match(/^(\d+) results?$/m)?.[1];
  const rows = [...(side?.querySelectorAll('[role=option]') ?? [])].map(o => {
    const b = o.getBoundingClientRect();
    const [name, context] = o.innerText.split('\n').map(s => s.trim()).filter(Boolean);
    return { name, context: context ?? null, x: b.left + Math.min(b.width / 2, 80), y: b.top + Math.min(b.height / 2, 12), h: b.height };
  }).filter(o => o.h > 0 && o.y > 0 && o.y < innerHeight);
  return { total: total ? Number(total) : null, rows };
}

// Rendered comment threads: the top-level comment of each, with the
// "#<id> <middle dot> <page>" header, author and age read from its card.
export function commentThreads() {
  return [...document.querySelectorAll('[data-testid=comment-message]')].map(m => {
    const card = m.closest('[class*="comments_row_presentation--comment-"]') ?? m.parentElement;
    const lines = card.innerText.split('\n').map(s => s.trim()).filter(Boolean);
    const head = lines.findIndex(l => /^#\d+ \u00b7 /.test(l));
    const parts = head >= 0 ? lines[head].split(' \u00b7 ') : null;
    return {
      id: parts ? parts[0] : null, page: parts ? parts.slice(1).join(' \u00b7 ') : null,
      author: head >= 0 ? lines[head + 1] ?? null : null, age: head >= 0 ? lines[head + 2] ?? null : null,
      text: m.innerText.trim(),
    };
  });
}

// Scroll the nearest scrollable ancestor of the first `itemSelector` match:
// to the top (`to` = 'top'), or down by most of its height. Returns true at
// the end of the list, or null when there is no scrollable list. Figma's
// virtualised lists follow scrollTop; wheel events over them proved
// unreliable.
export function scrollList(itemSelector, to) {
  let a = document.querySelector(itemSelector);
  while (a && !(a.scrollHeight > a.clientHeight + 5 && /(auto|scroll)/.test(getComputedStyle(a).overflowY))) a = a.parentElement;
  if (!a) return null;
  if (to === 'top') a.scrollTop = 0; else a.scrollTop += Math.round(a.clientHeight * 0.8);
  return a.scrollTop + a.clientHeight >= a.scrollHeight - 2;
}

// Centre and text of the first element that matches `selector` and whose
// trimmed text matches `pattern` (a regular-expression source; null matches
// any text). Null when there is none, or when it is not rendered.
export function centreOf(selector, pattern) {
  const re = pattern && new RegExp(pattern);
  const e = [...document.querySelectorAll(selector)].find(x => !re || re.test(x.textContent.trim()));
  if (!e) return null;
  const r = e.getBoundingClientRect();
  return r.width > 0 ? { x: r.left + r.width / 2, y: r.top + r.height / 2, text: e.textContent.trim() } : null;
}

// Centre of the right-panel tab whose label starts with `name`, or null.
export function tabCentre(name) {
  const t = [...document.querySelectorAll('[role=tab]')].find(e => e.textContent.trim().startsWith(name));
  if (!t) return null;
  const r = t.getBoundingClientRect();
  return [r.left + r.width / 2, r.top + r.height / 2];
}

// Page state for the 'status' command. Tab labels render twice in one
// element ("CommentsComments"), so the regular expression keeps one copy.
export function pageStatus() {
  const tabs = [...document.querySelectorAll('[role=tab]')]
    .map(e => ({ name: e.textContent.trim().replace(/^(.+)\1$/, '$1'), selected: e.getAttribute('aria-selected') === 'true' }));
  const props = document.querySelector('[data-testid=properties-panel]');
  return {
    url: location.href, title: document.title,
    viewport: { width: innerWidth, height: innerHeight, devicePixelRatio },
    layersPanel: !!document.querySelector('[data-testid=objects-panel]'),
    propertiesPanel: !!props, devModeLocked: /Dev Mode simplifies handoff/.test(props?.innerText ?? ''),
    rightTabs: tabs, platform: navigator.platform,
  };
}

// Viewport size in CSS pixels, and the device pixel ratio.
export function viewport() {
  return { width: innerWidth, height: innerHeight, devicePixelRatio };
}

// True on macOS, where Figma's Mod key is Meta rather than Control.
export function isMac() {
  return /Mac/.test(navigator.platform);
}

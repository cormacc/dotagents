// Pure helpers for the org-tasks widget, loadable by `node --test`.
// Task logic (which rows, order, emphasis, progress, counts) belongs to
// `ot compact`; this file only picks the project and maps data to presentation.

// The project follows the focused terminal's folder, an agent's or a shell's,
// so a tab without an agent still shows its tasks. A space on another machine
// adds that `machine`: its folder is a path there, and `ot` runs there. A mod
// pane gives a reason instead of a folder, so the widget never shows a
// different project's tasks.
export function project(focus, panes, spaces) {
  const none = { reason: "Focus a terminal to show its tasks" };
  if (!focus) return none;
  const machine = spaces.find(s => s.id === focus.space)?.machine ?? null;
  // A focused layout terminal block has no pane; its `cwd` describes it.
  const pane = focus.pane ? panes.find(p => p.pane === focus.pane) : null;
  if (focus.pane ? !pane : !focus.terminal) return none;
  const dir = pane?.cwd ?? focus.cwd;
  if (!dir) return { reason: "Terminal folder unknown" };
  return machine ? { dir, machine } : { dir };
}

// The same folder on two machines is two projects.
const projectKey = (dir, machine, reason) => dir ? JSON.stringify([machine ?? null, dir]) : `!${reason}`;

// Should the widget switch to project `p`? `memo` keeps the shown project
// (`dir`, `reason`) and a pending candidate between polls. The first project
// switches at once; later changes must hold for `polls` polls.
export function settle(memo, p, polls) {
  const key = projectKey(p.dir, p.machine, p.reason);
  const shown = memo.dir || memo.reason != null ? projectKey(memo.dir, memo.machine, memo.reason) : null;
  if (key === shown) { memo.candidate = null; return false; }
  if (shown !== null) {
    if (memo.candidate !== key) { memo.candidate = key; memo.seen = 1; return polls <= 1; }
    if (++memo.seen < polls) return false;
  }
  memo.candidate = null;
  return true;
}

// Block px for a rows column `height` logical px tall: whole px at zoom 1,
// including padding above and below.
export function fitHeight(height, padY, zoom) {
  return Math.ceil(height / (zoom || 1)) + 2 * padY;
}

// Status words in the theme's ANSI colours, matching the terminal palette
// (TODO yellow, STARTED blue, DONE green), so they follow light and dark
// themes. ANSI has no orange, so WAITING takes the theme's blocked colour.
export function statusColor(t, status) {
  return ({ TODO: t.ansi3, STARTED: t.ansi4, WAITING: t.blocked, DONE: t.ansi2, CANCELLED: t.dim })[status] ?? t.foreground;
}

// `<N> tasks: <n> TODO / ...` parts, in the order `ot compact` gives.
export function countParts(model) {
  return (model.counts ?? []).map(c => ({ count: c.count, status: c.status }));
}

const ACTIONS = [
  ["org-tasks.select", "select"],
  ["layout.toggle:org-tasks", "show/hide"],
];

// One key-hint line for the bound actions, read from the live key table, as
// the Herdr TUI compact pane shows it; null when neither action is bound.
export function hint(bindings) {
  const parts = ACTIONS.flatMap(([action, text]) => {
    const b = bindings.find(k => k.action === action);
    return b ? [`${b.keys}: ${text}`] : [];
  });
  return parts.length ? parts.join(" / ") : null;
}

// Did a watch report touch a file the view reads?
export function touches(changed, files) {
  const watched = new Set(files ?? []);
  return changed.length === 0 || changed.some(p => watched.has(p));
}

// The model, or a reason, from an `ot compact --format json` run.
export const INSTALL_URL = "https://github.com/cormacc/dotagents/blob/main/skills/herdr-orch/references/install-surfaces.md";
export const notInstalled = path => `ot not installed at ${path}. See ${INSTALL_URL}`;

// On another machine the home-installed `ot` is under that machine's $HOME,
// which this side does not know, so a shell there resolves it. A missing file
// is reported with a marker line, so it is told apart from a failure inside
// `ot`, such as a missing `bb`.
const MISSING = "org-tasks: ot not installed at ";
const REMOTE_SCRIPT = `p="$HOME/.agents/skills/org-tasks/scripts/ot"; `
  + `[ -e "$p" ] || { printf '${MISSING}%s\\n' "$p" >&2; exit 127; }; exec "$p" "$@"`;

// [command, args] that run the home-installed `ot` with `args` on another machine.
export function remoteOt(args) {
  return ["sh", ["-c", REMOTE_SCRIPT, "ot", ...args]];
}

// The missing path that a `remoteOt` run reported, or null.
export function missingRemote(result) {
  if (result.code !== 127) return null;
  const line = String(result.stderr ?? "").split("\n").find(l => l.startsWith(MISSING));
  return line ? line.slice(MISSING.length) : null;
}

export function parse(result) {
  if (result.code !== 0) {
    let message = null;
    try { message = JSON.parse(result.stderr || result.stdout)?.error?.message; } catch { /* plain text */ }
    return { reason: `ot failed: ${message ?? (result.stderr || `exit ${result.code}`).trim().split("\n")[0]}` };
  }
  try {
    const envelope = JSON.parse(result.stdout);
    return envelope.ok ? { model: envelope.result } : { reason: `ot failed: ${envelope.error?.message ?? "unknown error"}` };
  } catch {
    const head = String(result.stdout ?? "").trim().slice(0, 80) || String(result.stderr ?? "").trim().slice(0, 80);
    return { reason: `ot returned unreadable output: ${head || "(empty)"}` };
  }
}

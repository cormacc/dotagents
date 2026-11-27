// Pure logic for the emacs-magit and emacs-plan mods: what to run for the
// focused terminal, and how to read the answer. No `herdr` imports, so the
// node tests can load it.
//
// herdr-app refuses a module outside a mod's folder, symlinks included, so
// emacs-plan holds byte-identical copies of this file and opener.js;
// herdr-app/tests/emacs-tabs.test.mjs fails when they differ.

// The focused terminal's folder and machine, as the org-tasks widget finds
// them (herdr-app/mods/org-tasks/state.js `project`).
export function project(focus, panes, spaces) {
  const none = { reason: "Focus a terminal first" };
  if (!focus) return none;
  const machine = spaces.find(s => s.id === focus.space)?.machine ?? null;
  // A focused layout terminal block has no pane; its `cwd` describes it.
  const pane = focus.pane ? panes.find(p => p.pane === focus.pane) : null;
  if (focus.pane ? !pane : !focus.terminal) return none;
  const dir = pane?.cwd ?? focus.cwd;
  if (!dir) return { reason: "Terminal folder unknown" };
  return machine ? { dir, machine } : { dir };
}

// Run through `sh` on the space's machine, so `$HOME` is that machine's home.
export const EMACS_TAB = "$HOME/dotfiles/agents/emacs/herdr-tabs/emacs-tab";

// Remote space/tab ids carry a `machine/` prefix; labels on that machine omit it.
export const localId = id => (id && id.includes("/") ? id.slice(id.indexOf("/") + 1) : id);
export const qualify = (machine, address) => (machine ? `${machine}/${address}` : address);

// The `exec` call that opens `view` for the focused terminal, or `{reason}`.
// `--no-focus`: the mod focuses the tab itself, which also works for a remote
// space, where `emacs-tab` cannot reach this window.
export function request(view, focus, panes, spaces) {
  const p = project(focus, panes, spaces);
  if (!p.dir) return { reason: p.reason };
  const args = ["-c", `exec "${EMACS_TAB}" "$@"`, "emacs-tab", view, "--runtime", "hc",
                "--space", localId(focus.space), "--cwd", p.dir, "--no-focus", "--format", "json"];
  return { command: "sh", args, options: { cwd: p.dir, on: p.machine ?? null, timeout: 15000 }, machine: p.machine ?? null };
}

// `{terminal, created}` from emacs-tab's JSON answer, or `{error}`.
export function answer(result, machine) {
  let body = null;
  try { body = JSON.parse(result?.stdout ?? ""); } catch { /* reported below */ }
  if (body?.ok && body.result?.terminal) {
    return { terminal: qualify(machine, body.result.terminal), created: !!body.result.created };
  }
  const detail = body?.error?.message ?? (result?.stderr || result?.stdout || "").trim();
  if (result?.code === 127 || /not found|No such file/.test(detail)) {
    return { error: `emacs-tab not installed at ${machine ? `${machine}:` : ""}${EMACS_TAB}` };
  }
  return { error: detail || `emacs-tab exited ${result?.code}` };
}

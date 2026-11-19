// Compact selected-task context for the focused agent's project, drawn natively
// from `ot compact --format json`. `ot` owns every task decision; this mod only
// chooses the project, watches its task files and presents the rows.
import { View, div } from "gpui-kit";
import { h_flex, v_flex } from "gpui-base";
import { exec, hc, home, focused, panes, spaces, theme, keys, props, settings, run, openOverlay, nextCommand, watchFolder, notify, readTextFile } from "herdr";
import { project, settle, statusColor, countParts, hint, touches, parse, fitHeight, remoteOt, missingRemote } from "./state.js";

// Layout props may name the local `ot` executable and the block that holds this
// widget (default: `org-tasks`), which it sizes to its rows. The default `ot` is
// the home-installed skill script by absolute path, like the quota mod's `oh`:
// a desktop-started app's PATH can omit the user's shell PATH additions.
// Another machine always runs its own home-installed `ot` (`remoteOt`).
const ot = () => props()?.ot ?? home() + "/.agents/skills/org-tasks/scripts/ot";
const block = () => props()?.block ?? "org-tasks";
// Vertical padding around the rows, px at zoom 1.
const PAD_Y = 6;
// Picks up focus changes without an exec; file changes arrive through watchFolder.
const FOCUS_MS = 1000;
// A fallback for edits a watch misses, such as a replaced folder.
const REFRESH_MS = 30000;
// watchFolder sees only this computer, so a project on another machine is
// polled instead. A run still in flight skips the poll, so a slow link never
// stacks runs up to exec's 30 s limit.
const REMOTE_MS = 5000;
// A pane's live folder is its foreground process's, so an agent's tool commands
// move it for a moment. Within one terminal, switch only once a new project
// holds for this many polls; focusing another terminal switches at once.
const STABLE_POLLS = 2;

const COMPACT = ["compact", "--format", "json"];

// A start failure is reported as not installed only when the file is absent;
// a failed check is not proof of absence.
async function runLocal(dir) {
  const path = ot();
  return exec(path, COMPACT, { cwd: dir })
    .catch(async e => await readTextFile(path).then(text => text === null, () => false)
      ? { missing: path }
      : { code: 127, stdout: "", stderr: String(e?.message ?? e) });
}

// A rejection here means the machine or its folder could not be reached.
async function runRemote(dir, machine) {
  const [command, args] = remoteOt(COMPACT);
  const result = await exec(command, args, { cwd: dir, on: machine })
    .catch(e => ({ code: 127, stdout: "", stderr: `${machine}: ${String(e?.message ?? e)}` }));
  const missing = missingRemote(result);
  return missing ? { missing: `${machine}:${missing}` } : result;
}

export default class OrgTasks extends View {
  init(_props, cx) {
    // known-limit: pins are view-local; revisit if cross-widget synchronisation is required.
    this.pin = null;
    this.dir = null;
    this.machine = null;
    this.reason = null;
    this.model = null;
    this.generation = 0;
    this.request = 0;
    this.selectionRequest = 0;
    this.busy = null;
    this.watched = null;
    this.follow(cx);
    cx.timer.every(FOCUS_MS, cx => this.follow(cx));
    cx.timer.every(REFRESH_MS, cx => this.dir && !this.machine && this.load(cx));
    cx.timer.every(REMOTE_MS, cx => this.dir && this.machine && this.busy === null && this.load(cx));
    cx.spawn(async cx => {
      for (;;) {
        const c = await nextCommand();
        if (c === "select") cx.spawn(async cx => { await this.select(); cx.notify(); });
        if (c === "pin") this.togglePin(cx);
        cx.notify();
      }
    });
  }

  togglePin(cx) {
    if (!this.pin && (this.reason || !this.model?.memory || !this.model?.root)) {
      notify({ title: "Org tasks", body: "Cannot pin without resolved task memory", level: "info" });
      return;
    }
    this.pin = this.pin ? null : { root: this.model.root, machine: this.machine };
    // Focus can move arbitrarily while pinned; no earlier candidate is relevant.
    this.source = null;
    this.candidate = null;
    this.seen = 0;
    const focus = focused();
    const p = this.pin ? { dir: this.pin.root, machine: this.pin.machine } : project(focus, panes(), spaces());
    this.source = focus?.pane ?? focus?.terminal ?? null;
    this.transition(cx, p);
  }

  // Re-read focus only when it owns the project's identity.
  follow(cx) {
    if (this.pin) return;
    const focus = focused();
    const p = project(focus, panes(), spaces());
    const next = settle(this, p, STABLE_POLLS, focus?.pane ?? focus?.terminal ?? null);
    if (!next) return;
    this.transition(cx, p);
  }

  transition(cx, p) {
    // Invalidate even when the new state has no folder and starts no request.
    ++this.request;
    this.busy = null;
    this.dir = p.dir ?? null;
    this.machine = p.machine ?? null;
    this.reason = p.reason ?? null;
    this.model = null;
    this.watched = null;
    ++this.generation;
    if (this.dir) this.load(cx);
    cx.notify();
  }

  load(cx) {
    const dir = this.dir, machine = this.machine, pin = this.pin, generation = this.generation, request = ++this.request;
    this.busy = request;
    cx.spawn(async cx => {
      try {
        const result = machine ? await runRemote(dir, machine) : await runLocal(dir);
        // A later run, or a focus change, supersedes this result.
        if (request !== this.request || generation !== this.generation) return;
        const parsed = parse(result, pin);
        this.model = parsed.model ?? null;
        this.reason = parsed.reason ?? null;
        // ot resolves the project root upwards from the pane's folder, so watch
        // that root. A remote root is polled instead.
        const root = pin?.root ?? this.model?.root;
        if (!machine && root && root !== this.watched) {
          this.watched = root;
          this.watch(cx, root, generation);
        }
        cx.notify();
      } finally {
        if (this.busy === request) this.busy = null;
      }
    });
  }

  // One watch loop per project root; it ends at its next change once superseded.
  // Without a model (a failed run) any change reloads, so a fix shows at once.
  watch(cx, root, generation) {
    const current = () => generation === this.generation && root === this.watched;
    cx.spawn(async cx => {
      while (current()) {
        const changed = await watchFolder(root).catch(() => null);
        if (changed === null || !current()) return;
        if (!this.model || touches(changed, this.model.files)) this.load(cx);
      }
    });
  }

  async select() {
    if (!this.dir) {
      notify({ title: "Org tasks", body: this.reason ?? "No project", level: "info" });
      return;
    }
    const dir = this.dir, machine = this.machine, pin = this.pin, generation = this.generation;
    if (pin) {
      // Observe the display request epoch without superseding an in-flight load.
      const request = this.request, selectionRequest = ++this.selectionRequest;
      // Check again at selection time: memory may disappear between refreshes.
      const result = machine ? await runRemote(dir, machine) : await runLocal(dir);
      if (generation !== this.generation) return;
      const parsed = parse(result, pin);
      if (parsed.reason) {
        // Newer display loads and selection checks own subsequent display state.
        if (request === this.request && selectionRequest === this.selectionRequest) {
          this.model = null;
          this.reason = parsed.reason;
        }
        notify({ title: "Org tasks", body: `Cannot select pinned tasks: ${parsed.reason}`, level: "info" });
        return;
      }
    }
    // A detached terminal ends with `ot`, and the overlay closes when it ends.
    const argv = machine ? remoteOt(["tui"]).flat() : [ot(), "tui"];
    const on = machine ? ["--on", machine] : [];
    const r = await hc(["run", "--json", "--bg", "--no-parent", "--rm", ...on, "--cwd", dir, "--", ...argv]);
    let address = null;
    try { address = JSON.parse(r.stdout)?.address; } catch { /* reported below */ }
    if (r.code !== 0 || !address) {
      notify({ title: "Org tasks", body: `Could not start ot: ${(r.stderr || r.stdout).trim()}`, level: "error" });
      return;
    }
    openOverlay({ kind: "dialog", modal: true, width: 0.8, height: 0.8,
                  layout: { type: "block", content: { terminal: address } } });
  }

  row(t, r) {
    const muted = s => div().text_color(t.muted).child(s);
    const text = h_flex().gap(6).min_w(0).flex_1()
      .when(r.role === "started" || r.role === "next", el => el.child(muted("...")))
      .child(div().flex_none().text_color(statusColor(t, r.status)).child(r.status))
      .child(div().truncate().text_color(r.current ? t.foreground : t.muted).child(r.label));
    return h_flex().id(`row-${r.role}-${r.id}`).w_full().gap(8)
      .child(text)
      .when(r.token != null, el => el.child(div().flex_none().text_color(t.muted).child(r.token)));
  }

  lines(t) {
    const m = this.model;
    const where = this.machine ? `${this.machine}:${this.dir}` : this.dir;
    if (this.reason || !m) return [div().text_color(t.muted).child(this.reason ?? `Loading tasks in ${where}…`)];
    if (!m.memory) return [div().text_color(t.muted).child(`No TASKS.org in ${m.folder}`)];
    if (m.selection === "selected")
      return m.rows.map(r => r.role === "parent"
        ? div().truncate().text_color(t.muted).child(`Parent: ${r.summary}`)
        : this.row(t, r));
    if (m.selection === "stale")
      return [div().text_color(t.muted).child(`Stale selection: ${m.selectedId}`)];
    const summary = h_flex().gap(4).child(div().text_color(t.muted).child(`${m.total} tasks${m.counts.length ? ":" : ""}`))
      .children(countParts(m).flatMap(({ count, status }, i) => [
        div().text_color(t.muted).child(`${i ? "/ " : ""}${count}`),
        div().text_color(statusColor(t, status)).child(status),
      ]));
    return [summary];
  }

  // Size the block to the rows shown. The layout's px are at zoom 1.
  fit(height) {
    const px = fitHeight(height, PAD_Y, settings()?.zoom);
    if (px === this.px) return;
    this.px = px;
    run("layout.resize", { id: block(), px });
  }

  render() {
    const t = theme();
    const title = this.model?.title ?? "Tasks";
    const keyHint = hint(keys());
    const pinnedLocation = this.pin ? (this.pin.machine ? `${this.pin.machine}:${this.pin.root}` : this.pin.root) : null;
    // The rows column keeps its natural height, so its size is what the block needs.
    // The key hint shares the title row, right-justified, so it costs no row of
    // its own and stays visible in every state. The title truncates first.
    const header = h_flex().id("org-tasks-title").w_full().gap(8).text_size(11)
      .child(div().min_w(0).flex_1().truncate().text_color(t.dim).child(title))
      .when(keyHint !== null, el => el.child(div().id("org-tasks-hint").flex_none().text_color(t.muted).child(keyHint)));
    const rows = v_flex().id("org-tasks-rows").flex_none().w_full().gap(2)
      .on_size_change(size => this.fit(size.height))
      .child(h_flex().id("org-tasks-pin").w_full().gap(8).text_color(t.muted)
        .on_click(() => run("org-tasks.pin"))
        .child(div().flex_none().child(this.pin ? "Pinned" : "Following"))
        .when(pinnedLocation !== null, el => el.child(div().min_w(0).truncate().tooltip(pinnedLocation)
          .child(pinnedLocation))))
      .child(v_flex().id("org-tasks-selection").w_full().gap(2)
        .on_click(() => run("org-tasks.select"))
        .child(header)
        .children(this.lines(t)));
    return v_flex().id("org-tasks").size_full().px(12).py(PAD_Y).text_size(12).overflow_hidden()
      .child(rows);
  }
}

// CPU, memory and GPU load in the status bar: a small live graph and a percent for each.
// Follows the selected space's machine. macOS reads `iostat`, `vm_stat`, `sysctl` and `ioreg`;
// Linux reads /proc and, inside a container, its cgroup limits (see load.js). None need root.
import { View, div } from "gpui-kit";
import { h_flex } from "gpui-base";
import { exec, theme, settings, spaces, run, window } from "herdr";
import { readLoad, sustained } from "./load.js";
import { roles, level, readable } from "./colors.js";

const EVERY = 3000, HISTORY = 12, WATCH = 500;
// After a failed read the last numbers stay, dimmed; after this long they are dropped.
const GIVE_UP = 60000;

// Calm unless it stays high: the numbers and graphs are quiet, and a metric takes the quota bars'
// scale (colors.js) only after about 30 s at 80% or more. It stops at orange: a busy machine is not a
// failure, and red stays for what needs you.
const monitor = () => exec("open", ["-a", "Activity Monitor"]).catch(() => {});
const tone = (history, pct, r) => sustained(history) ? level(pct, r, { cap: "hot" }) : null;

// The selected space's machine, as the quota mod finds it: null locally, else its name.
const currentMachine = () => spaces().find(s => s.active)?.machine ?? null;

// The last HISTORY reads as thin columns, newest on the right, tallest at 100%.
// Height carries the load; the columns take the metric's color only while it stays high.
function graph(history, r, color) {
  const cols = Array.from({ length: HISTORY }, (_, i) => history[history.length - HISTORY + i]);
  return h_flex().h(12).gap(1).items_end().flex_none()
    .children(cols.map(pct => div().w(2).rounded(1)
      .h(pct === undefined ? 1 : Math.max(1, Math.round(pct / 100 * 12)))
      .bg(pct === undefined ? r.track : color ?? r.sub)));
}

export default class System extends View {
  init(_props, cx) {
    this.handle = cx.focus_handle();
    this.machine = currentMachine();
    this.reset();
    this.refresh(cx);
    cx.timer.every(EVERY, cx => this.refresh(cx));
    // Switching spaces needs no exec to notice, so check often and read the new machine at once.
    cx.timer.every(WATCH, cx => {
      const m = currentMachine();
      if (m === this.machine) return;
      this.machine = m;
      this.reset();
      cx.notify();
      this.refresh(cx);
    });
  }

  // pane.go can land here, as on the runners chip: Enter or Space does what a click does.
  focus() { this.handle?.focus(); }

  key(e, cx, act) {
    const k = e.keystroke;
    if (k === "enter" || k === "space") act();
    else if (k === "escape") run("pane.go", { direction: "up" });
    else if (["up", "left", "right"].includes(k)) run("pane.go", { direction: k });
    else return;
    cx.stop_propagation();
  }

  // One machine's graphs never mix with another's.
  reset() {
    this.history = { cpu: [], ram: [], gpu: [] };
    this.last = null;
    this.at = null;
    this.error = null;
  }

  refresh(cx) {
    if (this.loading) return;
    this.loading = true;
    const machine = this.machine;
    cx.spawn(async cx => {
      try {
        const now = await readLoad(exec, machine);
        // A late reply from the machine before a switch never replaces the new one's data.
        if (machine !== this.machine) return;
        this.last = now;
        this.at = Date.now();
        this.error = null;
        for (const key of ["cpu", "ram", "gpu"]) {
          if (!now[key]) continue;
          this.history[key].push(now[key].pct);
          if (this.history[key].length > HISTORY) this.history[key].shift();
        }
      } catch (e) {
        if (machine !== this.machine) return;
        this.error = String(e.message ?? e);
        // A machine that has not answered for a while shows as unavailable, not as old numbers.
        if (this.at && Date.now() - this.at > GIVE_UP) { this.history = { cpu: [], ram: [], gpu: [] }; this.last = null; }
      }
      finally {
        this.loading = false;
        cx.notify();
        // Switched while this read ran: read the new machine now, not on the next tick.
        if (machine !== this.machine) this.refresh(cx);
      }
    });
  }

  render(cx) {
    const t = theme(), r = roles(t, t.chrome), now = this.last, machine = this.machine;
    const canMonitor = machine === null && window()?.platform === "macos";
    // Narrow status bar: drop the graphs first, then the GPU, so CPU and RAM always show.
    // A remote machine's name takes room, and a machine without a GPU needs less.
    const label = machine ? 14 + machine.length * 7 : 0;
    const w = (this.width ?? Infinity) - label, graphs = w >= (now && !now.gpu ? 210 : 300), gpu = w >= 170;
    const where = machine ? `${machine} · ` : "";
    // A failed read keeps the last numbers, dimmed, with the error and their age in the tooltip.
    const stale = Boolean(this.error) && Boolean(now);
    const age = stale ? `Not updated for ${Math.round((Date.now() - this.at) / 1000)} s (${this.error}) · ` : "";
    const spoken = now ? ["cpu", "ram", "gpu"].filter(k => now[k]).map(k => `${k.toUpperCase()} ${Math.round(now[k].pct)}%`).join(", ") : "";
    const metric = (key, name) => {
      const m = now?.[key];
      if (now && !m) return null;
      const color = m ? tone(this.history[key], m.pct, r) : null;
      const high = color ? ` · high for ${Math.round(EVERY * 10 / 1000)} s or more` : "";
      return h_flex().id(`sys-${key}`).gap(5).items_center().flex_none().tooltip(age + where + (m?.tip ?? name) + high).when(stale, el => el.opacity(0.45))
        .child(div().text_size(11).text_color(r.hint).child(name))
        .when(graphs, el => el.child(graph(this.history[key], r, color)))
        .child(div().min_w(26).text_size(11).text_color(color ?? r.sub).child(m ? `${Math.round(m.pct)}%` : "–"));
    };
    return h_flex().id("system").size_full().px(graphs ? 12 : 8).gap(graphs ? 12 : 8).items_center().justify_end().overflow_hidden()
      .bg(t.chrome).border_t(1).border_color(t.border).text_size(11.8).text_color(r.sub)
      .on_size_change((size, cx) => { const w = size.width / (settings().zoom || 1); if (w !== this.width) { this.width = w; cx.notify(); } })
      // Activity Monitor only makes sense for this Mac.
      .when(canMonitor, el => el.cursor_pointer().on_click(() => monitor()).role("button"))
      .accessibility_label(`System load on ${machine ?? "this computer"}: ${spoken || "unavailable"}.${canMonitor ? " Enter opens Activity Monitor." : ""}`)
      .track_focus(this.handle).on_key_down((e, cx) => this.key(e, cx, () => { if (canMonitor) monitor(); }))
      .when(this.handle.is_focused(), el => el.border_2().border_color(readable(t.accent, t.chrome, 3)))
      .when(Boolean(machine), el => el.child(div().id("sys-machine").flex_none().text_size(10).font_semibold().text_color(t.accent)
        .tooltip(`Load on ${machine}, the selected space's machine`).child(machine)))
      .when(Boolean(this.error) && !now, el => el.child(div().id("sys-error").text_color(r.hint).tooltip(where + this.error).child("System load unavailable")))
      .when(!this.error || Boolean(now), el => el.children([metric("cpu", "CPU"), metric("ram", "RAM"), gpu && metric("gpu", "GPU")].filter(Boolean)))
      .child(div().w(1).h(12).ml(2).flex_none().bg(t.border));
  }
}

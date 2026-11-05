// Adapted from Lakris2407/herdr-mods 612d5e8fc8117db8a75f457b2f0ba48f50550626 (MIT; see LICENSE).
// Source: https://github.com/Lakris2407/herdr-mods/tree/612d5e8fc8117db8a75f457b2f0ba48f50550626/quota/mods/quota
// Click it for details and options. The same mod draws the popup (props().menu).
import { coordinator, elapsed, EVERY, EXPIRE, visibleWindows } from "./quota-state.js";
import { roles, level, readable } from "./colors.js";
import { View, div, svg, image } from "gpui-kit";
import { h_flex, v_flex } from "gpui-base";
import { exec, theme, settings, shared, setShared, props, openOverlay, knownAgents, nextCommand, home, run } from "herdr";

const DEFAULTS = { kinds: { session: true, weekly: true, monthly: true, model: false, unknown: true }, hidden: [], signedOut: false, names: false, remaining: false };
const ICONS = { claude: "claude", codex: "codex" };
const TINT = { claude: "#d97757" };
const KINDS = [["session", "Session limit"], ["weekly", "Weekly limit"], ["monthly", "Monthly limit"], ["model", "Model limits"], ["unknown", "Other windows"]];

// Layout props set the starting options (`{"mod": "herdr-orch", "props": {"hidden": ["openai-codex"]}}`); the popup's choices win.
function options() {
  const start = props()?.menu ? props().start ?? {} : props() ?? {}, saved = shared("herdr-orch.options") ?? {};
  return { ...DEFAULTS, ...start, ...saved, kinds: { ...DEFAULTS.kinds, ...(start.kinds ?? {}), ...(saved.kinds ?? {}) } };
}
const setOptions = change => setShared("herdr-orch.options", { ...options(), ...change });
const signedIn = p => p.windows.length > 0;
const stateKey = () => "herdr-orch.quota";

// Preserve scope and window identity when the kind alone is ambiguous.
function short(w, twin) {
  if (w.scope !== "all_models" || w.kind === "unknown") return w.label;
  if (twin) return w.id;
  if (w.kind === "session") return w.windowSeconds ? `${w.windowSeconds / 3600}h` : w.label;
  if (w.kind === "weekly") return "wk";
  if (w.kind === "monthly") return "mo";
  return (w.label ?? "model").split(" ")[0].slice(0, 6);
}

function until(iso, now) {
  const min = Math.max(0, Math.round((Date.parse(iso) - now) / 60000));
  if (min < 60) return `${min}m`;
  const h = Math.floor(min / 60);
  return h < 24 ? `${h}h ${min % 60}m` : `${Math.floor(h / 24)}d ${h % 24}h`;
}

function ago(at, now) {
  const min = Math.round((now - at) / 60000);
  return min < 1 ? "just now" : `${min}m ago`;
}

function pace(w, now) {
  const e = elapsed(w, now);
  if (e === null || e < 0.03) return null;
  const diff = Math.round(w.percentUsed - e * 100);
  return { diff, ahead: diff >= 10 && w.percentUsed >= 20 };
}

const percent = (w, remaining) => remaining ? 100 - w.percentUsed : w.percentUsed;
const percentText = (w, remaining) => `${Math.round(percent(w, remaining))}% ${remaining ? "remaining" : "used"}`;

// Calm until it matters (colors.js): green, amber from 60%, orange from 80%, red from 92%, when a limit is close.
function bar(w, width, height, now, t, remaining, r) {
  const e = elapsed(w, now), pct = Math.min(100, Math.max(0, percent(w, remaining)));
  return div().relative().w(width).h(height).flex_none().rounded(height / 2).bg(r.track)
    .child(div().absolute().left(0).top(0).h(height).w(Math.max(pct > 0 ? height : 0, width * pct / 100)).rounded(height / 2).bg(level(w.percentUsed, r)))
    .when(e !== null, el => el.child(div().absolute().top(-2).h(height + 4).w(1).left(Math.round(width * (remaining ? 1 - e : e))).bg(t.foreground).opacity(0.45)));
}

export default class Quota extends View {
  init(_props, cx) {
    // The app's program table knows each agent's icon and whether it is a one-color glyph.
    this.programs = {};
    this.quota = coordinator({ exec, home, read: () => shared(stateKey()), write: value => setShared(stateKey(), value) });
    cx.spawn(async cx => {
      const list = await knownAgents().catch(() => []);
      for (const a of list) this.programs[a.key] = a;
      cx.notify();
    });
    this.handle = cx.focus_handle();
    if (props()?.menu) { this.handle.focus(); return; }
    this.armExpiry(cx);
    this.refresh(cx);
    cx.timer.every(EVERY, cx => this.refresh(cx));
    // Key commands reach the status owner; the popup also accepts R to refresh.
    cx.spawn(async cx => {
      for (;;) {
        const c = await nextCommand();
        if (c === "menu") this.openMenu();
        if (c === "refresh") this.refresh(cx);
      }
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

  glyph(p, size, t) {
    const program = this.programs[p.adapter], icon = program?.icon ?? (ICONS[p.adapter] ? `icons/${ICONS[p.adapter]}.svg` : null);
    if (!icon) return div().size(6).rounded(3).flex_none().bg(t.dim);
    const el = TINT[p.adapter] || !program || program.tint
      ? svg(icon).size(size).flex_none().text_color(TINT[p.adapter] ?? program?.color ?? t.foreground)
      : image(icon).size(size).flex_none().rounded(3);
    return signedIn(p) ? el : el.opacity(0.35);
  }

  armExpiry(cx) {
    const at = this.quota.snapshot().at;
    if (props()?.menu || at == null || at === this.expiryAt) return;
    this.expiryAt = at;
    cx.timer.after(Math.max(0, at + EXPIRE - Date.now()), () => {
      setShared(stateKey(), { ...shared(stateKey()) });
    });
  }

  refresh(cx) {
    cx.spawn(async cx => { await this.quota.refresh(); this.armExpiry(cx); cx.notify(); });
  }

  openMenu() {
    const data = this.quota.snapshot(), o = options();
    const all = data?.providers ?? [], shown = all.filter(p => !o.hidden.includes(p.id) && (!p.local || o.signedOut));
    const details = shown.reduce((n, p) => n + 30 + p.windows.length * 42, 0);
    const toggles = (KINDS.length + all.length + 3) * 32;
    openOverlay({ mod: "herdr-orch", kind: "popup", width: 300, height: Math.min(720, 44 + details + 40 + toggles + 77), props: { menu: true, start: props() ?? {} } });
  }

  render(cx) {
    const t = theme();
    if (props()?.menu) return this.menu(t);
    this.armExpiry(cx);
    const r = roles(t, t.chrome);
    const data = this.quota.snapshot(), error = data.error, o = options(), now = Date.now();
    const stale = data.stale;
    const list = (data?.providers ?? []).filter(p => !o.hidden.includes(p.id) && (!p.local || o.signedOut));
    // Stack providers before identity can overflow; long aliases/scopes remain scrollable.
    const width = this.width ?? Infinity, need = list.reduce((n, p) => n + visibleWindows(p, o).length, 0);
    const per = need ? (width - 24 - list.length * 46) / need : Infinity;
    const barW = per >= 96 ? 34 : per >= 76 ? 20 : 0, compact = per < 76;
    const parts = [], spoken = [];
    list.forEach((p, i) => {
      if (i > 0 && !compact) parts.push(div().w(1).h(12).flex_none().bg(t.border));
      const windows = visibleWindows(p, o);
      spoken.push(windows.length ? `${p.label} ${windows.map(w => `${w.label} ${percentText(w, o.remaining)}`).join(", ")}` : `${p.label} ${p.status}`);
      parts.push(h_flex().id(`q-${p.id}`).gap(compact ? 6 : 9).items_center().flex_none()
        .when(compact, el => el.w_full().h(13).overflow_x_scroll())
        .tooltip(windows.map(w => `${p.label} ${w.label}: ${percentText(w, o.remaining)}${w.resetsAt && Date.parse(w.resetsAt) > now ? `, resets in ${until(w.resetsAt, now)}` : ""}`).join("\n") || `${p.label}: ${p.status}`)
        .child(h_flex().gap(compact ? 4 : 5).items_center().flex_none()
          .child(this.glyph(p, compact ? 11 : 12, t))
          .when(o.names || p.id !== p.adapter || !ICONS[p.adapter], el => el.child(div().text_color(signedIn(p) ? r.sub : r.hint).child(p.label))))
        .when(!signedIn(p) || p.status !== "fresh" || p.stale, el => el.child(div().text_size(11).text_color(r.hint).child(`${p.status}${p.stale ? " (stale)" : ""}`)))
        .children(windows.map(w => {
          const pc = pace(w, now);
          return h_flex().gap(compact ? 3 : 5).items_center().flex_none()
            .child(div().text_size(11).text_color(r.hint).child(short(w, windows.filter(x => x.kind === w.kind).length > 1)))
            .when(barW > 0, el => el.child(bar(w, barW, 4, now, t, o.remaining, r)))
            .child(div().min_w(24).text_size(11).text_color(w.percentUsed >= 60 ? level(w.percentUsed, r) : r.sub).child(`${Math.round(percent(w, o.remaining))}%`))
            .when(Boolean(pc?.ahead), el => el.child(div().ml(-3).text_size(10).text_color(level(Math.max(60, w.percentUsed), r)).child("▲")));
        })));
    });
    return h_flex().id("herdr-orch").size_full().px(compact ? 6 : 12).gap(compact ? 0 : 11).justify_start()
      .when(compact, el => el.flex_col().items_start().overflow_y_scroll().line_height("12px"))
      .when(!compact, el => el.items_center().overflow_x_scroll())
      .bg(t.chrome).border_t(1).border_color(t.border).text_size(compact ? 11 : 11.8).text_color(r.sub).cursor_pointer()
      .on_size_change((size, cx) => { const w = size.width / (settings().zoom || 1); if (w !== this.width) { this.width = w; cx.notify(); } })
      .tooltip(`Quota on this computer`)
      .role("button").accessibility_label(`Agent quota on this computer: ${spoken.join("; ") || "nothing to show"}. Enter opens details.`)
      .track_focus(this.handle).on_key_down((e, cx) => this.key(e, cx, () => this.openMenu()))
      .when(this.handle.is_focused(), el => el.border_2().border_color(readable(t.accent, t.chrome, 3)))
      .when(stale, el => el.opacity(0.6))
      .on_click(() => this.openMenu())
      .on_mouse_down("right", () => this.openMenu())
      .when(parts.length === 0, el => el.child(div().text_color(r.hint).child(error || (data.at != null && data.stale) ? "Quota unavailable" : data.at != null ? "No quota to show" : "Loading quota…")))
      .children(parts);
  }

  menu(t) {
    const r = roles(t, t.popover), data = this.quota.snapshot(), error = data.error, o = options(), now = Date.now();
    const all = data?.providers ?? [];
    this.menuActions = [cx => this.refresh(cx)];
    const toggle = (label, on, act, id) => {
      const index = this.menuActions.push(act) - 1;
      return h_flex().id(id).h(32).px(10).gap(10).rounded(6).items_center().cursor_pointer()
      .when(this.menuIndex === index, el => el.bg(t.hover))
      .role("check_box").accessibility_label(`${label}: ${on ? "on" : "off"}`)
      .hover(s => s.bg(t.hover)).on_click(act)
      .child(div().flex_1().text_color(on ? t.foreground : r.sub).child(label))
      .child(div().size(16).flex_none().flex().items_center().justify_center()
        .when(on, el => el.child(div().text_color(t.accent).child("✓"))));
    };
    const heading = text => div().px(8).pt(10).pb(4).text_size(11).text_color(r.hint).child(text);
    const details = all.filter(p => !o.hidden.includes(p.id) && (!p.local || o.signedOut)).map(p => v_flex().px(8).py(4).gap(4)
      .child(h_flex().gap(6).items_center()
        .child(this.glyph(p, 13, t))
        .child(div().font_semibold().text_color(t.foreground).child(p.label))
        .child(div().text_size(11).text_color(r.hint).child(`${p.status}${p.stale ? " (stale)" : ""}`)))
      .children(p.windows.map(w => {
        const pc = pace(w, now);
        return v_flex().gap(3)
          .child(h_flex().gap(8).items_center()
            .child(div().w(62).flex_none().truncate().tooltip(w.label).text_size(12).text_color(r.sub).child(w.label))
            .child(bar(w, 120, 5, now, t, o.remaining, r))
            .child(div().flex_1().text_right().text_size(12).text_color(w.percentUsed >= 60 ? level(w.percentUsed, r) : t.foreground).child(`${Math.round(percent(w, o.remaining))}%`)))
          .child(div().pl(70).text_size(11).text_color(r.hint).child([
            w.resetsAt && Date.parse(w.resetsAt) > now ? `resets in ${until(w.resetsAt, now)}` : null,
            pc ? (Math.abs(pc.diff) < 5 ? "on pace" : pc.diff > 0 ? `${pc.diff}% ahead of pace` : `${-pc.diff}% under pace`) : null,
          ].filter(Boolean).join(" · ")));
      })));
    return v_flex().id("herdr-orch-menu").track_focus(this.handle).on_key_down((e, cx) => {
      const k = e.keystroke, n = this.menuActions.length;
      const selected = Math.min(this.menuIndex ?? 0, n - 1);
      if (k === "r") this.refresh(cx);
      else if (k === "down" || k === "tab") this.menuIndex = (selected + 1) % n;
      else if (k === "up" || k === "shift-tab") this.menuIndex = (selected + n - 1) % n;
      else if (k === "enter" || k === "space") this.menuActions[selected](cx);
      else return;
      cx.stop_propagation(); cx.notify();
    }).w_full().p(5).bg(t.popover).text_size(12).overflow_y_scrollbar()
      .child(h_flex().px(8).pt(4).pb(2).items_center()
        .child(div().flex_1().text_size(11).text_color(r.hint).child(data.at != null ? `Quota ${o.remaining ? "remaining" : "used"} · ${ago(data.at, now)}${data.stale ? " · stale" : ""}` : "Quota unavailable"))
        .child(div().id("herdr-orch-refresh").px(6).rounded(5).cursor_pointer().text_size(11).text_color(r.sub).hover(s => s.bg(t.hover))
          .when(!this.menuIndex, el => el.bg(t.hover)).role("button").accessibility_label("Refresh quota (R)")
          .on_click((_event, cx) => this.refresh(cx)).child("Refresh")))
      .child(div().px(8).py(4).text_size(11).text_color(r.sub).child(`Machine: This computer`))
      .when(Boolean(error), el => el.child(div().px(8).py(4).text_size(11).text_color(r.fail).child(error)))
      .children(details)
      .child(div().mx(8).my(6).h(1).bg(t.border))
      .child(toggle("Show remaining instead of used", o.remaining, () => setOptions({ remaining: !o.remaining }), "remaining"))
      .child(heading("Limits to show in the status bar"))
      .children(KINDS.map(([kind, label]) => toggle(label, o.kinds[kind], () => setOptions({ kinds: { ...o.kinds, [kind]: !o.kinds[kind] } }), `kind-${kind}`)))
      .child(div().mx(8).my(6).h(1).bg(t.border))
      .child(heading("Providers"))
      .children(all.map(p => toggle(p.label, !o.hidden.includes(p.id),
        () => setOptions({ hidden: o.hidden.includes(p.id) ? o.hidden.filter(x => x !== p.id) : [...o.hidden, p.id] }), `prov-${p.id}`)))
      .child(toggle("Local providers", o.signedOut, () => setOptions({ signedOut: !o.signedOut }), "signed-out"))
      .child(toggle("Provider names", o.names, () => setOptions({ names: !o.names }), "names"));
  }
}

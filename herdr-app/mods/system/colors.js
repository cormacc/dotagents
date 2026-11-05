// One color file for every mod. Each mod installs on its own, so each keeps a copy as its colors.js:
// edit this file, then `./sync.sh` copies it into every mod that has one (a test fails on a stale copy).
// Pure, so `node --test` loads it.
//
// Themes set `muted` and `dim` for their own taste, and some put them below 4.5:1; `readable` lifts a
// color toward the foreground pole just enough to reach a contrast floor, and leaves it alone when it
// already does. `roles` gives each meaning one color, the same in every mod.

// [r, g, b, alpha 0..1], or null when it is not a hex color.
function parse(hex) {
  const m = /^#([0-9a-f]{3,8})$/i.exec(String(hex ?? "").trim());
  if (!m) return null;
  let h = m[1];
  if (h.length === 3 || h.length === 4) h = [...h].map((c) => c + c).join("");
  if (h.length !== 6 && h.length !== 8) return null;
  return [0, 2, 4, 6].map((i, n) => (n < 3 ? parseInt(h.slice(i, i + 2), 16) : h.length === 8 ? parseInt(h.slice(6, 8), 16) / 255 : 1));
}

const hex = (rgb) => `#${rgb.map((v) => Math.round(v).toString(16).padStart(2, "0")).join("")}`;

// A see-through color as it shows on its background (taken as opaque), so its contrast is the one you see.
const over = ([r, g, b, a], [br, bg, bb]) => [r, g, b].map((v, i) => v * a + [br, bg, bb][i] * (1 - a));

// A color with alpha as it shows over an opaque `under`, such as a `border` of "#ffffff18";
// opaque colors come back as they are.
export function flatten(color, under) {
  const c = parse(color), u = parse(under);
  if (!c || !u || c[3] === 1) return color;
  return hex(over(c, u));
}

function luminance([r, g, b]) {
  const lin = (v) => { v /= 255; return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4; };
  return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
}

// WCAG contrast ratio between two colors, or null when either is not a hex color.
export function contrast(a, b) {
  const x = parse(a), y = parse(b);
  if (!x || !y) return null;
  const [hi, lo] = [luminance(over(x, y)), luminance(y)].sort((p, q) => q - p);
  return (hi + 0.05) / (lo + 0.05);
}

// Whether a background is light, so a color pair can pick the variant that separates on it.
export const isLight = (bg) => { const b = parse(bg); return Boolean(b) && luminance(b) >= 0.5; };

// `fg` itself when it reaches `min` on `bg`; otherwise the least lift toward white (dark `bg`) or
// black (light `bg`) that does. Colors it cannot read come back unchanged. Views ask for the same few
// pairs on every render, so each answer is kept (a theme has a few dozen pairs).
const seen = new Map();
export function readable(fg, bg, min) {
  const key = `${fg}|${bg}|${min}`;
  if (seen.has(key)) return seen.get(key);
  if (seen.size > 500) seen.clear();
  const out = lift(fg, bg, min);
  seen.set(key, out);
  return out;
}

function lift(fg, bg, min) {
  const p = parse(fg), b = parse(bg);
  if (!p || !b || contrast(fg, bg) >= min) return fg;
  const f = over(p, b); // a see-through color is lifted from what it shows as, and comes back opaque
  const pole = luminance(b) < 0.5 ? [255, 255, 255] : [0, 0, 0];
  const at = (k) => hex(f.map((v, i) => v + (pole[i] - v) * k));
  let lo = 0, hi = 1;
  for (let i = 0; i < 12; i++) {
    const mid = (lo + hi) / 2;
    if (contrast(at(mid), bg) >= min) hi = mid; else lo = mid;
  }
  return at(hi);
}

// The status scale, one hue per meaning. Fixed rather than the theme's ANSI colors, because a theme's
// "bright yellow" or "red" can sit anywhere; each step differs in lightness as well as hue.
const SCALE = {
  dark: { ok: "#5ed49a", warn: "#f2c14e", hot: "#f59152", fail: "#f2665c" },
  light: { ok: "#1f9d63", warn: "#c98a0b", hot: "#dd6b20", fail: "#d64545" },
};

// Every meaning's color on one background `bg` (the theme's `chrome` for bars, `popover` for menus...).
//   text  primary text           sub   secondary text        hint  labels and quiet text
//   ok    healthy, done          warn  waiting, slow, stale   hot   busy or high, not broken
//   fail  failed, offline, needs you now: the only red, so red always means act
//   line  the edge of a control (3:1)                         track the empty part of a bar (3:1)
export function roles(t, bg = t.background) {
  const s = isLight(bg) ? SCALE.light : SCALE.dark;
  const lift = (c, min = 4.5) => readable(c, bg, min);
  return {
    text: t.foreground,
    sub: lift(t.muted),
    hint: lift(t.dim),
    ok: lift(s.ok),
    warn: lift(s.warn),
    hot: lift(s.hot),
    fail: lift(s.fail),
    line: lift(flatten(t.border, bg), 3),
    track: lift(flatten(t.dim, bg), 3),
  };
}

// A percent on the scale: ok, warn from 60, hot from 80, fail from 92. `cap: "hot"` stops below red,
// for a number that is high but is not a failure (a busy CPU while agents work).
export function level(pct, r, { cap = "fail" } = {}) {
  const step = pct >= 92 && cap === "fail" ? "fail" : pct >= 80 ? "hot" : pct >= 60 ? "warn" : "ok";
  return r[step];
}

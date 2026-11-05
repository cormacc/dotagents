// Adapted UI source: Lakris2407/herdr-mods 612d5e8fc8117db8a75f457b2f0ba48f50550626 (MIT).
export const EVERY = 60000, EXPIRE = 300000;
const number = n => Number.isFinite(n) && n >= 0 && n <= 100;
const text = s => typeof s === "string" && s.length > 0;
const date = s => typeof s === "string"
  && /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/.test(s)
  && Number.isFinite(Date.parse(s));
const bad = () => { throw new Error("Invalid quota output"); };

// Copy only presentation fields; never expose command output or arbitrary error strings.
export function parseQuota(stdout) {
  const data = JSON.parse(stdout);
  if (data?.schema !== "herdr-orch/v1" || data.ok !== true || !Array.isArray(data.result?.providers)) bad();
  const seen = new Set();
  return data.result.providers.map(p => {
    if (!text(p.localProvider) || seen.has(p.localProvider) || !Array.isArray(p.windows)
      || (p.provider != null && !text(p.provider)) || (p.local != null && typeof p.local !== "boolean")
      || !["fresh", "stale", "auth_required", "rate_limited", "unavailable"].includes(p.state?.status)
      || typeof p.state.stale !== "boolean") bad();
    seen.add(p.localProvider);
    const windows = p.windows.map(w => {
      if (!text(w.id) || !text(w.kind) || !text(w.scope) || !number(w.percentUsed) || !number(w.percentRemaining)
        || Math.abs(w.percentUsed + w.percentRemaining - 100) > 0.001) bad();
      const duration = Number.isInteger(w.windowSeconds) && w.windowSeconds > 0 ? w.windowSeconds : null;
      const label = w.scope !== "all_models" ? `${w.scope} · ${w.id}${duration ? ` (${duration}s)` : ""}` : w.kind === "unknown"
        ? `${w.id}${duration ? ` (${duration}s)` : ""}`
        : w.kind === "session" && duration ? `${duration / 3600} hours` : w.kind;
      return { id: w.id, kind: w.kind, scope: w.scope, label, percentUsed: w.percentUsed,
        windowSeconds: duration, resetsAt: date(w.resetsAt) ? w.resetsAt : null };
    });
    return { id: p.localProvider, adapter: p.provider ?? null, label: p.localProvider,
      status: p.state.status, stale: p.state.stale, local: p.local === true,
      windows: p.state.status === "auth_required" ? [] : windows };
  });
}

export function snapshot(state, now) {
  const expired = state?.at == null || now - state.at >= EXPIRE;
  return { providers: expired ? [] : state.providers, at: state?.at,
    stale: Boolean(state?.error) || expired, error: state?.error ?? null, running: Boolean(state?.running) };
}

// Shared storage is the cross-copy lock: popup and status cannot start competing commands.
// The native exec limit is 30 seconds; no UI timer pretends to cancel a process.
export function coordinator({ exec, home, read, write, clock = Date.now }) {
  let pending;
  return {
    refresh() {
      if (pending) return pending;
      const previous = read() ?? {};
      if (previous.running && clock() - previous.startedAt <= 30000) return Promise.resolve();
      write({ ...previous, running: true, startedAt: clock() });
      pending = Promise.resolve().then(async () => {
        try {
          const r = await exec(home() + "/.agents/skills/herdr-orch/scripts/oh", ["usage", "--format", "json"], { cwd: home() });
          if (r.code !== 0) { write({ ...read(), error: "Command failed", running: false }); return; }
          let providers;
          try { providers = parseQuota(r.stdout); }
          catch (_) { write({ ...read(), error: "Invalid quota output", running: false }); return; }
          write({ providers, at: clock(), error: null, running: false });
        } catch (_) {
          write({ ...read(), error: "Command unavailable or timed out", running: false });
        } finally { pending = null; }
      });
      return pending;
    },
    snapshot() { return snapshot(read(), clock()); },
  };
}

export function elapsed(w, now) {
  if (!w.windowSeconds || !w.resetsAt) return null;
  return Math.min(1, Math.max(0, 1 - (Date.parse(w.resetsAt) - now) / (w.windowSeconds * 1000)));
}
export const visibleWindows = (p, o) => p.windows.filter(w => o.kinds[w.kind] ?? true);

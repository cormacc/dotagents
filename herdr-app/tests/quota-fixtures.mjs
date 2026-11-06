export const window = { id: "five_hour", kind: "session", scope: "all_models", percentUsed: 25, percentRemaining: 75, windowSeconds: 18000, resetsAt: "2030-01-01T05:00:00Z" };
export const modelWindows = [
  { ...window, id: "extra:0:primary_window", kind: "model", scope: "model:sol", windowSeconds: 18000, resetsAt: null },
  { ...window, id: "extra:0:secondary_window", kind: "model", scope: "model:sol", percentUsed: 80, percentRemaining: 20, windowSeconds: 18000, resetsAt: null },
  { ...window, id: "extra:1:primary_window", kind: "model", scope: "model:sol", windowSeconds: null, resetsAt: null },
  { ...window, id: "extra:1:secondary_window", kind: "model", scope: "model:sol", windowSeconds: null, resetsAt: null },
];
export const row = (localProvider = "work", changes = {}) => ({ localProvider, provider: "claude", state: { status: "fresh", stale: false }, windows: [{ ...window }], ...changes });
export const namedAccounts = [
  row("anthropic-1", { baseProvider: "anthropic", accountId: "anthropic-1",
    windows: [{ ...window, percentUsed: 1, percentRemaining: 99 }] }),
  row("anthropic-2", { baseProvider: "anthropic", accountId: "anthropic-2",
    windows: [{ ...window, percentUsed: 99, percentRemaining: 1 }] }),
];
export const envelope = (providers = [row()]) => ({ schema: "herdr-orch/v1", ok: true, result: { providers } });
export const response = (providers) => ({ code: 0, stdout: JSON.stringify(envelope(providers)), stderr: "" });

/**
 * Derive the extension name from its module URL.
 *
 * - `extensions/my-ext/index.ts`  → `"my-ext"`   (parent directory name)
 * - `extensions/my-ext.ts`        → `"my-ext"`    (filename without extension)
 */
import { basename, dirname } from "node:path";
import { fileURLToPath } from "node:url";
import type { ExtensionAPI } from "@mariozechner/pi-coding-agent";
import { truncateToWidth, visibleWidth } from "@mariozechner/pi-tui";

/**
 * Pad or truncate an ANSI-styled string to exactly `width` visible columns.
 * If the string is longer, it is truncated with an ellipsis.
 * If shorter, it is right-padded with spaces.
 */
export function ansiPad(s: string, width: number): string {
  const vis = visibleWidth(s);
  if (vis >= width) return truncateToWidth(s, width, "…", true);
  return s + " ".repeat(width - vis);
}

export function getExtensionName(importMetaUrl: string): string {
  const filePath = fileURLToPath(importMetaUrl);
  const fileName = basename(filePath, ".ts");
  if (fileName === "index") {
    return basename(dirname(filePath));
  }
  return fileName;
}

// ── Leader-menu registration ────────────────────────────────────────────────
//
// Helper for contributing leader-chord entries to the `leader-menu`
// extension. The verb "register" matches pi's existing extension-API
// pattern (`registerCommand`, `registerTool`, `registerShortcut`).
// Internally emits `leader-menu:register` and listens for
// `leader-menu:ready` so contributions survive reloads.

/**
 * A single leader-menu item: a leaf with an action, or a sub-menu with
 * nested items. Mirrors the JSON shape of `leader-menu/defaults.json`.
 */
export interface LeaderMenuItem {
  label: string;
  action?: string;
  items?: Record<string, LeaderMenuItem>;
}

/**
 * A contribution into one of the two leader slots. The trigger key
 * itself is owned by `leader-menu` and may be reconfigured by the
 * user via `~/.pi/agent/leader-menu.json`; consumers never specify
 * trigger keys directly.
 */
export interface LeaderMenuRoot {
  /** Override the slot's title in the overlay. Optional. */
  label?: string;
  /** Top-level chord items contributed to this slot. */
  items?: Record<string, LeaderMenuItem>;
}

/**
 * Payload accepted by the `leader-menu:register` event. The `source`
 * field is injected automatically by `registerLeaderMenu()`.
 *
 * Two abstract slots:
 *   - `globalMenu` — default trigger `Space`. The primary leader for
 *     extension-specific chords.
 *   - `localMenu` — default trigger `,`. Reserved for pi-agent quick
 *     actions (model swap, thinking toggle, etc.). Most consumers
 *     contribute to `globalMenu` only.
 */
export interface LeaderMenuRegistration {
  source?: string;
  globalMenu?: LeaderMenuRoot;
  localMenu?: LeaderMenuRoot;
}

/**
 * Tracks active subscriptions so repeated calls for the same extension
 * automatically clean up the previous listener (safe across reloads).
 */
const activeLeaderMenuSubs = new Map<string, () => void>();

/**
 * Register leader-menu entries with the `leader-menu` extension.
 *
 * Handles the full lifecycle:
 *  - Emits the registration immediately (in case `leader-menu` is
 *    already loaded).
 *  - Subscribes to `leader-menu:ready` so the registration is re-sent
 *    when leader-menu re-initialises (e.g. after `/reload`).
 *  - Automatically unsubscribes any prior listener for the same
 *    extension name, preventing duplicate callbacks on reload.
 *
 * Returns a cleanup function that unsubscribes the listener. Call it
 * from your `session_shutdown` handler.
 *
 * @example
 * ```ts
 * let cleanupKb: (() => void) | null = null;
 *
 * pi.on("session_start", async () => {
 *   cleanupKb = registerLeaderMenu(pi, "my-ext", {
 *     globalMenu: {
 *       items: {
 *         m: {
 *           label: "+my-ext",
 *           items: {
 *             x: { label: "Do X", action: "command:/my-cmd" },
 *           },
 *         },
 *       },
 *     },
 *   });
 * });
 *
 * pi.on("session_shutdown", () => {
 *   cleanupKb?.();
 *   cleanupKb = null;
 * });
 * ```
 */
export function registerLeaderMenu(
  pi: ExtensionAPI,
  extensionName: string,
  registration: LeaderMenuRegistration,
): () => void {
  // Clean up any prior registration for this extension (handles reload).
  activeLeaderMenuSubs.get(extensionName)?.();

  // Inject source from extensionName so callers don't have to repeat it.
  const payload = { ...registration, source: extensionName };

  const register = () => {
    pi.events.emit("leader-menu:register", payload);
  };

  const unsub = pi.events.on("leader-menu:ready", register);
  register();

  const cleanup = () => {
    unsub();
    activeLeaderMenuSubs.delete(extensionName);
  };

  activeLeaderMenuSubs.set(extensionName, cleanup);
  return cleanup;
}

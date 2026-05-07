/**
 * Window manager helpers — focus windows by app ID across compositors.
 *
 * Detects the running compositor via environment variables and uses the
 * appropriate IPC command. Falls back to a no-op on unsupported compositors
 * (X11 apps should use their own focus mechanisms, e.g. Emacs's
 * select-frame-set-input-focus).
 *
 * @example
 *   import { focusWindow } from "../lib/wm.ts";
 *   await focusWindow("emacs", exec);
 */

type ExecFn = (
  cmd: string,
  args: string[],
  opts?: { timeout?: number },
) => Promise<{ stdout: string; stderr: string; code: number | null }>;

/**
 * Focus a window by its app ID (Wayland app_id / X11 class).
 *
 * Supports Sway/i3 and Hyprland. Best-effort — silently does nothing
 * if the compositor is unsupported or the command fails.
 *
 * @param appId - The app_id (Sway) or window class (Hyprland) to focus
 * @param exec  - An exec function, typically `pi.exec` or the one from EmacsclientOptions
 */
export async function focusWindow(
  appId: string,
  exec: ExecFn,
): Promise<void> {
  try {
    if (process.env.SWAYSOCK) {
      await exec("swaymsg", [`[app_id=${appId}]`, "focus"], {
        timeout: 2000,
      });
    } else if (process.env.HYPRLAND_INSTANCE_SIGNATURE) {
      await exec("hyprctl", ["dispatch", "focuswindow", `class:${appId}`], {
        timeout: 2000,
      });
    }
    // X11 / other compositors: callers should use their own focus
    // mechanism (e.g. Emacs's select-frame-set-input-focus).
  } catch {
    // Best-effort; don't break the caller's flow.
  }
}

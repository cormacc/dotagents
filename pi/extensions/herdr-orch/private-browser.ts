import type { OAuthAuth } from "@earendil-works/pi-ai";
import { spawn } from "node:child_process";

/** Return a private-window command for the supported desktop platforms. */
export function privateBrowserCommand(platform: NodeJS.Platform, url: string): [string, string[]] | undefined {
  if (platform === "linux") return ["chromium", ["--incognito", url]];
  if (platform === "darwin") return ["/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", ["--incognito", url]];
  if (platform === "win32") return ["chrome.exe", ["--incognito", url]];
  return undefined;
}

function isAnthropicAuthUrl(value: string): boolean {
  try {
    const url = new URL(value);
    return !/[\x00-\x20\x7f]/.test(value) && url.origin === "https://claude.ai"
      && url.pathname === "/oauth/authorize" && !url.username && !url.password && !url.hash;
  } catch {
    return false;
  }
}

function openPrivateBrowser(url: string, signal: AbortSignal): void {
  if (signal.aborted || !isAnthropicAuthUrl(url)) return;
  const command = privateBrowserCommand(process.platform, url);
  if (!command) return;
  try {
    // Browser lifetime must not hold login open. Errors never include the sensitive URL.
    spawn(command[0], command[1], { stdio: "ignore", detached: true, shell: false })
      .on("error", () => {})
      .unref();
  } catch {
    // The native interaction always supplies manual private-window instructions.
  }
}

/** Keep native Anthropic login/token handling, with private browser launch for browser login only. */
export function privateBrowserOAuth(oauth: OAuthAuth): OAuthAuth {
  return {
    ...oauth,
    login(interaction, options) {
      let browserLogin = false;
      return oauth.login({
        ...interaction,
        async prompt(prompt) {
          const result = await interaction.prompt(prompt);
          if (prompt.type === "select" && prompt.options.some((option) => option.id === "copy_code")
            && prompt.options.some((option) => option.id === "browser")) browserLogin = result === "browser";
          return result;
        },
        notify(event) {
          if (event.type !== "auth_url") {
            interaction.notify(event);
            return;
          }
          // Pi's auth_url consumer unconditionally opens the default browser. Native info
          // links show the exact URL without that side effect, including in headless login.
          // known-limit: info links lack the auth-URL copy shortcut; revisit when auth_url
          // supports suppressing its automatic browser launch.
          interaction.notify({
            type: "info",
            message: [event.instructions, "If no private window opens, copy this URL into a private/incognito window manually. Do not sign out of your primary browser session."].filter(Boolean).join("\n"),
            links: [{ url: event.url }],
          });
          if (browserLogin) openPrivateBrowser(event.url, interaction.signal);
        },
      }, options);
    },
  };
}

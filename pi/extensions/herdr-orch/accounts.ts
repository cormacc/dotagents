import { anthropicProvider } from "@earendil-works/pi-ai/providers/anthropic";
import type { Provider } from "@earendil-works/pi-ai";
import { getAgentDir, type ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { readFileSync } from "node:fs";
import { join } from "node:path";

export const ACCOUNT_PREFIX = "anthropic-account-";
const NAME = /^[a-z][a-z0-9_-]{0,31}$/;

export function accountId(name: string): string {
  if (!NAME.test(name)) throw new Error("Account name must be 1-32 lowercase letters, digits, _ or -, starting with a letter");
  return ACCOUNT_PREFIX + name;
}

export function isAccountId(id: string): boolean {
  return id.startsWith(ACCOUNT_PREFIX) && NAME.test(id.slice(ACCOUNT_PREFIX.length));
}

/** Reuse native Anthropic OAuth and streaming without its API-key or ambient auth methods. */
export function accountProvider(id: string): Provider<"anthropic-messages"> {
  if (!isAccountId(id)) throw new Error("Invalid Anthropic account identity");
  const base = anthropicProvider();
  return {
    ...base,
    id,
    name: id.slice(ACCOUNT_PREFIX.length),
    auth: { oauth: base.auth.oauth },
    getModels: () => base.getModels().map((model) => ({ ...model, provider: id })),
    getAllModels: () => base.getModels().map((model) => ({ ...model, provider: id })),
  };
}

/** Discover only the supported namespace; credentials stay in Pi's existing auth.json. */
export function storedAccountIds(authPath = join(getAgentDir(), "auth.json")): string[] {
  let data: unknown;
  try {
    data = JSON.parse(readFileSync(authPath, "utf8").replace(/^\uFEFF/, ""));
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return [];
    throw new Error("Cannot read Pi auth.json for account discovery");
  }
  if (!data || typeof data !== "object" || Array.isArray(data)) throw new Error("Invalid Pi auth.json");
  return Object.keys(data).filter(isAccountId).sort();
}

export function registerStoredAccounts(pi: Pick<ExtensionAPI, "registerProvider">): Set<string> {
  const registered = new Set<string>();
  for (const id of storedAccountIds()) {
    pi.registerProvider(accountProvider(id));
    registered.add(id);
  }
  return registered;
}

export function registerAccounts(pi: ExtensionAPI): void {
  const registered = registerStoredAccounts(pi);
  const add = (id: string) => {
    if (registered.has(id)) return;
    pi.registerProvider(accountProvider(id));
    registered.add(id);
  };
  pi.registerCommand("oh-account", {
    description: "Register a named Anthropic subscription: /oh-account add <name>; then use Pi /login, /model and /logout",
    handler: async (args, ctx) => {
      if (!ctx.hasUI) return;
      const parts = args.trim().split(/\s+/);
      if (parts.length !== 2 || parts[0] !== "add") {
        ctx.ui.notify("Use /oh-account add <name>, then /login to sign in, /model to select this session's account, or /logout to remove its credential.", "info");
        return;
      }
      try {
        const id = accountId(parts[1]);
        add(id);
        ctx.ui.notify(`Registered ${id}. Use /login and choose ${parts[1]}, then /model and choose ${id}/<model>. /logout removes only the chosen account's credential.`, "info");
      } catch {
        ctx.ui.notify("Account registration failed. Use a 1-32 character name starting with a lowercase letter, then lowercase letters, digits, _ or -.", "error");
      }
    },
  });
}

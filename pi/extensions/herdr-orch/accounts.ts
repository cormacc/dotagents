import { anthropicProvider } from "@earendil-works/pi-ai/providers/anthropic";
import type { Provider } from "@earendil-works/pi-ai";
import { getAgentDir, type ExtensionAPI, type ExtensionCommandContext } from "@earendil-works/pi-coding-agent";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { privateBrowserOAuth } from "./private-browser.ts";

// Pi provider IDs may contain hyphens (`openai-codex`), so the base is the text before the
// final `-<N>` and must be a provider whose accounts this module can build.
const BASES = new Set(["anthropic"]);
const ACCOUNT = /^(.+)-[1-9][0-9]*$/;
const NUMBER = /^[1-9][0-9]*$/;

/** An Anthropic account identity `anthropic-<N>`: N is a positive integer without leading zeros. */
export function accountId(number: string): string {
  if (!NUMBER.test(number)) throw new Error("Account number must be a positive integer without leading zeros");
  return `anthropic-${number}`;
}

/** Return the supported base provider of a `<provider>-<N>` account identity, else undefined. */
export function accountBase(id: string): string | undefined {
  const base = ACCOUNT.exec(id)?.[1];
  return base !== undefined && BASES.has(base) ? base : undefined;
}

export function isAccountId(id: string): boolean {
  return accountBase(id) !== undefined;
}

/** Reuse native Anthropic OAuth with private browser login, without API-key or ambient auth methods. */
export function accountProvider(id: string): Provider<"anthropic-messages"> {
  if (!isAccountId(id)) throw new Error("Invalid Anthropic account identity");
  const base = anthropicProvider();
  return {
    ...base,
    id,
    name: id,
    auth: { oauth: base.auth.oauth && privateBrowserOAuth(base.auth.oauth) },
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

export type SubcommandHandler = (args: string, ctx: ExtensionCommandContext) => Promise<void>;

const ACCOUNT_USAGE = "Use /oh account add [N] to register anthropic-<N>, then /login to sign in, /model to select this session's account, or /logout to remove its credential.";

/** Register stored accounts and return the `/oh account` handler, which `/oh` dispatches. */
export function registerAccounts(pi: ExtensionAPI): SubcommandHandler {
  const registered = registerStoredAccounts(pi);
  const add = (id: string) => {
    if (registered.has(id)) return;
    pi.registerProvider(accountProvider(id));
    registered.add(id);
  };
  return async (args, ctx) => {
    if (!ctx.hasUI) return;
    const parts = args.trim().split(/\s+/).filter(Boolean);
    if (parts.length === 0) {
      // A registered identity can lack a credential until /login, so the listing marks it.
      let stored: Set<string> | undefined;
      try { stored = new Set(storedAccountIds()); } catch { stored = undefined; }
      const rows = [...registered].sort().map((id) => stored === undefined || stored.has(id) ? id : `${id} (no credential: use /login)`);
      ctx.ui.notify(`${rows.length ? `Registered accounts: ${rows.join(", ")}.` : "No registered accounts."} ${ACCOUNT_USAGE}`, "info");
      return;
    }
    if (parts.length > 2 || parts[0] !== "add") {
      ctx.ui.notify(ACCOUNT_USAGE, "info");
      return;
    }
    try {
      let n = 1;
      while (registered.has(accountId(String(n)))) n++;
      const id = parts.length === 2 ? accountId(parts[1]) : accountId(String(n));
      add(id);
      ctx.ui.notify(`Registered ${id}. Use /login and choose ${id}, then /model and choose ${id}/<model>. /logout removes only the chosen account's credential.`, "info");
    } catch {
      ctx.ui.notify("Account registration failed. Use a positive account number without leading zeros, such as /oh account add 2.", "error");
    }
  };
}

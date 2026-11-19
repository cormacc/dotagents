// Register native account providers during extension loading, before CLI model resolution.
// No ranking or credential implementation belongs in this adapter.
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { getSupportedThinkingLevels } from "@earendil-works/pi-ai/compat";
import { writeFileSync } from "node:fs";
import { registerNativeAccounts } from "./account-registration.ts";

// One refresh must not hold up a launch; the account then stays expired and is omitted.
const REFRESH_TIMEOUT_MS = 15000;

export default async function (pi: ExtensionAPI) {
  const accounts = await registerNativeAccounts(pi);
  if (process.env.HERDR_ORCH_ADAPTER !== "model-inventory") return;
  pi.on("session_start", async (_event, ctx) => {
    const available = ctx.modelRegistry.getAvailable();
    // Pi resolves OAuth auth through its locked auth storage and refreshes an expired token
    // there, so an account that no session uses still reaches routing with a usable token.
    // A valid token is only read. A failed refresh leaves the stored credential unchanged.
    const oauthProviders = [...new Set(available.filter(model => ctx.modelRegistry.isUsingOAuth(model)).map(model => model.provider))];
    await Promise.all(oauthProviders.map(provider => Promise.race([
      ctx.modelRegistry.getProviderAuth(provider).catch(() => undefined),
      new Promise(resolve => setTimeout(resolve, REFRESH_TIMEOUT_MS).unref()),
    ])));
    const inventory = available.map(model => ({
      canonical: `${accounts.has(model.provider) ? "anthropic" : model.provider}/${model.id}`,
      account: model.provider,
      reasoning: getSupportedThinkingLevels(model),
    }));
    writeFileSync(process.env.HERDR_ORCH_ADAPTER_RESULT!, JSON.stringify({ ok: true, inventory }));
    process.exit(0);
  });
}

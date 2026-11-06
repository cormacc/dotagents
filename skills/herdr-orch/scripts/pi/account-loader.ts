// Register native account providers during extension loading, before CLI model resolution.
// No ranking or credential implementation belongs in this adapter.
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { getSupportedThinkingLevels } from "@earendil-works/pi-ai/compat";
import { writeFileSync } from "node:fs";
import { registerNativeAccounts } from "./account-registration.ts";

export default async function (pi: ExtensionAPI) {
  const accounts = await registerNativeAccounts(pi);
  if (process.env.HERDR_ORCH_ADAPTER !== "model-inventory") return;
  pi.on("session_start", (_event, ctx) => {
    const inventory = ctx.modelRegistry.getAvailable().map(model => ({
      canonical: `${accounts.has(model.provider) ? "anthropic" : model.provider}/${model.id}`,
      account: model.provider,
      reasoning: getSupportedThinkingLevels(model),
    }));
    writeFileSync(process.env.HERDR_ORCH_ADAPTER_RESULT!, JSON.stringify({ ok: true, inventory }));
    process.exit(0);
  });
}

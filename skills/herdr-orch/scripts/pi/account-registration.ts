import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { DefaultPackageManager, getAgentDir, SettingsManager } from "@earendil-works/pi-coding-agent";
import { existsSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

/** Reuse the installed extension's native registration; bare skills need no account module. */
export async function registerNativeAccounts(pi: ExtensionAPI): Promise<Set<string>> {
  const source = resolve(dirname(fileURLToPath(import.meta.url)), "../../../../pi/extensions/herdr-orch/accounts.ts");
  const installed = join(getAgentDir(), "extensions/herdr-orch/accounts.ts");
  let module = [source, installed].find(path => existsSync(path));
  if (!module) {
    const cwd = process.cwd(), agentDir = getAgentDir();
    // Isolated CLI loaders run before project trust. Resolve user resources without
    // installing missing packages or executing any of their extension factories.
    const settingsManager = SettingsManager.create(cwd, agentDir, { projectTrusted: false });
    const resources = await new DefaultPackageManager({ cwd, agentDir, settingsManager }).resolve(async () => "skip");
    module = resources.extensions
      .filter(resource => resource.enabled && resource.metadata.origin === "package"
        && /(?:^|\/)herdr-orch\/index\.(?:ts|js)$/.test(resource.path))
      .map(resource => join(dirname(resource.path), "accounts.ts"))
      .find(path => existsSync(path));
  }
  if (!module) return new Set();
  const { registerStoredAccounts } = await import(module);
  return registerStoredAccounts(pi);
}

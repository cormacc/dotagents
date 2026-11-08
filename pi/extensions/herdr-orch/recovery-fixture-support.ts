import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { parseAction } from "./help.ts";
import { registerRecovery } from "./recovery.ts";

/**
 * Install recovery with only the `/oh tier` dispatch of `index.ts`, so an in-process
 * fixture drives the shipped command syntax without the `oh` listings or tools.
 */
export function registerRecoveryCommand(pi: ExtensionAPI, execute: Parameters<typeof registerRecovery>[1]): void {
  const tier = registerRecovery(pi, execute);
  pi.registerCommand("oh", {
    handler: async (args, ctx) => {
      const action = parseAction(args);
      if (action?.action !== "tier") throw new Error(`fixture supports only /oh tier, not /oh ${args}`);
      await tier(action.rest, ctx);
    },
  });
}

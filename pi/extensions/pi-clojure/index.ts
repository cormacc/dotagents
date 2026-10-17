// SPDX-License-Identifier: EPL-2.0
// Copyright © 2026-present Marko Kocic <marko@euptera.com>

import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { createSessionClient } from "./nrepl-client";
import { createEvalTool } from "./tools/eval";
import { findNreplPortTool } from "./tools/find-nrepl-port";
import { parenRepairTool } from "./tools/paren-repair";

export default function (pi: ExtensionAPI) {
  // The client opens connections only when clojure_eval runs.
  const sessions = createSessionClient();

  pi.registerTool(createEvalTool((opts) => sessions.eval(opts)));
  pi.registerTool(findNreplPortTool);
  pi.registerTool(parenRepairTool);

  // closeAll forgets each session before it closes it, so repeated shutdowns are harmless.
  pi.on("session_shutdown", async () => {
    await sessions.closeAll();
  });
}

// Test extension for the herdr-orch Pi adapters. `./test.sh` loads it into the installed Pi
// (`pi -e run-tests.ts`); it runs every adapter suite in-process on session_start, prints the
// results and the recorded runtime identity, and ends the process before any model turn.
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { assertRuntime, expectationFromEnv, reportAndExit, runSuite, runtimeIdentity } from "./runtime.ts";
import { tests as forkTests } from "./fork-adapter.test.ts";
import { tests as forkProviderTests } from "./fork-provider-context.test.ts";
import { tests as measurementTests } from "./measurement-adapter.test.ts";

export default function (pi: ExtensionAPI) {
  pi.on("session_start", async () => {
    let identity;
    try {
      identity = assertRuntime(expectationFromEnv());
    } catch (error) {
      process.stdout.write(`FAIL runtime :: ${(error as Error).message}\nRUNTIME ${JSON.stringify(runtimeIdentity())}\n`);
      process.exit(2);
    }
    const only = process.env.HERDR_ORCH_ADAPTER_SUITES?.split(/\s+/).filter(Boolean);
    const suites: [string, typeof forkTests][] = [["fork-adapter", forkTests], ["fork-provider-context", forkProviderTests], ["measurement-adapter", measurementTests]];
    const results = [];
    for (const [name, tests] of suites) if (!only || only.includes(name)) results.push(await runSuite(name, tests));
    reportAndExit(identity, results);
  });
}

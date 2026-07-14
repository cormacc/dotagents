#!/usr/bin/env tsx
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { createEvalTool } from "./eval.ts";
import { createFindNreplPortTool } from "./find-nrepl-port.ts";

async function main() {
const evalTool = createEvalTool(async () => {
  throw new Error("connection refused");
});

try {
  await evalTool.execute("test", { code: "(+ 1 1)", port: 1 }, undefined, undefined, {} as any);
  throw new Error("clojure_eval returned instead of throwing");
} catch (error) {
  const message = error instanceof Error ? error.message : String(error);
  if (!message.includes("Clojure eval failed") || !message.includes("connection refused")) {
    throw new Error(`clojure_eval lost diagnostic: ${message}`);
  }
  console.log("ok - clojure_eval throws a useful failure");
}

const cwd = mkdtempSync(join(tmpdir(), "pi-clojure-failure-"));
try {
  writeFileSync(join(cwd, ".nrepl-port"), "65530\n", "utf8");
  const findTool = createFindNreplPortTool({
    cwd: () => cwd,
    validate: async () => false,
    defaultPorts: [],
  });
  try {
    await findTool.execute("test", {}, undefined, undefined, {} as any);
    throw new Error("clojure_find_nrepl_port returned instead of throwing");
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    if (!message.includes("Clojure nREPL discovery failed") || !message.includes(cwd)) {
      throw new Error(`clojure_find_nrepl_port lost diagnostic: ${message}`);
    }
    console.log("ok - clojure_find_nrepl_port throws a useful failure");
  }
} finally {
  rmSync(cwd, { recursive: true, force: true });
}
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});

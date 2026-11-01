#!/usr/bin/env tsx
import registerDataspex, { discoverPort } from "./index.ts";
import assert from "node:assert/strict";
import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { join } from "node:path";

let registered: any;
registerDataspex({
  registerTool(tool: unknown) {
    registered = tool;
  },
} as any);

if (!registered) throw new Error("dataspex tool was not registered");

const opSchema = registered.parameters.properties.op;
if (!Array.isArray(opSchema.enum) || opSchema.enum.join(",") !== "labels,value,history,track,untrack,db_query,actions_tail") {
  throw new Error(`dataspex op is not a StringEnum-compatible schema: ${JSON.stringify(opSchema)}`);
}

async function main() {
  const root = await mkdtemp(join(fileURLToPath(new URL("../../../.tmp/", import.meta.url)), "dataspex-ports-"));
  try {
    await mkdir(join(root, ".shadow-cljs"));
    await writeFile(join(root, ".nrepl-port"), "7888");
    await writeFile(join(root, ".shadow-cljs/nrepl.port"), "9000");
    assert.equal(await discoverPort(root), 9000, "Dataspex prefers shadow-cljs");
    await writeFile(join(root, ".shadow-cljs/nrepl.port"), "9000junk");
    assert.equal(await discoverPort(root), 7888, "invalid files do not mask a later valid port");
    console.log("ok - Dataspex preserves shadow-cljs precedence and rejects malformed ports");
  } finally {
    await rm(root, { recursive: true, force: true });
  }
try {
  await registered.execute("test", { op: "value" }, undefined, undefined, { cwd: process.cwd() });
  throw new Error("dataspex failure returned instead of throwing");
} catch (error) {
  const message = error instanceof Error ? error.message : String(error);
  if (!message.includes("Dataspex failed") || !message.includes("label is required")) {
    throw new Error(`dataspex failure lost its diagnostic: ${message}`);
  }
}

console.log("ok - dataspex throws useful tool failures and exposes a provider-compatible enum");
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});

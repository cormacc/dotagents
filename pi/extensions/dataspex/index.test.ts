#!/usr/bin/env tsx
import registerDataspex from "./index.ts";

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

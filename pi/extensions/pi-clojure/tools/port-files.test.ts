import assert from "node:assert/strict";
import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { readNreplPortFile } from "../port-files.ts";
import { createFindNreplPortTool } from "./find-nrepl-port.ts";

async function main() {
  const root = await mkdtemp(join(fileURLToPath(new URL("../../../../.tmp/", import.meta.url)), "port-files-"));
  try {
    const file = join(root, ".nrepl-port");
    for (const [input, expected] of [["1", 1], [" 65535\n", 65535], ["0", null], ["65536", null], ["7888junk", null], ["", null], ["3.5", null]] as const) {
      await writeFile(file, input);
      assert.equal(await readNreplPortFile(file), expected, input);
    }
    assert.equal(await readNreplPortFile(join(root, "missing")), null);
    await mkdir(join(root, ".shadow-cljs"));
    await writeFile(file, "7888");
    await writeFile(join(root, ".shadow-cljs/nrepl.port"), "9000");
    const calls: number[] = [];
    const tool = createFindNreplPortTool({ cwd: () => root, validate: async (_host, port) => { calls.push(port); return true; } });
    await tool.execute("test", {}, undefined, undefined, {} as any);
    assert.deepEqual(calls, [7888], "generic discovery preserves its port-file precedence");
    console.log("ok - shared port parsing, boundaries, missing files, and caller-specific precedence");
  } finally {
    await rm(root, { recursive: true, force: true });
  }
}
main().catch((error) => { console.error(error); process.exitCode = 1; });

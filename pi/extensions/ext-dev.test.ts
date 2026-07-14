#!/usr/bin/env tsx
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import {
  AGGREGATE_LIMIT_BYTES,
  PER_FILE_LIMIT_BYTES,
  buildContext,
  parseExtArgs,
  readExtensionSource,
} from "./ext-dev.ts";

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

const root = mkdtempSync(join(tmpdir(), "ext-dev-test-"));
const extensions = join(root, "extensions");
try {
  mkdirSync(join(extensions, "small"), { recursive: true });
  writeFileSync(join(extensions, "small", "index.ts"), "export default 1;\n");
  writeFileSync(join(extensions, "small", "index.test.ts"), "throw new Error('test');\n");
  writeFileSync(join(extensions, "small", "package-lock.json"), "{}\n");

  const small = readExtensionSource(root, "small");
  assert(small?.files.map((file) => file.path).join(",") === "index.ts", "small default should load production source only");
  assert(small.omitted.some((item) => item.path === "index.test.ts"), "small default should report omitted tests");
  console.log("ok - small extension defaults to production source and reports omissions");

  mkdirSync(join(extensions, "tasks"), { recursive: true });
  for (let index = 0; index < 6; index++) {
    writeFileSync(join(extensions, "tasks", `source-${index}.ts`), `${index}`.repeat(40 * 1024));
  }
  writeFileSync(join(extensions, "tasks", "large.test.ts"), "x".repeat(40 * 1024));
  const large = readExtensionSource(root, "tasks");
  assert(large !== null, "large extension should resolve");
  assert(large.totalBytes <= AGGREGATE_LIMIT_BYTES, "aggregate ceiling exceeded");
  assert(large.files.every((file) => file.includedBytes <= PER_FILE_LIMIT_BYTES), "per-file ceiling exceeded");
  assert(large.files.some((file) => file.truncated), "large files should be marked truncated");
  assert(large.omitted.some((item) => item.reason === "aggregate limit"), "aggregate omissions should be reported");
  assert(Buffer.byteLength(buildContext("tasks", large), "utf8") <= AGGREGATE_LIMIT_BYTES, "complete injected context exceeded aggregate ceiling");
  console.log("ok - large tasks-like extension is bounded and reports truncation");

  const explicit = readExtensionSource(root, "small", { files: ["index.test.ts"] });
  assert(explicit?.selection === "explicit", "explicit selection should be identified");
  assert(explicit.files.length === 1 && explicit.files[0]?.path === "index.test.ts", "explicit selection should load a test file");
  console.log("ok - explicit --files selection can opt into tests");

  const parsed = parseExtArgs("small --include-tests --files index.ts,index.test.ts diagnose loading");
  assert(parsed.name === "small" && parsed.selection.includeTests === true, "command options should parse");
  assert(parsed.selection.files?.length === 2 && parsed.instruction === "diagnose loading", "explicit files and instruction should parse");
  console.log("ok - /ext option parsing is deterministic");
} finally {
  rmSync(root, { recursive: true, force: true });
}

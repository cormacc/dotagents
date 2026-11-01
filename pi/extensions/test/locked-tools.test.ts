import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { chmodSync, copyFileSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const repo = fileURLToPath(new URL("../../../", import.meta.url));
const root = mkdtempSync(join(repo, ".tmp/locked-tools-"));
try {
  const support = join(root, "pi/extensions/test/support");
  const bin = join(root, "node_modules/.bin");
  const ambient = join(root, "ambient");
  mkdirSync(support, { recursive: true });
  mkdirSync(bin, { recursive: true });
  mkdirSync(ambient);
  const helper = join(support, "locked-tools.sh");
  copyFileSync(join(repo, "pi/extensions/test/support/locked-tools.sh"), helper);
  for (const [path, value] of [[join(bin, "tsx"), "locked"], [join(ambient, "tsx"), "ambient"]]) {
    writeFileSync(path, `#!/bin/sh\nprintf '%s\\n' '${value}'\n`);
    chmodSync(path, 0o755);
  }
  const run = () => spawnSync("bash", ["-c", 'source "$1"; run_tsx', "test", helper], {
    encoding: "utf8", env: { ...process.env, PATH: `${ambient}:${process.env.PATH}` },
  });
  const pass = run();
  assert.equal(pass.status, 0, pass.stderr);
  assert.equal(pass.stdout.trim(), "locked", "ambient tsx cannot override the lockfile");
  rmSync(join(bin, "tsx"));
  const fail = run();
  assert.equal(fail.status, 1, "missing locked dependency fails instead of using ambient tsx");
  assert.match(fail.stderr, /locked tsx not found.*run npm ci/);
  console.log("ok - locked launcher selects the local binary and rejects missing dependencies");
} finally {
  rmSync(root, { recursive: true, force: true });
}

import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync, mkdtempSync, mkdirSync, writeFileSync, symlinkSync, rmSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { resolve, join } from "node:path";

const layout = JSON.parse(readFileSync("herdr-app/layouts/herdr-orch.json", "utf8"));
const wiringPath = process.env.HERDR_APP_WIRING;
const leaves = ["mods/herdr-orch", "mods/system", "mods/org-tasks", "layouts/herdr-orch.json"];

function validateLayout(value) {
  const work = value.children.find(x => x.id === "work");
  const status = work.children.find(x => x.id === "status");
  assert.equal(status.type, "row");
  assert.deepEqual(status.children.map(x => x.content.mod), ["workspace-status", "system", "herdr-orch"]);
  // The task widget sits between the tab area and the status row; its id is the
  // `layout.toggle` target that the README's key binding names.
  const ids = work.children.map(x => x.id);
  assert.deepEqual(ids.slice(ids.indexOf("work-body")), ["work-body", "org-tasks", "status"]);
  assert.equal(work.children.find(x => x.id === "org-tasks").content.mod, "org-tasks");
  const text = JSON.stringify(value);
  for (const name of ["workspace-chrome", "spaces", "agents", "tabs", "pane", "files", "changes"])
    assert.ok(text.includes(`"mod":"${name}"`), name);
}

function validateWiring(value) {
  for (const leaf of leaves) {
    assert.ok(value.includes(`/.config/herdr-app/${leaf}" = link "\${agentsRoot}/herdr-app/${leaf}";`), leaf);
    assert.ok(value.includes(`check_herdr_app_leaf "\${config.home.homeDirectory}/.config/herdr-app/${leaf}" "\${agentsRoot}/herdr-app/${leaf}"`), leaf);
  }
  assert.ok(value.includes('lib.hm.dag.entryBefore [ "writeBoundary" ]'));
  // keys.json is a macOS-only leaf with a platform-named source.
  const keys = ['"${config.home.homeDirectory}/.config/herdr-app/keys.json"', '"${agentsRoot}/herdr-app/keys.darwin.json"'];
  assert.ok(value.includes(`${keys[0]} = link ${keys[1]};`), "keys link");
  assert.ok(value.includes(`keys=${keys[0]}`) && value.includes(`check_herdr_app_leaf "$keys" ${keys[1]}`), "keys guard");
}

test("layout validator rejects missing status", () => {
  validateLayout(layout);
  const bad = structuredClone(layout);
  bad.children.find(x => x.id === "work").children.find(x => x.id === "status").children.pop();
  assert.throws(() => validateLayout(bad));
});

test("layout validator rejects a missing task widget", () => {
  const bad = structuredClone(layout);
  const work = bad.children.find(x => x.id === "work");
  work.children = work.children.filter(x => x.id !== "org-tasks");
  assert.throws(() => validateLayout(bad));
});

test("parent wiring validators reject missing leaf", { skip: wiringPath === undefined }, () => {
  const wiring = readFileSync(wiringPath, "utf8");
  validateWiring(wiring);
  assert.throws(() => validateWiring(wiring.replace('/.config/herdr-app/mods/system" = link', '/.config/herdr-app/mods/other" = link')));
  assert.throws(() => validateWiring(wiring.replace('herdr-app/keys.darwin.json";', 'herdr-app/keys.json";')));
});

test("collision guard rejects foreign files, directories and dangling links", { skip: wiringPath === undefined }, () => {
  const wiring = readFileSync(wiringPath, "utf8");
  const match = wiring.match(/check_herdr_app_leaf\(\) \{([\s\S]*?)\n        \}/);
  assert.ok(match);
  const dir = mkdtempSync(resolve(".tmp/herdr-leaves-"));
  const fn = `check_herdr_app_leaf() {${match[1]}\n}`
    .replaceAll('${pkgs.coreutils}/bin/readlink', 'readlink')
    .replaceAll('${builtins.storeDir}', dir);
  try {
    const source = join(dir, "source"), target = join(dir, "target");
    writeFileSync(source, "same");
    const check = () => spawnSync("bash", ["-c", `${fn}\ncheck_herdr_app_leaf "$1" "$2"`, "guard", target, source], { encoding: "utf8" });
    assert.equal(check().status, 0);
    writeFileSync(target, "same");
    assert.equal(check().status, 1);
    rmSync(target);
    mkdirSync(target);
    assert.equal(check().status, 1);
    rmSync(target, { recursive: true });
    symlinkSync(join(dir, "missing"), target);
    assert.equal(check().status, 1);
    rmSync(target);
    symlinkSync(source, target);
    assert.equal(check().status, 1);
    rmSync(target);
    const generation = join(dir, "fixture-home-manager-files");
    mkdirSync(generation);
    symlinkSync(source, join(generation, "entry"));
    symlinkSync(join(generation, "entry"), target);
    assert.equal(check().status, 0);
    const alias = join(dir, "alias");
    symlinkSync(dir, alias);
    assert.equal(spawnSync("bash", ["-c", `${fn}\ncheck_herdr_app_leaf "$1" "$2"`, "guard", target, join(alias, "source")]).status, 0);
    for (const failedPath of [target, source]) {
      for (const outcome of ["return 1", "return 0"]) {
        const override = `readlink() { if [ "$1" = -f ] && [ "$2" = "$FAIL_PATH" ]; then ${outcome}; fi; command readlink "$@"; }`;
        assert.equal(spawnSync("bash", ["-c", `${override}\n${fn}\ncheck_herdr_app_leaf "$1" "$2"`, "guard", target, source], { env: { ...process.env, FAIL_PATH: failedPath } }).status, 1);
      }
    }
    rmSync(source);
    assert.equal(check().status, 1);
    assert.equal(spawnSync("bash", ["-c", `${fn}\ncheck_herdr_app_leaf "$1" "$2"`, "guard", target, ""]).status, 1);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

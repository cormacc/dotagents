import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync, mkdtempSync, mkdirSync, writeFileSync, symlinkSync, rmSync } from "node:fs";
import { spawnSync } from "node:child_process";
import { resolve, join } from "node:path";

const layout = JSON.parse(readFileSync("herdr-app/layouts/herdr-orch.json", "utf8"));
const wiringPath = process.env.HERDR_APP_WIRING;
const leaves = ["mods/herdr-orch", "mods/system", "mods/org-tasks", "mods/orch-spaces", "layouts/herdr-orch.json"];

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
  // One sidebar list holds spaces and their agents.
  const sidebar = value.children.find(x => x.id === "sidebar");
  assert.equal(sidebar.children.find(x => x.id === "spaces").content.mod, "orch-spaces");
  const text = JSON.stringify(value);
  for (const name of ["workspace-chrome", "tabs", "pane", "files", "changes"])
    assert.ok(text.includes(`"mod":"${name}"`), name);
  for (const name of ["spaces", "agents"])
    assert.ok(!text.includes(`"mod":"${name}"`), `${name} is replaced by orch-spaces`);
}

// One `herdrAppLeaves` list drives both the links and the activation guard.
function leafList(value) {
  const block = value.match(/herdrAppLeaves = \[([\s\S]*?)\];/);
  assert.ok(block, "herdrAppLeaves list");
  return [...block[1].matchAll(/"([^"]+)"/g)].map(m => m[1]);
}

function validateWiring(value) {
  assert.deepEqual(leafList(value).toSorted(), leaves.toSorted(), "leaf list");
  assert.ok(value.includes('herdrAppConfig = "${config.home.homeDirectory}/.config/herdr-app";'), "app config");
  assert.match(value, /lib\.nameValuePair "\$\{herdrAppConfig\}\/\$\{leaf\}" \(link "\$\{agentsRoot\}\/herdr-app\/\$\{leaf\}"\)\)\s+herdrAppLeaves/, "leaf links");
  assert.match(value, /check_herdr_leaf app "\$\{herdrAppConfig\}\/\$\{leaf\}" "\$\{agentsRoot\}\/herdr-app\/\$\{leaf\}"\s+''\) herdrAppLeaves/, "leaf guard");
  assert.ok(value.includes('lib.hm.dag.entryBefore [ "writeBoundary" ]'));
  // keys.json is linked on every platform, from that platform's key table.
  const keys = ['"${config.home.homeDirectory}/.config/herdr-app/keys.json"', '"${agentsRoot}/herdr-app/keys.${herdrAppKeysPlatform}.json"'];
  assert.ok(value.includes('herdrAppKeysPlatform = if pkgs.stdenv.hostPlatform.isDarwin then "darwin" else "linux";'), "keys platform");
  assert.ok(value.includes(`${keys[0]} = link ${keys[1]};`), "keys link");
  assert.ok(value.includes('keys="${herdrAppConfig}/keys.json"') && value.includes(`check_herdr_leaf app "$keys" ${keys[1]}`), "keys guard");
}

test("each platform has a key table that binds the org-tasks actions", () => {
  for (const platform of ["darwin", "linux"]) {
    const actions = Object.values(JSON.parse(readFileSync(`herdr-app/keys.${platform}.json`, "utf8")).global);
    for (const action of ["org-tasks.select", "layout.toggle:org-tasks"])
      assert.ok(actions.includes(action), `${platform}: ${action}`);
  }
});

test("layout validator rejects missing status", () => {
  validateLayout(layout);
  const bad = structuredClone(layout);
  bad.children.find(x => x.id === "work").children.find(x => x.id === "status").children.pop();
  assert.throws(() => validateLayout(bad));
});

test("layout validator rejects the stock agents list beside orch-spaces", () => {
  const bad = structuredClone(layout);
  bad.children.find(x => x.id === "sidebar").children.push({ id: "agents", type: "block", content: { mod: "agents" } });
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
  assert.throws(() => validateWiring(wiring.replace('"mods/system"', '"mods/other"')));
  assert.throws(() => validateWiring(wiring.replace('check_herdr_leaf app "${herdrAppConfig}/${leaf}"', 'true "${herdrAppConfig}/${leaf}"')));
  assert.throws(() => validateWiring(wiring.replace('herdr-app/keys.${herdrAppKeysPlatform}.json";', 'herdr-app/keys.json";')));
  assert.throws(() => validateWiring(wiring.replace('else "linux";', 'else "darwin";')));
});

test("collision guard rejects foreign files, directories and dangling links", { skip: wiringPath === undefined }, () => {
  const wiring = readFileSync(wiringPath, "utf8");
  const match = wiring.match(/check_herdr_leaf\(\) \{([\s\S]*?)\n        \}/);
  assert.ok(match);
  const dir = mkdtempSync(resolve(".tmp/herdr-leaves-"));
  const fn = `check_herdr_leaf() {${match[1]}\n}`
    .replaceAll('${pkgs.coreutils}/bin/readlink', 'readlink')
    .replaceAll('${builtins.storeDir}', dir);
  try {
    const source = join(dir, "source"), target = join(dir, "target");
    writeFileSync(source, "same");
    const check = () => spawnSync("bash", ["-c", `${fn}\ncheck_herdr_leaf app "$1" "$2"`, "guard", target, source], { encoding: "utf8" });
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
    assert.equal(spawnSync("bash", ["-c", `${fn}\ncheck_herdr_leaf app "$1" "$2"`, "guard", target, join(alias, "source")]).status, 0);
    for (const failedPath of [target, source]) {
      for (const outcome of ["return 1", "return 0"]) {
        const override = `readlink() { if [ "$1" = -f ] && [ "$2" = "$FAIL_PATH" ]; then ${outcome}; fi; command readlink "$@"; }`;
        assert.equal(spawnSync("bash", ["-c", `${override}\n${fn}\ncheck_herdr_leaf app "$1" "$2"`, "guard", target, source], { env: { ...process.env, FAIL_PATH: failedPath } }).status, 1);
      }
    }
    rmSync(source);
    assert.equal(check().status, 1);
    assert.equal(spawnSync("bash", ["-c", `${fn}\ncheck_herdr_leaf app "$1" "$2"`, "guard", target, ""]).status, 1);
  } finally {
    rmSync(dir, { recursive: true, force: true });
  }
});

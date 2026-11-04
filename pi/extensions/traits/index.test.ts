#!/usr/bin/env tsx
import { chmodSync, mkdirSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { createTraitInputHandler, expandTraits, resolveTraitsBinary } from "./index.ts";

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

const root = mkdtempSync(join(tmpdir(), "pi-traits-unit-"));
try {
  const projectTraits = join(root, ".agents", "traits");
  mkdirSync(join(projectTraits, "focused"), { recursive: true });
  writeFileSync(join(projectTraits, "focused", "prompt.md"), "---\nname: focused\n---\nExpanded");
  writeFileSync(join(projectTraits, "focused", "gate.md"), "MUST NOT APPEAR");

  const emptyHome = join(root, "home-traits");
  mkdirSync(emptyHome);
  const result = await expandTraits("page%focused %20 %focused %unknown", root, {
    projectTrusted: true,
    homeTraits: emptyHome,
  });
  assert(result.text === "page%focused %20 Expanded %unknown", `unexpected text: ${result.text}`);
  assert(result.resolved.length === 1 && result.resolved[0]?.trait === "focused", "focused should resolve once");
  assert(result.unknowns.join(",") === "unknown", "unknown should be reported");
  assert(!result.text.includes("MUST NOT APPEAR"), "gate.md must not be concatenated");
  console.log("ok - trusted project traits expand and negative tokens pass through");

  // A project-only name, distinct from anything packaged, isolates project-layer trust from
  // the packaged layer asserted below -- `%focused` now resolves via the packaged store
  // regardless of trust, so it cannot discriminate project exclusion on its own.
  mkdirSync(join(projectTraits, "projectonly"), { recursive: true });
  writeFileSync(join(projectTraits, "projectonly", "prompt.md"), "---\nname: projectonly\n---\nExpanded");
  const untrusted = await expandTraits("%projectonly", root, { homeTraits: emptyHome });
  assert(untrusted.text === "%projectonly", `untrusted project trait expanded: ${untrusted.text}`);
  assert(untrusted.resolved.length === 0, "untrusted project trait must not resolve");
  console.log("ok - untrusted project traits are excluded from resolution");

  const packaged = await expandTraits("%read-only", root, { homeTraits: emptyHome });
  assert(packaged.resolved.length === 1 && packaged.resolved[0]?.source === "packaged", "packaged fragment should resolve by default");
  assert(packaged.resolved[0]?.trait === "read-only", "packaged resolution should name the trait");
  assert(!packaged.text.includes("%read-only"), `packaged trait did not expand: ${packaged.text}`);
  console.log("ok - the packaged layer is a default, available without project trust");

  const binaryRoot = join(root, "binary-resolution");
  const extensionDirectory = join(binaryRoot, "pi", "extensions", "traits");
  const envBinary = join(binaryRoot, "env-traits");
  const home = join(binaryRoot, "home");
  const cleanHome = join(binaryRoot, "empty-home");
  const homeBinary = join(home, ".agents", "skills", "herdr-orch", "scripts", "traits");
  const extensionBinary = join(extensionDirectory, "../../../skills", "herdr-orch", "scripts", "traits");
  mkdirSync(dirname(homeBinary), { recursive: true });
  mkdirSync(dirname(extensionBinary), { recursive: true });
  mkdirSync(cleanHome);
  writeFileSync(envBinary, "");
  writeFileSync(homeBinary, "");
  writeFileSync(extensionBinary, "");

  assert(
    resolveTraitsBinary(home, { HERDR_ORCH_TRAITS_BIN: envBinary }, extensionDirectory) === envBinary,
    "environment override should resolve first when it exists",
  );
  assert(
    resolveTraitsBinary(home, {}, extensionDirectory) === homeBinary,
    "canonical home path should resolve before the extension-relative fallback",
  );
  rmSync(homeBinary);
  assert(
    resolveTraitsBinary(cleanHome, {}, extensionDirectory) === extensionBinary,
    "extension-relative fallback should resolve with a clean home",
  );
  rmSync(extensionBinary);
  const missingOverride = join(binaryRoot, "missing-env-traits");
  try {
    resolveTraitsBinary(cleanHome, { HERDR_ORCH_TRAITS_BIN: missingOverride }, extensionDirectory);
    throw new Error("expected unresolved traits binary to throw");
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    for (const path of [missingOverride, join(cleanHome, ".agents", "skills", "herdr-orch", "scripts", "traits"), extensionBinary]) {
      assert(message.includes(path), `missing candidate from resolution error: ${path}`);
    }
  }
  console.log("ok - CLI resolution uses environment, home, and extension-relative candidates with an actionable error");

  const launcher = join(dirname(fileURLToPath(import.meta.url)), "../../../skills/herdr-orch/scripts/traits");
  const launcherAlias = join(root, "traits-launcher");
  const savedLauncherOverride = process.env.HERDR_ORCH_TRAITS_BIN;
  symlinkSync(launcher, launcherAlias);
  try {
    for (const [label, binary] of [["real launcher", launcher], ["symlink launcher", launcherAlias]] as const) {
      process.env.HERDR_ORCH_TRAITS_BIN = binary;
      const expansion = await expandTraits("%read-only", root, { homeTraits: emptyHome });
      assert(expansion.resolved.length === 1 && expansion.resolved[0]?.source === "packaged", `${label} should resolve the packaged trait`);
      assert(expansion.resolved[0]?.trait === "read-only", `${label} should name the packaged trait`);
      assert(!expansion.text.includes("%read-only"), `${label} did not expand the packaged trait: ${expansion.text}`);
    }
  } finally {
    if (savedLauncherOverride === undefined) delete process.env.HERDR_ORCH_TRAITS_BIN;
    else process.env.HERDR_ORCH_TRAITS_BIN = savedLauncherOverride;
  }
  console.log("ok - packaged traits expand through real and symlink launchers");

  const broken = join(root, "broken-traits");
  writeFileSync(broken, "#!/usr/bin/env bash\necho 'synthetic traits failure' >&2\nexit 7\n");
  chmodSync(broken, 0o755);
  try {
    await expandTraits("%focused", root, { binary: broken, homeTraits: emptyHome });
    throw new Error("expected synthetic CLI failure");
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    assert(message.includes("synthetic traits failure"), `failure message missing stderr: ${message}`);
    console.log(`ok - CLI failure is surfaced: ${message}`);
  }

  const notices: Array<{ message: string; level: string }> = [];
  const handler = createTraitInputHandler({ binary: broken, homeTraits: emptyHome });
  const handled = await handler(
    { source: "interactive", text: "%focused" },
    {
      cwd: root,
      isProjectTrusted: () => false,
      ui: { notify: (message, level) => notices.push({ message, level }) },
    },
  );
  assert(handled.action === "continue", "interpolation errors must send the original input unchanged");
  assert(notices.length === 1 && notices[0]?.level === "warning", "interpolation errors must warn visibly");
  assert(notices[0]?.message.includes("synthetic traits failure"), "warning must include the interpolation error");
  console.log("ok - input interpolation errors fail open with a visible warning");
} finally {
  rmSync(root, { recursive: true, force: true });
}

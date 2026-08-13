#!/usr/bin/env tsx
import { chmodSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
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

  const binary = resolveTraitsBinary();
  assert(binary.endsWith("/.agents/skills/herdr-orch/scripts/traits"), `unexpected binary: ${binary}`);
  console.log("ok - CLI resolution is anchored under ~/.agents/skills");

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

#!/usr/bin/env tsx
/**
 * Unit tests for pi/extensions/question.ts.
 *
 * Covers:
 * - `executionMode: "sequential"` is declared at registration time.
 * - Every non-TUI `ctx.mode` is rejected before `ctx.ui.custom()` is ever
 *   called, so RPC's `custom()` (which resolves to `undefined`) can't be
 *   mistaken for a user cancellation.
 * - An interactive smoke test drives the actual `render`/`handleInput`
 *   component returned to `ctx.ui.custom()` in "tui" mode, exercising the
 *   option-list navigation and free-text ("Type something…") paths without
 *   a real terminal.
 *
 * Run directly with `tsx` (registered as part of pi/scripts/check-extensions.sh).
 */

import question from "./question.ts";

let passed = 0;
let failed = 0;

function ok(name: string) {
  console.log(`ok - ${name}`);
  passed++;
}

function notOk(name: string, reason: string) {
  console.log(`not ok - ${name}`);
  console.log(`  # ${reason}`);
  failed++;
}

async function test(name: string, fn: () => Promise<void> | void) {
  try {
    await fn();
    ok(name);
  } catch (err) {
    notOk(name, err instanceof Error ? err.message : String(err));
  }
}

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

function registerQuestionTool(): any {
  const tools: any[] = [];
  question({ registerTool: (t: any) => tools.push(t) } as any);
  assert(tools.length === 1, `expected exactly one registered tool, got ${tools.length}`);
  return tools[0];
}

// A minimal fake `tui`/`theme` sufficient for the question tool's custom
// component (render() just needs theme.fg/bold; Editor needs a tui with
// requestRender()).
const fakeTui = { requestRender: () => {} };
const fakeTheme = {
  fg: (_tone: string, s: string) => s,
  bold: (s: string) => s,
} as any;

const BASE_PARAMS = {
  question: "Pick one",
  options: [{ label: "A" }, { label: "B" }],
};

(async () => {
  await test("question.executionMode is declared sequential at registration", () => {
    const tool = registerQuestionTool();
    assert(tool.executionMode === "sequential", `expected "sequential", got ${JSON.stringify(tool.executionMode)}`);
  });

  for (const mode of ["rpc", "json", "print"] as const) {
    await test(`execute() rejects mode="${mode}" without opening custom UI`, async () => {
      const tool = registerQuestionTool();
      let customCalled = false;
      const ctx: any = {
        mode,
        hasUI: mode === "rpc",
        ui: {
          custom: async () => {
            customCalled = true;
            // Mirror RPC's real behavior: resolves to undefined, no dialog shown.
            return undefined;
          },
        },
      };

      const result = await tool.execute("call-1", BASE_PARAMS, undefined, undefined, ctx);

      assert(!customCalled, `custom UI must not open in mode="${mode}"`);
      assert(
        result.content[0]?.text === "Error: UI not available (running in non-interactive mode)",
        `expected unsupported-mode error text, got ${JSON.stringify(result.content)}`,
      );
      assert(result.details.answer === null, "unsupported-mode result must not report an answer");
      // Must be distinguishable from a real user cancellation.
      assert(
        !String(result.content[0]?.text).toLowerCase().includes("cancel"),
        "unsupported-mode result must not read as a user cancellation",
      );
    });
  }

  await test("interactive smoke test: selecting a pre-defined option in tui mode", async () => {
    const tool = registerQuestionTool();
    const ctx: any = {
      mode: "tui",
      hasUI: true,
      ui: {
        custom: async (factory: any) => {
          let done: any;
          const component = factory(fakeTui, fakeTheme, {}, (r: unknown) => {
            done = r;
          });
          const lines = component.render(40);
          assert(lines.length > 0, "component must render at least one line");
          component.handleInput("\x1b[B"); // down -> select option 2 ("B")
          component.handleInput("\r"); // enter -> confirm selection
          return done;
        },
      },
    };

    const result = await tool.execute("call-2", BASE_PARAMS, undefined, undefined, ctx);
    assert(result.content[0]?.text === "User selected: 2. B", `unexpected content: ${JSON.stringify(result.content)}`);
    assert(result.details.answer === "B" && result.details.wasCustom === false, `unexpected details: ${JSON.stringify(result.details)}`);
  });

  await test("interactive smoke test: free-text answer via 'Type something…' in tui mode", async () => {
    const tool = registerQuestionTool();
    const ctx: any = {
      mode: "tui",
      hasUI: true,
      ui: {
        custom: async (factory: any) => {
          let done: any;
          const component = factory(fakeTui, fakeTheme, {}, (r: unknown) => {
            done = r;
          });
          component.render(40);
          component.handleInput("\x1b[B"); // down -> "B"
          component.handleInput("\x1b[B"); // down -> "Type something…"
          component.handleInput("\r"); // enter -> enter edit mode
          for (const ch of "custom reply") component.handleInput(ch);
          component.handleInput("\r"); // submit editor
          return done;
        },
      },
    };

    const result = await tool.execute("call-3", BASE_PARAMS, undefined, undefined, ctx);
    assert(result.content[0]?.text === "User wrote: custom reply", `unexpected content: ${JSON.stringify(result.content)}`);
    assert(
      result.details.answer === "custom reply" && result.details.wasCustom === true,
      `unexpected details: ${JSON.stringify(result.details)}`,
    );
  });

  await test("interactive smoke test: escape in the option list cancels", async () => {
    const tool = registerQuestionTool();
    const ctx: any = {
      mode: "tui",
      hasUI: true,
      ui: {
        custom: async (factory: any) => {
          let done: any;
          const component = factory(fakeTui, fakeTheme, {}, (r: unknown) => {
            done = r;
          });
          component.render(40);
          component.handleInput("\x1b"); // escape -> cancel
          return done;
        },
      },
    };

    const result = await tool.execute("call-4", BASE_PARAMS, undefined, undefined, ctx);
    assert(result.details.answer === null, `expected cancellation, got ${JSON.stringify(result.details)}`);
    assert(
      result.content[0]?.text.toLowerCase().includes("cancel"),
      `expected cancellation text, got ${JSON.stringify(result.content)}`,
    );
  });

  console.log(`\n# ${passed} passed, ${failed} failed`);
  if (failed > 0) process.exit(1);
})();

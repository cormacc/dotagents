#!/usr/bin/env tsx
/** Unit tests for pi/extensions/systemprompt.ts mode-specific transcript behavior. */

import systemprompt from "../systemprompt.ts";

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
  } catch (error) {
    notOk(name, error instanceof Error ? error.message : String(error));
  }
}

function assert(condition: unknown, message: string): asserts condition {
  if (!condition) throw new Error(message);
}

function registerSystemPrompt() {
  let command: any;
  let renderer: any;
  const entries: any[] = [];
  let sendMessageCalls = 0;

  systemprompt({
    registerCommand: (_name: string, definition: any) => { command = definition; },
    registerEntryRenderer: (_type: string, definition: any) => { renderer = definition; },
    appendEntry: (type: string, data: unknown) => entries.push({ type, data }),
    sendMessage: () => { sendMessageCalls++; },
  } as any);

  assert(command, "expected /systemprompt command to register");
  assert(renderer, "expected systemprompt entry renderer to register");
  return { command, renderer, entries, get sendMessageCalls() { return sendMessageCalls; } };
}

function context(mode: "tui" | "rpc" | "json" | "print", prompt: string | undefined) {
  const notifications: Array<{ message: string; type: string }> = [];
  return {
    ctx: {
      mode,
      hasUI: mode === "tui" || mode === "rpc",
      getSystemPrompt: () => prompt,
      ui: {
        notify: (message: string, type: string) => notifications.push({ message, type }),
      },
    },
    notifications,
  };
}

(async () => {
  await test("TUI stores the complete prompt in a custom entry, not a message", async () => {
    const registered = registerSystemPrompt();
    const { ctx, notifications } = context("tui", "line one\nline two");

    await registered.command.handler("", ctx);

    assert(registered.entries.length === 1, `expected one entry, got ${registered.entries.length}`);
    assert(registered.entries[0].type === "systemprompt", "expected a systemprompt entry");
    assert(registered.entries[0].data.prompt === "line one\nline two", "entry must retain the complete prompt");
    assert(notifications.length === 0, "TUI should render a transcript entry instead of notifying");
    assert(registered.sendMessageCalls === 0, "system prompt must not enter model context");
  });

  await test("TUI entry renderer displays the complete stored prompt", () => {
    const { renderer } = registerSystemPrompt();
    const component = renderer(
      { data: { prompt: "first line\nlast line" } },
      {},
      { bg: (_tone: string, text: string) => text, fg: (_tone: string, text: string) => text, bold: (text: string) => text },
    );
    const rendered = component.render(80).join("\n");

    assert(rendered.includes("first line") && rendered.includes("last line"), `renderer omitted prompt: ${JSON.stringify(rendered)}`);
  });

  await test("RPC emits the complete prompt through notify without a session entry", async () => {
    const registered = registerSystemPrompt();
    const { ctx, notifications } = context("rpc", "complete RPC prompt");

    await registered.command.handler("", ctx);

    assert(registered.entries.length === 0, "RPC must not append an interactive transcript entry");
    assert(notifications.length === 1, `expected one RPC notification, got ${notifications.length}`);
    assert(notifications[0].message === "complete RPC prompt", "RPC notification must contain the complete prompt");
    assert(notifications[0].type === "info", "RPC prompt notification must be informational");
    assert(registered.sendMessageCalls === 0, "system prompt must not enter model context");
  });

  for (const mode of ["json", "print"] as const) {
    await test(`${mode} mode has no transcript output or context message`, async () => {
      const registered = registerSystemPrompt();
      const { ctx, notifications } = context(mode, "hidden outside interactive modes");

      await registered.command.handler("", ctx);

      assert(registered.entries.length === 0, `${mode} must not append an entry`);
      assert(notifications.length === 0, `${mode} must not emit UI output`);
      assert(registered.sendMessageCalls === 0, `${mode} must not send a context message`);
    });
  }

  await test("missing prompt warns only when UI output is available", async () => {
    const registered = registerSystemPrompt();
    const { ctx, notifications } = context("tui", undefined);

    await registered.command.handler("", ctx);

    assert(registered.entries.length === 0, "missing prompt must not append an entry");
    assert(notifications.length === 1, "missing prompt should warn the interactive caller");
    assert(notifications[0].message === "No system prompt loaded.", "unexpected warning text");
    assert(notifications[0].type === "warning", "missing prompt warning should be warning severity");
    assert(registered.sendMessageCalls === 0, "missing prompt must not send a context message");
  });

  console.log(`\n# ${passed} passed, ${failed} failed`);
  if (failed > 0) process.exit(1);
})();

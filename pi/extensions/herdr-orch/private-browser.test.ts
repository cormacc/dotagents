#!/usr/bin/env tsx
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import { ModelRuntime } from "@earendil-works/pi-coding-agent";
import { anthropicProvider } from "@earendil-works/pi-ai/providers/anthropic";
import type { AuthEvent, OAuthAuth } from "@earendil-works/pi-ai";
import { accountProvider } from "./accounts.ts";
import { privateBrowserCommand, privateBrowserOAuth } from "./private-browser.ts";
import { createHarness } from "../test/support/rpc.ts";

assert.equal(process.platform, "linux", "native browser executable tests require Linux stubs; never launch a real desktop browser");

const root = resolve(dirname(fileURLToPath(import.meta.url)), "../../..");
const cwd = mkdtempSync(join(root, ".tmp/private-browser-"));
const oldPath = process.env.PATH;
const oldFetch = globalThis.fetch;
const capture = join(cwd, "argv.json");
const defaultCapture = join(cwd, "default-browser");
const heldBrowserPid = join(cwd, "held-browser-pid");
const bin = join(cwd, "bin");
mkdirSync(bin);
const stub = (name: string, body: string) => {
  const path = join(bin, name);
  writeFileSync(path, `#!${process.execPath}\n${body}\n`);
  chmodSync(path, 0o755);
};
stub("chromium", `import { writeFileSync } from 'node:fs'; writeFileSync(${JSON.stringify(capture)}, JSON.stringify(process.argv.slice(2))); if (process.env.PRIVATE_BROWSER_TEST_HOLD === '1') { writeFileSync(${JSON.stringify(heldBrowserPid)}, String(process.pid)); setTimeout(() => process.exit(0), 10000); } else process.exit(Number(process.env.PRIVATE_BROWSER_TEST_EXIT || 0));`);
stub("xdg-open", `import { writeFileSync } from 'node:fs'; writeFileSync(${JSON.stringify(defaultCapture)}, 'unexpected default browser');`);
process.env.PATH = bin;

const url = "https://claude.ai/oauth/authorize?state=synthetic&code_challenge=test%26value";
const credential = { type: "oauth" as const, access: "fixture-access", refresh: "fixture-refresh", expires: 4102444800000 };
const events: AuthEvent[] = [];
const signal = new AbortController().signal;
const waitForCapture = async () => {
  for (let n = 0; n < 200 && !existsSync(capture); n++) await new Promise((r) => setTimeout(r, 10));
  assert.ok(existsSync(capture), "private browser executable must receive the URL");
  return JSON.parse(readFileSync(capture, "utf8"));
};
let exchanges = 0;
const server = createServer(async (req, res) => {
  let raw = "";
  for await (const chunk of req) raw += chunk;
  const body = JSON.parse(raw);
  assert.equal(body.grant_type, "authorization_code");
  assert.equal(body.code, "fixture-code");
  exchanges++;
  res.setHeader("Content-Type", "application/json");
  res.end(JSON.stringify({ access_token: credential.access, refresh_token: credential.refresh, expires_in: 3600 }));
});
try {
  assert.deepEqual(privateBrowserCommand("linux", url), ["chromium", ["--incognito", url]]);
  assert.deepEqual(privateBrowserCommand("darwin", url), ["/Applications/Google Chrome.app/Contents/MacOS/Google Chrome", ["--incognito", url]]);
  assert.deepEqual(privateBrowserCommand("win32", url), ["chrome.exe", ["--incognito", url]]);
  assert.equal(privateBrowserCommand("freebsd", url), undefined);
  console.log("ok - Linux, macOS and Windows use separate incognito and exact-URL argv entries");

  // These are the installed Pi classes, not a replacement OAuth provider or UI implementation.
  const dist = dirname(fileURLToPath(import.meta.resolve("@earendil-works/pi-coding-agent")));
  const { initTheme } = await import(pathToFileURL(join(dist, "modes/interactive/theme/theme.js")).href);
  const { LoginDialogComponent } = await import(pathToFileURL(join(dist, "modes/interactive/components/login-dialog.js")).href);
  const { InteractiveMode } = await import(pathToFileURL(join(dist, "modes/interactive/interactive-mode.js")).href);
  initTheme("dark", false);
  const dialog = new LoginDialogComponent({ requestRender() {} }, "anthropic-2", () => {});
  const notify = (event: AuthEvent) => {
    events.push(event);
    InteractiveMode.prototype.notifyAuthDialog.call({}, dialog, event);
  };
  // A positive control proves that this native consumer really does launch the default handler.
  notify({ type: "auth_url", url });
  for (let n = 0; n < 200 && !existsSync(defaultCapture); n++) await new Promise((r) => setTimeout(r, 10));
  assert.ok(existsSync(defaultCapture), "native auth_url control opens the default handler");
  rmSync(defaultCapture);
  events.length = 0;

  await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
  const origin = `http://127.0.0.1:${(server.address() as any).port}`;
  globalThis.fetch = async (input, init) => {
    assert.equal(String(input), "https://platform.claude.com/v1/oauth/token", "no external provider request allowed");
    return oldFetch(origin, init);
  };
  const authPath = join(cwd, "auth.json");
  const preserved = { anthropic: credential, "anthropic-1": { ...credential, access: "fixture-sibling" } };
  writeFileSync(authPath, JSON.stringify(preserved));
  const runtime = await ModelRuntime.create({ authPath, modelsPath: null, refreshOnCreate: false });
  runtime.registerNativeProvider(accountProvider("anthropic-2"));
  for (const mode of ["success", "failure", "absent", "copy_code", "cancel", "abort", "lifetime"]) {
    events.length = 0;
    rmSync(capture, { force: true });
    process.env.PATH = mode === "absent" ? join(cwd, "missing") : bin;
    process.env.PRIVATE_BROWSER_TEST_EXIT = mode === "failure" ? "7" : "0";
    process.env.PRIVATE_BROWSER_TEST_HOLD = mode === "lifetime" ? "1" : "0";
    const controller = new AbortController();
    const login = runtime.login("anthropic-2", "oauth", {
      signal: controller.signal,
      prompt: async (p) => {
        if (p.type === "select") return mode === "copy_code" ? "copy_code" : "browser";
        if (mode === "cancel") throw new Error("Login cancelled");
        if (mode === "abort") { controller.abort(); controller.signal.throwIfAborted(); }
        return "fixture-code";
      },
      notify,
    });
    if (mode === "cancel") await assert.rejects(login, /Login cancelled/);
    else if (mode === "abort") await assert.rejects(login, { name: "AbortError" });
    else await login;
    const shown = events.find((e) => e.type === "info" && e.links?.length);
    assert.ok(shown?.type === "info", "production account wrapper must suppress native default opening");
    assert.match(shown.message, /copy this URL into a private\/incognito window manually/);
    const nativeUrl = shown.links![0].url;
    assert.equal(new URL(nativeUrl).origin, "https://claude.ai");
    assert.equal(events.some((e) => e.type === "auth_url"), false);
    if (mode !== "absent" && mode !== "copy_code") assert.deepEqual(await waitForCapture(), ["--incognito", nativeUrl]);
    else {
      await new Promise((r) => setTimeout(r, 100));
      assert.equal(existsSync(capture), false);
    }
    if (mode === "lifetime") {
      assert.ok(existsSync(heldBrowserPid));
      process.kill(Number(readFileSync(heldBrowserPid, "utf8")), 0);
      process.kill(Number(readFileSync(heldBrowserPid, "utf8")), "SIGTERM");
      rmSync(heldBrowserPid);
    }
    assert.equal(existsSync(defaultCapture), false, "named login must not open the default browser");
    const stored = JSON.parse(readFileSync(authPath, "utf8"));
    assert.deepEqual(stored.anthropic, preserved.anthropic);
    assert.deepEqual(stored["anthropic-1"], preserved["anthropic-1"]);
    if (mode === "cancel" || mode === "abort") assert.equal(stored["anthropic-2"], undefined);
    else assert.equal(stored["anthropic-2"].access, credential.access);
    await runtime.logout("anthropic-2");
    console.log(`ok - installed native OAuth ${mode}: exact URL, manual fallback, native UI and credential isolation`);
  }
  assert.equal(exchanges, 5);
  delete process.env.PRIVATE_BROWSER_TEST_HOLD;
  const aborted = new AbortController();
  aborted.abort();
  events.length = 0;
  rmSync(capture, { force: true });
  await assert.rejects(runtime.login("anthropic-2", "oauth", { signal: aborted.signal, prompt: async () => { throw new Error("unexpected prompt"); }, notify }), { name: "AbortError" });
  assert.deepEqual(events, []);
  assert.equal(existsSync(capture), false);

  let refreshes = 0;
  const native: OAuthAuth = {
    name: "fixture", login: async (interaction, options) => {
      assert.equal(options?.agentName, "fixture-agent");
      await interaction.prompt({ type: "select", message: "method", options: [{ id: "browser", label: "browser" }, { id: "copy_code", label: "headless" }] });
      interaction.notify({ type: "auth_url", url });
      interaction.notify({ type: "progress", message: "fixture progress" });
      return credential;
    },
    refresh: async () => { refreshes++; return credential; },
    toAuth: async () => ({ apiKey: credential.access }),
  };
  const wrapped = privateBrowserOAuth(native);
  assert.equal(wrapped.refresh, native.refresh);
  assert.equal(wrapped.toAuth, native.toAuth);
  await wrapped.refresh(credential, signal);
  assert.equal(refreshes, 1);
  assert.equal(existsSync(capture), false, "refresh cannot launch a browser");
  for (const untrusted of ["file:///tmp/evil", "https://claude.ai.evil/oauth/authorize", "https://user@claude.ai/oauth/authorize", "https://claude.ai/other", "https://claude.ai/oauth/authorize\n--evil"]) {
    const invalid = privateBrowserOAuth({ ...native, login: async (i) => {
      await i.prompt({ type: "select", message: "method", options: [{ id: "browser", label: "browser" }, { id: "copy_code", label: "headless" }] });
      i.notify({ type: "auth_url", url: untrusted });
      return credential;
    } });
    await invalid.login({ signal, prompt: async () => "browser", notify() {} });
  }
  await new Promise((r) => setTimeout(r, 100));
  assert.equal(existsSync(capture), false, "untrusted URLs cannot reach a browser command");
  // Aborted notification cannot launch even if a provider publishes after cancellation.
  await wrapped.login({ signal: aborted.signal, prompt: async () => "browser", notify() {} }, { agentName: "fixture-agent" });
  await new Promise((r) => setTimeout(r, 100));
  assert.equal(existsSync(capture), false);
  const nativeEvents: AuthEvent[] = [];
  await anthropicProvider().auth.oauth!.login({ signal, prompt: async (p) => p.type === "select" ? "copy_code" : "fixture-code", notify: (e) => nativeEvents.push(e) });
  assert.ok(nativeEvents.some((e) => e.type === "auth_url"), "default native login keeps its original callback");
  assert.equal(existsSync(capture), false);
  console.log("ok - abort, URL trust boundary, refresh and default native callback are unchanged");

  const fixture = join(cwd, "installed-login.ts");
  const shownPath = join(cwd, "shown-url.json");
  const nativeAuthPath = join(cwd, "installed-auth.json");
  writeFileSync(nativeAuthPath, JSON.stringify(preserved));
  writeFileSync(fixture, `
    import { ModelRuntime } from '@earendil-works/pi-coding-agent';
    import { anthropicProvider } from '@earendil-works/pi-ai/providers/anthropic';
    import { writeFileSync } from 'node:fs';
    import { accountProvider } from ${JSON.stringify(join(root, "pi/extensions/herdr-orch/accounts.ts"))};
    export default (pi) => {
      globalThis.installAccountTestRedirect();
      const provider = accountProvider('anthropic-2');
      if (process.env.PRIVATE_BROWSER_TEST_UNWRAPPED === '1') provider.auth.oauth = anthropicProvider().auth.oauth;
      pi.registerProvider(provider);
      pi.registerCommand('fixture-login', { description: 'synthetic login only', handler: async (_, ctx) => {
        const runtime = await ModelRuntime.create({ authPath: ${JSON.stringify(nativeAuthPath)}, modelsPath: null, refreshOnCreate: false });
        runtime.registerNativeProvider(provider);
        await runtime.login('anthropic-2', 'oauth', {
          prompt: async (p) => p.type === 'select' ? 'browser' : 'fixture-code',
          notify: (e) => {
            if (e.type === 'auth_url') throw new Error('unexpected default-browser event');
            if (e.type === 'info' && e.links?.length) writeFileSync(${JSON.stringify(shownPath)}, JSON.stringify(e));
          },
        });
        ctx.ui.notify('fixture login completed', 'info');
      }});
    }`);
  const piCommand = oldPath!.split(":").map((part) => join(part, "pi")).find((path) => existsSync(path));
  assert.ok(piCommand, "installed Pi binary must exist");
  for (const mode of ["success", "failure", "absent"]) {
    rmSync(capture, { force: true });
    rmSync(shownPath, { force: true });
    writeFileSync(nativeAuthPath, JSON.stringify(preserved));
    const h = createHarness(fixture, cwd, {
      command: piCommand,
      args: ["--no-extensions", "--no-skills", "--no-prompt-templates", "--no-context-files"],
      env: {
        PATH: mode === "absent" ? join(cwd, "missing") : bin,
        PRIVATE_BROWSER_TEST_EXIT: mode === "failure" ? "7" : "0",
        NODE_OPTIONS: `--require=${join(root, "pi/extensions/herdr-orch/account-fake-only.cjs")}`,
        HERDR_ORCH_FAKE_ORIGIN: origin,
        HC_TERMINAL_ID: undefined,
        ORCH_ASSIGNMENT_ROOT: cwd,
      },
    });
    try {
      h.send({ type: "prompt", message: "/fixture-login" });
      await h.waitFor((e) => e.type === "extension_ui_request" && e.message === "fixture login completed", "installed native login");
      const shown = JSON.parse(readFileSync(shownPath, "utf8"));
      assert.match(shown.message, /copy this URL/);
      if (mode !== "absent") assert.deepEqual(await waitForCapture(), ["--incognito", shown.links[0].url]);
      else assert.equal(existsSync(capture), false);
      const stored = JSON.parse(readFileSync(nativeAuthPath, "utf8"));
      assert.equal(stored["anthropic-2"].access, credential.access);
      assert.deepEqual(stored.anthropic, preserved.anthropic);
      assert.deepEqual(stored["anthropic-1"], preserved["anthropic-1"]);
      assert.equal(existsSync(defaultCapture), false);
      assert.doesNotMatch(h.diagnostics(), /failed to load extension|extension-load error/i);
      assert.ok(!JSON.stringify(h.events).includes("https://claude.ai/oauth/authorize"), "auth URL must not enter RPC/session output");
    } finally {
      await h.dispose();
    }
    console.log(`ok - installed Pi executable native OAuth ${mode}: production wrapper, fake endpoint and isolated credentials`);
  }
} finally {
  process.env.PATH = oldPath;
  delete process.env.PRIVATE_BROWSER_TEST_EXIT;
  delete process.env.PRIVATE_BROWSER_TEST_HOLD;
  if (existsSync(heldBrowserPid)) {
    try { process.kill(Number(readFileSync(heldBrowserPid, "utf8")), "SIGTERM"); } catch {}
  }
  globalThis.fetch = oldFetch;
  server.closeAllConnections();
  await new Promise<void>((r) => server.close(() => r()));
  rmSync(cwd, { recursive: true, force: true });
}

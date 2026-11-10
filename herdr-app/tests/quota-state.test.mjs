import test from "node:test";
import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { promisify } from "node:util";
import { parseQuota, coordinator, elapsed, snapshot, visibleWindows, EVERY, EXPIRE, INSTALL_URL } from "../mods/herdr-orch/quota-state.js";
import { window, modelWindows, namedAccounts, row, envelope, response } from "./quota-fixtures.mjs";

const parse = x => parseQuota(JSON.stringify(x));
function harness(exec = async () => response()) {
  let state, now = 0;
  const dependencies = { exec, home: () => "/fake home", read: () => state, write: x => { state = x; }, clock: () => now };
  return { quota: coordinator(dependencies), other: coordinator(dependencies), advance: n => { now = n; }, state: () => state };
}

test("valid envelope preserves aliases and adapter identity without leaking arbitrary fields", () => {
  const providers = parse(envelope([row("work", { secret: "do not show" }), row("personal")]));
  assert.deepEqual(providers.map(p => p.id), ["work", "personal"]);
  assert.deepEqual(providers.map(p => p.adapter), ["claude", "claude"]);
  assert.equal(providers[0].windows[0].percentUsed, 25);
  assert.equal(providers[0].secret, undefined);
});

test("named accounts retain separate base and account identities and opposite quotas", () => {
  const providers = parse(envelope(namedAccounts));
  assert.deepEqual(providers.map(p => p.baseProvider), ["anthropic", "anthropic"]);
  assert.deepEqual(providers.map(p => p.accountId), ["anthropic-1", "anthropic-2"]);
  assert.deepEqual(providers.map(p => p.windows[0].percentUsed), [1, 99]);
  assert.deepEqual(providers.map(p => p.id), ["anthropic-1", "anthropic-2"]);
  for (const changes of [{ baseProvider: {} }, { accountId: [] }, { accountId: "anthropic-3" },
    { baseProvider: "openai-codex" }, { provider: "codex" },
    { localProvider: "anthropic-01", accountId: "anthropic-01" }, { localProvider: "anthropic-", accountId: "anthropic-" }])
    assert.throws(() => parse(envelope([{ ...namedAccounts[0], ...changes }])));
});

test("scoped windows retain supplied scope, ID and duration without invented values", () => {
  const windows = parse(envelope([row("x", { windows: modelWindows })]))[0].windows;
  assert.deepEqual(windows.map(w => w.label), [
    "model:sol · extra:0:primary_window (18000s)",
    "model:sol · extra:0:secondary_window (18000s)",
    "model:sol · extra:1:primary_window",
    "model:sol · extra:1:secondary_window",
  ]);
});

test("reject malformed envelopes and invalid percentages rather than treating them as zero", () => {
  for (const invalid of [null, {}, { ...envelope(), ok: false }, { ...envelope(), schema: "other/v1" },
    { ...envelope(), result: {} }, envelope([row("x"), row("x")]), envelope([row("")]),
    envelope([row("x", { windows: null })]), envelope([row("x", { state: {} })]),
    envelope([row("x", { provider: {} })]), envelope([row("x", { local: "true" })])]) {
    assert.throws(() => parse(invalid));
  }
  for (const value of [-1, 101, null, "25", Infinity, NaN]) {
    assert.throws(() => parse(envelope([row("x", { windows: [{ ...window, percentUsed: value }] })])));
  }
  assert.throws(() => parseQuota("banner\n" + response().stdout));
  assert.throws(() => parseQuota("{"));
  assert.throws(() => parse(envelope([row("x", { windows: [{ ...window, percentRemaining: 99 }] })])));
});

test("unknown durations, exact model scopes and unavailable local rows retain their identity", () => {
  const p = parse(envelope([row("x", { windows: [
    { ...window, kind: "unknown", id: "odd", windowSeconds: undefined, resetsAt: "bad" },
    { ...window, kind: "unknown", id: "long", windowSeconds: 2592000 },
    { ...window, kind: "model", scope: "model:unfamiliar" },
  ] }), row("local", { local: true, provider: undefined, windows: [] })]));
  assert.equal(p[0].windows[0].label, "odd");
  assert.equal(elapsed(p[0].windows[0], 0), null);
  assert.equal(p[0].windows[1].label, "long (2592000s)");
  assert.equal(p[0].windows[2].label, "model:unfamiliar · five_hour (18000s)");
  assert.equal(p[1].local, true);
  assert.equal(visibleWindows(p[0], { kinds: { model: false } }).length, 2);
  assert.equal(visibleWindows(p[0], { kinds: { model: true } }).length, 3);
  assert.equal(elapsed({ ...window, windowSeconds: null }, 0), null);
  const invalidReset = parse(envelope([row("x", { windows: [{ ...window, resetsAt: "123" }] })]))[0].windows[0];
  assert.equal(invalidReset.resetsAt, null);
  assert.equal(elapsed(invalidReset, 0), null);
  assert.equal(elapsed(window, Date.parse("2030-01-01T02:30:00Z")), 0.5);
});

test("initial and concurrent status/popup refreshes run one HOME-scoped command", async () => {
  let resolve, calls = [];
  const h = harness((...args) => { calls.push(args); return new Promise(r => { resolve = r; }); });
  assert.deepEqual(h.quota.snapshot().providers, []);
  const first = h.quota.refresh();
  assert.equal(h.quota.refresh(), first);
  const second = h.other.refresh();
  await Promise.resolve();
  assert.equal(calls.length, 1);
  assert.deepEqual(calls[0], ["/fake home/.agents/skills/herdr-orch/scripts/oh", ["usage", "--format", "json"], { cwd: "/fake home" }]);
  resolve(response()); await Promise.all([first, second]);
  assert.equal(h.quota.snapshot().providers[0].windows[0].percentUsed, 25);
  assert.deepEqual(h.other.snapshot(), h.quota.snapshot());
  assert.equal(EVERY, 60000);
});

test("command errors, timeout and malformed output are safely stale immediately and recover", async () => {
  for (const failure of [() => ({ code: 1, stderr: "SECRET", stdout: "" }),
    () => { throw Error("timeout SECRET"); }, () => ({ code: 0, stdout: "SECRET" })]) {
    let fail = false;
    const h = harness(async () => fail ? failure() : response());
    await h.quota.refresh();
    h.advance(1); fail = true; await h.other.refresh();
    assert.equal(h.quota.snapshot().stale, true);
    assert.equal(h.quota.snapshot().providers[0].windows[0].percentUsed, 25);
    assert.ok(h.quota.snapshot().error);
    assert.ok(!JSON.stringify(h.state()).includes("SECRET"));
    h.advance(299999); assert.equal(h.other.snapshot().providers.length, 1);
    h.advance(300000); assert.deepEqual(h.other.snapshot().providers, []);
    h.advance(300001); fail = false; await h.other.refresh();
    assert.equal(h.quota.snapshot().stale, false);
    assert.equal(h.quota.snapshot().providers.length, 1);
  }
});

test("failures before success never fabricate zero usage; abandoned native locks recover", async () => {
  const h = harness(async () => { throw Error("not installed"); });
  await h.quota.refresh();
  assert.deepEqual(h.quota.snapshot().providers, []);
  assert.equal(h.quota.snapshot().error, "Command unavailable or timed out");
  let state = { running: true, startedAt: 0 }, calls = 0;
  const q = coordinator({ exec: async () => { calls++; return response(); }, home: () => "/h", read: () => state, write: x => { state = x; }, clock: () => 30001 });
  await q.refresh(); assert.equal(calls, 1); assert.equal(q.snapshot().stale, false);
});

test("valid provider failures supersede old values, including authentication invalidation", async () => {
  let providers = [row()], fail = false;
  const h = harness(async () => fail ? { code: 1, stdout: "" } : response(providers));
  await h.quota.refresh();
  providers = [row("work", { state: { status: "auth_required", stale: false }, windows: [] })];
  await h.quota.refresh();
  assert.deepEqual(h.quota.snapshot().providers[0].windows, []);
  for (const status of ["unavailable", "rate_limited", "stale"]) {
    providers = [row("work", { state: { status, stale: true } })];
    await h.quota.refresh();
    assert.equal(h.quota.snapshot().providers[0].status, status);
    assert.equal(h.quota.snapshot().providers[0].stale, true);
  }
  providers = [row("work", { state: { status: "auth_required", stale: false } })];
  await h.quota.refresh();
  assert.deepEqual(h.quota.snapshot().providers[0].windows, []);
  h.advance(100); fail = true; await h.other.refresh();
  assert.deepEqual(h.other.snapshot().providers[0].windows, []);
});

test("a start failure names a missing home oh, and only a missing one", async () => {
  for (const [text, missing] of [[null, true], ["#!/usr/bin/env bash\n", false]]) {
    let state;
    const q = coordinator({ exec: async () => { throw Error("cannot start"); }, home: () => "/h",
      readTextFile: async () => text, read: () => state, write: x => { state = x; }, clock: () => 0 });
    await q.refresh();
    const s = q.snapshot();
    assert.equal(s.missing, missing ? "/h/.agents/skills/herdr-orch/scripts/oh" : null);
    if (missing) {
      assert.ok(s.error.startsWith("oh not installed at /h/.agents/skills/herdr-orch/scripts/oh."), s.error);
      assert.ok(s.error.includes(INSTALL_URL), s.error);
    } else assert.equal(s.error, "Command unavailable or timed out");
  }
  let state;
  const q = coordinator({ exec: async () => { throw Error("cannot start"); }, home: () => "/h",
    readTextFile: async () => { throw Error("unreachable"); }, read: () => state, write: x => { state = x; }, clock: () => 0 });
  await q.refresh();
  assert.equal(q.snapshot().missing, null, "a failed check is not proof of absence");
});

test("a synchronous exec rejection does not wedge future refreshes", async () => {
  let fail = true;
  const h = harness(() => { if (fail) throw Error("cannot start"); return Promise.resolve(response()); });
  await h.quota.refresh(); assert.ok(h.quota.snapshot().error);
  fail = false; await h.quota.refresh(); assert.equal(h.quota.snapshot().error, null);
});

test("expiry boundary applies to snapshots created at both earlier and later times", () => {
  for (const at of [0, 60000]) {
    const state = { at, providers: [{ id: "kept" }] };
    assert.equal(snapshot(state, at + EXPIRE - 1).providers.length, 1);
    assert.equal(snapshot(state, at + EXPIRE).providers.length, 0);
    assert.equal(snapshot(state, at + EXPIRE + 1).providers.length, 0);
  }
});

test("fake-HOME integration executes the real home-installed oh launcher via owned HTTP fixture", async () => {
  const home = process.env.HERDR_APP_TEST_HOME;
  assert.ok(home, "Run with bb scripts/test-herdr-app.bb; never use the real HOME");
  let state;
  const q = coordinator({ home: () => home, read: () => state, write: x => { state = x; },
    exec: async (cmd, args, options) => {
      const { stdout, stderr } = await promisify(execFile)(cmd, args, { cwd: options.cwd, timeout: 30000, env: process.env });
      return { code: 0, stdout, stderr };
    } });
  await q.refresh();
  assert.equal(q.snapshot().error, null);
  const providers = q.snapshot().providers;
  assert.equal(providers.find(p => p.id === "anthropic").windows[0].percentUsed, 39);
  assert.equal(providers.find(p => p.id === "openai-codex").windows[0].percentUsed, 58);
});

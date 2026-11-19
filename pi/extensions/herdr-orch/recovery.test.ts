import assert from "node:assert/strict";
import { existsSync, readFileSync, statSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { registerRecovery, quotaFailure, PENDING_WARNING } from "./recovery.ts";

const model = (provider: string, id = "model-a") => ({ provider, id, api: "openai-completions", reasoning: false });
const a = model("openai"), b = model("google"), c = model("mistral");
const deferred = () => { let resolve!: () => void; const promise = new Promise<void>(r => { resolve = r; }); return { promise, resolve }; };
function fixture(mode = "rpc", history: any[] = []) {
  const handlers = new Map<string, any>(), commands = new Map<string, any>(), notices: string[] = [], status: any[] = [], widgets: any[] = [], entries = [...history];
  let selected: any = a, aborts = 0, switches = 0, level = "off", available = [a, b, c];
  let selectionGate: ReturnType<typeof deferred> | undefined, routeGate: ReturnType<typeof deferred> | undefined;
  let autoRoute = false;
  let rosterResult: any = { pools: [{ pool: "small" }], models: [{ canonical: "google/model-a", aliases: ["gm"] }] };
  let setterError = false, routeResult: any = { chosen: "google/model-a", account: "google", concrete: "google/model-a" };
  const requests: any[] = [], requestPaths: string[] = [], authChecks: string[] = [];
  const controller = new AbortController();
  const ctx: any = { mode, hasUI: true, cwd: process.cwd(), signal: controller.signal,
    get model() { return selected; }, isIdle: () => false, abort: () => { aborts++; controller.abort(); }, hasPendingMessages: () => false,
    sessionManager: { getBranch: () => entries },
    modelRegistry: { getAvailableOfType: async () => available, isUsingOAuth: (m: any) => m.provider !== "mistral", getProviderAuth: async (p: string) => { authChecks.push(p); return {}; } },
    ui: { notify: (s: string) => notices.push(s), setStatus: (...s: any[]) => status.push(s), setWidget: (...s: any[]) => widgets.push(s) } };
  const pi: any = { on: (name: string, fn: any) => handlers.set(name, fn), registerCommand: (name: string, cmd: any) => commands.set(name, cmd),
    appendEntry: (customType: string, data: any) => entries.push({ type: "custom", customType, data }),
    getThinkingLevel: () => level, setThinkingLevel: (s: string) => { level = s; },
    setModel: async (m: any) => { switches++; await selectionGate?.promise; if (setterError) throw Error("SECRET-token"); const prev = selected; selected = m; await handlers.get("model_select")({ model: m, previousModel: prev }, ctx); return true; } };
  const execute: any = async (args: string[]) => {
    if (args[0] === "roster") return { details: rosterResult };
    requestPaths.push(args[2]);
    assert.equal(statSync(args[2]).mode & 0o777, 0o600);
    assert.equal(statSync(join(args[2], "..")).mode & 0o777, 0o700);
    requests.push(JSON.parse(readFileSync(args[2], "utf8")));
    if (routeResult === "throw") throw new Error("fixture route failure");
    await routeGate?.promise;
    return { details: autoRoute ? (() => {
      const request = requests.at(-1);
      const excluded = new Set(request.attempted.map((p: any) => `${p.account}/${p.canonical}`));
      const next = request.inventory.find((m: any) => !excluded.has(`${m.account}/${m.canonical}`));
      return next ? { chosen: next.canonical, account: next.account, concrete: `${next.account}/${next.canonical.split('/').slice(1).join('/')}` } : { refused: true };
    })() : routeResult };
  };
  const tier = process.env.RECOVERY_TEST_NO_CONTROLLER ? undefined : registerRecovery(pi, execute);
  const emit = async (name: string, event: any = {}) => handlers.get(name)?.(event, ctx);
  // A refused run up to agent_end; Pi then either retries (agent_start) or settles.
  const refuse = async (errorMessage = "429 quota exhausted", provider = selected.provider, id = "failed-entry", requestModel = selected.id) => {
    await emit("turn_start");
    await emit("turn_end", { message: { role: "assistant", provider, model: requestModel, stopReason: "error", errorMessage }, messageEntryId: id });
    await emit("agent_end", { messages: [] });
  };
  const fail = async (...args: Parameters<typeof refuse>) => {
    await refuse(...args);
    return emit("agent_before_settle", { entries: [{ type: "custom", customType: "other", data: {} }] });
  };
  return { ctx, pi, emit, refuse, fail, commands, tier, notices, status, widgets, entries, requests, requestPaths, controller, authChecks,
    get selected() { return selected; }, get aborts() { return aborts; }, get switches() { return switches; },
    gateSelection() { selectionGate = deferred(); return selectionGate; }, gateRoute() { routeGate = deferred(); return routeGate; },
    setResult(value: any) { routeResult = value; }, setRoster(value: any) { rosterResult = value; },
    useInventory(models: any[]) { available = models; autoRoute = true; }, setError() { setterError = true; }, removeB() { available = [a]; },
    async manual() { selected = c; await emit("model_select", { model: c }); },
    idle() { ctx.isIdle = () => true; } };
}
async function main() {
  for (const name of Object.keys(process.env)) if (name.startsWith("HERDR_ORCH_") || name === "HC_TERMINAL_ID") delete process.env[name];
  for (const text of ["401 quota exceeded", "403 rate_limit_error", "permission denied 429", "authentication quota exhausted", "500 server error", "overloaded_error"]) assert.equal(quotaFailure(text), false, text);
  for (const text of ["429 rate limit", "rate_limit_error", "quota exhausted", "insufficient_quota", "usage limit reached"]) assert.equal(quotaFailure(text), true, text);
  let tests = 1;
  for (const mode of ["tui", "rpc"]) {
    const f = fixture(mode);
    assert.equal(typeof f.tier, "function", "the /oh model handler must be returned");
    assert.equal(f.commands.size, 0, "recovery registers no top-level command");
    const boundary = await f.fail();
    assert.ok(f.requestPaths[0]?.startsWith(join(tmpdir(), "herdr-orch-route-")), "route requests use private runtime temp");
    assert.equal(existsSync(join(f.requestPaths[0], "..")), false, "successful route removes its directory");
    assert.equal(boundary.continue, true);
    assert.deepEqual(boundary.entries[1], { type: "context_edit", targetId: "failed-entry", replacement: null });
    assert.equal(f.switches, 0, "routing stages without a setter");
    await f.emit("agent_start");
    assert.equal(f.switches, 1);
    assert.equal(f.selected.provider, "google");
    assert.equal(f.requests[0].model, "openai/model-a");
    assert.deepEqual(f.requests[0].attempted, [{ canonical: "openai/model-a", account: "openai" }]);
    assert.equal(f.notices[0], PENDING_WARNING);
    assert.equal(f.widgets[0][1][0], PENDING_WARNING);
    assert.equal(f.widgets.at(-1)[1], undefined);
    await f.emit("agent_start");
    assert.equal(f.switches, 1, "stage consumed at most once");
    tests++;
  }
  for (const action of ["abort", "input", "manual", "shutdown", "error"] as const) {
    const f = fixture(); await f.fail();
    const gate = f.gateSelection(); const selecting = f.emit("agent_start");
    await new Promise(r => setImmediate(r));
    assert.equal(f.status.at(-1)[1], PENDING_WARNING);
    if (action === "abort") f.controller.abort();
    if (action === "input") await f.emit("input");
    if (action === "manual") await f.manual();
    if (action === "shutdown") await f.emit("session_shutdown");
    if (action === "error") f.setError();
    assert.equal(f.status.at(-1)[1], PENDING_WARNING, "cancellation cannot clear unresolved warning");
    const before = f.notices.length;
    gate.resolve(); await selecting;
    assert.ok(f.aborts > 0, "failed/cancelled selection suppresses continuation");
    if (action === "shutdown") assert.equal(f.notices.length, before, "obsolete callback writes no UI");
    else {
      assert.equal(f.status.at(-1)[1], undefined);
      assert.match(f.notices.at(-1)!, action === "error" ? /selection failed/ : /Recovery cancelled.*google\/model-a.*No automatic retry/);
    }
    assert.doesNotMatch(JSON.stringify(f.notices), /SECRET-token/);
    tests++;
  }
  for (const action of ["input", "manual", "shutdown"]) {
    const f = fixture(); const gate = f.gateRoute(); const routing = f.fail();
    await new Promise(r => setImmediate(r));
    await f.emit(action === "shutdown" ? "session_shutdown" : action === "input" ? "input" : "model_select", { model: c });
    gate.resolve(); assert.equal(await routing, undefined); await f.emit("agent_start"); assert.equal(f.switches, 0); tests++;
  }
  for (const error of ["401 unauthorised", "403 forbidden", "500 generic server failure"]) { const f = fixture(); assert.equal(await f.fail(error), undefined); assert.equal(f.requests.length, 0); tests++; }
  { const f = fixture(); assert.equal(await f.fail("429", "mistral"), undefined); assert.equal(f.requests.length, 0); tests++; }
  { const f = fixture(); assert.equal(await f.fail("429", "openai", "wrong-model", "other-model"), undefined); assert.equal(f.requests.length, 0); tests++; }
  { const f = fixture(); f.setResult("throw"); assert.equal(await f.fail(), undefined); assert.equal(existsSync(join(f.requestPaths[0], "..")), false); assert.match(f.notices.at(-1)!, /routing unavailable/); tests++; }
  { const f = fixture(); f.setResult({ refused: true }); assert.equal(await f.fail(), undefined); assert.match(f.notices.at(-1)!, /exhausted/); tests++; }
  { const f = fixture(); await f.fail(); f.removeB(); await f.emit("agent_start"); assert.equal(f.switches, 0); assert.ok(f.aborts); tests++; }
  { const f = fixture(); await f.tier!("", f.ctx); assert.match(f.notices.at(-1)!, /No pool selected/); f.idle(); await f.tier!("small", f.ctx); assert.equal(f.entries.at(-1).data.tier, "small");
    const entries = f.entries.length; await f.tier!("", f.ctx); assert.match(f.notices.at(-1)!, /^Pool: small\./); assert.equal(f.entries.length, entries, "a bare query records nothing");
    await f.emit("session_start"); await f.fail("429", "google"); assert.equal(f.requests.at(-1).model, "small");
    await f.manual(); assert.equal(f.entries.at(-1).data.tier, null); tests++; }
  { const f = fixture(); assert.equal((await f.fail()).continue, true); await f.tier!("", f.ctx); await f.emit("agent_start");
    assert.equal(f.switches, 1, "a bare tier query keeps staged recovery"); tests++; }
  { const f = fixture(); assert.equal((await f.fail()).continue, true); await f.tier!("clear", f.ctx); await f.emit("agent_start");
    assert.equal(f.switches, 0, "a tier change invalidates staged recovery"); tests++; }
  // A delegated child recovers within its launch request: copied fork tier metadata is
  // ignored, a direct launch keeps its model, and a launch alias bounds a change of model.
  { process.env.HERDR_ORCH_TASK = "fixture-child"; const f = fixture("rpc", [{ type: "custom", customType: "herdr-orch-tier", data: { tier: "small" } }]); delete process.env.HERDR_ORCH_TASK;
    await f.emit("session_start"); assert.equal((await f.fail()).continue, true, "a direct-model child recovers");
    assert.equal(f.requests.at(-1).model, "openai/model-a", "copied tier metadata does not widen a direct child");
    await f.emit("agent_start"); assert.equal(f.switches, 1);
    const entries = f.entries.length; f.idle(); await f.tier!("small", f.ctx);
    assert.match(f.notices.at(-1)!, /Delegated children keep their launch selection/); assert.equal(f.entries.length, entries, "a child records no tier"); tests++; }
  // /oh model with a model name or string alias routes that model only and clears the pool.
  for (const name of ["google/model-a", "gm"]) {
    const f = fixture(); f.idle(); await f.tier!("small", f.ctx); assert.equal(f.entries.at(-1).data.tier, "small");
    await f.tier!(name, f.ctx); assert.equal(f.requests.at(-1).model, name, `${name} routes as requested`);
    assert.equal(f.entries.at(-1).data.tier, null, `${name} clears the pool`); assert.equal(f.switches, 2); tests++;
  }
  { const f = fixture(); f.idle(); await f.tier!("nope", f.ctx); assert.match(f.notices.at(-1)!, /Use \/oh model/); assert.equal(f.requests.length, 0, "an unknown name makes no route call"); tests++; }
  { const f = fixture(); f.idle(); await f.tier!("small", f.ctx); await f.tier!("clear", f.ctx); assert.match(f.notices.at(-1)!, /^Pool cleared/); assert.equal(f.entries.at(-1).data.tier, null); tests++; }
  { const f = fixture(); f.idle(); await f.tier!("small", f.ctx); assert.deepEqual([...new Set(f.authChecks)].sort(), ["google", "openai"], "routing refreshes each OAuth account before the policy"); tests++; }
  {
    // A bare /oh model opens a picker of pools and canonical models, each annotated by the policy.
    const f = fixture(); f.idle(); let offered: string[] = [];
    f.setResult({ chosen: "google/model-a", account: "google", concrete: "google/model-a", candidates: [{ account: "google", canonical: "google/model-a", remaining: 0.82, "spare-rate": 0.56 }] });
    f.ctx.ui.select = async (_title: string, options: string[]) => { offered = options; return options.find(o => o.startsWith("small")); };
    await f.tier!("", f.ctx);
    assert.deepEqual(offered.map(o => o.split(" ")[0]), ["small", "google/model-a", "mistral/model-a", "openai/model-a"], "pools first, then each canonical model once");
    assert.match(offered[0], /google\/model-a.*82% left.*spare 0\.56\/day/, offered[0]);
    assert.equal(f.entries.at(-1).data.tier, "small", "choosing a pool selects it"); tests++;
  }
  { const f = fixture(); f.idle(); f.ctx.ui.select = async () => undefined; const entries = f.entries.length; await f.tier!("", f.ctx); assert.equal(f.entries.length, entries, "a cancelled picker changes nothing"); assert.equal(f.switches, 0); tests++; }
  // Startup routing: Pi's default model (here openai/model-a) moves to the policy's account.
  {
    const pools = (setting: boolean) => ({ pi: { "default-model-pool": setting }, models: [],
      pools: [{ pool: "wide", rank: 2, candidates: [{ canonical: "openai/model-a" }] }, { pool: "top", rank: 1, candidates: [{ canonical: "openai/model-a" }] },
              { pool: "loose", rank: null, candidates: [{ canonical: "openai/model-a" }] }] });
    const start = async (setting: boolean, reason = "startup", history: any[] = [], roster: any = pools(setting)) => {
      const f = fixture("rpc", history); f.setRoster(roster); await f.emit("session_start", { reason }); return f;
    };
    { const f = await start(false); assert.equal(f.requests.at(-1)?.model, "openai/model-a", "setting false routes the named model");
      assert.equal(f.selected.provider, "google", "the session moves to the chosen account"); assert.ok(!f.entries.some(e => e.customType === "herdr-orch-tier"), "a named model records no pool"); tests++; }
    { const f = await start(true); assert.equal(f.requests.at(-1)?.model, "top", "setting true routes the lowest-ranked pool containing the model");
      assert.equal(f.entries.at(-1).data.tier, "top", "the pool is kept as the recovery scope"); tests++; }
    { const f = await start(true, "startup", [], { pi: { "default-model-pool": true }, models: [], pools: [{ pool: "loose", rank: null, candidates: [{ canonical: "openai/model-a" }] }] });
      assert.equal(f.requests.at(-1)?.model, "openai/model-a", "an unranked pool never claims the model; the named model routes"); tests++; }
    { const f = await start(false, "new"); assert.equal(f.requests.length, 1, "a /new session routes too"); tests++; }
    for (const reason of ["resume", "fork", "reload"]) { const f = await start(false, reason); assert.equal(f.requests.length, 0, `${reason} does not route`); tests++; }
    { const f = await start(false, "startup", [{ type: "message", message: { role: "user" } }]); assert.equal(f.requests.length, 0, "a continued session keeps its model"); tests++; }
    for (const flag of ["--model", "--provider", "--models", "--model=openai/model-a"]) {
      const argv = process.argv; process.argv = [...argv, flag, ...(flag.includes("=") ? [] : ["x"])];
      try { const f = await start(false); assert.equal(f.requests.length, 0, `${flag} on the command line skips startup routing`); } finally { process.argv = argv; }
      tests++;
    }
    { process.env.HERDR_ORCH_TASK = "fixture-child"; const f = await start(false); delete process.env.HERDR_ORCH_TASK; assert.equal(f.requests.length, 0, "a delegated child never routes at startup"); tests++; }
    { const f = fixture("rpc"); f.setRoster(pools(false)); f.setResult({ refused: true }); await f.emit("session_start", { reason: "startup" });
      assert.equal(f.switches, 0); assert.equal(f.selected.provider, "openai", "a refusal keeps Pi's default"); assert.match(f.notices.at(-1)!, /Startup routing kept openai\/model-a/); tests++; }
    { const f = fixture("rpc"); f.setRoster(pools(false)); f.setResult("throw"); await f.emit("session_start", { reason: "startup" });
      assert.equal(f.switches, 0); assert.match(f.notices.at(-1)!, /Startup routing kept openai\/model-a/, "a routing failure does not block the session"); tests++; }
    { const f = await start(false, "startup", [], { models: [], pools: [] }); assert.equal(f.requests.length, 1, "an absent setting means false"); tests++; }
  }
  { process.env.HERDR_ORCH_TASK = "fixture-child"; process.env.HERDR_ORCH_MODEL_POOL = "heavy";
    const f = fixture("rpc", [{ type: "custom", customType: "herdr-orch-tier", data: { tier: "small" } }]);
    delete process.env.HERDR_ORCH_TASK; delete process.env.HERDR_ORCH_MODEL_POOL;
    await f.emit("session_start"); assert.equal((await f.fail()).continue, true);
    assert.equal(f.requests.at(-1).model, "heavy", "a pool child routes within its launch pool");
    assert.deepEqual(f.requests.at(-1).current, { canonical: "openai/model-a", account: "openai" }, "same-model siblings come first");
    await f.emit("agent_start"); assert.equal(f.switches, 1); tests++; }
  { const f = fixture(); await f.emit("session_start"); await f.fail(); assert.equal(f.requests.at(-1).model, "openai/model-a", "positive control: a primary without a tier routes the failed model"); tests++; }
  {
    const f = fixture();
    f.useInventory([a, ...Array.from({ length: 4095 }, (_, i) => model("google", `model-${i}`))]);
    for (let i = 1; i < 4096; i++) {
      assert.equal((await f.fail()).continue, true, `attempt ${i + 1} is permitted below the hard cap`);
      await f.emit("agent_start");
    }
    assert.equal(f.switches, 4095);
    assert.equal(await f.fail(), undefined, "4096th failed pair stops without a 4097th attempt");
    assert.equal(f.requests.length, 4095);
    assert.match(f.notices.at(-1)!, /route attempt limit/);
    tests++;
  }
  {
    // Pi's automatic retry: each refusal switches account before the retried request.
    const f = fixture(); f.useInventory([a, b, c]);
    await f.refuse(); assert.equal(f.switches, 0, "agent_end stages without a setter");
    await f.emit("agent_start"); assert.equal(f.selected.provider, "google", "first retry uses the next account");
    await f.refuse(); await f.emit("agent_start"); assert.equal(f.selected.provider, "mistral");
    assert.deepEqual(f.requests.at(-1).attempted.map((p: any) => p.account), ["openai", "google"]);
    await f.emit("turn_start");
    await f.emit("turn_end", { message: { role: "assistant", provider: "mistral", model: "model-a", stopReason: "stop" }, messageEntryId: "ok" });
    await f.emit("agent_end", { messages: [] });
    assert.equal(await f.emit("agent_before_settle", { entries: [] }), undefined, "a recovered retry requests no continuation");
    assert.equal(f.switches, 2); assert.equal(f.aborts, 0); tests++;
  }
  {
    const f = fixture(); f.useInventory([a, b]);
    await f.fail(); await f.emit("agent_start");
    f.useInventory([a, b, c]);
    assert.equal(await f.fail(), undefined, "new inventory entries cannot extend an existing chain");
    assert.equal(f.requests.at(-1).inventory.length, 2);
    assert.match(f.notices.at(-1)!, /exhausted/); tests++;
  }
  console.log(`SUMMARY: ${tests} recovery unit scenarios passed`);
}
main().catch(error => { console.error(error); process.exitCode = 1; });

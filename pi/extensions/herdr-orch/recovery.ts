import type { Api, Model } from "@earendil-works/pi-ai";
import { getSupportedThinkingLevels } from "@earendil-works/pi-ai";
import type { ExtensionAPI, ExtensionContext } from "@earendil-works/pi-coding-agent";
import { mkdtemp, writeFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { accountBase, isAccountId, storedAccountIds, type SubcommandHandler } from "./accounts.ts";

const TIER_ENTRY = "herdr-orch-tier";
const WARNING_KEY = "herdr-orch-recovery";
export const PENDING_WARNING = "Checking recovery account authentication. Avoid changing models until this finishes. Abort stops recovery, but the account selection may still change and overwrite a newer manual selection.";
type Pair = { canonical: string; account: string };
type Level = ReturnType<ExtensionAPI["getThinkingLevel"]>;
type Candidate = { account?: string; canonical?: string; remaining?: number | null; "spare-rate"?: number | null };
type Route = { chosen?: string; account?: string; concrete?: string; refused?: boolean; candidates?: Candidate[] };
type Roster = { pools?: { pool: string; rank?: number | null; candidates?: { canonical?: string }[] }[]; models?: { canonical: string; aliases?: string[] }[];
  pi?: { "default-model-pool"?: boolean } };
// Each Pi flag that selects the starting model; the user's explicit choice is not rerouted.
const EXPLICIT_MODEL = /^--(?:model|models|provider)(?:=|$)/;
// One refresh must not hold up a selection; the account then stays expired and reports unknown quota.
const REFRESH_TIMEOUT_MS = 15_000;
const PICKER_CONCURRENCY = 8;
type Execute = (args: string[], signal?: AbortSignal, options?: { cwd?: string; timeout?: number }) => Promise<{ details: unknown }>;
const pair = (model: Model<Api>): Pair => ({ canonical: `${accountBase(model.provider) ?? model.provider}/${model.id}`, account: model.provider });
const key = (p: Pair) => `${p.account}/${p.canonical}`;
const selectable = (model?: Model<Api>) => model ? `${model.provider}/${model.id}` : "none";

/** Only explicit allowance/rate-limit failures qualify; auth and generic failures never do. */
export function quotaFailure(message: string): boolean {
  if (/auth|unauthori[sz]ed|permission|forbidden|invalid.?key|access.denied|\b40[13]\b/i.test(message)) return false;
  return /\b429\b|rate_limit_error|quota[_ -](?:exhausted|exceeded)|usage limit|insufficient_quota/i.test(message);
}

/**
 * Install quota recovery and return the `/oh model` handler, which `/oh` dispatches.
 * `/oh model <pool>` keeps the pool as the session's recovery scope; `/oh model <model>`
 * routes that model only and clears the pool. The session entry keeps its `herdr-orch-tier`
 * custom type so that sessions from before pools resume with their pool.
 * A delegated child recovers within its launch request: the launch pool that `oh`
 * injects as HERDR_ORCH_MODEL_POOL, else the failed model only. It ignores pool metadata
 * copied from a forked session and refuses `/oh model`.
 */
export function registerRecovery(pi: ExtensionAPI, execute: Execute): SubcommandHandler {
  const pinned = Boolean(process.env.HERDR_ORCH_TASK || process.env.HERDR_ORCH_CHILD || process.env.HERDR_ORCH_RESULT);
  const launchAlias = pinned ? process.env.HERDR_ORCH_MODEL_POOL || undefined : undefined;
  let active = true, generation = 0, tier: string | undefined;
  let attempted = new Map<string, Pair>();
  let chainInventory: Set<string> | undefined;
  let request: { pair: Pair; reasoning: Level; generation: number } | undefined;
  let failure: { id: string; pair: Pair; reasoning: Level; generation: number } | undefined;
  // Until `committed`, Pi's own retry may consume the stage; agent_before_settle commits it
  // to a continuation that hides `failedId`.
  let staged: { model: Model<Api>; reasoning: Level; generation: number; failedId: string; committed?: boolean } | undefined;
  let ownModel: Model<Api> | undefined;
  let setting = false;

  const report = (ctx: ExtensionContext, text: string, kind: "info" | "warning" | "error" = "info") => {
    if (!active) return;
    if (ctx.hasUI) ctx.ui.notify(text, kind);
    else process.stderr.write(`${text}\n`);
  };
  const invalidate = () => { generation++; failure = undefined; staged = undefined; attempted = new Map(); chainInventory = undefined; };
  const retainTier = (value?: string) => { tier = value; pi.appendEntry(TIER_ENTRY, { tier: value ?? null }); };
  const restore = (ctx: ExtensionContext) => {
    invalidate(); tier = launchAlias;
    if (pinned) return;
    for (const entry of ctx.sessionManager.getBranch()) {
      if (entry.type === "custom" && entry.customType === TIER_ENTRY) {
        const value = (entry.data as { tier?: unknown })?.tier;
        tier = typeof value === "string" ? value : undefined;
      }
    }
  };
  const inventory = async (ctx: ExtensionContext) => {
    const models = await ctx.modelRegistry.getAvailableOfType("chat");
    const stored = new Set(storedAccountIds());
    const usable = models.filter(model => !isAccountId(model.provider) || stored.has(model.provider));
    // Pi refreshes an expired OAuth token through its locked auth storage when asked for the
    // provider's auth, so an account that no session uses gets fresh quota evidence. A valid
    // token is only read, and a failed refresh leaves the credential unchanged.
    const registry = ctx.modelRegistry as { isUsingOAuth?: (m: Model<Api>) => boolean; getProviderAuth?: (p: string) => Promise<unknown> };
    if (registry.getProviderAuth) {
      const oauth = [...new Set(usable.filter(m => registry.isUsingOAuth?.(m)).map(m => m.provider))];
      await Promise.all(oauth.map(provider => Promise.race([
        registry.getProviderAuth!(provider).catch(() => undefined),
        new Promise(resolve => setTimeout(resolve, REFRESH_TIMEOUT_MS).unref()),
      ])));
    }
    return usable;
  };
  async function evaluate(ctx: ExtensionContext, models: Model<Api>[], model: string, current: Pair | undefined, reasoning: Level, exclusions: Pair[]) {
    if (models.length > 4096) throw new Error("Routing inventory exceeds 4096 pairs");
    const directory = await mkdtemp(join(tmpdir(), "herdr-orch-route-"));
    try {
      const path = join(directory, "request.json");
      await writeFile(path, JSON.stringify({ schema: "herdr-orch-route/v1", model, current,
        inventory: models.map(m => ({ ...pair(m), reasoning: getSupportedThinkingLevels(m) })), attempted: exclusions, reasoning }), { mode: 0o600 });
      return (await execute(["route", "--request", path], ctx.signal, { cwd: ctx.cwd, timeout: 30_000 })).details as Route;
    } finally { await rm(directory, { recursive: true, force: true }); }
  }
  async function route(ctx: ExtensionContext, model: string, current: Pair | undefined, reasoning: Level, exclusions: Pair[]) {
    let models = await inventory(ctx);
    if (current) {
      chainInventory ??= new Set(models.map(m => key(pair(m))));
      models = models.filter(m => chainInventory!.has(key(pair(m))));
    }
    const result = await evaluate(ctx, models, model, current, reasoning, exclusions);
    if (!result || result.refused) return undefined;
    const chosen = models.find(m => result.chosen === pair(m).canonical && result.account === m.provider && result.concrete === selectable(m));
    if (!chosen || exclusions.some(p => key(p) === key(pair(chosen))) || !getSupportedThinkingLevels(chosen).includes(reasoning)) throw new Error("Invalid route");
    return chosen;
  }
  async function select(ctx: ExtensionContext, model: Model<Api>, reasoning: Level, automatic: boolean, token: number): Promise<boolean> {
    if (setting || !active || token !== generation || ctx.signal?.aborted) return false;
    setting = true;
    // A distinct public model value lets model_select distinguish our event from an
    // interleaved manual selection of the same catalogue model.
    const owned = { ...model };
    ownModel = owned;
    if (automatic && ctx.hasUI) {
      ctx.ui.setStatus(WARNING_KEY, PENDING_WARNING);
      ctx.ui.setWidget(WARNING_KEY, [PENDING_WARNING]);
    }
    if (automatic) report(ctx, PENDING_WARNING, "warning");
    try {
      // known-limit: native auth awaits can commit after abort or manual selection;
      // pending requests and silent equal selections emit no reliable invalidation.
      // Revisit when Pi offers a cancellation-safe public setter or impact is unacceptable.
      const success = await pi.setModel(owned);
      if (!success) throw new Error("Selection failed");
      if (!active) return false;
      if (token !== generation || ctx.signal?.aborted) {
        report(ctx, `Recovery cancelled. Selected account/model: ${selectable(ctx.model)}. No automatic retry will run.`, "warning");
        return false;
      }
      pi.setThinkingLevel(reasoning);
      if (pi.getThinkingLevel() !== reasoning) throw new Error("Reasoning level changed");
      report(ctx, `${automatic ? "Recovery selected" : "Selected"} ${selectable(ctx.model)}; reasoning ${reasoning}.`);
      return true;
    } catch {
      report(ctx, "Recovery selection failed. No automatic retry will run.", "error");
      return false;
    } finally {
      ownModel = undefined; setting = false;
      if (active && automatic && ctx.hasUI) {
        ctx.ui.setStatus(WARNING_KEY, undefined);
        ctx.ui.setWidget(WARNING_KEY, undefined);
      }
    }
  }

  // Pi awaits session_start before it accepts a prompt, so the first request already uses
  // the routed account. Only a fresh session on Pi's default model is routed.
  pi.on("session_start", async (event, ctx) => {
    active = true; restore(ctx);
    if (event.reason === "startup" || event.reason === "new") await routeStartup(ctx);
  });
  pi.on("session_tree", (_event, ctx) => restore(ctx));
  pi.on("session_shutdown", () => { active = false; invalidate(); });
  pi.on("input", (_event, ctx) => {
    const pending = Boolean(staged?.committed || setting || failure);
    invalidate();
    if (pending && !ctx.isIdle()) void ctx.abort();
  });
  pi.on("model_select", (event, ctx) => {
    if (event.model === ownModel) return;
    // An uncommitted stage needs no abort: dropping it leaves Pi's retry on the new selection.
    const pending = Boolean(staged?.committed || setting || failure);
    invalidate();
    // A child records no tier entry; a manual selection still narrows it to that model.
    if (pinned) tier = undefined; else retainTier();
    if (pending && !ctx.isIdle()) void ctx.abort();
  });
  pi.on("agent_settled", () => invalidate());
  pi.on("agent_start", async (_event, ctx) => {
    const next = staged; staged = undefined;
    if (!next) return;
    if (!active || next.generation !== generation || ctx.signal?.aborted) { void ctx.abort(); return; }
    try {
      const models = await inventory(ctx);
      if (!models.some(m => selectable(m) === selectable(next.model)) || !await select(ctx, next.model, next.reasoning, true, next.generation)) void ctx.abort();
    } catch { report(ctx, "Recovery account is no longer available. No automatic retry will run.", "warning"); void ctx.abort(); }
  });
  pi.on("turn_start", (_event, ctx) => {
    request = ctx.model ? { pair: pair(ctx.model), reasoning: pi.getThinkingLevel(), generation } : undefined;
    failure = undefined;
  });
  pi.on("turn_end", (event) => {
    if (!request || request.generation !== generation || event.message.role !== "assistant") return;
    if (event.message.stopReason === "error" && quotaFailure(event.message.errorMessage ?? "")) {
      // The message identifies the physical request, not a mutable UI selection.
      if (event.message.provider !== request.pair.account || pair({ provider: event.message.provider, id: event.message.model } as Model<Api>).canonical !== request.pair.canonical) return;
      failure = { ...request, id: event.messageEntryId };
    }
  });
  // Routing happens at agent_end, which Pi awaits before its own automatic retry, so a
  // retried refusal goes to the staged account: agent_start applies it before the request.
  pi.on("agent_end", async (_event, ctx) => {
    const failed = failure; failure = undefined;
    if (setting || !failed || failed.generation !== generation) return;
    attempted.set(key(failed.pair), failed.pair);
    if (attempted.size >= 4096) {
      report(ctx, "Quota recovery exhausted: route attempt limit reached.", "warning"); return;
    }
    const token = generation;
    try {
      const model = await route(ctx, tier ?? failed.pair.canonical, failed.pair, failed.reasoning, [...attempted.values()]);
      if (!active || token !== generation) return;
      if (!model) { report(ctx, "Quota recovery exhausted: no eligible untried account/model in this selection.", "warning"); return; }
      attempted.set(key(pair(model)), pair(model));
      staged = { model, reasoning: failed.reasoning, generation: token, failedId: failed.id };
    } catch { if (token === generation) report(ctx, "Quota recovery stopped: routing unavailable.", "error"); }
  });
  // An uncommitted stage reaches this boundary only when Pi did not retry (non-retryable
  // message or retry budget spent). Pi's retry omits the failed attempt itself.
  pi.on("agent_before_settle", (event, ctx) => {
    const next = staged;
    if (setting || !next || next.committed || next.generation !== generation) return;
    if (ctx.hasPendingMessages()) { staged = undefined; return; }
    next.committed = true;
    return { entries: [...event.entries, { type: "context_edit" as const, targetId: next.failedId, replacement: null }], continue: true };
  });

  const roster = async (ctx: ExtensionContext) =>
    (await execute(["roster", "--format", "json"], undefined, { cwd: ctx.cwd, timeout: 30_000 })).details as Roster;
  // A name is a pool, a string alias or a canonical `provider/model` ID; anything else is refused
  // before routing, so a typo gets a usage hint instead of a routing error.
  const kind = (list: Roster, name: string): "pool" | "model" | undefined =>
    list?.pools?.some(p => p.pool === name) ? "pool"
      : name.includes("/") || list?.models?.some(m => m.aliases?.includes(name)) ? "model" : undefined;
  const percent = (value?: number | null) => typeof value === "number" ? `${Math.round(value * 100)}% left` : "quota unknown";
  const annotation = (result: Route | undefined) => {
    if (!result) return "routing unavailable";
    if (result.refused) return "no eligible account";
    const chosen = result.candidates?.find(c => c.account === result.account && c.canonical === result.chosen);
    const spare = chosen?.["spare-rate"];
    return [result.concrete, percent(chosen?.remaining), ...(typeof spare === "number" ? [`spare ${spare.toFixed(2)}/day`] : [])].join(", ");
  };
  // Pools first, then each canonical model once; every item shows the pair the policy would choose.
  async function pick(ctx: ExtensionContext): Promise<string | undefined> {
    const [list, models] = await Promise.all([roster(ctx), inventory(ctx)]);
    const pools = (list?.pools ?? []).map(p => p.pool);
    const names = [...pools, ...[...new Set(models.map(m => pair(m).canonical))].sort()];
    const labels: string[] = new Array(names.length);
    let next = 0;
    await Promise.all(Array.from({ length: Math.min(PICKER_CONCURRENCY, names.length) }, async () => {
      for (let i = next++; i < names.length; i = next++) {
        const result = await evaluate(ctx, models, names[i], undefined, pi.getThinkingLevel(), []).catch(() => undefined);
        labels[i] = `${names[i]}${i < pools.length ? " (pool)" : ""}  ->  ${annotation(result)}`;
      }
    }));
    const choice = await ctx.ui.select("Select a pool or model by quota", labels);
    const index = choice === undefined ? -1 : labels.indexOf(choice);
    return index < 0 ? undefined : names[index];
  }

  // Pi's default model is the preference. `:pi {:default-model-pool true}` reinterprets it as
  // the lowest-ranked pool that contains it; otherwise, or without such a pool, the model
  // itself is routed across its accounts. A refusal or failure keeps Pi's default.
  async function routeStartup(ctx: ExtensionContext) {
    const current = ctx.model;
    if (pinned || !current || process.argv.slice(2).some(arg => EXPLICIT_MODEL.test(arg))) return;
    // Pi records the starting model of every new session as a model change, so only a
    // conversation (a continued session) marks a model that the user already worked with.
    if (ctx.sessionManager.getBranch().some(entry => entry.type === "message")) return;
    const canonical = pair(current).canonical, token = generation;
    try {
      const list = await roster(ctx);
      const pool = list?.pi?.["default-model-pool"] === true
        ? (list.pools ?? []).filter(p => typeof p.rank === "number" && p.candidates?.some(c => c.canonical === canonical))
          .sort((x, y) => x.rank! - y.rank!)[0]?.pool
        : undefined;
      const model = await route(ctx, pool ?? canonical, undefined, pi.getThinkingLevel(), []);
      if (!model) { report(ctx, `Startup routing kept ${selectable(current)}: no eligible account/model.`, "warning"); return; }
      if (selectable(model) !== selectable(current) && !await select(ctx, model, pi.getThinkingLevel(), false, token)) return;
      if (pool) retainTier(pool);
    } catch { report(ctx, `Startup routing kept ${selectable(current)}: routing unavailable.`, "warning"); }
  }

  return async (args, ctx) => {
    if (pinned) { report(ctx, "Delegated children keep their launch selection.", "warning"); return; }
    let name = args.trim();
    // A bare query must not invalidate pending recovery, so it returns before invalidate().
    if (!name) {
      const ui = ctx.ui as { select?: unknown } | undefined;
      if (!ctx.hasUI || typeof ui?.select !== "function") {
        report(ctx, `${tier ? `Pool: ${tier}.` : "No pool selected; same-model account recovery only."} Use /oh model <pool|model>, or /oh model clear.`);
        return;
      }
      try { name = (await pick(ctx)) ?? ""; } catch { report(ctx, "Model selection failed: routing unavailable.", "error"); return; }
      if (!name) return;
    }
    invalidate();
    if (setting) { report(ctx, "An account switch is still pending. Wait for its selection report.", "warning"); return; }
    if (name === "clear") { retainTier(); report(ctx, "Pool cleared; same-model account recovery only."); return; }
    if (!ctx.isIdle()) { report(ctx, "Select a model while idle.", "warning"); return; }
    const token = generation;
    try {
      const target = kind(await roster(ctx), name);
      if (!target) { report(ctx, "Use /oh model <pool|model>; /oh models lists pools and aliases.", "warning"); return; }
      const model = await route(ctx, name, undefined, pi.getThinkingLevel(), []);
      if (!model) { report(ctx, "Model selection exhausted: no eligible account/model.", "warning"); return; }
      if (await select(ctx, model, pi.getThinkingLevel(), false, token)) retainTier(target === "pool" ? name : undefined);
    } catch { report(ctx, "Model selection failed: routing unavailable.", "error"); }
  };
}

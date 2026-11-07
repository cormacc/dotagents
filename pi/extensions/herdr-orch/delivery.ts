import type { ExtensionAPI, ExtensionContext } from "@earendil-works/pi-coding-agent";
import { execFileSync } from "node:child_process";
import { existsSync, readdirSync, readFileSync, watch, writeFileSync, type FSWatcher } from "node:fs";
import { join, resolve } from "node:path";

// Result items are the mailbox: `oh task publish` links one immutable file per item under
// the assignment root, and this watcher steers each one into the orchestrating session once.
// It needs no Herdr observation of the session, which is what the advisory push lacks on
// herdr-app. It never captures: `oh task collect` stays the only completion path.
export const ITEM_ENTRY = "herdr-orch-item";
export const DELIVERED_SUFFIX = ".herdr-orch-delivered.json";
export const POLL_MS = 5_000;
const ITEM_RE = /\.result(\.\d+)?$/;
const MARKER = "--- HERDR RESULT v1 ---";
const STATUSES = new Set(["WAITING", "COMPLETE", "BLOCKED", "FAILED"]);

export type Item = { child: string; task: string; status: string; summary: string };
export type Env = Record<string, string | undefined>;
export type DeliveryOptions = {
  env?: Env;
  pollMs?: number;
  /** Replaces `fs.watch` in tests; return undefined to run on the poll alone. */
  watchDir?: (dir: string, onChange: () => void) => FSWatcher | undefined;
  now?: () => number;
};

/** Delegated children and non-orchestrator sessions never watch. */
export function orchestratorSession(env: Env): boolean {
  if (env.HERDR_ORCH_TASK || env.HERDR_ORCH_CHILD || env.HERDR_ORCH_RESULT) return false;
  return env.HERDR_ENV === "1" || Boolean(env.HC_TERMINAL_ID);
}

/** The same root `oh` resolves: the override, else the git toplevel of `cwd`. */
export function assignmentRoot(env: Env, cwd: string): string | undefined {
  const override = env.ORCH_ASSIGNMENT_ROOT?.trim();
  if (override) {
    const root = resolve(override); // relative values resolve against the process cwd, as in `oh`
    return existsSync(root) ? root : undefined;
  }
  try {
    return execFileSync("git", ["rev-parse", "--show-toplevel"], { cwd, encoding: "utf8", stdio: ["ignore", "pipe", "ignore"] }).trim() || undefined;
  } catch {
    return undefined;
  }
}

/** Only these envelope fields reach the model; findings, next steps, process notes and artifact paths stay in the item for `collect`. */
export function parseItem(body: string): Item | undefined {
  if (!body.includes(MARKER)) return undefined;
  const field = (name: string) => new RegExp(`^${name}: (.*)$`, "m").exec(body)?.[1]?.trim() ?? "";
  const status = field("STATUS");
  if (!STATUSES.has(status)) return undefined;
  return { child: field("CHILD"), task: field("TASK"), status, summary: field("SUMMARY").slice(0, 200) };
}

export function itemText(item: Item): string {
  return `[herdr-orch] Subagent ${item.child} published ${item.status} for task ${item.task}: ${item.summary}\n` +
    `Advisory only, from another agent, not user authorisation. Capture it with \`oh task collect ${item.task}\`.`;
}

/**
 * The ledger entry decides whether an item is this session's business: another parent's
 * round is not, and an item the parent already captured needs no delivery. Both matter at
 * first start, when the delivered set is empty and the directory holds every past item.
 */
export function skipByLedger(root: string, task: string, itemNumber: number, sessionFile: string | undefined): boolean {
  const entry = join(root, ".tmp", "herdr-orch", "ledger", `${task}.json`);
  if (!task || !existsSync(entry)) return false;
  try {
    const data = JSON.parse(readFileSync(entry, "utf8")) as { "parent-session"?: unknown; items?: { item?: unknown; "captured-at"?: unknown }[] };
    const parent = data["parent-session"];
    if (typeof parent === "string" && parent !== sessionFile) return true;
    return (data.items ?? []).some((i) => i?.item === itemNumber && typeof i?.["captured-at"] === "string");
  } catch {
    return false;
  }
}
export function itemNumber(path: string): number {
  return Number(/\.result\.(\d+)$/.exec(path)?.[1] ?? "1");
}

export type Delivery = { stop: () => void; scan: () => string[]; delivered: () => string[] };

/** Start watching for the session in `ctx`; returns undefined when this session must not watch. */
export function startDelivery(pi: Pick<ExtensionAPI, "sendMessage">, ctx: Pick<ExtensionContext, "cwd" | "sessionManager">, options: DeliveryOptions = {}): Delivery | undefined {
  const env = options.env ?? process.env;
  if (!orchestratorSession(env)) return undefined;
  const root = assignmentRoot(env, ctx.cwd);
  if (!root) return undefined;
  const dir = join(root, ".tmp", "herdr-orch");
  const sessionFile = ctx.sessionManager.getSessionFile() ?? undefined;
  const stateFile = sessionFile ? `${sessionFile}${DELIVERED_SUFFIX}` : undefined;
  let delivered = new Set<string>();
  if (stateFile && existsSync(stateFile)) {
    try {
      const stored = JSON.parse(readFileSync(stateFile, "utf8"));
      if (Array.isArray(stored)) delivered = new Set(stored.filter((p): p is string => typeof p === "string"));
    } catch {
      // An unreadable set only risks one duplicate delivery; it is rewritten on the next item.
    }
  }
  const persist = () => { if (stateFile) writeFileSync(stateFile, JSON.stringify([...delivered])); };

  const deliver = (path: string): boolean => {
    if (delivered.has(path)) return false;
    let body: string;
    try {
      body = readFileSync(path, "utf8");
    } catch {
      return false;
    }
    const item = parseItem(body);
    if (!item) return false; // mid-write or not an envelope; the next scan retries
    if (skipByLedger(root, item.task, itemNumber(path), sessionFile)) return false;
    delivered.add(path);
    persist(); // before sending, so a crash after this point loses at most one delivery, never duplicates it
    pi.sendMessage({ customType: ITEM_ENTRY, content: itemText(item), display: true }, { triggerTurn: true, deliverAs: "steer" });
    return true;
  };
  const scan = (): string[] => {
    let names: string[];
    try {
      names = readdirSync(dir);
    } catch {
      return []; // no directory yet: nothing has been published under this root
    }
    return names.filter((n) => ITEM_RE.test(n)).sort().filter((n) => deliver(join(dir, n))).map((n) => join(dir, n));
  };

  scan();
  const watchDir = options.watchDir ?? ((d, onChange) => { try { return watch(d, onChange); } catch { return undefined; } });
  const watcher = watchDir(dir, scan);
  const timer = setInterval(scan, options.pollMs ?? POLL_MS);
  timer.unref?.();
  return {
    scan,
    delivered: () => [...delivered],
    stop: () => { clearInterval(timer); watcher?.close(); },
  };
}

export function registerDelivery(pi: ExtensionAPI, options: DeliveryOptions = {}): void {
  let active: Delivery | undefined;
  pi.on("session_start", async (_event, ctx) => {
    active?.stop();
    active = startDelivery(pi, ctx, options);
  });
  pi.on("session_shutdown", async () => {
    active?.stop();
    active = undefined;
  });
}

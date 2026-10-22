// herdr-orch Pi fork adapter: an explicitly loaded extension (`pi -e fork-adapter.ts`), never
// installed into Pi auto-discovery. It forks the *caller's own* persistent session at the safe
// pre-launch boundary using only Pi's public SessionManager API, so the JSONL format is never
// rewritten here. It runs inside the installed Pi process; see ./runtime.ts.
//
// Boundary rule: the launching tool call is the in-flight one, so the active leaf is the
// launching assistant entry, or tool results of that same batch that already finished. The
// child branches before the caller's current turn: the first message after the caller's last
// completed assistant response. The child inherits only completed turns, so its
// assignment is its only open request; an inherited unanswered request is otherwise taken as
// the child's own work. The caller's request, steering, failed attempts and tool work of the
// current turn, including the launching batch, stay outside the child's active context.
// Anything that does not prove this shape fails closed before a fork file exists.
import { createHash } from "node:crypto";
import { existsSync, readFileSync, realpathSync, statSync, unlinkSync, writeFileSync } from "node:fs";
import { CURRENT_SESSION_VERSION, SessionManager, VERSION } from "@earendil-works/pi-coding-agent";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { runtimeIdentity } from "./runtime.ts";

export const FORK_MARKER = "herdr-orch-fork";
export const BOUNDARY_RULE =
  "Branch before the first message after the caller's last completed assistant response, so the current turn and its launching tool batch stay outside the child";

// Entries that never enter model context and may be appended between a batch's assistant
// message and its results. Anything else there (a user message, compaction, system message,
// custom message) means the leaf is not provably inside one in-flight batch.
const METADATA_ONLY = new Set(["usage", "label", "session_info", "custom", "thinking_level_change", "model_change"]);

export interface RoleSnapshot {
  persona: string;
  text: string;
  sha256: string;
}

export interface ForkRequest {
  sourceSession: string;
  targetCwd: string;
  sessionDir?: string;
  // An explicit persona overlay replaces any inherited named role.
  overlay?: { persona: string; text: string };
  // A named caller must supply its launch-time snapshot for implicit inheritance; an unnamed
  // caller inherits no role.
  callerRole?: { named: boolean; snapshot?: RoleSnapshot };
}

export type SelectedRole =
  | { source: "none" }
  | { source: "overlay" | "snapshot"; persona: string; text: string; sha256: string };

export interface ForkPlan {
  source: { path: string; sessionId: string; sha256: string; leafId: string; entries: number };
  boundary: { entryId: string; batchEntryId: string; unansweredToolCalls: string[]; answeredToolCalls: string[]; messages: number; rule: string };
  role: SelectedRole;
  targetCwd: string;
}

export interface ForkResult extends ForkPlan {
  ok: true;
  sessionFile: string;
  sessionId: string;
  marker: { entryId: string; customType: string };
  runtime: ReturnType<typeof runtimeIdentity>;
}

const sha256 = (data: string | Buffer) => createHash("sha256").update(data).digest("hex");
const refuse = (message: string): never => {
  throw new Error(`fork refused: ${message}`);
};

export function forkCapability(): { ok: boolean; missing: string[]; piVersion: string } {
  const sm = SessionManager as unknown as Record<string, unknown>;
  const proto = (SessionManager as unknown as { prototype: Record<string, unknown> }).prototype;
  const missing = [
    ...["forkFrom", "inMemory"].filter((n) => typeof sm[n] !== "function").map((n) => `SessionManager.${n}`),
    ...["branch", "appendCustomEntry", "buildSessionContext", "getSessionFile"].filter((n) => typeof proto[n] !== "function").map((n) => `SessionManager#${n}`),
  ];
  if (typeof CURRENT_SESSION_VERSION !== "number") missing.push("CURRENT_SESSION_VERSION");
  return { ok: missing.length === 0, missing, piVersion: VERSION };
}

export function selectRole(request: Pick<ForkRequest, "overlay" | "callerRole">): SelectedRole {
  const { overlay, callerRole } = request;
  if (overlay) {
    if (!overlay.persona || !overlay.text) refuse("an explicit persona overlay needs a persona name and role text");
    return { source: "overlay", persona: overlay.persona, text: overlay.text, sha256: sha256(overlay.text) };
  }
  if (!callerRole?.named) return { source: "none" };
  const snapshot = callerRole.snapshot;
  if (!snapshot) {
    return refuse("the named caller has no recorded role snapshot; pass an explicit persona overlay instead of inheriting the role");
  }
  if (!snapshot.persona || typeof snapshot.text !== "string" || sha256(snapshot.text) !== snapshot.sha256) {
    return refuse("the recorded role snapshot does not match its hash");
  }
  return { source: "snapshot", persona: snapshot.persona, text: snapshot.text, sha256: snapshot.sha256 };
}

// Strict read: forkFrom itself skips malformed lines, which would hide a live partial write.
function readSource(path: string) {
  if (!existsSync(path)) return refuse(`source session is missing: ${path}`);
  const real = realpathSync(path);
  if (!statSync(real).isFile()) return refuse(`source session is not a file: ${real}`);
  const bytes = readFileSync(real);
  const lines = bytes.toString("utf8").split("\n");
  const parsed: any[] = [];
  lines.forEach((line, index) => {
    if (!line.trim()) return;
    try {
      parsed.push(JSON.parse(line));
    } catch {
      refuse(`source session line ${index + 1} is not valid JSON (a partial write is ambiguous)`);
    }
  });
  const header = parsed[0];
  if (!header || header.type !== "session") return refuse("source session has no header");
  if (header.version !== CURRENT_SESSION_VERSION) refuse(`source session is version ${header.version}, not ${CURRENT_SESSION_VERSION}`);
  const entries = parsed.slice(1);
  const ids = new Set<string>();
  for (const entry of entries) {
    if (entry.type === "session" || typeof entry.id !== "string" || ids.has(entry.id)) refuse("source session entries are not uniquely identified");
    ids.add(entry.id);
  }
  if (entries.length === 0) return refuse("source session has no entries (ephemeral or empty)");
  return { real, bytes, header, entries, all: parsed };
}

function activePath(entries: any[]): any[] {
  const byId = new Map(entries.map((e) => [e.id, e]));
  const path: any[] = [];
  const seen = new Set<string>();
  let cursor: any = entries[entries.length - 1];
  while (cursor) {
    if (seen.has(cursor.id)) return refuse("source session entry graph has a cycle");
    seen.add(cursor.id);
    path.push(cursor);
    if (cursor.parentId === null) break;
    cursor = byId.get(cursor.parentId);
    if (!cursor) return refuse("source session entry graph has a dangling parent");
  }
  return path.reverse();
}

const toolCallIds = (message: any): string[] =>
  (Array.isArray(message?.content) ? message.content : []).filter((b: any) => b?.type === "toolCall").map((b: any) => b.id);

export function findLaunchBoundary(entries: any[]) {
  const path = activePath(entries);
  const answered: string[] = [];
  for (let i = path.length - 1; i >= 0; i--) {
    const entry = path[i];
    if (entry.type === "message" && entry.message?.role === "toolResult") {
      answered.push(entry.message.toolCallId);
      continue;
    }
    if (entry.type !== "message") {
      if (METADATA_ONLY.has(entry.type)) continue;
      return refuse(`active leaf is not inside an in-flight tool batch (found ${entry.type} entry ${entry.id})`);
    }
    if (entry.message?.role !== "assistant") {
      return refuse(`active leaf is not inside an in-flight tool batch (found ${entry.message?.role} message ${entry.id})`);
    }
    const calls = toolCallIds(entry.message);
    if (calls.length === 0) return refuse(`the nearest assistant entry ${entry.id} has no tool calls to launch from`);
    const foreign = answered.filter((id) => !calls.includes(id));
    if (foreign.length > 0) return refuse(`tool results ${foreign.join(", ")} do not belong to the batch of assistant entry ${entry.id}`);
    const unanswered = calls.filter((id) => !answered.includes(id));
    if (unanswered.length === 0) return refuse(`every tool call of assistant entry ${entry.id} is answered, so no launching call is in flight`);
    if (entry.parentId === null) return refuse(`the launching batch ${entry.id} has no pre-launch boundary (null parent); empty history is not substituted`);
    return { batchEntryId: entry.id as string, boundaryEntryId: turnBoundary(path, i), unanswered, answered };
  }
  return refuse("no assistant tool batch found on the active branch");
}

// A completed response ends a turn: stop reason "stop" and no tool calls. An error, aborted,
// length or deferred response does not, so its turn is dropped with the current one: losing
// context is safe, inheriting an unanswered request is not. The turn starts at its first
// message of any kind, because Pi can also open a run with a custom message (an extension's
// triggerTurn) or continue one without a new request. Entries before that message, such as a
// compaction Pi appends before the request, stay on the child's path; a compaction inside the
// current turn does not.
function turnBoundary(path: any[], batchIndex: number): string {
  let start: any;
  for (let i = batchIndex - 1; i >= 0; i--) {
    const message = path[i].type === "message" ? path[i].message : undefined;
    if (message?.role === "assistant" && message.stopReason === "stop" && toolCallIds(message).length === 0) {
      return start ? start.parentId : refuse(`nothing of the current turn precedes the launching batch after the caller's last completed response ${path[i].id}`);
    }
    if (message || path[i].type === "custom_message") start = path[i];
  }
  return refuse("the caller has no completed earlier turn to inherit; use --context fresh and put the needed context in the assignment");
}

function projectedMessages(all: any[], boundaryEntryId: string): any[] {
  const sm = SessionManager.inMemory(undefined, undefined, structuredClone(all));
  sm.branch(boundaryEntryId);
  return sm.buildSessionContext().messages;
}

function assertSelfContained(messages: any[]) {
  if (messages.length === 0) refuse("the pre-launch boundary yields no conversation; empty history is not substituted");
  const calls = new Set<string>();
  const results = new Set<string>();
  for (const message of messages) {
    for (const id of toolCallIds(message)) calls.add(id);
    if (message?.role === "toolResult") results.add(message.toolCallId);
  }
  const unmatched = [...calls].filter((id) => !results.has(id));
  if (unmatched.length > 0) refuse(`the pre-launch context holds unmatched tool calls: ${unmatched.join(", ")}`);
}

// Read-only: touches neither the source nor the filesystem beyond reading it.
export function planFork(request: ForkRequest): ForkPlan & { messages: any[]; bytes: Buffer } {
  if (!request.targetCwd || !existsSync(request.targetCwd) || !statSync(request.targetCwd).isDirectory()) {
    refuse(`target checkout is not an existing directory: ${request.targetCwd}`);
  }
  const role = selectRole(request);
  const source = readSource(request.sourceSession);
  const found = findLaunchBoundary(source.entries);
  const messages = projectedMessages(source.all, found.boundaryEntryId);
  assertSelfContained(messages);
  return {
    source: { path: source.real, sessionId: source.header.id, sha256: sha256(source.bytes), leafId: source.entries[source.entries.length - 1].id, entries: source.entries.length },
    boundary: { entryId: found.boundaryEntryId, batchEntryId: found.batchEntryId, unansweredToolCalls: found.unanswered, answeredToolCalls: found.answered, messages: messages.length, rule: BOUNDARY_RULE },
    role,
    targetCwd: realpathSync(request.targetCwd),
    messages,
    bytes: source.bytes,
  };
}

// Removes only a session this adapter created: the header must name this session id and the
// recorded source as its parent. A source or foreign file is never removed.
export function discardFork(result: Pick<ForkResult, "sessionFile" | "sessionId" | "source">): { removed: boolean } {
  if (result.sessionFile === result.source.path) refuse("discard would remove the source session");
  if (!existsSync(result.sessionFile)) return { removed: false };
  const header = JSON.parse(readFileSync(result.sessionFile, "utf8").split("\n", 1)[0]);
  if (header.type !== "session" || header.id !== result.sessionId || header.parentSession !== result.source.path) {
    refuse(`discard target is not a fork this adapter created: ${result.sessionFile}`);
  }
  unlinkSync(result.sessionFile);
  return { removed: true };
}

export function createFork(request: ForkRequest): ForkResult {
  const capability = forkCapability();
  if (!capability.ok) refuse(`installed Pi lacks ${capability.missing.join(", ")}`);
  const plan = planFork(request);
  const { messages, bytes, ...provenance } = plan;
  const child = SessionManager.forkFrom(provenance.source.path, provenance.targetCwd, request.sessionDir);
  const sessionFile = child.getSessionFile() as string;
  const partial = { sessionFile, sessionId: child.getSessionId(), source: provenance.source };
  try {
    if (!readFileSync(provenance.source.path).equals(bytes)) refuse("the source session changed while forking");
    child.branch(provenance.boundary.entryId);
    const markerId = child.appendCustomEntry(FORK_MARKER, {
      sourceSession: provenance.source.path,
      sourceSessionId: provenance.source.sessionId,
      boundaryEntryId: provenance.boundary.entryId,
      batchEntryId: provenance.boundary.batchEntryId,
    });
    // The child's active context must be exactly the projection proved above, with no
    // trace of the launching batch or of the marker.
    if (JSON.stringify(child.buildSessionContext().messages) !== JSON.stringify(messages)) refuse("the forked context differs from the proved pre-launch projection");
    const header = JSON.parse(readFileSync(sessionFile, "utf8").split("\n", 1)[0]);
    if (header.cwd !== provenance.targetCwd) refuse(`forked session header cwd ${header.cwd} is not the target checkout ${provenance.targetCwd}`);
    return { ok: true, ...provenance, sessionFile, sessionId: partial.sessionId, marker: { entryId: markerId, customType: FORK_MARKER }, runtime: runtimeIdentity() };
  } catch (error) {
    // known-limit: a failure inside forkFrom itself (disk error mid-copy) leaves a partial file
    // this adapter cannot name; revisit if Pi's forkFrom ever exposes the path before copying.
    discardFork(partial);
    throw error;
  }
}

// Inert unless HERDR_ORCH_ADAPTER selects an operation, so loading it into an ordinary
// session by mistake does nothing. The request and result travel as files, not argv.
export default function (pi: ExtensionAPI) {
  pi.on("session_start", async () => {
    const op = process.env.HERDR_ORCH_ADAPTER;
    if (!op || !op.startsWith("fork-")) return;
    const resultPath = process.env.HERDR_ORCH_ADAPTER_RESULT;
    let out: unknown;
    let code = 0;
    try {
      const request = process.env.HERDR_ORCH_ADAPTER_REQUEST ? JSON.parse(readFileSync(process.env.HERDR_ORCH_ADAPTER_REQUEST, "utf8")) : {};
      if (op === "fork-capability") out = { ok: true, ...forkCapability(), runtime: runtimeIdentity() };
      else if (op === "fork-create") out = createFork(request);
      else if (op === "fork-discard") out = { ok: true, ...discardFork(request) };
      else refuse(`unknown adapter operation ${op}`);
    } catch (error) {
      code = 1;
      out = { ok: false, error: error instanceof Error ? error.message : String(error) };
    }
    if (resultPath) writeFileSync(resultPath, JSON.stringify(out));
    process.exit(code);
  });
}

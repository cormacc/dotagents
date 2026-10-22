// In-process checks for fork-adapter.ts. Loaded only by ./run-tests.ts inside the installed Pi
// runtime (see ./test.sh); every fixture is a synthetic session under a temporary directory.
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { spawnSync } from "node:child_process";
import { appendFileSync, existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, realpathSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { SessionManager } from "@earendil-works/pi-coding-agent";
import { createFork, discardFork, findLaunchBoundary, forkCapability, planFork, selectRole, FORK_MARKER, BOUNDARY_RULE } from "./fork-adapter.ts";
import { assertRuntime, runtimeIdentity } from "./runtime.ts";
import type { TestCase } from "./runtime.ts";

const here = dirname(fileURLToPath(import.meta.url));
const sha = (data: string | Buffer) => createHash("sha256").update(data).digest("hex");
const now = () => Date.now();

const user = (text: string) => ({ role: "user", content: text, timestamp: now() });
const text = (t: string) => ({ type: "text", text: t });
const assistant = (...content: any[]) => ({
  role: "assistant", content, api: "fixture", provider: "fixture", model: "fixture",
  usage: { input: 1, output: 1, cacheRead: 0, cacheWrite: 0, totalTokens: 2, cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0 } },
  stopReason: "stop", timestamp: now(),
});
const call = (id: string, name = "bash") => ({ type: "toolCall", id, name, arguments: { command: `echo ${id}` } });
const result = (id: string, out = "ok") => ({ role: "toolResult", toolCallId: id, toolName: "bash", content: [text(out)], isError: false, timestamp: now() });
// A completed earlier turn: the minimum history a fork can inherit.
const priorTurn = (w: World) => { w.sm.appendMessage(user("earlier request") as any); return w.sm.appendMessage(assistant(text("earlier answer")) as any); };

interface World { root: string; source: string; target: string; forks: string; sm: SessionManager }
function world(): World {
  const root = realpathSync(mkdtempSync(join(tmpdir(), "herdr-fork-")));
  const target = join(root, "target-checkout");
  const sourceDir = join(root, "source-sessions");
  const forks = join(root, "fork-sessions");
  mkdirSync(target);
  mkdirSync(forks);
  const sm = SessionManager.create(join(root, "source-checkout"), sourceDir);
  return { root, source: "", target, forks, sm };
}
function finish(w: World): World {
  w.source = w.sm.getSessionFile() as string;
  return w;
}
const cleanup = (w: World) => rmSync(w.root, { recursive: true, force: true });
const listForks = (w: World) => readdirSync(w.forks);
async function inWorld(build: (w: World) => void, body: (w: World) => void | Promise<void>) {
  const w = world();
  try {
    build(w);
    finish(w);
    await body(w);
  } finally {
    cleanup(w);
  }
}
const roles = (messages: any[]) => messages.map((m) => m.role);

// The reference conversation: compaction, a dead sibling branch, then an in-flight parallel
// batch with one sibling result already appended.
function compactedMultiBranch(w: World) {
  const { sm } = w;
  sm.appendMessage(user("first question") as any);
  const a1 = sm.appendMessage(assistant(text("first answer")) as any);
  sm.appendMessage(user("DEAD BRANCH question") as any);
  sm.appendMessage(assistant(text("DEAD BRANCH answer")) as any);
  sm.branch(a1);
  const u2 = sm.appendMessage(user("second question") as any);
  sm.appendMessage(assistant(text("thinking"), call("early")) as any);
  sm.appendMessage(result("early") as any);
  sm.appendMessage(assistant(text("second answer")) as any);
  sm.appendCompaction("SUMMARY OF EARLY WORK", u2, 1000);
  sm.appendMessage(user("third question") as any);
  sm.appendMessage(assistant(text("launching"), call("launch"), call("sibling-a"), call("sibling-b")) as any);
  sm.appendMessage(result("sibling-a", "SIBLING RESULT") as any);
}

const snapshotOf = (persona: string, body: string) => ({ persona, text: body, sha256: sha(body) });
const base = (w: World, extra: object = {}) => ({ sourceSession: w.source, targetCwd: w.target, sessionDir: w.forks, ...extra });
const refusal = (fn: () => unknown, pattern: RegExp) => assert.throws(fn, (e: any) => pattern.test(e.message) || (assert.fail(`message ${e.message} !~ ${pattern}`), false));

function piRun(args: string[], env: Record<string, string>, cwd: string) {
  return spawnSync(process.execPath, [process.argv[1], "--offline", "--no-session", "-ne", ...args, "--no-skills", "--no-prompt-templates", "--no-context-files", "--no-mcp", "-p", "x"], {
    env: { ...process.env, PI_OFFLINE: "1", ...env }, cwd, encoding: "utf8", timeout: 60000, stdio: ["ignore", "pipe", "pipe"],
  });
}

export const tests: TestCase[] = [
  { name: "installed runtime supplies the required session API", run() {
    const capability = forkCapability();
    assert.deepEqual(capability.missing, []);
    assert.equal(capability.ok, true);
  } },

  { name: "runtime expectation mismatch is an error", run() {
    const identity = runtimeIdentity();
    assert.deepEqual(assertRuntime({ version: identity.piVersion }), identity);
    refusal(() => assertRuntime({ version: "0.0.0-not-installed" }), /runtime mismatch: expected Pi 0\.0\.0-not-installed/);
    refusal(() => assertRuntime({ forbiddenBundlePrefix: dirname(identity.bundle) }), /forbidden path/);
  } },

  { name: "fork keeps the compacted active projection and excludes the current turn", async run() {
    await inWorld(compactedMultiBranch, (w) => {
      const before = readFileSync(w.source);
      const forked = createFork(base(w) as any);
      assert.deepEqual(roles(planFork(base(w) as any).messages), ["compactionSummary", "user", "assistant", "toolResult", "assistant"]);
      const child = SessionManager.open(forked.sessionFile);
      const messages = child.buildSessionContext().messages as any[];
      assert.deepEqual(roles(messages), ["compactionSummary", "user", "assistant", "toolResult", "assistant"]);
      const flat = JSON.stringify(messages);
      assert.match(flat, /SUMMARY OF EARLY WORK/);
      assert.match(flat, /second answer/);
      for (const absent of ["DEAD BRANCH", "third question", "launch", "sibling-a", "sibling-b", "SIBLING RESULT", "launching"]) assert.equal(flat.includes(absent), false, absent);
      assert.equal(forked.boundary.messages, messages.length);
      assert.equal(forked.boundary.rule, BOUNDARY_RULE);
      assert.deepEqual(forked.boundary.unansweredToolCalls, ["launch", "sibling-b"]);
      assert.deepEqual(forked.boundary.answeredToolCalls, ["sibling-a"]);
      assert.equal(readFileSync(w.source).equals(before), true, "source bytes unchanged");
      assert.equal(forked.source.sha256, sha(before));
    });
  } },

  { name: "forked session is independent, rooted at the target checkout, and marks its selected branch", async run() {
    await inWorld(compactedMultiBranch, (w) => {
      const sourceEntries = readFileSync(w.source, "utf8").trim().split("\n").map((l) => JSON.parse(l));
      const forked = createFork(base(w) as any);
      assert.notEqual(forked.sessionFile, w.source);
      assert.equal(dirname(forked.sessionFile), w.forks);
      const lines = readFileSync(forked.sessionFile, "utf8").trim().split("\n").map((l) => JSON.parse(l));
      assert.equal(lines[0].cwd, w.target, "session header cwd is the target checkout");
      assert.equal(lines[0].parentSession, w.source);
      assert.equal(lines[0].id, forked.sessionId);
      const marker = lines[lines.length - 1];
      assert.equal(marker.type, "custom");
      assert.equal(marker.customType, FORK_MARKER);
      assert.equal(marker.id, forked.marker.entryId);
      assert.equal(marker.parentId, forked.boundary.entryId, "marker extends the selected boundary entry");
      assert.equal(marker.data.batchEntryId, forked.boundary.batchEntryId);
      // The launching batch survives only as an inactive branch in the file, never in context.
      assert.equal(lines.length, sourceEntries.length + 1);
      assert.equal(forked.source.leafId, sourceEntries[sourceEntries.length - 1].id);
      const reopened = SessionManager.open(forked.sessionFile);
      assert.equal(reopened.getLeafId(), marker.id);
      assert.equal(reopened.getCwd(), w.target);
    });
  } },

  { name: "an unanswered-only batch and a partly answered batch both fork; a fully answered one refuses", async run() {
    await inWorld((w) => {
      priorTurn(w);
      w.sm.appendMessage(user("go") as any);
      w.sm.appendMessage(assistant(call("only")) as any);
    }, (w) => assert.equal(createFork(base(w) as any).boundary.messages, 2));
    await inWorld((w) => {
      priorTurn(w);
      w.sm.appendMessage(user("go") as any);
      w.sm.appendMessage(assistant(call("a"), call("b"), call("c")) as any);
      w.sm.appendMessage(result("b") as any);
      w.sm.appendMessage(result("a") as any);
    }, (w) => assert.deepEqual(planFork(base(w) as any).boundary.unansweredToolCalls, ["c"]));
    await inWorld((w) => {
      priorTurn(w);
      w.sm.appendMessage(user("go") as any);
      w.sm.appendMessage(assistant(call("a")) as any);
      w.sm.appendMessage(result("a") as any);
    }, (w) => {
      refusal(() => createFork(base(w) as any), /every tool call .* is answered/);
      assert.deepEqual(listForks(w), []);
    });
  } },

  { name: "steering, a failed attempt and a mid-turn compaction stay outside the child", async run() {
    let done = "";
    await inWorld((w) => {
      done = priorTurn(w);
      const request = w.sm.appendMessage(user("FORK REQUEST") as any);
      w.sm.appendMessage({ ...assistant(text("FAILED ATTEMPT")), stopReason: "error", errorMessage: "transient" } as any);
      w.sm.appendMessage(user("RETRY STEER") as any);
      w.sm.appendMessage(assistant(call("work")) as any);
      w.sm.appendMessage(result("work", "TURN WORK") as any);
      w.sm.appendCompaction("MID-TURN SUMMARY", request, 1000);
      w.sm.appendMessage(assistant(call("launch")) as any);
    }, (w) => {
      const forked = createFork(base(w) as any);
      assert.equal(forked.boundary.entryId, done, "the child branches at the last completed response");
      const messages = SessionManager.open(forked.sessionFile).buildSessionContext().messages as any[];
      assert.deepEqual(roles(messages), ["user", "assistant"]);
      const flat = JSON.stringify(messages);
      assert.match(flat, /earlier answer/);
      for (const absent of ["FORK REQUEST", "FAILED ATTEMPT", "RETRY STEER", "TURN WORK", "MID-TURN SUMMARY", "launch"]) assert.equal(flat.includes(absent), false, absent);
    });
  } },

  { name: "a turn opened by a custom message or a continuation stays outside the child, even with steering", async run() {
    // Pi opens a run from an extension's triggerTurn custom message, or continues one with no new
    // request; a later steering message must not be taken as the turn start.
    const openers: [string, (w: World) => void][] = [
      ["custom message", (w) => { w.sm.appendCustomMessageEntry("jira", "CUSTOM CURRENT REQUEST", false); }],
      ["continuation", () => {}],
    ];
    for (const [label, open] of openers) {
      let done = "";
      await inWorld((w) => {
        done = priorTurn(w);
        open(w);
        w.sm.appendMessage(assistant(call("work")) as any);
        w.sm.appendMessage(result("work", "TURN WORK") as any);
        w.sm.appendMessage(user("STEERING") as any);
        w.sm.appendMessage(assistant(call("launch")) as any);
      }, (w) => {
        const forked = createFork(base(w) as any);
        assert.equal(forked.boundary.entryId, done, label);
        const flat = JSON.stringify(SessionManager.open(forked.sessionFile).buildSessionContext().messages);
        for (const absent of ["CUSTOM CURRENT REQUEST", "TURN WORK", "STEERING", "launch"]) assert.equal(flat.includes(absent), false, `${label}: ${absent}`);
      });
    }
  } },

  { name: "a compaction appended before the turn's request stays on the child's path", async run() {
    await inWorld((w) => {
      const u0 = w.sm.appendMessage(user("earlier request") as any);
      w.sm.appendMessage(assistant(text("earlier answer")) as any);
      w.sm.appendCompaction("BETWEEN-TURN SUMMARY", u0, 1000);
      w.sm.appendMessage(user("CURRENT REQUEST") as any);
      w.sm.appendMessage(assistant(call("launch")) as any);
    }, (w) => {
      const flat = JSON.stringify(SessionManager.open(createFork(base(w) as any).sessionFile).buildSessionContext().messages);
      assert.match(flat, /BETWEEN-TURN SUMMARY/);
      assert.equal(flat.includes("CURRENT REQUEST"), false);
    });
  } },

  { name: "metadata-only entries after the batch do not hide the boundary", async run() {
    await inWorld((w) => {
      priorTurn(w);
      w.sm.appendMessage(user("go") as any);
      w.sm.appendMessage(assistant(call("a")) as any);
      w.sm.appendCustomEntry("some-extension", { x: 1 });
      w.sm.appendThinkingLevelChange("high");
    }, (w) => assert.equal(findLaunchBoundary(readFileSync(w.source, "utf8").trim().split("\n").slice(1).map((l) => JSON.parse(l))).batchEntryId.length > 0, true));
  } },

  { name: "ambiguous or non-launching leaves refuse before a fork file exists", async run() {
    const cases: [string, (w: World) => void, RegExp][] = [
      ["leaf is a user message", (w) => { w.sm.appendMessage(user("hi") as any); w.sm.appendMessage(assistant(call("a")) as any); w.sm.appendMessage(result("a") as any); w.sm.appendMessage(assistant(text("done")) as any); w.sm.appendMessage(user("next") as any); }, /not inside an in-flight tool batch/],
      ["leaf is a text-only assistant", (w) => { w.sm.appendMessage(user("hi") as any); w.sm.appendMessage(assistant(text("hello")) as any); }, /has no tool calls/],
      ["results from another batch", (w) => { w.sm.appendMessage(user("hi") as any); w.sm.appendMessage(assistant(call("a")) as any); w.sm.appendMessage(result("zzz") as any); }, /do not belong to the batch/],
      ["a compaction sits between", (w) => { w.sm.appendMessage(user("hi") as any); const a = w.sm.appendMessage(assistant(call("a")) as any); w.sm.appendCompaction("s", a, 1); }, /not inside an in-flight tool batch/],
      ["null parent boundary", (w) => { w.sm.appendMessage(assistant(call("a")) as any); }, /null parent/],
      ["no history before the batch", (w) => { w.sm.appendModelChange("p", "m"); w.sm.appendMessage(assistant(call("a")) as any); }, /no completed earlier turn/],
      ["first turn of the caller", (w) => { w.sm.appendMessage(user("go") as any); w.sm.appendMessage(assistant(call("a")) as any); }, /no completed earlier turn/],
      ["launch directly after a completed response", (w) => { priorTurn(w); w.sm.appendMessage(assistant(call("a")) as any); }, /nothing of the current turn precedes the launching batch/],
    ];
    for (const [label, build, pattern] of cases) {
      await inWorld(build, (w) => {
        refusal(() => createFork(base(w) as any), pattern);
        assert.deepEqual(listForks(w), [], label);
      });
    }
  } },

  { name: "unreadable, ephemeral, malformed or old-version sources refuse", async run() {
    const w = world();
    try {
      const nothing = { sourceSession: join(w.root, "missing.jsonl"), targetCwd: w.target, sessionDir: w.forks };
      refusal(() => createFork(nothing as any), /source session is missing/);
      const empty = join(w.root, "empty.jsonl");
      writeFileSync(empty, JSON.stringify({ type: "session", version: 3, id: "e", timestamp: "t", cwd: w.root }) + "\n");
      refusal(() => createFork({ ...nothing, sourceSession: empty } as any), /no entries/);
      const old = join(w.root, "old.jsonl");
      writeFileSync(old, JSON.stringify({ type: "session", version: 2, id: "o", timestamp: "t", cwd: w.root }) + "\n");
      refusal(() => createFork({ ...nothing, sourceSession: old } as any), /version 2/);
      const partial = join(w.root, "partial.jsonl");
      writeFileSync(partial, JSON.stringify({ type: "session", version: 3, id: "p", timestamp: "t", cwd: w.root }) + "\n{\"type\":\"message\",\"id\":\"x\"");
      refusal(() => createFork({ ...nothing, sourceSession: partial } as any), /not valid JSON/);
      refusal(() => createFork({ ...nothing, sourceSession: empty, targetCwd: join(w.root, "no-such-dir") } as any), /not an existing directory/);
      assert.deepEqual(listForks(w), []);
    } finally {
      cleanup(w);
    }
  } },

  { name: "a source that changes during forking is discarded", async run() {
    await inWorld(compactedMultiBranch, (w) => {
      const entries = readFileSync(w.source, "utf8");
      const original = SessionManager.forkFrom;
      // Simulate the parent appending between analysis and copy by growing the source from
      // inside the real forkFrom call.
      (SessionManager as any).forkFrom = (...args: any[]) => {
        const child = original.apply(SessionManager, args as any);
        appendFileSync(w.source, JSON.stringify({ type: "custom", id: "late", parentId: null, timestamp: "t", customType: "late" }) + "\n");
        return child;
      };
      try {
        refusal(() => createFork(base(w) as any), /source session changed/);
      } finally {
        (SessionManager as any).forkFrom = original;
      }
      assert.deepEqual(listForks(w), []);
      assert.equal(readFileSync(w.source, "utf8").startsWith(entries), true);
    });
  } },

  { name: "role selection: none, snapshot, overlay and refusal paths", async run() {
    assert.deepEqual(selectRole({}), { source: "none" });
    assert.deepEqual(selectRole({ callerRole: { named: false } }), { source: "none" });
    const snapshot = snapshotOf("worker", "WORKER ROLE");
    assert.deepEqual(selectRole({ callerRole: { named: true, snapshot } }), { source: "snapshot", ...snapshot });
    const overlay = selectRole({ callerRole: { named: true, snapshot }, overlay: { persona: "reviewer", text: "REVIEWER ROLE" } });
    assert.deepEqual(overlay, { source: "overlay", persona: "reviewer", text: "REVIEWER ROLE", sha256: sha("REVIEWER ROLE") });
    refusal(() => selectRole({ callerRole: { named: true } }), /no recorded role snapshot/);
    refusal(() => selectRole({ callerRole: { named: true, snapshot: { ...snapshot, text: "tampered" } } }), /does not match its hash/);
    refusal(() => selectRole({ overlay: { persona: "", text: "x" } }), /overlay needs/);
    // A named caller without a snapshot may still fork with an explicit overlay.
    assert.equal(selectRole({ callerRole: { named: true }, overlay: { persona: "reviewer", text: "R" } }).source, "overlay");
  } },

  { name: "missing role snapshot refuses before the adapter creates a fork", async run() {
    await inWorld(compactedMultiBranch, (w) => {
      refusal(() => createFork(base(w, { callerRole: { named: true } }) as any), /no recorded role snapshot/);
      assert.deepEqual(listForks(w), []);
      const ok = createFork(base(w, { callerRole: { named: true, snapshot: snapshotOf("worker", "WORKER ROLE") } }) as any);
      assert.equal((ok.role as any).persona, "worker");
      const replaced = createFork(base(w, { callerRole: { named: true, snapshot: snapshotOf("worker", "WORKER ROLE") }, overlay: { persona: "reviewer", text: "REVIEWER" } }) as any);
      assert.equal((replaced.role as any).persona, "reviewer");
      // The role is provenance for the caller; it never enters the forked conversation.
      assert.equal(JSON.stringify(SessionManager.open(replaced.sessionFile).buildSessionContext().messages).includes("REVIEWER"), false);
    });
  } },

  { name: "discard removes only the created fork and never the source or a foreign file", async run() {
    await inWorld(compactedMultiBranch, (w) => {
      const before = readFileSync(w.source);
      const forked = createFork(base(w) as any);
      assert.equal(discardFork(forked).removed, true);
      assert.equal(existsSync(forked.sessionFile), false);
      assert.equal(readFileSync(w.source).equals(before), true);
      assert.equal(discardFork(forked).removed, false, "already gone is not an error");
      refusal(() => discardFork({ sessionFile: w.source, sessionId: "x", source: forked.source }), /would remove the source/);
      const foreign = join(w.forks, "foreign.jsonl");
      writeFileSync(foreign, JSON.stringify({ type: "session", version: 3, id: "other", cwd: w.root }) + "\n");
      refusal(() => discardFork({ sessionFile: foreign, sessionId: forked.sessionId, source: forked.source }), /not a fork this adapter created/);
      assert.equal(existsSync(foreign), true);
      assert.equal(readFileSync(w.source).equals(before), true);
    });
  } },

  { name: "an explicit session takes its runtime cwd from the forked header, not the launch directory", async run() {
    await inWorld(compactedMultiBranch, (w) => {
      const forked = createFork(base(w) as any);
      const probe = join(w.root, "cwd-probe.ts");
      writeFileSync(probe, `export default function (pi) { pi.on("session_start", async (_e, ctx) => { process.stdout.write("RUNTIME_CWD " + ctx.cwd + "\\n"); process.exit(0); }); }\n`);
      const launchDir = join(w.root, "elsewhere");
      mkdirSync(launchDir);
      const run = spawnSync(process.execPath, [process.argv[1], "--offline", "--session", forked.sessionFile, "-ne", "-e", probe, "--no-skills", "--no-prompt-templates", "--no-context-files", "--no-mcp", "-p", "x"], {
        env: { ...process.env, PI_OFFLINE: "1" }, cwd: launchDir, encoding: "utf8", timeout: 60000, stdio: ["ignore", "pipe", "pipe"],
      });
      assert.equal(run.status, 0, run.stderr);
      assert.match(run.stdout + run.stderr, new RegExp(`RUNTIME_CWD ${w.target.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}\\n`));
    });
  } },

  { name: "the loaded extension forks through request and result files, and reports refusals", async run() {
    await inWorld(compactedMultiBranch, (w) => {
      const adapter = join(here, "fork-adapter.ts");
      const requestFile = join(w.root, "request.json");
      const resultFile = join(w.root, "result.json");
      const env = { HERDR_ORCH_ADAPTER: "fork-create", HERDR_ORCH_ADAPTER_REQUEST: requestFile, HERDR_ORCH_ADAPTER_RESULT: resultFile };
      writeFileSync(requestFile, JSON.stringify(base(w)));
      const ok = piRun(["-e", adapter], env, w.root);
      assert.equal(ok.status, 0, ok.stderr + ok.stdout);
      const out = JSON.parse(readFileSync(resultFile, "utf8"));
      assert.equal(out.ok, true);
      assert.equal(existsSync(out.sessionFile), true);
      assert.equal(out.runtime.piVersion, runtimeIdentity().piVersion);
      rmSync(resultFile);
      writeFileSync(requestFile, JSON.stringify({ ...base(w), sourceSession: join(w.root, "missing.jsonl") }));
      const failed = piRun(["-e", adapter], env, w.root);
      assert.equal(failed.status, 1);
      assert.match(JSON.parse(readFileSync(resultFile, "utf8")).error, /source session is missing/);
      assert.equal(listForks(w).length, 1, "only the first fork exists");
      // Inert without an operation: the adapter neither exits nor writes, so a second
      // extension in the same session still reaches its own session_start.
      const probe = join(w.root, "reached.ts");
      writeFileSync(probe, `export default function (pi) { pi.on("session_start", async () => { process.stdout.write("PROBE_REACHED\\n"); process.exit(0); }); }\n`);
      const inert = piRun(["-e", adapter, "-e", probe], {}, w.root);
      assert.equal(inert.status, 0, inert.stderr);
      assert.match(inert.stdout + inert.stderr, /PROBE_REACHED/);
      assert.equal(listForks(w).length, 1);
    });
  } },
];

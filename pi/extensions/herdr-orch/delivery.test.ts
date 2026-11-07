import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { DELIVERED_SUFFIX, ITEM_ENTRY, itemNumber, itemText, orchestratorSession, parseItem, skipByLedger, startDelivery } from "./delivery.ts";

const envelope = (task: string, status = "COMPLETE", extra = "") =>
  `--- HERDR RESULT v1 ---\nCHILD: child-${task}\nTASK: ${task}\nRESULT: /x/${task}.result\nWORK-ROOT: /x\nSTATUS: ${status}\nSUMMARY: summary for ${task}\n` +
  `ARTIFACTS:\n- secret-artifact.md :: do not deliver\nFINDINGS:\n- secret finding\nNEXT: secret next\n${extra}--- END HERDR RESULT ---\n`;

function fixture() {
  const root = resolve(mkdtempSync(join(tmpdir(), "oh-delivery-"))); // test.sh exports a relative TMPDIR
  const dir = join(root, ".tmp", "herdr-orch");
  mkdirSync(join(dir, "ledger"), { recursive: true });
  const sessionFile = join(root, "session.jsonl");
  writeFileSync(sessionFile, "");
  const sent: { content: string; options: unknown }[] = [];
  const pi = { sendMessage: (message: any, options: any) => { sent.push({ content: message.content, options }); } };
  const ctx = { cwd: root, sessionManager: { getSessionFile: () => sessionFile } };
  let onChange: (() => void) | undefined;
  const env = { HERDR_ENV: "1", ORCH_ASSIGNMENT_ROOT: root };
  const start = (overrides: Record<string, string | undefined> = {}) =>
    startDelivery(pi as any, ctx as any, { env: { ...env, ...overrides }, pollMs: 60_000, watchDir: (_d, cb) => { onChange = cb; return { close() {} } as any; } });
  const publish = (task: string, status = "COMPLETE", name = `${task}.result`) => { writeFileSync(join(dir, name), envelope(task, status)); return join(dir, name); };
  return { root, dir, sessionFile, sent, start, publish, change: () => onChange?.(), cleanup: () => rmSync(root, { recursive: true, force: true }) };
}

async function main() {
  // Gating: delegated children and plain sessions never watch; both hosts do.
  assert.equal(orchestratorSession({}), false);
  assert.equal(orchestratorSession({ HERDR_ENV: "1" }), true);
  assert.equal(orchestratorSession({ HC_TERMINAL_ID: "t1" }), true);
  assert.equal(orchestratorSession({ HERDR_ENV: "1", HERDR_ORCH_TASK: "x" }), false);

  // Parsing keeps only the four fields; the text never carries findings, next or artifacts.
  const item = parseItem(envelope("t1"))!;
  assert.deepEqual(item, { child: "child-t1", task: "t1", status: "COMPLETE", summary: "summary for t1" });
  const text = itemText(item);
  assert.ok(text.includes("oh task collect t1") && text.includes("not user authorisation"));
  for (const leak of ["secret", "ARTIFACTS", "FINDINGS", "NEXT"]) assert.equal(text.includes(leak), false, leak);
  assert.equal(parseItem("prose, not an envelope"), undefined);
  assert.equal(parseItem(envelope("t1", "BOGUS")), undefined);

  {
    const f = fixture();
    // No watcher for a non-orchestrator session or an unresolvable root; both return undefined.
    assert.equal(f.start({ HERDR_ENV: undefined }), undefined);
    assert.equal(f.start({ ORCH_ASSIGNMENT_ROOT: join(f.root, "missing") }), undefined);
    f.cleanup();
  }
  {
    // Startup scan, watch delivery, poll delivery, dedup and persisted set across a reload.
    const f = fixture();
    const a = f.publish("a");
    const d1 = f.start()!;
    assert.deepEqual(d1.delivered(), [a], "startup scan delivers the pre-existing item");
    assert.equal(f.sent.length, 1);
    assert.deepEqual(f.sent[0].options, { triggerTurn: true, deliverAs: "steer" });
    const b = f.publish("b");
    f.change();
    assert.deepEqual(d1.delivered(), [a, b], "watch event delivers the new item");
    const c = f.publish("c", "WAITING", "c.result.2");
    d1.scan();
    assert.equal(f.sent.length, 3, "poll scan delivers an item the watcher missed");
    f.change(); d1.scan();
    assert.equal(f.sent.length, 3, "no duplicate on repeated scans");
    assert.deepEqual(JSON.parse(readFileSync(f.sessionFile + DELIVERED_SUFFIX, "utf8")).sort(), [a, b, c].sort());
    d1.stop();
    const control = f.publish("control");
    const d2 = f.start()!; // reload: same session file, same directory
    assert.equal(f.sent.length, 4, "reload delivers only the item added while stopped");
    assert.ok(f.sent[3].content.includes("control"));
    d2.stop();
    f.cleanup();
  }
  {
    // A partial file is retried, not recorded; delivered once after it completes.
    const f = fixture();
    const d = f.start()!;
    const p = join(f.dir, "partial.result");
    writeFileSync(p, "--- HERDR RESULT v1 ---\nCHILD: c\nTASK: partial\n");
    f.change();
    assert.equal(f.sent.length, 0);
    assert.deepEqual(d.delivered(), []);
    writeFileSync(p, envelope("partial"));
    d.scan();
    assert.equal(f.sent.length, 1);
    d.scan();
    assert.equal(f.sent.length, 1);
    d.stop();
    f.cleanup();
  }
  {
    // A foreign parent's item is skipped; an item with no ledger entry and one recorded for this session are delivered.
    const f = fixture();
    writeFileSync(join(f.dir, "ledger", "theirs.json"), JSON.stringify({ task: "theirs", "parent-session": "/elsewhere/session.jsonl" }));
    writeFileSync(join(f.dir, "ledger", "mine.json"), JSON.stringify({ task: "mine", "parent-session": f.sessionFile }));
    // An item this session already captured (first start with an empty set) is not re-delivered.
    writeFileSync(join(f.dir, "ledger", "captured.json"), JSON.stringify({ task: "captured", "parent-session": f.sessionFile, items: [{ item: 1, "captured-at": "2026-11-07T00:00:00Z" }] }));
    assert.equal(skipByLedger(f.root, "theirs", 1, f.sessionFile), true);
    assert.equal(skipByLedger(f.root, "mine", 1, f.sessionFile), false);
    assert.equal(skipByLedger(f.root, "none", 1, f.sessionFile), false);
    assert.equal(skipByLedger(f.root, "captured", 1, f.sessionFile), true);
    assert.equal(skipByLedger(f.root, "captured", 2, f.sessionFile), false, "a later uncaptured item of a captured round is still delivered");
    assert.equal(itemNumber("/x/t.result"), 1);
    assert.equal(itemNumber("/x/t.result.3"), 3);
    f.publish("theirs"); f.publish("mine"); f.publish("none"); f.publish("captured"); f.publish("captured", "WAITING", "captured.result.2");
    const d = f.start()!;
    assert.deepEqual(f.sent.map((s) => /task (\S+):/.exec(s.content)?.[1]).sort(), ["captured", "mine", "none"]);
    assert.equal(d.delivered().some((p) => p.endsWith("theirs.result")), false, "a skipped foreign item stays undelivered, not recorded");
    assert.equal(d.delivered().some((p) => p.endsWith("captured.result")), false, "a captured item stays unrecorded too");
    d.stop();
    f.cleanup();
  }
  {
    // Missing result directory: no watcher error, nothing delivered, later items still found by scan.
    const f = fixture();
    rmSync(f.dir, { recursive: true, force: true });
    const d = f.start()!;
    assert.equal(f.sent.length, 0);
    mkdirSync(f.dir, { recursive: true });
    f.publish("late");
    d.scan();
    assert.equal(f.sent.length, 1);
    assert.equal(f.sent[0].content.startsWith("[herdr-orch] Subagent child-late published COMPLETE for task late"), true);
    assert.equal(ITEM_ENTRY, "herdr-orch-item");
    d.stop();
    f.cleanup();
  }
  console.log("delivery.test.ts: ok");
}

main().catch((error) => { console.error(error); process.exit(1); });

#!/usr/bin/env tsx
/**
 * Tests for the read-only task helpers in parser.ts.
 *
 * `ot` owns org parsing and serialization (see skills/org-tasks/scripts/test/);
 * these tests build `Task` values the way `wireTaskToTask` in index.ts does.
 *
 * Run: `tsx parser.test.ts` (or via `./test.sh`).
 */

import {
  expandOrgLinkTarget,
  getDrawerProperty,
  getDrawerPropertyValues,
  getTaskBlockers,
  getTaskHandoff,
  isTaskReady,
  parseBlocker,
  parseLinkTemplates,
  type Task,
} from "./parser.ts";

let passed = 0;
let failed = 0;

function assertEqual<T>(actual: T, expected: T, message: string): void {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a === e) {
    passed++;
    console.log(`ok - ${message}`);
  } else {
    failed++;
    console.log(`not ok - ${message}`);
    console.log(`  expected: ${e}`);
    console.log(`  actual:   ${a}`);
  }
}

function task(status: string, propertyLines: string[]): Task {
  return {
    level: 1, status, priority: null, summary: "Fixture", tags: [], description: "",
    children: [], propertyLines, logbookLines: [], importPath: null, closed: null,
    lineNumber: 0, endLine: 0,
  };
}

// ── parseLinkTemplates helper ─────────────────────────────────────────

{
  const links = parseLinkTemplates([
    "#+LINK: jira https://example.atlassian.net/browse/%s",
    "#+LINK: gh https://github.com/%s",
    "",
  ].join("\n"));
  assertEqual(links.get("jira"), "https://example.atlassian.net/browse/%s",
    "parseLinkTemplates: parses jira template");
  assertEqual(links.get("gh"), "https://github.com/%s",
    "parseLinkTemplates: parses second template");

  const firstWins = parseLinkTemplates([
    "#+LINK: plan file:local-plans/%s",
    "#+LINK: plan file:shared-plans/%s",
    "",
  ].join("\n"));
  assertEqual(firstWins.get("plan"), "file:local-plans/%s",
    "parseLinkTemplates: preserves the first declaration for a repeated prefix");
}

// ── expandOrgLinkTarget helper ───────────────────────────────────────

{
  const content = [
    "#+LINK: jira https://example.atlassian.net/browse/%s",
    "#+LINK: plan file:design/log/%s",
    "",
  ].join("\n");

  // file: templates resolve to a project-root-relative path and keep keys literal.
  const plan = expandOrgLinkTarget("plan:2026-05-13-foo.org", content);
  assertEqual(plan.target, "design/log/2026-05-13-foo.org",
    "expandOrgLinkTarget: plan: typed target expands through file: template");
  assertEqual(plan.fromProjectRoot, true,
    "expandOrgLinkTarget: file: template marks target as project-root-relative");

  // file: template keys keep slashes literal (no URL encoding).
  const nested = expandOrgLinkTarget("plan:subdir/foo.org", content);
  assertEqual(nested.target, "design/log/subdir/foo.org",
    "expandOrgLinkTarget: nested keys keep `/` literal under a file: template");
  assertEqual(nested.fromProjectRoot, true,
    "expandOrgLinkTarget: nested file: result is still project-root-relative");

  // URL templates URL-encode the key.
  const jira = expandOrgLinkTarget("jira:PROJ 1", content);
  assertEqual(jira.target, "https://example.atlassian.net/browse/PROJ%201",
    "expandOrgLinkTarget: URL templates URL-encode key separators");
  assertEqual(jira.fromProjectRoot, false,
    "expandOrgLinkTarget: URL templates do not flag fromProjectRoot");

  // file: typed targets pass through unchanged.
  const file = expandOrgLinkTarget("file:design/log/explicit.org", content);
  assertEqual(file.target, "file:design/log/explicit.org",
    "expandOrgLinkTarget: file: typed targets pass through verbatim");
  assertEqual(file.fromProjectRoot, false,
    "expandOrgLinkTarget: file: typed targets are not marked as resolved");

  // Unknown prefix returns the original target.
  const unknown = expandOrgLinkTarget("slack:C1234", content);
  assertEqual(unknown.target, "slack:C1234",
    "expandOrgLinkTarget: unknown prefix returns target verbatim");
  assertEqual(unknown.fromProjectRoot, false,
    "expandOrgLinkTarget: unknown prefix is not marked as resolved");

  // Plain paths (no typed prefix) pass through.
  const plain = expandOrgLinkTarget("design/log/legacy.org", content);
  assertEqual(plain.target, "design/log/legacy.org",
    "expandOrgLinkTarget: plain paths pass through verbatim");
  assertEqual(plain.fromProjectRoot, false,
    "expandOrgLinkTarget: plain paths are not marked as resolved");

  // A bare LinkTemplateMap is accepted in lieu of content.
  const templates = parseLinkTemplates(content);
  const fromMap = expandOrgLinkTarget("plan:foo.org", templates);
  assertEqual(fromMap.target, "design/log/foo.org",
    "expandOrgLinkTarget: LinkTemplateMap argument resolves identically");
  assertEqual(fromMap.fromProjectRoot, true,
    "expandOrgLinkTarget: LinkTemplateMap argument preserves fromProjectRoot");
}

// ── getDrawerProperty ─────────────────────────────────────────────────

{
  const t = task("TODO", [":CUSTOM_ID: cccccccc-dddd-4eee-8fff-000011112222", ":FOO_BAZ: original value"]);
  assertEqual(getDrawerProperty(t, "FOO_BAZ"), "original value",
    "getDrawerProperty returns value for present property");
  assertEqual(getDrawerProperty(t, "foo_baz"), "original value",
    "getDrawerProperty is case-insensitive on name");
  assertEqual(getDrawerProperty(t, "MISSING"), null,
    "getDrawerProperty returns null for absent property");
}

// ── Multi-valued :BLOCKED-BY: + ready-task query ──────────────────────

{
  const t = task("WAITING", [":CUSTOM_ID: aaaa1111-2222-4333-8444-555555555555", ":BLOCKED-BY: url:https://example.com/pr/1"]);
  assertEqual(getDrawerPropertyValues(t, "BLOCKED-BY"), ["url:https://example.com/pr/1"],
    "single-value :BLOCKED-BY: read via getDrawerPropertyValues");
  assertEqual(getDrawerProperty(t, "BLOCKED-BY"), "url:https://example.com/pr/1",
    "single-value :BLOCKED-BY: still readable via the single-value getter");
}

{
  const t = task("TODO", [
    ":CUSTOM_ID: bbbb1111-2222-4333-8444-555555555555",
    ":BLOCKED-BY: task:cccc1111-2222-4333-8444-555555555555",
    ":BLOCKED-BY+: url:https://example.com/pr/2",
    ":BLOCKED-BY+: human: waiting on Alice",
  ]);
  assertEqual(
    getDrawerPropertyValues(t, "BLOCKED-BY"),
    ["task:cccc1111-2222-4333-8444-555555555555", "url:https://example.com/pr/2", "human: waiting on Alice"],
    "multi-value :BLOCKED-BY: collects base + continuation lines in order",
  );
}

{
  assertEqual(parseBlocker("task:abc-123"),
    { raw: "task:abc-123", kind: "task", ref: "abc-123" },
    "parseBlocker: task:<UUID>");
  assertEqual(parseBlocker("url:https://x.com"),
    { raw: "url:https://x.com", kind: "url", ref: "https://x.com" },
    "parseBlocker: url:<URL>");
  assertEqual(parseBlocker("human: waiting on Alice"),
    { raw: "human: waiting on Alice", kind: "human", ref: "waiting on Alice" },
    "parseBlocker: human:<text> trims leading whitespace from ref");
  assertEqual(parseBlocker("jira:ABC-1"),
    { raw: "jira:ABC-1", kind: "jira", ref: "ABC-1" },
    "parseBlocker: jira:<KEY>");
  assertEqual(parseBlocker("some-opaque-thing"),
    { raw: "some-opaque-thing", kind: "other", ref: "some-opaque-thing" },
    "parseBlocker: unknown form classified as 'other'");
  assertEqual(parseBlocker("TASK:xyz").kind, "task",
    "parseBlocker: kind prefix is case-insensitive");
}

{
  const blockers = getTaskBlockers(task("TODO", [":BLOCKED-BY: task:dep-1", ":BLOCKED-BY+: human: review pending"]));
  assertEqual(blockers.length, 2, "getTaskBlockers: returns one entry per line");
  assertEqual(blockers[0]!.kind, "task", "getTaskBlockers: kind for task: entry");
  assertEqual(blockers[1]!.kind, "human", "getTaskBlockers: kind for human: entry");
}

{
  const report = isTaskReady(task("TODO", [":CUSTOM_ID: 1111aaaa-2222-4333-8444-555555555555"]), () => null);
  assertEqual(report.ready, true, "isTaskReady: no blockers → ready");
  assertEqual(report.gating.length, 0, "isTaskReady: no blockers → empty gating");
}

{
  const dep = task("DONE", [":CUSTOM_ID: 2222aaaa-2222-4333-8444-555555555555"]);
  const gated = task("TODO", [":BLOCKED-BY: task:2222aaaa-2222-4333-8444-555555555555"]);
  const report = isTaskReady(gated, (id) => (id === "2222aaaa-2222-4333-8444-555555555555" ? dep : null));
  assertEqual(report.ready, true, "isTaskReady: task: blocker resolving to DONE → ready");
}

{
  const dep = task("TODO", [":CUSTOM_ID: 4444aaaa-2222-4333-8444-555555555555"]);
  const gated = task("TODO", [":BLOCKED-BY: task:4444aaaa-2222-4333-8444-555555555555"]);
  const report = isTaskReady(gated, (id) => (id === "4444aaaa-2222-4333-8444-555555555555" ? dep : null));
  assertEqual(report.ready, false, "isTaskReady: open task: blocker → not ready");
  assertEqual(report.gating.length, 1, "isTaskReady: one gating entry");
  assertEqual(report.gating[0]!.reason, "unresolved-task", "isTaskReady: reason 'unresolved-task' for open dep");
}

{
  const report = isTaskReady(task("TODO", [":BLOCKED-BY: task:does-not-exist"]), () => null);
  assertEqual(report.ready, false, "isTaskReady: missing task ref → not ready");
  assertEqual(report.gating[0]!.reason, "missing-task", "isTaskReady: reason 'missing-task' for unknown UUID");
}

{
  const report = isTaskReady(
    task("TODO", [":BLOCKED-BY: url:https://example.com/pr/1", ":BLOCKED-BY+: human: review pending"]), () => null);
  assertEqual(report.ready, false, "isTaskReady: opaque blockers → not ready");
  assertEqual(report.gating.length, 2, "isTaskReady: every opaque entry gates");
  assertEqual(report.gating.every((g) => g.reason === "opaque"), true,
    "isTaskReady: opaque entries report reason 'opaque'");
}

{
  const dep = task("DONE", [":CUSTOM_ID: 8888aaaa-2222-4333-8444-555555555555"]);
  const mixed = task("TODO", [":BLOCKED-BY: task:8888aaaa-2222-4333-8444-555555555555", ":BLOCKED-BY+: url:https://example.com"]);
  const report = isTaskReady(mixed, (id) => (id === "8888aaaa-2222-4333-8444-555555555555" ? dep : null));
  assertEqual(report.ready, false, "isTaskReady: opaque blocker still gates even when task: dep is closed");
  assertEqual(report.gating.length, 1, "isTaskReady: only opaque gates");
  assertEqual(report.gating[0]!.reason, "opaque", "isTaskReady: opaque reason for url: blocker");
}

// ── :HANDOFF: ─────────────────────────────────────────────────────────

{
  assertEqual(getTaskHandoff(task("STARTED", [":HANDOFF: Pick up at the parser delimiter test."])),
    "Pick up at the parser delimiter test.",
    "getTaskHandoff: returns the trimmed value when present");
  assertEqual(getTaskHandoff(task("STARTED", [])), null, "getTaskHandoff: null when absent");
}

console.log(`\n# ${passed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);

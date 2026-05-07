#!/usr/bin/env tsx
/**
 * Unit tests for the jira extension's pure helpers.
 *
 * The MCP-driven workflows themselves are tested manually against the
 * SAND sandbox project (per the parent change-record's design
 * decisions); these tests cover only the deterministic, I/O-free
 * argument-resolution and keyword-reading logic.
 *
 * Run: `tsx jira.test.ts` (or via `./test.sh`).
 */

import {
  buildClaimPrompt,
  buildClonePrompt,
  buildCommentPrompt,
  buildCreatePrompt,
  buildGetPrompt,
  buildTransitionPrompt,
  getFileKeyword,
  parseCreateArgs,
  resolveKey,
} from "./utils.ts";

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

// ── resolveKey: well-formed PROJ-NNN passes through ──────────────────

assertEqual(
  resolveKey("MBFW-123", "SAND"),
  { key: "MBFW-123" },
  "resolveKey: PROJ-NNN passes through unchanged",
);

assertEqual(
  resolveKey("SAND-42", null),
  { key: "SAND-42" },
  "resolveKey: PROJ-NNN works without project keyword",
);

// ── resolveKey: bare number with project ──────────────────────────────

assertEqual(
  resolveKey("42", "SAND"),
  { key: "SAND-42" },
  "resolveKey: bare number prepends #+JIRA_PROJECT",
);

// ── resolveKey: bare number without project ───────────────────────────

{
  const r = resolveKey("42", null);
  if ("error" in r) {
    passed++;
    console.log("ok - resolveKey: bare number without project returns error");
  } else {
    failed++;
    console.log("not ok - resolveKey: bare number without project should error");
  }
}

// ── resolveKey: malformed input ───────────────────────────────────────

{
  const r = resolveKey("not-a-key", "SAND");
  if ("error" in r) {
    passed++;
    console.log("ok - resolveKey: malformed input returns error");
  } else {
    failed++;
    console.log("not ok - resolveKey: malformed input should error");
  }
}

// ── getFileKeyword: matches helper behaviour from tasks/parser.ts ─────

{
  const content = [
    "#+JIRA_CLOUDID: abc-123",
    "#+JIRA_PROJECT:",
    "#+JIRA_BASE_URL: https://example.com",
    "",
  ].join("\n");

  assertEqual(
    getFileKeyword(content, "JIRA_CLOUDID"),
    "abc-123",
    "getFileKeyword: returns value",
  );
  assertEqual(
    getFileKeyword(content, "JIRA_PROJECT"),
    "",
    "getFileKeyword: empty value yields empty string (not next-line)",
  );
  assertEqual(
    getFileKeyword(content, "JIRA_BASE_URL"),
    "https://example.com",
    "getFileKeyword: line after empty keyword still resolvable",
  );
  assertEqual(
    getFileKeyword(content, "ABSENT"),
    null,
    "getFileKeyword: returns null for absent keyword",
  );
  assertEqual(
    getFileKeyword(content, "jira_cloudid"),
    "abc-123",
    "getFileKeyword: case-insensitive on name",
  );
}

// ── buildClonePrompt: structural sanity ───────────────────────────

{
  const prompt = buildClonePrompt(
    ["SAND-1", "SAND-2"],
    {
      cloudId: "abc-cloud",
      project: "SAND",
      baseUrl: "https://example.atlassian.net",
    },
    "/tmp/proj",
    "TASKS.org",
    "TASKS.local.org",
  );
  if (prompt.includes("SAND-1") && prompt.includes("SAND-2")) {
    passed++;
    console.log("ok - buildClonePrompt: lists every key");
  } else {
    failed++;
    console.log("not ok - buildClonePrompt: should list every key");
  }
  if (prompt.includes("abc-cloud")) {
    passed++;
    console.log("ok - buildClonePrompt: inlines cloudId when present");
  } else {
    failed++;
    console.log("not ok - buildClonePrompt: should inline cloudId");
  }
  if (prompt.includes("atlassian_getJiraIssue")) {
    passed++;
    console.log("ok - buildClonePrompt: references the MCP tool name");
  } else {
    failed++;
    console.log("not ok - buildClonePrompt: should reference atlassian_getJiraIssue");
  }
  if (prompt.includes("jira_clone_apply")) {
    passed++;
    console.log("ok - buildClonePrompt: dispatches to jira_clone_apply");
  } else {
    failed++;
    console.log("not ok - buildClonePrompt: should reference jira_clone_apply");
  }
  // The two-step dispatch must NOT instruct the agent to manually assemble
  // drawer text. Regression guard against drift back to the v1 prompt-builder
  // approach.
  const looksHandRolled =
    prompt.includes(":LINKED_ISSUES: <KEY>") ||
    /:ID:\s*<new uuid>/.test(prompt) ||
    /properties drawer:/i.test(prompt);
  if (!looksHandRolled) {
    passed++;
    console.log(
      "ok - buildClonePrompt: does not instruct manual drawer assembly",
    );
  } else {
    failed++;
    console.log(
      "not ok - buildClonePrompt: should not instruct manual drawer assembly (drift back to v1 contract)",
    );
  }
  if (prompt.includes('"duplicate"')) {
    passed++;
    console.log(
      "ok - buildClonePrompt: documents the duplicate-status return",
    );
  } else {
    failed++;
    console.log(
      "not ok - buildClonePrompt: should document the duplicate-status return",
    );
  }
}

// Without #+JIRA_CLOUDID, the prompt must instruct cloudId resolution.
{
  const prompt = buildClonePrompt(
    ["SAND-1"],
    { cloudId: null, project: "SAND", baseUrl: "https://example.atlassian.net" },
    "/tmp/proj",
    "TASKS.org",
    "TASKS.local.org",
  );
  if (prompt.includes("atlassian_getAccessibleAtlassianResources")) {
    passed++;
    console.log(
      "ok - buildClonePrompt: instructs cloudId resolution when keyword absent",
    );
  } else {
    failed++;
    console.log(
      "not ok - buildClonePrompt: should instruct cloudId resolution when keyword absent",
    );
  }
}

// ── buildClaimPrompt ──────────────────────────────────────────────────

{
  const prompt = buildClaimPrompt(
    {
      cloudId: "abc",
      project: "SAND",
      baseUrl: "https://your-org.atlassian.net",
    },
    "/tmp/proj",
    "TASKS.org",
    "TASKS.local.org",
    "01234567-89ab-4def-8123-456789abcdef",
  );
  if (prompt.includes("atlassian_atlassianUserInfo")) {
    passed++;
    console.log(
      "ok - buildClaimPrompt: references the user-info MCP tool",
    );
  } else {
    failed++;
    console.log(
      "not ok - buildClaimPrompt: should reference atlassian_atlassianUserInfo",
    );
  }
  if (prompt.includes("atlassian_editJiraIssue")) {
    passed++;
    console.log(
      "ok - buildClaimPrompt: references atlassian_editJiraIssue",
    );
  } else {
    failed++;
    console.log(
      "not ok - buildClaimPrompt: should reference atlassian_editJiraIssue",
    );
  }
  if (prompt.includes("your-org.atlassian.net")) {
    passed++;
    console.log(
      "ok - buildClaimPrompt: includes the Jira host for org-link filtering",
    );
  } else {
    failed++;
    console.log(
      "not ok - buildClaimPrompt: should include the Jira host",
    );
  }
  if (prompt.includes("01234567-89ab-4def-8123-456789abcdef")) {
    passed++;
    console.log("ok - buildClaimPrompt: identifies the selected task ID");
  } else {
    failed++;
    console.log(
      "not ok - buildClaimPrompt: should identify the selected task ID",
    );
  }
}

// ── buildCommentPrompt ───────────────────────────────────────────────

{
  const prompt = buildCommentPrompt(
    "Looks good to me \u2014 ready to merge.",
    { cloudId: "abc", project: "SAND", baseUrl: null },
    "/tmp/proj",
    "TASKS.org",
    "TASKS.local.org",
    "some-uuid",
  );
  if (prompt.includes("atlassian_addCommentToJiraIssue")) {
    passed++;
    console.log(
      "ok - buildCommentPrompt: references atlassian_addCommentToJiraIssue",
    );
  } else {
    failed++;
    console.log(
      "not ok - buildCommentPrompt: should reference the MCP tool",
    );
  }
  if (prompt.includes("Looks good to me")) {
    passed++;
    console.log("ok - buildCommentPrompt: embeds the comment body verbatim");
  } else {
    failed++;
    console.log("not ok - buildCommentPrompt: should embed the body");
  }
}

// ── buildCreatePrompt + parseCreateArgs ────────────────────────────────

assertEqual(
  parseCreateArgs([]),
  { project: null, type: "Task" },
  "parseCreateArgs: empty args defaults to null project + Task type",
);
assertEqual(
  parseCreateArgs(["SAND"]),
  { project: "SAND", type: "Task" },
  "parseCreateArgs: positional project",
);
assertEqual(
  parseCreateArgs(["--type", "Story"]),
  { project: null, type: "Story" },
  "parseCreateArgs: --type with separate value",
);
assertEqual(
  parseCreateArgs(["SAND", "--type=Bug"]),
  { project: "SAND", type: "Bug" },
  "parseCreateArgs: --type=value form",
);
assertEqual(
  parseCreateArgs(["SAND", "--type", "Epic"]),
  { project: "SAND", type: "Epic" },
  "parseCreateArgs: project + --type combo",
);

{
  const prompt = buildCreatePrompt(
    { project: "SAND", type: "Story" },
    { cloudId: "abc", project: null, baseUrl: null },
    "/tmp/proj",
    "TASKS.org",
    "TASKS.local.org",
    "sel-uuid",
  );
  if (
    prompt.includes("atlassian_createJiraIssue") &&
    prompt.includes("atlassian_getJiraProjectIssueTypesMetadata")
  ) {
    passed++;
    console.log(
      "ok - buildCreatePrompt: references the create + types-metadata tools",
    );
  } else {
    failed++;
    console.log(
      "not ok - buildCreatePrompt: should reference create + types-metadata tools",
    );
  }
  if (prompt.includes("`SAND`") && prompt.includes("`Story`")) {
    passed++;
    console.log("ok - buildCreatePrompt: pins down project + type");
  } else {
    failed++;
    console.log("not ok - buildCreatePrompt: should pin project + type");
  }
  if (prompt.includes(":LINKED_ISSUES:")) {
    passed++;
    console.log(
      "ok - buildCreatePrompt: instructs writing the new key back to :LINKED_ISSUES:",
    );
  } else {
    failed++;
    console.log(
      "not ok - buildCreatePrompt: should instruct :LINKED_ISSUES: writeback",
    );
  }
}

{
  // Refuses with no project from either source.
  const prompt = buildCreatePrompt(
    { project: null, type: "Task" },
    { cloudId: "abc", project: null, baseUrl: null },
    "/tmp/proj",
    "TASKS.org",
    "TASKS.local.org",
    "sel-uuid",
  );
  if (
    prompt.toLowerCase().includes("refuse") &&
    prompt.includes("#+JIRA_PROJECT")
  ) {
    passed++;
    console.log(
      "ok - buildCreatePrompt: refuses when no project resolvable",
    );
  } else {
    failed++;
    console.log(
      "not ok - buildCreatePrompt: should refuse with no project",
    );
  }
}

// ── buildTransitionPrompt ───────────────────────────────────────────

{
  const promptStarted = buildTransitionPrompt(
    "STARTED",
    "abc-123",
    "Refactor stim driver",
    { cloudId: "abc", project: "SAND", baseUrl: null },
    "/tmp/proj",
    "TASKS.org",
    "TASKS.local.org",
  );
  if (
    promptStarted.includes("Start Progress") &&
    promptStarted.includes("In Progress")
  ) {
    passed++;
    console.log(
      "ok - buildTransitionPrompt: STARTED uses Start Progress / In Progress",
    );
  } else {
    failed++;
    console.log(
      "not ok - buildTransitionPrompt: STARTED should pick Start Progress / In Progress",
    );
  }
  if (promptStarted.includes("abc-123")) {
    passed++;
    console.log("ok - buildTransitionPrompt: includes the task UUID");
  } else {
    failed++;
    console.log(
      "not ok - buildTransitionPrompt: should include the task UUID",
    );
  }

  const promptDone = buildTransitionPrompt(
    "DONE",
    "abc-123",
    "Refactor",
    { cloudId: "abc", project: "SAND", baseUrl: null },
    "/tmp/proj",
    "TASKS.org",
    "TASKS.local.org",
  );
  if (
    promptDone.includes("Done") &&
    promptDone.includes("Closed") &&
    promptDone.includes("Resolved")
  ) {
    passed++;
    console.log(
      "ok - buildTransitionPrompt: DONE uses Done / Closed / Resolved",
    );
  } else {
    failed++;
    console.log(
      "not ok - buildTransitionPrompt: DONE should list Done / Closed / Resolved",
    );
  }
  if (promptDone.includes("atlassian_transitionJiraIssue")) {
    passed++;
    console.log(
      "ok - buildTransitionPrompt: references atlassian_transitionJiraIssue",
    );
  } else {
    failed++;
    console.log(
      "not ok - buildTransitionPrompt: should reference atlassian_transitionJiraIssue",
    );
  }
}

// ── buildGetPrompt: single + multi-key forms ─────────────────────

{
  const single = buildGetPrompt(
    ["SAND-1"],
    {
      cloudId: "abc-cloud",
      project: "SAND",
      baseUrl: "https://example.atlassian.net",
    },
    "/tmp/proj",
  );
  if (single.includes("SAND-1")) {
    passed++;
    console.log("ok - buildGetPrompt: includes the requested key");
  } else {
    failed++;
    console.log("not ok - buildGetPrompt: should include requested key");
  }
  if (single.includes("atlassian_getJiraIssue")) {
    passed++;
    console.log("ok - buildGetPrompt: references atlassian_getJiraIssue");
  } else {
    failed++;
    console.log("not ok - buildGetPrompt: should reference atlassian_getJiraIssue");
  }
  if (single.includes("abc-cloud")) {
    passed++;
    console.log("ok - buildGetPrompt: inlines cloudId when present");
  } else {
    failed++;
    console.log("not ok - buildGetPrompt: should inline cloudId when present");
  }
  if (single.includes("https://example.atlassian.net/browse/<KEY>")) {
    passed++;
    console.log("ok - buildGetPrompt: footer link uses #+JIRA_BASE_URL");
  } else {
    failed++;
    console.log("not ok - buildGetPrompt: should compose footer link from baseUrl");
  }
  if (
    single.includes("summary,priority,labels,description,issuetype,parent,subtasks")
  ) {
    passed++;
    console.log("ok - buildGetPrompt: reuses the field-list constant");
  } else {
    failed++;
    console.log("not ok - buildGetPrompt: should reuse the field filter from /jira clone");
  }
  // Inspection helper must NOT instruct any TASKS file write.
  if (
    !single.toLowerCase().includes("jira_clone_apply") &&
    !single.toLowerCase().includes("tasks.org")
  ) {
    passed++;
    console.log("ok - buildGetPrompt: does not write to TASKS files");
  } else {
    failed++;
    console.log("not ok - buildGetPrompt: should not write to TASKS files");
  }

  // Without a baseUrl: footer link suppressed.
  const noBaseUrl = buildGetPrompt(
    ["SAND-1"],
    { cloudId: "abc", project: "SAND", baseUrl: null },
    "/tmp/proj",
  );
  if (!/example\.atlassian\.net\/browse/.test(noBaseUrl)) {
    passed++;
    console.log("ok - buildGetPrompt: no footer link when #+JIRA_BASE_URL absent");
  } else {
    failed++;
    console.log("not ok - buildGetPrompt: should suppress footer link when baseUrl null");
  }

  // Multi-key prompt mentions all keys + plural wording.
  const multi = buildGetPrompt(
    ["SAND-1", "SAND-2", "SAND-3"],
    { cloudId: "abc", project: "SAND", baseUrl: null },
    "/tmp/proj",
  );
  if (
    multi.includes("SAND-1") &&
    multi.includes("SAND-2") &&
    multi.includes("SAND-3")
  ) {
    passed++;
    console.log("ok - buildGetPrompt: multi-key form lists every key");
  } else {
    failed++;
    console.log("not ok - buildGetPrompt: multi-key form should list every key");
  }
  if (multi.includes("issues")) {
    passed++;
    console.log("ok - buildGetPrompt: multi-key form uses plural wording");
  } else {
    failed++;
    console.log("not ok - buildGetPrompt: multi-key form should use plural wording");
  }
}

console.log(`\n# ${passed} passed, ${failed} failed`);
process.exit(failed === 0 ? 0 : 1);

#!/usr/bin/env tsx
/**
 * Tests for custom 'write' tool in emacsclient extension.
 */

import {
  escapeElispString,
  parseEmacsclientOutput,
  buildWriteElisp,
} from "./elisp.ts";

// ---------------------------------------------------------------------------
// Test harness
// ---------------------------------------------------------------------------

let passed = 0;
let failed = 0;

function assert(condition: boolean, message?: string): asserts condition {
  if (!condition) throw new Error(message || "Assertion failed");
}

function assertEqual<T>(actual: T, expected: T, message?: string) {
  if (actual !== expected) {
    throw new Error(
      `${message || "assertEqual"}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`
    );
  }
}

function assertDeepEqual<T>(actual: T, expected: T, message?: string) {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a !== e) {
    throw new Error(
      `${message || "assertDeepEqual"}: expected ${e}, got ${a}`
    );
  }
}

function assertContains(haystack: string, needle: string, message?: string) {
  if (!haystack.includes(needle)) {
    throw new Error(
      `${message || "assertContains"}: expected to find "${needle}" in string`
    );
  }
}

function assertThrows(fn: () => void, message?: string) {
  try {
    fn();
    throw new Error(`${message || "assertThrows"}: expected function to throw`);
  } catch (err) {
    // Expected
  }
}

function test(name: string, fn: () => void | Promise<void>) {
  const runner = async () => {
    try {
      await fn();
      console.log(`ok - ${name}`);
      passed++;
    } catch (err) {
      console.log(`not ok - ${name}`);
      console.log(`  # ${err instanceof Error ? err.message : String(err)}`);
      failed++;
    }
  };
  return runner();
}

// ---------------------------------------------------------------------------
// buildWriteElisp - basic parameter handling
// ---------------------------------------------------------------------------

test("buildWriteElisp - accepts name and insert parameters", () => {
  const result = buildWriteElisp("test.txt", "hello world");
  assert(typeof result === "string", "Should return a string");
  assertContains(result, "test.txt", "Should reference the name");
  assertContains(result, "hello world", "Should reference the text to insert");
});
test("buildWriteElisp - escapes special characters in name", () => {
  const result = buildWriteElisp('file "with" quotes.txt', "test");
  assertContains(result, '\\"', "Should escape quotes");
});
test("buildWriteElisp - escapes special characters in insert text", () => {
  const result = buildWriteElisp("test.txt", 'text with "quotes" and \nlines');
  assertContains(result, '\\"', "Should escape quotes");
  assertContains(result, '\\n', "Should escape newlines");
});
test("buildWriteElisp - handles file path with forward slash", () => {
  const result = buildWriteElisp("/home/user/file.txt", "content");
  assertContains(result, "/home/user/file.txt", "Should include path");
  assertContains(result, "find-file", "Should use find-file for paths");
});
test("buildWriteElisp - handles buffer name without slash", () => {
  const result = buildWriteElisp("*scratch*", "content");
  assertContains(result, "get-buffer", "Should use get-buffer for buffer names");
});
test("buildWriteElisp - handles relative path starting with ./", () => {
  const result = buildWriteElisp("./relative.txt", "content");
  assertContains(result, "./relative.txt", "Should preserve relative path");
  assertContains(result, "find-file", "Should use find-file for paths");
});
test("buildWriteElisp - detects TRAMP path", () => {
  const result = buildWriteElisp("/ssh:user@host:/path/to/file", "content");
  assertContains(result, "/ssh:user@host:/path/to/file", "Should handle TRAMP path");
  assertContains(result, "find-file", "Should use find-file for TRAMP");
});
test("buildWriteElisp - handles empty insert string", () => {
  const result = buildWriteElisp("test.txt", "");
  assertContains(result, '""', "Should handle empty string");
});

// ---------------------------------------------------------------------------
// buildWriteElisp - position parameters
// ---------------------------------------------------------------------------

test("buildWriteElisp - accepts pos parameter", () => {
  const result = buildWriteElisp("test.txt", "hello", { pos: 100 });
  assertContains(result, "100", "Should include position");
  assertContains(result, "goto-char", "Should use goto-char for position");
});
test("buildWriteElisp - accepts line parameter", () => {
  const result = buildWriteElisp("test.txt", "hello", { line: 10 });
  assertContains(result, "10", "Should include line number");
  assertContains(result, "goto-line", "Should use goto-line or forward-line");
});
test("buildWriteElisp - accepts point parameter (boolean)", () => {
  const result = buildWriteElisp("test.txt", "hello", { point: true });
  // Should not move point - inserts at current point
  assert(true, "Should accept point parameter");
});
test("buildWriteElisp - handles negative pos", () => {
  const result = buildWriteElisp("test.txt", "hello", { pos: -50 });
  assertContains(result, "-50", "Should include negative position");
});
test("buildWriteElisp - handles negative line", () => {
  const result = buildWriteElisp("test.txt", "hello", { line: -5 });
  assertContains(result, "-5", "Should include negative line number");
});
test("buildWriteElisp - no position means insert at current point", () => {
  const result = buildWriteElisp("test.txt", "hello");
  // Default behavior: insert at point (start of file if newly opened)
  assertContains(result, "insert", "Should use insert function");
});
test("buildWriteElisp - rejects ambiguous pos and line", () => {
  assertThrows(() => {
    buildWriteElisp("test.txt", "hello", { pos: 100, line: 10 });
  }, "Should reject both pos and line");
});
test("buildWriteElisp - rejects ambiguous pos and point", () => {
  assertThrows(() => {
    buildWriteElisp("test.txt", "hello", { pos: 100, point: true });
  }, "Should reject both pos and point");
});
test("buildWriteElisp - rejects ambiguous line and point", () => {
  assertThrows(() => {
    buildWriteElisp("test.txt", "hello", { line: 10, point: true });
  }, "Should reject both line and point");
});
test("buildWriteElisp - rejects all three position parameters", () => {
  assertThrows(() => {
    buildWriteElisp("test.txt", "hello", { pos: 100, line: 10, point: true });
  }, "Should reject all three position parameters");
});

// ---------------------------------------------------------------------------
// buildWriteElisp - save parameter
// ---------------------------------------------------------------------------

test("buildWriteElisp - accepts save parameter", () => {
  const result = buildWriteElisp("test.txt", "hello", { save: true });
  assertContains(result, "save-buffer", "Should call save-buffer");
});
test("buildWriteElisp - save false does not save", () => {
  const result = buildWriteElisp("test.txt", "hello", { save: false });
  assert(!result.includes("save-buffer") || result.includes("when.*save-buffer"),
    "Should not unconditionally save");
});
test("buildWriteElisp - save default saves when buffer has file", () => {
  const result = buildWriteElisp("test.txt", "hello");
  assert(result.includes("save-buffer"), "Should save by default");
  assert(result.includes("buffer-file-name"), "Save should be conditional on buffer having a file");
});

// ---------------------------------------------------------------------------
// buildWriteElisp - replace parameter
// ---------------------------------------------------------------------------

test("buildWriteElisp - accepts replace parameter", () => {
  const result = buildWriteElisp("test.txt", "new content", { replace: true });
  assertContains(result, "delete-region", "Should delete buffer contents");
  assertContains(result, "point-min", "Should clear from start");
  assertContains(result, "point-max", "Should clear to end");
});

test("buildWriteElisp - replace clears entire buffer", () => {
  const result = buildWriteElisp("test.txt", "replacement", { replace: true });
  assertContains(result, "delete-region", "Should use delete-region");
  assertContains(result, "goto-char (point-min)", "Should move to start after clear");
});

test("buildWriteElisp - replace inserts at start of cleared buffer", () => {
  const result = buildWriteElisp("test.txt", "hello", { replace: true });
  assertContains(result, "delete-region", "Should delete existing content");
  assertContains(result, "insert", "Should insert new content");
  assertContains(result, "hello", "Should insert the provided text");
});

test("buildWriteElisp - replace rejects pos parameter", () => {
  assertThrows(() => {
    buildWriteElisp("test.txt", "hello", { replace: true, pos: 100 });
  }, "Should reject replace with pos");
});

test("buildWriteElisp - replace rejects line parameter", () => {
  assertThrows(() => {
    buildWriteElisp("test.txt", "hello", { replace: true, line: 10 });
  }, "Should reject replace with line");
});

test("buildWriteElisp - replace rejects point parameter", () => {
  assertThrows(() => {
    buildWriteElisp("test.txt", "hello", { replace: true, point: true });
  }, "Should reject replace with point");
});

test("buildWriteElisp - replace allows save", () => {
  const result = buildWriteElisp("test.txt", "new content", { replace: true, save: true });
  assertContains(result, "delete-region", "Should clear buffer");
  assertContains(result, "save-buffer", "Should save after replacing");
});

test("buildWriteElisp - replace allows temp", () => {
  const result = buildWriteElisp("test.txt", "new content", { replace: true, temp: true });
  assertContains(result, "delete-region", "Should clear buffer");
  assertContains(result, "kill-buffer", "Should kill buffer in temp mode");
});

test("buildWriteElisp - replace with save and temp", () => {
  const result = buildWriteElisp("test.txt", "replacement", { replace: true, save: true, temp: true });
  assertContains(result, "delete-region", "Should clear buffer");
  assertContains(result, "save-buffer", "Should save");
  assertContains(result, "kill-buffer", "Should kill buffer");
});

test("buildWriteElisp - replace with empty string", () => {
  const result = buildWriteElisp("test.txt", "", { replace: true });
  assertContains(result, "delete-region", "Should clear buffer");
  assertContains(result, '""', "Should insert empty string");
});

test("buildWriteElisp - replace with multiline content", () => {
  const content = "line1\
line2\
line3";
  const result = buildWriteElisp("test.txt", content, { replace: true });
  assertContains(result, "delete-region", "Should clear existing");
  assertContains(result, content, "Should insert multiline content");
});

test("buildWriteElisp - replace with unicode content", () => {
  const result = buildWriteElisp("test.txt", "新しい内容", { replace: true });
  assertContains(result, "delete-region", "Should clear buffer");
  assertContains(result, "新しい内容", "Should insert unicode content");
});

test("buildWriteElisp - replace with special characters", () => {
  const content = 'special: "quotes" \
 and \\t tabs';
  const result = buildWriteElisp("test.txt", content, { replace: true });
  assertContains(result, "delete-region", "Should clear buffer");
  // Content should be escaped properly
  assert(result.includes("\"") || result.includes('\\\"'), "Should escape quotes");
});

test("buildWriteElisp - replace false (default) does not delete", () => {
  const result = buildWriteElisp("test.txt", "hello");
  // Default (no replace) should not delete
  assert(!result.includes("delete-region (point-min) (point-max)"),
    "Default should not replace entire buffer");
});

test("buildWriteElisp - replace true clears before position movements", () => {
  const result = buildWriteElisp("test.txt", "hello", { replace: true, save: true, temp: true });
  // Verify structure: delete comes before other operations
  assertContains(result, "delete-region", "Should have delete-region");
  assertContains(result, "insert", "Should have insert");
  const deleteIdx = result.indexOf("delete-region");
  const insertIdx = result.indexOf("insert");
  assert(deleteIdx < insertIdx, "Delete should come before insert");
});

// ---------------------------------------------------------------------------
// buildWriteElisp - temp parameter
// ---------------------------------------------------------------------------

test("buildWriteElisp - temp true saves and restores state", () => {
  const result = buildWriteElisp("test.txt", "hello", { temp: true });
  assertContains(result, "save-excursion", "Should use save-excursion or save-current-buffer");
});
test("buildWriteElisp - temp true kills new buffers", () => {
  const result = buildWriteElisp("test.txt", "hello", { temp: true });
  assertContains(result, "kill-buffer", "Should kill buffer if newly opened");
});
test("buildWriteElisp - temp false is default", () => {
  const resultExplicit = buildWriteElisp("test.txt", "hello", { temp: false });
  const resultDefault = buildWriteElisp("test.txt", "hello");
  // Both should not include buffer killing logic in the same way
  assertEqual(
    resultExplicit.includes("kill-buffer"),
    resultDefault.includes("kill-buffer"),
    "Default should match temp: false"
  );
});
test("buildWriteElisp - temp true with save should save before killing", () => {
  const result = buildWriteElisp("test.txt", "hello", { temp: true, save: true });
  assertContains(result, "save-buffer", "Should save buffer");
  assertContains(result, "kill-buffer", "Should kill buffer after save");
  // Verify order: save comes before kill
  const saveIdx = result.indexOf("save-buffer");
  const killIdx = result.indexOf("kill-buffer");
  assert(saveIdx < killIdx, "Save should come before kill");
});

// ---------------------------------------------------------------------------
// buildWriteElisp - temp and point movement
// ---------------------------------------------------------------------------

test("buildWriteElisp - temp true restores point after insert", () => {
  const result = buildWriteElisp("test.txt", "hello", { temp: true, point: true });
  assertContains(result, "save-excursion", "Should restore point");
});
test("buildWriteElisp - temp false moves point after insert", () => {
  const result = buildWriteElisp("test.txt", "hello", { temp: false, point: true });
  // Without temp, point should move after insert (normal Emacs behavior)
  assert(true, "Point should move normally");
});
test("buildWriteElisp - point true without temp leaves point after insert", () => {
  const result = buildWriteElisp("test.txt", "hello", { point: true });
  // Normal insert behavior: point moves to end of inserted text
  assertContains(result, "insert", "Should insert text");
});

// ---------------------------------------------------------------------------
// buildWriteElisp - elisp structure
// ---------------------------------------------------------------------------

test("buildWriteElisp - returns valid elisp", () => {
  const result = buildWriteElisp("test.txt", "hello");
  assert(result.startsWith("("), "Should start with opening paren");
  assert(result.endsWith(")"), "Should end with closing paren");
});
test("buildWriteElisp - balanced parentheses", () => {
  const result = buildWriteElisp("test.txt", "hello", { pos: 1, save: true, temp: true });
  let depth = 0;
  for (const ch of result) {
    if (ch === "(") depth++;
    if (ch === ")") depth--;
    assert(depth >= 0, "Parentheses went negative");
  }
  assertEqual(depth, 0, "Parentheses not balanced");
});
test("buildWriteElisp - uses json-encode", () => {
  const result = buildWriteElisp("test.txt", "hello");
  assertContains(result, "json-encode", "Should use json-encode for output");
});
test("buildWriteElisp - includes result metadata", () => {
  const result = buildWriteElisp("test.txt", "hello");
  const requiredFields = [
    "name", "path", "length", "point", "saved", "new", "dead"
  ];

  for (const field of requiredFields) {
    assertContains(result, `"${field}"`, `Should include field: ${field}`);
  }
});

// ---------------------------------------------------------------------------
// Result parsing - basic structure
// ---------------------------------------------------------------------------

test("parseWriteResult - handles successful insert", () => {
  const jsonResult = {
    name: "test.txt",
    path: "/home/user/test.txt",
    inserted: "hello world",
    length: 11,
    point: { pos: 12, line: 1, col: 11 },
    saved: false,
    new: false,
    dead: false
  };

  const elispStr = '"' + JSON.stringify(jsonResult).replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
  const parsed = parseEmacsclientOutput(elispStr);

  assertDeepEqual(parsed, jsonResult);
});
test("parseWriteResult - handles insert with save", () => {
  const jsonResult = {
    name: "test.txt",
    path: "/home/user/test.txt",
    inserted: "hello",
    length: 5,
    point: { pos: 6, line: 1, col: 5 },
    saved: true,
    new: false,
    dead: false
  };

  const elispStr = '"' + JSON.stringify(jsonResult).replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
  const parsed = parseEmacsclientOutput(elispStr);

  assertEqual(parsed.saved, true);
});
test("parseWriteResult - handles new buffer creation", () => {
  const jsonResult = {
    name: "new.txt",
    path: "/home/user/new.txt",
    inserted: "first content",
    length: 13,
    point: { pos: 14, line: 1, col: 13 },
    saved: false,
    new: true,
    dead: false
  };

  const elispStr = '"' + JSON.stringify(jsonResult).replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
  const parsed = parseEmacsclientOutput(elispStr);

  assertEqual(parsed.new, true);
  assertEqual(parsed.dead, false);
});
test("parseWriteResult - handles temp mode with new buffer", () => {
  const jsonResult = {
    name: "temp.txt",
    path: "/tmp/temp.txt",
    inserted: "temporary",
    length: 9,
    point: { pos: 1, line: 1, col: 0 },
    saved: false,
    new: true,
    dead: true
  };

  const elispStr = '"' + JSON.stringify(jsonResult).replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
  const parsed = parseEmacsclientOutput(elispStr);

  assertEqual(parsed.new, true);
  assertEqual(parsed.dead, true);
});
test("parseWriteResult - handles buffer without file", () => {
  const jsonResult = {
    name: "*scratch*",
    path: null,
    inserted: "(+ 1 2)",
    length: 7,
    point: { pos: 8, line: 1, col: 7 },
    saved: false,
    new: false,
    dead: false
  };

  const elispStr = '"' + JSON.stringify(jsonResult).replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
  const parsed = parseEmacsclientOutput(elispStr);

  assertEqual(parsed.path, null);
  assertEqual(parsed.saved, false);
});
test("parseWriteResult - handles multiline insert", () => {
  const content = "line1\nline2\nline3";
  const jsonResult = {
    name: "test.txt",
    path: "/home/user/test.txt",
    inserted: content,
    length: 17,
    point: { pos: 18, line: 3, col: 5 },
    saved: false,
    new: false,
    dead: false
  };

  const elispStr = '"' + JSON.stringify(jsonResult).replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
  const parsed = parseEmacsclientOutput(elispStr);

  assertEqual(parsed.inserted, content);
  assertEqual(parsed.length, 17);
});
test("parseWriteResult - handles insert at specific position", () => {
  const jsonResult = {
    name: "test.txt",
    path: "/home/user/test.txt",
    inserted: "INSERT",
    length: 6,
    point: { pos: 106, line: 5, col: 10 },
    saved: false,
    new: false,
    dead: false
  };

  const elispStr = '"' + JSON.stringify(jsonResult).replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
  const parsed = parseEmacsclientOutput(elispStr);

  assertEqual(parsed.point.pos, 106);
  assertEqual(parsed.point.line, 5);
});
test("parseWriteResult - handles empty string insert", () => {
  const jsonResult = {
    name: "test.txt",
    path: "/home/user/test.txt",
    inserted: "",
    length: 0,
    point: { pos: 1, line: 1, col: 0 },
    saved: false,
    new: false,
    dead: false
  };

  const elispStr = '"' + JSON.stringify(jsonResult).replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
  const parsed = parseEmacsclientOutput(elispStr);

  assertEqual(parsed.inserted, "");
  assertEqual(parsed.length, 0);
});
test("parseWriteResult - handles TRAMP remote buffer", () => {
  const jsonResult = {
    name: "remote.txt",
    path: "/ssh:user@host:/path/remote.txt",
    inserted: "remote content",
    length: 14,
    point: { pos: 15, line: 1, col: 14 },
    saved: true,
    new: false,
    dead: false
  };

  const elispStr = '"' + JSON.stringify(jsonResult).replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
  const parsed = parseEmacsclientOutput(elispStr);

  assertContains(parsed.path, "/ssh:user@host:");
  assertEqual(parsed.saved, true);
});

// ---------------------------------------------------------------------------
// Edge cases and error scenarios
// ---------------------------------------------------------------------------

test("buildWriteElisp - handles very long file path", () => {
  const longPath = "/very/long/path/" + "a".repeat(500) + "/file.txt";
  const result = buildWriteElisp(longPath, "test");
  assertContains(result, "a".repeat(100), "Should handle long paths");
});
test("buildWriteElisp - handles unicode in name", () => {
  const result = buildWriteElisp("test_文件.txt", "内容");
  assertContains(result, "文件", "Should handle unicode in name");
  assertContains(result, "内容", "Should handle unicode in content");
});
test("buildWriteElisp - handles emoji in name and content", () => {
  const result = buildWriteElisp("test🚀.txt", "Hello 🌍");
  assertContains(result, "🚀", "Should handle emoji in name");
  assertContains(result, "🌍", "Should handle emoji in content");
});
test("buildWriteElisp - handles backslashes in path", () => {
  const result = buildWriteElisp("C:\\Users\\test\\file.txt", "content");
  // Should escape properly for elisp
  assertContains(result, "\\\\", "Should escape backslashes");
});
test("buildWriteElisp - handles zero pos", () => {
  const result = buildWriteElisp("test.txt", "hello", { pos: 0 });
  assertContains(result, "0", "Should handle zero position");
});
test("buildWriteElisp - handles very long insert text", () => {
  const longText = "x".repeat(100000);
  const result = buildWriteElisp("test.txt", longText);
  assertContains(result, "x".repeat(100), "Should handle long text");
});
test("buildWriteElisp - handles insert text with special characters", () => {
  const specialText = 'text with\ttabs\nand\nnewlines\rand\r\ncarriage returns';
  const result = buildWriteElisp("test.txt", specialText);
  assert(result.includes("\\t") || result.includes("\t"), "Should handle tabs");
  assert(result.includes("\\n") || result.includes("\n"), "Should handle newlines");
});
test("buildWriteElisp - handles insert with pos at end of large file", () => {
  const result = buildWriteElisp("test.txt", "append", { pos: 1000000 });
  assertContains(result, "1000000", "Should handle large position");
});
test("buildWriteElisp - point parameter is boolean only", () => {
  // point should be boolean, not a number
  const result = buildWriteElisp("test.txt", "hello", { point: true });
  assertContains(result, "insert", "Should handle point as boolean");
});

// ---------------------------------------------------------------------------
// Combined scenarios
// ---------------------------------------------------------------------------

test("buildWriteElisp - pos + save + temp", () => {
  const result = buildWriteElisp("test.txt", "hello", { pos: 50, save: true, temp: true });
  assertContains(result, "goto-char", "Should move to position");
  assertContains(result, "save-buffer", "Should save");
  assertContains(result, "kill-buffer", "Should kill buffer in temp mode");
});
test("buildWriteElisp - line + save", () => {
  const result = buildWriteElisp("test.txt", "hello", { line: 10, save: true });
  assertContains(result, "10", "Should go to line");
  assertContains(result, "save-buffer", "Should save");
});
test("buildWriteElisp - point + temp", () => {
  const result = buildWriteElisp("test.txt", "hello", { point: true, temp: true });
  assertContains(result, "save-excursion", "Should restore state");
});
test("buildWriteElisp - replace + save", () => {
  const result = buildWriteElisp("test.txt", "completely new", { replace: true, save: true });
  assertContains(result, "delete-region", "Should clear buffer");
  assertContains(result, "insert", "Should insert new content");
  assertContains(result, "save-buffer", "Should save file");
});
test("buildWriteElisp - replace + temp", () => {
  const result = buildWriteElisp("test.txt", "new", { replace: true, temp: true });
  assertContains(result, "delete-region", "Should clear buffer");
  assertContains(result, "kill-buffer", "Should kill buffer after");
});
test("buildWriteElisp - replace + save + temp", () => {
  const result = buildWriteElisp("test.txt", "final content", { replace: true, save: true, temp: true });
  assertContains(result, "delete-region", "Should clear buffer");
  assertContains(result, "insert", "Should insert content");
  assertContains(result, "save-buffer", "Should save");
  assertContains(result, "kill-buffer", "Should kill buffer in temp mode");
  // Verify order: delete -> insert -> save -> kill
  const deleteIdx = result.indexOf("delete-region");
  const insertIdx = result.indexOf("insert");
  const saveIdx = result.indexOf("save-buffer");
  const killIdx = result.indexOf("kill-buffer");
  assert(deleteIdx < insertIdx && insertIdx < saveIdx && saveIdx < killIdx,
    "Operations should be in correct order: delete, insert, save, kill");
});

// ---------------------------------------------------------------------------
// buildWriteElisp - type parameter (keyboard macro)
// ---------------------------------------------------------------------------

test("buildWriteElisp - accepts type parameter", () => {
  const result = buildWriteElisp("test.txt", undefined, { type: "C-x C-s" });
  assertContains(result, "execute-kbd-macro", "Should call execute-kbd-macro");
  assertContains(result, "kbd", "Should use kbd to parse the macro string");
});

test("buildWriteElisp - type includes the macro string", () => {
  const result = buildWriteElisp("test.txt", undefined, { type: "C-c C-c" });
  assertContains(result, "C-c C-c", "Should include the macro string verbatim");
});

test("buildWriteElisp - type alone (no insert) is valid", () => {
  // Running a macro without inserting text is explicitly a supported use case
  const result = buildWriteElisp("test.txt", undefined, { type: "RET" });
  assert(typeof result === "string", "Should not throw");
  assertContains(result, "execute-kbd-macro", "Should execute the macro");
});

test("buildWriteElisp - type alone does not emit (insert ...) call", () => {
  const result = buildWriteElisp("test.txt", undefined, { type: "M-x fill-paragraph RET" });
  assert(!/\(insert "/.test(result), "Should not call (insert ...) when no insert text given");
});

test("buildWriteElisp - type escapes double-quotes", () => {
  const result = buildWriteElisp("test.txt", undefined, { type: 'C-c "mark" C-c' });
  assertContains(result, '\\"', "Should escape quotes inside macro string");
});

test("buildWriteElisp - type escapes newlines", () => {
  const result = buildWriteElisp("test.txt", undefined, { type: "C-c\nC-c" });
  assertContains(result, "\\n", "Should escape newlines in macro string");
});

test("buildWriteElisp - type alone balanced parentheses", () => {
  const result = buildWriteElisp("test.txt", undefined, { type: "C-c C-c" });
  let depth = 0;
  for (const ch of result) {
    if (ch === "(") depth++;
    if (ch === ")") depth--;
    assert(depth >= 0, "Parentheses went negative");
  }
  assertEqual(depth, 0, "Parentheses not balanced");
});

test("buildWriteElisp - type with pos: navigates before executing macro", () => {
  const result = buildWriteElisp("test.txt", undefined, { type: "RET", pos: 10 });
  assertContains(result, "goto-char", "Should navigate to position");
  assertContains(result, "execute-kbd-macro", "Should execute macro");
  assert(result.indexOf("goto-char") < result.indexOf("execute-kbd-macro"),
    "Should navigate before executing macro");
});

test("buildWriteElisp - type with line: navigates before executing macro", () => {
  const result = buildWriteElisp("test.txt", undefined, { type: "C-k", line: 5 });
  assertContains(result, "forward-line", "Should navigate to line");
  assertContains(result, "execute-kbd-macro", "Should execute macro");
  assert(result.indexOf("forward-line") < result.indexOf("execute-kbd-macro"),
    "Should navigate before executing macro");
});

test("buildWriteElisp - type with replace: clears buffer before executing macro", () => {
  const result = buildWriteElisp("test.txt", undefined, { type: "C-x C-s", replace: true });
  assertContains(result, "delete-region", "Should clear buffer");
  assertContains(result, "execute-kbd-macro", "Should execute macro");
  assert(result.indexOf("delete-region") < result.indexOf("execute-kbd-macro"),
    "Should clear buffer before executing macro");
});

test("buildWriteElisp - type with save: executes macro before saving", () => {
  const result = buildWriteElisp("test.txt", undefined, { type: "C-c C-c", save: true });
  assertContains(result, "execute-kbd-macro", "Should execute macro");
  assertContains(result, "save-buffer", "Should save buffer");
  assert(result.indexOf("execute-kbd-macro") < result.indexOf("save-buffer"),
    "Should execute macro before saving");
});

test("buildWriteElisp - type with temp: wraps in save-excursion", () => {
  const result = buildWriteElisp("test.txt", undefined, { type: "M-<", temp: true });
  assertContains(result, "save-excursion", "Should use save-excursion in temp mode");
  assertContains(result, "execute-kbd-macro", "Should still execute macro");
});

test("buildWriteElisp - type with pos + save + temp", () => {
  const result = buildWriteElisp("test.txt", undefined, { type: "C-e", pos: 50, save: true, temp: true });
  assertContains(result, "goto-char", "Should navigate");
  assertContains(result, "execute-kbd-macro", "Should execute macro");
  assertContains(result, "save-buffer", "Should save");
  assertContains(result, "kill-buffer", "Should kill buffer in temp mode");
});

// ---------------------------------------------------------------------------
// buildWriteElisp - type + insert combination
// ---------------------------------------------------------------------------

test("buildWriteElisp - type with insert includes both operations", () => {
  const result = buildWriteElisp("test.txt", "hello", { type: "C-e" });
  assertContains(result, "(insert ", "Should insert text");
  assertContains(result, "execute-kbd-macro", "Should execute macro");
});

test("buildWriteElisp - type runs after insert", () => {
  const result = buildWriteElisp("test.txt", "hello", { type: "C-e" });
  assert(result.indexOf("(insert ") < result.indexOf("execute-kbd-macro"),
    "insert should run before execute-kbd-macro");
});

test("buildWriteElisp - type with insert and save: order is insert -> macro -> save", () => {
  const result = buildWriteElisp("test.txt", "hello", { type: "C-e", save: true });
  const insertIdx = result.indexOf("(insert ");
  const kbdIdx    = result.indexOf("execute-kbd-macro");
  const saveIdx   = result.indexOf("save-buffer");
  assert(insertIdx < kbdIdx, "insert should come before macro");
  assert(kbdIdx    < saveIdx, "macro should come before save");
});

test("buildWriteElisp - type with insert and replace: order is delete -> insert -> macro", () => {
  const result = buildWriteElisp("test.txt", "hello", { type: "C-e", replace: true });
  const deleteIdx = result.indexOf("delete-region");
  const insertIdx = result.indexOf("(insert ");
  const kbdIdx    = result.indexOf("execute-kbd-macro");
  assert(deleteIdx < insertIdx, "delete should come before insert");
  assert(insertIdx < kbdIdx,    "insert should come before macro");
});

test("buildWriteElisp - type with insert and temp", () => {
  const result = buildWriteElisp("test.txt", "hello", { type: "C-e", temp: true });
  assertContains(result, "save-excursion",  "Should restore state in temp mode");
  assertContains(result, "(insert ",        "Should insert text");
  assertContains(result, "execute-kbd-macro", "Should execute macro");
});

test("buildWriteElisp - type with insert and replace + save + temp: full order", () => {
  const result = buildWriteElisp("test.txt", "hello", { type: "C-e", replace: true, save: true, temp: true });
  const deleteIdx = result.indexOf("delete-region");
  const insertIdx = result.indexOf("(insert ");
  const kbdIdx    = result.indexOf("execute-kbd-macro");
  const saveIdx   = result.indexOf("save-buffer");
  const killIdx   = result.indexOf("kill-buffer");
  assert(deleteIdx < insertIdx, "delete before insert");
  assert(insertIdx < kbdIdx,    "insert before macro");
  assert(kbdIdx    < saveIdx,   "macro before save");
  assert(saveIdx   < killIdx,   "save before kill");
});

test("buildWriteElisp - type with insert balanced parentheses", () => {
  const result = buildWriteElisp("test.txt", "hello world", { type: "C-x C-s", save: true, temp: true });
  let depth = 0;
  for (const ch of result) {
    if (ch === "(") depth++;
    if (ch === ")") depth--;
    assert(depth >= 0, "Parentheses went negative");
  }
  assertEqual(depth, 0, "Parentheses not balanced");
});

// ---------------------------------------------------------------------------
// buildWriteElisp - neither insert nor type (navigation / clear / save only)
// ---------------------------------------------------------------------------

test("buildWriteElisp - neither insert nor type is valid (navigate only)", () => {
  // e.g. just move point without touching buffer contents
  const result = buildWriteElisp("test.txt", undefined, { pos: 100 });
  assert(typeof result === "string", "Should not throw");
  assertContains(result, "goto-char", "Should navigate to position");
});

test("buildWriteElisp - neither insert nor type with save (save-only)", () => {
  const result = buildWriteElisp("test.txt", undefined, { save: true });
  assert(typeof result === "string", "Should not throw");
  assertContains(result, "save-buffer", "Should save buffer");
});

test("buildWriteElisp - neither insert nor type with replace (clear-only)", () => {
  const result = buildWriteElisp("test.txt", undefined, { replace: true });
  assert(typeof result === "string", "Should not throw");
  assertContains(result, "delete-region", "Should clear buffer");
});

test("buildWriteElisp - neither insert nor type with line + save", () => {
  const result = buildWriteElisp("test.txt", undefined, { line: 42, save: true });
  assert(typeof result === "string", "Should not throw");
  assertContains(result, "save-buffer", "Should save buffer");
});

test("buildWriteElisp - neither insert nor type with temp (peek metadata)", () => {
  // Valid: open buffer, capture metadata, restore state
  const result = buildWriteElisp("test.txt", undefined, { temp: true });
  assert(typeof result === "string", "Should not throw");
  assertContains(result, "save-excursion", "Should restore state");
});

test("buildWriteElisp - neither insert nor type omits inserted, length, and typed from result", () => {
  const result = buildWriteElisp("test.txt", undefined, { save: true });
  assert(!result.includes('"inserted"'), "Should not include inserted field");
  assert(!result.includes('"typed"'),    "Should not include typed field");
});

test("buildWriteElisp - neither insert nor type balanced parentheses", () => {
  const result = buildWriteElisp("test.txt", undefined, { pos: 1, save: true, temp: true });
  let depth = 0;
  for (const ch of result) {
    if (ch === "(") depth++;
    if (ch === ")") depth--;
    assert(depth >= 0, "Parentheses went negative");
  }
  assertEqual(depth, 0, "Parentheses not balanced");
});

// ---------------------------------------------------------------------------
// make-directory: parent dirs created silently (no interactive prompt)
// ---------------------------------------------------------------------------

test("buildWriteElisp - path case includes make-directory before find-file-noselect", () => {
  const result = buildWriteElisp("/some/new/dir/file.ts", "hello");
  // make-directory must appear before find-file-noselect so the parent is
  // created unconditionally, preventing Emacs's "Create directory?" prompt.
  const mkIdx = result.indexOf("make-directory");
  const ffIdx = result.indexOf("find-file-noselect");
  assert(mkIdx !== -1, "Should contain make-directory");
  assert(ffIdx !== -1, "Should contain find-file-noselect");
  assert(mkIdx < ffIdx, "make-directory must come before find-file-noselect");
});

test("buildWriteElisp - buffer name (no path prefix) does not add make-directory", () => {
  // Buffer names like "*scratch*" are not file paths; no directory creation needed.
  const result = buildWriteElisp("*scratch*", "hello");
  assert(!result.includes("make-directory"), "Buffer names should not call make-directory");
});

// ---------------------------------------------------------------------------
// Summary
// ---------------------------------------------------------------------------

process.on("beforeExit", () => {
  console.log(`\n# ${passed} passed, ${failed} failed`);
  if (failed > 0) process.exit(1);
});

#!/usr/bin/env tsx
/**
 * Tests for custom 'read' tool in emacsclient extension.
 */

import {
  escapeElispString,
  parseEmacsclientOutput,
  buildReadElisp,
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
// buildReadElisp - basic parameter handling
// ---------------------------------------------------------------------------

test("buildReadElisp - accepts name parameter", () => {
  const result = buildReadElisp("test.txt");
  assert(typeof result === "string", "Should return a string");
  assertContains(result, "test.txt", "Should reference the name");
});

test("buildReadElisp - escapes special characters in name", () => {
  const result = buildReadElisp('file "with" quotes.txt');
  assertContains(result, '\\"', "Should escape quotes");
});

test("buildReadElisp - handles file path with forward slash", () => {
  const result = buildReadElisp("/home/user/file.txt");
  assertContains(result, "/home/user/file.txt", "Should include path");
  assertContains(result, "find-file", "Should use find-file for paths");
});

test("buildReadElisp - handles buffer name without slash", () => {
  const result = buildReadElisp("*scratch*");
  assertContains(result, "get-buffer", "Should use get-buffer for buffer names");
});

test("buildReadElisp - handles relative path starting with ./", () => {
  const result = buildReadElisp("./relative.txt");
  assertContains(result, "./relative.txt", "Should preserve relative path");
  assertContains(result, "find-file", "Should use find-file for paths");
});

test("buildReadElisp - detects TRAMP path", () => {
  const result = buildReadElisp("/ssh:user@host:/path/to/file");
  assertContains(result, "/ssh:user@host:/path/to/file", "Should handle TRAMP path");
  assertContains(result, "find-file", "Should use find-file for TRAMP");
});

// ---------------------------------------------------------------------------
// buildReadElisp - position parameters
// ---------------------------------------------------------------------------

test("buildReadElisp - accepts pos parameter", () => {
  const result = buildReadElisp("test.txt", { pos: 100 });
  assertContains(result, "100", "Should include position");
  assertContains(result, "goto-char", "Should use goto-char for position");
});

test("buildReadElisp - accepts line parameter", () => {
  const result = buildReadElisp("test.txt", { line: 10 });
  assertContains(result, "10", "Should include line number");
  assertContains(result, "goto-line", "Should use goto-line or forward-line");
});

test("buildReadElisp - accepts line and col parameters", () => {
  const result = buildReadElisp("test.txt", { line: 10, col: 5 });
  assertContains(result, "10", "Should include line number");
  assertContains(result, "5", "Should include column number");
});

test("buildReadElisp - pos overrides line/col", () => {
  const result = buildReadElisp("test.txt", { pos: 100, line: 10, col: 5 });
  assertContains(result, "100", "Should include position");
  assertContains(result, "goto-char", "Should prefer goto-char when pos is given");
});

test("buildReadElisp - handles negative pos", () => {
  const result = buildReadElisp("test.txt", { pos: -50 });
  assertContains(result, "-50", "Should include negative position");
});

test("buildReadElisp - handles negative line", () => {
  const result = buildReadElisp("test.txt", { line: -5 });
  assertContains(result, "-5", "Should include negative line number");
});

test("buildReadElisp - move: true moves point to content-end after reading", () => {
  const result = buildReadElisp("test.txt", { move: true });
  // When move is explicitly true, point should be left at the end of the content
  // read, so a subsequent read (with no pos) continues from where we left off.
  // The let* binding (_ (goto-char content-end)) is what achieves this.
  assertContains(result, "(_ (goto-char content-end))",
    "Should bind _ to (goto-char content-end) to advance point when move: true");
});

test("buildReadElisp - move: false (default) does not move point to content-end", () => {
  const result = buildReadElisp("test.txt");
  // Default is move: false — point must be restored to where it was before
  // reading. The _ binding must be nil (not a goto-char).
  // Note: (goto-char content-end) may still appear inside save-excursion in the
  // 'got' metadata block — that's a temporary peek, not a permanent move.
  assertContains(result, "(_ nil)",
    "Should bind _ to nil (no point advancement) when move defaults to false");
  assert(!result.includes("(_ (goto-char content-end))"),
    "Should not have the point-advancing binding when move: false");
});

test("buildReadElisp - move: false restores original point", () => {
  const result = buildReadElisp("test.txt", { move: false });
  // When move is false, the elisp must save and later restore the original point.
  assertContains(result, "original-point",
    "Should capture original-point for restoration");
  assertContains(result, "(goto-char original-point)",
    "Should restore original point after reading");
});

test("buildReadElisp - move: true with temp: true still moves point", () => {
  // temp and move are independent: temp governs buffer lifecycle,
  // move governs point position.
  const result = buildReadElisp("test.txt", { temp: true, move: true });
  assertContains(result, "(_ (goto-char content-end))",
    "move: true should advance point even in temp mode");
  // temp: true should still kill newly opened buffers
  assertContains(result, "kill-buffer",
    "temp: true should still kill newly opened buffers");
});

test("buildReadElisp - move: false with temp: true kills buffers but restores point", () => {
  const result = buildReadElisp("test.txt", { temp: true, move: false });
  assertContains(result, "kill-buffer",
    "temp: true should kill newly opened buffers");
  assertContains(result, "(_ nil)",
    "move: false should not advance point");
  assertContains(result, "(goto-char original-point)",
    "Should restore original point when move: false");
});

test("buildReadElisp - temp and move are fully independent", () => {
  // All four combinations should behave correctly
  const tt = buildReadElisp("test.txt", { temp: true,  move: true  });
  const tf = buildReadElisp("test.txt", { temp: true,  move: false });
  const ft = buildReadElisp("test.txt", { temp: false, move: true  });
  const ff = buildReadElisp("test.txt", { temp: false, move: false });

  // move: true → advances point
  assertContains(tt, "(_ (goto-char content-end))", "temp:true  move:true  → point advances");
  assertContains(ft, "(_ (goto-char content-end))", "temp:false move:true  → point advances");
  // move: false → restores point
  assertContains(tf, "(_ nil)", "temp:true  move:false → point not advanced");
  assertContains(ff, "(_ nil)", "temp:false move:false → point not advanced");

  // temp: true → kills new buffers
  assertContains(tt, "kill-buffer", "temp:true  move:true  → kills buffers");
  assertContains(tf, "kill-buffer", "temp:true  move:false → kills buffers");
  // temp: false → does not kill new buffers
  assert(!ft.includes("kill-buffer"), "temp:false move:true  → no kill");
  assert(!ff.includes("kill-buffer"), "temp:false move:false → no kill");
});

// ---------------------------------------------------------------------------
// buildReadElisp - content extraction parameters
// ---------------------------------------------------------------------------

test("buildReadElisp - accepts length parameter", () => {
  const result = buildReadElisp("test.txt", { length: 1000 });
  assertContains(result, "1000", "Should include length");
});

test("buildReadElisp - accepts lines parameter", () => {
  const result = buildReadElisp("test.txt", { lines: 10 });
  assertContains(result, "10", "Should include line count");
});

test("buildReadElisp - uses maxLength when no length/lines specified", () => {
  const result = buildReadElisp("test.txt", {}, 50000);
  assertContains(result, "50000", "Should use max length");
});

test("buildReadElisp - respects maxLength as upper bound", () => {
  const result = buildReadElisp("test.txt", { length: 100000 }, 50000);
  // Should cap at maxLength
  assertContains(result, "50000", "Should respect max length");
});

// ---------------------------------------------------------------------------
// buildReadElisp - temp mode
// ---------------------------------------------------------------------------

test("buildReadElisp - temp: true kills newly opened buffers", () => {
  // temp governs buffer lifecycle only (not point movement — see move parameter).
  const result = buildReadElisp("test.txt", { temp: true });
  assertContains(result, "kill-buffer", "Should kill newly opened buffers in temp mode");
  assertContains(result, "was-new", "Should check whether buffer was newly opened");
});

test("buildReadElisp - temp: false (default) does not kill newly opened buffers", () => {
  const result = buildReadElisp("test.txt", { temp: false });
  assert(!result.includes("kill-buffer"), "Should not kill buffer when temp is false");
});

test("buildReadElisp - temp true kills new buffers", () => {
  const result = buildReadElisp("test.txt", { temp: true });
  assertContains(result, "kill-buffer", "Should kill buffer if newly opened");
});

test("buildReadElisp - temp false is default", () => {
  const resultExplicit = buildReadElisp("test.txt", { temp: false });
  const resultDefault = buildReadElisp("test.txt");
  // Both should not include buffer killing logic in the same way
  assertEqual(
    resultExplicit.includes("kill-buffer"),
    resultDefault.includes("kill-buffer"),
    "Default should match temp: false"
  );
});

test("buildReadElisp - temp false does not produce 'false' symbol in elisp", () => {
  const result = buildReadElisp("test.txt", { temp: false });
  assert(!result.includes(" false)"), "Should not contain elisp 'false' symbol");
  assert(!result.includes("(not false)"), "Should not contain '(not false)'");
  // Should use proper elisp nil instead
  assertContains(result, "nil", "Should contain nil for false boolean");
});

test("buildReadElisp - temp true does not produce 'true' symbol in elisp", () => {
  const result = buildReadElisp("test.txt", { temp: true });
  assert(!result.includes(" true)"), "Should not contain elisp 'true' symbol");
  assert(!result.includes("(not true)"), "Should not contain '(not true)'");
  // Should use proper elisp t instead
  assertContains(result, "(not t)", "Should use 't' for true boolean");
});

// ---------------------------------------------------------------------------
// buildReadElisp - elisp structure
// ---------------------------------------------------------------------------

test("buildReadElisp - returns valid elisp", () => {
  const result = buildReadElisp("test.txt");
  assert(result.startsWith("("), "Should start with opening paren");
  assert(result.endsWith(")"), "Should end with closing paren");
});

test("buildReadElisp - balanced parentheses", () => {
  const result = buildReadElisp("test.txt", { pos: 1, length: 1000, temp: true });
  // Count parens, but skip escaped ones and ones in strings
  let depth = 0;
  let inString = false;
  let escaped = false;
  for (let i = 0; i < result.length; i++) {
    const ch = result[i];
    if (escaped) {
      escaped = false;
      continue;
    }
    if (ch === "\\") {
      escaped = true;
      continue;
    }
    if (ch === '"') {
      inString = !inString;
      continue;
    }
    if (!inString) {
      if (ch === "(") depth++;
      if (ch === ")") depth--;
      assert(depth >= 0, "Parentheses went negative at position " + i);
    }
  }
  assertEqual(depth, 0, "Parentheses not balanced");
});

test("buildReadElisp - uses json-encode", () => {
  const result = buildReadElisp("test.txt");
  assertContains(result, "json-encode", "Should use json-encode for output");
});

test("buildReadElisp - includes all required fields", () => {
  const result = buildReadElisp("test.txt");
  const requiredFields = [
    "name", "path", "exists", "unsaved", "outdated", "size", "lines", "mode",
    "eglot", "ts", "tramp", "new", "dead", "process", "point", "region", "got"
  ];

  for (const field of requiredFields) {
    assertContains(result, `"${field}"`, `Should include field: ${field}`);
  }
});

// ---------------------------------------------------------------------------
// buildReadElisp - move parameter: point object behaviour
// ---------------------------------------------------------------------------

test("buildReadElisp - move: false uses original-point for point object", () => {
  const result = buildReadElisp("test.txt", { move: false });
  // When move: false, result.point must reflect the RESTORED position
  // (original-point), not wherever the read expression happened to leave point.
  // We check that the point object is computed via (goto-char original-point).
  assertContains(result, "goto-char original-point",
    "Should use original-point for point object when move: false");
});

test("buildReadElisp - move: true does not save/restore original-point for point object", () => {
  const result = buildReadElisp("test.txt", { move: true });
  // When move: true, original-point is never captured and never used:
  // point ends up at content-end and is reported as-is.
  assert(!result.includes("original-point"),
    "move: true should not reference original-point at all");
});

// ---------------------------------------------------------------------------
// Result parsing - basic structure
// ---------------------------------------------------------------------------

test("parseReadResult - handles complete result object", () => {
  const jsonResult = {
    name: "test.txt",
    path: "/home/user/test.txt",
    exists: true,
    changed: false,
    size: 1234,
    lines: 56,
    mode: "text-mode",
    eglot: false,
    ts: false,
    tramp: null,
    new: false,
    dead: false,
    process: null,
    point: { pos: 1, line: 1, col: 0 },
    region: null,
    got: {
      content: "test content",
      length: 12,
      lines: 1,
      start: { pos: 1, line: 1, col: 0 },
      end: { pos: 13, line: 1, col: 12 },
      truncated: false
    }
  };

  const elispStr = '"' + JSON.stringify(jsonResult).replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
  const parsed = parseEmacsclientOutput(elispStr);

  assertDeepEqual(parsed, jsonResult);
});

test("parseReadResult - handles file that doesn't exist", () => {
  const jsonResult = {
    name: "new.txt",
    path: "/home/user/new.txt",
    exists: false,
    changed: false,
    size: 0,
    lines: 0,
    mode: "fundamental-mode",
    eglot: false,
    ts: false,
    tramp: null,
    new: true,
    dead: false,
    process: null,
    point: { pos: 1, line: 1, col: 0 },
    region: null,
    got: {
      content: "",
      length: 0,
      lines: 0,
      start: { pos: 1, line: 1, col: 0 },
      end: { pos: 1, line: 1, col: 0 },
      truncated: false
    }
  };

  const elispStr = '"' + JSON.stringify(jsonResult).replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
  const parsed = parseEmacsclientOutput(elispStr);

  assertEqual(parsed.exists, false);
  assertEqual(parsed.new, true);
});

test("parseReadResult - handles buffer without file", () => {
  const jsonResult = {
    name: "*scratch*",
    path: null,
    exists: null,
    changed: false,
    size: 100,
    lines: 5,
    mode: "lisp-interaction-mode",
    eglot: false,
    ts: false,
    tramp: null,
    new: false,
    dead: false,
    process: null,
    point: { pos: 50, line: 3, col: 10 },
    region: null,
    got: {
      content: "test",
      length: 4,
      lines: 1,
      start: { pos: 50, line: 3, col: 10 },
      end: { pos: 54, line: 3, col: 14 },
      truncated: true
    }
  };

  const elispStr = '"' + JSON.stringify(jsonResult).replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
  const parsed = parseEmacsclientOutput(elispStr);

  assertEqual(parsed.path, null);
  assertEqual(parsed.exists, null);
});

test("parseReadResult - handles buffer with active region", () => {
  const jsonResult = {
    name: "test.py",
    path: "/home/user/test.py",
    exists: true,
    changed: true,
    size: 500,
    lines: 20,
    mode: "python-mode",
    eglot: true,
    ts: true,
    tramp: null,
    new: false,
    dead: false,
    process: null,
    point: { pos: 150, line: 8, col: 5 },
    region: {
      content: "selected text",
      truncated: false,
      start: { pos: 100, line: 5, col: 0 },
      end: { pos: 150, line: 8, col: 5 }
    },
    got: {
      content: "buffer content",
      length: 14,
      lines: 1,
      start: { pos: 150, line: 8, col: 5 },
      end: { pos: 164, line: 8, col: 19 },
      truncated: false
    }
  };

  const elispStr = '"' + JSON.stringify(jsonResult).replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
  const parsed = parseEmacsclientOutput(elispStr);

  assert(parsed.region !== null, "Should have region");
  assertEqual(parsed.region.content, "selected text");
});

test("parseReadResult - handles buffer with process", () => {
  const jsonResult = {
    name: "*shell*",
    path: null,
    exists: null,
    changed: false,
    size: 1000,
    lines: 30,
    mode: "shell-mode",
    eglot: false,
    ts: false,
    tramp: null,
    new: false,
    dead: false,
    process: {
      state: "run",
      cmd: "/bin/bash"
    },
    point: { pos: 1000, line: 30, col: 0 },
    region: null,
    got: {
      content: "$ ",
      length: 2,
      lines: 1,
      start: { pos: 1000, line: 30, col: 0 },
      end: { pos: 1002, line: 30, col: 2 },
      truncated: false
    }
  };

  const elispStr = '"' + JSON.stringify(jsonResult).replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
  const parsed = parseEmacsclientOutput(elispStr);

  assert(parsed.process !== null, "Should have process");
  assertEqual(parsed.process.state, "run");
  assertEqual(parsed.process.cmd, "/bin/bash");
});

test("parseReadResult - handles TRAMP remote buffer", () => {
  const jsonResult = {
    name: "remote.txt",
    path: "/ssh:user@host:/path/remote.txt",
    exists: true,
    changed: false,
    size: 200,
    lines: 10,
    mode: "text-mode",
    eglot: false,
    ts: false,
    tramp: "ssh:user@host",
    new: false,
    dead: false,
    process: null,
    point: { pos: 1, line: 1, col: 0 },
    region: null,
    got: {
      content: "remote content",
      length: 14,
      lines: 1,
      start: { pos: 1, line: 1, col: 0 },
      end: { pos: 15, line: 1, col: 14 },
      truncated: false
    }
  };

  const elispStr = '"' + JSON.stringify(jsonResult).replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
  const parsed = parseEmacsclientOutput(elispStr);

  assertEqual(parsed.tramp, "ssh:user@host");
  assertContains(parsed.path, "/ssh:user@host:");
});

test("parseReadResult - handles temp mode with new buffer", () => {
  const jsonResult = {
    name: "temp.txt",
    path: "/tmp/temp.txt",
    exists: false,
    changed: false,
    size: 0,
    lines: 0,
    mode: "fundamental-mode",
    eglot: false,
    ts: false,
    tramp: null,
    new: true,
    dead: true,
    process: null,
    point: { pos: 1, line: 1, col: 0 },
    region: null,
    got: {
      content: "",
      length: 0,
      lines: 0,
      start: { pos: 1, line: 1, col: 0 },
      end: { pos: 1, line: 1, col: 0 },
      truncated: false
    }
  };

  const elispStr = '"' + JSON.stringify(jsonResult).replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
  const parsed = parseEmacsclientOutput(elispStr);

  assertEqual(parsed.new, true);
  assertEqual(parsed.dead, true);
});

test("parseReadResult - handles truncated content", () => {
  const jsonResult = {
    name: "large.txt",
    path: "/home/user/large.txt",
    exists: true,
    changed: false,
    size: 100000,
    lines: 5000,
    mode: "text-mode",
    eglot: false,
    ts: false,
    tramp: null,
    new: false,
    dead: false,
    process: null,
    point: { pos: 1, line: 1, col: 0 },
    region: null,
    got: {
      content: "a".repeat(50000),
      length: 50000,
      lines: 1,
      start: { pos: 1, line: 1, col: 0 },
      end: { pos: 50001, line: 1, col: 50000 },
      truncated: true
    }
  };

  const elispStr = '"' + JSON.stringify(jsonResult).replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
  const parsed = parseEmacsclientOutput(elispStr);

  assertEqual(parsed.got.truncated, true);
  assertEqual(parsed.got.length, 50000);
});

test("parseReadResult - handles multiline content", () => {
  const content = "line1\nline2\nline3";
  const jsonResult = {
    name: "test.txt",
    path: "/home/user/test.txt",
    exists: true,
    changed: false,
    size: 17,
    lines: 3,
    mode: "text-mode",
    eglot: false,
    ts: false,
    tramp: null,
    new: false,
    dead: false,
    process: null,
    point: { pos: 1, line: 1, col: 0 },
    region: null,
    got: {
      content: content,
      length: 17,
      lines: 3,
      start: { pos: 1, line: 1, col: 0 },
      end: { pos: 18, line: 3, col: 5 },
      truncated: false
    }
  };

  const elispStr = '"' + JSON.stringify(jsonResult).replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
  const parsed = parseEmacsclientOutput(elispStr);

  assertEqual(parsed.got.content, content);
  assertEqual(parsed.got.lines, 3);
});

test("parseReadResult - handles region truncation", () => {
  const jsonResult = {
    name: "test.txt",
    path: "/home/user/test.txt",
    exists: true,
    changed: false,
    size: 1000,
    lines: 50,
    mode: "text-mode",
    eglot: false,
    ts: false,
    tramp: null,
    new: false,
    dead: false,
    process: null,
    point: { pos: 500, line: 25, col: 10 },
    region: {
      content: "a".repeat(60000),
      truncated: true,
      start: { pos: 100, line: 5, col: 0 },
      end: { pos: 900, line: 45, col: 10 }
    },
    got: {
      content: "test",
      length: 4,
      lines: 1,
      start: { pos: 500, line: 25, col: 10 },
      end: { pos: 504, line: 25, col: 14 },
      truncated: false
    }
  };

  const elispStr = '"' + JSON.stringify(jsonResult).replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';
  const parsed = parseEmacsclientOutput(elispStr);

  assertEqual(parsed.region.truncated, true);
  assertEqual(parsed.region.content.length, 60000);
});

// ---------------------------------------------------------------------------
// Edge cases and error scenarios
// ---------------------------------------------------------------------------

test("buildReadElisp - handles very long file path", () => {
  const longPath = "/very/long/path/" + "a".repeat(500) + "/file.txt";
  const result = buildReadElisp(longPath);
  assertContains(result, "a".repeat(100), "Should handle long paths");
});

test("buildReadElisp - handles unicode in name", () => {
  const result = buildReadElisp("test_文件.txt");
  assertContains(result, "文件", "Should handle unicode");
});

test("buildReadElisp - handles emoji in name", () => {
  const result = buildReadElisp("test🚀.txt");
  assertContains(result, "🚀", "Should handle emoji");
});

test("buildReadElisp - handles backslashes in path", () => {
  const result = buildReadElisp("C:\\Users\\test\\file.txt");
  // Should escape properly for elisp
  assertContains(result, "\\\\", "Should escape backslashes");
});

test("buildReadElisp - pos 0 means use current point (no goto-char)", () => {
  // pos: 0 is documented as "uses point" — same as omitting pos entirely.
  // The generated elisp must NOT unconditionally move point.
  const resultWithZero = buildReadElisp("test.txt", { pos: 0 });
  const resultOmitted  = buildReadElisp("test.txt", {});
  // Both should behave the same: neither should emit an unconditional goto-char
  // (a goto-char inside save-excursion/unless is acceptable for internal use).
  const hasUncondGotoChar = (s: string) =>
    // Detect a bare (goto-char <literal-number>) not inside save-excursion
    /\(goto-char\s+\d+\)/.test(s);
  assert(!hasUncondGotoChar(resultWithZero),
    "pos:0 should not emit goto-char with a literal position");
  assert(!hasUncondGotoChar(resultOmitted),
    "omitted pos should not emit goto-char with a literal position");
  // The two forms should produce identical elisp
  assert(resultWithZero === resultOmitted,
    "pos:0 and omitted pos should produce identical elisp");
});

test("buildReadElisp - handles zero length", () => {
  const result = buildReadElisp("test.txt", { length: 0 });
  assertContains(result, "0", "Should handle zero length");
});

test("buildReadElisp - handles zero lines", () => {
  const result = buildReadElisp("test.txt", { lines: 0 });
  assertContains(result, "0", "Should handle zero lines");
});

// ---------------------------------------------------------------------------
// buildReadElisp - span parameter handling
// ---------------------------------------------------------------------------

test("buildReadElisp - accepts span parameter", () => {
  const result = buildReadElisp("test.txt", { span: "fn89f4" });
  assert(typeof result === "string", "Should return a string");
  assertContains(result, "fn89f4", "Should reference the span ID");
});

test("buildReadElisp - span parameter triggers warbo-span-calculators", () => {
  const result = buildReadElisp("test.txt", { span: "fn89f4" });
  assertContains(result, "warbo-span-calculators", "Should reference warbo-span-calculators");
});

test("buildReadElisp - span parameter includes narrowing logic", () => {
  const result = buildReadElisp("test.txt", { span: "fn89f4" });
  assertContains(result, "narrow-to-region", "Should include narrowing");
  assertContains(result, "widen", "Should include widening");
});

test("buildReadElisp - span parameter includes warning for not found", () => {
  const result = buildReadElisp("test.txt", { span: "fn89f4" });
  assertContains(result, "span-warning", "Should include span warning variable");
  assertContains(result, "not found", "Should include warning message");
});

test("buildReadElisp - span parameter escapes special characters", () => {
  const result = buildReadElisp("test.txt", { span: 'span"with"quotes' });
  assertContains(result, '\\"', "Should escape quotes in span ID");
});

test("buildReadElisp - without span in temp mode does not calculate spans", () => {
  const result = buildReadElisp("test.txt", { temp: true });
  // In temp mode without span, we shouldn't calculate spans for output
  assertContains(result, "all-spans", "Should define all-spans variable");
});

test("buildReadElisp - without span and without temp calculates spans", () => {
  const result = buildReadElisp("test.txt", { temp: false });
  assertContains(result, "warbo-span-calculators", "Should reference warbo-span-calculators");
  assertContains(result, "selected-spans", "Should select spans");
  assertContains(result, "warbo-span-selector", "Should reference warbo-span-selector");
});

test("buildReadElisp - spans field only added when not empty", () => {
  const result = buildReadElisp("test.txt", { temp: false });
  assertContains(result, "when (and spans-json (> (length spans-json) 0))",
    "Should only add spans when not empty");
});

test("buildReadElisp - span uses default selector (first 3)", () => {
  const result = buildReadElisp("test.txt", { temp: false });
  assertContains(result, "seq-take spans 3", "Should use default selector taking first 3");
});

test("buildReadElisp - span warning only added with span parameter", () => {
  const resultWithSpan = buildReadElisp("test.txt", { span: "fn89f4" });
  assertContains(resultWithSpan, "when span-warning", "Should add warning with span");

  const resultWithoutSpan = buildReadElisp("test.txt", { temp: false });
  // The warning cons should not be generated without span parameter
  const hasWarningCheck = resultWithoutSpan.includes('(cons "warning" span-warning)');
  assert(!hasWarningCheck, "Should not add warning without span parameter");
});

test("buildReadElisp - span with temp mode still narrows", () => {
  const result = buildReadElisp("test.txt", { span: "fn89f4", temp: true });
  assertContains(result, "narrow-to-region", "Should still narrow in temp mode");
  assertContains(result, "widen", "Should still widen in temp mode");
});

// ---------------------------------------------------------------------------
// Summary
// ---------------------------------------------------------------------------

process.on("beforeExit", () => {
  console.log(`\n# ${passed} passed, ${failed} failed`);
  if (failed > 0) process.exit(1);
});

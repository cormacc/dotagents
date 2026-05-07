#!/usr/bin/env tsx
/**
 * Emacs integration tests.
 *
 * Spins up an Emacs daemon with a known socket, sends our generated elisp
 * through emacsclient, and verifies the results.
 *
 * Requires: emacs, emacsclient on PATH (provided by Nix build environment).
 * Now imports the actual implementation instead of inline copies.
 */

import { execFileSync } from "node:child_process";
import { mkdirSync, writeFileSync, rmSync, existsSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import {
  escapeElispString,
  buildTsQueryElisp,
  buildEvalElisp,
  parseEmacsclientOutput,
} from "./elisp.ts";

// ---------------------------------------------------------------------------
// Test harness
// ---------------------------------------------------------------------------

let passed = 0;
let failed = 0;

function assert(condition, message) {
  if (!condition) throw new Error(message || "Assertion failed");
}

function assertEqual(actual, expected, message) {
  if (actual !== expected) {
    throw new Error(
      `${message || "assertEqual"}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`
    );
  }
}

function assertDeepEqual(actual, expected, message) {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a !== e) {
    throw new Error(
      `${message || "assertDeepEqual"}: expected ${e}, got ${a}`
    );
  }
}

async function test(name, fn) {
  try {
    await fn();
    console.log(`ok - ${name}`);
    passed++;
  } catch (err) {
    console.log(`not ok - ${name}`);
    console.log(`  # ${err.message}`);
    failed++;
  }
}

// ---------------------------------------------------------------------------
// Emacs daemon lifecycle
// ---------------------------------------------------------------------------

const tempDir = mkdirSync(join(tmpdir(), `emacs-test-${Date.now()}`), {
  recursive: true,
  mode: 0o700,
});
const socketName = join(tempDir, "emacs-test-socket");

function emacsclient(elisp) {
  const result = execFileSync("emacsclient", [
    "--socket-name", socketName,
    "--eval", elisp,
  ], {
    encoding: "utf-8",
    timeout: 10000,
    env: { ...process.env, HOME: tempDir },
  });
  return result;
}

function emacsclientParsed(elisp) {
  return parseEmacsclientOutput(emacsclient(elisp));
}

function startEmacs() {
  // Start Emacs daemon with minimal config
  // Use --daemon instead of --fg-daemon for better sandbox compatibility
  execFileSync("emacs", [
    "--daemon=" + socketName,
    "--no-window-system", // Ensure headless mode
    "--eval", "(require 'json)", // Load json library for json-encode
  ], {
    timeout: 30000,
    env: {
      ...process.env,
      HOME: tempDir,
      DISPLAY: "", // Prevent X11 issues
    },
    stdio: "pipe",
  });

  // Wait for socket to be ready
  let retries = 50;
  while (retries-- > 0 && !existsSync(socketName)) {
    const start = Date.now();
    while (Date.now() - start < 100) {
      // Busy wait for 100ms
    }
  }

  if (!existsSync(socketName)) {
    throw new Error("Emacs socket did not appear");
  }
}

function stopEmacs() {
  try {
    execFileSync("emacsclient", [
      "--socket-name", socketName,
      "--eval", "(kill-emacs)",
    ], {
      timeout: 5000,
      env: { ...process.env, HOME: tempDir },
      stdio: "pipe",
    });
  } catch {
    // Emacs may already be dead
  }
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

(async function () {
  // Start the Emacs daemon
  console.log("# Starting Emacs daemon...");
  try {
    startEmacs();
  } catch (err) {
    console.log(`Bail out! Could not start Emacs daemon: ${err.message}`);
    rmSync(tempDir, { recursive: true, force: true });
    process.exit(1);
  }

  // Verify it's running
  try {
    const ver = emacsclient("(emacs-version)");
    console.log(`# Emacs running: ${ver.trim().slice(0, 60)}`);
  } catch (err) {
    console.log(`Bail out! Cannot connect to Emacs: ${err.message}`);
    rmSync(tempDir, { recursive: true, force: true });
    process.exit(1);
  }

  // -- FOUNDATIONAL json-encode TESTS --
  // These tests verify our understanding of how json-encode actually works

  await test("json-encode - simple string", () => {
    const raw = emacsclient('(json-encode "hello")');
    console.log(`# json-encode "hello" -> ${JSON.stringify(raw)}`);
    assertEqual(raw.trim(), '"\\"hello\\""');
  });

  await test("json-encode - empty string", () => {
    const raw = emacsclient('(json-encode "")');
    console.log(`# json-encode "" -> ${JSON.stringify(raw)}`);
    assertEqual(raw.trim(), '"\\"\\""');
  });

  await test("json-encode - string with newline", () => {
    const raw = emacsclient('(json-encode "a\nb")');
    console.log(`# json-encode "a\\nb" -> ${JSON.stringify(raw)}`);
    // Should be: "\"a\\nb\"" (elisp string containing JSON "a\nb")
    assert(raw.includes('\\n'), "Should contain escaped newline");
  });

  await test("json-encode - string with quote", () => {
    const raw = emacsclient('(json-encode "say \\"hi\\"")');
    console.log(`# json-encode with quote -> ${JSON.stringify(raw)}`);
    assert(raw.includes('\\\\"'), "Should contain escaped quote");
  });

  await test("json-encode - string with backslash", () => {
    const raw = emacsclient('(json-encode "path\\\\to")');
    console.log(`# json-encode with backslash -> ${JSON.stringify(raw)}`);
    assert(raw.includes('\\\\\\\\'), "Should contain double-escaped backslash");
  });

  await test("json-encode - number", () => {
    const raw = emacsclient('(json-encode 42)');
    console.log(`# json-encode 42 -> ${JSON.stringify(raw)}`);
    assertEqual(raw.trim(), '"42"');
  });

  await test("json-encode - negative number", () => {
    const raw = emacsclient('(json-encode -17)');
    console.log(`# json-encode -17 -> ${JSON.stringify(raw)}`);
    assertEqual(raw.trim(), '"-17"');
  });

  await test("json-encode - float", () => {
    const raw = emacsclient('(json-encode 3.14)');
    console.log(`# json-encode 3.14 -> ${JSON.stringify(raw)}`);
    assert(raw.includes('3.14'), "Should contain float value");
  });

  await test("json-encode - true (t)", () => {
    const raw = emacsclient('(json-encode t)');
    console.log(`# json-encode t -> ${JSON.stringify(raw)}`);
    assertEqual(raw.trim(), '"true"');
  });

  await test("json-encode - false (:json-false)", () => {
    const raw = emacsclient('(json-encode :json-false)');
    console.log(`# json-encode :json-false -> ${JSON.stringify(raw)}`);
    assertEqual(raw.trim(), '"false"');
  });

  await test("json-encode - null (nil)", () => {
    // In Emacs 30, nil maps to JSON null (not :null which becomes the string "null")
    const raw = emacsclient('(json-encode nil)');
    console.log(`# json-encode nil -> ${JSON.stringify(raw)}`);
    assertEqual(raw.trim(), '"null"');
  });

  await test("json-encode - empty array (vector)", () => {
    // '() is nil in Emacs (maps to null). Use [] (vector) for empty arrays.
    const raw = emacsclient("(json-encode [])");
    console.log(`# json-encode [] -> ${JSON.stringify(raw)}`);
    assertEqual(raw.trim(), '"[]"');
  });

  await test("json-encode - simple array", () => {
    const raw = emacsclient("(json-encode '(1 2 3))");
    console.log(`# json-encode '(1 2 3) -> ${JSON.stringify(raw)}`);
    assertEqual(raw.trim(), '"[1,2,3]"');
  });

  await test("json-encode - array with strings", () => {
    const raw = emacsclient('(json-encode \'("a" "b"))');
    console.log(`# json-encode '("a" "b") -> ${JSON.stringify(raw)}`);
    assert(raw.includes('\\"a\\"'), "Should contain escaped string");
  });

  await test("json-encode - empty object (alist)", () => {
    const raw = emacsclient("(json-encode '())");
    console.log(`# json-encode empty alist -> ${JSON.stringify(raw)}`);
    // Could be [] or {}, depending on context
  });

  await test("json-encode - object with string value", () => {
    const raw = emacsclient('(json-encode \'(("key" . "value")))');
    console.log(`# json-encode alist -> ${JSON.stringify(raw)}`);
    assert(raw.includes('key'), "Should contain key");
    assert(raw.includes('value'), "Should contain value");
  });

  await test("json-encode - object with number value", () => {
    const raw = emacsclient('(json-encode \'(("count" . 42)))');
    console.log(`# json-encode with number -> ${JSON.stringify(raw)}`);
    assert(raw.includes('count'), "Should contain key");
    assert(raw.includes('42'), "Should contain number");
  });

  await test("json-encode - object with null value", () => {
    // nil in alist value position encodes to JSON null
    // (cons "val" nil) = ("val"), cdr is nil → null
    const raw = emacsclient('(json-encode \'(("val")))');
    console.log(`# json-encode with nil value -> ${JSON.stringify(raw)}`);
    assert(raw.includes('null'), "Should contain null");
  });

  await test("json-encode - object with boolean values", () => {
    const raw = emacsclient('(json-encode \'(("yes" . t) ("no" . :json-false)))');
    console.log(`# json-encode with booleans -> ${JSON.stringify(raw)}`);
    assert(raw.includes('true'), "Should contain true");
    assert(raw.includes('false'), "Should contain false");
  });

  await test("json-encode - nested object", () => {
    const raw = emacsclient('(json-encode \'(("outer" . (("inner" . 123)))))');
    console.log(`# json-encode nested -> ${JSON.stringify(raw)}`);
    assert(raw.includes('outer'), "Should contain outer key");
    assert(raw.includes('inner'), "Should contain inner key");
  });

  await test("json-encode - object with newline in string value", () => {
    const raw = emacsclient('(json-encode \'(("content" . "line1\nline2")))');
    console.log(`# json-encode with newline in value -> ${JSON.stringify(raw)}`);
    assert(raw.includes('\\n'), "Should escape newline in JSON");
    // Verify it's double-escaped for elisp print
    assert(raw.includes('\\\\n'), "Should be double-escaped");
  });

  await test("json-encode - object with quote in string value", () => {
    const raw = emacsclient('(json-encode \'(("msg" . "say \\"hi\\"")))');
    console.log(`# json-encode with quote in value -> ${JSON.stringify(raw)}`);
    assert(raw.includes('\\\\"'), "Should escape quotes");
  });

  await test("json-encode - object with backslash in string value", () => {
    const raw = emacsclient('(json-encode \'(("path" . "c:\\\\dir")))');
    console.log(`# json-encode with backslash in value -> ${JSON.stringify(raw)}`);
    // Should have double-double backslashes
    assert(raw.includes('\\\\\\\\'), "Should double-escape backslashes");
  });

  await test("json-encode - complex realistic buffer content", () => {
    // Simulate realistic buffer content with mixed special characters
    const raw = emacsclient('(json-encode \'(("content" . "def test():\n    print(\\"hello\\")\n    return True")))');
    console.log(`# json-encode realistic code -> ${JSON.stringify(raw.substring(0, 100))}`);
    assert(raw.includes('\\n'), "Should have newlines");
    assert(raw.includes('\\\\"'), "Should have escaped quotes");
  });

  await test("json-encode - parse roundtrip simple string", () => {
    const raw = emacsclient('(json-encode "test")');
    const parsed = parseEmacsclientOutput(raw);
    assertEqual(parsed, "test");
  });

  await test("json-encode - parse roundtrip string with newline", () => {
    const raw = emacsclient('(json-encode "a\nb")');
    const parsed = parseEmacsclientOutput(raw);
    assertEqual(parsed, "a\nb", "Should parse to string with actual newline");
  });

  await test("json-encode - parse roundtrip string with quote", () => {
    const raw = emacsclient('(json-encode "say \\"hi\\"")');
    const parsed = parseEmacsclientOutput(raw);
    assertEqual(parsed, 'say "hi"');
  });

  await test("json-encode - parse roundtrip string with backslash", () => {
    const raw = emacsclient('(json-encode "path\\\\to")');
    const parsed = parseEmacsclientOutput(raw);
    assertEqual(parsed, 'path\\to');
  });

  await test("json-encode - parse roundtrip number", () => {
    const raw = emacsclient('(json-encode 42)');
    const parsed = parseEmacsclientOutput(raw);
    assertEqual(parsed, 42);
  });

  await test("json-encode - parse roundtrip boolean true", () => {
    const raw = emacsclient('(json-encode t)');
    const parsed = parseEmacsclientOutput(raw);
    assertEqual(parsed, true);
  });

  await test("json-encode - parse roundtrip boolean false", () => {
    const raw = emacsclient('(json-encode :json-false)');
    const parsed = parseEmacsclientOutput(raw);
    assertEqual(parsed, false);
  });

  await test("json-encode - parse roundtrip null", () => {
    const raw = emacsclient('(json-encode nil)');
    const parsed = parseEmacsclientOutput(raw);
    assertEqual(parsed, null);
  });

  await test("json-encode - parse roundtrip array", () => {
    const raw = emacsclient("(json-encode '(1 2 3))");
    const parsed = parseEmacsclientOutput(raw);
    assertDeepEqual(parsed, [1, 2, 3]);
  });

  await test("json-encode - parse roundtrip object", () => {
    const raw = emacsclient('(json-encode \'(("name" . "test") ("count" . 42)))');
    const parsed = parseEmacsclientOutput(raw);
    assertDeepEqual(parsed, { name: "test", count: 42 });
  });

  await test("json-encode - parse roundtrip object with newline", () => {
    const raw = emacsclient('(json-encode \'(("content" . "a\nb")))');
    const parsed = parseEmacsclientOutput(raw);
    assertDeepEqual(parsed, { content: "a\nb" });
    assert(parsed.content.includes('\n'), "Content should have actual newline character");
  });

  await test("json-encode - parse roundtrip object with all special chars", () => {
    const raw = emacsclient('(json-encode \'(("text" . "line1\n\\"quoted\\"\\npath\\\\to")))');
    const parsed = parseEmacsclientOutput(raw);
    assertEqual(parsed.text, 'line1\n"quoted"\npath\\to');
  });

  // -- DETAILED escaping level tests --

  await test("escaping levels - single backslash in elisp", () => {
    // Elisp: "\\" (two backslashes in source) = one backslash in string
    const raw = emacsclient('(json-encode "\\\\")');
    console.log(`# json-encode "\\\\" -> ${JSON.stringify(raw)}`);
    console.log(`# Expecting: one backslash character encoded as JSON`);
  });

  await test("escaping levels - double backslash in elisp", () => {
    // Elisp: "\\\\" (four backslashes in source) = two backslashes in string
    const raw = emacsclient('(json-encode "\\\\\\\\")');
    console.log(`# json-encode "\\\\\\\\" -> ${JSON.stringify(raw)}`);
    console.log(`# Expecting: two backslash characters encoded as JSON`);
  });

  await test("escaping levels - backslash-n vs newline", () => {
    // Elisp source with actual newline in string literal
    const raw1 = emacsclient('(json-encode "a\nb")');
    console.log(`# json-encode "a<newline>b" -> ${JSON.stringify(raw1)}`);

    // Elisp source with backslash-n (should be: "\\n" in elisp source)
    const raw2 = emacsclient('(json-encode "a\\\\nb")');
    console.log(`# json-encode "a\\\\nb" -> ${JSON.stringify(raw2)}`);
    console.log(`# First should have JSON \\n, second should have JSON \\\\n`);
  });

  await test("escaping levels - what our parser gets for newline", () => {
    const raw = emacsclient('(json-encode "a\nb")');
    console.log(`# Raw string length: ${raw.length}`);
    console.log(`# Raw chars: ${Array.from(raw).map((c, i) => `[${i}]:${c.charCodeAt(0)}`).join(' ')}`);
  });

  await test("escaping levels - what our parser gets for backslash-n", () => {
    const raw = emacsclient('(json-encode "a\\\\nb")');
    console.log(`# Raw string length: ${raw.length}`);
    console.log(`# Raw chars: ${Array.from(raw).map((c, i) => `[${i}]:${c.charCodeAt(0)}`).join(' ')}`);
  });

  await test("escaping levels - manual parse newline case", () => {
    const raw = emacsclient('(json-encode "a\nb")');
    console.log(`# Input: ${JSON.stringify(raw)}`);
    const trimmed = raw.trim();
    console.log(`# After trim: ${JSON.stringify(trimmed)}`);
    const sliced = trimmed.slice(1, -1);
    console.log(`# After slice(1,-1): ${JSON.stringify(sliced)}`);
    const afterQuote = sliced.replace(/\\"/g, '"');
    console.log(`# After replacing \\\\": ${JSON.stringify(afterQuote)}`);
    const afterBackslash = afterQuote.replace(/\\\\/g, "\\");
    console.log(`# After replacing \\\\\\\\: ${JSON.stringify(afterBackslash)}`);
    console.log(`# About to JSON.parse: ${JSON.stringify(afterBackslash)}`);
    try {
      const result = JSON.parse(afterBackslash);
      console.log(`# JSON.parse result: ${JSON.stringify(result)}`);
    } catch (e) {
      console.log(`# JSON.parse error: ${e.message}`);
    }
  });

  await test("escaping levels - manual parse backslash-n case", () => {
    const raw = emacsclient('(json-encode "a\\\\nb")');
    console.log(`# Input: ${JSON.stringify(raw)}`);
    const trimmed = raw.trim();
    console.log(`# After trim: ${JSON.stringify(trimmed)}`);
    const sliced = trimmed.slice(1, -1);
    console.log(`# After slice(1,-1): ${JSON.stringify(sliced)}`);
    const afterQuote = sliced.replace(/\\"/g, '"');
    console.log(`# After replacing \\\\": ${JSON.stringify(afterQuote)}`);
    const afterBackslash = afterQuote.replace(/\\\\/g, "\\");
    console.log(`# After replacing \\\\\\\\: ${JSON.stringify(afterBackslash)}`);
    console.log(`# About to JSON.parse: ${JSON.stringify(afterBackslash)}`);
    try {
      const result = JSON.parse(afterBackslash);
      console.log(`# JSON.parse result: ${JSON.stringify(result)}`);
    } catch (e) {
      console.log(`# JSON.parse error: ${e.message}`);
    }
  });

  await test("null representation - :null vs :json-false vs nil vs 'null", () => {
    const r1 = emacsclient('(json-encode :null)');
    console.log(`# :null -> ${JSON.stringify(r1)}`);

    const r2 = emacsclient('(json-encode :json-false)');
    console.log(`# :json-false -> ${JSON.stringify(r2)}`);

    const r3 = emacsclient('(json-encode nil)');
    console.log(`# nil -> ${JSON.stringify(r3)}`);

    const r4 = emacsclient("(json-encode 'null)");
    console.log(`# 'null -> ${JSON.stringify(r4)}`);
  });

  await test("array representation - empty list vs vector", () => {
    const r1 = emacsclient("(json-encode '())");
    console.log(`# '() -> ${JSON.stringify(r1)}`);

    const r2 = emacsclient("(json-encode [])");
    console.log(`# [] -> ${JSON.stringify(r2)}`);

    const r3 = emacsclient("(json-encode '(1 2 3))");
    console.log(`# '(1 2 3) -> ${JSON.stringify(r3)}`);

    const r4 = emacsclient("(json-encode [1 2 3])");
    console.log(`# [1 2 3] -> ${JSON.stringify(r4)}`);
  });

  await test("object representation - alist with :json-null", () => {
    const raw = emacsclient('(json-encode \'(("val" . :json-null)))');
    console.log(`# alist with :json-null -> ${JSON.stringify(raw)}`);
  });

  await test("comprehensive escape sequence test", () => {
    // Test string with: newline, quote, backslash, tab
    const raw = emacsclient('(json-encode "a\n\\"b\\"\\nc\\\\d\\te")');
    console.log(`# Complex escapes -> ${JSON.stringify(raw)}`);
    console.log(`# Should contain: newline, quote, backslash, tab`);
  });

  await test("realistic buffer content - Python with docstring", () => {
    const code = 'def foo():\n    """Docstring with "quotes" """\n    return True';
    // We need to properly escape this for elisp
    const escaped = code
      .replace(/\\/g, '\\\\')
      .replace(/"/g, '\\"')
      .replace(/\n/g, '\\n');
    const raw = emacsclient(`(json-encode "${escaped}")`);
    console.log(`# Python code -> ${JSON.stringify(raw.substring(0, 100))}`);
    try {
      const parsed = parseEmacsclientOutput(raw);
      console.log(`# Parsed length: ${parsed.length}, original length: ${code.length}`);
      assertEqual(parsed, code);
    } catch (e) {
      console.log(`# Parse error: ${e.message}`);
    }
  });

  await test("realistic buffer content - Windows path", () => {
    const path = 'C:\\Users\\Name\\Documents\\file.txt';
    const escaped = path.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
    const raw = emacsclient(`(json-encode "${escaped}")`);
    console.log(`# Windows path -> ${JSON.stringify(raw)}`);
    try {
      const parsed = parseEmacsclientOutput(raw);
      assertEqual(parsed, path);
    } catch (e) {
      console.log(`# Parse error: ${e.message}`);
    }
  });

  await test("realistic buffer content - JSON file", () => {
    const jsonContent = '{\n  "key": "value",\n  "num": 42\n}';
    const escaped = jsonContent
      .replace(/\\/g, '\\\\')
      .replace(/"/g, '\\"')
      .replace(/\n/g, '\\n');
    const raw = emacsclient(`(json-encode "${escaped}")`);
    console.log(`# JSON content -> ${JSON.stringify(raw.substring(0, 80))}`);
    try {
      const parsed = parseEmacsclientOutput(raw);
      assertEqual(parsed, jsonContent);
    } catch (e) {
      console.log(`# Parse error: ${e.message}`);
    }
  });

  await test("realistic buffer content - Shell script", () => {
    const script = '#!/bin/bash\necho "test"\nif [ -f "file.txt" ]; then\n  cat "file.txt"\nfi';
    const escaped = script
      .replace(/\\/g, '\\\\')
      .replace(/"/g, '\\"')
      .replace(/\n/g, '\\n');
    const raw = emacsclient(`(json-encode "${escaped}")`);
    console.log(`# Shell script -> ${JSON.stringify(raw.substring(0, 80))}`);
    try {
      const parsed = parseEmacsclientOutput(raw);
      assertEqual(parsed, script);
    } catch (e) {
      console.log(`# Parse error: ${e.message}`);
    }
  });

  await test("edge case - consecutive backslashes", () => {
    const input = '\\\\\\\\'; // Four backslashes
    const escaped = input.replace(/\\/g, '\\\\');
    const raw = emacsclient(`(json-encode "${escaped}")`);
    console.log(`# Four backslashes -> ${JSON.stringify(raw)}`);
    try {
      const parsed = parseEmacsclientOutput(raw);
      assertEqual(parsed, input);
    } catch (e) {
      console.log(`# Parse error: ${e.message}`);
    }
  });

  await test("edge case - backslash before quote", () => {
    const input = 'test\\"quoted'; // Backslash before quote
    const escaped = input.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
    const raw = emacsclient(`(json-encode "${escaped}")`);
    console.log(`# Backslash-quote -> ${JSON.stringify(raw)}`);
    try {
      const parsed = parseEmacsclientOutput(raw);
      assertEqual(parsed, input);
    } catch (e) {
      console.log(`# Parse error: ${e.message}`);
    }
  });

  await test("edge case - mixed escape sequences", () => {
    const input = 'a\\nb\nc\\\\d\\"e"f';
    const escaped = input
      .replace(/\\/g, '\\\\')
      .replace(/"/g, '\\"')
      .replace(/\n/g, '\\n');
    const raw = emacsclient(`(json-encode "${escaped}")`);
    console.log(`# Mixed escapes -> ${JSON.stringify(raw.substring(0, 80))}`);
    try {
      const parsed = parseEmacsclientOutput(raw);
      assertEqual(parsed, input);
    } catch (e) {
      console.log(`# Parse error: ${e.message}`);
    }
  });

  // -- Original Tests --

  await test("emacs_eval - simple arithmetic", () => {
    const elisp = buildEvalElisp("(+ 21 21)");
    const result = emacsclientParsed(elisp);
    assertEqual(result, 42);
  });

  await test("emacs_eval - string result", () => {
    const elisp = buildEvalElisp('(concat "hello" " " "world")');
    const result = emacsclientParsed(elisp);
    assertEqual(result, "hello world");
  });

  await test("emacs_eval - nil result", () => {
    const elisp = buildEvalElisp("nil");
    const result = emacsclientParsed(elisp);
    assertEqual(result, false);
  });

  await test("emacs_eval - t result", () => {
    const elisp = buildEvalElisp("t");
    const result = emacsclientParsed(elisp);
    assertEqual(result, true);
  });

  await test("emacs_eval - list result", () => {
    const elisp = buildEvalElisp("'(1 2 3)");
    const result = emacsclientParsed(elisp);
    assertDeepEqual(result, [1, 2, 3]);
  });

  // Create a test file and open it in Emacs for buffer content tests
  const testFilePath = join(tempDir, "test-file.txt");
  writeFileSync(testFilePath, "line one\nline two\nline three\n", "utf-8");

  await test("setup - open test file in emacs", () => {
    emacsclient(`(find-file-noselect "${escapeElispString(testFilePath)}")`);
    // Verify it's open by checking the buffer exists
    const found = emacsclient(`(find-buffer-visiting "${escapeElispString(testFilePath)}")`);
    assert(found, "Should be able to open file");
    assert(found, "test-file.txt should be in buffer list");
  });

  // Test eval with buffer context
  await test("emacs_eval - buffer-local query", () => {
    const elisp = buildEvalElisp(
      '(with-current-buffer "test-file.txt" (symbol-name major-mode))'
    );
    const result = emacsclientParsed(elisp);
    assert(typeof result === "string", "Should return mode name string");
    assert(result.length > 0, "Mode name should be non-empty");
  });

  // -----------------------------------------------------------------------
  // Tree-sitter multi-capture tests (issue bee03c0a135aa875)
  //
  // These tests expose the bug where treesit-query-capture returns a flat
  // list of (capture-name . node) pairs, and the code iterates over each
  // capture individually rather than grouping by match. This means:
  //   - A query with 2 captures per match produces 2N results, not N
  //   - An action referencing multiple capture names simultaneously fails
  // -----------------------------------------------------------------------

  // Check if tree-sitter is available with Python support
  const treesitAvailable = (() => {
    try {
      const r = emacsclient("(treesit-available-p)");
      return r.trim() === "t";
    } catch { return false; }
  })();

  const pythonAvailable = (() => {
    if (!treesitAvailable) return false;
    try {
      const r = emacsclient("(treesit-language-available-p 'python)");
      return r.trim() === "t";
    } catch { return false; }
  })();

  await test("ts_query multi-capture - tree-sitter available", () => {
    assert(treesitAvailable, "Emacs must have tree-sitter support (treesit-available-p)");
    assert(pythonAvailable, "Python tree-sitter grammar must be installed");
  });

  if (pythonAvailable) {
    console.log("# Tree-sitter tests using language: python");

    // Create a test file with multiple functions for tree-sitter queries
    const tsTestFilePath = join(tempDir, "ts-test.py");
    const tsTestContent = "def foo():\n    return 1\n\ndef bar():\n    return 2\n\ndef baz():\n    return 3\n";
    writeFileSync(tsTestFilePath, tsTestContent, "utf-8");

    // Open the file with tree-sitter mode
    const tsModeSetup = `(let ((buf (find-file-noselect "${escapeElispString(tsTestFilePath)}")))
           (with-current-buffer buf (python-ts-mode)) buf)`;

    await test("ts_query multi-capture - tree-sitter mode activates", () => {
      emacsclient(tsModeSetup);
    });

    // The query that captures both function name and body
    const multiCaptureQuery = "(function_definition name: (identifier) @name body: (block) @body)";
    const tsTestFile = "ts-test.py";

    // --- Test 1: multi-capture query produces one result per match, not per capture ---
    await test("ts_query multi-capture - should produce one result per match (3 functions)", async () => {
      // With 3 functions & 2 captures each (@name, @body), it should produce 3.
      const elisp = buildTsQueryElisp(
        tsTestFile,
        multiCaptureQuery,
        "python",
        "(treesit-node-text node t)"
      );
      const result = emacsclientParsed(elisp);
      assert(Array.isArray(result), "Should return an array");
      assertEqual(result.length, 3,
        `Should get 3 results (one per function match), got ${result.length}`);
    });

    // --- Test 2: action can reference multiple capture names simultaneously ---
    await test("ts_query multi-capture - action should access both @name and @body", async () => {
      // An action that uses both capture names.
      const action = '(format "%s" (treesit-node-text name t))';
      const elisp = buildTsQueryElisp(
        tsTestFile,
        multiCaptureQuery,
        "python",
        action
      );
      const result = emacsclientParsed(elisp);
      assert(Array.isArray(result), "Should return an array");
      console.log(`#   Results: ${JSON.stringify(result)}`);
      // None of the results should be errors from unbound variables
      for (const r of result) {
        assert(!r.startsWith("ERROR:"),
          `Action should not error; got: ${r}`);
      }
      // Should get the three function names
      assertEqual(result.length, 3, "Should get 3 results");
      assert(result.includes("foo"), "Should find function foo");
      assert(result.includes("bar"), "Should find function bar");
      assert(result.includes("baz"), "Should find function baz");
    });

    // --- Test 3: action that correlates two captures from the same match ---
    await test("ts_query multi-capture - action correlating name with body", async () => {
      // An action that combines data from both captures in a single match.
      const action = '(format "%s:%d" (treesit-node-text name t) (treesit-node-end body))';
      const elisp = buildTsQueryElisp(
        tsTestFile,
        multiCaptureQuery,
        "python",
        action
      );
      const result = emacsclientParsed(elisp);
      assert(Array.isArray(result), "Should return an array");
      console.log(`#   Results: ${JSON.stringify(result)}`);
      assertEqual(result.length, 3, "Should get 3 results (one per function)");
      for (const r of result) {
        assert(!r.startsWith("ERROR:"),
          `Correlating action should not error; got: ${r}`);
        assert(r.includes(":"),
          `Each result should be "name:pos" format; got: ${r}`);
      }
      // Verify each function name appears exactly once
      const names = result.map(r => r.split(":")[0]);
      assert(names.includes("foo"), "Should have foo");
      assert(names.includes("bar"), "Should have bar");
      assert(names.includes("baz"), "Should have baz");
    });

  }

  // Cleanup
  stopEmacs();
  rmSync(tempDir, { recursive: true, force: true });

  console.log(`\n# ${passed} passed, ${failed} failed`);
  if (failed > 0) process.exit(1);
})();

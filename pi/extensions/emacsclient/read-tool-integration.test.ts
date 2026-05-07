#!/usr/bin/env tsx
/**
 * Integration tests for custom 'read' tool.
 *
 * Spins up an Emacs daemon and tests the read functionality with real buffers.
 */

import { execFileSync } from "node:child_process";
import { mkdtempSync, writeFileSync, rmSync, existsSync, mkdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import {
  buildReadElisp,
  parseEmacsclientOutput,
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

function assertContains(haystack: string, needle: string, message?: string) {
  if (!haystack.includes(needle)) {
    throw new Error(
      `${message || "assertContains"}: expected to find "${needle}"`
    );
  }
}

function test(name: string, fn: () => void) {
  try {
    fn();
    console.log(`ok - ${name}`);
    passed++;
  } catch (err) {
    console.log(`not ok - ${name}`);
    console.log(`  # ${err instanceof Error ? err.message : String(err)}`);
    failed++;
  }
}

// ---------------------------------------------------------------------------
// Emacs daemon lifecycle
// ---------------------------------------------------------------------------

const tempDir = mkdtempSync(join(tmpdir(), "emacs-read-test-"));
const socketName = join(tempDir, "socket");
const testFilesDir = join(tempDir, "files");
mkdirSync(testFilesDir, { recursive: true });

function emacsclient(elisp: string): string {
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

function emacsclientParsed(elisp: string): any {
  return parseEmacsclientOutput(emacsclient(elisp));
}

function cleanupBuffers() {
  // Kill all non-default buffers and deactivate regions
  try {
    emacsclient(`(progn
      ;; Deactivate mark
      (deactivate-mark t)
      ;; Kill file-visiting buffers
      (mapc (lambda (buf)
              (when (and (buffer-live-p buf)
                        (buffer-file-name buf))
                (with-current-buffer buf
                  (set-buffer-modified-p nil))
                (kill-buffer buf)))
            (buffer-list))
      nil)`);
  } catch (err) {
    // Ignore errors in cleanup
  }
}

function startEmacs() {
  execFileSync("emacs", [
    "--daemon=" + socketName,
    "--no-window-system",
    "--eval", "(require 'json)",
  ], {
    encoding: "utf-8",
    timeout: 30000,
    env: { ...process.env, HOME: tempDir },
  });
}

function stopEmacs() {
  try {
    execFileSync("emacsclient", [
      "--socket-name", socketName,
      "--eval", "(kill-emacs)",
    ], {
      encoding: "utf-8",
      timeout: 5000,
      env: { ...process.env, HOME: tempDir },
    });
  } catch (err) {
    // Expected - Emacs exits
  }
}

function cleanup() {
  stopEmacs();
  rmSync(tempDir, { recursive: true, force: true });
}

// ---------------------------------------------------------------------------
// Test setup
// ---------------------------------------------------------------------------

process.on("exit", cleanup);
process.on("SIGINT", () => {
  cleanup();
  process.exit(130);
});

console.log("# Starting Emacs daemon...");
startEmacs();
console.log("# Emacs daemon started");

// Create test files
const testFile = join(testFilesDir, "test.txt");
const testFileContent = "Line 1\nLine 2\nLine 3\nLine 4\nLine 5\n";
writeFileSync(testFile, testFileContent);

const pythonFile = join(testFilesDir, "test.py");
const pythonContent = 'def hello():\n    print("Hello, world!")\n    return True\n';
writeFileSync(pythonFile, pythonContent);

const largeFile = join(testFilesDir, "large.txt");
const largeContent = "x".repeat(100000);
writeFileSync(largeFile, largeContent);

// ---------------------------------------------------------------------------
// Read tool - file path tests
// ---------------------------------------------------------------------------

test("read - opens file with absolute path", () => {
  const elisp = buildReadElisp(testFile, { pos: 1, length: 100 });
  const result = emacsclientParsed(elisp);

  assertEqual(result.path, testFile);
  assertEqual(result.exists, true);
  assertContains(result.got.content, "Line 1");
});

test("read - opens file with relative path", () => {
  const relPath = `./files/test.txt`;
  process.chdir(tempDir);

  const elisp = buildReadElisp(relPath, { pos: 1, length: 100 });
  const result = emacsclientParsed(elisp);

  assertEqual(result.exists, true);
  assertContains(result.got.content, "Line 1");
});

test("read - detects non-existent file", () => {
  const nonExistent = join(testFilesDir, "nonexistent.txt");
  const elisp = buildReadElisp(nonExistent, { pos: 1, length: 100 });
  const result = emacsclientParsed(elisp);

  assertEqual(result.exists, false);
  assertEqual(result.new, true);
  assertEqual(result.got.content, "");
});

test("read - opens python file and detects mode", () => {
  const elisp = buildReadElisp(pythonFile, { pos: 1, length: 100 });
  const result = emacsclientParsed(elisp);

  assertContains(result.mode.toLowerCase(), "python");
  assertContains(result.got.content, "def hello");
});

// ---------------------------------------------------------------------------
// Read tool - buffer name tests
// ---------------------------------------------------------------------------

test("read - accesses scratch buffer by name", () => {
  const elisp = buildReadElisp("*scratch*", { length: 100 });
  const result = emacsclientParsed(elisp);

  assertEqual(result.name, "*scratch*");
  assertEqual(result.path, null);
  assertEqual(result.exists, null);
});

test("read - creates buffer for name without slash", () => {
  const elisp = buildReadElisp("newbuffer", { pos: 1, length: 100 });
  const result = emacsclientParsed(elisp);

  assertEqual(result.new, true);
});

test("read - existing buffer is not new", () => {
  // Open a buffer first
  emacsclient(`(find-file "${testFile}")`);

  const elisp = buildReadElisp(testFile, { length: 100 });
  const result = emacsclientParsed(elisp);

  assertEqual(result.new, false);
});

// ---------------------------------------------------------------------------
// Read tool - position navigation tests
// ---------------------------------------------------------------------------

test("read - navigates to specific position", () => {
  const elisp = buildReadElisp(testFile, { pos: 10, length: 10, move: true });
  const result = emacsclientParsed(elisp);

  // got.start is where we began reading
  assertEqual(result.got.start.pos, 10);
  // move: true → point is left at the end of what was read
  assertEqual(result.point.pos, result.got.end.pos);
});

test("read - navigates to specific line", () => {
  const elisp = buildReadElisp(testFile, { line: 3, length: 10, move: true });
  const result = emacsclientParsed(elisp);

  // got.start records where reading began
  assertEqual(result.got.start.line, 3);
  // move: true → point is left at the end of what was read
  assertEqual(result.point.pos, result.got.end.pos);
});

test("read - navigates to line and column", () => {
  const elisp = buildReadElisp(testFile, { line: 2, col: 5, length: 10, move: true });
  const result = emacsclientParsed(elisp);

  // got.start records the reading origin (line 2, col 5)
  assertEqual(result.got.start.line, 2);
  assertEqual(result.got.start.col, 5);
  // move: true → point is left at the end of what was read
  assertEqual(result.point.pos, result.got.end.pos);
});

test("read - pos overrides line/col", () => {
  const elisp = buildReadElisp(testFile, { pos: 1, line: 5, col: 10, length: 10, move: true });
  const result = emacsclientParsed(elisp);

  // got.start records where reading actually began (pos 1, not line 5)
  assertEqual(result.got.start.pos, 1);
  assertEqual(result.got.start.line, 1);
  // move: true → point is left at the end of what was read
  assertEqual(result.point.pos, result.got.end.pos);
});

test("read - negative pos moves backward from current point", () => {
  // First open and position at line 3
  emacsclient(`(progn (find-file "${testFile}") (goto-line 3))`);

  // Now read with negative pos (5 chars back from line 3)
  const elisp = buildReadElisp(testFile, { pos: -5, length: 10, move: true });
  const result = emacsclientParsed(elisp);

  // Reading started before line 3 (negative offset)
  assert(result.got.start.line < 3, "Should start reading before line 3");
  // move: true → point is at the end of what was read
  assertEqual(result.point.pos, result.got.end.pos);
});

test("read - move: true with no position reads from and advances past current point", () => {
  // Position at line 4
  emacsclient(`(progn (find-file "${testFile}") (goto-line 4))`);

  // Read without specifying position, but with move: true
  const elisp = buildReadElisp(testFile, { length: 10, move: true });
  const result = emacsclientParsed(elisp);

  // Reading started from line 4 (where point was)
  assertEqual(result.got.start.line, 4);
  // move: true → point is now at the end of what was read
  assertEqual(result.point.pos, result.got.end.pos);
});

test("read - move: false (default) restores point after read", () => {
  // Position at line 4
  emacsclient(`(progn (find-file "${testFile}") (goto-line 4))`);
  const startLine = 4;

  // Read without specifying move (defaults to false)
  const elisp = buildReadElisp(testFile, { length: 10 });
  const result = emacsclientParsed(elisp);

  // Reading started from line 4 (where point was)
  assertEqual(result.got.start.line, startLine);
  // move: false (default) → point is restored to original position
  assertEqual(result.point.line, startLine,
    "point should be restored to original line when move is false");
});

test("read - pos 0 uses current point (same as omitting pos)", () => {
  // pos: 0 is documented as "Default is 0, which uses point."
  // It must NOT move point to position 0/1 (beginning of buffer).
  // Position at line 5
  emacsclient(`(progn (find-file "${testFile}") (goto-line 5))`);

  // Read with explicit pos: 0
  const elisp = buildReadElisp(testFile, { pos: 0, length: 10 });
  const result = emacsclientParsed(elisp);

  // Reading started from line 5 (the current point), not the beginning
  assertEqual(result.got.start.line, 5,
    "pos:0 should start reading from current point (line 5)");

  // move defaults to false → point is restored to original position (line 5)
  assertEqual(result.point.line, 5,
    "pos:0 with default move:false should restore point to original line");
});

// ---------------------------------------------------------------------------
// Read tool - content extraction tests
// ---------------------------------------------------------------------------

test("read - extracts specific length", () => {
  const elisp = buildReadElisp(testFile, { pos: 1, length: 10 });
  const result = emacsclientParsed(elisp);

  assertEqual(result.got.length, 10);
  assertEqual(result.got.content, "Line 1\nLin");
});

test("read - extracts specific number of lines", () => {
  const elisp = buildReadElisp(testFile, { pos: 1, lines: 2 });
  const result = emacsclientParsed(elisp);

  assertEqual(result.got.lines, 2);
  assertContains(result.got.content, "Line 1");
  assertContains(result.got.content, "Line 2");
});

test("read - respects maxLength", () => {
  const elisp = buildReadElisp(largeFile, { pos: 1 }, 1000);
  const result = emacsclientParsed(elisp);

  assert(result.got.length <= 1000, "Should not exceed maxLength");
  assertEqual(result.got.truncated, true);
});

test("read - truncated is true when more content available", () => {
  const elisp = buildReadElisp(largeFile, { pos: 1, length: 1000 });
  const result = emacsclientParsed(elisp);

  assertEqual(result.got.truncated, true);
});

test("read - truncated is false when reaching end of buffer", () => {
  const smallContent = "abc";
  const smallFile = join(testFilesDir, "small.txt");
  writeFileSync(smallFile, smallContent);

  const elisp = buildReadElisp(smallFile, { pos: 1, length: 1000 });
  const result = emacsclientParsed(elisp);

  assertEqual(result.got.content, smallContent);
  assertEqual(result.got.truncated, false);
});

test("read - got.end points to correct position", () => {
  const elisp = buildReadElisp(testFile, { pos: 1, length: 10 });
  const result = emacsclientParsed(elisp);

  assertEqual(result.got.end.pos, result.got.start.pos + result.got.length);
});

test("read - two consecutive reads with move:true concatenate to full content", () => {
  // Read the first half, then the second half using point advancement.
  // Both reads use move: true so that the second read starts where the first ended.
  // The two halves must join seamlessly (no gaps, no overlaps).
  const content = testFileContent; // "Line 1\nLine 2\nLine 3\nLine 4\nLine 5\n"
  const half = Math.floor(content.length / 2);

  // First read: explicit start position, limited length, move: true to advance point
  const elisp1 = buildReadElisp(testFile, { pos: 1, length: half, move: true });
  const result1 = emacsclientParsed(elisp1);

  // move: true → point should now sit right at the end of what was read
  assertEqual(result1.point.pos, result1.got.end.pos,
    "point should be at end of first read");

  // Second read: no position – starts from current point (end of first read)
  const elisp2 = buildReadElisp(testFile, { move: true });
  const result2 = emacsclientParsed(elisp2);

  // The two pieces must stitch together perfectly
  assertEqual(result1.got.content + result2.got.content, content,
    "consecutive reads should reconstruct the full file content with no gaps or repeats");
});

// ---------------------------------------------------------------------------
// Read tool - temp mode tests
// ---------------------------------------------------------------------------

test("read - move: false (default) restores point even when temp: true", () => {
  // Position at line 2
  emacsclient(`(progn (find-file "${testFile}") (goto-line 2))`);

  // Read from line 4. temp: true governs buffer lifecycle; move defaults to false → point restored.
  const elisp = buildReadElisp(testFile, { line: 4, length: 10, temp: false });
  emacsclientParsed(elisp);

  // Check that point is back at line 2 (restored by move: false)
  const currentLine = emacsclientParsed(
    `(with-current-buffer (find-buffer-visiting "${testFile}") (line-number-at-pos))`
  );
  assertEqual(currentLine, 2);
});

test("read - move: true with temp: true advances point and kills new buffers", () => {
  // Position at line 2
  emacsclient(`(progn (find-file "${testFile}") (goto-line 2))`);

  // Read from line 4 with both move: true and temp: false (existing buffer)
  const elisp = buildReadElisp(testFile, { line: 4, length: 10, move: true });
  const result = emacsclientParsed(elisp);

  // move: true → point should advance to end of what was read
  assertEqual(result.point.pos, result.got.end.pos,
    "move: true should leave point at content end");

  // Confirm point has moved in the buffer
  const currentLine = emacsclientParsed(
    `(with-current-buffer (find-buffer-visiting "${testFile}") (line-number-at-pos))`
  );
  assert(currentLine !== 2, "point should have moved from line 2");
});

test("read - temp mode kills new buffer", () => {
  const newFile = join(testFilesDir, "temp-test.txt");

  // Read with temp mode
  const elisp = buildReadElisp(newFile, { pos: 1, length: 10, temp: true });
  const result = emacsclientParsed(elisp);

  assertEqual(result.new, true);
  assertEqual(result.dead, true);

  // Verify buffer is killed
  const bufferExists = emacsclientParsed(`(if (get-buffer "temp-test.txt") t :json-false)`);
  assertEqual(bufferExists, false);
});

test("read - temp mode does not kill existing buffer", () => {
  // Open buffer first
  emacsclient(`(find-file "${testFile}")`);

  // Read with temp mode
  const elisp = buildReadElisp(testFile, { pos: 1, length: 10, temp: true });
  const result = emacsclientParsed(elisp);

  assertEqual(result.new, false);
  assertEqual(result.dead, false);

  // Verify buffer still exists
  const bufferExists = emacsclientParsed(
    `(if (find-buffer-visiting "${testFile}") t :json-false)`
  );
  assertEqual(bufferExists, true);
});

// ---------------------------------------------------------------------------
// Read tool - metadata tests
// ---------------------------------------------------------------------------

test("read - reports buffer size correctly", () => {
  const elisp = buildReadElisp(testFile, { pos: 1, length: 10 });
  const result = emacsclientParsed(elisp);

  assertEqual(result.size, testFileContent.length);
});

test("read - reports line count correctly", () => {
  const elisp = buildReadElisp(testFile, { pos: 1, length: 10 });
  const result = emacsclientParsed(elisp);

  assertEqual(result.lines, 5);
});

test("read - detects unsaved buffer", () => {
  // Open and modify buffer
  emacsclient(`(progn (find-file "${testFile}") (insert "x") (set-buffer-modified-p t))`);

  const elisp = buildReadElisp(testFile, { length: 10 });
  const result = emacsclientParsed(elisp);

  assertEqual(result.unsaved, true);
});

test("read - detects unchanged buffer", () => {
  cleanupBuffers();

  const elisp = buildReadElisp(testFile, { pos: 1, length: 10 });
  const result = emacsclientParsed(elisp);

  assertEqual(result.unsaved, false);
});

test("read - reports correct major mode", () => {
  const elisp = buildReadElisp(pythonFile, { pos: 1, length: 10 });
  const result = emacsclientParsed(elisp);

  assertContains(result.mode.toLowerCase(), "python");
});

// ---------------------------------------------------------------------------
// Read tool - region tests
// ---------------------------------------------------------------------------

test("read - region is null when no active region", () => {
  cleanupBuffers();

  const elisp = buildReadElisp(testFile, { pos: 1, length: 10 });
  const result = emacsclientParsed(elisp);

  assertEqual(result.region, null);
});

test("read - captures active region", () => {
  // Set up: mark=1, point=11 (region covers positions 1..11)
  emacsclient(`(progn
    (find-file "${testFile}")
    (goto-char 1)
    (push-mark (point) t t)
    (forward-char 10))`);

  // Read 10 chars from point (pos 11); move: true advances point to 21.
  // Moving point extends the region: mark stays at 1, point is now 21.
  const elisp = buildReadElisp(testFile, { length: 10, move: true });
  const result = emacsclientParsed(elisp);

  assert(result.region !== null, "Should have region");
  assertEqual(result.region.start.pos, 1);
  assertEqual(result.region.end.pos, 21);  // point moved here after the read
});

test("read - region content matches expected text", () => {
  cleanupBuffers();

  // Set up: mark=1, point=7 (region covers "Line 1", positions 1..7)
  emacsclient(`(progn
    (find-file "${testFile}")
    (goto-char 1)
    (push-mark (point) t t)
    (forward-char 6))`);

  // Read exactly 6 chars starting at pos 1; move: true advances point to 7 —
  // same as where setup left point — so the region stays [1, 7] with content "Line 1".
  const elisp = buildReadElisp(testFile, { pos: 1, length: 6, move: true });
  const result = emacsclientParsed(elisp);

  assertEqual(result.region.content, "Line 1");
});

test("read - region truncated when too large", () => {
  cleanupBuffers();

  // Set up large region
  emacsclient(`(progn
    (find-file "${largeFile}")
    (goto-char 1)
    (push-mark (point) t t)
    (goto-char (point-max)))`);

  // Don't specify pos - we want to preserve the region
  const elisp = buildReadElisp(largeFile, { length: 10 }, 1000);
  const result = emacsclientParsed(elisp);

  assert(result.region !== null, "Should have region");
  assert(result.region.truncated, "Region should be truncated");
  assert(result.region.content.length <= 1000, "Region content should respect maxLength");
});

// ---------------------------------------------------------------------------
// Read tool - process tests
// ---------------------------------------------------------------------------

test("read - process is null for regular file buffer", () => {
  cleanupBuffers();

  const elisp = buildReadElisp(testFile, { pos: 1, length: 10 });
  const result = emacsclientParsed(elisp);

  assertEqual(result.process, null);
});

test("read - detects buffer with process", () => {
  // Start a shell process
  emacsclient(`(shell)`);

  const elisp = buildReadElisp("*shell*", { length: 10 });
  const result = emacsclientParsed(elisp);

  assert(result.process !== null, "Should have process");
  assertContains(result.process.cmd.toLowerCase(), "sh");
});

// ---------------------------------------------------------------------------
// Read tool - TRAMP tests (theoretical - requires SSH setup)
// ---------------------------------------------------------------------------

test("read - tramp is null for local file", () => {
  cleanupBuffers();

  const elisp = buildReadElisp(testFile, { pos: 1, length: 10 });
  const result = emacsclientParsed(elisp);

  assertEqual(result.tramp, null);
});

// ---------------------------------------------------------------------------
// Read tool - edge cases
// ---------------------------------------------------------------------------

test("read - handles empty file", () => {
  const emptyFile = join(testFilesDir, "empty.txt");
  writeFileSync(emptyFile, "");

  const elisp = buildReadElisp(emptyFile, { pos: 1, length: 100 });
  const result = emacsclientParsed(elisp);

  assertEqual(result.size, 0);
  assertEqual(result.lines, 0);
  assertEqual(result.got.content, "");
  assertEqual(result.got.truncated, false);
});

test("read - handles unicode content", () => {
  const unicodeFile = join(testFilesDir, "unicode.txt");
  const unicodeContent = "Hello 世界 🚀";
  writeFileSync(unicodeFile, unicodeContent);

  const elisp = buildReadElisp(unicodeFile, { pos: 1, length: 100 });
  const result = emacsclientParsed(elisp);

  assertEqual(result.got.content, unicodeContent);
});

test("read - handles file with only newlines", () => {
  const newlineFile = join(testFilesDir, "newlines.txt");
  writeFileSync(newlineFile, "\n\n\n\n");

  const elisp = buildReadElisp(newlineFile, { pos: 1, length: 100 });
  const result = emacsclientParsed(elisp);

  assertEqual(result.got.content, "\n\n\n\n");
  assertEqual(result.lines, 4);
});

test("read - point object has all fields", () => {
  const elisp = buildReadElisp(testFile, { line: 2, col: 3, length: 10 });
  const result = emacsclientParsed(elisp);

  assert(typeof result.point.pos === "number", "point.pos should be number");
  assert(typeof result.point.line === "number", "point.line should be number");
  assert(typeof result.point.col === "number", "point.col should be number");
});

test("read - got object has all required fields", () => {
  const elisp = buildReadElisp(testFile, { pos: 1, length: 10 });
  const result = emacsclientParsed(elisp);

  assert(typeof result.got.content === "string", "got.content should be string");
  assert(typeof result.got.length === "number", "got.length should be number");
  assert(typeof result.got.lines === "number", "got.lines should be number");
  assert(typeof result.got.truncated === "boolean", "got.truncated should be boolean");
  assert(typeof result.got.start === "object", "got.start should be object");
  assert(typeof result.got.end === "object", "got.end should be object");
});

// ---------------------------------------------------------------------------
// Span functionality tests
// ---------------------------------------------------------------------------

test("read - returns spans when warbo-span-calculators is defined", () => {
  // Set up a simple span calculator
  const setupElisp = `
    (setq warbo-span-calculators
      (list (lambda (buf)
              (with-current-buffer buf
                (list
                  (cons 'test1 '((start . 1) (end . 5) (value . "test span 1")))
                  (cons 'test2 '((start . 6) (end . 10) (value . "test span 2"))))))))
  `;
  emacsclient(setupElisp);

  const elisp = buildReadElisp(testFile, { pos: 1, length: 100 });
  const result = emacsclientParsed(elisp);

  assert(result.spans !== undefined, "Should have spans field");
  assert(typeof result.spans === "object", "spans should be an object");
});

test("read - uses warbo-span-selector when defined", () => {
  // Set up calculators and custom selector
  const setupElisp = `
    (progn
      (setq warbo-span-calculators
        (list (lambda (buf)
                (with-current-buffer buf
                  (list
                    (cons 'span1 '((start . 1) (end . 5) (value . "span 1")))
                    (cons 'span2 '((start . 6) (end . 10) (value . "span 2")))
                    (cons 'span3 '((start . 11) (end . 15) (value . "span 3")))
                    (cons 'span4 '((start . 16) (end . 20) (value . "span 4"))))))))
      (setq warbo-span-selector
        (lambda (spans) (seq-take spans 2))))
  `;
  emacsclient(setupElisp);

  const elisp = buildReadElisp(testFile, { pos: 1, length: 100 });
  const result = emacsclientParsed(elisp);

  const spanCount = Object.keys(result.spans || {}).length;
  assertEqual(spanCount, 2, "Should have exactly 2 spans (from custom selector)");
});

test("read - default selector takes first 3 spans", () => {
  // Set up calculators without custom selector
  const setupElisp = `
    (progn
      (makunbound 'warbo-span-selector)
      (setq warbo-span-calculators
        (list (lambda (buf)
                (with-current-buffer buf
                  (list
                    (cons 'span1 '((start . 1) (end . 5) (value . "span 1")))
                    (cons 'span2 '((start . 6) (end . 10) (value . "span 2")))
                    (cons 'span3 '((start . 11) (end . 15) (value . "span 3")))
                    (cons 'span4 '((start . 16) (end . 20) (value . "span 4")))
                    (cons 'span5 '((start . 21) (end . 25) (value . "span 5")))))))))
  `;
  emacsclient(setupElisp);

  const elisp = buildReadElisp(testFile, { pos: 1, length: 100 });
  const result = emacsclientParsed(elisp);

  const spanCount = Object.keys(result.spans || {}).length;
  assertEqual(spanCount, 3, "Should have exactly 3 spans (default selector)");
});

test("read - span parameter narrows to specified span", () => {
  // Set up a file with known content and span
  const spanTestFile = join(tempDir, "span-test.txt");
  writeFileSync(spanTestFile, "AAAAAABBBBBBBCCCCCC");

  const setupElisp = `
    (setq warbo-span-calculators
      (list (lambda (buf)
              (with-current-buffer buf
                (list
                  (cons 'spanA '((start . 1) (end . 7) (value . "region A")))
                  (cons 'spanB '((start . 7) (end . 14) (value . "region B")))
                  (cons 'spanC '((start . 14) (end . 20) (value . "region C"))))))))
  `;
  emacsclient(setupElisp);

  const elisp = buildReadElisp(spanTestFile, { span: "spanB", pos: 1, length: 100 });
  const result = emacsclientParsed(elisp);

  assertContains(result.got.content, "BBBBBBB", "Should only read content from spanB");
  assert(!result.got.content.includes("AAAAAAA"), "Should not include content from spanA");
  assert(!result.got.content.includes("CCCCCC"), "Should not include content from spanC");
});

test("read - span not found returns warning", () => {
  const setupElisp = `
    (setq warbo-span-calculators
      (list (lambda (buf)
              (with-current-buffer buf
                (list
                  (cons 'span1 '((start . 1) (end . 5) (value . "span 1"))))))))
  `;
  emacsclient(setupElisp);

  const elisp = buildReadElisp(testFile, { span: "nonexistent", pos: 1, length: 100 });
  const result = emacsclientParsed(elisp);

  assert(result.warning !== undefined, "Should have warning field");
  assertContains(result.warning, "not found", "Warning should mention span not found");
  assertContains(result.warning, "nonexistent", "Warning should mention the span ID");
});

test("read - temp mode does not include spans", () => {
  const setupElisp = `
    (setq warbo-span-calculators
      (list (lambda (buf)
              (with-current-buffer buf
                (list
                  (cons 'test1 '((start . 1) (end . 5) (value . "test span 1"))))))))
  `;
  emacsclient(setupElisp);

  const elisp = buildReadElisp(testFile, { pos: 1, length: 100, temp: true });
  const result = emacsclientParsed(elisp);

  assert(result.spans === undefined, "Should not have spans field in temp mode");
});

test("read - no spans when warbo-span-calculators undefined", () => {
  // Unset the variable
  emacsclient("(makunbound 'warbo-span-calculators)");

  const elisp = buildReadElisp(testFile, { pos: 1, length: 100 });
  const result = emacsclientParsed(elisp);

  assert(result.spans === undefined, "Should not have spans when calculators not defined");
});

test("read - empty spans not included in result", () => {
  // Set up calculator that returns empty list
  const setupElisp = `
    (setq warbo-span-calculators
      (list (lambda (buf) nil)))
  `;
  emacsclient(setupElisp);

  const elisp = buildReadElisp(testFile, { pos: 1, length: 100 });
  const result = emacsclientParsed(elisp);

  assert(result.spans === undefined, "Should not have spans field when empty");
});

// ---------------------------------------------------------------------------
// Summary
// ---------------------------------------------------------------------------

console.log(`\n# ${passed} passed, ${failed} failed`);
if (failed > 0) process.exit(1);

#!/usr/bin/env tsx
/**
 * Unit tests for emacsclient extension — pure function tests.
 *
 * Tests elisp generation and result parsing without any Emacs or Pi interaction.
 * Now imports the actual implementation instead of inline copies.
 */

import {
  escapeElispString,
  buildTsQueryElisp,
  buildEvalElisp,
  buildReadElisp,
  buildWriteElisp,
  buildTransportElisp,
  parseEmacsclientOutput,
  parseEmacsclientTransportOutput,
  parseEmacsclientError,
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
// escapeElispString tests
// ---------------------------------------------------------------------------

test("escapeElispString - plain string unchanged", () => {
  assertEqual(escapeElispString("hello"), "hello");
});

test("escapeElispString - escapes double quotes", () => {
  assertEqual(escapeElispString('say "hello"'), 'say \\"hello\\"');
});

test("escapeElispString - escapes backslashes", () => {
  assertEqual(escapeElispString("path\\to\\file"), "path\\\\to\\\\file");
});

test("escapeElispString - escapes newlines", () => {
  assertEqual(escapeElispString("line1\nline2"), "line1\\nline2");
});

test("escapeElispString - handles combined escapes", () => {
  const result = escapeElispString('a "b\nc\\d"');
  assertEqual(result, 'a \\"b\\nc\\\\d\\"');
});

test("escapeElispString - empty string", () => {
  assertEqual(escapeElispString(""), "");
});

// ---------------------------------------------------------------------------
// buildEvalElisp tests
// ---------------------------------------------------------------------------

test("buildEvalElisp - wraps expression", () => {
  const result = buildEvalElisp("(+ 1 2)");
  assert(result.includes("(+ 1 2)"), "Should contain the expression");
  assert(result.includes("json-encode"), "Should use json-encode");
});

test("buildEvalElisp - handles multi-expression", () => {
  const result = buildEvalElisp("(setq x 1) (+ x 2)");
  assert(result.includes("progn"), "Should wrap in progn");
});

test("buildTransportElisp - wraps expression in base64 transport", () => {
  const result = buildTransportElisp('(json-encode "hello")');
  assert(result.includes('(progn (json-encode "hello"))'), "Should evaluate the original expression");
  assert(result.includes("base64-encode-string"), "Should encode payload as base64");
  assert(result.includes("json-encode payload"), "Should JSON-encode the transport payload");
});

// ---------------------------------------------------------------------------
// parseEmacsclientOutput tests
// ---------------------------------------------------------------------------

test("parseEmacsclientOutput - JSON array string", () => {
  // emacsclient prints: "[{\"name\":\"scratch\"}]"
  const raw = '"[{\\"name\\":\\"scratch\\"}]"';
  const result = parseEmacsclientOutput(raw);
  assertDeepEqual(result, [{ name: "scratch" }]);
});

test("parseEmacsclientOutput - JSON object string", () => {
  const raw = '"{\\"buffer\\":\\"main.py\\",\\"point\\":42}"';
  const result = parseEmacsclientOutput(raw);
  assertDeepEqual(result, { buffer: "main.py", point: 42 });
});

test("parseEmacsclientOutput - simple string result", () => {
  const raw = '"\\"hello world\\""';
  const result = parseEmacsclientOutput(raw);
  assertEqual(result, "hello world");
});

test("parseEmacsclientOutput - number result", () => {
  assertEqual(parseEmacsclientOutput("42"), 42);
});

test("parseEmacsclientOutput - nil result", () => {
  assertEqual(parseEmacsclientOutput("nil"), null);
});

test("parseEmacsclientOutput - t result", () => {
  assertEqual(parseEmacsclientOutput("t"), true);
});

test("parseEmacsclientOutput - trims whitespace", () => {
  const result = parseEmacsclientOutput('  "42"  \n');
  assertEqual(result, 42);
});

// ---------------------------------------------------------------------------
// parseEmacsclientTransportOutput tests
// ---------------------------------------------------------------------------

function transportRaw(payload: unknown): string {
  return '"' + Buffer.from(JSON.stringify(payload), "utf8").toString("base64") + '"';
}

test("parseEmacsclientTransportOutput - decodes JSON string payload", () => {
  const raw = transportRaw({
    type: "string",
    value: '{"content":"line1\\nline2"}',
  });
  const result = parseEmacsclientTransportOutput(raw);
  assertDeepEqual(result, { content: "line1\nline2" });
});

test("parseEmacsclientTransportOutput - preserves plain strings", () => {
  const raw = transportRaw({
    type: "string",
    value: "GNU Emacs 30.2",
  });
  const result = parseEmacsclientTransportOutput(raw);
  assertEqual(result, "GNU Emacs 30.2");
});

test("parseEmacsclientTransportOutput - decodes booleans and null", () => {
  assertEqual(
    parseEmacsclientTransportOutput(transportRaw({ type: "boolean", value: false })),
    false
  );
  assertEqual(parseEmacsclientTransportOutput(transportRaw({ type: "null" })), null);
});

test("parseEmacsclientOutput - nested JSON with newlines", () => {
  // Emacs buffer has "line1<newline>line2". json-encode produces {"content":"line1\nline2"}.
  // json-encode output has \n as two chars (backslash + n).
  // Emacs prin1 escapes the \ to \\, so stdout is: "{\"content\":\"line1\\nline2\"}"
  // In JS source, we need \\\\ for the prin1 \\, giving us: \\\\n
  const raw = '"{\\"content\\":\\"line1\\\\nline2\\"}"';
  const result = parseEmacsclientOutput(raw);
  assertDeepEqual(result, { content: "line1\nline2" });
});

test("parseEmacsclientOutput - empty JSON array", () => {
  const raw = '"[]"';
  const result = parseEmacsclientOutput(raw);
  assertDeepEqual(result, []);
});

test("parseEmacsclientOutput - boolean fields in JSON", () => {
  const raw = '"{\\"modified\\":false,\\"visible\\":true}"';
  const result = parseEmacsclientOutput(raw);
  assertDeepEqual(result, { modified: false, visible: true });
});

test("parseEmacsclientOutput - null fields in JSON", () => {
  const raw = '"{\\"filepath\\":null}"';
  const result = parseEmacsclientOutput(raw);
  assertDeepEqual(result, { filepath: null });
});

// ---------------------------------------------------------------------------
// parseEmacsclientError tests
// ---------------------------------------------------------------------------

test("parseEmacsclientError - standard error format", () => {
  assertEqual(
    parseEmacsclientError("*ERROR*: Wrong type argument"),
    "Wrong type argument"
  );
});

test("parseEmacsclientError - without asterisks", () => {
  assertEqual(
    parseEmacsclientError("ERROR: Buffer not found"),
    "Buffer not found"
  );
});

test("parseEmacsclientError - plain message", () => {
  assertEqual(
    parseEmacsclientError("emacsclient: can't find socket"),
    "emacsclient: can't find socket"
  );
});

test("parseEmacsclientError - trims whitespace", () => {
  assertEqual(
    parseEmacsclientError("  *ERROR*: foo  \n"),
    "foo"
  );
});

test("parseEmacsclientError - multiline error", () => {
  const result = parseEmacsclientError("*ERROR*: line1\nline2\nline3");
  assert(result.includes("line1"), "Should include first line");
  assert(result.includes("line3"), "Should include last line");
});

// ---------------------------------------------------------------------------
// Elisp structural integrity tests
// ---------------------------------------------------------------------------

test("buildEvalElisp - balanced parentheses", () => {
  const elisp = buildEvalElisp("(message \"hello\")");
  let depth = 0;
  for (const ch of elisp) {
    if (ch === "(") depth++;
    if (ch === ")") depth--;
    assert(depth >= 0, "Parentheses went negative");
  }
  assertEqual(depth, 0, "Parentheses not balanced");
});

// ---------------------------------------------------------------------------
// Round-trip and edge case tests
// ---------------------------------------------------------------------------

test("parseEmacsclientOutput - handles content with special chars", () => {
  // Simulates buffer content containing quotes, backslashes, newlines.
  // The expected JS value has actual newlines and a real backslash.
  const expected = { content: 'line1\n"quoted"\npath\\to' };
  const jsonStr = JSON.stringify(expected);
  // Simulate prin1: escape \ to \\ and " to \" (order matters: \ first)
  const prin1Inner = jsonStr.replace(/\\/g, "\\\\").replace(/"/g, '\\"');
  const elispStr = '"' + prin1Inner + '"';
  const result = parseEmacsclientOutput(elispStr);
  assertDeepEqual(result, expected);
});

test("escapeElispString - roundtrip through parse", () => {
  // If we escape a string for embedding in elisp, the elisp engine would
  // produce the original string. We simulate this by un-escaping.
  const original = 'hello "world"\nfoo\\bar';
  const escaped = escapeElispString(original);

  // Simulate what Emacs does when it reads the escaped string in a "" literal
  const recovered = escaped
    .replace(/\\"/g, '"')
    .replace(/\\n/g, "\n")
    .replace(/\\\\/g, "\\");
  assertEqual(recovered, original);
});

// ---------------------------------------------------------------------------
// EXTENSIVE escaping edge case tests
// ---------------------------------------------------------------------------

test("escapeElispString - single backslash", () => {
  assertEqual(escapeElispString("\\"), "\\\\");
});

test("escapeElispString - double backslash", () => {
  assertEqual(escapeElispString("\\\\"), "\\\\\\\\");
});

test("escapeElispString - triple backslash", () => {
  assertEqual(escapeElispString("\\\\\\"), "\\\\\\\\\\\\");
});

test("escapeElispString - backslash before quote", () => {
  assertEqual(escapeElispString('\\"'), '\\\\\\"');
});

test("escapeElispString - backslash before newline", () => {
  assertEqual(escapeElispString("\\\n"), "\\\\\\n");
});

test("escapeElispString - quote before newline", () => {
  assertEqual(escapeElispString('"\n'), '\\"\\n');
});

test("escapeElispString - all three: backslash quote newline", () => {
  assertEqual(escapeElispString('\\"\n'), '\\\\\\"\\n');
});

test("escapeElispString - tab character", () => {
  // Tabs are NOT currently escaped - document this behavior
  assertEqual(escapeElispString("a\tb"), "a\tb");
});

test("escapeElispString - carriage return", () => {
  // CR is NOT currently escaped - document this behavior
  assertEqual(escapeElispString("a\rb"), "a\rb");
});

test("escapeElispString - null byte", () => {
  // Null bytes are NOT currently escaped - document this behavior
  assertEqual(escapeElispString("a\x00b"), "a\x00b");
});

test("escapeElispString - unicode characters", () => {
  assertEqual(escapeElispString("hello 世界"), "hello 世界");
});

test("escapeElispString - emoji", () => {
  assertEqual(escapeElispString("test 🚀 emoji"), "test 🚀 emoji");
});

test("escapeElispString - empty vs whitespace", () => {
  assertEqual(escapeElispString(""), "");
  assertEqual(escapeElispString(" "), " ");
  assertEqual(escapeElispString("  "), "  ");
});

test("escapeElispString - only special chars", () => {
  assertEqual(escapeElispString('"""'), '\\"\\"\\"');
  assertEqual(escapeElispString("\n\n\n"), "\\n\\n\\n");
  assertEqual(escapeElispString("\\\\\\"), "\\\\\\\\\\\\");
});

test("escapeElispString - long string with mixed escapes", () => {
  const input = 'line1\nline2 "quoted" \\path\\to\\file\nline3';
  const expected = 'line1\\nline2 \\"quoted\\" \\\\path\\\\to\\\\file\\nline3';
  assertEqual(escapeElispString(input), expected);
});

test("escapeElispString - realistic file content", () => {
  const code = 'function test() {\n  console.log("hello\\nworld");\n}';
  const escaped = escapeElispString(code);
  assert(escaped.includes('\\n'), "Should escape newlines");
  assert(escaped.includes('\\"'), "Should escape quotes");
  assert(escaped.includes('\\\\'), "Should escape backslashes");
});

// ---------------------------------------------------------------------------
// EXTENSIVE parseEmacsclientOutput tests
// ---------------------------------------------------------------------------

test("parseEmacsclientOutput - empty string result", () => {
  // json-encode "" → the string "" (two quote chars)
  // prin1: "\"\""
  // In JS: '"\\"\\""'
  const raw = '"\\"\\""';
  assertEqual(parseEmacsclientOutput(raw), "");
});

test("parseEmacsclientOutput - string with escaped quote", () => {
  // Value: "quoted" (with quotes)
  // json-encode: "\"quoted\"" (quotes escaped in JSON)
  // prin1: "\\\"quoted\\\"" (\ and " each escaped)
  // Full prin1 output: "\"\\\"quoted\\\"\""
  // In JS: '"\\"\\\\\\\"quoted\\\\\\\"\\""'
  // Let's build it programmatically instead:
  const value = '"quoted"';
  const json = JSON.stringify(value);       // "\"quoted\""
  const prin1 = json.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
  const raw = '"' + prin1 + '"';
  assertEqual(parseEmacsclientOutput(raw), value);
});

test("parseEmacsclientOutput - string with escaped backslash", () => {
  // Value: path\to\file (with backslashes)
  // json-encode: "path\\to\\file"
  // prin1 escapes each \: "path\\\\to\\\\file", and quotes
  const value = "path\\to\\file";
  const json = JSON.stringify(value);       // "path\\to\\file"
  const prin1 = json.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
  const raw = '"' + prin1 + '"';
  assertEqual(parseEmacsclientOutput(raw), value);
});

test("parseEmacsclientOutput - string with escaped newline", () => {
  // json-encode "a<newline>b" → JSON: "a\nb" (backslash+n)
  // prin1 escapes \ → \\: "\"a\\nb\""
  // In JS source: \\\\ for prin1's \\, \\\" for prin1's \"
  const raw = '"\\"a\\\\nb\\""';
  const result = parseEmacsclientOutput(raw);
  assertEqual(result, "a\nb");
});

test("parseEmacsclientOutput - json string containing literal backslash-n", () => {
  // Value is: a\nb (literal backslash + n + b, 4 chars)
  // json-encode: "a\\nb" (JSON escapes \ to \\)
  // prin1: "\"a\\\\nb\"" (prin1 escapes each \ to \\)
  // In JS source: each prin1 \\ needs \\\\, each prin1 \" needs \\"
  const raw = '"\\"a\\\\\\\\nb\\""';
  const result = parseEmacsclientOutput(raw);
  assertEqual(result, "a\\nb");
});

test("parseEmacsclientOutput - what json-encode actually produces for newline", () => {
  // Input to json-encode: "a<actual-newline>b"
  // json-encode output: "a\nb" (JSON: backslash+n for newline)
  // prin1 escapes \→\\: "\"a\\nb\""
  // In JS source: \\\\ for \\, \\" for \"
  const raw = '"\\"a\\\\nb\\""';
  const parsed = parseEmacsclientOutput(raw);
  assertEqual(parsed, "a\nb");
});

test("parseEmacsclientOutput - json object with newline in value", () => {
  // JSON: {"content":"line1\nline2"}
  // prin1: "{\"content\":\"line1\\nline2\"}" (\ before n gets escaped to \\)
  // In JS source: \\\\ for prin1's \\
  const raw = '"{\\"content\\":\\"line1\\\\nline2\\"}"';
  const result = parseEmacsclientOutput(raw);
  assertDeepEqual(result, { content: "line1\nline2" });
});

test("parseEmacsclientOutput - json object with backslash in value", () => {
  // Value: c:\dir (one backslash)
  // JSON: {"path":"c:\\dir"} (\ escaped to \\)
  // prin1: "{\"path\":\"c:\\\\dir\"}" (each \ escaped to \\, so \\ becomes \\\\)
  // In JS source: each prin1 \\ needs \\\\
  const raw = '"{\\"path\\":\\"c:\\\\\\\\dir\\"}"';
  const result = parseEmacsclientOutput(raw);
  assertDeepEqual(result, { path: "c:\\dir" });
});

test("parseEmacsclientOutput - json object with quote in value", () => {
  // Value: say "hi"
  // JSON: {"msg":"say \"hi\""} (quotes escaped to \")
  // prin1: "{\"msg\":\"say \\\"hi\\\"\"}" (\ escaped to \\, " escaped to \")
  // In JS source: prin1's \\ needs \\\\, prin1's \" needs \\"
  const raw = '"{\\"msg\\":\\"say \\\\\\"hi\\\\\\"\\"}"\n';
  const result = parseEmacsclientOutput(raw);
  assertDeepEqual(result, { msg: 'say "hi"' });
});

test("parseEmacsclientOutput - zero", () => {
  assertEqual(parseEmacsclientOutput("0"), 0);
});

test("parseEmacsclientOutput - negative number", () => {
  assertEqual(parseEmacsclientOutput("-42"), -42);
});

test("parseEmacsclientOutput - float", () => {
  assertEqual(parseEmacsclientOutput("3.14"), 3.14);
});

test("parseEmacsclientOutput - negative float", () => {
  assertEqual(parseEmacsclientOutput("-2.5"), -2.5);
});

test("parseEmacsclientOutput - very long number", () => {
  assertEqual(parseEmacsclientOutput("123456789"), 123456789);
});

test("parseEmacsclientOutput - leading/trailing spaces on string", () => {
  // Value: "  spaces  " (string with spaces)
  // json-encode: "\"  spaces  \"" → prin1: "\"\\\"  spaces  \\\"\""
  const value = "  spaces  ";
  const json = JSON.stringify(value);
  const prin1 = json.replace(/\\/g, '\\\\').replace(/"/g, '\\"');
  const raw = '"' + prin1 + '"';
  assertEqual(parseEmacsclientOutput(raw), value);
});

test("parseEmacsclientOutput - nested json arrays", () => {
  const raw = '"[[1,2],[3,4]]"';
  const result = parseEmacsclientOutput(raw);
  assertDeepEqual(result, [[1, 2], [3, 4]]);
});

test("parseEmacsclientOutput - deeply nested json", () => {
  const raw = '"{\\"a\\":{\\"b\\":{\\"c\\":123}}}"';
  const result = parseEmacsclientOutput(raw);
  assertDeepEqual(result, { a: { b: { c: 123 } } });
});

test("parseEmacsclientOutput - json with all types", () => {
  const raw = '"{\\"str\\":\\"hi\\",\\"num\\":42,\\"bool\\":true,\\"nil\\":null,\\"arr\\":[1,2]}"';
  const result = parseEmacsclientOutput(raw);
  assertDeepEqual(result, {
    str: "hi",
    num: 42,
    bool: true,
    nil: null,
    arr: [1, 2]
  });
});

test("parseEmacsclientOutput - json array with mixed types", () => {
  const raw = '"[1,\\"two\\",true,null]"';
  const result = parseEmacsclientOutput(raw);
  assertDeepEqual(result, [1, "two", true, null]);
});

test("parseEmacsclientOutput - NaN string (not a number parse)", () => {
  const raw = "NaN";
  const result = parseEmacsclientOutput(raw);
  assertEqual(result, "NaN"); // Should return as string, not number
});

test("parseEmacsclientOutput - Infinity string", () => {
  const raw = "Infinity";
  const result = parseEmacsclientOutput(raw);
  assertEqual(result, "Infinity"); // Should return as string, not number
});

// ---------------------------------------------------------------------------
// Tests documenting EXPECTED json-encode behavior from Emacs
// ---------------------------------------------------------------------------

test("EXPECTED - json-encode string with newline becomes escaped in JSON", () => {
  // json-encode "a<newline>b" → JSON: "a\nb" (two chars: backslash + n)
  // prin1 escapes \ → \\: "\"a\\nb\""
  // In JS source: \\\\ for prin1's \\
  const emacsclientOutput = '"\\"a\\\\nb\\""';
  const parsed = parseEmacsclientOutput(emacsclientOutput);
  assertEqual(parsed, "a\nb");
});

test("EXPECTED - json-encode object with newline in field", () => {
  // json-encode produces: {"content":"line1\nline2"}
  // prin1 escapes \ → \\: "{\"content\":\"line1\\nline2\"}"
  // In JS source: \\\\ for prin1's \\
  const emacsclientOutput = '"{\\"content\\":\\"line1\\\\nline2\\"}"';
  const parsed = parseEmacsclientOutput(emacsclientOutput);

  assertDeepEqual(parsed, { content: "line1\nline2" });
});

test("EXPECTED - json-encode preserves backslashes correctly", () => {
  // Value: c:\dir (one backslash)
  // JSON: "c:\\dir" (\ escaped to \\)
  // prin1: "\"c:\\\\dir\"" (\\ each escaped to \\\\)
  // In JS source: \\\\\\\\ for prin1's \\\\
  const emacsclientOutput = '"\\"c:\\\\\\\\dir\\""';
  const parsed = parseEmacsclientOutput(emacsclientOutput);

  assertEqual(parsed, "c:\\dir");
});

test("EXPECTED - json-encode with nil becomes JSON null", () => {
  // In Emacs 30, nil maps to JSON null (not :null which becomes the string "null")
  // (json-encode '(("val" . nil))) → {"val":null}
  // prin1: "{\"val\":null}"
  const emacsclientOutput = '"{\\"val\\":null}"';
  const parsed = parseEmacsclientOutput(emacsclientOutput);

  assertDeepEqual(parsed, { val: null });
});

test("EXPECTED - json-encode with :json-false becomes JSON false", () => {
  // When we call (json-encode '(("val" . :json-false))) in Emacs:
  // json-encode produces: {"val":false}

  const emacsclientOutput = '"{\\"val\\":false}"';
  const parsed = parseEmacsclientOutput(emacsclientOutput);

  assertDeepEqual(parsed, { val: false });
});

test("EXPECTED - json-encode with t becomes JSON true", () => {
  // When we call (json-encode '(("val" . t))) in Emacs:
  // json-encode produces: {"val":true}

  const emacsclientOutput = '"{\\"val\\":true}"';
  const parsed = parseEmacsclientOutput(emacsclientOutput);

  assertDeepEqual(parsed, { val: true });
});

test("EXPECTED - json-encode with number", () => {
  const emacsclientOutput = '"{\\"count\\":42}"';
  const parsed = parseEmacsclientOutput(emacsclientOutput);

  assertDeepEqual(parsed, { count: 42 });
});

test("EXPECTED - json-encode returns string that we parse as JSON", () => {
  // The pattern: json-encode returns a JSON string, Emacs prints it as elisp string
  // We parse the elisp string to get JSON, then JSON.parse to get the value

  const emacsclientOutput = '"[1,2,3]"';
  const parsed = parseEmacsclientOutput(emacsclientOutput);

  assertDeepEqual(parsed, [1, 2, 3]);
});

// ---------------------------------------------------------------------------
// Tests for realistic buffer content scenarios
// ---------------------------------------------------------------------------

test("realistic - python code with strings", () => {
  const pythonCode = 'def hello():\n    print("Hello, world!")\n    return True';

  // Simulate what json-encode would produce for buffer content
  const jsonObj = { content: pythonCode };
  const jsonStr = JSON.stringify(jsonObj);
  // Elisp print escapes
  const elispStr = '"' + jsonStr.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';

  const parsed = parseEmacsclientOutput(elispStr);
  assertDeepEqual(parsed, jsonObj);
  assertEqual(parsed.content, pythonCode);
});

test("realistic - javascript with regex and escapes", () => {
  const jsCode = 'const re = /\\d+/;\nconst str = "test\\nline";';

  const jsonObj = { content: jsCode };
  const jsonStr = JSON.stringify(jsonObj);
  const elispStr = '"' + jsonStr.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';

  const parsed = parseEmacsclientOutput(elispStr);
  assertEqual(parsed.content, jsCode);
});

test("realistic - windows path in buffer", () => {
  const path = 'C:\\Users\\Name\\Documents\\file.txt';

  const jsonObj = { filepath: path };
  const jsonStr = JSON.stringify(jsonObj);
  const elispStr = '"' + jsonStr.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';

  const parsed = parseEmacsclientOutput(elispStr);
  assertEqual(parsed.filepath, path);
});

test("realistic - shell script with quotes and newlines", () => {
  const script = '#!/bin/bash\necho "Starting..."\nif [ -f "test.txt" ]; then\n  cat "test.txt"\nfi';

  const jsonObj = { content: script };
  const jsonStr = JSON.stringify(jsonObj);
  const elispStr = '"' + jsonStr.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';

  const parsed = parseEmacsclientOutput(elispStr);
  assertEqual(parsed.content, script);
});

test("realistic - json file content in buffer", () => {
  const jsonContent = '{\n  "name": "test",\n  "value": 42\n}';

  const jsonObj = { content: jsonContent };
  const jsonStr = JSON.stringify(jsonObj);
  const elispStr = '"' + jsonStr.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';

  const parsed = parseEmacsclientOutput(elispStr);
  assertEqual(parsed.content, jsonContent);
});

test("realistic - markdown with various characters", () => {
  const markdown = '# Title\n\nSome text with "quotes" and `code`.\n\n- List item\n- Another item';

  const jsonObj = { content: markdown };
  const jsonStr = JSON.stringify(jsonObj);
  const elispStr = '"' + jsonStr.replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"';

  const parsed = parseEmacsclientOutput(elispStr);
  assertEqual(parsed.content, markdown);
});

// ---------------------------------------------------------------------------
// buildReadElisp / buildWriteElisp - name classification (path vs buffer)
// ---------------------------------------------------------------------------

// Helper: check if elisp uses find-buffer-visiting (path mode)
function usesPathMode(elisp: string): boolean {
  return elisp.includes('find-buffer-visiting');
}

// Helper: check if elisp uses get-buffer-create (bare buffer, no file association)
function usesGetBufferCreate(elisp: string): boolean {
  return elisp.includes('get-buffer-create');
}

// Helper: check if elisp uses find-file-noselect with "./" prefix (relative file)
function usesRelativeFileFallback(elisp: string): boolean {
  return elisp.includes('find-file-noselect "./' );
}

test("buildReadElisp - absolute path (/) treated as path", () => {
  const elisp = buildReadElisp("/home/user/file.txt");
  assert(usesPathMode(elisp), "Should use find-buffer-visiting for absolute paths");
  assert(!usesGetBufferCreate(elisp), "Should not use get-buffer-create for paths");
});

test("buildReadElisp - relative path (./) treated as path", () => {
  const elisp = buildReadElisp("./myfile.txt");
  assert(usesPathMode(elisp), "Should use find-buffer-visiting for ./ paths");
});

test("buildReadElisp - relative path (../) treated as path", () => {
  const elisp = buildReadElisp("../other/file.ts");
  assert(usesPathMode(elisp), "Should use find-buffer-visiting for ../ paths");
});

test("buildReadElisp - name with slash not at start treated as buffer name", () => {
  const elisp = buildReadElisp("foo/bar");
  assert(!usesPathMode(elisp), "Should NOT use find-buffer-visiting for 'foo/bar'");
  // Has / so it's a special-char buffer name => get-buffer-create
  assert(usesGetBufferCreate(elisp), "Should use get-buffer-create (/ is special char)");
});

test("buildReadElisp - *starred* buffer name uses get-buffer-create (no file)", () => {
  const elisp = buildReadElisp("*my-temp-buffer*");
  assert(!usesPathMode(elisp), "Should not use path mode for starred buffer name");
  assert(usesGetBufferCreate(elisp), "Should use get-buffer-create for *name*");
  assert(!usesRelativeFileFallback(elisp), "Should NOT use relative file fallback");
});

test("buildReadElisp - <buffer> name uses get-buffer-create (no file)", () => {
  const elisp = buildReadElisp("<special>");
  assert(usesGetBufferCreate(elisp), "Should use get-buffer-create for <name>");
});

test("buildReadElisp - plain buffer name uses relative file fallback", () => {
  const elisp = buildReadElisp("my-notes");
  assert(!usesPathMode(elisp), "Should not use path mode for plain buffer name");
  assert(!usesGetBufferCreate(elisp), "Should not use get-buffer-create for plain name");
  assert(usesRelativeFileFallback(elisp), "Should use find-file-noselect ./ for plain name");
  assert(elisp.includes('"./my-notes"'), "Should prepend ./ to the name");
});

test("buildReadElisp - plain buffer name with hyphen uses relative file fallback", () => {
  const elisp = buildReadElisp("my-buffer");
  assert(usesRelativeFileFallback(elisp), "Hyphenated name should use ./ fallback");
  assert(elisp.includes('"./my-buffer"'), "Should use ./my-buffer");
});

test("buildWriteElisp - absolute path treated as path", () => {
  const elisp = buildWriteElisp("/tmp/test.txt", "hello");
  assert(usesPathMode(elisp), "Should use find-buffer-visiting for absolute paths");
});

test("buildWriteElisp - ./ relative path treated as path", () => {
  const elisp = buildWriteElisp("./notes.txt", "content");
  assert(usesPathMode(elisp), "Should use find-buffer-visiting for ./ path");
});

test("buildWriteElisp - ../ relative path treated as path", () => {
  const elisp = buildWriteElisp("../README.md", "content");
  assert(usesPathMode(elisp), "Should use find-buffer-visiting for ../ path");
});

test("buildWriteElisp - *temp* buffer uses get-buffer-create (no file)", () => {
  const elisp = buildWriteElisp("*scratch*", "content");
  assert(usesGetBufferCreate(elisp), "Should use get-buffer-create for *scratch*");
  assert(!usesRelativeFileFallback(elisp), "Should NOT use relative file fallback");
});

test("buildWriteElisp - plain buffer name uses relative file fallback", () => {
  const elisp = buildWriteElisp("my-notes", "content");
  assert(!usesGetBufferCreate(elisp), "Should not use get-buffer-create for plain name");
  assert(usesRelativeFileFallback(elisp), "Should use find-file-noselect ./ for plain name");
  assert(elisp.includes('"./my-notes"'), "Should prepend ./ to the name");
});

test("buildWriteElisp - name with internal slash treated as buffer (not path)", () => {
  const elisp = buildWriteElisp("some/buffer", "content");
  assert(!usesPathMode(elisp), "Should NOT use path mode for 'some/buffer'");
  assert(usesGetBufferCreate(elisp), "Should use get-buffer-create (/ is special char)");
});

test("buildWriteElisp - save uses (when (buffer-file-name) (save-buffer))", () => {
  const elisp = buildWriteElisp("myfile", "content", { save: true });
  assert(elisp.includes('buffer-file-name'), "Save should check buffer-file-name");
  assert(elisp.includes('save-buffer'), "Should call save-buffer");
});

test("buildWriteElisp - save=false omits save-buffer", () => {
  const elisp = buildWriteElisp("myfile", "content", { save: false });
  assert(!elisp.includes('save-buffer'), "Should not call save-buffer when save=false");
});

// ---------------------------------------------------------------------------
// Summary
// ---------------------------------------------------------------------------

console.log(`\n# ${passed} passed, ${failed} failed`);
if (failed > 0) process.exit(1);

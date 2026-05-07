#!/usr/bin/env tsx
/**
 * Regression tests for emacsclient transport handling.
 *
 * These tests specifically guard against the macOS failure mode where
 * read-like JSON payloads containing embedded newlines/control characters were
 * printed by emacsclient as strings and then failed with:
 *
 *   Bad control character in string literal in JSON
 */

import { buildEvalElisp } from "./elisp.ts";
import { emacsEval } from "./emacsclient.ts";

let passed = 0;
let failed = 0;

function assert(condition: boolean, message?: string): asserts condition {
  if (!condition) throw new Error(message || "Assertion failed");
}

function assertDeepEqual<T>(actual: T, expected: T, message?: string) {
  const a = JSON.stringify(actual);
  const e = JSON.stringify(expected);
  if (a !== e) {
    throw new Error(`${message || "assertDeepEqual"}: expected ${e}, got ${a}`);
  }
}

async function test(name: string, fn: () => Promise<void>) {
  try {
    await fn();
    console.log(`ok - ${name}`);
    passed++;
  } catch (err) {
    console.log(`not ok - ${name}`);
    console.log(`  # ${err instanceof Error ? err.message : String(err)}`);
    failed++;
  }
}

function transportRaw(payload: unknown): string {
  return '"' + Buffer.from(JSON.stringify(payload), "utf8").toString("base64") + '"';
}

(async function main() {
  await test("emacsEval decodes read-style payloads with embedded newlines and tabs", async () => {
    const expected = {
      got: {
        content: "line1\nline2\tindent",
        truncated: false,
      },
      point: {
        pos: 12,
        line: 2,
        col: 3,
      },
    };

    let capturedElisp = "";
    const result = await emacsEval(buildEvalElisp("(+ 1 2)"), {
      exec: async (_cmd, args) => {
        capturedElisp = args[args.indexOf("--eval") + 1] ?? "";
        return {
          stdout: transportRaw({ type: "string", value: JSON.stringify(expected) }),
          stderr: "",
          code: 0,
        };
      },
    });

    assert(result.success, `Expected success, got error: ${result.error}`);
    assert(capturedElisp.includes("base64-encode-string"), "Expected transport wrapper around elisp");
    assertDeepEqual(result.data, expected);
  });

  await test("emacsEval preserves plain string results through transport", async () => {
    const result = await emacsEval(buildEvalElisp('(emacs-version)'), {
      exec: async () => ({
        stdout: transportRaw({ type: "string", value: "GNU Emacs 30.2" }),
        stderr: "",
        code: 0,
      }),
    });

    assert(result.success, `Expected success, got error: ${result.error}`);
    assert(result.data === "GNU Emacs 30.2", `Unexpected data: ${JSON.stringify(result.data)}`);
  });

  console.log(`\n# ${passed} passed, ${failed} failed`);
  if (failed > 0) process.exit(1);
})();

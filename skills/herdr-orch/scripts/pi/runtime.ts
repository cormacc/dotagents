// Shared by the explicitly loaded herdr-orch Pi adapters and their in-process tests.
//
// These modules run inside the *installed* Pi process (`pi -e <file>`), never against a
// repository-pinned SDK: the package specifier below resolves to the running runtime, and the
// identity helpers here record which runtime that was so a test run cannot silently bind to
// another one. Nothing here is installed into Pi auto-discovery.
import { realpathSync } from "node:fs";
import { VERSION } from "@earendil-works/pi-coding-agent";

export interface RuntimeIdentity {
  piVersion: string;
  bundle: string;
  node: string;
}

export function runtimeIdentity(): RuntimeIdentity {
  return { piVersion: VERSION, bundle: realpathSync(process.argv[1]), node: process.version };
}

// The caller records what it expects (`pi --version`, a path prefix the running bundle must
// not live under). A mismatch throws; the test entry point turns that into a failed run.
export interface RuntimeExpectation {
  version?: string;
  forbiddenBundlePrefix?: string;
}

export function expectationFromEnv(env: NodeJS.ProcessEnv = process.env): RuntimeExpectation {
  return {
    version: env.HERDR_ORCH_EXPECT_PI_VERSION || undefined,
    forbiddenBundlePrefix: env.HERDR_ORCH_FORBID_BUNDLE_PREFIX || undefined,
  };
}

export function assertRuntime(expected: RuntimeExpectation, identity: RuntimeIdentity = runtimeIdentity()): RuntimeIdentity {
  if (expected.version !== undefined && expected.version !== identity.piVersion) {
    throw new Error(`runtime mismatch: expected Pi ${expected.version}, API resolved to ${identity.piVersion} (${identity.bundle})`);
  }
  const prefix = expected.forbiddenBundlePrefix;
  if (prefix !== undefined && identity.bundle.startsWith(prefix)) {
    throw new Error(`runtime mismatch: API resolved inside forbidden path ${prefix} (${identity.bundle})`);
  }
  return identity;
}

export interface TestCase {
  name: string;
  run: () => void | Promise<void>;
}

export interface SuiteResult {
  suite: string;
  passed: number;
  failed: { name: string; error: string }[];
}

export async function runSuite(suite: string, tests: TestCase[]): Promise<SuiteResult> {
  const result: SuiteResult = { suite, passed: 0, failed: [] };
  for (const test of tests) {
    try {
      await test.run();
      result.passed += 1;
    } catch (error) {
      result.failed.push({ name: test.name, error: error instanceof Error ? [error.message, ...(error.stack ?? "").split("\n").filter((l) => l.includes("/herdr-orch/")).slice(0, 3)].join("\n") : String(error) });
    }
  }
  return result;
}

// Test extensions end the Pi process explicitly: they exist to run once inside the runtime and
// must never reach a model turn.
export function reportAndExit(identity: RuntimeIdentity, results: SuiteResult[]): never {
  const failed = results.reduce((n, r) => n + r.failed.length, 0);
  for (const r of results) {
    process.stdout.write(`SUITE ${r.suite} passed=${r.passed} failed=${r.failed.length}\n`);
    for (const f of r.failed) process.stdout.write(`FAIL ${r.suite} :: ${f.name}\n${f.error}\n`);
  }
  process.stdout.write(`RUNTIME ${JSON.stringify(identity)}\n`);
  process.exit(failed === 0 ? 0 : 1);
}

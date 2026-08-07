#!/usr/bin/env bash
set -euo pipefail

# Test runner for the tasks extension.
#
# Sanity-checks the extension's structural shape and runs the
# parser/scaffold unit tests via the root-local, lockfile-pinned tsx.
# Designed to mirror emacsclient/test.sh in style, but deliberately has no
# ambient/global tsx or `npx --yes` fallback: the dependency closure this
# runner tests against is exactly what the root package-lock.json declares.

cd "$(dirname "$0")"

root="$(cd ../../.. && pwd)"
TSX="$root/node_modules/.bin/tsx"

fail_locked_deps() {
  echo "not ok - $1" >&2
  echo "not ok - run \`npm ci\` from the repository root ($root) to install the locked development dependencies, then re-run this test.sh" >&2
  exit 1
}

if [ ! -x "$TSX" ]; then
  fail_locked_deps "locked tsx not found at $TSX"
fi

# The tsx-executable check above only proves one binary is present; a
# partial install (for example: tsx present, @earendil-works/pi-coding-agent
# absent) must still fail before the first test runs instead of starting
# import-dependent tests that fail deep inside a later file. `npm ls
# --depth=0 --include=dev` walks the root package.json's declared
# dependency/devDependency/peerDependency closure against the actually
# installed node_modules tree (and the lockfile) with no implicit download
# or ambient/global resolution, and exits non-zero on any UNMET DEPENDENCY.
if ! NPM_LS_OUTPUT="$(npm ls --prefix "$root" --depth=0 --include=dev 2>&1)"; then
  echo "not ok - locked development dependency closure is incomplete:" >&2
  echo "$NPM_LS_OUTPUT" | sed 's/^/  /' >&2
  fail_locked_deps "root node_modules does not satisfy the declared dependency closure (see npm ls output above)"
fi

# ── structural sanity ────────────────────────────────────────────────

if [ ! -f "index.ts" ]; then
  echo "not ok - index.ts not found"
  exit 1
fi

if ! grep -q "^export default function" index.ts; then
  echo "not ok - index.ts missing default export"
  exit 1
fi

if ! grep -q "pi.registerCommand" index.ts; then
  echo "not ok - index.ts does not register any commands"
  exit 1
fi

# A `void someAsyncCall()` is a floating promise. From a timer or watcher
# callback there is no caller to await it, so a rejection becomes an
# `uncaughtException` and kills the entire pi process rather than the one
# refresh. That is not hypothetical: a transient `ot list` timeout under load
# did exactly that, via the watcher-debounce refresh in `scheduleRefresh`.
# Attach `.catch` instead -- the failure belongs to the refresh, not the session.
if FLOATING="$(grep -nE 'void [a-zA-Z_$][a-zA-Z0-9_$]*\(' index.ts overlay.ts ot.ts)"; then
  echo "not ok - floating promise(s) found; attach .catch so a rejection cannot kill the pi process:" >&2
  echo "$FLOATING" | sed 's/^/  /' >&2
  exit 1
fi

# ── unit tests ───────────────────────────────────────────────────────

CODE=0

echo "# Running parser/scaffold unit tests..."
"$TSX" ./parser.test.ts || CODE=1

echo "# Running insert helper unit tests..."
"$TSX" ./insert.test.ts || CODE=1

echo "# Running SETUPFILE expansion unit tests..."
"$TSX" ./effective.test.ts || CODE=1

echo "# Running lifecycle unit tests..."
"$TSX" ./lifecycle.test.ts || CODE=1

echo "# Running path sandbox unit tests..."
"$TSX" ./paths.test.ts || CODE=1

echo "# Running global agent-directory path tests..."
"$TSX" ../lib/agent-paths.test.ts || CODE=1

echo "# Running doctor unit tests..."
"$TSX" ./doctor.test.ts || CODE=1

echo "# Running agent-memory scenario tests..."
"$TSX" ./memory.test.ts || CODE=1

echo "# Running closure-time summary unit tests..."
"$TSX" ./summary.test.ts || CODE=1

echo "# Running section reader unit tests..."
"$TSX" ./section.test.ts || CODE=1

echo "# Running scan-summaries unit tests..."
"$TSX" ./scan.test.ts || CODE=1

echo "# Running expanded-overlay removal unit tests..."
"$TSX" ./removal.test.ts || CODE=1

printf "# Running ot wrapper integration smoke tests...\n"
"$TSX" ./ot.test.ts || CODE=1

echo "# Running /tasks mode-boundary RPC integration tests..."
"$TSX" ./pi-integration.test.ts || CODE=1

exit "$CODE"

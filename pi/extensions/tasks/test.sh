#!/usr/bin/env bash
set -euo pipefail

# Test runner for the tasks extension.
#
# Sanity-checks the extension's structural shape and runs the
# parser/scaffold unit tests via tsx. Designed to mirror
# emacsclient/test.sh in style.

cd "$(dirname "$0")"

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

# ── unit tests ───────────────────────────────────────────────────────

CODE=0
echo "# Running parser/scaffold unit tests..."
# Use `tsx` directly when on PATH, otherwise fall back to `npx tsx`.
if command -v tsx >/dev/null 2>&1; then
  tsx ./parser.test.ts || CODE=1
else
  npx --yes tsx ./parser.test.ts || CODE=1
fi

echo "# Running insert helper unit tests..."
if command -v tsx >/dev/null 2>&1; then
  tsx ./insert.test.ts || CODE=1
else
  npx --yes tsx ./insert.test.ts || CODE=1
fi

echo "# Running lifecycle unit tests..."
if command -v tsx >/dev/null 2>&1; then
  tsx ./lifecycle.test.ts || CODE=1
else
  npx --yes tsx ./lifecycle.test.ts || CODE=1
fi

echo "# Running path sandbox unit tests..."
if command -v tsx >/dev/null 2>&1; then
  tsx ./paths.test.ts || CODE=1
else
  npx --yes tsx ./paths.test.ts || CODE=1
fi

echo "# Running doctor unit tests..."
if command -v tsx >/dev/null 2>&1; then
  tsx ./doctor.test.ts || CODE=1
else
  npx --yes tsx ./doctor.test.ts || CODE=1
fi

echo "# Running agent-memory scenario tests..."
if command -v tsx >/dev/null 2>&1; then
  tsx ./memory.test.ts || CODE=1
else
  npx --yes tsx ./memory.test.ts || CODE=1
fi
exit "$CODE"

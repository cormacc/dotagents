#!/usr/bin/env bash
set -euo pipefail

# Test runner for the jira extension.
#
# Sanity-checks the extension's structural shape. There are no unit
# tests yet — the extension is a thin wrapper around MCP-driven agent
# prompts and slash-command dispatch.

cd "$(dirname "$0")"

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

# Verify the file parses cleanly via esbuild (TypeScript syntax check).
if command -v npx >/dev/null 2>&1; then
  if ! npx --yes esbuild --bundle=false index.ts > /dev/null 2>&1; then
    echo "not ok - index.ts fails to parse via esbuild"
    exit 1
  fi
fi

CODE=0
echo "# Running unit tests..."
if command -v tsx >/dev/null 2>&1; then
  tsx ./jira.test.ts || CODE=1
else
  npx --yes tsx ./jira.test.ts || CODE=1
fi
exit "$CODE"

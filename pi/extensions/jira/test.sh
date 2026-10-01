#!/usr/bin/env bash
set -euo pipefail

# Test runner for the jira extension.
#
# Sanity-checks the extension's structural shape, then runs jira.test.ts
# (helpers, triggers, and the deferred jira_* tools against a fake
# ctx.executeTool). The tasks:status-changed listener is also covered by
# ../test/event-subscriptions.test.ts (run from ../emacsclient/test.sh).

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

run_tsx() {
  if command -v tsx >/dev/null 2>&1; then
    tsx "$@"
  elif [ -x "../../../node_modules/.bin/tsx" ]; then
    ../../../node_modules/.bin/tsx "$@"
  else
    npx --yes tsx "$@"
  fi
}

echo "# Running unit tests..."
run_tsx ./jira.test.ts

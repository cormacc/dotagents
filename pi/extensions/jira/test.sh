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

source ../test/support/locked-tools.sh

echo "# Running unit tests..."
run_tsx ./jira.test.ts

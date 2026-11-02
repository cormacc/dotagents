#!/usr/bin/env bash
set -euo pipefail

# Test runner for the jira extension.
#
# The Jira suite protects command and deferred-tool contracts. The exploration
# suite executes the documented recipes so examples cannot drift from tests.
# The tasks:status-changed listener is also covered by
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
run_tsx ./exploration.test.ts

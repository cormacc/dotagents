#!/usr/bin/env bash
set -euo pipefail

# Test runner for the jira extension.
#
# The Jira suite protects command and deferred-tool contracts. The exploration
# suite executes the documented recipes so examples cannot drift from tests.
# The tasks:status-changed listener is also covered by
# ../test/event-subscriptions.test.ts (run from ../test/test.sh).

cd "$(dirname "$0")"

source ../test/support/locked-tools.sh

echo "# Running unit tests..."
run_tsx ./jira.test.ts
run_tsx ./exploration.test.ts

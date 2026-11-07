#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

source ../test/support/locked-tools.sh

CODE=0
echo "# Running unit tests..."
run_tsx ./unit_test.test.ts || CODE=1
echo "# Running transport regression tests..."
run_tsx ./emacsclient-regression.test.ts || CODE=1
echo "# Running read tool unit tests..."
run_tsx ./read-tool.test.ts || CODE=1
echo "# Running write tool unit tests..."
run_tsx ./write-tool.test.ts || CODE=1
echo "# Running Emacs integration tests..."
run_tsx ./emacs-integration.test.ts || CODE=1
echo "# Running read tool integration tests..."
run_tsx ./read-tool-integration.test.ts || CODE=1
echo "# Running Pi integration tests..."
run_tsx ./pi-integration.test.ts || CODE=1
exit "$CODE"

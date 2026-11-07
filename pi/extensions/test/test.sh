#!/usr/bin/env bash
set -euo pipefail

# Runs the shared and cross-extension suites in this directory. Globbed rather
# than listed, so that a new test file here cannot be added without being run.
cd "$(dirname "$0")"
source ./support/locked-tools.sh

CODE=0
for t in ./*.test.ts; do
  echo "# Running $t..."
  "$TSX" "$t" || CODE=1
done
exit "$CODE"

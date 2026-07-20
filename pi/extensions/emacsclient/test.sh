#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

# Check that the extension file exists and has basic structure
if [ ! -f "index.ts" ]; then
  echo "not ok - index.ts not found"
  exit 1
fi

if ! grep -q "export default function" index.ts; then
  echo "not ok - Missing default export function"
  exit 1
fi

if ! grep -q "pi.registerTool" index.ts; then
  echo "not ok - Missing tool registration"
  exit 1
fi

# Verify all our tools are registered
for tool in emacs_eval emacs_ts_query emacs_read emacs_write; do
  if ! grep -q "name: \"$tool\"" index.ts; then
    echo "not ok - Missing tool: $tool"
    exit 1
  fi
done

run_tsx() {
  if command -v tsx >/dev/null 2>&1; then
    tsx "$@"
  elif [ -x "../../../node_modules/.bin/tsx" ]; then
    ../../../node_modules/.bin/tsx "$@"
  else
    npx --yes tsx "$@"
  fi
}

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
echo "# Running shared event subscription cleanup tests..."
run_tsx ../test/event-subscriptions.test.ts || CODE=1
exit "$CODE"

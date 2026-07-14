#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

if command -v tsx >/dev/null 2>&1; then
  tsx ./index.test.ts
elif [ -x "../../../node_modules/.bin/tsx" ]; then
  ../../../node_modules/.bin/tsx ./index.test.ts
else
  npx --yes tsx ./index.test.ts
fi

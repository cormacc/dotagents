#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

./node_modules/.bin/tsc --noEmit --module ESNext --moduleResolution Bundler --target ES2022 \
  --skipLibCheck --allowImportingTsExtensions --types node \
  index.ts nrepl-client.ts tools/*.ts types.d.ts

if command -v tsx >/dev/null 2>&1; then
  tsx ./tools/failure.test.ts
  tsx ./tools/pi-clojure.test.ts
elif [ -x "../../../node_modules/.bin/tsx" ]; then
  ../../../node_modules/.bin/tsx ./tools/failure.test.ts
  ../../../node_modules/.bin/tsx ./tools/pi-clojure.test.ts
else
  npx --yes tsx ./tools/failure.test.ts
  npx --yes tsx ./tools/pi-clojure.test.ts
fi

#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

# The type-check toolchain and pi's declarations come from the root development dependencies,
# because the Home Manager install of this directory omits development dependencies.
source ../test/support/locked-tools.sh

if [ ! -d node_modules/bencode ] || [ ! -d node_modules/parinfer ]; then
  echo "not ok - runtime dependencies missing; run npm ci in $(pwd)" >&2
  exit 1
fi

if [ ! -x "$TSC" ]; then fail_locked_deps "locked tsc not found at $TSC"; fi

"$TSC" --noEmit --module ESNext --moduleResolution Bundler --target ES2022 \
  --skipLibCheck --allowImportingTsExtensions --types node \
  index.ts nrepl-client.ts tools/*.ts types.d.ts

"$TSX" ./tools/port-files.test.ts
"$TSX" ./tools/failure.test.ts
"$TSX" ./tools/pi-clojure.test.ts

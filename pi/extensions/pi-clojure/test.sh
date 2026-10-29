#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

# The type-check toolchain and pi's declarations come from the root development dependencies,
# because the Home Manager install of this directory omits development dependencies.
root="$(cd ../../.. && pwd)"
TSC="$root/node_modules/.bin/tsc"
TSX="$root/node_modules/.bin/tsx"
for bin in "$TSC" "$TSX"; do
  if [ ! -x "$bin" ]; then
    echo "not ok - locked $(basename "$bin") not found at $bin; run npm ci from $root" >&2
    exit 1
  fi
done
if [ ! -d node_modules/bencode ] || [ ! -d node_modules/parinfer ]; then
  echo "not ok - runtime dependencies missing; run npm ci in $(pwd)" >&2
  exit 1
fi

"$TSC" --noEmit --module ESNext --moduleResolution Bundler --target ES2022 \
  --skipLibCheck --allowImportingTsExtensions --types node \
  index.ts nrepl-client.ts tools/*.ts types.d.ts

"$TSX" ./tools/failure.test.ts
"$TSX" ./tools/pi-clojure.test.ts

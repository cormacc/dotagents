#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

# The type-check toolchain and pi's declarations come from the root development dependencies.
source ../test/support/locked-tools.sh
if [ ! -x "$TSC" ]; then fail_locked_deps "locked tsc not found at $TSC"; fi

"$TSC" --noEmit --strict --module ESNext --moduleResolution Bundler --target ES2022 \
  --skipLibCheck --allowImportingTsExtensions --types node \
  index.ts discovery.ts

"$TSX" ./discovery.test.ts
"$TSX" ./pi-integration.test.ts

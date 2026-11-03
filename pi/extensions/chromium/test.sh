#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
source ../test/support/locked-tools.sh

"$TSC" --noEmit --strict --target es2022 --module nodenext --moduleResolution nodenext \
  --allowImportingTsExtensions --skipLibCheck index.ts targets.ts index.test.ts browser.test.ts pi-integration.test.ts
"$TSX" --test ./index.test.ts ./pi-integration.test.ts

if [[ -n "${CHROMIUM:-}" ]]; then
  "$TSX" --test ./browser.test.ts
else
  printf '%s\n' 'Live browser checks not run: set CHROMIUM to a Chrome/Chromium executable.'
fi

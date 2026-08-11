#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
root="$(cd ../../.. && pwd)"
TSX="$root/node_modules/.bin/tsx"

if [ ! -x "$TSX" ]; then
  echo "not ok - locked tsx not found at $TSX; run npm ci from $root" >&2
  exit 1
fi

"$TSX" ./index.test.ts
"$TSX" ./pi-integration.test.ts

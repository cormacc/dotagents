#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"

source ../test/support/locked-tools.sh
"$TSX" ./languages.test.ts
"$TSX" ./lsp.test.ts
"$TSX" ./dispatch.test.ts

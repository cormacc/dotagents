#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
source ../test/support/locked-tools.sh

"$TSX" ./index.test.ts
"$TSX" ./pi-integration.test.ts
